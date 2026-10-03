# -*- coding: utf-8 -*-
"""【10-01 第二阶段】SmoothLift.java 指令侧：
- 动作类指令的 sendSuccess 载荷 -> "指令执行成功"（若紧接着 return 0 则是 "指令执行失败"）
- 全部 sendFailure 载荷 -> "指令执行失败"
- 查询类（*Show / *Query）跳过，交第三阶段（只返回值）

用法： python _mk_ui_feedback_B.py [--apply]
"""
import re
import sys

P = "src/main/java/smooth/lift/SmoothLift.java"
APPLY = "--apply" in sys.argv

src = open(P, encoding="utf-8").read()
orig = src


def scan_parens(text, open_idx):
    j = open_idx
    depth = 0
    while True:
        c = text[j]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return j
        j += 1


def is_query(name):
    return name.endswith("Show") or "Query" in name


# ---- 方法块切分 ----
text = src
line_off = [0]
for ln in text.split("\n"):
    line_off.append(line_off[-1] + len(ln) + 1)

starts = []
for i, ln in enumerate(text.split("\n")):
    m = re.match(r"^    (?:private|public|protected)\s+.*?\s(\w+)\s*\(", ln)
    if m and "(" in ln and ln.rstrip().endswith("{"):
        starts.append((line_off[i], m.group(1)))
blocks = []
for idx, (off, name) in enumerate(starts):
    end = starts[idx + 1][0] if idx + 1 < len(starts) else len(text)
    blocks.append((name, off, end))

n_ok = n_fail = n_failcall = 0
touched_methods = []
new_src = []
prev = 0
for name, a, b in blocks:
    if is_query(name):
        continue
    body = text[a:b]
    if "sendSuccess(" not in body and "sendFailure(" not in body:
        continue
    touched_methods.append(name)
    calls = []
    for key in ("sendSuccess(", "sendFailure("):
        i = 0
        while True:
            k = body.find(key, i)
            if k < 0:
                break
            e = scan_parens(body, k + len(key) - 1) + 1
            calls.append((k, e, key))
            i = e
    calls.sort()
    enriched = []
    for (x, y, key) in calls:
        if key == "sendFailure(":
            ok = False
        else:
            rest = body[y:]
            nl = rest.split("\n")
            nxt = "\n".join(nl[:3])
            ok = not re.search(r"return\s+0\s*;", nxt)
        enriched.append((x, y, key, ok))
    newbody = body
    for (x, y, key, ok) in reversed(enriched):
        call = newbody[x:y]
        lits = []
        i = 0
        while True:
            k = call.find("Component.literal(", i)
            if k < 0:
                break
            e = scan_parens(call, k + len("Component.literal(") - 1) + 1
            lits.append((k, e))
            i = e
        word = "指令执行成功" if ok else "指令执行失败"
        nc = call
        for (k, e) in reversed(lits):
            nc = nc[:k] + 'Component.literal("%s")' % word + nc[e:]
        newbody = newbody[:x] + nc + newbody[y:]
        if ok:
            n_ok += 1
        else:
            n_failcall += 1
    new_src.append(text[prev:a])
    new_src.append(newbody)
    prev = b
new_src.append(text[prev:])
src = "".join(new_src)

print("动作类方法数:", len(touched_methods))
print("sendSuccess -> 成功:", n_ok)
print("sendFailure -> 失败:", n_failcall)
print("常量检查: 指令执行成功 x%d / 指令执行失败 x%d"
      % (src.count('"指令执行成功"'), src.count('"指令执行失败"')))
print("残留长句抽查（应只剩查询类）:")
for m in re.finditer(r'sendSuccess\(\(\) -> Component\.literal\(\s*"([^"]{0,40})', src):
    pass
if APPLY:
    open(P, "w", encoding="utf-8", newline="\n").write(src)
    print(">>> 已落盘  (原 %d -> 新 %d)" % (len(orig), len(src)))
else:
    print(">>> dry-run（未落盘）")
