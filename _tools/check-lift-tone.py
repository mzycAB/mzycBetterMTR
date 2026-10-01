# -*- coding: utf-8 -*-
"""离线校验：【1.45】石斧右键直梯楼层轨道、四列表换直梯提示音（up / down / open / close）。

## 需求（用户原话归纳）

    石斧右键直梯楼层轨道可以更换直梯无障碍提示音（上楼提示音、下楼提示音、开门提示音、关门提示音），
    分别 4 个列表；导入的 ogg 放在 MBM_Audio/lift/<项> 文件夹里（复用扶梯那套导入机制）。

## 设计要点（脚本要钉住的四条不变量）

1. **「哪条直梯」没有稳定 ID**：直梯 ID 跨重启会变，所以用「楼层轨道所在竖井的那一列
   (X, Z)」当身份 —— 一条直梯的所有楼层轨道共享 X/Z、只有 Y 不同 ⇒ key = `BlockPos.asLong(x, 0, z)`。
   石斧右键**任意**一层的楼层轨道都定位到同一个 key。
2. **四列表各自独立**：`up`（准备向上）/ `down`（准备向下）/ `open`（开门）/ `close`（关门）。
   取值三种语义：`default`（内置素材）/ `off`（这条不播）/ 音频库文件名（从本分类子文件夹导入）。
3. **「待导入」要真的先导入**：列表里本分类文件夹中的文件还没进音频库，
   点它必须走导入通道（IMPORT_FOLDER_LIFT_TONE_CHANNEL），点已入库的文件才走设置通道
   （SET_LIFT_TONE_CHANNEL）—— 不然服务端会以「音频不存在」拒绝。
4. **播放端按竖井列查素材**：LiftChimePlayer 播开关门（open/close）与准备移动（up/down）时，
   用「最近直梯的位置 → 竖井列 key」去查客户端镜像；`default`→内置事件、`off`→静默跳过、
   其它→`injectAudio` 自定义分支。删除音频时引用它的那一项要退化成默认（removeAudio 里处理）。
   ★【1.15】改成**两层查找**：竖井列那一层是 `default`（= 跟维度默认）时，回落到
   **维度默认素材**（`/lifthelp up|down|open|close <名字>` 设的），维度默认再是 default 才是内置素材。

用法：`python _tools/check-lift-tone.py`（退出码 0 = 全部通过）
"""
import glob
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION = re.search(r'^mod_version=(.+)$', open(os.path.join(ROOT, 'gradle.properties'), encoding='utf-8').read(), re.M).group(1).strip()  # 【1.23】版本号断言不再写死

MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
DATA = os.path.join(MAIN, "EscalatorSpeedData.java")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
SL = os.path.join(MAIN, "SmoothLift.java")
SLC = os.path.join(CLIENT, "SmoothLiftClient.java")
SCREEN = os.path.join(CLIENT, "LiftToneSetupScreen.java")
CHIME = os.path.join(CLIENT, "LiftChimePlayer.java")
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


def join(src, *subs):
    """把「同一串名字被 Java 拆成多段字符串拼接」还原成真实值（如三个通道名）。"""
    out = []
    for sub in subs:
        m = re.search(sub + r"\s*=\s*new ResourceLocation\(\"smoothlift\",\s*\"([^\"]+)\"\)", src)
        out.append(m.group(1) if m else None)
    return out


def strip_comments(src):
    """剥掉 `//` 行注释与 `/* */` 块注释。

    ★ 反面断言（「某某**不在**源码里」）必须先剥注释再判：否则新加的解释性注释
    （「原来那只开关按钮已删」之类）会把它判成「还在」；反过来也一样能骗出假绿。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


print("== 1. 通道（客户端/服务端各一个收发点） ==")
main = read(SL)
client = read(SLC)
mgr = read(MGR)
data = read(DATA)

set_chan, import_chan, sync_chan = join(main, r"SET_LIFT_TONE_CHANNEL", r"IMPORT_FOLDER_LIFT_TONE_CHANNEL",
                                        r"LIFT_TONE_SYNC_CHANNEL")
check(all([set_chan, import_chan, sync_chan]),
      "SmoothLift 定义了三个【1.45】通道",
      "set=%s import=%s sync=%s" % (set_chan, import_chan, sync_chan))
check("SET_LIFT_TONE_CHANNEL" in main and "IMPORT_FOLDER_LIFT_TONE_CHANNEL" in main
      and "LIFT_TONE_SYNC_CHANNEL" in main,
      "服务端注册了三个接收器（送出者都能收到处理）")
check("LIFT_TONE_SYNC_CHANNEL" in client,
      "客户端注册了 LIFT_TONE_SYNC 接收器（镜像会更新）")
check("syncLiftToneToAll(server)" in main and "sendLiftToneSyncTo(player, level)" in main,
      "JOIN / REQUEST_SYNC 两处都会推送直梯提示音表（进世界就能拿到）")

print("\n== 2. 数据层（竖井列 key + 三字段 + 三种语义） ==")
check("liftToneAudio" in data and "LiftToneAudio(" in data,
      "SavedData 里加了竖井列 → 三音频 id 的表")
check("LIFT_TONE_DEFAULT" in data and re.search(r'LIFT_TONE_DEFAULT\s*=\s*"default"', data) is not None
      and "LIFT_TONE_OFF" in data and re.search(r'LIFT_TONE_OFF\s*=\s*"off"', data) is not None,
      "default / off 两个哨兵值定义在数据层（与扶梯 HELP_AUDIO 同风格）")
check('tag.getList("liftToneAudio", 10)' in data and 'tag.put("liftToneAudio", toneList)' in data,
      "NBT 读写成对存在（旧存档缺表 → 空表 = 全部默认素材）")
check("record LiftToneAudio(String up, String down, String open, String close)" in data,
      "四项是 record：字段 final ⇒ 删除音频退默认时要整条替换（脚本第 5 段接着验）")

print("\n== 3. 服务端读写（校验 + 全默认即删除） ==")
check("setServerLiftTone" in mgr and "getServerLiftTone" in mgr and "getClientLiftTone" in mgr,
      "服务端读写 / 客户端读取三个入口都在 Manager")
m = re.search(r"public static boolean setServerLiftTone\((.*?)\n    }", mgr, re.S)
set_body = m.group(0) if m else ""
check(bool(set_body) and "categoryAudioNames(getServerData(level), liftToneCategory(which))" in set_body,
      "写入前校验 audioId 必须 ∈ {default, off, 本分类已导入}（否则拒绝）",
      "没有这道校验就能写进任意文件名 → 播放端静默不响还说不清原因")
check("liftToneAudio.remove(key)" in set_body or "LiftToneAudio.NONE" in set_body,
      "四项全默认 = 等于没设置 ⇒ 直接删记录（表只留有价值的行）")

print("\n== 4. 石斧右键（服务端+客户端拦默认交互，UI 四列表） ==")
check("isLiftTrackFloor" in main and "lift_track_floor" in main,
      "主类提供 isLiftTrackFloor（注册名前缀判，不依赖 MTR 编译期）")
check("isLiftTrackFloor(world.getBlockState" in main and "InteractionResult.FAIL" in main,
      "服务端 UseBlockCallback 拦截石斧右键楼层轨道（防 MTR 默认 onUse）")
check("new LiftToneSetupScreen(pos)" in client and "isLiftTrackFloor(world.getBlockState" in client,
      "客户端石斧右键楼层轨道 → 打开 LiftToneSetupScreen")
screen = read(SCREEN)
# 反面断言一律用剥掉注释的版本（见 strip_comments 的说明）
screen_code = strip_comments(screen)
for which, title in (("up", "上楼提示音"), ("down", "下楼提示音"),
                     ("open", "开门提示音"), ("close", "关门提示音")):
    check(('"%s"' % which) in read(CHIME) or ('"%s"' % which) in screen,
          "四列表 %s（%s）存在" % (which, title))
check("IMPORT_FOLDER_LIFT_TONE_CHANNEL" in screen and "SET_LIFT_TONE_CHANNEL" in screen,
      "UI 里既有导入通道又有设置通道（待导入行走导入，已入库行走设置）")
check("liftToneKey" in mgr and "getX()" in screen and "getZ()" in screen,
      "UI 构造时用右键格的竖井列 key（同一条直梯任意层同 key）")

print("\n== 5. 播放端（按竖井列查素材；default/off/custom 三分支） ==")
chime = read(CHIME)
m = re.search(r"private static String liftToneCustomId\(Minecraft mc, MtrLiftAccess.LiftView lift, String which\)"
              r"(.*?)\n    \}", chime, re.S)
body = m.group(1) if m else ""
check(bool(body), "找到 liftToneCustomId（播放端查素材的统一入口）")
if body:
    check("liftToneKeyNear" in body or "liftToneKey(" in body,
          "按最近直梯位置算竖井列 key")
    check("LIFT_TONE_OFF" in body and "LIFT_TONE_DEFAULT" in body,
          "off / default 两个哨兵都处理了（不播 / 内置）")
    # 【1.15】字段解析搬到了 Manager.toneField（两层查找要复用同一份 switch），
    #   播放端这里只负责「按 which 取字段 → default 时回落到维度默认」。
    check('EscalatorSpeedManager.toneField(tone, which)' in body,
          "四字段解析走 Manager.toneField（up/down/open/close 一个 switch，别处不再抄一份）")
    tf = re.search(r"public static String toneField\(EscalatorSpeedData\.LiftToneAudio tone, String which\)"
                   r"(.*?)\n    \}", mgr, re.S)
    tf_body = tf.group(1) if tf else ""
    for which in ("up", "down", "open", "close"):
        check('case "%s"' % which in tf_body, "toneField 里 %s 参与解析" % which)
    check("getLiftToneAudio(mc.level, which)" in body,
          "【1.15】单独设置是 default（或没这一项）时回落到维度默认素材")

check("STOP_SENTINEL" in chime and "STOP_SENTINEL.equals(customId)" in chime,
      "「不播」用哨兵值区分于「没设置」（没设置 = 内置素材）")
check("injectAudio(mc, customId)" in chime,
      "自定义素材走 injectAudio 注入分支（复用扶梯那套解码注入）+ 自定义实例播放")
check("new LiftMusicInstance(event, customId)" in chime,
      "播放实例带 customId（resolve 覆盖 → 直接播引擎缓存里那段）")

print("\n== 6. 删除音频 → 直梯引用退默认 ==")
m = re.search(r"public void removeAudio\((.*?)\n    \}", data, re.S)
rm = m.group(1) if m else ""
check("liftToneAudio" in rm and "LIFT_TONE_DEFAULT" in rm and "new LiftToneAudio(" in rm,
      "removeAudio 把引用已删音频的直梯项退化成默认（record 元素整体替换）")

print("\n== 6b. 【1.60】四提示音独立子开关（指令 + UI + 播放端 + 同步包） ==")
check("liftToneBranch" in main and 'liftToneBranch("up", "up")' in main
      and 'liftToneBranch("down", "down")' in main and 'liftToneBranch("open", "open")' in main
      and 'liftToneBranch("close", "close")' in main,
      "【1.60】指令树由 liftToneBranch(字面量, which) 生成，挂在 /lifthelp 下面（up/down/open/close）")
check("liftToneSwitchCommand" not in main,
      "【1.15】旧的顶级指令构造器 liftToneSwitchCommand 已删除")
for dead in ("lifthelpup", "lifthelpdown", "lifthelpchime"):
    check('Commands.literal("%s")' % dead not in main,
          "【1.15】不再注册顶级指令 /%s" % dead)
check('Commands.literal("lifthelpspeed")' not in main and "liftHelpSpeedArg" not in main
      and "liftHelpSpeedShow" not in main,
      "【1.15】/lifthelpspeed 指令与它的处理函数全部删除")
check("SET_LIFT_TONE_SWITCH_CHANNEL" in main,
      "定义并注册了 UI 开关通道 SET_LIFT_TONE_SWITCH")
check("defaultLiftToneUpEnabled" in data and "defaultLiftToneDownEnabled" in data
      and "defaultLiftToneChimeEnabled" in data,
      "数据层三个子开关字段（缺省 true，旧存档兼容）")
check("isLiftToneEnabled" in mgr and "setDefaultLiftToneEnabled" in mgr
      and "replaceDefaultLiftToneEnabledAll" in mgr,
      "Manager 提供子开关读写（单维度 / 全维度 / X to Y）")
check("applyClientLiftToneSwitchLocal" in mgr,
      "客户端镜像有本地翻子开关的入口（UI 点完立即回显）")
chime = read(CHIME)
check("isLiftToneEnabled(mc.level, which)" in chime and "STOP_SENTINEL" in chime,
      "播放端：维度默认子开关关 → 该项静默（总开关之外还能再关一层）")
# ★★【09-29 · 二改】用户原话：「『不播』代表关闭，『默认』或者玩家导入的就代表开启，
#   不需要一个专门的开关按钮」—— 右列那只「开关：开/关 + 切换」**整行删掉**。
#   ⇒ 反面断言：哨兵与切换方法都不许再出现在**代码**里（注释里提到它们不算，先剥注释）。
#   ★ 但子开关这一层**不能跟着消失**（播放端第一道门就是它，见上式）——
#     「选会出声的素材 → 顺带打开闸门」这个出口（ensureToneEnabled）必须还在，否则回到 LOG6。
check("TOGGLE_SENTINEL" not in screen_code and "toggleToneEnabled" not in screen_code,
      "★★ 右列「开关」按钮及其哨兵已删净（代码里没有 TOGGLE_SENTINEL / toggleToneEnabled）")
check('"开关："' not in screen_code and '"切换"' not in screen_code,
      "★ 开关行那两个控件的文案（「开关：开/关」/「切换」）都不在代码里")
check("SET_LIFT_TONE_SWITCH_CHANNEL" in screen_code and "ensureToneEnabled" in screen_code,
      "★★ 子开关仍够得着：选「会出声」的素材时经 ensureToneEnabled 打开（LOG6 的唯一出口）")

print("\n== 6d. 【1.15】直梯音频快捷设置（维度默认素材 + default 命名 + 补全 + 两层查找） ==")
check("defaultLiftToneAudioUp" in data and "defaultLiftToneAudioDown" in data
      and "defaultLiftToneAudioOpen" in data and "defaultLiftToneAudioClose" in data,
      "数据层四个「维度默认素材」字段（初始 default ⇒ 与 1.14 行为一致）")
# 两层的 default 都是「跟上一层」；【09-27 三改】文案统一成**「默认」**（用户点名，与屏蔽门那页同叫法）。
# ★ 但**不能**写成「默认素材」：后者会和指令里的 default（= 模组内置素材）撞名。
# ★ 断言只查**字符串字面量**（带 ASCII 引号）—— 注释里解释改动时会写「默认（跟维度默认）」，不该误伤。
#   反面同样只否掉字面量：源码里不再有带 ASCII 引号的这个文案 = 真正改干净了。
_OLD_LABEL_LITERAL = '"' + '默认（跟维度默认）' + '"'
check('+ "默认")' in screen and 'return "默认";' in screen
      and '"默认素材"' not in screen and _OLD_LABEL_LITERAL not in screen,
      "★【09-27 三改】那一行文案 =「默认」（按钮 + 状态行同名；不长括注、不叫「默认素材」）")
check('tag.putString("defaultLiftToneAudioUp"' in data
      and 'data.defaultLiftToneAudioUp = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioUp"))' in data
      and 'data.defaultLiftToneAudioOpen = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioOpen"))' in data,
      "四个新字段写/读成对（缺字段读回 default）")
check("normalizeLiftToneAudio" in data and "isLiftToneAllDefault" in data,
      "数据层有「空值→default」与「四项全默认」两个小工具")
rm = re.search(r"public void removeAudio\((.*?)\n    \}", data, re.S)
rmb = rm.group(1) if rm else ""
check("defaultLiftToneAudioUp" in rmb and "defaultLiftToneAudioDown" in rmb
      and "defaultLiftToneAudioOpen" in rmb and "defaultLiftToneAudioClose" in rmb,
      "删音频时把指向它的**维度默认素材**也退回 default（不能留着指向已删文件）")
check("resolveLiftToneName" in mgr and "liftToneNameCandidates" in mgr
      and "getServerAudioLibraryKeys" in mgr,
      "Manager：名字解析（default / none / off / 库文件名）、补全候选、库键读取")
check('return new AudioArg(EscalatorSpeedData.LIFT_TONE_DEFAULT, false, null)' in mgr,
      "指令里的 default = 模组内置素材哨兵（不是扶梯那个内置底噪 id）")
check('"none".equals(lower)' in mgr,
      "「这一项不播」在指令里写作 none（off 被字面量占了）")
check("setDefaultLiftToneAudioAll" in mgr and "clearLiftToneOverrides" in mgr
      and "replaceDefaultLiftToneAudioAll" in mgr,
      "-f：所有维度都设成它 + 清掉按竖井列的单独设置（含 X to Y 版）")
check("liftToneNameSuggestions" in main and "suggests((ctx, b) -> liftToneNameSuggestions(ctx, b, category))" in main,
      "音频名字参数挂了补全提供器（Tab 能补出 default 与导入过的 ogg）")
check("source == null" in main and "return builder.buildFuture()" in main,
      "补全提供器容忍 null source（_tools/CmdTreeCheck 用 null source 解析真指令树）")
check("liftToneAudioSet" in main and "liftToneAudioForceSet" in main
      and "liftToneAudioFromTo" in main and "liftToneAudioForceFromTo" in main,
      "四个音频素材处理函数（本维度 / 本维度 X to Y / -f 全部 / -f X to Y）")
# 播放端两层查找：单独设置 → 维度默认 → 内置
check("EscalatorSpeedManager.toneField(tone, which)" in chime
      and "EscalatorSpeedManager.getLiftToneAudio(mc.level, which)" in chime,
      "播放端【1.15】两层查找：竖井列单独设置 →（default 时回落到）维度默认素材")
# 同步包与接收器：四个新字段必须成对
check("toneAudioUp" in mgr and "liftToneAudioUp = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioUp)" in mgr
      and "liftToneAudioOpen = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioOpen)" in mgr,
      "applyClientLiftChime 接收四个新字段（服务端同步过来）")
packet = re.search(r"private static FriendlyByteBuf buildLiftChimePacket\((.*?)\n    \}", mgr, re.S)
pk = packet.group(1) if packet else ""
check("writeUtf(EscalatorSpeedData.normalizeLiftToneAudio(data.defaultLiftToneAudioUp), 128)" in pk
      and pk.index("defaultLiftToneAudioUp") > pk.index("defaultLiftToneVolumeClose")
      and pk.index("defaultLiftToneAudioDown") > pk.index("defaultLiftToneAudioUp")
      and pk.index("defaultLiftToneAudioOpen") > pk.index("defaultLiftToneAudioDown")
      and pk.index("defaultLiftToneAudioClose") > pk.index("defaultLiftToneAudioOpen"),
      "同步包在包尾按 up→down→open→close 追加四个新字符串（顺序与读侧一致）")
check("String toneAudioUp = buf.readUtf(128)" in client,
      "客户端接收器按同一顺序读四个新字段")

print("\n== 6c. 【1.60 / 09-27 二改】UI 四按钮 + 逐项音量框 + lifthelploud up/down/open/close ==")
check('PAGES' in screen and '"up"' in screen and '"down"' in screen
      and '"open"' in screen and '"close"' in screen,
      "UI 一级菜单 = 四项跳转（PAGES = up/down/open/close）")
check('buildMainPage' in screen and 'buildTonePage' in screen,
      "UI 分两级：一级菜单（四按钮 + 各自右边的音量框）与二级素材列表")
check("mainVolumeInputs" in screen and "applyMainVolumes" in screen,
      "★ 音量框搬到一级菜单：mainVolumeInputs（四项同时存在）+ applyMainVolumes 一起落地")
check("SET_LIFT_TONE_VOLUME_CHANNEL" in screen,
      "音量写回走 SET_LIFT_TONE_VOLUME_CHANNEL（/lifthelploud up|down|open|close）")
check("defaultVolumeInput" not in screen and "applyDefaultVolume" not in screen
      and "SET_LIFT_CHIME_VOLUME_CHANNEL" not in screen,
      "★ 底部「共用默认音量」输入框已删（用户点名：下面的音量输入框删掉）")
check("toneVolumeInput" not in screen,
      "★ 二级页的「这一项的音量」输入框已删（音量改在一级菜单给）")
check('liftToneLoudCommand("up", "up")' in main and 'liftToneLoudCommand("open", "open")' in main
      and 'liftToneLoudCommand("close", "close")' in main,
      "/lifthelploud 注册 up/down/open/close 四项")
check("liftToneLoudForceBranch" in main,
      "-f 节点下也带 up/down/open/close 分支（/lifthelploud -f up 200 全维度）")
check("LIFT_TONE_VOLUME_UNSET" in screen or "clampLiftToneVolume" in main,
      "单项音量用 -1 哨兵 = 未设置（跟随共用默认）")
chime = read(CHIME)
check('liftToneVolume(mc, up ? "up" : "down")' in chime,
      "播放端：准备移动用 up/down 单项音量（detectMove 内按方向取）")
check('liftToneVolume(mc, seqWhich)' in chime,
      "播放端：开关门连播用 open/close 单项音量（detect 里按方向设 seqWhich，advance 内取）")

# ---- 6e) ★【1.17】「删除所有 ui 里的确认按钮，输入框在退出 ui 时立即应用」 ----
#   用户原话（这一条是对**全部** UI 的长期约定，不只屏蔽门那一张）：
#     「从现在开始删除所有 ui 里的『确认』按钮，所有 ui 里的输入框都会在玩家按下 esc 退出 ui 时立即应用」
#   ★ 这条断言的价值在于**它是全局的**：以后谁加一个新 UI、顺手摆一个「应用」按钮，
#     这里立刻红，不必等用户来报。所以这里扫的是**目录里所有 Screen**，不是逐个点名。
print("\n== 6e. 【1.17】所有 UI 都不再有「确认/应用」按钮（输入框在退出 UI 时落地） ==")
CONFIRM_LITERALS = ('Component.literal("应用")', 'Component.literal("确认")',
                    'Component.literal("保存")', 'Component.literal("确定")',
                    'Component.literal("OK")', 'Component.literal("Apply")')
_ui_dir = CLIENT
_bad = []
for _f in sorted(os.listdir(_ui_dir)):
    if not _f.endswith("Screen.java"):
        continue
    _src = read(os.path.join(_ui_dir, _f))
    for _lit in CONFIRM_LITERALS:
        if _lit in _src:
            _bad.append("%s 里还有 %s" % (_f, _lit))
check(not _bad, "目录里所有 *Screen.java 都不再摆「确认/应用」按钮",
      "；".join(_bad) if _bad else "全部已删（含 %d 个 UI）" % len(
          [f for f in os.listdir(_ui_dir) if f.endswith("Screen.java")]))

# 有输入框的那两张 UI，必须把「落地」接在 onClose 上（否则删了按钮就成了「填了没用」）。
for _f, _fn in (("PsdToneSetupScreen.java", "applyMainInputs"),
                ("LiftToneSetupScreen.java", "applyMainVolumes")):
    _src = read(os.path.join(_ui_dir, _f))
    _m = re.search(r"public void onClose\(\)\s*\{(.*?)\n    \}", _src, re.S)
    _body = _m.group(1) if _m else ""
    check(_fn in _body,
          "%s 的 onClose() 里调了 %s（按 ESC 退出即应用，删按钮不等于删功能）" % (_f, _fn),
          "onClose 体 %d 字符" % len(_body))
# ★ 反面：跳页也会重建控件 —— 不接这一步，「填完数字直接点进下一页」那条路会把输入丢掉。
check(screen.count("applyMainVolumes();") >= 2,
      "★ LiftToneSetupScreen 两处都落地（onClose 关界面 + 进二级页那一跳）",
      "实际 %d 处" % screen.count("applyMainVolumes();"))

# ---- 6f) ★【09-27】单项页 = 左右两列列表（按屏蔽门 UI 的样式改版） ----
#   用户原话：「按照屏蔽门 ui 的样式，更新直梯的 ui（双列结构之类的样式更新）」。
#   ★ 断言「几何来自 SoundListLayout」而不是「数字 == 190」—— 版式数字只有一份（那份是唯一来源）。
print("\n== 6f. ★ 直梯单项页 = 左右两列列表（按屏蔽门 UI 样式；【09-27】） ==")
check("SoundListLayout.leftColX(" in screen and "SoundListLayout.rightColX(" in screen
      and "SoundListLayout.rowPickX(" in screen and "SoundListLayout.rowDeleteX(" in screen
      and "SoundListLayout.rowY(" in screen,
      "两列 / 行内三格的 x 与行 y 全部取自 SoundListLayout（不再各算一遍）")
check("未导入存档" in screen and "已导入存档" in screen,
      "两列表头 = 「未导入存档」/「已导入存档」（与屏蔽门逐字相同）")
check("private static final int RIGHT_SPECIAL_ROWS = 2;" in screen,
      "★【09-29 · 二改】右列特殊行 = 2（不播 / 默认）—— 原来是 3，删掉「开关」那一行")
check("i + RIGHT_SPECIAL_ROWS" in screen,
      "★ 已存入第 i 条落在第 i + 2 行（前 2 行被特殊行占了）")
for _nm in ("ROW_H", "LIST_TOP", "COL_W", "ROW_BTN_W", "ROW_BTN_GAP",
            "ROW_NAME_W", "ROW_NAME_CHARS", "BTN_Y"):
    check(("private static final int %s = SoundListLayout.%s;" % (_nm, _nm)) in screen,
          "直梯界面的 %s 是指向 SoundListLayout 的别名" % _nm)
_m = re.search(r"private void buildTonePage\(String which\)\s*\{(.*?)\n    \}", screen, re.S)
# ★ 先剥注释再判：方法体里新加的解释性注释（「原来那只开关按钮已删」）不该被当成控件。
_body = strip_comments(_m.group(1)) if _m else ""
check(_body != "", "抠得出 buildTonePage() 方法体（已剥注释）")
if _body:
    check("不播" in _body and '"默认"' in _body and "开关" not in _body,
          "★★【09-29 · 二改】右列只剩两个特殊行（不播 / 默认）；「开关」那一行已整行删掉")
    check("rowY(0)" in _body and "rowY(1)" in _body and "rowY(2)" not in _body,
          "★ 两个特殊行占 rowY(0)/rowY(1)（原来占 0/1/2）")
    check(_body.count("SoundListLayout.rowDeleteX(") == 1,
          "★ 「删除」只出现在已存入行（两个特殊行都没有删除键）—— rowDeleteX 恰好 1 处",
          "实际 %d 处" % _body.count("SoundListLayout.rowDeleteX("))
    check("SoundListLayout.leftColX(this.width)" in _body
          and "pending" in _body and "stored" in _body,
          "左列 = pending（未导入）、右列 = stored（已导入），两列都建了控件")
# 灰字（与屏蔽门【1.18】同一口径：这一类颜色一个都不许留）
GRAY_TEXTS_LIFT = ("0x808080", "0x909090", "0xFF909090", "0xFFE0E0E0", "0xC0C0C0", "0xA0A0A0")
_gray = [c for c in GRAY_TEXTS_LIFT if c in screen]
check(not _gray, "★ 直梯 UI 里没有任何灰色文字色（对齐屏蔽门：灰字已清）",
      "；".join(_gray) if _gray else "白/黄两色之外无灰")

# ---- 6g) ★【09-27 二改】音量控件搬到一级菜单；二级页无输入框、列表按「6 个选项」算 ----
#   用户原话：「直梯ui应该是在一级菜单里的上楼下楼开门关门的按钮右边设置单独音量，
#             下面的音量输入框删掉。而且二级菜单的列表中间的字和下面的音量输入框删掉，
#             列表长度是6个选项的长度才对。」
print("\n== 6g. ★【09-27 二改】音量逐项搬到一级菜单；二级页只选素材、列表 6 行 ==")

# (A) 一级菜单：每行 = [按钮][音量标签][输入框]，四项同时存在
_mp = re.search(r"private void buildMainPage\(\)\s*\{(.*?)\n    \}", screen, re.S)
_main = _mp.group(1) if _mp else ""
check(_main != "", "抠得出 buildMainPage() 方法体")
if _main:
    check("new EditBox(" in _main, "★ 一级菜单建了音量输入框（EditBox）")
    check(_main.count("mainVolumeInputs.put(") == 1
          and "for (int i = 0; i < PAGES.length; i++)" in _main,
          "★ 一行一个框、四项在同一个循环里建（mainVolumeInputs.put 只写一次）",
          "实际 %d 处" % _main.count("mainVolumeInputs.put("))
    check("mainRowInputX()" in _main and "mainRowStartX()" in _main,
          "★ 输入框 x 取自 mainRowInputX()（= 按钮右边），行起点取自 mainRowStartX()")
check("MAIN_INPUT_W" in screen and "MAIN_GAP" in screen and "VOLUME_LABEL" in screen,
      "一级菜单的「按钮→标签→输入框」三个空隙/宽度都是命名常量")
check("mainRowLabelX()" in screen and 'Component.literal(VOLUME_LABEL)' in screen,
      "★ 按钮右边那个「音量」标签是**画出来的**（不是按钮、不占 widget）")

# (B) 二级页：没有任何 EditBox；底部那行常驻字与输入框标签都不再画
_mt = re.search(r"private void buildTonePage\(String which\)\s*\{(.*?)\n    \}", screen, re.S)
_bt = _mt.group(1) if _mt else ""
check(_bt != "" and "new EditBox(" not in _bt,
      "★ 二级页里**没有任何 EditBox**（音量输入框已删）")
check("drawInputLabel" not in screen,
      "★ 画出来的输入框标签已删（一级页改成逐行「音量」小标签）")
check("STATUS_Y_LIST" in screen and "statusText != null" in screen,
      "★ 二级页只在**有操作反馈时**才画那行字（否则点删除/点刷新没有回执）")

# (C) 列表长度按「6 个选项」算：底部预留取屏蔽门同一份常量 44
check("private static final int BOTTOM_RESERVE_LIST = SoundListLayout.BOTTOM_RESERVE_LIST;" in screen,
      "★ 二级页底部预留 = SoundListLayout.BOTTOM_RESERVE_LIST（44，与屏蔽门同一份）")
check("STATUS_Y_LIST" in screen and "BTN_Y" in screen,
      "★ 底部两行仍取 SoundListLayout 的 BTN_Y / STATUS_Y_LIST（不自己算）")
# 可见行数不写死：直接从 SoundListLayout 的常量算（240 px 画布）
_sll = read(os.path.join(CLIENT, "SoundListLayout.java"))
_h = int(re.search(r"public static final int ROW_H = (\d+);", _sll).group(1))
_top = int(re.search(r"public static final int LIST_TOP = (\d+);", _sll).group(1))
_res = int(re.search(r"public static final int BOTTOM_RESERVE_LIST = (\d+);", _sll).group(1))
_rows240 = (240 - _res - (_top + _h)) // _h
check(_rows240 == 6,
      "★ 算术核对：240 px 画布下可见行 = (240-%d-(%d+%d))//%d = 6（用户点名的「6 个选项」）"
      % (_res, _top, _h, _h), "实际 %d 行" % _rows240)

print("\n== 7. 构建产物 ==")
if not os.path.isfile(JAR):
    print("[SKIP] 未找到 %s，跳过打包校验（先跑 gradlew build）" % os.path.basename(JAR))
else:
    with zipfile.ZipFile(JAR) as z:
        names = set(z.namelist())
        check("smooth/lift/client/LiftToneSetupScreen.class" in names,
              "jar 内含 LiftToneSetupScreen（两列列表界面编进去了）")
        blob = z.read("smooth/lift/client/LiftChimePlayer.class")
        check(b"liftToneCustomId" in blob, "LiftChimePlayer.class 里有 liftToneCustomId（播放端分支编进去了）")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)

# 【1.23】直梯 2 级菜单按 Esc = 返回主界面不落地（一级才落地退出）
check('page > 0' in screen and 'init();' in screen,
      "★【1.23】直梯 onClose：二级 Esc 返回主界面（不落地），一级才落地退出")
print("\n== 全部通过 ==")