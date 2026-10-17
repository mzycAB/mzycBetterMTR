# -*- coding: utf-8 -*-
"""用「滞回 + 最短间隔」正确地数嘀嘀脉冲（上一版把单个采样当脉冲了）。

只回答两个问题：
  1) 分界点之后那串嘀嘀，脉冲间隔是不是严格等间隔的 200ms？
  2) 分界点之前（语音播报段）的句间停顿有多长（用来证明 500ms 的阈值不会误切语音）？
"""
import os

import numpy as np
import soundfile as sf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOUND_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift", "sounds", "audio")


def envelope_ms(x, sr, win_ms=10.0):
    """10ms 窗的 RMS 包络 + 对应时刻（ms）。"""
    w = max(1, int(sr * win_ms / 1000.0))
    n = len(x) // w
    e = x[:n * w].reshape(n, w, x.shape[1])
    env = np.sqrt((e ** 2).mean(axis=(1, 2)))
    t = (np.arange(n) + 0.5) * w / sr * 1000.0
    return env, t, win_ms


def pulses(env, t, hi=0.03, lo=0.005):
    """滞回检脉冲：env 上穿 hi 记一次起点，必须回落 < lo 才允许下一次触发。"""
    on = False
    starts = []
    for i, v in enumerate(env):
        if not on and v >= hi:
            starts.append(t[i])
            on = True
        elif on and v < lo:
            on = False
    return starts


for name in ("mdoorclose.ogg", "doorclose.ogg"):
    x, sr = sf.read(os.path.join(SOUND_DIR, name), always_2d=True)
    env, t, win = envelope_ms(x, sr)
    st = pulses(env, t)
    print("=" * 72)
    print("%s  %dms  包络窗 %.0fms" % (name, int(round(1000.0 * len(x) / sr)), win))
    print("  检到脉冲 %d 个，起点(ms)：%s" % (len(st), ", ".join("%.0f" % s for s in st)))
    if len(st) > 1:
        gaps = [round(st[i + 1] - st[i], 1) for i in range(len(st) - 1)]
        print("  间隔(ms)：%s" % ", ".join("%.1f" % g for g in gaps))
        # 后半串（嘀嘀）与前半串（语音）分开看
        tail = [g for s, g in zip(st, gaps) if s >= 6500]
        head = [g for s, g in zip(st, gaps) if s < 6500]
        if tail:
            print("  ★ 分界点之后的间隔：min=%.1f max=%.1f 均值=%.1f（n=%d）"
                  % (min(tail), max(tail), sum(tail) / len(tail), len(tail)))
            print("    ⇒ 严格等间隔吗？极差 %.1fms" % (max(tail) - min(tail)))
        if head:
            print("    分界点之前的间隔（语音音节/句读）：min=%.1f max=%.1f 均值=%.1f（n=%d）"
                  % (min(head), max(head), sum(head) / len(head), len(head)))
            print("    ⇒ 句间最长停顿 %.0fms（要 < 500ms 才不会被误当分界）" % max(head))

    # 分界点之后第一声「嘀」的确切时刻
    print("  最后一声起点 %.0fms，素材总长 %dms ⇒ 末尾还有 %.0fms 余音"
          % (st[-1], int(round(1000.0 * len(x) / sr)), 1000.0 * len(x) / sr - st[-1]))
    print()
