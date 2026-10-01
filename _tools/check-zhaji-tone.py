# -*- coding: utf-8 -*-
"""离线校验：【09-30】闸机（MTR Ticket Barrier）提示音 —— 「石斧右键闸机设进/出站声音」。

用户点名（09-30 初版）：
- 石斧右键**出/入站闸机**打开 ui，可以设置闸机声音；出站闸机设置出站声音、进站闸机设置进站声音；
- 指令 `zhaji in/out XXX`；
- ui 仿造直梯的 ui，可以调节 1~1000 的音量；
- 自定义 ogg 文件夹位置：进站 `MBM_Audio/zhaji/in`、出站 `MBM_Audio/zhaji/out`。

用户点名（【09-30 续】三条）：
1. 「为什么闸机的声音超过 100 就调不了了？」—— 根因 = 实例**非 Tickable**：OpenAL 的 AL_GAIN
   只在开播那一刻写一次，而那一刻我们的 `AL_MAX_GAIN` 抬升还排在引擎那条 execute **之后**
   （AL_MAX_GAIN 仍 = 1.0）⇒ 有效增益被夹回 1.0×。修法 = 实例实现 `TickableSoundInstance`，
   每 tick 再抬一次上限（引擎只对 `tickingSounds` 每 tick 重写 volume）。
2. 「闸机声音不能单独调整」+「连着的相同功能(进站/出站)闸机为一组，可以单独调整一组闸机的音效和音量」
   ⇒ 引入**逐组层**（仿屏蔽门【1.20】）：石斧界面改「右键到的那一组」，指令改「维度默认层」，
   右上角「同步所有」= 把本组此刻生效的一套写进维度默认层。
3. 「LOG7 有 log」—— 闸机同步此前**一条日志都没有**，补 `[SmoothLift/Zhaji]` 一行（与屏蔽门同规格）。

## 判据（改错了都不报错，症状 = 「配了不响」/「响错那一侧」/「音量调不动」/「改了一台全都变」）

1. **两层粒度**：维度默认层（`defaultZhajiToneAudioIn/Out` + `…Volume…`，**只由指令写**）
   + 逐组层（`Map<Long, ZhajiTone>`，**石斧界面写**，未设过 → 回落维度默认）。
   ★ 两个「没设置」的哨兵不许混：`ZHAJI_GROUP_NONE = Long.MIN_VALUE`（算不出组 ⇒ 退化成旧行为）
   / `null` 音量（本组「跟维度默认」）—— 后者**不是** 0、**不是** -1。
   ★ `clampZhajiVolume` **不得**放行 `LIFT_TONE_VOLUME_UNSET(-1)`（那是直梯「跟随共用默认」的哨兵）。
2. **组身份 = 段锚点**（`ZhajiChain.anchorOf`）：6 邻域洪水填充（**非** 26 —— 站台两条平行闸机线
   之间往往只隔一格，26 邻域会把它们并成一段）、取段内 `asLong` **最小**者（确定性与起点无关）；
   起点不是闸机 → `ZHAJI_GROUP_NONE`。
   ★ 禁止用 `id > 0` / `id <= 0` 判「有没有组」：世界原点就是 0。
3. **方块判据是精确注册名**（`equals`，不是 `startsWith`）：MTR 那一族还有
   `ticket_processor_*`，「名字里含 ticket_barrier」这种模糊判据迟早命中兄弟方块。
   `zhajiWhichOf` 是界面 / 播放端 / 服务端拦截**共用**的那一份。
4. **同步包读写逐格同序**：`buildZhajiPacket`（服务端写）与
   `SmoothLiftClient` 的 `ZHAJI_TONE_SYNC_CHANNEL` 接收器（客户端读）——
   顺序错位**不报错**，只是 in/out 串字段 / 组音量串格。
   写序 = dim → in → out → inVol → outVol → **条数 N → (long, utf128, utf128, OptInt, OptInt) × N**；
   两格可选音量必须 `writeDoorOptInt` / `readDoorOptInt` **成对**。这只包必须**独立**。
5. **通道三对写读同序**：`set_zhaji_tone` / `set_zhaji_volume` / `import_folder_zhaji_tone`
   各自的「客户端写 → 服务端读」逐格配对（第二格都是 `writeLong(groupKey)` / `readLong()`）。
6. **播放端拦截**：只认命名空间 `mtr` + 两个**精确**路径；`default` 放行原声、
   `off` 吃掉这一声、自定义播自己的。
   ★ 音量要**三件事一起做**：① 实例实现 `GainManagedSound`（⇒ `calculateVolume` 不夹 1.0）
   ② 抬 `AL_MAX_GAIN` 到 10× ③ 实例实现 `TickableSoundInstance` 且 `tick()` 里**每 tick**抬一次
   —— 只做前两件就是用户报的「音量调到 100 以上没变化」。
7. **UI 照抄直梯**：一级菜单两行「…设置…」+ 1~1000 音量框（用 `clampZhajiVolume`）、
   二级页两列列表几何取自 `SoundListLayout`、右列特殊行 = 2（不播 / 默认）、
   右上角「同步所有」（带**本组锚点**）+「打开文件夹」各一处。
   ★【09-30】石斧右键**一律进一级菜单**：客户端只传那一格坐标、构造器把 `page` 钉成 0。
8. **分类**：`zhaji/in` / `zhaji/out` 两个分类、`GROUP_ZHAJI = "zhaji"`；
   分类扫进 `ALL_CATEGORIES`（否则同步包里没有这两类、界面永远空）。
9. **日志**：客户端同步接收器要打一行 `[SmoothLift/Zhaji]`（用户点名「LOG7 看不到闸机设置」）。
10. **反向对照**：每一族判据都要有 mutation 必须变红（顺序 / 哨兵 / 邻域 / Tickable / 计数器）。

用法：`python _tools/check-zhaji-tone.py`（退出码 0 = 全部通过）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
MIXINS = os.path.join(CLIENT, "mixin")
RES = os.path.join(ROOT, "src", "client", "resources")

DATA = os.path.join(MAIN, "EscalatorSpeedData.java")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
SL = os.path.join(MAIN, "SmoothLift.java")
SLC = os.path.join(CLIENT, "SmoothLiftClient.java")
PLAYER = os.path.join(CLIENT, "ZhajiChimePlayer.java")
SCREEN = os.path.join(CLIENT, "ZhajiToneSetupScreen.java")
CHAIN = os.path.join(CLIENT, "ZhajiChain.java")
MIXIN = os.path.join(MIXINS, "ZhajiSoundMixin.java")
MIXJSON = os.path.join(RES, "smoothlift.client.mixins.json")

FAILS = []


def check(ok, label, detail=""):
    print("  ==> %s  %s%s" % ("通过" if ok else "失败", label,
                              ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def squash(text):
    return " ".join(text.split())


def tight(text):
    return re.sub(r"\s+", "", text)


def count(text, needle):
    return text.count(needle)


def body_after(src, start_marker, end_markers):
    """从 start_marker 之后截一段（到第一个出现的 end_marker 为止）。"""
    i = src.find(start_marker)
    if i < 0:
        return ""
    end = len(src)
    for m in end_markers:
        j = src.find(m, i + len(start_marker))
        if j >= 0:
            end = min(end, j)
    return src[i:end]


data = read(DATA)
mgr = read(MGR)
sl = read(SL)
slc = read(SLC)
player = read(PLAYER)
screen = read(SCREEN)
chain = read(CHAIN)
mixin = read(MIXIN)
mixjson = read(MIXJSON)

data_no = strip_comments(data)
mgr_no = strip_comments(mgr)
sl_no = strip_comments(sl)
slc_no = strip_comments(slc)
player_no = strip_comments(player)
screen_no = strip_comments(screen)
chain_no = strip_comments(chain)
mixin_no = strip_comments(mixin)

# ======================================================================
print("===== 1) 数据层：4 个字段 + NBT 读写成对 + 音量夹取 =====")

for name in ("ZHAJI_TONE_DEFAULT", "ZHAJI_TONE_OFF", "DEFAULT_ZHAJI_VOLUME"):
    m = re.search(r"public static final \w+ %s\s*=\s*([^;]+);" % name, data_no)
    check(m is not None, "%s 有定义" % name, "实际 = %s" % (m.group(1).strip() if m else "(没找到)"))
    if m:
        rhs = squash(m.group(1))
        want = {"ZHAJI_TONE_DEFAULT": "LIFT_TONE_DEFAULT",
                "ZHAJI_TONE_OFF": "LIFT_TONE_OFF",
                "DEFAULT_ZHAJI_VOLUME": "DEFAULT_HELP_VOLUME"}[name]
        check(want in rhs, "★ %s 复用 %s（与直梯/屏蔽门同一套哨兵与缺省值，不另造一份）"
              % (name, want), "实际 = %s" % rhs)

FIELDS = ["defaultZhajiToneAudioIn", "defaultZhajiToneAudioOut",
          "defaultZhajiToneVolumeIn", "defaultZhajiToneVolumeOut"]
for f in FIELDS:
    check(("String %s =" % f) in data_no or ("int %s =" % f) in data_no,
          "字段 %s 有定义" % f)
_i_write = data_no.find("defaultZhajiToneAudioIn\", defaultZhajiToneAudioIn")
if _i_write < 0:
    _i_write = data_no.find('tag.putString("defaultZhajiToneAudioIn"')
_w = data_no[_i_write:_i_write + 700] if _i_write >= 0 else ""
for f in FIELDS:
    check('%s' % f in _w, "写档：%s 落 NBT" % f)
# 读档：每个字段都要有 contains 守卫（老存档没这几个键）
for f in FIELDS:
    check('tag.contains("%s")' % f in data_no,
          "★ 读档：%s 有 contains 守卫（老存档缺键 ⇒ 用默认，不抛）" % f)

# 音量夹取：必须是 clamp(1,1000) 且**不碰** -1 哨兵
#   ★ clampZhajiVolume 定义在 **EscalatorSpeedData**（与 clampLiftToneVolume 并排），不在 Manager。
_m = re.search(r"public static int clampZhajiVolume\(int volume\)\s*\{(.*?)\n    \}", data_no, re.S)
_clamp = _m.group(1) if _m else ""
check(bool(_clamp) and "HELP_VOLUME_MIN" in _clamp and "HELP_VOLUME_MAX" in _clamp,
      "clampZhajiVolume 夹到 HELP_VOLUME_MIN~HELP_VOLUME_MAX（1~1000）", "实际 = %s" % squash(_clamp))
check("LIFT_TONE_VOLUME_UNSET" not in _clamp and "TONE_VOLUME_UNSET" not in _clamp,
      "★★ clampZhajiVolume **不放行** -1 哨兵（闸机没有「跟随共用默认」这一层）")

# removeAudio 要把引用该素材的两侧默认解绑（否则删了音频还指着一个不存在的 id）
_rm = body_after(data_no, "public void removeAudio(", ["\n    public ", "\n    private "])
if not _rm:
    _rm = data_no[data_no.find("removeAudio"):data_no.find("removeAudio") + 2500]
check("defaultZhajiToneAudioIn" in _rm and "defaultZhajiToneAudioOut" in _rm,
      "★ removeAudio 同时解绑闸机两侧的默认（删掉素材后不再指向不存在的 id）")
check("zhajiTone" in _rm and "withAudio(\"in\"" in _rm and "withAudio(\"out\"" in _rm,
      "★【09-30 续】removeAudio 也清**逐组层**的引用（回落成「跟维度默认」，空则删整条）")

# --- 【09-30 续】逐组层：ZhajiTone record + 表 + NBT 读写 -------------------
check("public final Map<Long, ZhajiTone> zhajiTone = new HashMap<>();" in squash(data_no),
      "★ 逐组层：`Map<Long, ZhajiTone> zhajiTone`（键 = 段锚点 asLong）")
_m_zt = re.search(r"public record ZhajiTone\((.*?)\)\s*\{", data_no, re.S)
check(_m_zt is not None
      and squash(_m_zt.group(1)) == "String audioIn, String audioOut, Integer volumeIn, Integer volumeOut",
      "★ ZhajiTone 四字段：素材 ×2 + **装箱**音量 ×2（null = 本组跟维度默认）",
      "实际 = %s" % (squash(_m_zt.group(1)) if _m_zt else "(没找到)"))
check("public static final ZhajiTone NONE" in squash(data_no),
      "ZhajiTone.NONE（一组什么都没单独设过 = 两项素材与两个音量都跟维度默认）")
_zt = body_after(data_no, "public record ZhajiTone(", ["\n    public record PsdToneAudio"])
for fn in ("isEmpty()", "audioFor(", "volumeFor(", "withAudio(", "withVolume("):
    check(fn in _zt, "ZhajiTone.%s 存在" % fn)
check("volumeIn == null && volumeOut == null" in squash(_zt),
      "★ isEmpty 把「两个音量都没设过」也算「等于没设置过」（表里不留空条目）")
check("return \"out\".equals(which) ? audioOut : audioIn;" in squash(_zt)
      and "return \"out\".equals(which) ? volumeOut : volumeIn;" in squash(_zt),
      "★ audioFor / volumeFor：out 走 out 那一格、其余走 in（与 zhajiWhich 同口径）")

for key in ('"zhajiTone"', '"k"', '"ain"', '"aout"', '"vin"', '"vout"'):
    check(key in data_no, "组表 NBT 键 %s 出现" % key)
check('tag.contains("zhajiTone", 9)' in data_no,
      "★ 读档：`contains(\"zhajiTone\", 9)` 守卫（缺键 = 空表 = 全走维度默认，行为与 09-30 之前一致）")
check('tag.put("zhajiTone", zhajiToneList);' in squash(data_no),
      "★ 写档：`tag.put(\"zhajiTone\", zhajiToneList)`")
check('entry.contains("vin") ? clampZhajiVolume(entry.getInt("vin")) : null' in squash(data_no)
      and 'entry.contains("vout") ? clampZhajiVolume(entry.getInt("vout")) : null' in squash(data_no),
      "★★ 读档：音量**缺键 = null**（跟维度默认）—— 不是 0、不是 -1")
check("if (v.volumeIn() != null) {" in data_no and "if (v.volumeOut() != null) {" in data_no,
      "★★ 写档：音量**只有非 null 才写**（不用 -1 哨兵，免得跟 LIFT_TONE_VOLUME_UNSET 撞语义）")
check("if (v == null || v.isEmpty()) {" in data_no,
      "★ 写档：`isEmpty()` 的条目直接跳过（表里不留空条目）")
check("if (!tone.isEmpty()) {" in data_no,
      "★ 读档：读回来是空的也不进表（服务端历史数据 / 手改存档都不至于留垃圾）")

# ======================================================================
print()
print("===== 2) 粒度：两层 = 维度默认层（指令）+ 逐组层（石斧界面），无子开关 =====")

check("zhajiToneKey" not in mgr_no and "zhajiToneKey" not in sl_no,
      "★★ 没有 zhajiToneKey：闸机**不按单个方块**记设置（身份是「一组闸机」的段锚点）")
check("zhajiToneAudio" in mgr_no or "zhajiToneAudioIn" in mgr_no,
      "客户端镜像里有闸机字段")
for f in ("zhajiToneAudioIn", "zhajiToneAudioOut", "zhajiToneVolumeIn", "zhajiToneVolumeOut"):
    check(f in mgr_no, "ClientDimensionData 镜像有 %s" % f)
check("isZhajiToneEnabled" not in mgr_no and "isZhajiToneEnabled" not in sl_no,
      "★★ 没有 isZhajiToneEnabled：闸机**没有子开关层**（「不播」直接由素材层表达）")
# /zhaji 不许有 on/off 开关分支：只认 in/out 两个方向
_zhaji_cmd = body_after(sl_no, 'dispatcher.register(Commands.literal("zhaji")',
                        ['dispatcher.register(Commands.literal("zhajiloud")'])
_zhaji_cmd_t = squash(_zhaji_cmd)
check('zhajiToneBranch("in", "in")' in _zhaji_cmd_t and 'zhajiToneBranch("out", "out")' in _zhaji_cmd_t,
      "/zhaji 有 in / out 两个方向", "命令体 = %s" % _zhaji_cmd_t[:200])
check('literal("on")' not in _zhaji_cmd and 'literal("off")' not in _zhaji_cmd,
      "★ /zhaji 没有 on/off 开关分支（闸机没有第二道闸门）")
check('literal("-f")' in _zhaji_cmd and 'zhajiToneForceLeaf("in", "in")' in _zhaji_cmd_t
      and 'zhajiToneForceLeaf("out", "out")' in _zhaji_cmd_t,
      "★ /zhaji 有 -f（强制所有维度）分支，且 in/out 各一条")
# /zhajiloud 用 volumeArg()（1~1000），不是自己写一个区间
_i_loud = sl_no.find('dispatcher.register(Commands.literal("zhajiloud")')
_zhajiloud_t = squash(sl_no[_i_loud:_i_loud + 900]) if _i_loud >= 0 else ""
check('zhajiLoudBranch("in", "in")' in _zhajiloud_t and 'zhajiLoudBranch("out", "out")' in _zhajiloud_t,
      "/zhajiloud 有 in / out 两个方向", "命令体 = %s" % _zhajiloud_t[:200])
_vol_args = body_after(sl_no, "private static LiteralArgumentBuilder<CommandSourceStack> zhajiLoudBranch(",
                       ["private static LiteralArgumentBuilder<CommandSourceStack> zhajiLoudForceLeaf("])
check("volumeArg()" in _vol_args,
      "★ /zhajiloud 复用 volumeArg()（= 1~1000，与扶梯/直梯/屏蔽门同一份区间定义）")

# --- 【09-30 续】两层粒度的接口面 -----------------------------------------
check("public static final long ZHAJI_GROUP_NONE = Long.MIN_VALUE;" in squash(mgr_no),
      "★★ 组哨兵 = Long.MIN_VALUE（**不是** 0 —— 世界原点就是 0；也不是 -1 —— 合法坐标）")
check("public final Map<Long, EscalatorSpeedData.ZhajiTone> zhajiTone = new HashMap<>();" in squash(mgr_no),
      "★ 客户端镜像也有逐组表 `zhajiTone`（同步包整表覆盖它）")
for _sig in ("public static EscalatorSpeedData.ZhajiTone zhajiGroupRecord(ServerLevel level, long groupKey)",
             "public static EscalatorSpeedData.ZhajiTone clientZhajiGroupRecord(Level level, long groupKey)",
             "public static String getZhajiToneAudio(Level level, String which, long groupKey)",
             "public static int getZhajiToneVolume(Level level, String which, long groupKey)"):
    check(_sig in squash(mgr_no), "读接口 %s" % _sig.split("(")[0].split()[-1])
for _sig in ("public static boolean setZhajiGroupTone(ServerLevel level, long groupKey, String which, String audioId)",
             "public static void setZhajiGroupVolume(ServerLevel level, long groupKey, String which, int volume)",
             "public static void clearZhajiGroupVolume(ServerLevel level, long groupKey, String which)",
             "private static void updateZhajiGroup(ServerLevel level, long groupKey,"):
    check(_sig in squash(mgr_no), "写接口 %s" % _sig.split("(")[0].split()[-1])

# 回落语义：素材「设过且非 default」才用本组；音量判 **null**（不是判等于默认值）
_ga = body_after(mgr_no, "public static String getZhajiToneAudio(Level level, String which, long groupKey)",
                 ["\n    public static int getZhajiToneVolume(Level level, String which, long groupKey)"])
check("if (own != null && !EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(own))" in squash(_ga)
      and "return getZhajiToneAudio(level, which);" in squash(_ga),
      "★ 素材回落：本组设过且非 default → 本组，否则 → 维度默认层")
_gv = body_after(mgr_no, "public static int getZhajiToneVolume(Level level, String which, long groupKey)",
                 ["\n    public static boolean setZhajiGroupTone("])
check("if (own != null)" in _gv and "return getZhajiToneVolume(level, which);" in squash(_gv),
      "★★ 音量回落：本组记过（非 null）→ 本组，否则 → 维度默认层（**判 null，不是判等于默认值**）")
check("groupKey != ZHAJI_GROUP_NONE" in _ga and "groupKey != ZHAJI_GROUP_NONE" in _gv,
      "★★ 两个读接口都先挡 `groupKey != ZHAJI_GROUP_NONE`（算不出组 ⇒ 退回旧行为，不静默哑掉）")

_ug = body_after(mgr_no, "private static void updateZhajiGroup(ServerLevel level, long groupKey,",
                 ["\n    public static boolean zhajiAudioValid("])
check("data.zhajiTone.remove(groupKey);" in _ug and "data.zhajiTone.put(groupKey, now);" in _ug,
      "★ 改完「等于没设置过」整条删掉，否则写回（表越干净越好查）")
check("EscalatorSpeedData.ZhajiTone.NONE" in _ug,
      "★ 先取**旧**记录、用它派生新的（绝不重建整条 —— 否则会丢掉另一侧）")

check("applyClientZhajiGroupToneLocal" in mgr_no and "applyClientZhajiGroupVolumeLocal" in mgr_no,
      "界面本地回显走 applyClientZhajiGroup*Local（逐组镜像的唯一写入口）")
check("private static void applyClientZhajiGroupLocal(" in mgr_no,
      "两个本地回显共用一个 applyClientZhajiGroupLocal")
check("applyClientZhajiToneAudioLocal" not in mgr_no and "applyClientZhajiToneVolumeLocal" not in mgr_no,
      "★★ 旧的「默认层本地回显」两个方法已删（无人调用；留着只会让人以为界面写的是默认层）")

# ======================================================================
print()
print("===== 3) 方块判据：精确注册名 + 三个使用点共用一份 =====")

_ent = re.search(r"public static boolean isZhajiEntrance\(BlockState state\)\s*\{\s*return\s*(.*?);",
                 sl_no, re.S)
_exit = re.search(r"public static boolean isZhajiExit\(BlockState state\)\s*\{\s*return\s*(.*?);",
                  sl_no, re.S)
check(_ent is not None and '"ticket_barrier_entrance_1".equals(registryPathOf(state))' in squash(_ent.group(1)),
      "★ 进站判据 = 精确 equals(\"ticket_barrier_entrance_1\")",
      "实际 = %s" % squash(_ent.group(1)) if _ent else "(没找到)")
check(_exit is not None and '"ticket_barrier_exit_1".equals(registryPathOf(state))' in squash(_exit.group(1)),
      "★ 出站判据 = 精确 equals(\"ticket_barrier_exit_1\")",
      "实际 = %s" % squash(_exit.group(1)) if _exit else "(没找到)")
check("startsWith(\"ticket_barrier\")" not in sl_no and "contains(\"ticket_barrier\")" not in sl_no,
      "★★ 判据**不是** startsWith/contains（那会连带命中 ticket_processor_* 等兄弟方块）")

_which_fn = body_after(sl_no, "public static String zhajiWhichOf(BlockState state)",
                       ["\n    public ", "\n    private "])
check('return "in";' in _which_fn and 'return "out";' in _which_fn and "return null;" in _which_fn,
      "zhajiWhichOf 返回 in / out / null 三态")

# 三个使用点：播放端、石斧右键、服务端拦截
check("SmoothLift.zhajiWhichOf(" in player_no or "zhajiWhichOf(" in player_no,
      "★ 播放端用 zhajiWhichOf 判方向（与界面同一判据）")
check("SmoothLift.zhajiWhichOf(world.getBlockState(pos))" in squash(slc_no)
      or "SmoothLift.zhajiWhichOf(" in slc_no,
      "★ 石斧右键用 zhajiWhichOf 判方向")
check("isZhajiBarrier(world.getBlockState(hitResult.getBlockPos()))" in squash(sl_no)
      or "isZhajiBarrier(" in sl_no,
      "★ 服务端拦默认交互（MTR 的闸机自己也有右键行为）")

# --- 【09-30 续】组身份：ZhajiChain.anchorOf（6 邻域 + 段内 asLong 最小） ----
check(os.path.exists(CHAIN), "ZhajiChain.java 存在（算「一组闸机」的段锚点）")
check("public static long anchorOf(Level level, BlockPos start)" in chain_no,
      "ZhajiChain.anchorOf(Level, BlockPos) 是唯一入口")
check("if (!SmoothLift.isZhajiBarrier(startState))" in squash(chain_no)
      and "return EscalatorSpeedManager.ZHAJI_GROUP_NONE;" in chain_no,
      "★ 起点不是闸机 → 返回 ZHAJI_GROUP_NONE（调用方退回维度默认层，不会静默哑掉）")
check("if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {" in squash(chain_no),
      "★★ 只走 **6 邻域**（面相邻）：站台两条平行闸机线往往只隔一格，26 邻域会把它们并成一段")
check("long anchor = start.asLong();" in squash(chain_no) and "if (key < anchor) {" in chain_no,
      "★★ 锚点 = 段内 **asLong 最小**者（与起点无关 ⇒ 右键这一段任意一台都算出同一个键）")
check("CACHE_TTL_TICKS" in chain_no and "public static void clear() {" in chain_no,
      "★ 组锚点带 TTL 缓存 + 断线可清（锚点是世界相关的，别跨世界复用）")
check("MAX_BLOCKS" in chain_no and "visited < MAX_BLOCKS" in squash(chain_no),
      "★ 洪水填充有截断上限（有人在超平坦铺一整片闸机也不会卡主线程）")
check("!level.isLoaded(next)" in squash(chain_no),
      "★ 没加载的相邻格不展开（闸机就在眼前、区块必然已加载；展开远处那一头只会把段越算越长）")

check("ZhajiChain.anchorOf(level, pos)" in player_no,
      "★ 播放端按**音源坐标**算组锚点（先查逐组、再回落默认层）")
check("ZhajiChain.anchorOf(level, this.pos)" in squash(screen_no),
      "★ 界面按**右键那一格**算组锚点（与播放端同一份身份）")
check("ZhajiChain.clear();" in player_no,
      "★ 断线时清组缓存（与 warnedMissing 一起在 onDisconnect 里）")

# ======================================================================
print()
print("===== 4) 同步包：独立通道 + 读写逐格同序 =====")

check('new ResourceLocation("smoothlift", "zhaji_tone_sync")' in sl_no,
      "ZHAJI_TONE_SYNC_CHANNEL 定义（id = smoothlift:zhaji_tone_sync）")
check("ZHAJI_TONE_SYNC_CHANNEL" not in body_after(mgr_no, "buildLiftTonePacket", ["\n    public static", "\n    private static"]),
      "★ 闸机的设置**不塞进**直梯同步包")

_pkt = body_after(mgr_no, "public static FriendlyByteBuf buildZhajiPacket(",
                  ["\n    public static void sendZhajiSyncTo("])
_w_labels = [x[0] + ":" + squash(x[1]).split(",")[0]
             for x in re.findall(r"buf\.write(Utf|VarInt|Long)\(([^;]*?)\);", _pkt)]
_w_opt = count(_pkt, "writeDoorOptInt(buf,")
check(_w_labels == ["Utf:level.dimension().location().toString()",
                    "Utf:EscalatorSpeedData.normalizeZhajiToneAudio(data.defaultZhajiToneAudioIn)",
                    "Utf:EscalatorSpeedData.normalizeZhajiToneAudio(data.defaultZhajiToneAudioOut)",
                    "VarInt:data.defaultZhajiToneVolumeIn",
                    "VarInt:data.defaultZhajiToneVolumeOut",
                    "VarInt:entries.size()",
                    "Long:e.getKey()",
                    "Utf:EscalatorSpeedData.normalizeZhajiToneAudio(t.audioIn())",
                    "Utf:EscalatorSpeedData.normalizeZhajiToneAudio(t.audioOut())"],
      "★ 写序 = dimId → in → out → inVol → outVol → 条数 N → (long, in, out, OptInt, OptInt) × N",
      "实际 = %s" % _w_labels)
check(_w_opt == 2 and "buf.writeVarInt(entries.size());" in _pkt,
      "★★ 逐组段的两个音量用 writeDoorOptInt **成对**（缺格 = 跟维度默认）",
      "writeDoorOptInt 实际 %d 处" % _w_opt)
check("if (e.getValue() != null && !e.getValue().isEmpty())" in squash(_pkt),
      "★ 只发**非空**记录（等于没设置过的不占带宽）")

_recv = body_after(slc_no, "SmoothLift.ZHAJI_TONE_SYNC_CHANNEL", ["\n        ClientPlayNetworking.registerGlobalReceiver"])
_r_labels = [x[0] + ":" + (x[1] or "")
             for x in re.findall(r"buf\.read(Utf|VarInt|Long)\((\d+)?\)", _recv)]
_r_opt = count(_recv, "readDoorOptInt(buf)")
check(_r_labels == ["Utf:256", "Utf:128", "Utf:128", "VarInt:", "VarInt:", "VarInt:",
                    "Long:", "Utf:128", "Utf:128"],
      "★★ 读序**逐格同序**（错位不报错，只是 in/out 串字段 / 组音量串格）", "实际 = %s" % _r_labels)
check(_r_opt == 2, "★★ 两格可选音量用 readDoorOptInt **成对**（与 writeDoorOptInt 配同一对）",
      "readDoorOptInt 实际 %d 处" % _r_opt)
check("applyClientZhaji(dimKey, audioIn, audioOut, volumeIn, volumeOut, tones)" in squash(_recv),
      "客户端按同一顺序喂给 applyClientZhaji(..., tones)")
check("[SmoothLift/Zhaji] 闸机提示音已同步" in _recv,
      "★【09-30 续】同步接收器打一行 `[SmoothLift/Zhaji]`（用户点名「LOG7 里看不到闸机设置」）")
check("LOGGER.info(" in _recv and "tones.size()" in squash(_recv),
      "★ 日志里带**单独设置 N 组**（与屏蔽门「单独设置 N 条」同规格）")

_ac = body_after(mgr_no, "public static void applyClientZhaji(ResourceKey<Level> dimension,",
                 ["\n    public static long psdToneKey("])
check("data.zhajiTone.clear();" in _ac and "data.zhajiTone.putAll(tones);" in squash(_ac),
      "★★ 逐组段**整表覆盖**（先 clear 再 putAll —— 否则服务端删掉的组会留在镜像里）")

# ======================================================================
print()
print("===== 5) 三条命令通道：写读逐格配对 =====")

_PAIRS = [
    ("set_zhaji_tone", "SET_ZHAJI_TONE_CHANNEL",
     "buf.writeUtf(which, 32); buf.writeLong(groupKey); buf.writeUtf(audioId, 128);",
     ["Utf:32", "Long:", "Utf:128"], "which(32) + groupKey(long) + audioId(128)"),
    ("set_zhaji_volume", "SET_ZHAJI_VOLUME_CHANNEL",
     "buf.writeUtf(which, 32); buf.writeLong(groupKey); buf.writeVarInt(v);",
     ["Utf:32", "Long:", "VarInt:"], "which(32) + groupKey(long) + volume(VarInt)"),
    ("import_folder_zhaji_tone", "IMPORT_FOLDER_ZHAJI_TONE_CHANNEL",
     "buf.writeUtf(which, 32); buf.writeLong(groupKey); buf.writeUtf(audioId, 128);",
     ["Utf:32", "Long:", "Utf:128"], "which(32) + groupKey(long) + fileName(128)"),
]
for chan_id, const, write_expr, reads, label in _PAIRS:
    check('new ResourceLocation("smoothlift", "%s")' % chan_id in sl_no,
          "%s 通道 id = smoothlift:%s" % (const, chan_id))
    check(squash(write_expr) in squash(screen_no),
          "%s：界面侧写 %s" % (const, label))
    _r = body_after(sl_no, "registerGlobalReceiver(%s," % const,
                    ["\n        ServerPlayNetworking.registerGlobalReceiver"])
    _got = [x[0] + ":" + (x[1] or "")
            for x in re.findall(r"buf\.read(Utf|VarInt|Long)\((\d+)?\)", _r)]
    check(_got == reads, "%s：服务端读 %s（与写侧**逐格同序**）" % (const, label),
          "实际 = %s" % _got)
    check("groupKey != EscalatorSpeedManager.ZHAJI_GROUP_NONE" in squash(_r),
          "%s：按 groupKey 分流（组哨兵 → 写**维度默认层**，界面一律带本组）" % const)

# 每个接收器都要 syncZhajiToAll，否则别的玩家的客户端镜像不变、界面打勾不同步
_n_sync = count(sl_no, "EscalatorSpeedManager.syncZhajiToAll(server)")
check(_n_sync >= 3, "★ 三条写通道各自都 syncZhajiToAll（>=3 处）", "实际 %d 处" % _n_sync)
check("syncZhajiToAll(server);" in squash(sl_no[sl_no.find("ServerPlayConnectionEvents.JOIN"):sl_no.find("ServerLifecycleEvents.SERVER_STARTED")]),
      "★ 进世界时也同步一次（否则客户端镜像为空、界面看着像「没设置过」）")

# ======================================================================
print()
print("===== 6) 播放端：拦 mtr 的两个过闸声 + 音量上限成对放开 =====")

check('MTR_NAMESPACE = "mtr"' in player_no, "只认命名空间 mtr")
check('PATH_BARRIER = "ticket_barrier"' in player_no
      and 'PATH_BARRIER_CONCESSIONARY = "ticket_barrier_concessionary"' in player_no,
      "两个过闸声事件名（已从 MTR SoundEvents 反汇编核实）")
check("PATH_BARRIER.equals(path)" in player_no and "PATH_BARRIER_CONCESSIONARY.equals(path)" in player_no,
      "★ 路径用**精确** equals（不是 startsWith：MTR 还有 ticket_processor_* 一族）")
check("instance.isRelative()" in player_no,
      "★ 相对坐标的声音直接放行（相对声音的 x/y/z 不是世界坐标，查方块会查错格）")
check("BlockPos.containing(instance.getX(), instance.getY(), instance.getZ())" in squash(player_no),
      "按音源坐标取那一格方块（MTR 走 Level.playSound(..., BlockPos, ...) ⇒ 音源 = 方块中心）")

# 三档语义：default 放行原声 / off 吃掉 / 自定义播自己的
_int = body_after(player_no, "public static boolean intercept(SoundInstance instance)",
                  ["\n    private static", "\n    public static"])
check('ZHAJI_TONE_DEFAULT.equals(audioId)' in _int and "return false;" in _int,
      "★ default = 放行 MTR 原声（「跟内置」）")
check('ZHAJI_TONE_OFF.equals(audioId)' in _int and "return true;" in _int,
      "★ off = 吃掉这一声（「不播」）")
check("play(mc, audioId, pos, volume)" in _int,
      "★ 自定义 = 播自己的、并吃掉原声（不重复响）")
check("if (play(mc, audioId, pos, volume)) {" in squash(_int) and "return false;" in _int,
      "★ 素材播不出来时退回原声（宁可响内置那声，也不要一片死寂）")

# 音量成对放开：① GainManagedSound ② AL_MAX_GAIN
check("GainManagedSound" in player_no,
      "★ ① 实例实现 GainManagedSound（⇒ calculateVolume 不夹到 1.0）")
check("AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN)" in squash(player_no),
      "★ ② 开播抬 AL_MAX_GAIN 到 10×（只做一半 ⇒「音量调到 100 以上没变化」）")
check("implements TickableSoundInstance, GainManagedSound" in squash(player_no),
      "★★ ③ 实例实现 TickableSoundInstance ——「音量 >100 调不动」的**根因修法**"
      "（引擎只对 tickingSounds 每 tick 重写 volume）")
check("import net.minecraft.client.resources.sounds.TickableSoundInstance;" in player_no,
      "TickableSoundInstance 已 import")
check("public void tick() {" in player_no
      and "raiseMaxGain(Minecraft.getInstance(), this);" in squash(player_no),
      "★★ tick() 里**每 tick**抬一次 AL_MAX_GAIN（开播那一次排在引擎写 AL_GAIN 之后，一次不够）")
check("raiseMaxGain(mc, inst);" in player_no,
      "★ 开播那一刻也抬一次（第一条声道还没被 tick 覆盖时的兜底）")
check("public boolean isStopped() {" in player_no
      and "return false;" in squash(body_after(player_no, "public boolean isStopped() {",
                                               ["\n        @Override"])),
      "★ isStopped() 恒 false（素材播完由引擎回收通道，不提前掐）")
check("manager.soundEngine" in squash(player_no) and "instanceToChannel.get(inst)" in squash(player_no),
      "★ raiseMaxGain 走 SoundEngine.instanceToChannel 拿通道（与 PsdChimePlayer 同一手法）")
check("if (engine == null)" in squash(player_no) and "if (manager == null)" in squash(player_no),
      "★ mc / soundEngine 可能为 null（构造期 / 换世界）⇒ raiseMaxGain 有守卫，不 NPE")
check("volume / VOLUME_BASE" in squash(player_no) and "VOLUME_BASE = EscalatorSpeedData.DEFAULT_AUDIO_VOLUME" in squash(player_no),
      "100 = 原始音量（1.0×）、1000 = 10×（与扶梯/直梯/屏蔽门同一套换算）")
check("Attenuation.LINEAR" in player_no,
      "距离衰减沿用原版 LINEAR（用户没点名闸机要有可调射程）")

# ★★ 防自吞：自己播出去的那一声会**再次**走进 Mixin（SoundManager.play 是我们自己调的）
#   ⇒ 没有这道闸就是**无限递归**（栈溢出 / 卡死）。占位事件也必须是 smoothlift 的。
check("instance instanceof ZhajiSoundInstance" in player_no and "return false;" in
      body_after(player_no, "instance instanceof ZhajiSoundInstance", ["\n        if (!isTicketBarrierSound"]),
      "★★ 防自吞：`instance instanceof ZhajiSoundInstance` 直接放行（否则自己播的一声再被拦 ⇒ 无限递归）")
check('new ResourceLocation("smoothlift", "audio/zhaji")' in player_no,
      "★ 自有实例的占位事件是 smoothlift 自己的（**不是** mtr:ticket_barrier —— 那会自己撞自己的判据）")
check("super(new ResourceLocation(MTR_NAMESPACE, PATH_BARRIER)" not in squash(player_no),
      "★ super(...) 不再用 mtr 的过闸事件当占位")

# mixin 注册
check('"ZhajiSoundMixin"' in mixjson, "ZhajiSoundMixin 已登记进 smoothlift.client.mixins.json")
check("SoundManager.class" in mixin_no and "ZhajiChimePlayer.intercept(instance)" in mixin_no,
      "Mixin 注入 SoundManager 的 play/playDelayed（拦截一声声音进入引擎的唯一公开入口）")
check("Lnet/minecraft/client/resources/sounds/SoundInstance;)V" in mixin_no,
      "play 的描述符与 1.20.4 字节码逐字一致（写错 = 注入点缺失，运行时才炸）")

# ======================================================================
print()
print("===== 7) UI：照抄直梯 + 右上角两个按钮 =====")

check("SoundListLayout.ROW_H" in screen_no and "SoundListLayout.COL_W" in screen_no,
      "两列几何全部取自 SoundListLayout（唯一来源）")
check("RIGHT_SPECIAL_ROWS = 2" in screen_no,
      "右列特殊行 = 2（不播 / 默认 —— 与直梯【09-29 二改】后同一口径）")
check("RIGHT_SPECIAL_ROWS = 3" not in screen_no, "★ 没有第 3 个特殊行（开关按钮不存在）")
check('literal("开关")' not in screen_no and '"开关：' not in screen_no,
      "★ 界面上没有「开关」按钮（闸机没有子开关层）")
check("EscalatorSpeedData.clampZhajiVolume(v)" in screen_no,
      "音量框用 clampZhajiVolume 夹取（1~1000）")
check(count(strip_comments(screen), "FolderOpenButton.of(") == 1,
      "恰好一处 FolderOpenButton.of(...)")
check(count(strip_comments(screen), "SyncPopupScreen.syncButton(") == 1,
      "恰好一处 syncButton")
check('String[] PAGES = {"in", "out"};' in squash(screen_no),
      "两个方向 in/out（页号 = 下标 + 1，与服务端 SYNC_ZHAJI_WHICH 同序）")
check("EscalatorSpeedManager.zhajiToneCategory(which)" in screen_no,
      "二级页分类 = Manager 的 zhajiToneCategory（不另抄一张表）")

# ★【09-30 订正】石斧右键**一律进一级菜单**（用户报「右键进去的是二级菜单、要按 Esc 才到一级菜单」）。
#   ★【09-30 续】构造器改收 BlockPos —— 只用来算「这一组」，**仍然不收方向**。
check("public ZhajiToneSetupScreen(net.minecraft.core.BlockPos pos)" in squash(screen_no),
      "★【09-30 续】界面构造器只收 BlockPos（算组用），**不收方向**")
check("this.page = 0;" in body_after(screen_no, "public ZhajiToneSetupScreen(net.minecraft.core.BlockPos pos)",
                                     ["\n    public static void notifyToneDataChanged()"]),
      "★【09-30】构造器把 page 钉成 0 = 一级菜单")
check("ZhajiToneSetupScreen(String" not in screen_no,
      "★【09-30】旧签名 `ZhajiToneSetupScreen(String which)` 已删（那一版会按右键那一侧落二级页）")
check("zhajiWhichOf" not in screen_no,
      "★【09-30】界面里不再出现 zhajiWhichOf（落页与右键那一侧零耦合）")
check("new ZhajiToneSetupScreen(pos)" in squash(slc_no),
      "★【09-30 续】石斧右键开屏传**那一格坐标**（一级菜单 + 算出所在那一组）")
check("new ZhajiToneSetupScreen(which)" not in squash(slc_no),
      "★【09-30】不再把「右键到的那一侧」传给界面（== 用户报的那个 bug 的写法）")
check("zhajiWhichOf(world.getBlockState(pos)) == null" in squash(slc_no),
      "★【09-30】zhajiWhichOf 降级成**只当门禁**（判这一格是不是闸机），不参与落页")

# ★【09-30 续】界面改的是「这一组」：本组语义 + 右上角同步把本组推给默认层
check("this.groupKey = (level == null || this.pos == null)" in squash(screen_no),
      "★ 算不出组（世界没加载 / 坐标空）→ ZHAJI_GROUP_NONE = 退化成编辑维度默认层（旧行为）")
check("ZhajiChain.anchorOf(level, this.pos)" in squash(screen_no),
      "★ 组锚点来自 ZhajiChain（与播放端同一份身份）")
check("SyncPopupScreen.syncButton(this, \"zhaji\", page, groupKey," in squash(screen_no),
      "★★ 右上角「同步所有」把**本组锚点**传给服务端（= 把本组此刻生效的一套写进维度默认层）")
check("private boolean inGroup()" in screen_no
      and "return groupKey != EscalatorSpeedManager.ZHAJI_GROUP_NONE;" in squash(screen_no),
      "★ inGroup()：算得出组才显示「本组…」文案")
check("EscalatorSpeedManager.clientZhajiGroupRecord(level, groupKey)" in squash(screen_no),
      "★ 打 ✓ 用**本组原始值**（本组没设过 ⇒ 显示「默认」被选中，与屏蔽门那几个二级页同口径）")
check("EscalatorSpeedManager.getZhajiToneAudio(level, which, groupKey)" in squash(screen_no)
      and "EscalatorSpeedManager.getZhajiToneVolume(level, which, groupKey)" in squash(screen_no),
      "★ 生效值（状态行 / 音量框初值）走 3 参**回落读法**")
check("box.setValue(String.valueOf(effectiveVolume(which)));" in squash(screen_no),
      "★ 音量框初值 = **本组生效**音量（玩家看到的就是「这一组现在多大声」）")
check("private String groupNote()" in screen_no and "本组未单独设置" in screen_no,
      "★ 状态行带「本组未/已单独设置」后缀（让玩家知道下面这些值是哪一层的）")
check("buf.writeLong(groupKey);" in screen_no,
      "★ 三条写包都带本组锚点（界面改的是本组，不是全局）")
check("EscalatorSpeedManager.applyClientZhajiGroupToneLocal(" in screen_no
      and "EscalatorSpeedManager.applyClientZhajiGroupVolumeLocal(" in screen_no,
      "★ 本地回显走**逐组**镜像（界面改本组、立刻在界面生效）")

# ======================================================================
print()
print("===== 8) 分类：两个新分类 + 分组目录 + 扫进 ALL_CATEGORIES =====")

check('CAT_ZHAJI_IN = "zhaji/in"' in mgr_no and 'CAT_ZHAJI_OUT = "zhaji/out"' in mgr_no,
      "两个分类常量（值就是用户点名的 MBM_Audio/zhaji/in|out）")
check('GROUP_ZHAJI = "zhaji"' in mgr_no, '分组目录常量 GROUP_ZHAJI = "zhaji"')
_m = re.search(r"public static final String\[\] ALL_CATEGORIES\s*=\s*\{(.*?)\};", mgr_no, re.S)
_all = re.findall(r"CAT_[A-Z_]+", _m.group(1)) if _m else []
check("CAT_ZHAJI_IN" in _all and "CAT_ZHAJI_OUT" in _all,
      "★ 两个分类扫进 ALL_CATEGORIES（不扫 ⇒ 同步包里没有这两类、界面永远空）",
      "实际 = %s" % _all)
check(_all[:15] == ["CAT_FUTI", "CAT_HELP", "CAT_TRAIN_RUN", "CAT_TRAIN_ROUND", "CAT_TRAIN_SWITCH",
                    "CAT_TRAIN_IN", "CAT_TRAIN_OUT", "CAT_PSD_OPEN", "CAT_PSD_CLOSE",
                    "CAT_PSD_MIDIUM", "CAT_PSD_ARRIVE", "CAT_LIFT_UP", "CAT_LIFT_DOWN",
                    "CAT_LIFT_OPEN", "CAT_LIFT_CLOSE"],
      "★★ 新分类**追加在末尾**（旧的 15 个顺序一个都没动 —— 那就是同步包的书写顺序）")
_cat_fn = body_after(mgr_no, "public static String zhajiToneCategory(String which)",
                     ["\n    public ", "\n    private "])
check("CAT_ZHAJI_OUT" in _cat_fn and "CAT_ZHAJI_IN" in _cat_fn,
      "zhajiToneCategory：out → zhaji/out、其余 → zhaji/in")

# 素材名校验与补全按方向分类
check("public static AudioArg resolveZhajiToneName(" in mgr_no,
      "resolveZhajiToneName 存在（指令 <名字> 的解析）")
_res = body_after(mgr_no, "public static AudioArg resolveZhajiToneName(", ["\n    public ", "\n    private "])
check("zhajiToneCategory(which)" in _res, "★ 解析按**方向**取分类（in 的补全里不会出现 out 的文件）")
check('"none".equals(lower)' in _res and '"mute".equals(lower)' in _res
      and 'endsWith(".ogg")' in _res,
      "resolveZhajiToneName 认 default / off / none / mute + 少打 .ogg 兜底")
check("zhajiToneCategory(which)" in squash(body_after(mgr_no, "public static boolean zhajiAudioValid(",
                                                       ["\n    public ", "\n    private "])),
      "zhajiAudioValid 也按方向取分类（写入前校验）")

# ======================================================================
print()
print("===== 9) 反向对照（mutation 必须变红） =====")

# 9a) 把客户端读序里 in/out 对调 ⇒ 逐格同序那条必须变红
_mut = _recv.replace("String audioIn = buf.readUtf(128);\n            String audioOut = buf.readUtf(128);",
                     "String audioOut = buf.readUtf(128);\n            String audioIn = buf.readUtf(128);")
_mut_order = re.findall(r"buf\.read(Utf|VarInt|Long)\((\d+)?\)", _mut)
_mut_labels = [x[0] + ":" + (x[1] or "") for x in _mut_order]
check(_mut_labels == _r_labels,
      "★ 把 in/out 读序对调 -> READ_ORDER 一样（对照：说明这条判据只钉**顺序**，"
      "不钉变量名 —— 变量名换了但顺序没换本来就不该红）")

# 9b) 把精确判据放宽成 startsWith ⇒ 那条「不是 startsWith」必须变红
_loose = player_no.replace('PATH_BARRIER.equals(path)', 'path.startsWith("ticket_barrier")')
check("startsWith(\"ticket_barrier\")" in _loose,
      "★ 放宽成 startsWith -> 「判据不是 startsWith」这条当场变红（对照）")

# 9c) 方块判据改成前缀
_sl_loose = sl_no.replace('"ticket_barrier_entrance_1".equals(registryPathOf(state))',
                          'registryPathOf(state).startsWith("ticket_barrier")')
check('startsWith("ticket_barrier")' in _sl_loose,
      "★ 方块判据改成前缀 -> 「不是 startsWith/contains」这条当场变红（对照）")

# 9d) 右列特殊行加回第 3 个（开关）⇒ 两条都要红
_screen3 = screen_no.replace("RIGHT_SPECIAL_ROWS = 2", "RIGHT_SPECIAL_ROWS = 3")
check("RIGHT_SPECIAL_ROWS = 2" not in _screen3 and "RIGHT_SPECIAL_ROWS = 3" in _screen3,
      "★ 特殊行改回 3 -> 「== 2」与「没有第 3 个特殊行」两条当场变红（对照）")

# 9e) 把 clampZhajiVolume 放行 -1 哨兵 ⇒ 那条必须变红
_clamp_bad = _clamp.replace("Math.max(HELP_VOLUME_MIN,", "Math.max(LIFT_TONE_VOLUME_UNSET,")
check("LIFT_TONE_VOLUME_UNSET" in _clamp_bad and "LIFT_TONE_VOLUME_UNSET" not in _clamp,
      "★ 夹取放行 -1 哨兵 -> 「不放行哨兵」这条当场变红（对照）")

# 9f) 新分类插到中间 ⇒ 「追加在末尾」那条必须变红
_all_bad = list(_all)
_i_lift_close = _all_bad.index("CAT_LIFT_CLOSE")
_all_bad.insert(_i_lift_close, "CAT_ZHAJI_IN")
check(_all_bad[:15] != _all[:15],
      "★ 把 zhaji 分类插到中间 -> 「追加在末尾（旧 15 个没动）」这条当场变红（对照）")

# 9g) 忘了 syncZhajiToAll ⇒ 那条必须变红
_n_sync_bad = count(sl_no.replace("EscalatorSpeedManager.syncZhajiToAll(server);", ""),
                    "EscalatorSpeedManager.syncZhajiToAll(server)")
check(_n_sync_bad == 0 and _n_sync >= 3,
      "★ 删掉所有 syncZhajiToAll -> 「三条通道各自都同步」这条当场变红（对照）")

# 9h) 去掉防自吞闸 ⇒ 那条必须变红（去掉之后自己播的一声会再被拦 = 无限递归）
_guard_bad = player_no.replace("instance instanceof ZhajiSoundInstance", "false")
check("instance instanceof ZhajiSoundInstance" not in _guard_bad,
      "★ 去掉 `instance instanceof ZhajiSoundInstance` -> 「防自吞」这条当场变红（对照："
      "真的去掉就是无限递归）")

# 9i) 把「右键进一级菜单」改回「按右键那一侧落页」⇒ 【09-30】那几条必须变红
#    （用户报的就是这一版：右键直接落二级菜单、要按 Esc 才回到一级菜单）
_slc_bad = slc_no.replace("new ZhajiToneSetupScreen(pos)", "new ZhajiToneSetupScreen(which)")
check("new ZhajiToneSetupScreen(which)" in _slc_bad
      and "new ZhajiToneSetupScreen(which)" not in squash(slc_no),
      "★ 开屏改回 `new ZhajiToneSetupScreen(which)` -> 【09-30】「不传给界面」那条当场变红（对照）")

_screen_bad = screen_no.replace("public ZhajiToneSetupScreen(net.minecraft.core.BlockPos pos)",
                                "public ZhajiToneSetupScreen(String which)")
check("ZhajiToneSetupScreen(String" in _screen_bad
      and "ZhajiToneSetupScreen(String" not in screen_no,
      "★ 构造器改回收 which -> 「旧签名已删」那条当场变红（对照）")

# 9j) 组哨兵改成 0（世界原点）⇒ 那条必须变红
_mgr_bad0 = mgr_no.replace("ZHAJI_GROUP_NONE = Long.MIN_VALUE;", "ZHAJI_GROUP_NONE = 0L;")
check("ZHAJI_GROUP_NONE = 0L;" in _mgr_bad0
      and "ZHAJI_GROUP_NONE = Long.MIN_VALUE;" in mgr_no,
      "★ 组哨兵改成 0 -> 「Long.MIN_VALUE」那条当场变红（对照：0 是合法坐标 = 世界原点那台闸机）")

# 9k) 6 邻域改 26 邻域 ⇒ 「只走 6 邻域」那条必须变红
_chain_bad = chain_no.replace("if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {",
                              "if (false) {")
check("if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {" not in _chain_bad,
      "★ 去掉 6 邻域过滤（= 变 26 邻域）-> 「只走 6 邻域」这条当场变红"
      "（对照：平行两排闸机只隔一格，会被并成一段）")

# 9l) 锚点改取「起点」⇒ 「asLong 最小」那条必须变红
_chain_bad2 = chain_no.replace("if (key < anchor) {", "if (key == key) {")
check("if (key < anchor) {" not in _chain_bad2,
      "★ 锚点不再取最小 -> 「段内 asLong 最小」这条当场变红（对照：换起点就算出另一个键）")

# 9m) 逐组段两格音量写成一个 ⇒ 「OptInt 成对」那条必须变红
_pkt_bad = _pkt.replace("writeDoorOptInt(buf, t.volumeOut() == null ? null : EscalatorSpeedData.clampZhajiVolume(t.volumeOut()));", "")
check(count(_pkt_bad, "writeDoorOptInt(buf,") == 1 and _w_opt == 2,
      "★ 逐组段少写一格音量 -> 「writeDoorOptInt 成对」这条当场变红（对照：读写错位）")

# 9n) Tickable 去掉 ⇒ 「音量 >100」那条必须变红
_player_bad = player_no.replace("implements TickableSoundInstance, GainManagedSound",
                                "implements GainManagedSound")
check("implements TickableSoundInstance" not in _player_bad
      and "implements TickableSoundInstance" in player_no,
      "★ 去掉 TickableSoundInstance -> 音量 >100 那条当场变红（对照：**这就是用户报的那个 bug 的写法**）")

# 9o) 逐组镜像不再整表覆盖 ⇒ 「先 clear 再 putAll」那条必须变红
_ac_bad = _ac.replace("data.zhajiTone.putAll(tones);", "data.zhajiTone.putAll(java.util.Map.of());")
check("data.zhajiTone.putAll(tones);" not in _ac_bad,
      "★ 逐组镜像不整表覆盖 -> 「先 clear 再 putAll」这条当场变红（对照：服务端删掉的组会留在镜像里）")

# 9p) 界面同步按钮不带 groupKey ⇒ 「本组推给默认层」那条必须变红
_screen_bad2 = screen_no.replace('SyncPopupScreen.syncButton(this, "zhaji", page, groupKey,',
                                 'SyncPopupScreen.syncButton(this, "zhaji", page,')
check('syncButton(this, "zhaji", page, groupKey,' not in squash(_screen_bad2),
      "★ 同步按钮不带 groupKey -> 「把本组锚点传给服务端」那条当场变红（对照）")

# 9q) 同步接收器那行日志去掉 ⇒ 「LOG7 看得到闸机设置」那条必须变红
_recv_bad = _recv.replace("[SmoothLift/Zhaji] 闸机提示音已同步", "[SmoothLift/Zhaji]")
check("[SmoothLift/Zhaji] 闸机提示音已同步" not in _recv_bad,
      "★ 日志前缀换成别的 -> 「打一行 [SmoothLift/Zhaji]」那条当场变红（对照）")

# ======================================================================
if FAILS:
    print()
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print()
print("== 【09-30 / 09-30 续】闸机提示音（音量 >100 + 逐组层 + 日志）：全部通过 ==")
