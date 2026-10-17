# -*- coding: utf-8 -*-
import sys, os
import numpy as np
import soundfile as sf
sys.stdout.reconfigure(encoding="utf-8")
D = "src/main/resources/assets/smoothlift/sounds/audio"
for name in ("dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"):
    p = os.path.join(D, name)
    x, sr = sf.read(p, always_2d=True)
    mono = x.mean(axis=1)
    n = len(mono)
    dur = n / sr
    # 100ms 窗口 RMS 包络
    w = int(0.1 * sr)
    k = n // w
    env = np.sqrt((mono[:k*w].reshape(k, w) ** 2).mean(axis=1))
    peak = float(np.max(np.abs(mono)))
    print("== %s ==  sr=%d  dur=%.3fs  peak=%.4f" % (name, sr, dur, peak))
    # 找「明显有声」的起点：RMS 超过峰值包络 10% 的第一个窗口
    thr_hi = env.max() * 0.10
    idx_hi = int(np.argmax(env >= thr_hi))
    idx_last = int(len(env) - 1 - np.argmax(env[::-1] >= thr_hi))
    print("   包络(RMS/0.1s) 前 120 个窗口：")
    line = []
    for i in range(min(120, len(env))):
        line.append("%.3f" % env[i])
    for i in range(0, len(line), 10):
        print("     t=%.1fs: %s" % (i * 0.1, " ".join(line[i:i+10])))
    print("   >门限(峰值10%%) 区间: %.1fs ~ %.1fs  (共 %.1fs)" % (idx_hi*0.1, idx_last*0.1, (idx_last-idx_hi)*0.1))
    print("   尾部 30 个窗口：")
    tail = ["%.3f" % v for v in env[-30:]]
    print("     " + " ".join(tail))
