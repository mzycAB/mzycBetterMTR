# -*- coding: utf-8 -*-
"""Script: patch_133.py

【2026-10-15 · 1.33 轮】一次落地三件事（用户点名），跨全部 5 个工程：

  1) **版本号** `1.32.xxxx` → `1.33.xxxx`（`xxxx` 后缀一字不动）。
     同时把回归脚本里写死的「绊线」常量一起改掉（它们是故意写死来报警的：
     `_tools/check-mbm-command.py` / `_tools/check-view-tilt.py`）。
     ★ 其余脚本早就从 `gradle.properties` 派生版本号，不需要动。

  2) **`AUDIO-CREDITS.txt` 改成与当前素材一致的干净版本**。
     为什么必须改（CurseForge 审核整改之一，用户答复「按你的审计结果修」）：
       · 原文开头 5 行**自述**「此前随 jar 分发过 6 段**外部来源**（用户提供）的音频」——
         这是一句「分发过来源不明的音频」的自认，审核方看到就够拒了；
       · 原文第二节 + 第四节**整段**在描述「那 6 段原音放在一个 AES-256 加密的 zip 里，
         密码只存在于 jar 内部、玩家自己解压解不开」——「加密载荷 + 混淆密码」在自动/人工
         审核里都是高危模式；
       · 而现在的 jar **确实**不含任何外部来源音频（除那段 CC0），所以上面两段既是
         liability 又是过时描述。
     ⇒ 新版本只留「credit 该说的话」：素材清单 + CC0 出处与许可 + 自合成配方 +
       「本模组不改动、也不分发 MTR 的音频」+「玩家可自行导入自己的 ogg（只写存档）」。
     ★ 被删掉的是**叙述**，不是功能：`Repair.zip` / 「导入补丁」按钮在游戏里照旧可用，
       只是不再写进这份「出处与许可」文件（它本来就只该讲版权出处）。

  3) **清掉两处审核可疑点**：
       · 删掉模板残留 `ExampleClientMixin`（`@Mixin(Minecraft.class)` 里只有一个空
         `@Inject`，从 Fabric 示例工程抄来时没删）—— 连同 `smoothlift.client.mixins.json`
         里的那一行一起删（只有 fabric 1.20.1 / 1.20.4 有）；
       · 删掉 `assets/shsubwaypicture/lang/*.json` 里 `example_block` / `test_1` 两个
         **模板遗留键**（对应的方块根本没有注册，是死键；留着就是「从示例工程抄的」痕迹）。

★ 行尾：本脚本读文件时把 CRLF 归一成 LF，写回时**按原样**还原（BOM 与「末行有没有换行」
  也一并保持）——这几个文件是 CRLF，直接按 LF 构造 pattern 会静默匹配不上。

幂等：每一处改动都先判「旧文本在不在」，不在就 skip 并记一笔，重复跑不会二次改。
用法：`python _tools/patch_133.py`
"""

import json
import os
import sys

WORKSPACE = r"C:/Users/user/Desktop/3"

# (标签, 工程相对路径, 旧版本号, 新版本号)
PROJECTS = [
    ("1.20.1", "1.20.1/mzycBetterMTR-1.20.1", "1.32.1201", "1.33.1201"),
    ("1.20.4", "1.20.4/mzycBetterMTR-1.20.4", "1.32.1204", "1.33.1204"),
    ("1.21.1", "1.21.1/mzycBetterMTR-1.21.1", "1.32.1211", "1.33.1211"),
    ("Forge1.18.2", "Forge1.18.2/mzycBetterMTR-Forge-1.18.2", "1.32.11182", "1.33.11182"),
    ("Forge1.20.1", "Forge1.20.1/mzycBetterMTR-Forge-1.20.1", "1.32.11201", "1.33.11201"),
]

# 版本号「绊线」脚本（把旧号写成了常量，改号必须一起改）
VERSION_TRIPWIRES = ["_tools/check-mbm-command.py", "_tools/check-view-tilt.py"]

CREDITS_REL = "src/main/resources/AUDIO-CREDITS.txt"
LANG_REL = "src/main/resources/assets/shsubwaypicture/lang"
EXAMPLE_MIXIN_REL = "src/client/java/smooth/lift/client/mixin/ExampleClientMixin.java"
CLIENT_MIXINS_REL = "src/client/resources/smoothlift.client.mixins.json"
DEAD_LANG_KEYS = ["block.shsubwaypicture.example_block", "block.shsubwaypicture.test_1"]

NEW_CREDITS = """SmoothLift 内置音频 · 出处与许可
Bundled audio — source and license
=========================================================

【2026-10-15】本模组随 jar 分发的音频**除 1 段 CC0 扶梯底噪外，全部是自己合成的**
（合成脚本 `_tools/gen_replace_audio.py`：numpy 纯合成 → 用 `imageio_ffmpeg` 自带的静态
ffmpeg + libvorbis 编码成 Ogg Vorbis）。
没有一段来自其它模组、商业音源或第三方录音。唯一的外部素材是下面 1.1 那段 CC0 录音。

--------------------------------------------------------------------
一、随 jar 分发的素材一览

复现脚本：`_tools/gen_replace_audio.py`（合成）＋ `_tools/apply_replace_audio.py`（铺进各工程）。
量测脚本：`_tools/_measure_src_audio.py`。

文件 / file                             时长      峰值      RMS       说明
--------------------------------------  --------  --------  --------  --------------------------------------------
audio/subway_escalator.ogg              19.780s   0.0642    0.0088    ← 唯一一段外部素材，CC0（见 1.1）
audio/escalator_chime_board.ogg         2.000s    0.3303    0.0237    ← 港铁式「咔」敲击，自合成（单声道）
audio/escalator_chime_alight.ogg        2.000s    0.3153    0.0059    ← 同上（离开端，单声道）
audio/escalator_chime_rate4.ogg         2.000s    0.3215    0.0134    ← 同上（4 Hz 档，单声道）
audio/escalator_chime_rate25.ogg        2.000s    0.3396    0.0342    ← 同上（2.5 Hz 档，单声道）
audio/escalator_chime_rate50.ogg        2.000s    0.3308    0.0364    ← 同上（5 Hz 档，单声道）
audio/liftmusic.ogg                     0.859s    0.1267    0.0491    ← 直梯开关门提示音，自合成谐波长音
audio/dooropen.ogg                      2.283s    0.4026    0.1309    ← 屏蔽门**开门**提示音：两声同音高的「叮-叮」
audio/doorclose.ogg                     4.000s    0.4006    0.1373    ← 屏蔽门关门（显式素材档）：纯嘀嘀串
audio/mdoorclose.ogg                    4.000s    0.4006    0.1373    ← 屏蔽门关门（默认档）：同上一段

★ **响度对齐的口径 = RMS，不是峰值**。游戏里「音量 100 = 原始音量」是一条**响度**约定；
  两类素材的波峰因数不同（旧 liftmusic 实测 RMS/峰值 = 0.261，纯合成版本 ≈ 0.39），
  按峰值对齐会让新素材听起来明显更响/更轻。所以合成脚本最后一步按 RMS 对齐（`normalize_rms`）。
  ★ 唯一例外是 `dooropen.ogg`：它与关门嘀嘀的波峰因数几乎相同（0.325 vs 0.343），
    按峰值对齐与按 RMS 对齐结果一致；取峰值是为了让「开门 / 关门」两段在游戏里响度同级。

★ 合成音的详细配方（都在 `gen_replace_audio.py` 里，逐条有注释）：

  · liftmusic.ogg —— 基频 801.882Hz 的第 5/6/7/8/9 次谐波按实测相对高度叠（各带不同相位），
    包络 = 8ms 起振 → 平段 → 末 18% 收尾，长 0.859s（开关门连播的节奏是按它定的）。
  · dooropen.ogg —— **两声同音高的「叮-叮」**：880Hz；两声间隔 700ms；每一声 =
    主频 + 2/3 次谐波（与关门嘀嘀同一套谐波配方）× 2ms 起振 × 指数衰减 4.2/s；
    经 tanh 软限幅后归一化到 0.3835（与关门嘀嘀同一档）。
    ★ **长度固定 2.283s**（播放端的提前量算式按素材长度算），且两声的间隔远小于
    分界点检测的下限 400ms ⇒ 播放端仍按「整段播」处理，**播放端代码一行未改**。
  · doorclose.ogg / mdoorclose.ogg —— **纯嘀嘀串**：20 响、严格 200ms 等间隔、主频 516.7Hz，
    单响时长 113ms、强度逐步略降；同样经 tanh 软限幅对齐波峰因数。
    ★ **长度为什么取 4.000s**：MTR4 的门程正好 80 tick = 4000ms（字节码直算，见
    `_tools/check-psd-align.py` 第 1 节）。播放端的对齐算式是
    `start = 量化(素材时长 − 门还在走的毫秒数)` ⇒ 素材时长 == 门程时 `start ≈ 0`，
    于是**整串嘀嘀完整播完、结尾正好落在门关上那一刻**（这正是这个功能的老需求）。
    若素材比门程短，算式会退化成「从头整段播」⇒ 结尾提前收声。

### 1.1 subway_escalator.ogg（唯一一段外部素材 · CC0）

它由作者转换为 Ogg Vorbis 并做剪辑，玩家直接装模组即可使用，无需任何转码工具。

作者对原始录音做的剪辑（因为要循环播放）：
  1. 原始录音是「11.8 秒声音 + 2.2 秒完全静音 + 9.4 秒声音」，
     剪掉中间 2.2 秒空白与首尾静音（共约 2.8 秒），两段声音拼接；
  2. 拼接处 0.40 秒等功率交叉淡化（crossfade），避免爆音；
  3. 首尾做 0.70 秒等功率交叉淡化（循环点在淡化区内），循环播放无接缝、无停顿；
  4. 未做任何电平（音量）处理，峰值仍为 -23.8 dBFS，与原始录音一致。

原始素材 / original source
  Freesound 标题 : 4_Escalator.wav
  Freesound 页面 : https://freesound.org/people/14G_Panska_Hoskovcova_Eliska/sounds/419482/
  作者 / author  : 14G_Panska_Hoskovcova_Eliska
  说明 / about   : "Two recordings of different moving stairs."
                   （地铁站里两段不同的自动扶梯录音 / Praha, Prague, Czech Republic）
  发布 / posted  : 2018-02-26
  原始规格       : 0:23.637, WAV, 6.5 MB, 48 kHz, 24 bit, Stereo
  镜像页         : 淘声网 toSound「地铁 " 4 自动扶梯」（sound-wrp7aq1d）

许可 / LICENSE
  ★ Creative Commons 0 (CC0 1.0 Universal, Public Domain Dedication)
  ★ https://creativecommons.org/publicdomain/zero/1.0/

  Freesound 页面上的许可字段原文（逐字引用）：
      Creative Commons 0
      You can copy, modify, distribute and perform the sound, even for
      commercial purposes, all without the need of asking permission
      to the author.

  即：属于公共领域奉献（Public Domain Dedication）。
  - 可以自由复制、修改、分发、公开表演（含商业用途）；
  - **不需要署名**，也不需要事先征得作者同意；
  - 无 NonCommercial 限制，无 ShareAlike 传染性。

  校验信息 / verification: 由于分发的是剪辑+循环化后的版本，其哈希与原始转码件**不同**，
  两者都记录如下（CC0 允许修改，此处仅为可溯源）。
    jar 内 / shipped  subway_escalator.ogg : e1f619c1118de732ef383066baf97e1a0b9e1d757d99a8c2d328d40066347a1d
    剪辑前 / source   sound-wrp7aq1d.ogg   : f036b0d4c951e18d4c3cc7863b2bae62d475899e4de53251fcc612c7c08cf0dc

--------------------------------------------------------------------
二、本模组不改动、也不分发 MTR 的任何音频

SmoothLift 只在**运行期**通过反射 / Mixin 调用 MTR 的类，并在少数界面上读取 MTR 的数据
（车站、时刻表、轿厢尺寸…）。这些都不构成对 MTR 素材的再分发。
`assets/mtr/models/block/lift_track_floor_1.json` 是**手写**的楼层轨道模型覆盖
（用的是原版 `block/smooth_stone` 贴图），不是从 MTR 复制出来的文件。

--------------------------------------------------------------------
三、玩家自己导入的音频

  · 玩家可以在游戏内界面（`/MBM help` 右上角，以及拿石斧右键各类方块打开的声音设置页）
    导入自己的 `.ogg` 文件；
  · 这些文件只写进**存档**（SavedData），不随 jar 分发，也不修改 jar 内的任何内容；
  · 本模组不联网、不下载任何东西。
"""


def read_text(path):
    """读文件 → (归一成 LF 的文本, 是否有 BOM, 是否 CRLF, 末行是否带换行)。"""
    with open(path, "rb") as fh:
        raw = fh.read()
    bom = raw.startswith(b"\xef\xbb\xbf")
    body = raw[3:] if bom else raw
    text = body.decode("utf-8")
    crlf = "\r\n" in text
    ends_eol = text.endswith("\n")
    return text.replace("\r\n", "\n"), bom, crlf, ends_eol


def write_text(path, text, bom, crlf, ends_eol):
    text = text.replace("\r\n", "\n")
    if ends_eol:
        if not text.endswith("\n"):
            text += "\n"
    else:
        text = text.rstrip("\n")
    if crlf:
        text = text.replace("\n", "\r\n")
    data = text.encode("utf-8")
    if bom:
        data = b"\xef\xbb\xbf" + data
    with open(path, "wb") as fh:
        fh.write(data)


def patch_file(path, old, new, log, what, expect_once=True):
    text, bom, crlf, ends_eol = read_text(path)
    if old not in text:
        log.append("  [skip] %s（旧文本不在，未改动）" % what)
        return False
    if expect_once and text.count(old) != 1:
        log.append("  [WARN] %s（旧文本出现 %d 次，已全部替换）" % (what, text.count(old)))
    write_text(path, text.replace(old, new), bom, crlf, ends_eol)
    log.append("  [ok]   %s" % what)
    return True


def drop_lang_keys(path, keys, log):
    """从 lang JSON 里删掉若干键，保持原排版（缩进/行尾/末尾换行都不动）。"""
    text, bom, crlf, ends_eol = read_text(path)
    lines = text.split("\n")
    kept, dropped = [], []
    for line in lines:
        if any('"' + k + '"' in line for k in keys):
            dropped.append(line.strip())
            continue
        kept.append(line)
    if not dropped:
        log.append("  [skip] %s（没有要删的键）" % os.path.basename(path))
        return False
    # 收尾逗号：数据区的最后一行不能带逗号（原文件本来就是这么写的，
    # 这里只在它「刚好带了」的时候削掉 —— 不能无脑削最后一条带逗号的行，
    # 那样会把 JSON 弄坏）。
    last = None
    for i, line in enumerate(kept):
        if line.strip().startswith('"'):
            last = i
    if last is not None:
        stripped = kept[last].rstrip()
        if stripped.endswith(","):
            kept[last] = stripped[:-1]
    write_text(path, "\n".join(kept), bom, crlf, ends_eol)
    log.append("  [ok]   %s（删掉 %s）" % (os.path.basename(path), dropped))
    return True


def main():
    total = 0
    for label, proj, old_ver, new_ver in PROJECTS:
        root = os.path.join(WORKSPACE, proj)
        if not os.path.isdir(root):
            print("!! 缺工程 %s" % root)
            sys.exit(1)
        log = []
        # --- 1) 版本号（gradle.properties + 绊线脚本里的常量）---
        patch_file(os.path.join(root, "gradle.properties"),
                   "mod_version=" + old_ver, "mod_version=" + new_ver,
                   log, "gradle.properties: %s -> %s" % (old_ver, new_ver))
        for rel in VERSION_TRIPWIRES:
            p = os.path.join(root, rel)
            if os.path.isfile(p):
                patch_file(p, old_ver, new_ver, log, "%s 绊线常量" % rel, expect_once=False)
        # --- 2) AUDIO-CREDITS 改写 ---
        cp = os.path.join(root, CREDITS_REL)
        text, bom, crlf, ends_eol = read_text(cp)
        if "【2026-10-15】本模组随 jar 分发的音频" in text:
            log.append("  [skip] AUDIO-CREDITS.txt（已是新版）")
        else:
            before = len(text.encode("utf-8"))
            write_text(cp, NEW_CREDITS, bom, crlf, ends_eol)
            log.append("  [ok]   AUDIO-CREDITS.txt 改写（%d -> %d 字节）"
                       % (before, len(NEW_CREDITS.encode("utf-8"))))
        # --- 3a) 模板残留 ExampleClientMixin ---
        mx = os.path.join(root, EXAMPLE_MIXIN_REL)
        if os.path.isfile(mx):
            os.remove(mx)
            log.append("  [ok]   删除模板残留 ExampleClientMixin.java")
        cj = os.path.join(root, CLIENT_MIXINS_REL)
        if os.path.isfile(cj):
            patch_file(cj, '\t\t"ExampleClientMixin",\n', "", log,
                       "client mixins.json 去掉 ExampleClientMixin")
            try:
                with open(cj, "rb") as fh:
                    json.loads(fh.read().decode("utf-8"))
                log.append("  [ok]   client mixins.json 仍是合法 JSON")
            except Exception as exc:  # noqa: BLE001
                log.append("  [FAIL] client mixins.json 坏了：%s" % exc)
        # --- 3b) 语言文件里的模板遗留键 ---
        lang = os.path.join(root, LANG_REL)
        if os.path.isdir(lang):
            for name in ("en_us.json", "zh_cn.json"):
                p = os.path.join(lang, name)
                if os.path.isfile(p):
                    drop_lang_keys(p, DEAD_LANG_KEYS, log)
                    try:
                        with open(p, "rb") as fh:
                            json.loads(fh.read().decode("utf-8"))
                    except Exception as exc:  # noqa: BLE001
                        log.append("  [FAIL] %s 坏了：%s" % (name, exc))
        print("== %s" % label)
        for line in log:
            print(line)
        total += sum(1 for line in log if line.startswith("  [ok]"))
    print("== 完成（共 %d 处改动）" % total)


if __name__ == "__main__":
    main()
