# -*- coding: utf-8 -*-
"""Script: apply_replace_audio.py

把 `gen_replace_audio.py` 合成的 4 段替换素材写进**全部 5 个工程**，并删掉 `up.ogg` / `down.ogg`。

逐工程做的事：
  1. `liftmusic.ogg` / `dooropen.ogg` / `doorclose.ogg` / `mdoorclose.ogg`
     ← `_repair_audio/replace/` 里的合成版本（覆盖）；
  2. `up.ogg` / `down.ogg` **删除**（用户点名：直梯上下楼语音提示音直接静音）；
  3. `assets/smoothlift/sounds.json` 里删掉 `audio/up` / `audio/down` 两个条目
     （★ 必须删条目而不是留条目少文件：留条目会让 `SoundManager` 在资源重载时
     报一条「找不到文件」的警告，且 `getSoundEvent()` 仍然返回非 null、播放端起播后才静默失败；
     删条目则 `getSoundEvent()` 直接返回 null，播放端能**明确**判成「内置素材不存在」）。
  4. 行尾风格原样保留（Forge1.20.1 那份是 CRLF）。

用法：`python _tools/apply_replace_audio.py`
"""

import json
import os
import shutil
import sys

WORKSPACE = r"C:/Users/user/Desktop/3"
REF = os.path.join(WORKSPACE, "1.20.4", "mzycBetterMTR-1.20.4")
GEN = os.path.join(REF, "_repair_audio", "replace")

PROJECTS = [
    "1.20.1/mzycBetterMTR-1.20.1",
    "1.20.4/mzycBetterMTR-1.20.4",
    "1.21.1/mzycBetterMTR-1.21.1",
    "Forge1.18.2/mzycBetterMTR-Forge-1.18.2",
    "Forge1.20.1/mzycBetterMTR-Forge-1.20.1",
]

REPLACE = ["liftmusic.ogg", "dooropen.ogg", "doorclose.ogg", "mdoorclose.ogg"]
DELETE = ["up.ogg", "down.ogg"]
DROP_EVENTS = ["audio/up", "audio/down"]

AUDIO_REL = "src/main/resources/assets/smoothlift/sounds/audio"
JSON_REL = "src/main/resources/assets/smoothlift/sounds.json"


def strip_events(text, events):
    """从 sounds.json 里删掉若干事件条目，并**按原排版重新写出**。

    ★★ 教训（第一版就在这里踩了）：**不要用正则删 JSON 的块**。
    `"<event>":\\s*\\{.*?\\}` 这种非贪婪写法只吃到**内层** `{ "name": ... }` 的 `}`，
    外层对象的收尾被留下 ⇒ JSON 语法坏掉（`Expecting property name`），而且要等到
    加载资源时才炸。结构化格式一律「解析成对象 → 改 → 按原格式重排」。
    sounds.json 的排版是固定的三行式，重排能逐字节还原。
    """
    data = json.loads(text)
    keys = [k for k in data if k not in events]
    eol = "\r\n" if "\r\n" in text else "\n"
    lines = ["{"]
    for i, key in enumerate(keys):
        comma = "," if i < len(keys) - 1 else ""
        lines.append('  "%s": {' % key)
        lines.append('    "sounds": [')
        lines.append('      { "name": "smoothlift:%s", "stream": false }' % key)
        lines.append("    ]")
        lines.append("  }" + comma)
    lines.append("}")
    return eol.join(lines) + eol, len(data) - len(keys)


def main():
    for proj in PROJECTS:
        root = os.path.join(WORKSPACE, proj)
        if not os.path.isdir(root):
            print("!! 缺工程 %s" % proj)
            sys.exit(1)
        print("== %s" % proj)
        # 1) 覆盖
        for name in REPLACE:
            src = os.path.join(GEN, name)
            dst = os.path.join(root, AUDIO_REL, name)
            shutil.copyfile(src, dst)
            print("   替换 %-16s %d 字节" % (name, os.path.getsize(dst)))
        # 2) 删除
        for name in DELETE:
            dst = os.path.join(root, AUDIO_REL, name)
            if os.path.isfile(dst):
                os.remove(dst)
                print("   删除 %-16s" % name)
        # 3) sounds.json（先备份到 _repair_audio/backup/，再删条目）
        path = os.path.join(root, JSON_REL)
        with open(path, "r", encoding="utf-8", newline="") as fh:
            text = fh.read()
        bak = os.path.join(REF, "_repair_audio", "backup", "sounds.json." + os.path.basename(root))
        os.makedirs(os.path.dirname(bak), exist_ok=True)
        if not os.path.exists(bak):
            with open(bak, "w", encoding="utf-8", newline="") as fh:
                fh.write(text)
        text, removed = strip_events(text, DROP_EVENTS)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(text)
        json.loads(text)  # 回读校验：必须是合法 JSON
        print("   sounds.json 删条目 %s（共 %d 条）" % (list(DROP_EVENTS), removed))
    print("== 完成")


if __name__ == "__main__":
    main()
