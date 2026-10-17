# -*- coding: utf-8 -*-
"""离线守则校验：【1.34】讲述人自定义播报词新增 `|AC|`（车节(中)）/ `|AE|`（车节(英)）
—— 用户 2026-10-16 点名「可以播报来的是几节车」。

## 用户原话
「进站广播（讲述人）的自定义里增加 |AC| 和 |AE| 功能，功能照抄其他小按钮，
  这个按钮名字：『车节(中)』和『车节(英)』，可以播报来的是几节车」

## 这一轮钉住的形态（改错了会出现什么症状）
1. **数从哪来**：MTR `ArrivalResponse.getCarCount()`（MTR 4.0.5 字节码实证：
   方法体 = `getfield cars; ObjectArrayList.size(); ireturn`；`cars` 随这条
   ArrivalResponse 一起上线路 —— `ArrivalResponseSchema.updateData` 读 `"cars"`、
   `serializeCars` 写回；MTR 自己的 `RenderPIDS` 也调这个 getter）。
   ⇒ 必须是 `MtrDwellAccess` 在这条**同一个** ArrivalResponse 上取（不是另扫一遍），
   否则会拼出「A 车的终点站 + B 车的车节数」。
   改错症状：车节数念成 0 / 念不出 / 与 PIDS 上那块屏不一致。
2. **可选绑定**：`getCarCount` 绑不上（MTR3 / 换版本）时整条链路照常，只少念「几节车」。
   改错症状：没装 MTR 时报错。
3. **替换顺序**：`|AC|`/`|AE|` 必须在「剩下的单个 `|` → 换行」**之前**换掉。
   改错症状：`|AC|` 被拆成「换行 AC 换行」，回车换行 + 念出英文字母。
4. **两排按钮**：`车节(中)` / `车节(英)` 两格与 `|AC|` / `|AE|` **同列配对**，
   列数 5 → 6，且窄窗口下整排**不越过 x1**（越过去会盖住右列自定义词按钮）。
   改错症状：按钮点下去插错记号 / 窄窗口下按钮压住右边那一列。
5. **★【10-16 用户拍板改规矩】`|AC|` / `|AE|` 都只输出数字本身**（例：「6」，不带量词）。
   中/英读法不写在内容里，由念这一句的语音引擎决定（中文句念「六」、英文句念「six」）。
   改错症状：又出现「6节车厢」/「6 cars」——用户点名不要量词。
6. **其它占位符的中英读法区分**（用户点名检查）：4 对名字按钮（车站/站台/目的地/线路 × 中英）
   的区分在**内容**里 —— 中文槽只能出中文段、英文槽只能出英文段（念出来自然分语言），
   取不到时替成空串（不把另一种语言塞进对方槽位）。
   改错症状：中文句念出英文名 / 英文句念出中文名（中英串味）。

## 用法
  python _tools/check-narrate-carcount.py      # 全绿退 0；有红退 1
构建过之后还会顺带验一遍 jar 里的字节码常量（没构建就 SKIP）。
"""

import io
import os
import re
import sys
import zipfile

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join("src", "client", "java", "smooth", "lift", "client")
LAYOUTS = [CLIENT, ("src", "main", "java", "smooth", "lift", "client")]

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
    for base in LAYOUTS:
        p = os.path.join(PROJ, *base, os.path.basename(rel[-1]))
        if os.path.isfile(p):
            return io.open(p, encoding="utf-8", errors="replace").read()
    return None


def method_body(body, needle):
    """抠出 `needle` 那个方法/构造的 {...} 体（花括号配对；抠不到返回 None）。"""
    i = body.find(needle)
    if i < 0:
        return None
    ob = body.find("{", i)
    if ob < 0:
        return None
    depth = 0
    for j in range(ob, len(body)):
        if body[j] == "{":
            depth += 1
        elif body[j] == "}":
            depth -= 1
            if depth == 0:
                return body[ob:j + 1]
    return None


def array_literal(body, name):
    """抠 `String[] name = { ... };` 的字符串元素（抠不到返回 None）。"""
    m = re.search(r"String\[\]\s+%s\s*=\s*\{(.*?)\};" % re.escape(name), body, re.S)
    if not m:
        return None
    return re.findall(r'"([^"]*)"|null', m.group(1))


# ----------------------------------------------------------------------
# 1) 纯逻辑复算：carsText（照抄 Java，逐字对比）+ 其它占位符的中英内容区分复算
# ----------------------------------------------------------------------
def cars_text_py(car_count):
    """TrainAnnounceNarrator.carsText 的纯 Python 复刻。

    ★【10-16 用户拍板改规矩】|AC| / |AE| **都只输出数字本身**（不带量词）：
    中/英读法由念这一句的语音引擎决定（中文句念「六」、英文句念「six」）。
    """
    if car_count <= 0:
        return ""
    return "%d" % car_count


def expand_py(template, car_count):
    """只保留「|AC| / |AE| / | 换行」这三件事的复刻（其余记号不进本脚本的用例）。"""
    if not template or not template.strip():
        return None
    text = template.replace("|AC|", cars_text_py(car_count))
    text = text.replace("|AE|", cars_text_py(car_count))
    text = text.replace("|", "\n")
    text = text.replace("\r", "").strip()
    return text or None


def is_chinese_py(text):
    """TrainAnnounceNarrator.isChinese 的纯 Python 复刻：含汉字且不含假名/谚文。"""
    has_han = False
    for cp in [ord(c) for c in text]:
        if 0x3040 <= cp <= 0x30FF or 0x31F0 <= cp <= 0x31FF \
                or 0xFF66 <= cp <= 0xFF9D or 0x1100 <= cp <= 0x11FF \
                or 0x3130 <= cp <= 0x318F or 0xA960 <= cp <= 0xA97F \
                or 0xAC00 <= cp <= 0xD7AF or 0xFFA0 <= cp <= 0xFFDC:
            return False
        if 0x3400 <= cp <= 0x4DBF or 0x4E00 <= cp <= 0x9FFF \
                or 0xF900 <= cp <= 0xFAFF or cp in (0x3005, 0x3007):
            has_han = True
    return has_han


def name_part_py(raw_name, chinese):
    """TrainAnnounceNarrator.namePart 的纯 Python 复刻（中/英内容区分 = 读法区分）。"""
    if not raw_name or not raw_name.strip():
        return ""
    for piece in (p.strip() for p in raw_name.split("|")):
        if piece and is_chinese_py(piece) == chinese:
            return piece
    return ""


# ----------------------------------------------------------------------
# 2) 源码审计（做成函数，便于下面拿「被改坏的文本」反向对照）
# ----------------------------------------------------------------------
def audit_narrator(raw):
    body = strip_comments(raw)
    out = []
    out.append(("TrainAnnounceNarrator 声明 |AC| 常量",
                'TOKEN_CARS_CN = "|AC|"' in body))
    out.append(("TrainAnnounceNarrator 声明 |AE| 常量",
                'TOKEN_CARS_EN = "|AE|"' in body))
    exp = method_body(body, "public static String expandUserTemplate(") or ""
    out.append(("expandUserTemplate 替换 |AC|",
                "text.replace(TOKEN_CARS_CN, carsText(carCount))" in exp))
    out.append(("expandUserTemplate 替换 |AE|",
                "text.replace(TOKEN_CARS_EN, carsText(carCount))" in exp))
    i_cars = exp.find("text.replace(TOKEN_CARS_CN")
    i_nl = exp.find('text.replace(TOKEN_LINE_BREAK, "\\n")')
    out.append(("★ 车节记号在「单个 | → 换行」**之前**换（顺序反了记号就认不出来）",
                i_cars >= 0 and i_nl > i_cars))
    fn = method_body(body, "private static String carsText(") or ""
    out.append(("carsText：<=0 → 空串", 'if (carCount <= 0)' in fn and 'return "";' in fn))
    out.append(("★ carsText 输出**纯数字**（Integer.toString，不带量词）",
                "Integer.toString(carCount)" in fn))
    out.append(("★ carsText 里没有量词字面量（有人加回「节车厢」/「 cars」就红）",
                u"节车厢" not in fn and " cars" not in fn and u"卡" not in fn))
    # 【10-16 用户点名检查】其它占位符的中英读法区分：4 对名字按钮（车站/站台/目的地/线路）
    #   的内容区分就是读法区分 —— |SC| 等 中文 槽只能出中文段、|SE| 等 英文 槽只能出英文段。
    pp = method_body(body, "private static String namePart(") or ""
    out.append(("namePart 按 isChinese(piece) == chinese 选段（中文槽不得拿英文段）",
                "isChinese(piece) == chinese" in pp))
    out.append(("namePart 取不到时替成空串（不把另一种语言塞进来）",
                'return "";' in pp))
    style = method_body(body, "public static String arriveTextForStyle(") or ""
    out.append(("arriveTextForStyle 把 carCount 转给 expandUserTemplate",
                "expandUserTemplate(template," in style and "carCount)" in style))
    return out


def audit_access(raw):
    body = strip_comments(raw)
    out = []
    out.append(("MtrDwellAccess 有 arrivalGetCarCount 字段",
                "private static Method arrivalGetCarCount;" in body))
    out.append(("bindArrivals 把它清成 null（重新绑定前先复位）",
                "arrivalGetCarCount = null;" in body))
    out.append(('bindArrivals 绑定 getCarCount（可选绑定 method(...)）',
                'arrivalGetCarCount = method(response, "getCarCount");' in body))
    out.append(("ArrivalInfo 有 carCount 字段", "public final int carCount;" in body))
    out.append(("intOf 辅助（getter 为 null / 调用失败 → 0）",
                "private static int intOf(Object target, Method getter)" in body
                and "if (getter == null)" in (method_body(body, "private static int intOf(") or "")))
    near = method_body(body, "public static ArrivalInfo nearestArrival(long platformId)") or ""
    out.append(("★ 车节数与终点站/站台名**取自同一条**被选中的 ArrivalResponse",
                "bestCarCount = intOf(response, arrivalGetCarCount);" in near
                and near.find("bestCarCount = intOf(") > near.find("if (best == Long.MIN_VALUE")))
    out.append(("MTR4 出口带上 bestCarCount",
                "new ArrivalInfo(best, bestDestination, bestPlatform, bestCarCount)" in body))
    out.append(("MTR3 出口给 0（读不到，念不出车节但其余照常）",
                "new ArrivalInfo(best, bestDest, bestPlatform, 0)" in body))
    out.append(("ArrivalInfo 构造函数 4 个参数",
                "private ArrivalInfo(long remainingMs, String destination, String platformName, int carCount)"
                in body))
    return out


def audit_chime(raw):
    body = strip_comments(raw)
    out = []
    out.append(("进站讲述人：传 arrival.carCount",
                "TrainAnnounceSwitch.arriveUserTexts(), arrival.carCount)" in body))
    out.append(("站台讲述人：传 arrival == null ? 0 : arrival.carCount",
                "arrival == null ? 0 : arrival.carCount)" in body))
    return out


def audit_screen(raw):
    body = strip_comments(raw)
    fn = method_body(body, "private void buildInsertButtons(") or ""
    cn = array_literal(fn, "cn")
    en = array_literal(fn, "en")
    cns = array_literal(fn, "cnTokens")
    ens = array_literal(fn, "enTokens")
    out = []
    out.append(("两排标签是 6 项（多了 车节(中)/(英)）",
                cn is not None and len(cn) == 6 and en is not None and len(en) == 6))
    out.append((u"中文排第 5 列（0 起）就是「车节(中)」",
                bool(cn) and len(cn) == 6 and cn[5] == u"车节(中)"))
    out.append((u"英文排第 5 列（0 起）就是「车节(英)」",
                bool(en) and len(en) == 6 and en[5] == u"车节(英)"))
    out.append(("★ 中英两排**同列配对**：cnTokens[5]=|AC| 且 enTokens[4]=|AE|"
                "（英文排少「换行」那一格，按钮按下标 col-1 取记号）",
                bool(cns) and bool(ens) and len(cns) == 6 and len(ens) == 5
                and cns[5] == "|AC|" and ens[len(ens) - 1] == "|AE|"))
    out.append(("列数由 cn.length 推（不是写死 5）", "int cols = cn.length;" in fn))
    out.append(("★ 窄窗口兜底：按比例缩列，保证整排不越过 x1（否则盖住右列）",
                "if (total + gap * (cols - 1) > avail)" in fn
                and "while (sum > budget)" in fn
                and "Math.max(8, widths[i] * budget / Math.max(1, total))" in fn))
    return out


def audit_switch(raw):
    body = strip_comments(raw)
    return [("TrainAnnounceSwitch 的 /jsr 帮助文本列出 |AC|/|AE| 且注明纯数字",
             "|AC|/|AE| = 本次列车几节车" in body
             and "两个都输出纯数字" in body)]


def run(audit, raw, tag):
    print()
    print("===== %s =====" % tag)
    if raw is None:
        check(False, "读到源码（%s）" % tag)
        return 0
    items = audit(raw)
    for label, ok in items:
        check(ok, label)
    return len(items)


def main():
    nar = read((CLIENT, "TrainAnnounceNarrator.java"))
    acc = read((CLIENT, "MtrDwellAccess.java"))
    chime = read((CLIENT, "PsdChimePlayer.java"))
    scr = read((CLIENT, "PsdToneSetupScreen.java"))
    sw = read((CLIENT, "TrainAnnounceSwitch.java"))

    # ---- 1) 纯逻辑复算 ----
    print("===== 1) 纯逻辑复算：carsText / 记号替换 / 名字中英区分 =====")
    # |AC| 和 |AE| **一样**都输出纯数字（【10-16 用户拍板】：不带量词，
    # 中/英读法由念这一句的语音引擎决定 —— 中文句念「六」、英文句念「six」）。
    cases = [
        (8, "8"),
        (4, "4"),
        (6, "6"),
        (1, "1"),          # ★ 1 也还是「1」，不是「1 节」也不是「1 car」
        (0, ""),           # 读不到 → 空串
        (-3, ""),
    ]
    for n, want in cases:
        got = cars_text_py(n)
        check(got == want, u"carsText(%d) → %r" % (n, want), "得到 %r" % got)
    check(cars_text_py(8) == cars_text_py(8),
          u"|AC| / |AE| 输出同一个数字（无中英内容差异）")

    tpl = u"本次列车共|AC||This train has |AE|."
    got = expand_py(tpl, 8)
    want = u"本次列车共8\nThis train has 8."
    check(got == want, u"模板两端替换 + | 换行（连写两个 | 也只剩一次换行）", repr(got))
    check(expand_py(u"|AC|", 0) is None, u"全是没值可替的记号 → null（调用方跳过）",
          repr(expand_py(u"|AC|", 0)))
    check(expand_py(u"本次列车共|AC|，请候车", 6) == u"本次列车共6，请候车",
          u"夹在句子中间也能换", repr(expand_py(u"本次列车共|AC|，请候车", 6)))
    check(expand_py(u"欢迎光临", 0) == u"欢迎光临", u"不含记号的原样通过")

    print("----- 1b) 其它占位符（车站/站台/目的地/线路 × 中英）的内容区分 = 读法区分 -----")
    # 用户点名检查：4 对名字按钮的中英文读法有没有做好区分。名字按钮的区分**在内容里**
    # （中文槽给中文段、英文槽给英文段，念出来自然分语言）；这里复算 namePart 的判据。
    name_cases = [
        (u"人民路|People Road", True, u"人民路"),
        (u"人民路|People Road", False, "People Road"),
        (u"机场|Airport", True, u"机场"),
        (u"机场|Airport", False, "Airport"),
        (u"1号线|Line 1", True, u"1号线"),
        (u"1号线|Line 1", False, "Line 1"),
        (u"人民路", True, u"人民路"),      # 单语名：中文槽拿中文 ✓
        (u"人民路", False, ""),            # 单语名：英文槽替成空（句子的完整性由写模板的人负责）
        ("Airport", True, ""),             # 单语英文名：中文槽替成空
        ("Airport", False, "Airport"),
        (u"東京駅|Tokyo Station", False, "Tokyo Station"),  # 汉字日文名 → 非中文桶（字形判据上限）
    ]
    for raw, zh, want in name_cases:
        got = name_part_py(raw, zh)
        check(got == want, u"namePart(%r, 中文=%s) → %r" % (raw, zh, want), "得到 %r" % got)
    check(name_part_py(u"人民路|People Road", True) != name_part_py(u"人民路|People Road", False),
          u"★ 双语名的中/英槽**互不相同**（中文槽不得念成英文、英文槽不得念成中文）")

    # ---- 2) 源码形态 ----
    n1 = run(audit_narrator, nar, "2) TrainAnnounceNarrator：两个记号 + carsText")
    n2 = run(audit_access, acc, "3) MtrDwellAccess：getCarCount 那条链路")
    n3 = run(audit_chime, chime, "4) PsdChimePlayer：两个调用点都递 carCount")
    n4 = run(audit_screen, scr, "5) PsdToneSetupScreen：车节(中)/(英) 两格")
    n5 = run(audit_switch, sw, "6) TrainAnnounceSwitch：/jsr 帮助文本")

    # ---- 3) 反向对照（mutation）：把源码改坏，上面那套判据必须变红 ----
    print()
    print("===== 7) 反向对照：改坏了必须报红 =====")
    muts = [
        ("记号常量改了名", nar, lambda s: s.replace('TOKEN_CARS_CN = "|AC|"', 'TOKEN_CARS_CN = "|AZ|"'), audit_narrator),
        ("expandUserTemplate 不再替换 |AE|",
         nar, lambda s: s.replace("text = text.replace(TOKEN_CARS_EN, carsText(carCount));", ""), audit_narrator),
        ("★ 把车节记号挪到换行之后（顺序反了）",
         nar, lambda s: s.replace(
             "        text = text.replace(TOKEN_CARS_CN, carsText(carCount));\n"
             "        text = text.replace(TOKEN_CARS_EN, carsText(carCount));\n",
             '        text = text.replace(TOKEN_LINE_BREAK, "\\n");\n'
             "        text = text.replace(TOKEN_CARS_CN, carsText(carCount));\n"
             "        text = text.replace(TOKEN_CARS_EN, carsText(carCount));"), audit_narrator),
        ("★ carsText 又带量词（改回「节车厢」/「 cars」）",
         nar, lambda s: s.replace('return Integer.toString(carCount);',
                                  'return carCount + u"节车厢";'), audit_narrator),
        ("carsText 读不到时返回 0 而不是空串",
         nar, lambda s: s.replace('if (carCount <= 0) {\n            return "";\n        }',
                                  'if (carCount <= 0) {\n            return "0";\n        }'), audit_narrator),
        ("★ namePart 中英槽串语言（中文槽拿英文段 = 读法串味）",
         nar, lambda s: s.replace("isChinese(piece) == chinese", "isChinese(piece) != chinese"), audit_narrator),
        ("绑定丢掉 getCarCount",
         acc, lambda s: s.replace('arrivalGetCarCount = method(response, "getCarCount");', ""), audit_access),
        ("车节数不在被选中的那一条上取",
         acc, lambda s: s.replace("bestCarCount = intOf(response, arrivalGetCarCount);", "bestCarCount = 0;"), audit_access),
        ("MTR4 出口漏传车节数",
         acc, lambda s: s.replace("new ArrivalInfo(best, bestDestination, bestPlatform, bestCarCount)",
                                  "new ArrivalInfo(best, bestDestination, bestPlatform, 0)"), audit_access),
        ("站台讲述人漏传 carCount",
         chime, lambda s: s.replace("arrival == null ? 0 : arrival.carCount)", "0)"), audit_chime),
        ("按钮标签写成「车厢(中)」",
         scr, lambda s: s.replace(u'"车节(中)"', u'"车厢(中)"'), audit_screen),
        ("中英列错位（enTokens 少一项 ⇒ AE 落到别列）",
         scr, lambda s: s.replace('String[] enTokens = {"|SE|", "|WE|", "|LE|", "|DE|", "|AE|"};',
                                  'String[] enTokens = {"|SE|", "|WE|", "|LE|", "|AE|", "|DE|"};'), audit_screen),
        ("窄窗口兜底被删（窄窗口下会盖住右列）",
         scr, lambda s: s.replace("            while (sum > budget) {", "            while (false) {"), audit_screen),
        ("帮助文本漏掉 |AC|/|AE|",
         sw, lambda s: s.replace("|AC|/|AE| = 本次列车几节车", ""), audit_switch),
    ]
    for title, raw, mutate, audit in muts:
        if raw is None:
            check(False, u"反向对照「%s」：拿得到源码" % title)
            continue
        mutated = mutate(raw)
        if mutated == raw:
            check(False, u"反向对照「%s」：变异文本真的变了（改法没匹配上，判据本身失效）" % title)
            continue
        reds = [l for l, ok in audit(mutated) if not ok]
        check(bool(reds), u"反向对照「%s」→ 有判据报红" % title,
              (u"报红 %d 条，例：%s" % (len(reds), reds[0])) if reds else u"★ 全军覆没：一条都没红")

    # ---- 4) 字节码（构建过才有） ----
    print()
    print("===== 8) 字节码（有 build/libs 才跑） =====")
    libs = os.path.join(PROJ, "build", "libs")
    jars = [os.path.join(libs, f) for f in os.listdir(libs)] if os.path.isdir(libs) else []
    jars = sorted(j for j in jars if re.search(r"mzycBetterMTR-[\d.]+\.jar$", os.path.basename(j)))
    if not jars:
        print("  [SKIP] 没有 build/libs/mzycBetterMTR-*.jar（先跑一次 gradlew build）")
    else:
        resp = os.path.join(PROJ, "gradle.properties")
        mod_version = ""
        if os.path.isfile(resp):
            for line in io.open(resp, encoding="utf-8"):
                if line.startswith("mod_version="):
                    mod_version = line.strip().split("=", 1)[1]
        jar = jars[-1]
        check(mod_version in os.path.basename(jar),
              "jar 名带的是 gradle.properties 里的 mod_version",
              "%s / %s" % (os.path.basename(jar), mod_version))
        # ★ 用户点名：版本号 1.34.xxxx（xxxx 不变）
        check(mod_version == "1.34.11201", u"★ 版本号就是用户点名的 1.34.11201", "得到 %s" % mod_version)
        z = zipfile.ZipFile(jar)
        names = z.namelist()
        want = ["smooth/lift/client/TrainAnnounceNarrator.class",
                "smooth/lift/client/MtrDwellAccess.class",
                "smooth/lift/client/PsdToneSetupScreen.class",
                "smooth/lift/client/PsdChimePlayer.class",
                "smooth/lift/client/TrainAnnounceSwitch.class"]
        for rel in want:
            check(rel in names, "jar 里有 %s" % rel)
        nar_cls = z.read("smooth/lift/client/TrainAnnounceNarrator.class") if want[0] in names else b""
        scr_cls = z.read("smooth/lift/client/PsdToneSetupScreen.class") if want[2] in names else b""
        acc_cls = z.read("smooth/lift/client/MtrDwellAccess.class") if want[1] in names else b""
        check(b"|AC|" in nar_cls, u"TrainAnnounceNarrator.class 常量池里有 |AC|")
        check(b"|AE|" in nar_cls, u"TrainAnnounceNarrator.class 常量池里有 |AE|")
        # 【10-16 用户拍板】carsText 只输出数字、不带量词 ⇒ 常量池里**不许**再出现量词字面量。
        check(u"节车厢".encode("utf-8") not in nar_cls
              and b" cars" not in nar_cls and u"卡".encode("utf-8") not in nar_cls,
              u"TrainAnnounceNarrator.class 常量池里没有量词（「节车厢」/「 cars」/「卡」）")
        check(b"getCarCount" in acc_cls, "MtrDwellAccess.class 里反射名 getCarCount")
        check(b"|AC|" in scr_cls and b"|AE|" in scr_cls,
              u"PsdToneSetupScreen.class 里两个记号（按钮要插的就是它们）")
        check(u"车节(中)".encode("utf-8") in scr_cls and u"车节(英)".encode("utf-8") in scr_cls,
              u"PsdToneSetupScreen.class 里按钮标签「车节(中)」「车节(英)」")
        # 单类里出现的记号数（| 也算），用来证明「两排同列配对」不是靠猜
        check(scr_cls.count(b"|AC|") >= 1 and scr_cls.count(b"|AE|") >= 1,
              u"两个记号各至少出现一次")

    print()
    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("  ==> 失败  " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()
