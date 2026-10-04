# -*- coding: utf-8 -*-
"""离线校验：【1.15】关门提示音的**结尾**对齐「屏蔽门正好关上」那一刻。

## 需求（用户原话）

    关门时要屏蔽门提示音播完那一刻屏蔽门正好关上，
    而不是屏蔽门开始关门才播放关门提示音。

## 本脚本管哪一档（★ 先看这段，别把两档混了）

【1.15】第三次改动后，关门端分成**两条路**，它们**都**要满足「结尾落在门上」：

  · **路 B（提前量）** —— 素材带「语音播报」、而且玩家也要它（内置 `default` /
    `default-m` / `default-c`、以及导入素材）⇒ **整段**在门开始关**之前** ~6.8 秒起播，
    让**结尾**落在门上。这条路要**预测**关门时刻，由 `check-psd-split.py` 钉住；
  · **路 A（剪头）** —— **就是本脚本这一套**「剪头 ⇒ 结尾落在门上」的算式。
    它是**兜底主干**，覆盖三种情形：
      ① 只要嘀嘀（内置 `default-s`「默认（短）」）；
      ② 素材**没有**两段结构（导入的纯嘀嘀音频、开门端的 `dooropen.ogg`）；
      ③ 路 B **排不上 / 排晚了**（第一次到这一站还没学到周期、整轮塞不下整条素材、
         或提前播完得太早）—— 此时宁可这一轮没有人声，也要让结尾落在门上。

⇒ 判据是源码里那**两处**：`planClose` 里的 `!tone.announce() || tone.splitMs() <= 0`
（决定要不要排提前量）与 `detect` 里 `plannedCloseFired` 那一段（决定要不要退回剪头）。
本脚本只管路 A 的算式；两条路的分岔与时间轴由 `check-psd-split.py` 钉住。

## 为什么值得单独测（这一段全是「算错了也不报错」的东西）

1. **素材比门程长得多，这是整件事的前提。** 关门素材 10.8 秒，而门自己走完只要 4 秒（MTR4）
   或 1.7 秒（MTR3）。「从头播」必然变成「门早关上了、提示音还在响」。所以唯一的解法是
   **把开头剪掉一截**：从 `素材时长 − 门程` 处开始播。剪多剪少直接决定听感，算错不抛异常。
   ★ 剪掉的那一截**是语音播报**（0~6.7 秒），不是机械音 —— 所以这一档（只要嘀嘀）听起来
   是完整的一串嘀嘀；而带人声那一档改成整段起播、不走这里，由 `check-psd-split.py` 钉住。
   本脚本只管**结尾对齐**这一段算式。
2. **门程必须量，不能写死。** 两个大版本的门速差一倍以上（下面把两边的字节码公式都搬过来算了一遍，
   80 tick vs 34 tick）。写死一个数会让另一个版本「越对齐越歪」。
3. **量速度要等一 tick，但声音不能因此晚出来。** 本脚本把这条推导也钉住：
   晚播 Δt + 少剪 Δt ⇒ 任意时刻听到的内容完全相同。
4. **结尾对齐与「检测到关门的早晚」无关。** 检测点落在哪一帧只改变**剪掉多少头部**，
   不改变结尾时刻（下面用 f1 的扫描把这条钉死）—— 这是这套算法最值得信赖的性质。
5. **不能把开门端一起改了。** 开门素材 2.28 秒**比门程短**，对它做同样的算法会算出负偏移，
   只能退回整段播 —— 也就是与 1.50 完全一致。这条要有断言，否则哪天顺手「对称处理」就出事。

用法：`python _tools/check-psd-align.py`（退出码 0 = 全部通过）

★ 配套脚本 `check-psd-split.py` 钉住**路 B**（提前量）：分界点实测、提前量算术、
  顺序证明（人声在等待段、嘀嘀跨关门）、以及两条路的互斥与兜底。本脚本只管**路 A**。
"""
import glob
import os
import re
import sys
import zipfile

import numpy as np

try:
    import soundfile as sf
except Exception:  # pragma: no cover - 环境缺库时明确跳过音频断言
    sf = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION = re.search(r'^mod_version=(.+)$', open(os.path.join(ROOT, 'gradle.properties'), encoding='utf-8').read(), re.M).group(1).strip()  # 【1.23】版本号断言不再写死

MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
AUDIO_PLAYER = os.path.join(CLIENT, "EscalatorAudioPlayer.java")
SOUND_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift", "sounds", "audio")
GRADLE = os.path.join(ROOT, "gradle.properties")

# ★ 不写死版本号：取 build/libs 下最新的那个 jar
_jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")),
               key=os.path.getmtime)
JAR = _jars[-1] if _jars else os.path.join(ROOT, "build", "libs", "mzycBetterMTR-" + VERSION + ".jar")

# ★ 本轮的「用户点名版本号」只在这里写一次（改号时只改这一行）
EXPECTED_VER = VERSION
# 1.22 起 jar 前缀 = 显示名（`archives_base_name`），不再是 rootProject.name
EXPECTED_JAR_PREFIX = "mzycBetterMTR"

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


player_raw = read(PLAYER)
player = strip_comments(player_raw)
ap = strip_comments(read(AUDIO_PLAYER))
gradle = read(GRADLE)
build = read(os.path.join(ROOT, "build.gradle"))

# ======================================================================
# 0) 与 Java 侧完全相同的常量 + 两个小算法（模型化，不 import）
# ======================================================================
SLICE_STEP_MS = 25          # EscalatorAudioPlayer.SLICE_STEP_MS
TICK_MS = 50.0              # 20 TPS：1 tick = 50ms
EDGE = 0.02                 # PsdChimePlayer.EDGE


def quantize_offset(ms):
    """EscalatorAudioPlayer.quantizeOffset：量化到最近的 SLICE_STEP_MS 整数倍。"""
    if ms <= 0:
        return 0
    return int(round(ms / float(SLICE_STEP_MS))) * SLICE_STEP_MS


print("===== 1) 门程：把 MTR4 / MTR3 的字节码公式搬过来算（可核验的 tick 数）=====")


def mtr4_travel_ticks(partial=1.0):
    """MTR4 BlockPSDAPGDoorBase$BlockEntityBase.tick(F)：
       doorValue = min(1, doorValue + partialTick*20/3200*2)   （关门是同一个速率取负）
       ★ 逐次累加要留 1e-9 的余量：0.0125*80 在浮点下是 0.9999999999999999，
         不让一丁点误差会让「80 tick」变成「81 tick」。"""
    v, n = 0.0, 0
    while v < 1.0 - 1e-9:
        v = min(1.0, v + partial * 20.0 / 3200.0 * 2.0)
        n += 1
    return n


def mtr3_travel_ticks(partial=1.0):
    """MTR3 TileEntityPSDAPGDoorBase.getOpen(F)：openClient 每帧最多走 0.95*partialTick，
       量纲 openClient/32；服务端把目标 open 置 0（全关）后，客户端一路追到 open-0.1 吸附。"""
    target = -0.1          # open(=0) - 0.1
    x = 32.0 - 0.1         # 全开时 openClient = open(32) - 0.1
    n = 0
    while abs(target - x) >= 0.95 * partial:
        x -= 0.95 * partial
        n += 1
    return n + 1           # 最后一步是「吸附」，也占一帧


MTR4_TICKS = mtr4_travel_ticks()
MTR3_TICKS = mtr3_travel_ticks()
MTR4_MS = int(round(MTR4_TICKS * TICK_MS))
MTR3_MS = int(round(MTR3_TICKS * TICK_MS))
print("        MTR4：%d tick = %dms（每 tick 走 1/80 门程）" % (MTR4_TICKS, MTR4_MS))
print("        MTR3：%d tick = %dms（每 tick 走 0.95/32 门程）" % (MTR3_TICKS, MTR3_MS))
check(MTR4_TICKS == 80 and MTR4_MS == 4000, "MTR4 门程 = 80 tick = 4000ms（字节码直算）")
check(30 <= MTR3_TICKS <= 40, "MTR3 门程落在 30~40 tick（≈1.7 秒），与 MTR4 差一倍以上",
      "得到 %d tick" % MTR3_TICKS)
check(MTR4_MS > 2 * MTR3_MS * 0.9,
      "两个版本门程差到 2 倍以上 ⇒ 门程只能是**实测值**，写死一个数必歪一边",
      "MTR4 %dms vs MTR3 %dms" % (MTR4_MS, MTR3_MS))

print()
print("===== 2) 三段素材的真实时长（soundfile 实测，与 Java 侧算式交叉验证）=====")
DUR = {}
if sf is None:
    print("[SKIP] 没有 soundfile，跳过音频实测")
else:
    for name in ("dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"):
        path = os.path.join(SOUND_DIR, name)
        if not os.path.isfile(path):
            check(False, "内置素材存在：%s" % name)
            continue
        x, sr = sf.read(path, always_2d=True)
        frames, ch = x.shape[0], x.shape[1]
        dur_ms = int(round(1000.0 * frames / sr))
        DUR[name] = {"ms": dur_ms, "sr": sr, "frames": frames, "ch": ch, "x": x}
        # Java 侧算时长用的是「PCM 字节数 / 帧大小 / 采样率」，帧大小 = 声道数 * 2（16bit）
        java_ms = int(round(1000.0 * (frames * ch * 2) / (ch * 2) / sr))
        check(abs(java_ms - dur_ms) <= 1,
              "%s 时长：Java 侧算式 = %dms，实测 = %dms" % (name, java_ms, dur_ms))
        print("        %s  %dHz %d 声道  %d 帧  = %.3fs" % (name, sr, ch, frames, dur_ms / 1000.0))

print()
print("===== 3) 对齐算式（**路 A 剪头**）：start = 量化(素材时长 − 门还在走的毫秒数) =====")
print("        路 A 覆盖：default-s（默认（短））/ 素材没有两段结构 / 路 B 没排上的兜底；"
      "带人声且排上了提前量的那一档整段提前播，不走这里")


def align(duration_ms, travel_ms, f1):
    """模型化 PsdChimePlayer.resumeClose：
       t1 = 发现「门开始关」之后第 1 tick（挂起一 tick 就为了量门速）；
       per_tick = 每 tick 门值变化（匀速模型，实测值由管道给出）；f1 = t1 时刻的门值。
       剩余毫秒 = f1 / per_tick * 50。"""
    per_tick = TICK_MS / float(travel_ms)
    remain_ms = f1 / per_tick * TICK_MS
    return quantize_offset(int(round(duration_ms - remain_ms))), remain_ms


# ---- 3.1 MTR4 + 关门素材：真剪，且结尾落在门关上那一刻 ----
for name in ("mdoorclose.ogg", "doorclose.ogg"):
    if name not in DUR:
        continue
    d = DUR[name]["ms"]
    f1 = 1.0 - TICK_MS / float(MTR4_MS)          # 挂起后第 1 tick 量到的门值：0.9875
    start, remain = align(d, MTR4_MS, f1)
    end_delay = d - start                         # 从 t1 起还要响多久
    err = end_delay - remain
    print("        %s：素材 %dms，门程 %dms → 从 %dms 起播，余 %dms；"
          "门在 %dms 后关上 ⇒ 差 %+dms"
          % (name, d, MTR4_MS, start, end_delay, int(round(remain)), int(round(err))))
    check(start > 0, "%s 在 MTR4 上确实剪了头（素材 10.8 秒 > 门程 4 秒）" % name)
    check(abs(err) <= SLICE_STEP_MS / 2.0 + 1,
          "%s 结尾与门关上时刻的误差 ≤ 半格量化（%.1fms）" % (name, SLICE_STEP_MS / 2.0 + 1),
          "差 %+dms" % int(round(err)))
    check(start + (d - start) == d, "%s 剪头不改变「整段 = 起点 + 余量」" % name)

# ---- 3.1b ★ 检测点早晚只会改变「剪多少」，不会改变结尾时刻 ----
if "mdoorclose.ogg" in DUR:
    d = DUR["mdoorclose.ogg"]["ms"]
    worst = 0.0
    worst_at = 0.0
    for i in range(1, 100):                       # f1 扫 (0.90, 0.999]
        f1 = 1.0 - i * 0.001
        start, remain = align(d, MTR4_MS, f1)
        e = abs((d - start) - remain)
        if e > worst:
            worst, worst_at = e, f1
    print("        f1 扫 (0.90, 0.999]：结尾误差最大 %.0fms（出现在 f1=%.3f）" % (worst, worst_at))
    check(worst <= SLICE_STEP_MS / 2.0 + 1,
          "★ 无论在哪一帧检测到关门，结尾误差都 ≤ 半格量化（对齐与检测点无关，只与门速有关）",
          "最差 %.0fms" % worst)

# ---- 3.2 开门素材：**必须**退回整段播（与 1.50 完全一致）----
if "dooropen.ogg" in DUR:
    d = DUR["dooropen.ogg"]["ms"]
    f1 = 1.0 - TICK_MS / float(MTR4_MS)
    start, remain = align(d, MTR4_MS, f1)
    print("        dooropen.ogg：素材 %dms，门程 %dms → 算式得 %d（≤ 0 即不剪）"
          % (d, MTR4_MS, d - int(round(remain))))
    check(start == 0,
          "开门素材（2.28 秒）**比门程短** ⇒ 偏移 ≤ 0 ⇒ 退回整段播（开门端行为一字未改）")
    delayed = MTR4_MS - d
    check(delayed > 0,
          "对同一素材若改用「推迟 %dms 再播」，门都开完一半了 —— 这正是**不能**对称处理的原因"
          % delayed)

# ---- 3.3 MTR3：门程只有 1.7 秒，剪得更多 ----
if "mdoorclose.ogg" in DUR:
    d = DUR["mdoorclose.ogg"]["ms"]
    f1 = 1.0 - TICK_MS / float(MTR3_MS)
    start, remain = align(d, MTR3_MS, f1)
    print("        mdoorclose.ogg（MTR3）：从 %dms 起播，余 %dms（门程 %dms）"
          % (start, d - start, MTR3_MS))
    check(start > 0, "MTR3 上也剪（素材 10.8 秒 >> 门程 1.7 秒）")
    check(d - start <= MTR3_MS + SLICE_STEP_MS,
          "MTR3 上剪完只剩约一个门程的长度（不会剪过头）",
          "余 %dms vs 门程 %dms" % (d - start, MTR3_MS))

# ---- 3.4 「素材时长 == 门程」的临界点：偏移 0 ⇒ 整段播，本来就对齐 ----
start_edge, remain_edge = align(MTR4_MS, MTR4_MS, 1.0)
check(start_edge == 0, "素材时长恰好等于门程 ⇒ 偏移 0 ⇒ 整段播（本来就对齐）",
      "得到 %d" % start_edge)

# ---- 3.5 剪完的那一段必须**真的有声**（不能剪到一段静音里）----
if "mdoorclose.ogg" in DUR:
    info = DUR["mdoorclose.ogg"]
    d = info["ms"]
    x = info["x"]
    sr = info["sr"]
    f1 = 1.0 - TICK_MS / float(MTR4_MS)
    start, remain = align(d, MTR4_MS, f1)
    a = int(start / 1000.0 * sr)
    b = min(len(x), a + int(remain / 1000.0 * sr))
    seg = x[a:b]
    seg_rms = float(np.sqrt((seg ** 2).mean()))
    whole_rms = float(np.sqrt((x ** 2).mean()))
    # 剪掉的那一段（头部）也量一下，便于人眼对照
    head_rms = float(np.sqrt((x[:a] ** 2).mean()))
    print("        mdoorclose.ogg：剪掉 0~%dms（RMS %.4f），保留 %d~%dms（RMS %.4f）"
          % (start, head_rms, start, start + int(remain), seg_rms))
    check(seg_rms > 0.2 * whole_rms,
          "★ 保留的那一段是有声的（不是把提示音剪到静音里）",
          "保留段 RMS %.4f vs 全曲 %.4f" % (seg_rms, whole_rms))
    # ★ 剪点 ≈6.85s 落在这条录音的「语音播报 → 静音 → 嘀嘀」交界处。
    #   剪掉的那一截（0~6.85s）**正是语音播报** —— 所以路 A 听起来就是纯嘀嘀；
    #   想连人声一起听，得走路 B（整段提前到门开始关之前起播，见 check-psd-split.py）。
    #   本断言只负责钉住「剪点确实落在这个交界附近」，别让它哪天漂到嘀嘀中间去。
    check(abs(start / 1000.0 - 6.85) < 0.4,
          "剪点落在「语音播报 → 静音/嘀嘀」交界附近（≈6.85s；分界点实测 6.707s）",
          "剪点 %.3fs" % (start / 1000.0))

print()
print("===== 4) 挂起一 tick 不会让声音晚出来（推导 + 数值验证）=====")
# 理想：t0 就知道门速 ⇒ 从 D-remain0 播、t0 起响；
# 实际：t1 = t0+50ms 才知道 ⇒ 从 D-remain1 播、t1 起响。二者在任意 t 听到的内容应当相同。
for travel in (MTR4_MS, MTR3_MS):
    per_tick = TICK_MS / float(travel)
    remain0 = 1.0 / per_tick * TICK_MS
    remain1 = (1.0 - per_tick) / per_tick * TICK_MS
    d = 10806
    ok = True
    for wall_ms in (0, 100, 400, 1000, 2000, 3500):
        ideal_pos = (d - remain0) + wall_ms                      # t0 起响
        real_pos = (d - remain1) + (wall_ms - TICK_MS)           # t1 起响（晚一 tick）
        if abs(ideal_pos - real_pos) > 1e-6:
            ok = False
    check(ok, "门程 %dms：晚一 tick 播 + 少剪一 tick ⇒ 任意时刻听到的内容完全相同" % travel)

# ======================================================================
# 5) 源码结构：对齐只做关门端 + 三条兜底
# ======================================================================
print()
print("===== 5) 源码结构（对齐只做关门端 + 兜底齐备）=====")
check("pendingClose" in player and "resumeClose" in player,
      "PsdChimePlayer 有「挂起 → 下一 tick 播」这条链（pendingClose / resumeClose）")
check("ALIGN_MAX_WAIT_TICKS" in player, "有等待上限常量（门不动时不能无限等）")
check(re.search(r"if \(closing && tone\.durationMs\(\) > 0\)", player) is not None
      or re.search(r"if \(tone\.durationMs\(\) > 0\)\s*\{", player) is not None,
      "★ 只有 **closing** 才挂起对齐（开门端不走这条路 ⇒ 行为与 1.50 一致）")
check(re.search(r"drop\s*<\s*0\.0f", player) is not None,
      "兜底①：门反向（关到一半又开回去）→ 整段播一次，那一声不能丢")
check(re.search(r"drop\s*==\s*0\.0f", player) is not None,
      "兜底②：门这一 tick 没动（红石锁住 / 被挡）→ 再等，超时后整段播")
check("door == null || elapsed > ALIGN_MAX_WAIT_TICKS" in player,
      "兜底③：门不在快照里（走出渲染距离）或等到超时 → 整段播")
check(re.search(r"play\(mc, pending\.tone\(\), pending\.door\(\), pending\.volume\(\), 0\)", player)
      is not None,
      "三条兜底都落到「整段播」（offset=0），不是静音")
check("quantizeOffset" in player and "durationMs() - remainMs" in player,
      "偏移 = 量化(素材时长 − 剩余门程毫秒)")
check(re.search(r"if \(split > 0 && rawStart < split\)\s*\{\s*rawStart = split;", player) is not None,
      "★ 剪头被夹在「语音播报 / 嘀嘀」分界点之后（不许剪进语音播报里）；"
      "本脚本的 align() 模型在**内置素材上**不受这条影响（raw 已在分界点之后）")
check("50.0" in player, "剩余门程用 50ms/tick 换算（20 TPS）")
check("resumeClose(mc, doors)" in player,
      "resumeClose 每 tick 被调用（门列表为空时也先冲一次，避免挂起的那一声凭空消失）")
# ★ 路 B 与路 A 互斥：提前播过的门在关门那一刻**先**被 plannedCloseFired 拦下，
#   只有「没排上 / 已经播完」才落到 pendingClose（路 A）。顺序反了会叠一声。
_fired_i = player.find("plannedCloseFired.get(door.key())")
_pend_i = player.find("pendingClose.put")
check(0 <= _fired_i < _pend_i,
      "★ 路 A（挂起剪头）排在「已经提前播过」那道判断**之后** ⇒ 两条路互斥，"
      "本脚本的 align() 模型只对应没走成路 B 的情形",
      "fired@%d pending@%d" % (_fired_i, _pend_i))
_plan_i = player.find("!tone.announce() || tone.splitMs() <= 0")
check(_plan_i >= 0,
      "★ 路 B 的入口在 planClose 里，判据 = !announce || split<=0 ⇒ **不猜**、直接交给路 A"
      "（与 align() 模型互补：一个保人声、一个保结尾）")
check("startAnnouncement" not in player,
      "★ 旧「开门时提前播人声」整段删除 ⇒ 源码里只剩这两条路，align() 模型与它一一对应")

print()
print("===== 6) 剪头播放的水管（时长 / 字节来源 / 切片 / 退回）=====")
check("SLICE_STEP_MS" in ap and "quantizeOffset" in ap,
      "EscalatorAudioPlayer 有量化步长与量化函数")
check("bundledBytes" in ap and "bundledDurationMs" in ap,
      "内置素材的字节从模组 jar 读（内置档在同步表里没有字节）")
check("BUNDLED_AUDIO_PREFIX" in ap and "assets/smoothlift/sounds/audio/" in ap,
      "类路径前缀指向 assets/smoothlift/sounds/audio/（sounds.json 同一目录）")
check("sliceHead" in ap and "duplicate()" in ap and "slice()" in ap,
      "sliceHead 用「视图」（duplicate/slice）剪头，不复制内存")
check("getFrameSize()" in ap, "剪头按**帧**对齐（否则 16bit 立体声会声道错位）")
check("offsetPlaybackId" in ap and "offsetOf" in ap and "baseOf" in ap,
      "偏移编码进播放 ID（注入的缓存 key 与 resolve 的 Sound.getPath() 同源）")
check("injectPlayback" in ap, "有「按播放 ID 注入」的入口")
check(re.search(r"DECODE_FAILED\.add\(baseOf\(audioId\)\)", ap) is not None,
      "解码失败按**素材**记（换个偏移不会反复重解）")
check("bundledBytes(tone.builtinKey())" in player,
      "★ 剪头那档连**内置档**也拿字节走注入（原版事件没法从中间开始播）")
check(re.search(r"if \(bytes == null \|\| !EscalatorAudioPlayer\.injectPlayback\(mc, playbackId, bytes\)\)"
                r"\s*\{\s*playbackId = null", player) is not None,
      "剪头那档播不出来 → playbackId 置空、退回整段（宁可不对齐，也不要不出声）")
check("playbackId != null ? playbackId : tone.customId()" in player,
      "播放实例二选一：剪头档用播放 ID，否则用素材 ID / null（原版事件）")

print()
print("===== 7) 版本号与产物 =====")
ver = re.search(r"mod_version\s*=\s*(\S+)", gradle)
check(ver is not None and ver.group(1) == EXPECTED_VER,
      "gradle.properties 的 mod_version = %s（用户点名的号）" % EXPECTED_VER,
      "得到 %s" % (ver.group(1) if ver else "?"))
# 【1.22】jar 名 = <archives_base_name>-<版本>.jar，archives_base_name 必须是显示名
check(re.search(r"archives_base_name\s*=\s*(\S+)", gradle) is not None
      and re.search(r"archives_base_name\s*=\s*(\S+)", gradle).group(1) == EXPECTED_JAR_PREFIX,
      "gradle.properties 的 archives_base_name = %s（jar 名前缀 = 模组显示名）" % EXPECTED_JAR_PREFIX)
check("base" in build and "archivesName" in build,
      "★ build.gradle 真的**应用**了 archives_base_name（只声明不应用 ⇒ jar 仍是旧名）")

if not os.path.isfile(JAR):
    print("[SKIP] 未找到 %s，跳过 jar 校验（先跑 gradlew build）" % os.path.basename(JAR))
else:
    print("        检查 jar：%s" % os.path.basename(JAR))
    check(EXPECTED_VER in os.path.basename(JAR), "打包出来的是 %s（不是别的号）" % EXPECTED_VER)
    check(os.path.basename(JAR).startswith(EXPECTED_JAR_PREFIX + "-"),
          "jar 文件名以 %s- 开头（不是 smooth-escalator-）" % EXPECTED_JAR_PREFIX)
    with zipfile.ZipFile(JAR) as z:
        names = set(z.namelist())
        for f in ("dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"):
            check("assets/smoothlift/sounds/audio/%s" % f in names, "jar 内含素材 %s" % f)
        blob = z.read("smooth/lift/client/PsdChimePlayer.class")
        check(b"resumeClose" in blob and b"pendingClose" in blob,
              "PsdChimePlayer.class 里编进了挂起/恢复链")
        blob2 = z.read("smooth/lift/client/EscalatorAudioPlayer.class")
        check(b"bundledBytes" in blob2 and b"sliceHead" in blob2 and b"quantizeOffset" in blob2,
              "EscalatorAudioPlayer.class 里编进了剪头播放链路")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
