# -*- coding: utf-8 -*-
"""离线校验：【1.45】石斧右键直梯楼层轨道、三列表换直梯提示音（up / down / chime）。

## 需求（用户原话归纳）

    石斧右键直梯楼层轨道可以更换直梯无障碍提示音（上楼提示音、下楼提示音、开关门提示音），
    分别 3 个列表；导入的 ogg 放在 MBM_Audio 文件夹里（复用扶梯那套导入机制）。

## 设计要点（脚本要钉住的四条不变量）

1. **「哪条直梯」没有稳定 ID**：直梯 ID 跨重启会变，所以用「楼层轨道所在竖井的那一列
   (X, Z)」当身份 —— 一条直梯的所有楼层轨道共享 X/Z、只有 Y 不同 ⇒ key = `BlockPos.asLong(x, 0, z)`。
   石斧右键**任意**一层的楼层轨道都定位到同一个 key。
2. **三列表各自独立**：`up`（准备向上）/ `down`（准备向下）/ `chime`（开关门连播）。
   取值三种语义：`default`（内置素材）/ `off`（这条不播）/ 音频库文件名（从 MBM_Audio 导入）。
3. **「待导入」要真的先导入**：列表里 MBM_Audio 文件夹中的文件还没进音频库，
   点它必须走导入通道（IMPORT_FOLDER_LIFT_TONE_CHANNEL），点已入库的文件才走设置通道
   （SET_LIFT_TONE_CHANNEL）—— 不然服务端会以「音频不存在」拒绝。
4. **播放端按竖井列查素材**：LiftChimePlayer 播开关门（chime）与准备移动（up/down）时，
   用「最近直梯的位置 → 竖井列 key」去查客户端镜像；`default`→内置事件、`off`→静默跳过、
   其它→`injectAudio` 自定义分支。删除音频时引用它的那一项要退化成默认（removeAudio 里处理）。
   ★【1.15】改成**两层查找**：竖井列那一层是 `default`（= 跟维度默认）时，回落到
   **维度默认素材**（`/lifthelp up|down|door <名字>` 设的），维度默认再是 default 才是内置素材。

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
check("record LiftToneAudio(String up, String down, String chime)" in data,
      "三项是 record：字段 final ⇒ 删除音频退默认时要整条替换（脚本第 5 段接着验）")

print("\n== 3. 服务端读写（校验 + 全默认即删除） ==")
check("setServerLiftTone" in mgr and "getServerLiftTone" in mgr and "getClientLiftTone" in mgr,
      "服务端读写 / 客户端读取三个入口都在 Manager")
m = re.search(r"public static boolean setServerLiftTone\((.*?)\n    }", mgr, re.S)
set_body = m.group(0) if m else ""
check(bool(set_body) and "audioLibrary.containsKey" in set_body,
      "写入前校验 audioId 必须 ∈ {default, off, 音频库}（否则拒绝）",
      "没有这道校验就能写进任意文件名 → 播放端静默不响还说不清原因")
check("liftToneAudio.remove(key)" in set_body or "LiftToneAudio.NONE" in set_body,
      "三项全默认 = 等于没设置 ⇒ 直接删记录（表只留有价值的行）")

print("\n== 4. 石斧右键（服务端+客户端拦默认交互，UI 三列表） ==")
check("isLiftTrackFloor" in main and "lift_track_floor" in main,
      "主类提供 isLiftTrackFloor（注册名前缀判，不依赖 MTR 编译期）")
check("isLiftTrackFloor(world.getBlockState" in main and "InteractionResult.FAIL" in main,
      "服务端 UseBlockCallback 拦截石斧右键楼层轨道（防 MTR 默认 onUse）")
check("new LiftToneSetupScreen(pos)" in client and "isLiftTrackFloor(world.getBlockState" in client,
      "客户端石斧右键楼层轨道 → 打开 LiftToneSetupScreen")
screen = read(SCREEN)
for which, title in (("up", "上楼提示音"), ("down", "下楼提示音"), ("chime", "开关门提示音")):
    check(('"%s"' % which) in read(CHIME) or ('"%s"' % which) in screen,
          "三列表 %s（%s）存在" % (which, title))
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
          "三字段解析走 Manager.toneField（up/down/chime 一个 switch，别处不再抄一份）")
    tf = re.search(r"public static String toneField\(EscalatorSpeedData\.LiftToneAudio tone, String which\)"
                   r"(.*?)\n    \}", mgr, re.S)
    tf_body = tf.group(1) if tf else ""
    for which in ("up", "down", "chime"):
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

print("\n== 6b. 【1.46】三提示音独立子开关（指令 + UI + 播放端 + 同步包） ==")
check("liftToneBranch" in main and 'liftToneBranch("up", "up")' in main
      and 'liftToneBranch("down", "down")' in main and 'liftToneBranch("door", "chime")' in main,
      "【1.15】指令树由 liftToneBranch(字面量, which) 生成，挂在 /lifthelp 下面（up/down/door）")
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
check("TOGGLE_SENTINEL" in screen and "toggleToneEnabled" in screen
      and "SET_LIFT_TONE_SWITCH_CHANNEL" in screen,
      "UI 每个列表第一行是「开关」按钮（点=切换维度默认子开关）")
check('"开关："' in screen, "开关行显示当前开/关状态")

print("\n== 6d. 【1.15】直梯音频快捷设置（维度默认素材 + default 命名 + 补全 + 两层查找） ==")
check("defaultLiftToneAudioUp" in data and "defaultLiftToneAudioDown" in data
      and "defaultLiftToneAudioChime" in data,
      "数据层三个「维度默认素材」字段（初始 default ⇒ 与 1.14 行为一致）")
# 两层的 default 都是「跟上一层」，所以**石斧那一行**必须写成「跟维度默认」而不是「默认素材」：
# 后者会和指令里的 default（= 模组内置素材）撞名，玩家会以为点它就能回内置那一段。
check("默认（跟维度默认）" in screen and '"默认素材"' not in screen,
      "石斧列表第一行文案 =「默认（跟维度默认）」（与指令里的 default=内置素材 区分开）")
check('tag.putString("defaultLiftToneAudioUp"' in data
      and 'data.defaultLiftToneAudioUp = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioUp"))' in data,
      "三个新字段写/读成对（缺字段读回 default）")
check("normalizeLiftToneAudio" in data and "isLiftToneAllDefault" in data,
      "数据层有「空值→default」与「三项全默认」两个小工具")
rm = re.search(r"public void removeAudio\((.*?)\n    \}", data, re.S)
rmb = rm.group(1) if rm else ""
check("defaultLiftToneAudioUp" in rmb and "defaultLiftToneAudioDown" in rmb
      and "defaultLiftToneAudioChime" in rmb,
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
check("liftToneNameSuggestions" in main and "suggests(SmoothLift::liftToneNameSuggestions)" in main,
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
# 同步包与接收器：三个新字段必须成对
check("toneAudioUp" in mgr and "liftToneAudioUp = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioUp)" in mgr,
      "applyClientLiftChime 接收三个新字段（服务端同步过来）")
packet = re.search(r"private static FriendlyByteBuf buildLiftChimePacket\((.*?)\n    \}", mgr, re.S)
pk = packet.group(1) if packet else ""
check("writeUtf(EscalatorSpeedData.normalizeLiftToneAudio(data.defaultLiftToneAudioUp), 128)" in pk
      and pk.index("defaultLiftToneAudioUp") > pk.index("defaultLiftToneVolumeChime")
      and pk.index("defaultLiftToneAudioDown") > pk.index("defaultLiftToneAudioUp")
      and pk.index("defaultLiftToneAudioChime") > pk.index("defaultLiftToneAudioDown"),
      "同步包在包尾按 up→down→chime 追加三个新字符串（顺序与读侧一致）")
check("String toneAudioUp = buf.readUtf(128)" in client,
      "客户端接收器按同一顺序读三个新字段")

print("\n== 6c. 【1.48】UI 三按钮+音量输入框 + lifthelploud up/down/door 单项音量 ==")
check('PAGES' in screen and '"up"' in screen and '"down"' in screen and '"chime"' in screen,
      "UI 主界面 = 三项跳转（PAGES = up/down/chime）")
check('buildMainPage' in screen and 'buildTonePage' in screen,
      "UI 分两页：主界面（三按钮+共用音量输入框）与单项列表")
check("defaultVolumeInput" in screen and "applyDefaultVolume" in screen,
      "主界面有「共用默认音量」输入框（= /lifthelploud <音量>，三项跟随）")
check("toneVolumeInput" in screen and "applyToneVolume" in screen,
      "单项列表有「这一项的音量」输入框（= /lifthelploud up|down|door <音量>）")
check("SET_LIFT_CHIME_VOLUME_CHANNEL" in screen and "SET_LIFT_TONE_VOLUME_CHANNEL" in screen,
      "UI 两个音量输入框各走一个通道（共用 / 单项）")
check('liftToneLoudCommand("up", "up")' in main and 'liftToneLoudCommand("door", "chime")' in main,
      "/lifthelploud 注册 up/down/door 三项（door = chime 别名）")
check("liftToneLoudForceBranch" in main,
      "-f 节点下也带 up/down/door 分支（/lifthelploud -f up 200 全维度）")
check("LIFT_TONE_VOLUME_UNSET" in screen or "clampLiftToneVolume" in main,
      "单项音量用 -1 哨兵 = 未设置（跟随共用默认）")
chime = read(CHIME)
check('liftToneVolume(mc, up ? "up" : "down")' in chime,
      "播放端：准备移动用 up/down 单项音量（detectMove 内按方向取）")
check('liftToneVolume(mc, "chime")' in chime,
      "播放端：开关门连播用 chime 单项音量（advance 内）")

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
                ("LiftToneSetupScreen.java", "applyDefaultVolume")):
    _src = read(os.path.join(_ui_dir, _f))
    _m = re.search(r"public void onClose\(\)\s*\{(.*?)\n    \}", _src, re.S)
    _body = _m.group(1) if _m else ""
    check(_fn in _body,
          "%s 的 onClose() 里调了 %s（按 ESC 退出即应用，删按钮不等于删功能）" % (_f, _fn),
          "onClose 体 %d 字符" % len(_body))
# ★ 反面：跳页也会重建控件 —— 不接这一步，「填完数字直接点进下一页」那条路会把输入丢掉。
check("applyDefaultVolume();" in screen and "applyToneVolume(which);" in screen,
      "★ LiftToneSetupScreen 跳页前也落地（进子页面 / 从子页面返回那两跳）")

# ---- 6f) ★【09-27】单项页 = 左右两列列表（按屏蔽门 UI 的样式改版） ----
#   用户原话：「按照屏蔽门 ui 的样式，更新直梯的 ui（双列结构之类的样式更新）」。
#   ★ 断言「几何来自 SoundListLayout」而不是「数字 == 190」—— 版式数字只有一份（那份是唯一来源）。
#   ★ 1.20.1 分支没有分类隔离（无 CAT_*）：左列读同一个 MBM_Audio 文件夹、删除通道不带分类。
print("\n== 6f. ★ 直梯单项页 = 左右两列列表（按屏蔽门 UI 样式；【09-27】） ==")
check("SoundListLayout.leftColX(" in screen and "SoundListLayout.rightColX(" in screen
      and "SoundListLayout.rowPickX(" in screen and "SoundListLayout.rowDeleteX(" in screen
      and "SoundListLayout.rowY(" in screen,
      "两列 / 行内三格的 x 与行 y 全部取自 SoundListLayout（不再各算一遍）")
check("未导入存档" in screen and "已导入存档" in screen,
      "两列表头 = 「未导入存档」/「已导入存档」（与屏蔽门逐字相同）")
check("private static final int RIGHT_SPECIAL_ROWS = 3;" in screen,
      "右列特殊行 = 3（开关 / 不播 / 默认（跟维度默认））")
check("i + RIGHT_SPECIAL_ROWS" in screen,
      "★ 已存入第 i 条落在第 i + 3 行（前 3 行被特殊行占了）")
for _nm in ("ROW_H", "LIST_TOP", "COL_W", "ROW_BTN_W", "ROW_BTN_GAP",
            "ROW_NAME_W", "ROW_NAME_CHARS", "BTN_Y"):
    check(("private static final int %s = SoundListLayout.%s;" % (_nm, _nm)) in screen,
          "直梯界面的 %s 是指向 SoundListLayout 的别名" % _nm)
_m = re.search(r"private void buildTonePage\(String which\)\s*\{(.*?)\n    \}", screen, re.S)
_body = _m.group(1) if _m else ""
check(_body != "", "抠得出 buildTonePage() 方法体")
if _body:
    check("开关" in _body and "不播" in _body and "默认（跟维度默认）" in _body,
          "右列三个特殊行（开关 / 不播 / 默认（跟维度默认））都建了控件")
    check(_body.count("SoundListLayout.rowDeleteX(") == 1,
          "★ 「删除」只出现在已存入行（三个特殊行都没有删除键）—— rowDeleteX 恰好 1 处",
          "实际 %d 处" % _body.count("SoundListLayout.rowDeleteX("))
    check("SoundListLayout.leftColX(this.width)" in _body
          and "pending" in _body and "stored" in _body,
          "左列 = pending（未导入）、右列 = stored（已导入），两列都建了控件")
# ★ 1.20.1 没有分类隔离：删除通道只带 id、不带分类。
check("deleteStored(String id)" in screen and "writeUtf(categoryFor" not in screen,
      "1.20.1 无分类隔离：deleteStored 只带 id（不带 categoryFor）")
# 灰字（与屏蔽门【1.18】同一口径：这一类颜色一个都不许留）
GRAY_TEXTS_LIFT = ("0x808080", "0x909090", "0xFF909090", "0xFFE0E0E0", "0xC0C0C0", "0xA0A0A0")
_gray = [c for c in GRAY_TEXTS_LIFT if c in screen]
check(not _gray, "★ 直梯 UI 里没有任何灰色文字色（对齐屏蔽门：灰字已清）",
      "；".join(_gray) if _gray else "白/黄两色之外无灰")

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