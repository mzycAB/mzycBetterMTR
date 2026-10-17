# -*- coding: utf-8 -*-
"""把三段屏蔽门素材拆开看：有几段、每段在哪、里面有没有「脉冲串」。

只测量、不判断。输出人类可读的分段表 + 脉冲统计。
"""
import sys
import numpy as np
import soundfile as sf

PY = "C:/Users/user/Desktop/2/mzycBetterMTR-1.20.4/src/main/resources/assets/smoothlift/sounds/audio/"

FILES = ["dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"]


def envelope(x, sr, win_ms=20.0):
    w = max(1, int(sr * win_ms / 1000.0))
    n = len(x) // w
    if n == 0:
        return np.zeros(0), w
    trimmed = x[:n * w].reshape(n, w)
    # 用峰值而不是 RMS：脉冲式的「嘀」在 RMS 里会被摊平
    return np.abs(trimmed).max(axis=1), w


def segments(env, w, sr, thresh):
    """按阈值切成 有声/静音 段，返回 [(start_ms, end_ms, 有声?)]"""
    out = []
    cur = env[0] > thresh if len(env) else False
    start = 0
    for i in range(1, len(env)):
        v = env[i] > thresh
        if v != cur:
            out.append((start * w / sr * 1000.0, i * w / sr * 1000.0, cur))
            cur = v
            start = i
    if len(env):
        out.append((start * w / sr * 1000.0, len(env) * w / sr * 1000.0, cur))
    return out


def pulses(env, w, sr, hi_db, lo_db):
    """带滞回的脉冲检测：上穿 hi 记一次，回落到 lo 以下才允许下一次。"""
    hi = 10 ** (hi_db / 20.0)
    lo = 10 ** (lo_db / 20.0)
    hits = []
    armed = True
    for i, v in enumerate(env):
        if armed and v >= hi:
            hits.append(i * w / sr * 1000.0)
            armed = False
        elif not armed and v <= lo:
            armed = True
    return hits


def rms_db(x, a, b, sr):
    seg = x[int(a * sr / 1000.0):int(b * sr / 1000.0)]
    if len(seg) == 0:
        return -120.0
    r = float(np.sqrt(np.mean(seg.astype(np.float64) ** 2)))
    return 20 * np.log10(max(r, 1e-12))


for name in FILES:
    x, sr = sf.read(PY + name, always_2d=True)
    nch = x.shape[1]
    mono = x.mean(axis=1)
    dur = len(mono) / sr * 1000.0
    print("=" * 72)
    print("%s   时长 %.1fms   采样率 %d   声道 %d   峰值 %.4f"
          % (name, dur, sr, nch, float(np.abs(mono).max())))

    env, w = envelope(mono, sr, 20.0)
    # 阈值取「峰值 - 40dB」，比底噪高、比正文低
    peak = float(np.abs(mono).max())
    thr = peak * 10 ** (-40 / 20.0)
    segs = segments(env, w, sr, thr)
    print("  -- 分段（阈值 %.5f = 峰值-40dB）--" % thr)
    for a, b, loud in segs:
        if b - a < 20:
            continue
        print("     %7.1f ~ %7.1f ms  %5.1f ms  %s   RMS %6.1f dBFS"
              % (a, b, b - a, "有声" if loud else "静音", rms_db(mono, a, b, sr)))

    hits = pulses(env, w, sr, -40.0, -55.0)
    print("  -- 脉冲（20ms 窗峰值，上穿 -40dB / 回落 -55dB）: %d 个 --" % len(hits))
    if 1 < len(hits) <= 40:
        gaps = np.diff(hits)
        print("     前 8 个: %s" % ", ".join("%.0f" % h for h in hits[:8]))
        print("     间隔: 均值 %.1fms  极差 %.1fms  最小 %.1f  最大 %.1f"
              % (gaps.mean(), gaps.max() - gaps.min(), gaps.min(), gaps.max()))
    elif len(hits) > 40:
        gaps = np.diff(hits)
        print("     太多了（%d 个），间隔均值 %.1fms —— 大概是连续信号被切碎" % (len(hits), gaps.mean()))
    print()
