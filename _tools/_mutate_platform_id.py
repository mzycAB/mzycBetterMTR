# -*- coding: utf-8 -*-
"""【09-28 续 6】反向对照：把「站台 id 的正负哨兵」这个错法逐个注回去，断言必须变红。

用法：python _tools/_mutate_platform_id.py <A|B|C|D|E|F|backup|restore>

  A = PsdDoorTracker 认站台那一支退回 `if (id > 0L)`（= LOG12 的原始 bug）
  B = PsdDoorTracker 借用那一支退回 `if (borrowed > 0L)`
  C = PsdDoorTracker DoorView.platformId 缺省值退回 0L
  D = PsdDoorTracker borrowPlatformId 借不到时退回 -1L
  E = MtrDwellAccess.platformIdAt 给 id 加「正数过滤」（= 把负 id 再判死一次）
  F = PsdChimePlayer 进站报站缓存缺省值退回 -1L，且第一道闸门退回 `platformId <= 0L`

★ 备份路径必须写成 **Windows 形态**（走 %LOCALAPPDATA%\\Temp）：Git Bash 的 /tmp 交给
  Windows 版 Python 会 FileNotFoundError（踩过：restore 静默失败 ⇒ mutation 叠加、
  红项数逐轮变多，反而「看起来很正常」）。
"""
import hashlib
import io
import os
import shutil
import sys

BASE = "C:/Users/user/Desktop/2/mzycBetterMTR-1.20.4/"
CL = BASE + "src/client/java/smooth/lift/client/"
MD = CL + "MtrDwellAccess.java"
TR = CL + "PsdDoorTracker.java"
PL = CL + "PsdChimePlayer.java"

BK_DIR = os.path.join(os.environ.get("LOCALAPPDATA", "C:/Users/user/AppData/Local"), "Temp")
BK = {MD: os.path.join(BK_DIR, "bk_pid_MtrDwellAccess.java"),
      TR: os.path.join(BK_DIR, "bk_pid_PsdDoorTracker.java"),
      PL: os.path.join(BK_DIR, "bk_pid_PsdChimePlayer.java")}

ACT = sys.argv[1]

if ACT == "backup":
    for src, dst in BK.items():
        shutil.copyfile(src, dst)
    print("backup ok -> %s" % BK_DIR)
    sys.exit(0)

if ACT == "restore":
    for src, dst in BK.items():
        shutil.copyfile(dst, src)
        with open(src, "rb") as fh:
            print("  restored %s  md5=%s" % (src.rsplit("/", 1)[-1], hashlib.md5(fh.read()).hexdigest()))
    sys.exit(0)


def sub(path, old, new):
    src = io.open(path, encoding="utf-8").read()
    n = src.count(old)
    assert n == 1, "锚点命中 %d 次（应为 1）：%r" % (n, old)
    io.open(path, "w", encoding="utf-8", newline="").write(src.replace(old, new))
    print("  patched %s" % path.rsplit("/", 1)[-1])


if ACT == "A":
    sub(TR, "if (MtrDwellAccess.isPlatformKnown(id)) {", "if (id > 0L) {")
elif ACT == "B":
    sub(TR, "if (MtrDwellAccess.isPlatformKnown(borrowed)) {", "if (borrowed > 0L) {")
elif ACT == "C":
    sub(TR, "? MtrDwellAccess.PLATFORM_ID_NONE : recognized - Long.MIN_VALUE;",
        "? 0L : recognized - Long.MIN_VALUE;")
elif ACT == "D":
    sub(TR, "            return MtrDwellAccess.PLATFORM_ID_NONE;\n        }\n        return PLATFORM_CACHE.get(bestFlood) - Long.MIN_VALUE;",
        "            return -1L;\n        }\n        return PLATFORM_CACHE.get(bestFlood) - Long.MIN_VALUE;")
elif ACT == "E":
    sub(MD, "            return id instanceof Number n ? n.longValue() : PLATFORM_ID_NONE;",
        "            if (id instanceof Number n) {\n"
        "                long v = n.longValue();\n"
        "                return v > 0L ? v : PLATFORM_ID_NONE;\n"
        "            }\n"
        "            return PLATFORM_ID_NONE;")
elif ACT == "F":
    sub(PL, "long platformId = arrivePlatform.getOrDefault(runKey, MtrDwellAccess.PLATFORM_ID_NONE);",
        "long platformId = arrivePlatform.getOrDefault(runKey, -1L);")
    sub(PL, "if (!MtrDwellAccess.isPlatformKnown(platformId)) {\n                platformId = door.platformId();",
        "if (platformId <= 0L) {\n                platformId = door.platformId();")
else:
    raise SystemExit("unknown action")
print("mutation %s applied" % ACT)
