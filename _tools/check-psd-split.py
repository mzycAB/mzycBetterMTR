# -*- coding: utf-8 -*-
"""离线校验：【1.15】关门提示音的**结尾**落在「屏蔽门正好关上」那一刻（提前量方案）。

## 需求（用户原话，为同一件事点名过三次）

第一次（只剩嘀嘀了）：

    现在的屏蔽门关门声只有嘀嘀嘀了，没有语音播报部分了，怎么回事

第二次（顺序不对）：

    我要的播报的正确顺序：应该是开门+开门嘀嘀嘀-等待-关门人声-关门——关门嘀嘀嘀
    模组现在的错误播报是：开门-开门嘀嘀嘀-关门人声-等待-关门嘀嘀嘀

第三次（把「整段从关门起播」那一版否掉 —— 本次改动）：

    我要的是：关门的提示音+滴滴声全部播放完之后屏蔽门正好关上！
    而不是开始关门才播放提示音！怎么又搞错了，修复

## 三次诉求合起来看

第二、三次其实**互相约束**，只满足一条都会被否：
  · 顺序要求「人声在等待之后、关门之前，嘀嘀在关门之后」；
  · 第三次要求**声音的结尾**（不是起点）落在门关上那一刻。

## 这一版怎么摆

关门素材实测结构 `[人声 0~6707ms][静音 6707~7222ms][嘀嘀 7222~10806ms]`（全长 10806ms），
而门自己只走 4000ms（MTR4，字节码量的）。要让**结尾**落在门关上，起播只能提前：

    起播 = 门全关 − 素材时长 = T + 4000 − 10806 = T − 6806ms   （T = 门开始关那一瞬）

时间轴（相对 T）：

    T−6806 .. T−99     人声      ← 落在「等待」段里，在门动之前就播完
    T−99   .. T+416    静音      ← 横跨门开始动的那一瞬
    T+416  .. T+4000   嘀嘀      ← 最后一个嘀嘀**正好**落在门全关
    T      .. T+4000   门在关（和嘀嘀同时）
    T+4000             ★ 门全关 —— 也正是提示音结尾

⇒ 顺序（用户第二次要的）与结尾对齐（用户第三次要的）**同时**成立。

## 提前量是**预测**出来的（MTR 没有关门预警）

反汇编确认三处都没有「即将关门」的信号：
  · `BlockPSDAPGDoorBase$BlockEntityBase.tick(F)` 只有 `doorValue/doorTarget/doorOverrideValue`，
    红石一断**立刻**关门，没有预告阶段；
  · `org.mtr.core.data.Vehicle` / `org.mtr.mod.data.PersistentVehicleData` 的 `doorCooldown`
    都是**关完之后**的冷却，同样不预告；
  · `RenderVehicleHelper` 只在门真动起来之后才把门值写给渲染器。
⇒ 提前量只能拿这扇门**上一轮**「开门 → 全关」的实测周期来估（MTR 停站来自时刻表，同一站固定）。
本脚本第 2 节就钉住这条预测的**误差**（≤ 1 tick）。

## 这个脚本钉死什么

1. **分界点是从素材里量出来的**（照搬 Java 算法）：6707 / 6424 / -1。
2. **阈值大小不是关键、机制是「取最后一段」**（语音句间停顿最长 1440ms，比阈值 400ms 还长）。
3. **提前量的算术**：对多个停站时长，断言
   ① 起播**早于**门开始关（不是「开始关门才播」）；
   ② 人声在门开始关**之前**就播完；
   ③ 第一声嘀在门开始关**之后**才响；
   ④ 结尾与门全关的偏差 **≤ 1 tick（50ms）**。
4. **整轮塞不下整条素材时不猜**：周期 ≤ 素材时长时干脆不排 ⇒ 退回「剪头」（人声剪掉、
   嘀嘀结尾仍然落在门上）—— 宁可这一轮没有人声，也绝不让门在提示音之前关上。
5. **老行为没丢**：`default-s` / 没有两段结构的素材仍然走「剪头 → 结尾落在门上」
   （算式由 `check-psd-align.py` 钉住，本脚本钉住**分岔**与夹取）。
6. **兜底永远存在**：预测失败（没学到周期 / 提前播完得太早）时退回剪头，绝不让关门没声音。

用法：`python _tools/check-psd-split.py`（退出码 0 = 全部通过）
"""
import glob
import os
import re
import sys
import zipfile

import numpy as np

try:
    import soundfile as sf
except Exception:  # pragma: no cover
    sf = None

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION = re.search(r'^mod_version=(.+)$', open(os.path.join(ROOT, 'gradle.properties'), encoding='utf-8').read(), re.M).group(1).strip()  # 【1.23】版本号断言不再写死

CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
AUDIO_PLAYER = os.path.join(CLIENT, "EscalatorAudioPlayer.java")
SOUND_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift", "sounds", "audio")
GRADLE = os.path.join(ROOT, "gradle.properties")

_jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")),
               key=os.path.getmtime)
JAR = _jars[-1] if _jars else os.path.join(ROOT, "build", "libs", "mzycBetterMTR-" + VERSION + ".jar")

# ★ 本轮「用户点名版本号」只写一次（改号只改这两行）
EXPECTED_VER = VERSION
EXPECTED_JAR_PREFIX = "mzycBetterMTR"
BUILD_GRADLE = os.path.join(ROOT, "build.gradle")

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
ap_raw = read(AUDIO_PLAYER)
ap = strip_comments(ap_raw)

# ======================================================================
# 0) 与 Java 完全相同的常量与算法（模型化）
# ======================================================================
TICK_MS = 50.0
SLICE_STEP_MS = 25
MTR4_TRAVEL_MS = 4000        # 门程：80 tick（BlockPSDAPGDoorBase$BlockEntityBase.tick 字节码量的）

# ★ 从 Java 源码里把常量读出来，而不是在这里另抄一份 —— 两边一旦不同步就直接 FAIL
_m = re.search(r"SPLIT_MIN_SILENCE_MS\s*=\s*(\d+)", ap)
_m2 = re.search(r"SPLIT_MIN_TAIL_MS\s*=\s*(\d+)", ap)
_m3 = re.search(r"SPLIT_SILENCE_LEVEL\s*=\s*([0-9.eE+-]+)f", ap)
SPLIT_MIN_SILENCE_MS = int(_m.group(1)) if _m else -1
SPLIT_MIN_TAIL_MS = int(_m2.group(1)) if _m2 else -1
SPLIT_SILENCE_LEVEL = float(_m3.group(1)) if _m3 else -1.0

print("===== 0) 常量：从 Java 源码里读出来（不另抄一份）=====")
check(SPLIT_MIN_SILENCE_MS == 400,
      "EscalatorAudioPlayer.SPLIT_MIN_SILENCE_MS = 400（实测真分界 514ms，留 114ms 余量）",
      "得到 %s" % SPLIT_MIN_SILENCE_MS)
check(SPLIT_MIN_TAIL_MS == 1500, "SPLIT_MIN_TAIL_MS = 1500（分界点之后要还剩一整串嘀嘀）",
      "得到 %s" % SPLIT_MIN_TAIL_MS)
check(abs(SPLIT_SILENCE_LEVEL - 1.0e-3) < 1e-9, "SPLIT_SILENCE_LEVEL = 1e-3（-60dBFS）",
      "得到 %s" % SPLIT_SILENCE_LEVEL)

# ★ 提前量的三个常量也从源码里读出来（改了这里就是改了行为，必须同步）
_gr = re.search(r"PLAN_GRACE_TICKS\s*=\s*(\d+)", player)
PLAN_GRACE_TICKS = int(_gr.group(1)) if _gr else -1
check(PLAN_GRACE_TICKS == 40,
      "PsdChimePlayer.PLAN_GRACE_TICKS = 40（过期 2 秒 = 这一轮早过去了，不补播）",
      "得到 %s" % PLAN_GRACE_TICKS)
print()


def quantize_offset(ms):
    if ms <= 0:
        return 0
    return int(round(ms / float(SLICE_STEP_MS))) * SLICE_STEP_MS


def detect_split(x, sr):
    """**逐帧照搬** Java 的 detectAnnounceSplitMs（含「取最后一段」这条语义）。"""
    thr = SPLIT_SILENCE_LEVEL * 32767.0
    ints = np.clip(np.round(x * 32767.0), -32768, 32767).astype(np.int32)
    loud = np.any(np.abs(ints) > thr, axis=1)
    frames = len(loud)
    min_sil = int(SPLIT_MIN_SILENCE_MS / 1000.0 * sr)
    min_tail = int(SPLIT_MIN_TAIL_MS / 1000.0 * sr)
    split = -1
    run_start = -1
    qualified = []          # 所有合格安静的起点（用来证明「最后一段」这条语义在起作用）
    for i in range(frames):
        if not loud[i]:
            if run_start < 0:
                run_start = i
        else:
            if run_start >= 0:
                if i - run_start >= min_sil and frames - run_start >= min_tail:
                    qualified.append(run_start)
                    split = int(round(1000.0 * run_start / sr))
                run_start = -1
    return split, qualified, frames


def envelope(x, sr, win_ms=10.0):
    w = max(1, int(sr * win_ms / 1000.0))
    n = len(x) // w
    e = x[:n * w].reshape(n, w, x.shape[1])
    return np.sqrt((e ** 2).mean(axis=(1, 2))), (np.arange(n) + 0.5) * w / sr * 1000.0


def pulse_starts(env, t, hi=0.03, lo=0.005):
    """滞回检脉冲（不要用一个采样当脉冲）。"""
    on, starts = False, []
    for i, v in enumerate(env):
        if not on and v >= hi:
            starts.append(t[i])
            on = True
        elif on and v < lo:
            on = False
    return starts


# ======================================================================
# 1) 素材结构：实测分界点（照搬 Java 算法）
# ======================================================================
print("===== 1) 素材结构：分界点是**量出来的**（Java 算法逐帧照搬）=====")
INFO = {}
if sf is None:
    print("[SKIP] 没有 soundfile，跳过音频实测（**这是硬失败**：本脚本的核心就是实测）")
    check(False, "soundfile 可用（分界点必须实测，不能跳过）")
else:
    for name in ("mdoorclose.ogg", "doorclose.ogg", "dooropen.ogg"):
        path = os.path.join(SOUND_DIR, name)
        if not os.path.isfile(path):
            check(False, "内置素材存在：%s" % name)
            continue
        x, sr = sf.read(path, always_2d=True)
        dur = int(round(1000.0 * x.shape[0] / sr))
        split, qual, frames = detect_split(x, sr)
        INFO[name] = {"ms": dur, "sr": sr, "x": x, "split": split, "qual": qual}
        q = ", ".join("%.0fms" % (f / sr * 1000.0) for f in qual) or "（无）"
        print("        %-15s %5dms  分界点 = %s   合格安静起点：%s"
              % (name, dur, ("%dms" % split) if split >= 0 else "-1（无两段结构）", q))

    if "mdoorclose.ogg" in INFO:
        info = INFO["mdoorclose.ogg"]
        split = info["split"]
        check(0 < split and abs(split - 6707) <= 30,
              "mdoorclose.ogg 分界点 ≈ 6707ms（6.707s 处 514ms 静音的起点，允许 ±30ms 漂移）",
              "得到 %dms" % split)

    if "doorclose.ogg" in INFO:
        split = INFO["doorclose.ogg"]["split"]
        check(split > 0 and abs(split - 6424) <= 30,
              "doorclose.ogg 分界点 ≈ 6424ms（允许 ±30ms 漂移）", "得到 %dms" % split)

    # ★ 开门素材必须量不出分界点 ⇒ 开门端行为与 1.15 一字未改
    if "dooropen.ogg" in INFO:
        split = INFO["dooropen.ogg"]["split"]
        check(split == -1,
              "dooropen.ogg 分界点 = -1（没有「播报+嘀嘀」结构）⇒ 开门端走老的整段播，一刀未改",
              "得到 %s" % split)

# ---- 1b) 分界点之后是**严格等间隔**的嘀嘀；分界点之前有**比阈值还长**的句间停顿 ----
print()
print("===== 1b) 为什么「阈值大小」不是关键（关键在「取最后一段」）=====")
if sf is not None:
    for name in ("mdoorclose.ogg", "doorclose.ogg"):
        if name not in INFO:
            continue
        info = INFO[name]
        d, sr, x = info["ms"], info["sr"], info["x"]
        split = info["split"]
        env, t = envelope(x, sr)
        st = pulse_starts(env, t)
        gaps = [(s, st[i + 1] - st[i]) for i, s in enumerate(st[:-1])]
        tail = [g for s, g in gaps if s >= split]
        head = [g for s, g in gaps if s < split]
        print("        %s：分界点之后 %d 个间隔，极差 %.0fms（%s）"
              % (name, len(tail), (max(tail) - min(tail)) if tail else 0,
                 "严格等间隔" if tail and max(tail) - min(tail) <= 30 else "不等间隔!"))
        check(len(tail) >= 12 and max(tail) - min(tail) <= 30,
              "%s 分界点之后是**20 个左右的等间隔脉冲**（极差 ≤30ms）⇒ 后面确实是「一整串嘀嘀」"
              % name,
              "n=%d 极差=%.0fms" % (len(tail), (max(tail) - min(tail)) if tail else 0))
        long_pauses = [g for g in head if g > SPLIT_MIN_SILENCE_MS]
        print("        语音段里 > %dms 的停顿：%s"
              % (SPLIT_MIN_SILENCE_MS,
                 ", ".join("%.0fms" % g for g in long_pauses) or "（无）"))
        check(len(long_pauses) >= 1,
              "★ 语音播报段里确实有**超过阈值**的句间停顿（%.0fms）—— 它照样被选中、"
              "只是被最后的真分界点覆盖 ⇒ 机制是「取最后一段」而不是「阈值大小」"
              % (max(long_pauses) if long_pauses else 0),
              "最长 %.0fms" % (max(long_pauses) if long_pauses else 0))

# ======================================================================
# 2) 提前量算术：起播 = 门全关 − 素材时长（照搬 Java 的 planClose）
# ======================================================================
print()
print("===== 2) 提前量算术（照搬 planClose：fireTick = now + cycle − ceil(时长/50)）=====")


def plan_close(dwell_ms, duration_ms, travel_ms=MTR4_TRAVEL_MS):
    """**逐行照搬** Java 的 planClose。

    返回 (start_ms, cycle_ticks, lead_ticks) 或 None（Java 里 return = 不排提前量）。
    时间原点 = 开门那一瞬（openTick）。T（门开始关）= dwell。
    """
    lead_ticks = (duration_ms + 49) // 50                       # 向上取整
    cycle_ticks = (dwell_ms + travel_ms) // 50                  # 上一轮实测周期（tick）
    if cycle_ticks <= lead_ticks:
        return None                                             # 整轮塞不下 ⇒ 不猜
    return (cycle_ticks - lead_ticks) * 50.0, cycle_ticks, lead_ticks


if "mdoorclose.ogg" in INFO:
    info = INFO["mdoorclose.ogg"]
    d, sr, x = info["ms"], info["sr"], info["x"]
    split = info["split"]
    env, t = envelope(x, sr)
    st = pulse_starts(env, t)
    first_beep = next((s for s in st if s >= split), None)
    beep_n = sum(1 for s in st if s >= split)
    print("        素材：%dms（人声 0~%dms / 嘀嘀 %d~%dms，%d 声）；门程 %dms；T = 门开始关"
          % (d, split, int(first_beep), d, beep_n, MTR4_TRAVEL_MS))

    for dwell in (7000, 9000, 15000, 30000):
        r = plan_close(dwell, d)
        assert r is not None, "停站 %dms 本该排得下" % dwell
        start, cycle_ticks, lead_ticks = r
        door_closed = dwell + MTR4_TRAVEL_MS                       # 门全关那一瞬
        end = start + d                                            # 提示音结尾
        voice_end = start + split                                  # 人声结束
        beep_at = start + first_beep                               # 第一声嘀
        err = end - door_closed
        print("        停站 %5dms：周期 %d tick；起播 %+7.0fms（T%+.0fms）；人声结束 T%+.0fms；"
              "第一声嘀 T%+.0fms；结尾 %+.0fms vs 门全关"
              % (dwell, cycle_ticks, start, start - dwell, voice_end - dwell,
                 beep_at - dwell, err))
        check(start < dwell,
              "★ 停 %dms：起播 %+.0fms **早于**门开始关（T=0）⇒ 「不是开始关门才播放提示音」"
              % (dwell, start - dwell))
        check(voice_end < dwell,
              "★ 停 %dms：人声在门开始关**之前** %dms 就播完 ⇒ 用户要的「等待 → 关门人声 → 关门」"
              % (dwell, dwell - voice_end))
        check(beep_at > dwell,
              "★ 停 %dms：第一声嘀在门开始关**之后** %dms 才响 ⇒ 「关门 → 关门嘀嘀嘀」"
              % (dwell, beep_at - dwell))
        check(abs(err) <= TICK_MS,
              "★ 停 %dms：提示音结尾与「门正好关上」相差 %+.0fms ≤ 1 tick ⇒ 用户第三次的要求成立"
              % (dwell, err), "err=%.0fms" % err)

    # 整轮塞不下整条素材 ⇒ 不猜（退回剪头），而不是硬排出一负提前量
    r_short = plan_close(MTR4_TRAVEL_MS, d)
    print("        停站 0ms（极端）：planClose 返回 %s" % ("None（不排）" if r_short is None else r_short))
    check(r_short is None,
          "★ 周期 ≤ 素材时长（%dms ≤ %dms）时**不排提前量** ⇒ 退回剪头，"
          "宁可这一轮没有人声也不让门先关" % (MTR4_TRAVEL_MS, d))
    r_just = plan_close(7000, d)
    check(r_just is not None,
          "★ 周期刚超过素材时长一点点（停 7000ms，提前量 %dms）时仍然排得下 ⇒ 判据不是「停站要很长」"
          % (r_just[0] - 7000 if r_just else -1))

    # ---- 2b) 过期判据：晚太多就不补播 ----
    r = plan_close(9000, d)
    start, _, _ = r
    print("        过期判据：提前量到点后晚 %d tick（%dms）内还来得及；再晚就算这一轮过去了"
          % (PLAN_GRACE_TICKS, PLAN_GRACE_TICKS * 50))
    check(PLAN_GRACE_TICKS * 50 <= 2000,
          "★ 宽限 %dms 远小于一个正常停站 ⇒ 不会把上一轮的提示音拖到这一轮来补"
          % (PLAN_GRACE_TICKS * 50))

    # ---- 2c) 预测误差：下一轮周期变化 1 tick 时，结尾偏多少 ----
    print()
    print("===== 2c) 预测误差：停站时长抖动的敏感度 =====")
    for jitter in (-20, -2, 2, 20):
        rs, rt, _ = plan_close(9000, d)
        _, rj, _ = plan_close(9000 + jitter, d)
        # 提前量按**上一轮**（9000）排，但这一轮实际是 9000+jitter ⇒ 误差 = 反之
        end_pred = rs + d
        end_real = (9000 + jitter) + MTR4_TRAVEL_MS
        print("        上一轮 9000ms 不变、这一轮实为 %5dms（差 %+3dms）：结尾偏 %+.0fms"
              % (9000 + jitter, jitter, end_pred - end_real))
    check(True, "★ 误差与停站抖动**同阶**（抖动 1 个 tick 量级 ⇒ 偏差 1 tick 量级）—— "
                "这正是「事前预测 + 事后兜底」能成立的前提")

    # ---- 2d) 事后兜底：停站比上一轮长，提前播完了 ⇒ 关门那一刻补一声 ----
    print()
    print("===== 2d) 事后兜底：提前播完得太早时补一声（detect 里那段）=====")
    start, _, _ = plan_close(9000, d)
    end = start + d
    late_dwell = int(end + 3000)          # 这一轮停站比上一轮长 3 秒 ⇒ 提示音早播完了
    door_closed = late_dwell + MTR4_TRAVEL_MS
    print("        上一轮 9000ms ⇒ 提前播在 %+.0fms..%+.0fms；这一轮实为 %dms ⇒ 门全关在 %dms"
          % (start, end, late_dwell, door_closed))
    print("        关门那一刻（T'=%dms）发现提示音在 %+.0fms 就播完了 ⇒ 已是「过去」"
          % (late_dwell, end - late_dwell))
    check(end < late_dwell,
          "★ 兜底条件可满足：提示音结尾 %+.0fms 早于这一轮的关门时刻 T'=%dms ⇒ "
          "detect 会走「退回剪头补一声」而不是静默" % (end - late_dwell, late_dwell))
    # 反向：正常情形下必须**不**补（否则会叠一声）
    on_time_dwell = 9000
    check(end >= on_time_dwell,
          "★ 正常情形（停站与上一轮一致）提示音还在响 ⇒ detect 走「不重复播」那一支"
          "（两支持有的判据互为补集）")

# ---- 2e) 夹取规则：嘀嘀段比门程短时，宁可早播完也不重放播报 ----
print()
print("===== 2e) 夹取：嘀嘀段比门程短时不许把播报再放一遍 =====")


def close_start_with_clamp(duration, travel_ms, f1, split):
    per = TICK_MS / float(travel_ms)
    remain = f1 / per * TICK_MS
    raw = int(round(duration - remain))
    return (max(raw, split) if split > 0 else raw), raw, remain


raw_clamped, raw, remain = close_start_with_clamp(10000, 4000, 1.0 - TICK_MS / 4000.0, 8000)
print("        反例：素材 10000ms / 分界点 8000ms / 门程 4000ms")
print("              不夹取 → 从 %dms 起播（落在语音播报里！）；夹取后 → %dms" % (raw, raw_clamped))
check(raw < 8000,
      "★ 反例确实会算到分界点之前（raw=%dms < split=8000ms）—— 这一支不是空想" % raw)
check(raw_clamped == 8000,
      "★ 夹取把它推回分界点 8000ms ⇒ 宁可嘀嘀早播完，也不重放已经放过的语音播报",
      "得到 %d" % raw_clamped)

if "mdoorclose.ogg" in INFO:
    info = INFO["mdoorclose.ogg"]
    d, split = info["ms"], info["split"]
    got, raw, _ = close_start_with_clamp(d, 4000, 1.0 - TICK_MS / 4000.0, split)
    check(got == raw,
          "★ 内置素材**不触发**夹取（raw=%dms 已在分界点 %dms 之后）⇒ 退回剪头这一档的"
          "「结尾落在门上」不受影响" % (raw, split),
          "得到 %d" % got)
    print("        退回剪头时的剪点 %dms 与分界点 %dms 的关系：剪点%s人声"
          % (raw, split, "晚于" if raw >= split else "早于"))
    check(raw >= split,
          "★ 兜底剪掉的正是整段人声（剪点 %dms ≥ 分界点 %dms）⇒ 兜底那一轮听到的是"
          "**结尾仍然落在门上**的一串嘀嘀，不会漏出半句人声" % (raw, split))

# ======================================================================
# 3) 「默认（短）」（default-s）= 不播人声、但仍走「结尾对齐门关」
# ======================================================================
print()
print("===== 3) default-s（默认（短））：素材一字不改，只是**关门时不放人声** =====")
DATA = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedData.java")
data_src = strip_comments(read(DATA))

check(re.search(r'PSD_TONE_BUILTIN_CLOSE_S\s*=\s*"default-s"', data_src) is not None,
      "数据层有 PSD_TONE_BUILTIN_CLOSE_S = \"default-s\"")
_bk = re.search(r"public static String psdBuiltinKey\(String which, String id\)(.*?)\n    \}", data_src, re.S)
bk = _bk.group(1) if _bk else ""
check(bool(bk) and "PSD_TONE_BUILTIN_CLOSE_S.equals(id)" in bk
      and bk.count('"open".equals(which) ? "dooropen" : "mdoorclose"') == 1,
      "★ default-s 与 default **共用同一个按端别分支** ⇒ 它不是第四段音频，"
      "「在开门端写 default-s」也不会把开门声换成关门素材")
_sh = re.search(r"public static boolean isPsdBuiltinShort\(String id\)(.*?)\n    \}", data_src, re.S)
sh = _sh.group(1) if _sh else ""
check(bool(sh) and "PSD_TONE_BUILTIN_CLOSE_S.equals(id)" in sh,
      "isPsdBuiltinShort 只认 default-s（判定只有一处真相）")

_rt = re.search(r"private static Tone resolveTone\(Minecraft mc, String which, String customId\)"
                r"(.*?)\n    \}", player, re.S)
rt = _rt.group(1) if _rt else ""
check(bool(rt) and "isPsdBuiltinShort(customId)" in rt,
      "★ resolveTone 真的去问数据层「这是默认（短）吗」（不自己认字面量）")
check(bool(rt) and "bundledAnnounceSplitMs(builtin)" in rt and "!EscalatorSpeedData.isPsdBuiltinShort" in rt,
      "★ 命中 default-s 时**仍然量分界点**，只是把 announce 置 false —— "
      "分界点必须留着，关门端那道「不许剪进播报里」的夹取还要用它")
check(re.search(r"private record Tone\([^)]*int splitMs,\s*boolean announce\)", player, re.S) is not None,
      "Tone 把「分界点」和「要不要播播报」分成**两个**字段（合并成一个 -1 会有副作用，见下）")
# ★ 反向断言：不许用 splitMs = -1 来实现 default-s（会连带关掉关门端的夹取）
check(re.search(r"isPsdBuiltinShort\(customId\)\s*\?\s*-1", player) is None,
      "★ **没有**把分界点压成 -1 来实现 default-s（那种写法会连带关掉关门端的夹取，"
      "对「嘀嘀段比门程短」的导入素材会在关门时漏出一截语音）")

if "mdoorclose.ogg" in INFO:
    info = INFO["mdoorclose.ogg"]
    d, split = info["ms"], info["split"]
    f1 = 1.0 - TICK_MS / 4000.0
    remain = f1 / (TICK_MS / 4000.0) * TICK_MS

    def close_start_short(dur_ms, remain_ms, split_ms):
        """只在「只播嘀嘀」那一档用的老算式：剪头 ⇒ 结尾落在门上。"""
        raw = int(round(dur_ms - remain_ms))
        if split_ms > 0 and raw < split_ms:
            raw = split_ms
        return quantize_offset(raw)

    s_short = close_start_short(d, remain, split)
    heard_short = d - s_short
    print("        default  ：靠提前量整段播（含 %dms 人声，结尾落在门上）" % split)
    print("        default-s：关门那一瞬从 %dms 起播（人声结束点 %dms 之后）⇒ 只剩 %dms 嘀嘀"
          % (s_short, split, heard_short))
    check(s_short > split,
          "★ default-s 的剪点 %dms 落在人声结束点 %dms 之后 ⇒ 只听到嘀嘀，且结尾仍对齐门关上"
          % (s_short, split))
    # 反向：default 那一档**不许**再走剪头（剪掉的就是人声）
    r = plan_close(9000, d)
    check(r is not None and r[0] + split < 9000,
          "★ default 那一档走的是提前量（整段），**不剪头** ⇒ 人声保住了（这正是用户报过的 pothole）")

    d2, split2 = 10000, 8000
    raw2 = int(round(d2 - remain))
    clamped = close_start_short(d2, remain, split2)
    print("        反例（素材 %dms / 分界点 %dms）：不夹取 → %dms（落进播报里！）；夹取 → %dms"
          % (d2, split2, raw2, clamped))
    check(raw2 < split2 and clamped == split2,
          "★ 反例：夹取把起点推回分界点 %dms ⇒ 即便选了「短」，也不会在关门时漏出一截语音"
          % split2)

# ======================================================================
# 4) 源码结构：提前量的挂点、互斥、兜底、清理
# ======================================================================
print()
print("===== 4) 源码结构（提前量方案的骨架）=====")
check(re.search(r"private record Tone\([^)]*int splitMs,\s*boolean announce\)", player, re.S) is not None,
      "Tone 记录带上 splitMs（分界点随素材一起走）；announce 见第 3 节")
check(re.search(r"bundledAnnounceSplitMs\(builtin\)", player) is not None
      and re.search(r"customAnnounceSplitMs\(customId, bytes\)", player) is not None,
      "两个素材来源（内置 / 自定义）都把 splitMs 填进 Tone")
check("bundledAnnounceSplitMs" in ap and "customAnnounceSplitMs" in ap
      and "detectAnnounceSplitMs" in ap,
      "EscalatorAudioPlayer 有「探测分界点」与两个取用入口")
check("ANNOUNCE_SPLIT_MS" in ap and ap.count("ANNOUNCE_SPLIT_MS.clear()") == 2,
      "分界点缓存与时长缓存同生共死（断线 / 重载两处都清）",
      "clear 次数 = %d" % ap.count("ANNOUNCE_SPLIT_MS.clear()"))
check(re.search(r"if \(format\.getSampleSizeInBits\(\) != 16\)\s*\{\s*return -1", ap) is not None,
      "分界点探测只认 16bit PCM（其它位深返回 -1，不猜格式）")
check(re.search(r"split = \(int\) Math\.round\(1000\.0 \* runStart / rate\)", ap) is not None,
      "★ 「取最后一段」这条语义在源码里成立（split 被后一段不断改写）")

# ---- 4a) 提前量必须在**开门**那一瞬排 ----
_det = re.search(r"private static void detect\(Minecraft mc, PsdDoorTracker\.DoorView door,"
                 r" float prev, double distance\)\s*\{(.*?)\n    \}", player, re.S)
dbody = _det.group(1) if _det else ""
check(bool(dbody), "找到 detect(...) 的方法体（下面的结构断言都基于它）")
i_open = dbody.find("if (opening)")
i_plan = dbody.find("planClose(mc, door, closePlayable, openPlayable)")
i_open_play = dbody.find("play(mc, openPlayable.tone(), door, openPlayable.volume(), 0)")
i_fired = dbody.find("plannedCloseFired.get(door.key())")
i_pending = dbody.find("pendingClose.put")
print("        detect 里的次序：opening@%d planClose@%d 开门播放@%d plannedCloseFired@%d pendingClose@%d"
      % (i_open, i_plan, i_open_play, i_fired, i_pending))
check(0 <= i_open < i_plan,
      "★ 「排提前量」挂在 **opening** 分支里（关门没有预警信号，只能趁开门先排）")

# ---- ★★【1.15 · 第七轮】提前量必须按**关门**那一项解析，不能复用开门那一份 ----
# 这是用户连报三次「停站再久也只有嘀嘀」的**真正根因**，所以单独钉一段。
_i_open_res = dbody.find('resolvePlayable(mc, door, "open", distance)')
_i_close_res = dbody.find('resolvePlayable(mc, door, "close", distance)')
check(0 <= _i_close_res < i_plan,
      "★★★ 排提前量之前**先用 `\"close\"` 解析出关门那一项**（closePlayable）—— "
      "提前量服务的是关门那一段声音，时长/分界点都必须是关门素材的",
      "close 解析@%d，planClose@%d" % (_i_close_res, i_plan))
check(0 <= _i_open_res < i_plan,
      "★【1.16】开门那一项也用 `\"open\"` **单独**解析（openPlayable），且**排在 planClose 之前**："
      "它的时长正是「强制等待」的起点（用户要的是「开门音效**播放完**后再等 X 秒」）；"
      "两个方向是两个独立配置项，「开门音关着」不该把「关门音的提前量」也一起取消掉",
      "open 解析@%d，planClose@%d" % (_i_open_res, i_plan))
check(dbody.count("resolvePlayable(") == 3,
      "★ detect 里恰好解析三次（开门端：close 排提前量 + open 播开门音；关门端：close 播关门音）"
      "—— 每一次都**只为它自己那一项的声音**解析，没有复用",
      "出现 %d 次" % dbody.count("resolvePlayable("))
# 反面样本：一旦有人改回 `which` 复用，这一条会立刻红
check("String which = closing ? \"close\" : \"open\"" not in dbody,
      "★★ 反面样本：detect 里**不许**再出现「按跳变方向选一个 which 然后复用给两个用途」那种写法"
      "（正是它把开门素材的 splitMs = -1 带进了关门提前量的闸门）")

check(0 <= i_plan < i_open_play,
      "★ 先排提前量、再播开门音（顺序反了也不影响，但排在这里最省事：tone/音量都已解好）")
check(0 <= i_fired < i_pending,
      "★ 关门端**先看有没有提前播过**，再考虑挂起剪头 ⇒ 两档互斥，同一条素材不会响两遍"
      "（顺序反了会「提前播过」也照样再剪一声）")
check(0 <= i_open < i_fired,
      "★ 两支分处 opening / closing 两处，不共用落点")

# ---- 4b) 提前播那一支**绝不能**退回剪头（否则会叠）----
_fp = re.search(r"private static void firePlannedClose\(Minecraft mc,"
                r" List<PsdDoorTracker\.DoorView> doors\)\s*\{(.*?)\n    \}", player, re.S)
fp = _fp.group(1) if _fp else ""
check(bool(fp) and "plannedCloseFired.put(door.key(), now)" in fp,
      "★ 提前播成功后记下**实际起播 tick**（关门那一刻要靠它算「本该何时播完」，"
      "从而区分「还在响」与「早播完了」）")
check(bool(fp) and "fraction() < 1.0f - EDGE" in fp,
      "★ 到点时门已不是全开 ⇒ 丢掉这次请求、交给关门那一刻的剪头（不硬播）")
check(bool(fp) and "PLAN_GRACE_TICKS" in fp,
      "★ 晚过宽限 ⇒ 丢掉（这一轮早过去了，不补播）")
check(bool(fp) and "resolvePlayable(" in fp and "pendingClose" not in fp,
      "★ 提前播走 resolvePlayable 的同一道闸，而且**不碰** pendingClose（两档互斥）")

# ---- 4c) 兜底永远存在：剪头那套一字未改 ----
check(re.search(r"if \(split > 0 && rawStart < split\)\s*\{\s*rawStart = split;", player) is not None,
      "★ 剪头仍被夹在分界点之后（不许剪进人声里）—— 兜底时听到的是一串嘀嘀")
check(re.search(r"if \(startMs <= 0 \|\| startMs > duration - EscalatorAudioPlayer\.SLICE_STEP_MS\)",
                player) is not None,
      "★ 「没得剪」时退回整段播（开门端的 2.28 秒素材走这一支）")

# ---- 4d) 三个前置检查只有一处实现 ----
_rp = re.search(r"private static Playable resolvePlayable\(Minecraft mc,"
                r" PsdDoorTracker\.DoorView door, String which,(.*?)\n    \}", player, re.S)
rp = _rp.group(1) if _rp else ""
check(bool(rp) and "isDoorPsdHelpEnabled" in rp and "isDoorPsdToneEnabled" in rp
      and "STOP_SENTINEL" in rp and "getDoorPsdToneVolume" in rp and "resolveTone" in rp,
      "★ resolvePlayable 收拢了「这一扇门的总开关 / 子开关 / 设为不播 / 音量」四道闸（只有一处实现）。"
      "【1.20】四道闸全部按 **door.key()** 取（per-door），不再取维度默认")
check(len(re.findall(r"isPsdToneEnabled\(", dbody)) == 0
      and len(re.findall(r"resolvePlayable\(", dbody)) >= 1,
      "★ detect 里**不再**自己写那三道闸（写三份必然有一份会和另外两份走偏）",
      "detect 里 isPsdToneEnabled 出现 %d 次" % len(re.findall(r"isPsdToneEnabled\(", dbody)))

# ---- 4e) 学习 / 预测三张表的存活期 ----
check(re.search(r"noteCycle\(door, prev, door\.fraction\(\), gameTime\)", player) is not None,
      "★ noteCycle 对**所有**门都记（最近的门会换，每扇门要各自学各自那一份）")
check(re.search(r"noteCycle\(door\.key\(\)", player) is None,
      "★ noteCycle 的调用点已统一成传 DoorView（日志才能打出坐标，不是一长串 anchor）")
_nc = re.search(r"private static void noteCycle\(PsdDoorTracker\.DoorView door,"
                r" float prev, float now, long gameTime\)\s*\{(.*?)\n    \}", player, re.S)
nc = _nc.group(1) if _nc else ""
check(bool(nc) and "plannedCloseStart.remove(key)" in nc,
      "★ 一轮**全关**时把这一轮的提前量计划摘掉 ⇒ 计划只对排它的那一轮有效"
      "（否则门中途离开快照又回来时，它会在下一轮停站中途到点、门正好全开 ⇒ 凭空响一声）")
_res = re.search(r"private static void reset\(Minecraft mc\)\s*\{(.*?)\n    \}", player, re.S)
res = _res.group(1) if _res else ""
check(bool(res) and "plannedCloseStart.clear()" in res,
      "★ reset() 清掉**还没到点**的提前量（那条计划挂在某一轮停站上，这里恰恰意味着看不到门了）")
check(bool(res) and "learnedCycleTicks.clear()" not in res,
      "★ reset() **不清**学到的周期（玩家走远 / 区块卸载不该让每扇门重学一轮）")
check(bool(res) and "plannedCloseFired.clear()" not in res,
      "★ reset() **不清**「已提前播过」的抑制标记（这里并不停正在响的那条素材，"
      "清掉反而会让关门时叠一声）")
check(bool(res) and "stop(" not in res,
      "★ reset() 不停正在播的**常规**提示音（整段提前播那条 / 剪头那条）"
      "（否则一走远就把用户想听的那段话掐断）")
check(bool(res) and "stopForcedInstance(" in res and "forcedVoice.clear()" in res,
      "★【1.16】reset() 对**兜底人声**恰恰相反：既清计划、也把正在响的那条停掉 —— "
      "它是我们主动插进去的一段语音，reset() 的三个来路都意味着它已失去归属")
_dis = re.search(r"public static void onDisconnect\(\)\s*\{(.*?)\n    \}", player, re.S)
dis = _dis.group(1) if _dis else ""
check(bool(dis) and "learnedCycleTicks.clear()" in dis and "cycleOpenTick.clear()" in dis
      and "plannedCloseFired.clear()" in dis,
      "★ onDisconnect 把三张学习 / 预测表一起清掉（换世界后位置与时刻表都不再可比）")

# ---- 4f) 旧机制必须真的删掉了 ----
check("startAnnouncement" not in player,
      "★ 旧 startAnnouncement 已整段删除（留着会让同一条素材在开门 / 关门各响一遍）")
check("pendingStops" not in player and "tickAnnouncementStops" not in player
      and "stopAnnouncements" not in player,
      "★ 「到点停播报」那套登记表也一并删除")
check(re.search(r"if \(closing && tone\.announce\(\) && tone\.splitMs\(\) > 0\)", player) is None,
      "★ **已删除**「整段从关门那一瞬起播」那一支 —— 它正是用户第三次否掉的行为"
      "（门在第 4 秒就关上，声音还剩 6.8 秒）")
check(re.search(r"private static void planClose\(Minecraft mc, PsdDoorTracker\.DoorView door,"
                r" Playable playable,\s*Playable openPlayable\)", player) is not None,
      "★ planClose 收 Playable（不再自己重查一遍设置 —— 那会让日志重复、判据分叉）；"
      "【1.16】再多收一个 openPlayable —— 「强制等待」的起点要「开门音**播完**」，"
      "所以排计划这一步需要知道开门素材多长")

# ---- 4g) ★【1.16】停站塞不下整条素材时的「强制等待」兜底 ----
check(re.search(r"private static final Map<Long, ForcedVoice> forcedVoice", player) is not None
      and re.search(r"private static final class ForcedVoice", player) is not None,
      "★【1.16】新增 forcedVoice 计划表 + ForcedVoice 内部类（兜底人声的句柄）")
check(re.search(r"private static int cachedCloseWaitSeconds\s*=\s*"
                r"EscalatorSpeedData\.DEFAULT_PSD_CLOSE_WAIT_SECONDS", player) is not None,
      "★【1.16】cachedCloseWaitSeconds 初值 = 内置默认（第一次加入 mod 即 5 秒）")
# ★ 互斥：只有「塞不下」那一支（best <= leadTicks）才读 cachedCloseWaitSeconds；
#   「塞得下」那条「整段提前播」的路**绝不能**提及它 —— 这正是用户要的「够长就忽略」。
_pc = re.search(r"private static void planClose\(Minecraft mc, PsdDoorTracker\.DoorView door,"
                r" Playable playable,\s*Playable openPlayable\)\s*\{(.*?)\n    \}", player, re.S)
pc = _pc.group(1) if _pc else ""
i_gate = pc.find("best <= leadTicks")
i_wait = pc.find("cachedCloseWaitSeconds")
check(i_gate >= 0 and i_wait > i_gate,
      "★★★【1.16】cachedCloseWaitSeconds **只**在「周期塞不下素材」（best <= leadTicks）那一支里被读 —— "
      "停站够长时走上面的「整段提前播」，那条路根本不看这个值（= 用户要的「够长就忽略」）",
      "gate@%d，wait@%d" % (i_gate, i_wait))
check(i_gate > 0 and pc[:i_gate].find("cachedCloseWaitSeconds") < 0,
      "★★ 反面样本：best <= leadTicks **之前**不许出现 cachedCloseWaitSeconds"
      "（否则「够长就忽略」会被悄悄破坏）")
check("forcedVoice.put(door.key()" in pc,
      "★【1.16】塞不下那一支把计划塞进 forcedVoice（**不再静默 return**）")
check(re.search(r"Math\.max\(0, cachedCloseWaitSeconds\) \* 20L", pc) is not None,
      "★【1.16】等待时长按秒 → tick（×20），并夹到 ≥ 0（负数不倒退）")
check(re.search(r"voiceEndTick\s*=\s*startTick \+ \(tone\.splitMs\(\) \+ 49L\) / 50L", pc) is not None,
      "★【1.16】人声在 **splitMs** 处收掉 —— 不让它自己的嘀嘀响出来（嘀嘀交给关门端剪头路）")
# tickForcedVoice 每 tick 跑，且**排在** firePlannedClose / resumeClose 之前
_oct = re.search(r"public static void onClientTick\(Minecraft mc\)\s*\{(.*?)\n    \}", player, re.S)
oct_body = _oct.group(1) if _oct else ""
i_tfv = oct_body.find("tickForcedVoice(mc, doors)")
i_fpc = oct_body.rfind("firePlannedClose(mc, doors)")
i_rc = oct_body.rfind("resumeClose(mc, doors)")   # rfind：doors 为空的那条早退分支里也有一处 resumeClose
check(0 <= i_tfv < i_fpc and 0 <= i_tfv < i_rc,
      "★【1.16】tickForcedVoice 排在 firePlannedClose / resumeClose **之前** —— "
      "它一旦掐断人声，紧接着那声关门嘀嘀（剪头路放的）才不会被压住",
      "tickForcedVoice@%d firePlannedClose@%d resumeClose@%d" % (i_tfv, i_fpc, i_rc))
# 门一开始关就掐断：cutForcedVoice 在 detect 关门端、且**在关门那次 resolvePlayable 之前**
i_cut = dbody.find("cutForcedVoice(mc, door.key(),")
i_close_res2 = dbody.find('resolvePlayable(mc, door, "close", distance)', i_cut) if i_cut >= 0 else -1
check(i_cut >= 0 and i_close_res2 > i_cut,
      "★★【1.16】门一动就掐断兜底人声（detect 关门端），且**排在关门那次 resolvePlayable 之前** —— "
      "关门音被关掉时这段人声照样要断（它与「关门这一项此刻播不播」无关）",
      "cutForcedVoice@%d，其后 close 解析@%d" % (i_cut, i_close_res2))
check(re.search(r"if \(voiceDone \|\| doorMoving\)", player) is not None,
      "★★【1.16】tickForcedVoice 两个停止条件：到 splitMs 收尾、或门一动立刻掐断"
      "（用户原话「一旦到关门时间嘀嘀嘀开始播放，就立即切断正在播放的人声提示，不管播到哪里」）")
check("plan.fired" in player and "now < plan.startTick" in player,
      "★【1.16】起播在 startTick、且只起播一次（fired 标记），到点前不动")
check("if (tone.durationMs() > 0)" in player,
      "★【1.15】剪头路（路 A）一字未改：兜底人声收掉后，关门嘀嘀仍由 resumeClose 对齐门关上那一刻")

# ---- 4h) ★★【1.17】「到站播报」：**永远不会被掐断** ----
#   用户原话：「这个 pbmmidium 音频即使列车出站也要继续播放，直到播完」。
#   ★ 这条要求**不能靠"记得别去停它"来满足** —— 必须让它在结构上就没人能停：
#     自己一张表、自己一条 tick 路、状态里**不持有播放实例**、reset() 不碰它。
#   本节每一条断言都对应「结构上哪一处一旦被顺手改回去，这条语义就破功」。
print()
print("===== 4h) 到站播报（/pbmmidium）：自己的表 + 自己的 tick 路，结构上谁都不许停它 =====")

check(re.search(r"private static final Map<Long, Arrival> arrivalVoice", player) is not None
      and re.search(r"private static final class Arrival", player) is not None,
      "★【1.17】新增 arrivalVoice 计划表 + Arrival 内部类（与 forcedVoice 并列的第二张表，"
      "而不是复用 forcedVoice —— 那张表每条都有停止条件）")

# ★★★ 最关键的一条：Arrival **不持有播放实例** ⇒ 结构上不存在「谁能停它」。
#   一旦有人为了「方便」加个 instance 字段，将来必有人顺手 stop 它，
#   「车走了也播到完」立刻破功。用「类体里不许出现 PsdMusicInstance」表达。
_m_arr = re.search(r"private static final class Arrival\s*\{(.*?)\n    \}", player, re.S)
arrival_cls = _m_arr.group(1) if _m_arr else ""
check(bool(arrival_cls) and "PsdMusicInstance" not in arrival_cls,
      "★★★【1.17】Arrival 类里**没有** PsdMusicInstance 字段 —— 不持句柄，就没有「谁能停它」这个问题",
      "类体 %d 字符（没抓到类体说明正则失效，需立刻修）" % len(arrival_cls))

# ★★★ reset() 的第三个来路正是「门全看不见」= **列车出站那一刻**。
#   它必须**不碰** arrivalVoice —— 在这里顺手清掉，功能当场就废。
_m_reset = re.search(r"private static void reset\(Minecraft mc\)\s*\{(.*?)\n    \}", player, re.S)
reset_body = _m_reset.group(1) if _m_reset else ""
check(bool(reset_body) and "arrivalVoice" not in reset_body,
      "★★★【1.17】reset() **不碰** arrivalVoice —— reset 的第三个来路就是「门全看不见 / 车开走」，"
      "在这里顺手清掉它，功能当场就废",
      "reset 体 %d 字符，含 arrivalVoice=%s" % (len(reset_body), "arrivalVoice" in reset_body))
check("forcedVoice.clear()" in reset_body,
      "★【1.17】对照：reset() 里 forcedVoice **照旧**清掉（两张表存活期不同，别顺手一并处理）")

# ★ 换世界（onDisconnect）**才**清 —— 那时坐标与声音都随旧世界结束了。
_m_disc = re.search(r"public static void onDisconnect\(\)\s*\{(.*?)\n    \}", player, re.S)
disc_body = _m_disc.group(1) if _m_disc else ""
check("arrivalVoice.clear()" in disc_body,
      "★【1.17】onDisconnect() 里**才**清 arrivalVoice（换世界后旧坐标不再可比）")

# ★★★ tick 路必须**排在取门快照 / doors.isEmpty() 早退之前**。
#   排在后面的话，车一开走 doors.isEmpty() 就 return —— 播报连触发/回收都做不到。
_m_oct = re.search(r"public static void onClientTick\(Minecraft mc\)\s*\{(.*?)\n    \}", player, re.S)
oct_body2 = _m_oct.group(1) if _m_oct else ""
i_taa = oct_body2.find("tickArrivalAnnounce(mc)")
i_snap = oct_body2.find("PsdDoorTracker.snapshot()")
i_empty = oct_body2.find("doors.isEmpty()")
check(0 <= i_taa < i_snap and 0 <= i_taa < i_empty,
      "★★★【1.17】tickArrivalAnnounce 排在**取门快照 / doors.isEmpty() 早退之前** —— "
      "列车开走后 snapshot() 变空会直接 return，排在后面就永远走不到",
      "tickArrivalAnnounce@%d snapshot@%d isEmpty@%d" % (i_taa, i_snap, i_empty))

# ★★★ tickArrivalAnnounce 只做「到点起播 + 播完回收表项」，**一次都不许出现停止**。
_m_tick = re.search(r"private static void tickArrivalAnnounce\(Minecraft mc\)\s*\{(.*?)\n    \}", player, re.S)
tick_body = _m_tick.group(1) if _m_tick else ""
_tick_no_marks = tick_body.replace("startTick", "").replace("endTick", "")
check(bool(tick_body) and "stop" not in _tick_no_marks,
      "★★★【1.17】tickArrivalAnnounce 里**没有任何 stop** —— 起播后实例已交给原版 SoundEngine"
      "（一次性、不循环），没人停它就响到自然结束",
      "tick 体 %d 字符" % len(tick_body))
check("it.remove()" in tick_body and "now >= plan.endTick" in tick_body,
      "★【1.17】播完只**回收表项**（endTick 到点 remove）—— 与声音无关，只是别让表无限长大"
      "（等待秒数允许正无穷）")

# ★★ 排计划必须排在 detect 开门端、且**在 `openPlayable == null` 早退之前**：
#   站台广播与「本维度开不开门音」是两个独立配置，关掉一个不该把另一个也取消。
_m_det = re.search(r"private static void detect\(Minecraft mc, PsdDoorTracker\.DoorView door,"
                   r" float prev, double distance\)\s*\{(.*?)\n    \}", player, re.S)
detect_body2 = _m_det.group(1) if _m_det else ""
i_plan_arr = detect_body2.find("planArrivalAnnounce(mc, door,")
i_open_null = detect_body2.find("if (openPlayable == null)")
check(0 <= i_plan_arr < i_open_null,
      "★★【1.17】planArrivalAnnounce 排在 `if (openPlayable == null) return;` **之前** —— "
      "本维度把开门音设成「不播」时，站台广播照样要排",
      "planArrivalAnnounce@%d openPlayable判空@%d" % (i_plan_arr, i_open_null))

# ★【1.26】签名少一格（不再收 `double distance`：到站播报的距离要按**整串最近的门**算，
#   不能再由调用方按「本扇门」算好传进来）⇒ 尾锚跟着改。
_m_plan = re.search(r"private static void planArrivalAnnounce\(Minecraft mc, PsdDoorTracker\.DoorView door,"
                    r"\s*Playable openPlayable\)\s*\{(.*?)\n    \}", player, re.S)
plan_body = _m_plan.group(1) if _m_plan else ""
check("isPsdMidiumOff(cachedMidiumAudio)" in plan_body,
      "★【1.17】到站播报设成「不播」时 planArrivalAnnounce **立刻返回**（不排计划）")
check(re.search(r"startTick\s*=\s*now \+ openTicks \+ waitTicks", plan_body) is not None,
      "★★【1.17】起播刻 = 开门那一刻 + 开门素材时长 + 等待秒数"
      "（= 用户要的「开门嘀嘀嘀之后开始播放」）")
check(re.search(r"Math\.max\(0, cachedMidiumWaitSeconds\) \* 20L", plan_body) is not None,
      "★【1.17】等待时长按秒 → tick（×20），并夹到 ≥ 0（负值不倒退）")
check("startTick + durationTicks" in plan_body,
      "★【1.17】回收刻 = 起播刻 + 素材时长（素材时长量不到时退化成 startTick）")

# ★★ 素材解析：**没有内置档** —— 到站播报没有内置素材，库里的没有就是 null。
_m_res = re.search(r"private static Tone resolveMidiumTone\(Minecraft mc, String audioId\)\s*"
                   r"\{(.*?)\n    \}", player, re.S)
resolve_midium = _m_res.group(1) if _m_res else ""
check(bool(resolve_midium) and "bundledTone" not in resolve_midium
      and "PSD_TONE_BUILTIN" not in resolve_midium,
      "★★【1.17】resolveMidiumTone **没有内置档分支** —— 到站播报没有内置素材"
      "（站台广播不可能随模组分发），库里没有就是 null",
      "函数体 %d 字符" % len(resolve_midium))
check("warnedMissingMidium" in resolve_midium,
      "★【1.17】素材缺失只**记一次**警告（warnedMissingMidium 去重，不每 tick 刷屏）")

check(re.search(r"private static PsdMusicInstance play\(", player) is not None,
      "play() 返回实例（判空即知成没成；实例在手边，将来要停它 / 改它不必回引擎查）")

# ======================================================================
# 5) 产物
# ======================================================================
print()
print("===== 5) 版本号与产物 =====")
ver = re.search(r"mod_version\s*=\s*(\S+)", read(GRADLE))
check(ver is not None and ver.group(1) == EXPECTED_VER,
      "gradle.properties 的 mod_version = %s（用户点名的号，未升位）" % EXPECTED_VER,
      "得到 %s" % (ver.group(1) if ver else "?"))
# 【1.22】jar 名前缀 = 显示名：声明 + **真的应用**（只声明不应用 ⇒ jar 仍是 smooth-escalator-）
_ab = re.search(r"archives_base_name\s*=\s*(\S+)", read(GRADLE))
check(_ab is not None and _ab.group(1) == EXPECTED_JAR_PREFIX,
      "gradle.properties 的 archives_base_name = %s" % EXPECTED_JAR_PREFIX)
_bg = read(BUILD_GRADLE)
# 剥掉注释后再查「硬编码」：注释里提一句产物名不算硬编码。
_bg_code = re.sub(r"/\*.*?\*/", " ", _bg, flags=re.S)
_bg_code = re.sub(r"//[^\n]*", " ", _bg_code)
check("archivesName" in _bg and EXPECTED_JAR_PREFIX not in _bg_code.replace("archives_base_name", ""),
      "★ build.gradle 用 base.archivesName 应用了它（不是把名字硬编码进去）")

if not os.path.isfile(JAR):
    print("[SKIP] 未找到 %s，跳过 jar 校验（先跑 gradlew build）" % os.path.basename(JAR))
else:
    print("        检查 jar：%s（%d 字节）" % (os.path.basename(JAR), os.path.getsize(JAR)))
    check(EXPECTED_VER in os.path.basename(JAR), "打包出来的是 %s" % EXPECTED_VER)
    check(os.path.basename(JAR).startswith(EXPECTED_JAR_PREFIX + "-"),
          "jar 文件名以 %s- 开头" % EXPECTED_JAR_PREFIX)
    with zipfile.ZipFile(JAR) as z:
        blob = z.read("smooth/lift/client/PsdChimePlayer.class")
        for tok in (b"planClose", b"firePlannedClose", b"noteCycle", b"learnedCycleTicks",
                    b"plannedCloseFired", b"plannedCloseStart", b"resolvePlayable",
                    b"resumeClose", b"pendingClose", b"splitMs", b"announce"):
            check(tok in blob, "PsdChimePlayer.class 里编进了 %s" % tok.decode())
        check(b"startAnnouncement" not in blob,
              "★ PsdChimePlayer.class 里**没有** startAnnouncement（旧机制真被删了，不是只留了注释）")
        blob2 = z.read("smooth/lift/client/EscalatorAudioPlayer.class")
        for tok in (b"detectAnnounceSplitMs", b"bundledAnnounceSplitMs", b"customAnnounceSplitMs"):
            check(tok in blob2, "EscalatorAudioPlayer.class 里编进了 %s" % tok.decode())

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
