# -*- coding: utf-8 -*-
"""离线守则校验：**Mixin 配置插件里绝对不能加载类**。

## 为什么需要它（真实事故，本模组造成的）

2026-09-19 用户的崩溃报告 `错误报告-2026-9-19_10.20.59.zip`：

```
[10:20:00] [INFO] [SmoothLift/Mtr3Fix] 未启用直梯自动关门修复（mtr.data.LiftServer=false、org.mtr.core.data.Lift=true；…）
[10:20:01] [ERROR] Mixin prepare for mod jsblock failed preparing modded.mtrpatch.LiftMixin
                   in jsblock.mixins.json: MixinTargetAlreadyLoadedException
                   Critical problem: … target org.mtr.core.data.Lift was loaded too early.
```

根因：`Mtr3LiftMixinPlugin.onLoad` 里用 `Class.forName(name, false, cl)` 探测
「这个类在不在」。它在 **Mixin 准备阶段**执行，而 `Class.forName` 即使
`initialize=false`，也**已经把类 load + link 进类加载器了**。整合包里 `jsblock`
的 `modded.mtrpatch.LiftMixin` 正好以 `org.mtr.core.data.Lift` 为目标，
于是轮到它准备时 Mixin 直接拒绝 → Axiom 的 `preLaunch` 入口点连带失败 → **启动即崩**。

**正确做法**：探测「类在不在」只读**类路径资源**
（`loader.getResource("a/b/C.class") != null`），不 define 任何类。

## 这个脚本查什么

1. 枚举 `src/**/*MixinPlugin*.java`（= `IMixinConfigPlugin` 的实现类）；
2. **剥掉注释与字符串字面量**（否则我自己写的那段「禁止 Class.forName」的文档
   会被误判成违规）后，断言正文里没有 `Class.forName(` / `.loadClass(` 调用；
3. 若文件里有 `classPresent` 这类探测方法，断言它用的是 `getResource(`。
4. ★★ 第 2 段（第二条铁律）：读所有 `*.mixins.json` 的 `package`，扫
   `src/main/java` **与** `src/client/java` 下落在那个包里的每个类，断言
   「是配置里点名过的 mixin / 是配置 `plugin` 点名的插件类 / 带 `@Mixin` /
   实现 `IMixinConfigPlugin`」——否则就是「工具类混进 mixin 包」，
   会在 handler 被引用时抛 `IllegalClassLoadError`（2026-10-01 崩过）。
   本段自带三组沙盒对照（干净布局 / 工具类放 main 侧 / 工具类放 client 侧）。

★ 注意范围：`MtrLiftAccess`（客户端 tick 调用）与 `Mtr3LiftAutoClose`（服务端 tick 调用）
里的 `Class.forName` 是**允许**的 —— 那时所有 mixin 配置早已准备完毕，加载类不会
触发 `MixinTargetAlreadyLoadedException`。**危险的是「准备阶段早于别人」，不是「加载类」本身。**
所以本脚本只盯 `*MixinPlugin*`。

用法：`python _tools/check-mixin-plugin-safety.py`（退出码 0 = 全部通过）
"""
import json
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def strip_comments_and_strings(src):
    """去掉 // 行注释、/* */ 块注释、以及 "..." / '...' 字面量，保留其它字符。

    这样 `Class.forName` 出现在文档/注释/字符串里就不算「调用」。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            i += 2
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if c in "\"'":
            quote = c
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == quote:
                    i += 1
                    break
                i += 1
            out.append(" ")          # 用空格占位，保持长度无关紧要
            continue
        out.append(c)
        i += 1
    return "".join(out)


# ----------------------------------------------------------------------
# 0) 对照实验：证明「剥注释」这一步真的有鉴别力（否则整脚本可能恒真）
# ----------------------------------------------------------------------
CTRL_BAD = 'class P implements IMixinConfigPlugin {\n' \
           '  static boolean has(String n) { try { Class.forName(n, false, null); return true; }\n' \
           '    catch (Throwable t) { return false; } }\n}'
CTRL_OK = 'class P implements IMixinConfigPlugin {\n' \
          '  // 铁律：这里不能用 Class.forName 探测类\n' \
          '  /** 见注释里的 Class.forName 反面教材 */\n' \
          '  static boolean has(String n) { return P.class.getClassLoader()\n' \
          "      .getResource(n.replace('.', '/') + \".class\") != null; }\n}"
CALL_RE = re.compile(r"Class\.forName\s*\(|\.loadClass\s*\(")

check(bool(CALL_RE.search(strip_comments_and_strings(CTRL_BAD))),
      "对照 A：正文里的 Class.forName 会被判定为违规", "对照样本命中")
check(not CALL_RE.search(strip_comments_and_strings(CTRL_OK)),
      "对照 B：只在注释里出现 Class.forName 不会误判", "对照样本未命中")
print()


# ----------------------------------------------------------------------
# 1) 枚举 Mixin 配置插件
# ----------------------------------------------------------------------
plugins = []
for dirpath, _dirnames, filenames in os.walk(SRC):
    for fn in filenames:
        if fn.endswith(".java") and "MixinPlugin" in fn:
            plugins.append(os.path.join(dirpath, fn))

plugins.sort()
check(bool(plugins), "找到 Mixin 配置插件（IMixinConfigPlugin 实现）",
      "、".join(os.path.relpath(p, ROOT) for p in plugins))
print()

for path in plugins:
    rel = os.path.relpath(path, ROOT)
    with open(path, encoding="utf-8") as fh:
        raw = fh.read()
    body = strip_comments_and_strings(raw)

    hits = CALL_RE.findall(body)
    check(not hits,
          "%s：正文里没有任何 Class.forName / loadClass 调用" % rel,
          ("命中 %d 处 ← 这会在 Mixin 准备阶段把类定义掉，搞崩别的模组的 mixin" % len(hits))
          if hits else "命中 0 处，安全")

    # 若存在「探测类在不在」的方法，必须走资源
    if re.search(r"classPresent|isClassPresent|classExists", body):
        uses_resource = "getResource(" in body
        check(uses_resource,
              "%s：类存在性探测用的是 getResource（不加载类）" % rel,
              "正文中出现 getResource(，未加载任何类" if uses_resource
              else "未见到 getResource( ← 探测方式不对，会加载类")
    print()

# ----------------------------------------------------------------------
# 2) ★★ 第二条 mixin 铁律：mixin 包里**只准放 @Mixin 类**（配置插件类除外）
# ----------------------------------------------------------------------
# 事故（2026-10-01 用户的崩溃报告，本模组造成的）：
#
#   java.lang.RuntimeException: Mixin transformation of
#       smooth.lift.mixin.mtr.Mtr3LiftAutoClose failed
#   Caused by: org.spongepowered.asm.mixin.transformer.throwables.IllegalClassLoadError:
#       smooth.lift.mixin.mtr.Mtr3LiftAutoClose is in a defined mixin package
#       smooth.lift.mixin.mtr.* owned by smoothlift.mtr.mixins.json
#       and cannot be referenced directly
#
# 根因：`Mtr3LiftAutoClose` 是**没有 @Mixin 注解的普通工具类**，却被放在
# `smooth.lift.mixin.mtr`（= `smoothlift.mtr.mixins.json` 声明的 `package`）下。
# Mixin 的类加载守卫（`MixinProcessor.applyMixins`）对「名字落在某个 mixin 包里的类」
# 一律拒绝由游戏类加载器**直接解析**，因为那个包是 Mixin 的**虚拟命名空间**。
# 而 `Mtr3LiftDoorMixin` 注入进 `mtr.data.LiftServer.tickServer` 的 handler 里
# 有 `invokestatic smooth/lift/mixin/mtr/Mtr3LiftAutoClose.available()` ⇒ 解析该类
# ⇒ 抛错 ⇒ **服务端 tick 线程死**（接着整个服务端 tick 循环崩）。
# ⇒ 工具类一律放普通包（本项目惯例 `smooth.lift` / `smooth.lift.client`）。
#
# ★ 允许留在 mixin 包里的只有两类：
#   1. 配置文件 `mixins`/`client`/`server` 数组里**列名**的那些类（= 真 mixin）；
#   2. 配置文件 `plugin` 字段点名的那一个类（Mixin 用自己的类加载器实例化它）。
#   —— 除此之外一律必须带 `@Mixin` 注解或实现 `IMixinConfigPlugin`。
#
# ★★ 路径坑（踩过）：Java 源码根是 `src/main/java` 与 `src/client/java`，**不是** `src/`。
#    只写 `join(SRC, pkg)` 会指向不存在的 `src/smooth/...` ⇒ 循环 continue 跳过 ⇒
#    **检查恒真**（假通过）。所以下面两个根都扫，并用「工具类放回 mixin 包」的沙盒
#    对照证明鉴别力（对照组 C 就是专门守这条路径坑的）。
JAVA_ROOTS = ["main/java", "client/java"]


def read_mixin_configs(root):
    """收集 `src/**/*.mixins.json` 里的 package / plugin / 声明类名。"""
    cfgs = []
    src = os.path.join(root, "src")
    for dirpath, _dirnames, filenames in os.walk(src):
        for fn in filenames:
            if not fn.endswith(".mixins.json"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                with open(path, encoding="utf-8") as fh:
                    data = json.load(fh)
            except Exception as exc:                       # 坏 JSON 单独报，别静默跳过
                cfgs.append({"file": path, "error": str(exc)})
                continue
            if not isinstance(data, dict) or not data.get("package"):
                continue
            declared = set()
            for key in ("mixins", "client", "server"):
                declared |= set(data.get(key) or [])
            cfgs.append({
                "file": path,
                "package": data["package"],
                "plugin": data.get("plugin"),
                "declared": declared,
            })
    return cfgs


def owner_of(pkg, cfgs):
    """这个包归哪个配置文件管 —— 取**最长前缀**匹配（与 Mixin 的归属判定同款）。"""
    best = None
    for cfg in cfgs:
        cp = cfg.get("package")
        if not cp:
            continue
        if pkg == cp or pkg.startswith(cp + "."):
            if best is None or len(cp) > len(best["package"]):
                best = cfg
    return best


def scan_mixin_packages(root):
    """扫 root 下所有落在 mixin 包里的 Java 文件；返回 [(相对路径, 说明)]，空 = 通过。"""
    cfgs = read_mixin_configs(root)
    bad = []
    for java_root_rel in JAVA_ROOTS:
        java_root = os.path.join(root, "src", *java_root_rel.split("/"))
        if not os.path.isdir(java_root):
            continue
        for dirpath, _dirnames, filenames in os.walk(java_root):
            for fn in filenames:
                if not fn.endswith(".java"):
                    continue
                path = os.path.join(dirpath, fn)
                pkg = os.path.relpath(dirpath, java_root).replace(os.sep, ".")
                if pkg == ".":
                    continue
                cfg = owner_of(pkg, cfgs)
                if cfg is None:
                    continue
                simple = fn[:-len(".java")]
                if simple in cfg["declared"]:              # ① 真 mixin（配置里点了名）
                    continue
                if cfg.get("plugin") == pkg + "." + simple:  # ② 配置插件类
                    continue
                with open(path, encoding="utf-8") as fh:
                    body = strip_comments_and_strings(fh.read())
                if "@Mixin" in body or "IMixinConfigPlugin" in body:
                    continue
                bad.append((
                    os.path.relpath(path, root),
                    "在 mixin 包 %s（配置 %s）里，但既不是 @Mixin 类、也不是配置插件"
                    " ⇒ 一旦被 handler 引用就会抛 IllegalClassLoadError 崩服务端 tick"
                    % (pkg, os.path.relpath(cfg["file"], root)),
                ))
    return bad


FAKE_JSON = {
    "package": "a.b.mixin",
    "plugin": "a.b.mixin.FakePlugin",
    "mixins": ["GoodMixin"],
}
FAKE_CLIENT_JSON = {
    "package": "a.b.cmixin",
    "mixins": ["CGood"],
}
FAKE_GOOD = "package a.b.mixin;\nimport org.spongepowered.asm.mixin.Mixin;\n" \
            "@Mixin(Object.class)\npublic abstract class GoodMixin {}\n"
FAKE_PLUGIN = "package a.b.mixin;\n" \
              "public class FakePlugin implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin {}\n"
FAKE_TOOL = "package a.b.mixin;\n/** 普通工具类（不许放在 mixin 包）. */\npublic final class ToolClass {}\n"
FAKE_CGOOD = "package a.b.cmixin;\nimport org.spongepowered.asm.mixin.Mixin;\n" \
             "@Mixin(Object.class)\npublic abstract class CGood {}\n"
FAKE_CTOOL = "package a.b.cmixin;\npublic final class ToolC {}\n"


def _sandbox_write(tmp, rel, text):
    path = os.path.join(tmp, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def sandbox_controls():
    """★ 沙盒对照：证明本段检查**真的会红**（否则「通过」可能只是没跑到）。"""
    tmp = tempfile.mkdtemp(prefix="mixinpkg-")
    try:
        _sandbox_write(tmp, "src/main/resources/fake.mixins.json", json.dumps(FAKE_JSON))
        _sandbox_write(tmp, "src/client/resources/fake.client.mixins.json", json.dumps(FAKE_CLIENT_JSON))
        _sandbox_write(tmp, "src/main/java/a/b/mixin/GoodMixin.java", FAKE_GOOD)
        _sandbox_write(tmp, "src/main/java/a/b/mixin/FakePlugin.java", FAKE_PLUGIN)
        _sandbox_write(tmp, "src/client/java/a/b/cmixin/CGood.java", FAKE_CGOOD)

        # 对照 A：干净布局 ⇒ 一条都不许报（否则会天天假红）
        check(scan_mixin_packages(tmp) == [],
              "对照 A：mixin 包里只有 @Mixin 类 + 插件 ⇒ 通过", "沙盒样本未命中")

        # 对照 B：工具类放进 src/main/java 的 mixin 包 ⇒ 必须精确报出它
        _sandbox_write(tmp, "src/main/java/a/b/mixin/ToolClass.java", FAKE_TOOL)
        bad_b = scan_mixin_packages(tmp)
        check(len(bad_b) == 1 and bad_b[0][0].endswith(os.path.join("a", "b", "mixin", "ToolClass.java")),
              "对照 B：工具类放进 mixin 包 ⇒ 精确报出这一个类",
              "命中：%s" % [b[0] for b in bad_b])
        os.remove(os.path.join(tmp, "src", "main", "java", "a", "b", "mixin", "ToolClass.java"))

        # 对照 C：★ 只写在 src/client/java 下的 mixin 包 —— 专守「只扫 src/ 会恒真」那条路径坑
        _sandbox_write(tmp, "src/client/java/a/b/cmixin/ToolC.java", FAKE_CTOOL)
        bad_c = scan_mixin_packages(tmp)
        check(len(bad_c) == 1 and bad_c[0][0].endswith(os.path.join("a", "b", "cmixin", "ToolC.java")),
              "对照 C：src/client/java 下的 mixin 包也要扫到（防「路径写错 ⇒ 检查恒真」）",
              "命中：%s" % [b[0] for b in bad_c])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


sandbox_controls()
print()

bad_real = scan_mixin_packages(ROOT)
check(not bad_real,
      "mixin 包内只有 @Mixin 类与配置插件（工具类必须待在普通包）",
      ("命中 %d 个：" % len(bad_real)) + "；".join("%s %s" % b for b in bad_real)
      if bad_real else "mixin 包干净")
print()

if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
