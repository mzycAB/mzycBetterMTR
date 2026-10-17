"""Script: gen_psd_audio.py

⚠️ **【1.15】已被 `_tools/import_psd_audio.py` 取代，本脚本产出的两个文件不再被任何代码引用。**
   屏蔽门的内置素材换成了用户提供的 `dooropen.ogg` / `doorclose.ogg` / `mdoorclose.ogg`
   （见 sounds.json 的 `audio/dooropen|doorclose|mdoorclose`，以及 `PsdChimePlayer`），
   原来的 `psd_open.ogg` / `psd_close.ogg` 已从 assets 里删除。
   **保留本脚本只是作为「纯合成一个气动门音」的手法参考**（RBJ 带通白噪声 + 阻尼谐振撞击），
   不要再拿它的产物当内置素材 —— 那会变成「两套内置素材并存」的困惑源。

合成屏蔽门（MTR 平台幕门 / 半高安全门）的**内置**开关门提示音：

    assets/smoothlift/sounds/audio/psd_open.ogg    开门
    assets/smoothlift/sounds/audio/psd_close.ogg   关门

为什么要自己合成：
  SmoothLift 不允许打包第三方素材（版权），而「屏蔽门开关门」这件事本身又必须**默认就能响**
  （玩家不导入音频时听不到任何反应 = 以为功能坏了）。所以这里用纯合成做两个「像那么回事」的
  气动声：门机放气（带通白噪声）+ 机械撞击（阻尼谐振）+ 关门那一下更实。

两条素材的听感分工：
  psd_open  = 轻「咔」起头 → 放气「嘶——」  → 收尾一声轻碰（门滑到尽头）
  psd_close = 轻「咔」起头 → 放气「嘶——」  → 收尾一记更沉、更闷的「咚」（门合拢撞到门框）

两条都**从第 0 个采样点就开始出声**（不留开头空白）——提示音是脉冲式的，留空白会让它听起来
慢半拍（这一条是扶梯那边踩过的坑，见 EscalatorChimePlayer 的注释）。

用法：
  python gen_psd_audio.py [项目根目录]
"""

import math
import os
import subprocess
import sys

import numpy as np

SR = 48000
DEFAULT_ROOT = r"C:\Users\user\Desktop\2\mzycBetterMTR-1.20.4"


# ----------------------------------------------------------------------
# 基础件
# ----------------------------------------------------------------------

def rbj_bandpass(x, f0, q):
    """RBJ cookbook 带通（constant skirt gain, peak gain = Q），直接时域 IIR。

    只用 numpy 手写，不引入 scipy —— 本机 venv 里未必装了它。
    """
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
    """一阶低通，用来把白噪声的高频磨掉一点（更像气流而不是「嘶嘶」静电）。"""
    a = math.exp(-2.0 * math.pi * cutoff / SR)
    y = np.empty_like(x)
    prev = 0.0
    for i in range(len(x)):
        prev = (1.0 - a) * x[i] + a * prev
        y[i] = prev
    return y


def envelope(n, points):
    """把 [(时刻比例, 幅度), ...] 折线插值成逐采样包络（保证首尾为 0，不咬到边界）。"""
    xs = np.array([p[0] for p in points], dtype=float) * (n - 1)
    ys = np.array([p[1] for p in points], dtype=float)
    return np.interp(np.arange(n), xs, ys)


def hiss(n, f0, q, gain, points):
    """气动声：带通白噪声 × 折线包络。"""
    rng = np.random.default_rng(20260920)
    noise = rng.standard_normal(n)
    noise = one_pole_lowpass(noise, 9000.0)
    noise = rbj_bandpass(noise, f0, q)
    return noise * envelope(n, points) * gain


def clack(n, freq, decay, gain, t0_ratio=0.0, noise_mix=0.25):
    """机械撞击：一组阻尼谐振（基频 + 2.7× 非谐分音）叠一点瞬态噪声。

    MTR 的门机不算重，所以用指数衰减的正弦簇比「脉冲」自然。
    """
    rng = np.random.default_rng(7)
    t = np.arange(n) / SR
    start = int(t0_ratio * (n - 1))
    env = np.zeros(n)
    tail = np.arange(n - start) / SR
    shape = np.exp(-tail * decay)
    # 起音 0.6ms 线性渐入，避免 DC 冲击（爆音）
    atk = np.minimum(tail / 0.0006, 1.0)
    env[start:] = shape * atk
    body = (np.sin(2.0 * math.pi * freq * t)
            + 0.45 * np.sin(2.0 * math.pi * freq * 2.7 * t)
            + 0.2 * np.sin(2.0 * math.pi * freq * 4.9 * t))
    body = body * env
    spark = rng.standard_normal(n) * env * noise_mix
    return (body + spark) * gain


def thud(n, freq, decay, gain, t0_ratio):
    """关门那一下：更低的基频 + 更长尾巴，听感更「闷实」。"""
    rng = np.random.default_rng(11)
    t = np.arange(n) / SR
    start = int(t0_ratio * (n - 1))
    env = np.zeros(n)
    tail = np.arange(n - start) / SR
    shape = np.exp(-tail * decay)
    atk = np.minimum(tail / 0.0012, 1.0)
    env[start:] = shape * atk
    body = (np.sin(2.0 * math.pi * freq * t)
            + 0.35 * np.sin(2.0 * math.pi * freq * 1.6 * t))
    body = body * env
    rumble = one_pole_lowpass(rng.standard_normal(n), 260.0) * env * 0.5
    return (body + rumble) * gain


# ----------------------------------------------------------------------
# 两条素材
# ----------------------------------------------------------------------

def build_open():
    n = int(0.86 * SR)
    out = np.zeros(n)
    # 门机解锁的轻「咔」（开头立刻出声，不留空白）
    out += clack(n, 1750.0, 42.0, 0.34)
    # 滑门放气：先急后缓，0.08s 处最响
    out += hiss(n, 1500.0, 1.1, 0.30,
                [(0.0, 0.15), (0.09, 1.0), (0.45, 0.36), (0.86, 0.02), (1.0, 0.0)])
    # 门滑到尽头，轻轻碰上缓冲器
    out += clack(n, 620.0, 26.0, 0.30, t0_ratio=0.72)
    out += thud(n, 150.0, 20.0, 0.16, t0_ratio=0.73)
    return out


def build_close():
    n = int(0.94 * SR)
    out = np.zeros(n)
    # 解锁「咔」 + 起动的低闷启动声
    out += clack(n, 1500.0, 46.0, 0.30)
    out += thud(n, 120.0, 34.0, 0.20, t0_ratio=0.0)
    # 加速滑门放气
    out += hiss(n, 1750.0, 1.0, 0.26,
                [(0.0, 0.12), (0.06, 0.95), (0.4, 0.42), (0.62, 0.10), (0.7, 0.0), (1.0, 0.0)])
    # 门合拢撞门框：这一下是本音最重的成分
    out += thud(n, 105.0, 12.0, 0.62, t0_ratio=0.66)
    out += clack(n, 900.0, 30.0, 0.26, t0_ratio=0.665)
    return out


# ----------------------------------------------------------------------
# 收尾：归一 + 防爆音 + 编码
# ----------------------------------------------------------------------

def finalize(x):
    # 去直流
    x = x - float(np.mean(x))
    # 起止各 1.5ms 淡入淡出（削掉编码器边界爆音；不影响「开头就出声」）
    k = int(0.0015 * SR)
    if len(x) > 2 * k:
        x[:k] *= np.linspace(0.0, 1.0, k)
        x[-k:] *= np.linspace(1.0, 0.0, k)
    peak = float(np.max(np.abs(x)))
    if peak > 0:
        x = x / peak * 0.89
    return x


def write_ogg(path, x):
    try:
        import soundfile as sf
    except ImportError:
        sf = None
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if sf is not None:
        try:
            sf.write(path, x.astype(np.float32), SR, format="OGG", subtype="VORBIS")
            return os.path.getsize(path)
        except Exception as exc:  # libsndfile 没编 vorbis
            print(f"  [warn] soundfile 写不了 OGG（{exc}），改用 ffmpeg")
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav = tmp.name
    if sf is not None:
        sf.write(wav, x.astype(np.float32), SR, format="WAV", subtype="PCM_16")
    else:
        raise SystemExit("缺少 soundfile，无法写 WAV")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav,
                    "-c:a", "libvorbis", "-qscale:a", "5", path], check=True)
    os.unlink(wav)
    return os.path.getsize(path)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ROOT
    out_dir = os.path.join(root, "src", "main", "resources", "assets",
                           "smoothlift", "sounds", "audio")
    for name, fn in (("psd_open", build_open), ("psd_close", build_close)):
        x = finalize(fn())
        path = os.path.join(out_dir, name + ".ogg")
        size = write_ogg(path, x)
        peak = float(np.max(np.abs(x)))
        # 「开头有没有留空白」是可核验的：首个越过 1% 满量程的采样点必须就在 0 附近。
        lead = int(np.argmax(np.abs(x) > 0.01)) / SR * 1000.0
        print(f"[ok] {path}  {len(x) / SR:.2f}s  峰值 {peak:.3f}  {size} 字节  "
              f"起始点 {lead:.2f}ms")


if __name__ == "__main__":
    main()
