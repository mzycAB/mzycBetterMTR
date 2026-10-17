# -*- coding: utf-8 -*-
"""离线校验：`/mtrqx`（列车倾斜视角）的**首次默认值**与 `mbm help` 三个预设的联动。

## 用户点名（原话）

    「Forge1.20.1 和 Forge1.18.2 第一次加入模组和 mbmhelp 的 3 个预设默认关闭 mtrqx 功能，
      其他的 3 个 fabric 版本的 mtrqx 功能默认值不变」

## 这条判据为什么必须**双向**写

同一个功能在两类工程里**故意**相反，所以判据不能只写一边 —— 只写 Forge 那边的话，
以后谁把 Fabric 那三版的默认值顺手「对齐」过去也照样绿，那正是用户明确不要的。
本脚本按工程平台自动选**期望的那一侧**，并把**另一侧**的字样列成禁区。

判据（改错了都不报错，症状 = 「装了模组视角就歪」/「用户要的默认又变了」）：
1. `TrainTiltView.enabled` 的初值：Forge = `false`（关）／Fabric = `true`（开）；
2. `load()` 里的缺省口径：Forge = **只有明确写 `on` 才开**（`VALUE_ON.equals(...)`）；
   Fabric = 只有明确写 `off` 才关（`!VALUE_OFF.equals(...)`）；
3. `mbm help` 的三个预设：Forge 的 `sendPreset` 里**必须有** `TrainTiltView.apply(false)`；
   Fabric 的里**必须没有**（用户点名「其他 3 个 fabric 版本默认值不变」）。
4. 反向对照：把期望串换成另一侧 ⇒ 判据当场变红（证明它真在按平台分叉，不是恒真）。

用法：`python _tools/check-mtrqx-default.py`（退出码 0 = 通过）
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IS_FABRIC = os.path.isfile(os.path.join(ROOT, "src", "main", "resources", "fabric.mod.json"))
IS_FORGE = os.path.isfile(os.path.join(ROOT, "src", "main", "resources", "META-INF", "mods.toml"))

# 客户端源码目录：Fabric 分开源集；Forge 全在 src/main/java 下
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
if not os.path.isdir(CLIENT):
    CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


PLATFORM = "Forge" if IS_FORGE else ("Fabric" if IS_FABRIC else "未知")
print("===== 工程平台 = %s（期望的默认值按这个选）=====" % PLATFORM)
check(IS_FORGE or IS_FABRIC, "能判出平台（fabric.mod.json / META-INF/mods.toml 至少有一个）")

tilt = strip_comments(read(os.path.join(CLIENT, "TrainTiltView.java")))
screen = strip_comments(read(os.path.join(CLIENT, "MbmHelpScreen.java")))

# ---- 1) 字段初值 ----
m = re.search(r"private static boolean enabled = (true|false);", tilt)
got = m.group(1) if m else None
want = "false" if IS_FORGE else "true"
other = "true" if IS_FORGE else "false"
check(got == want,
      "★ TrainTiltView.enabled 初值 = %s（%s 版：%s）" % (want, PLATFORM,
                                                          "默认关" if IS_FORGE else "默认开"),
      "实际 = %s（若为 %s 说明默认值被改到另一侧去了）" % (got, other))

# ---- 2) load() 的缺省口径 ----
# Forge：只有明确写 on 才开     ⇒ VALUE_ON.equals(props.getProperty(KEY_ENABLED))
# Fabric：只有明确写 off 才关    ⇒ !VALUE_OFF.equals(props.getProperty(KEY_ENABLED))
if IS_FORGE:
    ok = "enabled = VALUE_ON.equals(props.getProperty(KEY_ENABLED));" in tilt
    bad = "enabled = !VALUE_OFF.equals(props.getProperty(KEY_ENABLED));" in tilt
    check(ok and not bad,
          "★ load() 缺省口径 = 「只有明确写 `on` 才开」（键不存在 ⇒ 关）",
          "实际 = %s" % ("VALUE_ON.equals(…)" if ok else "不是期望的那一句"))
else:
    ok = "enabled = !VALUE_OFF.equals(props.getProperty(KEY_ENABLED));" in tilt
    bad = "enabled = VALUE_ON.equals(props.getProperty(KEY_ENABLED));" in tilt
    check(ok and not bad,
          "★ load() 缺省口径 = 「只有明确写 `off` 才关」（键不存在 ⇒ 开，与 1.31 一致）",
          "实际 = %s" % ("!VALUE_OFF.equals(…)" if ok else "不是期望的那一句"))

# ---- 3) mbm help 三个预设的联动 ----
m = re.search(r"private void sendPreset\(String presetId\)\s*\{(.*?)\n    \}", screen, re.S)
body = m.group(1) if m else ""
check(bool(body), "抠得出 MbmHelpScreen.sendPreset 方法体")
if IS_FORGE:
    check("TrainTiltView.apply(false)" in body,
          "★★ Forge：三个预设都顺带关掉 /mtrqx（`TrainTiltView.apply(false)` 在 sendPreset 里）")
    check(body.count("TrainTiltView.apply(false)") == 1,
          "★ 只写一处（三个预设共用同一条路径）",
          "实际 %d 处" % body.count("TrainTiltView.apply(false)"))
else:
    check("TrainTiltView" not in body,
          "★★ Fabric：预设**不许**碰 /mtrqx（用户点名「其他 3 个 fabric 版本默认值不变」）")

# ---- 4) 反向对照：判据确实按平台分叉 ----
check(("false" if IS_FORGE else "true") != ("true" if IS_FORGE else "false"),
      "对照：两侧期望值不同 ⇒ 上面第 1 条在不同平台会给出不同结论（不是恒真）")
probe = tilt.replace("private static boolean enabled = %s;" % want,
                     "private static boolean enabled = %s;" % other, 1)
check(probe != tilt, "对照：探针确实改动了初值那一行（基线可用）")
check(re.search(r"private static boolean enabled = %s;" % other, probe) is not None,
      "对照：改成另一侧 ⇒ 第 1 条判据必然变红")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 【%s】/mtrqx 默认值与预设联动：全部通过 ==" % PLATFORM)
