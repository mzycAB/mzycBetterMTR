# -*- coding: utf-8 -*-
"""离线守则校验：**MTR3 站台数据层绑定不得再用「字符串方法名反射」取 BlockPos 分量**
（2026-10-01 LOG115 现场钉死）。

## 背景
1.20.1 + MTR3（3.2.2-hotfix-1）+ smooth_lift 1.29.1201 时，「站台播报（含讲述人）与
进站播报（含讲述人）」全部失效。日志两条关键行：
```
读不到 MTR3 站台数据层（java.lang.NoSuchMethodException: net.minecraft.class_2338.getX()）
屏蔽门 @[-63,-20,32] 认不到 MTR 站台 ⇒ 到站播报 / 进站报站会跳过这一串
```
根因：`MtrDwellAccess.bindMtr3()` 里对 BlockPos 做 `blockPos.getMethod("getX")` ——
**字符串方法名不会被 loom remap**：生产 jar 运行期 BlockPos 是 intermediary 名
（`net.minecraft.class_2338`，方法叫 `method_xxxxx`）⇒ `getMethod("getX")` 必然
`NoSuchMethodException` ⇒ MTR3 数据层绑定失败 ⇒ `platformId` 全认不到
（`isPlatformKnown` 恒 false）⇒ 所有吃站台身份的播报（到站/进站素材 + 两条讲述人）
被「认不到站台就跳过」的判据静默吞掉。降级文案还写着「功能照常，只是第一次停站没人声」——
**严重误导**：它把整个站台识别一起带崩了。

## 修法（本脚本要钉住的形态）
BlockPos 的分量读取**改编译期调用**：`((net.minecraft.core.BlockPos) pos).getX()` ——
invokevirtual 会被 loom remap 成运行期正确的 intermediary 方法名，天然跨环境。
MTR4 的 Position 是 MTR 自己的类（不 remap），保留反射无妨。

## 布局
本脚本在 1.20.4 / 1.20.1(Fabric) / Forge-1.20.1 三处共用（MtrDwellAccess 三版本应逐字节相同）；
路径解析探测式（client 在 src/client/java，Forge 在 src/main/java）。
"""

import io
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label,
                         ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def main():
    target = None
    for rel in (("src", "client", "java", "smooth", "lift", "client", "MtrDwellAccess.java"),
                ("src", "main", "java", "smooth", "lift", "client", "MtrDwellAccess.java")):
        p = os.path.join(PROJ, *rel)
        if os.path.isfile(p):
            target = p
            break
    if not target:
        print("[FAIL] 找不到 MtrDwellAccess.java")
        sys.exit(1)
    print("  MtrDwellAccess  " + os.path.relpath(target, PROJ).replace("\\", "/"))
    src = io.open(target, encoding="utf-8", errors="replace").read()
    body = strip_comments(src)

    # ① 不得再有「按字符串名反射 BlockPos 的 getX/getY/getZ」
    bad = [m.group(0) for m in re.finditer(r"getMethod\(\s*\"get[XYZ]\"", body)]
    check(not bad,
          "MTR3 分支不再反射 BlockPos.getX/getY/getZ（字符串名不会被 remap ⇒ 生产必 NoSuchMethodException）",
          ("命中：" + "、".join(bad)) if bad else "干净")
    check("mtr3PosGetX" not in body and "mtr3PosGetY" not in body and "mtr3PosGetZ" not in body,
          "mtr3PosGetX/Y/Z 字段已彻底移除")

    # ② px/py/pz 的 MTR3 分支 = 编译期 ((net.minecraft.core.BlockPos) pos).getX/Y/Z
    for axis in ("X", "Y", "Z"):
        m = re.search(r"if\s*\(\s*mode\s*==\s*MODE_MTR3\s*\)\s*\{\s*return\s*"
                      r"\(\(net\.minecraft\.core\.BlockPos\)\s*pos\)\.get%s\(\)" % axis, body)
        check(m is not None,
              "px/py/pz 的 MTR3 分支用 ((BlockPos) pos).get%s()（remap 自动对）" % axis,
              "MODE_MTR3 分支形态")
        if m is None:
            for lm in re.finditer(r"px\(Object pos\)|py\(Object pos\)|pz\(Object pos\)", body):
                pass  # 仅定位用

    # ③ 绑定失败降级文案不再误导（写明「认不到站台 ⇒ 播报会跳」）
    check("功能照常" not in body,
          "MTR3 绑定失败的降级文案不再写「功能照常」—— 它把站台识别一起带崩，必须如实")

    print()
    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("   - " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()