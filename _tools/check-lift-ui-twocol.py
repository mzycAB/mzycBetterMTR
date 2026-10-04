# -*- coding: utf-8 -*-
"""离线校验（Forge-1.20.1）：直梯单项页 = 左右两列列表（按屏蔽门 UI 样式；【09-27】）。

## 需求（用户原话）

    按照屏蔽门 ui 的样式，更新直梯的 ui（双列结构之类的样式更新）。

## 设计要点（脚本要钉住的四条不变量）

1. **两列版式只有一份来源**：几何（列宽 / 行高 / 行内三格的 x / 行 y）**全部**取自
   `SoundListLayout`，本类只写「别名」而不另算一遍 190 / 22 这类数字。
   ⇒ 断言的是「引用了 SoundListLayout 的哪个方法/常量」，而不是「数字等于几」。
2. **左列 = 未导入、右列 = 已导入**，表头逐字为「未导入存档」/「已导入存档」（与屏蔽门相同）。
3. **右列特殊行占 3 行**（开关 / 不播 / 默认（跟维度默认））⇒ 已存入第 i 条落在第 i+3 行；
   三个特殊行**只有「选用」、没有「删除」** ⇒ `rowDeleteX` 全类恰好 1 处。
4. **灰字已清**：与屏蔽门【1.18】同一口径，`0x808080 / 0x909090 / 0xC0C0C0 …` 一个都不留。

★ Forge-1.20.1 分支**没有音频分类隔离**（无 `CAT_*`）：左列读同一个 `MBM_Audio` 文件夹、
  删除通道只带 id 不带分类。
★ Forge 与 Fabric 的差异：发包走 `Packets.CHANNEL.sendToServer(new XxxPacket(...))`，
  而不是 Fabric 的 `ClientPlayNetworking.send(通道, buf)`。

用法：`python _tools/check-lift-ui-twocol.py`（退出码 0 = 全部通过）
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREEN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "client",
                      "LiftToneSetupScreen.java")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)


with open(SCREEN, encoding="utf-8") as fh:
    screen = fh.read()

print("== ★ 直梯单项页 = 左右两列列表（按屏蔽门 UI 样式；【09-27】） ==")

check("SoundListLayout.leftColX(" in screen and "SoundListLayout.rightColX(" in screen
      and "SoundListLayout.rowPickX(" in screen and "SoundListLayout.rowDeleteX(" in screen
      and "SoundListLayout.rowY(" in screen,
      "两列 / 行内三格的 x 与行 y 全部取自 SoundListLayout（不再各算一遍）")

check("未导入存档" in screen and "已导入存档" in screen,
      "两列表头 = 「未导入存档」/「已导入存档」（与屏蔽门逐字相同）")

check("private static final int RIGHT_SPECIAL_ROWS = 3;" in screen,
      "右列特殊行 = 3（开关 / 不播 / 默认（跟维度默认））")

check("i + RIGHT_SPECIAL_ROWS" in screen,
      "★ 已存入第 i 条落在第 i + 3 行（前 3 行被特殊行占了）")

for _nm in ("ROW_H", "LIST_TOP", "COL_W", "ROW_BTN_W", "ROW_BTN_GAP",
            "ROW_NAME_W", "ROW_NAME_CHARS", "BTN_Y"):
    check(("private static final int %s = SoundListLayout.%s;" % (_nm, _nm)) in screen,
          "直梯界面的 %s 是指向 SoundListLayout 的别名" % _nm)

# buildTonePage() 方法体级别的断言
import re  # noqa: E402  （放在后面只为让上面读起来像「按节推进」）

_m = re.search(r"private void buildTonePage\(String which\)\s*\{(.*?)\n    \}", screen, re.S)
_body = _m.group(1) if _m else ""
check(_body != "", "抠得出 buildTonePage() 方法体")

if _body:
    check("开关" in _body and "不播" in _body and "默认（跟维度默认）" in _body,
          "右列三个特殊行（开关 / 不播 / 默认（跟维度默认））都建了控件")
    check(_body.count("SoundListLayout.rowDeleteX(") == 1,
          "★ 「删除」只出现在已存入行（三个特殊行都没有删除键）—— rowDeleteX 恰好 1 处",
          "实际 %d 处" % _body.count("SoundListLayout.rowDeleteX("))
    check("SoundListLayout.leftColX(this.width)" in _body
          and "pending" in _body and "stored" in _body,
          "左列 = pending（未导入）、右列 = stored（已导入），两列都建了控件")

# ★ Forge-1.20.1 无分类隔离：删除只带 id、不带 categoryFor（与 1.20.4 的差异点）
check("deleteStored(String id)" in screen and "categoryFor" not in screen,
      "Forge-1.20.1 无分类隔离：deleteStored 只带 id（全类无 categoryFor）")

# ★ Forge 发包方式（六个动作各走一只包）
for _pkt in ("RequestSyncPacket", "SetLiftChimeVolumePacket", "SetLiftToneVolumePacket",
             "ImportFolderLiftTonePacket", "SetLiftTonePacket", "SetLiftToneSwitchPacket",
             "DeleteAudioPacket"):
    check(("Packets.CHANNEL.sendToServer(new %s(" % _pkt) in screen,
          "发包走 Forge 通道：%s" % _pkt)

# 灰字（与屏蔽门【1.18】同一口径：这一类颜色一个都不许留）
GRAY_TEXTS = ("0x808080", "0x909090", "0xFF909090", "0xFFE0E0E0", "0xC0C0C0", "0xA0A0A0")
_gray = [c for c in GRAY_TEXTS if c in screen]
check(not _gray, "★ 直梯 UI 里没有任何灰色文字色（对齐屏蔽门：灰字已清）",
      "；".join(_gray) if _gray else "白/黄两色之外无灰")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)

print("\n== 全部通过 ==")
