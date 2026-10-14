# -*- coding: utf-8 -*-
"""Script: port_repair_v2.py

把【补丁】第二轮（**音频融入存档** + 4 项弹窗）在 `1.20.4` 上的改动移植到另外 4 个工程。

★ 与第一轮 `port_repair_patch.py` 的分工：
  · `port_repair_patch.py` 只负责**第一轮**（补丁落游戏目录 + 两只按钮）—— 已经跑过，
    它的产物会被本脚本**改写/删除**（客户端 RepairCrypto 删掉、RepairPatch 重写）。
  · 本脚本负责**第二轮**的全部内容。两轮合起来，四个目标工程 = 1.20.4 的现状。

★ 本轮各平台差异（本脚本逐处适配）：
  | 关注点 | Fabric（1.20.1/1.21.1） | Forge1.18.2 | Forge1.20.1 |
  |---|---|---|---|
  | 客户端发包 | `ClientPlayNetworking.send(ch, PacketByteBufs.empty())` | `SLNet.sendToServer(ch, SLNet.emptyBuf())` | `Packets.CHANNEL.sendToServer(new MbmRepair*Packet())` |
  | 服务端收包 | `ServerPlayNetworking.registerGlobalReceiver` | `SLNet.registerServer` | 两个 packet 类（各调 `SmoothLift.serverImportRepair`） |
  | 客户端同步解析文件 | `client/SmoothLiftClient.java`（`client.execute`） | 同左 | `client/SmoothLiftClientEvents.java`（`Minecraft.getInstance().execute`） |
  | 按钮 API | `Button.builder(Component.literal(…))` | `ButtonBuilder.builder(new TextComponent(…))` | `Button.builder` |

用法：`python _tools/port_repair_v2.py`
"""

import os
import re
import shutil
import sys

W = r"C:/Users/user/Desktop/3"
REF = os.path.join(W, "1.20.4", "mzycBetterMTR-1.20.4")
REF_MAIN = os.path.join(REF, "src", "main", "java", "smooth", "lift")
REF_CLIENT = os.path.join(REF, "src", "client", "java", "smooth", "lift", "client")

PROJECTS = {
    "1.20.1/mzycBetterMTR-1.20.1": dict(
        kind="fabric", client_dir="src/client/java/smooth/lift/client",
        sync_file="src/client/java/smooth/lift/client/SmoothLiftClient.java",
        execute="client.execute("),
    "1.21.1/mzycBetterMTR-1.21.1": dict(
        kind="fabric", client_dir="src/client/java/smooth/lift/client",
        sync_file="src/client/java/smooth/lift/client/SmoothLiftClient.java",
        execute="client.execute("),
    "Forge1.18.2/mzycBetterMTR-Forge-1.18.2": dict(
        kind="forge1182", client_dir="src/main/java/smooth/lift/client",
        sync_file="src/main/java/smooth/lift/client/SmoothLiftClient.java",
        execute="client.execute("),
    "Forge1.20.1/mzycBetterMTR-Forge-1.20.1": dict(
        kind="forge1201", client_dir="src/main/java/smooth/lift/client",
        sync_file="src/main/java/smooth/lift/client/SmoothLiftClientEvents.java",
        execute="Minecraft.getInstance().execute("),
}

FAILS = []
NOTES = []


def read(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as fh:
        fh.write(s)


def once(text, old, new, tag, mandatory=True):
    """一次替换；找不到就记 FAIL（除非 mandatory=False）。

    ★★ 幂等判据只有一条：**new 已经在文本里**。
      ★ 首版写的是「new 在 且 old 不在」，对**插入式**替换（`new = old + 块`）是错的：
        替换之后 `old` 仍然是 `new` 的前缀 ⇒ `old` 还在文本里 ⇒ 判据不成立 ⇒ **重复插入**。
        实测后果：1.21.1 的 SmoothLift 里「服务端两个入口」被插了两份（编译不过 + 语义重复），
        而脚本每条都报「成功」—— 这正是「同一轮里对同一文件发多个编辑」那类坑的表亲。
      ★ 什么时候不能只看 new：`new` 是原文里本来就存在的短片段时。本脚本的 new 都是整块文档，
        不会撞车。
    """
    if new and new in text:
        return text                     # 已经移植过
    if old not in text:
        if mandatory:
            FAILS.append("%s：锚点没找到（%r…）" % (tag, old.strip().splitlines()[0][:70] if old.strip() else ""))
        return text
    return text.replace(old, new, 1)


def block(text, start, end, end_len_extra=0):
    i = text.find(start)
    if i < 0:
        raise SystemExit("抽块失败：找不到 %r" % start[:60])
    j = text.find(end, i)
    if j < 0:
        raise SystemExit("抽块失败：找不到结尾 %r" % end[:40])
    return text[i:j + len(end) + end_len_extra]


# ======================================================================
# 从 1.20.4 现场抽块（唯一真值）
# ======================================================================
ref_data = read(os.path.join(REF_MAIN, "EscalatorSpeedData.java"))
ref_mgr = read(os.path.join(REF_MAIN, "EscalatorSpeedManager.java"))
ref_main = read(os.path.join(REF_MAIN, "SmoothLift.java"))
ref_client = read(os.path.join(REF_CLIENT, "SmoothLiftClient.java"))
ref_popup = read(os.path.join(REF_CLIENT, "SyncPopupScreen.java"))
ref_screen = read(os.path.join(REF_CLIENT, "MbmHelpScreen.java"))

B = {}
B["data_field"] = block(ref_data, "    /**\n     * 【补丁】**补丁素材**：内置素材键 → 原始 ogg 字节",
                        "public final Map<String, byte[]> repairAssets = new HashMap<>();\n")
B["data_read"] = block(ref_data, "        // 【补丁】补丁素材（内置素材键 → 原始 ogg 字节）：与音频库**分开一张表**",
                       "data.repairAssets.put(key, bytes);\n            }\n        }\n") + "\n"
B["data_write"] = block(ref_data, "        // 【补丁】补丁素材：与音频库同一种平铺字节存储",
                        'tag.put("repairAssets", repairTag);\n') + "\n"
B["mgr_field"] = block(ref_mgr, "        /**\n         * 【补丁】内置素材键 → 原始 ogg 字节（服务端同步过来的镜像）。",
                       "public final Map<String, byte[]> repairAssets = new HashMap<>();\n")
B["mgr_methods"] = block(ref_mgr, "    /**\n     * 【补丁】取**补丁素材**字节（内置素材键 → 原始 ogg）；没导入过补丁时返回 {@code null}。",
                         "data.repairAssets.putAll(repairAssets);\n    }\n") + "\n"
B["mgr_payload"] = block(ref_mgr, "        // ③【补丁】补丁素材（内置素材键 → 原始 ogg 字节）。",
                         "buf.writeByteArray(entry.getValue());\n        }\n") + "\n"
B["main_logger"] = block(ref_main, "    /** 【补丁】本类自己的日志", 'getLogger("smoothlift");\n') + "\n"
B["main_helpers"] = block(ref_main, "    /**\n     * 【补丁】**服务端侧**：把 `<存档>/MBM_Repair/Repair.zip` 里的音频导入存档。",
                          "noteUiResult(player, deleted);\n        return deleted;\n    }\n") + "\n"
B["main_channels"] = block(ref_main, "    /**\n     * 【补丁】客户端 -&gt; 服务端：把存档目录 `MBM_Repair/Repair.zip` 里的音频**导入存档**",
                          'new ResourceLocation("smoothlift", "mbm_repair_delete");\n') + "\n"
B["popup_panelh"] = block(ref_popup, "    /**\n     * 面板高度：**按实际按钮数算**",
                          "return 30 + (options.size() + 1) * 26 + 4;\n    }\n") + "\n"
B["screen_buttons"] = block(ref_screen, "        // 【补丁】右上角挂一只「导入补丁」",
                            "                .build());\n")
B["client_parse"] = block(ref_client, "            // 【补丁】补丁素材：附加在负载**末尾**",
                          "                    repairAssets.put(key, bytes);\n                }\n            }\n") + "\n"
# client.apply 两行（Fabric 用 client.execute，Forge1.20.1 用 Minecraft.getInstance().execute）
B["client_apply"] = block(ref_client, "                EscalatorSpeedManager.applyClientRepairData(dimKey, repairAssets);",
                          "EscalatorAudioPlayer.clearBundledMetrics();\n") + "\n"

print("== 抽块尺寸 ==")
for k, v in B.items():
    print("   %-18s %5d 字符" % (k, len(v)))

FABRIC_SCREEN_BUTTONS = B["screen_buttons"]
FORGE1182_SCREEN_BUTTONS = FABRIC_SCREEN_BUTTONS.replace(
    'Button.builder(Component.literal("导入补丁"), button -> {',
    'ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("导入补丁"), button -> {')

# 客户端发包：三种写法
FABRIC_SENDS = ("""        ClientPlayNetworking.send(SmoothLift.MBM_REPAIR_IMPORT_CHANNEL, PacketByteBufs.empty());""",
                """        SLNet.sendToServer(SmoothLift.MBM_REPAIR_IMPORT_CHANNEL, SLNet.emptyBuf());""",
                """        Packets.CHANNEL.sendToServer(new MbmRepairImportPacket());""")
FABRIC_DELETES = ("""        ClientPlayNetworking.send(SmoothLift.MBM_REPAIR_DELETE_CHANNEL, PacketByteBufs.empty());""",
                  """        SLNet.sendToServer(SmoothLift.MBM_REPAIR_DELETE_CHANNEL, SLNet.emptyBuf());""",
                  """        Packets.CHANNEL.sendToServer(new MbmRepairDeletePacket());""")


def port(proj, cfg):
    root = os.path.join(W, proj)
    main = os.path.join(root, "src", "main", "java", "smooth", "lift")
    cdir = os.path.join(root, cfg["client_dir"])
    kind = cfg["kind"]
    print("\n########## %s（%s）" % (proj.split("/")[0], kind))

    # ---------- 0) 公共源集的两个新文件 ----------
    shutil.copyfile(os.path.join(REF_MAIN, "RepairStore.java"), os.path.join(main, "RepairStore.java"))
    shutil.copyfile(os.path.join(REF_MAIN, "RepairCrypto.java"), os.path.join(main, "RepairCrypto.java"))
    print("   复制 RepairStore.java / RepairCrypto.java -> src/main/java/smooth/lift/")
    old_crypto = os.path.join(cdir, "RepairCrypto.java")
    if os.path.isfile(old_crypto):
        os.remove(old_crypto)
        print("   删除上一轮放在客户端源集的 RepairCrypto.java")

    # ---------- 1) RepairPatch：从 1.20.4 复制 + 按平台换发送语句 ----------
    patch = read(os.path.join(REF_CLIENT, "RepairPatch.java"))
    if kind == "forge1182":
        patch = patch.replace("import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;\n"
                              "import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;\n",
                              "import smooth.lift.net.SLNet;\n", 1)
        patch = patch.replace(FABRIC_SENDS[0], FABRIC_SENDS[1], 1)
        patch = patch.replace(FABRIC_DELETES[0], FABRIC_DELETES[1], 1)
    elif kind == "forge1201":
        patch = patch.replace("import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;\n"
                              "import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;\n",
                              "", 1)
        patch = patch.replace("import net.minecraft.client.Minecraft;",
                              "import net.minecraft.client.Minecraft;\n"
                              "import smooth.lift.network.MbmRepairDeletePacket;\n"
                              "import smooth.lift.network.MbmRepairImportPacket;\n"
                              "import smooth.lift.network.Packets;", 1)
        patch = patch.replace(FABRIC_SENDS[0], FABRIC_SENDS[2], 1)
        patch = patch.replace(FABRIC_DELETES[0], FABRIC_DELETES[2], 1)
    assert "ClientPlayNetworking.send" not in patch if kind != "fabric" else True
    write(os.path.join(cdir, "RepairPatch.java"), patch)
    print("   重写 RepairPatch.java（%s 的发包写法）" % kind)

    # ---------- 2) EscalatorSpeedData ----------
    p = os.path.join(main, "EscalatorSpeedData.java")
    s = read(p)
    if "repairAssets" not in s:
        anchor = "    /** 音频ID → OGG 文件字节（按内容哈希去重，一个音频可被多条扶梯复用）。 */\n" \
                 "    public final Map<String, byte[]> audioLibrary = new HashMap<>();\n"
        s = once(s, anchor, anchor + "\n" + B["data_field"], "%s EscalatorSpeedData 字段" % proj)
        r_anchor = ("        CompoundTag audioTag = tag.getCompound(\"audioLibrary\");\n"
                    "        for (String key : audioTag.getAllKeys()) {\n"
                    "            byte[] bytes = audioTag.getByteArray(key);\n"
                    "            if (bytes.length > 0 && bytes.length <= MAX_AUDIO_BYTES) {\n"
                    "                data.audioLibrary.put(key, bytes);\n"
                    "            }\n"
                    "        }\n")
        s = once(s, r_anchor, r_anchor + B["data_read"], "%s EscalatorSpeedData NBT 读" % proj)
        s = once(s, '        tag.put("audioLibrary", audioTag);\n',
                 '        tag.put("audioLibrary", audioTag);\n' + B["data_write"],
                 "%s EscalatorSpeedData NBT 写" % proj)
        write(p, s)
        print("   EscalatorSpeedData：repairAssets 字段 + NBT 写/读")
    else:
        print("   EscalatorSpeedData：已有 repairAssets，跳过")

    # ---------- 3) EscalatorSpeedManager ----------
    p = os.path.join(main, "EscalatorSpeedManager.java")
    s = read(p)
    if s.count("repairAssets") < 3:
        anchor = ("        /** 【1.7】音频ID → OGG 字节（服务端同步过来的镜像，供客户端播放器注入声音引擎）。 */\n"
                  "        public final Map<String, byte[]> audioLibrary = new HashMap<>();\n")
        s = once(s, anchor, anchor + "\n" + B["mgr_field"], "%s Manager 镜像字段" % proj)
        a2 = ("        return getServerData((ServerLevel) level).audioLibrary.get(audioId);\n"
              "    }\n")
        s = once(s, a2, a2 + "\n" + B["mgr_methods"], "%s Manager 三个方法" % proj)
        a3 = '        buf.writeUtf(data.defaultAudio == null ? "" : data.defaultAudio, 128);\n'
        s = once(s, a3, a3 + B["mgr_payload"], "%s Manager 同步负载" % proj)
        write(p, s)
        print("   EscalatorSpeedManager：镜像字段 + 三个方法 + 同步负载 ③")
    else:
        print("   EscalatorSpeedManager：已含补丁逻辑，跳过")

    # ---------- 4) SmoothLift ----------
    p = os.path.join(main, "SmoothLift.java")
    s = read(p)
    if "MBM_REPAIR_IMPORT_CHANNEL" not in s:
        # 4a) LOGGER + noteUiResult 改 public + 两个服务端入口
        s = once(s, "    /** 【10-01】记录一次界面操作的结果（见 {@link #UI_SESSION_OK}）。 */\n"
                    "    static void noteUiResult(ServerPlayer player, boolean ok) {\n",
                 B["main_logger"] + "\n"
                 "    /** 【10-01】记录一次界面操作的结果（见 {@link #UI_SESSION_OK}）。 */\n"
                 "    public static void noteUiResult(ServerPlayer player, boolean ok) {\n",
                 "%s SmoothLift LOGGER + noteUiResult" % proj)
        anchor_ui = ("        UI_SESSION_OK.merge(player.getUUID(), ok, (a, b) -> a && b);\n"
                     "    }\n")
        s = once(s, anchor_ui, anchor_ui + "\n" + B["main_helpers"], "%s SmoothLift 两个入口" % proj)
        # 4b) 频道常量
        a4 = '            new ResourceLocation("smoothlift", "mbm_open_folder");\n'
        s = once(s, a4, a4 + "\n" + B["main_channels"], "%s SmoothLift 频道常量" % proj)
        # 4c) 接收器（按平台）
        a5 = ('        ServerPlayNetworking.registerGlobalReceiver(MBM_ALL_VOLUME_CHANNEL, '
              '(server, player, handler, buf, responseSender) -> {\n')
        i = s.find(a5)
        if i < 0:
            FAILS.append("%s：找不到 MBM_ALL_VOLUME 接收器的位置" % proj)
        else:
            j = s.find("\n        });\n", i) + len("\n        });\n")
            if kind == "forge1201":
                recv = ""      # Forge1.20.1 用 packet 类，不在这里注册
            else:
                recv = ("\n        // 【补丁】「导入补丁」：服务端读 <存档>/MBM_Repair/Repair.zip -> 解密 -> 写进存档。\n"
                        "        //   ★ 只在服务端做（文件在世界目录下、数据也写在世界存档里）。\n"
                        "        %s(MBM_REPAIR_IMPORT_CHANNEL, (server, player, handler, buf, responseSender) -> {\n"
                        "            server.execute(() -> serverImportRepair(player));\n"
                        "        });\n\n"
                        "        // 【补丁】「删除补丁」：只清存档里的补丁表；玩家自己导入的 ogg 一个都不动。\n"
                        "        %s(MBM_REPAIR_DELETE_CHANNEL, (server, player, handler, buf, responseSender) -> {\n"
                        "            server.execute(() -> serverDeleteRepair(player));\n"
                        "        });\n" % (
                            "SLNet.registerServer" if kind == "forge1182"
                            else "ServerPlayNetworking.registerGlobalReceiver",
                            "SLNet.registerServer" if kind == "forge1182"
                            else "ServerPlayNetworking.registerGlobalReceiver"))
            s = s[:j] + recv + s[j:]
        write(p, s)
        print("   SmoothLift：LOGGER + public noteUiResult + 两个入口 + 频道 + 接收器")
    else:
        print("   SmoothLift：已有补丁频道，跳过")

    # ---------- 5) 客户端同步解析 ----------
    p = os.path.join(root, cfg["sync_file"])
    s = read(p)
    if "applyClientRepairData" not in s:
        a6 = '            final String defaultAudio = defaultAudioRaw.isEmpty() ? null : defaultAudioRaw;\n'
        s = once(s, a6, a6 + B["client_parse"], "%s 客户端解析" % proj)
        a7 = "                EscalatorSpeedManager.applyClientAudioData(dimKey, audioLibrary, folderByCategory,\n" \
             "                        audioCategoryNames, blockAudio, defaultAudio);\n"
        s = once(s, a7, a7 + B["client_apply"], "%s 客户端应用" % proj)
        # 把注释里那句「客户端读到之后」的 execute 形式对齐（Fabric 用 client.execute，
        # Forge1.20.1 用 Minecraft.getInstance().execute）—— 只在日志/注释里出现，不动代码
        write(p, s)
        print("   客户端同步解析：读回 ③ + applyClientRepairData + 清缓存 (%s)" % os.path.basename(p))
    else:
        print("   客户端同步解析：已含，跳过")

    # ---------- 6) SyncPopupScreen：面板高度按按钮数 ----------
    p = os.path.join(cdir, "SyncPopupScreen.java")
    s = read(p)
    if "panelH()" not in s:
        a8 = "    private static final int PANEL_W = 240;\n"
        m = re.search(r"    /\*\* 面板尺寸。 \*/\n    private static final int PANEL_W = 240;\n"
                      r"    private static final int PANEL_H = 112;\n", s)
        if m:
            s = s[:m.start()] + "    /** 面板宽度。 */\n" + a8 + "\n" + B["popup_panelh"] + s[m.end():]
        else:
            s = once(s, a8, a8 + "\n" + B["popup_panelh"], "%s SyncPopupScreen 面板高度" % proj)
        s = once(s, "        return (this.height - PANEL_H) / 2;",
                 "        return (this.height - panelH()) / 2;", "%s panelY" % proj)
        s = once(s, "        guiGraphics.fill(px - 2, py - 2, px + PANEL_W + 2, py + PANEL_H + 2, 0xFF000000);\n"
                    "        guiGraphics.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF0101010);",
                 "        int ph = panelH();\n"
                 "        guiGraphics.fill(px - 2, py - 2, px + PANEL_W + 2, py + ph + 2, 0xFF000000);\n"
                 "        guiGraphics.fill(px, py, px + PANEL_W, py + ph, 0xF0101010);",
                 "%s render 面板" % proj)
        write(p, s)
        print("   SyncPopupScreen：面板高度改按按钮数算")
    else:
        print("   SyncPopupScreen：已有 panelH，跳过")

    # ---------- 7) MbmHelpScreen：撤掉两只按钮，改成一只 + 弹窗 ----------
    p = os.path.join(cdir, "MbmHelpScreen.java")
    s = read(p)
    if "SyncPopupScreen.Option(\"删除补丁\"" not in s:
        old_buttons = ('        // 【补丁】右上角再挂两只按钮（用户点名）：')
        i = s.find(old_buttons)
        if i < 0:
            FAILS.append("%s：找不到上一轮的「两只按钮」注释块" % proj)
        else:
            j = s.find("        setInitialFocus(volumeInput);\n", i)
            if j < 0:
                FAILS.append("%s：找不到 setInitialFocus 收尾" % proj)
            else:
                new_buttons = (FORGE1182_SCREEN_BUTTONS if kind == "forge1182" else FABRIC_SCREEN_BUTTONS)
                s = s[:i] + new_buttons + "\n" + s[j:]
        write(p, s)
        print("   MbmHelpScreen：改成一只「导入补丁」+ 4 项弹窗")
    else:
        print("   MbmHelpScreen：已是新形态，跳过")

    # ---------- 8) Forge1.20.1：两个 packet 类 + 注册 ----------
    if kind == "forge1201":
        net = os.path.join(root, "src", "main", "java", "smooth", "lift", "network")
        for cls, ch in (("MbmRepairImportPacket", "Import"), ("MbmRepairDeletePacket", "Delete")):
            desc = "把存档目录 MBM_Repair/Repair.zip 里的音频导入存档" if ch == "Import" else "删除已经融入存档的补丁素材"
            write(os.path.join(net, cls + ".java"),
                  FORGE1201_PACKET % (desc, ch, cls, cls, cls, cls, cls, cls, ch))
        p = os.path.join(net, "Packets.java")
        s = read(p)
        if "MbmRepairImportPacket" not in s:
            a9 = "        CHANNEL.messageBuilder(MbmAllVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)\n" \
                 "                .encoder(MbmAllVolumePacket::encode)\n" \
                 "                .decoder(MbmAllVolumePacket::decode)\n" \
                 "                .consumerMainThread(MbmAllVolumePacket::handle)\n" \
                 "                .add();\n"
            add = "".join(
                "        CHANNEL.messageBuilder(%s.class, nextId++, NetworkDirection.PLAY_TO_SERVER)\n"
                "                .encoder(%s::encode)\n"
                "                .decoder(%s::decode)\n"
                "                .consumerMainThread(%s::handle)\n"
                "                .add();\n" % (c, c, c, c)
                for c in ("MbmRepairImportPacket", "MbmRepairDeletePacket"))
            s = once(s, a9, a9 + "        // 【补丁】导入 / 删除补丁（各调 SmoothLift 里那唯一的服务端入口）\n" + add,
                     "%s Packets 注册" % proj)
            write(p, s)
        print("   Forge1.20.1：两个 packet 类 + 注册")


FORGE1201_PACKET = '''package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【补丁】客户端 -&gt; 服务端：%s（无字段）。
 *
 * <p>Forge1.20.1 是一个包一个类（没有 Fabric 那种「频道 id + lambda」的形状），
 * 所以这里只做转发 —— 真正的逻辑在 {@code SmoothLift.server%sRepair}，
 * **五个工程共用那一份实现**（免得某个平台上导入变成旧版本）。
 */
public class %s {
    public %s() {
    }

    public static void encode(%s pkt, FriendlyByteBuf buf) {
    }

    public static %s decode(FriendlyByteBuf buf) {
        return new %s();
    }

    public static void handle(%s pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            SmoothLift.server%sRepair(player);
        });
        context.setPacketHandled(true);
    }
}
'''


for proj, cfg in PROJECTS.items():
    port(proj, cfg)

print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 第二轮移植完成（4 个工程）==")
