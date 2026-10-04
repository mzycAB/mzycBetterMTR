# -*- coding: utf-8 -*-
"""离线校验：名字里的 `%`（本名/别名分隔符）显示掩码功能是否落地正确。

## 查什么（不开游戏）

1. **纯逻辑**：把 Java 的 `PlatformNameMask.mask` / `aliasOrSame` 原样搬成 Python，跑断言表。
   ★【10-03 五改】语义由「成对 toggle 隐藏」改成「**只有第一个 % 是分隔符**」：
   `%` 前 = 本名（到处都用它）、`%` 后 = 别名（只有屏蔽门玻璃那句「往X / toX」用）。
2. **源码结构**：
   - `PlatformNameMask.java` 存在且算法是「第一个 % 切两段」（同实例短路 + indexOf/substring）；
   - `PlatformNameMask` 暴露 `aliasOrSame`（别名出口）与 `enterRawWindow`/`rawWindowOpen`；
   - `Mtr4PidsNameMixin` 的 `@Mixin` target == `org.mtr.core.operation.ArrivalResponse`，
     `Mtr3PidsNameMixin` 的 `@Mixin` target == `mtr.render.RenderPIDS`；
   - ★【10-03 五改】新增 `Mtr4RouteArrowAliasMixin`：目标 `org.mtr.mod.client.RouteMapGenerator`，
     `@ModifyArg` 打在第 3 个 `IGui.insertTranslation`（ordinal = 2，= 往/to 那一次）的 varargs 数组上，
     调 `PlatformNameMask.aliasOrSame`；进了 json 的 client 数组 + 插件有放行分支；
   - 三个 mixin 都进了 `smoothlift.psd.mixins.json` 的 `client` 数组；
   - `PsdDoorMixinPlugin` 有对应的放行分支（`Mtr4PidsNameMixin` / `Mtr3PidsNameMixin` / 别名那条）；
   - ★ `MtrDwellAccess` 里 `PlatformNameMask.mask(` **正好 4 处** —— `ArrivalInfo` 的
     平台名两个出口（MTR3 的 `mtr3PlatformNameForPlatform`、MTR4 的
     `textOf(response, arrivalGetPlatformName)`）+【10-03 五改】终点站名两个出口
     （MTR3 的 `mtr3DestinationFor`、MTR4 的 `textOf(response, arrivalGetDestination)`）。
     **用户点名「讲述人念的是 % 前面的本名」**，而 MTR3 的名字是 public 字段、**不经 getter**，
     光靠 mixin 拦不下，所以这 4 处是需求的一部分，不是可选装饰。
3. **反向对照**：把 `Mtr4PidsNameMixin` 的 target 字符串故意改错 ==> 结构断言当场变红；
   把 `MtrDwellAccess` 里一处 `PlatformNameMask.mask(` 改坏 ==> 「正好 4 处」当场变红；
   把别名那条 mixin 的 `ordinal = 2` 改坏 ==> 断言变红；
   三处各再还原并用 md5 证明文件一字不差地回来了（防止反向实验污染源码）。

用法：`python _tools/check-pids-name-mask.py`（退出码 0 = 全部通过）
"""
import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def find_file(rel_glob_root_parts, name):
    """在 src 下找文件；返回绝对路径或 None。"""
    for base in ("src/client/java", "src/main/java", "src/client/resources", "src/main/resources"):
        cand = os.path.join(ROOT, base, *rel_glob_root_parts, name)
        if os.path.isfile(cand):
            return cand
    # 兜底：全盘搜
    for dirpath, _d, files in os.walk(os.path.join(ROOT, "src")):
        if name in files:
            return os.path.join(dirpath, name)
    return None


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as fh:
        h.update(fh.read())
    return h.hexdigest()


def strip_comments(src):
    """剥掉块注释与行注释 —— 断言只认**语义**，不认文档里出现的字面量。"""
    return re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", src, flags=re.S), flags=re.S)


# ----------------------------------------------------------------------
# 1) 纯逻辑：Python 版 mask / aliasOrSame（与 PlatformNameMask.java 逐字对应）
#    ★【10-03 五改】只有**第一个** % 是分隔符：前面 = 本名（到处显示它），
#    后面 = 别名（只有屏蔽门玻璃那句「往X / toX」用）。
# ----------------------------------------------------------------------
MARK = "%"


def mask(name):
    """本名 = 第一个 % 之前那一段；不含 % 时返回同一个实例（这里用原值表达）。"""
    if name is None or name == "":
        return name
    mark = name.find(MARK)
    if mark < 0:
        return name
    return name[:mark]


def alias_or_same(name):
    """别名 = 第一个 % 之后那一段（后面的 % 当普通字符）；不含 % 时原样。

    ★ Java 那边还多一条「查显示名->别名小表」的兜底（服务端已把目的地拼好、字符串里
    已经没有 % 时靠它），那一条依赖运行期 mask() 的调用历史，纯逻辑测不了；
    这里只对「字符串里还带 %」这一条做断言。
    """
    if name is None or name == "":
        return name
    mark = name.find(MARK)
    if mark < 0:
        return name
    alias = name[mark + 1:]
    return name if alias == "" else alias


CASES = [
    # (输入, 本名, 别名)
    ("香港|HongKong%市区|CityCenter", "香港|HongKong", "市区|CityCenter"),
    ("5%A%AB", "5", "A%AB"),
    ("AB%C%DE", "AB", "C%DE"),
    ("5A", "5A", "5A"),
    ("", "", ""),
    ("%A%", "", "A%"),
    ("a%b%c%d%e", "a", "b%c%d%e"),
    ("5%AB", "5", "AB"),
    # ★ 幂等：MtrDwellAccess 在 MTR4 那一支会**第二次**掩（getter 已被 mixin 掩过），
    #   掩过的值再掩必须还是它自己，否则第二处就变成 bug 而不是保险。
    ("5AB", "5AB", "5AB"),
    ("", "", ""),
    # ★ 别名本身不含 %（掩码输出永远不含）会走「查表」那条路；纯逻辑这一层它原样返回。
    ("5A", "5A", "5A"),
]


print("== [1] mask / aliasOrSame 逻辑断言 ==")
logic_ok = True
for src, exp_shown, exp_alias in CASES:
    got_shown = mask(src)
    ok_shown = got_shown == exp_shown
    logic_ok = logic_ok and ok_shown
    check(ok_shown, "mask(%r) == %r" % (src, exp_shown),
          "实际得到 %r" % got_shown if not ok_shown else "")
    got_alias = alias_or_same(src)
    ok_alias = got_alias == exp_alias
    logic_ok = logic_ok and ok_alias
    check(ok_alias, "aliasOrSame(%r) == %r" % (src, exp_alias),
          "实际得到 %r" % got_alias if not ok_alias else "")
print("==> %s" % ("通过" if logic_ok and not FAILS else "失败"))
print()


# ----------------------------------------------------------------------
# 2) 源码结构断言
# ----------------------------------------------------------------------
print("== [2] 源码结构断言 ==")

mask_file = find_file(("smooth", "lift", "client"), "PlatformNameMask.java")
mask_file = mask_file or find_file((), "PlatformNameMask.java")
check(bool(mask_file), "PlatformNameMask.java 存在",
      os.path.relpath(mask_file, ROOT) if mask_file else "未找到")
if mask_file:
    msrc = open(mask_file, encoding="utf-8").read()
    check("public static String mask(String" in msrc,
          "PlatformNameMask 暴露 public static String mask(String)")
    check("int mark = name.indexOf(MARK);" in msrc and "if (mark < 0) {" in msrc
          and "return name;" in msrc,
          "无 % 时返回同一个实例（零分配捷径：indexOf < 0 直接 return 入参）")
    # ★【10-03 五改】语义由「成对 toggle 隐藏」改成「只有第一个 % 是分隔符」：
    #   判据 = 用 indexOf + substring 切两段，而**不是** hidden 翻转。
    check("int mark = name.indexOf(MARK);" in msrc and "name.substring(0, mark)" in msrc,
          "算法 = 只有第一个 % 是分隔符（indexOf + substring 切「本名」）")
    check("hidden" not in msrc,
          "旧的成对 toggle（hidden = !hidden）已经不存在")
    check("public static String aliasOrSame(String" in msrc,
          "PlatformNameMask 暴露 aliasOrSame（别名出口，屏蔽门方向箭头那一处用）")
    check("name.substring(mark + 1)" in msrc,
          "别名 = 第一个 % 之后那一段（后面的 % 当普通字符）")
    check("ALIAS_BY_SHOWN" in msrc and "ConcurrentHashMap" in msrc,
          "★ 有「显示名 -> 别名」小表（服务端已拼好的目的地字符串里没有 %，只能靠掩码那一刻记）")

m4 = find_file(("smooth", "lift", "client", "mixin"), "Mtr4PidsNameMixin.java")
m3 = find_file(("smooth", "lift", "client", "mixin"), "Mtr3PidsNameMixin.java")
check(bool(m4), "Mtr4PidsNameMixin.java 存在",
      os.path.relpath(m4, ROOT) if m4 else "未找到")
check(bool(m3), "Mtr3PidsNameMixin.java 存在",
      os.path.relpath(m3, ROOT) if m3 else "未找到")

if m4:
    s4 = open(m4, encoding="utf-8").read()
    check('@Mixin(targets = "org.mtr.core.operation.ArrivalResponse")' in s4,
          "Mtr4PidsNameMixin 目标 = org.mtr.core.operation.ArrivalResponse")
    check("@Pseudo" in s4, "Mtr4PidsNameMixin 带 @Pseudo")
    check("getPlatformName()Ljava/lang/String;" in s4,
          "Mtr4PidsNameMixin 注入 getPlatformName()Ljava/lang/String;")
    check("PlatformNameMask.mask" in s4, "Mtr4PidsNameMixin 调 PlatformNameMask.mask")

if m3:
    s3 = open(m3, encoding="utf-8").read()
    check('@Mixin(targets = "mtr.render.RenderPIDS")' in s3,
          "Mtr3PidsNameMixin 目标 = mtr.render.RenderPIDS")
    check("@Pseudo" in s3, "Mtr3PidsNameMixin 带 @Pseudo")
    check("lambda$getSchedules$0" in s3, "Mtr3PidsNameMixin 重定向 lambda$getSchedules$0")
    check("Ljava/util/Map;put" in s3, "Mtr3PidsNameMixin 重定向 Map.put")
    check("require = 0" in s3, "Mtr3PidsNameMixin 用 require = 0（lambda 名缺失也不崩）")
    check("PlatformNameMask.mask" in s3, "Mtr3PidsNameMixin 调 PlatformNameMask.mask")

# mixins.json 的 client 数组
mj = find_file((), "smoothlift.psd.mixins.json")
check(bool(mj), "smoothlift.psd.mixins.json 存在",
      os.path.relpath(mj, ROOT) if mj else "未找到")
if mj:
    import json
    raw = open(mj, encoding="utf-8").read()
    cfg = json.loads(raw.replace("\r\n", "\n").replace("\r", "\n"))
    client = cfg.get("client", [])
    check("Mtr4PidsNameMixin" in client, "Mtr4PidsNameMixin 在 client 数组", str(client))
    check("Mtr3PidsNameMixin" in client, "Mtr3PidsNameMixin 在 client 数组", str(client))

# PsdDoorMixinPlugin 放行分支
plugin = find_file(("smooth", "lift", "mixin", "mtr"), "PsdDoorMixinPlugin.java")
check(bool(plugin), "PsdDoorMixinPlugin.java 存在",
      os.path.relpath(plugin, ROOT) if plugin else "未找到")
if plugin:
    psrc = open(plugin, encoding="utf-8").read()
    # 剥注释后判定（避免注释里出现类名/铁律反例被误判）
    body = re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", psrc, flags=re.S), flags=re.S)
    check("Mtr4PidsNameMixin" in body, "PsdDoorMixinPlugin 有 Mtr4PidsNameMixin 放行分支")
    check("Mtr3PidsNameMixin" in body, "PsdDoorMixinPlugin 有 Mtr3PidsNameMixin 放行分支")
    # 铁律：不得用 Class.forName 探测（剥掉注释后再查正文，避免文档里的反例被误伤）
    check("Class.forName" not in body and ".loadClass(" not in body,
          "PsdDoorMixinPlugin 仍遵守铁律：正文中无 Class.forName / loadClass")

# ★ MtrDwellAccess：ArrivalInfo 的**平台名**与**终点站名**两组出口都要过掩码。
#   判据按「域」切：只看 MtrDwellAccess 这一个文件里的调用数，不跟全局计数混。
dwell = find_file(("smooth", "lift", "client"), "MtrDwellAccess.java")
check(bool(dwell), "MtrDwellAccess.java 存在",
      os.path.relpath(dwell, ROOT) if dwell else "未找到")
if dwell:
    dbody = strip_comments(open(dwell, encoding="utf-8").read())
    n_mask = dbody.count("PlatformNameMask.mask(")
    check(n_mask == 4,
          "MtrDwellAccess 里 PlatformNameMask.mask( 正好 4 处（平台名 2 + 终点站名 2）",
          "实际 %d 处" % n_mask)
    check("PlatformNameMask.mask(mtr3PlatformNameForPlatform(platformId))" in dbody,
          "MTR3 出口已掩码（Platform.name 是 public 字段、不经 getter，必须在这里掩）")
    check("PlatformNameMask.mask(textOf(response, arrivalGetPlatformName))" in dbody,
          "MTR4 出口已掩码（与 MTR3 走同一出口；mask 幂等 ==> 第二处是保险不是重复）")
    # 【10-03 五改】终点站名（讲述人念的那一句「前往X的列车」）也必须用本名。
    check("PlatformNameMask.mask(mtr3DestinationFor(entry))" in dbody,
          "MTR3 终点站名出口已掩码（% 后面是给屏蔽门箭头用的别名，讲述人不念）")
    check("PlatformNameMask.mask(textOf(response, arrivalGetDestination))" in dbody,
          "MTR4 终点站名出口已掩码（与 MTR3 同形）")

print("==> %s" % ("通过" if not FAILS else "失败"))
print()


# ----------------------------------------------------------------------
# 3) 反向对照：故意改错 target ==> 断言变红 ==> 还原并 md5 证还原
# ----------------------------------------------------------------------
print("== [3] 反向对照（mutation 后必须变红 + md5 证还原） ==")

if m4:
    with open(m4, "rb") as fh:
        before_b = fh.read()
    before_md5 = hashlib.md5(before_b).hexdigest()
    t_good = b'@Mixin(targets = "org.mtr.core.operation.ArrivalResponse")'
    t_bad = b'@Mixin(targets = "org.mtr.core.operation.ArrivalResponse_WRONG")'
    check(t_good in before_b, "Mtr4PidsNameMixin 原文件含正确 target（实验前提）")
    mutated_b = before_b.replace(t_good, t_bad)
    check(mutated_b != before_b, "已构造 mutation（target 改错）")
    with open(m4, "wb") as fh:
        fh.write(mutated_b)
    s4m = open(m4, "rb").read()
    turned_red = t_good not in s4m
    check(turned_red, "mutation 后『target == ArrivalResponse』断言当场变红")
    # 还原（二进制写回，保住原行尾）
    with open(m4, "wb") as fh:
        fh.write(before_b)
    after_md5 = hashlib.md5(open(m4, "rb").read()).hexdigest()
    check(before_md5 == after_md5,
          "还原成功（md5 一致）：%s" % after_md5,
          "还原前后 md5 不一致！源码可能被污染" if before_md5 != after_md5 else "")
    s4r = open(m4, "rb").read()
    check(t_good in s4r, "还原后结构断言恢复为绿")
else:
    check(False, "反向对照：Mtr4PidsNameMixin 缺失，无法做 mutation 实验")

# 第二组反向对照：把 MtrDwellAccess 的 MTR3 出口掩码改坏 ==>「正好 4 处」必须变红。
if dwell:
    with open(dwell, "rb") as fh:
        d_before = fh.read()
    d_before_md5 = hashlib.md5(d_before).hexdigest()
    d_good = b"PlatformNameMask.mask(mtr3PlatformNameForPlatform(platformId))"
    d_bad = b"PlatformNameMask.xxxx(mtr3PlatformNameForPlatform(platformId))"
    check(d_good in d_before, "MtrDwellAccess 原文件含 MTR3 出口掩码（实验前提）")
    d_mutated = d_before.replace(d_good, d_bad)
    check(d_mutated != d_before, "已构造 mutation（MTR3 出口掩码改坏）")
    with open(dwell, "wb") as fh:
        fh.write(d_mutated)
    d_now = strip_comments(open(dwell, encoding="utf-8").read())
    d_n = d_now.count("PlatformNameMask.mask(")
    check(d_n == 3, "mutation 后『正好 4 处』当场变红", "实际 %d 处" % d_n)
    # 还原（二进制写回，保住原行尾）
    with open(dwell, "wb") as fh:
        fh.write(d_before)
    d_after_md5 = hashlib.md5(open(dwell, "rb").read()).hexdigest()
    check(d_before_md5 == d_after_md5,
          "还原成功（md5 一致）：%s" % d_after_md5,
          "还原前后 md5 不一致！源码可能被污染" if d_before_md5 != d_after_md5 else "")
else:
    check(False, "反向对照：MtrDwellAccess 缺失，无法做 mutation 实验")

# ----------------------------------------------------------------------
# 2b) 二次订正：名字掩码改成「源头掩 + 设置界面开原样窗口」
# ----------------------------------------------------------------------
print("== [2b] 二次订正的结构断言 ==")

if mask_file:
    msrc2 = open(mask_file, encoding="utf-8").read()
    check("public static void enterRawWindow()" in msrc2,
          "PlatformNameMask 暴露 enterRawWindow()")
    check("public static boolean rawWindowOpen()" in msrc2,
          "PlatformNameMask 暴露 rawWindowOpen()")
    check("if (rawWindowOpen()) {" in msrc2,
          "★ mask 开头有『原样窗口开着就原样返回』的短路（设置界面靠它放行）")
    check("rawUntilMillis = 0L;" in msrc2,
          "窗口过期即归零（常见路径不调时钟、且不会漏成常开）")

# 三条新 mixin
mm = find_file(("smooth", "lift", "client", "mixin"), "Mtr4NameMaskMixin.java")
check(bool(mm), "Mtr4NameMaskMixin.java 存在",
      os.path.relpath(mm, ROOT) if mm else "未找到")
if mm:
    sm = open(mm, encoding="utf-8").read()
    check('@Mixin(targets = "org.mtr.core.data.NameColorDataBase")' in sm,
          "Mtr4NameMaskMixin 目标 = org.mtr.core.data.NameColorDataBase（名字源头，一处盖住全部）")
    check("@Pseudo" in sm, "Mtr4NameMaskMixin 带 @Pseudo")
    check("getName()Ljava/lang/String;" in sm,
          "Mtr4NameMaskMixin 注入 getName()Ljava/lang/String;")
    check("PlatformNameMask.mask" in sm, "Mtr4NameMaskMixin 调 PlatformNameMask.mask")

sr = find_file(("smooth", "lift", "client", "mixin"), "Mtr4SavedRailRawMixin.java")
check(bool(sr), "Mtr4SavedRailRawMixin.java 存在",
      os.path.relpath(sr, ROOT) if sr else "未找到")
if sr:
    ss = open(sr, encoding="utf-8").read()
    check('@Mixin(targets = "org.mtr.mod.screen.SavedRailScreenBase")' in ss,
          "Mtr4SavedRailRawMixin 目标 = org.mtr.mod.screen.SavedRailScreenBase（站台/侧线设置）")
    check("PlatformNameMask.enterRawWindow" in ss, "Mtr4SavedRailRawMixin 开原样窗口")
    # ★ 填输入框那一步必须 require = 1：不开窗口 ==> 一保存就把用户的 % 吃掉（丢数据）
    check(bool(re.search(r'@Inject\(method = "init2\(\)V".{0,200}?require = 1', ss, re.S)),
          "Mtr4SavedRailRawMixin.init2 用 require = 1（不开窗口就会丢数据）")

en = find_file(("smooth", "lift", "client", "mixin"), "Mtr4EditNameRawMixin.java")
check(bool(en), "Mtr4EditNameRawMixin.java 存在",
      os.path.relpath(en, ROOT) if en else "未找到")
if en:
    se = open(en, encoding="utf-8").read()
    check('@Mixin(targets = "org.mtr.mod.screen.EditNameColorScreenBase")' in se,
          "Mtr4EditNameRawMixin 目标 = org.mtr.mod.screen.EditNameColorScreenBase（车站/车厂/线路设置）")
    check("PlatformNameMask.enterRawWindow" in se, "Mtr4EditNameRawMixin 开原样窗口")
    check(bool(re.search(r'@Inject\(method = "setPositionsAndInit\(III\)V".{0,200}?require = 1', se, re.S)),
          "Mtr4EditNameRawMixin.setPositionsAndInit 用 require = 1（saveData 会无条件写回输入框）")

# ★ 控制板的内联改名：startEditingArea/startEditingRoute 填输入框、onDoneEditing* 无条件写回
#   ==> 也必须在这两个「开始编辑」入口放行一次（列表照旧隐藏，因为窗口 500ms 就过期）。
db = find_file(("smooth", "lift", "client", "mixin"), "Mtr4DashboardRawMixin.java")
check(bool(db), "Mtr4DashboardRawMixin.java 存在",
      os.path.relpath(db, ROOT) if db else "未找到")
if db:
    sd = open(db, encoding="utf-8").read()
    check('@Mixin(targets = "org.mtr.mod.screen.DashboardScreen")' in sd,
          "Mtr4DashboardRawMixin 目标 = org.mtr.mod.screen.DashboardScreen（控制板）")
    check("PlatformNameMask.enterRawWindow" in sd, "Mtr4DashboardRawMixin 开原样窗口")
    _a = re.search(r'@Inject\(method = "startEditingArea\(Lorg/mtr/core/data/AreaBase;Z\)V".{0,200}?require = 1', sd, re.S)
    _r = re.search(r'@Inject\(method = "startEditingRoute\(Lorg/mtr/core/data/Route;Z\)V".{0,200}?require = 1', sd, re.S)
    check(bool(_a), "Mtr4DashboardRawMixin.startEditingArea 用 require = 1（填输入框，失败会丢标记）")
    check(bool(_r), "Mtr4DashboardRawMixin.startEditingRoute 用 require = 1（同上）")
    _n_inject = strip_comments(sd).count("@Inject(")
    check(_n_inject == 2,
          "Mtr4DashboardRawMixin 只挂 2 个注入（★ 第三个入口 startEditingRouteDestination "
          "填的是 getCustomDestination —— 普通字符串字段、本来没被掩，故意不挂）",
          "实际 %d 个 @Inject(" % _n_inject)

if mj:
    import json as _json3
    _cfg3 = _json3.loads(open(mj, encoding="utf-8").read().replace("\r\n", "\n").replace("\r", "\n"))
    check("Mtr4DashboardRawMixin" in _cfg3.get("client", []),
          "Mtr4DashboardRawMixin 在 client 数组", str(_cfg3.get("client", [])))
if plugin:
    _pb3 = re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", open(plugin, encoding="utf-8").read(), flags=re.S), flags=re.S)
    check("Mtr4DashboardRawMixin" in _pb3, "PsdDoorMixinPlugin 有 Mtr4DashboardRawMixin 放行分支")

# ★【10-03 五改】别名那条 mixin：屏蔽门玻璃上「往X / toX」的 X 用别名。
ra = find_file(("smooth", "lift", "client", "mixin"), "Mtr4RouteArrowAliasMixin.java")
check(bool(ra), "Mtr4RouteArrowAliasMixin.java 存在",
      os.path.relpath(ra, ROOT) if ra else "未找到")
if ra:
    sr2 = open(ra, encoding="utf-8").read()
    check('@Mixin(targets = "org.mtr.mod.client.RouteMapGenerator")' in sr2,
          "别名 mixin 目标 = org.mtr.mod.client.RouteMapGenerator（往X 的唯一产地）")
    check("@Pseudo" in sr2, "别名 mixin 带 @Pseudo（没装 MTR4 时安静跳过）")
    check("@ModifyArg(" in sr2, "别名 mixin 用 @ModifyArg（只换那一格字串，不换几何/图层）")
    check("generateDirectionArrow(JZZLorg/mtr/mod/data/IGui$HorizontalAlignment;ZFFIII)" in sr2,
          "别名 mixin 打在 generateDirectionArrow（往/to 那句就在这一个方法里）")
    check("ordinal = 2" in sr2,
          "★ 挑第 3 个 insertTranslation（ordinal = 2）：前两个是环线的 clockwise/anticlockwise via，"
          "只有第三个是「往 / to」")
    check("index = 3" in sr2, "改第 4 个实参（varargs 的 String[]）里的第 0 格")
    check("PlatformNameMask.aliasOrSame" in sr2, "别名 mixin 调 PlatformNameMask.aliasOrSame")
    check("require = 1" in sr2 and "remap = false" in sr2,
          "别名 mixin 用 require = 1 + remap = false（MTR 自己的方法名，不参与原版映射）")
    # ★【10-03 五改】Mixin 的硬判据：处理器的 static 必须与目标方法一致
    #   （Injector 原话 "'static' modifier of handler method does not match target"；
    #   不一致 ⇒ 注入被拒，require = 0 时还完全静默 —— 这条真踩过）。
    #   generateDirectionArrow 是 **static** ⇒ 这里必须 static。
    check("private static String[] smoothlift$aliasTerminusOnArrow(String[] args)" in sr2,
          "★ 别名 mixin 的处理器是 **static**（目标 generateDirectionArrow 是静态方法，static 必须一致）")

if mj:
    import json as _json4
    _cfg4 = _json4.loads(open(mj, encoding="utf-8").read().replace("\r\n", "\n").replace("\r", "\n"))
    check("Mtr4RouteArrowAliasMixin" in _cfg4.get("client", []),
          "Mtr4RouteArrowAliasMixin 在 client 数组", str(_cfg4.get("client", [])))
if plugin:
    _pb4 = re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", open(plugin, encoding="utf-8").read(), flags=re.S), flags=re.S)
    check("Mtr4RouteArrowAliasMixin" in _pb4, "PsdDoorMixinPlugin 有 Mtr4RouteArrowAliasMixin 放行分支")
    check("org.mtr.mod.client.RouteMapGenerator" in _pb4,
          "别名 mixin 的门禁按它自己的目标类判（RouteMapGenerator）")

# ★【10-03 五改 修订】屏蔽门/线路牌的**线路图圆圈**站名：SimplifiedRoutePlatform.getStationName()
#   是**序列化字段**（getter 就是 getfield），不经 getName() ⇒ 掩码 mixin 拦不住字段，
#   用户实测「线路图圆圈整段显示 香港|HongKong%市区|CityCenter，而 MTR 地图是对的」。
rm = find_file(("smooth", "lift", "client", "mixin"), "Mtr4RouteMapNameMixin.java")
check(bool(rm), "Mtr4RouteMapNameMixin.java 存在",
      os.path.relpath(rm, ROOT) if rm else "未找到")
if rm:
    srm = open(rm, encoding="utf-8").read()
    check('@Mixin(targets = "org.mtr.core.data.SimplifiedRoutePlatform")' in srm,
          "线路图圆圈 mixin 目标 = org.mtr.core.data.SimplifiedRoutePlatform")
    check("getStationName()Ljava/lang/String;" in srm,
          "注入 getStationName()Ljava/lang/String;（序列化字段的 getter）")
    check("PlatformNameMask.mask(cir.getReturnValue())" in srm,
          "调 PlatformNameMask.mask（掩成 % 前的本名）")
    check("private void smoothlift$maskRouteMapStationName(" in srm
          and "private static void smoothlift$maskRouteMapStationName" not in srm,
          "★ 处理器是**实例方法**（getStationName 是实例方法 —— Mixin 的 static 判据）")
    check("require = 1" in srm, "用 require = 1（线路图圆圈全站可见，漏掉就是明晃晃的 %）")
if mj:
    import json as _json5
    _cfg5 = _json5.loads(open(mj, encoding="utf-8").read().replace("\r\n", "\n").replace("\r", "\n"))
    check("Mtr4RouteMapNameMixin" in _cfg5.get("client", []),
          "Mtr4RouteMapNameMixin 在 client 数组", str(_cfg5.get("client", [])))
if plugin:
    _pb5 = re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", open(plugin, encoding="utf-8").read(), flags=re.S), flags=re.S)
    check("Mtr4RouteMapNameMixin" in _pb5
          and 'maskTargetPresent("org.mtr.core.data.SimplifiedRoutePlatform"' in _pb5,
          "PsdDoorMixinPlugin 有 Mtr4RouteMapNameMixin 放行分支（按自己的目标类判）")

# json 注册
if mj:
    import json as _json2
    _raw2 = open(mj, encoding="utf-8").read()
    _cfg2 = _json2.loads(_raw2.replace("\r\n", "\n").replace("\r", "\n"))
    _client2 = _cfg2.get("client", [])
    for _n in ("Mtr4NameMaskMixin", "Mtr4SavedRailRawMixin", "Mtr4EditNameRawMixin"):
        check(_n in _client2, "%s 在 client 数组" % _n, str(_client2))

# 插件门禁
if plugin:
    pbody2 = re.sub(r"/\*.*?\*/", "", re.sub(r"//[^\n]*", "", open(plugin, encoding="utf-8").read(), flags=re.S), flags=re.S)
    for _n in ("Mtr4NameMaskMixin", "Mtr4SavedRailRawMixin", "Mtr4EditNameRawMixin"):
        check(_n in pbody2, "PsdDoorMixinPlugin 有 %s 放行分支" % _n)
    check("maskTargetPresent" in pbody2, "PsdDoorMixinPlugin 用通用门禁 maskTargetPresent")
    check("org.mtr.core.data.NameColorDataBase" in pbody2,
          "门禁按 mixin 自己的目标类判（NameColorDataBase），不是借 PSD 门方块")
    check("Class.forName" not in pbody2 and ".loadClass(" not in pbody2,
          "新增门禁仍遵守铁律：正文中无 Class.forName / loadClass")

print("==> %s" % ("通过" if not FAILS else "失败"))
print()


# ----------------------------------------------------------------------
# 3b) 二次订正的反向对照：拿掉「原样窗口短路」==> 断言必须变红
# ----------------------------------------------------------------------
print("== [3b] 反向对照：原样窗口短路（mutation 后必须变红 + md5 证还原） ==")

if mask_file:
    with open(mask_file, "rb") as fh:
        w_before = fh.read()
    w_before_md5 = hashlib.md5(w_before).hexdigest()
    w_good = b"if (rawWindowOpen()) {"
    w_bad = b"if (false) {"
    check(w_good in w_before, "PlatformNameMask 原文件含原样窗口短路（实验前提）")
    w_mut = w_before.replace(w_good, w_bad)
    check(w_mut != w_before, "已构造 mutation（短路被拿掉）")
    with open(mask_file, "wb") as fh:
        fh.write(w_mut)
    w_now = open(mask_file, encoding="utf-8").read()
    check(w_good.decode() not in w_now,
          "mutation 后『mask 里带原样窗口短路』当场变红")
    with open(mask_file, "wb") as fh:
        fh.write(w_before)
    w_after_md5 = hashlib.md5(open(mask_file, "rb").read()).hexdigest()
    check(w_before_md5 == w_after_md5,
          "还原成功（md5 一致）：%s" % w_after_md5,
          "还原前后 md5 不一致！源码可能被污染" if w_before_md5 != w_after_md5 else "")
else:
    check(False, "反向对照：PlatformNameMask 缺失，无法做 mutation 实验")

print("==> %s" % ("通过" if not FAILS else "失败"))
print()


# ----------------------------------------------------------------------
# 3c) 别名那条 mixin 的反向对照：把 ordinal 改错 ==> 断言必须变红
# ----------------------------------------------------------------------
print("== [3c] 反向对照：别名 mixin 的 ordinal（mutation 后必须变红 + md5 证还原） ==")

if ra:
    with open(ra, "rb") as fh:
        r_before = fh.read()
    r_before_md5 = hashlib.md5(r_before).hexdigest()
    r_good = b"ordinal = 2"
    r_bad = b"ordinal = 1"
    check(r_good in r_before, "别名 mixin 原文件含 ordinal = 2（实验前提）")
    r_mut = r_before.replace(r_good, r_bad)
    check(r_mut != r_before, "已构造 mutation（ordinal 改成 1）")
    with open(ra, "wb") as fh:
        fh.write(r_mut)
    r_now = open(ra, encoding="utf-8").read()
    check("ordinal = 2" not in r_now,
          "mutation 后『挑第 3 个 insertTranslation』断言当场变红")
    with open(ra, "wb") as fh:
        fh.write(r_before)
    r_after_md5 = hashlib.md5(open(ra, "rb").read()).hexdigest()
    check(r_before_md5 == r_after_md5,
          "还原成功（md5 一致）：%s" % r_after_md5,
          "还原前后 md5 不一致！源码可能被污染" if r_before_md5 != r_after_md5 else "")
else:
    check(False, "反向对照：Mtr4RouteArrowAliasMixin 缺失，无法做 mutation 实验")

print("==> %s" % ("通过" if not FAILS else "失败"))
print()


# ----------------------------------------------------------------------
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
