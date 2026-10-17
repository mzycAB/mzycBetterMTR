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

★ 注意范围：`MtrLiftAccess`（客户端 tick 调用）与 `Mtr3LiftAutoClose`（服务端 tick 调用）
里的 `Class.forName` 是**允许**的 —— 那时所有 mixin 配置早已准备完毕，加载类不会
触发 `MixinTargetAlreadyLoadedException`。**危险的是「准备阶段早于别人」，不是「加载类」本身。**
所以本脚本只盯 `*MixinPlugin*`。

用法：`python _tools/check-mixin-plugin-safety.py`（退出码 0 = 全部通过）
"""
import os
import re
import sys

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

if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
