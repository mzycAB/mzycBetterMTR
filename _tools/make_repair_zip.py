# -*- coding: utf-8 -*-
"""Script: make_repair_zip.py

生成补丁包 `Repair.zip`：把被移除的那 6 段**外部来源**音频（`up` / `down` / `liftmusic` /
`dooropen` / `doorclose` / `mdoorclose`）打成 **WinZip AES-256** 加密的 zip。

★ 为什么是 AES-256 而不是普通 ZIP 加密：普通 ZIP（PKWARE traditional / ZipCrypto）
  对 OGG 等于没加密 —— OGG 文件头是公开已知的（`OggS` + `\\x01vorbis` + 采样率…），
  12 字节已知明文就够 `bkcrack` 反推出密码。AES-256（AE-2）没有这个问题。

★ 密码与 `RepairPatch.java` 的 `LOCK` 数组**必须成对**：本脚本是**唯一真值**，
  改动密码后要把打印出来的 `LOCK` 覆盖进 Java（脚本末尾会打印可直接粘贴的那一行）。

★ 依赖：`pyzipper`（本机 venv 里已装；它内部用 pycryptodomex 做 AES）。

用法：
    python _tools/make_repair_zip.py [输出路径]
    （默认 `<工作区根>/Repair.zip`，同时留一份在 `_repair_audio/Repair.zip`）
"""

import os
import sys

import pyzipper

# ★★ 密码（20 字符：大小写字母 + 数字 + 特殊符号）—— 与 RepairPatch.LOCK 成对
PASSWORD = "ip3V2$ylypxA.!O:-3l6"
LOCK_SEED = 0x5A17

REF = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 工程在 <工作区根>/<版本目录>/<工程目录> 下 ⇒ 再上两级才是工作区根
WORKSPACE = os.path.dirname(os.path.dirname(REF))
SRC = os.path.join(REF, "_repair_audio", "original")

# zip 里的条目名 = 内置素材键 + .ogg（RepairPatch 按这个名找）
KEYS = ["up", "down", "liftmusic", "dooropen", "doorclose", "mdoorclose"]


def java_lock(password):
    """与 RepairPatch.password() 完全同式：32 位回绕 + 无符号右移 16 取低 8 位。"""
    state = LOCK_SEED
    out = []
    for ch in password:
        state = (state * 1103515245 + 12345) & 0xFFFFFFFF
        out.append(ord(ch) ^ ((state >> 16) & 0xFF))
    return out


def java_lock_from(lock):
    """反推（自检用）。"""
    state = LOCK_SEED
    out = ""
    for v in lock:
        state = (state * 1103515245 + 12345) & 0xFFFFFFFF
        out += chr(v ^ ((state >> 16) & 0xFF))
    return out


def main():
    if len(sys.argv) > 1:
        out_path = os.path.abspath(sys.argv[1])
    else:
        out_path = os.path.join(WORKSPACE, "Repair.zip")

    lock = java_lock(PASSWORD)
    assert java_lock_from(lock) == PASSWORD, "LOCK 反推不一致"
    assert len(PASSWORD) == 20, "密码必须 20 个字符"
    has_alpha = any(c.isalpha() for c in PASSWORD)
    has_digit = any(c.isdigit() for c in PASSWORD)
    has_symbol = any(not c.isalnum() for c in PASSWORD)
    assert has_alpha and has_digit and has_symbol, "密码必须同时含字母/数字/特殊符号"

    if os.path.exists(out_path):
        os.remove(out_path)
    with pyzipper.AESZipFile(out_path, "w",
                             compression=pyzipper.ZIP_DEFLATED,
                             encryption=pyzipper.WZ_AES) as zf:
        zf.setpassword(PASSWORD.encode("utf-8"))
        zf.setencryption(pyzipper.WZ_AES, nbits=256)
        for key in KEYS:
            src = os.path.join(SRC, key + ".ogg")
            zf.write(src, key + ".ogg")
            print("  + %-18s %8d 字节" % (key + ".ogg", os.path.getsize(src)))

    size = os.path.getsize(out_path)
    print("== 写出 %s（%d 字节）" % (out_path, size))

    # 自检：用 pyzipper 读回来，逐条比对字节
    with pyzipper.AESZipFile(out_path) as zf:
        zf.setpassword(PASSWORD.encode("utf-8"))
        names = sorted(zf.namelist())
        assert names == sorted(k + ".ogg" for k in KEYS), names
        for key in KEYS:
            got = zf.read(key + ".ogg")
            want = open(os.path.join(SRC, key + ".ogg"), "rb").read()
            assert got == want, key
    print("== 自检通过：AES-256 / %d 条目 / 内容逐字节一致" % len(KEYS))
    print("   密码 = %s" % PASSWORD)
    print("   Java LOCK = { %s }" % ", ".join("0x%02X" % v for v in lock))

    # 再留一份在工程里（便于和 jar 一起归档）
    copy = os.path.join(REF, "_repair_audio", "Repair.zip")
    if os.path.abspath(copy) != out_path:
        open(copy, "wb").write(open(out_path, "rb").read())
        print("   另存一份 -> %s" % copy)


if __name__ == "__main__":
    main()
