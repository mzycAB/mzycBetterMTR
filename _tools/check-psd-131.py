# -*- coding: utf-8 -*-
"""离线校验：【1.31】三件事（用户原话逐句对应）：

1. 「屏蔽门ui设置的即将进站播报（pbmarrive）没有用，但是指令设置的可以。」
2. 「玩家在列车内减少80%的pbmarrive和pbmmidium的声音，离开列车立刻恢复到100%，
    不需要淡入淡出的功能好像不见了。」
3. 「列车行进方向最前面的2个屏蔽门的门是单独连在一起设置的，其他的屏蔽门是连在一起设置的。」

## 根因（代码链可复核）

- 现象 3 → 现象 1：最前面 2 扇门是独立连通串，且 `platformIdAt` 认不到站台（站台端头离
  中轴 > 4 格）⇒ runKey 停留连通串身份 ⇒ `tickArriveAnnounce` 在**读配置之前**就被
  platformId<=0 挡掉 continue ⇒ UI 按串设的 pbmarrive 素材/秒数根本走不到
  `getDoorPsdArriveAudio` —— 「UI 设置没用」；而指令设的是**维度默认**，主串能读到 ⇒ 有效。
  修：认不到站台时向「最近已认站台串」**借用**站台身份（≤12 格、同层、唯一近邻），
  两串并入同一身份 ⇒ 配置/去重/射程/声源自动按整站台。
- 现象 2：1.30 的报站平滑把「进出列车立即 -80%/恢复 100%」拖成了 ~250ms 淡入淡出。
  修：列车倍率在平滑**之后**乘（平滑只拦距离变化）。

## 判据（改错了不报错，症状=「前2扇门 UI 设置还是没用」/「进出列车又变平滑了」）

- runKeyOf 认不到分支里真的会执行 `borrowPlatformId(doorKey)`（方法体断言防短路）。
- 借用带防串台约束（距离上限 + 同层 + 唯一近邻）。
- refreshVolume：`v *= trainRamp();` 的索引在 `smoothedVolume + (v - smoothedVolume)` 之后
  （列车挡不经过平滑）。
"""

import os
import re
import sys
import glob
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client")
PLAYER = os.path.join(CLIENT, "PsdChimePlayer.java")
TRACKER = os.path.join(CLIENT, "PsdDoorTracker.java")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


tracker = load(TRACKER)
player = load(PLAYER)

# ======================================================================
# 1) 认不到站台 → 借用相邻已认站台串的身份（现象 1 + 3）
# ======================================================================
print()
print("===== 1) 认不到站台的串借用相邻站台身份（UI 按串设置才有意义） =====")

check("borrowPlatformId(" in tracker and "PLATFORM_CACHE.get(flood)" in tracker
      and "PLATFORM_BORROW_DIST" in tracker and "PLATFORM_BORROW_MARGIN" in tracker,
      "borrowPlatformId + 两个防串台常量存在")
m = re.search(r"PLATFORM_BORROW_DIST\s*=\s*12\.0\s*;", tracker)
check(m is not None, "PLATFORM_BORROW_DIST = 12.0（端头门到主串门距 4~9 格内）")
check(re.search(r"PLATFORM_BORROW_MARGIN\s*=\s*8\.0\s*;", tracker) is not None,
      "PLATFORM_BORROW_MARGIN = 8.0（次近明显更远才算唯一近邻）")
check("second - best < PLATFORM_BORROW_MARGIN" in tracker,
      "★ 唯一性判据在（两个候选差不多近 ⇒ 不借，宁可保持连通串）")
check("Math.abs(oy - dy) > 8.0" in tracker,
      "同层门槛（锚点 |Δy| ≤ 8，与 MtrDwellAccess.MAX_DY 同值）")

m_rk = re.search(r"private static long runKeyOf\(Level level, BlockState state, BlockPos raw, long doorKey\)"
                 r"\s*\{(.*?)\n    \}", tracker, flags=re.S)
check(m_rk is not None, "抠得出 runKeyOf() 方法体")
if m_rk:
    fb = m_rk.group(1)
    check("if (true)" not in fb and "if (false)" not in fb,
          "runKeyOf() 里没有短路守卫（借用真的会执行到）")
    check("long borrowed = borrowPlatformId(doorKey);" in fb,
          "借用调用在方法体内、且紧跟认不到分支")
    check(fb.count("return flood;") == 1, "「return flood;」仍只允许一处（认不到的最终出口）")

# ======================================================================
# 2) 列车倍率在平滑之后乘（现象 2：不用淡入淡出，进出列车立即切换）
# ======================================================================
print()
print("===== 2) 列车倍率绕过 1.30 平滑（进出列车立即 -80% / 恢复 100%） =====")

m_rv = re.search(r"void refreshVolume\(Minecraft mc\)\s*\{(.*?)\n        \}", player, flags=re.S)
check(m_rv is not None, "抠得出 refreshVolume() 方法体")
if m_rv:
    rb = m_rv.group(1)
    i_smooth = rb.find("smoothedVolume + (v - smoothedVolume)")
    i_train = rb.find("v *= trainRamp();")
    check(i_smooth != -1 and i_train != -1 and i_smooth < i_train,
          "★ 平滑（距离部分）在 `v *= trainRamp();` **之前** —— 列车挡不经过平滑",
          "平滑@%d 列车@%d" % (i_smooth, i_train))
    check("updateTrainRamp(mc);" in rb and "v *= trainRamp();" in rb,
          "列车倍率仍每 tick 现取（阶跃语义保持，1.28 不变）")

# ======================================================================
# 3) 字节码
# ======================================================================
print()
print("===== 3) 字节码 =====")

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    with zipfile.ZipFile(jar) as z:
        tr = z.read("smooth/lift/client/PsdDoorTracker.class")
        pl = z.read("smooth/lift/client/PsdChimePlayer.class")
        for tok in (b"borrowPlatformId", b"PLATFORM_BORROW_DIST", b"PLATFORM_BORROW_MARGIN"):
            check(tok in tr, "PsdDoorTracker.class 里编进了 %s" % tok.decode())
        # VOLUME_SMOOTH_PER_TICK 是 `static final` 会在编译期内联、而且字段在内嵌类
        # PsdChimePlayer$PsdMusicInstance 里 —— 在生产 PsdChimePlayer.class 里查不到名字是常态
        # （1.23 的同款坑）。改查内嵌类里那个**非静态**字段 smoothedVolume（名字必在常量池）。
        blob_inner = z.read("smooth/lift/client/PsdChimePlayer$PsdMusicInstance.class")
        check(b"smoothedVolume" in blob_inner,
              "PsdChimePlayer$PsdMusicInstance.class 里编进了 smoothedVolume（平滑实例字段）")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")