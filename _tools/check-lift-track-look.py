# -*- coding: utf-8 -*-
"""离线校验：本模组「把 MTR4 电梯楼层轨道换成 MTR3 样式」的覆盖文件是对的。

## 背景（这条覆盖的来龙去脉，别再动它）

用户需求原话：

1. 「能不能加入这个mod之后把mtr4的电梯楼层轨道的样式换成mtr3的楼层轨道，
   但是功能不变，只改变建模和贴图」
2. 之后报：「为什么改过的那个楼层轨道会变成类似于x光的透视方块？应该是不透明的才对啊」
3. 我据此把整个覆盖**撤掉**了，用户随即纠正：
   「不是要变成mtr3的轨道样式吗，为什么又变回来了」

⇒ **用户要的就是 MTR3 的样式，覆盖必须保留。** 「看着透」不是 bug、也不是被改坏的：

| | elements | 正面覆盖率 |
|---|---|---|
| MTR4 原版 | `[0,0,0] → [16,16,1]` | 16×16 / 16×16 = **100%** |
| MTR3 原版 | `[6,0,0] → [10,16,1]` | 4×16 / 16×16 = **25%** |
| 本模组的覆盖文件 | `[6,0,0] → [10,16,1]` | **25%**（= MTR3 原样） |

★ **不透明度 = 立柱宽度 ÷ 16。** MTR3 的楼层轨道**原版就是**这根 4px 窄柱，
所以「换成 MTR3 样式」与「方块不透明」**必然互斥**：
要 100% 不透明 ⇒ 宽度必须是 16px ⇒ 那恰好是 MTR4 原版。
在 MTR3 里不觉得它透，是因为柱子背后通常有井道墙；MTR4 的那片全宽薄板本身
常常就是那道"墙/门板"，一换窄柱当然就看穿了 —— 这是 MTR4 特有的观感，不是缺陷。

⇒ 所以本脚本**不再**把「不透明」当判据（那会与用户的需求互相打架），
  而是把覆盖率**算出来打印**，让「为什么看着透」永远可复核。

## 这个脚本查什么

1. 覆盖文件存在且是合法 JSON；
2. 它的几何 == **期望的 MTR3 几何**（常量，见下）；
3. 打印它的三向覆盖率（正面应为 25% = 4/16），并把「不透明度 = 宽度 ÷ 16」这条关系钉住；
4. `cullface` 只在 down/up/north（窄立柱不占满东西面，给它们 cullface 会漏面）；
5. **对照**：几何 != MTR4 的全宽薄板 —— 否则这个覆盖等于没做（证明断言有鉴别力）；
6. 若在工作区找到真实 MTR3 / MTR4 jar：把常量与 jar 内实际模型对撞，
   并断言我们的文件与真实 MTR3 模型**逐面一致**（连 cullface 都对）；
7. item 模型两侧都只是 parent 方块模型 ⇒ 我们**不该**单独覆盖它。
8. **【1.44】碰撞箱 / 选中框**：只换模型不换形状 = 留下「16px 宽、只有 4px 有贴图」的隐形墙。
   本段断言「我们发的模型几何 == 改完形状后的体素边界」（看得见的 == 挡得住的），
   并用 javap 把 MTR4 的 `getOutlineShape2(0,0,0,16,16,1)` 与 MTR3 的
   `method_9530(6,0,0,10,16,1)` 从**真实字节码**里读出来对撞 —— 顺带验证 mixin 里那条
   描述符字符串与 MTR4 字节码逐字相同（改错一个字母就查得出来）。

用法：`python _tools/check-lift-track-look.py`
"""
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLOCK_MODELS = os.path.join(ROOT, "src", "main", "resources", "assets", "mtr", "models", "block")
OVERRIDE = os.path.join(BLOCK_MODELS, "lift_track_floor_1.json")
ENTRY = "assets/mtr/models/block/lift_track_floor_1.json"
ITEM_ENTRY = "assets/mtr/models/item/lift_track_floor_1.json"

# 两版原生几何（下面第 6 步会拿真实 jar 复核这两个常量）
MTR3_FROM, MTR3_TO = [6, 0, 0], [10, 16, 1]
MTR4_FROM, MTR4_TO = [0, 0, 0], [16, 16, 1]

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def geometry_of(model):
    """把模型压成可比较的几何签名：(from, to, 每个面的 texture+cullface)。"""
    els = model.get("elements") or []
    return [(
        e.get("from"), e.get("to"),
        {f: (d.get("texture"), d.get("cullface")) for f, d in (e.get("faces") or {}).items()},
    ) for e in els]


def coverage_of(model):
    """把模型压成「各方向覆盖面占比」—— 这就是玩家看到的『透不透』。

    返回 (正面, 顶面, 侧面)：
      * 正面 = 法线沿 z 的面（north/south）→ 占 (dx/16) × (dy/16)
      * 顶面 = 法线沿 y 的面（up/down）  → 占 (dx/16) × (dz/16)
      * 侧面 = 法线沿 x 的面（east/west）→ 占 (dz/16) × (dy/16)
    """
    front = top = side = 0.0
    for frm, to, _faces in geometry_of(model):
        if not frm or not to or len(frm) != 3 or len(to) != 3:
            continue
        dx = (to[0] - frm[0]) / 16.0
        dy = (to[1] - frm[1]) / 16.0
        dz = (to[2] - frm[2]) / 16.0
        front += dx * dy
        top += dx * dz
        side += dz * dy
    return front, top, side


def width_of(model):
    """元素在 x 方向的像素宽度（正面不透明度的分子）。"""
    for frm, to, _f in geometry_of(model):
        if frm and to and len(frm) == 3 and len(to) == 3:
            return to[0] - frm[0]
    return None


def find_mtr_jars():
    """在工作区里按**内容**认 MTR3 / MTR4（不依赖文件名）。"""
    found = {"mtr3": None, "mtr4": None}
    for d in (os.path.dirname(ROOT), ROOT):
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.lower().endswith(".jar"):
                continue
            path = os.path.join(d, fn)
            try:
                with zipfile.ZipFile(path) as z:
                    names = set(z.namelist())
            except Exception:
                continue
            if "mtr/data/LiftServer.class" in names:
                found["mtr3"] = found["mtr3"] or path
            elif "org/mtr/core/data/Lift.class" in names:
                found["mtr4"] = found["mtr4"] or path
    return found


def read_model(jar, entry):
    try:
        with zipfile.ZipFile(jar) as z:
            return json.loads(z.read(entry).decode("utf-8"))
    except Exception:
        return None


# ----------------------------------------------------------------------
# 1) 覆盖文件本身
# ----------------------------------------------------------------------
check(os.path.isfile(OVERRIDE),
      "覆盖文件存在：assets/mtr/models/block/lift_track_floor_1.json（= MTR3 样式，用户点名要的）",
      os.path.relpath(OVERRIDE, ROOT))

own = None
if os.path.isfile(OVERRIDE):
    try:
        with open(OVERRIDE, encoding="utf-8") as fh:
            own = json.load(fh)
        check(True, "覆盖文件是合法 JSON")
    except Exception as exc:
        check(False, "覆盖文件是合法 JSON", "解析失败：%s" % exc)

own_geo = geometry_of(own) if own else []

# ----------------------------------------------------------------------
# 2) 必须是 MTR3 的几何
# ----------------------------------------------------------------------
expect3 = [(MTR3_FROM, MTR3_TO)]
check(len(own_geo) == 1 and own_geo[0][0] == MTR3_FROM and own_geo[0][1] == MTR3_TO,
      "几何 == MTR3 的窄立柱（from %s to %s）" % (MTR3_FROM, MTR3_TO),
      "实际 %s" % [(g[0], g[1]) for g in own_geo])

check(bool(own) and (own.get("textures") or {}).get("particle") == "block/smooth_stone",
      "贴图仍是 block/smooth_stone（两侧本来就一样，无需替换贴图文件）",
      "实际 %s" % (own.get("textures") if own else None))

# cullface 只该出现在 down/up/north —— 窄立柱不占满东西面，给它们 cullface 会漏面
own_faces = own_geo[0][2] if own_geo else {}
culled = sorted(f for f, (_t, c) in own_faces.items() if c)
check(culled == ["down", "north", "up"],
      "cullface 只在 down/up/north（窄立柱不该给 east/west 加 cullface）",
      "实际带 cullface 的面：%s" % culled)

# ----------------------------------------------------------------------
# 3) ★ 把「为什么看着透」算成数字（不是判据，是事实陈述）
# ----------------------------------------------------------------------
if own:
    c = coverage_of(own)
    w = width_of(own)
    print("      ★ 三向覆盖率：正面 %.2f%%、顶面 %.2f%%、侧面 %.2f%%"
          % (c[0] * 100, c[1] * 100, c[2] * 100))
    print("      ★ 宽度 %s px ⇒ 正面不透明度 = 宽度/16 = %.4f" % (w, (w or 0) / 16.0))
    check(w is not None and abs(c[0] - w / 16.0) < 1e-9,
          "「不透明度 = 立柱宽度 ÷ 16」这条关系成立（改宽度就能按比例调不透明度）",
          "%s px → 期望 %.4f，实测 %.4f" % (w, (w or 0) / 16.0, c[0]))

# ----------------------------------------------------------------------
# 4) 对照：必须和 MTR4 的几何**不同**，否则这个覆盖等于没做
# ----------------------------------------------------------------------
expect4 = [(MTR4_FROM, MTR4_TO)]
check([(g[0], g[1]) for g in own_geo] != expect4,
      "对照：几何 != MTR4 的全宽薄板（from %s to %s）⇒ 覆盖真的改变了外观"
      % (MTR4_FROM, MTR4_TO),
      "MTR4 是 %s；若两者相同则本覆盖毫无作用" % (expect4,))
print()

# ----------------------------------------------------------------------
# 5) 有 jar 就与真实模型对撞（证明上面的常量不是凭空写的）
# ----------------------------------------------------------------------
jars = find_mtr_jars()

if not jars["mtr3"] or not jars["mtr4"]:
    print("[SKIP] 工作区里没同时找到 MTR3 / MTR4 jar（MTR3=%s，MTR4=%s），"
          "跳过「与真实模型对撞」这一步" % (jars["mtr3"], jars["mtr4"]))
else:
    real3 = read_model(jars["mtr3"], ENTRY)
    real4 = read_model(jars["mtr4"], ENTRY)
    g3 = [(g[0], g[1]) for g in geometry_of(real3)] if real3 else None
    g4 = [(g[0], g[1]) for g in geometry_of(real4)] if real4 else None

    check(g3 == expect3,
          "真实 MTR3 jar 里的几何 == 脚本常量（%s → %s）" % (MTR3_FROM, MTR3_TO),
          "MTR3 实际 %s" % g3)
    check(g4 == expect4,
          "真实 MTR4 jar 里的几何 == 脚本常量（%s → %s）" % (MTR4_FROM, MTR4_TO),
          "MTR4 实际 %s" % g4)

    # 我们覆盖的几何是否与真实 MTR3 逐面一致（含 cullface / texture）
    if real3:
        check(geometry_of(own) == geometry_of(real3),
              "覆盖文件与真实 MTR3 模型**逐面一致**（连 cullface 都对）⇒ 就是 MTR3 原样",
              "对比面数 %d" % len(geometry_of(real3)))
        c3 = coverage_of(real3)
        print("      ★ 真实 MTR3 的覆盖率：正面 %.2f%%、顶面 %.2f%%、侧面 %.2f%%"
              % (c3[0] * 100, c3[1] * 100, c3[2] * 100))
        if real4:
            c4 = coverage_of(real4)
            print("      ★ 真实 MTR4 的覆盖率：正面 %.2f%%、顶面 %.2f%%、侧面 %.2f%%"
                  % (c4[0] * 100, c4[1] * 100, c4[2] * 100))
            print("      ★ 正面差 %.2f 个百分点 —— 这就是「换成 MTR3 样式」看得见的那部分"
                  % ((c4[0] - c3[0]) * 100))
            check(abs(c4[0] - 1.0) < 1e-9,
                  "对照：MTR4 原版正面覆盖率 == 100%（不透明），MTR3 只有 25%（见上）",
                  "两者互斥：要 100% 不透明就只能保留 MTR4 原版几何")

    # 物品模型：两侧都只是 parent 方块模型 ⇒ 我们**不该**单独覆盖它
    it3 = read_model(jars["mtr3"], ITEM_ENTRY) or {}
    it4 = read_model(jars["mtr4"], ITEM_ENTRY) or {}
    check(it3.get("parent") == "mtr:block/lift_track_floor_1"
          and it4.get("parent") == "mtr:block/lift_track_floor_1",
          "两侧物品模型都只是 parent 方块模型 ⇒ 无需单独覆盖物品模型",
          "MTR3 parent=%s，MTR4 parent=%s" % (it3.get("parent"), it4.get("parent")))

own_item = os.path.join(ROOT, "src", "main", "resources", "assets", "mtr",
                        "models", "item", "lift_track_floor_1.json")
check(not os.path.isfile(own_item),
      "本模组没有多余地覆盖物品模型（覆盖了反而可能把 MTR3 的图标带歪）",
      os.path.relpath(own_item, ROOT))

# ----------------------------------------------------------------------
# 6) ★【1.44】碰撞箱 / 选中框：模型换窄了，形状必须跟着窄，否则留下隐形墙
#
# 原版 BlockBehaviour.getCollisionShape 的默认实现就是 state.getShape(level,pos)，
# 也就是「形状 == 选中框 == 碰撞箱」。只换模型不换形状 ⇒ 一个 16px 宽、1px 厚、
# 但只有中间 4px 有贴图的隐形墙（用户报的 bug）。
#
# 这一段把三件事一起钉住：
#   a. 我们发的模型几何 == MTR3 的形状体素边界（看得见的 == 挡得住的）；
#   b. MTR4 的形状字节码确实是全宽薄板（所以要改）、MTR3 确实是窄柱（所以照着改）；
#   c. mixin 里那条描述符字符串 == MTR4 字节码里真实的那条调用（改错一个字母就查得出来）。
# ----------------------------------------------------------------------
MIXIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr",
                     "Mtr4LiftTrackFloorShapeMixin.java")
MIXINS_JSON = os.path.join(ROOT, "src", "main", "resources", "smoothlift.mtr.mixins.json")
PLUGIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr",
                      "Mtr3LiftMixinPlugin.java")
MTR3_SHAPE = [6.0, 0.0, 0.0, 10.0, 16.0, 1.0]
MTR4_SHAPE = [0.0, 0.0, 0.0, 16.0, 16.0, 1.0]

print()
check(os.path.isfile(MIXIN), "【1.44】碰撞箱 mixin 源文件存在", os.path.relpath(MIXIN, ROOT))

mixin_src = ""
if os.path.isfile(MIXIN):
    with open(MIXIN, encoding="utf-8") as fh:
        mixin_src = fh.read()

m_target = re.search(r'@Mixin\(\s*targets\s*=\s*"([^"]+)"', mixin_src)
check(bool(m_target) and m_target.group(1) == "org.mtr.mod.block.BlockLiftTrackFloor",
      "【1.44】mixin 目标是 MTR4 的楼层轨道方块类（org.mtr.mod.block.BlockLiftTrackFloor）",
      "实际 %s" % (m_target.group(1) if m_target else None))

check("@Pseudo" in mixin_src,
      "【1.44】带 @Pseudo（MTR 不是编译依赖，缺了它会报 Mixin target … could not be found 编译失败）")
check("remap = false" in mixin_src,
      "【1.44】@At/@ModifyArg 带 remap = false（目标是 MTR 自己的接口静态方法，不该被重映射）")


def parse_modify_args(src):
    """从 mixin 源码解析 (method, target, index, 替换值) —— 不抄常量。"""
    out = []
    for chunk in src.split("@ModifyArg")[1:]:
        m = re.search(r'method\s*=\s*"([^"]+)"', chunk)
        t = re.search(r'target\s*=\s*([^,\)]+)', chunk)
        i = re.search(r"index\s*=\s*(\d+)", chunk)
        v = re.search(r"return\s+([0-9.]+)f?\s*;", chunk)
        if m and i and v:
            out.append((m.group(1), (t.group(1).strip() if t else None), int(i.group(1)),
                        float(v.group(1))))
    return out


args = parse_modify_args(mixin_src)
check(len(args) == 2 and {a[2] for a in args} == {0, 3},
      "【1.44】只改 minX(index 0)/maxX(index 3) 两个实参，其余四个原样保留",
      "实际 %s" % [(a[2], a[3]) for a in args])
check(all(a[1] == "SHAPE_CALL" for a in args),
      "【1.44】两个 @ModifyArg 的 target 都指向 SHAPE_CALL 常量（便于与真实字节码对撞）",
      "实际 %s" % [a[1] for a in args])

_m = re.search(r'SHAPE_CALL\s*=\s*(.*?");', mixin_src, re.S)
call_str = "".join(re.findall(r'"([^"]*)"', _m.group(1))) if _m else ""
check(call_str.startswith("Lorg/mtr/mod/block/IBlock;getVoxelShapeByDirection(DDDDDD"),
      "【1.44】SHAPE_CALL 描述符形状正确（六个 double + Direction 的接口静态方法）",
      call_str)


def find_javap():
    home = os.path.expanduser("~")
    for jdk in ("eclipse_adoptium-17-amd64-windows.2", "jdk-21.0.11.10-hotspot"):
        for exe in ("javap", "javap.exe"):
            p = os.path.join(home, ".gradle", "jdks", jdk, "bin", exe)
            if os.path.isfile(p):
                return p
    return shutil.which("javap")


def javap_shape(javap, jar, cls, method):
    """从真实字节码里读出 (六个 double 常量, 规范化后的调用描述符)。"""
    try:
        out = subprocess.run([javap, "-c", "-p", "-cp", jar, cls],
                             capture_output=True, text=True, errors="replace").stdout
    except Exception:
        return None, None
    vals, desc, started = [], None, False
    for ln in out.splitlines():
        if not started:
            if method in ln and "(" in ln and ln.strip().endswith(";"):
                started = True
            continue
        if "getVoxelShapeByDirection" in ln:
            mm = re.search(r"//\s*(?:Interface)?Method\s+([\w/$]+)\.([\w$<>]+):(\(.*?\))(\S*)", ln)
            if mm:
                desc = "L%s;%s%s%s" % (mm.group(1), mm.group(2), mm.group(3), mm.group(4))
            break
        # javap 行形如「      3: ldc2_w        #101                // double 16.0d」
        mm = re.search(r"^\s*\d+:\s*([a-z0-9_]+)", ln)
        if not mm:
            continue
        op = mm.group(1)
        if op == "dconst_0":
            vals.append(0.0)
        elif op == "dconst_1":
            vals.append(1.0)
        elif op == "ldc2_w":
            d = re.search(r"//\s*double\s+([0-9.]+)d", ln)
            if d:
                vals.append(float(d.group(1)))
    return vals, desc


javap = find_javap()
if not (javap and jars["mtr3"] and jars["mtr4"]):
    print("[SKIP] 缺 javap 或缺 MTR jar（javap=%s），跳过形状字节码对撞" % javap)
else:
    v4, d4 = javap_shape(javap, jars["mtr4"],
                         "org.mtr.mod.block.BlockLiftTrackFloor", "getOutlineShape2")
    v3, _d3 = javap_shape(javap, jars["mtr3"], "mtr.block.BlockLiftTrack", "method_9530")

    check(v4 == MTR4_SHAPE,
          "【1.44】MTR4 真实字节码形状 == 全宽薄板 (0,0,0,16,16,1) ⇒ 这就是隐形墙的来源",
          "MTR4 实际 %s" % v4)
    check(v3 == MTR3_SHAPE,
          "【1.44】MTR3 真实字节码形状 == 窄柱 (6,0,0,10,16,1) ⇒ 照着它改就对了",
          "MTR3 实际 %s" % v3)
    check(call_str == d4,
          "【1.44】mixin 里的 SHAPE_CALL == MTR4 字节码里真实的那条调用（逐字相同）",
          "mixin %s\n          真实 %s" % (call_str, d4))

    by_index = {a[2]: a[3] for a in args}
    check(by_index.get(0) == v3[0] and by_index.get(3) == v3[3],
          "【1.44】mixin 的替换值 == MTR3 的 minX/maxX（6.0 / 10.0）",
          "mixin index0=%s index3=%s；MTR3 minX=%s maxX=%s"
          % (by_index.get(0), by_index.get(3), v3[0], v3[3]))
    check(v3[1] == v4[1] and v3[2] == v4[2] and v3[4] == v4[4] and v3[5] == v4[5],
          "【1.44】对照：minY/minZ/maxY/maxZ 两版本来就相同 ⇒ 只需改 X 那两个值",
          "MTR3 %s vs MTR4 %s" % (v3, v4))

    own_flat = [float(x) for x in (list(own_geo[0][0]) + list(own_geo[0][1]))] if own_geo else []
    check(own_flat == v3,
          "【1.44】★ 核心不变量：我们发的模型几何 == 改完形状后的体素边界"
          "（看得见的 == 挡得住的）",
          "模型 %s vs MTR3 形状 %s" % (own_flat, v3))

# mixins.json 注册 + 插件门禁
mj = {}
if os.path.isfile(MIXINS_JSON):
    with open(MIXINS_JSON, encoding="utf-8") as fh:
        mj = json.load(fh)
_mixins = mj.get("mixins") or []
check({"Mtr3LiftDoorMixin", "Mtr4LiftTrackFloorShapeMixin"} <= set(_mixins),
      "【1.44】mixins.json 同时注册了自动关门 mixin 与 MTR4 楼层轨道形状 mixin",
      "实际 %s" % _mixins)
_MIXIN_DIR = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr")
_missing = [m for m in _mixins if not os.path.isfile(os.path.join(_MIXIN_DIR, m + ".java"))]
check(not _missing,
      "mixins.json 里注册的每条 mixin 都有对应源文件（【1.45】加了 ExternalCall/PanelCall 后这里曾是盲区）",
      "缺失 %s" % _missing)

plug = ""
if os.path.isfile(PLUGIN):
    with open(PLUGIN, encoding="utf-8") as fh:
        plug = fh.read()
check("Mtr4LiftTrackFloorShapeMixin" in plug and "mtr4TrackFloorPresent" in plug,
      "【1.44】插件按「目标类是否存在」单独门禁新的 MTR4 mixin（装了 MTR3 时不动它）")
_plug_code = re.sub(r"/\*.*?\*/", "", plug, flags=re.S)
_plug_code = re.sub(r"//[^\n]*", "", _plug_code)
check("Class.forName" not in _plug_code and ".loadClass(" not in _plug_code,
      "【1.44】插件代码里没有 Class.forName / loadClass"
      "（铁律：Mixin 准备阶段加载类会让别的模组崩在 MixinTargetAlreadyLoadedException）")

if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
