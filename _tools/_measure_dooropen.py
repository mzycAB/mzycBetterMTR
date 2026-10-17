# -*- coding: utf-8 -*-
"""细看 dooropen.ogg 的内部结构：两段 1 秒的声音里到底是「一声长音」还是「一串嘀」。
用 5ms 窗口 + 带滞回检测，并给出每段的频率特征（区分机械声 vs 蜂鸣）。
"""
import numpy as np
import soundfile as sf

PY = "C:/Users/user/Desktop/2/mzycBetterMTR-1.20.4/src/main/resources/assets/smoothlift/sounds/audio/dooropen.ogg"

x, sr = sf.read(PY, always_2d=True)
mono = x.mean(axis=1)
print("时长 %.1fms  采样率 %d  声道 %d" % (len(mono) / sr * 1000.0, sr, x.shape[1]))

# 5ms 窗包络
w = int(sr * 0.005)
n = len(mono) // w
env = np.abs(mono[:n * w].reshape(n, w)).max(axis=1)
t = np.arange(n) * w / sr * 1000.0

peak = float(np.abs(mono).max())
print("峰值 %.4f" % peak)

# 带滞回脉冲检测（-45dB 触发 / -55dB 复位）
hi = peak * 10 ** (-30 / 20.0)
lo = peak * 10 ** (-50 / 20.0)
hits = []
armed = True
for i, v in enumerate(env):
    db = 20 * np.log10(max(v, 1e-12) / peak)
    if armed and db >= -30:
        hits.append(t[i])
        armed = False
    elif not armed and db <= -50:
        armed = True
print("脉冲数（-30dB 触发 / -50dB 复位）: %d" % len(hits))
if len(hits) > 1:
    g = np.diff(hits)
    print("  脉冲时刻(ms): %s" % ", ".join("%.0f" % h for h in hits[:20]))
    print("  间隔(ms): %s" % ", ".join("%.1f" % v for v in g[:20]))

# 每 50ms 打一条包络（dB），看块内是否起伏
print("\n每 50ms 的包络(dBFS)，'.' = 静音：")
line = []
for i in range(0, n, 10):
    db = 20 * np.log10(max(env[i], 1e-12) / peak)
    line.append("%4.0f " % (t[i]) if db > -30 else "  .  ")
print("  " + "".join(line))

# 频谱：看是宽带（机械/噪声）还是单音（蜂鸣）
print("\n各块的谱形（前 6 个峰，Hz @ 相对幅度）：")
for a, b, tag in [(30, 1020, "块1"), (1210, 2230, "块2")]:
    seg = mono[int(a * sr / 1000):int(b * sr / 1000)]
    if len(seg) < 1024:
        continue
    win = seg * np.hanning(len(seg))
    S = np.abs(np.fft.rfft(win))
    f = np.fft.rfftfreq(len(win), 1.0 / sr)
    S /= S.max()
    # 找局部峰
    pk = []
    for i in range(2, len(S) - 2):
        if S[i] > S[i - 1] and S[i] >= S[i + 1] and S[i] > 0.06 and f[i] < 8000:
            pk.append((S[i], f[i]))
    pk.sort(reverse=True)
    pk = sorted(pk[:6], key=lambda p: p[1])
    print("  %s [%d,%d]ms: %s" % (tag, a, b,
          ", ".join("%.0fHz@%.2f" % (fr, am) for am, fr in pk)))
    # 谱平坦度：接近 1 = 噪声/宽带，接近 0 = 单音
    flat = float(np.exp(np.mean(np.log(np.maximum(S, 1e-9)))) / max(np.mean(S), 1e-9))
    print("        谱平坦度 %.4f（1=宽带噪声，0=单音）" % flat)
