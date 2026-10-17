# -*- coding: utf-8 -*-
"""离线校验：**每一份**「淡入淡出范围」的距离曲线都是**按比例线性** `1 - d/R`。

## 用户点名的口径（2026-10-03，原话）

> 「扶梯，直梯，屏蔽门，讲述人的所有淡入淡出改为按照比例。
>   例子：玩家设置 futimusicround=10，意味着距离扶梯 0 格音量 100%，1 格 90%，以此类推。
>   例子2：玩家设置 futimusicround=5 就是距离 0 格 100%，距离 1 格 80%，以此类推。」

⇒ 增益 `gain(d) = max(0, 1 - d / R)`，其中 `R` = **这条声音自己**那份可闻范围（单位格）：
距离 0 格满音量，每远 1 格减 `100/R`%，到 `R` 格正好 0（出界硬切 0）。

## ★【10-03 二改】所有 round 拆**双维**（用户点名）

> 「所有 round 之前默认为 xz16，y5 的改为默认 xz10，y5，没有 y 轴设置的 round 加上 y 轴设置。」
> 追加确认：「新加的 y 也**参与距离判定**」（= 水平 / 垂直各算一次，取较小）。

⇒ 现在**每一份**距离增益都按双维算：
`gain(dxz, dy) = 0` 若任一维越界；否则 `min(1 - dxz/Rxz, 1 - dy/Ry)`。
（`dxz = hypot(Δx, Δz)`、`dy = |Δy|`）。默认值：底噪 10/5、提示音 4/5、直梯 4/5、屏蔽门 10/5。

## 为什么值得一个**跨域**脚本

这几类声音分别在**四个不同的类**里各写了一份距离增益，而且历史上**故意不一致**：

  * 扶梯提示音、直梯提示音 → **平方** `(1-d/R)²`（当时的理由：「脉冲音要贴块、
    好分辨声音从哪头来」）；
  * 屏蔽门 → **线性**（【1.25】改的，理由：一串同声、要整串都听得见）。

2026-10-03 用户点名**统一成线性** ⇒ 现在四份必须同形（且都双维）。
把它们放在**同一个脚本**里，就是为了让「谁又被改回平方/别的形状」一眼可见 ——
逐份放断言：① 那份增益**确实是**双维线性取较小；② 里面**没有** `f * f` 这类二次项。

## 四份增益各自的落点（改公式时**四处一起看**）

  * `EscalatorAudioPlayer#distanceFactor` —— 扶梯**运行底噪**（`/futiround`，默认 水平 10 / 垂直 5）
  * `EscalatorChimePlayer#gain`           —— 扶梯**无障碍提示音**（`/futihelpround`，默认 水平 4 / 垂直 5）
  * `LiftChimePlayer#spatialFactor`       —— 直梯**提示音**（`/lifthelpround`，默认 水平 4 / 垂直 5）
      ★ **唯一例外**：轿厢内 = 100%，**出厢那一步硬降**到 20%（用户点名「进出车厢没有淡入淡出，
        直接 100% 变 20%」），轿厢**之外**才是 `0.2 · min(1 - dxz/跨度xz, 1 - dy/跨度y)`。
        所以那份是「双维线性 × 0.2」，起点不是 100% —— 别把这一份也改成纯 `1 - d/R`。
        回落分支（读不到真实轿厢盒）**故意保持单维**（见该文件注释），本脚本只断言真实盒那一支。
  * `PsdChimePlayer#gain`（**两个**重载） —— 屏蔽门提示音 / 到站播报 / 进站报站
      （`/pbmround`、`/pbmmidiumround`、`/pbmarriveround`；**双维**：水平与垂直各算一次线性、取较小）

★ **讲述人（TTS）不在这里**：`Narrator.say()` 没有音量接口，本仓库族里也没有任何按距离改
TTS 音量的机制 ⇒ 它只有「越界即停」的硬切断。2026-10-03 用户点名「讲述人也不做淡入淡出」，
所以那一份**不参与**本脚本的断言，也别去给它加渐变。

## 扶梯底噪那一份为什么没有 `while / for` 级缓存坑

`distanceFactor` 是 `onClientTick(...)` 里的**局部变量**，每 tick 现算（范围随时可被
`/futiround` 改）。本脚本的负面断言因此**收窄到 `onClientTick` 方法体** —— 同一个类里的
`Math.pow` 属于隔壁的 `fadeInGain`（**淡入包络**，跟距离衰减完全无关），整类不许有 `Math.pow`
是**假守卫**（会误伤合法的淡入包络）。

解析失败**报错退出**，不做静默兜底。

用法：`python _tools/check-fade-curves.py`（退出码 0 = 全部通过）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")

ESC_AUDIO = os.path.join(CLIENT, "EscalatorAudioPlayer.java")
ESC_CHIME = os.path.join(CLIENT, "EscalatorChimePlayer.java")
LIFT_CHIME = os.path.join(CLIENT, "LiftChimePlayer.java")
PSD_CHIME = os.path.join(CLIENT, "PsdChimePlayer.java")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src):
    """去掉块注释与行注释 —— 断言只判**代码**，注释里怎么写都不算。"""
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    src = re.sub(r"//[^\n]*", " ", src)
    return src


def method_body(src, header_regex):
    m = re.search(header_regex + r"\s*\{(.*?)\n    \}", src, flags=re.S)
    return m.group(1) if m else None


# ======================================================================
print("== 1. 口径表：把用户那两个例子写成测试向量（`1 - d/R`） ==")
# ----------------------------------------------------------------------


def linear(d, r):
    """口径：范围内线性 (1 - d/r)，出界硬切 0。"""
    if not r > 0.0 or d >= r:
        return 0.0
    return max(0.0, 1.0 - d / r)


def dual(dxz, dy, rxz, ry):
    """★双维口径：任一维越界即 0；否则两维各线性、取较小。"""
    if not rxz > 0.0 or not ry > 0.0 or dxz >= rxz or dy >= ry:
        return 0.0
    return max(0.0, min(1.0 - dxz / rxz, 1.0 - dy / ry))


check(abs(linear(0.0, 10.0) - 1.00) < 1e-12, "R=10：距离 0 格 = 100%",
      "%.4f" % linear(0.0, 10.0))
check(abs(linear(1.0, 10.0) - 0.90) < 1e-12, "R=10：距离 1 格 = 90%（用户例子 1）",
      "%.4f" % linear(1.0, 10.0))
check(abs(linear(5.0, 10.0) - 0.50) < 1e-12, "R=10：距离 5 格 = 50%（「以此类推」的中点）",
      "%.4f" % linear(5.0, 10.0))
check(abs(linear(1.0, 5.0) - 0.80) < 1e-12, "R=5：距离 1 格 = 80%（用户例子 2）",
      "%.4f" % linear(1.0, 5.0))
check(abs(linear(4.0, 5.0) - 0.20) < 1e-12, "R=5：距离 4 格 = 20%",
      "%.4f" % linear(4.0, 5.0))
check(linear(10.0, 10.0) == 0.0 and linear(11.0, 10.0) == 0.0,
      "到 R 格正好 0，出界硬切 0（不是变负 / 不是留一点尾巴）")
# 单调性：这条是「按比例」的另一半语义（每远 1 格减同样多）
_steps = [linear(d, 10.0) for d in range(11)]
check(all(abs((_steps[i] - _steps[i + 1]) - 0.1) < 1e-12 for i in range(10)),
      "R=10：相邻每格的**差值恒定 0.1** ⇒ 真的是「按比例」，不是平方/指数",
      " / ".join("%.1f" % v for v in _steps))
# 对照：平方曲线在同样的测试向量上**过不去**，证明上面的向量有鉴别力
check(abs(1.0 * (1 - 1.0 / 10.0) ** 2 - 0.90) > 1e-3,
      "对照：平方曲线 (1-d/R)² 在「R=10、d=1」处是 %.2f ≠ 0.90 ⇒ 本向量能分出两种曲线"
      % ((1 - 1.0 / 10.0) ** 2))

# ======================================================================
print("\n== 1b. ★双维向量：水平 / 垂直各算线性、取较小；任一维越界即 0 ==")
# ----------------------------------------------------------------------
check(abs(dual(1.0, 1.0, 10.0, 5.0) - 0.80) < 1e-12,
      "水平 1/10 = 0.9、垂直 1/5 = 0.8 ⇒ 取较小的 0.8（垂直那维先淡出）",
      "%.4f" % dual(1.0, 1.0, 10.0, 5.0))
check(abs(dual(1.0, 0.0, 10.0, 5.0) - 0.90) < 1e-12,
      "垂直为 0（与扶梯同高）⇒ 只看水平那维 0.9",
      "%.4f" % dual(1.0, 0.0, 10.0, 5.0))
check(abs(dual(0.0, 3.0, 10.0, 5.0) - 0.40) < 1e-12,
      "水平为 0（正在扶梯正方）⇒ 只看垂直那维 1 − 3/5 = 0.4",
      "%.4f" % dual(0.0, 3.0, 10.0, 5.0))
check(dual(10.0, 0.0, 10.0, 5.0) == 0.0 and dual(0.0, 5.0, 10.0, 5.0) == 0.0,
      "**任一维**到 R 格即 0（不是两维都超才 0）—— 与屏蔽门 /pbmround 同口径")
check(dual(0.0, 0.0, 10.0, 5.0) == 1.0, "两维都为 0 ⇒ 100%")

# ======================================================================
print("\n== 2. 扶梯运行底噪：EscalatorAudioPlayer#distanceFactor（双维，写在 onClientTick 里） ==")
# ----------------------------------------------------------------------
esc_audio = strip_comments(read(ESC_AUDIO))
check(re.search(r"float distanceFactor = \(float\) Math\.max\(0\.0,\s*"
                r"Math\.min\(1\.0 - dxz / rangeXz,\s*1\.0 - dy / rangeY\)\);",
                esc_audio) is not None,
      "★【10-03】增益 = max(0, min(1 − dxz/rangeXz, 1 − dy/rangeY))（双维线性取较小；"
      "range = /futiround 的「水平 / 垂直」两份）")
# ★【10-03 守卫修正】distanceFactor 是 onClientTick(...) 里的**局部变量**，不是方法。
# 同一个类里的 Math.pow 属于隔壁的 fadeInGain（**淡入包络**，跟距离衰减完全无关），
# 所以「整类不许有 Math.pow」是**假守卫**（会误伤合法的淡入包络）⇒ 负面断言必须
# **收窄到 onClientTick 方法体**。语义仍是「距离增益这一处没有二次项」。
_tick = method_body(esc_audio, r"public static void onClientTick\(Minecraft mc\)")
check(_tick is not None, "找到 onClientTick(Minecraft mc)（distanceFactor 就写在里面）")
if _tick is not None:
    check("f * f" not in _tick and "Math.pow" not in _tick,
          "★ distanceFactor 所在的方法体里没有二次项 / Math.pow "
          "—— 底噪这条一直是线性的，别被顺手改成平方"
          "（别把隔壁 fadeInGain 的淡入包络也算进来）")
    check(re.search(r"double dxz = horizontalDistanceToBlock\(playerPos, bestNear\);", _tick) is not None
          and re.search(r"double dy = verticalDistanceToBlock\(playerPos, bestNear\);", _tick) is not None,
          "★ 距离也拆双维：dxz = 水平分量、dy = 垂直分量（都取自同一条链上最近那块）")
    check(re.search(r"if \(!\(dxz < rangeXz\) \|\| !\(dy < rangeY\)\)", _tick) is not None,
          "★ 任一维越界即静音（硬切；不是「慢慢趋近」）")

# ======================================================================
print("\n== 3. 扶梯无障碍提示音：EscalatorChimePlayer#gain（双维） ==")
# ----------------------------------------------------------------------
esc_chime = strip_comments(read(ESC_CHIME))
_b = method_body(esc_chime, r"private static float gain\(double distanceXz, double distanceY, "
                           r"double rangeXz, double rangeY\)")
check(_b is not None, "找到 gain(double distanceXz, double distanceY, double rangeXz, double rangeY)")
if _b is not None:
    check(re.search(r"return \(float\) Math\.max\(0\.0,\s*"
                    r"Math\.min\(1\.0 - distanceXz / rangeXz, 1\.0 - distanceY / rangeY\)\);",
                    _b) is not None,
          "★★【10-03】返回**双维线性** min(1 − dxz/rxz, 1 − dy/ry)"
          "（原为单维 `f * f` 平方，用户点名改按比例 + 加 y）")
    check("f * f" not in _b,
          "★★ 那段**不再**有 `f * f` —— 这是本轮改动的正钉（改回平方立刻红）")
    check(re.search(r"distanceXz < rangeXz", _b) is not None
          and re.search(r"distanceY < rangeY", _b) is not None,
          "★ 两维**各自**判越界（任一维到 R 格即 0，不是两维都超才 0）")

# ======================================================================
print("\n== 4. 直梯提示音：LiftChimePlayer#spatialFactor（唯一带 0.2 起点的一份，双维） ==")
# ----------------------------------------------------------------------
lift = strip_comments(read(LIFT_CHIME))
_sf = method_body(lift, r"private static float spatialFactor\(Vec3 player, Vec3 sound, "
                        r"MtrLiftAccess\.Cabin cabin\)")
check(_sf is not None, "找到 spatialFactor(Vec3, Vec3, MtrLiftAccess.Cabin)")
if _sf is not None:
    check(re.search(r"if \(hxz <= 0\.0 && py <= 0\.0\) \{\s*return 1\.0f;", _sf) is not None,
          "★ 轿厢内（水平 / 垂直两维都 <= 0）= 100%")
    check(re.search(r"OUTSIDE_CABIN_FACTOR \* f \* f", _sf) is None,
          "★★ 轿厢外**没有**平方项了（原为 `OUTSIDE_CABIN_FACTOR * f * f`）")
    check(re.search(r"return \(float\) \(OUTSIDE_CABIN_FACTOR \* f\);", _sf) is not None,
          "★★【10-03】轿厢外 = 0.2 × **双维线性**（保留 20% 起点，只把曲线拉直 + 拆双维）")
    check(re.search(r"double f = Math\.min\(1\.0 - hxz / spanXz, 1\.0 - py / spanY\);", _sf) is not None,
          "★ f = 双维各算线性后取较小（跨度从**轿厢表面**起算）")
    check(re.search(r"!\(hxz < spanXz\) \|\| !\(py < spanY\)", _sf) is not None,
          "★ 任一维越界即 0（与其它三份同口径）")
    check(re.search(r"if \(cabin == null\) \{.*?player\.distanceTo\(sound\) - CABIN_RADIUS_H;",
                    _sf, re.S) is not None,
          "★ 回落圆那一支**保持单维**（读不到真实轿厢尺寸时的兜底，别顺手也拆成双维）")
m_out = re.search(r"OUTSIDE_CABIN_FACTOR = ([0-9.]+)f;", lift)
check(bool(m_out) and float(m_out.group(1)) == 0.2,
      "★ OUTSIDE_CABIN_FACTOR 仍 = 0.2（用户点名的「出厢直接 20%」没被动）",
      "实际 %s" % (m_out.group(1) if m_out else None))

# ======================================================================
print("\n== 5. 屏蔽门三类：PsdChimePlayer#gain（两个重载，双维取较小） ==")
# ----------------------------------------------------------------------
psd = strip_comments(read(PSD_CHIME))
_g2 = method_body(psd, r"private static float gain\(double distanceXz, double distanceY, int roundKind\)")
check(_g2 is not None, "找到双维重载 gain(distanceXz, distanceY, roundKind)")
if _g2 is not None:
    check("1.0 - distanceXz / roundXz" in _g2 and "1.0 - distanceY / roundY" in _g2
          and "f * f" not in _g2,
          "★ 双维：水平/垂直各算**线性** 1 - d/r，取**较小**的那个（无平方）")
_g1 = method_body(psd, r"private static float gain\(double distance, int roundKind\)")
check(_g1 is not None, "找到单维重载 gain(distance, roundKind)")
if _g1 is not None:
    check(re.search(r"return \(float\) Math\.max\(0\.0,\s*1\.0 - distance / round\);", _g1) is not None,
          "★ 单维：线性 1 - d/round（门提示音用）")

# ======================================================================
print("\n== 6. 讲述人（TTS）：**不参与**（按用户点名不做渐变） ==")
# ----------------------------------------------------------------------
narr = strip_comments(read(os.path.join(CLIENT, "TrainAnnounceNarrator.java")))
check("gain(" not in narr and "distanceFactor" not in narr,
      "★ 讲述人里没有任何距离增益 —— 它只有「越界即停」的硬切断"
      "（2026-10-03 用户点名「讲述人也不做淡入淡出」）")
check("tickRangeGuard" in narr,
      "★ 那条「越界即停」护栏仍在（别把讲述人整体删掉）")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
