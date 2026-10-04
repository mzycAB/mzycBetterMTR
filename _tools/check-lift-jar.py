# -*- coding: utf-8 -*-
"""离线校验：**打包产物（jar）里直梯那一套的结构**，而不是源码。

## 为什么要有这一层（源码级校验查不到的东西）

`check-lift-*.py` 那几个脚本查的是**源码文本**，它们对下面这几件事是盲的：

1. **类根本没被编进去** —— 例如 `src/client/java` 的类在 Fabric 下要经过
   `compileClientJava`，若 sourceset 配错，源码在、class 不在；
2. **资源没进 jar** —— ogg / 覆盖模型 / mixin 配置只在 `src/**/resources` 里躺着，
   processResources 没带上就白写；
3. **mixin 包守卫**（真实事故，会**启动期崩**）：Mixin 的 `package` 是一个
   **虚拟命名空间**，`MixinProcessor.applyMixins` 会拒绝该包内**任何不在
   mixins.json 列表里**的类 —— 报 `IllegalClassLoadError`。
   所以「一个普通工具类被顺手放进 smooth.lift.mixin.mtr」= 想崩就崩。
   ★ 这条在源码层看不出来（文件在不在源码树里都「合理」），只能在 jar 上对撞
   「该包内实际有哪些 class」vs「mixins.json 注册了哪些」。
4. **配置有没有真的挂上去** —— `fabric.mod.json` 的 `mixins` 数组漏一项，
   整套 mixin 就是静默不生效（不报错，只是没效果）。

## 用法

    python _tools/check-lift-jar.py          # 自动取 build/libs 下最新那只 jar
    python _tools/check-lift-jar.py <jar>    # 或显式指定

退出码 0 = 全部通过。
"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_LIBS = os.path.join(ROOT, "build", "libs")

if len(sys.argv) > 1:
    JAR = sys.argv[1]
else:
    # ★ 2026-10-01 订正：原来写死 `n.startswith("smooth-escalator-")` —— 那是**旧的打包名**，
    #   本仓的 jar 早就叫 `mzycBetterMTR-*.jar`（archives_base_name 已应用）⇒ 一个都匹配不上
    #   ⇒ JAR=None ⇒ 走下面的 [SKIP] 静默退出 0 ⇒ **这套包守卫从来没真正跑过**，
    #   于是 `Mtr3LiftAutoClose` 住进 mixin 包这件事一路瞒到了用户实机崩溃。
    #   现在改成「build/libs 下最新的非 sources/dev 的 jar」，与其它脚本同款。
    cands = [n for n in (os.listdir(_LIBS) if os.path.isdir(_LIBS) else [])
             if n.endswith(".jar") and "sources" not in n and "-dev" not in n]
    cands.sort(key=lambda n: os.path.getmtime(os.path.join(_LIBS, n)), reverse=True)
    JAR = os.path.join(_LIBS, cands[0]) if cands else None

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


if not JAR or not os.path.isfile(JAR):
    # ★ 2026-10-01 订正：原来是 `print("[SKIP]..."); sys.exit(0)` —— **静默通过比失败更危险**
    #   （项目铁律）。check-all.sh 的前置就是「先构建过」，找不到 jar 就是环境不对，
    #   必须响亮失败，否则这套守卫又会变成装样子的摆设。
    print("[FAIL] 找不到 jar（先跑 gradlew build）：%s" % JAR)
    sys.exit(1)

print("校验 %s（%d B）" % (os.path.relpath(JAR, ROOT), os.path.getsize(JAR)))
z = zipfile.ZipFile(JAR)
names = set(z.namelist())

# ----------------------------------------------------------------------
# 1) 类都在
# ----------------------------------------------------------------------
print("\n== 1. 类 ==")
for cls in ["smooth/lift/Mtr3LiftAutoClose.class",
            "smooth/lift/mixin/mtr/Mtr3LiftDoorMixin.class",
            "smooth/lift/mixin/mtr/Mtr3LiftExternalCallMixin.class",
            "smooth/lift/mixin/mtr/Mtr3LiftPanelCallMixin.class",
            "smooth/lift/mixin/mtr/Mtr3LiftMixinPlugin.class",
            "smooth/lift/mixin/mtr/Mtr4LiftTrackFloorShapeMixin.class",
            "smooth/lift/client/LiftChimePlayer.class",
            "smooth/lift/client/LiftToneSetupScreen.class",
            "smooth/lift/client/MtrLiftAccess.class"]:
    check(cls in names, "jar 内含 %s" % cls)

# ----------------------------------------------------------------------
# 2) mixin 包守卫：包内每个类都必须注册（Mtr3LiftAutoClose 尤其不能进去）
# ----------------------------------------------------------------------
print("\n== 2. mixin 包守卫 ==")
check("smooth/lift/Mtr3LiftAutoClose.class" in names
      and not any(n.startswith("smooth/lift/mixin/mtr/") and "AutoClose" in n for n in names),
      "Mtr3LiftAutoClose 在 smooth/lift、不在 smooth/lift/mixin/mtr（进了 mixin 包会启动期崩）")

mj = json.loads(z.read("smoothlift.mtr.mixins.json"))
PKG = "smooth/lift/mixin/mtr/"
in_pkg = sorted({n[len(PKG):-len(".class")].replace("/", ".") for n in names
                 if n.startswith(PKG) and n.endswith(".class")
                 and "$" not in n[len(PKG):]})
registered = set(mj.get("mixins") or [])
# 插件类（plugin 指向的）允许在该包里，Mixin 会单独放行它。
# ★ 不许写死成 {"Mtr3LiftMixinPlugin"}：`smoothlift.psd.mixins.json` 的插件
#   `smooth.lift.mixin.mtr.PsdDoorMixinPlugin` 也物理躺在这个包里，写死会**假红**
#   （2026-10-01 实测：包内 4 mixin + 2 插件，写死的名单只认 1 个）。
#   正确做法：把所有 `*.mixins.json` 的 `plugin` 字段收集起来，只要 FQN 落在这个包就放行。
allowed_unregistered = set()
for _n in names:
    if not _n.endswith(".mixins.json"):
        continue
    try:
        _cfg = json.loads(z.read(_n))
    except Exception:
        continue
    _plugin = _cfg.get("plugin")
    if isinstance(_plugin, str) and _plugin.startswith(PKG.replace("/", ".")):
        allowed_unregistered.add(_plugin.rsplit(".", 1)[-1])
extra = [c for c in in_pkg if c not in registered and c not in allowed_unregistered]
check(not extra,
      "mixin 包内的每个类都在 mixins.json 里注册、或是某份配置的 plugin（未注册的普通类会让 Mixin 报 IllegalClassLoadError）",
      "包内 %s / 未注册 %s / 放行的 plugin %s" % (in_pkg, extra, sorted(allowed_unregistered)))
check(mj.get("package") == "smooth.lift.mixin.mtr",
      "mixins.json 的 package 正确", str(mj.get("package")))
check(mj.get("plugin") == "smooth.lift.mixin.mtr.Mtr3LiftMixinPlugin",
      "mixins.json 的 plugin 指向 Mtr3LiftMixinPlugin", str(mj.get("plugin")))

# ----------------------------------------------------------------------
# 3) 资源
# ----------------------------------------------------------------------
print("\n== 3. 资源 ==")
for res in ["smoothlift.mtr.mixins.json",
            "assets/smoothlift/sounds/audio/up.ogg",
            "assets/smoothlift/sounds/audio/down.ogg",
            "assets/smoothlift/sounds/audio/liftmusic.ogg",
            "assets/mtr/models/block/lift_track_floor_1.json"]:
    check(res in names, "jar 内含 %s" % res)

snd = json.loads(z.read("assets/smoothlift/sounds.json"))
for k in ["audio/up", "audio/down", "audio/liftmusic"]:
    check(k in snd, "sounds.json 注册 %s" % k)

# 覆盖模型必须真的是 MTR3 那根窄柱（6..10），不是 MTR4 的全宽薄板（0..16）
try:
    ov = json.loads(z.read("assets/mtr/models/block/lift_track_floor_1.json"))
    els = ov.get("elements") or []
    _x = sorted({e["from"][0] for e in els} | {e["to"][0] for e in els})
    check(_x == [6.0, 10.0],
          "覆盖模型仍是 MTR3 窄柱（X 边界 6..10，不是 MTR4 原版的 0..16）", "实际 X=%s" % _x)
except Exception as e:
    check(False, "覆盖模型可解析且是 MTR3 几何", repr(e))

# ----------------------------------------------------------------------
# 4) 元数据：mixin 配置真的挂上去了 / MC 依赖版本
# ----------------------------------------------------------------------
print("\n== 4. 元数据 ==")
fm = json.loads(z.read("fabric.mod.json"))
check("smoothlift.mtr.mixins.json" in (fm.get("mixins") or []),
      "fabric.mod.json 的 mixins 列表含 smoothlift.mtr.mixins.json（漏了 = 整套 mixin 静默不生效）",
      str(fm.get("mixins")))

# 期望的 MC 版本从 gradle.properties 读，别写死 —— 本脚本要在 1.20.1 / 1.20.4 两个工程通用。
EXPECT_MC = None
_gp = os.path.join(ROOT, "gradle.properties")
if os.path.isfile(_gp):
    for line in open(_gp, encoding="utf-8"):
        if line.strip().startswith("minecraft_version="):
            EXPECT_MC = "~" + line.split("=", 1)[1].strip()
if EXPECT_MC:
    check(fm.get("depends", {}).get("minecraft") == EXPECT_MC,
          "fabric.mod.json 依赖 minecraft %s（与 gradle.properties 一致）" % EXPECT_MC,
          str(fm.get("depends", {}).get("minecraft")))
else:
    print("[SKIP] 读不到 gradle.properties 的 minecraft_version，跳过 MC 版本一致性检查")

# ----------------------------------------------------------------------
# 5) 字节码里的关键符号
#
# ★ 两类符号要分开查，别混：
#   * **我们的 / MTR 的**（反射字符串）—— 是编译期字面量，remapJar 不动它，
#     所以**在最终 jar 里能查到**（例如 "forEachTrackPosition"、"hasFloor"）。
#   * **原版方法**（如 Level.getBlockEntity）—— remapJar 会把它改成 intermediary
#     （Forge 那边是 reobf 成 m_xxxxx_），**最终 jar 里查不到**。
#     查它必须用**未重映射**的 build/classes/java/main（构建中间产物）。
#     （这条是踩过的坑：拿最终 jar 查原版方法名会永远 FAIL，与代码对错无关。）
# ----------------------------------------------------------------------
print("\n== 5. 关键符号（字节码） ==")
blob = z.read("smooth/lift/Mtr3LiftAutoClose.class")
check(b"forEachTrackPosition" in blob,
      "jar 内 Mtr3LiftAutoClose.class 有 forEachTrackPosition 字面量（【1.45】楼层身份的唯一正确来源，反射字符串不受 remap 影响）")
check(b"hasFloor" in blob,
      "jar 内 Mtr3LiftAutoClose.class 有 hasFloor 字面量（按楼层身份匹配直梯）")
blob2 = z.read("smooth/lift/client/LiftChimePlayer.class")
check(b"liftToneCustomId" in blob2, "jar 内 LiftChimePlayer.class 有 liftToneCustomId（自定义提示音分支）")

_UNREMAPPED = os.path.join(ROOT, "build", "classes", "java", "main",
                           "smooth", "lift", "Mtr3LiftAutoClose.class")
if os.path.isfile(_UNREMAPPED):
    with open(_UNREMAPPED, "rb") as fh:
        raw = fh.read()
    check(b"getBlockEntity" in raw,
          "未重映射的 Mtr3LiftAutoClose.class 有 getBlockEntity（外呼入口必须知道自己点到哪个方块实体）")
    check(b"forEachTrackPosition" in raw,
          "未重映射的 Mtr3LiftAutoClose.class 也有 forEachTrackPosition（两边一致）")
else:
    print("[SKIP] 未找到未重映射的 class（%s），跳过原版方法名检查"
          % os.path.relpath(_UNREMAPPED, ROOT))

# ----------------------------------------------------------------------
print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("  -", f)
    sys.exit(1)
print("== 全部通过 ==")
