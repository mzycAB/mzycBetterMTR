#!/usr/bin/env python3
"""_spec.py -- 给 psd 的三段 ogg 画频谱图（PNG + ASCII 双份）。

为什么需要它：_probe_audio2.py 只用 RMS/过零率/谱平坦度给「有声段」打标签，
但**谱平坦度低不等于「不是语音」**——持续元音（a/o/e）的谐波结构本身就很「纯」，
会被误判成「纯音(嘀嘀?)」。要判断「语音播报在哪一段」，必须看**共振峰**
（formant：能量在 300~3500Hz 里成组出现的横向条带）。所以这里直接出图。

不依赖 matplotlib/PIL（本机没装），自己用 zlib+struct 写 PNG。

用法:
  python _tools/_spec.py                      # 画全部三段的 asset 版
  python _tools/_spec.py _tools/psd_audio_src/mdoorclose.ogg
"""
import os
import struct
import sys
import zlib

import numpy as np
import soundfile as sf

SRC = "src/main/resources/assets/smoothlift/sounds/audio"
DEFAULT = ["dooropen", "mdoorclose", "doorclose"]

FMAX = 8000          # 只画到 8kHz（语音共振峰都在 4k 以下）
NFFT = 1024
HOP = 512            # 11.6ms/列 @44.1k
IMG_H = 420


def write_png(path, rgb):
    """rgb: (H, W, 3) uint8 -> 写 PNG。"""
    h, w, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[y].tobytes() for y in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def spectrogram(x, sr):
    """返回 (mag_db, freqs, times)。"""
    win = np.hanning(NFFT).astype(np.float32)
    if x.ndim > 1:
        x = x.mean(axis=1)
    n = 1 + max(0, (len(x) - NFFT) // HOP)
    frames = np.lib.stride_tricks.as_strided(
        x, shape=(n, NFFT),
        strides=(x.strides[0] * HOP, x.strides[0]), writeable=False)
    spec = np.fft.rfft(frames * win, axis=1)
    mag = np.abs(spec).T                      # (bins, n)
    freqs = np.fft.rfftfreq(NFFT, 1.0 / sr)
    times = (np.arange(n) * HOP + NFFT / 2) / sr
    db = 20.0 * np.log10(mag + 1e-9)
    return db, freqs, times


def to_rgb(db, freqs, fmax, cut_ms=None, t0=None, t1=None, lo=-72.0, hi=-12.0, grid=1000):
    """固定 dB 窗口的「热」色标频谱图。

    ★ 必须用**固定**窗口（而不是按分位数自适应）：自适应会把静音段也拉成满亮度，
      整张图变成一片黄，看不出「哪里在说话」—— 这是本脚本第一版踩过的坑。
      默认 [-72, -12] dB 是「底噪 ~ -70、正常发音 ~ -20」的经验窗。
    """
    keep = freqs <= fmax
    d = db[keep][::-1]                        # 低频放下面
    f = freqs[keep][::-1]
    if t1 is None:
        t1 = db.shape[1] * HOP / 44100.0
    if t0 is None:
        t0 = 0.0
    c0 = int(t0 * 44100.0 / HOP)
    c1 = int(t1 * 44100.0 / HOP)
    c0, c1 = max(0, c0), min(d.shape[1], max(c0 + 1, c1))
    d = d[:, c0:c1]
    norm = np.clip((d - lo) / max(1e-6, hi - lo), 0, 1)
    # 色标：黑 -> 红 -> 黄 -> 白
    r = np.clip(norm * 3.0, 0, 1)
    g = np.clip(norm * 3.0 - 1.0, 0, 1)
    b = np.clip(norm * 3.0 - 2.0, 0, 1)
    rgb = np.stack([r, g, b], axis=-1)
    # 纵向重采样到 IMG_H（最近邻，够用了）
    idx = (np.arange(IMG_H) * rgb.shape[0] // IMG_H)
    rgb = rgb[idx]
    rgb = (rgb * 255).astype(np.uint8).copy()
    # 频率网格线（每 1kHz 一条暗线，方便读轴）
    for k in range(grid, int(fmax) + 1, grid):
        row = int((1.0 - k / fmax) * (IMG_H - 1))
        if 0 <= row < IMG_H:
            rgb[row] = (rgb[row] * 0.35).astype(np.uint8)
    if cut_ms is not None:
        # 标记剪头位置（绿色竖线；只是标记，与涨红跌绿无关）
        nt = rgb.shape[1]
        col = int((cut_ms / 1000.0 - t0) / max(1e-6, t1 - t0) * nt)
        if 0 <= col < nt:
            rgb[:, col] = (0, 255, 0)
    # 时间刻度：每秒一条暗线
    nt = rgb.shape[1]
    span = t1 - t0
    for sec in range(int(np.ceil(t0)), int(t1) + 1):
        col = int((sec - t0) / span * nt)
        if 0 <= col < nt:
            rgb[:, col] = (rgb[:, col] * 0.35).astype(np.uint8)
    return rgb


def ascii_map(db, freqs, fmax, times, cols=110, rows=22):
    """低频在下、高频在上的 ASCII 频谱；顺带标出语音频带(300~3400Hz)。"""
    keep = freqs <= fmax
    d = db[keep]
    f = freqs[keep]
    nt = d.shape[1]
    col_idx = (np.arange(cols) * nt // cols)
    out = []
    # 行：从高频到低频
    edges = np.linspace(f.min(), f.max(), rows + 1)
    ramp = " .:-=+*#%@"
    for r in range(rows - 1, -1, -1):
        lo, hi = edges[r], edges[r + 1]
        sel = (f >= lo) & (f < hi)
        if not sel.any():
            continue
        band = d[sel].mean(axis=0)[col_idx]
        b_lo, b_hi = np.percentile(d[sel], 5), np.percentile(d[sel], 99)
        t = np.clip((band - b_lo) / max(1e-6, b_hi - b_lo), 0, 1)
        row = "".join(ramp[int(v * (len(ramp) - 1))] for v in t)
        tag = "语音带" if 300 <= lo and hi <= 3600 else "      "
        out.append(f"{int(lo):5d}Hz {tag}|{row}|")
    # 时间轴
    axis = [" "] * cols
    for sec in range(int(times[-1]) + 1):
        c = int(sec / times[-1] * cols)
        if c < cols:
            s = str(sec)
            for i, ch in enumerate(s):
                if c + i < cols:
                    axis[c + i] = ch
    out.append("           |" + "".join(axis) + "|  (秒)")
    return "\n".join(out)


def main():
    argv = sys.argv[1:]
    files = argv if argv else [os.path.join(SRC, k + ".ogg") for k in DEFAULT]
    outdir = "_tools/out"
    os.makedirs(outdir, exist_ok=True)
    for path in files:
        if not os.path.exists(path):
            print(f"!! 找不到 {path}")
            continue
        x, sr = sf.read(path, always_2d=False)
        db, freqs, times = spectrogram(x, sr)
        base = os.path.splitext(os.path.basename(path))[0]
        dur = len(x) / sr
        print("=" * 100)
        print(f"{path}   sr={sr}  {dur:.3f}s  帧数={len(times)}")
        # 分段视图：方便逐段看结构（语音 / 静音 / 嘀嘀）
        views = [("full", 0.0, None, FMAX, None)]
        seg = 2.75
        t = 0.0
        while t < dur - 0.2:
            views.append((f"z{int(t * 10):03d}", t, min(t + seg, dur), FMAX, None))
            t += seg
        for tag, t0, t1, fmax, cut in views:
            rgb = to_rgb(db, freqs, fmax, cut_ms=cut, t0=t0, t1=t1)
            out = os.path.join(outdir, f"spec_{base}_{tag}.png")
            write_png(out, rgb)
            print(f"  写出 {out}  (t={t0:.1f}s-{t1 if t1 else dur:.1f}s, f<={fmax}Hz)")
        print(ascii_map(db, freqs, FMAX, times))
        # 语音带(300~3400Hz)与低频带(<300Hz)的能量对比，逐 0.5s
        band_v = (freqs >= 300) & (freqs <= 3400)
        band_l = freqs < 300
        step = int(0.5 * sr / HOP)
        print("  t(s)   语音带dB  低频dB   语音-低频")
        for i in range(0, db.shape[1], step):
            v = db[band_v][:, i:i + step].mean()
            l = db[band_l][:, i:i + step].mean()
            print(f"  {times[i]:5.1f}   {v:7.1f}  {l:7.1f}   {v - l:+7.1f}")


if __name__ == "__main__":
    main()
