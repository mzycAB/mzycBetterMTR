# -*- coding: utf-8 -*-
"""逐段拆解 mdoorclose.ogg / doorclose.ogg：静音间隙 + 过零率 + 谱平坦度 + 自相关周期性，
把「机械滑音 / 语音播报 / 嘀嘀嘀」三类内容分开。"""
import sys, os
import numpy as np
import soundfile as sf
sys.stdout.reconfigure(encoding="utf-8")

D = os.path.join("src", "main", "resources", "assets", "smoothlift", "sounds", "audio")

def analyse(name):
    p = os.path.join(D, name)
    x, sr = sf.read(p, always_2d=True)
    mono = x.mean(axis=1).astype(np.float64)
    n = len(mono)
    dur = n / sr
    print("=" * 78)
    print("%s  sr=%d  %.3fs  (%d 帧)" % (name, sr, dur, n))

    # ---- 1) 20ms 窗口：RMS + 过零率 + 谱平坦度（用 FFT 近似）----
    w = int(0.02 * sr)
    k = n // w
    frames = mono[:k * w].reshape(k, w)
    rms = np.sqrt((frames ** 2).mean(axis=1))
    zcr = (np.diff(np.sign(frames), axis=1) != 0).mean(axis=1)
    # 谱平坦度：几何均值/算术均值（语音/噪声高，纯音低）
    sp = np.abs(np.fft.rfft(frames * np.hanning(w), axis=1)) + 1e-12
    flat = np.exp(np.log(sp).mean(axis=1)) / sp.mean(axis=1)
    # 主频
    dom = np.argmax(sp[:, 1:], axis=1) * (sr / w)

    # ---- 2) 找「安静间隙」：RMS < 全曲峰值 RMS 的 4% ----
    thr = rms.max() * 0.04
    quiet = rms < thr
    segs = []
    i = 0
    while i < k:
        j = i
        while j < k and quiet[j] == quiet[i]:
            j += 1
        segs.append((i, j, bool(quiet[i])))
        i = j

    # ---- 3) 打印「有声段」的统计（>0.15s 才打印）----
    print("  --- 有声段（>=0.15s）---")
    print("     区间(s)        时长    RMS均值  过零率  谱平坦  主频Hz  判定")
    for (a, b, q) in segs:
        if q or (b - a) * 0.02 < 0.15:
            continue
        t0, t1 = a * 0.02, b * 0.02
        r = rms[a:b].mean(); z = zcr[a:b].mean(); f = flat[a:b].mean(); d = np.median(dom[a:b])
        # 判定：平坦度低 + 过零率低 + 主频稳定 -> 纯音/嘀嘀；平坦度高 -> 噪声/语音
        if f < 0.02:
            kind = "纯音(嘀嘀?)"
        elif f < 0.10:
            kind = "有谐波的声音(语音?)"
        else:
            kind = "宽带噪声(机械/摩擦?)"
        print("     %6.2f~%6.2f  %5.2fs  %7.4f  %6.3f  %6.4f  %6.0f  %s"
              % (t0, t1, t1 - t0, r, z, f, d, kind))

    # ---- 4) 逐 0.5s 的粗表（便于人眼扫）----
    print("  --- 每 0.5s 概览 ---")
    step = int(0.5 * sr)
    for i in range(0, n, step):
        seg = mono[i:i + step]
        if len(seg) < step // 2:
            break
        r = float(np.sqrt((seg ** 2).mean()))
        ww = int(0.02 * sr)
        kk = len(seg) // ww
        if kk == 0:
            continue
        fr = seg[:kk * ww].reshape(kk, ww)
        z = float((np.diff(np.sign(fr), axis=1) != 0).mean())
        sp2 = np.abs(np.fft.rfft(fr * np.hanning(ww), axis=1)) + 1e-12
        f2 = float((np.exp(np.log(sp2).mean(axis=1)) / sp2.mean(axis=1)).mean())
        print("     t=%5.1fs  RMS=%.4f  ZCR=%.3f  平坦=%.4f" % (i / sr, r, z, f2))
    print()

for nm in ("mdoorclose.ogg", "doorclose.ogg", "dooropen.ogg"):
    analyse(nm)
