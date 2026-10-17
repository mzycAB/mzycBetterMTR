# -*- coding: utf-8 -*-
"""Script: port_repair_patch.py

把【补丁】那一轮在 `1.20.4` 工程上做的改动**原样移植**到另外 4 个工程。

★ 设计要点：**1.20.4 是唯一真值** —— 本脚本从它的文件里**现场抽块**（不手抄一行），
  再按各处已核过存在的锚点注入目标工程。这样「同一份改动写两遍」导致的分叉不可能发生。

各处锚点都已逐工程 grep 核过存在（`play()` 等整段方法在 5 个工程里逐字节相同）。

覆盖：
  · 新增两个文件：RepairCrypto.java / RepairPatch.java（纯客户端）
  · FolderOpenButton：白名单加 MBM_Repair
  · EscalatorAudioPlayer：bundledBytes 补丁优先 + patched() + clearBundledMetrics()
  · LiftChimePlayer：WITHDRAWN_EVENTS/withdrawnLogged 两个字段 + play() 整段（补丁分支 +
    静音分支 + playInstance/raiseMaxGain/builtinKeyOf）
  · PsdChimePlayer：整段起播也认补丁
  · MbmHelpScreen：右上角两只按钮 + rightRowX()

用法：`python _tools/port_repair_patch.py`（幂等：已经注入过的工程会跳过并报「已存在」）
"""

import os
import shutil
import sys

WORKSPACE = r"C:/Users/user/Desktop/3"
REF = os.path.join(WORKSPACE, "1.20.4", "mzycBetterMTR-1.20.4")
REF_CLIENT = os.path.join(REF, "src", "client", "java", "smooth", "lift", "client")

# 目标工程 → 客户端源码目录（Fabric 是独立源集；Forge 全在 src/main/java 下）
TARGETS = {
    "1.20.1/mzycBetterMTR-1.20.1": "src/client/java/smooth/lift/client",
    "1.21.1/mzycBetterMTR-1.21.1": "src/client/java/smooth/lift/client",
    "Forge1.18.2/mzycBetterMTR-Forge-1.18.2": "src/main/java/smooth/lift/client",
    "Forge1.20.1/mzycBetterMTR-Forge-1.20.1": "src/main/java/smooth/lift/client",
}

NEW_FILES = ["RepairCrypto.java", "RepairPatch.java"]

FAILS = []


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def span(text, start_marker, end_marker="\n    }\n", include_end=True):
    """从 `start_marker` 抽到 `end_marker` 结尾（含）。"""
    i = text.find(start_marker)
    if i < 0:
        raise SystemExit("抽块失败：找不到起点 %r" % start_marker[:60])
    j = text.find(end_marker, i)
    if j < 0:
        raise SystemExit("抽块失败：找不到终点 %r" % end_marker[:40])
    return text[i:j + (len(end_marker) if include_end else 0)]


# ======================================================================
# 从 1.20.4 现场抽块（唯一真值）
# ======================================================================
ref_audio = read(os.path.join(REF_CLIENT, "EscalatorAudioPlayer.java"))
ref_lift = read(os.path.join(REF_CLIENT, "LiftChimePlayer.java"))
ref_psd = read(os.path.join(REF_CLIENT, "PsdChimePlayer.java"))
ref_screen = read(os.path.join(REF_CLIENT, "MbmHelpScreen.java"))

# --- EscalatorAudioPlayer ---
AUDIO_PATCH_FIRST = span(ref_audio, "        byte[] patch = RepairPatch.bytes(builtinKey);",
                         "        try (InputStream in = EscalatorAudioPlayer.class", include_end=False)
AUDIO_NEW_METHODS = span(ref_audio,
                         "    /**\n     * 【补丁】有没有**特意**用补丁字节覆盖某段内置素材。",
                         "    static void clearBundledMetrics() {", include_end=False)
# 手工收到 clearBundledMetrics 的结尾（span 的 end_marker 只匹配 `    }`，这里方法体里没有嵌套的 4 空格闭合）
_k = ref_audio.find("    static void clearBundledMetrics() {")
_j = ref_audio.find("\n    }\n", _k)
AUDIO_NEW_METHODS = ref_audio[ref_audio.find(
    "    /**\n     * 【补丁】有没有**特意**用补丁字节覆盖某段内置素材。"): _j + len("\n    }\n")]

# --- LiftChimePlayer ---
LIFT_FIELDS = span(ref_lift,
                   "    /**\n     * 【补丁】这版**故意移除**了两个内置事件",
                   "private static final Set<ResourceLocation> withdrawnLogged = new HashSet<>();",
                   include_end=False)
LIFT_FIELDS = ref_lift[ref_lift.find("    /**\n     * 【补丁】这版**故意移除**了两个内置事件"):
                       ref_lift.find("private static final Set<ResourceLocation> withdrawnLogged = new HashSet<>();")
                       + len("private static final Set<ResourceLocation> withdrawnLogged = new HashSet<>();\n\n")]

LIFT_PLAY_SIG = "private static void play(Minecraft mc, ResourceLocation event, String customId, Vec3 pos,"
_i = ref_lift.find(LIFT_PLAY_SIG)
_k = ref_lift.find("    private static String builtinKeyOf(ResourceLocation event) {", _i)
_j = ref_lift.find("\n    }\n", _k)
LIFT_PLAY_NEW = ref_lift[_i:_j + len("\n    }\n")]

# --- PsdChimePlayer ---
PSD_PATCH_BRANCH = span(ref_psd,
                        "        if (playbackId == null && tone.customId() == null",
                        "        if (playbackId == null) {", include_end=False)

# --- MbmHelpScreen ---
SCREEN_BLOCK = span(ref_screen,
                    "        // 【补丁】右上角再挂两只按钮（用户点名）：",
                    "    private int rightRowX(int index) {", include_end=False)
_m = ref_screen.find("    private int rightRowX(int index) {")
_n = ref_screen.find("\n    }\n", _m)
SCREEN_BLOCK = ref_screen[ref_screen.find("        // 【补丁】右上角再挂两只按钮（用户点名）："):
                          _n + len("\n    }\n")]
# ▶ 注入点（`this::applyVolume));` 之后自带 `setInitialFocus(volumeInput);` 与 init() 的收尾 `}`）
#   已经把这两行包含在内 ⇒ 抽块后必须把它们**砍掉**，否则目标文件里会多一份、`}` 落错位置。
#   ★ 这是首轮真踩过的坑：1.20.1 上直接 39 个「需要 class/interface/enum/record」编译错误。
_tail = "\n        setInitialFocus(volumeInput);\n    }\n"
assert SCREEN_BLOCK.endswith(_tail), "SCREEN_BLOCK 结尾形状变了，请同步更新这条砍尾逻辑"
SCREEN_BLOCK = SCREEN_BLOCK[:-len(_tail)]

print("== 抽块尺寸 ==")
for name, blk in (("AUDIO_PATCH_FIRST", AUDIO_PATCH_FIRST), ("AUDIO_NEW_METHODS", AUDIO_NEW_METHODS),
                  ("LIFT_FIELDS", LIFT_FIELDS), ("LIFT_PLAY_NEW", LIFT_PLAY_NEW),
                  ("PSD_PATCH_BRANCH", PSD_PATCH_BRANCH), ("SCREEN_BLOCK", SCREEN_BLOCK)):
    print("   %-18s %5d 字符 / %d 行" % (name, len(blk), blk.count("\n")))
assert "playInstance" in LIFT_PLAY_NEW and "builtinKeyOf" in LIFT_PLAY_NEW
assert "RepairPatch.bytes" in AUDIO_PATCH_FIRST
assert "patched(String builtinKey)" in AUDIO_NEW_METHODS
assert "clearBundledMetrics" in AUDIO_NEW_METHODS
assert "TrainTiltView" not in SCREEN_BLOCK      # Forge 专属的 mtrqx 那一行不在这个块里


def apply(proj, rel):
    root = os.path.join(WORKSPACE, proj)
    cdir = os.path.join(root, rel)
    if not os.path.isdir(cdir):
        FAILS.append("%s：客户端源码目录不存在" % proj)
        return
    print("\n== %s" % proj)

    # 1) 新增文件
    for name in NEW_FILES:
        src = os.path.join(REF_CLIENT, name)
        dst = os.path.join(cdir, name)
        if os.path.exists(dst) and read(dst) == read(src):
            print("   %-22s 已存在且一致，跳过" % name)
            continue
        shutil.copyfile(src, dst)
        print("   %-22s 已写入（%d 字节）" % (name, os.path.getsize(dst)))

    # 2) FolderOpenButton 白名单
    p = os.path.join(cdir, "FolderOpenButton.java")
    s = read(p)
    old = 'Pattern.compile("MBM_(Audio|Picture)(/[a-z]+)*")'
    new = 'Pattern.compile("MBM_(Audio|Picture|Repair)(/[a-z]+)*")'
    if new in s:
        print("   FolderOpenButton         白名单已含 Repair，跳过")
    elif old in s:
        s = s.replace(old, new, 1)
        s = s.replace(
            "     * <p>只放行 {@code MBM_Audio} / {@code MBM_Picture} 这两个模组自己的来源文件夹，\n"
            "     * 以及它们下面**纯小写字母**的一层 / 两层子目录（{@code pbm/arrive}、{@code futi/music}…）。\n"
            "     * 这样 {@code ..}、绝对路径、盘符、反斜杠全部进不来。\n     *\n",
            "     * <p>只放行 {@code MBM_Audio} / {@code MBM_Picture} / {@code MBM_Repair} 这三个模组自己的\n"
            "     * 来源文件夹，以及它们下面**纯小写字母**的一层 / 两层子目录（{@code pbm/arrive}、{@code futi/music}…）。\n"
            "     * 这样 {@code ..}、绝对路径、盘符、反斜杠全部进不来。\n     *\n"
            "     * <p>★ {@code MBM_Repair}（【补丁】那一轮加的）= 补丁包 `Repair.zip` 的投放目录，\n"
            "     * 由 {@code MbmHelpScreen} 右上角的「打开补丁文件夹」打开；它是一级目录，没有子目录。\n     *\n", 1)
        write(p, s)
        print("   FolderOpenButton         白名单 + MBM_Repair")
    else:
        FAILS.append("%s：FolderOpenButton 白名单锚点没找到" % proj)

    # 3) EscalatorAudioPlayer
    p = os.path.join(cdir, "EscalatorAudioPlayer.java")
    s = read(p)
    if "RepairPatch.bytes(builtinKey)" in s:
        print("   EscalatorAudioPlayer     已含补丁逻辑，跳过")
    else:
        anchor = ("        if (builtinKey == null || builtinKey.isEmpty()) {\n"
                  "            return null;\n"
                  "        }\n")
        if anchor not in s:
            FAILS.append("%s：bundledBytes 开头锚点没找到" % proj)
        else:
            s = s.replace(anchor, anchor + AUDIO_PATCH_FIRST, 1)
        tail = ("        } catch (IOException e) {\n"
                "            LOGGER.warn(\"[SmoothLift/Audio] 读不到模组自带素材 {}.ogg：{}\", builtinKey, e.getMessage());\n"
                "            return null;\n"
                "        }\n    }\n")
        if tail not in s:
            FAILS.append("%s：bundledBytes 结尾锚点没找到" % proj)
        else:
            s = s.replace(tail, tail + "\n" + AUDIO_NEW_METHODS, 1)
        write(p, s)
        print("   EscalatorAudioPlayer     补丁优先 + patched() + clearBundledMetrics()")

    # 4) LiftChimePlayer
    p = os.path.join(cdir, "LiftChimePlayer.java")
    s = read(p)
    if "WITHDRAWN_EVENTS" in s:
        print("   LiftChimePlayer          已含补丁逻辑，跳过")
    else:
        fa = "    private static final Set<ResourceLocation> warnedMissingEvents = new HashSet<>();\n"
        if fa not in s:
            FAILS.append("%s：warnedMissingEvents 锚点没找到" % proj)
        else:
            s = s.replace(fa, fa + "\n" + LIFT_FIELDS, 1)
        i = s.find(LIFT_PLAY_SIG)
        if i < 0:
            FAILS.append("%s：play() 签名没找到" % proj)
        else:
            j = s.find("\n    }\n", i)
            s = s[:i] + LIFT_PLAY_NEW + s[j + len("\n    }\n"):]
        write(p, s)
        print("   LiftChimePlayer          两个字段 + play() 整段")

    # 5) PsdChimePlayer
    p = os.path.join(cdir, "PsdChimePlayer.java")
    s = read(p)
    if "EscalatorAudioPlayer.patched(tone.builtinKey())" in s:
        print("   PsdChimePlayer           已含补丁逻辑，跳过")
    else:
        anchor = "        if (playbackId == null) {\n"
        k = s.find("String playbackId = startMs > 0 ? playbackId(tone, startMs) : null;")
        k2 = s.find(anchor, k)
        if k < 0 or k2 < 0:
            FAILS.append("%s：PsdChimePlayer 插入点没找到" % proj)
        else:
            s = s[:k2] + PSD_PATCH_BRANCH + s[k2:]
        write(p, s)
        print("   PsdChimePlayer           整段起播也认补丁")

    # 6) MbmHelpScreen
    p = os.path.join(cdir, "MbmHelpScreen.java")
    s = read(p)
    if "RepairPatch.importPatch()" in s:
        print("   MbmHelpScreen            已含两只按钮，跳过")
    else:
        anchor = "                this::applyVolume));\n\n        setInitialFocus(volumeInput);\n"
        if anchor not in s:
            FAILS.append("%s：MbmHelpScreen 插入点没找到" % proj)
        else:
            s = s.replace(anchor,
                          "                this::applyVolume));\n\n" + SCREEN_BLOCK
                          + "\n        setInitialFocus(volumeInput);\n", 1)
        write(p, s)
        print("   MbmHelpScreen            右上角两只按钮 + rightRowX()")


for proj, rel in TARGETS.items():
    apply(proj, rel)

print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 移植完成（4 个工程）==")
