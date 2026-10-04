# -*- coding: utf-8 -*-
"""离线校验：【1.15】屏蔽门（平台幕门 / 半高安全门）开关门**自定义素材**。

## 需求（用户原话归纳）

    5：列车屏蔽门开关门时可以播放自定义声音；石斧右键屏蔽门打开 UI（与直梯的石斧 UI 基本一致），
       有开门声音 / 关门声音列表和音量输入框（音量 1~1000）；音频导入文件夹 MBM_Audio。
       指令 1：`pbmmusic open/close open/close/(-f) XXX (to) XXX` —— 修改屏蔽门音效；
       指令 2：`pbmloud X` —— 修改屏蔽门声音大小。两条指令都有 to / -f 功能。
    6：dooropen.ogg、doorclose.ogg、mdoorclose.ogg 设为屏蔽门默认音频。
       特殊情况：doorclose.ogg 在指令里是 default-c、mdoorclose.ogg 在指令里是 default-m。

## 设计要点（脚本要钉住的不变量）

1. **三段内置音频 + 四个名字**：
   - `default`   → **按端别**落内置：开门 dooropen.ogg、**关门 mdoorclose.ogg**
     （= 「跟上一层」的终点；用户点名「屏蔽门默认音效改为 mdoorclose.ogg」）；
   - `default-c` → 显式 doorclose.ogg（与端别无关）；
   - `default-m` → 显式 mdoorclose.ogg（与端别无关；它同时就是关门端的默认）；
   - `default-s` → **「默认（短）」**：与 `default` 落到**同一段素材**（同样按端别），
     但**不播开头的语音播报段**（只播嘀嘀）—— 它不是第四段音频，只是一条播放策略，
     所以**不能**给它单开一个返回分支（那样会让「在开门端写 default-s」把开门声换成关门素材）；
   - `none`      → 这一项不播（指令里不能写 `off`：`off` 已被子开关字面量占用）。
2. **两层回落**（与直梯 1.15 同一套）：
   某扇门的值 --(空/default)→ 维度默认素材 --(空/default)→ 端别内置。
3. **维度默认素材有专门字段**（`defaultPsdToneAudioOpen/Close`）+ 走 chime 同步包下发；
   `-f` 还会清/改「按扇门单独设置」那张表 ⇒ 额外再走一次 tone 同步包。
4. **UI 必须能选到各段内置**：`doorclose.ogg`（default-c）没有任何一端缺省它，
   界面不摆就没法选；`default-s` 只在**关门页**摆（开门端它与第一行效果相同）。
5. **子开关与素材各占各的**：`/pbmmusic open off` 是「关掉开门提示音」，不是「把素材设成 off」。
6. **关门端「结尾对齐门关上那一刻」**见 `_tools/check-psd-align.py`；
   **「播报 / 嘀嘀」分两段摆 + default-s 的压制效果**见 `_tools/check-psd-split.py`
   （都要模型化门速与剪头算式，不适合混在素材链路里）。
7. **名字解析要真调一次**：`_tools/CmdTreeCheck.java` 里直接调 `resolvePsdToneName(null, 名字)`
   把四个内置名逐个钉死 —— 字符串参数在**解析期不报错**，只 grep 常量名证明不了它被正确接住。

用法：`python _tools/check-psd-tone.py`（退出码 0 = 全部通过）
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
DATA = os.path.join(MAIN, "EscalatorSpeedData.java")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
SL = os.path.join(MAIN, "SmoothLift.java")
SLC = os.path.join(CLIENT, "SmoothLiftClientEvents.java")
SCREEN = os.path.join(CLIENT, "PsdToneSetupScreen.java")
# 【1.57】两列版式的**唯一来源**：屏蔽门这几个二级页的几何常量已改成指向它的别名
SOUND_LIST = os.path.join(CLIENT, "SoundListLayout.java")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
SOUNDS = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift", "sounds.json")
SOUND_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "smoothlift", "sounds", "audio")

# ★ 不写死版本号：取 build/libs 下最新的那个 jar
_jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")),
               key=os.path.getmtime)
JAR = _jars[-1] if _jars else os.path.join(ROOT, "build", "libs", "mzycBetterMTR-" + VERSION + ".jar")

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


main_raw = read(SL)
main = strip_comments(main_raw)
client_raw = read(SLC)
client = strip_comments(client_raw)
mgr_raw = read(MGR)
mgr = strip_comments(mgr_raw)
data_raw = read(DATA)
data = strip_comments(data_raw)
player = strip_comments(read(PLAYER))
screen = strip_comments(read(SCREEN))
# 【1.57】两列几何的真身；屏蔽门界面里那些同名常量现在是**别名**
layout = strip_comments(read(SOUND_LIST))
# 【1.21】一串门（runKey）的算法在 PsdDoorTracker，时刻表在 MtrDwellAccess。
tracker = strip_comments(read(os.path.join(CLIENT, "PsdDoorTracker.java")))
dwell = strip_comments(read(os.path.join(CLIENT, "MtrDwellAccess.java")))
sounds = read(SOUNDS)

# ---- forge：屏蔽门 C2S 包类语料（fabric 版的服务端接收器逻辑在各包 handle 里） ----
PACKET_DIR = os.path.join(MAIN, "network")
def pkt_text(name):
    p = os.path.join(PACKET_DIR, name + ".java")
    return strip_comments(read(p)) if os.path.isfile(p) else ""
SetPsdTonePacket = pkt_text("SetPsdTonePacket")
pkt_tone = pkt_text("PsdToneSyncPacket")
SetPsdToneSwitchPacket = pkt_text("SetPsdToneSwitchPacket")
SetPsdToneVolumePacket = pkt_text("SetPsdToneVolumePacket")
SetPsdChimeVolumePacket = pkt_text("SetPsdChimeVolumePacket")
SetPsdOpenWaitPacket = pkt_text("SetPsdOpenWaitPacket")
SetPsdCloseWaitPacket = pkt_text("SetPsdCloseWaitPacket")
SetPsdMidiumPacket = pkt_text("SetPsdMidiumPacket")
SetPsdArrivePacket = pkt_text("SetPsdArrivePacket")
ImportPsdMidiumAudioPacket = pkt_text("ImportPsdMidiumAudioPacket")
ImportFolderPsdTonePacket = pkt_text("ImportFolderPsdTonePacket")
print("== 1. 素材（三段内置 ogg + sounds.json 注册） ==")
for f in ("dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"):
    check(os.path.isfile(os.path.join(SOUND_DIR, f)), "内置素材文件存在：%s" % f)
for name in ("audio/dooropen", "audio/doorclose", "audio/mdoorclose"):
    check('"%s"' % name in sounds, "sounds.json 注册了 %s" % name)
check("audio/psd_open" not in sounds and "audio/psd_close" not in sounds,
      "旧的合成素材 psd_open / psd_close 已从 sounds.json 移除（别再加回来）")
check(not os.path.isfile(os.path.join(SOUND_DIR, "psd_open.ogg"))
      and not os.path.isfile(os.path.join(SOUND_DIR, "psd_close.ogg")),
      "旧的 psd_open.ogg / psd_close.ogg 文件已删除")

print("\n== 2. 数据层（四个内置名常量 + 别名映射 + 两个维度默认字段） ==")
check(re.search(r'PSD_TONE_BUILTIN_OPEN\s*=\s*PSD_TONE_DEFAULT', data) is not None
      or re.search(r'PSD_TONE_BUILTIN_OPEN\s*=\s*"default"', data) is not None,
      "PSD_TONE_BUILTIN_OPEN = default（跟上一层 / 按端别落内置）")
check(re.search(r'PSD_TONE_BUILTIN_CLOSE\s*=\s*"default-c"', data) is not None,
      "PSD_TONE_BUILTIN_CLOSE = default-c（显式 doorclose.ogg）")
check(re.search(r'PSD_TONE_BUILTIN_CLOSE_M\s*=\s*"default-m"', data) is not None,
      "PSD_TONE_BUILTIN_CLOSE_M = default-m（显式 mdoorclose.ogg）")
check(re.search(r'PSD_TONE_BUILTIN_CLOSE_S\s*=\s*"default-s"', data) is not None,
      "PSD_TONE_BUILTIN_CLOSE_S = default-s（「默认（短）」：同素材但不播语音播报段）")
check("isPsdBuiltinName" in data, "数据层提供 isPsdBuiltinName（四个内置名判定）")
check("isPsdBuiltinShort" in data,
      "数据层提供 isPsdBuiltinShort（谁负责「只播嘀嘀」这条判定 —— 播放端不自己认字面量）")
m = re.search(r"public static String psdBuiltinKey\(String which, String id\)(.*?)\n    \}", data, re.S)
bk = m.group(1) if m else ""
check(bool(bk), "找到 psdBuiltinKey（名字 → 内置档 key）")
if bk:
    check('"open".equals(which) ? "dooropen" : "mdoorclose"' in bk,
          "default 按端别落：开门 dooropen / **关门 mdoorclose**（用户点名的默认音效）")
    check('return "doorclose"' in bk, "default-c → doorclose")
    check('return "mdoorclose"' in bk, "default-m → mdoorclose")
    # ★ default-s 必须与 default **落到同一段素材**（差别只在播放策略），且必须**按端别**
    #   —— 这样「在开门端写 default-s」只会等价于 default，不会把开门声换成关门素材。
    check("PSD_TONE_BUILTIN_CLOSE_S.equals(id)" in bk
          and bk.count('"open".equals(which) ? "dooropen" : "mdoorclose"') == 1,
          "★ default-s 与 default 共用**同一个**按端别分支（不另起一个返回值）"
          " ⇒ 它不是第四段音频，只是一条播放策略")
m = re.search(r"public static boolean isPsdBuiltinName\(String id\)(.*?)\n    \}", data, re.S)
inb = m.group(1) if m else ""
check(all(("PSD_TONE_BUILTIN_OPEN" in inb, "PSD_TONE_BUILTIN_CLOSE" in inb,
          "PSD_TONE_BUILTIN_CLOSE_M" in inb, "PSD_TONE_BUILTIN_CLOSE_S" in inb)),
      "isPsdBuiltinName 收录**全部四个**名字（漏一个 ⇒ UI 点那一行会被判「音频不存在」）")
check("defaultPsdToneAudioOpen" in data and "defaultPsdToneAudioClose" in data,
      "数据层两个「维度默认素材」字段（初始 default ⇒ 与 1.50 行为一致）")
check("normalizePsdToneAudio" in data and "isPsdToneAllDefault" in data,
      "数据层有「空值→default」与「两项全默认」两个小工具")
check('tag.putString("defaultPsdToneAudioOpen"' in data
      and 'data.defaultPsdToneAudioOpen = normalizePsdToneAudio(tag.getString("defaultPsdToneAudioOpen"))' in data
      and 'tag.putString("defaultPsdToneAudioClose"' in data
      and 'data.defaultPsdToneAudioClose = normalizePsdToneAudio(tag.getString("defaultPsdToneAudioClose"))' in data,
      "两个新字段写/读成对（缺字段读回 default，旧存档兼容）")
m = re.search(r"public void removeAudio\((.*?)\n    \}", data, re.S)
rm = m.group(1) if m else ""
check("psdToneAudio" in rm and "defaultPsdToneAudioOpen" in rm and "defaultPsdToneAudioClose" in rm,
      "删音频时把指向它的**按扇门**与**维度默认**引用都退回 default（不留悬空引用）")

print("\n== 3. Manager（读写 / 名字解析 / 补全候选 / 同步） ==")
check("getPsdToneAudio" in mgr and "psdToneField" in mgr and "setServerPsdToneAudio" in mgr,
      "Manager：读维度默认 / 取单项字段 / 写单项默认三个入口")
check("setDefaultPsdToneAudio" in mgr and "replaceDefaultPsdToneAudio" in mgr
      and "setDefaultPsdToneAudioAll" in mgr and "replaceDefaultPsdToneAudioAll" in mgr,
      "四个设定接口（本维度 / 本维度 X to Y / -f 全部 / -f X to Y）")
check("clearPsdToneOverrides" in mgr,
      "-f 会清掉「按扇门单独设置」（那些门从此跟维度默认）")
check("resolvePsdToneName" in mgr and "psdNameCandidates" in mgr,
      "Manager：名字解析 + Tab 补全候选")
m = re.search(r"public static AudioArg resolvePsdToneName\(ServerLevel level, String name\)(.*?)\n    \}", mgr, re.S)
rz = m.group(1) if m else ""
check(bool(rz), "找到 resolvePsdToneName")
if rz:
    check(all(("PSD_TONE_BUILTIN_OPEN" in rz, "PSD_TONE_BUILTIN_CLOSE" in rz,
              "PSD_TONE_BUILTIN_CLOSE_M" in rz, "PSD_TONE_BUILTIN_CLOSE_S" in rz)),
          "★ 四个内置名**都**参与解析（漏掉 default-s ⇒ /pbmmusic close -f default-s 会被判"
          "「存档里没有叫…的音频」）")
    check('"none".equals(lower)' in rz and '"mute".equals(lower)' in rz,
          "「这一项不播」写作 none（off 被字面量占了，但仍兼容）")
    check("audioLibrary.containsKey" in rz, "其它名字去音频库找（找不到再试 名字+.ogg）")
m = re.search(r"public static List<String> psdNameCandidates\(ServerLevel level\)(.*?)\n    \}", mgr, re.S)
cd = m.group(1) if m else ""
check(bool(cd) and cd.find("PSD_TONE_BUILTIN_OPEN") < cd.find('"none"'),
      "补全候选：四段内置名排最前，然后才是 none 与库文件名")
check(bool(cd) and "PSD_TONE_BUILTIN_CLOSE_S" in cd, "补全候选里有 default-s（Tab 能补出来）")
# 校验写入：显式别名不能被当成「音频不存在」拒掉
m = re.search(r"public static boolean setServerPsdTone\(ServerLevel level, long key, String which, String audioId\)"
              r"(.*?)\n    \}", mgr, re.S)
st = m.group(1) if m else ""
check(bool(st) and "isPsdBuiltinName(id)" in st,
      "★ setServerPsdTone 用 isPsdBuiltinName(id) 放行（**不是**把四个名字各抄一遍）"
      " ⇒ 以后再加内置名不会漏掉这一处")
check(bool(st) and "PSD_TONE_OFF.equals(id)" in st and "audioLibrary.containsKey(id)" in st,
      "放行集合 = 内置名 ∪ 不播 ∪ 音频库（三支都在）")

print("\n== 4. 指令（/pbmmusic open|close 素材分支 + 补全；/pbmloud 音量） ==")
check('pbmMusicItemCommand("open", "open")' in main and 'pbmMusicItemCommand("close", "close")' in main,
      "/pbmmusic 注册 open / close 两条子树")
for fn in ("pbmMusicItemAudioSet", "pbmMusicItemAudioFromTo",
           "pbmMusicItemAudioForceSet", "pbmMusicItemAudioForceFromTo"):
    check(fn in main, "指令处理器存在：%s" % fn)
check("psdToneNameSuggestions" in main and "suggests(SmoothLift::psdToneNameSuggestions)" in main,
      "素材名参数挂了补全提供器（Tab 能补出四个内置名 / none / 导入过的 ogg）")
check("source == null" in main and "return builder.buildFuture()" in main,
      "补全提供器容忍 null source（_tools/CmdTreeCheck 用 null source 解析真指令树）")
# ★ 指令反馈的显示名：新名字必须有中文说明，否则玩家看到「default-s」不知道是什么
m = re.search(r"(?:private|public) static String psdToneAudioLabel\(String audioId\)(.*?)\n    \}", main, re.S)
lb = m.group(1) if m else ""
check(bool(lb) and "PSD_TONE_BUILTIN_CLOSE_S" in lb,
      "指令反馈给 default-s 留了显示名（玩家能看懂「默认（短）」是什么意思）")
# 字面量必须排在字符串参数前面（Brigadier 字面量优先，顺序即优先级）
m = re.search(r"private static LiteralArgumentBuilder<CommandSourceStack> pbmMusicItemCommand\(.*?\n    \}", main, re.S)
tree = m.group(0) if m else ""
check(bool(tree), "找到 pbmMusicItemCommand 构造器")
if tree:
    check(tree.index('Commands.literal("-f")') < tree.rindex('Commands.argument("name"'),
          "字面量 -f 排在字符串参数 name 前面（顺序即优先级）")
    check(tree.index('Commands.literal("on")') < tree.rindex('Commands.argument("name"'),
          "字面量 on 排在字符串参数 name 前面")
# 四条素材指令的反馈与同步
for need in ("setDefaultPsdToneAudio(", "replaceDefaultPsdToneAudio(",
             "setDefaultPsdToneAudioAll(", "replaceDefaultPsdToneAudioAll("):
    check(need in main, "指令调用 %s" % need.rstrip("("))
check("syncPsdChimeToAll" in main and "syncPsdToneToAll" in main,
      "素材改动既同步 chime 包（维度默认）又同步 tone 包（按扇门表）")
check("psdToneAudioLabel" in main, "指令反馈里有素材 id 的显示名函数")
check('Commands.literal("pbmloud")' in main
      and 'roundCommand("pbmround", RoundKind.TONE)' in main,
      "/pbmloud（音量 1~1000）与 /pbmround（范围）都还在"
      "（【1.23】后者改由 roundCommand 产出 —— 四条范围指令共用一条产出器）")
check('pbmLoudItemCommand("open", "open")' in main and 'pbmLoudItemCommand("close", "close")' in main,
      "/pbmloud 有 open / close 两项单独音量")

print("\n== 5. 同步包（服务端写 / 客户端读，顺序成对） ==")
_pkt_chime = open(os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", "PsdChimeSyncPacket.java"), encoding="utf-8").read()
m = re.search(r"public static void encode\(PsdChimeSyncPacket pkt, FriendlyByteBuf buf\)\s*\{(.*?)\n    \}", _pkt_chime, re.S)
pk = m.group(1) if m else ""
check(bool(pk), "找到 PsdChimeSyncPacket.encode")
if pk:
    check("writeUtf(pkt.toneAudioOpen, 128)" in pk
          and "writeUtf(pkt.toneAudioClose, 128)" in pk,
          "包尾追加 open / close 两个默认素材字符串")
    check(pk.index("pkt.toneAudioOpen") < pk.index("pkt.toneAudioClose"),
          "写出顺序 open → close")
    check(pk.rindex("pkt.toneAudioOpen") > pk.index("pkt.toneVolumeClose"),
          "两个新字段排在最后（不打断旧字段布局）")
check(re.search(r"toneAudioOpen.*?toneAudioClose", _pkt_chime, re.S) is not None,
      "客户端接收器按同一顺序读两个新字段")
check("String toneAudioOpen, String toneAudioClose" in mgr,
      "applyClientPsdChime 签名接收两个新字段")
check("data.psdToneAudioOpen = EscalatorSpeedData.normalizePsdToneAudio(toneAudioOpen)" in mgr
      and "data.psdToneAudioClose = EscalatorSpeedData.normalizePsdToneAudio(toneAudioClose)" in mgr,
      "客户端镜像两个字段被赋值（播放端才查得到）")

print("\n== 6. 播放端（三层映射 + 两层回落 + event 占位非 null） ==")
for const in ("PSD_DOOR_OPEN", "PSD_DOOR_CLOSE", "PSD_MDOOR_CLOSE"):
    check(const in player, "播放端定义声音事件常量 %s" % const)
m = re.search(r"private static ResourceLocation builtinEvent\(String builtinKey\)(.*?)\n    \}", player, re.S)
be = m.group(1) if m else ""
check(bool(be), "找到 builtinEvent（内置档 → 声音事件）")
if be:
    check('case "dooropen"' in be and 'case "mdoorclose"' in be,
          "dooropen / mdoorclose 两个特殊档单独映射")
    check("default ->" in be, "其余（doorclose）落默认分支")
m = re.search(r"private static String psdToneCustomId\(Minecraft mc, long key, String which\)(.*?)\n    \}", player, re.S)
ci = m.group(1) if m else ""
check(bool(ci), "找到 psdToneCustomId（播放端查素材的统一入口）")
if ci:
    check("psdToneField(tone, which)" in ci, "① 先取这一扇门的单独设置")
    check("PSD_TONE_DEFAULT.equals(value)" in ci and "getPsdToneAudio(mc.level, which)" in ci,
          "② 单独设置是 default（或没设过）→ 回落维度默认素材")
    check("STOP_SENTINEL" in ci, "③ 不播用哨兵区分于「没设置」")
check("psdBuiltinKey(which, customId)" in player,
      "播放端用数据层的 psdBuiltinKey 判内置档（判断只写一份）")
check(player.count("resolveTone(mc, which, customId)") == 1,
      "【1.15】内置/自定义、字节来源、素材时长都在 resolveTone 里判一次"
      "（三个调用点共用 —— 它在 resolvePlayable 里，正是「只判一次」那一道闸）",
      "出现 %d 次" % player.count("resolveTone(mc, which, customId)"))
check("injectAudio(mc, tone.customId())" in player,
      "自定义素材走 injectAudio 注入分支（复用扶梯那套解码注入）")
check("new PsdMusicInstance(tone.event()," in player,
      "播放实例带「要注入的那一段」的 ID（resolve 覆盖 → 直接播引擎缓存里那段）")
# 自定义那一支也必须给非 null 的 event 占位（不能传 null）
# ★ 【1.15】play() 的返回类型是 PsdMusicInstance（不是 boolean）—— 判空即知成没成，
#   实例在手边便于将来停它 / 改它。断言随之放宽为「任意返回类型」，
#   只要它仍按 customId 分两条路即可。
#   【1.22】play() 有了多参重载，5 参那个成了只转发的壳
#   ⇒ 正则必须**钉在多参那个**上，否则会匹配到空壳、这段断言就失去意义。
#   【1.23】多一格 roundKind；【1.25】删掉 factorOnly ⇒ 那时是 7 参；
#   【1.26】再多一格 chainRunKey（站台广播按哪一串算距离）⇒ 现在 8 参，尾锚跟着往后挪一格。
m = re.search(r"private static \w+ play\(Minecraft mc, Tone tone[^)]*"
              r"boolean trainAttenuated,\s*int roundKind,\s*long chainRunKey\)(.*?)\n    \}",
              player, re.S)
pl = m.group(1) if m else ""
check(bool(pl) and "if (tone.customId() == null)" in pl,
      "play() 按 tone.customId() 分两条路（null = 内置事件）")
check(re.search(r"private static PsdMusicInstance play\(", player) is not None,
      "★ play() 返回 PsdMusicInstance（不是 boolean）；类型若被改回 boolean，本项直接 FAIL")
check("new Tone(PSD_DOOR_CLOSE, customId, null," in player,
      "自定义分支给非 null 占位 event（与 LiftChimePlayer 一致，不传 null）")
# 【1.15】关门端那两条路都是独立脚本，这里只钉「入口还在」
check("resumeClose" in player and "pendingClose" in player,
      "【1.15】关门端「挂起一 tick 量门速 → 剪头对齐」的入口在（明细见 check-psd-align.py）")
check("planClose" in player and "firePlannedClose" in player and "learnedCycleTicks" in player,
      "【1.15】关门端「整段提前量（语音播报 + 嘀嘀，结尾落在门上）」那条链的入口在"
      "（明细见 check-psd-split.py）")

print("\n== 7. 石斧 UI（四段内置可选 + 文案与直梯对齐） ==")
check("PSD_TONE_BUILTIN_CLOSE" in screen and "PSD_TONE_BUILTIN_CLOSE_M" in screen,
      "UI 把 default-c / default-m 两段显式内置摆进列表（否则没法选到 mdoorclose.ogg）")
# ★【1.15】「默认（短）」：按钮文案 + **只在关门页**出现（理由见 addPicks 的 javadoc）
check("PSD_TONE_BUILTIN_CLOSE_S" in screen, "UI 把 default-s（默认（短））摆进列表")
check(re.search(r'"close"\.equals\(which\)\)\s*\{\s*rows\.add\(new Row\(T_PICK, which,'
                r'[\s\S]{0,120}?PSD_TONE_BUILTIN_CLOSE_S', screen) is not None,
      "★ 「默认（短）」这一行**只在关门页**加（开门端它的效果与第一行「默认」完全相同，"
      "摆两行一样的选项只会让人犯迷糊）")
_lbl = re.search(r"private static String audioLabel\(String id\)(.*?)\n    \}", screen, re.S)
check(_lbl is not None and "PSD_TONE_BUILTIN_CLOSE_S" in _lbl.group(1)
      and "默认只播嘀嘀" in _lbl.group(1),
      "UI 的 audioLabel 认识 default-s（状态栏 / 当前值显示得到中文名）")
check("ROW_TEXT_MAX = 30" in screen,
      "★ 行文字截断上限 30（原来 26 ⇒ 最长那两行一旦是「当前值」，行尾的 ✓ 会被截掉）")
# 【1.22】去括号：四段内置的显示名改为「默认 / doorclose.ogg / mdoorclose.ogg / 默认只播嘀嘀」
#   （原来带（default-c）这种括注 —— 用户点名「删掉 ui 里所有的括号」）。
#   这里逐个钉住**四行都在**，而不是只查一个词：四段内置少一行就选不到了。
check('"默认" + (isDefault' in screen and '"doorclose.ogg" + (isC' in screen
      and '"mdoorclose.ogg" + (isM' in screen and '"默认只播嘀嘀" + (isS' in screen,
      "四段内置可选行文案都在（去括号版：默认 / doorclose.ogg / mdoorclose.ogg / 默认只播嘀嘀）")
check('"默认素材"' not in screen,
      "「默认素材」这个词不出现（不与指令里的 default=内置 撞名）")
check("ImportFolderPsdTonePacket" in screen and "SetPsdTonePacket" in screen,
      "UI 里既有导入通道又有设置通道（待导入行走导入，已入库行走设置）")
check("TOGGLE_SENTINEL" in screen and "SetPsdToneSwitchPacket" in screen,
      "UI 有开关行（点=切换维度默认子开关）")
check("openVolumeInput" in screen and "closeVolumeInput" in screen
      and "openWaitInput" in screen and "closeWaitInput" in screen
      and "SetPsdOpenWaitPacket" in screen and "SetPsdToneVolumePacket" in screen,
      "★【1.23】开门/关门两行 = 按钮 + 等待秒数框 + 音量框（等待走 SET_PSD_OPEN_WAIT/CLOSE_WAIT）")
check("1~1000" in screen or "1000" in screen, "UI 文案给出音量范围 1~1000")

# ★【1.18】用户点名「ui 里的灰色小字删掉」。
#   这一条做成**颜色面板级**的断言：屏蔽门 UI 里**不许出现任何灰阶文字色**
#   （浅灰 0xC0C0C0 / 中灰 0x808080 / 0x909090 / 0xFF909090 / 0xFFE0E0E0）。
#   为什么按颜色断而不是按句子断：灰字是「一类东西」，以后谁再顺手加一行灰字说明，
#   这里立刻红 —— 不必等用户再来报一次。（白色 0xFFFFFF / 黄 0xFFFF55 是允许的：
#   那是读数与状态提示，不是「灰色小字」。0xFFAAAAAA 是滚动条滑块、0x40000000 是滑道，
#   都是图形不是文字。）
GRAY_TEXTS = ("0x808080", "0x909090", "0xFF909090", "0xFFE0E0E0", "0xC0C0C0", "0xA0A0A0")
_gray_hits = [g for g in GRAY_TEXTS if g in screen]
check(not _gray_hits, "★【1.18】屏蔽门 UI 里没有任何灰色文字色（灰字已全部清掉）",
      "仍在使用：%s" % _gray_hits if _gray_hits else "已清空（白 0xFFFFFF / 黄 0xFFFF55 / 滑块 0xFFAAAAAA 保留）")
# 被删掉的那几行灰字，连字符串一起核对（防「颜色改了但灰字句子还在」）
for _dead in ("★ 这段播报不会被掐断", "（暂无）", "（文件夹里没有待导入的 .ogg）",
              "（存档里还没有音频）", "音量 = 1~1000（100 = 原始音量"):
    check(_dead not in screen, "★【1.18】那行灰字已删：%s" % _dead)
# 读数行必须还在（只是由灰改白）：删灰字 ≠ 把读数一起删掉
check('总开关 " + (isMasterEnabled()' not in screen
      and "强制等待 " not in screen,
      "★【1.23】主界面「总开关：…」读数行与底部「默认音量/强制等待」框已按点名删除")
check("未导入存档" in screen and "已导入存档" in screen,
      "★【1.18】到站页两列表头保留（删灰字不等于删表头），颜色由灰改白；"
      "文案跟着【1.19】的新语义（左列只导入、右列负责选用/删除）"
      "　★【1.22】去括号：括注（点=导入）/（选用 / 删除）已按用户要求删掉")
check('drawInputLabel' in screen and "0xFFFFFF" in screen,
      "★【1.18】输入框标签保留（改白）—— 删了按钮后它们是唯一的说明，删掉会让输入框无从辨认")

print("\n== 7b. 【1.17】到站播报：指令 / 同步包 / 数据层 / UI 版式 ==")
# ---- 指令 ----
check(re.search(r'Commands\.literal\("pbmmidium"\)', main) is not None,
      "/pbmmidium 已注册")
check(re.search(r'IntegerArgumentType\.integer\(EscalatorSpeedData\.PSD_MIDIUM_WAIT_MIN\)',
                main) is not None,
      "★ 等待秒数参数 = integer(PSD_MIDIUM_WAIT_MIN)，**没有第二个上界实参** "
      "（= 用户点名的 [0,+∞)；写成 integer(0, 60) 就会「填大没用」）")
check(re.search(r'Commands\.literal\("pbmmidium"\)[\s\S]{0,400}?pbmMidiumForce\("-f"\)',
                main) is not None,
      "/pbmmidium 带 -f（全维度）分支")
# ★ 形状：先 register 名字参数（字符串）、再 register 秒数参数 —— 与 closewait 那条同形
check(re.search(r'pbmMidiumShow[\s\S]{0,900}?pbmMidiumSetNameOnly[\s\S]{0,300}?pbmMidiumGlobal',
                main) is not None,
      "四条路齐全：显示 / 只改名字 / <名字> <秒> / -f")
check(bool(SetPsdMidiumPacket) and "SetPsdMidiumPacket" in screen,
      "石斧 UI 的「站台广播 / 等待几秒后播放」走 SetPsdMidiumPacket"
      "（服务端、UI 侧两端都在）")
# ★ 同步包：两个新字段必须**追加在尾部**，且写/读**顺序一致**
#   （顺序错位不会报错，表现是「秒数串到别的字段上」这类诡异症状）。
check("PsdChimeSyncPacket" in mgr or os.path.isfile(os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", "PsdChimeSyncPacket.java")),
      "维度同步仍走 PsdChimeSyncPacket（服务端推 / 客户端收）")
_w = re.search(r"public static void encode\(PsdChimeSyncPacket pkt, FriendlyByteBuf buf\)\s*\{(.*?)\n    \}", _pkt_chime, re.S)
wbody = _w.group(1) if _w else ""
i_w_mid = wbody.find("pkt.midiumAudio")
i_w_wait = wbody.find("pkt.midiumWaitSeconds")
check(i_w_mid > 0 and i_w_wait > i_w_mid,
      "★ PsdChimeSyncPacket.encode 在**尾部**依次写 midiumAudio → midiumWaitSeconds",
      "写 midiumAudio@%d / wait@%d（体长 %d）" % (i_w_mid, i_w_wait, len(wbody)))
# 客户端读取点（decode 内两行必须按同一顺序出现）
_dr = re.search(r"public static PsdChimeSyncPacket decode\(FriendlyByteBuf buf\)\s*\{(.*?)\n    \}", _pkt_chime, re.S)
dec_region = _dr.group(1) if _dr else ""
# decode 单行 return：校验 параметр 顺序（midiumAudio 在 arriveAudio 之前）
_ao = _pkt_chime.find("arriveAudio")
_ma = _pkt_chime.find("midiumAudio")
check(0 < _ma < _ao,
      "★ PsdChimeSyncPacket.decode 按**同一顺序**读回（midiumAudio → midiumWaitSeconds）",
      "midiumAudio@%d / arriveAudio@%d" % (_ma, _ao))
# 形参：漏加 ⇒ 编不过；这里顺带把「传参顺序」也钉住
check(re.search(r"int closeWaitSeconds,\s*String midiumAudio, int midiumWaitSeconds,\s*"
                r"String arriveAudio, int arriveSeconds", mgr) is not None,
      "★ applyClientPsdChime 形参尾部依次是 closeWaitSeconds → midiumAudio → midiumWaitSeconds"
      " → arriveAudio → arriveSeconds")
check(re.search(r"pkt\.toneAudioOpen, pkt\.toneAudioClose, pkt\.closeWaitSeconds,\s*\n"
                r"\s*pkt\.midiumAudio, pkt\.midiumWaitSeconds, pkt\.arriveAudio, pkt\.arriveSeconds,\s*\n"
                r"\s*pkt\.midiumVolume, pkt\.arriveVolume", _pkt_chime) is not None,
      "★ 调用点的**实参顺序**与形参一致（这里错位同样不报错，只会把值串到别的字段上）"
      "—— 含【1.21】追加的 arriveAudio → arriveSeconds")
check("data.psdMidiumAudio = EscalatorSpeedData.normalizePsdMidiumAudio(midiumAudio)" in mgr
      and "data.psdMidiumWaitSeconds = EscalatorSpeedData.clampPsdMidiumWaitSeconds(midiumWaitSeconds)"
      in mgr,
      "★ 客户端镜像赋值时一并归一化 / 夹取（同步包不是可信来源）")

# ---- 数据层 ----
check('PSD_MIDIUM_OFF = LIFT_TONE_OFF' in data,
      "PSD_MIDIUM_OFF 复用 LIFT_TONE_OFF 字符串（off）—— 到站播报的「不播」")
check("DEFAULT_PSD_MIDIUM_WAIT_SECONDS = 0" in data,
      "默认等待 0 秒（开门音一播完就播报）")
check("PSD_MIDIUM_WAIT_MIN = 0" in data and "PSD_MIDIUM_WAIT_MAX" not in data,
      "★ 只有下界 PSD_MIDIUM_WAIT_MIN = 0，**没有** PSD_MIDIUM_WAIT_MAX（[0,+∞)）")
check(re.search(r"clampPsdMidiumWaitSeconds\(int seconds\)\s*\{\s*return Math\.max\("
                r"PSD_MIDIUM_WAIT_MIN, seconds\);", data) is not None,
      "★ clamp = Math.max(PSD_MIDIUM_WAIT_MIN, seconds) —— 只有 max、没有 min（对比 closeWait 有上下界）")
# ★ 空值只能是「不播」：到站播报**没有内置素材**，不能像提示音那样落回 default。
_mid_norm = re.search(r"normalizePsdMidiumAudio\(String audioId\)\s*\{(.*?)\n    \}", data, re.S)
mid_norm = _mid_norm.group(1) if _mid_norm else ""
check("PSD_MIDIUM_OFF" in mid_norm and "DEFAULT" not in mid_norm,
      "★ normalizePsdMidiumAudio 的空值走 PSD_MIDIUM_OFF（**不是** LIFT_TONE_DEFAULT）——"
      "到站播报没有内置素材，落回 default 会去播一段不存在的音频")
check("isPsdMidiumOff" in data and "isPsdMidiumOff" in player,
      "isPsdMidiumOff 被播放端用上（不是死代码）")
# 素材解析：**不许收内置名**
_mid_resolve = re.search(r"public static String resolvePsdMidiumName\(ServerLevel level, String name\)\s*"
                         r"\{(.*?)\n    \}", mgr, re.S)
mid_resolve = _mid_resolve.group(1) if _mid_resolve else ""
check(bool(mid_resolve) and "isPsdBuiltinName" not in mid_resolve,
      "★ resolvePsdMidiumName **不收内置名**（到站播报没有内置素材，收下会变成一条播不出的引用）")
check("importAudioToStore" in mid_resolve,
      "★ resolvePsdMidiumName 带**文件夹自动导入**（与 pbmmusic 同一套：只写了名字也能用）")
check(re.search(r"psdMidiumSuggestions", mgr) is not None
      and re.search(r"pbmMidiumNameSuggestions", main) is not None,
      "补全：psdMidiumSuggestions ↔ pbmMidiumNameSuggestions 两端都在")
check("clearPsdMidiumIfRemoved" in mgr and "clearPsdMidiumIfRemoved" in main,
      "★ 从存档删音频时要顺手清掉指向它的「到站播报」（否则留下一条指向空文件的引用）")

# ---- UI 版式（用户点名的那张图） ----
check('Component.literal("站台广播")' in screen,
      "UI 有「站台广播」按钮（点击进入选择列表）")
check('Button.builder(Component.literal("站台广播"), button -> {' in screen
      and re.search(r'Component\.literal\("站台广播"\)[\s\S]{0,120}?page = 3;', screen) is not None,
      "「站台广播」是**控件**且点它通向 page 3 = buildArrivalPage（不是画上去的标题）")
check("midiumWaitInput" in screen
      and "midiumWaitInput = new EditBox(this.font, rowWaitBoxX(cx), midiumY, ROW_BOX_W, 20," in screen
      and "drawInputLabelAt(guiGraphics, ROW_WAIT_LABEL, cx + 4, midiumY)" in screen,
      "★ 到站行是「等待秒数:」+ 输入框（EditBox，不是按钮）；"
      "★【1.22】标签改成短的全角冒号版（去括号：原「等待几秒后播放」）"
      " + 右边紧跟一个「音量:」框")
check("parseMidiumWait" in screen and "PSD_MIDIUM_WAIT_MIN" in screen
      and "PSD_MIDIUM_WAIT_MAX" not in screen,
      "★ UI 的解析只卡下界 PSD_MIDIUM_WAIT_MIN（与指令同一个 [0,+∞)）")
check('Component.literal("删除")' in screen and "deleteStored" in screen,
      "★ 右侧「已导入」每行各带一个「删除」按钮（按下从存档移除）")
check(screen.count('Component.literal((midiumOffNow ? "✓" : "") + "不播")') == 1
      and screen.count('Component.literal((arriveOffNow ? "✓" : "") + "不播")') == 1,
      "到站 / 进站两页都有「不播」出口（否则设过一次就再也关不掉）"
      "　★【1.22-2】它已从**页底**挪进**右列第 0 行**（用户点名），文案仍是「不播」")
check("leftColX" in screen and "rightColX" in screen and "COL_GAP" in screen,
      "★ 两列布局：左=未导入 / 右=已导入（leftColX / rightColX 由 COL_GAP 分开）")
check(re.search(r"fill\(cx, listTop, cx \+ 1, listBottom, 0x80FFFFFF\)", screen) is not None,
      "★ 两列之间**一条竖线**（用户点名「中间一条竖线隔开」）")
check('Component.literal("未导入存档")' in screen
      and 'Component.literal("已导入存档")' in screen,
      "两列各有表头文案（画上去的，不是控件）")
check(screen.count("notifyToneDataChanged") >= 1,
      "★ 音频同步回来时会通知本界面刷新（导入/删除后列表要立刻变）")

print("\n== 7c. 【1.19】「导入」与「选用」分成两件事 + 导入后列表**立刻**刷新 ==")
# 用户原话（两条，一条设计一条 bug）：
#   「导入的不直接选用，要在导入的音频的右侧加 2 个按钮，一个是选用，一个是删除」
#   「点击导入之后没有立即进入右侧，重启游戏之后导入进去了」
# ★ 这一节钉的是**两条独立的通路**：
#   1) 版式：右列每行 = 名字 +「选用」+「删除」；左列点一下**不再**顺手选用。
#   2) 刷新：导入只发生在服务端，客户端右列来自音频库同步包（AUDIO_SYNC_CHANNEL）；
#      导入之后**不补发这一包**，界面就会「点了没反应，重启之后才跑到右边」——
#      这正是用户报的那个症状。所以断言必须同时覆盖「有 sendAudioSyncTo」与
#      「它挂在导入那条路上」，只断前一半等于没断。
check('Component.literal("选用")' in screen and "pickMidium" in screen,
      "★ 右列每行有「选用」按钮（用户点名要的那个）")
_imp = re.search(r"private void importPending\(String id\)\s*\{(.*?)\n    \}", screen, re.S)
imp_body = _imp.group(1) if _imp else ""
check(bool(imp_body) and "ImportPsdMidiumAudioPacket" in imp_body,
      "★ 左列点一下走**只导入**通道（不再走 SetPsdMidiumPacket）")
check("SetPsdMidiumPacket" not in imp_body,
      "★★ 左列的 importPending 里**不许**再出现 SetPsdMidiumPacket"
      "（出现即「导入的又被直接选用」= 用户点名要去掉的那种行为）")
# 左列控件必须绑 importPending、右列名字控件必须绑 pickMidium —— 反过来就前功尽弃
check(re.search(r'button -> importPending\(id\)\)[\s\S]{0,80}?bounds\(leftColX\(\)', screen) is not None,
      "★ 左列（leftColX）那一排按钮绑的是 importPending（点=导入）")
check(re.search(r'bounds\(rightColX\(\), y, ROW_NAME_W, 20\)', screen) is not None
      and re.search(r'button -> pickMidium\(id\)\)[\s\S]{0,80}?bounds\(rightColX\(\), y, ROW_NAME_W, 20\)',
                    screen) is not None,
      "★ 右列（rightColX）名字按钮绑的是 pickMidium（点=选用）")
# 版式算术：三个控件必须**互不重叠且正好铺满一列**（错位不报错，只会看起来「按钮叠在一起」）
# 【1.57】算术已搬到 SoundListLayout（两列版式的唯一来源）⇒ 这里改判**那份真身**，
#   并且要求屏蔽门界面**别名过去**、自己不再写第二份数字（两份各写一遍 = 将来只改一边）。
check(re.search(r'ROW_NAME_W = COL_W - 2 \* ROW_BTN_W - 2 \* ROW_BTN_GAP', layout) is not None,
      "★ 名字宽度由两个按钮宽度反算（ROW_NAME_W = COL_W - 2*ROW_BTN_W - 2*ROW_BTN_GAP）"
      "  —— 断言的是 SoundListLayout 那份唯一来源")
check(re.search(r'\bROW_NAME_W\s*=\s*SoundListLayout\.ROW_NAME_W\s*;', screen) is not None,
      "★ 屏蔽门界面的 ROW_NAME_W 是别名（指回 SoundListLayout），不是又抄了一份数字")
check(re.search(r'bounds\(rightColX\(\) \+ ROW_NAME_W \+ ROW_BTN_GAP, y, ROW_BTN_W, 20\)', screen)
      is not None
      and re.search(r'bounds\(rightColX\(\) \+ COL_W - ROW_BTN_W, y, ROW_BTN_W, 20\)', screen)
      is not None,
      "★「选用」紧贴名字右侧、「删除」贴到列尾 —— 两个按钮的位置都由列宽算出来（不写死坐标）")
_code = re.search(r"public static final int COL_W = (\d+);", layout)
check(_code is not None and int(_code.group(1)) == 190,
      "★ 列宽 190（176 会挤不下「名字+两个按钮」，这是改版式时一起量出来的）"
      "  —— 读的是 SoundListLayout 里的唯一一份",
      "COL_W = %s" % (_code.group(1) if _code else "?"))
check(re.search(r'\bCOL_W\s*=\s*SoundListLayout\.COL_W\s*;', screen) is not None,
      "★ 屏蔽门界面的 COL_W 也是别名（190 这个数字只许出现在 SoundListLayout）")
# ---- 刷新通路（用户报的 bug：导入后不刷新） ----
check(bool(ImportPsdMidiumAudioPacket),
      "★ 服务端有「只导入」通道的接收器（ImportPsdMidiumAudioPacket，客户端 send 了没人收 = 点了没反应）")
recv = ImportPsdMidiumAudioPacket
check("importAudioToStore" in recv and "sendAudioSyncTo" in recv,
      "★★ 导入通道的 handler 里：importAudioToStore 之后**补发音频库同步包**"
      "（sendAudioSyncTo）—— 少了它，导入成功但客户端右列不动，就是用户报的「重启后才出现」")
# ★★ 根因那条：SET_PSD_MIDIUM_CHANNEL 自己也会导入（resolvePsdMidiumName 顺手导一次），
#   所以那条路上也必须补发同步包（指令 /pbmmidium 一个没入库的名字时走的就是它）。
mid_recv = SetPsdMidiumPacket
check("syncAudioToAll(player.server)" in mid_recv or "libBefore" in mid_recv,
      "★★ SetPsdMidiumPacket 那条路上也要补发同步包，且**只在真的导入了才发**"
      "（libBefore 前后比对音频库大小）—— 这条是用户 bug 的根因所在")
check("syncPsdToneToAll(player.server)" in mid_recv or "syncPsdChimeToAll(player.server)" in mid_recv,
      "原本的设置同步不能因此被删掉（一个管设置、一个管素材库）。"
      "【1.20】这条路的写入已改成 **per-door**（setDoorPsdMidium），所以设置同步也要走"
      " per-door 那条（syncPsdToneToAll）；名字换了、责任没变")
# ★★ 分块重发的自愈：一次重发 = 从 index 0 重新开始。
#   现在「导入」会立刻触发一次整库重发（几 MB，按 256 个/块切），连着点两次导入就可能交错；
#   没有这一句，两批分块会在同一个 dimId 下拼起来，拼出垃圾 payload（列表清空或出现乱码名）。
_i_clear = client.find("if (chunkIndex == 0) {")
_i_put = client.find("chunks.put(chunkIndex, chunk)")
_i_done = client.find("PENDING_SYNC_CHUNKS.remove(dimId)")
check(_i_clear > 0 and 0 < _i_clear < _i_put < _i_done,
      "★★ 音频库分块同步：见到 0 号块先 clear 再 put，收满才 remove "
      "（保证「导入触发的重发」与上一批不会拼在一起）",
      "clear@%d / put@%d / remove@%d" % (_i_clear, _i_put, _i_done))
check(_i_clear > 0 and "chunks.clear();" in client[_i_clear:_i_put],
      "★ 那句 clear 真的在同一批的 put 之前（不是写反位置的死代码）")

print("\n== 7d. 【1.20】石斧 UI 只改「玩家右键那一扇门」；只有指令才改「全部」 ==")
# ---- 数据层：per-door 记录扩出 9 个**可选覆盖字段**（null = 跟维度默认） ----
rec = re.search(r"public record PsdToneAudio\(String open, String close,(.*?)\{", data, re.S)
rec_body = rec.group(1) if rec else ""
check(rec_body.count("Boolean") >= 3 and rec_body.count("Integer") >= 5
      and "String midium" in rec_body,
      "★★ psdToneAudio 记录扩到 9 个可选覆盖字段（3×Boolean + 5×Integer + 1×String；"
      "加上素材 open/close 共 11 字段）—— "
      "用**可空盒装类型**而不是哨兵值，才能区分「跟维度默认」与「已设过的真实值」",
      "Boolean x%d / Integer x%d" % (rec_body.count("Boolean"), rec_body.count("Integer")))
for w in ("withHelp", "withToneEnabled", "withVolume", "withToneVolume",
          "withCloseWaitSeconds", "withMidium", "withMidiumWaitSeconds"):
    check(("%s(" % w) in data,
          "★ 记录带 %s(...)：覆盖字段**逐个**可改，改一个不动其余（不许整条重建）" % w)

# ---- 管理端：per-door 读写 API 齐备（键 = anchor key = 这一扇门） ----
for fn in ("setDoorPsdHelp", "setDoorPsdToneEnabled", "setDoorPsdHelpVolume",
           "setDoorPsdToneVolume", "setDoorPsdCloseWaitSeconds", "setDoorPsdMidium",
           "isDoorPsdHelpEnabled", "isDoorPsdToneEnabled", "getDoorPsdHelpVolume",
           "getDoorPsdToneVolume", "getDoorPsdCloseWaitSeconds", "getDoorPsdMidiumAudio",
           "getDoorPsdMidiumWaitSeconds"):
    check(fn in mgr, "管理端有 per-door API：%s（按 anchor key 取这一扇门）" % fn)
check("hasAnyDoorPsdHelpOn" in mgr and "hasAnyDoorPsdHelpOn" in player,
      "★ hasAnyDoorPsdHelpOn 被播放端用上：维度默认关着时仍要放行「自己开着」的那几扇门"
      "（缺了它，per-door 总开关等于没生效 / 只在维度默认开着时才碰巧生效）")

# ---- 指令那条路：仍然写**维度默认**（= 用户说的「只有指令才是修改全部」） ----
for fn in ("setDefaultPsdHelpAll", "setDefaultPsdToneEnabledAll", "setDefaultPsdHelpVolumeAll",
           "setDefaultPsdCloseWaitSecondsAll", "setDefaultPsdMidiumAll"):
    check(fn in mgr, "指令那条路仍写**维度默认**（不带 -f 改本维度、-f 改全部维度）：%s" % fn)
check("remapPsdDoorOverrides" in mgr,
      "★【1.20】-f = 真的「修改全部」：所有 *All 指令还调 remapPsdDoorOverrides 把"
      "「按扇门单独设置」那一项一起处理（否则 UI 单独设过的门不跟着变，全局指令看着像没改到）")
_re = re.search(r"private static boolean remapPsdDoorOverrides(.*?)\n    \}", mgr, re.S)
rb = _re.group(1) if _re else ""
check(bool(rb) and "fn.apply(e.getValue())" in rb and "isEmpty()" in rb,
      "remapPsdDoorOverrides 语义完整：null = 不动、新记录 = 替换、清空后为空的记录删掉")
check("withHelp(null)" in mgr and "withVolume(null)" in mgr
      and "withCloseWaitSeconds(null)" in mgr and "withMidium(null)" in mgr,
      "※ 设值型 *All（-f <新值>）把 per-door 那一项抹回「跟维度默认」（withX(null)）")
check(re.search(r"!= from \? null : t\.withHelp\(to\)", mgr) is not None
      and re.search(r"!= from\b", mgr) is not None,
      "※ 条件替换型 *All（-f X to Y）只动「正好是 X」的 per-door 项（!= from ? null : ...(to)）"
      "—— 与素材那条 replace 的 per-door 半同一语义")

# ---- UI：每条设置包都先写 key（这一扇门），且读取一律走 Door 版 ----
#   【1.21】`key` 改名为 `runKey`：现在发的是**串锚点**（用户点名「改一个就改一串」）。
for mth, pkt in (("sendSetPsdVolume", "SetPsdChimeVolumePacket"),
                 ("sendSetCloseWait", "SetPsdCloseWaitPacket"),
                 ("sendSetMidium", "SetPsdMidiumPacket"),
                 ("sendSetArrive", "SetPsdArrivePacket")):
    b = re.search(r"private void %s\([^)]*\)\s*\{(.*?)\n    \}" % mth, screen, re.S)
    body = b.group(1) if b else ""
    check(bool(body) and ("new %s(runKey" % pkt) in body,
          "★ UI 的 %s 先传 runKey 再发（new %s(runKey, ...)）—— 少了它，改动的就是整个维度" % (mth, pkt))
# ★★ 这几个「维度级 getter」是 UI **绝不能**再读的（它们读的是维度默认，不是这一扇门）。
#   注意排除三类合法调用：EscalatorSpeedData.isPsdMidiumOff(id)（按 id 判哨兵，与门无关）、
#   isPsdBuiltinShort(id)（判内置短档）、getPsdHelpRound(level)（/pbmround 项，本就没有按门层，
#   UI 把它当只读信息显示并标注「（本维度）」）。
_forbidden_dim_reads = ("isPsdHelpEnabled", "isPsdToneEnabled", "getPsdHelpVolume",
                        "getPsdToneVolume", "getPsdCloseWaitSeconds", "getPsdMidiumAudio",
                        "getPsdMidiumWaitSeconds", "getPsdToneAudio")
_legacy = [n for n in _forbidden_dim_reads if n + "(" in screen]
check(len(_legacy) == 0,
      "★★ UI 里**没有**遗留的「维度级设置读取」（isPsdHelpEnabled / getPsdHelpVolume / …）—— "
      "石斧 UI 一律按这一扇门读；这几个名字若出现，说明某处读数还是「全部」的",
      "遗留 %s" % (_legacy or "0 处"))
check("applyClientPsdDoorLocal" in screen,
      "★ UI 本地回显走 per-door（applyClientPsdDoorLocal）：点了立刻变、不必等服务端包回来")

# ---- 接收端：5 条设置包都先读 key，并调 per-door setter ----
for pkt_cls, setter in (("SetPsdToneSwitchPacket", "setDoorPsd"),
                     ("SetPsdChimeVolumePacket", "setDoorPsdHelpVolume"),
                     ("SetPsdCloseWaitPacket", "setDoorPsdCloseWaitSeconds"),
                     ("SetPsdToneVolumePacket", "setDoorPsdToneVolume"),
                     ("SetPsdMidiumPacket", "setDoorPsdMidium")):
    bd = pkt_text(pkt_cls)
    check("buf.readLong" in bd and setter in bd,
          "★ %s.handle 里：buf.readLong 取这一扇门、再调 %s(...)（二者缺一就写错对象）"
          % (pkt_cls, setter))

# ---- 包编码：9 个可选字段各带「存在位」 ----
check(pkt_tone.count("writeDoorOptBool(") >= 3 and pkt_tone.count("writeDoorOptInt(") >= 5
      and "writeDoorOptString(" in pkt_tone,
      "★★ 包编码：per-door 覆盖字段逐个带「存在位」（writeDoorOptBool/Int/String）—— "
      "包没有 NBT 的 contains，可空值必须显式标「存在与否」，否则「跟默认」与「真实值」在线上分不开",
      "Bool x%d / Int x%d" % (mgr.count("writeDoorOptBool("), mgr.count("writeDoorOptInt(")))
check("applyClientPsdDoorLocal" in mgr,
      "管理端提供 applyClientPsdDoorLocal（客户端本地回显 per-door 的落地实现）")

# ---- 客户端接收：按写入顺序读回 13 个可选字段建记录（顺序错会整条错位） ----
_client_reads = re.findall(r"readDoorOpt(?:Bool|Int|String)\(", pkt_tone)
check(len(_client_reads) == 14 and "new EscalatorSpeedData.PsdToneAudio(" in pkt_tone,
      "★ PsdToneSyncPacket.decode 按**写入顺序**读回 14 个可选字段建 16 字段记录"
      "（顺序错 = 整条记录错位，而且不报错）"
      "　★【1.23】可选字段 13 → 14（多了开门提示等待秒数）",
      "读回可选字段 %d 个" % len(_client_reads))

print("\n== 7.5 【1.21】进站报站 /pbmarrive + 一串门（runKey） ==")

# ---- 数据层：与到站播报**成对**、但 clamp 方向**相反** ----
check("PSD_ARRIVE_OFF = LIFT_TONE_OFF" in data,
      "进站报站的「不播」哨兵与提示音同串（off）")
check("PSD_ARRIVE_SECONDS_MAX = 0" in data and "PSD_ARRIVE_SECONDS_MIN" not in data,
      "★ 只有**上界** PSD_ARRIVE_SECONDS_MAX = 0，**没有**下界（用户点名的输入范围 (-∞, 0]）")
check(re.search(r"clampPsdArriveSeconds\(int seconds\)\s*\{\s*return Math\.min\("
                r"PSD_ARRIVE_SECONDS_MAX, seconds\);", data) is not None,
      "★ clampPsdArriveSeconds = Math.min(上界, seconds) —— **只有 min、没有 max**"
      "（与到站播报的 clampPsdMidiumWaitSeconds 正好相反，谁「顺手补齐」两处一起红）")
_ar_norm = re.search(r"normalizePsdArriveAudio\(String audioId\)\s*\{(.*?)\n    \}", data, re.S)
ar_norm = _ar_norm.group(1) if _ar_norm else ""
check("PSD_ARRIVE_OFF" in ar_norm and "DEFAULT" not in ar_norm,
      "★ normalizePsdArriveAudio 的空值走 PSD_ARRIVE_OFF（**不是** LIFT_TONE_DEFAULT）——"
      "进站报站没有内置素材，落回 default 会去播一段不存在的音频")
check("normalizePsdArriveAudio" in data and "normalizePsdMidiumAudio" in data,
      "两套 normalize **分开**（不合并）—— 合并的代价是空值语义被另一项拖走")
_ar_rec = re.search(r"public record PsdToneAudio\(([^)]*)\)", data, re.S)
ar_fields = _ar_rec.group(1) if _ar_rec else ""
check("String arrive" in ar_fields and "Integer arriveSeconds" in ar_fields,
      "★ PsdToneAudio 尾部有 arrive / arriveSeconds 两个**可空**字段（null = 跟维度默认）")
check("public PsdToneAudio withArrive(String v)" in data
      and "public PsdToneAudio withArriveSeconds(Integer v)" in data,
      "withArrive / withArriveSeconds 都在（UI 只改其中一项时另一项必须原样保留）")
check("arrive == null && arriveSeconds == null" in data,
      "★ isEmpty() 把 arrive 两项算进「还有没有实际内容」"
      "（少了它，只设了进站报站的门会被当成空记录删掉）")
check("defaultPsdArriveAudio" in data and "defaultPsdArriveSeconds" in data,
      "维度默认层有 defaultPsdArriveAudio / defaultPsdArriveSeconds")

# ---- 指令 /pbmarrive ----
check(re.search(r'Commands\.literal\("pbmarrive"\)[\s\S]{0,400}?pbmArriveForce\("-f"\)',
                main) is not None, "/pbmarrive 带 -f（全维度）分支")
check(re.search(r"pbmArriveShow[\s\S]{0,900}?pbmArriveSetNameOnly[\s\S]{0,300}?pbmArriveGlobal",
                main) is not None, "四条路齐全：显示 / 只改名字 / <名字> <X> / -f")
check("SetPsdArrivePacket" in screen,
      "石斧 UI 的「进站广播 / 到站前秒数」走 SetPsdArrivePacket（服务端 + UI 两端都在）")
check(re.search(r"IntegerArgumentType\.integer\(Integer\.MIN_VALUE,\s*"
                r"EscalatorSpeedData\.PSD_ARRIVE_SECONDS_MAX\)", main) is not None,
      "★ /pbmarrive 的秒数参数 = (-∞, 0]（Integer.MIN_VALUE ~ PSD_ARRIVE_SECONDS_MAX）"
      "—— 与 /pbmmidium 的 [0,+∞) 正好相反")
_bd_ar = pkt_text("SetPsdArrivePacket")
check("buf.readLong" in _bd_ar and "setDoorPsdArrive" in _bd_ar,
      "★ SetPsdArrivePacket.handle：先 buf.readLong 取这一串门、再调 setDoorPsdArrive(...)"
      "（二者缺一就写错对象）")
check("resolvePsdArriveName" in mgr and "psdArriveSuggestions" in mgr
      and "setDefaultPsdArrive" in mgr and "getDoorPsdArriveAudio" in mgr
      and "getDoorPsdArriveSeconds" in mgr,
      "管理端一整套进站报站读写口都在")
_ar_res = re.search(r"public static String resolvePsdArriveName\(ServerLevel level, String name\)\s*"
                    r"\{(.*?)\n    \}", mgr, re.S)
ar_res = _ar_res.group(1) if _ar_res else ""
check(bool(ar_res) and "isPsdBuiltinName" not in ar_res and "PSD_TONE_DEFAULT" not in ar_res,
      "★ resolvePsdArriveName **不收内置名**（进站报站没有内置素材，收下会变成一条播不出的引用）")
check("clearPsdArriveIfRemoved" in mgr and "clearPsdArriveIfRemoved" in main,
      "★ 删音频时一并清「进站报站」（clearPsdArriveIfRemoved 定义 + 调用两端在）")
check("withArrive(null).withArriveSeconds(null)" in mgr,
      "★ -f <名字> <X> 把「按串单独设置」的进站报站抹回跟维度默认"
      "（不抹的话 UI 单独设过的串不跟着变，全局指令看着像没改到）")

# ---- 一串门：runKey ----
check("public static long runKeyOf(Level level, BlockPos raw)" in tracker,
      "★ PsdDoorTracker.runKeyOf：一串门的**配置身份**")
_tv = re.search(r"public record DoorView\(([^)]*)\)", tracker, re.S)
check("long runKey" in (_tv.group(1) if _tv else ""),
      "★ DoorView 带 runKey（播放端才拿得到「这一串」）")
check("public static boolean isPsdFamily(BlockState state)" in tracker
      and '"psd_"' in tracker and '"apg_"' in tracker,
      "★ 一串的白名单 = MTR 屏蔽门**家族**（psd_ / apg_：站台门 + 屏蔽门玻璃 + 玻璃端）"
      "—— 用户点名「连在一起的屏蔽门是指站台门，屏蔽门玻璃，屏蔽门玻璃尾部相连的一串屏蔽门」")
_fr = re.search(r"private static long floodRun\(Level level, BlockPos start\)\s*\{(.*?)\n    \}",
                tracker, re.S)
fr = _fr.group(1) if _fr else ""
check(bool(fr) and '"UPPER".equals(valueName' in fr and "pos.below()" in fr,
      "★ floodRun 起点先折到**下半格**（门与玻璃都有 half=UPPER 的同伴格）——"
      "不折的话同一个物理串会算出两个 runKey，「改一串」就断成两截")
check("if (cur.asLong() < min)" in fr,
      "★ floodRun 取 asLong 最小 ⇒ 同一串里任何起点都收敛到同一个 key")
check("RUN_CACHE.clear()" in tracker,
      "★ 串锚点缓存会被清（不清就是「换世界后仍用旧串锚点」的静默错配）")
# 播放端**读配置**一律走串锚点。
# ★【1.26】两种写法都合法：`door.runKey()`（手里是 DoorView）或直接 `runKey`（已经取出来了）；
#   要禁的永远是「拿**门锚点** door.key() 去读 per-串配置」⇒ 所以这条断言改成
#   「把 get/is DoorPsd* 的第一个实参全抓出来，逐个看它是不是串锚点」，
#   不再数 `door.runKey()` 的出现次数（数次数会绑死写法：本轮把它从 8 降到 6）。
_reads = re.findall(r"(?:get|is)DoorPsd\w+\(mc\.level,\s*([^,()]+(?:\(\))?)", player)
check(bool(_reads) and all("runKey" in t for t in _reads),
      "★ 播放端读配置的第一个实参一律是**串锚点**（runKey / door.runKey() / plan.runKey）"
      " —— 「改一个 = 改一串」；出现 door.key() 就是退回「只改右键那一扇」",
      "得到 %s" % sorted(set(t.strip() for t in _reads)))
check("getDoorPsdMidiumAudio(mc.level, door.key())" not in player
      and "getDoorPsdToneVolume(mc.level, door.key(), which)" not in player
      and "isDoorPsdToneEnabled(mc.level, door.key(), which)" not in player,
      "★ 播放端**没有**遗留的「按门锚点读配置」调用点（那会退回「只改右键那一扇」）")
check("PsdDoorTracker.runKeyOf(level, pos)" in screen
      and "PsdDoorTracker.anchorOf(level, pos)" not in screen,
      "★ 石斧 UI 的配置身份用 runKeyOf（**不是** anchorOf）——"
      "用户点名「修改其中一个，就要一起修改这一串」")
check(screen.count("new Set") >= 8 and "runKey" in screen and "buf.writeLong(key)" not in screen,
      "★ UI 的每条设置包都先传 runKey 再发")

# ---- UI 版式：进站广播那一行 ----
check('Component.literal("进站广播")' in screen, "UI 主界面有「进站广播」按钮")
check(re.search(r"LIST_TOP \+ 10 \+ ROW_H \* 4", screen) is not None,
      "★ 它排在站台广播（ROW_H*3）的**下面**一行（ROW_H*4）")
check(re.search(r"LIST_TOP \+ 10 \+ ROW_H \* 4 \+ 20", screen) is not None,
      "★ 主界面状态行的下限也跟着下移（多了一行 ⇒ fixedBottom 必须一起改，"
      "否则状态行又会压回「挡住中间播报」那个原始症状上）")
check("arriveLeadInput" in screen and "parseArriveLead" in screen and "sendSetArrive" in screen
      and "arriveLeadInput = new EditBox(this.font, rowWaitBoxX(cx), arriveY, ROW_BOX_W, 20," in screen
      and "drawInputLabelAt(guiGraphics, ROW_WAIT_LABEL, cx + 4, arriveY)" in screen,
      "UI 有「等待秒数:」+「音量:」两个输入框（退出界面即应用，没有确认按钮）"
      "　★【1.22】去括号：原标签「到站前秒数(≤0)」→「等待秒数:」")
check(re.search(r"v > EscalatorSpeedData\.PSD_ARRIVE_SECONDS_MAX", screen) is not None,
      "★ UI 解析只挡正值（(-∞, 0]），与数据层 clamp 同口径")
check("sendSetArrive(null, lead)" in screen,
      "★ 秒数变了不发素材（null = 只改秒数、素材不动）—— 与 sendSetMidium 同形")
check("buildArrivePage" in screen and "pickArrive" in screen and "renderArrivePage" in screen,
      "进站报站有自己一个选择列表页（build / pick / render 三件套都在）")
check('Component.literal("选用")' in screen and 'Component.literal("删除")' in screen,
      "右列每个已导入音频都有「选用」+「删除」两个按钮")
_arr_page = re.search(r"private void buildArrivePage\((.*?)\n    \}", screen, re.S)
check(_arr_page is not None
      and 'Component.literal((arriveOffNow ? "✓" : "") + "不播")' in _arr_page.group(1),
      "进站报站列表页有「不播」出口"
      "　★【1.22-2】已从页底挪进**右列（已导入存档）第 0 行**，且该行只有「选用」没有「删除」")
check(re.search(r"boolean listPage = page >= 1;", screen) is not None
      and re.search(r"listTop = listPage \? LIST_TOP \+ ROW_H : LIST_TOP;", screen) is not None,
      "★【1.23】所有列表页（开门/关门/站台/进站）都整体下移一行为表头让位（listTop = listPage 那档）")

# ---- 播放端：进站报站 ----
check("private static final Map<Long, Arrive> arriveVoice = new HashMap<>();" in player,
      "★ 进站报站有**自己一张表**（不复用任何带停止条件的表）")
_av = re.search(r"private static final class Arrive\s*\{(.*?)\n    \}", player, re.S)
av = _av.group(1) if _av else ""
check(bool(av) and "PsdMusicInstance" not in av,
      "★★ 进站报站的状态类**不持播放实例**（源码级：不持句柄 ⇒ 结构上没有「谁能停它」）")
check("tickArriveAnnounce(mc, doors)" in player, "★ 进站报站有一条自己的 tick 路")
_rst = re.search(r"private static void reset\(Minecraft mc\)\s*\{(.*?)\n    \}", player, re.S)
rst = _rst.group(1) if _rst else ""
check("arriveVoice" not in rst,
      "★★ reset() 里**不出现** arriveVoice —— reset 的第三个来路恰好是「门全看不见」"
      "（列车进站那一刻），在那儿清一下功能立刻就废了")
check("arriveVoice.clear()" in player and "arrivePlatform.clear()" in player
      and "arriveLastPoll.clear()" in player,
      "onDisconnect 清进站报站那一套（只有换世界才清）")
check("MtrDwellAccess.nextArrivalRemainingMs" in player,
      "★ 触发读**时刻表**（不是猜列车位置/速度）—— 用户点名「看时刻表啊，不要猜」")
check("MtrDwellAccess.platformIdAt" in player, "认站台走 MtrDwellAccess.platformIdAt")
check("ARRIVE_SAME_TRAIN_MS" in player
      and "Math.abs(arrivalMs - state.firedArrival) <= ARRIVE_SAME_TRAIN_MS" in player,
      "★ 「同一班车只播一次」的守卫在（没有它，「还剩 |X| 秒到站」那个窗口内会每一 tick 重播一次）")
check("ARRIVE_POLL_TICKS" in player and "now - last < ARRIVE_POLL_TICKS" in player,
      "★ 查时刻表有节流（阈值以秒计，不该每 tick 都查）")
check(player.find("tickArrivalAnnounce(mc)") < player.find("doors.isEmpty()"),
      "★ tickArrivalAnnounce 排在 doors.isEmpty() 早退之前（原有一条，别被挤到后面）")
check(player.find("tickArriveAnnounce(mc, doors)") < player.find("if (doors.isEmpty())"),
      "★ tickArriveAnnounce 同样排在 doors.isEmpty() 早退之前")
check("resolveArriveTone" in player and "warnedMissingArrive" in player,
      "进站报站有自己的素材解析 + 缺库告警集合")

# ---- 反射层：MTR 到达缓存 ----
check("ArrivalsCacheClient" in dwell and "requestArrivals" in dwell and "getMillisOffset" in dwell,
      "★ MtrDwellAccess 接上 MTR 自己的到达缓存（PIDS / 列车时刻表传感器用的那一个）")
check("bindArrivals" in dwell and "读不到 MTR 到达缓存" in dwell,
      "★ 到达缓存这一层是**可选**的（读不到只让进站报站不响，不拖挂停站时长）")
check("ARRIVAL_PAST_MS" in dwell and "remaining < -ARRIVAL_PAST_MS" in dwell
      and "ARRIVAL_STALE_MS" not in dwell,
      "★ 算「最近的一班列车」时，**已经过站**超过 ARRIVAL_PAST_MS 的条目不算数"
      "（★ 不许再放宽成 60s 那种松值：刚进站的那班在时刻表里会一直压住后面那班，"
      "密集时刻表下整段漏播）")
check("public static final long ARRIVAL_PAST_MS = 5_000L" in dwell,
      "★ ARRIVAL_PAST_MS 是 public 常数 —— 播放端判「是不是已经晚了」用的就是它"
      "（同一件事只留一个数字，别两头各写一份）")
check("MtrDwellAccess.ARRIVAL_PAST_MS" in player and "ARRIVE_FIRE_GRACE_MS" not in player,
      "★ 播放端不再自带一份「宽限毫秒」，直接用 MtrDwellAccess.ARRIVAL_PAST_MS")
check("long thresholdMs = (long) (-thresholdSeconds) * 1000L;" in player
      and "remainMs > thresholdMs" in player,
      "★ 触发口径 = 「**最近一班车还剩 |X| 秒到站**」：thresholdMs = -X*1000，"
      "remainMs ≤ thresholdMs 就起播（X=-10 ⇒ 剩 10 秒到站时起播）")
check("leadMs" not in player,
      "★ 旧的 leadMs（把 X 当「提前几秒开门」讲）彻底消失 —— 口径只与**到站剩余时间**有关")
check("到站前 " in screen and "、到站前 " in main,
      "★ UI 状态行与指令反馈都按「到站前 N 秒」措辞"
      "（口径是**最近一班列车到站的时间** —— 这条由上面 remainMs/thresholdMs 那两条钉住；"
      "　★【1.22】去括号后不再带「（最近一班车还剩这么多秒到站时起播）」这类括注）")
check("提前开门提示音" not in screen and "提前秒数" not in screen and "提前秒数" not in main,
      "★ 「提前开门提示音 / 提前秒数」这种把 X 挂在开门时刻上的说法一个字都不许留")
check("public static long platformIdAt(double x, double y, double z)" in dwell,
      "MtrDwellAccess.platformIdAt 在")
check("matchDistance" in dwell and "bestUsable" in dwell,
      "★ platformIdAt 的认亲规则与 dwellMsAt 同一套（优先挑停站时长有效的站台）——"
      "否则「进站报站读的时刻表」与「关门提示音读的停站时长」会认到不同站台")

print("\n== 8. 构建产物 ==")
if not os.path.isfile(JAR):
    print("[SKIP] 未找到 %s，跳过打包校验（先跑 gradlew build）" % os.path.basename(JAR))
else:
    print("        检查 jar：%s" % os.path.basename(JAR))
    with zipfile.ZipFile(JAR) as z:
        names = set(z.namelist())
        for f in ("dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"):
            check("assets/smoothlift/sounds/audio/%s" % f in names, "jar 内含素材 %s" % f)
        check("assets/smoothlift/sounds/audio/psd_open.ogg" not in names
              and "assets/smoothlift/sounds/audio/psd_close.ogg" not in names,
              "jar 内不含旧的 psd_open.ogg / psd_close.ogg")
        blob = z.read("smooth/lift/client/PsdChimePlayer.class")
        check(b"psdToneCustomId" in blob, "PsdChimePlayer.class 里有 psdToneCustomId")
        check(b"mdoorclose" in blob, "PsdChimePlayer.class 里编进了 mdoorclose 事件名")
        blob2 = z.read("smooth/lift/EscalatorSpeedData.class")
        check(b"default-c" in blob2 and b"default-m" in blob2 and b"default-s" in blob2,
              "EscalatorSpeedData.class 里编进了 default-c / default-m / default-s 三个显式名字")
        # ---- 【1.17】到站播报 ----
        check(b"pbmmidium" in z.read("smooth/lift/SmoothLift.class"),
              "SmoothLift.class 里编进了 pbmmidium（指令真的注册了）")
        blob3 = z.read("smooth/lift/client/PsdChimePlayer.class")
        for tok in (b"arrivalVoice", b"planArrivalAnnounce", b"tickArrivalAnnounce",
                    b"resolveMidiumTone", b"warnedMissingMidium"):
            check(tok in blob3, "PsdChimePlayer.class 里编进了 %s（到站播报播放端）" % tok.decode())
        # ★★ 字节码级钉「谁都不许停它」：Arrival 内部类里若出现过 PsdMusicInstance，
        #    说明有人给它加了播放实例字段（下一步必然是「顺手 stop 它」），
        #    而这条需求的全部意义就是「没人能停它」。
        _arrival_entry = "smooth/lift/client/PsdChimePlayer$Arrival.class"
        if _arrival_entry in names:
            check(b"PsdMusicInstance" not in z.read(_arrival_entry),
                  "★★★ PsdChimePlayer$Arrival.class 里**没有** PsdMusicInstance 引用 —— "
                  "到站播报的「计划」不持播放实例，字节码级证明没有「谁能停它」的抓手")
        else:
            check(False, "找到 PsdChimePlayer$Arrival.class（内部类应当被单独编译出来）",
                  "jar 里没有 %s" % _arrival_entry)

print("\n== 8. 【1.23】三条「淡入淡出范围」指令（各存一份 + /pbmmusicround 是别名） ==")
# 四条指令都由 SmoothLift.roundCommand(...) 一处产出 ⇒ 形状必然一致。
for _nm in ("pbmround", "pbmmusicround", "pbmmidiumround", "pbmarriveround"):
    check(re.search(r'dispatcher\.register\(roundCommand\("%s", RoundKind\.' % _nm, main) is not None,
          "注册了 /%s（走 roundCommand(...) 唯一那条产出器，形状不会各自演化）" % _nm)
# ★★ /pbmmusicround 与 /pbmround 必须读**同一份数据**（都 Kind.TONE）
check(re.search(r'roundCommand\("pbmmusicround", RoundKind\.TONE\)', main) is not None,
      "★★ /pbmmusicround 与 /pbmround 同为 RoundKind.TONE ⇒ 读写**同一份数据**"
      "（用户点名的名字，不是第五份配置）")
check(re.search(r'roundCommand\("pbmmidiumround", RoundKind\.MIDIUM\)', main) is not None,
      "/pbmmidiumround = RoundKind.MIDIUM（自己的那份）")
check(re.search(r'roundCommand\("pbmarriveround", RoundKind\.ARRIVE\)', main) is not None,
      "/pbmarriveround = RoundKind.ARRIVE（自己的那份）")
# 形状 = 一个数值 + to + -f（与 /pbmround 同构），且只有**一处**产出器
check(re.search(r"private static LiteralArgumentBuilder<CommandSourceStack> roundCommand\("
                r"String literal, RoundKind kind\)", main) is not None,
      "roundCommand(literal, kind) 是四条指令的唯一产出器")
_rc = re.search(r"private static LiteralArgumentBuilder<CommandSourceStack> roundCommand\("
                r"String literal, RoundKind kind\)\s*\{(.*?)\n    \}", main, re.S)
if _rc:
    _body = _rc.group(1)
    check('.then(Commands.literal("to")' in _body, "roundCommand 里有 <X> to <Y> 分支")
    check("roundForce(kind)" in _body, "roundCommand 里挂了 -f 分支（roundForce(kind)）")
    check('Commands.literal("-f")' in main, "-f 字面量在（roundForce 里）")
# 五个 handler 都是 (context, kind) 参数化的 —— 旧的无参版必须**一个不剩**
for _old in ("pbmRoundShow", "pbmRoundGlobal", "pbmRoundFromTo",
             "pbmRoundForceAll", "pbmRoundForceFromTo", "pbmRoundForce("):
    check(_old not in main,
          "旧的 %s 已不存在（否则新老两套混在一起，改一半就是静默失效）" % _old)
for _new in ("roundShow", "roundGlobal", "roundFromTo", "roundForceAll", "roundForceFromTo"):
    check(re.search(r"%s\(CommandContext<CommandSourceStack> context, RoundKind kind\)" % _new,
                    main) is not None,
          "%s 收 (context, kind)" % _new)
# -f 的语义：setRoundAll / replaceRoundAll 要真的走「所有维度」
check("setRoundAll(source.getServer(), kind, round)" in main
      and "replaceRoundAll(source.getServer(), kind, from, to)" in main,
      "-f 两条分支走 setRoundAll / replaceRoundAll（所有维度）")
check("roundForceAll" in main and "roundForceFromTo" in main,
      "-f <值> 与 -f <X> to <Y> 两条分支都在")

print("\n== 9. 数据层：三份范围字段（默认都 = 16 ⇒ 老存档行为逐位不变） ==")
for _f in ("defaultPsdMidiumRound", "defaultPsdArriveRound"):
    check(re.search(r"public int %s = DEFAULT_" % _f, data) is not None,
          "EscalatorSpeedData 有字段 %s" % _f)
check(re.search(r"DEFAULT_PSD_MIDIUM_ROUND\s*=\s*DEFAULT_PSD_HELP_ROUND;", data) is not None
      and re.search(r"DEFAULT_PSD_ARRIVE_ROUND\s*=\s*DEFAULT_PSD_HELP_ROUND;", data) is not None,
      "★ 两个默认范围都**指向** DEFAULT_PSD_HELP_ROUND（16）"
      " ⇒ 没敲过新指令时行为与 1.22 逐位相同")
check(re.search(r"PSD_MIDIUM_ROUND_MIN\s*=\s*PSD_HELP_ROUND_MIN;", data) is not None
      and re.search(r"PSD_MIDIUM_ROUND_MAX\s*=\s*PSD_HELP_ROUND_MAX;", data) is not None,
      "到站范围夹取边界指向提示音那一对（1 ~ 128）")
check(re.search(r"PSD_ARRIVE_ROUND_MIN\s*=\s*PSD_HELP_ROUND_MIN;", data) is not None
      and re.search(r"PSD_ARRIVE_ROUND_MAX\s*=\s*PSD_HELP_ROUND_MAX;", data) is not None,
      "进站范围夹取边界同样 1 ~ 128")
check(re.search(r"static int clampPsdMidiumRound\(int round\)", data) is not None
      and re.search(r"static int clampPsdArriveRound\(int round\)", data) is not None,
      "两个 clampPsd*Round(int) 都在")
# NBT 兼容：写出去 + 读回来（旧存档没有这两个键 ⇒ 保持默认）
for _f in ("defaultPsdMidiumRound", "defaultPsdArriveRound"):
    check('tag.putInt("%s"' % _f in data, "NBT 写出 %s" % _f)
    check(re.search(r'if \(tag\.contains\("%s"\)\)' % _f, data) is not None,
          "★★ NBT 读 %s 有 contains 守卫（老存档没有这个键 ⇒ 保持默认，不是读成 0）" % _f)
# 同步包：维度包追加两格 + 客户端读两格 + apply 两参
check(re.search(r"buf\.writeVarInt\(pkt\.midiumRound\);", _pkt_chime) is not None
      and re.search(r"buf\.writeVarInt\(pkt\.arriveRound\);", _pkt_chime) is not None,
      "维度包把两份范围写出去（追加在最后两格）")
check(re.search(r"buf\.readVarInt\(\), buf\.readVarInt\(\)\)", _pkt_chime) is not None
      or re.search(r"midiumRound.*?arriveRound", _pkt_chime, re.S) is not None,
      "★★ 客户端按**同样顺序**读回两格（包上没有字段名，差一格就是静默错位）")
for _need in ("getPsdMidiumRound", "setDefaultPsdMidiumRound", "replaceDefaultPsdMidiumRound",
              "setDefaultPsdMidiumRoundAll", "replaceDefaultPsdMidiumRoundAll",
              "getPsdArriveRound", "setDefaultPsdArriveRound", "replaceDefaultPsdArriveRound",
              "setDefaultPsdArriveRoundAll", "replaceDefaultPsdArriveRoundAll"):
    check(_need in mgr, "Manager 有 %s" % _need)


# 【1.23】开门/关门按钮文案与尺寸（用户点名：开门提示音设置→开门提示、关门提示音设置→关门提示；与广播按钮同宽 96）
check('"开门提示"' in screen and '"关门提示"' in screen and "psdToneButtonLabel" in screen,
      "★【1.23】主界面按钮 = 开门提示 / 关门提示（去掉「音设置…」尾巴）")
check(re.search(r'Component\.literal\(psdToneButtonLabel\(which\)\), button -> \{', screen) is not None
      and re.search(r'\.bounds\(cx - BTN_W / 2, y, 96, 20\)', screen) is not None,
      "★ 按钮用 96 宽（与站台/进站广播按钮同尺寸）")
check('applyToneBox("open", openVolumeInput)' in screen
      and 'applyToneBox("close", closeVolumeInput)' in screen,
      "★【1.23】开门/关门音量框在主界面落地（applyMainInputs 统一提交）")
check('openWaitInput = waitBox' in screen and 'closeWaitInput = waitBox' in screen,
      "★【1.23】两行等待秒数框真的建在主界面（openWait/closeWait）")
check("setDoorPsdOpenWaitSeconds" in pkt_text("SetPsdOpenWaitPacket"),
      "✓ 服务端有开门等待通道接收器（setDoorPsdOpenWaitSeconds）")
check("parseAnyInt" in screen and "setMaxLength(i == 0 ? 11 : 6)" in screen,
      "★【1.23】开门等待框可填负（(-∞,+∞)），关门仍 [0,999999] 上限（6 位）")
check('if (page != 0)' in screen and 'page = 0;' in screen and 'init();' in screen,
      "★【1.23】屏蔽门 2 级菜单按 Esc = 返回主界面不落地（一级才落地退出）")
check("futiMusicNameSuggestions" in main and '"default"' in main and '"off"' in main
      and 'futiMusicNameSuggestions' in main,
      "★【1.23】/futimusic 补全含 default/off/内置/已导入，并挂到 name/to(-f 同)")

# 【1.23】主界面删总开关；开门/关门页改成与广播页同构，右列 = 不播 + 默认（开门1/关门2）+ 已导入
check('"总开关："' not in screen,
      "★【1.23】主界面「总开关」按钮已按点名删除（/pbmmusic on|off 指令仍在）")
check('rightStoredStartRow = 1 + defaultRowCount(which)' in screen,
      "开门/关门页右列从「不播 + 默认 + 已导入」之后开始（与广播页同一套行数口径）")
check('"默认（长）"' in screen and '"默认（短）"' in screen,
      "关门页默认行 = 默认（长）/默认（短）（开门页只有「默认」）")
check('return "close".equals(which) ? 2 : 1;' in screen,
      "默认行数：开门 1 / 关门 2")
check('pick(which, EscalatorSpeedData.PSD_TONE_OFF, false)' in screen
      and 'button -> importPending(id)' in screen,
      "左列点=只导入（与广播页同一语义）；右列选用走 pick(which, value, false)")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
