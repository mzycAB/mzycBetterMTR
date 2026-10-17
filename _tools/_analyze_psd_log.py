# -*- coding: utf-8 -*-
"""按门坐标聚合 PsdChime 日志（用法：python _tools/_analyze_psd_log.py <latest.log>）。

用来回答「走近了有的门有声、有的没声」：把「有过播放」与「只有 LEARN」两堆门分开，
再用同一秒里其它门的「距玩家 X 格」反推玩家位置，判断静音的门到底是不是在射程外。
"""
import io
import re
import collections

PATH = r"C:\Users\user\Desktop\2\LOG3\latest.log"

door_pat = re.compile(r"门 @\[(-?\d+),(-?\d+),(-?\d+)\]")
openp = re.compile(r"门 @\[(-?\d+),(-?\d+),(-?\d+)\] 开始开门")
closep = re.compile(r"门 @\[(-?\d+),(-?\d+),(-?\d+)\] 开始关门")
learnp = re.compile(r"门 @\[(-?\d+),(-?\d+),(-?\d+)\] 学到这一轮")
dist_pat = re.compile(r"距玩家 ([\d.]+) 格")
vol_pat = re.compile(r"音量 (\d+)")

events = collections.defaultdict(list)   # key -> list of (time, kind, extra)
lines = 0
with io.open(PATH, "r", encoding="utf-8", errors="replace") as f:
    for raw in f:
        if "PsdChime" not in raw:
            continue
        lines += 1
        m = door_pat.search(raw)
        if not m:
            continue
        key = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
        t = raw[1:9]
        if openp.search(raw):
            kind = "OPEN"
        elif closep.search(raw):
            kind = "CLOSE"
        elif learnp.search(raw):
            kind = "LEARN"
        else:
            continue
        d = dist_pat.search(raw)
        v = vol_pat.search(raw)
        events[key].append((t, kind,
                            float(d.group(1)) if d else None,
                            int(v.group(1)) if v else None))

print("PsdChime 行数 =", lines)
print("去重门数 =", len(events))
played = [k for k, v in events.items() if any(e[1] in ("OPEN", "CLOSE") for e in v)]
onlylearn = [k for k, v in events.items() if not any(e[1] in ("OPEN", "CLOSE") for e in v)]
print("有过播放(OPEN/CLOSE)的门 =", len(played))
print("只有 LEARN、从未播放的门 =", len(onlylearn))
print()
print("=== 只有 LEARN 从无播放的门 ===")
for k in sorted(onlylearn, key=lambda p: (p[2], p[0])):
    kinds = collections.Counter(e[1] for e in events[k])
    print("  ", k, dict(kinds))
print()
print("=== 播放过的门（按 z,x 排序）===")
for k in sorted(played, key=lambda p: (p[2], p[0])):
    kinds = collections.Counter(e[1] for e in events[k])
    ds = [e[2] for e in events[k] if e[2] is not None]
    vs = [e[3] for e in events[k] if e[3] is not None]
    print("  ", k, dict(kinds), "距离", ds, "音量", sorted(set(vs)))
