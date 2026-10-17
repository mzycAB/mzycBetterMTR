"""Script: gen_assets.py

生成「全透明」的阶梯底图，并生成 MTR 阶梯模型的覆盖 JSON。

为什么要这么做：
  MTR 的 `mtr:block/escalator_up` / `escalator_down` 是 320x5120 的 16 帧竖排 flipbook。
  在 atlas 里它只占「一帧 320x320」的槽位（AnimationMetadataSection.calculateFrameSize 对
  320x5120 且未声明 width/height 的动画取 min(w,h)=320），同一时刻全图只有一个相位，
  所以无法让每条扶梯各自动各自的速度。

  SmoothLift 的做法：把 12 个阶梯模型（*_up / *_down）的 `#step` 贴图换成 SmoothLift 自己的
  贴图；再由 SmoothLift 自己按「每条扶梯自己的速度」逐方块绘制阶梯面，实现真正的逐条独立动画。

  这张底图**必须是全透明的**：
    - MTR 的 ESCALATOR_STEP 注册在 cutout 渲染层（InitClient 里 RenderLayer.getCutout()），
      cutout 会做 alpha 裁剪 —— 全透明像素被直接丢弃，等于把 MTR 原版那份「静止的台阶面」
      彻底隐藏掉，不会再和 SmoothLift 画的那份重叠（否则玩家会看到「两个重叠的台阶，
      一个不动一个动」）。
    - 为什么还需要这张贴图、不能干脆不写：SmoothLift 的逐方块重绘是**从 MTR 的烘焙模型里
      挑面**的，靠「sprite 名字 == smoothlift:block/step_static_up|down」来识别哪些面是台阶面。
      所以这张贴图是**标记**，只需要占住一个 sprite 槽位 + uv 有效，内容透明即可。

用法：
  python gen_assets.py [MTR jar 路径] [项目根目录]
"""

import json
import os
import struct
import sys
import zlib

DEFAULT_JAR = (
    r"C:\Users\user\Desktop\1\aPCLZY\.minecraft\versions\1.20.4-Fabric 0.19.2"
    r"\mods\[我的世界铁路] MTR-fabric-4.0.5+1.20.4.jar"
)
DEFAULT_ROOT = r"C:\Users\user\Desktop\1\SmoothLiftMod-1.20.4"

# 需要覆盖的 MTR 阶梯模型（相对 assets/mtr/models/block/ 的名字 -> 用哪张底图）
MODEL_OVERRIDES = {}
for part in ("flat_left", "flat_right", "slope_left", "slope_right",
             "transition_bottom_left", "transition_bottom_right"):
    MODEL_OVERRIDES[f"escalator_step_{part}_up"] = "up"
    MODEL_OVERRIDES[f"escalator_step_{part}_down"] = "down"

# slope_* 的原模型带 ambientocclusion=false，覆盖时要保留，否则光照会和原版不一致
HAS_AO_FALSE = {"escalator_step_slope_left_up", "escalator_step_slope_left_down",
                "escalator_step_slope_right_up", "escalator_step_slope_right_down"}


def png_size(data):
    """只读 PNG 的 IHDR，返回 (width, height)。"""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("不是 PNG 文件")
    (w, h) = struct.unpack(">II", data[16:24])
    return w, h



def write_png(path, width, height, rgba):
    """写出 8bit RGBA PNG（滤波全 0，最高压缩）。"""
    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(typ, data):
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(png)
    return len(png)


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_JAR
    root = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_ROOT
    if not os.path.isfile(jar):
        raise SystemExit(f"找不到 MTR jar：{jar}")

    import zipfile
    zf = zipfile.ZipFile(jar)

    # 1) 生成「全透明」的阶梯底图（尺寸沿用 MTR 原图的一帧，保证 uv/atlas 布局不变）
    tex_dir = os.path.join(root, "src", "main", "resources", "assets",
                           "smoothlift", "textures", "block")
    for direction in ("up", "down"):
        entry = f"assets/mtr/textures/block/escalator_{direction}.png"
        w, h = png_size(zf.read(entry))
        transparent = b"\x00" * (w * w * 4)
        out = os.path.join(tex_dir, f"step_static_{direction}.png")
        size = write_png(out, w, w, transparent)
        print(f"[ok] {out}  ({w}x{w} 全透明, 原图 {w}x{h})  {size} 字节")

    # 2) 生成 MTR 阶梯模型覆盖 JSON
    model_dir = os.path.join(root, "src", "main", "resources", "assets",
                             "mtr", "models", "block")
    os.makedirs(model_dir, exist_ok=True)
    for name, direction in sorted(MODEL_OVERRIDES.items()):
        base = name.rsplit("_", 1)[0]
        body = {"parent": f"mtr:block/{base}_base"}
        if name in HAS_AO_FALSE:
            body["ambientocclusion"] = False
        body["textures"] = {"step": f"smoothlift:block/step_static_{direction}"}
        out = os.path.join(model_dir, name + ".json")
        open(out, "w", encoding="utf-8").write(json.dumps(body, indent=2) + "\n")
        print(f"[ok] {out}")
    print(f"\n共生成 {len(MODEL_OVERRIDES)} 个模型覆盖。")


if __name__ == "__main__":
    main()
