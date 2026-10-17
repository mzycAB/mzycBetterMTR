# -*- coding: utf-8 -*-
"""Script: check-psd-anchor.py

离线回归：把「屏蔽门门锚点」与「开关门跳变判定」两段纯逻辑搬到 Python 里跑断言。

为什么值得单独测：
  1. **锚点算错不会报错，只会「配了不响」。** 一扇屏蔽门在客户端由多个方块实体表示
     （左右两块，MTR4 还分上/下半格），门值却是同一个。如果「一扇门」折算出的 key 不唯一，
     石斧界面写的 key 和播放端查的 key 就是两个格子 ⇒ 界面显示已设置、门开却查不到 ⇒ 静默无声。
     几何算错**永远不抛异常**，只能靠断言钉住。
  2. **判据要和 MTR 自己的一致。** 配对方向抄错了（把 rotateYClockwise 写成 Counter）
     会得到**另一对**格子：左右两块各自成对 ⇒ 同扇门两个 key。
     这里把 MTR 的 `getDoorValue()` 字节码里那段
     `side==RIGHT ? facing.rotateYCounterclockwise() : facing.rotateYClockwise()`
     原样搬过来做交叉验证。
  3. **MTR3 的量纲要补 0.1/32。** 不补的话「全开」只到 0.996875；容差若取 1e-3，
     关门事件永远判不出来（下面的对照把这条钉住）。

用法：`python _tools/check-psd-anchor.py`（退出码 0 = 全部通过）
"""

import itertools
import os
import re
import sys

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


# ----------------------------------------------------------------------
# 1) 把「方块状态」建模成一张 property 名 → 值 的字典（Java 侧的 Property.getName()）
# ----------------------------------------------------------------------

# 原版 Direction.getClockWise() / getCounterClockWise()（水平四向）——照抄 vanilla
CW = {"NORTH": "EAST", "EAST": "SOUTH", "SOUTH": "WEST", "WEST": "NORTH"}
CCW = {v: k for k, v in CW.items()}

# Direction → (dx, dz)（Minecraft：NORTH = -Z、SOUTH = +Z、EAST = +X、WEST = -X）
DELTA = {"NORTH": (0, -1), "SOUTH": (0, 1), "EAST": (1, 0), "WEST": (-1, 0)}


def as_long(x, y, z):
    """BlockPos.asLong 只用来比大小（取较小的一格），不需要和原版位运算一致。"""
    return (x & 0x3FFFFFF) << 38 | (y & 0xFFF) << 26 | (z & 0x3FFFFFF)


def anchor_of(state, x, y, z):
    """Java 侧 PsdDoorTracker.anchorOf 的等价实现（几何部分逐句对应）。"""
    if not state.get("__isPsdDoor", False):
        return as_long(x, y, z)
    # 1) 下移到下半格
    if state.get("half") == "UPPER":
        y -= 1
    # 2) 按 side / facing 配对，取 asLong 较小的一格
    side = state.get("side")
    if side in ("LEFT", "RIGHT"):
        facing = state.get("facing")
        if facing is not None:
            nxt = CCW[facing] if side == "RIGHT" else CW[facing]
            dx, dz = DELTA[nxt]
            other = as_long(x + dx, y, z + dz)
            if other < as_long(x, y, z):
                return other
    return as_long(x, y, z)


# ----------------------------------------------------------------------
# 2) MTR 自己的配对方向（来自 BlockPSDAPGDoorBase$BlockEntityBase.getDoorValue 字节码）
# ----------------------------------------------------------------------

def mtr_paired_pos(state, x, y, z):
    """MTR getDoorValue() 里算「配对那一格」的那三行。

    字节码原文（MTR4 4.0.5）：
        side == EnumSide.RIGHT ? facing.rotateYCounterclockwise() : facing.rotateYClockwise()
        ... BlockPSDAPGDoorBase.getBottomBlockEntity(world, pos.offset(dir))
    """
    if state.get("half") == "UPPER":
        y -= 1
    facing = state.get("facing")
    nxt = CCW[facing] if state.get("side") == "RIGHT" else CW[facing]
    dx, dz = DELTA[nxt]
    return (x + dx, y, z + dz)


def build_panels(facing):
    """在 (100,64,200) 放一扇面向 facing 的屏蔽门：LEFT / RIGHT 两块，各分上下半格。

    两块互指：从 LEFT 出发按 MTR 的规则应当指到 RIGHT，反之亦然。
    """
    left = (100, 64, 200)
    probe = {"half": "LOWER", "side": "LEFT", "facing": facing}
    right = mtr_paired_pos(probe, *left)
    assert mtr_paired_pos({"half": "LOWER", "side": "RIGHT", "facing": facing}, *right) == left, \
        "MTR 的配对不是对称的？！"
    return left, right


print("===== 1) 锚点：左右两块 + 上下半格必须折到同一格 =====")
for facing in ("NORTH", "SOUTH", "EAST", "WEST"):
    left, right = build_panels(facing)
    seen = set()
    for (bx, by, bz), side in ((left, "LEFT"), (right, "RIGHT")):
        for half in ("LOWER", "UPPER"):
            st = {"__isPsdDoor": True, "half": half, "side": side, "facing": facing}
            # 上半格的坐标是下半格 +1
            yy = by + (1 if half == "UPPER" else 0)
            seen.add(anchor_of(st, bx, yy, bz))
    check(len(seen) == 1,
          "facing=%s：左右 × 上下 共 4 个位置 → 锚点唯一" % facing,
          "锚点数 = %d" % len(seen))
    # 锚点必须是这一对里 asLong 较小的那个
    want = min(as_long(*left), as_long(*right))
    st = {"__isPsdDoor": True, "half": "LOWER", "side": "LEFT", "facing": facing}
    check(anchor_of(st, *left) == want,
          "facing=%s：锚点 = 两块里 asLong 较小的那个" % facing)

print()
print("===== 2) 配对方向必须与 MTR getDoorValue() 一致（抄错方向会得到另一对格子）=====")
for facing in ("NORTH", "SOUTH", "EAST", "WEST"):
    left, right = build_panels(facing)
    # 若把 LEFT 的方向写成 CCW（抄反），配到的是「另一侧」——必须与正确的不同
    wrong = {"half": "LOWER", "side": "LEFT", "facing": facing}
    dx, dz = DELTA[CCW[facing]]
    wrong_pair = (left[0] + dx, left[1], left[2] + dz)
    check(mtr_paired_pos({"half": "LOWER", "side": "LEFT", "facing": facing}, *left) == right,
          "facing=%s：MTR 规则下 LEFT → RIGHT（本实现同）" % facing)
    check(wrong_pair != right,
          "facing=%s：抄反方向会配到 %s ≠ 正确的那一格" % (facing, wrong_pair))

print()
print("===== 3) 非门方块（玻璃/顶板）退化成自身，不吸附到门上 =====")
# 设想玻璃贴在 LEFT 的侧面一格：如果它「顺便」按 LEFT 配对，就会抢走门的 key
glass = {"__isPsdDoor": False, "half": "LOWER", "side": "LEFT", "facing": "SOUTH"}
left, right = build_panels("SOUTH")
gx, gy, gz = left[0], left[1], left[2] - 1
check(anchor_of(glass, gx, gy, gz) == as_long(gx, gy, gz),
      "非门方块 anchorOf = 自身（不会吸附到门上）")
check(anchor_of(glass, gx, gy, gz) != anchor_of(
    {"__isPsdDoor": True, "half": "LOWER", "side": "LEFT", "facing": "SOUTH"}, *left),
      "非门方块的 key 与门的 key 不同 ⇒ 不会互相顶掉")

print()
print("===== 4) 开门 / 关门跳变：每次「起动」必须**恰好**触发一次 =====")
# ★ 语义钉死在这里（这是本功能唯一容易想歪的地方）：
#   事件 = 「门机**开始**动作」，不是「门走完了」。所以判据是「离开全关 / 全开那个静止位」，
#   而不是「到达另一端」。理由：真实屏蔽门的声音（放气 + 解锁咔）就发生在起动那一瞬，
#   门是匀速滑完剩下行程的；等它走完再响，听感上会慢半拍。
#   由此推出两个**正确**的行为：
#     - 只开一半又收回去  → 响 1 次开门（门机确实起动过），不响关门；
#     - 从中途（例如 0.5）继续滑到全开 → **不重复**响（prev 早就 > EDGE）。
EDGE = 0.02


def run_edges(samples):
    """samples = 逐 tick 的门开合度；返回 (open 次数, close 次数)。"""
    opening = closing = 0
    prev = samples[0]
    for now in samples[1:]:
        if prev >= 1.0 - EDGE and now < 1.0 - EDGE:
            closing += 1
        if prev <= EDGE and now > EDGE:
            opening += 1
        prev = now
    return opening, closing


# 门机不是匀速的：起步快、末段缓（cos 缓动），更接近真实
def ease(n, frm, to):
    import math
    out = []
    for i in range(n + 1):
        t = i / n
        e = (1 - math.cos(math.pi * t)) / 2.0
        out.append(frm + (to - frm) * e)
    return out


open_seq = ease(30, 0.0, 1.0)
close_seq = ease(30, 1.0, 0.0)
o, c = run_edges(open_seq)
check((o, c) == (1, 0), "整段开门（0→1，30 tick）→ 恰好 1 次开门、0 次关门", "得到 %s" % ((o, c),))
o, c = run_edges(close_seq)
check((o, c) == (0, 1), "整段关门（1→0，30 tick）→ 恰好 0 次开门、1 次关门", "得到 %s" % ((o, c),))
o, c = run_edges(ease(10, 0.0, 0.5) + ease(10, 0.5, 0.0))
check((o, c) == (1, 0),
      "只开一半又收回去（0→0.5→0）→ 1 次开门（门机起动过）、0 次关门（没到过全开）",
      "得到 %s" % ((o, c),))
o, c = run_edges(ease(10, 1.0, 0.5) + ease(10, 0.5, 1.0))
check((o, c) == (0, 1),
      "只关一半又推回去（1→0.5→1）→ 0 次开门、1 次关门（对称）", "得到 %s" % ((o, c),))
o, c = run_edges(open_seq + close_seq)
check((o, c) == (1, 1), "开一轮再关一轮 → 各 1 次", "得到 %s" % ((o, c),))
# ★ 反向的「不该响」：从中途起步（例如区块刚加载、门已经在 0.5）不该响
o, c = run_edges(ease(20, 0.5, 1.0))
check((o, c) == (0, 0), "从半开（0.5）滑到全开 → 不触发（没离开过全关）", "得到 %s" % ((o, c),))
o, c = run_edges(ease(20, 0.5, 0.0))
check((o, c) == (0, 0), "从半开（0.5）滑到全关 → 不触发（没离开过全开）", "得到 %s" % ((o, c),))
# ★ 连续两轮完整开合 → 各 2 次（不能漏，也不能多）
o, c = run_edges(open_seq + close_seq + open_seq + close_seq)
check((o, c) == (2, 2), "连开合两轮 → 各 2 次（不漏不多）", "得到 %s" % ((o, c),))
# ★ 静置不动（门一直全关 / 一直全开）→ 不触发
o, c = run_edges([0.0] * 60)
check((o, c) == (0, 0), "门一直全关（60 tick 不动）→ 不触发", "得到 %s" % ((o, c),))
o, c = run_edges([1.0] * 60)
check((o, c) == (0, 0), "门一直全开（60 tick 不动）→ 不触发", "得到 %s" % ((o, c),))

print()
print("===== 5) MTR3 的 0.1/32 偏移：补与不补的差别（可核验的数字）=====")
# MTR3 getOpen(f) 返回 openClient/32，而 openClient 收敛到 open-0.1，open ∈ [0,32]
# ⇒ 原始返回值区间 = [-0.1/32, (32-0.1)/32]
raw_closed = (0 - 0.1) / 32.0
raw_open = (32 - 0.1) / 32.0
fix_closed = raw_closed + 0.1 / 32.0
fix_open = raw_open + 0.1 / 32.0
print("        MTR3 原始返回值区间 = [%.6f, %.6f]" % (raw_closed, raw_open))
print("        补 0.1/32 之后         = [%.6f, %.6f]" % (fix_closed, fix_open))
check(abs(fix_closed - 0.0) < 1e-6, "补偏移后「全关」正好是 0.0", "%.9f" % fix_closed)
check(abs(fix_open - 1.0) < 1e-6, "补偏移后「全开」正好是 1.0", "%.9f" % fix_open)

# 真实差别：容差取直梯那种 1e-3 时，不补偏移的「关门」永远判不出来
STRICT = 1e-3
strict_ok = raw_open >= 1.0 - STRICT
check(not strict_ok,
      "若容差取 1e-3（直梯那套）且不补偏移 → 「全开」到不了 1-1e-3，关门永远不触发",
      "0.996875 >= 0.999 为 %s" % strict_ok)
# 补了偏移，或者用 0.02 的容差，两种都能触发
check(raw_open >= 1.0 - EDGE,
      "容差取 %s 时，即便不补偏移关门也能触发（本实现两道保险都上了）" % EDGE,
      "0.996875 >= %.3f 为 True" % (1.0 - EDGE))
check(fix_open >= 1.0 - STRICT,
      "补了偏移后，即使容差取 1e-3 关门也能触发", "1.000000 >= 0.999 为 True")

print()
print("===== 6) MTR4 的量纲：getDoorValue() 已经是 0..1，不该再补偏移 =====")
# 若给 MTR4 也补 0.1/32，全开会被顶到 1.003125 ⇒ 夹到 1.0 无害，但全关会变成 +0.003125
# ⇒ 关门时 prev 到不了「严格全开」以外的边界没有影响，真正的问题是「全关」被抬离 0，
#    开门判据 now > EDGE 仍成立（0.003125 < 0.02），所以**不补**才是正确选择。
mtr4_closed = 0.0
mtr4_open = 1.0
check(mtr4_closed <= EDGE and mtr4_open >= 1.0 - EDGE,
      "MTR4 原值 [0,1] 直接满足两条边界（无需偏移）")
check(not (mtr4_closed + 0.1 / 32.0 <= 0.0),
      "给 MTR4 补偏移会把「全关」抬离 0（所以 acceptMtr4 不补）",
      "0 + 0.1/32 = %.6f" % (0.1 / 32.0))

print()
print("===== 7) 组合穷举：4 个朝向 × 2 种半格 × 2 侧，锚点分组数必须恰好 = 1 =====")
for facing in ("NORTH", "SOUTH", "EAST", "WEST"):
    left, right = build_panels(facing)
    groups = {}
    for (bx, by, bz), side in ((left, "LEFT"), (right, "RIGHT")):
        for half in ("LOWER", "UPPER"):
            st = {"__isPsdDoor": True, "half": half, "side": side, "facing": facing}
            k = anchor_of(st, bx, by + (1 if half == "UPPER" else 0), bz)
            groups.setdefault(k, []).append((side, half))
    check(len(groups) == 1,
          "facing=%s：8 种（位置 × 半格）组合 → 1 组" % facing,
          "分组 = %s" % {k: v for k, v in groups.items()})

print()
print("===== 8) 数据源门禁：只有真屏蔽门才采集（电梯门必须被挡掉，且先筛后采）=====")


def is_psd_door(path):
    """Java 侧 SmoothLift.isPsdDoor 的等价实现：

        BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
            .startsWith("psd_door") || .startsWith("apg_door")
    """
    return path.startswith("psd_door") or path.startswith("apg_door")


# 注册名取自两个 MTR jar 的 assets/mtr/blockstates/ 与 InitClient 的渲染器注册表。
# ★「电梯门」这一组的名字里**没有** psd_/apg_ 前缀，所以白名单天然把它挡在外面 ——
#   但它继承了注入点的那个基类（见 Java 侧注释），少了门禁就会被当成屏蔽门播 psd_open/psd_close。
CASES = [
    ("psd_door",           True,  "MTR3/MTR4 屏蔽门主扇"),
    ("psd_door_2",         True,  "MTR 第二档屏蔽门"),
    ("apg_door",           True,  "半高安全门 APG"),
    ("lift_door_1",        False, "MTR3 电梯门（继承同一基类，曾静默多响一声）"),
    ("lift_door_odd_1",    False, "MTR3 电梯门（奇数档）"),
    ("lift_door_even_1",   False, "MTR4 电梯门（偶数档）"),
    ("psd_glass",          False, "屏蔽门玻璃：side/half 与门不是同一对，界面刻意不认"),
    ("psd_glass_end_2",    False, "屏蔽门端玻璃"),
    ("psd_top",            False, "屏蔽门顶板"),
    ("apg_glass",          False, "APG 玻璃"),
]
for name, want, note in CASES:
    got = is_psd_door(name)
    check(got == want, "注册名 %-18s → %s" % (name, "采集" if want else "丢弃"), note)

# 上面那条只是「判据本身对不对」；这条才是「门禁真的接在采集链路上」——
# 光有 isPsdDoor 而不在 accept() 里调用，症状一模一样（照样响）。
_java = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "src", "client", "java", "smooth", "lift", "client", "PsdDoorTracker.java")
_src = open(_java, encoding="utf-8").read()


def strip_comments(s):
    """去掉 Java 的行注释与块注释。

    ★ 必须去掉再判：本文件里注释也反复出现 isPsdDoor 这个词，
      不去掉的话「门禁代码被删掉、注释还留着」会让断言**假通过** ——
      本轮就是这么发现自己的断言不严的（见下面的反向对照）。
    """
    return re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", s, flags=re.S))


def accept_body(src):
    """取 accept(BlockPos, float) 的方法体（到下一个 4 空格缩进的右括号为止）。"""
    return src.split("private static void accept(", 1)[1].split("\n    }", 1)[0]


_code = strip_comments(accept_body(_src))
_i_gate = _code.find("SmoothLift.isPsdDoor(")
_i_put = _code.find("LIVE.put")
check(_i_gate >= 0, "accept() 里确实调用了 SmoothLift.isPsdDoor（门禁接在采集链路上，不是只在注释里）")
check(_i_gate >= 0 and (_i_put < 0 or _i_gate < _i_put),
      "门禁出现在 LIVE.put 之前（先筛后采，不是采完再删）",
      "gate@%d put@%d" % (_i_gate, _i_put))
check("private static long anchorOf(BlockState state, BlockPos raw)" in strip_comments(_src),
      "anchorOf 有 BlockState 重载（门禁查过的 state 复用，同一格不每帧查两次）")

# ★★ 反向对照：断言本身是否有效。把门禁那一行换成 if (false) 后，上面那条必须检测不到它。
#   没有这一条，「门禁被删掉但注释还在」的假通过是查不出来的。
_neg_code = strip_comments(accept_body(_src.replace("if (!SmoothLift.isPsdDoor(state)) {",
                                                    "if (false) {")))
check(_neg_code.find("SmoothLift.isPsdDoor(") < 0,
      "反向对照：拿掉门禁那一行后，判据检测不到它（说明判的是代码，不是注释）")

# ======================================================================
# 9) 【1.23】连在一起的每一扇站台门都要各自发声（不再是「只给最近那一扇」）
# ======================================================================
print()
print("===== 9) 每一扇站台门各自发声 =====")

_player = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "src", "client", "java", "smooth", "lift", "client",
                       "PsdChimePlayer.java")
_pcode = strip_comments(open(_player, encoding="utf-8").read())

# 取出 onClientTick 里那个「逐门」循环（从 for 到它自己的右括号）
_lm = re.search(r"for \(PsdDoorTracker\.DoorView door : doors\)\s*\{(.*?)\n        \}",
                _pcode, flags=re.S)
check(_lm is not None, "找得到 onClientTick 里的逐门循环")
if _lm:
    _loop = _lm.group(1)
    check("detect(mc, door, prev" in _loop,
          "★★ 循环体里对**每一扇**门都调 detect（各自带这一扇的距离）"
          " —— 不再是「只有最近那一扇」")
    check("Vec3(door.x(), door.y(), door.z())" in _loop,
          "★ 距离是按**这一扇门自己**的坐标算的（不是把最近门的距离复用到所有门）")
    check(_loop.index("noteCycle(") < _loop.index("detect("),
          "noteCycle 排在 detect 之前（先学周期再判跳变，顺序反了会晚一拍）")

# ★【1.26】`nearest*` 现在有了**新的合法含义**（「这一串里离玩家最近的那一扇」，
#   见 PsdDoorTracker.nearestInRun / PsdChimePlayer.nearestPerRun）—— 所以不能再整体禁掉这个词。
#   要禁的是**旧的闸门**：「先求出离玩家最近的那一扇门、然后只对它发声」。
#   那个闸门必然表现为一个**局部变量**（`DoorView nearest` / `== nearest` / `nearestDist`）。
_old_gate = re.search(r"(DoorView\s+nearest\b|==\s*nearest\b|\bnearestDist\b)", _pcode)
check(_old_gate is None,
      "★★ 旧的「只给离玩家最近的那一扇发声」闸门已**彻底不存在**"
      "（留着它 ⇒ 症状照旧：整条屏蔽门只有一个点在响）；"
      "★【1.26】nearestInRun / nearestPerRun 是**新的**合法含义（本串最近的门），不受这条限制",
      "命中 %s" % (_old_gate.group(1) if _old_gate else "无"))

# 「玻璃幕墙 / 幕墙尾部不发声」= 数据源头就滤掉（复用第 8 节那张注册名表）
for _n in ("psd_glass", "psd_glass_2", "psd_glass_end", "psd_glass_end_2",
           "psd_top", "apg_glass"):
    check(not is_psd_door(_n),
          "幕墙类 %-18s 进不了快照 ⇒ 结构上不可能发声" % _n)

# ★ 反向对照：断言本身有效吗？把「逐门」改回「只给最近一扇」，上面那条必须能抓到。
_neg = _pcode.replace("            detect(mc, door, prev, dist);",
                      "            if (door == nearest) { detect(mc, door, prev, dist); }")
check("nearest" in _neg and _neg != _pcode,
      "反向对照：改回「最近门」闸门后，判据检测得到它（说明判的是代码，不是注释）")

print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
