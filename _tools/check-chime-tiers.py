# -*- coding: utf-8 -*-
"""离线校验：/futihelpspeed 的 1~100 Hz 每一档**真的**到得了，且没有任何一档被 pitch 钳制吃掉。

## 为什么需要它

速率的实现方式（【1.31】起）是「**换素材 + 调 pitch**」，而原版
`SoundEngine.calculatePitch` 会把 pitch 钳在 [0.5, 2.0]（字节码：
`Mth.clamp(getPitch(), 0.5F, 2.0F)`）。于是一个素材只覆盖 4 倍的速率区间，
**光放宽 `HELP_SPEED_MAX` 而不补素材，20 Hz 以上会被悄悄压回去** ——
表现是「调 50 跟调 20 一模一样」，玩家只会觉得指令没用。

这个脚本把 `EscalatorChimePlayer` 里的分档逻辑**从源码里解析出来**（不是抄一份），
然后对 [HELP_SPEED_MIN, HELP_SPEED_MAX] 的**每一个整数**验证：

  1. 选中了某个素材；
  2. 该档算出的 pitch 落在 [0.5, 2.0]（否则会被原版钳制 ⇒ 实际速率 ≠ 设定值）；
  3. `素材原始速率 × pitch == 设定值`（误差 < 1e-6）。

顺带检查每个素材都有对应的 .ogg 与 sounds.json 条目（否则引擎拿不到声音，直接不响）。

解析失败会**报错退出**，不做静默兜底 —— 这样如果以后有人改了分档的写法，
脚本会明确要求更新它，而不是悄悄放过。

用法：`python _tools/check-chime-tiers.py`（退出码 0 = 全部通过）
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHIME = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client", "EscalatorChimePlayer.java")
DATA = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedData.java")
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift")


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def parse_range(data_src):
    lo = re.search(r"HELP_SPEED_MIN\s*=\s*(\d+)", data_src)
    hi = re.search(r"HELP_SPEED_MAX\s*=\s*(\d+)", data_src)
    if not lo or not hi:
        sys.exit("✗ 解析不出 HELP_SPEED_MIN / HELP_SPEED_MAX")
    return int(lo.group(1)), int(hi.group(1))


def parse_materials(src):
    """素材常量名 -> (原始速率 Hz, 资源路径)。名字形如 CHIME_25HZ。"""
    out = {}
    for name, path in re.findall(
            r'ResourceLocation\s+(CHIME_\d+HZ)\s*=\s*\n?\s*new ResourceLocation\("smoothlift",\s*"([^"]+)"\)',
            src):
        out[name] = (int(re.search(r"\d+", name).group(0)), path)
    if not out:
        sys.exit("✗ 解析不出任何 CHIME_*HZ 素材常量")
    return out


def parse_tiers(src, materials):
    """从 chimeEventFor / chimePitchFor 里解析出 [(上限, 素材名, 除数), ...]。

    两个方法的写法必须一一对应（源码注释也强调了「两处必须同步改」），
    这里就按出现顺序把 `hz <= N` / `return CHIME_XHZ` / `hz / D` 配对。
    """
    def body(method):
        m = re.search(r"private static .*?%s\(int hz\)\s*\{(.*?)\n    \}" % method, src, re.S)
        if not m:
            sys.exit("✗ 找不到方法 %s" % method)
        return m.group(1)

    ev = body("chimeEventFor")
    pi = body("chimePitchFor")

    # 事件：`hz <= N` 的边界（if 形式与最后那个三元形式都算）+ 素材名（return 与三元两支）
    bounds = [int(x) for x in re.findall(r"hz\s*<=\s*(\d+)", ev)]
    names = re.findall(r"return\s+(CHIME_\d+HZ)", ev)
    tri = re.findall(r"\?\s*(CHIME_\d+HZ)\s*:\s*(CHIME_\d+HZ)", ev)
    flat = list(names)
    for a, b in tri:
        flat.append(a)
        flat.append(b)
    if not flat:
        sys.exit("✗ chimeEventFor 里解析不出素材")

    # pitch：第一档是 `return hz;`（除数 1），其余是 `hz / D.0f`
    divisors = []
    for tok in re.findall(r"return hz\s*;|hz\s*/\s*([\d.]+)f", pi):
        divisors.append(1.0 if tok == "" else float(tok))
    if len(divisors) != len(flat):
        sys.exit("✗ chimePitchFor 的分档数(%d) 与 chimeEventFor 的素材数(%d) 不一致 —— "
                 "两处必须同步改" % (len(divisors), len(flat)))
    if len(bounds) != len(flat) - 1:
        sys.exit("✗ chimeEventFor 的边界数(%d) 与素材数(%d) 不匹配" % (len(bounds), len(flat)))

    tiers = []
    for i, (name, div) in enumerate(zip(flat, divisors)):
        if name not in materials:
            sys.exit("✗ chimeEventFor 用了未定义的素材 %s" % name)
        upper = bounds[i] if i < len(bounds) else 10 ** 9
        tiers.append((upper, name, div))
    return tiers


def main():
    chime = read(CHIME)
    lo, hi = parse_range(read(DATA))
    materials = parse_materials(chime)
    tiers = parse_tiers(chime, materials)

    print("素材（原始速率）:")
    for name, (rate, path) in sorted(materials.items(), key=lambda kv: kv[1][0]):
        ogg = os.path.join(RES, "sounds", path + ".ogg")
        ok = os.path.exists(ogg)
        print("  %-12s %4d Hz  %-40s %s" % (name, rate, path, "存在" if ok else "**缺失**"))
        if not ok:
            sys.exit("✗ 素材 %s 的 ogg 不存在: %s" % (name, ogg))

    with open(os.path.join(RES, "sounds.json"), encoding="utf-8") as fh:
        sounds = json.load(fh)
    print("分档:")
    for upper, name, div in tiers:
        rate = materials[name][0]
        span = "∞" if upper >= 10 ** 9 else str(upper)
        path = materials[name][1]
        reg = path in sounds
        print("  hz <= %-4s -> %-12s (原始 %3d Hz, 除数 %-5g ⇒ 覆盖 [%.2f, %.2f])%s"
              % (span, name, rate, div, rate * 0.5, rate * 2.0, "" if reg else "  **sounds.json 缺条目**"))
        if not reg:
            sys.exit("✗ sounds.json 里没有 %s" % path)

    failures = 0
    for hz in range(lo, hi + 1):
        chosen = None
        for upper, name, div in tiers:
            if hz <= upper:
                chosen = (name, div)
                break
        if chosen is None:
            print("✗ %d Hz 落不到任何素材档" % hz)
            failures += 1
            continue
        name, div = chosen
        rate = materials[name][0]
        pitch = hz / div
        actual = rate * pitch
        if not (0.5 - 1e-9 <= pitch <= 2.0 + 1e-9):
            print("✗ %d Hz -> %s pitch=%.4f **超出 [0.5,2.0]**（会被原版钳制）" % (hz, name, pitch))
            failures += 1
        elif abs(actual - hz) > 1e-6:
            print("✗ %d Hz -> %s pitch=%.4f 实际速率=%.4f" % (hz, name, pitch, actual))
            failures += 1

    print()
    if failures == 0:
        print("结果：[%d, %d] Hz 共 %d 档全部可达、无钳制 ✅" % (lo, hi, hi - lo + 1))
    else:
        print("结果：有 %d 档不达标 ❌" % failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
