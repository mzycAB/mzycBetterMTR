"""SmoothLift Forge 1.20.1 —— 直梯（【1.42】~【1.48】）移植的离线回归校验。

只测量、不修文件：从**编译产物**（build/libs 的 jar）里读字节码/资源，逐条断言
「该在的东西真的在、不该在的东西真的不在」。任何一条不成立就非零退出。

注意两个刻意的做法：
  * 常量池里的字符串是**原样字节**，所以中文按 UTF-8 字节搜索，不走 ASCII 正则；
  * 「网络包注册了几只」必须看**调用指令**而不是常量池（messageBuilder 这个 Utf8
    在常量池里只会出现一次），所以用 javap -c 数调用数。
"""
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

JAR = Path(sys.argv[1] if len(sys.argv) > 1
           else r"C:\Users\user\Desktop\2\SmoothLiftMod-Forge-1.20.1\build\libs\smooth-escalator-1.12.11201.jar")
JAVAP = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\javap.exe"

fails, oks = [], []


def check(name, cond, detail=""):
    (oks if cond else fails).append(f"{name}{(' — ' + detail) if detail else ''}")


zf = zipfile.ZipFile(JAR)
names = set(zf.namelist())


def raw(p):
    return zf.read(p)


def has(p, text):
    """类常量池里是否有该字符串（按原始字节匹配，支持中文）。"""
    return text.encode("utf-8") in raw(p)


# --------------------------------------------------------------------------
# 1) 主类：指令 + 文案 + 判据
# --------------------------------------------------------------------------
MAIN = "smooth/lift/SmoothLift.class"

# 顶层指令字面名（能直接出现的）
for c in ["lifthelp", "lifthelpspeed", "lifthelploud", "lifthelpround",
          "futispeed", "jietispeed", "futimusic", "futiloud",
          "futihelp", "futihelploud", "futiround", "futihelpround",
          "futihelpspeed", "futihelpmusic"]:
    check(f"[指令] 字面名 {c!r} 存在", has(MAIN, c))

# /lifthelpup|down|chime 是运行时拼出来的（"lifthelp" + which），
# 所以这里只能断言「拼接器 + 三个 which 字面量」都在，真正的可达性由指令树 dump 兜底。
check("[指令] liftToneSwitchCommand（lifthelpup|down|chime 的拼接器）存在",
      has(MAIN, "liftToneSwitchCommand"))
for w in ["up", "down", "chime"]:
    check(f"[指令] 子开关 which 字面量 {w!r} 存在", has(MAIN, w))

# 分支字面名（含 2 字符的 in/out/hz —— 不能用 ≥3 的 ASCII 正则）
for lit in ["round", "volume", "target", "speed", "door", "hz", "in", "out", "on", "off", "-f", "to"]:
    check(f"[指令] 分支字面名 {lit!r} 存在", has(MAIN, lit))

# 反馈文案（中文，UTF-8 字节匹配）：证明业务逻辑真的搬来了
for s in ["上楼提示音", "下楼提示音", "开关门提示音", "提示音",
          "已强制**所有维度**", "已把所有直梯提示音倍速为",
          "本维度（", "直梯开关门提示音", "倍速", "淡入淡出范围",
          "（100 = 原始音量；其它维度不变，要对所有维度生效用 /lifthelploud "]:
    check(f"[文案] {s!r} 存在", has(MAIN, s))

# 判据方法（public static，供数据包共用）
check("[判据] isLiftTrackFloor 存在（客户端/服务端共用同一判据）", has(MAIN, "isLiftTrackFloor"))
check("[判据] liftToneLabel 存在（与数据包共用）", has(MAIN, "liftToneLabel"))
check("[判据] truncateForMsg 存在（与数据包共用）", has(MAIN, "truncateForMsg"))
check("[判据] 注册名前缀 lift_track_floor 存在", has(MAIN, "lift_track_floor"))
check("[入口] registerCommands(CommandDispatcher) 已抽出（供离线建树）",
      has(MAIN, "registerCommands"))

# --------------------------------------------------------------------------
# 2) 网络层：用 javap 数真实调用数（常量池会去重，不能用字节搜索）
# --------------------------------------------------------------------------
tmp = Path(tempfile.mkdtemp())
zf.extract("smooth/lift/network/Packets.class", tmp)
javap = subprocess.run([JAVAP, "-p", "-c", "-cp", str(tmp), "smooth.lift.network.Packets"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

n_msg = len(re.findall(r"messageBuilder", javap))
registered = sorted(set(re.findall(r"class smooth/lift/network/([A-Za-z0-9_$]+)", javap)))
check("[网络] messageBuilder 调用数 = 33（26 旧 + 7 新）", n_msg == 33, f"实际 {n_msg}")
check("[网络] 注册的包类去重后 = 33 只", len(registered) == 33, f"实际 {len(registered)}")

dirs = re.findall(r"NetworkDirection\.([A-Z_]+)", javap)
check("[网络] PLAY_TO_SERVER 22 / PLAY_TO_CLIENT 11",
      dirs.count("PLAY_TO_SERVER") == 22 and dirs.count("PLAY_TO_CLIENT") == 11,
      f"实际 S={dirs.count('PLAY_TO_SERVER')} C={dirs.count('PLAY_TO_CLIENT')}")

NEW_PACKETS = ["LiftChimeSyncPacket", "LiftToneSyncPacket", "SetLiftTonePacket",
               "SetLiftToneSwitchPacket", "SetLiftChimeVolumePacket",
               "SetLiftToneVolumePacket", "ImportFolderLiftTonePacket"]
for p in NEW_PACKETS:
    check(f"[网络] {p} 已注册进 SimpleChannel", p in registered)
    check(f"[网络] {p}.class 已入包", f"smooth/lift/network/{p}.class" in names)

# 包体里不能残留 Fabric 网络 API
for p in NEW_PACKETS:
    b = raw(f"smooth/lift/network/{p}.class")
    check(f"[网络] {p} 无 Fabric 网络引用",
          b"net/fabricmc" not in b and b"ClientPlayNetworking" not in b)

# --------------------------------------------------------------------------
# 3) Mixin：包位置规则（Mtr3LiftAutoClose 必须在 mixin 包之外）
# --------------------------------------------------------------------------
check("[Mixin] Mtr3LiftAutoClose 在 smooth/lift/（mixin 包之外）",
      "smooth/lift/Mtr3LiftAutoClose.class" in names)
check("[Mixin] Mtr3LiftAutoClose **不**在 smooth/lift/mixin/** 内",
      not any(n.startswith("smooth/lift/mixin/") and "Mtr3LiftAutoClose" in n for n in names))

# 【1.45】同一层按按钮 → 重开门：反射层的三个出入口都得在
AUTOCLOSE = "smooth/lift/Mtr3LiftAutoClose.class"
for sym in ["onExternalCall", "onPanelCall", "consumePendingSync", "hasFloor",
            "getPositionY", "getLiftDirection", "RailwayData", "lifts", "NONE"]:
    check(f"[1.45] Mtr3LiftAutoClose 里有 {sym!r}", has(AUTOCLOSE, sym))

# ★★ 【1.45】楼层身份 —— 第一版就错在这条上，症状「按了完全没反应」（不报错、不打日志）：
#   外呼入口拿到的 pos 是**按钮方块**的坐标，而 Lift.hasFloor(p) == floors.contains(p)
#   且 floors 里装的是**楼层轨道方块**的坐标（见 check-mtr3-contract.py 的 A3b 段）。
#   用按钮的 pos 去问 hasFloor 恒为 false ⇒ 整条修复静默失效。
#   ⇒ 正确的实现**必然**出现这两样东西，缺任何一样就说明楼层身份又搞错了：
#       ① 字面量 "forEachTrackPosition"（只有走按钮实体拿楼层轨道才会有）；
#       ② 调用 Level.getBlockEntity（真的去取按钮方块实体了）。
#   ★ ① 在**已 reobf 的 jar** 里可查；② 的 vanilla 方法名会被 reobf 成 srg，
#     所以只能查**未 reobf** 的 build/classes/java/main。
check("[1.45] ★楼层身份：走按钮实体的 forEachTrackPosition 拿「楼层轨道」坐标"
      "（拿按钮方块自己的 pos 去问 hasFloor 会恒 false ⇒ 静默失效）",
      has(AUTOCLOSE, "forEachTrackPosition"))
AUTOCLOSE_SRC = Path(JAR).parent.parent / "classes" / "java" / "main" / "smooth" / "lift" / \
    "Mtr3LiftAutoClose.class"
if AUTOCLOSE_SRC.is_file():
    check("[1.45] ★楼层身份：外呼那段确实去查了按钮方块实体（Level.getBlockEntity）",
          b"getBlockEntity" in AUTOCLOSE_SRC.read_bytes())
else:
    check("[1.45] ★楼层身份：找得到未 reobf 的 Mtr3LiftAutoClose.class"
          "（getBlockEntity 只能在这里查，reobf 后是 m_7702_）", False, str(AUTOCLOSE_SRC))
check("[1.45] door tick mixin 会取走「待同步」标记并把这条直梯塞进同步集合",
      has("smooth/lift/mixin/mtr/Mtr3LiftDoorMixin.class", "consumePendingSync"))
mtr_entries = sorted(n for n in names
                     if n.startswith("smooth/lift/mixin/mtr/") and not n.endswith("/"))
check("[Mixin] smooth/lift/mixin/mtr/ 下只有 5 个类（4 mixin + 1 plugin）",
      mtr_entries == ["smooth/lift/mixin/mtr/Mtr3LiftDoorMixin.class",
                      "smooth/lift/mixin/mtr/Mtr3LiftExternalCallMixin.class",
                      "smooth/lift/mixin/mtr/Mtr3LiftMixinPlugin.class",
                      "smooth/lift/mixin/mtr/Mtr3LiftPanelCallMixin.class",
                      "smooth/lift/mixin/mtr/Mtr4LiftTrackFloorShapeMixin.class"],
      str(mtr_entries))

# 【1.45】两条新入口的注入选择器必须**原样**在 mixin 的常量池里
# （选择器漂了 = 启动期 InjectionError，所以这里先卡一遍）
EXT_MIXIN = "smooth/lift/mixin/mtr/Mtr3LiftExternalCallMixin.class"
PANEL_MIXIN = "smooth/lift/mixin/mtr/Mtr3LiftPanelCallMixin.class"
check("[Mixin/1.45] 外呼 mixin 的选择器是带完整描述符的静态 addInstruction",
      has(EXT_MIXIN, "addInstruction(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Z)V"))
check("[Mixin/1.45] 外呼 mixin 打在 TAIL 且调用 onExternalCall",
      has(EXT_MIXIN, "TAIL") and has(EXT_MIXIN, "onExternalCall"))
check("[Mixin/1.45] 面板 mixin 的选择器是 pressButton(I)V",
      has(PANEL_MIXIN, "pressButton(I)V"))
check("[Mixin/1.45] 面板 mixin 打在 TAIL 且调用 onPanelCall",
      has(PANEL_MIXIN, "TAIL") and has(PANEL_MIXIN, "onPanelCall"))
check("[Mixin/1.45] 两条新 mixin 都带 @Pseudo（MTR 不在时安全跳过）",
      has(EXT_MIXIN, "Lorg/spongepowered/asm/mixin/Pseudo;")
      and has(PANEL_MIXIN, "Lorg/spongepowered/asm/mixin/Pseudo;"))
check("[Mixin/1.45] 两条新 mixin 的 handler 里只有 SLF4J（catch 里不许再调本模组类）",
      has(EXT_MIXIN, "smoothlift") and has(PANEL_MIXIN, "smoothlift"))

mtr_cfg = json.loads(raw("smoothlift.mtr.mixins.json"))
check("[Mixin] mtr 配置 required=false（MTR 不在时整份跳过）", mtr_cfg.get("required") is False)
check("[Mixin] mtr 配置 package=smooth.lift.mixin.mtr",
      mtr_cfg.get("package") == "smooth.lift.mixin.mtr")
check("[Mixin] mtr 配置 plugin=Mtr3LiftMixinPlugin",
      mtr_cfg.get("plugin") == "smooth.lift.mixin.mtr.Mtr3LiftMixinPlugin")
check("[Mixin] mtr 配置列 4 条 mixin（含 1.45 新加的两条）",
      mtr_cfg.get("mixins") == ["Mtr3LiftDoorMixin", "Mtr3LiftExternalCallMixin",
                                "Mtr3LiftPanelCallMixin", "Mtr4LiftTrackFloorShapeMixin"])
check("[Mixin/1.45] mtr 配置里 defaultRequire=1（选择器漂了要启动期就炸，不许静默）",
      mtr_cfg.get("injectors", {}).get("defaultRequire") == 1)

mf = raw("META-INF/MANIFEST.MF").decode()
check("[清单] MixinConfigs 同时列出两份配置",
      "smooth_escalator.mixins.json,smoothlift.mtr.mixins.json" in mf)
check("[清单] Implementation-Version = 1.12.11201",
      "Implementation-Version: 1.12.11201" in mf)

# 客户端 mixin 必须仍只在 "client" 数组里（服务端不加载）
cfg = json.loads(raw("smooth_escalator.mixins.json"))
check("[Mixin] SoundEngineVolumeMixin 仍只在 client 数组",
      "SoundEngineVolumeMixin" in cfg.get("client", [])
      and "SoundEngineVolumeMixin" not in cfg.get("mixins", []))

# --------------------------------------------------------------------------
# 4) 数据层 / 管理器
# --------------------------------------------------------------------------
DATA = "smooth/lift/EscalatorSpeedData.class"
for f in ["liftToneAudio", "defaultLiftToneVolumeUp", "defaultLiftToneVolumeDown",
          "defaultLiftToneVolumeChime", "defaultLiftToneUpEnabled", "defaultLiftToneDownEnabled",
          "defaultLiftToneChimeEnabled", "defaultLiftHelpRound", "defaultLiftHelpSpeed",
          "defaultLiftHelpVolume", "defaultLiftHelp"]:
    check(f"[数据] 字段 {f} 存在", has(DATA, f))
for m in ["clampLiftHelpSpeed", "clampLiftHelpVolume", "clampLiftToneVolume"]:
    check(f"[数据] 夹取方法 {m} 存在", has(DATA, m))
for c in ["LIFT_HELP_SPEED_MIN", "LIFT_HELP_ROUND_MAX", "LIFT_HELP_CLOSE_REPEATS"]:
    check(f"[数据] 常量 {c} 存在", has(DATA, c))
check("[数据] LiftToneAudio record 已入包",
      "smooth/lift/EscalatorSpeedData$LiftToneAudio.class" in names)

MGR = "smooth/lift/EscalatorSpeedManager.class"
for m in ["syncLiftChimeToAll", "syncLiftToneToAll", "sendLiftChimeSyncTo",
          "sendLiftToneSyncTo", "applyClientLiftChime", "applyClientLiftTone",
          "setServerLiftTone", "liftToneKeyNear", "liftToneKey", "liftToneEnabledLabel",
          "getLiftHelpRound", "setDefaultLiftToneVolume", "setDefaultLiftToneEnabled",
          "applyClientLiftToneSwitchLocal", "applyClientLiftVolumeLocal",
          "applyClientLiftToneVolumeLocal", "importAudioToStore", "syncAudioToAll"]:
    check(f"[管理器] 方法 {m} 存在", has(MGR, m))

# --------------------------------------------------------------------------
# 5) 资源
# --------------------------------------------------------------------------
for a in ["assets/smoothlift/sounds/audio/liftmusic.ogg",
          "assets/smoothlift/sounds/audio/up.ogg",
          "assets/smoothlift/sounds/audio/down.ogg",
          "assets/mtr/models/block/lift_track_floor_1.json"]:
    check(f"[资源] {a} 已入包", a in names)
for a in ["assets/smoothlift/sounds/audio/liftmusic.ogg",
          "assets/smoothlift/sounds/audio/up.ogg",
          "assets/smoothlift/sounds/audio/down.ogg"]:
    if a in names:
        check(f"[资源] {a} 非空（>4KB）", len(raw(a)) > 4000, f"{len(raw(a))} bytes")

snd = json.loads(raw("assets/smoothlift/sounds.json"))
for k in ["audio/liftmusic", "audio/up", "audio/down"]:
    check(f"[资源] sounds.json 含 {k}", k in snd)

# 提示音播放器用到的 sound id 必须都能在 sounds.json 里找到
CHIME = "smooth/lift/client/LiftChimePlayer.class"
used = sorted(set(re.findall(r"audio/[a-z0-9_]+", raw(CHIME).decode("latin-1"))))
for u in used:
    check(f"[资源] LiftChimePlayer 用的 {u} 在 sounds.json 里", u in snd)

# --------------------------------------------------------------------------
# 6) 客户端
# --------------------------------------------------------------------------
for c in ["smooth/lift/client/MtrLiftAccess.class",
          "smooth/lift/client/LiftChimePlayer.class",
          "smooth/lift/client/LiftToneSetupScreen.class"]:
    check(f"[客户端] {c} 已入包", c in names)

ts = raw("smooth/lift/client/LiftToneSetupScreen.class")
check("[客户端] LiftToneSetupScreen 已无 Fabric 网络引用（ClientPlayNetworking）",
      b"ClientPlayNetworking" not in ts)
check("[客户端] LiftToneSetupScreen 已无 Fabric 网络引用（PacketByteBufs）",
      b"PacketByteBufs" not in ts)
check("[客户端] LiftToneSetupScreen 走 Forge Packets.CHANNEL.sendToServer",
      b"sendToServer" in ts)
for p in ["SetLiftTonePacket", "SetLiftToneSwitchPacket", "SetLiftChimeVolumePacket",
          "SetLiftToneVolumePacket", "ImportFolderLiftTonePacket", "RequestSyncPacket"]:
    check(f"[客户端] 界面发得出 {p}", p.encode() in ts)

# 1.20.1 的 API 签名（3 参数 mouseScrolled / 1 参数 renderBackground）。
# ★ 必须查**未 reobf 的** build/classes（jar 里 vanilla 方法名已被 reobf 成 srg 名，
#   `mouseScrolled` 那种名字在 jar 里根本不存在 —— 直接搜 jar 会得到假阴性）。
UNREOBF = Path(JAR).parent.parent / "classes" / "java" / "main"
pre = UNREOBF / "smooth" / "lift" / "client" / "LiftToneSetupScreen.class"
if pre.exists():
    jv = subprocess.run([JAVAP, "-p", "-cp", str(UNREOBF), "smooth.lift.client.LiftToneSetupScreen"],
                        capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    check("[客户端] mouseScrolled 用 1.20.1 的 3 参数签名（未 reobf 类）",
          "mouseScrolled(double, double, double)" in jv,
          "未找到 3 参数 mouseScrolled")
    check("[客户端] 没有 1.20.4 的 4 参数 mouseScrolled 残留",
          "mouseScrolled(double, double, double, double)" not in jv)
else:
    check("[客户端] 找到未 reobf 的类目录（用于查 vanilla 签名）", False, str(pre))

ev = raw("smooth/lift/client/SmoothLiftClientEvents.class")
check("[接线] 客户端刻里接了 LiftChimePlayer.onClientTick", b"onClientTick" in ev)
check("[接线] 断开时接了 LiftChimePlayer.onDisconnect", b"onDisconnect" in ev)
check("[接线] 石斧右键接了 LiftToneSetupScreen", b"LiftToneSetupScreen" in ev)
check("[接线] 右键仍保留 EscalatorSpeedScreen", b"EscalatorSpeedScreen" in ev)

# 客户端镜像清理：clearClientData 里要把直梯提示音代次也清掉
check("[接线] 服务端通道无 Fabric 痕迹", not any(n.startswith("net/fabricmc/") for n in names))

zf.close()

print(f"通过 {len(oks)} 项，失败 {len(fails)} 项")
if fails:
    print()
    for f in fails:
        print("  ✗", f)
else:
    print("  全部通过 ✓")
sys.exit(1 if fails else 0)
