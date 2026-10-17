#!/usr/bin/env python3
"""给 PsdChimePlayer 的 Tone record 补上 splitMs 字段（编辑器的整块插入在本会话里偶发不落盘，改用脚本）。

幂等：已经有 splitMs 参数就跳过。
"""
import io
import os
import sys

TARGET = "src/client/java/smooth/lift/client/PsdChimePlayer.java"

REC_OLD = ("private record Tone(ResourceLocation event, String customId, String builtinKey, "
           "int durationMs) {")
REC_NEW = ("private record Tone(ResourceLocation event, String customId, String builtinKey, "
           "int durationMs,\n                        int splitMs) {")

DOC_OLD = ("     * @param durationMs 素材总时长（ms）；{@code <= 0} = 没量出来"
           "（这一项就不剪头，照原样播）\n")
DOC_NEW = (DOC_OLD
           + "     * @param splitMs    【1.15】素材里「语音播报 / 嘀嘀声」的分界点（ms）；"
             "{@code <= 0} = 没有这种结构。\n"
             "     *                   见 {@link EscalatorAudioPlayer#bundledAnnounceSplitMs} —— "
             "用它把两段摆到各自的时刻\n"
             "     *                   （播报在开门时起播、嘀嘀在关门时对齐门程）。\n")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    path = os.path.join(root, TARGET)
    raw = io.open(path, "rb").read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"

    changed = []
    if "int durationMs,\n" in raw.replace("\r\n", "\n") and "int splitMs" in raw:
        print("  [skip] Tone 已经有 splitMs")
    else:
        old, new = REC_OLD.replace("\n", nl), REC_NEW.replace("\n", nl)
        if raw.count(old) != 1:
            print("  [fail] Tone record 锚点命中 %d 次（要求恰好 1 次）" % raw.count(old))
            return 1
        raw = raw.replace(old, new, 1)
        changed.append("record")

    if "splitMs()" in raw and "bundledAnnounceSplitMs" in raw:
        print("  [skip] resolveTone 已经填了 splitMs")
    else:
        print("  [warn] resolveTone 可能还没填 splitMs，请人工核对")
        return 1

    if "@param splitMs" in raw:
        print("  [skip] javadoc 已有 splitMs")
    else:
        old, new = DOC_OLD.replace("\n", nl), DOC_NEW.replace("\n", nl)
        if raw.count(old) != 1:
            print("  [fail] javadoc 锚点命中 %d 次" % raw.count(old))
            return 1
        raw = raw.replace(old, new, 1)
        changed.append("javadoc")

    if not changed:
        print("  [ok] 无需改动（幂等）")
        return 0
    io.open(path, "wb").write(raw.encode("utf-8"))
    print("  [ok] 已改动：", ", ".join(changed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
