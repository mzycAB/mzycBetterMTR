# -*- coding: utf-8 -*-
"""Script: skill_note_1015b.py

把 2026-10-15 第二轮（**补丁文件夹门禁**）的结论写进 skill：

  1. `smoothlift-escalator-step-animation`：**追加**一节
     「【2026-10-15 · 补丁文件夹门禁】玩家自制（无密码）补丁包 + zip 必须正好一个」；
  2. `smoothlift-psd-chime`：**更正**结尾那句过时笔记
     （旧文写「补丁包现在 7 段、收进了 subway_escalator、jar 里没有它」——
     实测不符：补丁包是 6 段；`subway_escalator.ogg` 是**随 jar 分发**的 CC0 素材）。

★ 两个 SKILL.md 都是 CRLF：按 LF 读进来、按原样写回（BOM / 末行换行也保持）。
幂等：带 `_APPLIED_1015B` 标记，重复跑会跳过。
用法：`python _tools/skill_note_1015b.py`
"""

import os
import sys

SKILLS = "C:/Users/user/.workbuddy/skills"
STEP = os.path.join(SKILLS, "smoothlift-escalator-step-animation", "SKILL.md")
PSD = os.path.join(SKILLS, "smoothlift-psd-chime", "SKILL.md")
MARK = "_APPLIED_1015B"

STEP_BLOCK = """

---
# 【2026-10-15 · 补丁文件夹门禁】玩家自制（无密码）补丁包 + 「zip 必须正好一个」 _APPLIED_1015B

## 一、用户原话

> 「如果玩家放进去同名压缩包（Repair.zip）但是没有密码，并且里面的 ogg 文件名字也一样…
>   按下『导入补丁』按钮之后就把模组默认音频改为这个『补丁包』里的音频…
>   记住，替换补丁过程是：『删除旧的补丁音频，再导入新的补丁音频。』所以如果新补丁音频缺少
>   一些，就缺少的地方不管他，变成模组默认…
>   如果补丁文件夹（MBM_Repair）里的补丁压缩包大于 1 个就在玩家按下『导入补丁』按钮之后
>   不执行任何操作，也不给任何报错提示，只有补丁文件夹里的补丁压缩包数量为 1 时才可以导入补丁」
>
> 追问后定的三条边界：**任意 `.zip` 都算**（名字不限）／**0 个 zip = 记一次失败**（保持原行为）／
> **包里一个能用的条目都没有 = 当失败、旧补丁不动**（保持原行为）。

## 二、★ 先量一遍再动手 —— 「无密码包」**本来就已经能读**

| 试的包（`RepairCrypto.readAesZip` + `pickOggs`，纯 JDK 离线） | 结果 |
|---|---|
| 官方 AES-256 `Repair.zip` | 6 段全认出来 |
| 无密码 zip + `doorclose.ogg` | `[doorclose]` |
| 无密码 zip + 两个条目 | 正好那两个 |
| 无密码 zip + 名字对但内容不是 OggS | `[]`（有 OggS 头校验） |
| 无密码 zip + `lala.ogg` | `[]` |
| 无密码 zip + `MBM_Repair/doorclose.ogg`（带文件夹前缀） | **`[]`** —— 口径是**精确条目名** |

⇒ `readAesZip` 里 `strength == 0` 那一支本来就同时吃「未加密条目」；
  `importFromSave` 也本来就是 `clear()` → `putAll(本次认出的键)`（= 用户要的「先删后导」）。
  **本轮真正要写的只有「门禁」那三行。**
★ 教训：用户说「我要加一个新功能」时，先把**现有实现能跑到哪一步**量出来 ——
  这次差一点就重写了整个读包链路（而它早就支持了，只是没人做过那个包）。

## 三、改动（5 个工程；`RepairStore.java` 在 5 个工程里逐字节相同 ⇒ 改一份铺五份）

| 文件 | 改动 |
|---|---|
| `RepairStore` | 新增 `ZIP_EXT=".zip"` 与 `IMPORT_SILENT=-1`；`zipPath()` → `folder()` + `patchZips()`（列补丁文件夹里**直接放着**的 `.zip`：大小写不敏感、只数普通文件、按名排序）；`importFromSave()` 顶部三态门禁 |
| `SmoothLift.serverImportRepair` | 拿到 `IMPORT_SILENT` ⇒ **直接 `return false`，不调 `noteUiResult`** |
| `client/RepairPatch.importPatch` | 注释 + 日志改成「该文件夹里唯一那个 `.zip`」（名字不再有约束） |
| `_tools/check-repair-patch.py`（5 份同哈希） | 新增 §9 门禁判据 + §10b 三条反向对照；docstring 补判据 10 |
| `RepairCrypto` 类注释 | 补一句「未加密的普通 zip 也走这个读法（`strength == 0` 那一支）」 |
| Forge1.20.1 `MbmRepairImportPacket` 注释 | 同上口径 |

★★ **为什么必须让 `importFromSave` 返回一个哨兵，而不是复用 `0`**：`0` 已经表示「失败」，
  而「>1 静默」**不是失败**（用户点名「不给任何报错提示」）。两者都「什么都没干」，
  分不清就会多给玩家回一句「UI执行失败」—— 那正是他不要的提示。
  判据落在：`if (imported == RepairStore.IMPORT_SILENT) { … return false; }` 必须在
  `noteUiResult(...)` **之前**（`check-repair-patch.py` §9 的 `silent_ok()` 直接判这个顺序）。

## 四、三态决策表（已落成判据 + 反向对照）

| 补丁文件夹里 zip 个数 | 服务端 | 玩家看到 |
|---|---|---|
| 0 | 失败（**保持原行为**） | 退出界面 →「UI执行失败」 |
| 1 | 导入它（**名字随便、有没有密码都行**） | 退出界面 →「UI执行成功」+ 音频生效 |
| >1 | **静默**：什么都不做、也不给任何提示 | 按了跟没按一样；退出界面仍是「UI执行成功」 |

## 五、证据（都可复跑）

- 离线探针 `_tools/RepairPlainZipProbe.java`（**纯 JDK，不开游戏**）：
  ```
  "$JDK/javac" -encoding UTF-8 -d _tools/out-rcz src/main/java/smooth/lift/RepairCrypto.java _tools/RepairPlainZipProbe.java
  "$JDK/java" -Dfile.encoding=UTF-8 -cp _tools/out-rcz RepairPlainZipProbe <工作区根>
  ```
  ⇒ 第二节那张表每一行都是它跑出来的（含「带文件夹前缀不认」这条已知口径）。
- 字节码直查：`javap -c` 的 `importFromSave` 里就是 `List.size()` → `iconst_1` → `if_icmple`
  → `ireturn`（返回 `IMPORT_SILENT`），门禁确实在 `Files.readAllBytes` **之前**。
- `check-repair-patch.py` 在 **5 个工程上全绿**（含新 §9 / §10b 反向对照）。
- 补丁包的**密码与格式没动**：`Repair.zip` 哈希与上一轮一致，`make_repair_zip.py` 也没重跑。

## 六、跨工程落地套路（本轮复用得很顺，值得记）

1. 先确认目标文件在 5 个工程里**是不是同一份字节**（`RepairStore.java` / `RepairCrypto.java` /
   `check-repair-patch.py` 都是）⇒ 是就「改一份铺五份」，别手改五遍；
2. 写**幂等 Python 补丁脚本**（`_tools/patch_repair_folder_gate.py` +
   `_tools/patch_repair_gate_docs_and_checks.py`），每处先判旧文本在不在、**按原行尾写回**
   （这几个文件在 5 个工程里 LF / CRLF 混着：1.21.1 与 Forge 两版的 `SmoothLift.java` 是 CRLF，
   1.20.4 与 1.21.1 的 `RepairPatch.java` 是 CRLF）；
3. 备份到 `1.20.4/_tools/_bak_1015b_gate/`；
4. 5 个工程各自 JDK 离线构建（Fabric 1.20.1/1.20.4 = JDK17；1.21.1 与 Forge 两版 = JDK21）；
5. 回归只看**标签级差集**（这几个套件本来就有 42 / 86 / 71 条历史欠账，看总数没意义）。
"""

PSD_OLD = """> ★ 补丁包现在 **7 段**（2026-10-14 起又收进了 `subway_escalator` 扶梯默认运行底噪）——
> jar 里**没有**它，**没导入补丁时扶梯不出声**。"""

PSD_NEW = """> ★【2026-10-15 更正】补丁包是 **6 段**（up / down / liftmusic / dooropen / doorclose / mdoorclose）
> —— 实测 `<工作区根>/Repair.zip` 就是 6 条目，`RepairCrypto.KEYS` 与 `make_repair_zip.py`
> 的 `KEYS` 都是 6，`check-repair-patch.py` §8 断言的就是 6。
> 另外 `subway_escalator.ogg`（CC0 扶梯底噪）**是随 jar 分发**的（见 AUDIO-CREDITS 第一节），
> 不在补丁包里 —— 旧笔记那句「7 段 / jar 里没有它」两处都与实况不符，按本节读。
> ★ 补丁文件夹的新门禁（玩家自制无密码包 / 文件夹里 zip 必须**正好一个**，>1 静默不提示）
> 见 `smoothlift-escalator-step-animation` 的「【2026-10-15 · 补丁文件夹门禁】」一节。 _APPLIED_1015B"""


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


def main():
    ok = True
    # 1) step-animation：追加
    text, bom, crlf, ends = read_norm(STEP)
    if MARK in text:
        print("  [skip] step-animation（已含本轮标记）")
    else:
        write_norm(STEP, text.rstrip("\n") + "\n" + STEP_BLOCK, bom, crlf, ends)
        print("  [ok]   step-animation 追加 %d 字节" % len(STEP_BLOCK.encode("utf-8")))
    # 2) psd-chime：更正那句过时笔记
    text, bom, crlf, ends = read_norm(PSD)
    if PSD_OLD not in text:
        print("  [%s] psd-chime：找不到待更正的原文（可能已改过）" % ("skip" if PSD_NEW in text else "FAIL"))
        ok = PSD_NEW in text
    elif text.count(PSD_OLD) != 1:
        print("  [FAIL] psd-chime：待更正文本出现 %d 次" % text.count(PSD_OLD))
        ok = False
    else:
        write_norm(PSD, text.replace(PSD_OLD, PSD_NEW), bom, crlf, ends)
        print("  [ok]   psd-chime：更正「7 段」那两行")
    print("== 完成（%s）" % ("全部成功" if ok else "★ 有失败项"))
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
