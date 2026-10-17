# -*- coding: utf-8 -*-
"""离线校验：【1.27】修**原版 MTR 自带**的「扶梯贴着完整方块 ⇒ 扶梯贴图变黑」。

用户原话：

    「这个mod能不能修复原版mtr就存在的扶梯碰到完整方块扶梯的贴图会变黑的bug
      模组版本：fabric1.20.4，mod版本号1.27.1204」

## 现象与根因（1.20.4 字节码实测，不是猜的）

扶梯外壳（侧板那条 `#particle` 面）只要 **挨着一个完整实心方块**，那一处贴图变纯黑；
把方块拆掉就恢复。**不装本模组也复现** ⇒ 是 MTR 的 bug。

两件事拼出来：

1. **这个面永远不会被剔除。** 原版 `Block.shouldRenderFace`：

       VoxelShape shape = state.getFaceOcclusionShape(level, pos, side);
       if (shape.isEmpty()) return true;          // ← 不剔除

   而 MTR 的 `BlockEscalatorSide.getCullingShape2` 就是 **`VoxelShapes.empty()`**
   （本脚本第 8 步直接从 MTR jar 字节码里读出来，不是抄注释）。
   `BlockStateBase.getFaceOcclusionShape` 默认委派给 `getOcclusionShape`
   （字节码里的 `occlusionShapes[]` 缓存就是这个），所以拿到空形状
   ⇒ 命中 `isEmpty() → iconst_1 / ireturn` ⇒ 邻块再实心也不剔除，面照画。

2. **画出来的那个面，取光点在邻块里。** 原版按「面朝哪边就去那一格取光」：

   - **AO 路径**（侧板模型 `ambientocclusion` 默认 true）：
     `ModelBlockRenderer$AmbientOcclusionFace.calculate(level, state, pos, dir, shape, flags, shade)`
     内部 `pos.setWithOffset(corners[i])` → `level.getBlockState(cornerPos)` →
     `Cache.getLightColor(sampleState, level, cornerPos)`。
   - **平面路径**（扶梯斜坡模型 `escalator_step_slope_*_base` 写了 `"ambientocclusion": false`）：
     `tesselateWithoutAO` 与 `renderModelFaceFlat` 里直接
     `LevelRenderer.getLightColor(level, state, pos.relative(quad.getDirection()))`。

   邻块是实心方块 ⇒ 那格的光是 **0**（光传不进不透光方块）⇒ 顶点光图 0 ⇒ **纯黑**。
   又因为第 1 条没剔除，这个黑面被实实在在画了出来。

**为什么不能「给扶梯补一个遮挡形状」来修**：扶梯几何是跨格的，若按整格遮挡，
邻块朝向扶梯的那一面会被剔除 ⇒ 露出扶梯内部 = **X 光透视方块**（1.44 踩过的坑）。
⇒ 只能修**取光**，不碰遮挡形状。本脚本第 4 步专门守这条。

## 修法

`EscalatorLightRepair`（共用判据）+ 三个客户端 mixin：

- 某次取光**取到纯黑（== 0）**时，改用「扶梯自己那一格」的亮度；
- 判据只认「**采样值 == 0**」，**不**去推断「采样格是不是被实心方块埋住」——
  采样点是原版自己算出来的角点（`base.offset(corners[i])`），推断差一格判据就静默失效，
  这正是 1.27 第一版修不干净的原因；
- 扶梯自己那格也是 0（真在黑屋子里）时**交回原版**，不硬点亮。

★ 另有一道**重入闸** `IN_REPAIR`：`LevelRenderer.getLightColor(level, pos)` 这个 **2 参**重载
的方法体就是「转调 3 参」，而 3 参正是被 `EscalatorFlatLightMixin` 挂过的方法
⇒ 不设闸就会「借光 → 2 参 → 3 参 → mixin → 借光 ……」无限递归（StackOverflowError）。
所以本脚本第 2 步会把「开闸必须写在任何取光之前、并在 finally 复位」也验一遍。

## 本节查什么（源码剥注释 + 字节码双验 + 反向对照）

1.  四个源文件存在；
2.  `EscalatorLightRepair` 结构：哨兵值 / ThreadLocal 安全 / **重入闸**（开闸必须在任何取光之前、
    且在 finally 复位）/ 判据只认「采样值 == 0」/ 扶梯格也黑时不硬点亮；
3.  三个 mixin 的 `@Mixin` 目标与 `@Inject` 注入点（描述符逐字）；
4.  ★ **反面对照**：全工程没有任何「补遮挡形状」式 mixin（那会换来 X 光），
    也没有扶梯模型资源覆盖（不许靠改 AO/cullface 蒙混）；
5.  自绘引擎侧本来就不踩这个 bug（取的是扶梯**自己那一格**的光）⇒ 修复只需管 MTR 那半；
6.  产物校验：3 个 mixin class + 判据类真在 jar 里；
7.  ★ refmap 真把三处注入点 remap 到了 intermediary（`class_778$class_4303` /
    `class_778$class_780` / `class_761`），**并且**没有漏 remap（目标名仍是 mojmap 就是漏了）；
8.  ★ MTR 字节码复核：`BlockEscalatorSide.getCullingShape2` 的方法体**只有** `VoxelShapes.empty`；
9.  ★ MC 字节码复核：`shouldRenderFace` 确有 `isEmpty() → return true` 分支；
    `calculate` / `Cache.getLightColor` / `LevelRenderer.getLightColor` 三个签名与
    mixin 里的描述符**逐字相同**；`tesselateWithoutAO` + `renderModelFaceFlat` 都在调
    `LevelRenderer.getLightColor`；★ 并且验出 **2 参 `getLightColor` 的方法体就是转调 3 参**
    （`IN_REPAIR` 重入闸的存在理由，机器可验）。

用法：`python _tools/check-escalator-light-repair.py`
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
MIXIN_DIR = os.path.join(CLIENT, "mixin")

REPAIR = os.path.join(CLIENT, "EscalatorLightRepair.java")
STASH = os.path.join(MIXIN_DIR, "EscalatorAoStashMixin.java")
SAMPLE = os.path.join(MIXIN_DIR, "EscalatorAoSampleMixin.java")
FLAT = os.path.join(MIXIN_DIR, "EscalatorFlatLightMixin.java")
CACHE = os.path.join(CLIENT, "EscalatorStepCache.java")
CLIENT_MIXINS_JSON = os.path.join(ROOT, "src", "client", "resources",
                                  "smoothlift.client.mixins.json")

# ---- 期望的描述符（第 9 步会拿真实字节码逐字复核这三个常量）----
STASH_DESC = ("calculate(Lnet/minecraft/world/level/BlockAndTintGetter;"
              "Lnet/minecraft/world/level/block/state/BlockState;"
              "Lnet/minecraft/core/BlockPos;"
              "Lnet/minecraft/core/Direction;"
              "[FLjava/util/BitSet;Z)V")
SAMPLE_DESC = ("getLightColor(Lnet/minecraft/world/level/block/state/BlockState;"
               "Lnet/minecraft/world/level/BlockAndTintGetter;"
               "Lnet/minecraft/core/BlockPos;)I")
FLAT_DESC = ("getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;"
             "Lnet/minecraft/world/level/block/state/BlockState;"
             "Lnet/minecraft/core/BlockPos;)I")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_java(src, keep_strings=False):
    """剥掉 Java 注释（// 与 /* */），只留下**可执行代码**。

    必须剥：这些文件的 Javadoc 里大段写着「为什么能这么修 / 不能那么修」，
    直接 grep 会把**解释性文字**误判成代码（例如注释里出现 `getOcclusionShape`，
    那是说明「别动它」，不是「动了它」）。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            start = i
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
            i += 1
            out.append(src[start:i] if keep_strings else '""')
        else:
            out.append(c)
            i += 1
    return "".join(out)


def java_body(code, header_re):
    """截出某个方法/构造器的**方法体**（按花括号配对）。没找到返回空串。"""
    m = re.search(header_re, code)
    if not m:
        return ""
    i = code.find("{", m.end())
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(code)):
        if code[j] == "{":
            depth += 1
        elif code[j] == "}":
            depth -= 1
            if depth == 0:
                return code[i:j + 1]
    return code[i:]


def javap_body(text, header_re):
    """从 javap 文本里截出某个成员的后继块（`Code:` + 指令行）。

    javap 的成员声明缩进 2 空格、`Code:` 缩进 4、指令行缩进 ≥6，
    所以「回到缩进 ≤2 的非空行」就是下一个成员。
    """
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if re.search(header_re, ln):
            out = []
            for cur in lines[i + 1:]:
                if not cur.strip():
                    out.append(cur)
                    continue
                indent = len(cur) - len(cur.lstrip(" "))
                if indent <= 2:
                    break
                out.append(cur)
            return "\n".join(out)
    return ""


def extract_concat(src, arg):
    """取 `arg = "..." + "..." + "..."` 里所有字面量拼起来的结果。

    `src` 必须是 keep_strings=True 剥注释后的代码。
    """
    m = re.search(arg + r'\s*=\s*(.*?),\s*(?:at|args|name)\s*=', src, re.S)
    if not m:
        m = re.search(arg + r'\s*=\s*(.*?)\)', src, re.S)
    if not m:
        return None
    return "".join(re.findall(r'"([^"]*)"', m.group(1)))


def find_javap():
    home = os.path.expanduser("~")
    for jdk in ("eclipse_adoptium-17-amd64-windows.2", "jdk-21.0.11.10-hotspot"):
        for exe in ("javap", "javap.exe"):
            p = os.path.join(home, ".gradle", "jdks", jdk, "bin", exe)
            if os.path.isfile(p):
                return p
    for exe in ("javap", "javap.exe"):
        p = shutil.which(exe)
        if p:
            return p
    return None


def loom_jars():
    """Minecraft 1.20.4 的命名（mojmap）jar：common（方块类）+ clientOnly（渲染类）。"""
    pats = ("minecraft-common-*", "minecraft-clientOnly-*")
    jars = []
    for pat in pats:
        found = glob.glob(os.path.join(
            ROOT, ".gradle", "loom-cache", "minecraftMaven", "net", "minecraft",
            pat, "*", "*.jar"))
        if found:
            jars.append(sorted(found)[-1])
    return jars


def find_mtr_jar():
    """按**内容**认 MTR 4.0.5（1.20.4）：含 org/mtr/mod/block/BlockEscalatorSide.class。"""
    cands = []
    for d in (os.path.dirname(ROOT), ROOT):
        if not os.path.isdir(d):
            continue
        cands += sorted(glob.glob(os.path.join(d, "*.jar")))
    for path in cands:
        try:
            with zipfile.ZipFile(path) as z:
                if "org/mtr/mod/block/BlockEscalatorSide.class" in z.namelist():
                    return path
        except Exception:
            continue
    return None


def our_jar():
    found = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "mzycBetterMTR-*.jar")),
                   key=os.path.getmtime)
    return found[-1] if found else None


def to_descriptor(signature_params, ret):
    """把 javap 风格的参数表转成 JVM 描述符参数串（断言 mixin 描述符逐字用）。"""
    prim = {"boolean": "Z", "byte": "B", "char": "C", "short": "S",
            "int": "I", "long": "J", "float": "F", "double": "D", "void": "V"}
    out = ""
    for raw in [p.strip() for p in signature_params.split(",") if p.strip()]:
        arr = ""
        while raw.endswith("[]"):
            arr += "["
            raw = raw[:-2].strip()
        if raw in prim:
            out += arr + prim[raw]
        else:
            out += arr + "L" + raw.replace(".", "/") + ";"
    return "(" + out + ")" + prim.get(ret.strip(), "L" + ret.strip().replace(".", "/") + ";")


def javap(javap_exe, cp_jars, cls):
    cp = os.pathsep.join(cp_jars)
    try:
        r = subprocess.run([javap_exe, "-p", "-c", "-cp", cp, cls],
                           capture_output=True, text=True, errors="replace")
        return r.stdout
    except Exception:
        return ""


# ======================================================================
# 1) 四个源文件存在
# ======================================================================
print("== 1) 源文件 ==")
for path, label in (
        (REPAIR, "共用判据 EscalatorLightRepair.java"),
        (STASH, "mixin EscalatorAoStashMixin.java（记录正在画的扶梯格）"),
        (SAMPLE, "mixin EscalatorAoSampleMixin.java（AO 路径取光修正）"),
        (FLAT, "mixin EscalatorFlatLightMixin.java（平面路径取光修正）")):
    check(os.path.isfile(path), "存在：" + label, os.path.relpath(path, ROOT))

repair_code = strip_java(load(REPAIR)) if os.path.isfile(REPAIR) else ""
stash_code = strip_java(load(STASH), keep_strings=True) if os.path.isfile(STASH) else ""
sample_code = strip_java(load(SAMPLE), keep_strings=True) if os.path.isfile(SAMPLE) else ""
flat_code = strip_java(load(FLAT), keep_strings=True) if os.path.isfile(FLAT) else ""

# ======================================================================
# 2) EscalatorLightRepair 结构
# ======================================================================
print()
print("== 2) 判据类结构 ==")

m = re.search(r"NOT_REPAIRED\s*=\s*(-?\d+)", repair_code)
sentinel = int(m.group(1)) if m else None
check(sentinel == -1,
      "哨兵 NOT_REPAIRED == -1（负数，与任何合法 packed light（≥0）都不冲突）",
      "实际 %s" % sentinel)

check("Long.MAX_VALUE" in repair_code and re.search(r"NO_OWN\s*=\s*Long\.MAX_VALUE", repair_code),
      "NO_OWN == Long.MAX_VALUE（BlockPos.asLong() 的取值不可能到它）")

check("ThreadLocal<long[]> AO_OWN" in repair_code.replace("  ", " ") or
      re.search(r"ThreadLocal<\s*long\[\]\s*>\s*AO_OWN", repair_code),
      "AO 用的「当前扶梯格」是 ThreadLocal<long[]>（区块网格在 worker 线程编译，静态字段会被踩）")
check("withInitial" in repair_code,
      "ThreadLocal 走 withInitial（热路径 get() 不再有 null 判定）")

check("EscalatorUtil.isEscalatorStep" in repair_code and "EscalatorUtil.isEscalatorSide" in repair_code,
      "识别扶梯复用 EscalatorUtil.isEscalatorStep / isEscalatorSide（不硬编码 MTR 类）")

check("lastOther" in repair_code and re.search(r"block\s*==\s*lastOther", repair_code),
      "isEscalator 有单条负缓存 lastOther（挂在超热方法上，绝大多数方块要一次引用比较就退出）")

# ---- 重入闸：LevelRenderer.getLightColor 的 2 参重载会转调被 mixin 挂过的 3 参 ----
check(re.search(r"ThreadLocal<\s*Boolean\s*>\s*IN_REPAIR", repair_code),
      "★ 有重入闸 ThreadLocal<Boolean> IN_REPAIR"
      "（2 参 getLightColor 的字节码就是「转调 3 参」，3 参被 mixin 挂着 ⇒ 不设闸会无限递归）")

for name, header in (("repairAo", r"int\s+repairAo\s*\("),
                     ("repairFlat", r"int\s+repairFlat\s*\(")):
    body = java_body(repair_code, header)
    check(bool(body), "%s(level, …) 方法存在" % name)
    gate = body.find("IN_REPAIR.set(Boolean.TRUE)")
    first_light = body.find("rawLight(")
    check(gate >= 0 and first_light > gate,
          "★ %s：开闸（IN_REPAIR.set(TRUE)）写在**任何取光之前**"
          "（顺序反了 ⇒ 借光时又会被自己的 mixin 拦一次）" % name,
          "开闸@%d 首次取光@%d" % (gate, first_light))
    check("finally" in body and "IN_REPAIR.set(Boolean.FALSE)" in body,
          "★ %s：闸在 finally 里复位（抛异常也不能把闸卡住）" % name)
    check("rawLight(level, samplePos) != 0" in body.replace("  ", " "),
          "★ %s：先判「采到的光是纯黑」才动（!= 0 的绝大多数情况一次整数比较就放行）" % name)
    check("isEscalator(level.getBlockState(samplePos))" in body.replace("  ", " "),
          "★ %s：采样点自己就是扶梯格 ⇒ 直接交回原版"
          "（守住自绘引擎那条路：EscalatorStepCache.writeBlock 正是拿扶梯自己那格取光的，"
          "不能由本类改到）" % name)

ao = java_body(repair_code, r"int\s+repairAo\s*\(")
check(re.search(r"ownPos\.equals\s*\(\s*samplePos\s*\)", ao),
      "repairAo：采样格就是「正在画的扶梯那一格」时直接交回原版"
      "（原版在 calculate 里就会用扶梯自己那格取一次光，那次本来就对）")
check(re.search(r"ownLight\s*==\s*0", ao) and "NOT_REPAIRED" in ao,
      "★ 扶梯自己那格也是全黑时（ownLight == 0）交回原版，不硬点亮")

flat = java_body(repair_code, r"int\s+repairFlat\s*\(")
check("Direction.values()" in flat and re.search(r"light\s*>\s*best", flat),
      "repairFlat：在采样点周围 6 格里取**最亮**的那一格扶梯格借光"
      "（阶梯块与它正上方的侧板成对，取最亮才不会挑到更暗的那一格）")

# 反向对照：不许出现「直接调亮」的写法
check("setReturnValue(0" not in repair_code and "0xFFFFFF" not in repair_code
      and "15728880" not in repair_code,
      "反面对照：判据类里没有「直接给满亮 / 写死 0」的蒙混写法")
# 反向对照：不许再出现「自己推断采样格被不被埋住」那套易错判据
check("isSolidRender" not in repair_code and "getLightEmission" not in repair_code,
      "反面对照：判据类里没有 isSolidRender / getLightEmission"
      "（1.27 第一版靠它推断「采样格被埋住」，推断差一格就静默失效 —— 已改成只看「光是不是 0」）")

# ======================================================================
# 3) 三个 mixin 的目标与注入点
# ======================================================================
print()
print("== 3) 三个 mixin ==")

for code, label, target, expect_desc in (
        (stash_code, "AoStash", "net.minecraft.client.renderer.block.ModelBlockRenderer$AmbientOcclusionFace",
         STASH_DESC),
        (sample_code, "AoSample", "net.minecraft.client.renderer.block.ModelBlockRenderer$Cache",
         SAMPLE_DESC),
        (flat_code, "FlatLight", None, FLAT_DESC)):
    if target is None:
        check("@Mixin(LevelRenderer.class)" in code.replace("  ", " "),
              "%s：目标是 LevelRenderer（平面路径那个静态取光方法在这里）" % label)
    else:
        mt = re.search(r'@Mixin\(\s*targets\s*=\s*"([^"]+)"', code)
        check(bool(mt) and mt.group(1) == target,
              "%s：@Mixin targets = %s（包级私有内嵌类，只能用字符串指）" % (label, target),
              "实际 %s" % (mt.group(1) if mt else None))
    got = extract_concat(code, "method")
    check(got == expect_desc,
          "%s：@Inject method 描述符逐字正确" % label,
          "\n           期望 %s\n           实际 %s" % (expect_desc, got))
    check('@At("HEAD")' in code, "%s：注入在 HEAD" % label)

check("cancellable = true" in sample_code and "cancellable = true" in flat_code,
      "AoSample / FlatLight 都声明 cancellable = true（要 setReturnValue 顶掉原返回值）")
check("@Inject" in stash_code and "@Redirect" not in stash_code and
      "@Redirect" not in sample_code and "@Redirect" not in flat_code,
      "★ 三处都用 @Inject 而不是 @Redirect"
      "（@Redirect 的 handler 首个参数必须是 receiver 类型 Cache / AmbientOcclusionFace，"
      "那两个类是包级私有，命名空间外写不出来）")
check("remap = false" not in sample_code and "remap = false" not in flat_code
      and "remap = false" not in stash_code,
      "三处都**没有** remap = false（目标是 MC 自己的方法，必须交给 refmap 重映射）")

# ======================================================================
# 4) ★ 反面对照：不许用「补遮挡形状」这个会换来 X 光的错修法
# ======================================================================
print()
print("== 4) 反面对照：没走「补遮挡形状」这条错路 ==")

mixin_sources = []
for dirpath, _dirs, files in os.walk(os.path.join(ROOT, "src")):
    for fn in files:
        if fn.endswith(".java") and os.path.sep + "mixin" + os.path.sep in dirpath + os.path.sep:
            mixin_sources.append(os.path.join(dirpath, fn))

shape_tokens = ("getOcclusionShape", "getFaceOcclusionShape", "getCullingShape",
                "getCullingFace", "getOutlineShape")
offenders = []
for path in mixin_sources:
    code = strip_java(load(path))
    for tok in shape_tokens:
        if tok in code:
            offenders.append("%s:%s" % (os.path.basename(path), tok))
check(not offenders,
      "★ 全工程没有任何 mixin 去打「遮挡/外观形状」（给扶梯补整格遮挡 ⇒ 邻块朝它那面被剔除 ⇒ X 光）",
      "命中 %s" % offenders)

escalator_assets = glob.glob(os.path.join(
    ROOT, "src", "**", "assets", "**", "escalator_*.json"), recursive=True)
check(not escalator_assets,
      "反面对照：本模组没有覆盖任何 escalator_* 模型资源"
      "（不许靠改 ambientocclusion / cullface 蒙混过去）",
      "命中 %s" % [os.path.relpath(p, ROOT) for p in escalator_assets])

# ======================================================================
# 5) 自绘引擎侧本来就不黑 ⇒ 修复只需管 MTR 那半
# ======================================================================
print()
print("== 5) 自绘引擎侧不踩此 bug ==")
cache_code = strip_java(load(CACHE)) if os.path.isfile(CACHE) else ""
check("LevelRenderer.getLightColor(level, state, pos)" in cache_code,
      "自绘引擎 writeBlock 取的是**扶梯自己那一格**的光 ⇒ 阶梯面从来不会黑",
      "EscalatorStepCache.writeBlock")

# ======================================================================
# 6~7) 产物：class + refmap 真 remap
# ======================================================================
print()
print("== 6) 产物（jar） ==")
jar = our_jar()
if not jar:
    print("  [SKIP] 没有 build/libs/mzycBetterMTR-*.jar（先跑一次 gradlew build）")
else:
    print("  产物：%s（%d 字节）" % (os.path.relpath(jar, ROOT), os.path.getsize(jar)))
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        refmap = z.read("client-mzycBetterMTR-refmap.json").decode("utf-8") \
            if "client-mzycBetterMTR-refmap.json" in names else ""
    for cls in ("smooth/lift/client/EscalatorLightRepair.class",
                "smooth/lift/client/mixin/EscalatorAoStashMixin.class",
                "smooth/lift/client/mixin/EscalatorAoSampleMixin.class",
                "smooth/lift/client/mixin/EscalatorFlatLightMixin.class"):
        check(cls in names, "jar 内含 %s" % cls)

    print()
    print("== 7) refmap 真 remap 到 intermediary ==")
    # 1.20.4 intermediary：class_778 = ModelBlockRenderer、class_761 = LevelRenderer、
    #                      class_1920 = BlockAndTintGetter、class_2680 = BlockState、
    #                      class_2338 = BlockPos、class_2350 = Direction
    expect_map = {
        "AoSample": ("class_778$class_4303", "method_20549"),
        "AoStash": ("class_778$class_780", "method_3388"),
        "FlatLight": ("class_761", "method_23793"),
    }
    for key, (cls_int, meth_int) in expect_map.items():
        check(cls_int in refmap and meth_int in refmap,
              "refmap：%s 映到 %s / %s(intermediary)" % (key, cls_int, meth_int))
    # 漏 remap 的反面对照：注入点目标名若还是 mojmap（含 net/minecraft/...），说明没重映射
    for moj in ("net/minecraft/client/renderer/block/ModelBlockRenderer",
                "net/minecraft/client/renderer/LevelRenderer"):
        check(moj not in refmap,
              "反面对照：refmap 里没有残留 mojmap 目标名 %s（残留 = 漏 remap）" % moj)

# ======================================================================
# 8) MTR 字节码：getCullingShape2 就是空的
# ======================================================================
print()
print("== 8) MTR 字节码复核（面为什么不剔除） ==")
javap_exe = find_javap()
mtr = find_mtr_jar()
if not (javap_exe and mtr):
    print("  [SKIP] 缺 javap 或缺 MTR jar（javap=%s，MTR=%s）" % (javap_exe, mtr))
else:
    print("  MTR：%s" % os.path.basename(mtr))
    side = javap(javap_exe, [mtr], "org.mtr.mod.block.BlockEscalatorSide")
    cull = javap_body(side, r"\bgetCullingShape2\s*\(")
    check("VoxelShapes.empty" in cull,
          "★ BlockEscalatorSide.getCullingShape2 的方法体里调了 VoxelShapes.empty()"
          "（= 遮挡形状为空 ⇒ shouldRenderFace 走 isEmpty 分支 ⇒ 永不剔除）",
          "方法体首行 %s" % (cull.strip().splitlines()[:1] or [""])[0])
    body_ops = [ln for ln in cull.splitlines() if re.match(r"^\s*\d+:", ln)]
    # 反向对照：方法体只该是「取空形状 + areturn」，不许出现任何真实几何构造
    check(len(body_ops) <= 3 and not re.search(r"cuboid|block\(|fullCube|copyAndMove", cull),
          "反面对照：getCullingShape2 只构造空形状，没有任何实际几何（cuboid/block/…）",
          "%d 条指令" % len(body_ops))

# ======================================================================
# 9) MC 字节码：三条取光链路的签名 + shouldRenderFace 的 isEmpty 分支
# ======================================================================
print()
print("== 9) MC 字节码复核（取光链路与 mixin 描述符逐字对撞） ==")
mcs = loom_jars()
if not (javap_exe and mcs):
    print("  [SKIP] 缺 javap 或缺 MC 命名 jar（javap=%s，MC=%s）" % (javap_exe, mcs))
else:
    block = javap(javap_exe, mcs, "net.minecraft.world.level.block.Block")
    srf = javap_body(block, r"\bshouldRenderFace\s*\(")
    check("getFaceOcclusionShape" in srf and "isEmpty" in srf,
          "shouldRenderFace 里确有 shape.isEmpty() 判定（空形状 ⇒ 直接 return true = 不剔除）")
    check(re.search(r"isEmpty[\s\S]{0,80}?\biconst_1\b", srf) is not None,
          "★ 该分支体是 iconst_1 / ireturn（返回 true = 该面要画）",
          "分支片段 %s" % re.findall(r"isEmpty[\s\S]{0,80}", srf)[:1])

    mbr = javap(javap_exe, mcs, "net.minecraft.client.renderer.block.ModelBlockRenderer")
    aof = javap(javap_exe, mcs,
                "net.minecraft.client.renderer.block.ModelBlockRenderer$AmbientOcclusionFace")

    def declarations(text, name, ret):
        """把某个名字的**全部重载**转成 `名字(描述符)` 集合（重载会被逐个列出）。"""
        params = re.findall(r"\bint\s+" + re.escape(name) + r"\(([^)]*)\)\s*;", text)
        return {name + to_descriptor(p, ret) for p in params}

    calc = re.search(r"\bvoid\s+calculate\(([^)]*)\)\s*;", aof)
    calc_params = calc.group(1) if calc else None
    check(calc_params is not None and "calculate" + to_descriptor(calc_params, "void") == STASH_DESC,
          "★ AoStash 的描述符 == AmbientOcclusionFace.calculate 的真实签名（逐字）",
          "真实 %s" % calc_params)

    cache_cls = javap(javap_exe, mcs, "net.minecraft.client.renderer.block.ModelBlockRenderer$Cache")
    check(SAMPLE_DESC in declarations(cache_cls, "getLightColor", "int"),
          "★ AoSample 的描述符 == ModelBlockRenderer$Cache.getLightColor 的真实签名（逐字）",
          "真实 %s" % sorted(declarations(cache_cls, "getLightColor", "int")))

    lr = javap(javap_exe, mcs, "net.minecraft.client.renderer.LevelRenderer")
    lr_overloads = declarations(lr, "getLightColor", "int")
    check(FLAT_DESC in lr_overloads,
          "★ FlatLight 的描述符 == LevelRenderer.getLightColor(3 参) 的真实签名（逐字）",
          "真实 %s" % sorted(lr_overloads))
    # ★ 判据类借光用的就是这条 2 参重载 —— 它必须真的存在（否则编译期就挂了，这里守住语义）
    two_arg = "getLightColor" + to_descriptor(
        "net.minecraft.world.level.BlockAndTintGetter, net.minecraft.core.BlockPos", "int")
    check(two_arg in lr_overloads,
          "★ LevelRenderer 确有 2 参重载 " + two_arg + "（借光走的就是它）",
          "真实 %s" % sorted(lr_overloads))
    # ★ 重入闸的存在理由（机器可验）：2 参的方法体就是「转调 3 参」，而 3 参被 mixin 挂着
    two_arg_body = javap_body(
        lr, r"public static int getLightColor\(net\.minecraft\.world\.level\.BlockAndTintGetter, "
            r"net\.minecraft\.core\.BlockPos\)\s*;")
    check("getLightColor" in two_arg_body and "BlockState" in two_arg_body,
          "★ LevelRenderer.getLightColor(2 参) 的方法体里**转调了 3 参**"
          "（⇒ 借光不加闸会经 3 参绕回 mixin 入口 = 无限递归；IN_REPAIR 的理由在这条上被验证）",
          "方法体 %s" % ("有转调" if two_arg_body else "空"))

    check("tesselateWithoutAO" in mbr and "renderModelFaceFlat" in mbr,
          "ModelBlockRenderer 里有 tesselateWithoutAO 与 renderModelFaceFlat（平面路径那两个调用点）")
    flat_calls = re.findall(r"LevelRenderer\.getLightColor", mbr)
    check(len(flat_calls) >= 2,
          "★ 平面路径确实直接调 LevelRenderer.getLightColor（≥2 处：tesselateWithoutAO + renderModelFaceFlat）",
          "命中 %d 处" % len(flat_calls))

# ======================================================================
print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
