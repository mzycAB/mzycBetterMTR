# -*- coding: utf-8 -*-
"""离线守则校验：**mixin 包里只准放「带 @Mixin 的类」和「插件实现类」**。

## 为什么需要它（真实事故，本模组造成的）

2026-10-01 用户的 MTR3 会话（1.20.1 + `mtr 1.20-3.2.2-hotfix-1`）**连续两次**在
「世界数据加载完的第 1 个服务端 tick」崩掉，报告
`crash-2026-10-01_11.20.15-server.txt` / `错误报告-2026-10-1_11.20.19.zip`：

```
java.lang.RuntimeException: Mixin transformation of smooth.lift.mixin.mtr.Mtr3LiftAutoClose failed
Caused by: IllegalClassLoadError: smooth.lift.mixin.mtr.Mtr3LiftAutoClose is in a defined
  mixin package smooth.lift.mixin.mtr.* owned by smoothlift.mtr.mixins.json
  and cannot be referenced directly
    at knot//mtr.data.LiftServer.handler$zmh000$smooth_lift$smoothlift$autoCloseIdleDoor(LiftServer.java:574)
    at knot//mtr.data.LiftServer.tickServer(LiftServer.java:44)
```

机制：`Mtr3LiftDoorMixin` 的 `@Inject` 处理器会被 Mixin **并进目标类**
`mtr.data.LiftServer`。并进去之后，那段字节码里的
`invokestatic smooth/lift/mixin/mtr/Mtr3LiftAutoClose.xxx` 由**游戏类加载器**解析
—— 而 Mixin 对它自己声明的 mixin 包有守卫（「包内类不许被包外直接引用」），
`KnotClassDelegate.getPostMixinClassByteArray` 当场抛错 ⇒ 服务端 tick 崩 ⇒ 游戏没了。

★ 这条**只对「@Mixin 类」放行**：Mixin 有自己的一套类提供者，包内 mixin 之间互相引用
  是缓存过的、没问题；但「只是放在 mixin 包里、却不是 @Mixin 的普通类」没有这层缓存。
  **一句话：`smooth.lift.mixin.mtr` 这种包里只许放 @Mixin 类和插件类。**

## 这个脚本查什么

1. **【主查】源码侧**：枚举 `src/**/*.mixins.json`，取其 `package`；
   再扫该包目录下每个 `.java`（`src/main/java/<pkg>` 与 `src/client/java/<pkg>` 都看）——
   凡**没有 `@Mixin` 注解**、且**不是某个配置的 `plugin` 类**的，一律判违规。
   （剥注释后再找 `@Mixin`，避免文档里提到 `@Mixin` 三个字被误当注解。）
   ★ `plugin` 是**全局**判定：`PsdDoorMixinPlugin` 住在 `smooth.lift.mixin.mtr`，
     却是 **psd** 配置的插件 —— 插件由 Mixin 自己加载（不走 `applyMixins`），合法。
2. **【主查】产物侧**：读构建出的 jar 里每个 `*.mixins.json`，列出「包内、却没被任何配置列到」
   的 class ⇒ 违规（源码被移走但 jar 是旧的，或配置漏登）。
3. **【定位】引用侧**：对**上面判出的孤儿类**，列出 jar 里引用了它的包外 class
   —— 那就是运行时真正会炸的那条调用链（`LiftServer.handler$…` 这种）。
   ★ **只查孤儿**：`@Mixin` 接口/访问器**允许**被包外直接 cast（标准 accessor 用法，
     `EscalatorSpeedManager` 就是 `((ChunkMapAccessor) chunkMap).smoothlift_getChunks()`），
     拿它报错就是假红。
4. **【反向对照】**内置三个合成用例：非 @Mixin 的包内工具类**必须**被判违规、
   注释里的 `@Mixin` 三个字**不许**冒充注解、真 `@Mixin` **不许**被冤枉
   —— 否则回归网本身失效（照项目规矩：断言必须先证明它会变红）。

## 约定

- 只读文件与 jar，**不加载任何类**、不写任何东西。
- 找不到产物 jar 时**判失败**（`check-all.sh` 的前置就是先构建过；「跳过」比「失败」更危险）。
"""

import io
import json
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label,
                         ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def strip_comments(src):
    """剥掉 // 与 /* */ 注释（保留字符串字面量，@Mixin 判断用得着）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


# ---------------------------------------------------------------- 收集配置

def collect_configs():
    """返回 [{'rel': 相对路径, 'pkg': 点分包名, 'entries': set(点分类名), 'plugin': 类名}]。"""
    out = []
    for root_dir, _dirs, files in os.walk(os.path.join(PROJ, "src")):
        for f in files:
            if not f.endswith(".mixins.json"):
                continue
            path = os.path.join(root_dir, f)
            try:
                d = json.load(io.open(path, encoding="utf-8"))
            except Exception as e:  # noqa: BLE001
                check(False, "%s：解析失败（%s）" % (f, e))
                continue
            entries = set()
            for key in ("mixins", "client", "server"):
                for name in d.get(key, []) or []:
                    entries.add(name.replace("/", "."))
            plugin = (d.get("plugin") or "").strip()
            out.append({
                "rel": os.path.relpath(path, PROJ).replace("\\", "/"),
                "pkg": (d.get("package") or "").strip(),
                "entries": entries,
                "plugin": plugin,
            })
    return out


# ------------------------------------------------- 主查 A：源码包内只许 @Mixin

def scan_source(cfgs):
    print("===== 1) 源码侧：mixin 包目录内只许有 @Mixin 类 / 插件类 =====")
    if not cfgs:
        check(False, "找不到任何 *.mixins.json  ← 配置都没了，守则无从谈起")
        return
    # ★ 插件按**全局**收集：插件可以住在别的配置的包目录里（见 docstring 第 1 条）
    plugins = {c["plugin"] for c in cfgs if c["plugin"]}
    print("-- 全部配置的 plugin 类 = %s" % ("、".join(sorted(plugins)) or "（无）"))
    for cfg in cfgs:
        pkg = cfg["pkg"]
        roots = [os.path.join(PROJ, "src", kind, "java", *pkg.split("."))
                 for kind in ("main", "client")]
        roots = [r for r in roots if os.path.isdir(r)]
        print("-- %s   包 = %s" % (cfg["rel"], pkg))
        if not roots:
            check(False, "%s：声明的包目录不存在（%s）" % (cfg["rel"], pkg))
            continue
        bad = []
        total = 0
        for r in roots:
            for f in sorted(os.listdir(r)):
                if not f.endswith(".java"):
                    continue
                total += 1
                p = os.path.join(r, f)
                body = strip_comments(io.open(p, encoding="utf-8", errors="replace").read())
                simple = f[:-5]
                is_mixin = bool(re.search(r"@Mixin\b", body))
                is_plugin = (pkg + "." + simple) in plugins
                if not is_mixin and not is_plugin:
                    bad.append(os.path.relpath(p, PROJ).replace("\\", "/"))
        check(total > 0, "%s：包内有 %d 个类文件" % (cfg["rel"], total))
        check(not bad,
              "%s：包内 %d 个类全部是 @Mixin 或插件类" % (cfg["rel"], total),
              ("★ 违规（非 @Mixin、也非插件）：" + "、".join(bad)
               + "  ← 放进 mixin 包会被目标类的类加载器直接引用 ⇒ IllegalClassLoadError ⇒ 崩") if bad else
              "无违规")
    print()


# ------------------------------------------------------- 主查 B：产物侧核对

def find_jar():
    libs = os.path.join(PROJ, "build", "libs")
    if not os.path.isdir(libs):
        return None
    cands = [os.path.join(libs, f) for f in os.listdir(libs)
             if f.endswith(".jar") and "-sources" not in f and "-dev" not in f]
    if not cands:
        return None
    cands.sort(key=os.path.getmtime, reverse=True)
    return cands[0]


def scan_jar(cfgs):
    print("===== 2) 产物侧：jar 里声明的包内类，必须都被某个配置列到 =====")
    jar = find_jar()
    if not jar:
        check(False, "找到构建产物 jar  ← check-all.sh 的前置就是「先构建过」")
        return
    print("-- jar = %s" % os.path.relpath(jar, PROJ).replace("\\", "/"))
    z = zipfile.ZipFile(jar)
    names = z.namelist()

    # jar 里所有 *.mixins.json
    jar_cfgs = []
    for n in names:
        if n.endswith(".mixins.json"):
            try:
                d = json.loads(z.read(n).decode("utf-8"))
            except Exception as e:  # noqa: BLE001
                check(False, "%s：jar 内解析失败（%s）" % (n, e))
                continue
            entries = set()
            for key in ("mixins", "client", "server"):
                for name in d.get(key, []) or []:
                    entries.add((d.get("package", "").strip() + "." + name).replace("/", "."))
            jar_cfgs.append({"file": n, "pkg": d.get("package", "").strip(),
                             "entries": entries,
                             "plugin": (d.get("plugin") or "").strip()})

    declared_pkgs = {c["pkg"] for c in jar_cfgs if c["pkg"]}
    check(bool(declared_pkgs), "jar 里读到了 %d 份 mixins.json，声明 %d 个包"
          % (len(jar_cfgs), len(declared_pkgs)))

    all_entries = set()
    all_plugins = set()
    for c in jar_cfgs:
        all_entries |= c["entries"]
        if c["plugin"]:
            all_plugins.add(c["plugin"])

    # 包内 class，谁没被登记
    orphans = []
    for n in names:
        if not n.endswith(".class"):
            continue
        fq = n[:-6].replace("/", ".")
        pkg = fq.rsplit(".", 1)[0] if "." in fq else ""
        if pkg not in declared_pkgs:
            continue
        # 内部类（A$B）跟着宿主；宿主登记了就算登记
        host = fq.split("$")[0]
        if host in all_entries or host in all_plugins:
            continue
        orphans.append(n)
    check(not orphans,
          "jar 的 mixin 包内没有「未登记」的类",
          ("★ 未登记：" + "、".join(sorted(orphans)[:8])) if orphans else
          "包内每类要么被 mixins.json 列着、要么是 plugin")

    # 定位：谁引用了那些「孤儿」类（= 运行时真正会炸的那条链）
    print()
    print("===== 3) 定位：谁引用了「包内未登记的孤儿类」 =====")
    print("-- 孤儿类 = %s" % ("、".join(sorted(orphans)) or "（无）"))
    inner = [n[:-6] for n in orphans]
    hits = []
    if inner:
        pats = [i.encode("utf-8") for i in inner]
        for n in names:
            if not n.endswith(".class"):
                continue
            pkg = n[:-6].rsplit("/", 1)[0].replace("/", ".")
            if pkg in declared_pkgs:
                continue
            b = z.read(n)
            for p, i in zip(pats, inner):
                if p in b:
                    hits.append("%s → %s" % (n, i))
                    break
    if hits:
        detail = ("★ 命中 %d 处（这几条就是运行时炸点）：\n      %s"
                  % (len(hits), "\n      ".join(hits[:10])))
    elif inner:
        detail = ("★ 有 %d 个孤儿类，但眼下没有包外引用它 —— 仍必须删掉："
                  "哪天被人一引就是 IllegalClassLoadError（本轮崩溃就是这么来的）" % len(inner))
    else:
        detail = "命中 0 处（没有孤儿类 ⇒ 没有可炸的引用）"
    check(not hits and not inner,
          "包内没有「未登记的孤儿类」，也没有谁引用它",
          detail)
    print()


# ------------------------------------------------------------- 反向对照

def reverse_control():
    print("===== 4) 反向对照：主查逻辑必须能判出「包内有非 @Mixin 类」 =====")
    # 合成用例：一个包内普通类（无 @Mixin）
    fake_pkg = "a.b.mixin"
    fake_files = {
        "RealMixin.java": "package a.b.mixin;\n@Mixin(targets=\"x.Y\")\nclass RealMixin {}\n",
        "Tool.java": "package a.b.mixin;\npublic final class Tool { public static void f() {} }\n",
    }
    bad = []
    for f, body in fake_files.items():
        if not re.search(r"@Mixin\b", strip_comments(body)):
            bad.append(f)
    got = (bad == ["Tool.java"])
    check(got, "合成用例里「非 @Mixin 的 Tool.java」被如实判出",
          "判出：%s" % (bad or "（无 ⇒ 主查失效！）"))

    # 再验一条：注释里写 @Mixin 不算数
    body = "package a.b.mixin;\n// 这个类长得像 @Mixin 但其实是工具类\npublic final class T2 {}\n"
    got2 = not re.search(r"@Mixin\b", strip_comments(body))
    check(got2, "注释里的「@Mixin」三个字不冒充注解",
          "剥注释后判为非 mixin" if got2 else "★ 被注释骗了 ⇒ 剥注释逻辑失效")

    # 再验一条：真 @Mixin 不许被冤枉
    body3 = "package a.b.mixin;\n@Mixin(targets = \"x.Y\")\npublic abstract class M {}\n"
    got3 = bool(re.search(r"@Mixin\b", strip_comments(body3)))
    check(got3, "真 @Mixin 不会被冤枉成工具类",
          "识别为 mixin" if got3 else "★ 误伤真 mixin")
    print()


# ------------------------------------------------------------------ main

def main():
    print("工程 = %s" % PROJ)
    print()
    cfgs = collect_configs()
    scan_source(cfgs)
    scan_jar(cfgs)
    reverse_control()

    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("   - " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()
