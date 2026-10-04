# -*- coding: utf-8 -*-
"""离线校验：【1.53】`/MBM help` 打开的「预设选择」界面（三个港铁预设 + 最下方全音量输入框）。

用户点名：
- `/MBM help` 运行后打开一个 UI；
- UI 里三个按钮「经典港铁预设」「简单港铁预设」「空白预设」，点一下依次执行**用户逐字给出的
  那几条指令**（功能等同，顺序一致）；【1.58】港铁两个预设末尾各多 2 条把 PSD 子开关打开的指令。
- UI 最下方一个音量输入框，可一次改掉游戏内所有音量，范围 1-1000，
  **没有确认按钮，按 ESC 退出 UI 之后自动应用**。

判据（改错了不报错，症状=「预设少做了一件事」/「按 ESC 没反应」/「退出界面把音量改了」）：
1. 三个预设的指令清单与**用户给的原文逐字相等**（含顺序）—— 直接对着硬编码的期望表比，
   少一条、多一条、写错一个字都会红。
2. 预设走「派发指令」而不是「手抄 setter」：`performPrefixedCommand` + `withSuppressedOutput`
   （不压制就会让每条反馈都刷屏）。
2b. ★★【1.58】**层判据**：PSD 那两行必须落在**素材层**——「不播」写 `none`，不许写 `off`
   （`off` 会被字面量优先匹配成**子开关**，之后配任何素材都不出声 = 用户报的「设不回来」）。
   并且「要出声」的预设必须显式 `pbmmusic open|close -f on`。
3. 全音量 = 11 条指令（覆盖扶梯底噪/扶梯提示音/直梯共用+三项/屏蔽门共用+两项/到站/进站）。
4. 输入框：1~1000、只收数字、maxLength 4、**没改动就不发**（否则「进来点个预设再 ESC」
   会静默把所有音量改成框里那个值）。
5. 开界面走服务端 -> 客户端包（指令在服务端执行、界面在客户端）；
   客户端必须注册该频道并 `setScreen(new MbmHelpScreen())`。
6. 界面按钮文字与 `SmoothLift.presetLabel` 的显示名一一对应（改一处要红）。
7. 【1.54】界面里**只允许两处文字**：标题（=「预设选择」）+ 音量输入框**左边**那个「音量」标签。
   > 数剥掉注释后的文字绘制次数：`drawCenteredString`（居中）== 1 且画 `this.title`；
   > `drawString`（左对齐）== 1 且画 `Component.literal("音量")`；两者合计 == 2。
   > 再断言标签的 x = 框左沿 − 4 − 字体实测宽度（★ 用户点名「左边」，挪到右边要红）。
   > 被点名删掉的那 6 行说明文案不许以字面量复活。
   > 症状对应：加了第二行文字 ⇒ 界面又变回「一堆小字」；标签挪到框右边 ⇒ 与用户要的不符。
   > ★ 只数 `drawCenteredString` 不够 —— 本轮新增的标签走的是 `drawString`，只数前者会漏放行。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
SL = os.path.join(SRC, "main", "java", "smooth", "lift", "SmoothLift.java")
SCREEN = os.path.join(SRC, "main", "java", "smooth", "lift", "client", "MbmHelpScreen.java")
CLIENT = os.path.join(SRC, "main", "java", "smooth", "lift", "client", "SmoothLiftClientEvents.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


# ======================================================================
# 0) 用户逐字给出的三份清单（本脚本的「真值」，不许从源码里反推）
# 【1.58】用户给了新清单：三个预设各插入 `lifthelp -f on|off` 作为**第 3 条**。
#   另有**两处刻意的修正**（见下面「层判据」一节）：
#     - 港铁两个预设在末尾**各多 2 条** `pbmmusic open|close -f on`（把子开关打开）；
#     - 空白预设的 PSD 两条写成 `none` 而不是用户字面的 `off`。
#   所以「逐字一致」只对**前 9 条**成立；后面那两条是**超集**，单独断言。
# ======================================================================
EXPECTED = {
    "classic": [
        "futimusic -f default",
        "futihelp -f on",
        "lifthelp -f on",
        "lifthelp door -f on",
        "lifthelp up -f on",
        "lifthelp down -f on",
        "pbmclosewait -f 1",
        "pbmmusic open -f default",
        "pbmmusic close -f default-m",
    ],
    "simple": [
        "futimusic -f default",
        "futihelp -f off",
        "lifthelp -f on",
        "lifthelp door -f off",
        "lifthelp up -f on",
        "lifthelp down -f on",
        "pbmclosewait -f 1",
        "pbmmusic open -f default",
        "pbmmusic close -f default-s",
    ],
    "blank": [
        "futimusic -f off",
        "futihelp -f off",
        "lifthelp -f off",
        "lifthelp door -f off",
        "lifthelp up -f off",
        "lifthelp down -f off",
        "pbmclosewait -f 1",
        # ★ 用户字面写的是 `pbmmusic open -f off`；那会落到**子开关**（字面量优先），
        #   于是「之后配任何素材都不出声」。按正名改成 `none`（素材层的不播）。
        "pbmmusic open -f none",
        "pbmmusic close -f none",
    ],
}

# 【1.58】两个「要出声」的预设必须显式把 PSD 子开关打开（素材与开关是两层）
BOOT_MUST_ENABLE = {
    "classic": ["pbmmusic open -f on", "pbmmusic close -f on"],
    "simple": ["pbmmusic open -f on", "pbmmusic close -f on"],
}
# 空白预设不许多出东西（它的语义是「全不播」，靠素材层的 none 表达）
BLANK_NO_EXTRA = True

# 界面按钮文字 ↔ presetLabel 的显示名
LABELS = {
    "classic": "经典港铁预设",
    "simple": "简单港铁预设",
    "blank": "空白预设",
}

sl = open(SL, encoding="utf-8").read()
sl_no = strip_comments(sl)
screen = open(SCREEN, encoding="utf-8").read()
screen_no = strip_comments(screen)
client = open(CLIENT, encoding="utf-8").read()
client_no = strip_comments(client)


def find_array(name):
    """从源码里抠出 `private static final String[] NAME = { "a", "b", ... };` 的字符串列表。"""
    m = re.search(r"String\[\]\s+" + name + r"\s*=\s*\{(.*?)\};", sl, flags=re.S)
    if m is None:
        return None
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))


# ======================================================================
# 1) 三个预设 = 用户那 9 条指令（逐字逐序）+ 港铁两个预设末尾的子开关「打开」
# ======================================================================
print()
print("===== 1) 三个预设的指令清单（前 9 条与用户原文逐字比对）=====")

ARRAYS = {
    "classic": "PRESET_CLASSIC_MTR",
    "simple": "PRESET_SIMPLE_MTR",
    "blank": "PRESET_BLANK",
}
for pid, arr_name in ARRAYS.items():
    got = find_array(arr_name)
    check(got is not None, "抠得出 %s 数组" % arr_name)
    if got is None:
        continue
    check(got[:len(EXPECTED[pid])] == EXPECTED[pid],
          "「%s」的前 %d 条与用户原文**逐字逐序**一致" % (LABELS[pid], len(EXPECTED[pid])),
          "实际前 %d 条 = %s" % (len(EXPECTED[pid]), got[:len(EXPECTED[pid])]))
    boot = BOOT_MUST_ENABLE.get(pid, [])
    check(got == EXPECTED[pid] + boot,
          "「%s」= 用户那 9 条 + %d 条" % (LABELS[pid], len(boot)),
          "实际 = %s" % got)

check(find_array("PRESET_CLASSIC_MTR") != find_array("PRESET_BLANK"),
      "经典与空白不是同一份清单（防止复制粘贴改漏）")
check(find_array("PRESET_SIMPLE_MTR") != find_array("PRESET_BLANK"),
      "简单与空白不是同一份清单")

# ======================================================================
# 1b) ★★【1.58】层判据：「不播」写 none（素材层），不写 off（会落到子开关）
#     —— 这就是用户报的「点空白预设后开关门声音没了、而且设不回来」的根因。
# ======================================================================
print()
print("===== 1b) 层判据：PSD 那两行必须落在**素材层**，不能落到子开关层 =====")

PSD_LINES = ("pbmmusic open", "pbmmusic close")


def psd_layer_violations(arr):
    """返回问题列表。判据只看 PSD 那两行（前缀匹配）。

    ★ 唯一实现：正向断言与下面的反向对照**共用这一个函数**（否则两份判据迟早分叉）。
    """
    problems = []
    for cmd in arr or []:
        if not cmd.startswith(PSD_LINES):
            continue
        toks = cmd.split()
        # 形状：pbmmusic <open|close> -f <值>  ⇒ 值那一段决定落在哪一层
        if len(toks) < 4 or toks[0] != "pbmmusic" or "-f" not in toks:
            problems.append("PSD 行形状不认识：" + cmd)
            continue
        val = toks[toks.index("-f") + 1]
        if val == "off":
            problems.append("「off」会落到**子开关**层（字面量优先）⇒ 之后配素材也不出声：" + cmd)
        elif val in ("on", "default", "default-c", "default-m", "default-s", "none"):
            pass  # 合法：on = 子开关层（显式），其余 = 素材层
        else:
            problems.append("PSD 列不认识的名字：" + cmd)
    return problems


def missing_boot_enablers(arr):
    """要出声的预设必须**显式**把 open/close 的子开关打开；返回缺掉的那两条。"""
    return [c for c in ("pbmmusic open -f on", "pbmmusic close -f on") if c not in (arr or [])]


for pid, arr_name in ARRAYS.items():
    arr = find_array(arr_name)
    if arr is None:
        continue
    v = psd_layer_violations(arr)
    check(v == [], "「%s」的 PSD 两行落在正确的层（不出现 off）" % LABELS[pid], repr(v))

# ★ 语义的两条：要出声的预设必须显式开开关；空白必须用 none 表达不播
for pid in ("classic", "simple"):
    arr = find_array(ARRAYS[pid]) or []
    check(missing_boot_enablers(arr) == [],
          "★「%s」显式把 open/close 子开关打开（否则救不回被关掉的开关）" % LABELS[pid],
          repr(missing_boot_enablers(arr)))
blank_arr = find_array(ARRAYS["blank"]) or []
check("pbmmusic open -f none" in blank_arr and "pbmmusic close -f none" in blank_arr,
      "★「空白预设」用**正名 none** 表达不播（素材层），不动子开关")
check(not any(c.endswith("-f off") and c.startswith(PSD_LINES) for c in blank_arr),
      "★★「空白预设」里**不许**再出现 `pbmmusic … -f off` —— 那是本轮 bug 的字面形态")

# ======================================================================
# 2) 预设 -> 派发指令（不是手抄 setter）
# ======================================================================
print()
print("===== 2) 预设走「依次派发指令」 =====")

check(re.search(r'if \("classic"\.equals\(presetId\)\)\s*\{\s*return PRESET_CLASSIC_MTR;', sl_no) is not None,
      "presetCommands：classic -> PRESET_CLASSIC_MTR")
check(re.search(r'if \("simple"\.equals\(presetId\)\)\s*\{\s*return PRESET_SIMPLE_MTR;', sl_no) is not None,
      "presetCommands：simple -> PRESET_SIMPLE_MTR")
check(re.search(r'if \("blank"\.equals\(presetId\)\)\s*\{\s*return PRESET_BLANK;', sl_no) is not None,
      "presetCommands：blank -> PRESET_BLANK")

m = re.search(r"private static int runCommandBatch\(ServerPlayer player, String\[\] commands\)\s*\{(.*?)\n    \}",
              sl, flags=re.S)
check(m is not None, "抠得出 runCommandBatch() 方法体")
if m:
    fb = strip_comments(m.group(1))
    check("performPrefixedCommand(" in fb, "用 performPrefixedCommand 派发指令（语义 = 手敲）")
    check("withSuppressedOutput()" in fb,
          "★ 用 withSuppressedOutput 压制每条指令的反馈（否则 8~11 条会刷屏）")
    check("for (" in fb, "是**逐条**执行（for 循环，保留顺序）")
    check("createCommandSourceStack()" in fb, "用玩家自己的指令源（与手敲同构）")
    check("if (true)" not in fb and "if (false)" not in fb and "return 0;" not in fb,
          "方法体里没有会让整批静默不执行的守卫")

# 预设 id 与界面按钮 id 必须同一套
for pid in EXPECTED:
    check('"%s"' % pid in screen_no, "界面里出现过预设 id 「%s」" % pid)

# ======================================================================
# 3) 全音量清单 = 11 条
# ======================================================================
print()
print("===== 3) 全音量 = 11 条指令 =====")

m = re.search(r"private static String\[\] allVolumeCommands\(int volume\)\s*\{(.*?)\n    \}", sl, flags=re.S)
check(m is not None, "抠得出 allVolumeCommands() 方法体")
if m:
    fb = strip_comments(m.group(1))
    templates = re.findall(r'"([^"]*)"\s*\+\s*v', fb)
    expected_templates = [
        "futiloud -f ",
        "futihelploud -f ",
        "lifthelploud -f ",
        "lifthelploud -f up ",
        "lifthelploud -f down ",
        "lifthelploud -f door ",
        "pbmloud -f ",
        "pbmloud -f open ",
        "pbmloud -f close ",
        "pbmmidiumloud -f ",
        "pbmarriveloud -f ",
    ]
    check(templates == expected_templates, "11 条音量指令（含 -f 强制变体）逐条齐全",
          "实际 = %s" % templates)
    check("String.valueOf(volume)" in fb, "用 String.valueOf 拼音量（不是手写死数）")

m = re.search(r"(?:private|public) static int applyAllVolumes\(ServerPlayer player, int volume\)\s*\{(.*?)\n    \}", sl, flags=re.S)
check(m is not None, "抠得出 applyAllVolumes() 方法体")
if m:
    fb = strip_comments(m.group(1))
    check("clampHelpVolume(volume)" in fb, "音量先夹到 1~1000 再派发")
    check("runCommandBatch(player, allVolumeCommands(" in fb, "全音量也走同一套派发通道")

# ======================================================================
# 4) 开界面：服务端发包 -> 客户端 setScreen
# ======================================================================
print()
print("===== 4) /MBM help 打开界面 =====")

check("MbmHelpOpenPacket" in sl_no, "服务端有开界面包（MbmHelpOpenPacket）")
m = re.search(r"private static int mbmOpenHelp\(CommandContext<CommandSourceStack> context\)\s*\{(.*?)\n    \}",
              sl, flags=re.S)
check(m is not None, "抠得出 mbmOpenHelp() 方法体")
if m:
    fb = strip_comments(m.group(1))
    check("source.getPlayer()" in fb, "取执行指令的玩家")
    check("sendFailure" in fb, "非玩家执行时给失败提示（不是静默）")
    check("Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MbmHelpOpenPacket())" in fb
          or "new MbmHelpOpenPacket()" in fb,
          "把开界面包发给那个玩家")
check("setScreen(new MbmHelpScreen())" in open(os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", "MbmHelpOpenPacket.java"), encoding="utf-8").read() or "setScreen(new MbmHelpScreen())" in client_no, "客户端打开开界面（MbmHelpScreen）")
check("mc.execute(" in open(os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", "MbmHelpOpenPacket.java"), encoding="utf-8").read() or "client.execute(" in client_no, "开界面切回客户端线程（网络线程里直接开界面会崩）")

# 两只 C2S 包：读写类型必须配对
check("readUtf(16)" in open(os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", "MbmPresetPacket.java"), encoding="utf-8").read(),
      "MbmPresetPacket.decode：读 presetId（readUtf(16)）")
check("readVarInt()" in open(os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "network", "MbmAllVolumePacket.java"), encoding="utf-8").read(),
      "MbmAllVolumePacket.decode：读 volume（readVarInt）")
check("new MbmPresetPacket(presetId)" in screen_no, "界面写 presetId 用 MbmPresetPacket(presetId)（与读侧配对）")
check("new MbmAllVolumePacket(volume)" in screen_no, "界面写 volume 用 MbmAllVolumePacket(volume)（与读侧配对）")

# ======================================================================
# 5) 界面本体：三按钮 + 底部输入框 + ESC 自动应用
# ======================================================================
print()
print("===== 5) MbmHelpScreen 本体 =====")

check(os.path.exists(SCREEN), "MbmHelpScreen.java 存在")
check(re.search(r"class MbmHelpScreen extends Screen", screen) is not None, "继承 Screen")

for pid, label in LABELS.items():
    check('Component.literal("%s")' % label in screen,
          "按钮文字「%s」在界面里" % label)
    check(re.search(r'Component\.literal\("%s"\),\s*button -> sendPreset\(ID_%s\)' % (label, pid.upper()),
                    screen) is not None,
          "「%s」按钮点击 -> sendPreset(ID_%s)" % (label, pid.upper()))

check(re.search(r'ID_CLASSIC = "classic"', screen_no) is not None, "ID_CLASSIC = classic")
check(re.search(r'ID_SIMPLE = "simple"', screen_no) is not None, "ID_SIMPLE = simple")
check(re.search(r'ID_BLANK = "blank"', screen_no) is not None, "ID_BLANK = blank")

# 按钮文字 ↔ presetLabel 的一致性
m = re.search(r"static String presetLabel\(String presetId\)\s*\{(.*?)\n    \}", sl, flags=re.S)
check(m is not None, "抠得出 presetLabel() 方法体")
if m:
    fb = strip_comments(m.group(1))
    for pid, label in LABELS.items():
        # presetLabel 的返回值带书名号（回执里是「经典港铁预设」），按钮上不带 —— 只能比内层名字
        check(label in fb, "presetLabel 里 %s 的显示名含「%s」（与按钮文字一致）" % (pid, label))

# 底部输入框
check("volumeInput = new EditBox(" in screen, "有输入框 volumeInput")
check("this.height - 42" in screen, "★ 输入框在界面**最下方**（用 this.height 定位，不是固定 y）")
check("volumeInput.setMaxLength(4)" in screen, "maxLength = 4（1000 够用）")
check("Character::isDigit" in screen, "只收数字")
check("setValue(String.valueOf(openVolume))" in screen, "打开时回填当前值")

# ESC 自动应用
check(re.search(r"public void onClose\(\)\s*\{\s*applyVolume\(\);", screen_no) is not None,
      "★ onClose() 先 applyVolume() 再 super.onClose()（按 ESC 自动应用）")
check("applyVolume()" in screen_no, "有 applyVolume()")
m = re.search(r"private void applyVolume\(\)\s*\{(.*?)\n    \}", screen, flags=re.S)
check(m is not None, "抠得出 applyVolume() 方法体")
if m:
    fb = strip_comments(m.group(1))
    check("volume == openVolume" in fb, "★ 没改动就 return（不静默改音量）")
    check("MbmAllVolumePacket" in fb, "改了就发 MbmAllVolumePacket")
    check("mc.level == null" in fb, "不在世界里时不发（防止空指针 / 无意义包）")
    # 夹取发生在 parseVolume() 里（applyVolume 只负责「改了才发」）；两处都要在，缺一不可。
    check("parseVolume(volumeInput.getValue())" in fb, "音量经 parseVolume 解析（夹取在那一层）")
    mp = re.search(r"private static Integer parseVolume\(String text\)\s*\{(.*?)\n    \}", screen, flags=re.S)
    check(mp is not None and "clampVolume" in mp.group(1), "parseVolume 里真的夹到 1~1000")

# 与模组其它界面一致的两条约定
check("isPauseScreen()" in screen_no and "return false;" in screen_no, "isPauseScreen = false（与其它界面一致）")
check("GLFW_KEY_ENTER" in screen_no, "回车 = 应用并退出（与 EscalatorSpeedScreen 一致）")

# ======================================================================
# 6) 【1.54】界面里不许有小字（只允许「标题」+ 音量框左边那个「音量」标签）
# ======================================================================
print()
print("===== 6) 界面无小字（标题 = 预设选择；音量框左边 1 个标签） =====")

check('super(Component.literal("预设选择"))' in screen,
      "★ 标题字面量是「预设选择」（原来的「MBM 帮助」已改名）")
check(re.search(r'Component\.literal\("MBM 帮助"\)', screen_no) is None,
      "★ 代码里不再出现旧标题「MBM 帮助」（注释里提到不算）")

# 剥注释后分别数「居中文字」与「左对齐文字」——两处合计必须正好 2，且各司其职。
# ★ 只数 drawCenteredString 不够：本轮加的「音量」标签走的是 drawString，只数前者会漏放行。
centered = re.findall(r"drawCenteredString\s*\(([^;]*)\)\s*;", screen_no, flags=re.S)
plain = re.findall(r"(?<![A-Za-z])drawString\s*\(([^;]*)\)\s*;", screen_no, flags=re.S)

check(len(centered) == 1,
      "★ 居中文字只有标题 1 处（剥注释后 drawCenteredString == 1）",
      "实际 %d 处 = %s" % (len(centered), [" ".join(c.split()) for c in centered]))
check("this.title" in (centered[0] if centered else ""),
      "那一处画的是 this.title（=「预设选择」），不是任何说明文案")

check(len(plain) == 1,
      "★ 左对齐文字只有「音量」标签 1 处（剥注释后 drawString == 1）",
      "实际 %d 处 = %s" % (len(plain), [" ".join(c.split()) for c in plain]))
label = plain[0] if plain else ""
check('Component.literal("音量")' in label, "那个标签的文字就是「音量」")
check(screen_no.count('Component.literal("音量")') == 1,
      "「音量」这个标签只有一处（没有第二份重复标签）",
      "实际 %d 处" % screen_no.count('Component.literal("音量")'))


def label_left_of_box(arg):
    """★ 「标签在框左边」的**唯一判据**（7b 的反向对照直接调它，证明这条判据真的会红）：
    x 必须从框左沿 `this.width / 2 - 100` 往左推、按字体实测宽度右对齐，且不得出现框右沿写法。"""
    return ("this.width / 2 - 100" in arg
            and "this.font.width(" in arg
            and "this.width / 2 + 100" not in arg
            and "+ 4 +" not in arg)


# ★ 用户点名「**左边**」—— 挪到右边必须红
check(len(plain) == 1 and label_left_of_box(label),
      "★ 标签贴在输入框**左沿外侧** 4 px（x = this.width/2 − 100 − 4 − 字体实测宽度）",
      "实际 = " + " ".join(label.split()))
check("this.font.lineHeight" in label, "标签纵向与 20 px 高的输入框居中对齐")

check(len(centered) + len(plain) == 2,
      "★ 全界面文字绘制合计 == 2（标题 + 音量标签；多一处即回退）")

# 被点名删掉的 6 行小字，不许以字面量复活（在注释/文档里提旧文案不算，故只看剥注释后的源码）
REMOVED_TEXTS = [
    "港铁预设：点一下立刻生效（不必退出界面）",
    "经典：扶梯默认音 · 提示音全开 · 屏蔽门 开门默认 / 关门 default-m",
    "简单：扶梯默认音、扶梯提示音关 · 直梯只留上下楼 · 屏蔽门关门 default-s",
    "空白：扶梯 / 直梯 / 屏蔽门提示音全部关闭",
    "全音量：一次改掉模组所有音量（1~1000，100 = 原始音量）",
    "按 ESC 退出界面时自动应用（没改动就不发）",
]
leftover = [t for t in REMOVED_TEXTS if t in screen_no]
check(not leftover, "6 行说明小字全部删净（代码里一处不剩）", "残留 = %s" % leftover)

# 删小字不许顺手删掉该留的东西
check("volumeInput = new EditBox(" in screen_no, "音量输入框还在（删的是文字，不是控件）")
check(screen_no.count("sendPreset(") >= 3, "三个预设按钮的点击回调还在")
check(re.search(r"public void onClose\(\)\s*\{\s*applyVolume\(\);", screen_no) is not None,
      "ESC 自动应用音量的约定还在（没被顺手删）")
check("volumeInput.setHint(" not in screen_no,
      "标签不用 EditBox 的 hint（hint 只在空值/聚焦前显示，回填了数字就永远看不见）")

# ======================================================================
# 7) 反向对照：清单比对的严格性自证
# ======================================================================
print()
print("===== 7) 反向对照 =====")

TITLE_LINE = "guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);"

# 7a) 「文字合计 == 2」这条要够狠：往 render 里再塞一行小字就必须变红
probe_render = screen_no.replace(
    TITLE_LINE,
    TITLE_LINE + "\n"
    '    guiGraphics.drawCenteredString(this.font, Component.literal("港铁预设：点一下立刻生效（不必退出界面）"), this.width / 2, 26, 0xA0A0A0);',
    1)
check(probe_render != screen_no, "反向对照的探针确实插进去了（基线可用）")
probe_centered = re.findall(r"drawCenteredString\s*\(([^;]*)\)\s*;", probe_render, flags=re.S)
probe_plain = re.findall(r"(?<![A-Za-z])drawString\s*\(([^;]*)\)\s*;", probe_render, flags=re.S)
check(len(probe_centered) + len(probe_plain) != 2, "塞回一行小字 -> 「文字合计 == 2」变红（对照）")
check(any(t in probe_render for t in REMOVED_TEXTS),
      "塞回旧文案 -> 「小字删净」变红（对照）")

# 7b) 「标签必须在框左边」这条也要够狠：把标签挪到框右边就必须变红
check(label_left_of_box(label), "「在框左边」的判据对当前写法成立（基线）")
probe_right = label.replace("this.width / 2 - 100", "this.width / 2 + 100 + 6")
check(probe_right != label, "「挪到右边」的探针确实改动了表达式（基线可用）")
check(not label_left_of_box(probe_right),
      "挪到框右边 -> 「标签在框左边」这条判据当场变红（对照）")

# 7c) 预设清单比对的严格性
probe = list(EXPECTED["classic"])
check(probe == EXPECTED["classic"], "原样清单比对通过（基线）")
probe_extra = probe + ["futiloud -f 100"]
check(probe_extra != EXPECTED["classic"], "多一条会红（对照）")
probe_missing = probe[:-1]
check(probe_missing != EXPECTED["classic"], "少一条会红（对照）")
probe_reorder = list(reversed(probe))
check(probe_reorder != EXPECTED["classic"], "顺序调了会红（对照）")
probe_typo = [s.replace("default-m", "default") for s in probe]
check(probe_typo != EXPECTED["classic"], "写错一个素材名会红（对照）")

# 7d) ★【1.58】层判据的严格性自证（与 1b 共用同一份判据函数）
check(psd_layer_violations(EXPECTED["blank"]) == [], "基线：空白预设（none）判据不误报")
probe_blank_off = [c.replace("-f none", "-f off") for c in EXPECTED["blank"]]
_v = psd_layer_violations(probe_blank_off)
check(len(_v) == 2,
      "★ 把空白预设那两行改回 `-f off` ⇒ 层判据**恰好**报 2 条（这就是本轮 bug 的字面形态）",
      repr(_v))
check(psd_layer_violations(["pbmmusic open -f on", "pbmmusic close -f default-m"]) == [],
      "基线：`on`（子开关层，显式）与 `default-m`（素材层）都不算违规")
check(psd_layer_violations(["pbmmusic open -f bogus"]) != [],
      "编造一个不在名单里的层值 ⇒ 也会红（对照）")
check(missing_boot_enablers(EXPECTED["classic"] + BOOT_MUST_ENABLE["classic"]) == [],
      "基线：经典预设的两条「开子开关」都在")
check(missing_boot_enablers([]) == ["pbmmusic open -f on", "pbmmusic close -f on"],
      "★ 把「开子开关」那两条整段删掉 ⇒ 「必须显式开」这条当场报出缺的两条（对照）",
      repr(missing_boot_enablers([])))
check(missing_boot_enablers(["pbmmusic open -f on"]) == ["pbmmusic close -f on"],
      "只开一半（漏了 close）也会红（对照）")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
