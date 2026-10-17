# -*- coding: utf-8 -*-
"""按门统计「到站播报(midium) / 进站报站(arrive)」的排程与播放，并反推玩家位置。

用法：python _tools/_analyze_psd_announce.py <latest.log>
"""
import io
import re
import sys
import math
import collections

PATH = sys.argv[1] if len(sys.argv) > 1 else "LOG4/latest.log"

ts = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\]")
door = re.compile(r"门 @\[(-?\d+),(-?\d+),(-?\d+)\]")
run = re.compile(r"串 @\[(-?\d+),(-?\d+),(-?\d+)\]")
at = re.compile(r"@\[(-?\d+),(-?\d+),(-?\d+)\]")
dist = re.compile(r"距玩家 ([\d.]+) 格")

sched = collections.defaultdict(list)      # door -> [秒]
midium = collections.defaultdict(list)     # door -> [(秒, 距玩家?)]
arrive = collections.defaultdict(list)     # door -> [秒]
runs = collections.Counter()               # 串锚点
events = []                                # (秒, 文本)

with io.open(PATH, "r", encoding="utf-8", errors="replace") as f:
    for raw in f:
        if "PsdChime" not in raw:
            continue
        m = ts.match(raw)
        if not m:
            continue
        sec = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
        body = raw.strip()
        rm = run.search(body)
        if rm:
            runs[(int(rm.group(1)), int(rm.group(2)), int(rm.group(3)))] += 1
        dm = door.search(body)
        if dm:
            key = (int(dm.group(1)), int(dm.group(2)), int(dm.group(3)))
            if "排到站播报" in body:
                sched[key].append(sec)
            if "到站播报" in body and "→" in body:
                d = dist.search(body)
                midium[key].append((sec, float(d.group(1)) if d else None))
        am = re.search(r"进站报站 @\[(-?\d+),(-?\d+),(-?\d+)\]", body)
        if am:
            arrive[(int(am.group(1)), int(am.group(2)), int(am.group(3)))].append(sec)
        if "距玩家" in body:
            events.append((sec, body))

print("=== 认到的「串」锚点 ===")
for k, n in sorted(runs.items(), key=lambda kv: (kv[0][2], kv[0][0])):
    print("   ", k, "x", n)

print()
print("=== 到站播报 midium：排程 / 播放 / 当时距玩家 ===")
allk = sorted(set(sched) | set(midium), key=lambda p: (p[2], p[0]))
for k in allk:
    ds = [d for (_, d) in midium[k] if d is not None]
    print("    %-16s 排程 %d 次  播放 %d 次  距离 %s"
          % (k, len(sched[k]), len(midium[k]), sorted(set(ds))))

print()
print("=== 进站报站 arrive：播放点 ===")
for k in sorted(arrive, key=lambda p: (p[2], p[0])):
    print("   ", k, "播放", len(arrive[k]), "次", arrive[k])

print()
print("=== 同一秒里所有「距玩家」观测（按秒聚合，可反推玩家位置）===")
bysec = collections.defaultdict(list)
for sec, body in events:
    dm = door.search(body)
    d = dist.search(body)
    if dm and d:
        bysec[sec].append(((int(dm.group(1)), int(dm.group(2)), int(dm.group(3))),
                           float(d.group(1))))
for sec in sorted(bysec):
    rows = bysec[sec]
    hh, mm, ss = sec // 3600, (sec % 3600) // 60, sec % 60
    print("  %02d:%02d:%02d  n=%d  %s"
          % (hh, mm, ss, len(rows),
             "  ".join("%s=%s" % (r[0], r[1]) for r in rows[:8])))
