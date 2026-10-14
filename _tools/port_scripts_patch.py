# -*- coding: utf-8 -*-
"""Script: port_scripts_patch.py

把 `1.20.4` 工程里对 `_tools/check-*.py` 的改动**逐 hunk 移植**到另外几个工程的同名脚本。

★ 为什么不是直接覆盖：各工程持有的脚本集不同、内容也略有分叉（例如 Forge 那边没有
  `check-lift-move-sound.py`）。直接覆盖会把目标工程**自己的**分叉抹掉。
  所以这里用 `1.20.4` 的 `HEAD → 工作区` 的 unified diff 作源，把每个 hunk 的
  「上下文 + 旧行 → 上下文 + 新行」当成一次字符串替换去目标文件里找。
  找不到就按「去掉上下文的裸块」再试一次；还找不到才记 FAIL（人工处理）。

★ 这是**一次性搬运脚本**，不是长期资产：搬完就应该没用了（下次改脚本时目标工程已经同步）。

用法：`python _tools/port_scripts_patch.py`
"""

import os
import re
import subprocess
import sys

WORKSPACE = r"C:/Users/user/Desktop/3"
REF = os.path.join(WORKSPACE, "1.20.4", "mzycBetterMTR-1.20.4")
TARGETS = [
    "1.20.1/mzycBetterMTR-1.20.1",
    "1.21.1/mzycBetterMTR-1.21.1",
    "Forge1.18.2/mzycBetterMTR-Forge-1.18.2",
    "Forge1.20.1/mzycBetterMTR-Forge-1.20.1",
]
# 要移植的脚本（相对工程根）；新增脚本直接复制
PATCH_FILES = [
    "_tools/check-lift-move-sound.py",
    "_tools/check-open-folder.py",
    "_tools/check-psd-align.py",
    "_tools/check-psd-predict.py",
    "_tools/check-lift-tone.py",
    "_tools/check-psd-split.py",
]
COPY_FILES = [
    "_tools/check-repair-patch.py",
]
FAILS = []


def git_diff(path):
    out = subprocess.run(["git", "-C", REF, "diff", "--unified=3", "HEAD", "--", path],
                         capture_output=True, text=True, encoding="utf-8").stdout
    return out


def hunks(diff_text):
    """把 unified diff 拆成 [(old_text, new_text), ...]（每个 hunk 的上下文+改动）。"""
    res, old, new = [], None, None
    for line in diff_text.splitlines():
        if line.startswith("@@"):
            if old is not None:
                res.append(("\n".join(old) + "\n", "\n".join(new) + "\n"))
            old, new = [], []
            continue
        if old is None:
            continue
        if line.startswith("---") or line.startswith("+++"):
            continue
        if line.startswith("-"):
            old.append(line[1:])
        elif line.startswith("+"):
            new.append(line[1:])
        elif line.startswith(" "):
            old.append(line[1:])
            new.append(line[1:])
        elif line.startswith("\\"):
            continue     # "\ No newline at end of file"
    if old is not None:
        res.append(("\n".join(old) + "\n", "\n".join(new) + "\n"))
    return res


def try_apply(text, old, new):
    """先整体替换；失败就把两边的上下文行逐步剥掉再试。返回 (新文本, 是否成功)。"""
    o, n = old, new
    for _ in range(4):
        if o and o in text:
            return text.replace(o, n, 1), True
        o_lines, n_lines = o.splitlines(True), n.splitlines(True)
        if len(o_lines) <= 1:
            break
        o, n = "".join(o_lines[1:]), "".join(n_lines[1:])
        if o in text:
            return text.replace(o, n, 1), True
        o_lines, n_lines = o.splitlines(True), n.splitlines(True)
        if len(o_lines) <= 1:
            break
        o, n = "".join(o_lines[:-1]), "".join(n_lines[:-1])
    return text, False


print("== 1) 逐 hunk 移植改动的脚本 ==")
for path in PATCH_FILES:
    diff = git_diff(path)
    hs = hunks(diff)
    print("%-40s hunk=%d" % (path, len(hs)))
    if not hs:
        continue
    for proj in TARGETS:
        dst = os.path.join(WORKSPACE, proj, path)
        if not os.path.isfile(dst):
            print("   %-38s 目标没有这个脚本，跳过" % os.path.basename(proj))
            continue
        text = open(dst, encoding="utf-8").read()
        done = 0
        for old, new in hs:
            if new.strip() and new.strip() in text and old.strip() not in text:
                done += 1          # 已经移植过
                continue
            text, ok = try_apply(text, old, new)
            if ok:
                done += 1
            else:
                FAILS.append("%s :: %s :: 一个 hunk 没对上（%r…）"
                             % (os.path.basename(proj), path, old.strip().splitlines()[0][:60]
                                if old.strip() else ""))
        open(dst, "w", encoding="utf-8", newline="").write(text)
        print("   %-38s 应用 %d/%d" % (os.path.basename(proj), done, len(hs)))

print()
print("== 2) 新增脚本直接复制 ==")
for path in COPY_FILES:
    src = os.path.join(REF, path)
    for proj in TARGETS:
        dst = os.path.join(WORKSPACE, proj, path)
        if not os.path.isdir(os.path.dirname(dst)):
            print("   %-38s 没有 _tools，跳过" % os.path.basename(proj))
            continue
        if os.path.isfile(dst) and open(dst, encoding="utf-8").read() == open(src, encoding="utf-8").read():
            print("   %-38s %s 已存在且一致" % (os.path.basename(proj), os.path.basename(path)))
            continue
        open(dst, "w", encoding="utf-8", newline="").write(open(src, encoding="utf-8").read())
        print("   %-38s %s 已复制" % (os.path.basename(proj), os.path.basename(path)))

print()
if FAILS:
    print("== 需要人工处理的 hunk：%d ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 脚本移植完成 ==")
