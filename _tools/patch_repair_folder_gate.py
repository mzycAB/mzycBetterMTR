# -*- coding: utf-8 -*-
"""Script: patch_repair_folder_gate.py

【2026-10-15 第二轮 · 5 个工程】「补丁文件夹里 zip 数量 == 1 才能导入，>1 静默不动」。

改动三处（**RepairStore.java 在 5 个工程里逐字节相同** ⇒ 改一份铺五份）：

  1. `src/main/java/smooth/lift/RepairStore.java` —— 以 **1.20.4 工程那份为准**覆盖全部 5 个：
     · 新增 `ZIP_EXT` / `IMPORT_SILENT(-1)`；
     · `zipPath()` → `folder()` + `patchZips()`（列出文件夹里直接放着的 `.zip`，大小写不敏感、按名排序）；
     · `importFromSave()` 顶部加门禁：`zips.size() > 1` ⇒ `IMPORT_SILENT`；`== 0` ⇒ 0（失败，原行为）；
       `== 1` ⇒ 导入那一个（不限名字、不限有没有密码）。
  2. `src/**/SmoothLift.java` 的 `serverImportRepair` —— 拿到 `IMPORT_SILENT` 时**直接 return，
     不调 `noteUiResult`**（一记失败，退出界面就会回「UI执行失败」= 用户点名不要的那句提示）。
  3. `src/**/RepairPatch.java` —— 把「读 Repair.zip」的注释与日志改成「读该文件夹里唯一那个 .zip」。

★ 行尾：这几个文件在 5 个工程里**混着 LF 和 CRLF**（SmoothLift 在 1.21.1 / Forge 两版是 CRLF，
  RepairPatch 在 1.20.4 / 1.21.1 是 CRLF）⇒ 一律「读进来归一成 LF、写回按原样还原」。
  按 LF 构造 pattern 去匹配 CRLF 文件会**静默漏掉**。
幂等：每处先判「新文本在不在 / 旧文本在不在」，重复跑不会二次改。
用法：`python _tools/patch_repair_folder_gate.py`
"""

import glob
import os
import shutil
import sys

WORKSPACE = r"C:/Users/user/Desktop/3"
REF = "1.20.4/mzycBetterMTR-1.20.4"
STORE_REL = "src/main/java/smooth/lift/RepairStore.java"
MAIN_CLS_REL = "src/main/java/smooth/lift/SmoothLift.java"
BAK = "_tools/_bak_1015b_gate"

PROJECTS = [
    "1.20.1/mzycBetterMTR-1.20.1",
    "1.20.4/mzycBetterMTR-1.20.4",
    "1.21.1/mzycBetterMTR-1.21.1",
    "Forge1.18.2/mzycBetterMTR-Forge-1.18.2",
    "Forge1.20.1/mzycBetterMTR-Forge-1.20.1",
]

# ---------------------------------------------------------------- SmoothLift 替换

IMPORT_OLD = """        int imported = RepairStore.importFromSave(level);
        if (imported > 0) {
            // 让在线客户端拿到补丁字节（分块同步，与音频库同一条通道）
            EscalatorSpeedManager.syncAudioToAll(player.server);
            LOGGER.info("[SmoothLift/Repair] 已导入并同步补丁素材 {} 段（{}）",
                    imported, RepairStore.keys(level));
        }
        noteUiResult(player, imported > 0);
        return imported > 0;"""

IMPORT_NEW = """        int imported = RepairStore.importFromSave(level);
        if (imported == RepairStore.IMPORT_SILENT) {
            // ★★ 用户点名（2026-10-15）：补丁文件夹里的补丁压缩包**大于 1 个** ⇒
            //    不执行任何操作、也不给任何报错提示。⇒ 这里**不许** noteUiResult：
            //    一记失败，玩家退出界面就会收到「UI执行失败」，那正是用户不要的那句提示。
            return false;
        }
        if (imported > 0) {
            // 让在线客户端拿到补丁字节（分块同步，与音频库同一条通道）
            EscalatorSpeedManager.syncAudioToAll(player.server);
            LOGGER.info("[SmoothLift/Repair] 已导入并同步补丁素材 {} 段（{}）",
                    imported, RepairStore.keys(level));
        }
        noteUiResult(player, imported > 0);
        return imported > 0;"""

RET_OLD = """     * @return 是否真的导进去了（0 段 = 失败：包不在 / 解不开 / 里面没有可用素材）"""

RET_NEW = """     * @return 是否真的导进去了（0 段 = 失败：没有包 / 解不开 / 里面没有可用素材；
     *         补丁文件夹里 zip 多于一个 ⇒ 直接返回 false 且**什么都不做、也不给提示**）"""

# ---------------------------------------------------------------- RepairPatch 替换

PATCH_DOC_OLD = """    /**
     * 「导入补丁」：请服务端去读 `<存档>/MBM_Repair/Repair.zip` 并**融入存档**。
     *
     * @return 请求有没有发出去（不在世界里 = 没发，界面会记失败）
     */"""

PATCH_DOC_NEW = """    /**
     * 「导入补丁」：请服务端去读补丁文件夹（`<存档>/MBM_Repair`）里**唯一那个 `.zip`** 并**融入存档**。
     *
     * <p>★ 2026-10-15 用户点名：那个 zip **名字不限、也不需要密码** —— 玩家自己录的 ogg 直接
     * 压进去就行（条目名用 {@code doorclose.ogg} 这种）。**缺哪个条目，哪个就回模组默认**；
     * 但文件夹里 zip **多于一个**时服务端**什么都不做、也不给任何提示**。
     *
     * @return 请求有没有发出去（不在世界里 = 没发，界面会记失败）
     */"""

PATCH_LOG_OLD = """        LOGGER.info("[SmoothLift/Repair] 已请服务端导入补丁（读 {} / {}）",
                RepairStore.FOLDER, RepairStore.ZIP_NAME);"""

PATCH_LOG_NEW = """        LOGGER.info("[SmoothLift/Repair] 已请服务端导入补丁（读 {} 文件夹里唯一那个 .zip）",
                RepairStore.FOLDER);"""


def read_norm(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    bom = raw.startswith(b"\xef\xbb\xbf")
    body = raw[3:] if bom else raw
    text = body.decode("utf-8")
    return text.replace("\r\n", "\n"), bom, ("\r\n" in text), text.endswith("\n")


def write_norm(path, text, bom, crlf, ends_eol):
    text = text.replace("\r\n", "\n")
    if ends_eol:
        if not text.endswith("\n"):
            text += "\n"
    else:
        text = text.rstrip("\n")
    data = text.encode("utf-8")
    if crlf:
        data = data.replace(b"\n", b"\r\n")
    if bom:
        data = b"\xef\xbb\xbf" + data
    with open(path, "wb") as fh:
        fh.write(data)


def sub_once(path, old, new, log, what):
    text, bom, crlf, ends = read_norm(path)
    if new in text and old not in text:
        log.append("  [skip] %s（已是新版）" % what)
        return True
    n = text.count(old)
    if n != 1:
        log.append("  [FAIL] %s（旧文本出现 %d 次，期望 1 次 —— 未改动）" % (what, n))
        return False
    write_norm(path, text.replace(old, new), bom, crlf, ends)
    log.append("  [ok]   %s" % what)
    return True


def find_client(path_root, name):
    """客户端类在 fabric 是 src/client/...、Forge 是 src/main/java/**/client/...。"""
    for pat in (("src", "client", "java", "smooth", "lift", "client"),
                ("src", "main", "java", "smooth", "lift", "client")):
        p = os.path.join(path_root, *pat, name)
        if os.path.isfile(p):
            return p
    hits = glob.glob(os.path.join(path_root, "src", "**", name), recursive=True)
    return hits[0] if hits else None


def backup(proj, rel):
    src = os.path.join(WORKSPACE, proj, rel)
    if not os.path.isfile(src):
        return
    dst = os.path.join(WORKSPACE, REF, BAK, os.path.basename(proj), rel.replace("/", "__"))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)


def main():
    ref_store = os.path.join(WORKSPACE, REF, STORE_REL)
    store_text, _, _, _ = read_norm(ref_store)          # 基准：1.20.4 那份（全 LF）
    ok_all = True
    for proj in PROJECTS:
        root = os.path.join(WORKSPACE, proj)
        if not os.path.isdir(root):
            print("!! 缺工程 %s" % root)
            sys.exit(1)
        log = []
        # --- 1) RepairStore：整份铺过去（行尾跟各工程自己原来的一致 = LF）---
        store = os.path.join(root, STORE_REL)
        backup(proj, STORE_REL)
        _, _, old_crlf, old_ends = read_norm(store)
        write_norm(store, store_text, False, old_crlf, old_ends)
        log.append("  [ok]   RepairStore.java 覆盖（%d 字节，crlf=%s）"
                   % (len(store_text.encode("utf-8")), old_crlf))
        # --- 2) SmoothLift.serverImportRepair ---
        main_cls = os.path.join(root, MAIN_CLS_REL)
        backup(proj, MAIN_CLS_REL)
        ok_all &= sub_once(main_cls, IMPORT_OLD, IMPORT_NEW, log, "SmoothLift: IMPORT_SILENT 分支")
        ok_all &= sub_once(main_cls, RET_OLD, RET_NEW, log, "SmoothLift: @return 注释")
        # --- 3) RepairPatch ---
        patch = find_client(root, "RepairPatch.java")
        if patch:
            backup(proj, os.path.relpath(patch, root))
            ok_all &= sub_once(patch, PATCH_DOC_OLD, PATCH_DOC_NEW, log, "RepairPatch: 「导入补丁」注释")
            ok_all &= sub_once(patch, PATCH_LOG_OLD, PATCH_LOG_NEW, log, "RepairPatch: 导入日志")
        else:
            log.append("  [FAIL] 找不到 RepairPatch.java")
            ok_all = False
        print("== %s" % os.path.basename(proj))
        for line in log:
            print(line)
    print("== 完成（%s）" % ("全部成功" if ok_all else "★ 有失败项，见上面 [FAIL]"))
    if not ok_all:
        sys.exit(1)


if __name__ == "__main__":
    main()
