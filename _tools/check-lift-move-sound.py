# -*- coding: utf-8 -*-
"""离线校验：【1.44】直梯「准备移动」提示音（up.ogg / down.ogg）。

## 需求原话（用户）

    扶梯准备向上移动时播放一次 up.ogg
    扶梯准备向下移动时播放一次 down.ogg
    举个例子：准备移动是指类似于玩家在 2 楼，按下 z 选择 3 楼时就是准备向上了

（用户嘴上说「扶梯」，但举的例子是**按 Z 选层** —— 那是直梯（Lift）的行为，不是扶梯。
所以这条功能落在直梯提示音那套里，与【1.42】的开关门提示音共用 `/lifthelp` 等开关。）

## 「准备移动」到底对应什么信号（两版都对着字节码核过）

     MTR3  mtr.data.Lift.tick → lambda$tick$4：
             有目标楼层 ⇒ liftDirection = (目标在上 ? UP : DOWN)，否则 NONE
     MTR4  org.mtr.core.data.Lift.getDirection()：
             instructions 为空 ⇒ NONE；否则 fromDifference(目标楼层进度 - 当前位置)

★ 两个关键性质：
  1. **每 tick 从待办指令重算**，所以在「刚接到指令」那一瞬就从 NONE 变成 UP/DOWN ——
     这正是用户说的「准备移动」；
  2. **移动全程保持同一个值**（不是只在位移那一帧才有值），所以「方向没变」不响，
     一条指令**只响一次**（中途经过中间楼层不会重复触发）。

⇒ 判据就是 **`now != NONE && now != prev`**，一条 `NONE→UP/DOWN` 的跳变 = 响一次。
   它同时覆盖「待命→上行」「待命→下行」「中途反悔（UP→DOWN）」。

## 这个脚本查什么

1. 素材：`up.ogg` / `down.ogg` 存在、非空、是 OggS；且与用户给的原始文件**逐字节相同**（没被转码/裁掉）；
2. `sounds.json` 注册了 `audio/up` / `audio/down`，且指向的 .ogg 真的在；
3. `LiftChimePlayer` 的判据 / 只响一次 / 不走连播排期 / 音量与倍速复用既有旋钮 / 只对最近那条响；
4. `MtrLiftAccess` 的方向解析（枚举恰为 NONE/UP/DOWN、按名字解析、认不出 → NONE、
   getter 缺失只 WARN 不 throw，不能把开关门提示音一起带坏）；
5. **语义模拟 + 对照实验**：用 Python 复刻判据跑几条真实轨迹，断言「恰好响一次」；
   再把 `now == prev` 去掉跑一遍，确认它会重复响（证明该判据有鉴别力，不是恒真）；
6. 打包：jar 里确实含两个 .ogg、`sounds.json` 有那两条、`LiftChimePlayer.class` 里有那两个字面量。

解析失败**报错退出**，不做静默兜底 —— 以后改写法时脚本会明确要求同步更新。

用法：`python _tools/check-lift-move-sound.py`（退出码 0 = 全部通过）
"""
import glob
import hashlib
import json
import math
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
AUDIO_DIR = os.path.join(RES, "assets", "smoothlift", "sounds", "audio")
SOUNDS_JSON = os.path.join(RES, "assets", "smoothlift", "sounds.json")
CHIME = os.path.join(CLIENT, "LiftChimePlayer.java")
ACCESS = os.path.join(CLIENT, "MtrLiftAccess.java")
SERVER_MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "SmoothLift.java")
# 用户放在工作区根的原始素材（用它证明「打进工程的字节没变」）
GIVEN = os.path.join(os.path.dirname(ROOT), "mzycBetterMTR-1.20.4")
GIVEN_DIR = os.path.dirname(ROOT)

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def ogg_duration(path):
    """读 OggS 最后一个页面的 granule，算时长（秒）。不需要外部依赖。"""
    try:
        import struct
        with open(path, "rb") as fh:
            data = fh.read()
        if data[:4] != b"OggS":
            return None
        # 逐页往后走，取最后一页的 granule（样本数）
        pos, granule, rate = 0, 0, None
        while pos + 27 <= len(data) and data[pos:pos + 4] == b"OggS":
            nseg = data[pos + 26]
            seg = data[pos + 27:pos + 27 + nseg]
            body = sum(seg)
            granule = struct.unpack_from("<q", data, pos + 6)[0]
            page = pos + 27 + nseg + body
            if rate is None:
                # 第一页（识别头）里 vorbis 采样率在 body 偏移 12
                b = pos + 27 + nseg
                if data[b + 1:b + 7] == b"vorbis":
                    rate = struct.unpack_from("<i", data, b + 12)[0]
            pos = page
        if rate:
            return granule / float(rate)
    except Exception:
        return None
    return None


# ----------------------------------------------------------------------
# 1) 素材：存在、非空、是 OggS、且与用户原始文件逐字节相同
# ----------------------------------------------------------------------
print("== 1. 素材 ==")
for name in ("up", "down"):
    path = os.path.join(AUDIO_DIR, name + ".ogg")
    ok = os.path.isfile(path) and os.path.getsize(path) > 1024
    check(ok, "assets/smoothlift/sounds/audio/%s.ogg 存在且非空" % name,
          ("%d B" % os.path.getsize(path)) if os.path.isfile(path) else "缺失")
    if ok:
        with open(path, "rb") as fh:
            magic = fh.read(4)
        check(magic == b"OggS", "%s.ogg 是 OggS 容器（Minecraft 只认 OGG Vorbis）" % name,
              "魔数 %r" % magic)
        dur = ogg_duration(path)
        if dur is not None:
            check(dur < 10.0, "%s.ogg 时长 %.2f s < 10 s（短素材，可直接内嵌不必 stream）"
                  % (name, dur))

        given = os.path.join(GIVEN_DIR, name + ".ogg")
        if os.path.isfile(given):
            same = sha256(given) == sha256(path)
            check(same, "%s.ogg 与用户给的原始素材逐字节相同（没被转码 / 裁掉）" % name,
                  "sha256 %s" % sha256(path)[:16])
        else:
            print("[SKIP] 工作区根没有 %s.ogg，跳过「与原素材逐字节相同」这一步" % name)


# ----------------------------------------------------------------------
# 2) sounds.json 注册
# ----------------------------------------------------------------------
print("\n== 2. sounds.json ==")
sounds = {}
if os.path.isfile(SOUNDS_JSON):
    sounds = json.loads(read(SOUNDS_JSON))
for name in ("up", "down"):
    ev = "audio/%s" % name
    entry = sounds.get(ev)
    ok = bool(entry) and bool(entry.get("sounds"))
    ref = (entry or {}).get("sounds", [{}])[0].get("name") if ok else None
    check(ok and ref == "smoothlift:%s" % ev,
          "sounds.json 注册了 %s → smoothlift:%s" % (ev, ev),
          "实际 %s" % ref)
    check(os.path.isfile(os.path.join(AUDIO_DIR, name + ".ogg")),
          "%s 指向的 .ogg 文件真的在（注册了却没有文件 = 引擎静默不响，最难查的那类）" % ev)
# 对照：故意写一个不存在的入口，确认「注册了但没有文件」这件事查得出来
check(not os.path.isfile(os.path.join(AUDIO_DIR, "no_such_sound.ogg")),
      "对照：本脚本确实能区分「有注册」与「有文件」（用一个不存在的名字验证查得到）")


# ----------------------------------------------------------------------
# 3) LiftChimePlayer：判据 / 只响一次 / 不走连播排期 / 复用既有旋钮
# ----------------------------------------------------------------------
print("\n== 3. LiftChimePlayer ==")
chime = read(CHIME)

for const, ev in (("LIFT_UP", "audio/up"), ("LIFT_DOWN", "audio/down")):
    m = re.search(r"%s\s*=\s*\n?\s*new ResourceLocation\(\"smoothlift\",\s*\"([^\"]+)\"\)"
                  % const, chime)
    check(bool(m) and m.group(1) == ev,
          "%s 指向 smoothlift:%s" % (const, ev),
          "实际 %s" % (m.group(1) if m else None))

# 判据：now == NONE || now == prev → return
m = re.search(r"private static void detectMove\((.*?)\n    \}", chime, re.S)
body = m.group(0) if m else ""
check(bool(body), "找到 detectMove 方法体")
if body:
    check(re.search(r"now\s*==\s*MtrLiftAccess\.Move\.NONE\s*\|\|\s*now\s*==\s*prev", body)
          is not None,
          "判据是 `now != NONE && now != prev`（写成 now==NONE || now==prev 提前 return）",
          "这就是「一条 NONE→UP/DOWN 跳变只响一次」的全部条件")
    check("playsLeft" not in body and "nextPlayTick" not in body,
          "detectMove 不碰连播排期（playsLeft/nextPlayTick）⇒ 不会顶掉正在进行的关门连播")
    check(re.search(r"up\s*\?\s*LIFT_UP\s*:\s*LIFT_DOWN", body) is not None,
          "方向 UP 放 LIFT_UP、DOWN 放 LIFT_DOWN（按方向选素材）")
    check(re.search(r"volumeFactor\(toneVolume\)\s*\*\s*spatialFactor\(playerPos\(mc\),\s*"
                    r"tonePos,\s*cabin\)\s*<=\s*0\.0f", body) is not None,
          "音量复用 `/lifthelploud` 且按方向取单项；先按【1.51/1.52】空间系数判"
          "「当前位置还听得见吗」（volumeFactor(toneVolume) × spatialFactor(playerPos(mc), tonePos, cabin)）")
    check(re.search(r"liftToneVolume\(mc,\s*up\s*\?\s*\"up\"\s*:\s*\"down\"\)", body) is not None,
          "【1.48】上楼用 up 单项音量、下楼用 down 单项音量（没单独调过回落共用默认）")
    check(re.search(r"cachedSpeed", body) is not None,
          "音高复用直梯倍速 cachedSpeed（【1.15】改了它的指令 /lifthelpspeed 已删除）")
    check(re.search(r"<=\s*0\.0f", body) is not None,
          "音量为 0 时直接不播（/lifthelploud 0、或【1.51】轿厢外超出 /lifthelpround 范围 ⇒ 静音不留空转）")

# 只对最近那条响
m = re.search(r"if\s*\(lift\s*==\s*nearest\)\s*\{(.*?)\n            \}", chime, re.S)
near_body = m.group(1) if m else ""
check("detectMove(" in near_body,
      "只在 `lift == nearest` 分支里调 detectMove ⇒ 同一时刻只有最近那条直梯会响",
      "否则一个大车站里几十条直梯会一起响")

# 首次看到不误触发 + 每条都记 + 清理
check(re.search(r"lastMove\.containsKey\(lift\.id\(\)\)\s*\n?\s*\?\s*lastMove\.get\(lift\.id\(\)\)"
                r"\s*:\s*lift\.move\(\)", chime) is not None,
      "首次看到某条直梯时 prev 取当前值 ⇒ 不会因为「第一次看见它」而误响一声")
check("lastMove.put(lift.id(), lift.move())" in chime,
      "每条直梯都记录方向（不只是最近那条）⇒ 最近的一条换成另一条时不会误响")
check("lastMove.keySet().retainAll(seen)" in chime,
      "每帧清掉已不在客户端集合里的直梯 ⇒ Map 不会无限长大")
check(re.search(r"lastMove\.clear\(\)", chime) is not None,
      "reset() 里清空 lastMove ⇒ 玩家进/出维度、重连后不会拿旧状态比")

# 复用既有指令（开关 / 音量都借用直梯那一套，不为准备移动音单独开指令）
# 【1.15】/lifthelpspeed 已按用户要求删除，所以这里只剩两条。
for cmd in ("lifthelp", "lifthelploud"):
    check(cmd in chime, "复用既有指令 /%s（本功能不新增指令）" % cmd)
server_main = read(SERVER_MAIN)
check(re.search(r'literal\(\s*"lifthelpspeed"', server_main) is None,
      "【1.15】/lifthelpspeed 已从指令树里删除（up/down 两项只跟着 /lifthelp 那套走）")
check(re.search(r'liftToneBranch\(\s*"up"\s*,\s*"up"\s*\)', server_main) is not None
      and re.search(r'liftToneBranch\(\s*"open"\s*,\s*"open"\s*\)', server_main) is not None
      and re.search(r'liftToneBranch\(\s*"close"\s*,\s*"close"\s*\)', server_main) is not None,
      "对照：脚本确实能读到指令树构造（/lifthelp up|down|open|close 在）⇒ 上一条断言有鉴别力")


# ----------------------------------------------------------------------
# 4) MtrLiftAccess：方向解析与降级
# ----------------------------------------------------------------------
print("\n== 3b. 【1.51/1.52】真实轿厢盒 + 音量逐 tick 重算 ==")

access_src = read(ACCESS)

m = re.search(r"private static float spatialFactor\(Vec3 player, Vec3 sound, "
              r"MtrLiftAccess\.Cabin cabin\) \{(.*?)\n    \}", chime, re.S)
sf = m.group(0) if m else ""
check(bool(sf), "找到 spatialFactor(Vec3, Vec3, MtrLiftAccess.Cabin) 方法体")
if sf:
    check(re.search(r"OUTSIDE_CABIN_FACTOR \* f \* f", sf) is not None,
          "轿厢外 = OUTSIDE_CABIN_FACTOR × 平方淡出（淡出跨度从轿厢表面起算）")
    check("player == null" in sf, "拿不到玩家位置时不衰减（返回 1.0）")
    check(re.search(r"double beyond = beyondCabin\(player, sound, cabin\);", sf) is not None
          and re.search(r"if \(beyond <= 0\.0\) \{\s*return 1\.0f;", sf) is not None,
          "* 判定分两步：先算「到轿厢表面的距离」，<= 0（在盒里）直接 100%")

m = re.search(r"private static double beyondCabin\((.*?)\n    \}", chime, re.S)
bc = m.group(0) if m else ""
check(bool(bc), "找到 beyondCabin 方法体")
if bc:
    check(re.search(r"if \(cabin == null\) \{.*?return player\.distanceTo\(sound\) - CABIN_RADIUS_H;",
                    bc, re.S) is not None,
          "* 读不到真实尺寸时**逐字回落**【1.51】的 1.5 格圆（含 <= 判定与 3D 距离 − 半径）",
          "这一支是「MTR 改了名 / 宽轿厢」时的兜底，必须与 1.51 行为一致")
    check(re.search(r"Math\.abs\(player\.x - cabin\.centerX\(\)\) - \(cabin\.halfWidth\(\) \+ CABIN_MARGIN\)",
                    bc) is not None
          and re.search(r"Math\.abs\(player\.z - cabin\.centerZ\(\)\) - \(cabin\.halfDepth\(\) \+ CABIN_MARGIN\)",
                        bc) is not None,
          "* 水平按**真实轿厢盒**判：|Δx| − (半宽 + 余量)（中心是轿厢中心，不是楼层方块那个角）")
    check(re.search(r"double slack = cabin\.spacing\(\);", bc) is not None
          and re.search(r"cabin\.baseY\(\) \+ cabin\.height\(\) \+ slack", bc) is not None,
          "* 竖直容差 = 相邻楼层最小间距 Cabin.spacing()（行进中基准最多偏一整段）")
    check(re.search(r"if \(cabin\.spacing\(\) > 0\.0\)", bc) is not None,
          "楼层表拿不到（spacing == 0）⇒ **不做**竖直判定，只按水平盒判（降级，不是判错）")

m = re.search(r"CABIN_RADIUS_H = ([0-9.]+);", chime)
check(bool(m) and float(m.group(1)) == 1.5,
      "CABIN_RADIUS_H = 1.5 格（【1.52】起降级为**回落值**，不再是主判据）",
      "实际 %s" % (m.group(1) if m else None))
m = re.search(r"CABIN_MARGIN = ([0-9.]+);", chime)
check(bool(m) and 0.0 < float(m.group(1)) <= 1.0,
      "CABIN_MARGIN ∈ (0, 1] 格（玩家身宽余量：贴墙站不算出厢）",
      "实际 %s" % (m.group(1) if m else None))
m = re.search(r"OUTSIDE_CABIN_FACTOR = ([0-9.]+)f;", chime)
check(bool(m) and float(m.group(1)) == 0.2,
      "OUTSIDE_CABIN_FACTOR = 0.2（用户点名的「原先的 20%」，【1.52】不动）",
      "实际 %s" % (m.group(1) if m else None))

# ---- 【1.52】MtrLiftAccess 真的把两个版本的几何都读出来了 ----
check(re.search(r"public record Cabin\(double centerX, double centerZ, double baseY,\s*"
                r"double halfWidth, double halfDepth, double height, double spacing\)",
                access_src) is not None,
      "【1.52】新增 Cabin record（中心 xz + 底面 y + 半宽半深 + 高 + 楼层间距）")
check(re.search(r"Cabin cabin\) \{", access_src) is not None,
      "LiftView 带上 cabin 字段（null = 读不到几何 ⇒ 回落 1.5 格圆）")
check("lift.cabin()" in chime,
      "播放侧从快照里取 cabin（不是自己再反射一遍）")
for meth in ("getWidth", "getDepth", "getHeight", "getOffsetX", "getOffsetZ", "iterateFloors"):
    check(('"%s"' % meth) in access_src, "MTR4 路径读了 %s" % meth)
for fld in ("liftWidth", "liftDepth", "liftHeight", "liftOffsetX", "liftOffsetZ", "floors"):
    check(('"%s"' % fld) in access_src, "MTR3 路径读了 %s" % fld)
check(re.search(r"bindCabinGeometry\(true\);", access_src) is not None
      and re.search(r"bindCabinGeometry\(false\);", access_src) is not None,
      "两个版本各自调 bindCabinGeometry(...)")
m = re.search(r"private static void bindCabinGeometry\(boolean mtr3\) \{(.*?)\n    \}",
              access_src, re.S)
bg = m.group(0) if m else ""
check(bool(bg) and "LOGGER.warn" in bg and re.search(r"^\s*throw\b", bg, re.M) is None,
      "* bindCabinGeometry 只 WARN、不 throw —— 抛出去会把 broken 置位、提示音**全部静音**",
      "几何是可选增强，读不到就只回落 1.5 格圆（教训 17：可选增强别把主功能带死）")
check(re.search(r"cabinGeometryOk = false;", access_src) is not None,
      "读不全时 cabinGeometryOk = false ⇒ 快照里 cabin 给 null")

check(re.search(r"class LiftMusicInstance extends AbstractSoundInstance implements TickableSoundInstance",
                chime) is not None,
      "* LiftMusicInstance 实现 TickableSoundInstance —— AbstractSoundInstance 本身**不**实现它"
      "（javap 实测只实现 SoundInstance），不实现就进不了 SoundEngine.tickingSounds，"
      "引擎一辈子不会回来读音量 ⇒ 音量永远是起播时那一个值（用户报的病）")
check(re.search(r"public void tick\(\) \{\s*Minecraft mc = Minecraft\.getInstance\(\);\s*"
                r"this\.volume = baseVolume\s*\*\s*spatialFactor\(playerPos\(mc\), "
                r"new Vec3\(this\.x, this\.y, this\.z\), this\.cabin\);\s*\}", chime) is not None,
      "* tick() 里按玩家当前位置重算 this.volume（引擎每 tick 把它写进 AL_GAIN），"
      "并带上该实例自己那份 this.cabin")
check(re.search(r"public boolean isStopped\(\) \{\s*return false;\s*\}", chime) is not None,
      "isStopped() 恒 false（一次性音效交回引擎，在通道播完时回收）")
check(re.search(r"this\.volume = baseVolume \* spatialFactor\(playerPos\(Minecraft\.getInstance\(\)\), "
                r"pos, cabin\);", chime) is not None,
      "起播当刻也算一次（既不炸一下、也没有开头空白）")
check("inst.setPosition(pos, baseVolume, cabin)" in chime,
      "play() 传给实例的是**不含**空间系数的设置音量 + 这条直梯的轿厢盒"
      "（空间系数由实例自己逐 tick 算）")
check(re.search(r"private MtrLiftAccess\.Cabin cabin;", chime) is not None,
      "LiftMusicInstance 自持一份 cabin（起播时定；一次提示音只响几秒，轿厢不会中途变形）")

# ---- 数值：把 spatialFactor + beyondCabin **忠实搬到 Python** 里跑 ----
# * 三个常量**从源码解析**、不抄一份：源码一改，下面的数值断言跟着变（改错立刻红）
# * 声源固定放在原点，cabin 用 dict 描述（cx/cz = 轿厢中心，by = 底面 y，hw/hd = 半宽半深，
#   h = 轿厢高，spacing = 相邻楼层最小间距）；cabin=None = 走回落的 1.5 格圆那一支。
CABIN_R = float(re.search(r"CABIN_RADIUS_H = ([0-9.]+);", chime).group(1))
CABIN_MARGIN = float(re.search(r"CABIN_MARGIN = ([0-9.]+);", chime).group(1))
OUTSIDE = float(re.search(r"OUTSIDE_CABIN_FACTOR = ([0-9.]+)f;", chime).group(1))


def beyond_of(player, cabin):
    """复刻 beyondCabin：返回「到轿厢表面的距离」（<= 0 = 在厢里）。"""
    px, py, pz = player
    if cabin is None:
        if math.hypot(px, pz) <= CABIN_R:
            return 0.0
        return math.sqrt(px * px + py * py + pz * pz) - CABIN_R
    ex = abs(px - cabin["cx"]) - (cabin["hw"] + CABIN_MARGIN)
    ez = abs(pz - cabin["cz"]) - (cabin["hd"] + CABIN_MARGIN)
    ey = 0.0
    if cabin["spacing"] > 0.0:
        slack = cabin["spacing"]
        ey = max(cabin["by"] - slack - py, py - (cabin["by"] + cabin["h"] + slack))
    if ex <= 0.0 and ez <= 0.0 and ey <= 0.0:
        return 0.0
    return math.sqrt(max(ex, 0.0) ** 2 + max(ez, 0.0) ** 2 + max(ey, 0.0) ** 2)


def spatial(player, cabin, round_=4.0, outside=OUTSIDE):
    """复刻 spatialFactor（默认 /lifthelpround = 4）。"""
    beyond = beyond_of(player, cabin)
    if beyond <= 0.0:
        return 1.0
    span = round_ - CABIN_R
    if not span > 0.0 or beyond >= span:
        return 0.0
    f = 1.0 - beyond / span
    return outside * f * f


def tight(player, cabin, round_=4.0):
    """对照组：竖直容差取 0（= 忘了「行进中基准会偏」的那个改法）。"""
    px, py, pz = player
    ex = abs(px - cabin["cx"]) - (cabin["hw"] + CABIN_MARGIN)
    ez = abs(pz - cabin["cz"]) - (cabin["hd"] + CABIN_MARGIN)
    ey = max(cabin["by"] - py, py - (cabin["by"] + cabin["h"]))
    if ex <= 0.0 and ez <= 0.0 and ey <= 0.0:
        return 1.0
    span = round_ - CABIN_R
    beyond = math.sqrt(max(ex, 0.0) ** 2 + max(ez, 0.0) ** 2 + max(ey, 0.0) ** 2)
    if not span > 0.0 or beyond >= span:
        return 0.0
    f = 1.0 - beyond / span
    return OUTSIDE * f * f


# 用户那台直梯：宽轿厢 5×5（半宽半深 2.5）、轿厢高 3、相邻楼层间距 5、轿厢中心压在楼层方块上
WIDE = {"cx": 0.0, "cz": 0.0, "by": 0.0, "hw": 2.5, "hd": 2.5, "h": 3.0, "spacing": 5.0}

check(abs(spatial((0.0, 1.0, 0.0), WIDE) - 1.0) < 1e-12, "* 厢内正中 = 100%")
check(abs(spatial((2.4, 1.0, 2.4), WIDE) - 1.0) < 1e-12,
      "★ 厢内**贴角**（离中心水平 3.4 格）= 100%",
      "同一个位置，旧 1.5 格圆只剩 %.1f%%" % (spatial((2.4, 1.0, 2.4), None) * 100))
check(abs(spatial((1.3, 1.0, 1.3), WIDE) - 1.0) < 1e-12,
      "★ 厢内水平 1.84 格（正是 LOG3 里实测那一段）= 100%",
      "同一个位置，旧圆 = %.1f%%（这就是「一会大一会小」的幅度）"
      % (spatial((1.3, 1.0, 1.3), None) * 100))


def ride_ref(depart_y, arrive_y, p):
    """复刻 getCurrentFloor 的取法：按进度 p 取**较近的那一端**（javap 实测 < 0.5 取起点）。"""
    return depart_y if p < 0.5 else arrive_y


SAMPLES = [i / 20.0 for i in range(20)]
RIDE_BAD = []
for p in SAMPLES:
    ref = ride_ref(0.0, 5.0, p)
    py = 1.0 + 5.0 * p          # 乘客脚底 = 轿厢底 + 1，轿厢竖直井道线性上升
    cab = dict(WIDE, by=ref)
    if abs(spatial((0.0, py, 0.0), cab) - 1.0) > 1e-12:
        RIDE_BAD.append((p, ref, py))
check(not RIDE_BAD,
      "★★ 向上移动**全程 20 个采样点**（基准取较近一端、最多偏一整段）= 恒 100%",
      "出问题的采样点：%s" % RIDE_BAD[:3])
BAD_TIGHT = [p for p in SAMPLES
             if abs(tight((0.0, 1.0 + 5.0 * p, 0.0), dict(WIDE, by=ride_ref(0.0, 5.0, p))) - 1.0) > 1e-9]
check(len(BAD_TIGHT) > 0,
      "★ 对照：竖直容差取 0 时，同一段路程里有 %d/20 个采样点被**误判成厢外**"
      % len(BAD_TIGHT),
      "⇒ Cabin.spacing() 这条容差是必须的，不是装饰")

SPAN = 4.0 - CABIN_R           # 淡出跨度 = /lifthelpround − 1.5
check(abs(spatial((2.5 + CABIN_MARGIN + 1e-3, 1.0, 0.0), WIDE) - 0.20) < 2e-3,
      "* 刚迈出轿厢表面 = **正好 20%**（用户点名要的那个数）",
      "%.4f" % spatial((2.5 + CABIN_MARGIN + 1e-3, 1.0, 0.0), WIDE))
vals = [spatial((2.5 + CABIN_MARGIN + d, 1.0, 0.0), WIDE) for d in (0.05, 0.5, 1.0, 1.5, 2.0)]
check(all(vals[i] > vals[i + 1] for i in range(len(vals) - 1)) and vals[-1] > 0.0,
      "* 越远越小：20% 起单调递减（还没到范围边界时不提前归零）",
      " / ".join("%.4f" % v for v in vals))
check(spatial((2.5 + CABIN_MARGIN + SPAN, 1.0, 0.0), WIDE) == 0.0,
      "* 到 /lifthelpround（默认 4 格）从**轿厢表面**起算归零")
check(spatial((0.0, 1.0 + 2 * 5.0 + 3.0, 0.0), WIDE) == 0.0,
      "★★ 同竖列但**隔两层楼**（2×5 + 3 = 13 格）= 0% —— 修掉了旧模型"
      "「同一竖列隔多远都 100%」那个洞（旧模型只看水平、根本没看竖直）")
check(abs(spatial((0.0, 1.0 + 5.0, 0.0), WIDE) - 1.0) < 1e-12,
      "★ 同竖列**相邻楼层**仍是 100%：容差 = 一整段间距的必然代价，与 1.51 行为一致、不算回归",
      "要连这一档也安静，就得拿 MTR 的轨道形状回调 —— 那要读客户端世界 + 逐段向量，不值当")
check(spatial((0.0, 1.0, 0.0), WIDE, round_=128.0) > 0.0,
      "范围可调：round=128 时厢外仍可闻（旁观者都听得到，只是淡）")
check(spatial((2.5 + CABIN_MARGIN + 2.0, 1.0, 0.0), WIDE, round_=1.0) == 0.0,
      "可预期降级：round ≤ 1.5 时轿厢外一律静音 —— 可见范围已被轿厢占满")

# ---- 回落分支：读不到几何时必须与【1.51】逐点一致 ----
check(abs(spatial((0.7, 1.0, 0.0), None) - 1.0) < 1e-12, "* 回落：水平 0.7 格 = 100%")
check(abs(spatial((CABIN_R + 1e-6, 0.0, 0.0), None) - 0.20) < 1e-6,
      "* 回落：刚越过 1.5 格 = 正好 20%",
      "%.6f" % spatial((CABIN_R + 1e-6, 0.0, 0.0), None))
check(spatial((CABIN_R, 0.0, 0.0), None) == 1.0,
      "回落：边界包含在轿厢内（正好 1.5 格仍 100%），越过才降档")
check(spatial((4.0, 0.0, 0.0), None) == 0.0 and spatial((9.9, 0.0, 0.0), None) == 0.0,
      "回落：到 /lifthelpround 归零")
check(spatial((2.0, 0.0, 0.0), None, round_=1.0) == 0.0,
      "回落：round=1（≤ 1.5）时轿厢外一律静音（可预期降级，不特判）")

# ---- 对照 1：把「轿厢外 20%」改回 1.0（= 不区分轿厢内外的旧行为）----
check(abs(spatial((2.5 + CABIN_MARGIN + 1e-3, 1.0, 0.0), WIDE, outside=1.0) - 0.20) > 1e-6,
      "对照 1：OUTSIDE_CABIN_FACTOR 改成 1.0 后「出厢 = 20%」不成立"
      " ⇒ 上面那条断言有鉴别力，不是恒真",
      "改后 %.4f" % spatial((2.5 + CABIN_MARGIN + 1e-3, 1.0, 0.0), WIDE, outside=1.0))

# ---- 对照 2：旧写法（音量只在起播时定死）在用户那条场景下的结果 ----
_play_at = spatial((0.7, 1.0, 0.0), WIDE)                       # 在轿厢里按下按钮那一刻
_walk_to = spatial((2.5 + CABIN_MARGIN + 2.0, 1.0, 0.0), WIDE)  # 走出去 2 格之后
check(abs(_play_at - _walk_to) > 1e-6,
      "对照 2：旧写法会把 %.2f 一直播完，新写法走到厢外 2 格只剩 %.4f ⇒ 两者可区分"
      "（这正是用户报的「不管走多远都清晰听到」）" % (_play_at, _walk_to))

# ---- 编译产物（dev 目录、未 remap）：接口真的挂上去了 ----
DEV = os.path.join(ROOT, "build", "classes", "java", "client", "smooth", "lift", "client",
                   "LiftChimePlayer$LiftMusicInstance.class")
if os.path.isfile(DEV):
    with open(DEV, "rb") as fh:
        blob = fh.read()
    check(b"net/minecraft/client/resources/sounds/TickableSoundInstance" in blob,
          "编译产物（dev、未 remap）里 LiftMusicInstance 真的 implements TickableSoundInstance")
else:
    check(False, "找不到 %s（先 ./gradlew compileClientJava）" % os.path.relpath(DEV, ROOT))


print("\n== 4. MtrLiftAccess ==")
access = read(ACCESS)

m = re.search(r"public enum Move\s*\{(.*?)\n    \}", access, re.S)
enum_body = m.group(1) if m else ""
consts = re.findall(r"^\s{8}([A-Z_]+)\s*[,;]", enum_body, re.M)
check(consts == ["NONE", "UP", "DOWN"],
      "Move 枚举恰为 NONE / UP / DOWN（顺序也一致）",
      "实际 %s" % consts)

check(re.search(r'if\s*\(\s*"UP"\.equals\(name\)\s*\)', access) is not None
      and re.search(r'if\s*\(\s*"DOWN"\.equals\(name\)\s*\)', access) is not None,
      "按 toString() 的**名字**解析（编译期不需要认识任何 MTR 类型）")
check(re.search(r"return NONE;", enum_body) is not None
      and re.search(r"if\s*\(mtrDirection\s*==\s*null\)", enum_body) is not None,
      "null / 名字不认识一律 → NONE（宁可不出声，也不按错方向出声）")

for meth in ("getLiftDirection", "getDirection"):
    m = re.search(r'= method\(lift,\s*"%s"\);\s*\n\s*if\s*\(\w+\s*==\s*null\)\s*\{(.*?)\n        \}'
                  % meth, access, re.S)
    block = m.group(1) if m else ""
    check(bool(block) and "LOGGER.warn" in block and "throw" not in block,
          "%s 拿不到时只 WARN、不 throw（不能把开关门提示音一起带坏）" % meth,
          "缺方向只会让 up/down 不响；缺开关门方法才是真炸")

m = re.search(r"private static Move directionOf\((.*?)\n    \}", access, re.S)
d_body = m.group(0) if m else ""
check("if (getDirection == null)" in d_body and "return Move.NONE;" in d_body
      and "catch (Throwable" in d_body,
      "directionOf：方法缺失 / 反射抛异常 → NONE（同一套降级）")

check(re.search(r"Move move,\s*\n?\s*Cabin cabin\)", access) is not None
      and "LiftView(" in access,
      "快照 LiftView 的 record 构造口带上了 move + cabin（【1.52】新增）")


# ----------------------------------------------------------------------
# 5) ★ 语义模拟 + 对照实验
# ----------------------------------------------------------------------
print("\n== 5. 语义模拟（把判据搬到 Python 里跑真实轨迹） ==")


def trig(prev, now):
    """复刻 detectMove 的判据：now != NONE && now != prev。"""
    return now != "NONE" and now != prev


def run(track):
    """track = [(prev, now), ...] 逐 tick；返回响的次数。"""
    return sum(1 for p, n in track if trig(p, n))


CASES = [
    # (名字, 轨迹, 期望次数, 说明)
    ("待命→上行（玩家在2楼按Z选3楼）", [("NONE", "UP")] * 1 + [("UP", "UP")] * 60, 1,
     "这正是用户举的例子"),
    ("待命→下行", [("NONE", "DOWN")] + [("DOWN", "DOWN")] * 60, 1, ""),
    ("纯待命（没人按）", [("NONE", "NONE")] * 60, 0, "停着就不该响"),
    ("上行到站停下", [("NONE", "UP")] + [("UP", "UP")] * 40 + [("UP", "NONE")] * 20, 1,
     "到站变 NONE 不响（那是停，不是准备走）"),
    ("中途反悔：上行途中按了更低那层", [("NONE", "UP")] + [("UP", "UP")] * 10
     + [("UP", "DOWN")] + [("DOWN", "DOWN")] * 30, 2,
     "反悔确实算「又准备走一次」，响第 2 声"),
    ("全程没有跳变", [("UP", "UP")] * 200, 0, "移动全程保持同一值 ⇒ 只响过起始那一次"),
]
for label, track, expect, note in CASES:
    got = run(track)
    check(got == expect, "模拟：%s ⇒ 响 %d 次" % (label, expect),
          ("实测 %d 次" % got) + ("（%s）" % note if note else ""))


def run_no_prev(track):
    """对照：把 `now == prev` 去掉（只看 now != NONE）会怎样。"""
    return sum(1 for _p, n in track if n != "NONE")


_track = [("NONE", "UP")] + [("UP", "UP")] * 60
_ctl = run_no_prev(_track)
check(_ctl == len(_track),
      "对照：去掉 `now == prev` 后，同一条上行轨迹会响 %d 次（= %d 个 tick 每 tick 都响）"
      % (_ctl, len(_track)),
      "⇒ 证明「方向没变就不响」这个条件是必要的，不是恒真的摆设")


# ----------------------------------------------------------------------
# 6) 打包：jar 里真的有这些
# ----------------------------------------------------------------------
print("\n== 6. 构建产物 ==")
jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")),
              key=os.path.getmtime)
if not jars:
    print("[SKIP] build/libs 下没有任何 jar，跳过打包校验（先跑 gradlew build）")
else:
    jar = jars[-1]
    print("      校验 %s（%d B）" % (os.path.relpath(jar, ROOT), os.path.getsize(jar)))
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for name in ("up", "down"):
            entry = "assets/smoothlift/sounds/audio/%s.ogg" % name
            check(entry in names, "jar 内含 %s" % entry)
            if entry in names:
                check(z.read(entry) == open(os.path.join(AUDIO_DIR, name + ".ogg"), "rb").read(),
                      "jar 内的 %s.ogg 与源码里的逐字节相同" % name)
        if "assets/smoothlift/sounds.json" in names:
            bundled = json.loads(z.read("assets/smoothlift/sounds.json").decode("utf-8"))
            check("audio/up" in bundled and "audio/down" in bundled,
                  "jar 内的 sounds.json 有 audio/up 与 audio/down",
                  "实际含 %s" % [k for k in bundled if "up" in k or "down" in k])
        else:
            check(False, "jar 内含 assets/smoothlift/sounds.json")

        cls = "smooth/lift/client/LiftChimePlayer.class"
        if cls in names:
            blob = z.read(cls)
            check(b"audio/up" in blob and b"audio/down" in blob,
                  "LiftChimePlayer.class 里有 audio/up 与 audio/down 两个字面量（确认编进去了）")
        else:
            check(False, "jar 内含 %s" % cls)

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
