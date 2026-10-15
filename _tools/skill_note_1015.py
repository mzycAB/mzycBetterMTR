# -*- coding: utf-8 -*-
"""Script: skill_note_1015.py

把 2026-10-15（1.33 轮）的两条笔记**追加**到两个 skill 的 SKILL.md 末尾。

★ 为什么用脚本而不是直接编辑：这两个 SKILL.md 是 **CRLF**。按 LF 去匹配旧文本会静默匹配不上
  （本项目已踩过多次），所以整段按 utf-8 读 → 追加 → 按原行尾写回。

幂等：带 `_APPLIED_1015` 标记，重复跑会跳过。
用法：`python _tools/skill_note_1015.py`
"""

import os

SKILLS = "C:/Users/user/.workbuddy/skills"
REF = os.path.join(SKILLS, "smoothlift-psd-chime", "SKILL.md")
STEP = os.path.join(SKILLS, "smoothlift-escalator-step-animation", "SKILL.md")
MARK = "_APPLIED_1015"

PSD = """

---
# 【续 64】屏蔽门**开门**音改成「两声同音高的叮-叮」（2026-10-15 · 1.33 轮，5 个工程一起改） _APPLIED_1015

## 一、用户原话与一次关键追问

> 「这个模组的默认屏蔽门开门声音为什么是"ding-pong"而不是"ding ding"，修改，但是关门声音是对的。
>   你可以把关门声音作为修复参考，但是开关门的声音是稍微有不同的」

追问（因为「ding-pong」这个词**对不上任何一段素材的实测**，必须让用户指认）→ 用户答：
「一声『嘶——』加咔哒」＝ **jar 内置**的那段自合成气动开门音（不是补丁包里的原素材）。

| 素材 | 实测 | 用户听的 |
|---|---|---|
| jar 内置 `dooropen.ogg`（改前） | 31944 B；带通白噪声「嘶——」铺满 2.283s + 430/350/620Hz 三个咔哒 | ★ 就是这个（他叫它 ding-pong） |
| 补丁包原素材 `dooropen.ogg` | 24360 B；**880Hz 响 0.25s → 700Hz 响 0.8s**，整段重复两遍（真·「叮-咚」） | 没装补丁，听不到 |
| 关门默认 `mdoorclose.ogg` | 4.000s；20 响严格 200ms 等间隔、**同一音高 516.7Hz** | 「关门声音是对的」＝参照物 |

★ **教训（可迁移）**：用户嘴里的拟声词**不一定对得上现行素材**。「ding-pong」既像原素材的
880→700、又像别处听过的门音 ⇒ 不问就改，很可能改错那一段。**先量频谱 → 再把候选摆给他选。**

## 二、改法与**两条硬约束**（回归钉子，改素材前必须先看）

`_tools/gen_replace_audio.py` 的 `make_dooropen()` 重写为「两声同音高的 880Hz」：

| 常量 | 值 | 为什么 |
|---|---|---|
| `DING_SECONDS` | **2.283s** | `check-psd-split.py` 断言 `abs(时长 − 2283) ≤ 25ms`（播放端提前量算式按素材长度算） |
| `DING_F` | **880Hz** | 与旧 dooropen 的第一声同音高 |
| `DING_ONSETS_MS` | **[0, 700]** | 间隔 **700ms ≪ 分界点检测下限 400ms** ⇒ `split` 仍是 **-1**（同一脚本断言）⇒ 开门端继续走「整段播」，播放端一行未改 |
| `DING_DECAY` | 4.2/s | 长尾＝「叮」不是「嘀」 |
| `DING_PEAK` | `DOOR_BEEP_PEAK`(0.3835) | 峰值对齐**关门**那一档 ⇒ 开关门两段响度同级（`check-psd-align.py` §3.2 另断言 align 得 0，因为素材 < 门程 4000ms） |

- 谐波配方与关门嘀嘀**同一套**（主频 + 0.35×2 次 + 0.15×3 次），只有音高与节奏不同 ⇒
  正是用户那句「开关门的声音是稍微有不同的」。
- ★★ **生成脚本必须能「只做一段」**：`gen_replace_audio.py [out] [key...]`（不给 key 才是全量）。
  否则重跑会把用户已认可的那三段（liftmusic / doorclose / mdoorclose）**重新编码一遍**，字节一变
  ⇒ 文档里那些 sha256 / 峰值 / RMS 全作废，他的耳朵还要重新验收一次。
  同理 `apply_replace_audio.py [key...]` 只覆盖指定素材，并**跳过**删 up/down 与改 sounds.json 两步。
- 只改 **jar 内置**，**不动 `Repair.zip`**（用户点名「补丁包不要改」）。

## 三、可核验数字（本轮）

| 项 | 值 |
|---|---|
| 新 `dooropen.ogg` | **24533 B**（旧 31944 B），sha256 `aa9f7dace419372c2c2db9ce71e7500a52a25eed1f76c7a926268b2e87d82246` |
| 规格 | 44100Hz / 2ch / **100680 帧 = 2.283s**；峰值 **0.4026**（关门 0.4006）、RMS 0.1309 |
| 频谱 | t=0.05 与 t=0.75 两处主频都是 **880Hz**（同音高 ✓）；`split(detect) = -1` ✓ |
| 落点 | 5 个工程 `src/main/resources/assets/smoothlift/sounds/audio/dooropen.ogg` **同一哈希** |
| 回归 | 4 个有套件的工程：**新增 0 条 FAIL**；`dooropen.ogg 时长 2283ms` / `分界点 = -1` / `算式得 -1667` 全 PASS |

★ 想再调音（音高 / 声数 / 间隔）只改上面那 5 个常量 + `DING_ONSETS_MS`，重跑两条脚本即可；
  **改完必须重跑** `check-psd-split.py` 与 `check-psd-align.py`（时长与 split 是硬约束）。
"""

STEP_NOTES = """

---
# 【1.33】CurseForge 审核整改 + 版本号 1.33.xxxx（2026-10-15，5 个工程） _APPLIED_1015

## 一、背景

用户：「只有 forge1.20.1 在 CurseForge 审核通过，其他版本全部转为人工审核，尤其是 fabric1.20.4
直接被拒绝，你检查一下模组有没有可疑的地方然后修复」；追问后用户答**没有**官方拒绝理由 ⇒
按自己的审计结果修。★ **拒绝原因未证实**：下面三条是「按平台规则本来就该改」的，不是「已定位真因」。

## 二、审计结果（都在 jar 里，逐条可核）

| # | 可疑点 | 处理 |
|---|---|---|
| 1 | 根目录 `AUDIO-CREDITS.txt` 开头**自述**「此前随 jar 分发过 6 段**外部来源**（用户提供）的音频」；第二节 + 第四节**整段**描述「那 6 段原音放在 AES-256 加密 zip 里，密码只存在于 jar 内部、玩家自己解压解不开」 | **重写**成干净版本（素材清单 + CC0 出处/许可 + 自合成配方 + 「不改动也不分发 MTR 的音频」+「玩家可自行导入 ogg，只写存档」）。删的是**叙述**不是功能：`Repair.zip` / 「导入补丁」照旧可用 |
| 2 | 模板残留 `ExampleClientMixin`（`@Mixin(Minecraft.class)` + 一个**空** `@Inject`，Fabric 示例工程抄来没删），还挂在 `smoothlift.client.mixins.json` 里 | 删文件 + 删 json 条目（只有 fabric 1.20.1 / 1.20.4 有） |
| 3 | `assets/shsubwaypicture/lang/*.json` 里 `block.shsubwaypicture.example_block` / `test_1` 两个**模板遗留键**（对应方块根本没注册） | 删键。★ 那 4 张 `pic_*.png` **保留** —— 已核对是**用户自己的虚构地铁图**（站名与他世界里的名字一致），不是第三方素材 |

★ 另核过、**确认没问题**的：`assets/mtr/models/block/lift_track_floor_1.json` 是手写模型 + 原版
`smooth_stone` 贴图（不是从 MTR 复制）；全 jar 扫 `https?://` / `Runtime` / `ProcessBuilder` / `wmic` /
`Base64` / `Class.forName` —— **零命中**（只有自己的 `deleteAudio` 之类正常串）。

## 三、版本号

`1.32.xxxx` → `1.33.xxxx`（`xxxx` 不动）：`1.33.1201` / `1.33.1204` / `1.33.1211` / `1.33.11182` / `1.33.11201`。
改 `gradle.properties` 的 `mod_version`，**并且**改写在回归脚本里写死的「绊线常量」：
`_tools/check-mbm-command.py`（1.20.1 / Forge1.18.2 / Forge1.20.1）与 `_tools/check-view-tilt.py`（1.20.4）。
其余脚本早已从 `gradle.properties` 派生（1.20.4 的 check-mbm-command 就是派生的）⇒ 不用动。

## 四、本轮的跨工程脚本

- `_tools/patch_133.py`（住在 1.20.4 工程，驱 5 个工程）：版本号 + 绊线常量 + AUDIO-CREDITS 重写 +
  删 ExampleClientMixin + 清语言文件死键。**幂等**（每处先判旧文本在不在）。
  ★ 读到 CRLF 先归一成 LF、写回按原样还原（BOM / 末行换行也保持）—— 这批文件里 `AUDIO-CREDITS.txt`
  与 `*lang*.json` 是 CRLF，按 LF 构造 pattern 会**静默匹配不上**。
- 备份在 `1.20.4/_tools/_bak_1015_133/`（29 个文件，按工程目录树原样排）。

## 五、★ 工程布局（第三次提醒：与「2026-10-14 起」那条**不同**）

`C:/Users/user/Desktop/3` 这个工作区里，五个工程是**两层**：
`<工作区>/<版本标签>/mzycBetterMTR-<号>`（例：`3/1.20.4/mzycBetterMTR-1.20.4`）。
⇒ `apply_replace_audio.py` / `patch_133.py` 里那份 `PROJECTS` 相对路径正是照这个布局写的；
`check-mtr3-contract.py` 则是在**工作区根**找 `MTR-*.jar` —— 搬家后根目录下没有 jar 了，
所以 Forge1.20.1 的「MTR3/MTR4 契约」那节会红（`[环境] 找到 MTR3 的 jar — None`），
**与本轮改动无关**；把 MTR-forge-1.20.1 / MTR-fabric-4 的 jar 放回工作区根即可恢复。
- 搬家后必修：`_tools/runtime.cp`（否则 CmdTreeCheck 报「程序包 net.minecraft.* 不存在」）。
  本轮 4 个工程都重建过：`JAVA_HOME=<jdk> ./gradlew -I _tools/dumpcp.gradle dumpRuntimeClasspath --offline`。

## 六、本轮产物与回归（可核验）

| 工程 | JDK | 产物 | 字节 |
|---|---|---|---|
| Fabric 1.20.4 | 17 | `mzycBetterMTR-1.33.1204.jar` | 1,501,657 |
| Fabric 1.20.1 | 17 | `mzycBetterMTR-1.33.1201.jar` | 1,515,957 |
| Fabric 1.21.1 | 21 | `mzycBetterMTR-1.33.1211.jar` | 1,505,903 |
| Forge 1.18.2 | 21 | `mzycBetterMTR-1.33.11182.jar` | 1,545,564 |
| Forge 1.20.1 | 21 | `mzycBetterMTR-1.33.11201.jar` | 1,623,448 |

- 5 个 `BUILD SUCCESSFUL`；`fabric.mod.json version` / Forge MANIFEST `Implementation-Version`
  逐个核过 = 新号。
- 回归**标签级差集**（与各自最近基线）：1.20.4 **42=42，新增 0 / 消失 0**；1.20.1 **86 vs 87，新增 0 / 消失 1**；
  Forge1.18.2 **71=71，新增 0 / 消失 0**；Forge1.20.1 基线本来全绿，本轮唯一红节是**缺 MTR jar**（见第五节）。
  ★ 这几个套件本来就有大面积历史欠账（42 / 86 / 71 条），判零回归只能看**标签差集**，不能看「FAIL 是不是 0」。

## 七、补丁包密码在哪（用户会问）

`_tools/make_repair_zip.py` 顶部的 `PASSWORD` 是**唯一真值**（20 字符，含大小写 / 数字 / 符号）；
它由 `java_lock()` 混淆成 `RepairCrypto.LOCK` 那 20 个字节写进 jar。
⇒ **改密码必须同步这两处**，`make_repair_zip.py` 跑完会把可直接粘贴的那一行 `LOCK` 打出来。
本轮**没有**改密码，也没重打 `Repair.zip`（用户点名「补丁包不要改」）。
"""


def append(path, block):
    with open(path, "rb") as fh:
        raw = fh.read()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8").replace("\r\n", "\n")
    if MARK in text:
        print("  [skip] %s（已含本轮标记）" % os.path.basename(os.path.dirname(path)))
        return False
    text = text.rstrip("\n") + "\n" + block
    data = text.encode("utf-8")
    if crlf:
        data = data.replace(b"\n", b"\r\n")
    with open(path, "wb") as fh:
        fh.write(data)
    print("  [ok]   %s 追加 %d 字节" % (os.path.basename(os.path.dirname(path)), len(block.encode("utf-8"))))
    return True


def main():
    print("== 追加 skill 笔记（1.33 轮）")
    append(REF, PSD)
    append(STEP, STEP_NOTES)
    print("== 完成")


if __name__ == "__main__":
    main()
