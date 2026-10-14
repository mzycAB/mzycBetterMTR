# -*- coding: utf-8 -*-
"""Script: fix_sounds_json.py

修复 `apply_replace_audio.py` 第一版正则删事件块留下的坏 JSON。

第一版错在哪（教训）：用「事件名 + 非贪婪星号」那种正则删块，
它只吃到**内层** `{ "name": ... }` 的那个 `}` 就收手了，
于是外层对象的收尾 `}\n  },` 被留了下来 ⇒ JSON 语法坏掉（`Expecting property name`）。
⇒ 结论：**结构化格式不要用正则删块**，解析成对象再按原格式重排。
（`sounds.json` 的格式是固定的三行式，重排能逐字节还原原来的排版。）

做法：从 `_repair_audio/backup/sounds.json.<工程名>` 读回原始内容 → 去掉 `audio/up` / `audio/down`
→ 按原格式（2 空格缩进、`sounds` 数组每项一行）重新写出，**行尾风格保持原样**。

用法：`python _tools/fix_sounds_json.py`
"""

import json
import os

WORKSPACE = r"C:/Users/user/Desktop/3"
REF = os.path.join(WORKSPACE, "1.20.4", "mzycBetterMTR-1.20.4")
BACKUP = os.path.join(REF, "_repair_audio", "backup")

PROJECTS = [
    "1.20.1/mzycBetterMTR-1.20.1",
    "1.20.4/mzycBetterMTR-1.20.4",
    "1.21.1/mzycBetterMTR-1.21.1",
    "Forge1.18.2/mzycBetterMTR-Forge-1.18.2",
    "Forge1.20.1/mzycBetterMTR-Forge-1.20.1",
]
DROP = ("audio/up", "audio/down")
JSON_REL = "src/main/resources/assets/smoothlift/sounds.json"


def render(keys, eol):
    """按工程原有的三行式排版重排（与备份文件逐字节可对齐）。"""
    lines = ["{"]
    for i, key in enumerate(keys):
        comma = "," if i < len(keys) - 1 else ""
        lines.append('  "%s": {' % key)
        lines.append('    "sounds": [')
        lines.append('      { "name": "smoothlift:%s", "stream": false }' % key)
        lines.append("    ]")
        lines.append("  }" + comma)
    lines.append("}")
    return eol.join(lines) + eol


def main():
    for proj in PROJECTS:
        name = os.path.basename(proj)
        src = os.path.join(BACKUP, "sounds.json." + name)
        raw = open(src, "rb").read()
        eol = "\r\n" if b"\r\n" in raw else "\n"
        data = json.loads(raw.decode("utf-8"))
        keys = [k for k in data if k not in DROP]
        out = os.path.join(WORKSPACE, proj, JSON_REL)
        with open(out, "wb") as fh:
            fh.write(render(keys, eol).encode("utf-8"))
        # 回读校验
        check = json.loads(open(out, encoding="utf-8").read())
        assert list(check) == keys, proj
        assert not any(k in check for k in DROP), proj
        print("%-40s keys=%d eol=%s  up/down 已移除" % (name, len(check), repr(eol)))


if __name__ == "__main__":
    main()
