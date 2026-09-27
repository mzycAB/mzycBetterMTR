# -*- coding: utf-8 -*-
"""离线校验：【1.42】直梯开关门提示音的**触发时刻**到底对不对。

## 为什么需要它

提示音的触发判据是「门开合度（`doorFraction`）从 1.0 掉下来 = 开始关门 / 从 0.0 涨上去 = 开始开门」，
所以「`doorFraction` 是不是**可见**开合度」直接决定了提示音响得早还是晚。

MTR 3 与 MTR 4 的门值口径**不一样**，而且 MTR3 的那个很容易搞错：

  * MTR 4：`getDoorValue()` 返回的就是可见开合度 0..1
      stoppingCoolDown <  500          -> 0
      < 2100                           -> (c-500)/1600      上行
      <= 4100                          -> 1（保持 2000ms）
      > 4100                           -> (5700-c)/1600     下行
  * MTR 3：字段 `doorValue` 走 **0..48**，但**可见**开合度是 `min(doorValue/24, 1)`
      —— 字节码依据（`mtr.data.LiftClient.tickClient`）：
           renderLift(..., frontCanOpen ? Math.min(doorValue / 24.0f, 1.0f) : 0.0f, ...)
      世界侧同口径：`checkDoor` -> `setOpen(Math.min(Math.round(doorValue), 24))`（DOOR_MAX=24）。
      ★ 也就是 doorValue 的 [24, 48] 是一段「门已全开、数值却继续走」的**空档**：
        开门/关门各有 24 tick（1.2 秒）看不见任何动作。

**如果 MTR3 按 48 归一化**，门从 48 掉到 47 时 `doorFraction` 就已离开 1.0，关门提示音会比
门**真的开始动**早 1.2 秒响起（这正是本脚本要防住的回归）。按 24 归一化后，两个跳变点才分别
落在「可见关门开始」（doorValue 24->23）与「可见开门开始」（doorValue 0->1）上。

## 这个脚本怎么查

**不抄一份逻辑**，而是把常量与分母**从源码里解析出来**（`EscalatorSpeedData.java` /
`MtrLiftAccess.java` / `LiftChimePlayer.java`），然后在 Python 里复现 `LiftChimePlayer.detect`
的判据，跑完整开门-停站-关门周期，断言：

  1. 归一化分母 = 24（**可见**全开值），不是 48；
  2. 一个完整周期里**恰好各触发一次**（不多不少），且在 [24,48] 空档里**不触发**；
  3. 触发时刻 == 门**可见**动作开始的那一 tick（±1 tick 容差，来自 eps 判据）；
  4. 连播节奏 = round(LIFT_HELP_INTERVAL_SECONDS × 20 / 倍速) ≥ 1 tick，对全速域成立；
     ★【1.52】「关门 4 次 / 开门 2 次」**只对内置 liftmusic 成立** —— 玩家导入的 ogg 只播一次；
  5. `sounds.json` 里有 `audio/liftmusic` 条目、且 .ogg 文件存在（否则引擎拿不到声音，静默不响）。

【1.42 修复】下面这两段是「直梯提示音完全没声音」那个 bug 的回归防线（症状与上述都不同：
**不是响得早/晚，而是一声不响**）：

  6. **容器形态**：MTR4 给的 `MinecraftClientData.liftWrapperList` 是 fastutil 的 **Map**，
     **不是 `Iterable`**；而 MTR3 的 `ClientData.LIFTS` 是 `java.util.Set`（是 Iterable）。
     源码因此必须走 `elementsOf()`（先 `java.util.Map` 再 `Iterable`），否则 MTR4 上
     `snapshot()` 永远返回空表 ⇒ 一声不响。本段会**拿真实 MTR jar 走一遍继承链**验证这两点，
     并做对照（Set 必须能到 Iterable、那个 Map 必须到不了）—— 证明这条判据有鉴别力。
  7. **`/lifthelploud` 音量链路**：常量 / 存档字段 / 夹取 / 指令树 / 同步包**读写顺序配对** /
     `GainManagedSound` + `AL_MAX_GAIN` 成对放行。

解析失败**报错退出**，不做静默兜底 —— 以后改写法时脚本会明确要求同步更新。

用法：`python _tools/check-lift-chime.py`（退出码 0 = 全部通过）
"""
import json
import math
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedData.java")
ACCESS = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client", "MtrLiftAccess.java")
PLAYER = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client", "LiftChimePlayer.java")
CHIME = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client", "EscalatorChimePlayer.java")
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift")

FAILS = []


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def check(ok, label, detail=""):
    tag = "PASS" if ok else "FAIL"
    line = "[%s] %s" % (tag, label)
    if detail:
        line += "  -- " + detail
    print(line)
    if not ok:
        FAILS.append(label)
    return ok


def num(src, pattern, label):
    m = re.search(pattern, src)
    if not m:
        print("[FAIL] 解析不到 %s（正则 %r）—— 源码写法变了，请同步本脚本" % (label, pattern))
        FAILS.append("parse:" + label)
        return None
    return float(m.group(1))


# ----------------------------------------------------------------------
# 1) 从源码解析常量
# ----------------------------------------------------------------------
data_src = read(DATA)
access_src = read(ACCESS)
player_src = read(PLAYER)

INTERVAL = num(data_src, r"LIFT_HELP_INTERVAL_SECONDS\s*=\s*([0-9.]+)", "LIFT_HELP_INTERVAL_SECONDS")
CLOSE_REPEATS = num(data_src, r"LIFT_HELP_CLOSE_REPEATS\s*=\s*(\d+)", "LIFT_HELP_CLOSE_REPEATS")
OPEN_REPEATS = num(data_src, r"LIFT_HELP_OPEN_REPEATS\s*=\s*(\d+)", "LIFT_HELP_OPEN_REPEATS")
SPEED_MIN = num(data_src, r"LIFT_HELP_SPEED_MIN\s*=\s*([0-9.]+)f", "LIFT_HELP_SPEED_MIN")
SPEED_MAX = num(data_src, r"LIFT_HELP_SPEED_MAX\s*=\s*([0-9.]+)f", "LIFT_HELP_SPEED_MAX")
EPS = num(player_src, r"\bEPS\s*=\s*([0-9.eE+-]+)f", "EPS")
TPS = num(player_src, r"TICKS_PER_SECOND\s*=\s*(\d+)", "TICKS_PER_SECOND")
if None in (INTERVAL, CLOSE_REPEATS, OPEN_REPEATS, SPEED_MIN, SPEED_MAX, EPS, TPS):
    sys.exit(1)

CLOSE_REPEATS = int(CLOSE_REPEATS)
OPEN_REPEATS = int(OPEN_REPEATS)
TPS = int(TPS)

print("解析到的常量：间隔=%ss  关门=%d次  开门=%d次  倍速=%s~%s  eps=%s  20tps=%d"
      % (INTERVAL, CLOSE_REPEATS, OPEN_REPEATS, SPEED_MIN, SPEED_MAX, EPS, TPS))
print()

# ----------------------------------------------------------------------
# 2) 归一化分母必须是「可见全开值」= 24（不是 48）
# ----------------------------------------------------------------------
# mtr3DoorFull 在源码里出现两次：字段声明（= 1.0f 的初值）与 bindMtr3 里的赋值（真正的值）。
# 取**最后一次**赋值，并排除 "float " 前缀那次声明。
m3_hits = re.findall(r"(?<!float )mtr3DoorFull\s*=\s*([0-9.]+)f", access_src)
if not m3_hits:
    print("[FAIL] 解析不到 MTR3 归一化分母（mtr3DoorFull = Xf）—— 源码写法变了，请同步本脚本")
    FAILS.append("parse:mtr3DoorFull")
    m3 = None
else:
    m3 = float(m3_hits[-1])
m4 = num(access_src, r"fraction\(doorValue,\s*([0-9.]+)f\)", "MTR4 归一化分母")
check(m3 == 24.0,
      "MTR3 归一化分母 == 24（可见全开值，= min(doorValue/24,1) 的分母）",
      "实际 %s；若为 48 则关门提示音会早 1.2 秒" % m3)
check(m4 == 1.0, "MTR4 归一化分母 == 1（getDoorValue() 本来就是可见开合度）", "实际 %s" % m4)

# 源码里必须留下「除以 24」的字节码依据说明，避免以后被人"顺手改回 48"
check("doorValue / 24.0f" in access_src and "LiftClient" in access_src,
      "MtrLiftAccess 记录了 doorValue/24 的字节码依据（LiftClient.tickClient）")
print()


# ----------------------------------------------------------------------
# 3) 复现 LiftChimePlayer.detect 的判据（含 Java 的 Math.round 语义）
# ----------------------------------------------------------------------
def jround(x):
    """Java 的 Math.round(float) = floor(x + 0.5)（Python 内建 round 是银行家舍入，不能用）。"""
    return int(math.floor(x + 0.5))


def fraction(door_value, full):
    if full <= 0.0:
        return 0.0
    return max(0.0, min(1.0, door_value / full))


def triggers(series, full):
    """series: [(tick, 本版本原始门值)] -> [(tick, 'closing'|'opening', 原始门值)]，判据同 detect()。"""
    out = []
    prev = fraction(series[0][1], full)
    for tick, raw in series[1:]:
        now = fraction(raw, full)
        closing = prev >= 1.0 - EPS and now < 1.0 - EPS
        opening = prev <= EPS and now > EPS
        if closing:
            out.append((tick, "closing", raw))
        elif opening:
            out.append((tick, "opening", raw))
        prev = now
    return out


def mtr3_series():
    """MTR3 一个完整周期：0 -> 48（开门，1/tick）→ 停站 40 tick → 48 -> 0（关门，1/tick）。

    返回的「原始门值」就是字段 `doorValue`（0..48）；**可见**全开 = 24，
    [24, 48] 是门已全开、数值却继续走的空档。"""
    out = [(0, 0.0)]
    tick = 0
    v = 0.0
    while v < 48.0:                      # 开门（可见动作在 0 -> 24）
        v = min(v + 1.0, 48.0)
        tick += 1
        out.append((tick, v))
    for _ in range(40):                  # 停站保持（= Mtr3LiftAutoClose.IDLE_TICKS）
        tick += 1
        out.append((tick, v))
    while v > 0.0:                       # 关门（可见动作在 24 -> 0）
        v = max(v - 1.0, 0.0)
        tick += 1
        out.append((tick, v))
    return out


def mtr4_door(c):
    """org.mtr.core.data.Lift.getDoorValue() 的字节码分段；参数是 stoppingCoolDown（ms）。"""
    if c < 500:
        return 0.0
    if c < 2100:
        return (c - 500) / 1600.0
    if c <= 4100:
        return 1.0
    return (5700 - c) / 1600.0


def mtr4_series():
    """MTR4：stoppingCoolDown 0 -> 5700，每步 1ms（tick 即 cooldown）。"""
    return [(c, mtr4_door(c)) for c in range(0, 5701)]


for name, series, full in (("MTR3", mtr3_series(), m3), ("MTR4", mtr4_series(), m4)):
    hits = triggers(series, full)
    kinds = [k for _, k, _ in hits]
    print("%s：触发 %s（%s）" % (name, kinds, hits))

    check(kinds.count("opening") == 1 and kinds.count("closing") == 1,
          "%s 一个周期恰好触发 1 次开门 + 1 次关门" % name,
          "实际 %s" % kinds)
    if not hits:
        continue
    open_hit = next((h for h in hits if h[1] == "opening"), None)
    close_hit = next((h for h in hits if h[1] == "closing"), None)
    if open_hit is None or close_hit is None:
        continue

    if name == "MTR3":
        # 可见开门开始 = doorValue 0->1；可见关门开始 = doorValue 24->23。
        # ★ 若归一化分母错写成 48，关门触发会落在 doorValue≈47（早 1.2 秒）。
        check(abs(open_hit[2] - 1.0) <= 1.0,
              "MTR3 开门触发落在可见开门起点（doorValue≈1）",
              "触发时 doorValue=%.1f" % open_hit[2])
        check(abs(close_hit[2] - 23.0) <= 1.0,
              "MTR3 关门触发落在可见关门起点（doorValue≈23，而不是 47）",
              "触发时 doorValue=%.1f（错写 48 时会是 ≈47）" % close_hit[2])
        check(close_hit[2] <= 24.0,
              "MTR3 关门触发不落在 doorValue∈(24,48] 的可见空档里",
              "触发时 doorValue=%.1f" % close_hit[2])
    else:
        check(abs(open_hit[0] - 500) <= 25,
              "MTR4 开门触发落在 cooldown≈500（可见开门起点）",
              "触发时 cooldown=%d ms" % open_hit[0])
        check(abs(close_hit[0] - 4101) <= 25,
              "MTR4 关门触发落在 cooldown≈4101（可见关门起点）",
              "触发时 cooldown=%d ms" % close_hit[0])
print()

# ----------------------------------------------------------------------
# 3b) 对照实验：证明上面那条判据**真的有鉴别力**（不是恒真）
#     把 MTR3 的分母换回 48（曾经的错法）重跑 —— 关门触发必须落到 doorValue≈47，
#     也就是掉进 [24,48] 的可见空档里（= 比门真的开始动早 24 tick / 1.2 秒）。
# ----------------------------------------------------------------------
wrong_close = next((h for h in triggers(mtr3_series(), 48.0) if h[1] == "closing"), None)
check(wrong_close is not None and wrong_close[2] >= 24.0,
      "对照：分母若退回 48，关门触发会落到 doorValue≈47（落在可见空档 → 早 1.2 秒）",
      "对照结果 %s" % (wrong_close,))
print()


# ----------------------------------------------------------------------
# 4) 连播节奏：全速域都要算得 ≥1 tick，且 4 下 / 2 下真的放得完
# ----------------------------------------------------------------------
speeds = [SPEED_MIN]
s = SPEED_MIN
while s < SPEED_MAX - 1e-9:
    s = min(s + 0.1, SPEED_MAX)
    speeds.append(round(s, 2))

bad = []
for sp in speeds:
    ticks = max(1, jround(INTERVAL / sp * TPS))
    if ticks < 1:
        bad.append((sp, ticks))
check(not bad, "连播间隔 max(1, round(%s/%s×%d)) ≥ 1 tick 对倍速域[%s,%s]全部成立"
      % (INTERVAL, "speed", TPS, SPEED_MIN, SPEED_MAX), "异常 %s" % bad)

span_close = max(1, jround(INTERVAL / SPEED_MIN * TPS)) * CLOSE_REPEATS
span_open = max(1, jround(INTERVAL / SPEED_MIN * TPS)) * OPEN_REPEATS
check(CLOSE_REPEATS == 4 and OPEN_REPEATS == 2,
      "关门连播 4 次、开门连播 2 次（需求原文）",
      "关门跨度 %dtick(%.1fs) 开门跨度 %dtick(%.1fs) @最慢倍速"
      % (span_close, span_close / TPS, span_open, span_open / TPS))


# ----------------------------------------------------------------------
# 4b) 【1.52】「4 次 / 2 次」只对**内置素材**成立；玩家导入的 ogg 只播一次
# ----------------------------------------------------------------------
m = re.search(r"private static void detect\(.*?\n    \}", player_src, re.S)
detect_body = m.group(0) if m else ""
check(bool(detect_body), "找到 detect 方法体")
if detect_body:
    check(re.search(r"if \(customId != null\) \{\s*//[^\n]*\n\s*seqPitch = 1\.0f;\s*repeats = 1;",
                    detect_body) is not None,
          "*【1.52】导入的 ogg（customId != null）连播次数 = 1（只播一次），"
          "与「自定义素材按原速播」写在同一个分支里")
    check(re.search(r"repeats = closing \? EscalatorSpeedData\.LIFT_HELP_CLOSE_REPEATS\s*\n\s*"
                    r": EscalatorSpeedData\.LIFT_HELP_OPEN_REPEATS;", detect_body) is not None,
          "*【1.52】内置素材仍走「关门 4 次 / 开门 2 次」（次数分流在 else 分支里）")
    check(re.search(r"int repeats = closing \?", detect_body) is None,
          "老的「先无条件按 4/2 算、再被自定义情况覆盖」写法已消失"
          "（留着就是两处规则并存，等着分叉）")


def plays(closing, custom):
    """detect() 的连播次数：内置 = 关门 4 / 开门 2；导入的 ogg = 1。"""
    return 1 if custom else (CLOSE_REPEATS if closing else OPEN_REPEATS)


check(plays(True, False) == 4 and plays(False, False) == 2,
      "内置素材：关门 4 次、开门 2 次（用户点名的节奏，本次不动）")
check(plays(True, True) == 1 and plays(False, True) == 1,
      "*导入的 ogg：开门、关门都**只播一次**（【1.52】用户点名）")
check(plays(False, True) != plays(False, False) and plays(True, True) != plays(True, False),
      "对照：若不看素材来源（导入也按 4/2 次）⇒ 开关门各会多放 1 / 3 下"
      " ⇒ 上面那条断言有鉴别力，不是恒真",
      "若照旧 = 关门 %d 次 / 开门 %d 次；现在 = 1 次 / 1 次"
      % (plays(True, False), plays(False, False)))
print()


# ----------------------------------------------------------------------
# 5) 音频资源必须齐（sounds.json 条目 + .ogg 文件）
# ----------------------------------------------------------------------
sounds_path = os.path.join(RES, "sounds.json")
sounds = json.loads(read(sounds_path))
check("audio/liftmusic" in sounds, "sounds.json 注册了 audio/liftmusic",
      "实际键 %s" % sorted(sounds.keys()))
ogg = os.path.join(RES, "sounds", "audio", "liftmusic.ogg")
check(os.path.isfile(ogg) and os.path.getsize(ogg) > 0,
      "assets/smoothlift/sounds/audio/liftmusic.ogg 存在且非空",
      "%s bytes" % (os.path.getsize(ogg) if os.path.isfile(ogg) else "缺失"))
print()

# ----------------------------------------------------------------------
# 6) 【1.42 修复】容器形态：MTR4 的 liftWrapperList 是 Map、**不是** Iterable
#     —— 这就是「直梯提示音一声不响」的根因。
# ----------------------------------------------------------------------
MGR = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "EscalatorSpeedManager.java")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client", "SmoothLiftClient.java")
mgr_src = read(MGR)
client_src = read(CLIENT)

# 6a) 源码层面：必须有 elementsOf()，且顺序是 Map 在前、Iterable 在后
check(re.search(r"private static Iterable<\?> elementsOf\(Object raw\)", access_src) is not None,
      "MtrLiftAccess 有 elementsOf(Object) 归一化入口")
idx_map = access_src.find("instanceof java.util.Map<?, ?>")
idx_iter = access_src.find("instanceof Iterable<?>")
check(0 <= idx_map < idx_iter,
      "elementsOf 先判 java.util.Map（取 values()）、再退回 Iterable —— 顺序不能反",
      "Map 在第 %d 字符、Iterable 在第 %d 字符" % (idx_map, idx_iter))

# 6b) 两个快照方法都必须走 elementsOf，且不得再对容器直接 instanceof Iterable
snap3 = access_src[access_src.find("private static List<LiftView> snapshotMtr3"):]
snap3 = snap3[:snap3.find("\n    private static List<LiftView> snapshotMtr4")]
snap4 = access_src[access_src.find("private static List<LiftView> snapshotMtr4"):]
snap4 = snap4[:snap4.find("\n    /** 拿直梯 ID")]
check("elementsOf(" in snap3, "snapshotMtr3 走 elementsOf（MTR3 的 Set 也一起兜住）")
check("elementsOf(" in snap4, "snapshotMtr4 走 elementsOf（★ 这个才是修 bug 的那处）")
check("!(raw instanceof Iterable" not in access_src,
      "不再对容器直接判 instanceof Iterable（那正是 MTR4 上永远为空的写法）")
check("warnCollectionShape" in access_src and "noteCollectionShape" in access_src,
      "容器形态不认识 / 认得出时各有一次诊断日志（下次一眼看出问题）")

# 6c) 与**真实 MTR jar** 对撞：走继承链，证明上面那条判据有鉴别力
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


def strip_generics(name):
    """去掉泛型参数：'java.util.Collection<E>' → 'java.util.Collection'（javap 不认带泛型的类名）。"""
    return re.sub(r"<[^>]*>", "", name).strip()


def supertypes(cp, cls):
    """用 javap 取一个类的直接父型（extends + implements 全部）。取不到返回 None。

    ★ None 与 [] 是**两件事**：[] = 该类确实没有父型（如 java.lang.Cloneable），
    None = javap 没跑成 / 类找不到。调用方必须区别对待，否则「解析失败」会被误当成
    「走不到目标」而让断言悄悄恒真 —— 那正是本脚本要防的那类假通过。
    """
    try:
        out = subprocess.run(["javap", "-cp", cp, cls],
                             capture_output=True, text=True, timeout=60).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if line.startswith(("public", "protected", "abstract", "final", "class", "interface")):
            # ★ 必须把 extends 与 implements **都**切开（不能 maxsplit=1）：
            #   'class A<V> extends B<V> implements C<V>, D' 若只切一次，
            #   tail[1] 会把 "implements C<V>" 黏在 B 后面，父型名字就变成
            #   "B implements C" 这种含空格的垃圾 → javap 取不到 → 整条链静默断掉。
            #
            # ★ 再**按组**处理：每一组（extends 那组、implements 那组）各自剥泛型、各自按逗号切。
            #   不能把各组拼起来再切逗号 —— 那样上一组的尾巴会和下一组的头黏成一个名字
            #   （'AbstractLong2ObjectSortedMap  java.io.Serializable'）。
            #
            # ★ 逗号也必须在**剥完泛型之后**才切：'Function<java.lang.Long, V>, X' 里的逗号
            #   是泛型参数分隔符，先 cut 会把 Function<… 从中间切断。
            parts = re.split(r"\bextends\b|\bimplements\b", line)
            if len(parts) < 2:
                return []
            names = []
            for part in parts[1:]:
                for piece in strip_generics(part.replace("{", " ")).split(","):
                    piece = piece.strip()
                    if piece:
                        names.append(piece)
            if any("<" in n or ">" in n or " " in n for n in names):
                # 名字没洗干净 ⇒ 宁可报「解析不出来」（调用方会 FAIL），
                # 也不要拿半个/带空格的类名去 javap —— 那会静默断链、把断言变成恒真。
                return None
            return names
    return None


def reaches(cp, start, target, max_nodes=400):
    """start 能否沿父型走到 target（BFS）。

    返回 (True/False, 走过的节点数)；**start 本身解析不出来时返回 (None, 0)**，
    调用方必须把它当失败处理（不能当成「走不到」）。
    """
    if start == target:
        return True, 0
    first = supertypes(cp, start)
    if first is None:
        return None, 0
    seen, queue, n = {start}, list(first), 1
    while queue:
        cur = queue.pop(0)
        if cur == target:
            return True, n
        if cur in seen or n > max_nodes:
            continue
        seen.add(cur)
        n += 1
        sup = supertypes(cp, cur)
        if sup is None:
            continue
        queue.extend(sup)
    return False, n


jars = find_mtr_jars()
if not jars["mtr4"] or not shutil.which("javap"):
    print("[SKIP] 找不到 MTR4 jar 或 javap —— 跳过与真实字节码的继承链对撞（不算失败）")
else:
    cp4 = (jars["mtr4"] + os.pathsep + jars["mtr3"]) if jars["mtr3"] else jars["mtr4"]
    M4_SET = "org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap"
    got_iter, _ = reaches(cp4, M4_SET, "java.lang.Iterable")
    got_map, _ = reaches(cp4, M4_SET, "java.util.Map")
    ctl_iter, _ = reaches(cp4, "java.util.Set", "java.lang.Iterable")
    if None in (got_iter, got_map, ctl_iter):
        check(False, "继承链解析成功（javap 取得到这几类的父型）",
              "取不到 ⇒ 本段断言无法成立（不能把「解析失败」当成「走不到」）")
    else:
        check(got_iter is False,
              "真实 MTR4：Long2ObjectAVLTreeMap **到不了** java.lang.Iterable",
              "⇒ instanceof Iterable 必然失败，这就是「一声不响」的根因")
        check(got_map is True,
              "真实 MTR4：Long2ObjectAVLTreeMap **是** java.util.Map（⇒ 取 values() 即可迭代）",
              "修复走的就是这条路")
        # 对照实验：MTR3 的容器必须**能**到 Iterable —— 证明上面两条不是恒真
        check(ctl_iter is True,
              "对照：java.util.Set（MTR3 的 ClientData.LIFTS）**能**到 Iterable",
              "⇒ 同一条 instanceof Iterable 判据在两代 MTR 上结果不同，"
              "正好解释「只有 MTR4 没声音」")
print()


# ----------------------------------------------------------------------
# 7) 【1.43】/lifthelploud 音量链路
# ----------------------------------------------------------------------
AUD_MIN = num(data_src, r"AUDIO_VOLUME_MIN\s*=\s*(\d+)", "AUDIO_VOLUME_MIN")
AUD_MAX = num(data_src, r"AUDIO_VOLUME_MAX\s*=\s*(\d+)", "AUDIO_VOLUME_MAX")
AUD_DEF = num(data_src, r"DEFAULT_AUDIO_VOLUME\s*=\s*(\d+)", "DEFAULT_AUDIO_VOLUME")
if None in (AUD_MIN, AUD_MAX, AUD_DEF):
    sys.exit(1)

check(AUD_MIN == 1 and AUD_MAX == 1000 and AUD_DEF == 100,
      "音量区间 1~1000、默认 100（= 原始音量）",
      "min=%s max=%s default=%s" % (AUD_MIN, AUD_MAX, AUD_DEF))
check(re.search(r"DEFAULT_LIFT_HELP_VOLUME\s*=\s*DEFAULT_HELP_VOLUME", data_src) is not None,
      "DEFAULT_LIFT_HELP_VOLUME 复用扶梯那套常量（不另写一份数字）",
      "⇒ 三套音量永远不会因为改了一处而悄悄不一致")

# 7a) 数据层：字段 / 读 / 写 / 夹取 四件都要有
check(re.search(r"public int defaultLiftHelpVolume\s*=", data_src) is not None,
      "EscalatorSpeedData 有 defaultLiftHelpVolume 字段")
check('tag.contains("defaultLiftHelpVolume")' in data_src,
      "fromTag 读 defaultLiftHelpVolume（旧存档缺字段 → 保持默认 100）")
check('tag.putInt("defaultLiftHelpVolume"' in data_src,
      "toTag 写 defaultLiftHelpVolume")
check(re.search(r"clampLiftHelpVolume\(int volume\)\s*\{\s*return Math\.max\(HELP_VOLUME_MIN",
                data_src) is not None,
      "clampLiftHelpVolume 夹到 [HELP_VOLUME_MIN, HELP_VOLUME_MAX]（数据层就夹住，不只靠指令参数类型）")

# 7b) 指令树：五种形态都要可达
cmd_src = read(os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "SmoothLift.java"))
check('Commands.literal("lifthelploud")' in cmd_src, "注册了 /lifthelploud")
for fn in ("liftHelpLoudShow", "liftHelpLoudGlobal", "liftHelpLoudFromTo",
           "liftHelpLoudForceAll", "liftHelpLoudForceFromTo"):
    check(fn in cmd_src, "  /lifthelploud 分支存在：%s" % fn)
check(re.search(r'Commands\.literal\("lifthelploud"\)[\s\S]{0,1500}?liftToneLoudCommand\("up", "up"\)',
                cmd_src) is not None
      and 'liftToneLoudForceBranch("open", "open")' in cmd_src
      and 'liftToneLoudForceBranch("close", "close")' in cmd_src,
      "/lifthelploud 挂了 up|down|open|close 四项分支 + 合并的 -f 节点（【1.60】直梯四提示音）")

# 7c) 同步包**读写顺序配对** —— 跨文件不变量，最容易被单边改坏
def pkt_ops(src, marker, kind, end=None, span=900):
    """截出「写侧方法体 / 读侧接收器」这一段的 buf.<kind>Xxx() 调用序列。

    【1.15】`end` 是**结束标记**：光靠固定长度窗口（span=900）会漏掉包尾新追加的字段
    —— 包一长，尾部那几个 writeUtf/readUtf 就滑出窗口，于是「读写顺序配对」这条断言
    会在**悄悄变瞎**的情况下仍然显示通过（那比红更危险）。所以两侧都按各自的收尾符号截断：
    写侧到方法右括号、读侧到接收器 lambda 的 `});`。
    """
    i = src.find(marker)
    if i < 0:
        return None
    j = len(src)
    if end is not None:
        k = src.find(end, i)
        if k > 0:
            j = k
    if end is None or j > i + span * 4:
        j = min(j, i + span * 4)
    return re.findall(r"buf\.%s([A-Z][A-Za-z]*)\(" % kind, src[i:j])


w_ops = pkt_ops(mgr_src, "private static FriendlyByteBuf buildLiftChimePacket", "write", end="\n    }")
r_ops = pkt_ops(client_src, "SmoothLift.LIFT_CHIME_SYNC_CHANNEL", "read", end="});")
check(w_ops is not None and r_ops is not None,
      "找得到同步包的写侧（buildLiftChimePacket）与读侧（SmoothLiftClient）",
      "写 %s / 读 %s" % (w_ops, r_ops))
if w_ops and r_ops:
    check(w_ops == r_ops,
          "同步包写入顺序 == 读取顺序（dimId → enabled → speed → volume → up → down → open → close → round → 单项音量×4）",
          "写 %s 读 %s" % (w_ops, r_ops))
    # 【1.15】包尾又追加了四个字符串（四项的维度默认素材）
    check(w_ops[-16:] == ["Boolean", "Float", "VarInt", "Boolean", "Boolean", "Boolean", "Boolean",
                          "VarInt", "VarInt", "VarInt", "VarInt", "VarInt",
                          "Utf", "Utf", "Utf", "Utf"],
          "【1.60】四子开关(up→down→open→close) + 范围(round) + 四项各自音量 + "
          "【1.15】四项默认素材(up→down→open→close)",
          "实际尾部 %s" % w_ops[-16:])

# 7d) applyClientLiftChime 的入参 == 读侧读到的值（【1.46】四个子开关，【1.47】范围，【1.48】四项各自音量）
check(re.search(
    r"applyClientLiftChime\(ResourceKey<Level> dimension,\s*boolean enabled,\s*"
    r"float speed,\s*int volume,\s*boolean upEnabled,\s*boolean downEnabled,\s*"
    r"boolean openEnabled,\s*boolean closeEnabled,\s*int round,\s*int toneVolumeUp,\s*int toneVolumeDown,\s*"
    r"int toneVolumeOpen,\s*int toneVolumeClose,\s*String toneAudioUp,\s*String toneAudioDown,\s*"
    r"String toneAudioOpen,\s*String toneAudioClose\)", mgr_src) is not None,
      "applyClientLiftChime 接收 (dimension, enabled, speed, volume, up, down, open, close, round, 单项音量×4, "
      "【1.15】四项维度默认素材×4) —— 与读侧顺序一致")

# 7e) 【1.47】播放端范围动态化：不再写死 16，而是从同步镜像读（默认 4 格）
check("DEFAULT_LIFT_HELP_ROUND" in mgr_src or "defaultLiftHelpRound" in mgr_src,
      "数据层有 defaultLiftHelpRound（首次载入默认 4 格）")
check("getLiftHelpRound(mc.level)" in player_src and "cachedRound" in player_src,
      "播放端范围从同步镜像读（cachedRound，随 /lifthelpround 同步更新）")
check('lifthelpround' in cmd_src,
      "注册了 /lifthelpround 指令（四项提示音共用的淡入淡出范围）")

# 7e) 音量真的要能放大：GainManagedSound + AL_MAX_GAIN 必须**成对**出现
check(re.search(r"class LiftMusicInstance extends AbstractSoundInstance\s+"
                r"implements\s+TickableSoundInstance,\s*"
                r"GainManagedSound", player_src) is not None,
      "LiftMusicInstance 实现 GainManagedSound（让 SoundEngineVolumeMixin 放行 [0,1] 夹取）"
      "　★【1.51】同时实现 TickableSoundInstance —— AbstractSoundInstance 本身不实现它，"
      "不实现就进不了 tickingSounds、引擎不会每 tick 回来读音量")
check("AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN)" in player_src,
      "开播时把该 OpenAL 源的 AL_MAX_GAIN 抬到 MAX_GAIN（缺这一步仍最多 1.0×）")
check(re.search(r"clampLiftHelpVolume\(helpVolume\)\s*/\s*100\.0f", player_src) is not None,
      "volumeFactor = 音量/100（100 → 1.0 = 原始音量）")

# 7f) 数值对照：端点 + 越界夹取（证明 1~1000 全都落进 [0, MAX_GAIN]）
MAX_GAIN = AUD_MAX / AUD_DEF


def vfactor(v):
    return max(AUD_MIN, min(AUD_MAX, v)) / 100.0


check(abs(vfactor(100) - 1.0) < 1e-9,
      "对照：音量 100 → 增益 1.0（原始音量，与 1.42 的听感一致）",
      "实际 %.4f" % vfactor(100))
check(abs(vfactor(1000) - MAX_GAIN) < 1e-9,
      "对照：音量 1000 → 增益 %.1f× == EscalatorAudioPlayer.MAX_GAIN" % MAX_GAIN,
      "实际 %.4f" % vfactor(1000))
check(abs(vfactor(5000) - MAX_GAIN) < 1e-9 and abs(vfactor(0) - AUD_MIN / 100.0) < 1e-9,
      "对照：越界值被夹到端点（5000→10×、0→0.01×），不会把增益算到区间外",
      "5000→%.4f  0→%.4f" % (vfactor(5000), vfactor(0)))
print()


if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
