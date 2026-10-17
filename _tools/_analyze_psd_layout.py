#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PSD 版图还原（取证脚本，不参与回归）：把一份 log 里出现过的**门坐标 / 串锚点 / 站台 id**
分组列出来，用来判断「一个站台有几扇门、是否被缺口切成几段、哪几段在射程外」。

用法：
    python _analyze_psd_layout.py <latest.log>

【1.27】就是这么定案的：同一站台 id 下打出多行「认到 MTR 站台」⇒ 那些是**不同的连通串**
（一个串只打一行），而 z=72 那 12 扇全程只打出一行 ⇒ 它是真·单串。
"""
import re
import sys
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else "../LOG5/latest.log"
txt = open(path, "r", encoding="utf-8", errors="replace").read().splitlines()

POS = re.compile(r"@\[(-?\d+),(-?\d+),(-?\d+)\]")

doors = defaultdict(int)          # (x,y,z) -> 出现次数
run_anchor_pairs = []             # (time, door_pos, platform_id)
events = []                       # (time, kind, text)

for line in txt:
    if "PsdChime" not in line:
        continue
    t = re.match(r"\[(\d\d:\d\d:\d\d)\]", line)
    t = t.group(1) if t else "??"
    if "认到 MTR 站台" in line:
        m = POS.search(line)
        pid = re.search(r"id=(\d+)", line)
        if m and pid:
            run_anchor_pairs.append((t, tuple(int(g) for g in m.groups()), pid.group(1)))
    if "→ 播放" in line or "排到站播报" in line:
        m = POS.search(line)
        kind = "midium" if "midium" in line else ("arrive" if "arrive" in line else "open/close")
        events.append((t, kind, line.strip()))
    for m in re.finditer(r"门 @\[(-?\d+),(-?\d+),(-?\d+)\]", line):
        doors[tuple(int(g) for g in m.groups())] += 1

print("=" * 78)
print("一、门坐标（按 y,z 分组）—— 同一行 = 同一条线路")
print("=" * 78)
groups = defaultdict(list)
for (x, y, z), n in doors.items():
    groups[(y, z)].append(x)
for (y, z), xs in sorted(groups.items()):
    xs = sorted(set(xs))
    gaps = [(a, b) for a, b in zip(xs, xs[1:]) if b - a > 1]
    print("y=%-5d z=%-5d 共 %2d 扇: %s" % (y, z, len(xs), xs))
    if gaps:
        print("              缺口(相邻差>1): %s" % ["%d→%d" % g for g in gaps])

print()
print("=" * 78)
print("二、每条「线」的 z/x 恒定方向")
print("=" * 78)
for (y, z), xs in sorted(groups.items()):
    print("y=%d z=%d  ⇒ 沿 %s 轴延伸" % (y, z, "X" if len(set(xs)) > 1 else "Z"))

print()
print("=" * 78)
print("三、认站台（每个 runKey 一次）—— 同一站台 id 出现几次就有几个串")
print("=" * 78)
by_pid = defaultdict(list)
for t, p, pid in run_anchor_pairs:
    if p not in [q for _, q in by_pid[pid]]:
        by_pid[pid].append((t, p))
for pid, items in sorted(by_pid.items()):
    print("站台 id=%s  ⇒ %d 个不同的「最近门」（= 串数下限）" % (pid, len(items)))
    for t, p in items:
        print("     %s  最近门 @%s" % (t, list(p)))

print()
print("=" * 78)
print("四、实际起播事件")
print("=" * 78)
for t, kind, line in events:
    if kind == "open/close":
        continue
    print("%s [%s] %s" % (t, kind, line[:150]))
