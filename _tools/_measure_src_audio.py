# -*- coding: utf-8 -*-
"""量一下 4 个待替换内置素材的参数（合成"类似"素材要以这些为锚）。"""
import numpy as np, soundfile as sf, os

AUD = None

def load(name):
    global AUD
    if AUD is None:
        for cand in (
            r"C:/Users/user/Desktop/3/1.20.4/mzycBetterMTR-1.20.4/src/main/resources/assets/smoothlift/sounds/audio",
        ):
            if os.path.isdir(cand):
                AUD = cand
                break
    x, sr = sf.read(os.path.join(AUD, name), always_2d=True)
    return x, sr


def env(x, sr, win_ms=10.0):
    m = x.mean(axis=1)
    w = max(1, int(sr * win_ms / 1000.0))
    n = len(m) // w
    e = np.sqrt((m[:n * w].reshape(n, w) ** 2).mean(axis=1))
    return e, (np.arange(n) + 0.5) * w / sr * 1000.0


def pulses(e, t, hi, lo):
    on = False
    st = []
    for i, v in enumerate(e):
        if not on and v >= hi:
            st.append(t[i]); on = True
        elif on and v < lo:
            on = False
    return st


print("=" * 78)
# ---------- liftmusic ----------
x, sr = load("liftmusic.ogg")
m = x.mean(axis=1)
print("liftmusic: sr=%d dur=%.4fs peak=%.4f rms=%.4f" % (sr, len(m) / sr, np.abs(m).max(), np.sqrt((m ** 2).mean())))
# 频谱
S = np.abs(np.fft.rfft(m * np.hanning(len(m))))
f = np.fft.rfftfreq(len(m), 1.0 / sr)
top = np.argsort(S)[::-1][:14]
top = sorted(top)
print("  峰值谱线: " + ", ".join("%.1fHz(%.4f)" % (f[i], S[i] / S.max()) for i in top if f[i] > 20))
e, t = env(x, sr, 5.0)
print("  包络(5ms窗) 前12: " + ", ".join("%.3f" % v for v in e[:12]))
print("  包络 中/后: " + ", ".join("%.3f" % v for v in e[len(e)//2-3:len(e)//2+3]) + " ... " + ", ".join("%.3f" % v for v in e[-6:]))

for name in ("dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"):
    x, sr = load(name)
    m = x.mean(axis=1)
    print("-" * 78)
    print("%s: sr=%d dur=%.4fs peak=%.4f rms=%.4f" % (name, sr, len(m) / sr, np.abs(m).max(), np.sqrt((m ** 2).mean())))
    e, t = env(x, sr, 20.0)
    hi = e.max() * 0.25
    lo = e.max() * 0.06
    st = pulses(e, t, hi, lo)
    print("  脉冲 %d 个" % len(st))
    if len(st) > 1:
        gaps = [round(st[i + 1] - st[i], 1) for i in range(len(st) - 1)]
        print("  间隔(ms): " + ", ".join("%.0f" % g for g in gaps))
    # 找最后一段长安静（分界）
    sil = e < e.max() * 0.02
    runs = []
    i = 0
    while i < len(sil):
        if sil[i]:
            j = i
            while j < len(sil) and sil[j]:
                j += 1
            runs.append((t[i], t[j - 1], t[j - 1] - t[i]))
            i = j
        else:
            i += 1
    runs = [r for r in runs if r[2] >= 300]
    print("  >=300ms 安静段: " + ", ".join("%.0f~%.0f(%.0fms)" % r for r in runs[-4:]))
    # 嘀嘀段的谱
    if len(st) >= 3:
        st2 = st[len(st)//2]
        i0 = int(st2 * sr / 1000.0)
        seg = m[i0:i0 + int(0.12 * sr)]
        if len(seg) > 64:
            S = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
            f = np.fft.rfftfreq(len(seg), 1.0 / sr)
            k = np.argmax(S[1:]) + 1
            print("  嘀嘀段主频 ≈ %.1f Hz，峰值 %.4f" % (f[k], np.abs(seg).max()))
    # 前段（人声）峰值
    i1 = int(len(m) * 0.3)
    print("  前段|峰值| %.4f  RMS %.4f" % (np.abs(m[:i1]).max(), np.sqrt((m[:i1] ** 2).mean())))
