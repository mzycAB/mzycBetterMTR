# -*- coding: utf-8 -*-
"""Script: patch_repair_gate_docs_and_checks.py

【2026-10-15 第二轮 · 收尾】把「补丁文件夹门禁」那段**注释**与**回归判据**补齐。

做三件事（全部幂等、按原行尾写回）：

  1. `SmoothLift.java` 的 `serverImportRepair` 方法头注释 —— 补上一句「文件夹里 zip 多于一个
     ⇒ 什么都不做、也不给提示」；
  2. `_tools/check-repair-patch.py`（5 个工程里**逐字节相同**，一起改）：
     · §2 那句判语的文案（不再写死 `MBM_Repair/Repair.zip`）；
     · 新增一节「门禁」：`IMPORT_SILENT` / `ZIP_EXT` / `patchZips` / 大小写不敏感 /
       **三态决策**（>1 静默、0 失败、==1 导入）/ `serverImportRepair` 的静默分支必须在
       `noteUiResult` **之前** return（这才叫「不给任何报错提示」）；
     · 旧的「反向对照」那节顺延成 §10，并追加两条针对门禁的反向对照；
     · docstring 里补一条判据说明；
  3. `RepairCrypto.java` 的类注释 —— 补一句「未加密的普通 zip 也走这个读法」（玩家自制包的入口）。

为什么：这三处都是「错了**不会有任何报错**」的地方 —— 门禁判错只是多/少一句提示，
注释说反了下一个人会按错的口径改。所以注释与判据必须跟着代码一起改。

用法：`python _tools/patch_repair_gate_docs_and_checks.py`
"""

import os
import sys

WORKSPACE = r"C:/Users/user/Desktop/3"
REF = "1.20.4/mzycBetterMTR-1.20.4"
MAIN_CLS_REL = "src/main/java/smooth/lift/SmoothLift.java"
CRYPTO_REL = "src/main/java/smooth/lift/RepairCrypto.java"
CHECK_REL = "_tools/check-repair-patch.py"

PROJECTS = [
    "1.20.1/mzycBetterMTR-1.20.1",
    "1.20.4/mzycBetterMTR-1.20.4",
    "1.21.1/mzycBetterMTR-1.21.1",
    "Forge1.18.2/mzycBetterMTR-Forge-1.18.2",
    "Forge1.20.1/mzycBetterMTR-Forge-1.20.1",
]

# ---------------------------------------------------------------- 1) SmoothLift 注释

DOC_OLD = """     * 【补丁】**服务端侧**：把 `<存档>/MBM_Repair/Repair.zip` 里的音频导入存档。"""

DOC_NEW = """     * 【补丁】**服务端侧**：把补丁文件夹（`<存档>/MBM_Repair`）里那个 zip 里的音频导入存档。
     *
     * <p>★ 2026-10-15 起规则改了（用户点名）：那个 zip **名字不限、也不需要密码** ——
     * 文件夹里**正好一个** `.zip` 才导入；**多于一个** ⇒ 什么都不做、也不给任何提示
     * （见 {@link RepairStore#IMPORT_SILENT}）；**一个都没有** ⇒ 失败（原行为）。"""

# ---------------------------------------------------------------- 2) RepairCrypto 注释

CRYPTO_OLD = """ * <h2>加密格式（WinZip AES-256，AE-2）</h2>"""

CRYPTO_NEW = """ * <p>★ 2026-10-15 追加：**玩家自制（未加密）的普通 zip 也走这个读法** ——
 * {@link #readAesZip} 里 {@code strength == 0} 那一支就是它（`method` 是 0/8 时直接 inflate）。
 * 所以「玩家把自己录的 `doorclose.ogg` 压进 zip 就能当补丁包」这件事**不需要新增解析代码**，
 * 只是补了一条「补丁文件夹里 zip 必须正好一个」的门禁（见 {@code RepairStore#importFromSave}）。
 *
 * <h2>加密格式（WinZip AES-256，AE-2）</h2>"""

# ---------------------------------------------------------------- 3) 回归脚本

CHK_MSG_OLD = """check("Files.readAllBytes" in STORE and "MBM_Repair" in STORE,
      "RepairStore 只**读**存档目录下的 MBM_Repair/Repair.zip")"""

CHK_MSG_NEW = """check("Files.readAllBytes" in STORE and "MBM_Repair" in STORE,
      "RepairStore 只**读**存档目录下的 MBM_Repair 文件夹（zip 本身不改、不落盘）")"""

CHK_DOC_OLD = """9. **反向对照**：拿掉「补丁优先」/ 把 `repairAssets` 换成 `audioLibrary` ⇒ 判据当场变红。"""

CHK_DOC_NEW = """9. **反向对照**：拿掉「补丁优先」/ 把 `repairAssets` 换成 `audioLibrary` ⇒ 判据当场变红。
10. ★★ **门禁（2026-10-15 用户点名）**：补丁文件夹里 zip **正好一个**才导入；
    **> 1** ⇒ `IMPORT_SILENT`（什么都不做、**连失败都不记** ⇒ 玩家看不到「UI执行失败」）；
    **0 个** ⇒ 失败（保持原行为）；扩展名大小写不敏感、只数普通文件。"""

CHK_S9_OLD = """print("===== 9) 反向对照（判据必须会红）=====")"""

CHK_S9_NEW = '''print("===== 9) ★★ 门禁：补丁文件夹里 zip **正好一个**才导入（2026-10-15 用户点名）=====")
# 用户原话：「如果补丁文件夹（MBM_Repair）里的补丁压缩包大于 1 个就在玩家按下『导入补丁』
# 按钮之后不执行任何操作，也不给任何报错提示，只有补丁文件夹里的补丁压缩包数量为 1 时
# 才可以导入补丁」＋（问过）「0 个 = 记一次失败（保持原行为）」「任意 .zip 都算，名字不限」。
store_t = tight(STORE)
check("IMPORT_SILENT=-1" in store_t,
      "★★ RepairStore 有 IMPORT_SILENT = -1（**静默**哨兵 —— 调用方靠它把「静默」与「失败」分开；"
      "没有它就只能给玩家多回一句「UI执行失败」，那正是用户不要的提示）")
check('ZIP_EXT=".zip"' in store_t, "认的扩展名写成常量 .zip（别在几处各写一遍字面量）")
check("patchZips(ServerLevel level)" in STORE,
      "新增 patchZips(level)：列出补丁文件夹里**直接放着**的 .zip（只这一层、不递归）")
check("toLowerCase(Locale.ROOT)" in STORE,
      "★ 扩展名**大小写不敏感**（玩家的包经常叫 .ZIP —— 不处理就是「放进去没反应」且不报错）")
check("Files.list(" in STORE and "filter(Files::isRegularFile)" in STORE,
      "只数**普通文件**（子目录不算补丁包）")
gate_body = strip_comments(body_of(STORE, "public static int importFromSave(ServerLevel level)"))


def gate_ok(body):
    """★ 这条判据**只有这一个实现**（下面的反向对照直接调它）。"""
    i_silent = body.find("zips.size() > 1")
    i_read = body.find("Files.readAllBytes")
    i_exact = body.find("zips.get(0)")
    return (i_silent >= 0 and "return IMPORT_SILENT;" in body
            and 0 <= i_silent < i_read          # ★ 门禁在读包**之前**：读都没读就返回了
            and "zips.isEmpty()" in body        # 0 个 ⇒ 失败（保持原行为）
            and i_exact > i_silent)             # == 1 ⇒ 导入那一个


check(gate_ok(gate_body),
      "★★ 门禁三态：>1 ⇒ IMPORT_SILENT（而且是在**读包之前**就返回）/ 0 ⇒ 失败 / ==1 ⇒ 导入那一个")
imp_body = strip_comments(body_of(MAIN_CLS, "public static boolean serverImportRepair(ServerPlayer player)"))


def silent_ok(body):
    """★ 用户点名的「不给任何报错提示」= 静默分支必须在 noteUiResult **之前** return。"""
    i = body.find("RepairStore.IMPORT_SILENT")
    j = body.find("noteUiResult")
    if i < 0 or j < 0 or j < i:
        return False
    k = body.find("return false;", i)
    return 0 < k < j


check(silent_ok(imp_body),
      "★★★ serverImportRepair 认得哨兵，并在 noteUiResult **之前** return ⇒ 连失败都不记"
      "（一记失败，玩家退出界面就会收到「UI执行失败」，正是用户点名不要的那句提示）")
check("patchZips" not in MAIN_CLS,
      "★ 文件夹遍历只住在 RepairStore 里（服务端入口不许自己再列一遍目录）")

print()
print("===== 10) 反向对照（判据必须会红）=====")'''

CHK_MUT_OLD = """if FAILS:"""

CHK_MUT_NEW = """# ---- 10b) 门禁那两条的反向对照（判据必须真的在判代码）----
check(not gate_ok(gate_body.replace("zips.size() > 1", "zips.size() > 2")),
      "★ 反向对照：把门禁的 `> 1` 改成 `> 2` ⇒ §9 那条判据当场变红（它真的在判这个常量）")
check(not gate_ok(gate_body.replace("return IMPORT_SILENT;", "return 0;")),
      "★ 反向对照：静默哨兵换成 0 ⇒ 同一判据变红（「静默」与「失败」必须分得开）")
check(not silent_ok(imp_body.replace("imported == RepairStore.IMPORT_SILENT", "false")),
      "★ 反向对照：把静默分支的条件抹掉 ⇒ 「不给提示」那条判据变红")

if FAILS:"""


def read_norm(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    bom = raw.startswith(b"\xef\xbb\xbf")
    body = raw[3:] if bom else raw
    text = body.decode("utf-8")
    return text.replace("\r\n", "\n"), bom, ("\r\n" in text), text.endswith("\n")


def write_norm(path, text, bom, crlf, ends):
    text = text.replace("\r\n", "\n")
    text = text if ends else text.rstrip("\n")
    if ends and not text.endswith("\n"):
        text += "\n"
    data = text.encode("utf-8")
    if crlf:
        data = data.replace(b"\n", b"\r\n")
    if bom:
        data = b"\xef\xbb\xbf" + data
    with open(path, "wb") as fh:
        fh.write(data)


def sub_once(path, pairs, log, tag):
    text, bom, crlf, ends = read_norm(path)
    orig = text
    for what, old, new in pairs:
        if new in text and old not in text:
            log.append("  [skip] %s: %s（已是新版）" % (tag, what))
            continue
        n = text.count(old)
        if n != 1:
            log.append("  [FAIL] %s: %s（旧文本出现 %d 次，期望 1 次 —— 未改动）" % (tag, what, n))
            return False
        text = text.replace(old, new)
        log.append("  [ok]   %s: %s" % (tag, what))
    if text != orig:
        write_norm(path, text, bom, crlf, ends)
    return True


def main():
    ok = True
    for proj in PROJECTS:
        root = os.path.join(WORKSPACE, proj)
        if not os.path.isdir(root):
            print("!! 缺工程 %s" % root)
            sys.exit(1)
        log = []
        ok &= sub_once(os.path.join(root, MAIN_CLS_REL),
                       [("方法头注释", DOC_OLD, DOC_NEW)], log, "SmoothLift")
        ok &= sub_once(os.path.join(root, CRYPTO_REL),
                       [("类注释加「未加密 zip 也走这条」", CRYPTO_OLD, CRYPTO_NEW)], log, "RepairCrypto")
        ok &= sub_once(os.path.join(root, CHECK_REL),
                       [("§2 文案", CHK_MSG_OLD, CHK_MSG_NEW),
                        ("docstring 补判据 10", CHK_DOC_OLD, CHK_DOC_NEW),
                        ("新增 §9 门禁 + §10 反向对照", CHK_S9_OLD, CHK_S9_NEW),
                        ("§10b 门禁反向对照", CHK_MUT_OLD, CHK_MUT_NEW)], log, "check-repair-patch")
        print("== %s" % os.path.basename(proj))
        for line in log:
            print(line)
    print("== 完成（%s）" % ("全部成功" if ok else "★ 有失败项，见上面 [FAIL]"))
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
