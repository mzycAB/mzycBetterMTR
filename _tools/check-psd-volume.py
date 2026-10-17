# -*- coding: utf-8 -*-
"""离线校验：【1.22】屏蔽门音量可调（1~1000）+ 到站/进站音量指令 + 距离淡入淡出 + 列车内衰减。

## 本轮需求（用户原话，逐条对号）

    1 增加 pbmmusicloud / pbmmidiumloud / pbmarriveloud 指令，音量范围 1-1000；
      屏蔽门 UI 也要增加音量输入框，放在「等待秒数」输入框右边，
      两个输入框前面加「等待秒数:」「音量:」文字提示；
      屏蔽门 UI 最下面的「强制等待」和「默认音量」输入框合并进一横排。
    4 当玩家在列车里时，玩家听到的 pbmmidium / pbmarrive 的音量减少 80%。
    5 屏蔽门 UI 里的到站/进站播放音频列表太短，至少同时出现 5 个音乐。
    6 屏蔽门的声音也要有淡入淡出。

## 为什么值得单独测（「算错了 / 错位了都不报错」的东西）

1. ★ **两条同步包的读写序必须逐格一致。** 包上没有任何字段名，只有「谁先谁后」。
   给 `PsdToneAudio` 加两个字段（`midiumVolume` / `arriveVolume`）时，只要
   `buildPsdTonePacket` 的写序与客户端读序差一格，**不抛异常、不崩**，
   只是把「进站素材 id」读成了「到站音量」—— 表现为「设了音量没反应 + 素材被串成乱码 id」。
   所以本脚本把**两边的调用序列抓出来逐个比类型**（第 4 节），这比 grep 单个 token 强得多。
2. **哨兵值 -1 必须原样穿过夹取。** 1.22 引入 `PSD_TONE_VOLUME_UNSET = -1`
   （= 「这一项没单独调过，跟随共用默认音量」）。若 `clampPsdToneVolume` 顺手把它夹进
   `[1,1000]`，用户看到的是「跟随」变成了「1」（几乎静音），而且**不会报错**。
   所以断言 `-1 → -1`，并断言其余值走 `max(1, min(1000, x))`。
3. **「每 tick 现算音量」是淡入淡出成立的前提。** 原版引擎每 tick 用
   `calculateVolume(instance)` 重取一次；如果音量只在 `play()` 前算一次，
   走远以后**永远保持开播那一刻的音量**（听起来就是「不会淡出」）。所以必须
   `implements TickableSoundInstance` 且 `tick()` 里重算。
4. **一次性声音没有「播完」回调。** 循环音靠引擎停；一次性音必须自己知道什么时候退场，
   否则实例会一直挂在 `SoundEngine` 里（`tick()` 每帧跑、音量还可能变负）。
   这里用 `remainingTicks` 自减 + `isStopped()`。
5. **列车内衰减要能不崩地失败。** MTR **不是**本工程的编译依赖，只能反射
   `VehicleRidingMovement.ridingVehicleId`。反射失败必须是「不衰减」而不是抛异常
   （否则没装 MTR 的整合包里一开声音就崩）。断言字段名 + 断言失败分支存在。
6. **列表页行数是几何量，不是「感觉」。** 用户说「至少 5 个」→ 1.22-2 又点名「增加到 6 个」。
   这里把 `ROW_H / LIST_TOP / BOTTOM_RESERVE_LIST / STATUS_Y_LIST` 搬成算式复算一遍：
   可见行数 = (height - BOTTOM_RESERVE_LIST - (LIST_TOP + ROW_H)) // ROW_H
   ⇒ GUI 高 270（1080p / 缩放 4）= (270-44-62)//22 = **7**；GUI 高 240（缩放 5）= (240-44-62)//22 = **6**。
   ★ 同时断言状态行**在列表底之下**（否则状态行会盖住最后一行，等于白改）。
7. **【1.22-2】「不播」从页底挪进右列第 0 行**（用户点名），且**只有「选用」没有「删除」**。
   这里把那个 `if (fullyVisible(midiumOffRowY))` 块整段抠出来断言「含 不播/选用、不含 删除」，
   并断言右列行数口径只写在 `listRowCount()` 一处（4 处调用点全走它，否则滚动比例迟早对不上）。

开了开关 `--apply` 无副作用（本脚本只读）。
"""

import glob
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION = re.search(r'^mod_version=(.+)$', open(os.path.join(ROOT, 'gradle.properties'), encoding='utf-8').read(), re.M).group(1).strip()  # 【1.23】版本号断言不再写死

MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")
GRADLE = os.path.join(ROOT, "gradle.properties")
BUILD_GRADLE = os.path.join(ROOT, "build.gradle")

CMD = os.path.join(MAIN, "SmoothLift.java")
DATA = os.path.join(MAIN, "EscalatorSpeedData.java")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
CLI = os.path.join(CLIENT, "SmoothLiftClientEvents.java")
UI = os.path.join(CLIENT, "PsdToneSetupScreen.java")
# 【1.57】两列版式的**唯一来源**（屏蔽门二级页的几何常量已改成指向它的别名）
SOUND_LIST = os.path.join(CLIENT, "SoundListLayout.java")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")

_jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
JAR = _jars[-1] if _jars else os.path.join(ROOT, "build", "libs", "mzycBetterMTR-" + VERSION + ".jar")

EXPECTED_VER = VERSION
EXPECTED_JAR_PREFIX = "mzycBetterMTR"

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


cmd = strip_comments(read(CMD))
data = strip_comments(read(DATA))
mgr = strip_comments(read(MGR))
cli = strip_comments(read(CLI))
ui = strip_comments(read(UI))
# 【1.57】几何数字的真身；`ui` 里那些同名常量现在是别名
layout = strip_comments(read(SOUND_LIST))
player = strip_comments(read(PLAYER))
gradle = read(GRADLE)
build_gradle = read(BUILD_GRADLE)

# ======================================================================
# 1) 三个新指令 + 音量范围
# ======================================================================
print()
print("===== 1) 三个新指令 + 音量范围 1~1000 =====")

for name in ("pbmmusicloud", "pbmmidiumloud", "pbmarriveloud"):
    check('dispatcher.register(Commands.literal("%s")' % name in cmd,
          "注册了 /%s" % name)

check(re.search(r"dispatch\w*\.register\(Commands\.literal\(\"pbmmusicloud\"\)\s*"
                r"\.executes\(SmoothLift::pbmLoudShow\)", cmd) is not None,
      "★ /pbmmusicloud 与 /pbmloud **同一份数据**（都走 pbmLoudShow / pbmLoudGlobal）")
check("pbmItemLoudForce(\"-f\", \"midium\")" in cmd
      and "pbmItemLoudForce(\"-f\", \"arrive\")" in cmd,
      "两项新指令都带 -f（强制所有维度）分支")
check(re.search(r'pbmItemLoudGlobal\(context, "midium"\)', cmd) is not None
      and re.search(r'pbmItemLoudGlobal\(context, "arrive"\)', cmd) is not None,
      "两项新指令的 X / X to Y 形状都在")

# 范围常量：1 / 1000，且 arg 用的就是这对常量
check(re.search(r"AUDIO_VOLUME_MIN\s*=\s*1\s*;", data) is not None,
      "AUDIO_VOLUME_MIN = 1")
check(re.search(r"AUDIO_VOLUME_MAX\s*=\s*1000\s*;", data) is not None,
      "AUDIO_VOLUME_MAX = 1000")
check(re.search(r"private static IntegerArgumentType volumeArg\(\)\s*\{\s*"
                r"return IntegerArgumentType\.integer\(EscalatorSpeedData\.AUDIO_VOLUME_MIN,\s*"
                r"EscalatorSpeedData\.AUDIO_VOLUME_MAX\)", cmd) is not None,
      "volumeArg() = integer(1, 1000)（指令树校验拿的就是这两个常量）")
# ★ 三处「配置面」都要夹：数据层 / 指令树 / UI。UI 那处在第 5 节查。
check(re.search(r"HELP_VOLUME_MIN\s*=\s*AUDIO_VOLUME_MIN\s*;", data) is not None
      and re.search(r"HELP_VOLUME_MAX\s*=\s*AUDIO_VOLUME_MAX\s*;", data) is not None,
      "HELP_VOLUME_MIN/MAX 直接引用 AUDIO_VOLUME_MIN/MAX（不会两套数各走各的）")
check(re.search(r"DEFAULT_PSD_HELP_VOLUME\s*=\s*DEFAULT_HELP_VOLUME\s*;", data) is not None,
      "DEFAULT_PSD_HELP_VOLUME 复用共用默认（不是又一个新数）")

# ======================================================================
# 2) 两条新通道 + 服务端接收器载荷
# ======================================================================
print()
print("===== 2) 两条新通道（到站音量 / 进站音量） =====")

# forge 版：这两个通道对应独立包类（SetPsdMidiumLoudPacket / SetPsdArriveLoudPacket），
# 其 handle 在服务端落 setter —— 不再有 fabric 的 registerGlobalReceiver + channel 常量。
for pkt_cls, method in (
        ("SetPsdMidiumLoudPacket", "setDoorPsdMidiumVolume"),
        ("SetPsdArriveLoudPacket", "setDoorPsdArriveVolume")):
    pkt = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", pkt_cls + ".java")
    check(os.path.isfile(pkt), "%s 包类存在" % pkt_cls)
    if os.path.isfile(pkt):
        pkt_src = open(pkt, encoding="utf-8").read()
        check(method in pkt_src, "%s.handle 落到 %s(...)" % (pkt_cls, method))

# forge 包类载荷形状：两个 loud 包都是 key(long) → volume(VarInt)
for pkt_cls in ("SetPsdMidiumLoudPacket", "SetPsdArriveLoudPacket"):
    pkt_path = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", pkt_cls + ".java")
    if not os.path.isfile(pkt_path):
        continue
    pkt_src = open(pkt_path, encoding="utf-8").read()
    check(re.search(r"buf\.writeLong\(pkt\.key\);\s*\n.*buf\.writeVarInt\(pkt\.volume\)", pkt_src, flags=re.S) is not None,
          "%s 载荷形状是 key(long) → volume(VarInt)" % pkt_cls)
    check("EscalatorSpeedManager.syncPsdToneToAll(player.server)" in pkt_src
          or "syncPsdToneToAll(player.server)" in pkt_src,
          "%s.handle 补发了「按门设置」那条包" % pkt_cls)

# ======================================================================
# 3) 数据层：20 字段 record + 哨兵 + 夹取 + NBT
# ======================================================================
print()
print("===== 3) 数据层：PsdToneAudio 20 字段 + 哨兵 -1 =====")

m = re.search(r"public record PsdToneAudio\(([^)]*)\)", data, flags=re.S)
check(m is not None, "找得到 PsdToneAudio record 声明")
if m:
    params = [p.strip() for p in m.group(1).split(",") if p.strip()]
    check(len(params) == 20, "★ PsdToneAudio 是 20 字段（素材 2 + 开关 3 + 音量/秒数 7 + 素材/秒数 4 + 音量 2 + narrate 系列 4）",
          "得到 %d：%s" % (len(params), ", ".join(params)))
    check(params[-4:] == ["Integer narrate", "Integer narrateSeconds",
                          "Integer midiumNarrate", "Integer midiumNarrateSeconds"],
          "★ narrate 系列四格在末尾（追加式改包的标准姿势：老位置一个都没动）",
          "得到 %s" % params[-4:])
    check(params[10] == "String midium" and params[11] == "Integer midiumWaitSeconds"
          and params[12] == "Integer midiumVolume",
          "★ midiumVolume 紧跟 midiumWaitSeconds（同一件事的三个字段聚在一起，别散开）",
          "得到 %s" % params[10:13])
    check(all("Integer " + n in params for n in
              ("volume", "openVolume", "closeVolume", "openWaitSeconds", "closeWaitSeconds",
               "midiumWaitSeconds", "midiumVolume", "arriveSeconds", "arriveVolume",
               "narrateSeconds", "midiumNarrateSeconds")),
          "11 个「可空盒装」音量/秒数字段都在（null = 跟维度默认）")

check(re.search(r"PSD_TONE_VOLUME_UNSET\s*=\s*LIFT_TONE_VOLUME_UNSET\s*;", data) is not None,
      "PSD_TONE_VOLUME_UNSET 复用 LIFT_TONE_VOLUME_UNSET（同一套哨兵语义）")
check(re.search(r"LIFT_TONE_VOLUME_UNSET\s*=\s*-1\s*;", data) is not None,
      "哨兵值 = -1")
check(re.search(r"public int defaultPsdMidiumVolume\s*=\s*PSD_TONE_VOLUME_UNSET\s*;", data)
      is not None
      and re.search(r"public int defaultPsdArriveVolume\s*=\s*PSD_TONE_VOLUME_UNSET\s*;", data)
      is not None,
      "两个新维度默认字段初值 = 哨兵（没调过 ⇒ 跟共用默认）")

# 夹取：哨兵原样穿过；其余 max(1, min(1000, x))  —— 用 Python 复算对照
mclamp = re.search(r"public static int clampPsdToneVolume\(int volume\)\s*\{(.*?)\n    \}",
                   data, flags=re.S)
check(mclamp is not None, "找得到 clampPsdToneVolume")
if mclamp:
    body = mclamp.group(1)
    check(re.search(r"if \(volume == PSD_TONE_VOLUME_UNSET\)\s*\{\s*return PSD_TONE_VOLUME_UNSET",
                    body) is not None,
          "★ 哨兵 -1 **原样放行**（不夹进 [1,1000] —— 夹了用户看到的是「跟随」变成 1）")
    check("Math.max(HELP_VOLUME_MIN" in body and "Math.min(HELP_VOLUME_MAX" in body,
          "其余值走 max(MIN, min(MAX, x))")


def clamp_psd(x):
    """与 Java 侧逐字同构。"""
    if x == -1:
        return -1
    return max(1, min(1000, x))


clamp_cases = [(-5, 1), (0, 1), (1, 1), (100, 100), (1000, 1000), (1001, 1000), (99999, 1000)]
ok = all(clamp_psd(a) == b for a, b in clamp_cases)
check(ok, "夹取复算：-5→1 / 0→1 / 1→1 / 100→100 / 1000→1000 / 1001→1000 / 99999→1000",
      "得到 %s" % [(a, clamp_psd(a)) for a, _ in clamp_cases])
check(clamp_psd(-1) == -1, "★ 夹取复算：-1 → -1（哨兵穿过）")

print()
print("----- NBT 往返（存档里的键名） -----")
for key in ("defaultPsdMidiumVolume", "defaultPsdArriveVolume"):
    check('tag.getInt("%s")' % key in data and 'tag.putInt("%s", %s)' % (key, key) in data,
          "维度默认音量 %s 读写成对" % key)
for key in ("midiumVolume", "arriveVolume"):
    check('optInt(entry, "%s")' % key in data and 'putOptInt(t, "%s"' % key in data,
          "按门覆盖 %s 读写成对（可选：不吃旧存档）" % key)
check(re.search(r"data\.defaultPsdMidiumVolume\s*=\s*clampPsdToneVolume\(", data) is not None
      and re.search(r"data\.defaultPsdArriveVolume\s*=\s*clampPsdToneVolume\(", data) is not None,
      "★ 读存档时也夹一次（坏存档 / 手改 NBT 绕过指令树那层 arg 校验）")

# ======================================================================
# 4) ★★ 核心不变式：两条包的读写序必须逐格一致
# ======================================================================
print()
print("===== 4) ★★ 包读写序逐格一致（错位不报错，只会把值串到别的字段） =====")

# 【1.29 / 裸负载】包类只剩 writeByteArray / readByteArray 两行；真正的字节序
#   在**服务端 builder**（buildPsdTonePacket / buildPsdChimePacket）和**客户端 handle**
#   （`FriendlyByteBuf data = ...` 之后那一整段）里。所以下面把这两处的调用序列
#   抓出来逐个比类型、比名字 —— 这比 grep 单个 token 强得多。
PKT_DIR = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network")


def _handle_read(src, marker):
    """截取 handle 里 data 声明之后的全部读取段（到 lambda 收尾 `});`）。"""
    m = re.search(re.escape(marker) + r"(.*?)\n        \}\);", src, flags=re.S)
    return m.group(1) if m else ""


# ---- 4a) 按门包（PsdToneSyncPacket）：buildPsdTonePacket 写序 vs handle 读序 ----
WRITE_DOOR_OPT = re.compile(r"writeDoorOpt(Bool|Int|String)\(buf, tone\.(\w+)\(\)\)")
READ_DOOR_OPT = re.compile(r"(?:Boolean|Integer|String) (\w+) = EscalatorSpeedManager\.readDoorOpt(Bool|Int|String)\(data\)")

pkt_tones_path = os.path.join(PKT_DIR, "PsdToneSyncPacket.java")
pkt_tones = open(pkt_tones_path, encoding="utf-8").read() if os.path.isfile(pkt_tones_path) else ""

# 写侧：buildPsdTonePacket 整段 + 每扇门循环体
m2w = re.search(r"private static FriendlyByteBuf buildPsdTonePacket\(ServerLevel level\)\s*\{(.*?)\n    \}",
                mgr, flags=re.S)
tone_wloop = (re.search(r"for \(Map\.Entry<Long, EscalatorSpeedData\.PsdToneAudio> e : data\.psdToneAudio\.entrySet\(\)\)\s*\{(.*?)\n        \}",
                        m2w.group(1), flags=re.S) if m2w else None)
# 读侧：handle 里 data 声明之后的读取段 + 每扇门循环体
tone_read = _handle_read(pkt_tones,
                         "FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));")
tone_rloop = (re.search(r"for \(int i = 0; i < n; i\+\+\)\s*\{(.*?)\n            \}",
                        tone_read, flags=re.S) if tone_read else None)
check(m2w is not None, "抓得到 buildPsdTonePacket（写侧）")
check(tone_read != "", "抓得到 PsdToneSyncPacket.handle 的读取段（读侧）")
if m2w and tone_read:
    w = WRITE_DOOR_OPT.findall(tone_wloop.group(1)) if tone_wloop else []
    r = READ_DOOR_OPT.findall(tone_rloop.group(1)) if tone_rloop else []
    print("        写序：%s" % ", ".join("%s:%s" % (n, t) for t, n in w))
    print("        读序：%s" % ", ".join("%s:%s" % (n, t) for n, t in r))
    check(len(w) == 18 and len(r) == 18,
          "★ 每扇门 18 格 writeDoorOpt/readDoorOpt（20 字段 record − open/close 两个直接 Utf）",
          "写在 %d / 读在 %d" % (len(w), len(r)))
    check(len(w) == len(r),
          "★ 读写格数相同（%d vs %d）—— 差一格就是静默错位" % (len(w), len(r)))
    check([t for t, _ in w] == [t for _, t in r],
          "★★ 读写**类型序列**逐格一致（Bool/Int/String 对位）",
          "写在 %s / 读在 %s" % ([t for t, _ in w], [t for _, t in r]))
    check([n for _, n in w] == [n for n, _ in r],
          "★★ 读写**字段名序列**逐格一致（连名字都对上，不只是类型）",
          "写在 %s / 读在 %s" % ([n for _, n in w], [n for n, _ in r]))
    check([n for _, n in w][-4:] == ["narrate", "narrateSeconds", "midiumNarrate", "midiumNarrateSeconds"],
          "★ narrate 系列四格在按门包**末尾**（追加式改包的标准姿势：老格子一个没动）",
          "末尾四格：%s" % [n for _, n in w][-4:])
    check(all("Round" not in n for _, n in w),
          "★ 范围**不在**按门包（维度级配置，与 /pbmround 同一层，见 4b）",
          "得到 %s" % [n for _, n in w])
    # 每扇门的头三格：key(Long) → open(Utf128) → close(Utf128)，两边同型
    whead = re.findall(r"buf\.write(Long|Utf)\(", tone_wloop.group(1))
    rhead = re.findall(r"(?:long|String) \w+ = data\.read(Long|Utf)\(", tone_rloop.group(1))
    check(whead == ["Long", "Utf", "Utf"] and rhead == ["Long", "Utf", "Utf"],
          "★ 每扇门头三格同型：key(Long) → open(Utf) → close(Utf)",
          "写在 %s / 读在 %s" % (whead, rhead))
    # 【10-03】门串级 runSettings 段：条数 → (锚点 Long, 平台 Long, 发车等待 OptInt) × N
    check(re.search(r"buf\.writeVarInt\(data\.psdRunSettings\.size\(\)\)", m2w.group(1)) is not None
          and re.search(r"int runCount = data\.readVarInt\(\)", tone_read) is not None,
          "★ runSettings 段头同型：写 psdRunSettings.size() / 读 runCount（都是 VarInt）")
    m_runw = re.search(r"for \(Map\.Entry<Long, EscalatorSpeedData\.PsdRunSetting> e : data\.psdRunSettings\.entrySet\(\)\)\s*\{(.*?)\n        \}",
                       m2w.group(1), flags=re.S)
    m_runr = re.search(r"for \(int i = 0; i < runCount; i\+\+\)\s*\{(.*?)\n            \}",
                       tone_read, flags=re.S)
    if m_runw and m_runr:
        wrun = (len(re.findall(r"buf\.writeLong\(", m_runw.group(1))),
                re.findall(r"writeDoorOptInt\(buf, v\.(\w+)\(\)\)", m_runw.group(1)))
        rrun = (len(re.findall(r"long \w+ = data\.readLong\(\)", m_runr.group(1))),
                re.findall(r"Integer (\w+) = EscalatorSpeedManager\.readDoorOptInt\(data\)", m_runr.group(1)))
        check(wrun == (2, ["departDelaySeconds"]) and rrun == (2, ["departDelaySeconds"]),
              "★ runSettings 每串同型：锚点(Long) → 平台(Long) → 发车等待(OptInt)，名字 departDelaySeconds 对齐",
              "写在 %s / 读在 %s" % (wrun, rrun))
    else:
        check(False, "抓得到 runSettings 段的写循环 / 读循环")
    # 注意：**不许**在这里断言「record 字段序 == 包字段序」—— 两者可以合法地不同：
    #   record 把同一件事的三个字段聚在一起（midium / midiumWaitSeconds / midiumVolume），
    #   而包为了「只追加不动老格子」把新字段放在最末。
    #   真正要钉的是「服务端写第 i 格的东西，客户端读第 i 格、并喂给**同名**的 record 参数」，
    #   那条由下面的 4d（构造实参逐个 == record 参数名）钉住。

# ---- 4b) 维度包（PsdChimeSyncPacket）：buildPsdChimePacket 写序 vs handle 读序 ----
pkt_chime_path = os.path.join(PKT_DIR, "PsdChimeSyncPacket.java")
pkt_chime = open(pkt_chime_path, encoding="utf-8").read() if os.path.isfile(pkt_chime_path) else ""

m3w = re.search(r"private static FriendlyByteBuf buildPsdChimePacket\(ServerLevel level\)\s*\{(.*?)\n    \}",
                mgr, flags=re.S)
chime_read = _handle_read(pkt_chime,
                          "FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));")
check(m3w is not None, "抓得到 buildPsdChimePacket（写侧）")
check(chime_read != "", "抓得到 PsdChimeSyncPacket.handle 的读取段（读侧）")
if m3w and chime_read:
    W_TOKEN = [("Utf", re.compile(r"buf\.writeUtf\(")), ("Boolean", re.compile(r"buf\.writeBoolean\(")),
               ("VarInt", re.compile(r"buf\.writeVarInt\(")), ("Long", re.compile(r"buf\.writeLong\(")),
               ("NarrTexts", re.compile(r"writeNarrateUserTexts\(buf"))]
    R_TOKEN = [("Utf", re.compile(r"data\.readUtf\(")), ("Boolean", re.compile(r"data\.readBoolean\(")),
               ("VarInt", re.compile(r"data\.readVarInt\(")), ("Long", re.compile(r"data\.readLong\(")),
               ("NarrTexts", re.compile(r"readNarrateUserTexts\(data"))]

    def token_seq(body, table):
        out = []
        for ln in body.split("\n"):
            for key, rx in table:
                if rx.search(ln):
                    out.append(key)
                    break
        return out

    w3 = token_seq(m3w.group(1), W_TOKEN)
    r3 = token_seq(chime_read, R_TOKEN)
    print("        写序(%d)：%s" % (len(w3), ", ".join(w3)))
    print("        读序(%d)：%s" % (len(r3), ", ".join(r3)))
    check(w3 == r3, "★★ 维度包读写**整条序列**逐格一致（含两条 NarrTexts 与末尾 departDelay）",
          "写在 %s / 读在 %s" % (w3, r3))
    check(len(w3) == 29,
          "★ 维度包 29 格 = 27 标量 + 2 组讲述人文字（1.20 起逐版追加，末尾不动老格子）",
          "得到 %d" % len(w3))
    check(w3[26:28] == ["NarrTexts", "NarrTexts"] and w3[-1] == "VarInt",
          "★ 结构：26 格标量 → 两条 NarrTexts → departDelaySeconds(VarInt) 收尾",
          "第 27/28/29 格：%s" % w3[26:])
    wlines = [ln.strip() for ln in m3w.group(1).split("\n") if "buf.write" in ln]
    check(wlines and "defaultPsdDepartDelaySeconds" in wlines[-1],
          "★ 维度包写入的**最后一格**就是 defaultPsdDepartDelaySeconds（10-04 修 2 追加在真正末尾）",
          "最后一格：%s" % (wlines[-1] if wlines else "?"))
    check(re.search(r"int departDelaySeconds = data\.readVarInt\(\)", chime_read) is not None,
          "★ 读侧末尾同序读到 departDelaySeconds（与写侧对称，VarInt 原码往返）")

# ---- 4c) applyClientPsdChime 的 27 入参 == 读端 27 个局部变量名（dimId→dimension 外逐格同名） ----
m4 = re.search(r"public static void applyClientPsdChime\((.*?)\)\s*\{", mgr, flags=re.S)
check(m4 is not None, "找得到 applyClientPsdChime 签名")
if m4:
    params4 = [p.strip() for p in m4.group(1).split(",") if p.strip()]
    check(len(params4) == 27,
          "★ applyClientPsdChime 27 入参 = 20 字段 record − open/close 2 + dimension/enabled + 9 个逐版追加",
          "得到 %d：%s" % (len(params4), ", ".join(params4)))
    check(params4[-1] == "int departDelaySeconds" and params4[-2] == "int midiumNarrateSeconds",
          "★ 末尾两参 = midiumNarrateSeconds / departDelaySeconds（与读段、与包尾三处一致）",
          "得到 %s" % params4[-2:])
    if chime_read:
        rnames = re.findall(r"(\w+) = data\.read(?:Utf|Boolean|VarInt|Long)\(", chime_read)
        check(rnames and rnames[0] == "dimId" and rnames[1:] == [p.split()[-1] for p in params4[1:]],
              "★★ 读端 27 个局部变量名 == applyClientPsdChime 入参名（dimId→dimension 外逐格同名）",
              "读=%s\n             参=%s" % (rnames, [p.split()[-1] for p in params4[1:]]))

# ---- 4d) 客户端 handle 构造实参 == record 参数名（逐个，含名字） ----
m5 = re.search(r"new EscalatorSpeedData\.PsdToneAudio\((.*?)\)\);", pkt_tones, flags=re.S)
check(m5 is not None, "找得到 PsdToneSyncPacket.handle 构造 PsdToneAudio 的地方")
if m5:
    args5 = [a.strip() for a in m5.group(1).split(",") if a.strip()]
    check(len(args5) == 20, "★ 构造实参 = 20 个", "得到 %d" % len(args5))
    if m:
        rec_names = [p.strip().split()[-1] for p in params]
        # ★★ 这是「服务端写 → 客户端读 → 喂进 record」这条链的最后一段：
        #    读端局部变量名已在 4a 里与写端序列逐个对齐；这里再保证**每个局部变量
        #    都被喂给了同名的 record 参数**。两段合起来 ⇒ 第 i 格的值一定落进
        #    名为「第 i 格」的那个字段，不会串位。
        check(args5 == rec_names,
              "★★ 构造实参逐个 == record 参数名（顺序 + 名字全对上）",
              "实参=%s\n             record=%s" % (args5, rec_names))
        check(set(args5) == set(rec_names) and len(set(args5)) == 20,
              "20 个实参/参数名互不重复（重名会让上面那条断言失去分辨力）")
    check(re.search(r"new EscalatorSpeedData\.PsdRunSetting\(\s*departDelaySeconds,", pkt_tones, flags=re.S) is not None,
          "★ runSettings 构造第一个实参 = departDelaySeconds（与 4a run 段同名）")

# ======================================================================
# 5) UI：音量框 + 标签 + 底部合并 + 列表 ≥5 行
# ======================================================================
print()
print("===== 5) 石斧 UI：音量输入框 / 标签 / 底部合并 / 列表 ≥ 5 行 =====")

check(re.search(r'ROW_WAIT_LABEL\s*=\s*"等待秒数:"\s*;', ui) is not None,
      '标签 ROW_WAIT_LABEL = "等待秒数:"')
check(re.search(r'ROW_LOUD_LABEL\s*=\s*"音量:"\s*;', ui) is not None,
      '标签 ROW_LOUD_LABEL = "音量:"')
for fld in ("midiumLoudInput", "arriveLoudInput"):
    check(re.search(r"private EditBox %s\s*;" % fld, ui) is not None, "字段 %s 存在" % fld)
    check(re.search(r"%s = new EditBox\(this\.font, rowLoudBoxX\(cx\)" % fld, ui) is not None,
          "%s 用 rowLoudBoxX(...) 定位（= 在「等待秒数」框右边）" % fld)
    check(re.search(r"addRenderableWidget\(%s\);" % fld, ui) is not None,
          "%s 真的加进了界面" % fld)
check(ui.count("Component.literal(ROW_LOUD_LABEL)") >= 2,
      "两个音量框都带「音量:」提示")
check(ui.count("Component.literal(ROW_WAIT_LABEL)") >= 2,
      "两个秒数框都带「等待秒数:」提示")

# 位置：音量框 x 必须在秒数框 x 的右边（由 label 宽度推出来，不是硬编码拍的）
m5 = re.search(r"private int rowLoudBoxX\(int cx\)\s*\{\s*return (.*?);", ui, flags=re.S)
check(m5 is not None and "rowLoudLabelX(cx)" in m5.group(1)
      and "width(ROW_LOUD_LABEL)" in m5.group(1),
      "★ rowLoudBoxX = rowLoudLabelX + 「音量:」自身宽度（跟着字体走，不是写死偏移）")
m6 = re.search(r"private int rowLoudLabelX\(int cx\)\s*\{\s*return (.*?);", ui, flags=re.S)
check(m6 is not None and "rowWaitBoxX(cx)" in m6.group(1) and "ROW_BOX_W" in m6.group(1),
      "★ rowLoudLabelX = rowWaitBoxX + ROW_BOX_W + 间隔（**先有秒数框、右边才是音量块**）",
      "得到 %s" % (m6.group(1).strip() if m6 else "?"))
check(re.search(r"private int rowWaitBoxX\(int cx\)\s*\{\s*return cx \+ 4 \+ this\.font\.width\("
                r"ROW_WAIT_LABEL\) \+ 2;", ui) is not None,
      "★ rowWaitBoxX 让开「等待秒数:」的宽度（标签不会压到框）")

# 【1.23】底部「默认音量」「强制等待」两框已按点名删除（强制等待上移到「关门提示」行等待框）
check("defaultVolumeInput = new EditBox" not in ui and "closeWaitInput = new EditBox" not in ui,
      "★【1.23】底部两框已删（强制等待 = 关门行右侧等待秒数框；默认音量走 /pbmloud 指令）")
check(re.search(r"private int bottomWaitLabelX\(int cx\)\s*\{\s*return bottomLoudBoxX\(cx\) \+ "
                r"BOTTOM_LOUD_W \+ 10;", ui) is not None
      and re.search(r"private int bottomWaitBoxX\(int cx\)\s*\{\s*return bottomWaitLabelX\(cx\) \+ "
                    r"this\.font\.width\(\"强制等待\"\) \+ 2;", ui) is not None,
      "★ 秒数框紧跟在音量框右边（x 由音量框宽度 + 「强制等待」标签宽度推出）")
check(re.search(r"BOTTOM_LOUD_W\s*=\s*56\s*;", ui) is not None
      and re.search(r"BOTTOM_WAIT_W\s*=\s*52\s*;", ui) is not None,
      "底部两框宽度 = 56 / 52")

# 落地：值变了才发包，非法值只提示
check(re.search(r"if \(midiumLoudInput != null\)\s*\{.*?sendSetMidiumLoud\(v\)", ui, flags=re.S)
      is not None
      and re.search(r"if \(arriveLoudInput != null\)\s*\{.*?sendSetArriveLoud\(v\)", ui, flags=re.S)
      is not None,
      "主界面输入框在 applyMainInputs 里落地（两个新框都在）")
check("parseVolume(midiumLoudInput.getValue(), false)" in ui
      and "parseVolume(arriveLoudInput.getValue(), false)" in ui,
      "两个新框都走同一个 parseVolume（范围校验只有一处）")

# ---- 列表页行数：把几何量搬成算式复算 ----
print()
print("----- 列表页可见行数（几何量复算，不靠肉眼） -----")
m7 = re.search(r"BOTTOM_RESERVE_LIST\s*=\s*(\d+)\s*;", layout)
m8 = re.search(r"ROW_H\s*=\s*(\d+)\s*;", layout)
m9 = re.search(r"LIST_TOP\s*=\s*(\d+)\s*;", layout)
m10 = re.search(r"STATUS_Y_LIST\s*=\s*(-?\d+)\s*;", layout)
check(all(x is not None for x in (m7, m8, m9, m10)), "取到 4 个版式常量")
# 【1.57】4 个数字必须**只存在于 SoundListLayout**；屏蔽门界面里是别名（否则将来只改一边）
check(re.search(r"\bROW_H\s*=\s*SoundListLayout\.ROW_H\s*;", ui) is not None
      and re.search(r"\bBOTTOM_RESERVE_LIST\s*=\s*SoundListLayout\.BOTTOM_RESERVE_LIST\s*;", ui)
      is not None
      and re.search(r"\bSTATUS_Y_LIST\s*=\s*SoundListLayout\.STATUS_Y_LIST\s*;", ui) is not None
      and re.search(r"\bLIST_TOP\s*=\s*SoundListLayout\.LIST_TOP\s*;", ui) is not None,
      "★ 这四个几何常量在屏蔽门界面里都是别名（数字只许写在 SoundListLayout）")
if all(x is not None for x in (m7, m8, m9, m10)):
    ROW_H, LIST_TOP = int(m8.group(1)), int(m9.group(1))
    RESERVE_LIST, STATUS_LIST = int(m7.group(1)), int(m10.group(1))
    print("        ROW_H=%d LIST_TOP=%d BOTTOM_RESERVE_LIST=%d STATUS_Y_LIST=%d"
          % (ROW_H, LIST_TOP, RESERVE_LIST, STATUS_LIST))
    # listTop = LIST_TOP + ROW_H（列表页上方留一行表头）；listBottom = height - reserve
    for height in (270, 240):
        visible = height - RESERVE_LIST - (LIST_TOP + ROW_H)
        rows = visible // ROW_H
        check(rows >= 6, "GUI 高 %d 时列表可见 %d 行（【1.22-2】用户点名 ≥6）"
                         % (height, rows))
    # 状态行必须在列表底之下
    height = 270
    list_bottom = height - RESERVE_LIST
    status_y = height + STATUS_LIST
    check(status_y > list_bottom,
          "★ 状态行 y=%d 在列表底 y=%d 之下（否则盖住最后一行，等于白改）"
          % (status_y, list_bottom))
    check(re.search(r"listTop = listPage \? LIST_TOP \+ ROW_H : LIST_TOP;", ui) is not None,
          "★【1.23】所有列表页（开门/关门/站台/进站）整体下移一行（给表头让位）")
    check(re.search(r"boolean listPage = page >= 1;", ui) is not None
      and re.search(r"int reserve = listPage \? BOTTOM_RESERVE_LIST : BOTTOM_RESERVE;", ui) is not None,
          "★【1.23】列表页（2/3/4）用自己那套预留（不共用主界面的 BOTTOM_RESERVE）")

check('? "" : ""' not in ui and '? "" : ""' not in cmd,
      "没有「? \"\" : \"\"」这种退化三目残留（去括号留下的空壳）")

# ---- 【1.22-2】「不播」从页底挪进右列第 0 行（只有 选用、没有 删除） ----
print()
print("----- 【1.22-2】右列第 0 行 = 「不播」（只有 选用 / 没有 删除） -----")
check(re.search(r"RIGHT_COL_FIRST_STORED_ROW\s*=\s*1\s*;", layout) is not None,
      "★ 常量 RIGHT_COL_FIRST_STORED_ROW = 1（右列第 0 行让给「不播」，存档从第 1 行起）"
      "  —— 断言的是 SoundListLayout 那份唯一来源")
check(re.search(r"\bRIGHT_COL_FIRST_STORED_ROW\s*=\s*SoundListLayout\.RIGHT_COL_FIRST_STORED_ROW\s*;",
                ui) is not None,
      "★ 屏蔽门界面里的 RIGHT_COL_FIRST_STORED_ROW 是别名（数字 1 只许写在 SoundListLayout）")
check(re.search(r"private int listRowCount\(\)\s*\{\s*return Math\.max\(pending\.size\(\), "
                r"stored\.size\(\) \+ rightStoredStartRow\);", ui) is not None,
      "★★ 右列起始行只写在 listRowCount() 一处（各页 build 先设 rightStoredStartRow）")
check(ui.count("int rowCount = listRowCount();") == 6,
      "★【1.23】listRowCount() 调用点 = 6（到站/进站 rebuild+滚动条 4 处 + 开门/关门 2 处）")
check(ui.count("rowY(i + RIGHT_COL_FIRST_STORED_ROW)") == 2,
      "两页的**存档项**都画在 rowY(i + 1)（第 0 行留给「不播」）")
check(ui.count("int midiumOffRowY = rowY(0);") == 1
      and ui.count("int arriveOffRowY = rowY(0);") == 1,
      "到站 / 进站两页各有且仅有 1 个「不播」行（rowY(0)）")
check("MIDIUM_Y" not in ui,
      "★ 页底「不播」用的 MIDIUM_Y 常量已无引用（留着会误导下一个改版式的人）")

# 抠出「不播」那一整块：含 不播 + 选用，**不含 删除**
#   （ui 已剥注释 ⇒ 连注释里写的「没有『删除』」也不会污染这条断言）
for _fn, _off, _pick in (("buildArrivalPage", "midiumOffRowY", "pickMidium"),
                         ("buildArrivePage", "arriveOffRowY", "pickArrive")):
    _mb = re.search(r"int %s = rowY\(0\);(.*?)\n        \}" % _off, ui, re.S)
    _body = _mb.group(1) if _mb else ""
    check(bool(_body) and '"不播"' in _body, "%s：右列第 0 行是「不播」" % _fn)
    check(bool(_body) and 'Component.literal("选用")' in _body
          and _pick in _body,
          "★ %s：「不播」那行有「选用」（点=把该项设成不播）" % _fn)
    check(bool(_body) and "删除" not in _body,
          "★★ %s：「不播」那行**没有「删除」**（它不是一个音频文件，删无可删）—— "
          "这是它与下面每一行的唯一结构差别" % _fn)
    check(bool(_body) and "rightColX()" in _body,
          "%s：「不播」画在**右列**（已导入存档那一列），不是左列" % _fn)
check(ui.count('Component.literal("删除")') >= 2,
      "下面每一行（存档项）的「删除」键都还在（只挪走了「不播」，没顺手删功能）")

# ======================================================================
# 6) 播放端：距离淡入淡出 + 每 tick 现算 + 列车内衰减 + 自动退场
# ======================================================================
print()
print("===== 6) 播放端：淡入淡出 / 每 tick 现算 / 列车内 -80% =====")

check("import net.minecraft.client.resources.sounds.TickableSoundInstance;" in read(PLAYER),
      "import 了 TickableSoundInstance")
check(re.search(r"implements TickableSoundInstance, GainManagedSound", player) is not None,
      "★ PsdMusicInstance implements TickableSoundInstance（原版引擎每 tick 才会重取音量）")
check(re.search(r"public void tick\(\)\s*\{.*?refreshVolume\(Minecraft\.getInstance\(\)\)", player,
                flags=re.S) is not None,
      "★★ tick() 里**现算**音量（淡入淡出的成立前提）")
check(re.search(r"void refreshVolume\(Minecraft mc\)", player) is not None,
      "有 refreshVolume(mc)")
check(re.search(r"v = gain\(dxz, dy, roundKind\) \* baseVolume;", player) is not None
      and re.search(r"v = gain\(d, roundKind\) \* baseVolume;", player) is not None,
      "★★【1.23】现算 = gain(距离, **这条声音自己的类别**) × baseVolume"
      "（不再是三类共用提示音那一份范围）")

# gain 公式复算：【1.25 起改为线性】1 - d/r（旧：平方 (1-d/r)^2），d >= r ⇒ 0，单调不增；
# ★【1.23】范围按类别取；★★【09-29】播报类拆双维（水平/垂直各一份，见 /jsr round AAA BBB）。
mg = re.search(r"private static float gain\(double distance, int roundKind\)\s*\{(.*?)\n    \}",
               player, flags=re.S)
check(mg is not None, "★★【1.23】找得到 gain(distance, roundKind) —— 提示音单维，范围**按类别**传进去的")
check(re.search(r"private static float gain\(double distance\)\s*\{", player) is None,
      "★★ 单参重载 gain(distance) **故意不存在** —— 逼每个调用点写清「我算哪一类」，"
      "而不是顺手用提示音那一份（本项目栽过「调用点漏改」）")
if mg:
    gbody = mg.group(1)
    check(re.search(r"double round = roundXzFor\(roundKind\);", gbody) is not None,
          "★ 单维范围由 roundXzFor(roundKind) 现取（改完指令下一 tick 就生效，不存快照）")
    check(re.search(r"if \(!\(distance < round\)\)\s*\{\s*return 0\.0f;", gbody) is not None,
          "★ d >= 范围 ⇒ 0（走远无声；用 !(d < r) 而不是 d > r，NaN 也归到 0）")
    check("1.0 - distance / round" in gbody and "f * f" not in gbody,
          "★★【1.25】公式 = **线性** 1 - d/r（**不是**扶梯那套平方掉块曲线）"
          " —— 屏蔽门是一串同声、要整串都听得见；平方曲线 8 格只剩 25%、15 格 0.4%")
    check("Math.max(0.0" in gbody,
          "★ 线性值再兜一次 Math.max(0, ...)（d 贴近范围时不出现 -0.0 / 负音量）")
# ★★【09-29】双维重载：播报两类走它 —— 水平/垂直各一份范围，任一越界即 0，两维取较小增益
mg2 = re.search(r"private static float gain\(double distanceXz, double distanceY, int roundKind\)"
                r"\s*\{(.*?)\n    \}", player, flags=re.S)
check(mg2 is not None,
      "★★【09-29】找得到 gain(distanceXz, distanceY, roundKind) —— 站台广播双维口径")
if mg2:
    gb2 = mg2.group(1)
    check(re.search(r"double roundXz = roundXzFor\(roundKind\);", gb2) is not None
          and re.search(r"double roundY = roundYFor\(roundKind\);", gb2) is not None,
          "★★ 双维范围 = roundXzFor / roundYFor 各现取（水平 / 垂直两份）")
    check(re.search(r"if \(!\(distanceXz < roundXz\) \|\| !\(distanceY < roundY\)\)\s*\{\s*return 0\.0f;",
                    gb2) is not None,
          "★ 任一方向越界即 0（与 /jsr round AAA BBB 同构）")
    check(re.search(r"Math\.min\(1\.0 - distanceXz / roundXz, 1\.0 - distanceY / roundY\)", gb2)
          is not None,
          "★★ 两维各自线性衰减、取**较小**那个（越贴边的那一维先行淡出）")
# 三类的身份 + roundXzFor / roundYFor 的双 switch（【09-29】范围拆水平/垂直两份）
check(re.search(r"private static final int ROUND_TONE = 0;", player) is not None
      and re.search(r"private static final int ROUND_MIDIUM = 1;", player) is not None
      and re.search(r"private static final int ROUND_ARRIVE = 2;", player) is not None,
      "【1.23】三类范围的身份常量 ROUND_TONE / ROUND_MIDIUM / ROUND_ARRIVE")
check(re.search(r"return switch \(roundKind\) \{\s*case ROUND_MIDIUM -> cachedMidiumRoundXz;"
                r"\s*case ROUND_ARRIVE -> cachedArriveRoundXz;\s*default -> cachedRoundXz;\s*\};",
                player) is not None,
      "★★ roundXzFor 把两类报站音分别落到 cachedMidiumRoundXz / cachedArriveRoundXz（水平）"
      "（都落回 cachedRoundXz ⇒ 三条 range 指令里有两条是死配置）")
check(re.search(r"return switch \(roundKind\) \{\s*case ROUND_MIDIUM -> cachedMidiumRoundY;"
                r"\s*case ROUND_ARRIVE -> cachedArriveRoundY;\s*default -> cachedRoundY;\s*\};",
                player) is not None,
      "★★ roundYFor 把两类报站音分别落到 cachedMidiumRoundY / cachedArriveRoundY（垂直）")
# ★ 六份范围缓存的声明（三类 × 水平/垂直，各带默认值 —— 换世界后第一 tick 就用这个范围）
check(re.search(r"private static double cachedRoundXz = EscalatorSpeedData\.DEFAULT_PSD_HELP_ROUND_XZ;",
                player) is not None
      and re.search(r"private static double cachedRoundY = EscalatorSpeedData\.DEFAULT_PSD_HELP_ROUND_Y;",
                    player) is not None
      and re.search(r"private static double cachedMidiumRoundXz = "
                    r"EscalatorSpeedData\.DEFAULT_PSD_MIDIUM_ROUND_XZ;", player) is not None
      and re.search(r"private static double cachedMidiumRoundY = "
                    r"EscalatorSpeedData\.DEFAULT_PSD_MIDIUM_ROUND_Y;", player) is not None
      and re.search(r"private static double cachedArriveRoundXz = "
                    r"EscalatorSpeedData\.DEFAULT_PSD_ARRIVE_ROUND_XZ;", player) is not None
      and re.search(r"private static double cachedArriveRoundY = "
                    r"EscalatorSpeedData\.DEFAULT_PSD_ARRIVE_ROUND_Y;", player) is not None,
      "★ 六份范围缓存声明：三类 × 水平/垂直，初始 = 各自默认（不共用提示音那份）")
# ★ 四个调用点各自写对了类别 —— 这条是本轮最容易漏的地方
check(re.search(r"float volume = volumeFactor\(toneVolume\);", player) is not None
      and re.search(r"gain\(distance, ROUND_TONE\) \* volume <= 0\.0f", player) is not None,
      "★★【1.25】resolvePlayable（开关门提示音）只取**音量系数**、射程判定用 ROUND_TONE"
      " —— 距离增益**不在起播那一刻算死**（那正是「门开时站得远 ⇒ 走近了也不变响」的根因）")
check(re.search(r"gain\(distanceXz, distanceY, ROUND_MIDIUM\)", player) is not None,
      "planArrivalAnnounce（到站播报）用 ROUND_MIDIUM —— 起播门限走**双维**口径")
check(re.search(r"gain\(distanceXz, distanceY, ROUND_ARRIVE\)", player) is not None,
      "tickArriveAnnounce（进站报站）用 ROUND_ARRIVE —— 起播门限走**双维**口径")
check(re.search(r"if \(gain\(distance\) \*", player) is None,
      "★ 没有漏改的 gain(distance) 单参调用点")
# 六份缓存的刷新与复位（【09-29】范围拆双维 ⇒ 三类 × 水平/垂直各一份）
check(re.search(r"cachedRoundXz = EscalatorSpeedManager\.getPsdHelpRoundXz\(mc\.level\);", player)
      is not None
      and re.search(r"cachedRoundY = EscalatorSpeedManager\.getPsdHelpRoundY\(mc\.level\);", player)
      is not None
      and re.search(r"cachedMidiumRoundXz = EscalatorSpeedManager\.getPsdMidiumRoundXz\(mc\.level\);",
                    player) is not None
      and re.search(r"cachedMidiumRoundY = EscalatorSpeedManager\.getPsdMidiumRoundY\(mc\.level\);",
                    player) is not None
      and re.search(r"cachedArriveRoundXz = EscalatorSpeedManager\.getPsdArriveRoundXz\(mc\.level\);",
                    player) is not None
      and re.search(r"cachedArriveRoundY = EscalatorSpeedManager\.getPsdArriveRoundY\(mc\.level\);",
                    player) is not None,
      "refreshSettings 里六份范围（三类 × 水平/垂直）都跟着代次刷新（改完指令下一个 tick 生效）")
check(re.search(r"cachedRoundXz = EscalatorSpeedData\.DEFAULT_PSD_HELP_ROUND_XZ;", player) is not None
      and re.search(r"cachedRoundY = EscalatorSpeedData\.DEFAULT_PSD_HELP_ROUND_Y;", player) is not None
      and re.search(r"cachedMidiumRoundXz = EscalatorSpeedData\.DEFAULT_PSD_MIDIUM_ROUND_XZ;",
                    player) is not None
      and re.search(r"cachedMidiumRoundY = EscalatorSpeedData\.DEFAULT_PSD_MIDIUM_ROUND_Y;",
                    player) is not None
      and re.search(r"cachedArriveRoundXz = EscalatorSpeedData\.DEFAULT_PSD_ARRIVE_ROUND_XZ;",
                    player) is not None
      and re.search(r"cachedArriveRoundY = EscalatorSpeedData\.DEFAULT_PSD_ARRIVE_ROUND_Y;",
                    player) is not None,
      "onDisconnect 里六份范围（三类 × 水平/垂直）都复位成各自默认（不是复用提示音那个常量）")


def gain(d, r):
    if not (d < r):
        return 0.0
    return max(0.0, 1.0 - d / r)


r = 16.0
seq = [gain(d, r) for d in (0, 4, 8, 12, 15.9, 16, 20)]
check(seq[0] == 1.0 and seq[-1] == 0.0, "复算：贴脸 = 1.0、范围外 = 0.0",
      "得到 %s" % [round(x, 4) for x in seq])
check(all(seq[i] >= seq[i + 1] for i in range(len(seq) - 1)),
      "★ 复算：随距离**单调不增**（不会走远了反而更响）")
check(abs(gain(8, 16) - 0.5) < 1e-9,
      "★★【1.25】复算：d = 半个范围 ⇒ **一半**音量（线性；旧平方曲线这里是 1/4）")
# ★★ 这两条把用户症状数值化：「一张站台一串门，远处那几扇也必须还听得见」
check(gain(12, 16) >= 0.2,
      "★★ 12 格（46 格长的门串里常出现的距离）仍有 %.3f 音量 —— 听得见" % gain(12, 16))
check(gain(15.5, 16) >= 0.03,
      "★★ 15.5 格仍有 %.4f（旧平方曲线这里是 0.00098 = -60dB，等于没声）"
      % gain(15.5, 16))
_old_sq = [(1 - d / 16.0) ** 2 if d < 16 else 0.0 for d in (8, 12, 15.5)]
_new_lin = [gain(d, 16) for d in (8, 12, 15.5)]
check(all(n >= o * 2 for n, o in zip(_new_lin, _old_sq)),
      "★★ 对照（现场 LOG3 的距离）：同一距离线性 ≥ 旧平方曲线的 2 倍"
      " —— 8 / 12 / 15.5 格分别 2.0× / 4.0× / 61×",
      "旧 %s → 新 %s" % ([round(x, 4) for x in _old_sq], [round(x, 4) for x in _new_lin]))

# 列车内衰减
check(re.search(r"TRAIN_VOLUME_FACTOR\s*=\s*0\.2f\s*;", player) is not None,
      "TRAIN_VOLUME_FACTOR = 0.2f（= 减少 80%）")
# ★【1.28】进出列车的切换改回**阶跃**（用户点名：「在列车内减少 80% 声音，
#   离开列车立刻恢复到 100%，不需要缓冲」；旧版 1.23 的 1 秒线性斜坡已删除）
check(re.search(r"TRAIN_RAMP_TICKS\s*=\s*20\s*;", player) is None,
      "★★【1.28】TRAIN_RAMP_TICKS = 20 已删除（1 秒斜坡按点名撤掉）")
check(re.search(r"trainRampChangeTick|trainRampFrom|trainRampInit|trainRampRiding", player) is None,
      "★ 斜坡状态字段已全部删除（不再有「变化时刻/起点/方向」状态机）")
check(re.search(r"if \(trainAttenuated\)\s*\{\s*updateTrainRamp\(mc\);\s*v \*= trainRamp\(\);",
                player, flags=re.S) is not None,
      "★★ 现算时乘的是当前倍率 trainRamp()（阶跃后它就是 0.2 / 1.0 本身）")
check(re.search(r"trainAttenuated && ridingTrain\(\)", player) is None,
      "★ 旧的双路径写法（实例里直接 ridingTrain() 判断）**不存在**（都走倍率那条路）")
check(re.search(r"static void updateTrainRamp\(Minecraft mc\)", player) is not None,
      "倍率刷新方法 updateTrainRamp() 存在")
check(re.search(r"trainRamp = ridingTrain\(\) \? TRAIN_VOLUME_FACTOR : 1\.0f;", player) is not None,
      "★ 每 tick 直接把当前车厢状态投影成倍率 —— 上下车下一 tick 就到位（阶跃本来就不需要状态机）")
check(re.search(r"public static void onClientTick\(Minecraft mc\)\s*\{[\s\S]*?"
                r"if \(mc\.level == null \|\| mc\.player == null\)"
                r"\s*\{\s*reset\(mc\);\s*return;\s*\}[\s\S]*?updateTrainRamp\(mc\);", player,
                flags=re.S) is not None,
      "★★ onClientTick 里刷新列车倍率，且它明确排在「mc/player 为空的早退分支」**之后**"
      " ⇒ 哪怕这一刻没有声音在播，倍率也已在走；下一声起播直接拿对")
check(re.search(r"public static void onDisconnect\(\)\s*\{.*?trainRamp = 1\.0f;", player,
                flags=re.S) is not None,
      "换世界时把倍率复位成 1.0 ⇒ 下一 tick 由 updateTrainRamp 投影到新的车厢状态"
      "（进存档时人在车里也一次到位，不会先响一声大的）")
# 复算倍率本身：阶跃 —— 上车下一 tick 直接 0.2、下车下一 tick 直接 1.0（无需缓冲）
def train_step(riding, ticks):
    return [0.2 if riding else 1.0 for _ in range(ticks)]


up = train_step(False, 5)
down = train_step(True, 5)
check(up == [1.0, 1.0, 1.0, 1.0, 1.0] and down == [0.2] * 5,
      "复算：下车每 tick 都是 1.0 / 上车每 tick 都是 0.2 —— 阶跃，没有过渡值",
      "得到 下车 %s / 上车 %s" % (up, down))
# 【1.30】报站淡入淡出平滑（比例式逼近；铃声不参与）
m_rv = re.search(r"void refreshVolume\(Minecraft mc\)\s*\{(.*?)\n    \}", player, flags=re.S)
check(m_rv is not None, "抠得出 refreshVolume() 方法体（到方法真正的收尾 4 空格右括号）")
if m_rv:
    rb = m_rv.group(1)
    check(re.search(r"VOLUME_SMOOTH_PER_TICK\s*=\s*0\.18f\s*;", player) is not None,
          "★【1.30】VOLUME_SMOOTH_PER_TICK = 0.18f（每 tick 走 18% 差距 ⇒ 时间常数 ≈ 250ms）")
    check("smoothedVolume" in rb and "VOLUME_SMOOTH_PER_TICK" in rb,
          "freshVolume 里按比例逼近（target 每 tick 现算、落到引擎的音量向 target 爬）")
    check("roundKind == ROUND_MIDIUM || roundKind == ROUND_ARRIVE" in rb,
          "★ 平滑只对报站类（到站 / 进站，长音）；铃声 ROUND_TONE 不参与（保持清脆立即跟随）")
    check("smoothedVolume + (v - smoothedVolume)" in rb,
          "★ 是「差距×比例」不是「每 tick 加固定值」—— 固定值限速会被音量大小拖累（1.23 教训）")
check("float t = Math.min(1.0f, (now - trainRampChangeTick)" not in player,
      "★ 旧斜坡算式（t 按 tick 推进）已不存在")
check("VehicleRidingMovement" in player and '"ridingVehicleId"' in player,
      "反射目标 = org.mtr...VehicleRidingMovement#ridingVehicleId")
check(re.search(r"\.setAccessible\(true\)", player) is not None,
      "反射前 setAccessible(true)（private static）")
check(re.search(r"ridingFieldResolved", player) is not None,
      "★ 反射结果被缓存（每 tick 都反射会拖帧；且失败只记一次日志）")
# 失败必须是「不衰减」而不是抛异常
mrf = re.search(r"(?:public|private) static boolean ridingTrain\(\)\s*\{(.*?)\n    \}", player,
                flags=re.S)
check(mrf is not None, "找得到 ridingTrain()")
if mrf:
    rb = mrf.group(1)
    check("try {" in rb and "catch (Throwable t)" in rb,
          "★ 反射包在 try/catch(Throwable) 里 —— 失败 = 不衰减，绝不能因为没装 MTR 就崩")
    check(len(re.findall(r"catch \(Throwable t\)", rb)) == 2
          and len(re.findall(r"return false;", rb)) >= 2,
          "★ 两段（找字段 / 读字段）各有一道 try/catch，失败都走 return false（安全默认）",
          "catch=%d return false=%d" % (len(re.findall(r"catch \(Throwable t\)", rb)),
                                        len(re.findall(r"return false;", rb))))
    check(re.search(r'ridingField = null;', rb) is not None,
          "找字段失败时把缓存字段定成 null（下一 tick 走 f == null 的早退，不再反复反射）")

# 只有到站 / 进站两处报站音才吃列车衰减（开关门提示音不吃 —— 它跟「在不在车里」无关）
# ★【1.23】末尾带着「哪一类范围」这一格；★【1.25】factorOnly 已整个删除；
# ★【1.26】再末尾多一格「按哪一串门算距离」（站台广播）
fire = re.findall(r"play\(mc, [^;]*?,\s*true,\s*ROUND_(MIDIUM|ARRIVE),\s*(\w+\.runKey|\brunKey|\bplan\.runKey),\s*(true|false)\);", player)
check(len(fire) == 2,
      "★ 恰好 2 处报站音传 trainAttenuated=true：到站 + 进站",
      "得到 %d 处：%s" % (len(fire), fire))
check(sorted(k for k, _, _ in fire) == ["ARRIVE", "MIDIUM"],
      "★★ 这两处**各自**带对了范围类别（到站 ROUND_MIDIUM / 进站 ROUND_ARRIVE）"
      " —— 两处都写 ROUND_MIDIUM 就是「/p 完全没反应」",
      "得到 %s" % [r[0] for r in fire])
check(fire and all(k[1] in ("plan.runKey", "runKey") and k[2] == "true" for k in fire),
      "★★【1.26/09-30续10】两处报站音把**串锚点**当倒数第二格传进去、串口径 chainDoubleDim=true（双维）"
      " —— 站台广播（距离按本串最近的门算）；哨兵 NO_BROADCAST_RUN 已删，没退回「每扇门各自 16 格」",
      "得到 %s" % [(r[1], r[2]) for r in fire])
check(re.search(r"play\(mc, tone, door, sharedVolume, startMs, false, ROUND_TONE,\s*"
                r"door\.runKey\(\),\s*false,\s*door\.platformId\(\)\)", player) is not None,
      "★★【1.25/1.26/10-01】开关门提示音也走「音量系数 + 每 tick 现算距离增益 + 按串算距离」："
      "不吃列车衰减(false) + 单维(false) + 收窄到本站台(door.platformId)；范围走 ROUND_TONE")
check(re.search(r"NO_BROADCAST_RUN", player) is None,
      "★★【09-30续10】NO_BROADCAST_RUN 哨兵已**整个删除** —— 提示音与播报统一按串算距离，"
      "不再有「按本扇门的那条位置音口径」")
check(player.count("factorOnly") == 0,
      "★★【1.25】factorOnly **整个删掉**（它的 false 分支 = 距离增益在起播那一刻算死 ⇒"
      "「门开时玩家站得远，这条声音就永远只剩 0.4% 音量、走近也不变响」）。"
      "留着它 = 留一条没人走的死路径（本项目栽过这个形态）")
check(player.count("trainAttenuated") >= 4,
      "列车衰减开关贯穿：字段 → 构造 → refreshVolume")
check(player.count("roundKind") >= 5,
      "【1.23】范围类别同样贯穿：常量 → play 形参 → 构造 → 字段 → refreshVolume",
      "得到 %d 处" % player.count("roundKind"))
# ★★ 两个开关**故意不合并**成一个：判据不同（一个是「在不在车里」、一个是「算哪一类范围」）
check(re.search(r"boolean trainAttenuated,\s*int roundKind,", player) is not None,
      "★【1.23】trainAttenuated 与 roundKind 是**两个独立形参**"
      "（眼下同进同出，但判据不同 —— 合并 = 本项目栽过的「一个哨兵表达两件事」）")

# 退场：一次性声音没有引擎的「播完」回调
check(re.search(r"long playableMs = Math\.max\(0L, tone\.durationMs\(\) - Math\.max\(0, startMs\)\);",
                player) is not None,
      "★ playableMs = max(0, 素材总长 − 剪掉的头部)（剪头那档要按**剩下的**算时长）")
check(re.search(r"\(int\) \(\(playableMs \+ 49L\) / 50L\) \+ 20", player) is not None,
      "★★ remainingTicks = ceil(playableMs / 50) + 20（向上取整成 tick + 20 tick 余量）")
check(re.search(r"public boolean isStopped\(\)\s*\{\s*return remainingTicks <= 0;", player)
      is not None,
      "★ isStopped() 用 remainingTicks <= 0 自己退场（否则实例永远挂在 SoundEngine 里）")
check(re.search(r"if \(remainingTicks > 0\)\s*\{\s*remainingTicks--;", player) is not None,
      "tick() 里递减 remainingTicks")
check(re.search(r"public boolean canPlaySound\(\)\s*\{\s*return true;", player) is not None,
      "canPlaySound() 恒 true")

# ======================================================================
# 7) 版本号 / 打包名 / 产物
# ======================================================================
print()
print("===== 7) 版本号与打包名 =====")

ver = re.search(r"mod_version\s*=\s*(\S+)", gradle)
check(ver is not None and ver.group(1) == EXPECTED_VER,
      "gradle.properties 的 mod_version = %s（用户点名的号）" % EXPECTED_VER,
      "得到 %s" % (ver.group(1) if ver else "?"))
_ab = re.search(r"archives_base_name\s*=\s*(\S+)", gradle)
check(_ab is not None and _ab.group(1) == EXPECTED_JAR_PREFIX,
      "archives_base_name = %s（jar 名前缀 = 模组显示名 mzycBetterMTR）" % EXPECTED_JAR_PREFIX,
      "得到 %s" % (_ab.group(1) if _ab else "?"))
check(re.search(r"base\s*\{\s*archivesName\s*=\s*project\.archives_base_name\s*\}",
                build_gradle) is not None,
      "★★ build.gradle 用 base.archivesName 真的**应用**了它"
      "（只声明不应用 ⇒ 产物仍叫 smooth-escalator-*，用户看不到改名）")
check(re.search(r'displayName="mzycBetterMTR"', read(os.path.join(ROOT, "src", "main",
                "resources", "META-INF/mods.toml"))) is not None,
      "META-INF/mods.toml 的 displayName = mzycBetterMTR（游戏内显示名）")
check(re.search(r'modId="smooth_lift"', read(os.path.join(ROOT, "src", "main",
                "resources", "META-INF/mods.toml"))) is not None,
      "★ modId 仍是 smooth_lift（改了会让 smoothlift: 命名空间整片崩）")

if not os.path.isfile(JAR):
    print("[SKIP] 未找到 %s，跳过 jar 校验（先跑 gradlew build）" % os.path.basename(JAR))
else:
    print("        检查 jar：%s（%d 字节）" % (os.path.basename(JAR), os.path.getsize(JAR)))
    check(EXPECTED_JAR_PREFIX + "-" + EXPECTED_VER + ".jar" == os.path.basename(JAR),
          "★ 产物名 = %s-%s.jar" % (EXPECTED_JAR_PREFIX, EXPECTED_VER),
          "得到 %s" % os.path.basename(JAR))
    with zipfile.ZipFile(JAR) as z:
        names = set(z.namelist())
        check("smooth/lift/EscalatorSpeedData$PsdToneAudio.class" in names,
              "jar 内含 PsdToneAudio.class")
        blob_cmd = z.read("smooth/lift/SmoothLift.class")
        for tok in (b"pbmmusicloud", b"pbmmidiumloud", b"pbmarriveloud"):
            check(tok in blob_cmd, "SmoothLift.class 里编进了 %s" % tok.decode())
        # forge 版：set_psd_*_loud 不是通道常量而是包类，验包类字节码
        for pkt_cls, tok in (("SetPsdMidiumLoudPacket", b"setDoorPsdMidiumVolume"),
                             ("SetPsdArriveLoudPacket", b"setDoorPsdArriveVolume")):
            check(any(n == "smooth/lift/network/" + pkt_cls + ".class" for n in names),
                  "jar 内含 %s.class" % pkt_cls)
        blob_data = z.read("smooth/lift/EscalatorSpeedData$PsdToneAudio.class")
        for tok in (b"midiumVolume", b"arriveVolume"):
            check(tok in blob_data,
                  "PsdToneAudio.class 里编进了字段 %s（record 组件名会在字节码里）" % tok.decode())
        blob_mgr = z.read("smooth/lift/EscalatorSpeedManager.class")
        for tok in (b"setDoorPsdMidiumVolume", b"setDoorPsdArriveVolume",
                    b"getDoorPsdMidiumVolume", b"getDoorPsdArriveVolume"):
            check(tok in blob_mgr, "EscalatorSpeedManager.class 里编进了 %s" % tok.decode())
        # ★ 播放实例是**内嵌类** ⇒ 它的字段/接口不在外层 class 里
        inner = "smooth/lift/client/PsdChimePlayer$PsdMusicInstance.class"
        check(inner in names, "★ 播放实例是内嵌类 %s（字节码断言要查它，不是外层）" % inner)
        blob_p = z.read(inner)
        for tok in (b"refreshVolume", b"remainingTicks", b"trainAttenuated", b"roundKind",
                    b"chainRunKey"):
            check(tok in blob_p, "PsdMusicInstance.class 里编进了 %s" % tok.decode())
        check(b"factorOnly" not in blob_p,
              "★★【1.25】PsdMusicInstance.class 里**没有** factorOnly —— "
              "「增益算一次就冻住」那条分支确实从字节码里消失了")
        # ★ 生产 jar 里 MC 的类名是**中间名**（class_1113 = TickableSoundInstance）
        #   ⇒ 要验「真的 implements 了」必须查 **dev jar**（有名版本）。
        devs = glob.glob(os.path.join(ROOT, "build", "devlibs", "*-dev.jar"))
        if not devs:
            print("[SKIP] 没找到 dev jar，跳过「真的 implements TickableSoundInstance」校验")
        else:
            dev = sorted(devs, key=os.path.getmtime)[-1]
            with zipfile.ZipFile(dev) as zd:
                bd = zd.read(inner)
            check(b"net/minecraft/client/resources/sounds/TickableSoundInstance" in bd,
                  "★★ dev jar 里 PsdMusicInstance **真的 implements** TickableSoundInstance"
                  "（生产 jar 是中间名 class_XXXX，验不了这个）")
            check(b"net/minecraft/client/resources/sounds/AbstractSoundInstance" in bd,
                  "★ 继承自 AbstractSoundInstance（原版引擎才会每 tick 调 calculateVolume）")
        blob_outer = z.read("smooth/lift/client/PsdChimePlayer.class")
        for tok in (b"ridingVehicleId", b"TRAIN_VOLUME_FACTOR", b"ridingTrain",
                    b"updateTrainRamp", b"trainRamp",
                    b"cachedMidiumRoundXz", b"cachedArriveRoundXz", b"roundXzFor",
                    b"roundYFor"):
            check(tok in blob_outer,
                  "PsdChimePlayer.class 里编进了 %s" % tok.decode())
        check(b"TRAIN_RAMP_TICKS" not in blob_outer,
              "★【1.28】PsdChimePlayer.class 里**没有** TRAIN_RAMP_TICKS（1 秒斜坡已删）")
        # ★ 字节码比 grep 强：常量池里能证明「ridingVehicleId 是**字符串**」（反射要的是串）
        check(b"ridingVehicleId" in blob_outer, "★ 反射字段名以**字符串**进了常量池")
        # 【1.23】★ 查的是 roundXzFor / roundYFor / cached*RoundXz(或 Y) 这些**方法名 / 字段名** ——
        #   ROUND_TONE/MIDIUM/ARRIVE 是 `static final int`，javac 会**内联成字面量**，
        #   常量池里根本没有它们的名字（拿它们去查会假红，别改回去）。
        check(b"roundXzFor" in blob_outer and b"roundYFor" in blob_outer
              and b"cachedArriveRoundXz" in blob_outer,
              "★★【09-29】roundXzFor / roundYFor（方法名）与 cachedArriveRoundXz（字段名）"
              "进了常量池 —— 三条 range 指令（三类 × 双维）不是死配置")
        check("smooth/lift/client/PsdToneSetupScreen.class" in names,
              "jar 内含 PsdToneSetupScreen.class")
        blob_ui = z.read("smooth/lift/client/PsdToneSetupScreen.class")
        for tok in (b"midiumLoudInput", b"arriveLoudInput", b"sendSetMidiumLoud",
                    b"sendSetArriveLoud"):
            check(tok in blob_ui, "PsdToneSetupScreen.class 里编进了 %s" % tok.decode())

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
