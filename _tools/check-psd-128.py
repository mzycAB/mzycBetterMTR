# -*- coding: utf-8 -*-
"""离线校验：【1.28】屏蔽门「到站/进站播报」的两轮修复 + 列车内倍率改阶跃。

## 需求（用户原话）

    现在某些屏蔽门arrive直接没有声音了，不知道怎么改的。log在LOG6
    玩家在列车内减少80%声音，离开列车立刻恢复到100%，不需要缓冲的功能好像不见了。

## 现场取证（`LOG6/latest.log`，数字可复核）

1. **进站报站什么时候响**：LOG6 里同一站台 9139277425791821951 的同**一班**车在 1 秒内
   响了两遍 —— 01:09:38 声源 `@[377,-20,32]`（时刻表还剩 -3831ms）、01:09:39 声源
   `@[364,-20,32]`（还剩 -4825ms）。两次算出的**绝对到站时刻**只差 6ms（同一班车），
   `ARRIVE_SAME_TRAIN_MS=10_000` 却没拦住 ⇒ 两个**不同的 runKey**（同一个站台的身份
   **没统一**，被拆成两个桶）各自播了一遍。

2. **为什么身份没统一**：`PsdDoorTracker.Entry.runKey` 只在**建 Entry 时写一次**。
   MTR 的站台数据比方块晚同步（LOG5 实测晚约 30 秒）⇒ 先跟踪的门拿的是「连通串身份」，
   站台数据到了之后 `runKeyOf` 能算出「站台身份」，但旧 Entry 的 runKey **不跟着刷新** ⇒
   同一站台的门永久分成「旧串身份 + 新站台身份」两桶。到站/进站播报各自去重、各自判射程 ⇒
   同一班车响两遍，且配置（音量/素材/不播）也会读成两份。

3. **为什么某些门「直接没有声音」**：进站报站一节在**身份之外**又自己调了一次
   `MtrDwellAccess.platformIdAt(门坐标)` 认站台 —— 与身份侧是两条**并行认亲**：
   同一扇门在 4 格边界上两边可能判出不同结果（身份认到、时刻表那侧没认到 ⇒ 静默
   `continue`，连日志都没有）。LOG6 的 x 轴 z=43 那排门**整排**从没出现在任何
   「认到 MTR 站台」日志里（只有 midium 响过、arrive 一次没有）—— 正是这种「静默炸点」。

4. **列车内倍率**：1.22 起车内 -80%（剩 0.2）；1.23 加了 1 秒线性斜坡（TRAIN_RAMP_TICKS=20）。
   用户现在点名**不需要缓冲**：车内减 80%、离开列车立刻恢复 100%（阶跃）。

## 判据（改错了不报错，症状是「还是有的响有的不响」/「车内不衰减」）

- 身份必须**可迁移**：accept 每次刷新 Entry 的 runKey / platformId（不再是 final）。
- 进站报站查时刻表用的站台 id 必须**来自 DoorView.platformId()**（同一份身份），
  不再各认各的；认不到时的诊断要打「最近站台差多远」。
- 列车内倍率必须**阶跃**（无 TRAIN_RAMP_TICKS 常量、无斜坡字段、直接投影当前状态）。
"""

import os
import re
import sys
import glob
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
TRACKER = os.path.join(CLIENT, "PsdDoorTracker.java")
MTR = os.path.join(CLIENT, "MtrDwellAccess.java")

FAILS = []


def check(ok, what):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what)
    if not ok:
        FAILS.append(what)


def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


tracker = load(TRACKER)
player = load(PLAYER)
mtr = load(MTR)

# ======================================================================
# 1) 身份可迁移：Entry.runKey 不再是 final，accept 每次刷新
# ======================================================================
print()
print("===== 1) 身份可迁移（连通串 → 站台，Entry 字段跟着刷新） =====")

check("final long runKey;" not in tracker,
      "Entry.runKey 不再 final（身份会迁移）")
check("long runKey;" in tracker, "Entry 里有可变 runKey 字段")
check("entry.runKey != runKey" in tracker and "entry.runKey = runKey" in tracker,
      "accept 里身份变了就刷新 Entry 字段")
check("long platformId" in tracker, "Entry/DoorView 有 platformId（认得到的站台 id）")
check("new DryView" not in tracker and "best.platformId" in tracker,
      "nearestInRun 把 platformId 带出来")
# ★ 认「accept」方法体本身（不能只 grep 全文：在刷新语句前插一句 `if (true) { return; }`
#   让刷新变死代码，全文照样命中 —— 正是 1.27 那次抓出来的假绿形态）。
m_accept = re.search(r"private static void accept\(BlockPos rawPos, float fraction\)\s*\{(.*?)\n    \}",
                     tracker, flags=re.S)
check(m_accept is not None, "抠得出 accept() 方法体")
if m_accept:
    ab = m_accept.group(1)
    check("if (true)" not in ab and "if (false)" not in ab,
          "accept() 里没有短路守卫（否则刷新语句=死代码，断言假绿）")
    check(re.search(r"entry\.runKey != runKey", ab) is not None
          and re.search(r"entry\.runKey = runKey;", ab) is not None,
          "身份迁移的刷写在方法体内（真的会执行到）")

# ======================================================================
# 2) 进站报报站用同一份身份查时刻表；认不到有诊断
# ======================================================================
print()
print("===== 2) 进站报报站：站台 id 走同一条身份链 + 认不到诊断 =====")

check("platformId = door.platformId();" in player,
      "进站报站优先用 DoorView.platformId()（与 runKey 同一份数据）")
check("MtrDwellAccess.nearestPlatformExplain" in player,
      "认不到时调最近站台诊断")
check("arriveFailNextLog" in player and "ARRIVE_FAIL_LOG_EVERY" in player,
      "诊断带节流（同一串每 60 秒一行）")
check("platformIdAt(door.x(), door.y(), door.z())" in player,
      "身份都认不到时才退回老路（platformIdAt 兜底）——1.21 语义保留")

# ======================================================================
# 3) MtrDwellAccess 的「最近站台差多远」探针
# ======================================================================
print()
print("===== 3) 最近站台距离探针 =====")

check("public static String nearestPlatformExplain" in mtr,
      "MtrDwellAccess.nearestPlatformExplain 存在")
check("rawMatchDistance" in mtr, "不卡上限的距离计算存在（诊断专用）")
check('"最近的站台 id="' in mtr and '"，距门 "' in mtr, "输出点名站台 id 与距离（格）")

# ======================================================================
# 4) 列车内倍率：阶跃（1.28 删除 1 秒斜坡）
# ======================================================================
print()
print("===== 4) 列车内倍率改阶跃（不需要缓冲） =====")

check("private static final int TRAIN_RAMP_TICKS" not in player,
      "TRAIN_RAMP_TICKS 常量已删除")
for tok in ("trainRampInit", "trainRampChangeTick", "trainRampFrom", "trainRampRiding"):
    check(tok not in player, "斜坡字段 %s 已删除" % tok)
check("trainRamp = ridingTrain() ? TRAIN_VOLUME_FACTOR : 1.0f;" in player,
      "每 tick 直接投影当前车厢状态（阶跃）")
check("TRAIN_VOLUME_FACTOR = 0.2f" in player, "车内 -80%（剩 0.2）保持")
# ★ 认「updateTrainRamp」方法体本身（同上：短路守卫会让全文 grep 假绿）。
m_ramp = re.search(r"static void updateTrainRamp\(Minecraft mc\)\s*\{(.*?)\n    \}",
                   player, flags=re.S)
check(m_ramp is not None, "抠得出 updateTrainRamp() 方法体")
if m_ramp:
    rb_ = m_ramp.group(1)
    check("if (true)" not in rb_ and "if (false)" not in rb_,
          "方法体内没有短路守卫（阶跃投影真的会执行到）")
    check(re.search(r"trainRamp\s*=\s*ridingTrain\(\) \? TRAIN_VOLUME_FACTOR : 1\.0f;", rb_) is not None,
          "阶跃投影就在方法体内")
    check("trainRampChangeTick" not in rb_ and "Math.min(1.0f" not in rb_,
          "方法体内没有斜坡数学（t 步进 / 起点 / 方向）")

# ======================================================================
# 5) 字节码：确认真的编进了 jar
# ======================================================================
print()
print("===== 5) 字节码 =====")

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    with zipfile.ZipFile(jar) as z:
        tr = z.read("smooth/lift/client/PsdDoorTracker.class")
        pl = z.read("smooth/lift/client/PsdChimePlayer.class")
        mw = z.read("smooth/lift/client/MtrDwellAccess.class")
    for blob, toks, cls in ((tr, (b"platformId", b"PLATFORM_FAIL_NEXT_LOG"), "PsdDoorTracker"),
                            (pl, (b"nearestPlatformExplain", b"arriveFailNextLog"), "PsdChimePlayer"),
                            (mw, (b"nearestPlatformExplain", b"rawMatchDistance"), "MtrDwellAccess")):
        for tok in toks:
            check(tok in blob, "%s.class 里编进了 %s" % (cls, tok.decode()))
    check(b"ridingTrain" in pl, "PsdChimePlayer.class 里 ridingTrain 还在（反射目标进了常量池）")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")