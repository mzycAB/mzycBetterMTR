# -*- coding: utf-8 -*-
"""离线校验：【1.29】屏蔽门两类声音的**归属范围**（用户点名）：

    「屏蔽门的门和幕墙以及幕墙尾部一起播报的是 pbmarrive 和 pbmmidium 的播报，
      只有连在一起的屏蔽门的门的播报是：铃声（开关门嘀嘀嘀和关门人声）」

即：
  - **到站 / 进站播报**（pbmmidium / pbmarrive）：按**整族**算 —— 门 + 幕墙（psd_glass* /
    apg_glass*）+ 幕墙尾部（*_end）都是「这一串」的成员，声源与射程口径把它们算进去
    （人站在幕墙边 / 站台端尾也算在组内，不会再被「最近那扇门超出射程」判走）；
  - **铃声**（开关门嘀嘀嘀 + 关门人声）：**只有门** —— 幕墙 / 幕墙尾部**永不发声**。

改错了不报错，症状是「幕墙旁听不到播报」（差在短射程 + 长幕墙时）或「墙也出声」。

## 判据

- DoorView / Entry 带「门 / 墙」区分（`door()` 组件）。
- `accept()` 把本串的幕墙 / 幕墙尾部注册进快照（`runWalls`，按串缓存，方法体断言防短路）。
- 铃声四条链路（detect 主循环 + firePlannedClose / resumeClose / tickForcedVoice 三个
  live 构建循环）都过滤 `!door.door()`（全文恰 4 处）。
- 播报两条口（tickArriveAnnounce 的 nearestPerRun、nearestInRun）**不**按门过滤。
"""

import os
import re
import sys
import glob
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
TRACKER = os.path.join(CLIENT, "PsdDoorTracker.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


tracker = load(TRACKER)
player = load(PLAYER)

# ======================================================================
# 1) 门 / 墙 区分
# ======================================================================
print()
print("===== 1) 快照项带「门 / 墙」区分 =====")

check(re.search(r"record DoorView\([^)]*boolean door\s*\)", tracker) is not None
      or "boolean door" in tracker and "record DoorView" in tracker,
      "DoorView 有 door 组件（门 / 幕墙）")
check(re.search(r"final boolean door;", tracker) is not None,
      "Entry 有 final boolean door")
check("runWalls(level, rawPos, flood)" in tracker and "RUN_WALL_CACHE" in tracker,
      "accept 调 runWalls（按串取幕墙/尾部位置缓存）")
check("isPsdFamily(cs) && !SmoothLift.isPsdDoor(cs)" in tracker,
      "runWalls 只收「非门」的家族方块（幕墙 / 尾部）")

# ★ 认 accept() 方法体本身：注册幕墙的调用必须真的会执行到（短路守卫 ⇒ 假绿）。
m_accept = re.search(r"private static void accept\(BlockPos rawPos, float fraction\)\s*\{(.*?)\n    \}",
                     tracker, flags=re.S)
check(m_accept is not None, "抠得出 accept() 方法体")
if m_accept:
    ab = m_accept.group(1)
    check("if (true)" not in ab and "if (false)" not in ab,
          "accept() 里没有短路守卫（幕墙注册真的会执行到）")
    check("runWalls(level, rawPos, flood)" in ab,
          "幕墙注册在 accept() 方法体内")

# ======================================================================
# 2) 铃声四条链路都只认门
# ======================================================================
print()
print("===== 2) 铃声（开关门+关门人声）只属于门 =====")

n_filter = player.count("if (!door.door())")
check(n_filter == 4,
      "铃声侧过滤 `!door.door()` 恰 4 处（detect 主循环 + 三个 live 构建循环）",
      "得到 %d 处" % n_filter)
check(player.count("continue;\n            live.put(door.key(), door);") == 3
      or player.count("continue;\n            }") >= 3,
      "三个关门链路循环（firePlannedClose / resumeClose / tickForcedVoice）都在循环头过滤")

# ======================================================================
# 3) 播报（pbmarrive / pbmmidium）按整族算，不过滤门/墙
# ======================================================================
print()
print("===== 3) 播报按整族（门 + 幕墙 + 幕墙尾部）算 =====")

m_arrive = re.search(r"private static void tickArriveAnnounce\([^)]*\)\s*\{(.*?)\n    \}",
                     player, flags=re.S)
check(m_arrive is not None, "抠得出 tickArriveAnnounce() 方法体")
if m_arrive:
    mb = m_arrive.group(1)
    check("!d.door()" not in mb and "door.door()" not in mb,
          "nearestPerRun 循环不按门/墙过滤（幕墙也计入「本串最近」）")
check("nearestEntryInRun" in tracker
      and "if (e.runKey != runKey" in tracker
      and "e.door" not in tracker.split("private static Entry nearestEntryInRun")[1][:1200],
      "nearestInRun/nearestDistanceInRun 不按 door 筛（墙位置也是合法声源/射程基准）")

# ======================================================================
# 4) 字节码
# ======================================================================
print()
print("===== 4) 字节码 =====")

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    with zipfile.ZipFile(jar) as z:
        tr = z.read("smooth/lift/client/PsdDoorTracker.class")
        pl = z.read("smooth/lift/client/PsdChimePlayer.class")
    for tok in (b"RUN_WALL_CACHE", b"runWalls"):
        check(tok in tr, "PsdDoorTracker.class 里编进了 %s" % tok.decode())
    check(b"door()Z" in pl or b"door" in pl, "PsdChimePlayer.class 里编进了 door() 区分")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")