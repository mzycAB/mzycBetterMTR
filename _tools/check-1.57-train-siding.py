# -*- coding: utf-8 -*-
"""离线校验：【1.57】「列车音效」界面（入口 = 石斧右键侧线铁轨；**一条侧线一份**）。

用户原话（三句，都在下面各节里钉着）：
  「ui打开方式改为石斧右键侧线铁路轨道连接处（就是黄色的那个）打开ui功能，
    如果连接处同时连接两段轨道，就打开玩家面向的那个轨道的ui」
  「取消MBM train music指令打开这个音效ui」
  「点进去的2级ui和屏蔽门ui里的"站台音效"ui使用相同设计，就是左右两列列表的那个ui，
    只不过设置的是列车运行音效 而不是 站台音效 别搞混了！
    这个列车运行音效的右侧列表最上方也有一个默认按钮，只不过这个是"MTR自带音效"按钮，
    依旧只有选择没有删除，按下之后恢复MTR列车自带音效。
    这个按钮下边就是玩家自己导入存档的音效了……中间加入自定义秒数的淡入淡出。
    默认1秒，自定义输入框放在这个二级菜单最下方。其他按钮ui也这样设计。
    先设计ui，这些按钮的功能先不做。」
  「ui只修改石斧右键的侧线内的所有列车音效 …… 只有点击ui右上角的"同步所有"按钮
    才会同步到其他侧线」

改错了不报错的症状：右键普通铁轨也开界面 / 一条侧线的设置串到旁边侧线 /
五个按钮点进去是同一个页 / 右列第 0 行多了个「删除」/ 界面又长出一堆小字 /
按「选用」回一句「已设置」但什么都没发生。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
PKG = os.path.join(SRC, "client", "java", "smooth", "lift", "client")
SL = os.path.join(SRC, "main", "java", "smooth", "lift", "SmoothLift.java")
SCREEN = os.path.join(PKG, "TrainSoundScreen.java")
SIDING = os.path.join(PKG, "MtrSidingAccess.java")
LAYOUT = os.path.join(PKG, "SoundListLayout.java")
PSD = os.path.join(PKG, "PsdToneSetupScreen.java")
CLIENT = os.path.join(PKG, "SmoothLiftClient.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def _match_pair(src, start, open_ch, close_ch):
    """从 start（指向那个开括号）按深度找配对的闭括号，返回其下标；找不到返回 -1。"""
    depth, j, in_str = 0, start, None
    while j < len(src):
        ch = src[j]
        if in_str:
            if ch == "\\":
                j += 2
                continue
            if ch == in_str:
                in_str = None
        elif ch in "\"'":
            in_str = ch
        elif ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def method_body(src, name):
    """抠方法体。

    ★★ 必须**先锚定声明**（行首 4 空格缩进 + 修饰符 + 返回类型 + 名字 + `(`），
       再按括号深度配对取到配对的 `}`。

       只按 `名字(` 找会先命中**调用点**（`button -> pickBuiltin()`），
       取回来的是别处的、甚至更靠后的方法的一段 —— 于是「这个方法里有没有 X」全部失真，
       而且**每一个**基于它的断言都会一起变红/变绿（本轮踩过一次）。
    """
    m = re.search(r"\n    (?:private|public|protected)\s+(?:static\s+)?[\w<>\[\], .]*?\s*"
                  + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    lp = src.index("(", m.end() - 1)
    rp = _match_pair(src, lp, "(", ")")
    if rp < 0:
        return None
    lb = src.find("{", rp)
    if lb < 0:
        return None
    rb = _match_pair(src, lb, "{", "}")
    return None if rb < 0 else src[lb + 1:rb]


def find_array(src, name):
    m = re.search(r"String\[\]\s+" + re.escape(name) + r"\s*=\s*\{(.*?)\};", src, flags=re.S)
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)) if m else None


def find_int_array(src, name):
    m = re.search(r"int\[\]\s+" + re.escape(name) + r"\s*=\s*\{(.*?)\};", src, flags=re.S)
    return re.findall(r"SmoothLift\.(\w+)", m.group(1)) if m else None


def find_const(src, name):
    m = re.search(r"\b" + re.escape(name) + r"\s*=\s*([^;]+);", src)
    return m.group(1).strip() if m else None


# ----------------------------------------------------------------------
# 判据函数（正向断言与反向对照**共用同一份** —— 否则对照证明不了正判据本身）
# ----------------------------------------------------------------------

def top_text_invariant(render_src, top_src):
    """一级页文字不变量：render 里 1 处居中（标题）+ renderTopPage 里 1 处左对齐（「音量」标签）
    ⇒ **绘制调用点合计 == 2**。

    ★ 数的是**调用点**不是运行期文字条数：循环会把 1 个调用点放大成 5 条，
      数运行期的话再塞一行文字也变不红（1.56 踩过）。
    """
    problems = []
    if render_src.count("drawCenteredString") != 1:
        problems.append("render 居中 != 1")
    if render_src.count("drawString") != 0:
        problems.append("render 里出现了 drawString")
    if top_src.count("drawString") != 1:
        problems.append("renderTopPage 左对齐 != 1")
    if "Component.literal(VOLUME_LABEL), labelX," not in top_src:
        problems.append("标签没用共享几何 labelX")
    total = (render_src.count("drawCenteredString") + render_src.count("drawString")
             + top_src.count("drawCenteredString") + top_src.count("drawString"))
    if total != 2:
        problems.append("合计 != 2（实际 %d）" % total)
    return problems


def builtin_row_has_no_delete(src):
    """右列第 0 行那一段：只许有「选用」，不许出现「删除」。

    ★ 窗口必须**止于「已导入存档」循环的开头**（`stored.size()`）。
      那个循环本来就每行都带「删除」，窗口开太大会把它的「删除」栽给第 0 行 ⇒ 假红。
    """
    head, sep, tail = src.partition("MTR_BUILTIN_LABEL")
    if not sep:
        return ["找不到右列第 0 行"]
    seg = head[-200:] + tail
    cut = seg.find("stored.size()")
    if cut >= 0:
        seg = seg[:cut]
    problems = []
    if "删除" in seg:
        problems.append("第 0 行出现了「删除」")
    if 'Component.literal("选用")' not in seg:
        problems.append("第 0 行没有「选用」")
    return problems


sl = open(SL, encoding="utf-8").read()
sl_no = strip_comments(sl)
screen = open(SCREEN, encoding="utf-8").read()
screen_no = strip_comments(screen)
siding = open(SIDING, encoding="utf-8").read()
siding_no = strip_comments(siding)
layout = open(LAYOUT, encoding="utf-8").read()
psd_no = strip_comments(open(PSD, encoding="utf-8").read())
client_no = strip_comments(open(CLIENT, encoding="utf-8").read())

print("  （行尾风格：TrainSoundScreen CRLF=%d / SmoothLift CRLF=%d）"
      % (screen.count("\r\n"), sl.count("\r\n")))

EXPECTED_LABELS = [
    "列车运行音效",
    "列车转弯音效",
    "列车道岔音效",
    "列车进站音效",
    "列车出站音效",
]
EXPECTED_SCOPES = [
    "SYNC_TRAIN_RUN",
    "SYNC_TRAIN_TURN",
    "SYNC_TRAIN_SWITCH",
    "SYNC_TRAIN_ARRIVE",
    "SYNC_TRAIN_DEPART",
]

# ======================================================================
# 1) 入口换掉：`/MBM train music` 已撤销，改成石斧右键
# ======================================================================
print("\n== 1) 入口：撤指令、改石斧右键 ==")
check("TRAIN_MUSIC_OPEN_CHANNEL" not in sl_no, "服务端不再有 train_music_open 频道常量")
check("train_music_open" not in sl_no, "服务端不再有 smoothlift:train_music_open 字样")
check("mbmTrainMusicOpen" not in sl_no, "服务端不再有 mbmTrainMusicOpen 方法")
check('literal("train")' not in sl_no,
      "★ 指令树里不留 train 空壳分类节点（留了会让 /MBM train 看起来「存在」）")
check("TRAIN_MUSIC_OPEN_CHANNEL" not in client_no, "客户端不再注册那只空包接收器")
check("SmoothLift.isMtrRail(world.getBlockState(pos))" in client_no,
      "客户端右键回调按 mtr:rail 判方块")
check("MtrSidingAccess.facingSidingKey()" in client_no, "客户端靠 MtrSidingAccess 认侧线")
check("new TrainSoundScreen(sidingKey)" in client_no, "界面带着侧线身份打开")
i_guard = client_no.find("sidingKey == MtrSidingAccess.NO_SIDING")
i_open = client_no.find("new TrainSoundScreen(sidingKey)")
check(i_guard >= 0 and i_open >= 0 and i_guard < i_open,
      "★ 认不到侧线（NO_SIDING）时**在开界面之前**就 return，不开一个身份不明的界面")
check("registerGlobalReceiver(SmoothLift.MBM_HELP_OPEN_CHANNEL" in client_no,
      "（顺带守住：帮助界面那只空包接收器没被这次删除误伤）")

# ======================================================================
# 2) 「黄色」这一层判据：mtr:rail 必须同时看命名空间与路径
# ======================================================================
print("\n== 2) 方块判据：mtr:rail（同时看命名空间，别撞上原版 minecraft:rail）==")
body = method_body(sl_no, "isMtrRail")
check(body is not None, "SmoothLift 里有 isMtrRail")
if body:
    check('"mtr".equals(id.getNamespace())' in body, "★ 判据查了命名空间 = mtr")
    check('"rail".equals(id.getPath())' in body, "判据查了路径 = rail")
    # ★ 断言的是**表达式**不是「有没有某个名字的辅助方法」：
    #   名字型判据会被「换个写法照样退化」骗过（只比路径也会绿）。这里要求两者与在一起。
    check('"mtr".equals(id.getNamespace()) && "rail".equals(id.getPath())' in body,
          "★ 两个判据必须与在同一个表达式里（只比路径的话，原版 minecraft:rail 的路径也是 "
          "rail，会一起命中 ⇒ 石斧右键普通铁轨也开界面）")

# ======================================================================
# 3) 侧线身份：反射层的三条事实 + 可降级
# ======================================================================
print("\n== 3) MtrSidingAccess：面向的那条轨道 -> 哪条侧线 ==")
check("getFacingRailAndBlockPos" in siding_no and "boolean.class" in siding_no,
      "用 MTR 自带的 getFacingRailAndBlockPos(boolean) 取「玩家面向的那段轨道」")
check('"sidingIdMap"' in siding_no, "从 MinecraftClientData.sidingIdMap 取侧线表")
check('"isSiding"' in siding_no, "★ 门禁用 Rail.isSiding()（「黄色」在运行时的真身）")
check('"containsPos"' in siding_no and '"getPosition1"' in siding_no
      and '"getPosition2"' in siding_no,
      "★ 认亲判据 = 两个端点都落在这条侧线里（与 MTR 自己的 checkOrCreateSavedRail 同款，不自己编阈值）")
check("getDeclaredMethod" in siding_no and ".getMethod(" not in siding_no,
      "★ 沿继承链用 getDeclaredMethod（Rail.getPosition1/2 是 protected，getMethod 会一律 null）")
check("public static final long NO_SIDING = Long.MIN_VALUE;" in siding_no,
      "哨兵用 Long.MIN_VALUE（不是 -1L：-1L 是合法的 asLong 结果）")
check(siding_no.count("return NO_SIDING;") >= 4, "每条认不到的路都返回 NO_SIDING（不开界面）")
check("warnedNoData" in siding_no and "warnedNoMatch" in siding_no,
      "失败路径各只打一次日志（不刷屏、也不静默）")
fb = method_body(siding_no, "facingSidingKey")
check(fb is not None, "有 facingSidingKey()")
if fb:
    check(fb.count("hexIdOf(rail)") == 1 and "LOGGER.info" in fb,
          "★ 轨道 hash 只用于**日志**，没有被拿去做降级身份（否则同一条侧线会有两个身份、静默分桶）")
    check("sidingGetId.invoke(siding)" in fb, "身份取的是 MTR 侧线的 id")

# ======================================================================
# 4) 一级页：仍是 1.56 那套（文字不许长出第三个字）
# ======================================================================
print("\n== 4) 一级页：五个按钮逐字逐序 + 音量框 + 文字只有标题与「音量」==")
check(find_array(screen_no, "LABELS") == EXPECTED_LABELS,
      "五个按钮的文字与用户给的五条逐字逐序相等",
      repr(find_array(screen_no, "LABELS")))
check(find_const(screen_no, "VOLUME_LABEL") == '"音量"', "音量标签逐字 = 「音量」")
check("Arrays.fill(volume, EscalatorSpeedData.DEFAULT_AUDIO_VOLUME)" in screen_no,
      "音量初值 = 默认音量（100）")
check("box.setMaxLength(4)" in screen_no, "音量框 maxLength = 4（1~1000 最多 4 位）")
check("allMatch(Character::isDigit)" in screen_no, "音量框只收数字")
check("EscalatorSpeedData.clampVolume" in screen_no, "音量经 clampVolume 夹到 1~1000")
check("this.width / 2 - rowWidth() / 2" in screen_no, "整行居中（窄屏不会把音量框挤出画面）")
check(find_const(screen_no, "TITLE_Y") == "12", "标题 y 仍是 1.56 的 12")

render = method_body(screen_no, "render")
top = method_body(screen_no, "renderTopPage")
check(render is not None and top is not None, "render() 与 renderTopPage() 都抠到了")
check(render is not None and top is not None and top_text_invariant(render, top) == [],
      "★ 一级页文字不变量：绘制调用点合计 == 2（标题 + 循环里的「音量」）",
      repr(top_text_invariant(render or "", top or "")))
check(render is not None and "this.title" in render, "render 里那一处画的是 this.title")

# ======================================================================
# 5) 二级页：两列版式取自 SoundListLayout；右列第 0 行 = MTR自带音效（只有选用）
# ======================================================================
print("\n== 5) 二级页：两列列表 + 右列第 0 行「MTR自带音效」只有选用 ==")
check("SoundListLayout.leftColX(" in screen_no, "左列 x 取自 SoundListLayout")
check("SoundListLayout.rightColX(" in screen_no, "右列 x 取自 SoundListLayout")
check("SoundListLayout.rowPickX(" in screen_no and "SoundListLayout.rowDeleteX(" in screen_no,
      "行内「选用」「删除」的 x 也取自 SoundListLayout（三格位置只有一份）")
check("SoundListLayout.maxScroll(" in screen_no, "滚动范围口径取自 SoundListLayout")
check("SoundListLayout.rightColX(w) + SoundListLayout.COL_W / 2" in screen_no,
      "两列表头分别画在各自列的中线上")
check(find_const(screen_no, "MTR_BUILTIN_LABEL") == '"MTR自带音效"',
      "右列第 0 行的文字逐字 = 「MTR自带音效」（不是屏蔽门那个「不播」）",
      repr(find_const(screen_no, "MTR_BUILTIN_LABEL")))
builtin = method_body(screen_no, "buildListPage")
check(builtin is not None, "有 buildListPage()")
if builtin:
    check(builtin_row_has_no_delete(builtin) == [],
          "★ 右列第 0 行只有「选用」、没有「删除」", repr(builtin_row_has_no_delete(builtin)))
    check("i + SoundListLayout.RIGHT_COL_FIRST_STORED_ROW" in builtin,
          "★ 已导入存档从第 RIGHT_COL_FIRST_STORED_ROW(1) 行开始（第 0 行被那个按钮占了）")
    check("pending" in builtin and "stored" in builtin, "两列都建了控件（左列未导入、右列已导入）")

# ======================================================================
# 6) 底部「淡入淡出」秒数框
# ======================================================================
print("\n== 6) 淡入淡出：默认 1 秒、放在二级页最下方、越界当非法 ==")
check(find_const(screen_no, "FADE_DEFAULT") == "1", "默认 1 秒")
check(find_const(screen_no, "FADE_LABEL") == '"淡入淡出:"', "标签逐字 = 「淡入淡出:」")
check(find_const(screen_no, "FADE_Y") == "-56", "★ 秒数框画在底部（负偏移，不是列表上方）")
check(find_const(screen_no, "FADE_MAX") == "60", "上限 60 秒")
pf = method_body(screen_no, "parseFade")
check(pf is not None and "v < FADE_MIN || v > FADE_MAX ? null : v" in pf,
      "★ 越界**不夹取**、当非法（夹取会让玩家看到「填 999 变 60」而不知道为什么）",
      repr(pf))
check("this.height + FADE_Y" in screen_no, "秒数框的 y 用 height 推（跟着窗口走）")
check("fadeSeconds[page - 1]" in screen_no, "秒数按页存（五个页面各自一份）")
ai = method_body(screen_no, "applyInputs")
check(ai is not None and "fadeInput" in ai and "page >= 1" in ai,
      "★ 秒数框在离开这一页之前落地（空判断齐全，一级页调它也安全）")

# ======================================================================
# 7) 骨架底线：能翻页，但「选用」不发包、不假装成功
# ======================================================================
print("\n== 7) 骨架底线（用户：这些按钮的功能先不做）==")
op = method_body(screen_no, "openSoundPage")
check(op is not None, "有 openSoundPage()")
if op:
    check("ClientPlayNetworking.send" not in op, "★ 翻页只改 page 并 init()，不发任何包")
    check("applyInputs()" in op, "★ 翻页前先落地输入框（重建控件会把刚填的数字吃掉）")
    check("init()" in op and "page = targetPage" in op, "翻页就是改 page + 重建控件")
for name in ("pickBuiltin", "pickStored"):
    b = method_body(screen_no, name)
    check(b is not None, "有 %s()" % name)
    if b:
        check("ClientPlayNetworking.send" not in b, "%s() 不发包" % name)
        check("setStatus" in b, "%s() 给状态行反馈" % name)
        check("还没接数据层" in b, "★ %s() 诚实说明还没接" % name)
        check(("已设置" not in b) and ("已恢复" not in b) and ("已把" not in b),
              "★ %s() 不许写「已设置/已恢复」这类假成功" % name)
st = method_body(sl_no, "syncTrain")
check(st is not None, "服务端有 syncTrain()")
if st:
    check("还没接数据层" in st and "已同步" not in st,
          "★ syncTrain 数据层没接之前只回「还没接数据层」，绝不回「已同步」")
    check("switch (scope)" in st, "按 scope 分档（接数据层时一眼看出域有没有接错）")
    for name in EXPECTED_SCOPES:
        check(name in st, "syncTrain 分档里有 %s" % name)

# ======================================================================
# 8) ★★ 跨层编号一致：页号 ↔ 服务端 scope
# ======================================================================
print("\n== 8) 页序与服务端 scope 常量同序同内容 ==")
check(find_int_array(screen_no, "PAGE_SCOPES") == EXPECTED_SCOPES,
      "★ PAGE_SCOPES 与 SYNC_TRAIN_* 同序同内容（页号原样当 scope 发，错位不报错）",
      repr(find_int_array(screen_no, "PAGE_SCOPES")))
for name, label in zip(EXPECTED_SCOPES, EXPECTED_LABELS):
    m = re.search(re.escape(name) + r"\s*->\s*\"([^\"]+)\"", st or "")
    check(m is not None and m.group(1) == label,
          "服务端 %s 的文案与界面「%s」对上" % (name, label),
          m.group(1) if m else "缺")
check("int scope = page == 0 ? SmoothLift.SYNC_TOP_LEVEL : PAGE_SCOPES[page - 1]" in screen_no,
      "一级页 scope = SYNC_TOP_LEVEL，二级页 = 对应项（同步的射程跟着页走）")
check('SyncPopupScreen.syncButton(this, "train", scope, sidingKey,' in screen_no,
      "★ 同步的身份 = sidingKey（一条侧线一份 ⇒ 只有「同步所有」才跨侧线）")

# ======================================================================
# 9) 唯一来源：屏蔽门界面不许再自己写一份列版式数字
# ======================================================================
print("\n== 9) 两列版式的数字只有一份 ==")
for name in ("ROW_H", "LIST_TOP", "COL_W", "COL_GAP", "ROW_BTN_W", "ROW_BTN_GAP",
             "ROW_NAME_W", "ROW_NAME_CHARS", "RIGHT_COL_FIRST_STORED_ROW",
             "BTN_Y", "STATUS_Y_LIST", "BOTTOM_RESERVE_LIST"):
    check(("private static final int %s = SoundListLayout.%s;" % (name, name)) in psd_no,
          "屏蔽门界面的 %s 是指向 SoundListLayout 的别名" % name)
for num in ("= 190;", "= 16;", "= 44;", "= 22;", "= 40;"):
    check(num not in psd_no, "屏蔽门界面里不再出现裸数字 %s" % num)
check("public static final int COL_W = 190;" in layout, "SoundListLayout 里持有 COL_W = 190")
check("public static final int ROW_H = 22;" in layout, "SoundListLayout 里持有 ROW_H = 22")

# ======================================================================
# 10) 反向对照（合成基线：判据本身要能变红）
# ======================================================================
print("\n== 10) 反向对照 ==")

GOOD_TOP = ('int labelX = labelX();\nfor (int i = 0; i < LABELS.length; i++) {\n'
            'guiGraphics.drawString(this.font, Component.literal(VOLUME_LABEL), labelX, 0, 0, false);\n}')
GOOD_RENDER = 'guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);'

check(top_text_invariant(GOOD_RENDER, GOOD_TOP) == [], "基线（好写法）判据通过")
p1 = top_text_invariant(GOOD_RENDER, GOOD_TOP.replace(
    "Component.literal(VOLUME_LABEL), labelX,", "Component.literal(VOLUME_LABEL), boxX(),"))
check(p1 == ["标签没用共享几何 labelX"],
      "把标签改成自己再算一遍 x ⇒ 只报「没用共享几何」这一条", repr(p1))
p2 = top_text_invariant(GOOD_RENDER, GOOD_TOP
                        + '\nguiGraphics.drawString(this.font, Component.literal("提示"), 0, 0, 0, false);')
check("renderTopPage 左对齐 != 1" in p2 and any("合计 != 2" in x for x in p2),
      "★ 多塞一行文字 ⇒ 两条一起变红（合计那条才是「多一处就回退」）", repr(p2))
p3 = top_text_invariant(GOOD_RENDER
                        + '\nguiGraphics.drawCenteredString(this.font, Component.literal("多一行"), 0, 0, 0);',
                        GOOD_TOP)
check(p3 == ["render 居中 != 1", "合计 != 2（实际 3）"],
      "★ 在 render 里多加一行居中文字也会红", repr(p3))

GOOD_BUILTIN = ('addRenderableWidget(Button.builder(Component.literal(MTR_BUILTIN_LABEL), b -> pickBuiltin())'
                '.bounds(SoundListLayout.rightColX(w), y, SoundListLayout.ROW_NAME_W, 20).build());'
                'addRenderableWidget(Button.builder(Component.literal("选用"), b -> pickBuiltin())'
                '.bounds(SoundListLayout.rowPickX(w), y, SoundListLayout.ROW_BTN_W, 20).build());')
check(builtin_row_has_no_delete(GOOD_BUILTIN) == [], "基线（第 0 行只有选用）判据通过")
bad4 = GOOD_BUILTIN + ('addRenderableWidget(Button.builder(Component.literal("删除"), b -> x())'
                       '.bounds(SoundListLayout.rowDeleteX(w), y, SoundListLayout.ROW_BTN_W, 20).build());')
check(builtin_row_has_no_delete(bad4) == ["第 0 行出现了「删除」"],
      "★ 给第 0 行加上「删除」⇒ 精确报这一条", repr(builtin_row_has_no_delete(bad4)))

# ★ 抠方法体这个工具的**自证**：调用点在前、声明在后时，不许把调用点那一段抠回来。
SELF_PROOF = ('    private void m() {\n'
              '        addRenderableWidget(Button.builder(Component.literal("x"), b -> target())\n'
              '                .bounds(0, 0, 1, 1).build());\n'
              '    }\n\n'
              '    private void target() {\n'
              '        setStatus("MARKER");\n'
              '    }\n')
mb = method_body(SELF_PROOF, "target")
check(mb is not None and "MARKER" in mb,
      "★ 自证：`target()` 的调用点在前、声明在后时，抠回来的仍是**声明那个方法体**",
      repr(mb))

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("  - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
