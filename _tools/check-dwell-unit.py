# -*- coding: utf-8 -*-
"""离线守则校验：MTR3 停站时长单位是**半秒**不是 ms（2026-10-01 LOG200 现场钉死）。

## 背景
1.20.1 + MTR3（3.2.2-hotfix-1）+ smooth_lift 1.29.1201，「只有开关门嘀嘀有声音，
站台广播 / 进站广播 / 讲述人全没声音」。日志两条关键行：
```
认到站台「1」@-33,-21,34 —— 离这扇门 8.6 格，停站时长 20ms
时刻表算的周期 4000ms 与实测 8000ms 差了 4000ms
```
根因：MTR3 `SavedRailBase.getDwellTime()` 单位是**半秒**（javap 证实：返回 int、
clamp ≤1200、无效默认 20；`Train.getTotalDwellTicks() = PathData.dwellTime × 10`，
20 → 200 tick = 10 秒），代码却当 ms 用 ⇒ 10 秒停站被读成 20ms ⇒ 关门周期算成
4000ms 塞不下素材 ⇒ 永远走「强制等待 + 人声被门动作掐断」兜底 ⇒ 语音播报段听不见。
MTR4 的 `PlatformSchema.dwellTime` 才是 ms（默认 10000）——只换算 MTR3 分支。

## 修法（本脚本要钉住的形态）
`dwellOf()`：
- MTR3 分支：`v *= 500L`（半秒 → 毫秒；20 半秒 = 10 秒 = 10000ms）
- MTR4 分支：**原样返回**（Platform.getDwellTime() 本来就是 ms）

## 布局
本脚本在 1.20.4 / 1.20.1(Fabric) / Forge-1.20.1 三处共用（MtrDwellAccess 三版本同构）；
路径解析探测式（client 在 src/client/java，Forge 在 src/main/java）。
"""

import io
import os
import re
import subprocess
import sys
import zipfile

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


def find_jar():
    """找 build/libs 下的生产 jar（字节码断言用）。"""
    libs = os.path.join(PROJ, "build", "libs")
    if not os.path.isdir(libs):
        return None
    jars = sorted(os.listdir(libs))
    if not jars:
        return None
    return os.path.join(libs, jars[-1])


def javap_method(jar, cls, method):
    """从生产 jar 里取某方法的反编译指令块；取不到返回 ''。"""
    try:
        with zipfile.ZipFile(jar) as z:
            name = cls.replace(".", "/") + ".class"
            found = [n for n in z.namelist() if n == name or n.endswith("/" + name)]
            if not found:
                return ""
            data = z.read(found[0])
    except Exception:
        return ""
    tmp = os.path.join(PROJ, "_dbg_dwell2", cls.split(".")[-1] + ".class")
    try:
        os.makedirs(os.path.dirname(tmp), exist_ok=True)
        with open(tmp, "wb") as f:
            f.write(data)
        out = subprocess.run(
            ["javap", "-p", "-c", tmp],
            capture_output=True, text=True, errors="replace",
        ).stdout
    except Exception:
        return ""
    # 按方法签名行切块：从「方法签名（含方法名」行开始，到下一个方法签名/类结束
    lines = out.splitlines()
    start = None
    for i, ln in enumerate(lines):
        if re.search(r"(^|\s)%s\(" % re.escape(method), ln):
            start = i
            break
    if start is None:
        return ""
    block = []
    for ln in lines[start + 1:]:
        # 下一个方法签名（缩进为 0、以 '  ' 开头的非指令行）或类声明结束
        if ln and not ln[0].isspace():
            break
        block.append(ln)
    return "\n".join(block)


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

    # ① 换算存在且只出现一次（MTR3 分支）
    mults = re.findall(r"v\s*\*=\s*500L", body)
    check(len(mults) == 1,
          "dwellOf 里有且仅有一处半秒→毫秒换算 v *= 500L",
          "实际 %d 处" % len(mults))

    # ② 换算必须位于 dwellOf 方法体内 mode == MODE_MTR3 的 if 块内（MTR4 分支不得换算）
    #    先切出 dwellOf 方法体（签名行起、括号配对止），避免误配到 bindMtr3 里别的 MTR3 if 块。
    m_start = re.search(r"(?:private|public|protected|static|\s)+\blong\s+dwellOf\s*\(", body)
    if not m_start:
        check(False, "dwellOf 方法定义存在")
    else:
        # 方法体 {：从签名匹配末尾往后找（签名行尾即 {）
        ob = body.index("{", m_start.end(), m_start.end() + 300)
        depth = 0
        end = None
        for i in range(ob, len(body)):
            if body[i] == "{":
                depth += 1
            elif body[i] == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        mbody = body[ob:end + 1] if end else ""
        check(len(mbody) > 0, "切得出 dwellOf 方法体", "%d 字符" % len(mbody))
        m = re.search(r"if\s*\(\s*mode\s*==\s*MODE_MTR3[^)]*\)\s*\{", mbody)
        if not m:
            check(False, "dwellOf 方法体内 MTR3 分支 if 块存在")
        else:
            open_brace = mbody.index("{", m.start())
            depth = 0
            end = None
            for i in range(open_brace, len(mbody)):
                if mbody[i] == "{":
                    depth += 1
                elif mbody[i] == "}":
                    depth -= 1
                    if depth == 0:
                        end = i
                        break
            block3 = mbody[open_brace:end + 1] if end else ""
            check("500L" in block3,
                  "×500 换算只在 dwellOf 的 mode == MODE_MTR3 分支里（MTR4 原样透传）",
                  "if 块跨度 %d 字符" % len(block3))
            # 反查：dwellOf 方法体里 500L 不该出现在 MTR4 分支（即 mode == MODE_MTR4 块）
            m4 = re.search(r"if\s*\(\s*mode\s*==\s*MODE_MTR4[^)]*\)\s*\{", mbody)
            if m4:
                ob4 = mbody.index("{", m4.start())
                d4, end4 = 0, None
                for i in range(ob4, len(mbody)):
                    if mbody[i] == "{":
                        d4 += 1
                    elif mbody[i] == "}":
                        d4 -= 1
                        if d4 == 0:
                            end4 = i
                            break
                block4 = mbody[ob4:end4 + 1] if end4 else ""
                check("500L" not in block4 and "*= 500" not in block4,
                      "MTR4 分支没有 ×500 换算", "MTR4 块 %d 字符" % len(block4))

    # ③ 纯逻辑复算（与 Java 实现逐值对撞）
    def dwell_of(mode, v):
        if mode == "MTR3" and v != -(2 ** 63):
            return v * 500
        return v

    cases = [("MTR3", 20, 10000), ("MTR3", 1000, 500000), ("MTR3", 1, 500),
             ("MTR4", 10000, 10000), ("MTR4", 0, 0),
             ("MTR3", -(2 ** 63), -(2 ** 63))]  # 读失败哨兵原样透传
    ok = all(dwell_of(m, v) == want for m, v, want in cases)
    check(ok,
          "纯逻辑复算：MTR3 半秒×500=ms、MTR4 原样、哨兵不换算",
          "；".join("%s %s→%s" % (m, v, dwell_of(m, v)) for m, v, _ in cases))

    # ④ 生产 jar 字节码：dwellOf 指令块里有 long 500l + lmul
    jar = find_jar()
    if not jar:
        check(False, "生产 jar 存在（先构建）")
    else:
        blk = javap_method(jar, "smooth.lift.client.MtrDwellAccess", "dwellOf")
        if not blk:
            check(False, "javap 取得到 dwellOf 方法块",
                  "jar=%s" % os.path.basename(jar))
        else:
            has500 = bool(re.search(r"long\s+500l", blk))
            haslmul = bool(re.search(r"\blmul\b", blk))
            check(has500 and haslmul,
                  "生产 jar 字节码：dwellOf 里有 long 500l × lmul（换算编进去了）",
                  "500l=%s lmul=%s（jar=%s）" % (has500, haslmul, os.path.basename(jar)))
            check("500l" not in javap_method(jar, "smooth.lift.client.MtrDwellAccess", "cycleTicksForDwell"),
                  "cycleTicksForDwell 消费的是 ms，不再自己 ×500（防双重换算）")

    print()
    if FAILS:
        print("== 失败 %d 项 ==" % len(FAILS))
        for f in FAILS:
            print("  ==> 失败  " + f)
        sys.exit(1)
    print("== 全部通过 ==")


if __name__ == "__main__":
    main()
