# -*- coding: utf-8 -*-
"""离线校验：【1.26】「到站播报 / 进站报站」是**一串门**的站台广播，不是每扇门的位置音。

## 本轮需求（用户原话）

    举个例子：一个站台连在一起的屏蔽门一共12个门，前3个和后4个arrive不响，
    后4个midium不响LOG4文件夹放着最新的log

## 症状 → 数字（`LOG4/latest.log` 现场取证，先把「门没被采集」排掉）

| 事实 | 数字 |
|---|---|
| z=72 那一串门的门坐标 | **12** 扇，x = -58,-53,-48,-43,-38,-33,-28,-23,-18,-13,-8,-3（间距 **5**）⇒ 跨度 **55 格** |
| 玩家 23:55:48 的位置（由「距玩家 X 格」反推） | ≈ **(-45, -12, 72)** ⇒ 该秒有日志的门 = x ∈ [-58, -33] **6 扇** |
| 玩家 23:55:53 | ≈ **(-20, -12, 72)** ⇒ 关门日志 = x ∈ [-33, -8] **6 扇** |
| 玩家 23:56:35 | ≈ **(-15.5, -12, 72)** ⇒ 该秒有日志的门 = x ∈ [-28, -3] **6 扇** |
| ⇒ 规律 | 每次**恰好是「距玩家 < 16 格」的那几扇**（默认范围 16） |
| 射程外那几扇 | 连一行日志都没有 —— 按设计**静默**跳过（`gain == 0 ⇒ return`，`resolvePlayable` /
  `planArrivalAnnounce` 都这样）。★ **「没有日志」≠「没执行」**，别把它当成「门没采到」 |
| 边界那一扇（决定性证据） | x=-28 距玩家 **17 格** ⇒ `gain(17, 16) == 0` ⇒ 整扇门（提示音 + 到站播报）**一声不响** |
| arrive 的声源（同一串、两条日志） | 23:55:30 与 23:56:14 **都是 @[-28,-12,72]**；而认站台那次记的是 **@[-33,-12,72]**
  ⇒ 「代表门」取的是**快照里第一个碰到的**，而 `LIVE` 是 `HashMap` ⇒ **迭代序会变** ⇒ 声源在串里随机跳 |

⇒ 根因不是「门没采到」、不是「子开关被关」、也不是「素材没同步」，而是**把站台广播当成了
每扇门的位置音**：12 扇各排一份（其实读的是**同一份按串的配置**＝纯重复），
而每份的射程只按「本扇门」算 —— 一串 55 格长，站在哪儿都只有约 6 扇在 16 格内。

## 这一层为什么必须单独测（算错了不报错、只是「有的响有的不响」）

1. ★ **「每串只播一次」的键必须是 `runKey`。** 键写回 `door.key()` 不会崩、日志照打，
   只是同一段 13.8 秒的广播叠 N 遍（相位噪声 + N 条音频流）。
2. ★ **射程与声源必须落在「本串离玩家最近的那一扇」上。** 写回「本扇门」或
   「快照第一扇」都不会错到编译期，只会让站台两端听不见。
3. ★ **哨兵不能撞。** `BlockPos.asLong` 的位布局下 `-1L` 是**合法**坐标（方块 (-1,-1,-1)）；
   `Long.MIN_VALUE` 解出来是 `x = 33554432`，**超出世界边界**（±29,999,984）⇒ 真实方块到不了。
   这条用 Python 复算位布局来钉，不靠注释。
4. ★ **只读不删。** 新的「串内最近门」查询会在**声音实例的每 tick 路径**上被调用，
   它不能顺手清理过期项（那是 `snapshot()` 的职责）—— 边迭代边改同一张表是另一个 bug。

开了开关 `--apply` 无副作用（本脚本只读）。
"""

import glob
import os
import re
import sys
import zipfile

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


player = strip_comments(read(PLAYER))
tracker = strip_comments(read(TRACKER))
raw_player = read(PLAYER)

# ======================================================================
# 1) 用户症状数值化：一串 12 扇 = 55 格长 vs 默认射程 16 格
# ======================================================================
print()
print("===== 1) 症状数值化：一串门的跨度 vs 射程 =====")

SPACING = 5.0          # LOG4：z=72 那一串的门间距（x = -58 … -3）
DOORS = 12             # 「一共12个门」
ROUND = 16.0           # 三份范围的老存档默认值（LOG4 头部：提示音 16 / 到站 16 / 进站 16）

span = SPACING * (DOORS - 1)
check(span == 55.0,
      "复算：12 扇、间距 5 的一串门跨度 = **%.0f 格**（远远大于默认射程 %.0f 格）"
      % (span, ROUND))

# 站在一串门中的某一扇旁边时，有多少扇在射程内（含自己那一扇）
def in_range(stand_at, x0=-58.0):
    xs = [x0 + SPACING * i for i in range(DOORS)]
    return [x for x in xs if abs(x - stand_at) < ROUND]


middle = in_range(-33.0)
end = in_range(-58.0)
check(len(middle) == 7 and len(end) == 4,
      "★ 复算：站**中间**那扇最多只有 **%d/12** 扇在 16 格内；站**一端**只剩 **%d/12** 扇"
      % (len(middle), len(end)),
      "中间 %s / 一端 %s" % (middle, end))
check(DOORS - len(middle) >= 5,
      "★★ 结论：**站在站台任何一扇门旁边，都至少有 %d 扇永远在射程外**（这就是「有的门不响」）"
      % (DOORS - len(middle)))


def gain(d, r):
    """与 PsdChimePlayer.gain 同一条算式（【1.25】线性，d >= r ⇒ 0）。"""
    if not (d < r):
        return 0.0
    return max(0.0, 1.0 - d / r)


check(gain(17.0, ROUND) == 0.0,
      "★★ 复算：LOG4 里那扇**距玩家 17 格**的门（x=-28，边界外一格）增益 = **0**"
      " —— 整扇门一声不响，正是因为射程判据把它整个丢掉了（不是它没被采集）")
check(gain(15.999, ROUND) > 0.0 and gain(16.0, ROUND) == 0.0,
      "★ 复算：射程是**硬边界**（15.999 格还有 %.5f，16 格整 = 0）"
      " —— 线性曲线只解决「太轻」，解决不了「硬截成 0」" % gain(15.999, ROUND))

# 按「本串最近那一扇」算距离之后，同一组位置能覆盖多少
stat = gain(0.0, ROUND)          # 站在本串某一扇旁边 ⇒ 到最近门 0 格 ⇒ 增益满
check(stat == 1.0,
      "★★ 对照：同一个玩家位置，若距离按「**本串离玩家最近的那一扇**」算 ⇒ 增益 **1.0**"
      "（玩家就在某一扇门旁边）⇒ 整串的播报都成立 —— 这就是【1.26】的改法")

# ======================================================================
# 2) 到站播报（midium）：按**串**一条，距离/声源取本串最近门
# ======================================================================
print()
print("===== 2) 到站播报：一串一条 + 声源取本串最近门 =====")

check(re.search(r"private static final Map<Long, Arrival> arrivalVoice", player) is not None,
      "到站播报的计划表还在（【1.26】只改键的**含义**，表的生命周期一行不动）")
check(re.search(r"if \(arrivalVoice\.containsKey\(runKey\)\)\s*\{\s*return;", player) is not None,
      "★★【1.26】planArrivalAnnounce 开头就是「这一串已经有一条还没播完的播报 ⇒ 直接返回」"
      " —— 一串只排一条（旧写法每扇门各排一份 = 同一份按串的配置排 N 遍）")
check(re.search(r"arrivalVoice\.put\(runKey,", player) is not None
      and re.search(r"arrivalVoice\.put\(door\.key\(\)", player) is None,
      "★★ 写进去的键是 **runKey**（串锚点）—— 键写回 door.key() 不会报错，"
      "只会让 12 帧内的开门事件各排一条同内容的 13.8 秒广播")
check(re.search(r"new Arrival\(startTick, startTick \+ durationTicks, tone, source, runKey, volume\)",
                player) is not None,
      "★ Arrival 多持一份 runKey（日志要按串读「等待几秒」，距离增益也要按串算）")
check(re.search(r"getDoorPsdMidiumWaitSeconds\(mc\.level, plan\.runKey\)", player) is not None,
      "★★ 起播日志按 **plan.runKey** 读「等待几秒」"
      " —— 拿 door.key() 去读 per-串配置会**静默落回维度默认**（读得到，但读的不是这一串那份）")

# 距离与声源：不能再用调用方传进来的「本扇门距离」
sig = re.search(r"private static void planArrivalAnnounce\(Minecraft mc, "
                r"PsdDoorTracker\.DoorView door,\s*Playable openPlayable\)", player)
check(sig is not None,
      "★★ planArrivalAnnounce 不再收 `double distance` 形参"
      "（那个距离是**本扇门**的 —— 收下它就等于又把广播按门算）")
check(re.search(r"planArrivalAnnounce\(mc, door, openPlayable\);", player) is not None,
      "★ detect 的调用点跟着去掉第四个实参")
check(re.search(r"PsdDoorTracker\.nearestInRun\(runKey, player\)", player) is not None,
      "★★ 声源 = 本串里离玩家最近的那一扇（PsdDoorTracker.nearestInRun）")
check(re.search(r"double distance = player == null \? 0\.0\s*"
                r": player\.distanceTo\(new Vec3\(source\.x\(\), source\.y\(\), source\.z\(\)\)\);",
                player) is not None,
      "★★ 射程判据用的距离也来自**那一扇**（source），不是这一扇（door）")
check(re.search(r"if \(gain\(distance, ROUND_MIDIUM\) \* volume <= 0\.0f\)", player) is not None,
      "★ 到站播报的射程判据仍在（类别 ROUND_MIDIUM）—— 改的只是「距离是谁的距离」")
check("getDoorPsdMidiumAudio(mc.level, runKey)" in player
      and "getDoorPsdMidiumVolume(mc.level, runKey)" in player,
      "★ 顺带把两个「这一扇门的口径」改成直接用 runKey（原本传的就是 door.runKey()）")

# ======================================================================
# 3) 进站报站（arrive）：声源 = 本串最近门（不再用「快照第一扇」）
# ======================================================================
print()
print("===== 3) 进站报站：声源取本串最近门 =====")

check("seenRun" not in player,
      "★★ 旧的 `seenRun`（「快照里第一个碰到的那个 runKey 就算代表门」）**整个删掉**"
      " —— 它是 `HashMap` 迭代序驱动的，现场 LOG4 里同一个串先记 -33、后播 -28")
check(re.search(r"Map<Long, PsdDoorTracker\.DoorView> nearestPerRun = new LinkedHashMap<>\(\);", player)
      is not None,
      "★ 改成先归并出「每一串里离玩家最近的那一扇」（LinkedHashMap：迭代序跟着门快照，日志好读）")
check(re.search(r"if \(cur == null \|\| closerThan\(player, d, cur\)\)", player) is not None,
      "★ 归并判据走 closerThan（抽成一个方法：两条路都别自己写一份比较）")
check(re.search(r"private static boolean closerThan\(Vec3 player, PsdDoorTracker\.DoorView cand,\s*"
                r"PsdDoorTracker\.DoorView cur\)", player) is not None,
      "★ closerThan(player, cand, cur) 存在（player == null ⇒ false，保持先来的那一扇）")
check(re.search(r"arrivePlatform\.keySet\(\)\.retainAll\(nearestPerRun\.keySet\(\)\);", player) is not None
      and re.search(r"arriveLastPoll\.keySet\(\)\.retainAll\(nearestPerRun\.keySet\(\)\);", player)
      is not None,
      "★ 两张缓存表跟着新的键集合收敛（键集合换了 ⇒ 收敛口径也得换，否则缓存无限长大）")
check(re.search(r"play\(mc, tone, door, volume, 0, true, ROUND_ARRIVE, runKey\);", player) is not None,
      "★★ 起播时把 runKey 一并传下去（站台广播：距离按本串最近的门算）")

# ======================================================================
# 4) 「本串最近门」的口径：只读不删 + 两个口
# ======================================================================
print()
print("===== 4) PsdDoorTracker：串内最近门 =====")

check(re.search(r"public static DoorView nearestInRun\(long runKey, Vec3 player\)", tracker)
      is not None,
      "★ nearestInRun(runKey, player) → DoorView（声源位置用它）")
check(re.search(r"public static double nearestDistanceInRun\(long runKey, Vec3 player\)", tracker)
      is not None,
      "★ nearestDistanceInRun(runKey, player) → double（每 tick 的距离增益用它）")
check(re.search(r"private static Entry nearestEntryInRun\(long runKey, Vec3 player\)", tracker)
      is not None,
      "★ 两个口共用一份取数逻辑（别写两遍 —— 两遍就会漂移）")
check(re.search(r"return best == null \? Double\.MAX_VALUE\s*"
                r": player\.distanceTo\(new Vec3\(best\.x, best\.y, best\.z\)\);", tracker) is not None,
      "★★ 这一串不在快照里 ⇒ 返回 Double.MAX_VALUE ⇒ 增益自然归 0"
      "（走远了就该淡出，而不是「看不见就当贴脸」）")
check(re.search(r"if \(player == null\)\s*\{\s*return 0\.0;\s*\}", tracker) is not None,
      "★ player == null（还没进世界 / 界面）当贴脸 = 0，与 refreshVolume 老口径一致")

# ★ 只读不删：nearestEntryInRun 体内不许出现 it.remove() / LIVE.remove
body = re.search(r"private static Entry nearestEntryInRun\(long runKey, Vec3 player\)\s*\{(.*?)\n    \}",
                 tracker, flags=re.S)
check(body is not None, "找得到 nearestEntryInRun 的实体")
if body:
    bb = body.group(1)
    check("remove" not in bb,
          "★★ 它**只读不删** —— 它跑在「声音实例每 tick」的路径上，"
          "边迭代边改 LIVE 是另一个 bug；清理过期项是 snapshot() 的职责")
    check("now - e.tick > STALE_TICKS" in bb,
          "★ 判活口径与 snapshot() 同一套（STALE_TICKS），不另立一个常数")
    check("distanceToSqr" in bb and "Math.sqrt" not in bb,
          "★ 比距离用 distanceToSqr 就够（开方只在最后算一次）—— 每 tick 每实例都会走这里")

# 哨兵：用 Python 复算 asLong 的位布局，证明 Long.MIN_VALUE 不可达
def as_long(x, y, z):
    """net.minecraft.core.BlockPos.asLong —— 位布局逐位复算。"""
    return ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)


def get_x(v):
    """BlockPos.getX(long) = (int)(v >> 38)。"""
    v &= (1 << 64) - 1
    if v >= 1 << 63:
        v -= 1 << 64
    return v >> 38


banned = (1 << 63)
neg_one_x = get_x(-1 & ((1 << 64) - 1))
check(get_x(banned) == -33554432,
      "★★ 复算：Long.MIN_VALUE 按 asLong 解出来是 (x,y,z) = (**%d**, 0, 0)" % get_x(banned),
      "BlockPos.getX(MIN_VALUE) = (int)(MIN_VALUE >> 38)（算术右移 ⇒ 带符号，负号是对的）")
check(abs(get_x(banned)) > 29999984,
      "★★ 而原版世界边界是 ±29,999,984 ⇒ **任何真实方块都到不了那一格** ⇒ 哨兵不会撞"
      "（只与**绝对值**有关，符号不重要）")
check(get_x(-1 & ((1 << 64) - 1)) == -1,
      "★★ 对照：`-1L` 是**合法**坐标（= 方块 x=%d）⇒ 拿它当哨兵会真撞" % neg_one_x)
check(re.search(r"private static final long NO_BROADCAST_RUN = Long\.MIN_VALUE;", player)
      is not None,
      "★★ 所以哨兵取 NO_BROADCAST_RUN = Long.MIN_VALUE（不是 -1）")
check(re.search(r"if \(chainRunKey == NO_BROADCAST_RUN\)", player) is not None
      and re.search(r"d = PsdDoorTracker\.nearestDistanceInRun\(chainRunKey, p\);", player)
      is not None,
      "★★ PsdMusicInstance.refreshVolume 按 chainRunKey 分两种口径"
      "（位置音 = 到 pos；站台广播 = 到本串最近的门）")
check(re.search(r"boolean trainAttenuated, int roundKind, long chainRunKey,", player) is not None,
      "★ play(...) 与构造里 chainRunKey 是**独立的第 8 格**"
      " —— 不用 `roundKind != ROUND_TONE` 推出来：范围类别与「是不是广播」是两个判据，"
      "合并 ⇔ 以后新加一类范围会**悄悄**变成广播（本项目栽过「一个哨兵表达两件事」）")

# ======================================================================
# 5) 最近门的选择：纯逻辑复算（与 Java 侧同一条算式）
# ======================================================================
print()
print("===== 5) 最近门选择：纯逻辑复算 =====")

DOOR_XS = [-58.0 + SPACING * i for i in range(DOORS)]


def nearest(player_x, xs=DOOR_XS):
    """closerThan 的语义：严格小于才替换 ⇒ 平手时**保持先来的那一扇**（顺序稳定）。"""
    best = None
    for x in xs:
        if best is None or (player_x - x) ** 2 < (player_x - best) ** 2:
            best = x
    return best


for px, want in ((-45.0, -43.0), (-16.0, -18.0), (-58.0, -58.0), (-3.0, -3.0)):
    check(nearest(px) == want,
          "复算：玩家 x=%.1f ⇒ 本串最近门 x=%.1f" % (px, want),
          "得到 %s" % nearest(px))
# -30.5 正好夹在 -33 与 -28 的**中点** ⇒ 平手；-45.5 同理（-48 与 -43 的中点）
check(nearest(-30.5) == -33.0 and nearest(-45.5) == -48.0,
      "★★ 复算：两扇门**正中间**平手时取 **x 更小**的那一扇（closerThan 用严格 < ⇒ 保持先来的）"
      " —— 平手必须有确定的规则，否则「代表门」会每 tick 在两扇之间跳",
      "得到 -30.5 ⇒ %s，-45.5 ⇒ %s" % (nearest(-30.5), nearest(-45.5)))

# 站台任意位置：到最近门的距离必须 < 射程（这就是「整串都听得见」）
worst = max(abs((x0 + SPACING / 2.0) - nearest(x0 + SPACING / 2.0)) for x0 in DOOR_XS[:-1])
check(worst == SPACING / 2.0,
      "★★ 复算：站台上**任何**位置到最近门最远只有 %.1f 格（= 间距的一半）"
      " ⇒ 射程 16 格时，整串 12 扇门覆盖的每一个点都在射程内" % worst)
check(all(gain(abs(px - nearest(px)), ROUND) >= 0.8
          for px in [x0 + SPACING / 4.0 for x0 in DOOR_XS]),
      "★★ 复算：沿站台走到任何一扇门前 1.25 格，增益都 ≥ 0.8（旧口径下站台两端是 0）")

# ======================================================================
# 6) 产物：类名 / 方法名进常量池
# ======================================================================
print()
print("===== 6) 产物字节码 =====")

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        blob_tracker = z.read("smooth/lift/client/PsdDoorTracker.class")
        blob_player = z.read("smooth/lift/client/PsdChimePlayer.class")
        inner = "smooth/lift/client/PsdChimePlayer$PsdMusicInstance.class"
        check(inner in names, "★ 播放实例是内嵌类 %s" % inner)
        blob_inner = z.read(inner) if inner in names else b""
    for tok in (b"nearestInRun", b"nearestDistanceInRun", b"nearestEntryInRun"):
        check(tok in blob_tracker,
              "PsdDoorTracker.class 里编进了 %s" % tok.decode())
    for tok in (b"closerThan", b"chainRunKey", b"nearestPerRun"):
        check(tok in blob_player,
              "PsdChimePlayer.class 里编进了 %s" % tok.decode())
    check(b"chainRunKey" in blob_inner,
          "★★ PsdMusicInstance 内嵌类里编进了 chainRunKey —— 距离口径真的传到了每 tick 那条路")
    # 注：NO_BROADCAST_RUN 是 `static final long`，javac 会**内联成字面量**，
    #   常量池里没有它的名字（与 check-psd-volume.py 里 ROUND_* 同一条坑，别拿它去查）。

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
