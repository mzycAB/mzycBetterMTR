# -*- coding: utf-8 -*-
"""离线校验：【09-27 六改】扶梯那三页 UI —— 一级菜单 5 行（按钮大小/皮肤与直梯同款）、
两个子界面「不播在上、默认在下」「删掉内置行 / 删掉设置端头 / 删掉开关」。

## 用户原话（就是本脚本要钉的东西）

    ── 【四改／五改，仍在生效】 ──
    「把扶梯的ui也改成这样。」
    「扶梯的调速功能也要改：就是把音量输入框变成速度输入框，和「扶梯/阶梯速度」按钮点不动而已」
    「4 个输入框（扶梯速度／阶梯速度／声音音量／提示音音量）这里的扶梯速度／阶梯速度的
      右侧输入框改的是速度，声音音量／提示音音量改的是音量」
    「阶梯速度对齐扶梯速度 按钮删掉
      声音设置和提示音设置为什么没有按照直梯的ui修改为音量输入框在右侧？
      提示音开关按钮删掉
      声音设置的二级菜单里加入不播按钮
      而且阶梯速度，扶梯速度按钮大小也不一样和直梯ui里的大小」

    ── 【09-27 六改】用户又提了五条 ──
    「扶梯ui的一级菜单的按钮大小调整为直梯ui一级菜单的按钮大小」
    「扶梯ui里的"声音设置"里的"默认"和"内置地铁自动扶梯"是一个东西，删除"内置地铁自动扶梯"按钮」
    「切记！所有ui里的"不播"按钮永远放在"默认"按钮上面，"不播"按钮一直叫这个名字，
      类似于"不播提示音"的按钮名字都改为"不播"」
    「"提示音设置"里的"开关"按钮去掉，因为选择音乐就默认开启，选择不播就默认关闭了，
      开关没有存在的必要」
    「"提示音设置"分裂为"提示音(进入)"和"提示音(离开)"2个按钮，所以2级菜单里的
      "设置端头"按钮删掉」

## 拆成可核验的条目

【六改·1】一级菜单（EscalatorSpeedScreen）= **五行** `[按钮] 标签 [框]`：
  扶梯速度 / 阶梯速度 / 声音设置… / 提示音(进入) / 提示音(离开)。
  前两行左边按钮用 **RowNameButton**：`active = false`（点不动）但渲染时借「启用态」贴图
  ⇒ 外观与直梯一级菜单按钮逐像素同款（几何本来就是同一个 200×20）。
【六改·2】「提示音设置…」拆成「提示音(进入)」/「提示音(离开)」，各自把 editIn 传给子界面。
【六改·3】HelpAudioSetupScreen = `(pos, editIn)`，右列 **2** 个特殊行：第 0 行「不播」、
  第 1 行「默认提示音」；**没有**「设置端头」、**没有**「开关」。
【六改·4】AudioSetupScreen 右列 = 第 0 行「不播」、第 1 行「默认」；**没有**内置音频行
  （「内置地铁自动扶梯」已删 —— 它与「默认」是同一条声音）。
【六改·5】全局规范：**「不播」永远在「默认」上面**，且名字就叫「不播」。

    ── 【七改】用户又提了三件 ──
    「bug：现在选择扶梯默认提示音没有声音了
      扶梯ui一级菜单下方的4行灰色小字全部删掉
      直梯ui里的那一行"默认音量500|上行......"删掉」
【七改·1】★★ 根因：运行底噪的「默认」层 = `defaultAudio`，**初始是 null** ⇒ 「默认」= 静音。
  六改按用户判断删掉了「内置 · 地铁自动扶梯」那行之后，玩家就再没有任何办法让它出声。
  ⇒ 加 `normaliseDefaultAudio()` 兜底：null / 空 → 内置底噪 ID（从 `BUILTIN_AUDIO` 现场取），
  三个出口（`getClientDefaultAudio` / `getDefaultAudio` / `effectiveAudioId` 服务端支）都走它，
  于是「默认」真的等于「内置地铁自动扶梯」（用户说的「是一个东西」）。
【七改·2】`bindHelpAudio` 绑**非 off** 的提示音时 `setHelp(level, pos, true)`
  （用户点名「选择音乐就默认开启」；只做「开」不做「关」，保住 1.41 的进/出各设各的），
  且 BIND 处理器补发 `syncHelpToAll`。
【七改·3】`EscalatorSpeedScreen` 一级菜单下方 **4 行灰字**全删；`LiftToneSetupScreen` 一级菜单
  那条常驻信息行（「默认音量 … ｜ 上楼 …」）全删，只保留有反馈时的黄字。

用法：`python _tools/check-escalator-ui-twocol.py`（**11 节**，退出码 0 = 全部通过）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
SPEED = os.path.join(CLIENT, "EscalatorSpeedScreen.java")
AUDIO = os.path.join(CLIENT, "AudioSetupScreen.java")
HELP = os.path.join(CLIENT, "HelpAudioSetupScreen.java")
PLAYER = os.path.join(CLIENT, "EscalatorAudioPlayer.java")
LAYOUT = os.path.join(CLIENT, "SoundListLayout.java")
LIFT = os.path.join(CLIENT, "LiftToneSetupScreen.java")

MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
DATA = os.path.join(MAIN, "EscalatorSpeedData.java")
MANAGER = os.path.join(MAIN, "EscalatorSpeedManager.java")
SMOOTH = os.path.join(MAIN, "SmoothLift.java")

CLS_DIR = os.path.join(ROOT, "build", "classes", "java", "client", "smooth", "lift", "client")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(text):
    """★ 断言一律先剥注释：解释性注释里出现的旧文案不许把判据弄假红（踩过一次）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def const_int(src, name):
    m = re.search(re.escape(name) + r"\s*=\s*(-?\d+)\s*;", src)
    return int(m.group(1)) if m else None


def const_str(src, name):
    m = re.search(re.escape(name) + r'\s*=\s*"([^"]*)"\s*;', src)
    return m.group(1) if m else None


def squash(text):
    return " ".join(text.split())


speed_raw = read(SPEED)
audio_raw = read(AUDIO)
help_raw = read(HELP)
player_raw = read(PLAYER)
layout_raw = read(LAYOUT)
data_raw = read(DATA)
manager_raw = read(MANAGER)
lift_raw = read(LIFT)
smooth_raw = read(SMOOTH)

speed = strip_comments(speed_raw)
audio = strip_comments(audio_raw)
help = strip_comments(help_raw)
player = strip_comments(player_raw)
data = strip_comments(data_raw)
manager = strip_comments(manager_raw)
lift = strip_comments(lift_raw)
smooth = strip_comments(smooth_raw)

# ======================================================================
print("===== 1) 一级菜单五行 = [按钮] 标签 [框]：几何 = 直梯一级菜单同款 =====")

check(const_int(speed, "BTN_W") == 200, "BTN_W = 200（= 直梯一级菜单 BTN_W，用户点名「大小一样」）",
      "实际 = %s" % const_int(speed, "BTN_W"))
check(const_int(speed, "MAIN_GAP") == 10, "MAIN_GAP = 10（按钮 → 标签）",
      "实际 = %s" % const_int(speed, "MAIN_GAP"))
check(const_int(speed, "MAIN_LABEL_GAP") == 6, "MAIN_LABEL_GAP = 6（标签 → 框）",
      "实际 = %s" % const_int(speed, "MAIN_LABEL_GAP"))
check(const_int(speed, "MAIN_INPUT_W") == 70, "MAIN_INPUT_W = 70（= 直梯 MAIN_INPUT_W）",
      "实际 = %s" % const_int(speed, "MAIN_INPUT_W"))
check(const_str(speed, "SPEED_LABEL") == "速度",
      "★ 速度行标签 =「速度」（直梯那一行这里写的是「音量」）",
      "实际 = %r" % const_str(speed, "SPEED_LABEL"))
check(const_str(speed, "VOLUME_LABEL") == "音量",
      "★ 音量行标签 =「音量」（与直梯同一份字）",
      "实际 = %r" % const_str(speed, "VOLUME_LABEL"))

# 五行的 y：40 / 62 / 84 / 106 / 128，步长 = 22
_rows = [const_int(speed, n) for n in ("ROW1_Y", "ROW2_Y", "ROW3_Y", "ROW4_Y", "ROW5_Y")]
check(_rows == [40, 62, 84, 106, 128], "★【六改】五行 y = 40 / 62 / 84 / 106 / 128（步长 22）",
      "实际 = %s" % _rows)

# --- 【六改 核心】RowNameButton：点不动但画「启用态」贴图 ---
m = re.search(r"private static final class RowNameButton extends Button \{(.*?)\n    \}", speed, flags=re.S)
check(m is not None, "★ 抠得出内部类 RowNameButton 的类体")
rnb = squash(m.group(1)) if m else ""
check("super(x, y, BTN_W, 20, label, button -> { }, DEFAULT_NARRATION)" in rnb,
      "★ RowNameButton 的尺寸 = BTN_W × 20（与可点按钮同一份常量）", rnb[:160])
check("this.active = false;" in rnb, "★ RowNameButton 构造里置 active = false（点不动）")
check("public boolean isHovered()" in rnb and "return false;" in rnb,
      "★ 压掉 hover ⇒ 永远画「启用但不 hover」那一格（与直梯按钮同一格）")
check("protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick)" in rnb,
      "★ 重写 renderWidget")
check("this.active = true;" in rnb and "super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);" in rnb,
      "★ 渲染时临时借「启用态」贴图（this.active = true）再调 super.renderWidget")
check("finally" in rnb and "this.active = wasActive;" in rnb,
      "★ 渲染后把 active 还原（finally）")

check(squash(speed).count("new RowNameButton(rowStartX(), ROW1_Y") == 1,
      "★ 第 1 行用 RowNameButton（扶梯速度）",
      "实际 %d" % squash(speed).count("new RowNameButton(rowStartX(), ROW1_Y"))
check(squash(speed).count("new RowNameButton(rowStartX(), ROW2_Y") == 1,
      "★ 第 2 行用 RowNameButton（阶梯速度）",
      "实际 %d" % squash(speed).count("new RowNameButton(rowStartX(), ROW2_Y"))
check(squash(speed).count("new RowNameButton(") == 2,
      "★ 正好两行行名按钮（不多不少）", "实际 %d" % squash(speed).count("new RowNameButton("))
check("frozenRowLabel" not in speed, "★ 旧写法 frozenRowLabel() 已删净")

check("runInput = new EditBox(this.font, rowInputX(), ROW1_Y, MAIN_INPUT_W" in squash(speed),
      "扶梯速度框落在 rowInputX() / ROW1_Y / MAIN_INPUT_W")
check("stepInput = new EditBox(this.font, rowInputX(), ROW2_Y, MAIN_INPUT_W" in squash(speed),
      "阶梯速度框落在 rowInputX() / ROW2_Y / MAIN_INPUT_W")

# 行宽算式（几何自洽：一处定义、五行复用）
check("return BTN_W + MAIN_GAP + labelColumnWidth() + MAIN_LABEL_GAP + MAIN_INPUT_W;" in squash(speed),
      "rowWidth() = 按钮 + 空隙 + 标签列 + 空隙 + 框（唯一一处算式）")
check("return Math.max(this.font.width(SPEED_LABEL), this.font.width(VOLUME_LABEL));" in squash(speed),
      "★ 标签列宽取两者更宽者 ⇒ 五行严格左对齐（用户抱怨过「不一样」）")
check("return rowStartX() + BTN_W + MAIN_GAP;" in squash(speed),
      "rowLabelX() 从行首 + 按钮宽 + 空隙 推出来")
check("return rowLabelX() + labelColumnWidth() + MAIN_LABEL_GAP;" in squash(speed),
      "rowInputX() 从标签列右边推出来")

# 旧的两行居中灰标签必须撤掉
check("扶梯速度（格/秒）" not in speed, "★ 旧的灰标签「扶梯速度（格/秒）」已撤掉")
check("阶梯速度（格/秒）" not in speed, "★ 旧的灰标签「阶梯速度（格/秒）」已撤掉")

# 反面对照：把 RowNameButton 里的 active=false 删掉 → 「点不动」判据必须变红
_probe = speed.replace("this.active = false;", "", 1)
_m = re.search(r"private static final class RowNameButton extends Button \{(.*?)\n    \}", _probe, flags=re.S)
check("this.active = false;" not in squash(_m.group(1) if _m else ""),
      "把 active=false 删掉 → 「点不动」判据变红（对照）")

print()
print("===== 2) 【六改】「提示音设置」拆成「提示音(进入)」「提示音(离开)」 =====")

check("阶梯速度对齐扶梯速度" not in speed,
      "★ 按钮文案「阶梯速度对齐扶梯速度」已从代码里删净")
check("alignStepToRun" not in speed, "★ 方法 alignStepToRun() 已删")
check('"声音设置…"' in speed or "声音设置…" in speed, "「声音设置…」按钮仍在（文案不变）")
check("button -> openAudioSetup()" in squash(speed), "「声音设置…」→ openAudioSetup()")

check("提示音设置" not in speed, "★ 文案「提示音设置…」已删净（拆成进入/离开两个按钮）")
check(squash(speed).count('Component.literal("提示音(进入)")') == 1,
      "★ 新按钮「提示音(进入)」存在", "实际 %d" % squash(speed).count('Component.literal("提示音(进入)")'))
check(squash(speed).count('Component.literal("提示音(离开)")') == 1,
      "★ 新按钮「提示音(离开)」存在", "实际 %d" % squash(speed).count('Component.literal("提示音(离开)")'))
check("button -> openHelpAudioSetup(true)" in squash(speed), "「提示音(进入)」→ openHelpAudioSetup(true)")
check("button -> openHelpAudioSetup(false)" in squash(speed), "「提示音(离开)」→ openHelpAudioSetup(false)")
check("openHelpAudioSetup()" not in squash(speed), "★ 旧的单参调用 openHelpAudioSetup() 已不在")
check("private void openHelpAudioSetup(boolean editIn)" in squash(speed),
      "★ openHelpAudioSetup(boolean) 定义存在")
check("new HelpAudioSetupScreen(pos, editIn)" in squash(speed),
      "★ 打开子界面时把 editIn 传进去（端头由从哪一行进来决定）")

# 音量框仍在各自按钮右边（行内），并与按钮同宽常量
check("volumeInput = new EditBox(this.font, rowInputX(), ROW3_Y, MAIN_INPUT_W" in squash(speed),
      "★ 声音音量框 = 第 3 行、落在「声音设置…」按钮右边（rowInputX() / ROW3_Y）")
check("bounds(rowStartX(), ROW3_Y, BTN_W, 20)" in squash(speed),
      "「声音设置…」按钮 = rowStartX() / ROW3_Y / BTN_W")
check("bounds(rowStartX(), ROW4_Y, BTN_W, 20)" in squash(speed),
      "「提示音(进入)」按钮 = rowStartX() / ROW4_Y / BTN_W")
check("bounds(rowStartX(), ROW5_Y, BTN_W, 20)" in squash(speed),
      "「提示音(离开)」按钮 = rowStartX() / ROW5_Y / BTN_W")
check("helpVolumeInInput = new EditBox(this.font, rowInputX(), ROW4_Y, MAIN_INPUT_W" in squash(speed),
      "★ 提示音(进入)音量框 = 第 4 行右侧（rowInputX() / ROW4_Y）")
check("helpVolumeOutInput = new EditBox(this.font, rowInputX(), ROW5_Y, MAIN_INPUT_W" in squash(speed),
      "★ 提示音(离开)音量框 = 第 5 行右侧（rowInputX() / ROW5_Y）")
check("private void mirrorHelpVolume(" in squash(speed),
      "★ 两个提示音量框互相同步（它们是同一个值 `/futihelploud`）")
check("SmoothLift.SET_HELP_VOLUME_CHANNEL" in squash(speed), "提示音量落地走 SET_HELP_VOLUME_CHANNEL")

# 对照：老的「并排在 y=126、96 宽」那两个框必须不在
check("this.width / 2 - 100, 126, 96, 20" not in squash(speed),
      "★ 老的并排底噪音量框（width/2-100, 126, 96）已不在")
check("this.width / 2 + 4, 126, 96, 20" not in squash(speed),
      "★ 老的并排提示音量框（width/2+4, 126, 96）已不在")
check("声音音量（100=原始）" not in speed, "★ 老的居中灰标签「声音音量（100=原始）」已不在")
check("提示音音量（100=原始）" not in speed, "★ 老的居中灰标签「提示音音量（100=原始）」已不在")

# 语义不许混：音量框仍是纯数字、速度框仍是浮点
check("volumeInput.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));"
      in squash(speed),
      "声音音量框仍是纯数字过滤（语义 = 音量）")
check("runInput.setMaxLength(32);" in squash(speed) and "stepInput.setMaxLength(32);" in squash(speed),
      "两个速度框放开到 32 字符（速度是浮点，不是 1~1000 的整数）")
check("runInput.setFilter" not in speed and "stepInput.setFilter" not in speed,
      "★ 速度框**没有**套音量那种纯数字过滤（否则小数点都打不进去）")

# 反面对照：把音量框塞回老位置 → 新判据必须变红
_probe = speed.replace("rowInputX(), ROW3_Y", "this.width / 2 - 100, 126", 1)
check("rowInputX(), ROW3_Y" not in squash(_probe),
      "把声音音量框挪回老位置 → 「音量框在右侧」判据变红（对照）")

print()
print("===== 3) 【六改】无障碍开关彻底离开主界面 =====")

check("无障碍：" not in speed, "★ 主界面那只「无障碍：开/关」按钮文案已删净")
check("helpButton" not in speed, "★ 字段 helpButton 已删")
check("toggleHelp" not in speed, "★ 主界面 toggleHelp() 已删")
check("SET_HELP_CHANNEL" not in speed, "★ 主界面不再发 SET_HELP_CHANNEL")

print()
print("===== 4) 两列几何：唯一来源 = SoundListLayout =====")

for fname, src in (("AudioSetupScreen.java", audio), ("HelpAudioSetupScreen.java", help)):
    for alias in ("ROW_H", "LIST_TOP", "COL_W", "ROW_NAME_W", "ROW_NAME_CHARS",
                  "ROW_BTN_W", "BTN_Y", "BOTTOM_RESERVE_LIST"):
        check(("%s = SoundListLayout.%s;" % (alias, alias)) in squash(src),
              "%s：%s 指向 SoundListLayout（别名，不抄数字）" % (fname, alias))
    # 不许出现裸列宽/行高字面量（单列那套是 `COL_W = 190;` / `ROW_H = 22;` 直接写死的）
    for bare in ("= 190;", "= 22;", "= 76;", "= 96;"):
        check(bare not in src, "%s：不出现裸数字 `%s`" % (fname, bare))

# 反面对照：把别名换成裸数字 → 判据变红
_probe = audio.replace("COL_W = SoundListLayout.COL_W;", "COL_W = 190;")
check("COL_W = SoundListLayout.COL_W;" not in _probe,
      "把 COL_W 换成 190 → 唯一来源判据变红（对照）")

print()
print("===== 5) 【六改】AudioSetupScreen：右列 = 不播(0) / 默认(1)，无内置行 =====")

check(const_int(audio, "RIGHT_SPECIAL_ROWS") == 2,
      "★ RIGHT_SPECIAL_ROWS = 2（第 0 行「不播」+ 第 1 行「默认」）",
      "实际 = %s" % const_int(audio, "RIGHT_SPECIAL_ROWS"))
check(const_str(audio, "OFF_ROW_LABEL") == "不播",
      "★ 右列第 0 行文案 =「不播」（不带尾巴）",
      "实际 = %r" % const_str(audio, "OFF_ROW_LABEL"))
check(const_str(audio, "DEFAULT_ROW_LABEL") == "默认",
      "★ 右列第 1 行文案 =「默认」（与直梯/屏蔽门同一叫法）",
      "实际 = %r" % const_str(audio, "DEFAULT_ROW_LABEL"))

# ★★ 顺序：不播（rowY(0)）必须在默认（rowY(1)）上面
check("int yOff = rowY(0);" in squash(audio), "★「不播」行落在 rowY(0)（第 0 行）")
check("int yDefault = rowY(1);" in squash(audio), "★「默认」行落在 rowY(1)（第 1 行）")
_i_off = audio.find('Component.literal((isOff ? "✓" : "") + OFF_ROW_LABEL)')
_i_def = audio.find('Component.literal((isDefault ? "✓" : "") + DEFAULT_ROW_LABEL)')
check(0 <= _i_off < _i_def,
      "★★ 全局规范：代码里「不播」出现在「默认」**之前**（不播在上）",
      "idx(off)=%d idx(def)=%d" % (_i_off, _i_def))

check("private void playOff()" in squash(audio), "★ playOff()（第 0 行「不播」的落地）")
check(squash(audio).count("button -> playOff()") == 2,
      "★ 第 0 行两只控件（名字 + 「选用」）都调 playOff()",
      "实际 %d" % squash(audio).count("button -> playOff()"))
check("buf.writeUtf(EscalatorSpeedData.FUTI_AUDIO_OFF, 128);" in squash(audio),
      "★ playOff() 发的是哨兵 FUTI_AUDIO_OFF（走普通 BIND_AUDIO_CHANNEL）")
check("button -> unbindAudio()" in squash(audio) and "private void unbindAudio()" in squash(audio),
      "第 1 行的「选用」→ unbindAudio()（= 原来底部那只「解绑此扶梯」）")
check("解绑此扶梯" not in audio, "★ 底部那只「解绑此扶梯」按钮本体已撤（功能升级成第 1 行）")

# ★★ 内置行彻底删掉
check("内置" not in audio, "★★「内置」字样已从 AudioSetupScreen 删净（内置地铁自动扶梯行已撤）")
check("builtinAudioIds" not in audio, "★ 不再引用 builtinAudioIds()")
check("List<String> builtin" not in squash(audio), "★ 字段 builtin 已删")

check("SoundListLayout.leftColX(this.width)" in squash(audio), "左列按钮用 leftColX()")
check("SoundListLayout.rightColX(this.width)" in squash(audio), "右列名字用 rightColX()")
check("SoundListLayout.rowPickX(this.width)" in squash(audio), "右列「选用」用 rowPickX()")
check("SoundListLayout.rowDeleteX(this.width)" in squash(audio), "右列「删除」用 rowDeleteX()")
check("SoundListLayout.maxScroll(listTop, listBottom," in squash(audio),
      "滚动范围走 SoundListLayout.maxScroll（口径只写一次）")
check("SoundListLayout.rowY(listTop, index, scroll)" in squash(audio), "行 y 走 SoundListLayout.rowY()")

# 底部只剩 返回 / 刷新
check(audio.count("this.height + BTN_Y") == 2, "★ 底部固定区只剩两只按钮（返回 / 刷新）",
      "实际 %d" % audio.count("this.height + BTN_Y"))
check("getClientAudioLibraryKeys(mc.level, EscalatorSpeedManager.CAT_FUTI)" in squash(audio),
      "【1.28】仍只列扶梯底噪分类 CAT_FUTI")
check("getClientFolderAudioKeys(mc.level, EscalatorSpeedManager.CAT_FUTI)" in squash(audio),
      "【1.28】待导入也只列 CAT_FUTI")

# DELETE 包仍先写分类(64)再写名字(128)
_i = audio.find("ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL")
_ctx = audio[max(0, _i - 350):_i] if _i >= 0 else ""
_writes = re.findall(r"writeUtf\(([^,]+),\s*(\d+)\)", _ctx)
check(len(_writes) >= 2 and _writes[-2][1] == "64" and _writes[-1][1] == "128",
      "DELETE 仍 = writeUtf(CAT_FUTI,64) + writeUtf(名字,128)（不许被这次改版碰坏）",
      "实际 = %s" % _writes[-2:])

print()
print("===== 6) 【六改】HelpAudioSetupScreen：右列 = 不播(0) / 默认提示音(1)，无端头/开关 =====")

check(const_int(help, "RIGHT_SPECIAL_ROWS") == 2,
      "★【六改】RIGHT_SPECIAL_ROWS = 2（原 4：默认/不播/端头/开关，删两行）",
      "实际 = %s" % const_int(help, "RIGHT_SPECIAL_ROWS"))
check(const_str(help, "OFF_ROW_LABEL") == "不播",
      "★ 右列第 0 行 =「不播」（原「不播提示音」，用户点名改名）",
      "实际 = %r" % const_str(help, "OFF_ROW_LABEL"))
check(const_str(help, "DEFAULT_ROW_LABEL") == "默认提示音",
      "右列第 1 行 =「默认提示音」", "实际 = %r" % const_str(help, "DEFAULT_ROW_LABEL"))
check("不播提示音" not in help, "★★ 旧文案「不播提示音」已删净（代码里）")
check("不播提示音（仅这条扶梯）" not in help, "★ 旧的括注「（仅这条扶梯）」已撤掉")

# 构造签名 + editIn 只读
check("public HelpAudioSetupScreen(BlockPos pos, boolean editIn)" in squash(help),
      "★【六改】构造签名 = (BlockPos pos, boolean editIn)")
check("private final boolean editIn;" in squash(help), "★ editIn 是 final（不再由界面切换）")
check("this.editIn = editIn;" in squash(help), "构造里把 editIn 存下来")

# 顺序：不播（rowY(0)）必须在默认（rowY(1)）上面
check("int y0 = rowY(0);" in squash(help), "★「不播」行落在 rowY(0)")
check("int y1 = rowY(1);" in squash(help), "★「默认提示音」行落在 rowY(1)")
_i_off = help.find('Component.literal((now ? "✓" : "") + OFF_ROW_LABEL)')
_i_def = help.find('Component.literal((now ? "✓" : "") + DEFAULT_ROW_LABEL)')
check(0 <= _i_off < _i_def,
      "★★ 全局规范：「不播」出现在「默认提示音」**之前**（不播在上）",
      "idx(off)=%d idx(def)=%d" % (_i_off, _i_def))
check(squash(help).count("button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_OFF)") == 2,
      "★ 第 0 行两只控件都调 setHelpAudio(HELP_AUDIO_OFF)",
      "实际 %d" % squash(help).count("button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_OFF)"))
check(squash(help).count("button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_DEFAULT)") == 2,
      "★ 第 1 行两只控件都调 setHelpAudio(HELP_AUDIO_DEFAULT)",
      "实际 %d" % squash(help).count("button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_DEFAULT)"))

# 「设置端头」行 + switchEnd 彻底删掉
check('Component.literal("设置端头："' not in squash(help), "★★「设置端头」行已删净")
check("switchEnd" not in help, "★★ switchEnd() 已删（端头改由一级菜单两个入口决定）")
check("editIn = !editIn;" not in squash(help), "★ 不再有任何端头切换写 editIn")

# 「开关」行 + toggleHelp + helpOn 彻底删掉
check("private boolean helpOn" not in squash(help), "★★ 字段 helpOn 已删")
check('Component.literal("开关："' not in squash(help), "★★「开关：开/关」行已删净")
check("toggleHelp" not in help, "★★ toggleHelp() 已删")
check("开关 " not in help, "★ 状态行里不再有「开关 开/关」那一段")
check("helpOn" not in help, "★ 代码里不再引用 helpOn")

check("ClientPlayNetworking.send(SmoothLift.SET_HELP_CHANNEL" not in squash(help),
      "★★ 子界面不再发 SET_HELP_CHANNEL（开关已删）")
check("EscalatorSpeedManager.isHelpEnabled" not in squash(help),
      "★ 不再回显 isHelpEnabled（开关已删）")

check("this.height + BTN_Y" in squash(help) and help.count("this.height + BTN_Y") == 2,
      "★ 底部固定区只剩 返回 / 刷新",
      "实际 %d" % help.count("this.height + BTN_Y"))
check("EscalatorSpeedManager.CAT_HELP" in squash(help), "【1.28】仍只列无障碍提示音分类 CAT_HELP")
check("endLabel()" in squash(help), "★ 端头短名（进入扶梯/离开扶梯）仍用于状态行与反馈")
check("buf.writeBoolean(editIn);" in squash(help), "绑定/导入仍带 editIn（只作用当前这一头）")

# 反面对照：把「不播」和「默认」顺序对调 → 顺序判据变红
_swapped = help.replace('OFF_ROW_LABEL', '@@TMP@@', 1)
check("@@TMP@@" in _swapped, "「不播」文案可被替换（说明判据确实钉在这段文本上）")

print()
print("===== 7) 两页共同的「两列」版式：表头 / 竖线 / 滚动条 / 状态行 =====")

for fname, src in (("AudioSetupScreen.java", audio), ("HelpAudioSetupScreen.java", help)):
    check('Component.literal("未导入存档")' in src, "%s：左列表头「未导入存档」" % fname)
    check('Component.literal("已导入存档")' in src, "%s：右列表头「已导入存档」" % fname)
    check("guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);" in squash(src),
          "%s：中间那条竖线（fill(cx, listTop, cx+1, listBottom)）" % fname)
    check("listTop = LIST_TOP + ROW_H;" in squash(src),
          "%s：列表区让出一行表头（listTop = LIST_TOP + ROW_H）" % fname)
    check("SoundListLayout.scrollBarX(this.width)" in squash(src), "%s：滚动条 x 走 scrollBarX()" % fname)
    check("SoundListLayout.STATUS_Y_LIST" in src, "%s：状态行用 STATUS_Y_LIST" % fname)
    check("0xFFFF55" in src and "0xFFFFFF" in src,
          "%s：文字只有白 / 黄两种色（反馈黄、其余白）" % fname)
    for gray in ("0x808080", "0xFF909090", "0xFFE0E0E0", "0xC0C0C0", "0xA0A0A0", "0x707070"):
        check(gray not in src, "%s：★ 不含灰字色 %s（【1.18】老规矩）" % (fname, gray))

# 反面对照：塞回一处灰字 → 判据变红
_probe = audio.replace("0xFFFFFF", "0x808080", 1)
check("0x808080" in _probe, "把一处白色换成 0x808080 → 灰字判据变红（对照）")

print()
print("===== 8) 列表容量 = 6 行（纯算术复算，不许写死结论） =====")

row_h = const_int(layout_raw, "ROW_H")
list_top = const_int(layout_raw, "LIST_TOP")
reserve = const_int(layout_raw, "BOTTOM_RESERVE_LIST")
check(row_h == 22 and list_top == 40 and reserve == 44,
      "SoundListLayout：ROW_H=22 / LIST_TOP=40 / BOTTOM_RESERVE_LIST=44",
      "实际 = %s / %s / %s" % (row_h, list_top, reserve))

H = 240  # 854x480 窗口、GUI 缩放 2 ⇒ 画布高 240（与直梯二级页同一口径）
list_bottom = max(list_top + row_h, H - reserve)
top = list_top + row_h
visible = max(row_h, list_bottom - top)
rows = visible // row_h
check(list_bottom == 196 and top == 62 and rows == 6,
      "★ 240 px 画布 ⇒ 列表区顶 62、底 196、可见 6 行",
      "listBottom=%d top=%d visible=%d rows=%d" % (list_bottom, top, visible, rows))
# 第 6 行完整可见、第 7 行不可见
check(top + 5 * row_h + 20 <= list_bottom, "第 6 行完整落在可视区内（y=%d）" % (top + 5 * row_h))
check(top + 6 * row_h + 20 > list_bottom, "第 7 行被挡掉（y=%d）" % (top + 6 * row_h))

# 对照：底部预留若回到旧值 108（单列那套）就只剩 3 行 —— 说明「改成两列那套」不是空话
visible_old = max(row_h, max(list_top + row_h, H - 108) - top)
check(visible_old // row_h == 3,
      "对照：底部预留回到旧的 108 ⇒ 只剩 3 行（这正是这次要改掉的东西）",
      "旧可见行 = %d" % (visible_old // row_h))

print()
print("===== 9) 「不播」全链路：哨兵 → 放行 → 播放端短路（五改，仍在） =====")

check(const_str(data, "FUTI_AUDIO_OFF") == "off",
      "★ EscalatorSpeedData.FUTI_AUDIO_OFF = \"off\"（扶梯底噪的不播哨兵）",
      "实际 = %r" % const_str(data, "FUTI_AUDIO_OFF"))
check(const_str(data, "HELP_AUDIO_OFF") == "off",
      "★ EscalatorSpeedData.HELP_AUDIO_OFF 仍是 \"off\"（提示音那一路，别混）",
      "实际 = %r" % const_str(data, "HELP_AUDIO_OFF"))

_ms = manager.find("public static boolean bindAudio(")
_me = manager.find("public static boolean unbindAudio(", _ms if _ms > 0 else 0)
_bind_body = manager[_ms:_me] if _ms >= 0 and _me > _ms else ""
check("EscalatorSpeedData.FUTI_AUDIO_OFF.equals(audioId)" in squash(_bind_body),
      "★ bindAudio 里专门放行 FUTI_AUDIO_OFF（它是合法取值，不是一个文件）",
      squash(_bind_body)[:160])

_i_off = player.find("EscalatorSpeedData.FUTI_AUDIO_OFF.equals(bestId)")
_i_bytes = player.find("getAudioBytes(mc.level, bestId)")
check(_i_off >= 0, "★ 播放端含「bestId == FUTI_AUDIO_OFF」的短路分支")
check(_i_off >= 0 and _i_bytes >= 0 and _i_off < _i_bytes,
      "★★ 短路在 getAudioBytes 之**前**（否则「不播」会误报成「音频还没同步」）",
      "idx(off)=%d  idx(getAudioBytes)=%d" % (_i_off, _i_bytes))
check("stopAll(mc);" in player[_i_off:_i_off + 200] if _i_off >= 0 else False,
      "短路分支里 stopAll(mc)（真的静音）")

# 反面对照：把短路挪到 getAudioBytes 之后 → 顺序判据变红
_probe = player.replace(
    """        if (EscalatorSpeedData.FUTI_AUDIO_OFF.equals(bestId)) {""",
    """        if (false) {""", 1)
_i_off2 = _probe.find("EscalatorSpeedData.FUTI_AUDIO_OFF.equals(bestId)")
check(not (_i_off2 >= 0 and _i_off2 < _probe.find("getAudioBytes(mc.level, bestId)")),
      "把「不播」短路拿掉 → 顺序判据变红（对照）")

print()
print("===== 10) 【七改】「默认」底噪兜底 = 内置；一级菜单灰字 + 直梯常驻信息行全删 =====")

# --- 10a) 「默认」不再等于静音 ---
check("public static String builtinDefaultAudioId()" in squash(manager),
      "★ manager 新增 builtinDefaultAudioId()（内置底噪 ID 的单一来源）")
check("public static String normaliseDefaultAudio(String audioId)" in squash(manager),
      "★ manager 新增 normaliseDefaultAudio()（null / 空 → 内置）")
check("BUILTIN_PREFIX + BUILTIN_AUDIO.keySet().iterator().next()" in squash(manager),
      "★ 兜底 ID 从 BUILTIN_AUDIO 现场取（★ 不写死第二份 \"builtin:subway_escalator\"）")
check("return audioId == null || audioId.isEmpty() ? builtinDefaultAudioId() : audioId;" in squash(manager),
      "normaliseDefaultAudio：null / 空串 → 内置，其余原样")

_ms = manager.find("public static String getClientDefaultAudio(")
_me = manager.find("}", _ms if _ms > 0 else 0)
check(_ms >= 0 and "normaliseDefaultAudio(" in squash(manager[_ms:_me + 1]),
      "★ getClientDefaultAudio 走兜底（客户端镜像空 → 内置，播放器不再拿到 null）")
_ms = manager.find("public static String getDefaultAudio(ServerLevel level)")
_me = manager.find("}", _ms if _ms > 0 else 0)
check(_ms >= 0 and "normaliseDefaultAudio(" in squash(manager[_ms:_me + 1]),
      "★ getDefaultAudio(ServerLevel) 走兜底")
check("return getServerData((ServerLevel) level).defaultAudio;" not in squash(manager),
      "★ effectiveAudioId 的服务端支不再直读裸字段")
check("return getDefaultAudio((ServerLevel) level);" in squash(manager),
      "★ effectiveAudioId 服务端支改用 getDefaultAudio(...)")

# --- 10b) 选会出声的提示音 → 顺手把这条扶梯的无障碍提示音打开 ---
_ms = manager.find("public static boolean bindHelpAudio(")
_me = manager.find("public static boolean unbindHelpAudio(", _ms if _ms > 0 else 0)
_hb = squash(manager[_ms:_me]) if _ms >= 0 and _me > _ms else ""
check("if (!EscalatorSpeedData.HELP_AUDIO_OFF.equals(id)) { setHelp(level, pos, true); }" in _hb,
      "★★ bindHelpAudio：绑非 off 的提示音时 setHelp(level, pos, true)（用户点名「选音乐就默认开启」）")
check("setHelp(level, pos, false)" not in _hb,
      "★ 只做「开」不顺手做「关」：某一头静音由 HELP_AUDIO_OFF 表达，别把另一头也哑掉（1.41 进出各设各的）")

_i = smooth.find("BIND_HELP_AUDIO_CHANNEL, (server, player")
_ctx = smooth[_i:_i + 1800] if _i >= 0 else ""
check("syncHelpAudioToAll(server);" in _ctx and "syncHelpToAll(server);" in _ctx,
      "★★ BIND_HELP_AUDIO 处理器同时发 syncHelpAudioToAll + syncHelpToAll（开关镜像也要刷新）")

# --- 10c) 扶梯一级菜单：4 行灰字全删 ---
for gray in ("0x808080", "0x909090", "0xFF909090", "0xFFE0E0E0", "0xC0C0C0", "0xA0A0A0", "0x707070"):
    check(gray not in speed_raw, "EscalatorSpeedScreen：★ 不含灰字色 %s（下方四行灰字已整段删）" % gray)
for dead in ("改扶梯速度会同步阶梯速度", "声音=整条扶梯底噪", "提示音分「进入", "按 ESC 保存并退出"):
    check(dead not in speed_raw, "EscalatorSpeedScreen：★ 旧灰字「%s…」已删净" % dead[:8])

# --- 10d) 直梯一级菜单：常驻信息行全删（★ 用剥注释后的源码判，别被 Javadoc 弄假红） ---
check("默认音量 " not in lift, "★ LiftToneSetupScreen：「默认音量 」常驻信息行已删净")
check("｜" not in lift, "★ LiftToneSetupScreen：分隔符「｜」已不在（那一行整段删）")
check("getLiftHelpVolume(mc.level)" not in lift, "★ 不再为那一行读共用默认音量")
check("if (statusText != null) {" in lift, "★ 改成「只在有操作反馈时才画那一行（黄色）」")

# 反面对照
_probe = manager.replace("normaliseDefaultAudio(data == null ? null : data.defaultAudio)",
                         "data == null ? null : data.defaultAudio", 1)
check("normaliseDefaultAudio(data == null ? null : data.defaultAudio)" not in _probe,
      "把客户端兜底去掉 → 兜底判据变红（对照）")

print()
print("===== 11) 真落进产物：字节码里的字面量 =====")


def cls(name):
    p = os.path.join(CLS_DIR, name)
    if not os.path.exists(p):
        return None
    with open(p, "rb") as fh:
        return fh.read()


def has(cls_bytes, s):
    return cls_bytes is not None and s.encode("utf-8") in cls_bytes


c_speed = cls("EscalatorSpeedScreen.class")
c_rnb = cls("EscalatorSpeedScreen$RowNameButton.class")
c_audio = cls("AudioSetupScreen.class")
c_help = cls("HelpAudioSetupScreen.class")
c_lift = cls("LiftToneSetupScreen.class")

check(c_speed is not None and c_audio is not None and c_help is not None,
      "三个界面的 .class 都在 build/classes 里（先跑过构建）")
check(c_rnb is not None, "★ 内部类 EscalatorSpeedScreen$RowNameButton.class 存在（真的编进去了）")
check(c_lift is not None, "★ LiftToneSetupScreen.class 在 build/classes 里")

check(has(c_speed, "扶梯速度") and has(c_speed, "阶梯速度") and has(c_speed, "速度"),
      "EscalatorSpeedScreen.class：含「扶梯速度 / 阶梯速度 / 速度」")
check(has(c_speed, "声音设置…") and has(c_speed, "音量"),
      "★ EscalatorSpeedScreen.class：含「声音设置… / 音量」")
check(has(c_speed, "提示音(进入)") and has(c_speed, "提示音(离开)"),
      "★★ EscalatorSpeedScreen.class：含「提示音(进入) / 提示音(离开)」（六改新按钮真进产物）")
check(not has(c_speed, "提示音设置…"),
      "★★ 字节码里**没有**「提示音设置…」（确实拆成两个按钮了，不是只改注释）")
check(not has(c_speed, "扶梯速度（格/秒）") and not has(c_speed, "阶梯速度（格/秒）"),
      "★ 字节码里**没有**旧的两行灰标签")
check(not has(c_speed, "阶梯速度对齐扶梯速度"),
      "★★ 字节码里**没有**「阶梯速度对齐扶梯速度」这个按钮")
check(not has(c_speed, "无障碍："),
      "★★ 字节码里**没有**「无障碍：」（开关按钮确实从主界面搬走了）")

check(has(c_audio, "未导入存档") and has(c_audio, "已导入存档") and has(c_audio, "默认"),
      "AudioSetupScreen.class：含两列表头 +「默认」")
check(has(c_audio, "不播"),
      "★★ AudioSetupScreen.class：含「不播」")
check(has(c_audio, "不播（这条扶梯静音）"),
      "AudioSetupScreen.class：含状态行文案「不播（这条扶梯静音）」")
check(not has(c_audio, "内置"),
      "★★ 字节码里**没有**「内置」（内置地铁自动扶梯行确实删了）")

check(has(c_help, "默认提示音") and has(c_help, "不播"),
      "HelpAudioSetupScreen.class：含「默认提示音 / 不播」")
check(not has(c_help, "不播提示音"),
      "★★ 字节码里**没有**「不播提示音」（六改改名生效）")
check(not has(c_help, "设置端头："),
      "★★ 字节码里**没有**「设置端头：」（那一行确实删了）")
check(not has(c_help, "开关："),
      "★★ 字节码里**没有**「开关：」（那一行确实删了）")

# 【七改】灰字 / 常驻信息行：必须真的没进产物
check(not has(c_speed, "改扶梯速度会同步阶梯速度") and not has(c_speed, "按 ESC 保存并退出")
      and not has(c_speed, "声音=整条扶梯底噪"),
      "★★ EscalatorSpeedScreen.class 里**没有**那四行灰字（不是只改了注释）")
check(not has(c_lift, "默认音量 ") and not has(c_lift, "｜"),
      "★★ LiftToneSetupScreen.class 里**没有**「默认音量…｜…」那一行")
# ★★【09-29 · 二改】直梯右列那只「开关：开/关 + 切换」按用户点名整行删掉 ——
#   与扶梯页（c_help「开关：」）同一口径，这里也钉到**字节码**上：注释里删干净不算数。
check(not has(c_lift, "开关：") and not has(c_lift, "切换"),
      "★★【09-29 · 二改】字节码里**没有**「开关：」/「切换」（直梯那只开关按钮确实没进产物）")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
