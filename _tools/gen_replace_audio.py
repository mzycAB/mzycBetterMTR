# -*- coding: utf-8 -*-
"""Script: gen_replace_audio.py

【Repair.zip 那一轮】把 4 段**外部来源**的屏蔽门 / 直梯内置素材换成**纯合成**的「类似」素材。

为什么要换（用户点名）：
  `liftmusic.ogg` / `dooropen.ogg` / `doorclose.ogg` / `mdoorclose.ogg` 是**用户提供**的素材
  （见 `import_psd_audio.py` 与 `check-lift-move-sound.py`），来源不可追 ⇒ 有版权风险。
  用户要求：把（实测为**人声语音播报**的）`up.ogg` / `down.ogg` 直接删掉（静音），
  其余四段改成**自己合成**的「相似」音频；被删掉的原音全部收进 `Repair.zip`（带密码）。

本脚本只做「合成」这一件事，产出替换用的 4 个 ogg：

  源（量出来的锚点，见 `_measure_src_audio.py` 的输出）            产物
  ------------------------------------------------------------------------------
  liftmusic.ogg   0.859s / f0≈801.882Hz / 峰值 0.1873      →  liftmusic.ogg（同长同音色、纯合成）
  dooropen.ogg    2.283s / 峰值 0.519                      →  dooropen.ogg（气动开门：轻咔 + 放气 + 收尾碰）
  doorclose.ogg   嘀嘀段 200ms 等间隔 / 主频≈516.7Hz / 峰值≈0.3835
  mdoorclose.ogg  同上                                     →  两段都换成**纯嘀嘀串**（20 响 @200ms = 4.000s）
                                                              —— 人声播报段被删掉，素材从 10.5s 缩到 4.0s。
                                                              ★ 为什么偏偏是 4.000s：MTR4 的门程正好
                                                              80 tick = 4000ms（见 check-psd-align.py 第 1 节
                                                              的字节码直算）。播放端的对齐算式是
                                                              start = 量化(素材时长 − 门还在走的毫秒数)，
                                                              素材 = 门程 ⇒ start ≈ 0 ⇒ **整串嘀嘀完整播完、
                                                              结尾正好落在门关上那一刻**（若素材比门程短，
                                                              算式会退化成「从头整段播」，结尾提前 550ms 收声）。

★ 为什么「关门素材」要缩短而不是凑够 10.5 秒：
  原素材 10.5 秒里 **6.6 秒是人声播报**，删掉人声之后就只剩那串嘀嘀。
  播放端（`PsdChimePlayer#planClose` / `resumeClose`）本来就是按「素材有多长」反推该从第几毫秒开始播、
  使**结尾正好落在门关上那一刻**；素材变短之后这条算式自然给出「提前一个门程播」，
  不需要任何特判（且 `announce` 段消失 ⇒ 自动退化成「纯嘀嘀」那一档的行为）。

★ 编码：**必须 Vorbis**（Minecraft 只认 Ogg Vorbis），且**不能用 soundfile 的 VORBIS 编码器**
  （本机 libsndfile 0.14 会直接把进程干掉，无输出、退出码 127）⇒ 统一走 `imageio_ffmpeg` 自带
  的静态 ffmpeg（v7.1，含 libvorbis）。

用法：
    python _tools/gen_replace_audio.py [输出目录]
    （默认输出到 4 个工程共用的 `_repair_audio/replace/`，再由 `apply_replace_audio.py` 拷进去）
"""

import math
import os
import subprocess
import sys

import numpy as np

SR = 44100

# 4 个工程共用的中转目录（相对工程根）
DEFAULT_OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "_repair_audio")

# ---- 量出来的锚点（见 _measure_src_audio.py）----------------------------------
LIFT_F0 = 801.882          # liftmusic 基频（Hz）；峰值谱线 4009.5 = h5
LIFT_PEAK = 0.1873         # liftmusic 峰值
LIFT_RMS = 0.0489          # liftmusic 整段 RMS（★ 响度以**这个**为准，见 normalize_rms）
LIFT_SECONDS = 0.859

DOOR_BEEP_F = 516.7        # 关门嘀嘀的主频
DOOR_BEEP_PEAK = 0.3835    # doorclose 嘀嘀段峰值
DOOR_BEEP_STEP_MS = 200.0  # 嘀嘀严格等间隔 200ms（实测最后 17 个间隔全是 200）
DOOR_BEEP_COUNT = 20
DOOR_TOTAL_MS = DOOR_BEEP_STEP_MS * DOOR_BEEP_COUNT   # 4000ms
# 原 doorclose.ogg 的**嘀嘀段**（6.95s~10.54s）RMS —— 新素材对齐它（不含已删掉的人声播报段）
DOOR_BEEP_RMS = 0.1340

OPEN_SECONDS = 2.283       # 与 dooropen.ogg 原长一致（播放端的提前量算式按素材长度算）
OPEN_PEAK = 0.519
OPEN_RMS = 0.1891          # 与新素材要匹配的原件整段 RMS


# ----------------------------------------------------------------------
# 基础件（与 gen_psd_audio.py 同一套手法：不用 scipy，纯 numpy）
# ----------------------------------------------------------------------

def rbj_bandpass(x, f0, q):
    w0 = 2.0 * math.pi * f0 / SR
    alpha = math.sin(w0) / (2.0 * q)
    b0, b1, b2 = alpha, 0.0, -alpha
    a0, a1, a2 = 1.0 + alpha, -2.0 * math.cos(w0), 1.0 - alpha
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    y = np.empty_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i in range(len(x)):
        v = x[i]
        out = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, v
        y2, y1 = y1, out
        y[i] = out
    return y


def one_pole_lowpass(x, cutoff):
    a = math.exp(-2.0 * math.pi * cutoff / SR)
    y = np.empty_like(x)
    prev = 0.0
    for i in range(len(x)):
        prev = (1.0 - a) * x[i] + a * prev
        y[i] = prev
    return y


def envelope(n, points):
    xs = np.array([p[0] for p in points], dtype=float) * (n - 1)
    ys = np.array([p[1] for p in points], dtype=float)
    return np.interp(np.arange(n), xs, ys)


def fade_edges(x, ms=3.0):
    """首尾各做一小段余弦渐入渐出，避免切点爆音（起音本身由包络控制）。"""
    k = max(1, int(SR * ms / 1000.0))
    if len(x) < 2 * k:
        return x
    ramp = 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, k))
    x[:k] *= ramp
    x[-k:] *= ramp[::-1]
    return x


def normalize_peak(x, peak):
    m = np.abs(x).max()
    return x if m <= 0 else x * (peak / m)


def normalize_rms(x, target_rms):
    """按 **RMS** 对齐响度（不是按峰值）。

    ★ 为什么用 RMS 而不是峰值：游戏里的「100 = 原始音量」是一条**响度**约定，
    而两段素材的波峰因数不同（原件 liftmusic 实测 RMS/峰值 = 0.261，纯合成版本约 0.39），
    按峰值对齐就会让新素材听起来比原件响 3.5dB。按 RMS 对齐才是"同一音量下一样响"。
    """
    r = math.sqrt(float((x ** 2).mean()))
    return x if r <= 0 else x * (target_rms / r)


# ----------------------------------------------------------------------
# 1) liftmusic.ogg —— 直梯开关门提示音（谐波长音）
# ----------------------------------------------------------------------

def make_liftmusic():
    n = int(SR * LIFT_SECONDS)
    t = np.arange(n) / SR
    # 实测峰值谱线 = f0 的 5/6/7 次谐波（4009.5 / 4809.5 / 5612.6Hz），
    # 再补一点 8/9 次谐波让尾巴不至于太"干"；幅度按实测的相对高度摆。
    # 相位全部错开（各带一点自身频率的相位偏移）⇒ 波形与原件不同，听感同族。
    partials = [(5, 1.00, 0.35), (6, 0.62, 1.10), (7, 0.34, 2.05), (8, 0.16, 0.80), (9, 0.10, 2.60)]
    body = np.zeros(n)
    for h, amp, phase in partials:
        body += amp * np.sin(2.0 * math.pi * LIFT_F0 * h * t + phase)
    # 包络：约 8ms 起振 -> 平段（原件平段几乎不衰减）-> 末尾 18% 收尾
    body *= envelope(n, [(0.0, 0.0), (0.01, 0.85), (0.02, 1.0), (0.82, 0.95), (0.93, 0.45), (1.0, 0.0)])
    body = fade_edges(body, 2.0)
    body = normalize_rms(body, LIFT_RMS)
    return np.stack([body, body], axis=1)


# ----------------------------------------------------------------------
# 2) dooropen.ogg —— 屏蔽门开门（气动：轻咔 + 放气「嘶」 + 收尾轻碰）
# ----------------------------------------------------------------------

def clack(n, freq, decay, gain, t0_ratio, noise_mix=0.25, seed=7):
    rng = np.random.default_rng(seed)
    t = np.arange(n) / SR
    start = int(t0_ratio * (n - 1))
    env = np.zeros(n)
    tail = np.arange(n - start) / SR
    env[start:] = np.exp(-tail * decay) * np.minimum(tail / 0.0006, 1.0)
    body = (np.sin(2.0 * math.pi * freq * t)
            + 0.45 * np.sin(2.0 * math.pi * freq * 2.7 * t)
            + 0.20 * np.sin(2.0 * math.pi * freq * 4.9 * t)) * env
    return (body + rng.standard_normal(n) * env * noise_mix) * gain


def hiss(n, f0, q, gain, points, seed=20260920):
    rng = np.random.default_rng(seed)
    noise = one_pole_lowpass(rng.standard_normal(n), 9000.0)
    noise = rbj_bandpass(noise, f0, q)
    return noise * envelope(n, points) * gain


def make_dooropen():
    n = int(SR * OPEN_SECONDS)
    x = np.zeros(n)
    # ① 起头轻「咔」（门机脱扣）
    x += clack(n, 430.0, 26.0, 0.35, 0.005, 0.30, seed=101)
    # ② 放气「嘶——」：几乎铺满整段（原件也是「整段有底噪、不是几个孤立脉冲」——
    #    实测原件 RMS 0.189 / 峰值 0.519 ⇒ RMS/峰值 ≈ 0.36，所以持续层必须够厚。
    x += hiss(n, 2100.0, 0.75, 1.60,
              [(0.00, 0.0), (0.03, 0.0), (0.08, 0.90), (0.50, 1.00), (0.85, 0.72), (0.97, 0.0)], seed=202)
    # ③ 第二下（门叶过中段）轻碰
    x += clack(n, 350.0, 20.0, 0.20, 0.52, 0.22, seed=303)
    # ④ 收尾「哒」——门滑到尽头
    x += clack(n, 620.0, 34.0, 0.26, 0.88, 0.28, seed=404)
    # ⑤ 低频行程底噪（"有质量的门"而不是纯电子音）
    x += hiss(n, 160.0, 1.1, 0.55,
              [(0.0, 0.0), (0.06, 0.75), (0.30, 1.0), (0.75, 0.9), (0.95, 0.3), (1.0, 0.0)], seed=505)
    # ⑥ 软限幅：带通白噪声的**波峰因数**约 5（RMS/峰值 ≈ 0.2），而原件是录出来的
    #    连续底噪（RMS/峰值 ≈ 0.36）⇒ 不做这一步，新素材听感会明显比原件"薄 / 轻"。
    #    tanh 软削顶把波峰因数压下来（实测 k=2.2 ⇒ RMS/峰值 ≈ 0.36，与原件同量级），
    #    再归一化到原峰值 —— 两段素材在游戏里的响度就一致了。
    x = np.tanh(2.2 * x / np.abs(x).max()) * np.abs(x).max()
    x = fade_edges(x, 4.0)
    x = normalize_rms(x, OPEN_RMS)
    return np.stack([x, x], axis=1)


# ----------------------------------------------------------------------
# 3) doorclose.ogg / mdoorclose.ogg —— 屏蔽门关门（纯嘀嘀串）
# ----------------------------------------------------------------------

def make_house_beep(n, freq, gain, seed=900):
    """一声「嘀」：主频 + 2/3 次谐波。

    包络用**指数衰减 19/s**：实测原件的单响（包络超过峰值 12% 的连续区）= **113ms**，
    `exp(-t·19)` 落到 12% 正好在 113ms ⇒ 新嘀嘀与原件"一样长"。
    """
    t = np.arange(n) / SR
    env = np.exp(-t * 19.0) * np.minimum(t / 0.0015, 1.0)
    body = (np.sin(2.0 * math.pi * freq * t)
            + 0.35 * np.sin(2.0 * math.pi * freq * 2.0 * t + 0.9)
            + 0.15 * np.sin(2.0 * math.pi * freq * 3.0 * t + 2.1))
    return body * env * gain


def make_doorclose():
    total = int(SR * DOOR_TOTAL_MS / 1000.0)
    x = np.zeros(total)
    beep_n = int(SR * 0.17)
    for i in range(DOOR_BEEP_COUNT):
        # 强度从头到尾略降（收束感），但保持与原件同量级
        g = 1.0 - 0.28 * (i / float(DOOR_BEEP_COUNT - 1))
        seg = make_house_beep(beep_n, DOOR_BEEP_F, g, seed=1000 + i)
        off = int(round(i * DOOR_BEEP_STEP_MS / 1000.0 * SR))
        x[off:off + beep_n] += seg
    # 软限幅（与 dooropen 同一条理由）：原件的嘀嘀是**录进来的**（RMS/峰值 = 0.358），
    # 纯正弦簇的 RMS/峰值只有 0.16 ⇒ 直接把峰值对齐会让它听起来比原件轻 6dB。
    # tanh 削顶把波峰因数压到 0.36 之后，**峰值与 RMS 同时**落在原件的量级上。
    x = np.tanh(3.0 * x / np.abs(x).max()) * np.abs(x).max()
    x = fade_edges(x, 4.0)
    x = normalize_peak(x, DOOR_BEEP_PEAK)
    return np.stack([x, x], axis=1)


# ----------------------------------------------------------------------
# 编码
# ----------------------------------------------------------------------

def ffmpeg_exe():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def write_ogg(path, data):
    """写 **Ogg Vorbis**：numpy -> 临时 wav（16bit PCM）-> ffmpeg libvorbis。"""
    import soundfile as sf
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp.wav"
    sf.write(tmp, data, SR, subtype="PCM_16")
    cmd = [ffmpeg_exe(), "-y", "-loglevel", "error", "-i", tmp,
           "-c:a", "libvorbis", "-q:a", "5", "-ar", str(SR), "-ac", "2", path]
    subprocess.run(cmd, check=True)
    os.remove(tmp)
    size = os.path.getsize(path)
    print("  写出 %-18s %8d 字节" % (os.path.basename(path), size))
    return size


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(DEFAULT_OUT, "replace")
    out = os.path.abspath(out)
    print("== 生成替换素材 -> %s" % out)
    write_ogg(os.path.join(out, "liftmusic.ogg"), make_liftmusic())
    write_ogg(os.path.join(out, "dooropen.ogg"), make_dooropen())
    close = make_doorclose()
    write_ogg(os.path.join(out, "doorclose.ogg"), close)
    write_ogg(os.path.join(out, "mdoorclose.ogg"), close.copy())
    print("== 完成（编码器：imageio_ffmpeg 自带的静态 ffmpeg + libvorbis）")


if __name__ == "__main__":
    main()
