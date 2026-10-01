# -*- coding: utf-8 -*-
"""离线校验：【1.15】**提前量状态机**（照搬 PsdChimePlayer 的 tick 循环，纯 Python 跑一遍）。

## 为什么必须有这个脚本

`check-psd-split.py` 钉住的是**算术**（提前量 = 周期 − 素材时长），
但「声音出不来 / 人声没了」这类 bug **不在算术里**，而在**状态机的时序**里：

  · `learnedCycleTicks` 什么时候才被写进去？（要看到**一整轮**开门 → 全关才算学到）
  · `planClose` 在**开门那一瞬**查得到周期吗？（查不到就静默退化成「只嘀嘀」）
  · 排出去的计划在**到点**时会不会被自己丢掉？（门值不是全开 / 晚过宽限）
  · 关门那一瞬 `plannedCloseFired` 还在不在？（不在 → 又剪一次头 → 听起来「只有嘀嘀」）

这四件事全都是**静默**的（不报错、不崩溃，只是没有声音），所以只有把 tick 循环整个
搬到 Python 里逐步跑，才能在离线的环境下把它们钉住。

## 【第六轮】**为什么「停站很久也永远只有嘀嘀」—— 判据按门锚点分表**

`learnedCycleTicks` 的键是**门锚点**（`BlockPos.asLong`）。这在「站在同一扇门前看多趟车」
时成立，但**玩家一动就不是同一扇门了**：

  · 坐在车上跑线 ⇒ 每一站的屏蔽门是**不同的方块位置** ⇒ 每一站都是「第一次见这扇门」
    ⇒ `learnedCycleTicks.get(新 key) == null` ⇒ `planClose` 直接 return ⇒ **永远只有嘀嘀**；
  · 沿站台走两步 ⇒ 最近的门换一扇 ⇒ 同理。

用户原话：「我现在即使停站时间远远超过关门音频播放时间也只有嘀嘀嘀了……时间足够为什么也没人声了？」
根因就是**学习被按门锚点切成了互不相通的小格子**，而**停站时长属于「这一站」不属于「这扇门」**
（门程 80 tick 全世界一样，停站时长来自线路时刻表）。第 2 节（同一扇门）永远发现不了这个 bug ——
第 6 节专门跑**多扇门**，把「修好之前：全程没人声」和「修好之后：第 2 站起有人声」都钉住。

## 【第八轮】**不再靠猜：直接读 MTR 时刻表**（第 7 节）

用户接着问：「能不能读 MTR 时刻表拿到精确停站时长，不要靠猜啊」，还举了 **A 站 10 秒 /
B 站 20 秒 / C 站 30 秒** 这张时刻表问「会发生什么」。

第 6 节的「跨门借用」其实是**另一种猜** —— 它假定「同一条线上各站停站时长都一样」。
而 MTR **本来就存着每个站台的停站时长**（`Platform.getDwellTime()`，就是列车实际用的那个 D：
`SidingPathFinder` 建路径时把 `Platform.getDwellTime()` 抄进了 `PathData.dwellTime`），
客户端手里就有 ⇒ 没有任何理由去猜。

第 7 节把这件事钉成四层：
  · **7a** 常数与 MTR4 字节码对齐（`DOOR_DELAY 1000` / `DOOR_MOVE_TIME 3200` / 门程 80 tick）；
  · **7b** 换算表：停站时长 → 「开门 → 全关」的周期（含 A/B/C 三站与单调性）；
  · **7c** 与状态机**互相印证**（同一条曲线，两个独立来源只差一个可推导的常数偏置）；
  · **7d/7e** 对撞：第一次停站（零实测数据）就该排对整段、**结尾正好落在门全关**；
    并且**反面样本**——不填时刻表时 3 站一次整段都排不出来（证明这一层真在起作用）。

## 模型化到什么程度

**逐行照搬**下面这些方法（名字与 Java 一致，便于对照）：
`noteCycle` / `planClose` / `firePlannedClose` / `detect` / `resumeClose` / `playAligned` / `reset`。

门值序列用**字节码实测的算式**生成（`BlockPSDAPGDoorBase$BlockEntityBase.tick(F)`）：
  开门：`doorValue = min(1, doorValue + partialTick*20/3200*2)`   ⇒ 每 tick +1/80
  关门：`doorValue = max(0, doorValue - partialTick*20/3200*2)`   ⇒ 每 tick −1/80
所以取值恒是 1/80 的整数倍（0, 0.0125, 0.025, …）。★ 这一点很关键：
`EDGE = 0.02` 正好落在一个步长（0.0125）之内 ⇒ **每个阈值窗口里恰好有一个采样点**，
两边（开门 / 全关）的跳变都一定被看到。脚本第 1 节就断言这条。

用法：`python _tools/check-psd-predict.py`（退出码 0 = 全部通过）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAYER = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client", "PsdChimePlayer.java")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    src = re.sub(r"//[^\n]*", " ", src)
    return src


player = strip_comments(read(PLAYER))

# 开关：第 6 节要跑「修好之前 / 之后」两遍，所以判据做成可切的。
BORROW = True          # 学不到这一扇门的周期时，借用**别的门**学到的（= 本轮修复）
GUARD_ON = True        # 借来的周期比这一轮长时，别让提示音拖过「门全关」（= 本轮修复）

# ----------------------------------------------------------------------
# 0) 常量：从 Java 源码里读出来（不另抄一份）
# ----------------------------------------------------------------------
print("===== 0) 常量：从 Java 源码里读出来 =====")
EDGE = float(re.search(r"float EDGE\s*=\s*([0-9.]+)f", player).group(1))
GRACE = int(re.search(r"PLAN_GRACE_TICKS\s*=\s*(\d+)", player).group(1))
WAIT = int(re.search(r"ALIGN_MAX_WAIT_TICKS\s*=\s*(\d+)", player).group(1))
DUR = 10806          # mdoorclose.ogg（实测）
SPLIT = 6707         # 分界点（实测）
ANN = True           # 「默认」= 要人声
check(abs(EDGE - 0.02) < 1e-9, "EDGE = 0.02（判「全开/全关」的容差）", "得到 %s" % EDGE)
check(GRACE == 40, "PLAN_GRACE_TICKS = 40", "得到 %d" % GRACE)
check(WAIT == 5, "ALIGN_MAX_WAIT_TICKS = 5", "得到 %d" % WAIT)

STEP = 1.0 / 80.0        # 每 tick 门值变化（字节码：partialTick*20/3200*2）
check(abs(STEP - 0.0125) < 1e-12, "门值步长 = 1/80 = 0.0125（字节码实测）")
print("        EDGE=%.3f  vs  步长=%.4f  ⇒ 阈值窗口宽度 = 一个步长" % (EDGE, STEP))

# ---- 0b) 本脚本用的 DUR / SPLIT 必须是**关门**素材的（第七轮根因就是拿错了素材）----
# 这里的 DUR/SPLIT 是硬编码的，但它们必须是 mdoorclose.ogg 的真实值 —— 顺手用真素材核一次，
# 免得「素材换了 / 有人把常数改成开门素材的 2283」之后脚本还照样绿。
try:
    import soundfile as _sf
    _sfok = True
except Exception:
    _sfok = False
check(_sfok, "soundfile 可用（要用真素材核 DUR —— 这个脚本的状态机就是按它跑的）")
if _sfok:
    _adir = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift", "sounds", "audio")
    _ms = {}
    for _n in ("mdoorclose.ogg", "dooropen.ogg"):
        _x, _sr = _sf.read(os.path.join(_adir, _n), always_2d=True)
        _ms[_n] = int(round(1000.0 * _x.shape[0] / _sr))
        print("        %-15s 实测时长 %dms" % (_n, _ms[_n]))
    check(abs(_ms["mdoorclose.ogg"] - DUR) <= 30,
          "DUR = %dms 就是关门素材 mdoorclose.ogg 的真实时长" % DUR, "实测 %dms" % _ms["mdoorclose.ogg"])
    check(abs(_ms["dooropen.ogg"] - DUR) > 1000,
          "★ 开门素材 dooropen.ogg 的时长（%dms）与关门素材**明显不同** ⇒ 「拿错素材」是可被发现的"
          "（第七轮就是这么错的：开门素材还兼有 splitMs = -1，于是提前量永远排不出来）"
          % _ms["dooropen.ogg"])
    check(0 < SPLIT < DUR,
          "SPLIT = %dms 落在 (0, 素材时长) 内 ⇒ 分界点这一刀切得出来" % SPLIT)
print()

TICK = 50
LEAD = (DUR + 49) // 50          # 素材时长 → tick（向上取整）= 217
GUARD_SLACK = int(re.search(r"OVERHANG_SLACK_TICKS\s*=\s*(\d+)", player).group(1)) \
    if re.search(r"OVERHANG_SLACK_TICKS\s*=\s*(\d+)", player) else 8

# ---- 0b2) 【1.16】「强制等待」兜底用到的两个量 ----
#   ★ 它们只影响**塞不下**那一支（`best <= LEAD`）：那一支的结果从
#     「这一轮无人声」变成「开门音播完 + X 秒起播人声、门一动就掐断」。
#     所以本脚本里凡断言「A 站无人声」的地方都要跟着改口径（见第 7d/7g 的文案）。
OPEN_MS = _ms["dooropen.ogg"] if _sfok else 2283
OPEN_TICKS = (OPEN_MS + 49) // 50                       # 开门素材 → tick（等待的**起点**）
DATA = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedData.java")
_m = re.search(r"DEFAULT_PSD_CLOSE_WAIT_SECONDS\s*=\s*(\d+)",
               strip_comments(read(DATA)) if os.path.exists(DATA) else "")
CLOSE_WAIT = int(_m.group(1)) if _m else 5              # 等待秒数（默认 5）
check(CLOSE_WAIT == 5, "默认强制等待 = 5 秒（用户点名「第一次加入 mod 时默认为 5 秒」）",
      "得到 %d" % CLOSE_WAIT)
print("        强制等待兜底：开门素材 %dms（%d tick）+ 等 %d 秒（%d tick）"
      % (OPEN_MS, OPEN_TICKS, CLOSE_WAIT, CLOSE_WAIT * 20))


# ---- 0c) 【1.15 · 第八轮】MTR 时刻表 → 「开门 → 全关」周期（推导与断言见第 7 节）----
# 常数从 MtrDwellAccess.java 里读出来，不另抄一份：抄一份就会各自漂移。
ACCESS = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client", "MtrDwellAccess.java")
access_src = strip_comments(read(ACCESS)) if os.path.exists(ACCESS) else ""
DOOR_DELAY_MS = int(re.search(r"DOOR_DELAY_MS\s*=\s*(\d+)", access_src).group(1)) \
    if re.search(r"DOOR_DELAY_MS\s*=\s*(\d+)", access_src) else 1000
DOOR_MOVE_TIME_MS = int(re.search(r"DOOR_MOVE_TIME_MS\s*=\s*(\d+)", access_src).group(1)) \
    if re.search(r"DOOR_MOVE_TIME_MS\s*=\s*(\d+)", access_src) else 3200
CLOSE_LEAD_MS = DOOR_MOVE_TIME_MS + DOOR_DELAY_MS      # = 4200ms（源码里那两个常量的和，第 7 节核）
DEFAULT_TRAVEL_TICKS = int(re.search(r"DEFAULT_TRAVEL_TICKS\s*=\s*(\d+)", access_src).group(1)) \
    if re.search(r"DEFAULT_TRAVEL_TICKS\s*=\s*(\d+)", access_src) else 80


def close_at_ms(dwell_ms):
    """MTR4 Vehicle.simulateStopped 字节码：门开始关（= 发车指令）的时刻，相对「停稳」。"""
    return max(dwell_ms // 2, dwell_ms - CLOSE_LEAD_MS)


def timetable_cycle_ticks(dwell_ms, travel=DEFAULT_TRAVEL_TICKS):
    """【1.15 · 第八轮】站台停站时长 → 「门开始开 → 门全关」的 tick 数（= Java 侧 cycleTicksForDwell）。"""
    if dwell_ms <= 0:
        return -1
    span = max(0, close_at_ms(dwell_ms) - DOOR_DELAY_MS)
    return (span + 49) // 50 + travel


def timetable_hold_ticks(dwell_ms):
    """同一个停站时长换成**模拟器**里的「门全开保持」tick 数（两种离散化的桥）。

    `door_series` 的开门斜坡占用 80 个采样点，而 MTR 的「门全开」判据是
    `elapsedDwellTime >= 1000` ⇒ 关门指令相对「开门第一个采样点」的偏移 = 80 + hold。
    """
    span = max(0, close_at_ms(dwell_ms) - DOOR_DELAY_MS)
    return max(0, span // 50 - DEFAULT_TRAVEL_TICKS)



# ----------------------------------------------------------------------
# 1) 阈值窗口：两边跳变都**一定**看得到
# ----------------------------------------------------------------------
print("===== 1) 跳变判据：EDGE 与步长的关系（决定学到 / 学不到周期）=====")
vals_open = [round(k * STEP, 6) for k in range(0, 81)]
vals_close = [round(1.0 - k * STEP, 6) for k in range(0, 81)]

open_pairs = [(a, b) for a, b in zip(vals_open, vals_open[1:]) if a <= EDGE and b > EDGE]
close_pairs = [(a, b) for a, b in zip(vals_close, vals_close[1:]) if a > EDGE and b <= EDGE]
print("        开门跳变（prev<=EDGE<now）：%s" % (open_pairs or "（无）"))
print("        全关跳变（prev>EDGE>=now）：%s" % (close_pairs or "（无）"))
check(len(open_pairs) == 1,
      "★ 「开门」跳变在每轮里恰好有 1 个采样点（prev=%s → now=%s）" % (open_pairs[0] if open_pairs else "?"),
      "n=%d" % len(open_pairs))
check(len(close_pairs) == 1,
      "★ 「全关」跳变在每轮里恰好有 1 个采样点（prev=%s → now=%s）—— "
      "这是 learnedCycleTicks 能学到东西的前提" % (close_pairs[0] if close_pairs else "?"),
      "n=%d" % len(close_pairs))
check(len(open_pairs) == 1 and len(close_pairs) == 1,
      "★ 两个跳变都可见 ⇒ 门值步长（0.0125）必须 **不小于** EDGE（0.02）的量级；"
      "若哪天有人把 EDGE 调到 0.005 以下，全关跳变会被整段跳过（**学不到周期 ⇒ 永远只嘀嘀**）")


# ----------------------------------------------------------------------
# 2) 门值序列（字节码算式）+ 状态机（逐行照搬 Java）
# ----------------------------------------------------------------------
def door_series(t0_open, dwell_ticks, travel_ticks=80):
    """返回 {tick: fraction}：t0_open 起开门，走 travel_ticks 到全开，停 dwell 后关门。

    ★ 用的是字节码那条算式（每 tick ±1/80，两端夹住），不是「一笔画到底」的理想斜坡。
    """
    out = {}
    t = t0_open
    for k in range(1, travel_ticks + 1):                 # 开门
        out[t] = min(1.0, round(k * STEP, 6))
        t += 1
    for _ in range(dwell_ticks):                          # 停站（全开）
        out[t] = 1.0
        t += 1
    for k in range(1, travel_ticks + 1):                  # 关门
        out[t] = max(0.0, round(1.0 - k * STEP, 6))
        t += 1
    return out, t


class Sim:
    """逐行照搬 PsdChimePlayer 的静态状态机（同一时刻只有一个「最近的门」）。"""

    def __init__(self):
        self.lastDoor = {}
        self.pendingClose = {}
        self.learnedCycleTicks = {}
        self.cycleOpenTick = {}
        self.plannedCloseStart = {}
        self.plannedCloseFired = {}
        # 【第六轮】跨门共用：门程（开门→全关）与「开门→门开始关」都由**任一扇门**学到的值兜底，
        #   因为停站时长属于「这一站」而不属于「这扇门」；门程更是全世界一致。
        self.globalCycle = None
        self.globalTravel = None
        self.closeStartTick = {}
        self.wholePlaying = {}        # key → 正在响的那条整段（提前量播出去的）
        self.stopped = []             # 被「拖过门全关」判据掐掉的整段（tick）
        # 【1.15 · 第八轮】门锚点 → MTR 时刻表里的**站台停站时长**（ms）。
        #   ★ 默认是**空表**（= 读不到时刻表），所以第 1~6 节跑的还是「实测学习」那条路，
        #     语义与加这一层之前**完全一样**；只有第 7 节会把它填上，专门验时刻表那一层。
        self.timetable = {}
        self.log = []
        self.plays = []               # (tick, startMs, 说明)
        # 【1.16】门锚点 → 「强制等待人声」的**起播 tick**。
        #   ★ 它**不是** plannedCloseStart 那一张：那一张是「整段提前播」，
        #     两者由**同一条**判据（周期 > 素材时长）分成互斥的两支。
        self.forced = {}
        # 【1.16】**排过的历史**（tick, key, 起播 tick）。为什么另记一份：
        #   self.forced 会被 reset() / 下一轮开门清掉，而断言要问的是
        #   「那一轮到底排没排出来」—— 只留当前表就会在跑完之后什么都看不到。
        self.forcedPlans = []

    # ---- noteCycle ----
    def noteCycle(self, key, prev, now, t):
        if prev <= EDGE and now > EDGE:
            self.cycleOpenTick[key] = t
            self.plannedCloseFired.pop(key, None)
            self.wholePlaying.pop(key, None)
            self.forced.pop(key, None)          # 【1.16】同上：兜底挂在「上一轮」上
        elif prev > EDGE and now <= EDGE:
            opened = self.cycleOpenTick.pop(key, None)
            self.forced.pop(key, None)          # 【1.16】新一轮开始 ⇒ 上一轮那条兜底作废
            if opened is not None and t > opened:
                cycle = t - opened
                before = self.learnedCycleTicks.get(key)
                self.learnedCycleTicks[key] = cycle
                if before is None or abs(before - cycle) > 20:
                    self.log.append("t=%d 学到周期 %dms" % (t, cycle * TICK))
                # 【第六轮】同时记进「跨门共用」那一份（下一个站第一次见就能用）
                if self.globalCycle is None or abs(self.globalCycle - cycle) > 20:
                    self.globalCycle = cycle
                cs = self.closeStartTick.get(key)
                if cs is not None and cs > opened:
                    self.globalTravel = t - cs          # 门程（稳定量，用来判「会不会拖过门全关」）
            self.plannedCloseFired.pop(key, None)
            self.plannedCloseStart.pop(key, None)
            self.wholePlaying.pop(key, None)
            self.closeStartTick.pop(key, None)

    # ---- planClose ----
    def planClose(self, key, t):
        if not ANN or SPLIT <= 0 or DUR <= 0:
            return
        # 【1.15 · 第十二轮】周期 = **所有可得证据里最大的那一个**（与 Java 侧一致）：
        #   ① MTR 时刻表（每次开门都读）② 本扇门上一轮实测 ③ 跨门学到的。
        #   ★ 取「最大」的理由：门只会被车流**掐短**，不会短于它本来该开的时间；
        #     估短 ⇒ 直接放弃（一定没声音）；估长 ⇒ 起播那一刻门若已关上，firePlannedClose
        #     会把计划丢掉（不会错放）。两种错的代价不对称，所以宁可估长。
        own = self.learnedCycleTicks.get(key)
        own = -1 if own is None else own
        borrowed = -1 if (self.globalCycle is None or not BORROW) else self.globalCycle
        dwell_ms = self.timetable.get(key)
        tt = timetable_cycle_ticks(dwell_ms) if dwell_ms else -1
        best, winner = -1, -1
        for val, who in ((tt, 0), (own, 1), (borrowed, 2)):
            if val > best:
                best, winner = val, who
        if best <= 0:
            self.log.append("t=%d 不排提前量：拿不到周期" % t)
            return
        src = {0: "时刻表(%sms)" % dwell_ms, 1: "本扇门", 2: "借用别的门"}[winner]
        if best <= LEAD:
            # ★★【1.16】语义变了：这一支**不再是「这一轮无人声」**，而是走「强制等待」兜底 ——
            #   开门音效播完之后再等 X 秒起播**语音播报**，门一进入关门行程就立刻掐断。
            #   （停站**够长**时走上面那一支，根本不会读 X —— 这就是用户要的「够长就忽略」。）
            start = t + OPEN_TICKS + CLOSE_WAIT * 20
            self.forced[key] = start
            self.forcedPlans.append((t, key, start))
            self.log.append("t=%d 走强制等待：周期 %d tick（%s）塞不下素材 %d tick ⇒ 人声 %d tick 后起播"
                            % (t, best, src, LEAD, start - t))
            return
        fire = t + best - LEAD
        self.plannedCloseStart[key] = fire
        self.log.append("t=%d 排出提前量（%s）：%d tick 后整段起播" % (t, src, best - LEAD))

    # ---- firePlannedClose ----
    def firePlannedClose(self, key, now_frac, t):
        fire = self.plannedCloseStart.get(key)
        if fire is None:
            return
        if t < fire:
            return
        del self.plannedCloseStart[key]
        if t > fire + GRACE:
            self.log.append("t=%d 提前量过期（晚 %d tick），不补播" % (t, t - fire))
            return
        if now_frac < 1.0 - EDGE:
            self.log.append("t=%d 提前量到点但门不是全开（%.4f），不播" % (t, now_frac))
            return
        self.plays.append((t, 0, "整段（人声+嘀嘀）"))
        self.plannedCloseFired[key] = t
        self.wholePlaying[key] = t

    # ---- detect ----
    def detect(self, key, prev, now, t):
        closing = prev >= 1.0 - EDGE and now < 1.0 - EDGE
        opening = prev <= EDGE and now > EDGE
        if not (closing or opening):
            return
        if opening:
            self.planClose(key, t)
            self.plays.append((t, 0, "开门音"))
            return
        self.closeStartTick[key] = t
        # 门已经开始关了 ⇒ 这一轮的提前量计划**永远错过了**（它的前提是「还全开着就起播」）
        self.plannedCloseStart.pop(key, None)
        fired = self.plannedCloseFired.get(key)
        if fired is not None:
            sound_end = fired + LEAD
            if t < sound_end:
                self.log.append("t=%d 关门：整段还在响（还剩 %d tick），一 tick 后核对" % (t, sound_end - t))
                self.pendingClose[key] = (t, now, fired)   # 带整段信息 → 由 resumeClose 核对
                return
            self.plannedCloseFired.pop(key, None)
            self.wholePlaying.pop(key, None)
            self.log.append("t=%d 关门：提前播的整段已播完 %d tick，退回剪头" % (t, t - sound_end))
        self.pendingClose[key] = (t, now, None)
        self.log.append("t=%d 关门：挂起一 tick 量门速" % t)

    # ---- resumeClose / playAligned ----
    def resumeClose(self, key, now_frac, t):
        p = self.pendingClose.pop(key, None)
        if p is None:
            return
        pt, pfrac, fired = p
        elapsed = t - pt
        if elapsed <= 0:
            self.pendingClose[key] = p
            return
        drop = pfrac - now_frac
        if drop < 0 or elapsed > WAIT:
            self.plays.append((t, 0, "剪头兜底：整段"))
            self.wholePlaying.pop(key, None)
            return
        if drop == 0:
            self.pendingClose[key] = p
            return
        per_tick = drop / elapsed
        remain_ms = now_frac / per_tick * TICK
        if fired is not None:
            # 【第六轮】整段还在响 —— 它「本该」在门全关那一刻收尾，但周期是**借来的**，
            # 借的那个比这一轮长时它就会拖过门全关（门口已经关上、嘀嘀还在响）。
            travel = self.globalTravel
            sound_end = fired + LEAD
            if GUARD_ON and travel is not None and sound_end > t + travel + GUARD_SLACK:
                self.stopped.append(t)
                self.log.append("t=%d 整段会拖过门全关 %d tick ⇒ 掐掉，改用剪头对齐"
                                % (t, sound_end - (t + travel)))
                self.wholePlaying.pop(key, None)
                # 落到下面的剪头算式
            else:
                self.log.append("t=%d 整段还会在门关上那一刻收尾，不动它" % t)
                return
        raw = int(round(DUR - remain_ms))
        if SPLIT > 0 and raw < SPLIT:
            raw = SPLIT
        start = int(round(raw / 25.0)) * 25 if raw > 0 else 0
        heard = DUR - start
        self.plays.append((t, start, "剪头：从 %dms 起（只剩 %dms 嘀嘀）" % (start, heard)))

    # ---- reset ----
    def reset(self):
        self.lastDoor.clear()
        self.pendingClose.clear()
        self.plannedCloseStart.clear()
        # 【1.16】兜底人声的计划也清（Java 侧 reset(mc) 连正在响的那条都停掉：
        #   它挂在某一轮的某一扇门上，参照已经没了）。
        self.forced.clear()
        # ★ 必须**不清** learnedCycleTicks / cyclicOpenTick / globalCycle / globalTravel：
        #   列车离站、玩家走远，都不该让学到的停站时长作废（否则每次都要重学一轮）。


def run(dwell_ticks, stops=2, gap_ticks=200, vanish_between=True):
    """跑 `stops` 次停站，**同一扇门**（= 玩家站在站台不动看多趟车）。"""
    sim = Sim()
    t = 0
    openings = []
    for _ in range(stops):
        series, end = door_series(t, dwell_ticks)
        for tick in range(t, end):
            frac = series.get(tick, 0.0)
            prev = sim.lastDoor.get(1, frac)
            sim.noteCycle(1, prev, frac, tick)
            sim.detect(1, prev, frac, tick)
            sim.lastDoor[1] = frac
            sim.firePlannedClose(1, frac, tick)
            sim.resumeClose(1, frac, tick)
        openings.append(t)
        t = end + gap_ticks          # 列车离站
        if vanish_between:
            sim.reset()               # 门不在快照里 ⇒ 每 tick 都 reset
        else:
            for tick in range(end, t):
                frac = 0.0
                prev = sim.lastDoor.get(1, frac)
                sim.noteCycle(1, prev, frac, tick)
                sim.lastDoor[1] = frac
                sim.firePlannedClose(1, frac, tick)
    return sim, openings


def run_multi(dwells, gap_ticks=200, base_key=100, dwell_ms_by_stop=None, keys_by_stop=None):
    """走过 `len(dwells)` 个站，**每一站都是另一扇门**（= 玩家在车上跑线）。

    这正是用户报「停站很久也只有嘀嘀」的真实场景：`learnedCycleTicks` 的键是门锚点，
    而每一站的屏蔽门是不同的方块位置 ⇒ 每一站都是「第一次见这扇门」。

    @param dwell_ms_by_stop 【1.15 · 第八轮】每站在 MTR 时刻表里的**站台停站时长**（ms）。
           给了就填进 `sim.timetable`（= 这一站读得到时刻表）；不给 = 读不到。
    @param keys_by_stop 显式指定每一站是哪扇门。默认按 `base_key + i` 递增（一站一扇门）；
           传 [100,101,102,100,101,102] 这种就是**跑两圈**（同一条线的同一个站 = 同一扇门），
           用来验「第二圈会不会被上一站的周期串味 / 自我强化」。
    """
    sim = Sim()
    t = 0
    stops = []
    for i, dwell in enumerate(dwells):
        key = keys_by_stop[i] if keys_by_stop is not None else base_key + i
        if dwell_ms_by_stop is not None:
            sim.timetable[key] = dwell_ms_by_stop[i]
        series, end = door_series(t, dwell)
        for tick in range(t, end):
            frac = series.get(tick, 0.0)
            prev = sim.lastDoor.get(key, frac)
            sim.noteCycle(key, prev, frac, tick)
            sim.detect(key, prev, frac, tick)
            sim.lastDoor[key] = frac
            sim.firePlannedClose(key, frac, tick)
            sim.resumeClose(key, frac, tick)
        stops.append({"open": t, "key": key, "dwell": dwell,
                      "close_start": t + 80 + dwell, "closed": t + 80 + dwell + 80})
        t = end + gap_ticks
        sim.reset()
    return sim, stops


# ----------------------------------------------------------------------
# 2) 两次停站（同一扇门）：第二次**必须**听到整段
# ----------------------------------------------------------------------
print()
print("===== 2) 同一扇门停两次（停站 20 秒，远长于素材 10.8 秒；中间列车离站）=====")
DWELL_20S = 400
sim, opens = run(DWELL_20S)
print("        第 1 次开门 @t=%d；第 2 次开门 @t=%d" % (opens[0], opens[1]))
for t, start, what in sim.plays:
    print("        t=%-6d 播放 %s" % (t, what))

stop2_open = opens[1]
whole = [p for p in sim.plays if p[0] >= stop2_open and p[2].startswith("整段")]
check(len(whole) >= 1,
      "★★ 第 2 次停站必须听到**整段**（人声 + 嘀嘀）",
      "整段播放 %d 次" % len(whole))
check(any(p[1] == 0 for p in whole), "★ 整段是从素材第 0ms 起播的（人声在，不是剪头）")

stop1_plays = [p for p in sim.plays if p[0] < stop2_open]
check(not any(p[2].startswith("整段") for p in stop1_plays),
      "★ 第 1 次停站**必然**只有嘀嘀（还没学到周期 ⇒ 不猜）—— 这是设计使然",
      "第 1 次播放 %s" % ([p[2] for p in stop1_plays],))

if whole:
    fire_tick = whole[0][0]
    close_start = stop2_open + 80 + DWELL_20S
    door_closed = close_start + 80
    err = (fire_tick + DUR / 50.0) - door_closed
    print("        开门 t=%d，门开始关 t=%d，门全关 t=%d；整段起播 t=%d（比关门早 %dms），结尾误差 %.1f tick"
          % (stop2_open, close_start, door_closed, fire_tick, (close_start - fire_tick) * TICK, err))
    check(fire_tick < close_start,
          "★ 整段起播**早于**门开始关（%d < %d）⇒ 「不是开始关门才播放提示音」" % (fire_tick, close_start))
    check(abs(err) <= 4, "★ 结尾与「门全关」相差 %.1f tick（≤ 4 tick ≈ 200ms）" % err)
    close_plays = [p for p in sim.plays if close_start <= p[0] <= door_closed + 5]
    check(len(close_plays) == 0,
          "★ 关门期间**没有**再播一声（否则会叠成「整段 + 又一串嘀嘀」）",
          "出现 %d 次" % len(close_plays))

# ---- 2b) 三次停站：第 2、3 次都必须有整段 ----
print()
print("===== 2b) 同一扇门连停三次：第 2 / 3 次都必须是整段 =====")
sim_b, opens_b = run(DWELL_20S, stops=3)
for i in (1, 2):
    lo = opens_b[i]
    hi = lo + 80 + DWELL_20S + 80
    got = [p for p in sim_b.plays if lo <= p[0] <= hi and p[2].startswith("整段")]
    check(len(got) >= 1, "★ 第 %d 次停站有整段（人声）" % (i + 1), "整段 %d 次" % len(got))

# ---- 2c) 玩家**中途才走到站台**（第 1 轮只看到关门的后半段）----
print()
print("===== 2c) 中途入场：开门没看到 ⇒ 那一轮学不到周期（要再等一轮）=====")
sim_c = Sim()
series, end = door_series(0, DWELL_20S)
saw_from = 80
for tick in range(saw_from, end):
    frac = series.get(tick, 0.0)
    prev = sim_c.lastDoor.get(1, frac)
    sim_c.noteCycle(1, prev, frac, tick)
    sim_c.detect(1, prev, frac, tick)
    sim_c.lastDoor[1] = frac
    sim_c.firePlannedClose(1, frac, tick)
    sim_c.resumeClose(1, frac, tick)
sim_c.reset()
print("        只看全开 → 全关这一段：学到周期了吗？%s"
      % ("学到了" if sim_c.learnedCycleTicks else "没学到（没看到开门跳变）"))
check(not sim_c.learnedCycleTicks,
      "★ 没看到「开门」跳变 ⇒ 那一轮学不到周期（`openedAt == null`）")
check("openedAt != null" in player,
      "★ 源码里确实有 `openedAt != null` 这道门（不能拿半个循环当周期用）")

# ----------------------------------------------------------------------
# 3) 停站极短：允许退化成「只嘀嘀」，但结尾仍要落在门上
# ----------------------------------------------------------------------
print()
print("===== 3) 停站 3 秒（塞不下整条素材）：不再静默 —— 走强制等待 + 剪头嘀嘀仍在 =====")
sim2, opens2 = run(60, stops=2)
for t, start, what in sim2.plays:
    print("        t=%-6d 播放 %s" % (t, what))
check(not any(p[2].startswith("整段") for p in sim2.plays),
      "★ 塞不下时**不排**提前量（那一支是「整段提前播」，塞不下就不该走）")
# 【1.16】★ 口径变了：以前这一支是「这一轮干脆没有人声」，现在必须**排出一条强制等待**。
#   这条断言故意用 `sim2.forced`（模拟器里与 Java 同构的那张表）而不是日志文本 ——
#   日志措辞会改，表在不在才是行为。
check(len(sim2.forcedPlans) >= 1,
      "★★【1.16】塞不下时**排出了强制等待人声**（不再是静默度过这一轮）",
      "排过 %d 次" % len(sim2.forcedPlans))
if sim2.forcedPlans:
    _op, _key, _st = sim2.forcedPlans[0]
    check(_st - _op == OPEN_TICKS + CLOSE_WAIT * 20,
          "★★ 起播点 = 开门那一 tick + 开门素材 %d tick + 等待 %d tick = %d tick"
          % (OPEN_TICKS, CLOSE_WAIT * 20, OPEN_TICKS + CLOSE_WAIT * 20),
          "实际 %d" % (_st - _op))
cut = [p for p in sim2.plays if p[2].startswith("剪头")]
check(len(cut) >= 1, "★ 关门端仍然走剪头：至少有一次「只剩嘀嘀」，而不是静默", "剪头 %d 次" % len(cut))
if cut:
    start_ms = cut[-1][1]
    check(start_ms >= SPLIT,
          "★ 剪点 %dms ≥ 分界点 %dms ⇒ 兜底那一轮**不会漏出人声**" % (start_ms, SPLIT))
    check(DUR - start_ms <= 4000 + 25 * 2,
          "★ 剪完剩下的长度 ≈ 一个门程（%dms），所以结尾仍然落在门上" % (DUR - start_ms))

# ----------------------------------------------------------------------
# 4) 停站比上一轮长：事后兜底必须补一声
# ----------------------------------------------------------------------
print()
print("===== 4) 停站比上一轮长（预测必然有误差）：必须补一声，不能静默 =====")
sim3 = Sim()
series1, end1 = door_series(0, DWELL_20S)
for tick in range(0, end1):
    frac = series1.get(tick, 0.0)
    prev = sim3.lastDoor.get(1, frac)
    sim3.noteCycle(1, prev, frac, tick)
    sim3.detect(1, prev, frac, tick)
    sim3.lastDoor[1] = frac
    sim3.firePlannedClose(1, frac, tick)
    sim3.resumeClose(1, frac, tick)
t = end1 + 200
series2, end2 = door_series(t, 800)          # 第二轮停 40 秒
for tick in range(t, end2):
    frac = series2.get(tick, 0.0)
    prev = sim3.lastDoor.get(1, frac)
    sim3.noteCycle(1, prev, frac, tick)
    sim3.detect(1, prev, frac, tick)
    sim3.lastDoor[1] = frac
    sim3.firePlannedClose(1, frac, tick)
    sim3.resumeClose(1, frac, tick)
print("        第二轮开门 t=%d，门全关 t=%d" % (t, t + 80 + 800 + 80))
for tk, start, what in sim3.plays:
    print("        t=%-6d 播放 %s" % (tk, what))
late = [p for p in sim3.plays if p[0] >= t]
check(len(late) >= 2,
      "★ 第二轮既有「整段提前播」、又有人声落地后补的一声（关门那一刻不静默）",
      "%d 声" % len(late))
check(any(p[2].startswith("剪头") for p in late), "★ 补的那一声走的是剪头（只嘀嘀、结尾仍然落在门上）")

# ----------------------------------------------------------------------
# 6) 【第六轮】跨站多扇门 —— 用户报「停站很久也只有嘀嘀」的真实场景
# ----------------------------------------------------------------------
print()
print("===== 6) 【第六轮】车上跑线：每一站都是**另一扇门**（判据按门锚点分表）=====")

# 6a) 修好之前：借用关掉、判据只按门锚点 ⇒ 全程没有人声
BORROW, GUARD_ON = False, False
sim_old, stops_old = run_multi([DWELL_20S] * 4)
whole_old = [p for p in sim_old.plays if p[2].startswith("整段")]
print("        【修好之前】4 站 20 秒停站，整段播放 %d 次" % len(whole_old))
print("        【修好之前】每站播放：%s"
      % {s["key"]: [p[2] for p in sim_old.plays if s["open"] <= p[0] <= s["closed"] + 5] for s in stops_old})
check(len(whole_old) == 0,
      "★★★ 复现用户报的现象：**停站 20 秒（远长于素材 10.8 秒）仍然全程只有嘀嘀** —— "
      "因为「学到的周期」是按门锚点存的，而每一站是另一扇门 ⇒ 每一站都算「第一次见」",
      "整段 %d 次" % len(whole_old))

# 6b) 修好之后：借用别的门学到的周期 ⇒ 第 2 站起都有人声，且结尾仍落在门上
BORROW, GUARD_ON = True, True
sim_new, stops_new = run_multi([DWELL_20S] * 4)
for s in stops_new:
    plays = [p for p in sim_new.plays if s["open"] <= p[0] <= s["closed"] + 5]
    print("        第 %d 站（key=%d，停站 %dms）：%s"
          % (stops_new.index(s) + 1, s["key"], s["dwell"] * TICK, [p[2] for p in plays]))
for i, s in enumerate(stops_new):
    plays = [p for p in sim_new.plays if s["open"] <= p[0] <= s["closed"] + 5]
    if i == 0:
        check(not any(p[2].startswith("整段") for p in plays),
              "★ 第 1 站本来就没有参照（谁都没学过周期）⇒ 只有嘀嘀，这是允许的")
    else:
        whole_here = [p for p in plays if p[2].startswith("整段") and p[1] == 0]
        check(len(whole_here) == 1,
              "★★ 第 %d 站必须听到**整段**（人声）—— 这就是用户要的「时间足够就有人声」" % (i + 1),
              "整段 %d 次" % len(whole_here))
        if whole_here:
            err = (whole_here[0][0] + DUR / 50.0) - s["closed"]
            check(abs(err) <= 4,
                  "★ 第 %d 站的整段结尾仍落在「门全关」上（差 %.1f tick）" % (i + 1, err))

# 6c) 借来的周期比这一轮**长**时，绝不能拖过门全关
print()
print("===== 6c) 借来的周期偏长（换车次/时刻表改了）：必须掐掉整段、改用剪头对齐 =====")
BORROW, GUARD_ON = True, True
sim_c2, stops_c2 = run_multi([DWELL_20S, 350])     # 第 1 站 20s 学到，第 2 站 17.5s
for s in stops_c2:
    plays = [p for p in sim_c2.plays if s["open"] <= p[0] <= s["closed"] + 5]
    print("        第 %d 站（停站 %dms，门全关 t=%d）：%s"
          % (stops_c2.index(s) + 1, s["dwell"] * TICK, s["closed"], [p[2] for p in plays]))
print("        掐掉的整段：%s" % (sim_c2.stopped or "（无）"))
second = stops_c2[1]
plays2 = [p for p in sim_c2.plays if p[0] >= second["open"]]
check(len(sim_c2.stopped) >= 1,
      "★★ 检测到「整段会拖过门全关」并**掐掉**了它（否则门都关上了、嘀嘀还在响）",
      "掐掉 %d 次" % len(sim_c2.stopped))
last = plays2[-1] if plays2 else None
check(last is not None and last[2].startswith("剪头"),
      "★ 掐掉之后改用剪头（只嘀嘀），仍然听得到声音、不是静默",
      "最后一次 %s" % (last[2] if last else "无"))
if last is not None and last[2].startswith("剪头"):
    err = (last[0] + (DUR - last[1]) / 50.0) - second["closed"]
    check(abs(err) <= 4,
          "★★ 掐掉之后那一声的结尾落在「门全关」上（差 %.1f tick）" % err)

# 6d) 对照：借来的周期**正好**，就不该掐（否则会平白把好端端的一条剪掉）
print()
print("===== 6d) 对照组：各站停站一致（借来的周期正好）⇒ 一次都不许掐 =====")
sim_c3, stops_c3 = run_multi([DWELL_20S] * 3)
check(not sim_c3.stopped,
      "★ 周期准确时判据不许误伤（掐掉次数必须为 0）", "掐掉 %d 次" % len(sim_c3.stopped))
whole_c3 = [p for p in sim_c3.plays if p[2].startswith("整段")]
check(len(whole_c3) >= 2, "★ 第 2 / 3 站都听到了整段", "整段 %d 次" % len(whole_c3))

# 6e) 把开关拨回「发布态」，后面的源码断言才对得上
BORROW, GUARD_ON = True, True

# ----------------------------------------------------------------------
# 5) 源码结构：这些路径都必须真在
# ----------------------------------------------------------------------
print()
print("===== 5) 源码结构 =====")
check("noteCycle(door, prev, door.fraction(), gameTime)" in player,
      "noteCycle 在 tick 循环里对**所有**门调用（学到周期的唯一入口）")
check(re.search(r'Playable closePlayable = resolvePlayable\(mc, door, "close", distanceXz, distanceY\)', player) is not None
      and "planClose(mc, door, closePlayable, openPlayable)" in player,
      "★★★ planClose 收的**第一份**是**按 `\"close\"` 解析出来的**那一份（第七轮根因：曾复用开门那一份 ⇒ "
      "开门素材 splitMs = -1 ⇒ 提前量永远排不出来 ⇒ 每一站都只有嘀嘀）。"
      "【1.16】它现在多收一份 `openPlayable` —— 那**只**用来量「开门音效多长」（强制等待的起点），"
      "绝不许参与「这一轮排不排提前量 / 素材时长是多少」的判断")
check(player.count("planClose(mc, door, closePlayable, openPlayable)") == 1
      and "planClose(mc, door, playable)" not in player
      and "planClose(mc, door, openPlayable" not in player,
      "★★ 反面样本：不许再出现「把开门那一份当成要播的素材喂给 planClose」的写法"
      "（前两个形式必须为 0；第三个形式 —— 把 openPlayable 放在第一参数位 —— 也是 0）")
check(re.search(r'Playable openPlayable = resolvePlayable\(mc, door, "open", distanceXz, distanceY\)', player) is not None
      and player.find('Playable openPlayable = resolvePlayable(mc, door, "open", distanceXz, distanceY)')
      < player.find("planClose(mc, door, closePlayable, openPlayable)"),
      "★【1.16】开门那一份在 planClose **之前**解析（强制等待的起点是「开门音效播完」，"
      "所以必须先把它的时长拿出来）—— 顺序反过来就会拿到上一次的残留")
check(re.search(r"if \(best <= 0L\)", player) is not None,
      "★ 拿不到周期时的分支还在（先试时刻表 / 本扇门实测 / 跨门借用，三个都没有才 return）")
check(re.search(r"globalCycleTicks", player) is not None,
      "★★ 源码里真有「跨门共用」的周期（`globalCycleTicks`）—— 这是第 6 节能过的前提")
check(re.search(r"OVERHANG_SLACK_TICKS", player) is not None,
      "★★ 源码里有「拖过门全关」判据的容差常量（`OVERHANG_SLACK_TICKS`）")
check(re.search(r"if \(best <= leadTicks\)", player) is not None, "★ 塞不下时 return（退剪头）")
check("plannedCloseFired.put(door.key(), now)" in player,
      "★ 提前播成功后记下起播 tick（关门那一刻靠它判断「还在响」还是「早播完」）")

# ----------------------------------------------------------------------
# 7) 【1.15 · 第八轮】MTR 时刻表那一层：从「站台停站时长」直接算出周期，**不用等实测**
# ----------------------------------------------------------------------
# 用户原话：「能不能读 MTR 时刻表拿到精确停站时长，不要靠猜啊」。
#
# 这一节把「时刻表 → 周期」的算式与**真实 MTR4 字节码**对齐，再和模拟器对撞：
#   7a 常数与源码一致（1000 / 3200 / 4200 / 80）
#   7b 换算表：用户点名的 **A 站 10s / B 站 20s / C 站 30s**
#   7c 与实测周期**互相印证**（同一条曲线两个来源，必须对得上）
#   7d 与状态机对撞：周期准确时，整段素材的结尾**正好**落在「门全关」上（误差 0 tick）
#   7e 反面样本：不填时刻表 ⇒ 第一站没人声（证明这一层真在起作用）
print()
print("===== 7) 【1.15 · 第八轮】MTR 时刻表那一层（读站台停站时长，不等实测）=====")
TIMETABLE_ON = bool(access_src)
check(TIMETABLE_ON, "MtrDwellAccess.java 存在（时刻表访问层）")

# ---- 7a) 常数：必须与 MTR4 字节码一字不差 ----
print()
print("---- 7a) 常数与 MTR4 字节码对齐 ----")
check(DOOR_DELAY_MS == 1000,
      "DOOR_DELAY_MS = 1000（字节码 Vehicle.DOOR_DELAY 的 ConstantValue）", "得到 %d" % DOOR_DELAY_MS)
check(DOOR_MOVE_TIME_MS == 3200,
      "DOOR_MOVE_TIME_MS = 3200（字节码 Vehicle.DOOR_MOVE_TIME 的 ConstantValue）", "得到 %d" % DOOR_MOVE_TIME_MS)
check(re.search(r"CLOSE_LEAD_MS\s*=\s*DOOR_MOVE_TIME_MS\s*\+\s*DOOR_DELAY_MS", access_src) is not None,
      "★ 源码里 CLOSE_LEAD_MS = DOOR_MOVE_TIME_MS + DOOR_DELAY_MS（= 字节码里那个 4200，"
      "不许写成裸数字 —— 否则将来改了一个忘另一个）")
check(CLOSE_LEAD_MS == 4200, "CLOSE_LEAD_MS = 4200", "得到 %d" % CLOSE_LEAD_MS)
check(DEFAULT_TRAVEL_TICKS == int(round(1.0 / STEP)),
      "★ 时刻表侧的门程兜底值（%d tick）与门值步长（1/%d）自洽 —— 同一个字节码依据"
      % (DEFAULT_TRAVEL_TICKS, int(round(1.0 / STEP))))

# ---- 7b) 换算表：用户点名的 A 站 10s / B 站 20s / C 站 30s ----
print()
print("---- 7b) 停站时长 → 周期（用户的 A=10s / B=20s / C=30s）----")
print("        %-10s %-12s %-14s %-12s %s" % ("停站", "关门指令@", "开门→全关", "整段 10806ms", "结果"))
ABC = [(10000, "A"), (20000, "B"), (30000, "C"), (60000, "D")]
abc_rows = []
for dwell_ms, tag in ABC:
    cyc = timetable_cycle_ticks(dwell_ms)
    fits = cyc > LEAD
    abc_rows.append((tag, dwell_ms, cyc, fits))
    print("        %-10s %-12s %-14s %-12s %s"
          % ("%s: %dms" % (tag, dwell_ms),
             "%dms" % close_at_ms(dwell_ms),
             "%d tick / %dms" % (cyc, cyc * TICK),
             "塞得下" if fits else "塞不下",
             "人声+嘀嘀（整段）" if fits else "只嘀嘀（剪头对齐结尾）"))

a_row, b_row, c_row = abc_rows[0], abc_rows[1], abc_rows[2]
check(a_row[3] is False,
      "★ A 站（10 秒）周期 %d tick < 素材 %d tick ⇒ **这一站本来就不该放人声**"
      "（用户说的「时间不够就默认不播人声」）" % (a_row[2], LEAD))
check(b_row[3] is True and c_row[3] is True,
      "★★ B 站（20 秒）/ C 站（30 秒）周期 %d / %d tick > 素材 %d tick ⇒ **必须放整段**"
      % (b_row[2], c_row[2], LEAD))
check(all(abc_rows[i][2] < abc_rows[i + 1][2] for i in range(len(abc_rows) - 1)),
      "★ 停站时长越长、周期越长（单调，不许出现「设了没用」）",
      " -> ".join(str(r[2]) for r in abc_rows))
check(all(r[2] > 0 for r in abc_rows), "★ 任何停站时长都算得出**正**的周期（不会因为 D/2 那一支掉成负数）")

# 长停站时周期应当是「停站 − 1200ms」（关门指令 4200ms 提前 + 门程 4000ms − 开门延迟 1000ms）
_LONG = [(9000 + i * 1000) for i in range(12)] + [20000, 30000, 60000, 120000]
_worst = max(abs(timetable_cycle_ticks(d) * TICK - (d - 1200)) for d in _LONG)
check(_worst <= TICK,
      "★ 长停站（≥ 9 秒）时「周期 = 停站时长 − 1200ms」（误差 ≤ 1 tick）",
      "最大偏差 %dms" % _worst)
# 短停站（< 8400ms）走 D/2 那一支：周期 = D/2 + 3000
check(abs(timetable_cycle_ticks(6000) * TICK - (6000 // 2 + 3000)) <= TICK,
      "★ 短停站（6 秒）时周期 = 停站/2 + 3000ms（另一半下限，不许忽略）",
      "得到 %dms" % (timetable_cycle_ticks(6000) * TICK))

# ---- 7c) 与「实测学习」互相印证：同一条曲线，两个独立来源必须对得上 ----
# ★ 这两个数**永远不可能完全相等**，差值是**状态机的跳变检测口径**造成的固定 3 tick
#   （不是误差，是可以逐步推出来的）：
#     · 开门跳变落在 t0+1：第 1 个采样点的值已经是 1/80，而 `lastDoor` 是**现填**的，
#       所以「离开 0」要到第 2 个点才看得见（少 1 tick）；
#     · 全关跳变落在 frac 首次 ≤ EDGE 的那一格：1/80 ≤ 0.02 < 2/80 ⇒ 比 frac 真正到 0 早一格（少 1 tick）；
#     · 斜坡 80 个采样点 ⇒ frac=0 落在 t0+175 而不是解析式的 t0+176（少 1 tick）。
#   ⇒ 稳态判据是「**差值恒为常数**」（两个来源是同一条直线），而不是「差值为 0」。
print()
print("---- 7c) 时刻表算的周期 vs 状态机实测到的周期 ----")
_JUMP_BIAS = 3
print("        %-10s %-14s %-14s %s" % ("停站", "时刻表(解析)", "实测(跑一遍)", "差"))
_diffs = []
for dwell_ms in (10_000, 20_000, 30_000, 60_000):
    hold = timetable_hold_ticks(dwell_ms)
    sim_x, stops_x = run_multi([hold], dwell_ms_by_stop=[dwell_ms])
    learned = sim_x.learnedCycleTicks.get(stops_x[0]["key"])
    parsed = timetable_cycle_ticks(dwell_ms)
    _diffs.append(None if learned is None else learned - parsed)
    print("        %-10s %-14s %-14s %s"
          % ("%dms" % dwell_ms, "%d tick" % parsed, "%s tick" % learned,
             None if learned is None else learned - parsed))
    check(learned is not None, "停站 %dms：状态机跑完一轮，学到了周期" % dwell_ms)
    # (a) 「门全关」发生的**时刻**必须对得上（这才是真正有物理含义的那个量）
    closed_offset = stops_x[0]["closed"] - stops_x[0]["open"]
    check(abs(closed_offset - parsed) <= 1,
          "★★ 停站 %dms：「门全关」的时刻（解析 %d tick / 模型 %d tick）对得上"
          % (dwell_ms, parsed, closed_offset))
check(len(set(_diffs)) == 1 and _diffs[0] == -_JUMP_BIAS,
      "★★★ 两个独立来源是同一条直线：差值恒为 -%d tick（跳变检测口径，见上方推导）"
      % _JUMP_BIAS, "得到 %s" % _diffs)

# ---- 7d) 与状态机对撞：第一次停站就该听到整段，且**结尾正好落在门全关** ----
print()
print("---- 7d) 每一站**第一次**见到这扇门（没有任何实测值）：靠时刻表能不能排对 ----")
for dwell_ms in (20_000, 30_000):
    hold = timetable_hold_ticks(dwell_ms)
    sim_t, stops_t = run_multi([hold], dwell_ms_by_stop=[dwell_ms])
    st = stops_t[0]
    whole = [p for p in sim_t.plays if p[2].startswith("整段")]
    print("        停站 %dms：门全关 t=%d，播放 %s" % (dwell_ms, st["closed"], [p[2] for p in whole]))
    check(len(whole) == 1,
          "★★★ 停站 %dms 的**第一次**停站就排出了整段（不用等上一轮实测）" % dwell_ms,
          "整段 %d 次" % len(whole))
    if whole:
        err = (whole[0][0] + LEAD) - st["closed"]
        check(abs(err) <= 2,
              "★★★ 停站 %dms：整段素材的结尾**正好**落在「门全关」上（差 %d tick）—— "
              "这一轮从头到尾没有任何实测数据参与" % (dwell_ms, err))
    check(not sim_t.stopped, "★ 没有触发「拖过门全关」的掐断（周期是这一站自己的，不该拖）")

# A 站（10 秒）对撞：不能排整段，但**必须有声音**（剪头只留嘀嘀、结尾仍落在门上）
hold_a = timetable_hold_ticks(10_000)
sim_a, stops_a = run_multi([hold_a], dwell_ms_by_stop=[10_000])
whole_a = [p for p in sim_a.plays if p[2].startswith("整段")]
sliced_a = [p for p in sim_a.plays if p[2].startswith("剪头")]
print("        A 站 10000ms：整段 %d 次，剪头 %d 次，强制等待 %d 次"
      % (len(whole_a), len(sliced_a), len(sim_a.forcedPlans)))
check(not whole_a, "★ A 站（10 秒）不排整段（周期塞不下整条素材 —— 那一支只服务「整段提前播」）")
# ★【1.16】A 站的口径变了：**不再是「没人声」**，而是走强制等待（人声挪到「开门音 + 5 秒」之后、
#   门一动掐断）。这一条是用户那次需求的直接后果，必须钉住，否则「A 站静默」会被当成正确行为。
check(len(sim_a.forcedPlans) >= 1,
      "★★【1.16】A 站改走**强制等待**（不再静默）：人声在「开门音 + %d 秒」之后起播、门一动掐断"
      % CLOSE_WAIT,
      "排过 %d 次" % len(sim_a.forcedPlans))
check(len(sliced_a) == 1, "★ A 站仍然**有声**（剪头只剩嘀嘀），不是静默", "剪头 %d 次" % len(sliced_a))
if sliced_a:
    err_a = (sliced_a[0][0] + (DUR - sliced_a[0][1]) / 50.0) - stops_a[0]["closed"]
    check(abs(err_a) <= 4, "★★ A 站那个「只嘀嘀」的结尾仍然落在门全关上（差 %.1f tick）" % err_a)

# ---- 7e) 反面样本：**不填**时刻表 ⇒ 第一站没人声（证明这一层真在起作用）----
print()
print("---- 7e) 反面样本：读不到时刻表时（没装 MTR4 / 还没同步完）----")
_BORROW_BAK = BORROW
BORROW = False
hold_b, hold_c = timetable_hold_ticks(20_000), timetable_hold_ticks(30_000)
sim_off, _ = run_multi([hold_a, hold_b, hold_c])          # 不填 timetable ⇒ 读不到
whole_off = [p for p in sim_off.plays if p[2].startswith("整段")]
print("        不填时刻表：整段 %d 次" % len(whole_off))
check(not whole_off,
      "★ 反面样本：读不到时刻表 ⇒ 3 站**一次整段都排不出来**（连 B、C 那种 20/30 秒的站也是）"
      "—— 与修复前用户报的现象一致")
sim_on, stops_on = run_multi([hold_a, hold_b, hold_c],
                             dwell_ms_by_stop=[10_000, 20_000, 30_000])
whole_on = [p for p in sim_on.plays if p[2].startswith("整段")]
by_stop = []
for st in stops_on:
    by_stop.append(any(st["open"] <= p[0] <= st["closed"] + LEAD for p in whole_on))
print("        填上时刻表：整段 %d 次，逐站 %s" % (len(whole_on), by_stop))
check(by_stop == [False, True, True],
      "★★★ 填上时刻表 ⇒ 逐站结果是「A 走强制等待（不静默）/ B 有人声 / C 有人声」"
      "—— 这就是用户问的那个 A=10s、B=20s、C=30s 的答案（口径【1.16】改过一次："
      "A 以前是「无人声」，现在是「强制等待」）", "得到 %s" % by_stop)
check(len(sim_on.forcedPlans) >= 1 and sim_on.forcedPlans[0][1] == 100,
      "★★【1.16】而且走的确实是 **A 站那一扇门**（key=100）—— 不是被 B/C 的长周期串味"
      "（若这里拿到的是 B/C，说明「取最大」那条判据把短站吞掉了）",
      "第一笔 %s" % str(sim_on.forcedPlans[0] if sim_on.forcedPlans else "无"))
for st, has in zip(stops_on, by_stop):
    if not has:
        continue
    p = [x for x in whole_on if st["open"] <= x[0] <= st["closed"] + LEAD][0]
    err = (p[0] + LEAD) - st["closed"]
    check(abs(err) <= 2,
          "★★ B/C 站整段的结尾落在「门全关」上（差 %d tick）" % err)
BORROW = _BORROW_BAK

# ---- 7f) 源码结构：三级来源的**顺序**本身就是设计，必须钉住 ----
print()
print("---- 7f) 源码结构：三级来源的顺序 ----")
_i_table = player.find("MtrDwellAccess.dwellMsAt(")
_i_own = player.find("long ownLearned = learnedCycleTicks")
_i_borrow = player.find("long borrowed = globalCycleTicks")
check(_i_table != -1 and _i_own != -1 and _i_borrow != -1,
      "★ 三个来源在源码里都真的存在（MtrDwellAccess / 本扇门实测 / globalCycleTicks）")
check(re.search(r"if \(fromTimetable > best\)", player) is not None
      and re.search(r"if \(ownLearned > best\)", player) is not None
      and re.search(r"if \(borrowed > best\)", player) is not None,
      "★★★ 三个来源是**取最大**（三个 `> best` 依次比较，索引 %d / %d / %d）"
      % (player.find("if (fromTimetable > best)"), player.find("if (ownLearned > best)"),
         player.find("if (borrowed > best)")))
check(re.search(r"if \(cycle == null\)", player) is None,
      "★★★ 时刻表的读取**不许再被 `if (cycle == null)` 包住** —— 那正是"
      "「改完停站时长还要等一两站才有声音」的根因（LOG2：门一直拿旧的 6400ms 判「塞不下」，"
      "而站台其实已经改成 30 秒了）")
check(player.find("long dwellMs = MtrDwellAccess.dwellMsAt(") < player.find("long best = -1L")
      and _i_own < player.find("long best = -1L") and _i_borrow < player.find("long best = -1L"),
      "★ 三个候选都先取好、再统一比较（不许在比较中途才去查 map）")
check("取「最大」而不是「最可信的那一个」" in read(PLAYER),
      "★★ 源码注释写明了「为什么取最大」（两种估错代价不对称：估短=一定没声音；"
      "估长=起播那一刻门若已关，firePlannedClose 会把计划丢掉且不错放）—— "
      "否则将来有人「顺手改回按可信度排序」就把这个修复废了")
check("MtrDwellAccess.cycleTicksForDwell(dwellMs, travel)" in player,
      "★ 时刻表给的是**周期**（cycleTicksForDwell），不是「停站时长减素材时长」那种半截算法")
check("globalTravelTicks > 0 ? globalTravelTicks : MtrDwellAccess.DEFAULT_TRAVEL_TICKS" in player,
      "★ 门程优先用**实测**学到的（只有还没学到时才退回字节码兜底 80）")
check("public static long cycleTicksForDwell(long dwellMs, long travelTicks)" in access_src
      and "Math.max(dwellMs / 2L, dwellMs - CLOSE_LEAD_MS)" in access_src,
      "★★ MtrDwellAccess 里的算式是 max(D/2, D-4200)（不是只写 D-4200 —— 短停站会算成负数）")
# ★【10-01 订正】原判据锚死字面量 `Math.abs(py - y) > MAX_DY`；当天给 MtrDwellAccess 补
#   MTR3 绑定时，中点坐标多出了 px/pz 兄弟变量 ⇒ 本地变量改名 `py` → `pyv`
#   （源码真实形态：`double pyv = py(mid);` 紧跟 `if (Math.abs(pyv - y) > MAX_DY) return NaN;`）。
#   语义一字未变（同一道纵向窗口、同一个 MAX_DY），只是名字变了 —— 所以断言改成**按语义**匹配
#   `Math.abs(p<名> - y) > MAX_DY`，不再钉死某一个变量名（钉死名字 = 下次 rename 又假红）。
check(bool(re.search(r"Math\.abs\(p[a-z]+ - y\) > MAX_DY", access_src)),
      "★ 认站台时有纵向窗口（不让楼上/楼下的站台被误认成同一个）")
check("dwellMsAt(double x, double y, double z)" in access_src
      and "return -1L" in access_src,
      "★ 读不到就返回 -1（上游原样退回实测学习，行为与加这一层之前完全一样）")

# ---- 7g) 跑两圈：同一条线 A/B/C/A/B/C，第二圈会不会「串味 / 自我强化」----
# ★ 这一段是用户那个问题的**完整形态**：火车来回跑，同一个站在那里停了好几次。
#   要防的是两件相反的事：
#     · A（10 秒）第二圈忽然冒出人声 —— 那是被 B/C 的长周期「串味」或者被上一轮实测带偏；
#     · B/C（20/30 秒）第二圈忽然没了人声 —— 那是被 A 的短周期带偏。
#   判据不是「第一圈对不对」，而是「**两圈结果逐站完全一致**」。
print()
print("---- 7g) 同一条线跑两圈（A/B/C/A/B/C，同站 = 同扇门）：结果必须逐站一模一样 ----")
_RT_DWELLS = [10_000, 20_000, 30_000, 10_000, 20_000, 30_000]
_RT_KEYS = [100, 101, 102, 100, 101, 102]          # 第二圈的 100/101/102 就是第一圈那三扇门
_RT_HOLDS = [timetable_hold_ticks(d) for d in _RT_DWELLS]
sim_rt, stops_rt = run_multi(_RT_HOLDS, dwell_ms_by_stop=_RT_DWELLS, keys_by_stop=_RT_KEYS)
whole_rt = [p for p in sim_rt.plays if p[2].startswith("整段")]
rt_has = []
for st in stops_rt:
    rt_has.append(any(st["open"] <= p[0] <= st["closed"] + LEAD for p in whole_rt))
print("        逐站（A,B,C,A,B,C）整段：%s" % rt_has)
check(len(stops_rt) == 6 and rt_has[:3] == [False, True, True],
      "★ 第一圈逐站 = [A 走强制等待（整段排不出）, B 有人声, C 有人声]",
      "得到 %s" % rt_has[:3])
check(rt_has[3:] == rt_has[:3],
      "★★★ 第二圈与第一圈**逐站完全一致**（%s vs %s）—— 同一扇门第二圈走「本扇门实测」，"
      "学到的是它自己那一站的周期，不会被别的站带偏" % (rt_has[3:], rt_has[:3]),
      "得到 %s" % rt_has)
# 同一扇门两圈学到的周期必须同一条线（不能因为反复停站而漂移）
for key, dwell_ms in zip(_RT_KEYS[:3], _RT_DWELLS[:3]):
    learned = sim_rt.learnedCycleTicks.get(key)
    parsed = timetable_cycle_ticks(dwell_ms)
    check(learned is not None and abs(learned - parsed) <= _JUMP_BIAS + 1,
          "★ 门 %d（停站 %dms）两圈跑完，实测学到的周期 %s tick 仍贴着解析值 %d tick"
          % (key, dwell_ms, learned, parsed))
# A 站在两圈里都必须「有声但只是嘀嘀」—— 不能静默，也不能冒出人声
for i in (0, 3):
    st = stops_rt[i]
    sliced = [p for p in sim_rt.plays
              if st["open"] <= p[0] <= st["closed"] + LEAD and p[2].startswith("剪头")]
    check(len(sliced) == 1,
          "★★ A 站第 %d 次停站仍然「有声音（剪头只剩嘀嘀）」且**恰好一次**" % (1 if i == 0 else 2),
          "剪头 %d 次" % len(sliced))
# 没有一扇门的播放会拖过它自己那一次的门全关（掐断计数必须为 0）
check(not sim_rt.stopped,
      "★★ 跑两圈全程没有触发一次「整段拖过门全关」的掐断 —— 周期是每站自己的，本来就不会拖",
      "掐断 %d 次" % len(sim_rt.stopped))

# ----------------------------------------------------------------------
# 8) 【1.15 · 第十轮】「改成 30 秒也没用」—— 第一道闸门不许再静默 + 认站台改线段 + 交叉核对
# ----------------------------------------------------------------------
# 用户报：有一次不知道设了什么就有人声了，改了一些东西又只剩嘀嘀，**停靠时间改成 30 也没用**。
# ★ 「30 秒也没用」这一句本身就排除了「停站不够」（30 秒的窗口 28.8 秒 >> 素材 10.8 秒），
#   所以问题一定在**与停站时长无关**的那道闸门上。这一节把「那道闸门以后不许再静默」
#   以及配套的三处可诊断性改动一起钉住。
print()
print("---- 8a) 第一道闸门（announce / splitMs / duration）不许再静默 return ----")
SCREEN = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client",
                      "PsdToneSetupScreen.java")
screen = strip_comments(read(SCREEN)) if os.path.exists(SCREEN) else ""

GATE = "!tone.announce() || tone.splitMs() <= 0 || duration <= 0"
check(GATE in player, "★ 闸门判据本身没被改掉（%s）" % GATE)
_i_gate = player.find(GATE)
_i_cycle = player.find("long dwellMs = MtrDwellAccess.dwellMsAt(")
check(0 <= _i_gate < _i_cycle,
      "★★★ 闸门在「查周期」**之前**（%d < %d）—— 这就是「改停站时长完全没用」的原因："
      "还没算周期就已经 return 了" % (_i_gate, _i_cycle))

# 三条原因各要有自己的日志（以前是一条静默 return 全吃掉）
for _need, _what in (
        ("这一轮**不会有人声播报**", "闸门被拦下这件事本身"),
        ("default-s", "点名「默认（短）」—— 它和「默认」用同一段素材，光看名字分不出来"),
        ("石斧界面把它换成", "直接告诉用户该改哪里"),
        ("没有「播报 + 嘀嘀」两段结构", "原因②：素材没有播报段"),
        ("时长量不到", "原因③：量不到素材时长")):
    check(_need in player, "★ 闸门日志里有「%s」（%s）" % (_need, _what))

check("!tone.announce()" in player and "tone.splitMs() <= 0" in player,
      "★ 两把锁分别判定（announce 与 splitMs 是**两件独立的事**，不许合并成一句）")
check("按设计只播嘀嘀" in player,
      "★ 日志把「按设计只播嘀嘀」说清楚（免得用户以为是坏了）")

print()
print("---- 8b) 跨门借用要**喊出来**（那是「改这一站停站没用」的真正现场）----")
_i_borrow = player.find("long borrowed = globalCycleTicks")
check(_i_borrow != -1, "★ 第三级「跨门借用」还在（读不到能用的时刻表时的兜底）")
check("改这一站的停站时间不会有任何效果" in player,
      "★★★ 借用那一刻打 WARN 明说「改这一站的停站时间不会有任何效果」—— "
      "以前只有一行普通 info 写着「跨门借来的」，用户根本联系不到「我改了停站」")
check("if (winner == 2)" in player and player.find("if (winner == 2)") > player.find("long borrowed = globalCycleTicks"),
      "★ 那条 WARN 只在**跨门值真的赢了**的时候打（不许只要借过就喊，否则变成噪音）")
check("但{}证明这一站的门开得更久" in player,
      "★★★【第十二轮】时刻表偏短、被实测顶掉时也要喊出来 —— 这就是用户这一轮的现场"
      "（时刻表说 10000ms，实测 22~26 秒），喊出来用户才知道「是站台数据没同步，不是我没设对」")

print()
print("---- 8c) 时刻表 × 实测 交叉核对（站台数据与实际不符）----")
_m = re.search(r"TIMETABLE_DISAGREE_TICKS\s*=\s*(\d+)L", player)
DISAGREE = int(_m.group(1)) if _m else -1
check(DISAGREE == 60, "★ 判「对不上」的阈值 = 60 tick", "得到 %s" % DISAGREE)
check(abs(DISAGREE * TICK / 1000.0 - 3.0) < 1e-9,
      "★ 也就是 3 秒：几十毫秒级的抖动不误报，「成秒」级别的偏差立刻喊出来")
check("timetableCycleTicks.put(door.key(), fromTimetable)" in player,
      "★ 读过时刻表就把预测值记下来（第十二轮起是**每次开门都读**，所以这段成了常规路径）")
check("timetableCycleTicks.remove(key)" in player,
      "★ 实测值出来时把它取出来对一次（用完即删，只对这一轮有意义）")
check("侧线设置「晚点缩短停站时间」会把停站砍短" in player,
      "★★★ 对不上就 WARN 并**点名原因**（头号：改过停站时长但客户端那一份还没同步；"
      "其次：侧线「晚点缩短停站时间」/「早到延长停站」/ 被信号憋住）")
check("timetableDisagreeWarned" in player and "timetableAgreeLogged" in player,
      "★★【第十二轮】两条结论都**每扇门只喊一次** —— 因为时刻表现在是每次开门都读，"
      "不去重就会把日志刷满（用户反而看不到真正的新信息）")
check(player.find("timetableCycleTicks.remove(key)")
      > player.find("learnedCycleTicks.put(key, cycle)"),
      "★ 必须先学到**这一轮实测值**、再去跟时刻表的预测对（顺序不能反）")
check("从下一轮起按「时刻表与实测里更大的那个」排" in player,
      "★ 告诉用户：下一轮会自动按两者中更大的那个排，不用手动做什么")

print()
print("---- 8d) 认站台：从「到中点」改成「到中轴**线段**」----")
_m = re.search(r"MAX_LATERAL\s*=\s*([\d.]+)", access_src)
LATERAL = float(_m.group(1)) if _m else -1.0
_m = re.search(r"MAX_HORIZONTAL\s*=\s*([\d.]+)", access_src)
HORIZ = float(_m.group(1)) if _m else -1.0
check(LATERAL == 4.0, "★ MAX_LATERAL = 4.0 格（门到站台中轴线的垂直距离）", "得到 %s" % LATERAL)
check(HORIZ == 64.0, "★ MAX_HORIZONTAL = 64.0 格（退路模式的**中点**距离上限）", "得到 %s" % HORIZ)
check(LATERAL < HORIZ,
      "★ 线段模式的容差必须**远小于**中点模式 —— 宁可「读不到」（会退回实测学习）"
      "也不要「安静地读到隔壁站台」")
for _need in ("pointToSegmentXZ", "segmentXZ", "dwellOf", "midText"):
    check(_need + "(" in access_src, "★ %s 存在" % _need)
check('fieldInHierarchy(savedRail, "position1")' in access_src
      and 'fieldInHierarchy(savedRail, "position2")' in access_src,
      "★ 站台两端点是**可选**绑定（拿不到就用中点模式，不让优化把功能拖挂）")
check(re.search(r"long dwell = dwellOf\(platform\);\s*if \(dwell > 0L\)", access_src) is not None,
      "★★ 优先挑**停站时长有效（> 0）**的站台 —— MTR 里没配过停站时间的站台读出来是 0，"
      "拿它算周期只会算出 -1，等于白认一次亲")
check("lastPick" in access_src,
      "★ 只在「选中的站台变了」时打日志（既不刷屏，又能看见换站了）")

# ★ 数值对撞：把 pointToSegmentXZ 照搬到 Python，验它真的是「到线段」而不是「到中点/到直线」
def point_to_segment_xz(px, pz, ax, az, bx, bz):
    vx, vz = bx - ax, bz - az
    len2 = vx * vx + vz * vz
    t = 0.0 if len2 <= 1e-9 else ((px - ax) * vx + (pz - az) * vz) / len2
    t = max(0.0, min(1.0, t))
    dx = px - (ax + t * vx)
    dz = pz - (az + t * vz)
    return (dx * dx + dz * dz) ** 0.5


check(abs(point_to_segment_xz(50, 0, 0, 0, 100, 0)) < 1e-9,
      "★ 点在线上 ⇒ 距离 0")
check(abs(point_to_segment_xz(50, 3, 0, 0, 100, 0) - 3.0) < 1e-9,
      "★ 垂直偏离 3 格 ⇒ 距离 3")
check(abs(point_to_segment_xz(130, 0, 0, 0, 100, 0) - 30.0) < 1e-9,
      "★★ 超出端点 ⇒ 算到**端点**的距离 30（不是到无限直线的 0 —— 否则站台延长出去的部分"
      "会把根本不在站台上的门也吸进来）")
check(abs(point_to_segment_xz(7, 4, 5, 5, 5, 5) - (2.0 ** 2 + 1.0 ** 2) ** 0.5) < 1e-9,
      "★ 退化成一点（两端点重合）⇒ 算到该点的距离（%s），不会除以 0" % round(5 ** 0.5, 3))

# ★★★ 关键性质：门站在站台**这一端**时，线段距离是 0，而中点距离是站台长度的一半
_SEG = point_to_segment_xz(200, 0, 0, 0, 200, 0)
_MID = ((200 - 100) ** 2 + 0) ** 0.5
print("        站台 (0,0)-(200,0)，门在 (200,0)：到线段 %.1f 格，到中点 %.1f 格" % (_SEG, _MID))
check(_SEG < 1e-9 < _MID,
      "★★★ 门站在 200 格长站台的**端点**上：到线段 0 格（认对），到中点 %.0f 格 —— "
      "★ 若按中点挑「最近站台」，这时它很可能去认**对面方向**那个站台（中点更近）"
      "⇒ 安静地读到隔壁站台的停站时长，正是「停站时长怎么改都没用」的形态" % _MID)

print()
print("---- 8e) 界面把结论直接写出来（不让用户去猜档位）----")
check("psdVoiceVerdict" in screen, "★ 石斧界面里有 psdVoiceVerdict 这个判据")
check('!"close".equals(which)' in screen,
      "★ 结论行只在**关门页**出现 —— 开门端的 dooropen.ogg 本来就没有播报段，"
      "在开门页写「不会有人声」只会吓人")
check('"默认（长）"' in screen and '"默认（短）"' in screen,
      "★【1.23】关门页右列默认行 = 默认（长）/默认（短）—— 名字本身说明长/短，替代 1.15 的结论行")
check('PSD_TONE_BUILTIN_CLOSE_S' in screen and '"close".equals(which)' in screen,
      "默认（短）= default-s（同一段关门素材、只播嘀嘀，且只在关门页出现）")
# 界面判据必须和播放端**同一套**，否则界面说会播、实际不播，比不说更坏
check("isPsdBuiltinShort" in screen and "bundledAnnounceSplitMs" in screen,
      "★★ 界面用的是播放端那套内置判据（isPsdBuiltinShort + bundledAnnounceSplitMs）")
check("customAnnounceSplitMs" in screen,
      "★★ 自定义素材也走**播放端那个**检测（customAnnounceSplitMs），不另写一份猜测")
check(screen.find("!EscalatorSpeedData.isPsdBuiltinShort(id)") < screen.find('return "★ 会播人声'),
      "★ 先算 announce/split，再给结论（顺序不能反）")

# ----------------------------------------------------------------------
# 9) 【1.15 · 第十二轮】「改了停留时间也没用」—— 把「改的是哪一条」变成可核对的证据
# ----------------------------------------------------------------------
# 现场（LOG/latest.log，2026-09-22 22:28:41）：
#   用户把某处「停留时间」改成 30 秒，而那一扇门实测仍然读到 10000ms（= MTR 默认值），
#   于是周期 8800ms < 素材 10806ms ⇒ 这一轮没人声。**代码的判断是对的，错的是数据**：
#   30 秒根本没落在「这扇门认到的那条站台」上。
#   可原来只打一条「认到站台 @x,y,z」，用户拿着一组坐标没法跟仪表盘里的站台对上号 ⇒ 只能反复试。
#   这一节钉住三样「拿证据」的东西：整张站台表、被上限挡掉的那个、以及最小可行停留时长。
print()
print("---- 9a) 站台清单：把**每一个**站台都摆出来（车站名 + 坐标 + 停留时长）----")
check("dumpPlatforms(" in access_src, "★ 有 dumpPlatforms（一次把整张表打出来）")
check("dumpedPlatformSignature" in access_src,
      "★★★【第十二轮】清单的自节流按**内容签名**（坐标+停留时长）而不是数量 —— "
      "按数量去重的话，「改了停留时间但数量还是 6」就永远不会重打，"
      "日志会一直停在「全是 10000ms（默认）」那个印象上")
check("MTR 站台数据**变了**" in access_src,
      "★★ 数据变了要**显式说一句**（用户最需要知道的就是「改动到底有没有到客户端」）")
check('fieldInHierarchy(savedRail, "area")' in access_src
      and "NameColorDataBase" in access_src,
      "★★ 站台 → 所属**车站** 是**可选**绑定（SavedRailBase.area + NameColorDataBase.getName）——"
      "「认到的是**哪个车站**的站台」比一组坐标好认得多")
check("stationNameOf(" in access_src, "★ stationNameOf 存在")
check("10000L" in access_src and "MTR 默认值，从没改过" in access_src,
      "★★★ 清单里把 10000ms 标成「MTR 默认值，从没改过」——"
      "这样「全表都是 10000」= 改动根本没存进世界，一眼可分")
check("ldc2_w 10000l" in read(ACCESS),
      "★ 注释写明了 10000 的出处（PlatformSchema 构造函数 ldc2_w 10000l）——"
      "否则将来有人「顺手改成别的数」就把这条判据废了")
check("长 %.0f 格" in access_src,
      "★ 每条都带站台长度（两端点距离）—— 用来判断它是不是你改的那一条")

print()
print("---- 9b) 被上限挡掉的最近站台也要报出来 ----")
check("bestRejected" in access_src,
      "★★★ 记录「离这扇门最近、却被认亲上限挡在外面」的那个站台")
check(access_src.count("bestRejectedDist = distance") == 2,
      "★ 线段模式与中点模式**两条路**都要记（少一条就会出现「某些布局下查不出原因」）",
      "出现 %d 次" % access_src.count("bestRejectedDist = distance"))
check("在认亲上限" in access_src and "没被选中" in access_src,
      "★★ 日志明说「它在认亲上限之外，没被选中」——"
      "这正是「我改了 30 秒却读到 10000ms」的现场（改到了隔壁那条站台）")

print()
print("---- 9b2) 两条站台都够得着时，把「次近」也报出来 ----")
check("secondUsable" in access_src,
      "★★★ 记「次近的有效站台」—— 岛式站台 / 门卡在两条之间时，我们只认**最近的那条**，"
      "而用户改的可能是另一条；那种情形**不触发**「被上限挡掉」，得单独记")
check(access_src.count("secondUsableDist = distance") == 1
      and access_src.count("secondUsableDwell = bestUsableDwell") == 1,
      "★ 两条更新路径都在（最近被顶下去时降级为次近 + 更远但比次近近时替换次近）")
check(re.search(r"if \(dwell > 0L\) \{", access_src) is not None,
      "★ 「次近」只在**有效停留时长**的候选里挑（跟主选择同一套判据）")
check("还有一条够得着的站台" in access_src and "两条都够得着时只按距离挑" in access_src,
      "★★ 日志把「两条都够得着、只按距离挑」说清楚，并点名两边的坐标与停留时长")

print()
print("---- 9c) 直接把「这一站至少要设多少秒」算出来（同一套公式反解）----")


def min_dwell_for_material(material_ms, travel):
    """照搬 Java 的 MtrDwellAccess.minDwellForMaterialMs。"""
    if material_ms <= 0:
        return -1
    lead = (material_ms + 49) // 50
    d = 5200 + 50 * (lead + 1 - travel)
    if d < 1000:
        d = 1000
    d = ((d + 999) // 1000) * 1000
    while timetable_cycle_ticks(d, travel) < lead + 1:
        d += 1000
        if d > 600_000:
            return -1
    return d


check("minDwellForMaterialMs(" in access_src, "★ MtrDwellAccess.minDwellForMaterialMs 存在")
check("minDwellForMaterialMs(duration, travel)" in player,
      "★★ 播放器在「塞不下」那一支**真的用了它**（不是又只报两个数让用户自己算）")
check("想让**整段**（人声+嘀嘀）都播完：把这一站的停留时长设到" in player,
      "★★★ 日志给出**可执行的数字**（【1.16】口径改过：以前是「想听到人声」，"
      "现在塞不下也能听到人声了，所以要设到多少说的是「想把**整段**都播完」）")

for _travel, _tag in ((DEFAULT_TRAVEL_TICKS, "门程按默认兜底 %d tick" % DEFAULT_TRAVEL_TICKS),
                      (29, "门程按实测学到的 29 tick")):
    _need = min_dwell_for_material(DUR, _travel)
    print("        %-28s ⇒ 至少 %dms（%d 秒）" % (_tag, _need, _need // 1000))
    check(_need > 0, "★ 算得出（%s）" % _tag)
    check(timetable_cycle_ticks(_need, _travel) > LEAD,
          "★★ 用它算出来的周期（%d tick）**确实**塞得下素材（%d tick）"
          % (timetable_cycle_ticks(_need, _travel), LEAD))
    check(timetable_cycle_ticks(_need - 1000, _travel) <= LEAD,
          "★★★ 而且**再少 1 秒就不够** —— 它是**最小**可行值（不是随手写个 30 秒）")

# 现场数字：素材 10806ms 时，门程按默认 80 tick ⇒ 13000ms；按实测 29 tick ⇒ 15000ms
check(min_dwell_for_material(DUR, DEFAULT_TRAVEL_TICKS) == 13000,
      "★★ 现场（素材 %dms / 门程兜底 80 tick）⇒ 13000ms = 13 秒 —— 与第八轮报给用户的口径一致" % DUR)
check(min_dwell_for_material(DUR, 29) == 15000,
      "★★★ 同一个素材、门程按**实测 29 tick** ⇒ 15000ms = **15 秒** ——"
      "所以「设 13 秒」在某些站上仍然不够，必须让程序自己算，不能给一个死数")

check(all(min_dwell_for_material(DUR, t) <= min_dwell_for_material(DUR, t - 5) for t in (40, 50, 60)),
      "★ 门程越长 ⇒ 要求的停留时长越长（非递减；整秒量化所以允许相等）")
check(min_dwell_for_material(0, 80) == -1, "★ 素材时长 ≤ 0 ⇒ -1（不参与判断）")
check(min_dwell_for_material(DUR, 300) > 0, "★ 极端门程也**不死循环**（照样收敛到一个正数）")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
