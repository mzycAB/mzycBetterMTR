# -*- coding: utf-8 -*-
"""离线校验：【1.53】/MBM 指令（music in|delete + help）与 MBM_Audio 文件夹改名。

用户点名：
- 把 `/dtmusic` 改成 `/MBM music in` / `/MBM music delete`（干净改名，旧名不保留）
- 存档音频文件夹 `smoothlift_audio` 改名 `MBM_Audio`
- 新增 `/MBM help`：打开一个界面（界面本体在 check-mbm-help.py 里校验）
- 版本号：不写死，跟随 gradle.properties 的 mod_version（改名轮次由用户点名决定）

判据（改错了不报错，症状=「指令没了」/「批量导入找不到文件」/「误删文件夹」）：
- 根字面量 `MBM` 与 `mbm` 都有注册（MC 的指令字面量大小写敏感，只写大写时 /mbm 会报未知指令）。
- `MBM music in` -> dtMusicImportAll；`MBM music delete` -> dtMusicDeleteAll；`MBM help` -> mbmOpenHelp。
- 旧的根字面量 `dtmusic` **一个字都不剩**（只在注释/javadoc 里作为改名历史出现）。
- EscalatorSpeedManager.AUDIO_FOLDER 的值是 "MBM_Audio"。
- import 走 scanAudioFiles 全量遍历 + importAudioToStore（同一套 ogg 校验）+ sendAudioSyncTo 补发右列。
- delete 遍历 audioLibrary.keySet() 快照逐条 deleteAudio（removeAudio 会连带清引用），
  并补三路同步（audio/help/psdChime）；★ delete handler 方法体内**没有任何删除文件夹文件的代码**。
"""

import os
import re
import sys
import glob
import zipfile
import json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
SL = os.path.join(MAIN, "SmoothLift.java")
ESM = os.path.join(MAIN, "EscalatorSpeedManager.java")
GP = os.path.join(ROOT, "gradle.properties")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


sl = open(SL, encoding="utf-8").read()
sl_no = strip_comments(sl)
esm = open(ESM, encoding="utf-8").read()
esm_no = strip_comments(esm)

# ======================================================================
# 1) 指令树：/MBM（含小写别名）
# ======================================================================
print()
print("===== 1) /MBM help | music in | music delete 注册 =====")

check(re.search(r'dispatcher\.register\(mbmTree\("MBM"\)\)', sl_no) is not None,
      "根字面量 MBM 已注册（走 mbmTree）")
check(re.search(r'dispatcher\.register\(mbmTree\("mbm"\)\)', sl_no) is not None,
      "小写别名 mbm 也已注册（MC 指令字面量大小写敏感）")
check(re.search(r"Commands\.literal\(literal\)\s*\n\s*\.executes\(SmoothLift::mbmOpenHelp\)", sl_no) is not None,
      "裸 /MBM = 与 /MBM help 同义（自带执行器 -> mbmOpenHelp）")
check(re.search(r'Commands\.literal\("help"\)\s*\n\s*\.executes\(SmoothLift::mbmOpenHelp\)', sl_no) is not None,
      "MBM help -> mbmOpenHelp")
check(re.search(r'Commands\.literal\("music"\)', sl_no) is not None,
      "MBM music 子节点存在")
check(re.search(r'Commands\.literal\("in"\)\s*\n\s*\.executes\(SmoothLift::dtMusicImportAll\)', sl_no) is not None,
      "MBM music in -> dtMusicImportAll")
check(re.search(r'Commands\.literal\("delete"\)\s*\n\s*\.executes\(SmoothLift::dtMusicDeleteAll\)', sl_no) is not None,
      "MBM music delete -> dtMusicDeleteAll")

# ★ 旧根字面量必须彻底消失（只在注释里出现 = 改名历史）
check(re.search(r'Commands\.literal\("dtmusic"\)', sl_no) is None,
      "★ 旧的根字面量 dtmusic 已从指令树里移除（干净改名，不保留别名）")
raw_hits = [i for i, line in enumerate(sl.split("\n"), 1) if "dtmusic" in line]
outside_comment = [i for i, line in enumerate(sl_no.split("\n"), 1) if "dtmusic" in line]
check(not outside_comment,
      "★ 剥掉注释后的源码里一个 dtmusic 都没有（残留只允许在注释/javadoc 里）",
      "行号 %s" % (outside_comment if outside_comment else "无"))

# ======================================================================
# 2) dtMusicImportAll 方法体（批量导入）
# ======================================================================
print()
print("===== 2) dtMusicImportAll：全量遍历文件夹导入 =====")

m = re.search(r"private static int dtMusicImportAll\(CommandContext<CommandSourceStack> context\)\s*\{(.*?)\n    \}",
              sl, flags=re.S)
check(m is not None, "抠得出 dtMusicImportAll() 方法体")
if m:
    fb = strip_comments(m.group(1))
    check("scanAudioFiles(level, category)" in fb or "scanAudioFiles(level," in fb,
          "用 scanAudioFiles 扫全部分类子文件夹（MBM_Audio/<分类>）")
    check("importAudioToStore(level," in fb and "e.getKey()" in fb,
          "逐条走 importAudioToStore（同一套 ogg 校验）")
    check("sendAudioSyncTo(source.getPlayer(), level)" in fb,
          "导入后补发音频库同步包（客户端「已导入」右列刷新）")
    check("if (true)" not in fb and "if (false)" not in fb,
          "方法体内没有短路守卫（批量真的会执行）")

# ======================================================================
# 3) dtMusicDeleteAll 方法体（批量删除 + 不碰文件夹）
# ======================================================================
print()
print("===== 3) dtMusicDeleteAll：清空音频库、不动 MBM_Audio 文件夹 =====")

m = re.search(r"private static int dtMusicDeleteAll\(CommandContext<CommandSourceStack> context\)\s*\{(.*?)\n    \}",
              sl, flags=re.S)
check(m is not None, "抠得出 dtMusicDeleteAll() 方法体")
if m:
    fb = strip_comments(m.group(1))
    check("audioCategoryNames.values()" in fb or "audioLibrary.keySet()" in fb,
          "遍历全量键（快照；【1.28】起跨分类注册表遍历，同名在多个分类也清干净）")
    check("deleteAudio(level," in fb and "ALL_CATEGORIES" in fb,
          "逐条走 deleteAudio（【1.28】起按分类删，removeAudio 连带清引用）")
    check("syncAudioToAll(level.getServer())" in fb
          and "syncHelpAudioToAll(level.getServer())" in fb
          and "syncPsdChimeToAll(level.getServer())" in fb,
          "删除后补三路同步（音频库 / 无障碍 / 屏蔽门）")
    check("Files.delete" not in fb and "deleteIfExists" not in fb
          and "deleteDirectory" not in fb,
          "★ delete 方法体内**没有任何删除文件夹文件的代码**（MBM_Audio 只读，绝不动）")
    check("if (true)" not in fb and "if (false)" not in fb,
          "方法体内没有短路守卫（批量真的会执行）")

# ======================================================================
# 4) 文件夹改名
# ======================================================================
print()
print("===== 4) 存档音频文件夹 = MBM_Audio =====")

check(re.search(r'public static final String AUDIO_FOLDER = "MBM_Audio";', esm_no) is not None,
      'AUDIO_FOLDER 的值是 "MBM_Audio"')
check('"smoothlift_audio"' not in esm_no, "EscalatorSpeedManager 里没有旧文件夹名的字符串字面量")
check("audioFolder(level)" in esm_no and "AUDIO_FOLDER" in esm_no,
      "文件夹路径仍然统一由 audioFolder(level) 产出")
# 别的地方（界面标签、README）也复盘一遍：源码里不该再有旧名（注释除外）
src_hits = []
for dirpath, _, names in os.walk(os.path.join(ROOT, "src")):
    for name in names:
        if not name.endswith(".java"):
            continue
        p = os.path.join(dirpath, name)
        body = strip_comments(open(p, encoding="utf-8").read())
        if "smoothlift_audio" in body:
            src_hits.append(os.path.relpath(p, ROOT))
check(not src_hits, "剥注释后全工程源码里没有旧文件夹名 smoothlift_audio", "命中 %s" % src_hits)

check("【1.53】" in esm, "改名有【1.53】标记（可追溯）")

# ======================================================================
# 5) 版本号（用户本轮没点名 => 沿用）
# ======================================================================
print()
gp = open(GP, encoding="utf-8").read()
got = re.search(r"mod_version=(.*)", gp).group(1).strip()
# ★ 不写死版本号：以 gradle.properties 为唯一源（用户点名的号就在那里）。
#   写死会在下一轮改号时误报「回归失败」—— 断言自己过期，不是代码回归。
EXPECTED_VER = got
print("===== 5) 版本号 = %s（跟随 gradle.properties）=====" % EXPECTED_VER)
check(re.fullmatch(r"\d+\.\d+\.\d+", got) is not None,
      "gradle.properties mod_version = 形如 x.y.z", "得到 %s" % got)

# ======================================================================
# 6) 字节码 / jar 元数据
# ======================================================================
print()
print("===== 6) 字节码 / jar =====")

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    with zipfile.ZipFile(jar) as z:
        sl_blob = z.read("smooth/lift/SmoothLift.class")
        esm_blob = z.read("smooth/lift/EscalatorSpeedManager.class")
        fm = json.loads(z.read("fabric.mod.json"))
        names = z.namelist()
    check(b"dtMusicImportAll" in sl_blob and b"dtMusicDeleteAll" in sl_blob,
          "两个批量 handler 都编进了字节码")
    check(b"MBM" in sl_blob and b"mbm" in sl_blob, "根字面量 MBM / mbm 编进了字节码")
    check(b"mbm_help_open" in sl_blob and b"mbm_preset" in sl_blob and b"mbm_all_volume" in sl_blob,
          "【1.53】三只新频道（开界面 / 预设 / 全音量）都在字节码里")
    check(b"dtmusic" not in sl_blob, "★ 字节码里没有旧的 dtmusic 字面量")
    check(b"MBM_Audio" in esm_blob, "★ 字节码里文件夹名是 MBM_Audio")
    check(b"smoothlift_audio" not in esm_blob, "★ 字节码里没有旧的 smoothlift_audio")
    check(fm["version"] == EXPECTED_VER, "jar 内 fabric.mod.json version = %s" % EXPECTED_VER,
          "得到 %s" % fm["version"])
    check(any(n.endswith("MbmHelpScreen.class") for n in names),
          "【1.53】MbmHelpScreen.class 已打进 jar")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
