# -*- coding: utf-8 -*-
"""按【1.15】Java 侧的 detectAnnounceSplitMs **逐帧照搬**量一遍三个内置素材。

目的：拿到可核验的数字（分界点 ms、静音段长度、嘀嘀脉冲间隔），
用来写 check-psd-split.py 里的断言，并确认 Java 那条算法在真实素材上真的给出预期值。
"""
import os
import sys

import numpy as np
import soundfile as sf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOUND_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift", "sounds", "audio")

# 与 Java 完全相同的三个常量
SPLIT_SILENCE_LEVEL = 1.0e-3
SPLIT_MIN_SILENCE_MS = 400
SPLIT_MIN_TAIL_MS = 1500


def detect_split_int16(x, sr):
    """照搬 Java：逐帧判「静音」（所有声道 |s| <= 0.001*32767），
    取最后一段「够长的安静」的起点；该安静后面必须还剩 >=1500ms 有声内容。"""
    thr = SPLIT_SILENCE_LEVEL * 32767.0
    ints = np.clip(np.round(x * 32767.0), -32768, 32767).astype(np.int32)
    # 逐声道取「任一声道不安静 → 这一帧有声」
    loud = np.any(np.abs(ints) > thr, axis=1)
    frames = len(loud)
    min_sil = int(SPLIT_MIN_SILENCE_MS / 1000.0 * sr)
    min_tail = int(SPLIT_MIN_TAIL_MS / 1000.0 * sr)
    split = -1
    run_start = -1
    for i in range(frames):
        if not loud[i]:
            if run_start < 0:
                run_start = i
        else:
            if run_start >= 0:
                if i - run_start >= min_sil and frames - run_start >= min_tail:
                    split = int(round(1000.0 * run_start / sr))
                run_start = -1
    return split


def runs(x, sr, threshold=1.0e-3):
    """列出所有「安静段」(start_ms, end_ms)；再列出所有「有声脉冲」的起点（做人眼看结构用）。"""
    ints = np.clip(np.round(x * 32767.0), -32768, 32767).astype(np.int32)
    loud = np.any(np.abs(ints) > threshold * 32767.0, axis=1)
    out = []
    i = 0
    n = len(loud)
    while i < n:
        if not loud[i]:
            j = i
            while j < n and not loud[j]:
                j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    return out, loud


def beeps(x, sr):
    """脉冲起点（ms）+ 脉冲间隔（ms）：找「有声段」的起点。"""
    _, loud = runs(x, sr)
    starts = []
    prev = False
    for i, v in enumerate(loud):
        if v and not prev:
            starts.append(i)
        prev = v
    ms = [s / sr * 1000.0 for s in starts]
    gaps = [round(ms[i + 1] - ms[i], 1) for i in range(len(ms) - 1)]
    return ms, gaps


for name in ("mdoorclose.ogg", "doorclose.ogg", "dooropen.ogg"):
    path = os.path.join(SOUND_DIR, name)
    if not os.path.isfile(path):
        print("!! 缺文件 %s" % path)
        continue
    x, sr = sf.read(path, always_2d=True)
    dur_ms = int(round(1000.0 * x.shape[0] / sr))
    print("=" * 72)
    print("%s  %dHz  %dch  %dms" % (name, sr, x.shape[1], dur_ms))

    split = detect_split_int16(x, sr)
    print("  detect_announce_split_ms() -> %s" % ("%dms (%.3fs)" % (split, split / 1000.0) if split >= 0 else "-1"))

    sil, _ = runs(x, sr)
    big = [(a, b) for (a, b) in sil if (b - a) / sr * 1000.0 >= 300]
    print("  >=300ms 的安静段：%s" % ", ".join("%.0f~%.0fms(%.0fms)" % (a / sr * 1000, b / sr * 1000, (b - a) / sr * 1000) for a, b in big))

    bm, bg = beeps(x, sr)
    if bm:
        # 只统计后半段（嘀嘀）的间隔
        tail = [(m, g) for m, g in zip(bm, bg) if m >= dur_ms * 0.5]
        print("  后半段脉冲起点(ms)：%s" % ", ".join("%.0f" % m for m, _ in tail[:24]))
        if tail:
            gs = [g for _, g in tail]
            print("  后半段脉冲间隔(ms)：min=%.1f max=%.1f 均值=%.1f 个数=%d"
                  % (min(gs), max(gs), sum(gs) / len(gs), len(gs)))
    print()

print("=" * 72)
print("判读（与 Java 常量对照）：")
print("  · 分界点 -1  ⇔ 素材没有「播报 + 嘀嘀」两段结构 → 调用方退回老行为（不分段）")
print("  · 分界点 >0  ⇔ 该点是「语音播报结束 / 静音开始」；后面那串等间隔脉冲就是嘀嘀")
print("  · 间隔 ≈200ms ⇔ 严格等间隔的嘀嘀脉冲（人耳听不出抖动）")
