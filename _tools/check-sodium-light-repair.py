# -*- coding: utf-8 -*-
"""离线校验：【1.27c】Sodium 光照修复 ——「纯黑采样点 ⇒ 借扶梯自己那格的光」搬到 Sodium 取光源头。

背景（2026-09-27 实录，用户 LOG1/latest.log 铁证）：
整合包有 **sodium 0.5.4**（包名 net.caffeinemc.mods.sodium；1.20.4）+ iris 1.6.13（着色器关）。
Sodium 用自己的一套网格光照管线，原版 `ModelBlockRenderer` / `AmbientOcclusionFace`
**根本不会被调用**（日志里「取光修正：AO 入口已挂上」0 次；唯一一次「平面路径入口」
来自本模组自绘引擎 `EscalatorStepCache.writeBlock` 自己的取光调用）。
⇒ 挂在原版方法上的三个修复 mixin 在 Sodium 环境下**全线空转**。
⇒ 唯一取光源头是 `LightDataAccess.compute(int,int,int)`（两个缓存类 ArrayLightDataCache /
　 HashLightDataCache 缓存未命中时都调它现算；全部角点光/AO 数据都从它产出）。

修法：两条薄 mixin（jellysquid 老包名 / caffeinemc 新包名各一，逐字相同只改 targets），
`@Inject(compute(III)I @At("RETURN"), cancellable)`：`getLightmap(data)==0`（纯黑）时
在 6 邻里找一格**有光的扶梯**，把整格打包数据（光+AO 特征）换给它；重入闸 `SodiumLightRepair`
防 compute→get→compute 递归。handler 参数与返回值全是 int，上下文用 `@Shadow`（world /
get / getLightmap），**不需要任何编译期 Sodium 依赖**。
启用门禁 `SodiumLightMixinPlugin` 按「目标类在不在类路径（getResource，禁 Class.forName）」。

## 本节查什么

1.  源文件存在（共享逻辑 / 两条 mixin / 插件 / 配置 json）；
2.  配置 json：required false / defaultRequire 0 / plugin / client 两条 / refmap；
    fabric.mod.json 里注册了该配置；
3.  两条 mixin：targets 分别含两个包名；@Inject method=="compute(III)I"、@At("RETURN")、
    cancellable；@Shadow world/pos/get/getLightmap 齐全；源码 **0 个 sodium import**；
4.  插件：只有 getResource 探测（无 Class.forName/loadClass）；shouldApplyMixin 按类名后缀门控；
5.  共享类：IN_REPAIR ThreadLocal / tryBegin / end / logFix 一次性；
6.  ★ 字节码对撞（参考 jar `_tools/_sodium/sodium-0.5.4.jar`，jellysquid 0.5.4）：
    LightDataAccess 确有 compute(III) / 抽象 get(III) / getLightmap(I) / world 字段(class_1920) /
    pos 字段(class_2338$class_2339)；getLightmap 方法体 = 两次 Math.max（BL/LU/SL 取最高）；
    compute 方法体用到 packBL/packSL 等打包器（数据布局成立）；
7.  反面对照：build.gradle 无 sodium 依赖；mixin 源码没有 import sodium；
8.  产物：jar 里 4 个新类 + 配置 json；refmap 里有 compute(III)I 条目。

用法：python _tools/check-sodium-light-repair.py
"""
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
SODIUM_DIR = os.path.join(CLIENT, "mixin", "sodium")
SODIUM_REF = os.path.join(ROOT, "_tools", "_sodium", "sodium-0.5.4.jar")

SHARED = os.path.join(CLIENT, "SodiumLightRepair.java")
JELLY = os.path.join(SODIUM_DIR, "LightDataAccessJellyMixin.java")
CAF = os.path.join(SODIUM_DIR, "LightDataAccessCafMixin.java")
PLUGIN = os.path.join(SODIUM_DIR, "SodiumLightMixinPlugin.java")
CFG = os.path.join(ROOT, "src", "client", "resources", "smoothlift.sodium.mixins.json")
FABRIC_MOD = os.path.join(ROOT, "src", "main", "resources", "fabric.mod.json")
GRADLE_FILES = ("build.gradle", "gradle.properties", "gradle/libs.versions.toml")

JELLY_TARGET = "me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess"
CAF_TARGET = "net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess"

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_java(src, keep_strings=False):
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


def find_javap():
    home = os.path.expanduser("~")
    for jdk in ("eclipse_adoptium-17-amd64-windows.2", "jdk-21.0.11.10-hotspot"):
        for exe in ("javap", "javap.exe"):
            p = os.path.join(home, ".gradle", "jdks", jdk, "bin", exe)
            if os.path.isfile(p):
                return p
    for exe in ("javap", "javap.exe"):
        p = __import__("shutil").which(exe)
        if p:
            return p
    return None


def our_jar():
    found = sorted(__import__("glob").glob(os.path.join(ROOT, "build", "libs", "mzycBetterMTR-*.jar")),
                   key=os.path.getmtime)
    return found[-1] if found else None


# ======================================================================
# 1) 源文件
# ======================================================================
print("== 1) 源文件 ==")
for path, label in ((SHARED, "共享闸门 SodiumLightRepair.java"),
                    (JELLY, "mixin LightDataAccessJellyMixin.java（jellysquid 包名）"),
                    (CAF, "mixin LightDataAccessCafMixin.java（caffeinemc 包名）"),
                    (PLUGIN, "门禁 SodiumLightMixinPlugin.java"),
                    (CFG, "配置 smoothlift.sodium.mixins.json")):
    check(os.path.isfile(path), "存在：" + label, os.path.relpath(path, ROOT))

shared_code = strip_java(load(SHARED)) if os.path.isfile(SHARED) else ""
jelly_code = strip_java(load(JELLY), keep_strings=True) if os.path.isfile(JELLY) else ""
caf_code = strip_java(load(CAF), keep_strings=True) if os.path.isfile(CAF) else ""
plugin_code = strip_java(load(PLUGIN), keep_strings=True) if os.path.isfile(PLUGIN) else ""

# ======================================================================
# 2) 配置
# ======================================================================
print()
print("== 2) 配置注册 ==")
cfg = load(CFG) if os.path.isfile(CFG) else ""
check('"required": false' in cfg or '"required" : false' in cfg,
      "配置 required = false（Sodium 可选，缺整体不崩）")
check('"defaultRequire": 0' in cfg,
      "★ defaultRequire = 0（caffeinemc 变体按类结构等价假设写，万一变了安静跳过而非崩游戏）")
check('"plugin": "smooth.lift.client.mixin.sodium.SodiumLightMixinPlugin"' in cfg,
      "配置注册了 SodiumLightMixinPlugin")
check('"LightDataAccessJellyMixin"' in cfg and '"LightDataAccessCafMixin"' in cfg,
      "client 列表含两条 mixin")
check('"refmap": "client-mzycBetterMTR-refmap.json"' in cfg, "refmap 指向主 refmap")

fab = load(FABRIC_MOD) if os.path.isfile(FABRIC_MOD) else ""
check('"smoothlift.sodium.mixins.json"' in fab and '"mixins"' in fab,
      "fabric.mod.json 的 mixins 列表里注册了 smoothlift.sodium.mixins.json")

# ======================================================================
# 3) 两条 mixin
# ======================================================================
print()
print("== 3) 两条 mixin ==")
for code, label, target in ((jelly_code, "Jelly", JELLY_TARGET), (caf_code, "Caf", CAF_TARGET)):
    mt = re.search(r'@Mixin\(\s*targets\s*=\s*"([^"]+)"', code)
    check(bool(mt) and mt.group(1) == target,
          "%s：targets = 精确包名变体" % label,
          "实际 %s" % (mt.group(1) if mt else None))
    check(re.search(r'@Pseudo[\s\S]{0,40}?@Mixin\(', code),
          "★ %s：标了 @Pseudo（目标类不在编译期类路径 ⇒ 编译期不校验，运行时找不到类也安静跳过）" % label)
    check('method = "compute(III)I"' in code.replace("  ", " "),
          "%s：@Inject method == compute(III)I（Sodium 全角点取光的唯一计算点）" % label)
    check('@At("RETURN")' in code, "%s：注入在 RETURN（读到算完的打包数据再决定换不换）" % label)
    check('(data & 0xFFF) != 0' in code.replace("  ", " "),
          "★ %s：纯黑快路径 = 一次 int 位与（光域低 12 位 ≡ getLightmap==0）——排在开闸之前，"
          "compute 每次调用（钠网格编译逐格）的最短路径" % label)
    fast = code.find("(data & 0xFFF) != 0")
    gate = code.find("tryBegin()")
    check(0 <= fast < gate, "★ %s：快路径在 tryBegin 之前（非黑格子连闸都不开）" % label,
          "快路径@%d 开闸@%d" % (fast, gate))
    check("cancellable = true" in code, "%s：cancellable = true（要 setReturnValue 顶掉）" % label)
    for shadow in ("world", "MutableBlockPos pos", "int get(int x, int y, int z)", "getLightmap(int data)"):
        check(shadow in code.replace("  ", " "),
              "%s：@Shadow %s" % (label, shadow))
    check("EscalatorLightRepair.isEscalator" in code.replace("  ", " "),
          "%s：判据复用 EscalatorLightRepair.isEscalator（识别扶梯同源）" % label)
    check("import me.jellysquid" not in code and "import net.caffeinemc" not in code,
          "★ %s：源码 0 个 sodium import（handler 全是原版类型 ⇒ 无编译期依赖）" % label)

# 两条必须逐字一致（只 targets + 类名 + 注释行数不同）——防止改了一条忘了另一条
jelly_no_target = re.sub(r'@Mixin\(\s*targets\s*=\s*"[^"]+"', "@Mixin()", jelly_code)
caf_no_target = re.sub(r'@Mixin\(\s*targets\s*=\s*"[^"]+"', "@Mixin()", caf_code)
jelly_no_target = re.sub(r"\s+", " ", jelly_no_target)
jelly_no_target = jelly_no_target.replace("LightDataAccessJellyMixin", "LightDataAccessMixin")
caf_no_target = re.sub(r"\s+", " ", caf_no_target)
caf_no_target = caf_no_target.replace("LightDataAccessCafMixin", "LightDataAccessMixin")
check(jelly_no_target == caf_no_target,
      "★ 两条 mixin 剥掉 targets 与类名后逐字一致（同一逻辑两套包名，防止分叉）")

# ======================================================================
# 4) 插件
# ======================================================================
print()
print("== 4) 门禁插件 ==")
check("Class.forName" not in plugin_code and "loadClass" not in plugin_code
      and "getResource" in plugin_code,
      "★ 只用 getResource 探测类（禁 Class.forName/loadClass —— 会把 Sodium 类提前装进类加载器，"
      "崩在 Sodium 自己的 mixin 准备阶段）")
check("shouldApplyMixin" in plugin_code and "endsWith(\"LightDataAccessJellyMixin\")" in plugin_code
      and "endsWith(\"LightDataAccessCafMixin\")" in plugin_code,
      "shouldApplyMixin 按类名后缀门控两条 mixin")
check("me/jellysquid/mods/sodium/client/model/light/data/LightDataAccess" in plugin_code,
      "插件里硬编码了 jellysquid 目标类（getResource 探测用）")
check("net/caffeinemc/mods/sodium/client/model/light/data/LightDataAccess" in plugin_code,
      "插件里硬编码了 caffeinemc 目标类（getResource 探测用）")

# ======================================================================
# 5) 共享类
# ======================================================================
print()
print("== 5) 共享闸门 ==")
check(re.search(r"ThreadLocal<\s*Boolean\s*>\s*IN_REPAIR", shared_code),
      "IN_REPAIR ThreadLocal<Boolean>（compute→get→compute 递归闸）")
check("tryBegin" in shared_code and "end()" in shared_code
      and re.search(r"saidFix", shared_code),
      "tryBegin / end / 一次性 logFix 齐全")

# ======================================================================
# 6) 字节码对撞（参考 jar）
# ======================================================================
print()
print("== 6) Sodium 0.5.4 字节码复核 ==")
javap_exe = find_javap()
if not (javap_exe and os.path.isfile(SODIUM_REF)):
    print("  [SKIP] 缺 javap 或缺参考 jar %s" % SODIUM_REF)
elif not os.path.isfile(SODIUM_REF):
    print("  [SKIP] 参考 jar 不在：%s" % SODIUM_REF)
else:
    print("  参考：%s" % os.path.relpath(SODIUM_REF, ROOT))
    try:
        r = subprocess.run([javap_exe, "-p", "-c",
                            "-cp", SODIUM_REF,
                            "me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess"],
                           capture_output=True, text=True, errors="replace")
        lda = r.stdout
    except Exception as e:
        lda = ""
        print("  javap 失败:", e)
    check("protected int compute(int, int, int);" in lda,
          "★ LightDataAccess.compute(III) 存在（我们 @Inject 的目标）")
    check("public abstract int get(int, int, int);" in lda,
          "★ get(III) 是抽象方法（缓存未命中才调 compute ⇒ 挂 compute 一处全覆盖）")
    check("public static int getLightmap(int);" in lda,
          "★ getLightmap(I) 存在（@Shadow 的纯黑判据）")
    check("class_1920" in lda and "world" in lda,
          "★ protected class_1920 world 字段存在（@Shadow 的取世界句柄）")
    check("class_2338$class_2339" in lda and "pos" in lda,
          "★ private final class_2338$class_2339 pos 字段存在（@Shadow 的零分配邻居游标）")
    # getLightmap 语义 = max(max(BL,LU),SL) 即「三路光取最高」⇒ ==0 才是真的全黑
    gm = re.search(r"public static int getLightmap\(int\);.*?(?=\n  public|\Z)", lda, re.S)
    math_max = (gm.group(0).count("Math.max") + gm.group(0).count("method_23687")) if gm else 0
    check(math_max == 2,
          "★ getLightmap 方法体恰有 2 次 Math.max（一处渲染成 java/lang/Math.max、一处中间名 "
          "class_765.method_23687；BL/LU/SL 三路取最高 ⇒ ==0 ⇔ 纯黑）",
          "命中 %d 次" % math_max)
    for packer in ("packBL", "packSL", "packLU", "packAO", "packEM", "packOP", "packFO", "packFC"):
        check("pack" in lda and packer + "(" in lda,
              "数据打包器 %s 存在（compute 的位布局正是这些）" % packer)

# ======================================================================
# 7) 反面对照
# ======================================================================
print()
print("== 7) 反面对照：没有 Sodium 编译期依赖 ==")
gradle_text = ""
for g in GRADLE_FILES:
    p = os.path.join(ROOT, g)
    if os.path.isfile(p):
        gradle_text += load(p).lower()
check("sodium" not in gradle_text,
      "build.gradle / gradle.properties / libs.versions.toml 里没有任何 sodium 依赖（不做编译期绑定）")

# ======================================================================
# 8) 产物
# ======================================================================
print()
print("== 8) 产物（jar） ==")
jar = our_jar()
if not jar:
    print("  [SKIP] 没有 build/libs/mzycBetterMTR-*.jar（先跑一次 gradlew build）")
else:
    print("  产物：%s（%d 字节）" % (os.path.relpath(jar, ROOT), os.path.getsize(jar)))
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        refmap = z.read("client-mzycBetterMTR-refmap.json").decode("utf-8") \
            if "client-mzycBetterMTR-refmap.json" in names else ""
        fab_jar = z.read("fabric.mod.json").decode("utf-8") if "fabric.mod.json" in names else ""
        jelly_cls = z.read("smooth/lift/client/mixin/sodium/LightDataAccessJellyMixin.class") \
            if "smooth/lift/client/mixin/sodium/LightDataAccessJellyMixin.class" in names else b""
    for cls in ("smooth/lift/client/SodiumLightRepair.class",
                "smooth/lift/client/mixin/sodium/LightDataAccessJellyMixin.class",
                "smooth/lift/client/mixin/sodium/LightDataAccessCafMixin.class",
                "smooth/lift/client/mixin/sodium/SodiumLightMixinPlugin.class",
                "smoothlift.sodium.mixins.json"):
        check(cls in names, "jar 内含 %s" % cls)
    check('"smoothlift.sodium.mixins.json"' in fab_jar,
          "jar 内 fabric.mod.json 注册了 smoothlift.sodium.mixins.json")
    check(b"compute(III)I" in jelly_cls,
          "★ 产物里 jelly mixin 字节码保留 named 描述符 compute(III)I"
          "（@Pseudo 外部目标不产生 refmap 条目是预期：描述符全原始类型、钠的方法/类名不在 MC 映射内，"
          "运行时由 named→intermediary remapper 原样通过）")
    check(b"class_1920" in jelly_cls and b"class_2338$class_2339" in jelly_cls,
          "★ @Shadow world / pos 在产物里的类型已是 intermediary（class_1920 / class_2338$class_2339）"
          "——与钠运行时字段类型逐字节一致（loom 重映射了类里 MC 类型描述，这正是对齐方式）")

print()
if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")