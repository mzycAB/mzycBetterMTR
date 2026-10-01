# -*- coding: utf-8 -*-
"""离线校验：【09-29】所有界面右上角「同步所有」左边那个「打开文件夹」按钮 + `/MBM picture fold`。

用户点名：
- **所有 UI** 右上角「同步」按钮**左边**加一个「打开文件夹」按钮，按一下弹出**音频导入文件夹**；
- 一级菜单（屏蔽门主界面）开的是「**组**」文件夹：`存档/MBM_Audio/pbm`；
- 二级页（屏蔽门「进站广播」）开的是「**分类**」文件夹：`存档/MBM_Audio/pbm/arrive`；
- 图片没有界面 ⇒ 用指令 `mbm picture fold` 弹 `存档/MBM_Picture`。

判据（改错了都不报错，症状 = 「按钮没出来」/「开的是别人的文件夹」/「开了个空目录」）：
1. 右上角**整排**按钮的几何只有一处定义（`SyncPopupScreen.ENTRY_*` 五个常量），
   `syncButton` 自己也用它；「打开文件夹」的 x 必须从 `host.width` 往左推、
   **正好落在同步按钮左边**（右边距 + 同步按钮宽 + 间距）。
2. 七个有同步按钮的界面**各挂一处** `FolderOpenButton.of(...)`（多挂 = 两个按钮叠一起，
   漏挂 = 那个界面没有入口）。
3. ★★ **一页开的目录 == 那一页列表读的目录**：分类与上级目录**只有一份**映射
   （`categoryForPage` / `categoryFor` / `PAGE_CATEGORIES`），按钮复用它、不另抄一份表。
   反向：把 `folderCategoryForPage` 改成直接返回 `categoryForPage`（少一层组目录回落）、
   或把某一页的分类常量换成隔壁的，必须当场变红。
4. 分类值（`pbm/arrive`…）的**上级目录**必须恰好是五个组名之一，且组名常量
   `GROUP_FUTI/TRAIN/PSD/LIFT/ZHAJI` 与之一致（组名只是目录、不是分类）。
5. 路径白名单 `SAFE_FOLDER` 必须是**整串匹配**（`.matches()`）的
   `MBM_(Audio|Picture)(/[a-z]+)*`：`..`、绝对路径、盘符、反斜杠、大写子目录全部进不来。
   （`/MBM picture fold` 的相对路径是**走网络**进来的，客户端不能无条件信任。）
6. 通道 `MBM_OPEN_FOLDER_CHANNEL`：服务端 `writeUtf(path, 64)` 一处、客户端 `readUtf(64)` 一处，
   写读**逐格同序同类型**；客户端拿到后必须落到**同一个** `FolderOpenButton.open`。
7. 指令树：`/MBM picture fold` 是**字面量**（Brigadier 先试字面量再试 `name` 参数，
   否则会被当成「切换到一张叫 fold 的图片」——不报错、只是干错事），
   节点落在 `picture` 那一支里、指向 `dtPictureOpenFolder`；无玩家时失败退出。
8. 「开文件夹」只有一条实现：`Util.getPlatform().openFile` 只许在 `FolderOpenButton` 里出现。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
PKG = os.path.join(SRC, "client", "java", "smooth", "lift", "client")
MAIN = os.path.join(SRC, "main", "java", "smooth", "lift")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
SL = os.path.join(MAIN, "SmoothLift.java")
SLC = os.path.join(PKG, "SmoothLiftClient.java")
POPUP = os.path.join(PKG, "SyncPopupScreen.java")
FOLDER = os.path.join(PKG, "FolderOpenButton.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def squash(text):
    return " ".join(text.split())


def tight(text):
    """去掉所有空白 —— 用来对**跨行折行**的实参做字形比对（比 squash 更抗排版）。

    ★ 两边都要 tight()：只 tight 正文、不 tight 期望串，就会栽在「期望串自己带空格」上。
    """
    return re.sub(r"\s+", "", text)


def count(text, needle):
    return text.count(needle)


# ----------------------------------------------------------------------
print("===== 1) 右上角整排按钮的几何只有一处定义 =====")

popup_no = strip_comments(read(POPUP))
folder_raw = read(FOLDER)
folder_no = strip_comments(folder_raw)
popup_t = tight(popup_no)
folder_t = tight(folder_no)

ENTRY_CONSTS = {"ENTRY_W": 76, "ENTRY_MARGIN": 4, "ENTRY_GAP": 4, "ENTRY_Y": 6, "ENTRY_H": 20}
for name, want in ENTRY_CONSTS.items():
    m = re.search(r"public static final int %s\s*=\s*(\d+)\s*;" % name, popup_no)
    check(m is not None and int(m.group(1)) == want,
          "%s 是 public 常量且 = %d" % (name, want),
          "实际 = %s" % (m.group(1) if m else "(没找到)"))

SYNC_BOUNDS = tight(".bounds(host.width - ENTRY_MARGIN - ENTRY_W, ENTRY_Y, ENTRY_W, ENTRY_H)")
check(SYNC_BOUNDS in popup_t,
      "★ syncButton 自己走那一套常量（不再有 -4 / 6 / 20 的魔数）")

# 「打开文件夹」在同步按钮**左边**：右边距 + 同步按钮宽 + 间距，再减自己的宽
FOLDER_X = tight("host.width - SyncPopupScreen.ENTRY_MARGIN - SyncPopupScreen.ENTRY_W"
                 " - SyncPopupScreen.ENTRY_GAP - W")
_m = re.search(r"\.bounds\((.*?), SyncPopupScreen\.ENTRY_Y", folder_t)
check(FOLDER_X in folder_t,
      "★★ 「打开文件夹」的 x = width − 右边距 − 同步按钮宽 − 间距 − 自己宽 ⇒ 正好在同步按钮左边",
      "实际式子 = %s" % (_m.group(1) if _m else "(没找到)"))
check(tight("public static final int W = SyncPopupScreen.ENTRY_W;") in folder_t,
      "按钮宽度复用同步按钮那个常量（不是另写一个魔数）")
check('Component.literal("打开文件夹")' in folder_no, "按钮文字就是「打开文件夹」")


# ----------------------------------------------------------------------
print()
print("===== 2) 七个有同步按钮的界面各挂一处「打开文件夹」 =====")

SCREENS = {
    "扶梯（一级菜单）": "EscalatorSpeedScreen.java",
    "扶梯（运行底噪页）": "AudioSetupScreen.java",
    "扶梯（无障碍提示音页）": "HelpAudioSetupScreen.java",
    "直梯（一级菜单 + 四项列表页）": "LiftToneSetupScreen.java",
    "列车音效（一级页 + 五项列表页）": "TrainSoundScreen.java",
    "屏蔽门（主界面 + 五个列表页）": "PsdToneSetupScreen.java",
    "闸机（一级菜单 + 两向列表页）": "ZhajiToneSetupScreen.java",
}
SCREEN_SRC = {}
for label, fn in SCREENS.items():
    SCREEN_SRC[label] = strip_comments(read(os.path.join(PKG, fn)))
    n = count(SCREEN_SRC[label], "FolderOpenButton.of(")
    check(n == 1, "%s：恰好一处 FolderOpenButton.of(...)" % label, "实际 %d 处" % n)

for label in SCREENS:
    n = count(SCREEN_SRC[label], "SyncPopupScreen.syncButton(")
    check(n == 1,
          "%s：恰好一处 syncButton（「打开文件夹」就是挂在它左边的）" % label,
          "实际 %d 处" % n)


# ----------------------------------------------------------------------
print()
print("===== 3) ★★ 一页开的目录 == 那一页列表读的目录（分类映射只有一份）=====")

EXPECTS = {
    "扶梯（一级菜单）": "FolderOpenButton.audioPath(EscalatorSpeedManager.GROUP_FUTI)",
    "扶梯（运行底噪页）": "FolderOpenButton.audioPath(EscalatorSpeedManager.CAT_FUTI)",
    "扶梯（无障碍提示音页）": "FolderOpenButton.audioPath(EscalatorSpeedManager.CAT_HELP)",
    "直梯（一级菜单 + 四项列表页）":
        "FolderOpenButton.audioPath(page>0?categoryFor(PAGES[page-1]):EscalatorSpeedManager.GROUP_LIFT)",
    "列车音效（一级页 + 五项列表页）":
        "FolderOpenButton.audioPath(page>0?PAGE_CATEGORIES[page-1]:EscalatorSpeedManager.GROUP_TRAIN)",
    "屏蔽门（主界面 + 五个列表页）": "FolderOpenButton.audioPath(folderCategoryForPage(page))",
    "闸机（一级菜单 + 两向列表页）":
        "FolderOpenButton.audioPath(page>0?categoryFor(PAGES[page-1]):EscalatorSpeedManager.GROUP_ZHAJI)",
}
for label, want in EXPECTS.items():
    check(want in tight(SCREEN_SRC[label]),
          "%s：按钮开的那一层目录逐字正确" % label,
          "期望 %s" % want)

psd_t = tight(SCREEN_SRC["屏蔽门（主界面 + 五个列表页）"])
check(tight("String folderCategoryForPage(int page) { String category = categoryForPage(page); "
            "return category == null ? EscalatorSpeedManager.GROUP_PSD : category; }") in psd_t,
      "★ 屏蔽门：folderCategoryForPage 就是 categoryForPage + 一层组目录回落（没有另抄一张表）")
check("Stringcategory=categoryForPage(page);" in psd_t,
      "★ 屏蔽门的「列表读哪个分类」与「按钮开哪个分类」共用 categoryForPage")

lift_t = tight(SCREEN_SRC["直梯（一级菜单 + 四项列表页）"])
for which, cat in (("up", "CAT_LIFT_UP"), ("down", "CAT_LIFT_DOWN"),
                   ("open", "CAT_LIFT_OPEN"), ("close", "CAT_LIFT_CLOSE")):
    check('case"%s"->EscalatorSpeedManager.%s;' % (which, cat) in lift_t,
          "★ 直梯：categoryFor(%s) → %s（按钮与列表同一份映射）" % (which, cat))

# 【09-30】闸机那一页不另抄一张分类表：categoryFor 直接转 Manager 的 zhajiToneCategory（方向 → 分类）。
zhaji_t = tight(SCREEN_SRC["闸机（一级菜单 + 两向列表页）"])
check(tight("private static String categoryFor(String which) { "
            "return EscalatorSpeedManager.zhajiToneCategory(which); }") in zhaji_t,
      "★ 闸机：categoryFor 就是转 Manager 的 zhajiToneCategory（没有另抄一张表）")
check(tight('String[] PAGES = {"in", "out"};') in zhaji_t,
      "★ 闸机两个方向 in/out 同序（二级页号 = 下标 + 1，与服务端 SYNC_ZHAJI_WHICH 同序）")
check(count(strip_comments(read(MGR)), "public static String zhajiToneCategory(") == 1,
      "★ 方向 → 分类的映射在 Manager 里只定义一处（zhajiToneCategory）")


# ----------------------------------------------------------------------
print()
print("===== 4) 组名常量与分类值的上级目录对得上 =====")

mgr_no = strip_comments(read(MGR))
GROUPS = {"GROUP_FUTI": "futi", "GROUP_TRAIN": "train", "GROUP_PSD": "pbm", "GROUP_LIFT": "lift",
          "GROUP_ZHAJI": "zhaji"}
for name, want in GROUPS.items():
    m = re.search(r'public static final String %s\s*=\s*"([^"]*)"\s*;' % name, mgr_no)
    check(m is not None and m.group(1) == want,
          "%s = \"%s\"" % (name, want), "实际 = %s" % (m.group(1) if m else "(没找到)"))

_cat_vals = dict(re.findall(r'public static final String (CAT_[A-Z_]+)\s*=\s*"([^"]*)"', mgr_no))
_parents = sorted(set(v.split("/")[0] for v in _cat_vals.values()))
check(len(_cat_vals) == 17, "17 个 CAT_* 分类常量都在", "实际 %d" % len(_cat_vals))
check(_parents == sorted(GROUPS.values()),
      "★ 每个分类值的上级目录恰好是五个组名之一（组只是目录、不是分类）",
      "实际上级 = %s" % _parents)
check(all(v not in GROUPS.values() for v in _cat_vals.values()),
      "★ 没有哪个分类值恰好等于组名（否则「组目录」里会被当成要放 ogg）",
      "分类值 = %s" % sorted(_cat_vals.values()))


# ----------------------------------------------------------------------
print()
print("===== 5) 路径白名单：整串匹配，挡越界 =====")

check("SAFE_FOLDER.matcher(relativePath).matches()" in folder_t,
      "★ 白名单用 .matches()（整串匹配），不是 .find()")
m = re.search(r'Pattern\.compile\("([^"]*)"\)', folder_no)
JAVA_PAT = m.group(1) if m else None
check(JAVA_PAT == "MBM_(Audio|Picture)(/[a-z]+)*",
      "白名单正则字形正确", "实际 = %s" % JAVA_PAT)

if JAVA_PAT is not None:
    rx = re.compile("^(?:" + JAVA_PAT + ")$")
    SAMPLES = [
        ("MBM_Audio", True),
        ("MBM_Picture", True),
        ("MBM_Audio/pbm", True),
        ("MBM_Audio/pbm/arrive", True),
        ("MBM_Audio/futi/music", True),
        ("MBM_Audio/lift/close", True),
        ("MBM_Audio/zhaji", True),
        ("MBM_Audio/zhaji/in", True),
        ("MBM_Audio/zhaji/out", True),
        ("", False),
        ("../MBM_Audio", False),
        ("MBM_Picture/..", False),
        ("MBM_Audio/../pbm", False),
        ("MBM_Audio/../../etc", False),
        ("C:/MBM_Audio", False),
        ("MBM_Audio\\pbm", False),
        ("MBM_Audio/pbm/Arrive", False),
        ("MBM_Picturex", False),
        ("MBM_Something", False),
        ("MBM_Audio/pbm/arrive/", False),
    ]
    bad = [(s, ok) for s, ok in SAMPLES if bool(rx.match(s)) != ok]
    check(not bad, "★★ %d 个样本全部判对（合法放行、越界全挡）" % len(SAMPLES),
          "判错 = %s" % bad)


# ----------------------------------------------------------------------
print()
print("===== 6) 通道：服务端写 / 客户端读，逐格配对 =====")

sl_no = strip_comments(read(SL))
slc_no = strip_comments(read(SLC))

check('public static final ResourceLocation MBM_OPEN_FOLDER_CHANNEL =' in squash(sl_no)
      and '"mbm_open_folder"' in sl_no,
      "MBM_OPEN_FOLDER_CHANNEL 定义在 SmoothLift（id = smoothlift:mbm_open_folder）")

check(count(sl_no, "ServerPlayNetworking.send(player, MBM_OPEN_FOLDER_CHANNEL, buf);") == 1,
      "服务端恰好一处发这只包（dtPictureOpenFolder）")
check(count(sl_no, "buf.writeUtf(EscalatorSpeedManager.PICTURE_FOLDER, 64);") == 1,
      "服务端写的是**相对**路径（PICTURE_FOLDER，utf64）—— 不发绝对路径给客户端")

_i_recv = slc_no.find("SmoothLift.MBM_OPEN_FOLDER_CHANNEL")
check(_i_recv >= 0, "客户端注册了这只包的接收器")
_recv = slc_no[_i_recv:_i_recv + 700] if _i_recv >= 0 else ""
check("String relativePath = buf.readUtf(64);" in squash(_recv),
      "客户端按 utf64 读回（与服务端 writeUtf(x, 64) 配对）", "接收器片段 = %s" % squash(_recv)[:120])
check("FolderOpenButton.open(relativePath)" in tight(_recv),
      "★ 客户端读到的路径落到**同一个** FolderOpenButton.open（没有第二套开文件夹实现）")

# 写读逐格配对：**这一只包**里各只有一处 utf，且长度都是 64
_w = re.findall(r"writeUtf\(([^,]+),\s*\d+\)", sl_no)
_r = re.findall(r"readUtf\((\d+)\)", _recv)
check(len(_w) == 1 and len(_r) == 1 and _r[0] == "64",
      "★ 通道只有一个 utf 字段、长度 64 两边一致（写读错位/截断会静默变空串）",
      "写 = %s、读 = %s" % (_w, _r))


# ----------------------------------------------------------------------
print()
print("===== 7) 指令树：/MBM picture fold 是字面量、落在 picture 那一支 =====")

check(count(sl_no, 'Commands.literal("fold")') == 1, "`fold` 字面量恰好一处")
check(tight('Commands.literal("fold").executes(SmoothLift::dtPictureOpenFolder)') in tight(sl_no),
      "`fold` 指向 dtPictureOpenFolder")
check(count(sl_no, "private static int dtPictureOpenFolder(") == 1, "dtPictureOpenFolder 定义一处")

_i_pic = sl_no.find('Commands.literal("picture")')
_i_end = sl_no.find("SmoothLift::dtPictureDeleteOne")
_i_fold = sl_no.find('Commands.literal("fold")')
check(_i_pic >= 0 and _i_end > _i_pic and _i_pic < _i_fold < _i_end,
      "★ `fold` 节点在 picture 那一支内部（不在别处、也不在根上）",
      "picture=%d fold=%d picture支结束=%d" % (_i_pic, _i_fold, _i_end))

# 没有玩家（控制台 / 命令方块）必须失败：文件夹要开在人自己的电脑上
_i_m = sl_no.find("private static int dtPictureOpenFolder(")
_i_next = sl_no.find("private static int dtPictureQuery(", _i_m)
_body = squash(sl_no[_i_m:(_i_next if _i_next > _i_m else _i_m + 1500)])
check("if (player == null)" in _body and "return 0;" in _body,
      "★ 无玩家时失败退出（不会给不存在的玩家发包）")
check("ServerPlayNetworking.send(player, MBM_OPEN_FOLDER_CHANNEL, buf);" in _body,
      "★ 发包在「有玩家」这一支之后（顺序对了才谈得上安全）")


# ----------------------------------------------------------------------
print()
print("===== 8) 开文件夹只有一条实现 =====")

_openfile = []
for dirpath, _dirnames, filenames in os.walk(SRC):
    for fn in filenames:
        if not fn.endswith(".java"):
            continue
        p = os.path.join(dirpath, fn)
        if "getPlatform().openFile" in strip_comments(read(p)):
            _openfile.append(os.path.relpath(p, ROOT).replace("\\", "/"))
check(_openfile == ["src/client/java/smooth/lift/client/FolderOpenButton.java"],
      "★ Util.getPlatform().openFile 只出现在 FolderOpenButton 一个文件里",
      "实际 = %s" % _openfile)
check("Files.createDirectories(dir)" in folder_no,
      "★ 开之前先建目录（不然「打开」指的是一个不存在的目录）")


# ----------------------------------------------------------------------
print()
print("===== 9) 反向对照（mutation 必须变红）=====")

# 9a) x 式子少了间距 ⇒ 两个按钮叠在一起
_mut_t = folder_t.replace(FOLDER_X, tight("host.width - SyncPopupScreen.ENTRY_W - W"))
check(FOLDER_X not in _mut_t,
      "★ 去掉右边距与间距 -> 「在同步按钮左边」这条判据当场变红（对照）")

# 9b) 屏蔽门不再回落到组目录（主界面/讲述人页会开到一个不存在的分类）
_mut = psd_t.replace("returncategory==null?EscalatorSpeedManager.GROUP_PSD:category;",
                     "returncategory;")
check("returncategory==null?EscalatorSpeedManager.GROUP_PSD:category;" not in _mut,
      "★ folderCategoryForPage 少一层组目录回落 -> 同源判据变红（对照）")

# 9c) 白名单放宽成「MBM_ 开头就行」
_loose = "MBM_(Audio|Picture)(/.*)"
_mut = folder_no.replace(JAVA_PAT or "___", _loose)
check(JAVA_PAT != _loose and 'Pattern.compile("%s")' % _loose in _mut,
      "★ 白名单放宽成 (/.*) -> 正则字形判据变红（对照）")
if JAVA_PAT is not None:
    rx_loose = re.compile("^(?:" + _loose + ")$")
    check(bool(rx_loose.match("MBM_Audio/../../etc")) is True
          and bool(re.compile("^(?:" + JAVA_PAT + ")$").match("MBM_Audio/../../etc")) is False,
          "★ 放宽后的正则确实放行了 MBM_Audio/../../etc（证明样本判据真的会变红）")

# 9d) 把「无障碍提示音」页的分类换成隔壁的分类
_mut = SCREEN_SRC["扶梯（无障碍提示音页）"].replace(
    "FolderOpenButton.audioPath(EscalatorSpeedManager.CAT_HELP)",
    "FolderOpenButton.audioPath(EscalatorSpeedManager.CAT_FUTI)")
check("FolderOpenButton.audioPath(EscalatorSpeedManager.CAT_HELP)" not in _mut,
      "★ 把 help 页换成 futi/music 的分类 -> 逐界面实参判据变红（对照）")

# 9e) .matches() 改成 .find() ⇒ 「MBM_Audio/../../etc」会被放行
_mm = "SAFE_FOLDER.matcher(relativePath).matches()"
_mut = folder_no.replace(_mm, "SAFE_FOLDER.matcher(relativePath).find()")
check(_mm not in tight(_mut),
      "★ .matches() 改成 .find() -> 整串匹配判据变红（对照）")

# 9f) 直梯一级菜单也去开子目录（page >= 0）
_ml = "FolderOpenButton.audioPath(page>0?categoryFor(PAGES[page-1]):EscalatorSpeedManager.GROUP_LIFT)"
_mut = lift_t.replace(_ml, "FolderOpenButton.audioPath(categoryFor(PAGES[page]))")
check(_ml not in _mut,
      "★ 直梯一级菜单也去开子目录 -> 逐界面实参判据变红（对照）")


# ----------------------------------------------------------------------
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 【09-29】打开文件夹：全部通过 ==")
