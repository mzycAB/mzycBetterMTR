# -*- coding: utf-8 -*-
"""Script: import_psd_audio.py

把**用户提供**的三个屏蔽门开关门音频导入为模组内置素材：

    源（工作区根目录）                    目标（jar 内）
    dooropen.ogg      ──►  assets/smoothlift/sounds/audio/dooropen.ogg     开门（指令名 default）
    doorclose.ogg     ──►  assets/smoothlift/sounds/audio/doorclose.ogg    关门（指令名 default-c）
    mdoorclose.ogg    ──►  assets/smoothlift/sounds/audio/mdoorclose.ogg   关门备选（指令名 default-m）

## 只做一件事：剪掉首尾的**数字静音**

三个源文件的开头分别有 505ms / 283ms / 88ms 的**纯静音**（采样值恒为 0），
结尾也有零头。提示音是「门机开始动的那一瞬」就该响的脉冲，
开头挂半秒空白会让它听起来**慢半拍** —— 这一条在扶梯那边真被用户报过
（「开头不要留空白」），所以这里按同一套规矩处理，并且**把量出来的数字打出来**
（「开头有没有空白」是可核验的，不靠耳朵判断）。

判据与余量：
  * 越阈 = |x| >= 1e-4（约 -80 dBFS，比任何可闻内容低两个数量级）；
  * 前面留 {@link PRE_ROLL_MS} = 5ms、后面留 {@link POST_ROLL_MS} = 20ms：
    剪切点一定落在「已经听不见」的区域，所以**不会切掉起振或留下咔嗒声**；
  * **不动增益、不重采样、不动声道数** —— 音量由指令/界面（1~1000）说了算，
    这里改增益会让「100 = 原始音量」这句话失去意义。

原始素材会被**收进工程**（`_tools/psd_audio_src/`）再处理：
这样即使工作区根目录那三个文件被移走，也能原样重跑本脚本。

用法：
    python _tools/import_psd_audio.py [源目录]
    （源目录默认 = 工程的上一级，即工作区根目录）
"""

import os
import shutil
import sys

import numpy as np
import soundfile as sf

# 越阈阈值（约 -80 dBFS）与剪切余量
THRESHOLD = 1e-4
PRE_ROLL_MS = 5.0
POST_ROLL_MS = 20.0

# (文件名, 用途说明)
FILES = [
    ("dooropen.ogg", "开门（指令名 default）"),
    ("doorclose.ogg", "关门（指令名 default-c）"),
    ("mdoorclose.ogg", "关门备选（指令名 default-m）"),
]


def measure(x, sr):
    """返回 (首个越阈采样点, 末个越阈采样点)；全静音返回 (-1, -1)。"""
    loud = np.nonzero(np.abs(x) >= THRESHOLD)[0]
    if len(loud) == 0:
        return -1, -1
    return int(loud[0]), int(loud[-1])


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    proj = os.path.dirname(here)
    src_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(proj)
    raw_dir = os.path.join(here, "psd_audio_src")
    out_dir = os.path.join(proj, "src", "main", "resources", "assets",
                           "smoothlift", "sounds", "audio")
    os.makedirs(raw_dir, exist_ok=True)
    os.makedirs(out_dir, exist_ok=True)

    print("源目录   = %s" % src_dir)
    print("原始素材 = %s（收进工程，保证可复现）" % raw_dir)
    print("输出目录 = %s" % out_dir)
    print()

    total = 0
    for name, note in FILES:
        raw = os.path.join(raw_dir, name)
        if not os.path.exists(raw):
            origin = os.path.join(src_dir, name)
            if not os.path.exists(origin):
                raise SystemExit("[fail] 找不到素材：%s（也没有已收录的 %s）" % (origin, raw))
            shutil.copyfile(origin, raw)
            print("  [copy] %s -> %s" % (origin, raw))

        d, sr = sf.read(raw, always_2d=True)
        mono = d.mean(axis=1) if d.shape[1] > 1 else d[:, 0]
        first, last = measure(mono, sr)
        if first < 0:
            raise SystemExit("[fail] %s 整段都是静音（阈值 %g）" % (name, THRESHOLD))

        pre = int(PRE_ROLL_MS / 1000.0 * sr)
        post = int(POST_ROLL_MS / 1000.0 * sr)
        a = max(0, first - pre)
        b = min(len(mono), last + 1 + post)
        cut = d[a:b]

        path = os.path.join(out_dir, name)
        # 保持原采样率/声道数，只重编码（libsndfile 的 vorbis；失败则报错让调用者处理）
        sf.write(path, cut.astype(np.float32), sr, format="OGG", subtype="VORBIS")
        size = os.path.getsize(path)
        total += size

        lead_ms = first / sr * 1000.0
        tail_ms = (len(mono) - 1 - last) / sr * 1000.0
        print("[ok] %-16s %-26s %6.3fs -> %6.3fs   sr=%d ch=%d  峰值=%.3f"
              % (os.path.basename(path), note, len(mono) / sr, len(cut) / sr,
                 sr, d.shape[1], float(np.abs(cut).max())))
        print("       剪掉开头静音 %.2fms / 结尾静音 %.2fms（阈值 %g，前留 %.0fms 后留 %.0fms）"
              % (lead_ms, tail_ms, THRESHOLD, PRE_ROLL_MS, POST_ROLL_MS))
        print("       落盘 %d 字节；改后起点 = %.2fms（= 0.00 说明开头没有空白）"
              % (size, (first - a) / sr * 1000.0))

    print()
    print("共 %d 个内置素材，合计 %d 字节" % (len(FILES), total))


if __name__ == "__main__":
    main()
