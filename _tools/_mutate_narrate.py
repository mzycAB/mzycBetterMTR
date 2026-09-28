# -*- coding: utf-8 -*-
"""【09-28 续 3】反向对照：把「香港档的名源」这个错法逐个注回去，断言必须变红。

用法：python _tools/_mutate_narrate.py <A|B|C|restore>
  A = 播放端把终点站换成车站名（上一版的错法）
  B = 香港分支把终点站换成站台名（更早一版的错法）
  C = arriveTextForStyle 加回第 4 个参数 stationName
  restore = 从备份还原（不许用 git，工程不是 git 仓库）
★ 备份路径必须写成 **Windows 形态**（C:/Users/...）：Git Bash 的 /tmp 交给 Windows 版
  Python 会 FileNotFoundError（踩过：restore 静默失败 ⇒ mutation 叠加、红项数逐轮变多）。
"""
import io
import os
import shutil
import sys

BASE = "C:/Users/user/Desktop/2/mzycBetterMTR-1.20.4/"
PL = BASE + "src/client/java/smooth/lift/client/PsdChimePlayer.java"
NA = BASE + "src/client/java/smooth/lift/client/TrainAnnounceNarrator.java"
BK_DIR = os.path.join(os.environ.get("LOCALAPPDATA", "C:/Users/user/AppData/Local"), "Temp")
BK_PL = os.path.join(BK_DIR, "bk_PsdChimePlayer.java")
BK_NA = os.path.join(BK_DIR, "bk_TrainAnnounceNarrator.java")

ACT = sys.argv[1]

if ACT == "backup":
    shutil.copyfile(PL, BK_PL)
    shutil.copyfile(NA, BK_NA)
    print("backup ok -> %s" % BK_DIR)
    sys.exit(0)

if ACT == "restore":
    shutil.copyfile(BK_PL, PL)
    shutil.copyfile(BK_NA, NA)
    print("restored")
    sys.exit(0)


def sub(path, old, new):
    src = io.open(path, encoding="utf-8").read()
    n = src.count(old)
    assert n == 1, "锚点命中 %d 次（应为 1）：%r" % (n, old)
    io.open(path, "w", encoding="utf-8", newline="").write(src.replace(old, new))
    print("  patched %s" % path.rsplit("/", 1)[-1])


if ACT == "A":
    sub(PL, "narrateMode, arrival.destination, arrival.platformName",
        "narrateMode, stationName, arrival.platformName")
elif ACT == "B":
    sub(NA, "return arriveTextHongKong(destination);",
        "return arriveTextHongKong(platformName);")
elif ACT == "C":
    sub(NA, "public static String arriveTextForStyle(int mode, String destination, String platformName) {",
        "public static String arriveTextForStyle(int mode, String destination, "
        "String stationName, String platformName) {")
else:
    raise SystemExit("unknown action")
print("mutation %s applied" % ACT)
