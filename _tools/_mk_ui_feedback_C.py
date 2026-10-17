# -*- coding: utf-8 -*-
"""【10-01 第三阶段】查询类指令（*Show / *Query）-> 只返回值。

取值口径（用户选定「极简值」）：
  数值 -> 数字；开关 -> 1/0；素材 -> 素材名；多值 -> 逗号并列；缺值 -> 无。

用法： python _mk_ui_feedback_C.py [--apply]
"""
import re
import sys

P = "src/main/java/smooth/lift/SmoothLift.java"
APPLY = "--apply" in sys.argv

TABLE = {
    "futiShow(CommandContext<CommandSourceStack> context)": [
        "EscalatorSpeedData.format(global)",
        "EscalatorSpeedData.format(speed)",
    ],
    "jietiShow(CommandContext<CommandSourceStack> context)": [
        "EscalatorSpeedData.format(global)",
        "EscalatorSpeedData.format(step)",
    ],
    "futiMusicShow(CommandContext<CommandSourceStack> context)": [
        "audioLabel(defaultAudio)",
        "\"无\"",
        "audioLabel(id)",
    ],
    "futiHelpMusicShow(CommandContext<CommandSourceStack> context)": [
        "helpAudioLabel(defaultIn) + \", \" + helpAudioLabel(defaultOut)",
        "helpAudioLabel(inId) + \", \" + helpAudioLabel(outId)",
    ],
    "futiLoudShow(CommandContext<CommandSourceStack> context)": [
        "\"\" + global",
        "\"\" + volume",
    ],
    "futiHelpShow(CommandContext<CommandSourceStack> context)": [
        "global ? \"1\" : \"0\"",
        "enabled ? \"1\" : \"0\"",
    ],
    "futiHelpLoudShow(CommandContext<CommandSourceStack> context)": [
        "\"\" + global",
        "\"\" + volume",
    ],
    "futiRoundShow(CommandContext<CommandSourceStack> context)": [
        "\"\" + global",
        "\"\" + round",
    ],
    "futiHelpRoundShow(CommandContext<CommandSourceStack> context)": [
        "\"\" + global",
        "\"\" + round",
    ],
    "futiHelpSpeedShow(CommandContext<CommandSourceStack> context)": [
        "globalIn + \", \" + globalOut",
        "in + \", \" + out",
    ],
    "liftHelpShow(CommandContext<CommandSourceStack> context)": [
        "(enabled ? \"1\" : \"0\") + \", \" + EscalatorSpeedData.format(speed)",
    ],
    "liftHelpLoudShow(CommandContext<CommandSourceStack> context)": [
        "volume + \", \" + (enabled ? \"1\" : \"0\")",
    ],
    "liftHelpRoundShow(CommandContext<CommandSourceStack> context)": [
        "\"\" + round",
    ],
    "liftToneShow(CommandContext<CommandSourceStack> context, String literal, String which)": [
        "(enabled ? \"1\" : \"0\") + \", \" + liftToneAudioLabel(audio)",
    ],
    "pbmMusicShow(CommandContext<CommandSourceStack> context)": [
        "(EscalatorSpeedManager.isPsdHelpEnabled(level) ? \"1\" : \"0\") + \", \""
        " + EscalatorSpeedManager.getPsdHelpVolume(level) + \", \""
        " + EscalatorSpeedManager.getPsdHelpRoundXz(level) + \", \""
        " + EscalatorSpeedManager.getPsdHelpRoundY(level)",
    ],
    "pbmMusicItemShow(CommandContext<CommandSourceStack> context, String which)": [
        "(enabled ? \"1\" : \"0\") + \", \" + psdToneAudioLabel(audio)",
    ],
    "pbmLoudShow(CommandContext<CommandSourceStack> context)": [
        "volume + \", \" + pbmVolumeText(level, \"open\") + \", \" + pbmVolumeText(level, \"close\")",
    ],
    "pbmItemLoudShow(CommandContext<CommandSourceStack> context, String which)": [
        "\"\" + v",
    ],
    "roundShow(CommandContext<CommandSourceStack> context, RoundKind kind)": [
        "round[0] + \", \" + round[1]",
    ],
    "pbmCloseWaitShow(CommandContext<CommandSourceStack> context)": [
        "\"\" + seconds",
    ],
    "pbmMidiumShow(CommandContext<CommandSourceStack> context)": [
        "(off ? \"无\" : id) + \", \" + seconds",
    ],
    "pbmArriveShow(CommandContext<CommandSourceStack> context)": [
        "(off ? \"无\" : id) + \", \" + (-seconds)",
    ],
    "pbmNarrateShow(CommandContext<CommandSourceStack> context)": [
        "EscalatorSpeedData.psdNarrateModeName(mode) + \", \" + (-seconds)",
    ],
    "dtPictureQuery(CommandContext<CommandSourceStack> context)": [
        "\"无\"",
        "current",
    ],
    "zhajiShow(CommandContext<CommandSourceStack> context)": [
        "zhajiAudioLabel(EscalatorSpeedManager.getZhajiToneAudio(level, \"in\")) + \", \""
        " + zhajiAudioLabel(EscalatorSpeedManager.getZhajiToneAudio(level, \"out\")) + \", \""
        " + EscalatorSpeedManager.getZhajiToneVolume(level, \"in\") + \", \""
        " + EscalatorSpeedManager.getZhajiToneVolume(level, \"out\")",
    ],
    "zhajiToneShow(CommandContext<CommandSourceStack> context, String which)": [
        "zhajiAudioLabel(EscalatorSpeedManager.getZhajiToneAudio(level, which)) + \", \""
        " + EscalatorSpeedManager.getZhajiToneVolume(level, which)",
    ],
    "zhajiLoudShow(CommandContext<CommandSourceStack> context)": [
        "EscalatorSpeedManager.getZhajiToneVolume(level, \"in\") + \", \""
        " + EscalatorSpeedManager.getZhajiToneVolume(level, \"out\")",
    ],
    "zhajiLoudShow(CommandContext<CommandSourceStack> context, String which)": [
        "\"\" + volume",
    ],
}

src = open(P, encoding="utf-8").read()


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


text = src
lines = text.split("\n")
line_off = [0]
for ln in lines:
    line_off.append(line_off[-1] + len(ln) + 1)

starts = []
for i, ln in enumerate(lines):
    m = re.match(r"^    (?:private|public|protected)\s+.*?\s(\w+)\s*\(", ln)
    if m and ln.rstrip().endswith("{"):
        sigfull = ln.strip().rstrip("{").strip()
        name = m.group(1)
        key = sigfull[sigfull.index(name + "("):]
        starts.append((line_off[i], key))

done = []
pieces = []
prev = 0
for idx, (off, sig) in enumerate(starts):
    if sig not in TABLE:
        continue
    end = starts[idx + 1][0] if idx + 1 < len(starts) else len(text)
    body = text[off:end]
    exprs = TABLE[sig]
    spans = []
    i = 0
    KEY = "() -> Component.literal("
    while True:
        k = body.find(KEY, i)
        if k < 0:
            break
        e = scan_parens(body, k + len(KEY) - 1) + 1
        spans.append((k, e))
        i = e
    assert len(spans) == len(exprs), "%s: 载荷数 %d != 表 %d" % (sig, len(spans), len(exprs))
    nb = body
    for (k, e), ex in reversed(list(zip(spans, exprs))):
        nb = nb[:k] + KEY + ex + ")" + nb[e:]
    pieces.append(text[prev:off])
    pieces.append(nb)
    prev = end
    done.append((sig, len(spans)))
pieces.append(text[prev:])
src = "".join(pieces)

print("改造的查询方法:", len(done))
for s, n in done:
    print("   %-70s %d 处" % (s[:70], n))
if APPLY:
    open(P, "w", encoding="utf-8", newline="\n").write(src)
    print(">>> 已落盘")
else:
    print(">>> dry-run（未落盘）")
