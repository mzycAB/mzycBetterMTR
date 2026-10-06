# -*- coding: utf-8 -*-
"""离线校验：屏蔽门（PIDS）站台名显示掩码功能是否落地正确。

## 查什么（不开游戏）

1. **纯逻辑**：把 Java 的 `PlatformNameMask.mask` 原样搬成 Python，跑断言表
   （`%` = 本名 / 别名分隔符，只有**第一个**起作用）。
2. **源码结构**：
   - `PlatformNameMask.java` 存在且算法是「第一个 `%` 分隔符」（无 `%` 同实例短路）；
   - `Mtr4PidsNameMixin` 的 `@Mixin` target == `org.mtr.core.operation.ArrivalResponse`，
     `Mtr3PidsNameMixin` 的 `@Mixin` target == `mtr.render.RenderPIDS`；
   - 两个 mixin 都进了 `smoothlift.psd.mixins.json` 的 `client` 数组；
   - `PsdDoorMixinPlugin` 有对应的放行分支（`Mtr4PidsNameMixin` / `Mtr3PidsNameMixin`）；
   - ★ `MtrDwellAccess` 里 `PlatformNameMask.mask(` **正好 4 处** —— 讲述人四个出口：站台名
     两个（MTR3 `mtr3PlatformNameForPlatform`、MTR4 `textOf(response, arrivalGetPlatformName)`）
     加终点站名两个（MTR3 `mtr3DestinationFor`、MTR4 `textOf(response, arrivalGetDestination)`）。
     **用户点名「讲述人念的站台名与屏蔽门一致」**，而 MTR3 的名字是 public 字段 `Platform.name`、
     **不经 getter**，光靠 mixin 拦不下，所以这 4 处是需求的一部分，不是可选装饰
     （【10-03 五改】：终点站名也过掩码，讲述人只念 `%` 前面的本名）。
3. **反向对照**：把 `Mtr4PidsNameMixin` 的 target 字符串故意改错 ⇒ 结构断言当场变红；
   把 `MtrDwellAccess` 里一处 `PlatformNameMask.mask(` 改坏 ⇒ 「正好 4 处」当场变红；
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
    # 【10-03 五改】唯一语义：第一个 % 是分隔符，它前面是本名、后面是别名。
    if name is None or name == "":
        return name
    mark = name.find(MARK)
    if mark < 0:
        return name
    return name[:mark]


CASES = [
    ("5%A%AB", "5"),
    ("AB%C%DE", "AB"),
    ("5A", "5A"),
    ("", ""),
    ("%A%", ""),
    ("a%b%c%d%e", "a"),
    ("5%AB", "5"),
    ("A%B", "A"),
    # ★ 幂等：MtrDwellAccess 的出口会在 getter 已被 mixin 掩过之后再掩一次，
    #   掩过的值（已不含 %）再掩必须还是它自己，否则那些第二处就变成 bug 而不是保险。
    ("A", "A"),
    ("5AB", "5AB"),
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
    check("int mark = name.indexOf(MARK)" in msrc and "mark < 0" in msrc
          and "return name" in msrc,
          "无 % 时返回同一个实例（零分配捷径）")
    check("name.substring(0, mark)" in msrc and "name.substring(mark + 1)" in msrc,
          "★ 只有第一个 % 是分隔符：substring(0, mark) 本名 + substring(mark + 1) 别名（【10-03 五改】）")
    check("hidden" not in strip_comments(msrc),
          "已无成对 toggle（正文不含 hidden）——不是隐藏切换，是分隔符语义")

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
    check(n_mask == 4,
          "MtrDwellAccess 里 PlatformNameMask.mask( 正好 4 处（讲述人四个出口）",
          "实际 %d 处" % n_mask)
    check("PlatformNameMask.mask(mtr3PlatformNameForPlatform(platformId))" in dbody,
          "MTR3 站台名出口已掩码（Platform.name 是 public 字段、不经 getter，必须在这里掩）")
    check("PlatformNameMask.mask(textOf(response, arrivalGetPlatformName))" in dbody,
          "MTR4 站台名出口已掩码（与 MTR3 走同一出口；mask 幂等 ⇒ 第二处是保险不是重复）")
    check("PlatformNameMask.mask(mtr3DestinationFor(entry))" in dbody,
          "MTR3 终点站名出口已掩码（【10-03 五改】讲述人只念本名）")
    check("PlatformNameMask.mask(textOf(response, arrivalGetDestination))" in dbody,
          "MTR4 终点站名出口已掩码（与屏蔽门箭头相反：这里要 % 前面的本名）")

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

print("==> %s" % ("通过" if not FAILS else "失败"))
print()


# ----------------------------------------------------------------------
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
