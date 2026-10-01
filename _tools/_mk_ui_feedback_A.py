# -*- coding: utf-8 -*-
"""【10-01 第一阶段】SmoothLift.java 的 UI 侧改造：
1) 接收器内部不再逐条输出长句 -> noteUiResult(player, ok)
2) 静默失败路径补 noteUiResult(player, false)
3) 新增 UI_CLOSE_CHANNEL + UI_SESSION_OK + noteUiResult + 退出界面回执接收器 + JOIN 复位

用法： python _mk_ui_feedback_A.py          # dry-run（只报告）
       python _mk_ui_feedback_A.py --apply  # 落盘
"""
import re
import sys

P = "src/main/java/smooth/lift/SmoothLift.java"
APPLY = "--apply" in sys.argv

src = open(P, encoding="utf-8").read()
orig = src
log = []


def note(msg):
    log.append(msg)


def scan_parens(text, open_idx):
    """open_idx 指向 '('；返回匹配 ')' 的下标（inclusive）"""
    j = open_idx
    depth = 0
    while True:
        c = text[j]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return j
        j += 1


# ============================================================
# 1) 接收器区间内的 displayClientMessage -> noteUiResult
# ============================================================
START = "        ServerPlayNetworking.registerGlobalReceiver(APPLY_CHAIN_CHANNEL,"
END = "        // 扶梯方块被破坏时清除对应记录"
si, ei = src.index(START), src.index(END)
region = src[si:ei]

KEY = "player.displayClientMessage("
out, i, nok, nfail = [], 0, 0, 0
while True:
    k = region.find(KEY, i)
    if k < 0:
        out.append(region[i:])
        break
    j = scan_parens(region, k + len(KEY) - 1)
    end = j + 1
    if end < len(region) and region[end] == ';':
        end += 1
    call = region[k:end]
    ok = ("失败" not in call) and ("未知" not in call)
    if ok:
        nok += 1
    else:
        nfail += 1
    out.append(region[i:k])
    out.append("noteUiResult(player, %s);" % ("true" if ok else "false"))
    i = end
region = "".join(out)
note("displayClientMessage -> noteUiResult: 成功 %d / 失败 %d" % (nok, nfail))
assert "player.displayClientMessage(" not in region, "接收器区仍有 displayClientMessage"

# ---- 静默失败：isEscalator 守卫 ----
G1 = ("                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {\n"
      "                    return;\n"
      "                }")
G1N = ("                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {\n"
       "                    noteUiResult(player, false);\n"
       "                    return;\n"
       "                }")
c = region.count(G1)
assert c == 12, "isEscalator 守卫数 %d != 12" % c
region = region.replace(G1, G1N)
note("isEscalator 守卫补失败回执: %d" % c)

# ---- 静默失败：which 守卫 ----
G2 = ("                if (!\"up\".equals(which) && !\"down\".equals(which)\n"
      "                        && !\"open\".equals(which) && !\"close\".equals(which)) {\n"
      "                    return;\n"
      "                }")
G2N = G2.replace("                    return;",
                 "                    noteUiResult(player, false);\n                    return;")
c = region.count(G2)
assert c == 2, "which 守卫数 %d != 2" % c
region = region.replace(G2, G2N)
note("which 守卫补失败回执: %d" % c)

# ---- 静默失败：屏蔽门开关的未知 which（else 分支直接 return） ----
G3 = ("                } else {\n"
      "                    return;\n"
      "                }")
G3N = ("                } else {\n"
       "                    noteUiResult(player, false);\n"
       "                    return;\n"
       "                }")
c = region.count(G3)
assert c >= 1, "PSD 开关 else-return 未找到"
region = region.replace(G3, G3N, 1)
note("PSD 开关未知 which 补失败回执: 1")

# ---- 计数型：int count = ...; 后插入结果记录 ----
lines = region.split("\n")
nl = []
cnt = 0
for ln in lines:
    nl.append(ln)
    m = re.match(r"^(\s+)int count = .+;\s*$", ln)
    if m:
        nl.append("%snoteUiResult(player, count > 0);" % m.group(1))
        cnt += 1
region = "\n".join(nl)
assert cnt == 6, "region 内 int count 行数 %d != 6" % cnt
note("int count 行补结果记录: %d" % cnt)

# ---- 布尔 if 无 else 的三处：先取布尔再记结果 ----
BOOL_IFS = [
    "if (EscalatorSpeedManager.unbindAudio(level, pos)) {",
    "if (EscalatorSpeedManager.deleteAudio(level, category, audioId)) {",
    "if (EscalatorSpeedManager.unbindHelpAudio(level, pos, in)) {",
]
for cond in BOOL_IFS:
    old = "                " + cond
    assert region.count(old) == 1, "布尔 if 未唯一: " + cond
    expr = cond[len("if ("):-len(") {")]
    new = ("                boolean uiOk = %s;\n"
           "                noteUiResult(player, uiOk);\n"
           "                if (uiOk) {" % expr)
    region = region.replace(old, new)
note("布尔 if 无 else 三处已改造: %d" % len(BOOL_IFS))

src = src[:si] + region + src[ei:]

# ============================================================
# 2) import
# ============================================================
IMP_OLD = "import java.util.Locale;\nimport java.util.concurrent.CompletableFuture;"
IMP_NEW = ("import java.util.Locale;\n"
           "import java.util.Map;\n"
           "import java.util.UUID;\n"
           "import java.util.concurrent.CompletableFuture;\n"
           "import java.util.concurrent.ConcurrentHashMap;")
assert src.count(IMP_OLD) == 1, "import 锚点未唯一"
src = src.replace(IMP_OLD, IMP_NEW, 1)
note("import 已补 Map/UUID/ConcurrentHashMap")

# ============================================================
# 3) 通道常量
# ============================================================
CH_OLD = ('    public static final ResourceLocation REQUEST_SYNC_CHANNEL = '
          'new ResourceLocation("smoothlift", "request_sync");\n')
assert src.count(CH_OLD) == 1, "REQUEST_SYNC 通道锚点未唯一"
CH_NEW = CH_OLD + (
    '\n'
    '    /**\n'
    '     * 【10-01】客户端 -> 服务端：玩家**退出设置界面**时发来（界面内部的逐条操作不再单独提示）。\n'
    '     * 服务端据此把「本次界面会话」的结果回一条到聊天框：「UI执行成功」或「UI执行失败」。\n'
    '     */\n'
    '    public static final ResourceLocation UI_CLOSE_CHANNEL = '
    'new ResourceLocation("smoothlift", "ui_close");\n')
src = src.replace(CH_OLD, CH_NEW, 1)
note("UI_CLOSE_CHANNEL 已加")

# ============================================================
# 4) 类字段 + noteUiResult
# ============================================================
CLS = "public class SmoothLift implements ModInitializer {\n"
assert src.count(CLS) == 1, "类声明锚点未唯一"
CLS_NEW = CLS + (
    '\n'
    '    /**\n'
    '     * 【10-01】每个玩家「本次设置界面会话」的结果。\n'
    '     *\n'
    '     * <p>界面内部的每一次操作**不再**逐条往聊天框打长句，而是把结果并进这里；\n'
    '     * 玩家退出界面时（{@link #UI_CLOSE_CHANNEL}）统一回一条「UI执行成功 / UI执行失败」。\n'
    '     * 只要会话内有一次失败就**保持** false（合并用「与」），后面成功不会把失败抹掉。\n'
    '     */\n'
    '    private static final Map<UUID, Boolean> UI_SESSION_OK = new ConcurrentHashMap<>();\n'
    '\n'
    '    /** 【10-01】记录一次界面操作的结果（见 {@link #UI_SESSION_OK}）。 */\n'
    '    static void noteUiResult(ServerPlayer player, boolean ok) {\n'
    '        if (player == null) {\n'
    '            return;\n'
    '        }\n'
    '        UI_SESSION_OK.merge(player.getUUID(), ok, (a, b) -> a && b);\n'
    '    }\n')
src = src.replace(CLS, CLS_NEW, 1)
note("UI_SESSION_OK + noteUiResult 已加")

# ============================================================
# 5) UI_CLOSE 接收器
# ============================================================
A3 = "        // 【1.53】「预设选择」界面：应用一个「港铁预设」（界面上按一下按钮）。"
assert src.count(A3) == 1, "MBM_PRESET 注释锚点未唯一"
A3_NEW = (
    '        // 【10-01】退出设置界面：把本次界面会话的结果回一条到聊天框\n'
    '        //   （界面内部不再逐条提示；没有失败过就是「UI执行成功」）。\n'
    '        ServerPlayNetworking.registerGlobalReceiver(UI_CLOSE_CHANNEL, (server, player, handler, buf, responseSender) -> {\n'
    '            server.execute(() -> {\n'
    '                Boolean ok = UI_SESSION_OK.remove(player.getUUID());\n'
    '                player.displayClientMessage(Component.literal(\n'
    '                        ok == null || ok ? "UI执行成功" : "UI执行失败"), false);\n'
    '            });\n'
    '        });\n'
    '\n' + A3)
src = src.replace(A3, A3_NEW, 1)
note("UI_CLOSE 接收器已加")

# ============================================================
# 6) JOIN 复位
# ============================================================
J_OLD = ("        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {\n"
         "            EscalatorSpeedManager.syncToAll(server);")
assert src.count(J_OLD) == 1, "JOIN 锚点未唯一"
J_NEW = ("        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {\n"
         "            // 【10-01】换世界/重进时清掉上一次界面会话残留的结果\n"
         "            UI_SESSION_OK.remove(handler.player.getUUID());\n"
         "            EscalatorSpeedManager.syncToAll(server);")
src = src.replace(J_OLD, J_NEW, 1)
note("JOIN 复位已加")

# ============================================================
print("\n".join("  - " + x for x in log))
print("\n原大小 %d -> 新大小 %d" % (len(orig), len(src)))
if APPLY:
    open(P, "w", encoding="utf-8", newline="\n").write(src)
    print(">>> 已落盘")
else:
    print(">>> dry-run（未落盘）")
