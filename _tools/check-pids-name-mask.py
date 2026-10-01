# -*- coding: utf-8 -*-
"""离线校验：屏蔽门（PIDS）站台名显示掩码功能是否落地正确。

## 查什么（不开游戏）

1. **纯逻辑**：把 Java 的 `PlatformNameMask.mask` 原样搬成 Python，跑断言表
   （`%` 是成对开关 / toggle）。
2. **源码结构**：
   - `PlatformNameMask.java` 存在且算法是 toggle（同实例短路 + `hidden = !hidden`）；
   - `Mtr4PidsNameMixin` 的 `@Mixin` target == `org.mtr.core.operation.ArrivalResponse`，
     `Mtr3PidsNameMixin` 的 `@Mixin` target == `mtr.render.RenderPIDS`；
   - 两个 mixin 都进了 `smoothlift.psd.mixins.json` 的 `client` 数组；
   - `PsdDoorMixinPlugin` 有对应的放行分支（`Mtr4PidsNameMixin` / `Mtr3PidsNameMixin`）；
   - ★ `MtrDwellAccess` 里 `PlatformNameMask.mask(` **正好 2 处** —— `ArrivalInfo.platformName`
     只有这两个出口（MTR3 的 `mtr3PlatformNameForPlatform`、MTR4 的
     `textOf(response, arrivalGetPlatformName)`）。**用户点名「讲述人念的站台名与屏蔽门一致」**，
     而 MTR3 的名字是 public 字段 `Platform.name`、**不经 getter**，光靠 mixin 拦不下，
     所以这 2 处是需求的一部分，不是可选装饰。
3. **反向对照**：把 `Mtr4PidsNameMixin` 的 target 字符串故意改错 ⇒ 结构断言当场变红；
   把 `MtrDwellAccess` 里一处 `PlatformNameMask.mask(` 改坏 ⇒ 「正好 2 处」当场变红；
   两处各再还原并用 md5 证明文件一字不差地回来了（防止反向实验污染源码）。

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
# 1) 纯逻辑：Python 版 mask（与 PlatformNameMask.java 逐字对应）
# ----------------------------------------------------------------------
MARK = "%"


def mask(name):
    if name is None or name == "" or name.find(MARK) < 0:
        return name
    sb = []
    hidden = False
    for c in name:
        if c == MARK:
            hidden = not hidden
            continue
        if not hidden:
            sb.append(c)
    return "".join(sb)


CASES = [
    ("5%A%AB", "5AB"),
    ("AB%C%DE", "ABDE"),
    ("5A", "5A"),
    ("", ""),
    ("%A%", ""),
    ("a%b%c%d%e", "ace"),
    ("5%AB", "5"),
    # ★ 幂等：MtrDwellAccess 在 MTR4 那一支会**第二次**掩（getter 已被 mixin 掩过），
    #   掩过的值再掩必须还是它自己，否则第二处就变成 bug 而不是保险。
    ("5AB", "5AB"),
    ("", ""),
]


print("== [1] mask 逻辑断言 ==")
logic_ok = True
for src, exp in CASES:
    got = mask(src)
    ok = got == exp
    logic_ok = logic_ok and ok
    check(ok, "mask(%r) == %r" % (src, exp), "实际得到 %r" % got if not ok else "")
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
    check("indexOf(MARK) < 0" in msrc and "return name" in msrc,
          "无 % 时返回同一个实例（零分配捷径）")
    check("hidden = !hidden" in msrc or "hidden = ! hidden" in msrc,
          "算法是成对开关（toggle）：hidden = !hidden")
    check("continue" in msrc, "遇到 % 翻转后跳过该字符本身")

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

# ★ MtrDwellAccess：ArrivalInfo.platformName 的**两个**出口都要过掩码。
#   判据按「域」切：只看 MtrDwellAccess 这一个文件里的调用数，不跟全局计数混。
dwell = find_file(("smooth", "lift", "client"), "MtrDwellAccess.java")
check(bool(dwell), "MtrDwellAccess.java 存在",
      os.path.relpath(dwell, ROOT) if dwell else "未找到")
if dwell:
    dbody = strip_comments(open(dwell, encoding="utf-8").read())
    n_mask = dbody.count("PlatformNameMask.mask(")
    check(n_mask == 2,
          "MtrDwellAccess 里 PlatformNameMask.mask( 正好 2 处（讲述人两个出口）",
          "实际 %d 处" % n_mask)
    check("PlatformNameMask.mask(mtr3PlatformNameForPlatform(platformId))" in dbody,
          "MTR3 出口已掩码（Platform.name 是 public 字段、不经 getter，必须在这里掩）")
    check("PlatformNameMask.mask(textOf(response, arrivalGetPlatformName))" in dbody,
          "MTR4 出口已掩码（与 MTR3 走同一出口；mask 幂等 ⇒ 第二处是保险不是重复）")

print("==> %s" % ("通过" if not FAILS else "失败"))
print()


# ----------------------------------------------------------------------
# 3) 反向对照：故意改错 target ⇒ 断言变红 ⇒ 还原并 md5 证还原
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

# 第二组反向对照：把 MtrDwellAccess 的 MTR3 出口掩码改坏 ⇒「正好 2 处」必须变红。
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
    check(d_n == 1, "mutation 后『正好 2 处』当场变红", "实际 %d 处" % d_n)
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
    # ★ 填输入框那一步必须 require = 1：不开窗口 ⇒ 一保存就把用户的 % 吃掉（丢数据）
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
#   ⇒ 也必须在这两个「开始编辑」入口放行一次（列表照旧隐藏，因为窗口 500ms 就过期）。
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
# 3b) 二次订正的反向对照：拿掉「原样窗口短路」⇒ 断言必须变红
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
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
