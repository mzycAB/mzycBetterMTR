# -*- coding: utf-8 -*-
"""离线校验：【1.55】扶梯 / 直梯 / 屏蔽门三个界面右上角的「同步所有」按钮 + 同步弹窗。

用户点名：
- 三个界面（扶梯、直梯、屏蔽门的 UI）**右上角**各加一个「同步所有」按钮；
- 按下后弹窗，弹窗里 **3 个按钮**：「同步所有」「强制同步」「取消」；
- 「同步所有」= 把当前界面里这一项的设置同步给**同域其它项**，**不包括修改过的**；
- 「强制同步」= 连**修改过的**一起改（类似指令的 `-f`）；
- 「取消」或 ESC = 关闭弹窗、回原界面；
- **射程 = 玩家当前所在的菜单层级**：一级菜单同步整页，二级菜单只同步二级菜单内的那一项。

判据（改错了不报错，症状=「按钮没出来」/「同步把改过的也覆盖了」/「按 ESC 不回去」/「同步了不该同步的项」）：
1. 五个界面文件各**恰好一处** `SyncPopupScreen.syncButton(...)`，且 host/域/射程/身份/落地回调五项**逐个正确**
   —— 域写错 = 同步打到别的系统上；射程写错 = 二级菜单同步了一整页。
2. 入口按钮定位在**右上角**（x 从 `host.width` 往左推，y = 6），不是随便放中间。
3. 弹窗（`SyncPopupScreen`）**正好 3 个按钮**，文案与回调一一对应：
   「同步所有」-> `send(false)`、「强制同步」-> `send(true)`、「取消」-> `onClose()`。
   ★ 「取消」绝不能发包 —— 那是「按取消却改了设置」。
4. 弹窗**只发一条 C2S 包**，写入序 = `utf(16) · varInt · boolean · long`；
   服务端 `SYNC_SETTINGS_CHANNEL` 的读序必须**逐格同序同类型**（写读错位 = 服务端读出垃圾值，
   最坏情况是 `scope` 读成 force 的 0/1 ⇒ 静默同步错射程）。
5. ESC 关闭：`onClose()` 必须 `setScreen(returnTo)`；且**不得**覆盖 `keyPressed` / `shouldCloseOnEsc`
   （覆盖了就把 Screen 默认的「ESC = onClose」路径掐断）。
6. ★★ **语义核心**：`if (force) {...} else {...}` 的 **else 支（=「同步所有」）里绝不许调用
   任何 `force*` / `*All` 版本 setter**。调了就等于「同步所有」把单独设置过的项也覆盖掉，
   正是用户点名要排除的行为。反向对照：往 else 支塞一个 force setter 必须变红。
7. 射程编号三方一致：
   扶梯 `SYNC_TOP_LEVEL=0` / `SYNC_ESC_AUDIO=1` / `SYNC_ESC_HELP_AUDIO=2`；
   直梯 `SYNC_LIFT_WHICH` 与界面 `PAGES` **同序同内容**；拦 `SYNC_PSD_WHICH` 与界面 `PAGES` 同序；
   `SYNC_PSD_MIDIUM_PAGE=3` / `SYNC_PSD_ARRIVE_PAGE=4` 与界面 `page == 3 / 4` 分支对上。
   对不上的症状是「二级菜单点了同步，改的是另一个二级项」。
8. 弹窗里**不写说明小字**（与 1.54「预设选择」同一套口味）：`drawString == 0`、`drawCenteredString == 1`。
9. 同步结果回执**不带括号**（用户点名的规范）。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
PKG = os.path.join(SRC, "client", "java", "smooth", "lift", "client")
SL = os.path.join(SRC, "main", "java", "smooth", "lift", "SmoothLift.java")
POPUP = os.path.join(PKG, "SyncPopupScreen.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def squash(text):
    return " ".join(text.split())


def split_args(s):
    """按**顶层**逗号切参（跳过字符串与括号内部），用于解析 syncButton(...) 的五个实参。"""
    args, depth, cur, i, in_str = [], 0, "", 0, None
    while i < len(s):
        ch = s[i]
        if in_str:
            cur += ch
            if ch == "\\" and i + 1 < len(s):
                cur += s[i + 1]
                i += 2
                continue
            if ch == in_str:
                in_str = None
        elif ch in "\"'":
            in_str = ch
            cur += ch
        elif ch in "([{":
            depth += 1
            cur += ch
        elif ch in ")]}":
            depth -= 1
            cur += ch
        elif ch == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += ch
        i += 1
    if cur.strip():
        args.append(cur.strip())
    return args


def brace_block(text, open_idx):
    """`open_idx` 指向 `{`，返回 (花括号内文本, 闭合花括号之后的下标)。"""
    depth, i = 0, open_idx
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i], i + 1
        i += 1
    return text[open_idx + 1:], len(text)


def paren_inner(text, start):
    """从 `start` 起找第一个 `(`，返回与它配对的那个 `)` 之间的文本。
    ★ 必须按深度配对：`syncButton(a, b, ..., this::applyChanges));` 里尾部有两个 `)`，
    用非贪婪正则抠会把其中一个留在实参末尾，导致「落地回调」永远比对不上。"""
    i = text.find("(", start)
    if i < 0:
        return None
    depth, j, in_str = 0, i, None
    while j < len(text):
        ch = text[j]
        if in_str:
            if ch == "\\":
                j += 2
                continue
            if ch == in_str:
                in_str = None
        elif ch in "\"'":
            in_str = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
        j += 1
    return None


def iter_calls(text, fname):
    """按出现顺序产出 `fname(...)` 的括号内文本。"""
    pos = 0
    while True:
        i = text.find(fname + "(", pos)
        if i < 0:
            return
        inner = paren_inner(text, i + len(fname))
        if inner is None:
            return
        yield inner
        pos = i + len(fname) + 1


def method_body(src, sig_regex):
    m = re.search(sig_regex + r"\(.*?\)\s*\{(.*?)\n    \}", src, flags=re.S)
    return m.group(1) if m else None


def calls(text, name):
    """函数名后面紧跟 `(` 才算「调用了它」——
    ★ 必须这样判：`setDefaultPsdToneAudio` 是 `setDefaultPsdToneAudioAll` 的前缀，
    单纯 `in` 判定会把 force 版误认成普通版。"""
    return re.search(re.escape(name) + r"\s*\(", text) is not None


def force_else_pairs(body):
    """抠出方法体里所有 `if (force) {A} else {B}`，返回 [(A, B), ...]。"""
    pairs = []
    for m in re.finditer(r"if\s*\(\s*force\s*\)", body):
        ob = body.find("{", m.end())
        if ob < 0:
            continue
        a, end = brace_block(body, ob)
        rest = body[end:]
        m2 = re.match(r"\s*else\s*", rest)
        if m2 is None:
            continue
        ob2 = rest.find("{", m2.end())
        if ob2 < 0:
            continue
        b, _ = brace_block(rest, ob2)
        pairs.append((a, b))
    return pairs


sl = open(SL, encoding="utf-8").read()
sl_no = strip_comments(sl)
popup = open(POPUP, encoding="utf-8").read()
popup_no = strip_comments(popup)

SCREENS = {
    "扶梯一级（扶梯设置）": ("EscalatorSpeedScreen.java", "esc", "SmoothLift.SYNC_TOP_LEVEL"),
    "扶梯二级（选择扶梯音乐）": ("AudioSetupScreen.java", "esc", "SmoothLift.SYNC_ESC_AUDIO"),
    "扶梯二级（选择无障碍提示音）": ("HelpAudioSetupScreen.java", "esc", "SmoothLift.SYNC_ESC_HELP_AUDIO"),
    "直梯（一级菜单 + 四项列表页）": ("LiftToneSetupScreen.java", "lift", "page"),
    "屏蔽门（主界面 + 四个二级页）": ("PsdToneSetupScreen.java", "psd", "page"),
}
SRC_TEXT = {}
SRC_NO = {}
for label, (fname, _d, _s) in SCREENS.items():
    raw = open(os.path.join(PKG, fname), encoding="utf-8").read()
    SRC_TEXT[label] = raw
    SRC_NO[label] = strip_comments(raw)

# ======================================================================
# 1) 五个界面各恰好一处入口按钮，五项实参逐个正确
# ======================================================================
print()
print("===== 1) 五个界面右上角各一处「同步所有」入口 =====")

CALL_NAME = "SyncPopupScreen.syncButton"

# 期望的 (域, 射程表达式, 身份表达式, 落地回调)
EXPECT_ARGS = {
    "扶梯一级（扶梯设置）": ("esc", "SmoothLift.SYNC_TOP_LEVEL", "pos.asLong()", "this::applyChanges"),
    "扶梯二级（选择扶梯音乐）": ("esc", "SmoothLift.SYNC_ESC_AUDIO", "pos.asLong()", "null"),
    "扶梯二级（选择无障碍提示音）": ("esc", "SmoothLift.SYNC_ESC_HELP_AUDIO", "pos.asLong()", "null"),
    "直梯（一级菜单 + 四项列表页）": ("lift", "page", "key",
                                     "page > 0 ? null : this::applyMainVolumes"),
    "屏蔽门（主界面 + 四个二级页）": ("psd", "page", "runKey", "this::applyMainInputs"),
}

for label, (fname, _domain, _scope) in SCREENS.items():
    found = list(iter_calls(SRC_NO[label], CALL_NAME))
    check(len(found) == 1, "%s：恰好一处 syncButton 调用" % label, "实际 %d 处" % len(found))
    if len(found) != 1:
        continue
    args = [squash(a) for a in split_args(found[0])]
    check(len(args) == 5, "%s：syncButton 五个实参齐全" % label, "实际 = %s" % args)
    if len(args) != 5:
        continue
    check(args[0] == "this", "%s：host = this（弹窗关掉后回到本界面）" % label, args[0])
    ed, es, ek, eb = EXPECT_ARGS[label]
    check(args[1] == '"%s"' % ed, "%s：域 = %s" % (label, ed), args[1])
    check(args[2] == es, "%s：射程 = %s" % (label, es), args[2])
    check(args[3] == ek, "%s：身份 = %s" % (label, ek), args[3])
    check(args[4] == eb, "%s：落地回调 = %s" % (label, eb), args[4])

# 域字面量只能是这三个（写错域 = 同步打到别的系统上）
doms = set()
for l in SCREENS:
    found = list(iter_calls(SRC_NO[l], CALL_NAME))
    if len(found) == 1:
        doms.add(squash(split_args(found[0])[1]).strip('"'))
check(doms == {"esc", "lift", "psd"}, "用到的域集合 == {esc, lift, psd}", "实际 = %s" % sorted(doms))

# ======================================================================
# 2) 入口按钮在右上角
# ======================================================================
print()
print("===== 2) 入口按钮定位在右上角 =====")

m = re.search(r"public static Button syncButton\(.*?\)\s*\{(.*?)\n    \}", popup, flags=re.S)
check(m is not None, "抠得出 syncButton() 方法体")
if m:
    fb = strip_comments(m.group(1))
    mb = re.search(r"\.bounds\((.*?)\)", fb, flags=re.S)
    check(mb is not None, "syncButton 里有 .bounds(...)")
    if mb:
        b = squash(mb.group(1))
        check(b.startswith("host.width"), "★ x 从 host.width 往左推（靠右）", b)
        check("-" in b.split(",")[0], "★ x 是「宽 − 按钮宽」（贴着右沿）", b.split(",")[0])
        # 【09-29】y 从魔数 6 抽成了**具名常量** ENTRY_Y —— 右上角现在是「打开文件夹 + 同步所有」
        #   一整排按钮，几何只能有一处定义（见 _tools/check-open-folder.py）。
        #   ★ 判据改成看**它解析出来的值**，别盯排版：常量值仍然必须是 6（贴顶）。
        _y = b.split(",")[1].strip()
        _ym = re.fullmatch(r"\d+", _y) or re.search(
            r"public static final int %s\s*=\s*(\d+)\s*;" % re.escape(_y), popup_no)
        _y_val = int(_ym.group(1)) if _ym else None
        check(_y_val == 6, "★ y = 6（贴顶；魔数或具名常量 ENTRY_Y 都行，值必须还是 6）", b)
    check("ENTRY_W" in fb, "按钮宽度用具名常量 ENTRY_W（不是魔数）")

# ======================================================================
# 3) 弹窗：正好 3 个按钮，文案与回调一一对应
# ======================================================================
print()
print("===== 3) 弹窗本体：3 个按钮 + 文案 ↔ 回调 =====")

check(os.path.exists(POPUP), "SyncPopupScreen.java 存在")
check(re.search(r"class SyncPopupScreen extends Screen", popup_no) is not None, "继承 Screen")

# ★ 只看 init()：类里还有一处 Button.builder 是「入口按钮」（在静态工厂 syncButton 里），
#   它不是弹窗内的按钮。整类数是 4，容易看错。
m = re.search(r"protected void init\(\)\s*\{(.*?)\n    \}", popup_no, flags=re.S)
check(m is not None, "抠得出 SyncPopupScreen.init()")
init_body = m.group(1) if m else ""

btns = list(iter_calls(init_body, "Button.builder"))
check(len(btns) == 3, "★ 弹窗 init() 里正好 3 个按钮", "实际 %d 个" % len(btns))
if len(btns) == 3:
    got = []
    for b in btns:
        a = split_args(b)
        ml = re.search(r'Component\.literal\("([^"]*)"\)', a[0] if a else "")
        cb = squash(a[1]) if len(a) > 1 else ""
        if cb.startswith("button ->"):
            cb = cb[len("button ->"):].strip()
        got.append((ml.group(1) if ml else squash(a[0] if a else ""), cb))
    expected = [("同步所有", "send(false)"), ("强制同步", "send(true)"), ("取消", "onClose()")]
    check(got == expected,
          "★ 三个按钮的文案与回调逐个正确（同步所有→send(false)、强制同步→send(true)、取消→onClose）",
          "实际 = %s" % got)
    check("ClientPlayNetworking" not in init_body and "send(" not in init_body.replace("send(false)", "").replace("send(true)", ""),
          "★ init() 里没有直接发包 —— 发不发由 send() 统一决定")
    check(len(got) == 3 and got[2][1] == "onClose()",
          "★「取消」的回调就是 onClose()（不发包）", got[2][1] if len(got) == 3 else "")

# 弹窗里的字面量集合：只许标题 + 三个按钮文案
literals = re.findall(r'Component\.literal\("([^"]*)"\)', popup_no)
check(sorted(set(literals)) == sorted(["同步", "同步所有", "强制同步", "取消"]),
      "★ 弹窗字面量只有 标题「同步」+ 三个按钮（没有多余说明文字）", "实际 = %s" % literals)
check(literals.count("同步所有") == 2,
      "「同步所有」出现 2 次（入口按钮 + 弹窗内按钮）", "实际 %d 次" % literals.count("同步所有"))

# ======================================================================
# 4) 发包与收包：类型逐格配对
# ======================================================================
print()
print("===== 4) 写序 / 读序逐格配对 =====")


def io_order(text, kind):
    """按出现顺序取回 `buf.KIND(...)` 的 KIND 列表。"""
    return re.findall(r"buf\.(%s)\s*\(" % kind, text)


m = re.search(r"private void send\(boolean force\)\s*\{(.*?)\n    \}", popup_no, flags=re.S)
write_order = None
check(m is not None, "抠得出 send() 方法体")
if m:
    fb = m.group(1)
    write_order = io_order(fb, r"write\w+")
    check(write_order == ["writeUtf", "writeVarInt", "writeBoolean", "writeLong"],
          "★ 写入序 = utf16 · varInt · boolean · long", "实际 = %s" % write_order)
    check("writeUtf(domain, 16)" in fb, "domain 用 writeUtf(domain, 16)（带上限）")
    check("ClientPlayNetworking.send(SmoothLift.SYNC_SETTINGS_CHANNEL, buf)" in fb,
          "走 SYNC_SETTINGS_CHANNEL")
    check("onClose()" in fb, "发完就 onClose() 回原界面")
    check(fb.count("ClientPlayNetworking.send(") == 1, "send() 只发一条包",
          "实际 %d 条" % fb.count("ClientPlayNetworking.send("))

m = re.search(r"registerGlobalReceiver\(SYNC_SETTINGS_CHANNEL(.*?)server\.execute\(",
              sl_no, flags=re.S)
read_order = None
check(m is not None, "抠得出 SYNC_SETTINGS_CHANNEL 的接收器读取段")
if m:
    seg = m.group(1)
    read_order = io_order(seg, r"read\w+")
    check(read_order == ["readUtf", "readVarInt", "readBoolean", "readLong"],
          "★ 服务端读序 = utf16 · varInt · boolean · long（与写序同序同类型）",
          "实际 = %s" % read_order)
    check("readUtf(16)" in seg, "domain 用 readUtf(16)（与写侧上限一致）")


def write_read_paired(writes, reads):
    """写序/读序能配对：逐格「writeX ↔ readX」同名。"""
    if writes is None or reads is None:
        return False
    if len(writes) != len(reads):
        return False
    return all(w.replace("write", "") == r.replace("read", "") for w, r in zip(writes, reads))


check(write_read_paired(write_order, read_order),
      "★ 写读逐格同名配对（错位即静默读出垃圾值）",
      "写 %s / 读 %s" % (write_order, read_order))

check("SYNC_SETTINGS_CHANNEL" in sl_no, "服务端声明了 SYNC_SETTINGS_CHANNEL")
check(re.search(r'public static final ResourceLocation SYNC_SETTINGS_CHANNEL\s*=\s*new ResourceLocation\("smoothlift",\s*"sync_settings"\)',
                sl) is not None,
      "频道名 = smoothlift:sync_settings")
check(re.search(r"SERVER.*?execute|server\.execute", sl_no) is not None, "接收器里 server.execute 切回主线程")
check("player.displayClientMessage(" in sl_no, "★ 同步结果回一条聊天栏消息（退出界面后还看得到）")

# ======================================================================
# 5) ESC / 取消：回原界面，且没掐断 ESC 默认路径
# ======================================================================
print()
print("===== 5) 关闭弹窗回原界面 =====")

m = re.search(r"public void onClose\(\)\s*\{(.*?)\n    \}", popup_no, flags=re.S)
check(m is not None, "抠得出 SyncPopupScreen.onClose()")
if m:
    fb = squash(m.group(1))
    check("mc.setScreen(returnTo" in fb, "★ onClose() 把界面设回 returnTo（= 打开弹窗前那个界面）", fb)
check("keyPressed" not in popup_no,
      "★ 不覆盖 keyPressed（ESC 走 Screen 默认 -> onClose() 这条路）")
check("shouldCloseOnEsc" not in popup_no,
      "★ 不覆盖 shouldCloseOnEsc（覆盖成 false 就把 ESC 关了）")
check(re.search(r"isPauseScreen\(\)\s*\{\s*return false;", popup_no) is not None,
      "isPauseScreen = false（与模组其它界面一致）")

# ======================================================================
# 6) ★★ 语义核心：「同步所有」那一支绝不调用 force* / *All 版本 setter
# ======================================================================
print()
print("===== 6) 「同步所有」(force=false) 不动单独设置过的项 =====")

FORCE_SETTERS = [
    "forceGlobalRunSpeed", "forceGlobalStepSpeed", "forceDefaultVolume",
    "forceDefaultHelpVolume", "forceDefaultHelp", "forceDefaultAudio",
    "forceDefaultHelpAudio",
    "setDefaultLiftToneAudioAll",
    "setDefaultPsdCloseWaitSecondsAll", "setDefaultPsdToneVolumeAll",
    "setDefaultPsdMidiumAll", "setDefaultPsdMidiumVolumeAll",
    "setDefaultPsdArriveAll", "setDefaultPsdArriveVolumeAll",
    "setDefaultPsdToneAudioAll",
    # 【09-28】讲述人（进站广播）维度默认：*All = 强制同步（含抹掉按串覆盖）
    "setDefaultPsdNarrateAll",
]
PLAIN_SETTERS = [
    "setGlobalRunSpeed", "setGlobalStepSpeed", "setDefaultVolume",
    "setDefaultHelpVolume", "setDefaultHelp", "setDefaultAudio",
    "setDefaultHelpAudio",
    "setDefaultLiftToneAudio",
    "setDefaultPsdCloseWaitSeconds", "setDefaultPsdToneVolume",
    "setDefaultPsdMidium", "setDefaultPsdMidiumVolume",
    "setDefaultPsdArrive", "setDefaultPsdArriveVolume",
    "setDefaultPsdToneAudio",
    # 【09-28】讲述人的非强制支（只写本维度默认）
    "setDefaultPsdNarrate",
]

METHODS = {
    "syncEscalator": r"private static String syncEscalator",
    "syncLift": r"private static String syncLift",
    "syncPsd": r"private static String syncPsd",
}
BODIES = {}
for name, sig in METHODS.items():
    body = method_body(sl_no, sig)
    check(body is not None, "抠得出 %s() 方法体" % name)
    BODIES[name] = body or ""

all_force_blocks = []
all_else_blocks = []
for name, body in BODIES.items():
    pairs = force_else_pairs(body)
    check(len(pairs) >= 1, "%s 里有 if (force) {..} else {..} 分支对" % name,
          "实际 %d 对" % len(pairs))
    all_force_blocks += [a for a, _ in pairs]
    all_else_blocks += [b for _, b in pairs]

check(len(all_else_blocks) >= 1, "至少有一处 else 支可供判定")

for name, body in BODIES.items():
    pairs = force_else_pairs(body)
    for i, (a, b) in enumerate(pairs):
        hit = [s for s in FORCE_SETTERS if calls(a, s)]
        check(len(hit) >= 1,
              "%s 第 %d 个 force 支确实调了强制 setter" % (name, i + 1),
              "实际 = %s" % hit)

leak = {}
for name, body in BODIES.items():
    pairs = force_else_pairs(body)
    for i, (a, b) in enumerate(pairs):
        hit = [s for s in FORCE_SETTERS if calls(b, s)]
        if hit:
            leak["%s#%d" % (name, i + 1)] = hit
check(not leak,
      "★★ 「同步所有」那一支（else）一处 force / *All setter 都没有 —— 所以改过的项保持不动",
      "泄漏 = %s" % leak)

plain_hits = {}
for name, body in BODIES.items():
    pairs = force_else_pairs(body)
    for i, (a, b) in enumerate(pairs):
        hit = [s for s in PLAIN_SETTERS if calls(b, s)]
        if hit:
            plain_hits["%s#%d" % (name, i + 1)] = hit
check(len(plain_hits) >= 3,
      "「同步所有」那一支确实在写**默认层**（普通 setter）",
      "实际 = %s" % plain_hits)

# ======================================================================
# 7) 射程编号三方一致
# ======================================================================
print()
print("===== 7) 射程编号：服务端常量 ↔ 界面页号 =====")


def const_int(src, name):
    m = re.search(r"static final int %s\s*=\s*(-?\d+)\s*;" % re.escape(name), src)
    return int(m.group(1)) if m else None


def find_array(src, name):
    m = re.search(r"String\[\]\s+" + re.escape(name) + r"\s*=\s*\{(.*?)\};", src, flags=re.S)
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)) if m else None


check(const_int(sl_no, "SYNC_TOP_LEVEL") == 0, "SYNC_TOP_LEVEL = 0", str(const_int(sl_no, "SYNC_TOP_LEVEL")))
check(const_int(sl_no, "SYNC_ESC_AUDIO") == 1, "SYNC_ESC_AUDIO = 1", str(const_int(sl_no, "SYNC_ESC_AUDIO")))
check(const_int(sl_no, "SYNC_ESC_HELP_AUDIO") == 2, "SYNC_ESC_HELP_AUDIO = 2",
      str(const_int(sl_no, "SYNC_ESC_HELP_AUDIO")))
check(const_int(sl_no, "SYNC_PSD_MIDIUM_PAGE") == 3, "SYNC_PSD_MIDIUM_PAGE = 3",
      str(const_int(sl_no, "SYNC_PSD_MIDIUM_PAGE")))
check(const_int(sl_no, "SYNC_PSD_ARRIVE_PAGE") == 4, "SYNC_PSD_ARRIVE_PAGE = 4",
      str(const_int(sl_no, "SYNC_PSD_ARRIVE_PAGE")))
# 【09-28】讲述人页 = 5（界面 page==5 ↔ 服务端 scope==5 ↔ SYNC_PSD_NARRATE_PAGE）
check(const_int(sl_no, "SYNC_PSD_NARRATE_PAGE") == 5, "★ SYNC_PSD_NARRATE_PAGE = 5",
      str(const_int(sl_no, "SYNC_PSD_NARRATE_PAGE")))
check(re.search(r"if \(scope == SYNC_PSD_NARRATE_PAGE\)", sl_no) is not None,
      "★ syncPsd 里有 scope == SYNC_PSD_NARRATE_PAGE 的分支（否则第 5 页点同步会落到「未知的页」）")

lift_which = find_array(sl_no, "SYNC_LIFT_WHICH")
lift_pages = find_array(SRC_TEXT["直梯（一级菜单 + 四项列表页）"], "PAGES")
check(lift_which is not None, "抠得出服务端 SYNC_LIFT_WHICH")
check(lift_which == lift_pages,
      "★ 直梯：服务端 SYNC_LIFT_WHICH 与界面 PAGES 同序同内容",
      "服务端 %s / 界面 %s" % (lift_which, lift_pages))

psd_which = find_array(sl_no, "SYNC_PSD_WHICH")
psd_pages = find_array(SRC_TEXT["屏蔽门（主界面 + 四个二级页）"], "PAGES")
check(psd_which is not None, "抠得出服务端 SYNC_PSD_WHICH")
check(psd_which == psd_pages,
      "★ 屏蔽门：服务端 SYNC_PSD_WHICH 与界面 PAGES 同序同内容",
      "服务端 %s / 界面 %s" % (psd_which, psd_pages))

# 界面侧 page -> 构建分支 必须与「3=到站 / 4=进站」对上
psd_screen = strip_comments(SRC_TEXT["屏蔽门（主界面 + 四个二级页）"])
check(re.search(r"else if \(page == 3\)\s*\{\s*buildArrivalPage\(\);", psd_screen) is not None,
      "屏蔽门界面：page == 3 -> 到站播报页")
check(re.search(r"else if \(page == 4\)\s*\{\s*buildArrivePage\(\);", psd_screen) is not None,
      "屏蔽门界面：page == 4 -> 进站报站页")
check(re.search(r"else if \(page == 5\)\s*\{\s*buildNarratePage\(\);", psd_screen) is not None,
      "★ 屏蔽门界面：page == 5 -> 讲述人页（与服务端 scope==5 对上）")

# 直梯界面：page >= 1 时取 PAGES[page-1]（与服务端 scope-1 同一套下标）
check(re.search(r"PAGES\[page - 1\]", strip_comments(SRC_TEXT["直梯（一级菜单 + 四项列表页）"])) is not None,
      "直梯界面：单项页用 PAGES[page - 1]（与服务端 whichs[scope - 1] 同下标）")
check(re.search(r"SYNC_LIFT_WHICH\[scope - 1\]", sl_no) is not None,
      "服务端：SYNC_LIFT_WHICH[scope - 1]")
check(re.search(r"SYNC_PSD_WHICH\[scope - 1\]", sl_no) is not None,
      "服务端：SYNC_PSD_WHICH[scope - 1]")

# 域分发
check(re.search(r'case "esc" ->', sl_no) is not None, 'syncSettings 分发 "esc"')
check(re.search(r'case "lift" ->', sl_no) is not None, 'syncSettings 分发 "lift"')
check(re.search(r'case "psd" ->', sl_no) is not None, 'syncSettings 分发 "psd"')

# ======================================================================
# 8) 弹窗里不写说明小字
# ======================================================================
print()
print("===== 8) 弹窗里没有说明小字 =====")

centered = re.findall(r"drawCenteredString\s*\(([^;]*)\)\s*;", popup_no, flags=re.S)
plain = re.findall(r"(?<![A-Za-z])drawString\s*\(([^;]*)\)\s*;", popup_no, flags=re.S)
check(len(centered) == 1, "★ 居中文字只有标题 1 处", "实际 %d 处" % len(centered))
check("this.title" in (centered[0] if centered else ""), "那一处画的是 this.title")
check(len(plain) == 0, "★ 一处左对齐小字都没有", "实际 %d 处 = %s" % (len(plain), plain))

# ======================================================================
# 9) 回执不带括号
# ======================================================================
print()
print("===== 9) 同步回执不带括号 =====")

sync_region = ""
for name, sig in METHODS.items():
    sync_region += (BODIES.get(name) or "")
m = method_body(sl_no, r"private static String syncSettings")
sync_region += (m or "")

# 只看「以 已同步/已强制同步/同步失败 开头」的文案
msg_literals = re.findall(r'"(已同步[^"]*|已强制同步[^"]*|同步失败[^"]*)"', sync_region)
check(len(msg_literals) >= 8, "抠得出同步回执文案（>= 8 条）", "实际 %d 条" % len(msg_literals))
bad = [s for s in msg_literals if any(c in s for c in "（）()")]
check(not bad, "★ 回执文案里没有括号（用户点名的规范）", "带括号的 = %s" % bad)
check(any("--no-op" in s for s in msg_literals) is False, "占位符没混进回执（防御性）")
# 剩下的字符串拼接段也要干净
concat_lits = re.findall(r'"([^"]*)"', sync_region)
bad2 = [s for s in concat_lits if any(c in s for c in "（）()")]
check(not bad2, "★ 同步方法体里所有字符串都没有括号", "带括号的 = %s" % bad2)

# ======================================================================
# 10) 反向对照
# ======================================================================
print()
print("===== 10) 反向对照 =====")

# 10a) 写读错位必须变红
w_probe = list(write_order or [])
if len(w_probe) >= 2:
    w_probe[1], w_probe[2] = w_probe[2], w_probe[1]
check(not write_read_paired(w_probe, read_order),
      "把写序里 varInt 与 boolean 对调 -> 配对判据当场变红（对照）")
r_probe = list(read_order or [])
if len(r_probe) >= 2:
    r_probe[3], r_probe[2] = r_probe[2], r_probe[3]
check(not write_read_paired(write_order, r_probe),
      "把读序里 boolean 与 long 对调 -> 配对判据当场变红（对照）")

# 10b) ★★ 「同步所有」那一支泄漏一个 force setter 必须变红 —— 这是本轮最要紧的对照
base_else = all_else_blocks[0] if all_else_blocks else ""
base_leak = [s for s in FORCE_SETTERS if calls(base_else, s)]
check(not base_leak, "基线：当前 else 支一个 force setter 都没有（对照基线）", "实际 = %s" % base_leak)
probe_else = "forceDefaultVolume(level, 1); " + base_else
probe_leak = [s for s in FORCE_SETTERS if calls(probe_else, s)]
check(bool(probe_leak),
      "★ 往 else 支塞一个 forceDefaultVolume -> 「不泄漏」这条判据当场变红（对照）",
      "探针命中 = %s" % probe_leak)

# 10b2) 前缀陷阱自证：`setDefaultPsdToneAudio` 是 `...AudioAll` 的前缀，calls() 必须能分开
check(calls("setDefaultPsdToneAudioAll(s, w, i);", "setDefaultPsdToneAudio") is False,
      "calls() 不把 setDefaultPsdToneAudioAll 误判成 setDefaultPsdToneAudio（前缀陷阱）")
check(calls("setDefaultPsdToneAudioAll(s, w, i);", "setDefaultPsdToneAudioAll") is True,
      "calls() 认得出 setDefaultPsdToneAudioAll 本身")

# 10c) 域写错必须变红
_lift_calls = list(iter_calls(SRC_NO["直梯（一级菜单 + 四项列表页）"], CALL_NAME))
if len(_lift_calls) == 1:
    probe = squash(split_args(_lift_calls[0])[1]).strip('"')
    check(probe == "lift", "直梯界面域字面量已是 lift（基线）", probe)
    probe_bad = {probe.replace("lift", "lifts")} | {"esc", "psd"}
    check(probe_bad != {"esc", "lift", "psd"},
          "域改成 lifts -> 域集合判据变红（对照）", "探针集合 = %s" % sorted(probe_bad))
else:
    check(False, "直梯界面 syncButton 调用唯一（对照基线不可用）", "实际 %d 处" % len(_lift_calls))

# 10d) 射程编号写错必须变红
check(lift_which == lift_pages, "直梯 which 一致（基线）")
check(lift_which is not None and list(reversed(lift_pages)) != lift_which,
      "把界面 PAGES 倒序 -> 一致判据变红（对照）",
      "倒序 = %s" % (list(reversed(lift_pages)) if lift_pages else None))
probe_swap = list(lift_pages or [])
if len(probe_swap) == 3:
    probe_swap[0], probe_swap[2] = probe_swap[2], probe_swap[0]
elif len(probe_swap) == 4:
    probe_swap[0], probe_swap[3] = probe_swap[3], probe_swap[0]
check(probe_swap != lift_which, "把 up 与末项（chime/close）对调 -> 一致判据变红（对照）",
      "探针 = %s" % probe_swap)

# 10e) 编号常量改错必须变红
check(const_int(sl_no, "SYNC_PSD_MIDIUM_PAGE") == 3, "到站页 = 3（基线）")
check(const_int(sl_no.replace("SYNC_PSD_MIDIUM_PAGE = 3", "SYNC_PSD_MIDIUM_PAGE = 5"),
                "SYNC_PSD_MIDIUM_PAGE") != 3,
      "到站页改成 5 -> 编号判据变红（对照）")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
