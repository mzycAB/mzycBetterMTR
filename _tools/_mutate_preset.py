# -*- coding: utf-8 -*-
"""【09-28 续 4】反向对照：把「三个预设对讲述人的态度」的错法逐个注回去，断言必须变红。

用法：python _tools/_mutate_preset.py <backup|A|B|C|D|E|F|restore>
  A = 简单港铁的 `pbmnarrate off -f` 换回续 2 那一版 `pbmnarrate shanghai -f`
  B = 空白预设**删掉** `pbmnarrate off -f`（= 续 2 那一版「空白不加戏」）
  C = sendPreset 里把「简单 / 空白 ⇒ 关总闸」那一支删掉（只留经典开总闸）
  D = CmdTreeCheck 那份**手抄清单**只改简单港铁（制造「两处分叉」）—— 单独用 check-command-tree.sh 看
  E = 【09-29】把简单港铁直梯那两条改回 `-f off`（= 本轮 bug 的字面形态：闸门焊死）—— 期望 1b 红
  F = 【09-29】把经典港铁末尾那两条 `lifthelp open|close -f default` 删掉
      （= 只写开关、不写素材 ⇒ 救不回被写成 none 的素材）—— 期望 1b 红
  restore = 从备份还原（★ 备份路径必须是 Windows 形态，见下）
★ 备份路径必须写成 Windows 形态（C:/Users/...）：Git Bash 的 /tmp 交给 Windows 版
  Python 会 FileNotFoundError（踩过：restore 静默失败 ⇒ mutation 叠加、红项数逐轮变多）。
"""
import io
import os
import shutil
import sys

BASE = "C:/Users/user/Desktop/2/mzycBetterMTR-1.20.4/"
SL = BASE + "src/main/java/smooth/lift/SmoothLift.java"
SCREEN = BASE + "src/client/java/smooth/lift/client/MbmHelpScreen.java"
CMD = BASE + "_tools/CmdTreeCheck.java"
BK_DIR = os.path.join(os.environ.get("LOCALAPPDATA", "C:/Users/user/AppData/Local"), "Temp")
FILES = {"sl": SL, "screen": SCREEN, "cmd": CMD}
BK = {k: os.path.join(BK_DIR, "bk_preset_" + os.path.basename(v)) for k, v in FILES.items()}

ACT = sys.argv[1]

if ACT == "backup":
    for k, v in FILES.items():
        shutil.copyfile(v, BK[k])
    print("backup ok -> %s" % BK_DIR)
    sys.exit(0)

if ACT == "restore":
    for k, v in FILES.items():
        shutil.copyfile(BK[k], v)
    print("restored")
    sys.exit(0)


def sub(path, old, new, why):
    src = io.open(path, encoding="utf-8").read()
    n = src.count(old)
    assert n == 1, "锚点命中 %d 次（应为 1）：%r" % (n, old)
    io.open(path, "w", encoding="utf-8", newline="").write(src.replace(old, new))
    print("  patched %s（%s）" % (path.rsplit("/", 1)[-1], why))


if ACT == "A":
    sub(SL, '"pbmnarrate off -f",\n    };\n\n    /**\n     * 【1.58】「空白预设」',
        '"pbmnarrate shanghai -f",\n    };\n\n    /**\n     * 【1.58】「空白预设」',
        "简单港铁换回开启(上海)")
elif ACT == "B":
    # 把空白预设里那一行连同注释整段删掉（= 续 2 的行为）
    sub(SL, '            "pbmmusic close -f none",\n'
            '            // ★【09-28 续 4】进站广播（讲述人）＝**关闭**。\n'
            '            "pbmnarrate off -f",\n',
        '            "pbmmusic close -f none",\n',
        "空白预设删掉讲述人那一条")
elif ACT == "C":
    sub(SCREEN, "        } else if (ID_SIMPLE.equals(presetId) || ID_BLANK.equals(presetId)) {\n"
                "            TrainAnnounceSwitch.disableForPreset();\n"
                "        }\n",
        "        }\n",
        "sendPreset 不再关总闸")
elif ACT == "D":
    # 只把 CmdTreeCheck 的简单港铁改回 shanghai（手抄清单与 SmoothLift 分叉）
    sub(CMD, '                        // 【09-28 续 4】进站广播（讲述人）＝**关闭**（用户点名：简单港铁要关讲述人）\n'
             '                        "pbmnarrate off -f"},\n'
             '                {"空白预设",',
        '                        "pbmnarrate shanghai -f"},\n'
        '                {"空白预设",',
        "手抄清单里简单港铁没跟着改")
elif ACT == "E":
    # 【09-29】简单港铁的直梯两条：把「素材层 none」改回「子开关层 off」= 本轮 bug 的字面形态
    sub(SL, '            // ★ 用户原文 `-f off` → **素材层**正名 `none`（见上面 ★★★；写 off 会把子开关焊死）\n'
            '            "lifthelp open -f none",\n',
        '            "lifthelp open -f off",\n',
        "简单港铁直梯开门改回 off（闸门焊死）")
elif ACT == "F":
    # 【09-29】经典港铁只写开关、不写素材 ⇒ 素材=none 的存档救不回来
    sub(SL, '            "lifthelp open -f default",\n'
            '            "lifthelp close -f default",\n',
        '',
        "经典港铁不再把直梯素材写回 default")
else:
    raise SystemExit("unknown action")
print("mutation %s applied" % ACT)
