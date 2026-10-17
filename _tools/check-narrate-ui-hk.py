# -*- coding: utf-8 -*-
"""离线守则校验：①香港档**始终**两句都念（缺段用原文兜底 —— 用户 2026-10-01 点名改的规矩）；
②石斧 UI 选讲述人样式要**清掉全局样式覆盖**（2026-10-01 LOG000001 / 1.20.4 现场钉死）。

## 背景
- 「香港预设只播报英文，中文播报去哪了」：根因是 **MTR3 的站名 / 目的地是单语言字段**
  （RoutePlatform.customDestination / SavedRailBase.name，没有 MTR4 的「中文|English」
  双语架构），站名「1」只有英文段。**旧规矩②**「只有英文名 ⇒ 中文句整句不接」下只念英文句。
- ★★【10-01 用户点名改规矩】香港档改为**始终**两句都念：名字里缺哪段，就用**另一段**兜底
  （不是省掉整句）。⇒ 站名「1」时中文句与英文句都念「1」；
  站名「人民路」时英文句念「人民路」。旧规矩②已作废。
- 「UI 里设置讲述人不管用，只能指令设置」：运行时 narrateMode =
  `styleOverride >= 0 ? styleOverride : doorNarrateMode`（全局覆盖优先）。用户先前
  /jsr on default-HK 设过全局 ⇒ UI 写的门串层被压住。修复：UI 点「选择」时清全局覆盖
  （style 回 default = 跟门串）。

## 修法（本脚本要钉住的形态）
- arriveTextHongKong：拆完段后**缺段用另一段兜底**（`if (chinese == null) chinese = foreign;`
  / `if (foreign == null) foreign = chinese;`），然后**无条件**拼「中文句 + 换行 + 英文句」。
- PsdToneSetupScreen.pickNarrate：narratorTarget==0 → sendSetNarrate + clearGlobalStyle；
  否则 → sendSetMidiumNarrate + clearMidiumGlobalStyle。
- TrainAnnounceSwitch.clearGlobalStyle / clearMidiumGlobalStyle：style/midiumStyle 回
  STYLE_DEFAULT 并 save()。
"""

import io
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label,
                         ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def read(rel):
    p = os.path.join(PROJ, *rel)
    return io.open(p, encoding="utf-8", errors="replace").read() if os.path.isfile(p) else None


def is_chinese_py(t):
    has = False
    for ch in t:
        cp = ord(ch)
        if 0x3040 <= cp <= 0x30FF or 0xAC00 <= cp <= 0xD7A3:  # 假名 / 谚文 → 非中文
            return False
        if 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0xF900 <= cp <= 0xFAFF \
                or ch in "\u3005\u3007":
            has = True
    return has


def hk_py(destination):
    """arriveTextHongKong 的纯 Python 复刻（用户【10-01 改】：香港档**始终**两句都念，
    缺段用原文兜底）。"""
    raw = (destination or "").strip()
    if not raw:
        return None
    chinese = foreign = None
    for part in raw.split("|"):
        piece = part.strip()
        if not piece:
            continue
        if is_chinese_py(piece):
            if chinese is None:
                chinese = piece
        elif foreign is None:
            foreign = piece
    if chinese is None and foreign is None:
        return None
    # ★ 缺段用**另一段**兜底（不是省掉整句）：两段不会同时为 None，取另一段必拿得到名字
    if chinese is None:
        chinese = foreign
    if foreign is None:
        foreign = chinese
    return (u"前往" + chinese + u"的列车即将到达，请先让车上的乘客下车"
            + "\n"
            + "The train to " + foreign + " is arriving, please let passengers exit first.")


def main():
    # ① 纯逻辑复算：香港档**始终**两句都念（缺段用原文兜底）
    cases = [
        ("1", True, True),                     # 纯数字/单语言：两句都念，都用「1」
        ("People Road", True, True),           # 纯英文名：英文段有 ⇒ 中文句用另一段兜底
        (u"人民路|People Road", True, True),    # 双语：两行，各取各段
        (u"人民路", True, True),                # 只有中文：英文句用另一段兜底
        ("", False, False),                    # 空 → null
    ]
    ok_all = True
    for dest, want_cn, want_en in cases:
        out = hk_py(dest)
        has_cn = out is not None and u"前往" in out and u"的列车即将到达" in out
        has_en = out is not None and "The train to " in out
        good = (has_cn == want_cn) and (has_en == want_en)
        ok_all = ok_all and good
        check(good, u"复算 %r → %s" % (dest, ("中文句+英文句" if has_cn and has_en
                                              else "仅中文句" if has_cn else "仅英文句" if has_en else "空")),
              repr(out) if out else "null")
    # 关键形态：单语言名「1」⇒ **两句都念**，且**中文句与英文句都用「1」**（用户 10-01 点名）
    out1 = hk_py("1")
    check(out1 is not None and "The train to 1 is arriving" in out1
          and u"前往1的列车即将到达" in out1,
          u"★「1」单语言名 ⇒ 中文句+英文句都念（都用「1」，缺段用另一段兜底）",
          repr(out1) if out1 else "null")

    # ② 源码：香港档**缺段用 raw 兜底** + **两句无条件拼**（用户 10-01 改的规矩）
    nar = read(("src", "client", "java", "smooth", "lift", "client", "TrainAnnounceNarrator.java")) \
        or read(("src", "main", "java", "smooth", "lift", "client", "TrainAnnounceNarrator.java"))
    if not nar:
        check(False, "找到 TrainAnnounceNarrator.java")
    else:
        body = strip_comments(nar)
        check(re.search(r"if\s*\(\s*chinese\s*==\s*null\s*\)\s*\{\s*chinese\s*=\s*foreign\s*;", body) is not None,
              u"缺中文段用另一段兜底（chinese = foreign）")
        check(re.search(r"if\s*\(\s*foreign\s*==\s*null\s*\)\s*\{\s*foreign\s*=\s*chinese\s*;", body) is not None,
              u"缺英文段用另一段兜底（foreign = chinese）")
        check(re.search(r"text\.append\(HK_CN_LEAD\)\.append\(chinese\)\.append\(HK_CN_TAIL\);", body) is not None
              and re.search(r"text\.append\(HK_EN_LEAD\)\.append\(foreign\)\.append\(HK_EN_TAIL\);", body) is not None,
              u"中文句 + 英文句**都无条件拼**（不再是「有哪段才念哪段」）")
        check(re.search(r"if\s*\(\s*chinese\s*!=\s*null\s*\)\s*\{\s*text\.append\(HK_CN_LEAD\)", body) is None,
              u"中文句**不再**受 chinese != null 守卫（旧规矩②已废，不许回退）")

    # ③ 源码：UI pickNarrate 清全局覆盖
    scr = read(("src", "client", "java", "smooth", "lift", "client", "PsdToneSetupScreen.java"))
    if not scr:
        check(False, "找到 PsdToneSetupScreen.java")
    else:
        body = strip_comments(scr)
        pick = None
        i = body.find("private void pickNarrate")
        if i >= 0:
            ob = body.index("{", i)
            d, end = 0, None
            for j in range(ob, len(body)):
                if body[j] == "{":
                    d += 1
                elif body[j] == "}":
                    d -= 1
                    if d == 0:
                        end = j
                        break
            pick = body[ob:end + 1] if end else ""
        check(pick is not None and pick and "TrainAnnounceSwitch.clearGlobalStyle()" in pick,
              u"pickNarrate 进站分支清全局样式（clearGlobalStyle）")
        check(pick is not None and pick and "TrainAnnounceSwitch.clearMidiumGlobalStyle()" in pick,
              u"pickNarrate 站台分支清全局样式（clearMidiumGlobalStyle）")

    # ④ 源码：TrainAnnounceSwitch 两个 clear 方法把覆盖回 default
    sw = read(("src", "client", "java", "smooth", "lift", "client", "TrainAnnounceSwitch.java"))
    if not sw:
        check(False, "找到 TrainAnnounceSwitch.java")
    else:
        body = strip_comments(sw)
        for name, field in (("clearGlobalStyle", "style"), ("clearMidiumGlobalStyle", "midiumStyle")):
            m = re.search(r"public\s+static\s+void\s+%s\s*\(\s*\)" % name, body)
            check(m is not None and "STYLE_DEFAULT" in body[m.start():m.start() + 400],
                  u"%s 把 %s 回 STYLE_DEFAULT 并 save" % (name, field))

    print()
    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("  ==> 失败  " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()
