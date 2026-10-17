#!/usr/bin/env python3
"""把「语音播报 / 嘀嘀声」分界点的两个访问器补进 EscalatorAudioPlayer。

为什么用脚本而不是编辑器：这一段的插入两次「报成功但没落盘」，
用脚本来做就是幂等的 —— 重复跑只会报 already，不会插两遍。
"""
import io
import os
import sys

TARGET = "src/client/java/smooth/lift/client/EscalatorAudioPlayer.java"

ANCHOR_TAIL = ("        Integer cached = DURATION_MS.get(audioId);\n"
               "        return cached != null ? cached : measureAndCache(audioId, bytes);\n"
               "    }\n")

ACCESSORS = """
    /**
     * 【1.15】模组自带素材的「语音播报 / 嘀嘀声」分界点（ms）；没有这种结构返回 {@code -1}。
     *
     * <p>与 {@link #bundledDurationMs} 共用同一次解码与同一张表，所以调用它的代价只是查表。
     */
    static int bundledAnnounceSplitMs(String builtinKey) {
        bundledDurationMs(builtinKey); // 先保证已经解过（没解过这里会解一次），表里才有分界点
        Integer split = ANNOUNCE_SPLIT_MS.get("builtin:" + builtinKey);
        return split != null ? split : -1;
    }

    /** 【1.15】玩家导入素材的「语音播报 / 嘀嘀声」分界点（ms）；没有这种结构返回 {@code -1}。 */
    static int customAnnounceSplitMs(String audioId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return -1;
        }
        customDurationMs(audioId, bytes); // 同上：先保证解过
        Integer split = ANNOUNCE_SPLIT_MS.get(audioId);
        return split != null ? split : -1;
    }
"""

DISCONNECT_OLD = "        DECODE_FAILED.clear();\n        DURATION_MS.clear();\n        clearChainCache();\n"
DISCONNECT_NEW = ("        DECODE_FAILED.clear();\n        DURATION_MS.clear();\n"
                  "        ANNOUNCE_SPLIT_MS.clear();\n        clearChainCache();\n")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    path = os.path.join(root, TARGET)
    raw = io.open(path, "rb").read().decode("utf-8")
    nl = "\r\n" if "\r\n" in raw else "\n"
    anchor = ANCHOR_TAIL.replace("\n", nl)
    accessors = ACCESSORS.replace("\n", nl) if nl == "\n" else ACCESSORS.replace("\n", nl)

    changed = []
    if "bundledAnnounceSplitMs" in raw:
        print("  [skip] 访问器已在文件里")
    else:
        if anchor not in raw:
            print("  [fail] 找不到 customDurationMs 的锚点")
            return 1
        raw = raw.replace(anchor, anchor + accessors, 1)
        changed.append("accessors")

    if "ANNOUNCE_SPLIT_MS.clear();" in raw:
        print("  [skip] onDisconnect 的 clear 已在文件里")
    else:
        old, new = DISCONNECT_OLD.replace("\n", nl), DISCONNECT_NEW.replace("\n", nl)
        if raw.count(old) < 1:
            print("  [fail] 找不到 onDisconnect 的锚点")
            return 1
        raw = raw.replace(old, new, 1)
        changed.append("disconnect-clear")

    if not changed:
        print("  [ok] 无需改动（幂等）")
        return 0
    io.open(path, "wb").write(raw.encode("utf-8"))
    print("  [ok] 已插入：", ", ".join(changed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
