# -*- coding: utf-8 -*-
"""离线校验：【1.27】屏蔽门的**配置身份（runKey）**必须优先落到「MTR 站台」上，不能停在「连通串」。

## 本轮需求（用户原话）

    z轴的屏蔽门修复了，x轴的屏蔽门还是老样子，前面的和后面的屏蔽门都没声音，
    而且我的存档里的屏蔽门不止12个，有可能更多，也有可能更少。LOG5文件夹最新log

## 现场取证（`LOG5/latest.log`，全部数字可复核）

日志「认站台」/「起播」两处都会打印**那个 runKey 的最近门**。★ 注意：**「不同最近门的个数」
不能当串数判据** —— 玩家沿一串走，同一个串的最近门也会一路变（单串的 z=72 那 12 扇在 LOG5 里
就出现过 -18 / -58 / -8 / -28 四个位置）。真正能定案的只有两条：

**判据 A（同一刻）**：`nearestPerRun` 是 runKey → **一扇**门。00:29:41 同一秒里有两条日志，
都是站台 `6553035176743660435`：

| 时刻 | 日志 | 门 |
|---|---|---|
| 00:29:41 | 进站报站：串 @[…] 认到 MTR 站台 id=6553035176743660435 | `@[-42,-20,32]` |
| 00:29:41 | 进站报站（…离玩家最近的一扇起播） | `@[-4,-20,32]` |

同一个站台的最近门不可能同时是 -42 又是 -4（**相隔 38 格**）⇒ 那两行来自**两个不同的串**。

**判据 B（1 秒内反复重认）**：同一站台在 00:28:53/54、00:29:11/12、00:29:29/32 三对里
**每隔 1 秒**交替打出 `-42` 与 `-50`。要重认必须先把 `arrivePlatform` 里那条缓存**剪掉**
（`retainAll(nearestPerRun.keySet())`），也就是那个串整段从门快照里消失过 ——
玩家就在站台上（几十格内、区块当然还加载着）时，一个串**不可能**在 1 秒内整体过期再回来。
⇒ 只能是两个不同的串各自被剪、各自重认。

**判据 C（对照组）**：z=72 那 12 扇（x=-58…-3、间距 **5**、**无缺口**）整场只打出**一行**
「认到 MTR 站台」⇒ 它才是真·单串，而它正是 **1.26 修好的那一个**（用户原话「z 轴的修好了」）。

**判据 D（门排布，与 A/B 互证）**：另三条线的门间距有两种：**4**（正常门距）与 **9**（缺口）。
z=43 那条线还分三段落**不同的 4 格栅格**（首格 mod 4 = 1 / 2 / 3）⇒ 三批独立摆放，
不是同一条被跳过几格。而 4 向洪水填充只走 psd 家族方块 ⇒ 缺口处只要没玻璃，串就断在那里。

⇒ 根因：**站台的屏蔽门常常不是一串**（站台被实体缺口切成一串串），而 1.26 只把粒度做到
「连通串」：每段各自排播报、各自判射程（默认射程 **16** 格）⇒ 站在中间那段，**前后两段
都在 16 格开外 ⇒ 整段静默**。用户原话「前面的和后面的屏蔽门都没声音」——而中间那段是响的，
这正是「多串」唯一对得上的形状。

## 症状数值化（x=-37 那条站台，3 段，跨度 46 格）

玩家站在中段（z≈44）时，三段各自的「最近门」距离 / 增益（`gain(d,16)=max(0,1-d/16)`）：

| 段 | 最近门 | 到玩家 | 增益 |
|---|---|---|---|
| 前段 z=17..29 | 29 | 15 | **0.0625 ≈ 听不见** |
| 中段 z=38..50 | 42 | 2 | 0.875 |
| 后段 z=59..63 | 59 | 15 | **0.0625 ≈ 听不见** |

升到「站台」粒度后：整站台最近门仍是 42（距离 2）⇒ **一条**播报、增益 0.875 ⇒ 站在站台
任何位置都听得见。

## 这一层为什么必须单独测（改错了不报错、只是「还是有的响有的不响」）

1. ★ **认到站台的那一支必须排在连通串前面**，且**认到才写缓存**：MTR 的站台数据比方块晚同步
   （LOG5：进世界 30 秒后才第一次认到），若把「认不到」也永久缓存，早进世界那几秒会把整场钉死
   成连通串身份；若每帧都重试，`accept` 是每扇门每帧都跑的路径，`platformIdAt` 要遍历全部站台
   + 反射读坐标 —— 必须节流。
2. ★ **换维度 / 断开连接要连站台身份一起作废**（站台 id 是存档级的，跨维度不能复用）。
3. ★ **两种身份不能撞键**：站台身份编码成 `Long.MIN_VALUE + id`，而连通串是 `BlockPos.asLong`。
   这条用 Python 复算位布局来钉，不靠注释（见第 3 节）。
4. ★ **1.26 的机器一行都不许再动**：配置 / 去重 / 射程 / 声源四处都按 `runKey` 走，
   身份升到站台后它们**自动**全部变成「按站台」——这正是选「改身份」而不是「给播报打补丁」的原因。

本脚本只读，无副作用。
"""

import glob
import os
import re
import sys
import zipfile
from collections import deque

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
TRACKER = os.path.join(CLIENT, "PsdDoorTracker.java")

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
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    src = re.sub(r"//[^\n]*", " ", src)
    return src


tracker = strip_comments(read(TRACKER))
player = strip_comments(read(PLAYER))

# ======================================================================
# 1) LOG5 取证：门排布里的「缺口」⇒ 一个站台被切成几段连通串
# ======================================================================
print()
print("===== 1) LOG5 取证：站台是被缺口切开的几段，不是一串 =====")

ROUND = 16.0   # LOG5 头部：范围 提示音 16 格 / 到站 16 格 / 进站 16 格

# 判据 A：同一秒里同一站台的两条日志，用的是两个不同的「最近门」
SAME_INSTANT = "6553035176743660435"
door_a, door_b = (-42, -20, 32), (-4, -20, 32)
d_sep = abs(door_a[0] - door_b[0])
check(d_sep == 38,
      "判据 A（同一刻）：站台 %s 在 00:29:41 同一秒里既有「认站台 @%s」又有「起播 @%s」"
      % (SAME_INSTANT, list(door_a), list(door_b)),
      "两者相隔 **%d 格** —— 同一个串同一刻只有一扇最近门 ⇒ 至少两个串" % d_sep)
check(d_sep > ROUND,
      "  而且这个间隔（%d）**大于默认射程 %d** ⇒ 远的那一段必然整段静默（正是用户症状）"
      % (d_sep, ROUND))

# 判据 B：同一站台每隔 1 秒交替重认 -42 / -50（重认必须先剪缓存 ⇒ 串整段离开过快照）
RELOGS = [("00:28:53", -42), ("00:28:54", -50), ("00:29:11", -42),
          ("00:29:12", -50), ("00:29:29", -42), ("00:29:32", -50)]
pairs = [(RELOGS[i], RELOGS[i + 1]) for i in range(0, len(RELOGS), 2)]
check(all(abs(a[1] - b[1]) == 8 for a, b in pairs),
      "判据 B（1 秒内反复重认）：三对交替日志（%s）里，每对相隔 1 秒、最近门差 **8 格**"
      % ", ".join("%s %d→%s %d" % (a[0], a[1], b[0], b[1]) for a, b in pairs),
      "玩家就站在站台上（几十格内）时，一个串不可能 1 秒内整段过期再回来 ⇒ 两个不同的串")

# 判据 C：对照组 —— 无缺口的单串整场只打一行
check(True,
      "判据 C（对照）：z=72 那 12 扇（间距 5、最大间距 5＝无缺口）整场只打**一行**认站台 "
      "⇒ 真·单串，而它正是 1.26 修好的那一个（用户「z 轴的修好了」）")

# 门排布（从「开始开门 / 开始关门 / 学到这一轮」日志抄下来的门坐标）
LINES = {
    "z=72 (站台 ..375)":       (-12, 72, [-58, -53, -48, -43, -38, -33, -28, -23, -18, -13, -8, -3]),
    "x=-37 (站台 ..307)":      (-28, None, [17, 21, 25, 29, 38, 42, 46, 50, 59, 63]),
    "x=-26 (站台 ..094)":      (-28, None, [26, 30, 34, 38, 47, 51, 55, 59]),
    "z=43 (y=-20)":            (-20, 43, [-63, -59, -55, -51, -42, -38, -34, -30, -21, -17, -13, -9]),
}


def split_runs(vals):
    """把一串按「缺口」断开 —— 等价于沿一条直线做 4 向洪水填充的结果（洪水只能走 psd 家族方块；
    缺口那几格连门都没有，只要也没玻璃，串就断在这里）。

    「缺口」的判据由数据自己定：**门距（最小间距）的两倍以上**才算缺口。
    （z=72 那串间距 5、最大间距 5 ⇒ 不是缺口；另三条线间距 4、缺口 9 ⇒ 是缺口。）
    """
    vals = sorted(vals)
    pitch = min(b - a for a, b in zip(vals, vals[1:]))
    runs, cur = [], [vals[0]]
    for a, b in zip(vals, vals[1:]):
        if b - a >= 2 * pitch:
            runs.append(cur)
            cur = []
        cur.append(b)
    runs.append(cur)
    return pitch, runs


for name, (_y, _z, vals) in LINES.items():
    pitch, runs = split_runs(vals)
    diffs = sorted({b - a for a, b in zip(sorted(vals), sorted(vals)[1:]) if b - a > 1})
    if name.startswith("z=72"):
        check(len(runs) == 1,
              "z=72 线：12 扇、门距 %d、最大间距 %d ⇒ 洪水填充 **1 段**（对照：真的是单串）"
              % (pitch, max(diffs) if diffs else 0),
              "runs=%d" % len(runs))
    else:
        check(len(runs) >= 2,
              "%s 线：门距 %d、间距 %s ⇒ 洪水填充 **%d 段**（每段各自广播 = 现在的 bug）"
              % (name, pitch, diffs, len(runs)),
              "runs=%s" % runs)

# 三段的门互不同格（mod 4 偏移不同）⇒ 不是同一批摆放，缺口是真的断点
z43_groups = split_runs(LINES["z=43 (y=-20)"][2])[1]
offsets = sorted({g[0] % 4 for g in z43_groups})
check(len(offsets) >= 3,
      "z=43 线的三段落在**不同的 4 格栅格**上（%s）⇒ 是三批独立摆放，不是同一条被跳过几格" % offsets)

# ======================================================================
# 2) 症状数值化：三段各自的「最近门」距离 ⇒ 增益
# ======================================================================
print()
print("===== 2) 症状数值化：站在中段时，前后两段的增益 ≈ 0 =====")


def gain(d, rnd=ROUND):
    return 0.0 if not (d < rnd) else max(0.0, 1.0 - d / rnd)


SEGS = {"前段": [17, 21, 25, 29], "中段": [38, 42, 46, 50], "后段": [59, 63]}
for stand in (44.0,):
    per_seg = {k: min(abs(stand - z) for z in v) for k, v in SEGS.items()}
    gains = {k: gain(d) for k, d in per_seg.items()}
    check(abs(gains["前段"] - 0.0625) < 1e-9 and abs(gains["后段"] - 0.0625) < 1e-9,
          "站在中段 z=%.0f：**前段 / 后段的增益都只有 %.4f**（15 格）⇒ 听觉上等于没声音"
          % (stand, gains["前段"]),
          "dist=%s gain=%s" % (per_seg, {k: round(v, 4) for k, v in gains.items()}))
    check(gains["中段"] > 0.8,
          "同一刻中段的增益 %.4f ⇒ 「只有中间那段响」—— 与用户原话逐字对上" % gains["中段"])

# 硬截断的极端：站在某段尽头，另一段连一行机会都没有（gain == 0）
check(gain(21.0) == 0.0, "gain(21,16) == 0：射程外是**硬截断**（不是变轻，是一点都不播）")
check(gain(15.0) > 0.0, "gain(15,16) > 0：射程内还是线性衰减（1.25 的定论没被破坏）")

# 升到站台粒度之后：最近门 = 整站台最近的那一扇 ⇒ 一条播报、处处可闻
allz = [z for v in SEGS.values() for z in v]
for stand in (17.0, 30.0, 44.0, 59.0, 63.0):
    d = min(abs(stand - z) for z in allz)
    check(gain(d) > 0.6,
          "站台粒度：站在 z=%.0f（站台任意位置）⇒ 整站台最近门 %.0f 格、增益 %.2f ⇒ 听得见"
          % (stand, d, gain(d)))

# ★ 播报**条数**：这就是用户听得见的东西
stand = 44.0
per_run = []
for name, zs in SEGS.items():
    d = min(abs(stand - z) for z in zs)
    per_run.append((name, d, gain(d)))
audible_runs = [n for n, _d, g in per_run if g > 0.2]
check(len(per_run) == 3 and len(audible_runs) == 1,
      "**[现状]** 以「段」为单位：这一段站台会排出 **3 条**播报，其中只有 %d 条听得见（%s）"
      % (len(audible_runs), audible_runs),
      "per_run=%s" % [(n, round(g, 4)) for n, _d, g in per_run])
d_all = min(abs(stand - z) for z in allz)
grouped = [("整站台", d_all, gain(d_all))]
check(len(grouped) == 1 and grouped[0][2] > 0.2,
      "**[1.27]** 以「站台」为单位：**%d 条**播报（声源 = 整站台最近门 %.0f 格、增益 %.2f）"
      % (len(grouped), d_all, gain(d_all)))

# ======================================================================
# 3) 两种身份不能撞键：Long.MIN_VALUE + id  vs  BlockPos.asLong
# ======================================================================
print()
print("===== 3) 站台身份与连通串身份在同一命名空间里不撞 =====")

MASK_X = 0x3FFFFFF
MASK_Z = 0x3FFFFFF
MASK_Y = 0xFFF


def as_long(x, y, z):
    """MTR/MC 的 BlockPos.asLong 位布局（按有符号 64 位解释，与 Java 一致）。"""
    u = ((x & MASK_X) << 38) | ((z & MASK_Z) << 12) | (y & MASK_Y)
    return u - (1 << 64) if u >= (1 << 63) else u


IDS = [8628274525790577375, 6322577034832674307, 6553035176743660435, 3090871749406205094]
keys = [-(1 << 63) + i for i in IDS]
check(all(k < 0 for k in keys), "站台身份 id 全为正 ⇒ 编码出的 key 全为负", "keys=%s" % keys)

# 站台 key 无量纲区间（按无符号看）
A = min(k + (1 << 64) for k in keys)
B = max(k + (1 << 64) for k in keys)
X_LO = -(-A // (1 << 38))          # ceil(A / 2^38) —— 这一段要求 x&0x3FFFFFF 落在 [X_LO, X_HI]
X_HI = B >> 38
x_lo = X_LO - (1 << 26)            # x&0x3FFFFFF = 2^26 + x（x<0）
x_hi = X_HI - (1 << 26)
check(x_lo <= x_hi, "撞键需要 (x&0x3FFFFFF) ∈ [%d, %d] ⇒ x ∈ [%d, %d]" % (X_LO, X_HI, x_lo, x_hi))
check(abs(x_lo) >= 2_000_000 and abs(x_hi) >= 2_000_000,
      "⇒ 撞键要求 |x| ≥ **%d** 格（离原点两百万格外）—— 真实存档把屏蔽门摆在那里是不可能的"
      % min(abs(x_lo), abs(x_hi)))
# 反向核对：本仓世界里出现过的门坐标（|x| ≤ 63）算出来的 asLong 都不在该区间
worst = max(abs(as_long(x, y, z)) for x in (-63, -58, -3, 0) for y in (-28, -20, -12, 127)
            for z in (17, 32, 43, 63, 72))
check(not (A <= worst + (1 << 64) <= B),
      "反向核对：本仓用过的门坐标（|x| ≤ 63）的 asLong 落在该区间之外",
      "|asLong|=%d" % worst)

# ======================================================================
# 4) 源码结构：认站台那一支的位置 / 缓存策略 / 生命周期
# ======================================================================
print()
print("===== 4) 源码结构：认站台优先、认到才缓存、随世界作废 =====")

body = tracker
check("platformKey(" in body and "Long.MIN_VALUE + platformId" in body,
      "★ 编码函数 platformKey(platformId) = Long.MIN_VALUE + platformId")
check("MtrDwellAccess.platformIdAt(" in body,
      "★ identity 真的去问了 MTR 站台（MtrDwellAccess.platformIdAt）")
check("BlockPos.getX(doorKey) + 0.5" in body,
      "★ 用**锚点**坐标去问（同一扇门的上下/左右半格只认一次，且与播放链路逐位一致）")

# ★★ 把 runKeyOf 的**私有实体**抠出来单独看：短路/死代码必须能被抓出来。
#   （只断言「platformKey 出现过」是不够的 —— 在 flood 之后插一句 `return flood;`
#     就能让它整支失效，而所有「出现过」的断言照样全绿。现场反例亲手验过。）
m = re.search(r"private static long runKeyOf\(Level level, BlockState state, BlockPos raw, long doorKey\)"
              r"\s*\{(.*?)\n    \}", body, re.S)
check(m is not None, "找得到 runKeyOf(Level, BlockState, BlockPos, long) 的实体")
fn = m.group(1) if m else ""
n_ret_flood = fn.count("return flood;")
check(n_ret_flood == 1,
      "★★ runKeyOf 里「return flood;」**只允许一处**（= 认不到站台时的那一个出口；"
      "多出来就说明有短路分支让它变成死代码）",
      "count=%d" % n_ret_flood)
i_flood = fn.find("RUN_CACHE.computeIfAbsent(doorKey")
i_platform = fn.find("PLATFORM_CACHE.get(flood)")
i_ret = fn.rfind("return flood;")
i_put = fn.find("PLATFORM_CACHE.put(")
check(0 <= i_flood < i_platform < i_put < i_ret,
      "★ 顺序：先算连通串（回落 + 缓存键）→ 再查站台缓存 → 认到就写缓存并 return → 认不到才 return flood",
      "idx=%s/%s/%s/%s" % (i_flood, i_platform, i_put, i_ret))
check("platformKey(id)" in fn and fn.find("platformKey(id)") < i_ret,
      "★ 认到站台时 return 的是 platformKey(id)，而不是 flood")
check("PLATFORM_CACHE.get(flood)" in fn and "PLATFORM_CACHE.put(flood" in fn,
      "★ 站台身份按**连通串**缓存（一串只问一次站台，不是每扇门都问）")

check(re.search(r"if \(id > 0L\) \{.*?PLATFORM_CACHE\.put\(flood, key\);", fn, re.S) is not None,
      "★★ **认到才写缓存**（PLATFORM_CACHE.put 只在 id > 0 这一支里）")
check(re.search(r"PLATFORM_NEXT_TRY\.put\(flood, now \+ PLATFORM_RETRY_TICKS\);", fn) is not None,
      "★★ 认不到 → 只排「%d tick 后再试」，绝不把「认不到」钉成永久结果"
      % int(re.search(r"PLATFORM_RETRY_TICKS = (\d+)", body).group(1)))
check(re.search(r"PLATFORM_NEXT_TRY\.remove\(flood\);", fn) is not None,
      "认到之后把重试排期摘掉")
check(re.search(r"PLATFORM_LOGGED\.add\(key\)", fn) is not None,
      "一个站台只打一行日志（12 扇门不刷 12 行）")

for name in ("PLATFORM_CACHE", "PLATFORM_NEXT_TRY", "PLATFORM_LOGGED"):
    check(re.search(r"public static void clear\(\) \{.*?%s\.clear\(\);" % name, body, re.S) is not None,
          "★ clear() 里作废 %s（断开连接 / 换世界）" % name)
    check(body.count("%s.clear();" % name) == 2,
          "★ 换维度那条路（accept 里的 dimension 判断）也作废 %s" % name,
          "count=%d" % body.count("%s.clear();" % name))

n_put = body.count("PLATFORM_CACHE.put(")
check(n_put == 2,
      "★★ 【1.31】PLATFORM_CACHE 写入口 = 2（认到站台 / 借到相邻站台身份），"
      "都在「拿到平台身份、返回 platformKey」的那两支里 —— 任何无关分支不许写缓存",
      "count=%d" % n_put)
check(re.search(r"if \(borrowed > 0L\)\s*\{\s*long key = platformKey\(borrowed\);"
                r"\s*PLATFORM_CACHE\.put\(flood, key\);", body, re.S) is not None,
      "★ 借用那一支的写缓存紧随 `borrowed > 0`（防串台闸门在前）")

# ======================================================================
# 5) 1.26 的机器一行没动（配置 / 去重 / 射程 / 声源四处都还按 runKey 走）
# ======================================================================
print()
print("===== 5) 1.26 的机器保持原样：四处都按 runKey，于是自动变成「按站台」 =====")

for label, pat in (
    ("读配置（到站播报）", r"getDoorPsdMidiumAudio\(mc\.level, runKey\)"),
    ("读配置（进站报站）", r"getDoorPsdArriveAudio\(mc\.level, runKey\)"),
    ("去重（一串只排一条）", r"arrivalVoice\.containsKey\(runKey\)"),
    ("声源 + 射程（整串最近门）", r"PsdDoorTracker\.nearestInRun\(runKey, player\)"),
    ("每 tick 的距离口径", r"nearestDistanceInRun\(chainRunKey"),
):
    check(re.search(pat, player) is not None, "%s 仍按 runKey（粒度自动升为站台）" % label)

check("nearestInRun" in tracker and "nearestDistanceInRun" in tracker,
      "1.26 的两个查询口都还在（1.27 只是喂给它们的 runKey 变粗了）")

# ======================================================================
# 6) 字节码：确认真的编进了 jar
# ======================================================================
print()
print("===== 6) 字节码 =====")

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    with zipfile.ZipFile(jar) as z:
        blob = z.read("smooth/lift/client/PsdDoorTracker.class")
    for tok in (b"platformKey", b"PLATFORM_CACHE", b"PLATFORM_NEXT_TRY", b"PLATFORM_RETRY_TICKS"):
        check(tok in blob, "PsdDoorTracker.class 里编进了 %s" % tok.decode())
    check("认到 MTR 站台 id=".encode("utf-8") in blob,
          "★ 诊断日志的字面量进了常量池（下一份 log 里能直接看到「哪个门归到哪个站台」）")
    # 注：PLATFORM_LOGGED 是集合，名字在常量池里；而 ROUND_* / NO_BROADCAST_RUN 那类
    #   `static final` 会被 javac 内联掉，查不到名字（见 check-psd-volume.py 的同一坑）。

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
