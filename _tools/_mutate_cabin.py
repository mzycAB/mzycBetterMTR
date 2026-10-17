# -*- coding: utf-8 -*-
"""反向对照（mutation test）：给【1.52】「真实轿厢盒」的回归断言做「注入变体 → 必须变红」。

## 为什么需要它

`check-lift-move-sound.py` 里【1.52】新增的那批断言，如果写成了**恒真**的（比如正则根本
匹配不上却用了 `is not None` 的反面、或者数值镜像自己算错了），它会一直绿着，等于没查。
唯一能证明「断言有鉴别力」的办法就是：**把正确代码改坏，看它红不红**。

## 每一条变异

都是一次**精确文本替换**（`old` 必须在文件里**恰好出现一次**，否则拒绝执行）。
注入后跑 `check-lift-move-sound.py`；只要**多出**至少一条 `[FAIL]` 就算这条断言有鉴别力。
跑完全部自动还原，并打印 md5 证明还原到位。

## 用法

    python _tools/_mutate_cabin.py all        # 一键跑完 A~M 并还原（推荐）
    python _tools/_mutate_cabin.py backup     # 手动备份
    python _tools/_mutate_cabin.py A          # 只注入 A（自己再跑 check 脚本）
    python _tools/_mutate_cabin.py restore    # 还原

退出码 0 = 每条变异都成功让回归变红（断言不是摆设）。
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "_tools")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
CHIME = os.path.join(CLIENT, "LiftChimePlayer.java")
ACCESS = os.path.join(CLIENT, "MtrLiftAccess.java")
CHECK = os.path.join(TOOLS, "check-lift-move-sound.py")
BAK = os.path.join(TOOLS, "_mutate_cabin_bak")

FILES = {CHIME: "LiftChimePlayer.java", ACCESS: "MtrLiftAccess.java"}

# (键, 说明, 文件, old, new, 该锚点应出现的次数)
MUTATIONS = [
    ("A", "beyondCabin：竖直判定被短路（= 忘了「行进中基准会偏」那类改法）", CHIME,
     "if (cabin.spacing() > 0.0) {", "if (false) {", 1),
    ("B", "beyondCabin：竖直容差取 0（乘客会被判成厢外）", CHIME,
     "double slack = cabin.spacing();", "double slack = 0.0;", 1),
    ("C", "beyondCabin：半宽退回**写死的旧半径**（= 1.52 白做）", CHIME,
     "(cabin.halfWidth() + CABIN_MARGIN)", "(CABIN_RADIUS_H - CABIN_MARGIN)", 1),
    ("D", "beyondCabin：圆心退回「楼层方块」而不是轿厢中心", CHIME,
     "Math.abs(player.x - cabin.centerX())", "Math.abs(player.x - sound.x)", 1),
    ("E", "spatialFactor：盒里判定改成严格小于（盒面那一刻掉档）", CHIME,
     "if (beyond <= 0.0) {", "if (beyond < 0.0) {", 1),
    ("F", "OUTSIDE_CABIN_FACTOR 改成 1.0（= 不区分厢内外）", CHIME,
     "OUTSIDE_CABIN_FACTOR = 0.2f;", "OUTSIDE_CABIN_FACTOR = 1.0f;", 1),
    ("G", "spatialFactor：永远走回落分支（真实盒根本没接上）", CHIME,
     "double beyond = beyondCabin(player, sound, cabin);",
     "double beyond = beyondCabin(player, sound, null);", 1),
    ("H", "bindCabinGeometry：读不到时 throw（会把提示音**全部**弄哑）", ACCESS,
     "            cabinGeometryOk = false;\n        }\n        if (!cabinGeometryOk) {",
     "            throw new IllegalStateException(\"no geometry\");\n        }\n        if (!cabinGeometryOk) {", 1),
    ("I", "bindCabinGeometry：读不全也硬说 OK（cabin 会给半个盒子）", ACCESS,
     "        } catch (Throwable t) {\n            cabinGeometryOk = false;\n        }",
     "        } catch (Throwable t) {\n            cabinGeometryOk = true;\n        }", 1),
    ("J", "tick()：忘了把实例自己那份 cabin 传下去", CHIME,
     "new Vec3(this.x, this.y, this.z), this.cabin);", "new Vec3(this.x, this.y, this.z), null);", 1),
    ("K", "play()：轿厢盒没传给实例（两个分支一起，永远回落旧圆）", CHIME,
     "inst.setPosition(pos, baseVolume, cabin);", "inst.setPosition(pos, baseVolume, null);", 2),
    ("L", "MtrLiftAccess：不再读 MTR4 的轿厢宽度", ACCESS,
     'mtr4GetWidth = method(lift, "getWidth");', "mtr4GetWidth = null;", 1),
    ("M", "LiftView：不把 cabin 带进快照", ACCESS,
     "float doorFraction, Move move,\n                           Cabin cabin) {",
     "float doorFraction, Move move,\n                           Cabin cabinX) {", 1),
    ("N", "MTR3 路径：不读 liftWidth（只剩 MTR4 能定位轿厢）", ACCESS,
     'mtr3LiftWidth = fieldInHierarchy(lift, "liftWidth");', "mtr3LiftWidth = null;", 1),
]


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as fh:
        h.update(fh.read())
    return h.hexdigest()


def backup():
    os.makedirs(BAK, exist_ok=True)
    for path, name in FILES.items():
        shutil.copy2(path, os.path.join(BAK, name))
    print("已备份：%s" % ", ".join(FILES.values()))


def restore():
    for path, name in FILES.items():
        src = os.path.join(BAK, name)
        if not os.path.isfile(src):
            print("!! 没有备份，无法还原：%s" % name)
            return False
        shutil.copy2(src, path)
    print("已还原：%s" % ", ".join(
        "%s md5=%s" % (name, md5(os.path.join(CLIENT, name))) for name in FILES.values()))
    return True


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def run_check():
    """跑回归脚本，返回它报出的 [FAIL] 行集合。"""
    proc = subprocess.run([sys.executable, CHECK], cwd=ROOT, capture_output=True,
                          text=True, encoding="utf-8", errors="replace")
    return set(re.findall(r"^\[FAIL\] .*$", proc.stdout, re.M))


def apply_one(key):
    for k, desc, path, old, new, expected in MUTATIONS:
        if k != key:
            continue
        text = read(path)
        n = text.count(old)
        if n != expected:
            print("!! 变异 %s 的锚点在 %s 里出现 %d 次（要求 %d 次），已跳过 —— "
                  "多半是上一轮改过代码，需要同步更新锚点" % (key, FILES[path], n, expected))
            return False
        write(path, text.replace(old, new))
        print("已注入 %s：%s" % (key, desc))
        return True
    print("!! 未知变异键：%s" % key)
    return False


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    cmd = args[0]

    if cmd == "backup":
        backup()
        return 0
    if cmd == "restore":
        return 0 if restore() else 1
    if cmd != "all":
        backup()
        ok = apply_one(cmd)
        print("（记得 python _tools/_mutate_cabin.py restore）")
        return 0 if ok else 1

    before_md5 = {name: md5(os.path.join(CLIENT, name)) for name in FILES.values()}
    backup()
    baseline = run_check()
    print("基线（未注入）失败 %d 条" % len(baseline))

    ok_count = 0
    for k, desc, path, old, new, expected in MUTATIONS:
        text = read(path)
        if text.count(old) != expected:
            print("[跳过] %s %s —— 锚点出现 %d 次（要求 %d 次）"
                  % (k, desc, text.count(old), expected))
            continue
        write(path, text.replace(old, new))
        after = run_check()
        new_fails = after - baseline
        write(path, text)                            # 立刻回滚这一条（text = 注入前的原文）
        if new_fails:
            ok_count += 1
            print("[变红] %s %s" % (k, desc))
            print("       新失败 %d 条，例如：%s" % (
                len(new_fails), sorted(new_fails)[0][:110]))
        else:
            print("[★恒真] %s %s —— 注入后**一条新失败都没有**，这条断言是摆设！" % (k, desc))

    restore()
    after_md5 = {name: md5(os.path.join(CLIENT, name)) for name in FILES.values()}
    same = before_md5 == after_md5
    print("\n还原校验：%s" % ("md5 全部一致" if same else "!!! md5 不一致，请手工检查 !!!"))
    print("有鉴别力的变异：%d / %d" % (ok_count, len(MUTATIONS)))
    return 0 if (ok_count == len(MUTATIONS) and same) else 1


if __name__ == "__main__":
    sys.exit(main())
