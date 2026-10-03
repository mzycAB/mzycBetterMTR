# -*- coding: utf-8 -*-
"""离线守则校验：**站台讲述人（midium 讲述人）在同一串门的一个开门周期里只许开一次口**。

## 这条守则为什么存在（2026-10-01 现场 LOG013）

用户的 1.20.4 + MTR 4.0.5 + `smooth_lift 1.29.1204` 会话里，「站台广播·讲述人」的同一份话
**一字不差地念了两遍**，而且他自己听出来「又是提前播报一次……之前犯过一次这个错误」。
LOG013（`[SmoothLift/PsdChime]` 行，原文照抄，数字可复核）：

```
14:56:33 一串门 @[-30,-20,43] 开门 → 排站台广播(讲述人)：0 tick 后开念
         （= 开门音 0ms 播完 + 等 0 秒；声源取这一串里离玩家最近的门 @[-16,-20,32]）
14:56:33 一串门 @[-63,-20,43] 开门 → 排站台广播(讲述人)：0 tick 后开念
         （= 开门音 0ms 播完 + 等 0 秒；声源取这一串里离玩家最近的门 @[-16,-20,32]）
14:56:33 站台广播·讲述人（这一串的门 @[-16,-20,32] 里离玩家最近的一扇起播） → 念出
         ……（终点站 城东机场北、站台 2-2）（第 32927497 tick）
14:56:33 站台广播·讲述人（这一串的门 @[-16,-20,32] 里离玩家最近的一扇起播） → 念出
         ……（终点站 城东机场北、站台 2-2）（第 32927499 tick）
14:55:43 / 14:55:53 另一对：声源同为 @[-38,-12,72]，句子同为「终点站 城南新区 / 站台 8-1」
```

## 机制（两个条件同时成立才会响两遍）

1. **等待基线塌成 0**：排计划的等待基线取「触发那一扇门的开门素材播完 + 等 N 秒」。
   触发的那扇门**不在玩家射程里**时 `resolvePlayable` 返回 `null`（按设计既不建实例也不刷日志）
   ⇒ 旧写法把 null 当成 0ms ⇒ `startTick == 现在` ⇒ **当场开念**（= 用户说的「提前播报一次」）。
2. **起播即摘表**：旧实现「起播那一 tick 就把 `midiumNarratorVoice` 的表项摘掉」，
   而排计划那道闸是 `midiumNarratorVoice.containsKey(runKey)` ⇒ 窗口被压成 **0**
   ⇒ 同一串里后开门的另外几扇门（每扇门各自一条开门沿）能**再排一条、再念一遍**。

★ 反向对照的杠杆是 **「念完把记录放回表里」这一步**，**不是**把守卫窗口调成 0：
  窗口 = 0 时那条「这一串的门已全关」照样会挡住，而 LOG013 那一刻门正开着（停站中）——
  只调 0 复现不出来。当初真正造成重复的动作是「起播即摘表」本身。

## 本脚本查什么

1. **结构**（剥注释后按语义断言，不锚排版）：
   - 排计划那道闸的键与写表 / 放回表用的键**必须是同一个**（都是 `runKey`）——
     三处任一处不一致 ⇔ 那道闸永远不会命中（本轮就是这样写出过一个 `holdKey` 分叉）；
   - 起播那一支必须**置 `fired` / `spokenTick`**、并把记录攒进 `respawn` 放回表里；
   - 已念过那一支必须走 `midiumHoldOver(...)` 收口；
   - 守卫窗口常量存在、为正、且硬上限 > 窗口。
2. **铃声的距离基准收窄到「同一个 MTR 站台」**（`PsdDoorTracker.nearestOnPlatform` 被
   `refreshVolume` 用到；而站台广播那条路传 `PLATFORM_ID_NONE` = 保持车站级）。
3. **纯逻辑复现器 + 反向对照**：把 LOG013 那四条实测事件灌进一个按 Java 逻辑逐 tick 演算的
   复现器，断言
   - 现行逻辑（念完放回表里）⇒ 每对只开 **1** 次口；
   - 把「放回表里」这一步去掉（≡ 旧实现）⇒ 那两对**必须复现成 2 次**（= 现场症状）；
   - 下一班车（窗口之外）仍要能念（不能永久哑掉）；
   - 门卡住不关时由硬上限兜底放行。
4. **脚本自带对照实验**：把真实源码里的「放回表里」那一行删掉，第 1 节的结构断言**必须变红**
   —— 否则本脚本自己就是假通过（本项目已经栽过两次「检查恒真」）。

## 与现场日志的关系

LOG013 的四条事件是**转录进来**的（原行在上面引着），所以本脚本**不依赖**日志文件是否存在。
若 `LOG013/latest.log` 恰好还在，额外核对一遍转录没抄错（抄错只报 `[INFO]`，不算失败：
日志是用户现场产物，不该成为回归网的必要条件）。

## 第二轮（10-01 续，LOG114）：**播报范围必须收窄到「触发那一扇门所属的站台」**

用户原话：「为什么站台广播还是会**提前播一次，开门播一次**，总共播放 2 次啊，站台广播不要
提前播放的那一次啊，LOG114」

LOG114 现场（`1.29.1204`；到站播报 / 进站报站的**素材**都是 `off`，`站台讲述人 = 自定义(user1)`）：

```
15:49:04 一串门 @[-55,-20,43] 开门（站台 id=-8915209352543605581 = 门线 z=43）→ 排站台广播
15:49:06 念出「你好」（终点站 城东机场北、站台 2-2）      ← 用户听到的「提前播的那一次」
15:49:20 门 @[-48,-12,2] 开门（站台 id=-8811008164878417542 = 门线 z=2，玩家脚下 0.9 格）→ 排
15:49:22 念出「你好」（终点站 北延三路西、站台 8-2）      ← 用户听到的「开门播的那一次」
```

根因：`planMidiumNarrator` 的声源取 `PsdDoorTracker.nearestInRun(runKey, 玩家)`，而 runKey 自
【09-30 续 9】起是**车站级** ⇒ 那个「最近的门」就是**玩家脚下**那扇 ⇒ `/jsr midium round`
（默认 水平 16 / 垂直 5）的两维**恒等于 0** ⇒ **别的站台**（别层 / 同层 11 格外的另一条门线）
的播报也在玩家耳边满音量念一遍。

改法（三处同一个粒度）：
1. 声源 = **触发这一扇门所属的那个站台**里离玩家最近的门（`nearestOnPlatform`；
   站台认不到才回落 `nearestInRun`）⇒ 范围判据重新变得有意义；
2. `midiumHoldOver` 的「这个站台的门已全关」按同一粒度比（`sameBroadcastScope`）——
   车站里别的站台有门卡着不关时，不该替这个站台占着守卫（LOG114 里 15:48:23 那次开门
   **一条排计划日志都没有**就是这一类）；
3. `isNearestMidiumNarrateRun` 的候选**必须按站台归并** —— 旧写法按 runKey 归并 ⇒ 整个车站
   塌成一个候选 ⇒ `nearest == runKey` **恒真** ⇒ 这道「多站台就近压制」从落地起就没生效过。
"""

import io
import math
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _find_src(name):
    """按**工程布局**找源文件 —— 本脚本在 1.20.4 / 1.20.1(Fabric) / Forge-1.20.1 三处共用，
    三种布局各不相同（Forge 只有 `src/main/java`，没有 `src/client`）：
      Fabric 分包 sourceset  → src/client/java/smooth/lift/client/<name>
      Forge 单一 sourceset   → src/main/java/smooth/lift/client/<name>
    ★ 就是为了让这份脚本文本在三工程**逐字节相同**，移植时直接 cp 不用改（改路径 = 分叉源头）。"""
    for rel in (("src", "client", "java", "smooth", "lift", "client", name),
                ("src", "main", "java", "smooth", "lift", "client", name)):
        p = os.path.join(PROJ, *rel)
        if os.path.isfile(p):
            return p
    return os.path.join(PROJ, "src", "client", "java", "smooth", "lift", "client", name)


PLAYER = _find_src("PsdChimePlayer.java")
TRACKER = _find_src("PsdDoorTracker.java")
LOG013 = os.path.join(os.path.dirname(PROJ), "LOG013", "latest.log")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label,
                         ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def strip_comments(src):
    """剥掉 // 与 /* */ 注释（保留字符串字面量）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def carve(body, header_re):
    """抠出 header_re 匹配的那个方法体（按大括号配平）。找不到返回 ''。"""
    m = re.search(header_re, body)
    if not m:
        return ""
    i = body.find("{", m.start())
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(body)):
        c = body[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return body[i:j + 1]
    return ""


# ---------------------------------------------------------------- 1) 结构

def structural_fails(src):
    """对给定的 PsdChimePlayer.java 正文做结构断言，返回失败标签列表。

    ★ 传入「被注入 mutation 的正文」时必须返回非空 —— 这是本脚本的自我对照。
    """
    fails = []
    body = strip_comments(src)

    plan = carve(body, r"private static void planMidiumNarrator\s*\(")
    if not plan:
        return ["抠不出 planMidiumNarrator"]

    # ① 排计划那道闸的键
    guard = re.search(r"midiumNarratorVoice\.containsKey\(\s*([A-Za-z_][\w.]*)\s*\)", plan)
    if not guard:
        fails.append("planMidiumNarrator 里找不到 containsKey 那道闸")
        guard_key = None
    else:
        guard_key = guard.group(1)

    # ② 写表的键
    put = re.search(
        r"midiumNarratorVoice\.put\(\s*([A-Za-z_][\w.]*)\s*,\s*new MidiumNarratorPlan\s*\(", plan)
    if not put:
        fails.append("planMidiumNarrator 里找不到写表那一句")
        put_key = None
    else:
        put_key = put.group(1)

    if guard_key and put_key and guard_key != put_key:
        fails.append("闸的键(%s)与写表的键(%s)不一致 ⇒ 那道闸永远不会命中"
                     % (guard_key, put_key))

    # ③ runKey 必须取自 door.runKey()
    if not re.search(r"long\s+runKey\s*=\s*door\.runKey\(\)", plan):
        fails.append("planMidiumNarrator 里没有 `long runKey = door.runKey();`")

    tick = carve(body, r"private static void tickMidiumNarrator\s*\(")
    if not tick:
        return fails + ["抠不出 tickMidiumNarrator"]

    # ④ 起播那一支：置 fired / spokenTick，并攒进 respawn
    if not re.search(r"plan\.fired\s*=\s*true\s*;", tick):
        fails.append("tickMidiumNarrator 起播后没有把 plan.fired 置 true")
    if not re.search(r"plan\.spokenTick\s*=\s*now\s*;", tick):
        fails.append("tickMidiumNarrator 起播后没有记 plan.spokenTick")
    if not re.search(r"respawn\.put\(\s*plan\.runKey\s*,\s*plan\s*\)", tick):
        fails.append("tickMidiumNarrator 没有把念过的记录攒进 respawn ⇒ 起播即摘表（旧 bug）")
    if not re.search(r"midiumNarratorVoice\.putAll\(\s*respawn\s*\)", tick):
        fails.append("tickMidiumNarrator 没有把 respawn 放回表里 ⇒ 守卫窗口恒为 0（旧 bug）")

    # ⑤ 已念过那一支走 midiumHoldOver
    if not re.search(r"if\s*\(\s*plan\.fired\s*\)", tick):
        fails.append("tickMidiumNarrator 里没有 `if (plan.fired)` 那一支")
    if not re.search(r"midiumHoldOver\(\s*doors\s*,\s*plan\s*,\s*now\s*\)", tick):
        fails.append("已念过那一支没有走 midiumHoldOver ⇒ 记录没有收口")

    hold = carve(body, r"private static boolean midiumHoldOver\s*\(")
    if not hold:
        fails.append("抠不出 midiumHoldOver")
    else:
        if "MIDIUM_NARRATE_HOLD_TICKS" not in hold:
            fails.append("midiumHoldOver 没有用 MIDIUM_NARRATE_HOLD_TICKS")
        if "MIDIUM_NARRATE_HOLD_MAX_TICKS" not in hold:
            fails.append("midiumHoldOver 没有硬上限 MIDIUM_NARRATE_HOLD_MAX_TICKS")

    return [f for f in fails if f]


def read_constants(src):
    body = strip_comments(src)
    out = {}
    for name in ("MIDIUM_NARRATE_HOLD_TICKS", "MIDIUM_NARRATE_HOLD_MAX_TICKS"):
        m = re.search(r"static final int\s+%s\s*=\s*(\d+)\s*;" % name, body)
        out[name] = int(m.group(1)) if m else None
    return out


# ------------------------------------------- 2) 铃声/播报两把尺子

def ruler_fails(player_src, tracker_src):
    fails = []
    pb = strip_comments(player_src)
    tb = strip_comments(tracker_src)

    if "public static DoorView nearestOnPlatform(long platformId, Vec3 player)" not in re.sub(
            r"\s+", " ", tb):
        fails.append("PsdDoorTracker 里没有 nearestOnPlatform（站台级那把尺子）")

    refresh = carve(pb, r"void refreshVolume\s*\(")
    if not refresh:
        fails.append("抠不出 refreshVolume")
    else:
        if "nearestOnPlatform" not in refresh:
            fails.append("refreshVolume 没有用 nearestOnPlatform ⇒ 铃声距离基准没收窄到站台")
        if "nearestInRun" not in refresh:
            fails.append("refreshVolume 丢了 nearestInRun 的回落支（认不到站台时无尺子）")
        if "isPlatformKnown" not in refresh:
            fails.append("refreshVolume 判「认不认得到站台」没用 isPlatformKnown（哨兵口径不统一）")

    fwd = carve(pb, r"private static PsdMusicInstance play\(Minecraft mc, Tone tone, "
                    r"PsdDoorTracker\.DoorView door,\s*\n?\s*float volume, int startMs,")
    if not fwd:
        # 退一步：直接找「站台广播那条路传 PLATFORM_ID_NONE」这个事实
        if not re.search(r"chainRunKey\s*,\s*chainDoubleDim\s*,\s*MtrDwellAccess\.PLATFORM_ID_NONE",
                         pb):
            fails.append("站台广播那条 play(...) 没有显式传 PLATFORM_ID_NONE（= 保持车站级）")
    return fails


# ------------------------------------------- 3) 纯逻辑复现器

def hold_over(rk, p, t, hold, max_hold, doors_open_until):
    """≡ Java 的 midiumHoldOver（返回 True = 可以摘了）。"""
    if t < p["spokenTick"] + hold:
        return False
    if t >= p["spokenTick"] + max_hold:
        return True
    return doors_open_until.get(rk, 0) <= t   # 这一串的门已全关


def simulate(events, hold, max_hold, doors_open_until, respawn):
    """按 onClientTick 的顺序逐 tick 演算，返回 (开念次数, 被闸挡下的次数)。

    events  = [(tick, runKey)] 排计划请求（= 每扇门开门沿那一刻）
    顺序    = 每 tick 先 tickMidiumNarrator（开念/收口），再走门循环（排计划）
    """
    by_tick = {}
    for tk, rk in events:
        by_tick.setdefault(tk, []).append(rk)
    t0 = min(by_tick)
    t1 = max(by_tick) + max_hold + 50

    plans = {}     # key = runKey
    voices = 0
    blocked = 0
    t = t0
    while t <= t1:
        respawn_buf = {}
        for rk in list(plans.keys()):
            p = plans[rk]
            if p["fired"]:
                if hold_over(rk, p, t, hold, max_hold, doors_open_until):
                    del plans[rk]
                continue
            if t < p["startTick"]:
                continue
            del plans[rk]
            voices += 1                     # 开口
            p["fired"] = True
            p["spokenTick"] = t
            if respawn:
                respawn_buf[rk] = p
        plans.update(respawn_buf)
        for rk in by_tick.get(t, []):
            if rk in plans:
                blocked += 1
                continue
            plans[rk] = {"startTick": t, "fired": False, "spokenTick": -1}
        t += 1
    return voices, blocked


# LOG013 实测：同一串里的两支开门沿（转录自上面引的原文行）
RUN_A, T_A1, T_A2 = 0x1A01, 32927497, 32927499      # 隔 2 tick（14:56:33 那一对）
RUN_B, T_B1, T_B2 = 0x1B01, 40000000, 40000160      # 隔 160 tick = 8 秒（14:55:43/53 那一对）
DOORS = 132                                          # 停站约 6.6s，门在这段时间里是开的


def replay_fails(hold, max_hold):
    fails = []
    print("-- 复现器：窗口=%d tick，硬上限=%d tick" % (hold, max_hold))

    # S1 现场第一对（隔 2 tick）
    ev = [(T_A1, RUN_A), (T_A2, RUN_A)]
    doors = {RUN_A: T_A1 + DOORS}
    got_new, blk = simulate(ev, hold, max_hold, doors, respawn=True)
    got_old, _ = simulate(ev, hold, max_hold, doors, respawn=False)
    check(got_new == 1,
          "S1 隔 2 tick：现行逻辑每对只开 1 次口",
          "实测 %d 次（挡下 %d）" % (got_new, blk))
    check(got_old == 2,
          "S1 隔 2 tick：去掉「放回表里」⇒ 必须复现成 2 次（LOG013 症状）",
          "实测 %d 次" % got_old)
    if not (got_new == 1 and got_old == 2):
        fails.append("S1 复现器鉴别力不足")

    # S2 现场第二对（隔 8 秒 = 160 tick）
    ev = [(T_B1, RUN_B), (T_B2, RUN_B)]
    doors = {RUN_B: T_B1 + DOORS}
    got_new, blk = simulate(ev, hold, max_hold, doors, respawn=True)
    got_old, _ = simulate(ev, hold, max_hold, doors, respawn=False)
    check(got_new == 1,
          "S2 隔 8 秒：现行逻辑每对只开 1 次口（这正是窗口要 ≥160 tick 的理由）",
          "实测 %d 次（挡下 %d）" % (got_new, blk))
    check(got_old == 2,
          "S2 隔 8 秒：去掉「放回表里」⇒ 必须复现成 2 次",
          "实测 %d 次" % got_old)
    if not (got_new == 1 and got_old == 2):
        fails.append("S2 复现器鉴别力不足")

    # S3 下一班车必须还能念（不能永久哑掉）
    ev = [(T_B1, RUN_B), (T_B1 + hold + 50, RUN_B)]
    doors = {RUN_B: T_B1 + DOORS}
    got, blk = simulate(ev, hold, max_hold, doors, respawn=True)
    check(got == 2,
          "S3 窗口之外（+%d tick）= 下一班车：仍要开念（不能永久哑掉）" % (hold + 50),
          "实测 %d 次（挡下 %d）" % (got, blk))
    if got != 2:
        fails.append("S3 窗口过长 ⇒ 下一班车被吞")

    # S4 门卡住不关 ⇒ 硬上限兜底
    ev = [(T_B1, RUN_B), (T_B1 + max_hold + 100, RUN_B)]
    doors = {RUN_B: 1 << 40}          # 门永远开着
    got, blk = simulate(ev, hold, max_hold, doors, respawn=True)
    check(got == 2,
          "S4 门卡住不关：硬上限 %d tick 兜底放行" % max_hold,
          "实测 %d 次（挡下 %d）" % (got, blk))
    if got != 2:
        fails.append("S4 硬上限没兜住 ⇒ 会永久哑掉")
    return fails


# ------------------------------------------- 4) 播报范围收窄到「触发门所属的站台」（LOG114）

def scope_fails(src):
    """声源 / 守卫收口 / 多站台压制的**粒度**都必须落在「站台」上，返回失败标签列表。"""
    fails = []
    body = strip_comments(src)

    plan = carve(body, r"private static void planMidiumNarrator\s*\(")
    if not plan:
        return ["抠不出 planMidiumNarrator"]
    flat = re.sub(r"\s+", "", plan)

    # ① 声源：必须走「这个站台里离玩家最近的门」，且带 isPlatformKnown 分支 + 回落支
    if "nearestOnPlatform(platformId" not in flat:
        fails.append("planMidiumNarrator 的声源没用 nearestOnPlatform(platformId, …) ⇒ "
                     "声源可能落在别的站台上（= LOG114 的「提前播一次」）")
    if not re.search(r"isPlatformKnown\(\s*platformId\s*\)", plan):
        fails.append("planMidiumNarrator 没有 isPlatformKnown(platformId) 那个分支")
    if "nearestInRun(runKey" not in flat:
        fails.append("planMidiumNarrator 丢了 nearestInRun 的回落支（站台认不到时无尺子）")
    if not re.search(r"long\s+platformId\s*=\s*door\.platformId\(\)", plan):
        fails.append("planMidiumNarrator 没有取 `long platformId = door.platformId()`")

    # ② 守卫收口：不能再拿 runKey（车站级）当「门关没关」的尺子
    hold = carve(body, r"private static boolean midiumHoldOver\s*\(")
    if not hold:
        fails.append("抠不出 midiumHoldOver")
    else:
        if "sameBroadcastScope(" not in hold:
            fails.append("midiumHoldOver 没有用 sameBroadcastScope 比「同一个站台」")
        if re.search(r"d\.runKey\(\)\s*==\s*plan\.runKey", hold):
            fails.append("midiumHoldOver 仍用 runKey（车站级）判「门关没关」⇒ "
                         "别的站台的门卡住不关会把守卫拖到硬上限")

    # ③ 多站台就近压制：候选粒度 = 站台（拿 plan 才拿得到 plan.platformId）
    #    ★ 形参表要单独从 body 上抓 —— carve() 只返回**方法体**（从 `{` 起），签名不在里面
    sig = re.search(r"private static boolean isNearestMidiumNarrateRun\s*\(([^)]*)\)", body)
    near = carve(body, r"private static boolean isNearestMidiumNarrateRun\s*\(")
    if not near:
        fails.append("抠不出 isNearestMidiumNarrateRun")
    else:
        if not sig or "MidiumNarratorPlan" not in re.sub(r"\s+", " ", sig.group(1)):
            fails.append("isNearestMidiumNarrateRun 没有收 MidiumNarratorPlan（拿不到 platformId）")
        if "sameBroadcastScope(" not in near:
            fails.append("isNearestMidiumNarrateRun 没有用 sameBroadcastScope ⇒ 压制粒度不对")
        if "nearestPerRun.get(d.runKey())" in re.sub(r"\s+", "", near):
            fails.append("isNearestMidiumNarrateRun 仍按 runKey 归并候选 ⇒ 整个车站塌成一个候选 "
                         "⇒ nearest == runKey 恒真（这道闸形同虚设）")

    # ④ 粒度判据只有这一处（别出现第二份各写各的）
    helper = carve(body, r"private static boolean sameBroadcastScope\s*\(")
    if not helper:
        fails.append("找不到 sameBroadcastScope（「同一个播报范围」的唯一判据）")
    else:
        h = re.sub(r"\s+", "", helper)
        if "isPlatformKnown(plan.platformId)" not in h:
            fails.append("sameBroadcastScope 没有 isPlatformKnown(plan.platformId) 分支")
        if "door.platformId()==plan.platformId" not in h:
            fails.append("sameBroadcastScope 没有按站台 id 比")
        if "door.runKey()==plan.runKey" not in h:
            fails.append("sameBroadcastScope 没有 runKey 回落支")

    # ⑤ 调用点必须把 plan 传进去（传 runKey = 又退回车站级）
    tick = carve(body, r"private static void tickMidiumNarrator\s*\(")
    if tick and "isNearestMidiumNarrateRun(mc.level,doors,plan," not in re.sub(r"\s+", "", tick):
        fails.append("tickMidiumNarrator 调 isNearestMidiumNarrateRun 时没有传 plan")
    return [f for f in fails if f]


# ------------------------------------------------------------------
# LOG114 现场数字（可逐字核）。三条门线 = 三个 MTR 站台：
PLAT_A = -8811008164878417542     # 门线 z=2（y=-12）= 玩家站的那条（报站名 8-2）
PLAT_B = -8915209352543605581     # 门线 z=43（y=-20）= 另一条（报站名 2-2）
PLAYER_XYZ = (-50.4, -12.0, 2.4)  # ≈ 日志里「距玩家 2.1 格」门 @[-48,-12,2] 的玩家位
A_DOORS = [(-48.5, -12.5, 2.5), (-38.5, -12.5, 2.5), (-53.5, -12.5, 2.5)]
B_DOORS = [(-55.5, -20.5, 43.5), (-63.5, -20.5, 43.5)]
RANGE_XZ, RANGE_Y = 16, 5         # /jsr midium round 默认值（= LOG114 现场值：水平 16 垂 5）
STATION_KEY = 0x5CE1              # runKey = 车站级（同站所有站台共用一个）
# 开门沿 = 开念 tick − 46 tick（开门音 2283ms）；开念 tick 取自日志「第 32930509 / 32930832 tick」
T_B_OPEN = 32930509 - 46
T_A_OPEN = 32930832 - 46
# ------------------------------------------------------------------


def _dist(p, origin=None):
    # ★ 默认值不能写成 PLAYER_XYZ：那是**定义时**绑定的，S6 里临时挪动玩家就失效了
    o = PLAYER_XYZ if origin is None else origin
    return math.sqrt(sum((a - b) ** 2 for a, b in zip(p, o)))


def _in_range(p):
    """≡ TrainAnnounceSwitch.isOutsideMidiumNarrateRange：水平(16) / 垂直(5) 各算各的。"""
    hz = math.hypot(p[0] - PLAYER_XYZ[0], p[2] - PLAYER_XYZ[2])
    return hz < RANGE_XZ and abs(p[1] - PLAYER_XYZ[1]) < RANGE_Y


def _nearest_of(doors, plats, in_range_only=True):
    """指定几个站台里离玩家最近的一扇门（in_range_only=False 时不过范围筛）。"""
    best = None
    for plat in plats:
        for p in doors.get(plat, []):
            if in_range_only and not _in_range(p):
                continue
            if best is None or _dist(p) < best[1]:
                best = (p, _dist(p))
    return None if best is None else best[0]


def _nearest_platform(doors):
    """快照里**站台**粒度、且玩家在范围内的最近一扇 ⇒ 它属于哪个站台（None = 无候选）。"""
    best = None
    for plat, lst in doors.items():
        for p in lst:
            if not _in_range(p):
                continue
            if best is None or _dist(p) < best[1]:
                best = (plat, _dist(p))
    return None if best is None else best[0]


def run_scope(events, doors, open_until, platform_scoped, hold, max_hold):
    """按 onClientTick 的两段顺序（先 tickMidiumNarrator，再门循环）逐 tick 演算。

    "platform_scoped=False" ≡ 改之前：声源取「**车站**里离玩家最近的门」（必然贴着玩家 ⇒
    恒在范围内），且「多站台就近压制」按 runKey 归并 ⇒ 恒真（等于没有这道闸）。

    返回 (开念次数, 排计划次数, 因出范围而没排的次数)。
    """
    by_tick = {}
    for tk, plat in events:
        by_tick.setdefault(tk, []).append(plat)
    plans = {}
    spoken = planned = out_of_range = 0
    for t in range(min(by_tick), max(by_tick) + max_hold + 40):
        # ---- 1) 到点开念 / 收口
        for key in list(plans.keys()):
            p = plans[key]
            if p["fired"]:
                still_open = open_until.get(p["plat"], 0) > t      # 这个站台的门还开着
                if t >= p["spoken"] + max_hold or (t >= p["spoken"] + hold and not still_open):
                    del plans[key]
                continue
            if t < p["startTick"]:
                continue
            del plans[key]
            if platform_scoped and _nearest_platform(doors) != p["plat"]:
                continue        # 多站台就近压制：不是离玩家最近的那个站台 ⇒ 这一班让位
            spoken += 1
            p["fired"] = True
            p["spoken"] = t
            plans[key] = p      # 念过的记录带着 fired 放回表里（守卫窗口）
        # ---- 2) 门循环：每扇门的开门沿排计划
        for plat in by_tick.get(t, []):
            if STATION_KEY in plans:
                continue                    # 闸：一次开门周期只排一条（键 = runKey = 车站级）
            if platform_scoped:
                src = _nearest_of(doors, [plat])            # 这个站台里离玩家最近的门
            else:
                src = _nearest_of(doors, list(doors.keys()), in_range_only=False)  # 车站里最近的门
            if src is None or not _in_range(src):
                out_of_range += 1
                continue                    # 出范围 ⇒ 不排（静默，与「射程外」同一条约定）
            plans[STATION_KEY] = {"startTick": t + 46, "fired": False, "spoken": -1, "plat": plat}
            planned += 1
    return spoken, planned, out_of_range


def scope_replay_fails(hold, max_hold):
    fails = []
    print("-- 复现器（平台粒度）：站台 A = 玩家脚下那条门线（z=2 / y=-12 / 距 2.1 格），"
          "站台 B = 另一条（z=43 / y=-20 / 距约 42 格）")
    doors_ab = {PLAT_A: A_DOORS, PLAT_B: B_DOORS}
    open_ab = {PLAT_A: T_A_OPEN + 127, PLAT_B: T_B_OPEN + 127}    # 门各开约 6.35 秒

    # S5 = LOG114 原样：B 先开门（15:49:04），A 后开门（15:49:20）
    ev = [(T_B_OPEN, PLAT_B), (T_A_OPEN, PLAT_A)]
    new_spoken, new_plan, oor = run_scope(ev, doors_ab, open_ab, True, hold, max_hold)
    old_spoken, _, _ = run_scope(ev, doors_ab, open_ab, False, hold, max_hold)
    check(new_spoken == 1,
          "S5 LOG114：站在 A 站台时只有 A 那班开口（B 那条根本排不出来）",
          "实测 %d 次（排了 %d 条，%d 条因出范围没排）" % (new_spoken, new_plan, oor))
    check(old_spoken == 2,
          "S5 LOG114：声源退回**车站级** ⇒ 必须复现成 2 次（= 用户报的「提前一次 + 开门一次」）",
          "实测 %d 次" % old_spoken)
    check(oor == 1, "S5：B 那条是被「范围判据」挡掉的（不是被守卫挡掉的）",
          "出范围 %d 条" % oor)
    if not (new_spoken == 1 and old_spoken == 2):
        fails.append("S5 复现器鉴别力不足")

    # S6 = 同一层、11 格外的另一条门线（水平 16 / 垂直 5 都罩得住 ⇒ 范围判据分不开它们）
    player_same_floor = (PLAT_A, [(-50.5, -20.5, 32.5)], PLAT_B, [(-50.5, -20.5, 43.5)])
    pa, da, pb, db = player_same_floor
    global PLAYER_XYZ
    keep = PLAYER_XYZ
    PLAYER_XYZ = (-50.4, -20.0, 32.4)                 # 站在 A 这条线的门边
    try:
        doors2 = {pa: da, pb: db}
        got, _, oor2 = run_scope([(T_B_OPEN, pb)], doors2, {pb: T_B_OPEN + 127}, True, hold, max_hold)
        check(got == 0 and oor2 == 0,
              "S6 同层邻站台（11 格，两维都在范围内）：只能靠「多站台就近压制」挡下 —— "
              "范围判据分不开它们",
              "实测开口 %d 次（出范围 %d 条）" % (got, oor2))
        old2, _, _ = run_scope([(T_B_OPEN, pb)], doors2, {pb: T_B_OPEN + 127}, False, hold, max_hold)
        check(old2 == 1, "S6：退回车站级声源 ⇒ 邻站台照样念（= 老症状）", "实测 %d 次" % old2)
        # S7 = 玩家自己那个站台必须照念（别把「压制」做成了「全哑」）
        got_a, _, _ = run_scope([(T_A_OPEN, pa)], doors2, {pa: T_A_OPEN + 127}, True, hold, max_hold)
        check(got_a == 1, "S7 玩家自己那个站台：照念（压制没做成全哑）", "实测 %d 次" % got_a)
        if not (got == 0 and old2 == 1 and got_a == 1):
            fails.append("S6/S7 复现器鉴别力不足")
    finally:
        PLAYER_XYZ = keep
    return fails


# ------------------------------------------- 现场日志（可选核对）

def verify_transcript():
    if not os.path.isfile(LOG013):
        print("[INFO] 没有 %s —— 跳过「转录对不对」这一项（本脚本自带数据，不依赖它）"
              % os.path.relpath(LOG013, os.path.dirname(PROJ)).replace("\\", "/"))
        return
    hits = []
    try:
        for line in io.open(LOG013, encoding="utf-8", errors="replace"):
            if "PsdChime" in line and "排站台广播(讲述人)" in line:
                hits.append(line)
    except OSError as e:  # noqa: BLE001
        print("[INFO] 读日志失败（%s）—— 跳过" % e)
        return
    dup = [h for h in hits if "@[-30,-20,43]" in h or "@[-63,-20,43]" in h]
    check(len(dup) >= 2,
          "LOG013 里确有「同一秒两条排计划」（转录来源）",
          "命中 %d 条" % len(dup))
    # 逐 tick 号核对那一对
    ticks = []
    for line in io.open(LOG013, encoding="utf-8", errors="replace"):
        if "PsdChime" in line and "站台广播·讲述人" in line and "念出" in line:
            m = re.search(r"第 (\d+) tick", line)
            if m:
                ticks.append(int(m.group(1)))
    check(T_A1 in ticks and T_A2 in ticks,
          "LOG013 里那一对的开念 tick 与转录一致",
          "转录 %d/%d；日志里共 %d 条念出" % (T_A1, T_A2, len(ticks)))


def main():
    print("===== 0) 读源码 =====")
    if not os.path.isfile(PLAYER):
        check(False, "找不到 PsdChimePlayer.java", PLAYER)
        sys.exit(1)
    src = io.open(PLAYER, encoding="utf-8", errors="replace").read()
    tracker = io.open(TRACKER, encoding="utf-8", errors="replace").read() \
        if os.path.isfile(TRACKER) else ""
    print("  %s  %d B" % (os.path.relpath(PLAYER, PROJ).replace("\\", "/"), len(src)))
    print()

    print("===== 1) 结构：同一班车只排一条 + 念完放回表里 =====")
    fails = structural_fails(src)
    check(not fails, "讲述人守卫的三处键一致、且念完放回表里",
          ("；".join(fails)) if fails else "全部命中")
    print()

    print("===== 2) 两把尺子：铃声收窄到站台，播报仍按车站 =====")
    rl = ruler_fails(src, tracker)
    check(not rl, "nearestOnPlatform 入网且播报保持车站级",
          ("；".join(rl)) if rl else "全部命中")
    print()

    print("===== 3) 常量 =====")
    c = read_constants(src)
    hold = c["MIDIUM_NARRATE_HOLD_TICKS"]
    max_hold = c["MIDIUM_NARRATE_HOLD_MAX_TICKS"]
    check(hold is not None and hold > 0,
          "MIDIUM_NARRATE_HOLD_TICKS 存在且为正", "= %s" % hold)
    check(max_hold is not None and hold is not None and max_hold > hold,
          "硬上限 > 窗口", "窗口=%s 上限=%s" % (hold, max_hold))
    if hold is None:
        hold = 200
    if max_hold is None or max_hold <= hold:
        max_hold = 1200
    print()

    print("===== 4) 纯逻辑复现（含反向对照）=====")
    rf = replay_fails(hold, max_hold)
    for f in rf:
        check(False, f)
    print()

    print("===== 5) 转录核对（可选）=====")
    verify_transcript()
    print()

    print("===== 6) 播报范围收窄到「触发门所属的站台」（LOG114）=====")
    sf = scope_fails(src)
    check(not sf, "声源 / 守卫收口 / 多站台压制三处同粒度（站台）",
          ("；".join(sf)) if sf else "全部命中")
    print()

    print("===== 7) 平台粒度纯逻辑复现（含反向对照）=====")
    srf = scope_replay_fails(hold, max_hold)
    for f in srf:
        check(False, f)
    print()

    print("===== 8) 脚本自带对照实验（结构断言必须会变红）=====")
    mutation = src
    # 删掉「把念过的记录放回表里」那一句 —— 正是旧实现与新版的分水岭
    mutated, n = re.subn(r"respawn\.put\(\s*plan\.runKey\s*,\s*plan\s*\)\s*;", ";", mutation)
    check(n == 1, "对照样本注入成功（删掉 respawn.put 那一句）", "替换 %d 处" % n)
    m_fails = structural_fails(mutated)
    check(bool(m_fails),
          "注入后结构断言必须判红（否则本脚本恒真 = 假通过）",
          ("命中：" + m_fails[0]) if m_fails else "★ 没判红 ⇒ 本脚本是个假网")

    # ② 把声源改回「车站里离玩家最近的门」—— 本轮修的那一处
    m2, n2 = re.subn(r"nearestOnPlatform\(\s*platformId\s*,\s*player\s*\)",
                     "nearestInRun(runKey, player)", src)
    check(n2 == 1, "对照样本②注入成功（声源改回车站级）", "替换 %d 处" % n2)
    f2 = scope_fails(m2)
    check(bool(f2), "注入②后「范围收窄到站台」必须判红",
          ("命中：" + f2[0]) if f2 else "★ 没判红 ⇒ 第 6 节是假网")

    # ③ 守卫收口退回 runKey（车站级）
    m3, n3 = re.subn(r"sameBroadcastScope\(\s*d\s*,\s*plan\s*\)",
                     "d.runKey() == plan.runKey", src)
    check(n3 == 1, "对照样本③注入成功（守卫收口退回 runKey）", "替换 %d 处" % n3)
    f3 = scope_fails(m3)
    check(bool(f3), "注入③后「守卫按站台收口」必须判红",
          ("命中：" + f3[0]) if f3 else "★ 没判红 ⇒ 第 6 节是假网")

    # ④ 粒度判据把 platformId 换成 runKey（两条支路塌成一条）
    m4, n4 = re.subn(r"door\.platformId\(\)\s*==\s*plan\.platformId", "true", src)
    check(n4 == 1, "对照样本④注入成功（sameBroadcastScope 丢掉站台支）", "替换 %d 处" % n4)
    f4 = scope_fails(m4)
    check(bool(f4), "注入④后「同一播报范围按站台判」必须判红",
          ("命中：" + f4[0]) if f4 else "★ 没判红 ⇒ 第 6 节是假网")

    print()
    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("   - " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()
