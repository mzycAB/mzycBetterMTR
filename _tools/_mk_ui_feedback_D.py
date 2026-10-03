# -*- coding: utf-8 -*-
"""【10-01 第四阶段】客户端：
1) SmoothLiftClient 新增 sendUiClose(boolean)
2) 服务端 UI_CLOSE 接收器改读布尔（客户端失败也要算失败）
3) 6 个界面退出时调用 sendUiClose(...)
4) Lift/Psd/Zhaji 的 notifyBadInput、MbmHelp 的 showFeedback 不再往聊天框打长句，
   改成记「本次会话失败过」并随退出上报。

用法： python _mk_ui_feedback_D.py [--apply]
"""
import sys

APPLY = "--apply" in sys.argv
BASE = "src/client/java/smooth/lift/client/"
LOG = []


def rd(p):
    return open(p, encoding="utf-8").read()


def wr(p, s):
    open(p, "w", encoding="utf-8", newline="\n").write(s)


def rep(src, old, new, n=1, tag=""):
    c = src.count(old)
    assert c == n, "[%s] 锚点出现 %d 次（期望 %d）" % (tag, c, n)
    LOG.append(tag)
    return src.replace(old, new, n)


# ---------- 1) SmoothLiftClient.sendUiClose ----------
P = "src/client/java/smooth/lift/client/SmoothLiftClient.java"
s = rd(P)
anchor = "public class SmoothLiftClient implements ClientModInitializer {\n"
s = rep(s, anchor, anchor + (
    '\n'
    '    /**\n'
    '     * 【10-01】退出设置界面：把「本次界面会话有没有失败」告诉服务端。\n'
    '     *\n'
    '     * <p>界面内部的逐条操作**不再**单独往聊天框打长句；服务端收到本包后回一条\n'
    '     * 「UI执行成功 / UI执行失败」（它自己的失败记录与这里的 ok 取「与」）。\n'
    '     */\n'
    '    public static void sendUiClose(boolean ok) {\n'
    '        FriendlyByteBuf buf = PacketByteBufs.create();\n'
    '        buf.writeBoolean(ok);\n'
    '        ClientPlayNetworking.send(SmoothLift.UI_CLOSE_CHANNEL, buf);\n'
    '    }\n'), tag="SmoothLiftClient.sendUiClose")
wr(P, s)

# ---------- 2) 服务端 UI_CLOSE 读布尔 ----------
P = "src/main/java/smooth/lift/SmoothLift.java"
s = rd(P)
old = ('        ServerPlayNetworking.registerGlobalReceiver(UI_CLOSE_CHANNEL, '
       '(server, player, handler, buf, responseSender) -> {\n'
       '            server.execute(() -> {\n'
       '                Boolean ok = UI_SESSION_OK.remove(player.getUUID());\n'
       '                player.displayClientMessage(Component.literal(\n'
       '                        ok == null || ok ? "UI执行成功" : "UI执行失败"), false);\n'
       '            });\n'
       '        });')
new = ('        ServerPlayNetworking.registerGlobalReceiver(UI_CLOSE_CHANNEL, '
       '(server, player, handler, buf, responseSender) -> {\n'
       '            // ★ 客户端也会报失败（输入框里的值非法、已忽略那种）\n'
       '            boolean clientOk = buf.readBoolean();\n'
       '            server.execute(() -> {\n'
       '                Boolean serverOk = UI_SESSION_OK.remove(player.getUUID());\n'
       '                boolean ok = clientOk && (serverOk == null || serverOk);\n'
       '                player.displayClientMessage(Component.literal(\n'
       '                        ok ? "UI执行成功" : "UI执行失败"), false);\n'
       '            });\n'
       '        });')
s = rep(s, old, new, tag="server UI_CLOSE 读布尔")
wr(P, s)

SEND = "ClientPlayNetworking.send(SmoothLift.UI_CLOSE_CHANNEL, PacketByteBufs.empty());"

# ---------- 3) 6 个界面 ----------
PLAIN = ["EscalatorSpeedScreen.java", "TrainSoundScreen.java"]
FLAGGED = ["LiftToneSetupScreen.java", "PsdToneSetupScreen.java",
           "ZhajiToneSetupScreen.java", "MbmHelpScreen.java"]

for f in PLAIN:
    p = BASE + f
    s = rd(p)
    s = rep(s, SEND, "SmoothLiftClient.sendUiClose(true);", tag=f + " 发送(恒成功)")
    wr(p, s)

for f in FLAGGED:
    p = BASE + f
    s = rd(p)
    # 字段
    cls = None
    for ln in s.split("\n"):
        if ln.startswith("public class ") and ln.rstrip().endswith("{"):
            cls = ln + "\n"
            break
    assert cls, f + " 未找到类声明"
    s = rep(s, cls, cls + (
        '\n'
        '    /** 【10-01】本次界面会话里是否出现过失败（退出界面时随 UI_CLOSE 上报给服务端）。 */\n'
        '    private boolean uiFailed;\n'), tag=f + " 字段")
    # 发送
    s = rep(s, SEND, "SmoothLiftClient.sendUiClose(!uiFailed);", tag=f + " 发送(带标志)")
    wr(p, s)

# ---------- 4) notifyBadInput ----------
NB_OLD = ('    private void notifyBadInput(String why) {\n'
          '        Minecraft mc = Minecraft.getInstance();\n'
          '        if (mc.player != null) {\n'
          '            mc.player.displayClientMessage(Component.literal("[SmoothLift] " + why), false);\n'
          '        }\n'
          '    }')
NB_NEW = ('    private void notifyBadInput(String why) {\n'
          '        // 【10-01】不再单独往聊天框打长句：记下「本次界面会话失败过」，\n'
          '        //   退出界面时由服务端统一回一条「UI执行失败」；细节留在日志里。\n'
          '        uiFailed = true;\n'
          '        LOGGER.warn("[SmoothLift/UI] {}", why);\n'
          '    }')
for f in ["LiftToneSetupScreen.java", "PsdToneSetupScreen.java", "ZhajiToneSetupScreen.java"]:
    p = BASE + f
    s = rd(p)
    s = rep(s, NB_OLD, NB_NEW, tag=f + " notifyBadInput")
    wr(p, s)

# ---------- 5) MbmHelpScreen.showFeedback ----------
p = BASE + "MbmHelpScreen.java"
s = rd(p)
SF_OLD = ('    private void showFeedback(String text) {\n'
          '        Minecraft mc = Minecraft.getInstance();\n'
          '        if (mc.player != null) {\n'
          '            mc.player.displayClientMessage(Component.literal(text), false);\n'
          '        }\n'
          '    }')
SF_NEW = ('    private void showFeedback(String text) {\n'
          '        // 【10-01】界面内部不再单独提示；只记结果，退出界面时统一回一条。\n'
          '        if (text != null && text.contains("失败")) {\n'
          '            uiFailed = true;\n'
          '        }\n'
          '    }')
s = rep(s, SF_OLD, SF_NEW, tag="MbmHelpScreen.showFeedback")
wr(p, s)

print("\n".join("  - " + x for x in LOG))
print(">>> " + ("已落盘" if APPLY else "dry-run（未落盘）"))
