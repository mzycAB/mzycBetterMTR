# -*- coding: utf-8 -*-
"""离线校验：【1.26】扶梯阶梯渲染引擎（/mtrxr）—— 两个用户点名的问题：

    「为什么 mtrxr off 加载完之后扶梯阶梯消失，退出重进存档之后出现」
    「而且没有任何性能上的优化！仍旧很卡！」

## 一、阶梯消失（LOG13 复现）

根因：`EscalatorRenderMode.apply()` 调了 `EscalatorStepIndex.reset()`。

`reset()` 会把**三样东西一起清空**：索引内容 + 每区块的段表 + **已加载区块登记表 LOADED**。
前两样清掉没关系（下一帧重扫就能回来），要命的是 LOADED —— 它只由 `CHUNK_LOAD` 事件填充，
而 CHUNK_LOAD **只对「新加载」的区块触发**。`/mtrxr` 切换时人已经站在存档里，周围的区块
早就加载完了，不会再有 CHUNK_LOAD ⇒ LOADED 永久为空 ⇒ `tick()` 直接 return ⇒ 索引永久为空
⇒ 渲染端一个方块都扫不到（没有阶梯可画），而此时 MTR 原版那份静止阶梯面**已经被透明标记
贴图隐藏**了 ⇒ **阶梯整片消失**。退出重进存档 = 所有区块重新加载 = CHUNK_LOAD 全来一遍
⇒ 恢复。症状与用户描述逐字吻合。

修法（两层，缺一不可）：
  1. `apply()` 改调 `invalidate()`：只作废索引内容、**保留 LOADED**，下一 tick 立刻重扫；
  2. `tick()` 自愈：`LOADED.isEmpty()` 时从客户端区块缓存 `getChunkNow(i, i)` 半径内重登记。

> 判源码时**必须先剥注释** —— `apply()` 的 Javadoc 里就写着「绝不能 reset()」，
> 直接 grep `reset()` 会命中这句解释性文字，把「说明」误判成「代码」。

## 二、卡顿（性能架构重构）

旧热路径是「平铺坐标数组 + 每帧逐方块解析两遍 + 逐属性 getBooleanProperty」。1.26 改为：

  - **分段稀疏索引**：16³ 段为最小登记单位（`Section`），只登记非空段；查询/剔除/遍历
    都降到「段」这一级，扶梯只占一小片时不再每次扫全表；
  - **分段级剔除**：距离（`distanceToBoxSqr > maxDistanceSq`）+ 视锥（`frustum.isVisible`）
    两级；看不到的段一个方块都不碰；
  - **单趟解析**：方块状态 / 模型 / 帧号 / 打包光照在第一趟一次算好，绘制趟只写顶点；
  - **零分配桶**：`Group` 用并行数组（positions/states/models/bands/lights）按需翻倍复用，
    每帧只把 `size` 归零，不再构建中间 List/对象；
  - **预烘焙 stopped**：`status=false` 判定挪到模型烘焙期（模型本就按状态缓存），
    不再每帧 `getBooleanProperty`；
  - **CPU 侧背面剔除**：`dot(法线, 面中心 − 相机) > ε` 直接不写顶点（`ε = 0.02`）。
    MTR 斜坡阶梯模型 95 个 `#step` 面里 cullface 全是 None，逐顶点全写；背面剔除能省掉
    朝背相机的近一半顶点，而画面**逐像素不变**（本 RenderType 开着 CULL，判据与卷绕一致）；
  - **动画驱动走索引**：`nearestEscalator` 从 O(33³) 球壳 `getBlockState` 改为索引查询。

## 判据（源码剥注释 + 字节码双验 + 反向对照）

反向对照（★ 必须有）：断言**旧的错误写法已不存在**，否则「改成对的了」无法与
「两处都写着」区分开。
"""

import os
import re
import sys
import glob
import json
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")

INDEX = os.path.join(CLIENT, "EscalatorStepIndex.java")
RENDERER = os.path.join(CLIENT, "EscalatorStepRenderer.java")
MODELS = os.path.join(CLIENT, "EscalatorStepModels.java")
MODE = os.path.join(CLIENT, "EscalatorRenderMode.java")
MODE_CMD = os.path.join(CLIENT, "EscalatorRenderModeCommand.java")
DRIVER = os.path.join(CLIENT, "EscalatorAnimationDriver.java")
# 【1.29】静态几何缓存架构的三根柱子
TEXTURES = os.path.join(CLIENT, "EscalatorStepTextures.java")
GROUPS = os.path.join(CLIENT, "EscalatorStepGroups.java")
CACHE = os.path.join(CLIENT, "EscalatorStepCache.java")
# 【1.28】access widener：遮挡剔除要借原版 LevelRenderer.visibleSections，靠它放开可见性
AW = os.path.join(ROOT, "src", "main", "resources", "smoothlift.accesswidener")
# 【1.30b】方块变更钩子：客户端 mixin（把「有方块被改过」告诉索引）+ 它的注册表
BLOCK_CHANGE_MIXIN = os.path.join(CLIENT, "mixin", "LevelBlockChangeMixin.java")
CLIENT_MIXINS_JSON = os.path.join(ROOT, "src", "client", "resources",
                                  "smoothlift.client.mixins.json")

FAILS = []


def check(ok, what, detail=""):
    print(("  ==> 通过  " if ok else "  ==> 失败  ") + what + ("  -- " + detail if detail else ""))
    if not ok:
        FAILS.append(what)


def load(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_java(src, keep_strings=False):
    """剥掉 Java 注释（// 与 /* */），只留下**可执行代码**。

    断言源码形态时用它：注释里会出现与代码相反的「解释性文字」（例如 apply 的 Javadoc
    写着「绝不能 reset()」），不剥掉就会把说明误判成代码。

    `keep_strings=False`（默认）连字符串字面量一起抹成 "" —— 避免日志文案里的
    "reset"/"invalidate" 干扰计数。
    `keep_strings=True` 保留字面量 —— 需要断言「某个属性名 / 常量字符串真的写在代码里时用它。
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


def body(code, header_re):
    """截出某个方法/构造器的**方法体**（按花括号配对），用来把断言钉在「这一段」里。

    方法头先用 header_re 找到，然后从第一个 '{' 起做括号配对；
    返回空串表示没找到。
    """
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
    return ""


def cp_utf8(token):
    """JVM 常量池里的 Utf8 条目：`u2 长度` + 字节。

    ★ 用它做字节码断言，而不是裸 `b"reset" in blob`。裸子串会误命中
    —— 例如 `"mbm_preset"` 里就含 `"reset"`，`b"reset" not in cls` 会被这种
    无关字面量**骗成通过**（本该失败却过了）。带长度前缀后 `reset` 与
    `preset` 是两个不同的条目，判据才真的指向那个方法名。
    """
    b = token.encode("utf-8")
    return bytes([(len(b) >> 8) & 0xFF, len(b) & 0xFF]) + b


index = load(INDEX)
renderer = load(RENDERER)
models = load(MODELS)
mode = load(MODE)
mode_cmd = load(MODE_CMD)
driver = load(DRIVER)
textures = load(TEXTURES)
groups = load(GROUPS)
cache = load(CACHE)

index_c = strip_java(index)
renderer_c = strip_java(renderer)
models_c = strip_java(models)
mode_c = strip_java(mode)
tex_c = strip_java(textures)
groups_c = strip_java(groups)
cache_c = strip_java(cache)

# ======================================================================
print()
print("===== 1) ★ 根因：apply() 只能 invalidate()，不得 reset() =====")
# ======================================================================

# 1.1 apply() 里确实是 invalidate()
apply_body = body(mode_c, r"public static boolean apply\s*\(\s*boolean\s+\w+\s*\)")
check("EscalatorStepIndex.invalidate()" in apply_body,
      "apply() 方法体里调用 EscalatorStepIndex.invalidate()")

# 1.2 ★ 反向对照：apply() 里不许出现 reset()
check("EscalatorStepIndex.reset()" not in apply_body,
      "★ 反向对照：apply() 方法体里**没有** EscalatorStepIndex.reset()（旧的消失 bug）")
# 顺带证明「剥注释」有效：原文注释里确实出现过 reset()，剥掉后没了
check("reset()" in mode and "reset()" not in mode_c,
      "★ 剥注释有效：apply() 的 Javadoc 提过 reset()，剥注释后代码区已无该词",
      "注释保留 %d 次 / 代码区 %d 次" % (mode.count("reset()"), mode_c.count("reset()")))

# 1.3 invalidate() 的实现：只作废内容、保留 LOADED
inv_body = body(index_c, r"public static void invalidate\s*\(\s*\)")
check(inv_body != "" and "LOADED" not in inv_body,
      "invalidate() 不动 LOADED（区块登记保留）")
check(inv_body != "" and "flattenedDirty" in inv_body,
      "invalidate() 至少把 flattenedDirty 置脏（下一帧重扫）")

# 1.4 reset() 仍然会把 LOADED 一起清掉（证明两者语义真的不同，不是改名）
reset_body = body(index_c, r"public static void reset\s*\(\s*\)")
check("LOADED.clear()" in reset_body and "SECTIONS.clear()" in reset_body,
      "reset() 仍清 LOADED + SECTIONS（换维度/退世界才用，语义与 invalidate 确实不同）")

# ======================================================================
print()
print("===== 2) ★ 自愈：LOADED 为空时从区块缓存重登记 =====")
# ======================================================================

tick_body = body(index_c, r"public static void tick\s*\(\s*ClientLevel\s+\w+\s*\)")
check("LOADED.isEmpty()" in tick_body and "rediscoverChunks(" in tick_body,
      "tick() 在 LOADED.isEmpty() 时调 rediscoverChunks()（不靠 CHUNK_LOAD 也能恢复）")
check(index_c.count("rediscoverChunks(") >= 2,
      "rediscoverChunks 有定义 + 至少 1 处调用",
      "出现 %d 次" % index_c.count("rediscoverChunks("))

redis_body = body(index_c, r"private static void rediscoverChunks\s*\(\s*ClientLevel\s+\w+\s*\)")
check("getChunkNow(" in redis_body,
      "rediscoverChunks 用 getChunkNow(i, i) 问客户端缓存（★ 不会凭空加载区块）")
check("MAX_REDISCOVER_RADIUS" in redis_body,
      "重登记有半径上限 MAX_REDISCOVER_RADIUS（不扫全图）")
check("onChunkLoad(" in redis_body,
      "重登记走统一入口 onChunkLoad（与 CHUNK_LOAD 事件同一条路，登记/建段逻辑不分叉）")

# ======================================================================
print()
print("===== 3) 分段稀疏索引结构 =====")
# ======================================================================

check(re.search(r"public\s+static\s+final\s+class\s+Section", index_c) is not None,
      "有 Section 内部类（16³ 段）")
check("SECTION_SIZE" in index_c and re.search(r"SECTION_SIZE\s*=\s*16", index_c) is not None,
      "SECTION_SIZE = 16")
check("Map<Long, Section> SECTIONS" in index_c.replace("  ", " ")
      or (re.search(r"\bSECTIONS\b", index_c) and "Map<" in index_c),
      "SECTIONS 是 Map（不是 List，查询不做 O(n) contains）")
check(re.search(r"\bLOADED\b[^;]*Map<", index_c) is not None,
      "LOADED 是 Map<Long, LevelChunk>（不是 List.contains 线性查找）")
check(re.search(r"Map<Long,\s*long\[\]>\s+CHUNK_SECTIONS", index_c.replace("  ", " ")) is not None
      or "CHUNK_SECTIONS" in index_c,
      "CHUNK_SECTIONS 记录每区块的段键（卸载时按段删，不整表重扫）")
check("dropChunkSections(" in index_c,
      "区块卸载走 dropChunkSections（只删该区块的段）")
check("flattenedDirty" in index_c and "flattened" in index_c,
      "positions() 平铺视图按需惰性拼装（flattenedDirty 惰性标记）")

# ======================================================================
print()
print("===== 4) 【1.26→1.29】渲染热路径：分段剔除，且一个方块都不碰 =====")
# ======================================================================
# 1.26 建立「分段级剔除」（距离 + 视锥）；1.29 把它推到终点 ——
# 剔除趟里**连 getBlockState 都不调**了：几何全在静态缓存里，每帧只比
# 「段版本戳 revision / 组表版本 epoch」两个 long，相等就整段跳过。

render_body = body(renderer_c, r"private static void render\s*\(\s*WorldRenderContext\s+\w+\s*\)")
check("distanceToBoxSqr(" in render_body and "maxDistanceSq" in render_body,
      "render 走分段级**距离**剔除（distanceToBoxSqr > maxDistanceSq 跳过）")
check("frustum" in render_body and ".isVisible(" in render_body,
      "render 走分段级**视锥**剔除（frustum.isVisible(box)）")
check("BEHIND_THRESHOLD" in render_body,
      "没有 frustum 时退化为「背后阈值」剔除（BEHIND_THRESHOLD）")
check("EscalatorStepIndex.sections()" in render_body,
      "render 遍历的是 sections()（段级），不是平铺的每方块数组")
# ★ 1.29 的核心：剔除趟里一次方块查询都没有了
check(render_body.count("getBlockState(") == 0,
      "★ 1.29：剔除趟里 getBlockState 恰 0 次（逐方块解析已彻底移出热路径）",
      "实数 %d" % render_body.count("getBlockState("))
check(render_body.count("getLightColor(") == 0,
      "★ 1.29：剔除趟里 getLightColor 恰 0 次（光照在**重建时**按方块查一次、烘进顶点）",
      "实数 %d" % render_body.count("getLightColor("))
check("getBooleanProperty" not in render_body,
      "★ stopped 判定不在热路径（烘焙期算好，见第 5 节）")
check("EscalatorStepCache.touch(" in render_body,
      "可见段交给 EscalatorStepCache.touch（内部只比 revision/epoch，相等就整段跳过）")
check("EscalatorStepCache.beginFrame(" in render_body,
      "每帧先 beginFrame（清可见列表与计数）")
check("EscalatorStepCache.refreshVisible(" in render_body,
      "接自愈刷新趟（光照变化不被版本戳看见，靠它兜底）")

# ★ 反向对照：1.26/1.27 的「零分配桶 + 逐帧写顶点」架构必须已不存在
check("private static final class Group" not in renderer_c,
      "★ 反向对照：Group（并行数组桶）已删 —— 逐帧写顶点的架构不存在了")
check("writeGroup" not in renderer_c,
      "★ 反向对照：writeGroup（逐帧写顶点的热路径）已删")
check("BACKFACE_EPSILON" not in renderer_c,
      "★ 反向对照：渲染器里已无 BACKFACE_EPSILON（CPU 背面剔除已删，见第 5 节）")

# ======================================================================
print()
print("===== 5) 【1.29】背面剔除：从 CPU 逐帧判 → 交给 GPU 的 CULL =====")
# ======================================================================
# 1.26 的 CPU 背面剔除要求「剔的时候知道相机在哪」；而静态 VertexBuffer 是**跨帧复用**的
# —— 同一份缓冲既要在相机左边可见、又要在相机右边可见，CPU 侧根本剔不了
# （剔掉的面在下一帧换个角度就该出现）。所以 1.29 把它交回 GPU：渲染类型开着 CULL，
# 背面由光栅化阶段丢弃 —— 效果等价，而 CPU 成本归零。

check("BACKFACE_EPSILON" not in renderer_c,
      "★ 反向对照：渲染器里已无 BACKFACE_EPSILON（逐帧 CPU 剔除判据随架构一起删了）")
check("setCullState(RenderStateShard.CULL)" in tex_c.replace("  ", " "),
      "★ 阶梯渲染类型开着 CULL（背面由 GPU 丢，等价于原来的 CPU 背面剔除）")
check("RENDERTYPE_CUTOUT_SHADER" in tex_c,
      "着色器状态用原版 rendertype_cutout（与方块 cutout 完全同一条管线）")
check("setLightmapState(RenderStateShard.LIGHTMAP)" in tex_c.replace("  ", " ")
      and "setOverlayState(RenderStateShard.OVERLAY)" in tex_c.replace("  ", " "),
      "光照图 / 覆盖层状态齐备（缺一个会让台阶面明暗或受伤闪红不对）")
check("DefaultVertexFormat.BLOCK" in tex_c,
      "顶点格式 BLOCK（阶梯是方块模型，必须和 cutout 一致）")

# 模型侧：为「逐帧写顶点」服务的辅助必须已删（否则是误导性的死代码）
check("centers()" not in models_c and "cullable()" not in models_c,
      "★ 反向对照：StepModel 的 centers()/cullable()（CPU 背面剔除用）已删")
check("rotatedFor" not in models_c and "vScaledFor" not in models_c,
      "★ 反向对照：顶点模板 rotatedFor/vScaledFor（为逐帧重写顶点服务）已删"
      " —— 顶点现在是静态的，不需要每帧做矩阵乘法")
check("quadCenter(" not in models_c,
      "★ 反向对照：quadCenter 已删（面中心只为 CPU 剔除/模板而存在）")
# 但 stopped 仍在：它是「这个 BlockState 的常量」，缓存仍要用它定组
check("boolean stopped" in body(models_c, r"public static final class StepModel")
      or "private final boolean stopped" in models_c.replace("  ", " "),
      "stopped 保留（缓存按它把「刷停」的阶梯归到第 0 组）")
check("model.stopped()" in cache_c,
      "EscalatorStepCache 读 model.stopped() 决定速度取 0 还是查速度配置")
# 这里要断言**字符串字面量** "status" 真的在最里面，所以保留字面量剥注释。
check('getBooleanProperty(state, "status", true)' in strip_java(models, keep_strings=True),
      "stopped 由 status 属性烘焙（与方块状态一一对应，模型本就按状态缓存）")

# ======================================================================
print()
print("===== 6) 动画驱动也走索引（不再 O(33³) 球壳扫方块） =====")
# ======================================================================

driver_c = strip_java(driver)
check("EscalatorStepIndex.nearestStep(" in driver_c,
      "EscalatorAnimationDriver 用索引的 nearestStep 找最近扶梯")
# ★ 断言要钉在**热路径那一个方法**里：驱动里另有 isSelectedUsable / escalatorAt /
#   lookedAtEscalator 三个「玩家交互时才算一次」的方法，它们读 getBlockState 是正常的
#   （不是每 tick）。旧版的球壳扫描在 nearestEscalator 里 —— 只查它。
nearest_body = body(driver_c, r"private static BlockPos nearestEscalator\s*\(")
check(nearest_body != "" and "getBlockState(" not in nearest_body,
      "★ 动画驱动**热路径**(nearestEscalator) 里没有 getBlockState（旧版 O(33³) 球壳扫描已删）")
check("nearestStep(" in nearest_body and "NEARBY_RADIUS" in nearest_body,
      "nearestEscalator 直接委托索引 nearestStep(位置, NEARBY_RADIUS)")
check(driver_c.count("getBlockState(") == 7,
      "驱动里剩下的 getBlockState 只在三个交互式方法里（与旧版逐帧扫描无关）",
      "实数 %d" % driver_c.count("getBlockState("))
idx_nearest = body(index_c, r"public static BlockPos nearestStep\s*\(")
check("Section" in idx_nearest or "sections()" in idx_nearest,
      "nearestStep 在段级上找（先定位候选段，再在段内比坐标）")

# ======================================================================
print()
print("===== 7) 性能计数（用户可自证「不再靠感觉」） =====")
# ======================================================================

check("statsLine()" in renderer_c and re.search(r"public static String statsLine", renderer_c),
      "EscalatorStepRenderer.statsLine() 存在")
check("STATS_WINDOW" in renderer_c and "statsMsAverage" in renderer_c,
      "有耗时滑动平均（statsMsAverage / STATS_WINDOW）")
check("statsFrustumCulledSections" in renderer_c,
      "有视锥剔除段计数 statsFrustumCulledSections")
mode_cmd_c = strip_java(mode_cmd)
check("EscalatorStepRenderer.statsLine()" in mode_cmd_c,
      "/mtrxr 指令把 statsLine() 回显给玩家")

# ======================================================================
print()
print("===== 8) 复核：MTR 原版静止阶梯面确实仍被隐藏（消失 bug 的另一半） =====")
# ======================================================================

override = os.path.join(CLIENT, "EscalatorModelOverride.java")
ov = strip_java(load(override))
check("TRANSPARENT" in ov or "transparent" in ov or "marker" in ov.lower(),
      "EscalatorModelOverride 仍把 #step 换成透明标记贴图（MTR 原版面被隐藏）"
      " —— 所以索引一旦为空就是整片消失，必须靠 1.24/1.26 的修法兜住")
check("step" in ov.lower(),
      "override 作用在 #step 面上")

# ======================================================================
print()
print("===== 9) 字节码复核（jar 里真的是这套逻辑） =====")
# ======================================================================

jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")), key=os.path.getmtime)
if not jars:
    print("[SKIP] 没有 build/libs/*.jar（先跑一次 gradlew build）")
else:
    jar = jars[-1]
    print("  校验产物：%s" % os.path.basename(jar))
    with zipfile.ZipFile(jar) as z:
        mode_cls = z.read("smooth/lift/client/EscalatorRenderMode.class")
        idx_cls = z.read("smooth/lift/client/EscalatorStepIndex.class")
        rdr_cls = z.read("smooth/lift/client/EscalatorStepRenderer.class")
        sm_cls = z.read("smooth/lift/client/EscalatorStepModels$StepModel.class")
        sec_cls = z.read("smooth/lift/client/EscalatorStepIndex$Section.class")

    # 用**精确常量池条目**判方法名（见 cp_utf8 的说明，裸子串会被 "preset" 之类骗过）。
    check(cp_utf8("invalidate") in mode_cls and cp_utf8("reset") not in mode_cls,
          "★ EscalatorRenderMode.class 引用 invalidate()、且**不含** reset()")
    check(cp_utf8("rediscoverChunks") in idx_cls,
          "EscalatorStepIndex.class 里编进了 rediscoverChunks（自愈路径在）")
    # 反混淆后的 jar 里 Minecraft 方法名是 intermediary：ChunkSource.getChunkNow(int,int)
    # → class_2802.method_21730:(II)Lnet/minecraft/class_2818;（1.20.4 intermediary）。
    # dev jar 没反混淆时是 Yarn 名 —— 两种都认，但必须命中一个。
    check(cp_utf8("method_21730") in idx_cls or cp_utf8("getChunkNow") in idx_cls,
          "EscalatorStepIndex.class 里编进了 getChunkNow（区块缓存重登记）",
          "method_21730(intermediary) / getChunkNow(Yarn)")
    check(cp_utf8("nearestStep") in idx_cls,
          "EscalatorStepIndex.class 里编进了 nearestStep")
    check(cp_utf8("statsLine") in rdr_cls,
          "EscalatorStepRenderer.class 里编进了 statsLine")
    check(cp_utf8("stop" + "ped") in sm_cls,
          "StepModel.class 里编进了 stopped（缓存按它把刷停的阶梯归第 0 组）")
    # ---- 反向对照：1.26/1.27 为「逐帧写顶点」服务的成员，字节码层面确认已删 ----
    for gone in ("writeGroup",):
        check(cp_utf8(gone) not in rdr_cls,
              "★ 反向对照：渲染器里**没有** %s（逐帧写顶点的热路径已删）" % gone)
    for gone in ("rotatedFor", "vScaledFor", "centers", "cullable"):
        check(cp_utf8(gone) not in sm_cls,
              "★ 反向对照：StepModel.class 里**没有** %s（逐帧顶点模板 / CPU 剔背面的辅助已删）"
              % gone)
    check(cp_utf8("rescanSlice") in idx_cls,
          "★ EscalatorStepIndex.class 里编进了 rescanSlice（重扫切片）")
    check(cp_utf8("rescanAll") not in idx_cls,
          "★ 反向对照：EscalatorStepIndex.class 里**没有** rescanAll（旧的整表全扫已删）")
    # ---- 【1.30b】方块变更钩子：mixin 类真在 jar 里、且 refmap 真把它映射到了注入点 ----
    check(cp_utf8("onBlockChanged") in idx_cls and cp_utf8("applyDirtyChunks") in idx_cls,
          "★ EscalatorStepIndex.class 里编进了 onBlockChanged + applyDirtyChunks（即时路在）")
    with zipfile.ZipFile(jar) as z:
        jar_names = set(z.namelist())
        mixin_cls = (z.read("smooth/lift/client/mixin/LevelBlockChangeMixin.class")
                     if "smooth/lift/client/mixin/LevelBlockChangeMixin.class" in jar_names else b"")
        refmap = (z.read("client-mzycBetterMTR-refmap.json")
                  if "client-mzycBetterMTR-refmap.json" in jar_names else b"")
    check(bool(mixin_cls), "jar 里有 smooth/lift/client/mixin/LevelBlockChangeMixin.class")
    check(cp_utf8("onBlockChanged") in mixin_cls,
          "★ mixin 类里真的调了 EscalatorStepIndex.onBlockChanged（不是空壳）")
    # ★★ 这条是「注入点真的存在」的离线代理：refmap 必须把 onBlockStateChange 映射到
    #    class_1937.method_19282（= Level.onBlockStateChange）。名字错了的话，
    #    mixins.json 里 defaultRequire:1 会让**游戏直接起不来**。
    check(b"method_19282" in refmap and b"onBlockStateChange" in refmap,
          "★ refmap 把 onBlockStateChange 映射到 class_1937.method_19282（Level 的注入点）")
    # ---- 【1.29】全量重写：三份新文件都得在字节码里 ----
    with zipfile.ZipFile(jar) as z:
        cache_cls = z.read("smooth/lift/client/EscalatorStepCache.class")
        grp_cls = z.read("smooth/lift/client/EscalatorStepGroups.class")
        tex_cls = z.read("smooth/lift/client/EscalatorStepTextures.class")
    for tok in ("beginFrame", "touch", "refreshVisible", "writeBlock", "invalidateAll"):
        check(cp_utf8(tok) in cache_cls,
              "★ EscalatorStepCache.class 里编进了 %s" % tok)
    for tok in ("indexFor", "epoch", "speedOf", "reset"):
        check(cp_utf8(tok) in grp_cls,
              "★ EscalatorStepGroups.class 里编进了 %s" % tok)
    for tok in ("bandFor", "frameCount", "ensureLoaded", "split"):
        check(cp_utf8(tok) in tex_cls,
              "★ EscalatorStepTextures.class 里编进了 %s" % tok)
    check(cp_utf8("pixelsEqual") in tex_cls,
          "★ EscalatorStepTextures.class 里编进了 pixelsEqual（up/down 全量逐像素比对）")
    # ★ 反向对照：被实测证伪的纹理矩阵路线，字节码里也不得出现。
    # 注意 setTextureMatrix 所在的 RenderSystem **不在** intermediary 的改名范围内
    # （remapJar 后它仍是 com/mojang/blaze3d/systems/RenderSystem.setTextureMatrix），
    # 所以这一条在 jar 里也真的能判；而 OffsetTexturingStateShard 会被改名成
    # RenderStateShard$class_xxxx，按名字判会**永远通过** —— 那种「不可能失败的反向对照」
    # 比没有更糟，所以它只留在源码层（见 11.1）。
    check(cp_utf8("setTextureMatrix") not in tex_cls,
          "★ 反向对照：贴图层**没有** setTextureMatrix（纹理矩阵路线在 1.20.4 已实测证伪）")
    # Section 的构造器：新的 7 参描述符必须在，旧的 2 参必须不在
    check(cp_utf8("(JIIIJJ[Lnet/minecraft/class_2338;)V") in sec_cls
          or cp_utf8("(JIIIJJ[Lnet/minecraft/core/BlockPos;)V") in sec_cls,
          "★ Section.class 里有新构造器 (JIIIJJ[BlockPos;)V（段键+原点+revision+signature）",
          "intermediary class_2338 / mojmap BlockPos")
    check(cp_utf8("statsRefreshMsAverage") in rdr_cls,
          "EscalatorStepRenderer.class 里编进了 statsRefreshMsAverage（三段分开计时）")
    check(cp_utf8("statsDrawnVertices") in rdr_cls,
          "EscalatorStepRenderer.class 里编进了 statsDrawnVertices（交给 GPU 的顶点数）")
    # ---- 【1.29】绘制路径：ChunkOffset uniform + 静态缓冲的「上传 / 提交」----
    check(cp_utf8("ChunkOffset") in rdr_cls,
          "★ 渲染器里编进了 ChunkOffset 字符串（原版渲染类型确实声明了这个 uniform，1.20.4 实测）")
    # VertexBuffer 系在 remapJar 后变成 intermediary。这张对照表来自
    # 1.20.4 的 mappings.tiny（`official / intermediary / named` 三列，直接查出来的，不是猜的）：
    #   VertexBuffer               → net/minecraft/class_291
    #   upload(RenderedBuffer)     → class_291.method_1352
    #   bind()                     → class_291.method_1353     ← ★ 打崩溃的根因就靠这个
    #   unbind()（static）         → class_291.method_1354
    #   drawWithShader(...)        → class_291.method_34427
    #   draw()                     → class_291.method_35665
    #   isInvalid()                → class_291.method_43444
    # dev 产物（未 remap）里是原名 —— 两种都认，但必须命中一个。
    # ⚠️ 注意 method_135x 只有 4 位数字：以前用 `method_\d{5}` 扫过，把这几个全漏了。
    check(cp_utf8("method_1352") in cache_cls or cp_utf8("upload") in cache_cls,
          "★ 缓存里有 VertexBuffer.upload（把渲染缓冲交给 GPU）",
          "class_291.method_1352 / upload(dev)")
    check(cp_utf8("method_1353") in cache_cls or cp_utf8("bind") in cache_cls,
          "★★ 缓存里有 VertexBuffer.bind（upload 之前的那个 bind —— 少了它就是 1.29 第一版的崩溃）",
          "class_291.method_1353 / bind(dev)")
    check(cp_utf8("method_1354") in cache_cls or cp_utf8("unbind") in cache_cls,
          "★ 缓存里有 VertexBuffer.unbind（上传后把 VAO 解绑回去）",
          "class_291.method_1354 / unbind(dev)")
    check(cp_utf8("method_35665") in rdr_cls or cp_utf8("draw") in rdr_cls,
          "★ 渲染器逐段调 VertexBuffer.draw（原版提交静态缓冲用的就是它，不是 drawWithShader）",
          "class_291.method_35665 / draw(dev)")
    check(cp_utf8("method_34427") in rdr_cls or cp_utf8("drawWithShader") in rdr_cls,
          "★ 渲染器保留 drawWithShader（没有 ChunkOffset 时的兜底路径）",
          "method_34427(intermediary) / drawWithShader(dev)")
    check(cp_utf8("net/minecraft/class_291") in cache_cls
          or cp_utf8("com/mojang/blaze3d/vertex/VertexBuffer") in cache_cls,
          "★ 缓存里用 VertexBuffer（静态顶点缓冲）",
          "class_291(intermediary) / com.mojang.blaze3d.vertex.VertexBuffer(dev)")
    # VertexBuffer.Usage.STATIC 同样会被 remake：dev 里是 STATIC，
    # remapJar 后是 class_291$class_8555.field_44793（intermediary，每个 MC 版本固定）。
    check(cp_utf8("field_44793") in cache_cls or cp_utf8("STATIC") in cache_cls,
          "★ VertexBuffer 的 Usage 是 STATIC（跨帧复用的静态缓冲）",
          "field_44793(intermediary) / STATIC(dev)")
    # ---- 【1.28】遮挡剔除：借原版「这一帧真要画的段」集合 ----
    check(cp_utf8("refreshVanillaVisibleKeys") in rdr_cls,
          "★ EscalatorStepRenderer.class 里编进了 refreshVanillaVisibleKeys（每帧收段键集合）")
    check(cp_utf8("occlusionStateText") in rdr_cls,
          "★ EscalatorStepRenderer.class 里编进了 occlusionStateText（统计行里的实时状态）")
    check(cp_utf8("it/unimi/dsi/fastutil/longs/LongOpenHashSet") in rdr_cls,
          "★ 段键集合用的是 LongOpenHashSet（primitive long，不装箱）")
    # 反混淆后 jar 里 MC 字段是 intermediary，且**字段 id 逐版本不同**（不同成员）：
    #   1.20.1：LevelRenderer.renderChunksInFrustum → class_761.field_34807
    #   1.20.4：LevelRenderer.visibleSections      → class_761.field_45616
    # ★★ 只认**本版本那一个**中介名，绝不许拿 mojmap 名当备选 ——
    #   render() 里有个局部变量就叫 `int visibleSections`，它以「局部变量名」进了
    #   LocalVariableTable ⇒ cp_utf8("visibleSections") **恒真**，这条断言在 1.20.1 上
    #   会静默假绿（本轮实测踩到；`getChunkNow` / `drawWithShader` 这类 mojmap 备选同理，
    #   都不是安全兜底）。下面这条用 1.20.1 实测值，且要求 owner 也对得上。
    # ★ 注意 cp_utf8 是**精确**匹配常量池条目：类名那条在池里是**全限定**形式
    #   （`net/minecraft/class_761`，Class 条目指向的 UTF8），不是裸 `class_761`。
    check(cp_utf8("net/minecraft/class_761") in rdr_cls and cp_utf8("field_34807") in rdr_cls,
          "★ 渲染器引用了 1.20.1 的 LevelRenderer.renderChunksInFrustum"
          "（class_761.field_34807）")
    # 1.20.1 的元素比 1.20.4 多包了一层：段本体在 RenderChunkInfo.chunk 里
    # （class_761$class_762.field_4124）。少剥这一层就取不到 origin ⇒ 段键全算不出。
    check(cp_utf8("field_4124") in rdr_cls,
          "★ 渲染器剥到了 RenderChunkInfo.chunk（class_761$class_762.field_4124）"
          "—— 段本体在里面")
    check(cp_utf8("field_45616") not in rdr_cls,
          "★ 反向对照：渲染器**没有**引用 1.20.4 的 visibleSections（field_45616）")
    check(cp_utf8("method_18675") in rdr_cls or cp_utf8("blockToSectionCoord") in rdr_cls,
          "★ 段键由 blockToSectionCoord 换算（origin 是方块坐标，不过这一关键就全错）")
    check(cp_utf8("()J") in sec_cls and cp_utf8("key") in sec_cls,
          "★ Section.class 里有 key()（返回 long 的段键访问器）")
    # ★ 反向对照：旧的无参构造器描述符 ([LBlockPos;)V 必须已消失
    check(cp_utf8("([Lnet/minecraft/class_2338;)V") not in sec_cls
          and cp_utf8("([Lnet/minecraft/core/BlockPos;)V") not in sec_cls,
          "★ 反向对照：Section 的旧构造器 ([BlockPos;)V 已不存在（段键已成必填参数）")
    # ★ 反向对照：没有绕过 access widener 去反射原版的 private 遮挡图
    check(cp_utf8("SectionOcclusionGraph") not in rdr_cls,
          "★ 反向对照：渲染器**不碰** SectionOcclusionGraph（只借 visibleSections，不反射私有状态）")
    check(cp_utf8("applyOcclusion") in mode_cls,
          "★ EscalatorRenderMode.class 里编进了 applyOcclusion（开关）")
    check(cp_utf8("escalatorOcclusionCulling") in mode_cls,
          "★ EscalatorRenderMode.class 里编进了 escalatorOcclusionCulling（配置键名）")

# ======================================================================
print()
print("===== 10) 【1.27】★ 段包围盒必须是「段内实际方块的紧致盒」 =====")
# ======================================================================
# 这一条是「玩家看不到的就不渲染」能不能落地的前提：
# 旧版盒子是**整块 16³ 立方体**再外扩 1 格 ⇒ 一个只装 3 个阶梯方块的段也报 18×18×18，
# 于是距离判定失真、视锥判定几乎恒为真 —— 判据写着「分段剔除」，实际一个段都没剔掉。

ctor_body = body(index_c, r"private\s+Section\s*\(\s*long\s+\w+\s*,\s*int\s+\w+\s*,\s*int\s+\w+\s*,"
                          r"\s*int\s+\w+\s*,\s*long\s+\w+\s*,\s*long\s+\w+\s*,\s*BlockPos\[\]\s+\w+\s*\)")
check(ctor_body != "",
      "Section 构造器存在（签名 = 段键 + 原点 xyz + revision + signature + BlockPos[]）",
      "【1.29】比 1.28 多了 原点 xyz / revision / signature")
check("Integer.MAX_VALUE" in ctor_body and "Integer.MIN_VALUE" in ctor_body,
      "构造器在 positions 上求 min/max（紧致盒的来源）")
check("minX" in ctor_body and "maxX" in ctor_body
      and "minY" in ctor_body and "maxY" in ctor_body
      and "minZ" in ctor_body and "maxZ" in ctor_body,
      "六个边界都算了（x/y/z 的 min 与 max）")
check(re.search(r"maxX\s*\+\s*1\.0", ctor_body) is not None,
      "上界用 max+1（方块占满 [x, x+1)），不是整段边长")
# ★ 反向对照：旧写法是 baseX + SECTION_SIZE。它必须已经消失。
check("SECTION_SIZE" not in ctor_body,
      "★ 反向对照：构造器里**没有** SECTION_SIZE（旧的「整块 16³ 盒子」写法已删）")
check("baseX" not in ctor_body and "baseY" not in ctor_body and "baseZ" not in ctor_body,
      "★ 反向对照：构造器里不再有 baseX/Y/Z（不再从段原点起算盒子）")
check("new AABB(" in ctor_body and "inflate(BOX_INFLATE)" in ctor_body,
      "盒子仍外扩 BOX_INFLATE（宁可多测一点，也不能把可见的剔掉）")
check("centerX" in ctor_body and "centerY" in ctor_body and "centerZ" in ctor_body,
      "center 也基于紧致盒（拿不到视锥时的兜底剔除才准）")

# ---- 【1.28】段键必须存成字段（遮挡剔除要拿它做集合查询）----
# 为什么单独钉这一条：段键的编码一旦在别处被重算 / 改法，就会出现
# 「索引里登记得进去、遮挡查询永远查不到」的静默失效 —— 表现是整片扶梯消失。
check(re.search(r"private\s+final\s+long\s+key", index_c.replace("  ", " ")) is not None,
      "Section 把段键存成字段（而不是每帧从 positions 现算）")
check(re.search(r"public\s+long\s+key\s*\(\s*\)", index_c) is not None,
      "Section.key() 对外可取")
check("this.key = key" in ctor_body,
      "构造器把段键原样存下（不在构造器里另算一遍）")
check(re.search(r"new\s+Section\(\s*key\s*,", index_c) is not None,
      "★ scanSections 把已经算好的段键传给 Section（键只在一处产生）")

# ======================================================================
print()
print("===== 11) 【1.29】★ 全量重写：静态几何缓存 + 每帧一张贴图 =====")
# ======================================================================
# 旧架构（1.24–1.28）每帧把视野内**每个阶梯方块**的 ~380 个顶点重写一遍，因为
# 「帧号焊在顶点里」（v = (band + 帧内v) / 帧数），而帧号每刻都在变 ⇒ 几何每刻失效。
# 1.29 把帧号从顶点里搬出去，三层一起改才成立：
#   · 贴图层：320×5120 竖排图切成 16 张 320×320「每帧一张」（+ 每帧一个 RenderType）；
#   · 顶点层：只留**帧内 uv**，与时间无关 ⇒ 可以缓存成静态 VertexBuffer；
#   · 分组层：按「速度」分组，同组共用一个帧号 ⇒ 一次贴图切换画完整组（= 用户说的 N）。

# ---- 11.1 贴图层：每帧一张（而不是竖排条带 + 顶点折帧号）----
check(re.search(r"FRAMES\s*=\s*16", tex_c) is not None,
      "FRAMES = 16（与 MTR 的 .mcmeta 帧序长度一致）")
check(re.search(r"final\s+DynamicTexture\[\]\s+textures", tex_c) is not None,
      "FrameSet 持有 DynamicTexture[]（每帧一张贴图，帧号 = 绑哪张）")
check(re.search(r"final\s+RenderType\[\]\s+types", tex_c) is not None,
      "FrameSet 持有 RenderType[]（每帧一个渲染类型）")
split_body = body(tex_c, r"private static FrameSet split\s*\(")
check(split_body != "", "有 split()：把竖排图切成每帧一张")
check(re.search(r"sourceFrame\s*\*\s*width", split_body) is not None,
      "★ 每帧 = 竖排图自第 sourceFrame 行起的 width 行（裁切偏移量的公式）")
check("copyRect(" in split_body,
      "用 NativeImage.copyRect 只拷一帧（不是整条）")
check("copyRectVerified(" in split_body and "manualCopy(" in split_body,
      "★ 裁切结果当场抽样验证，不符预期就退逐像素拷贝（不赌 copyRect 的语义）")
# ★ 反向对照：被实测证伪的「纹理矩阵偏移」路线不得复活
check("setTextureMatrix" not in tex_c and "OffsetTexturingStateShard" not in tex_c,
      "★ 反向对照：不用纹理矩阵（1.20.4 的 cutout 着色器根本不读 TextureMat，已实测证伪）")
check("texCoord0 = UV0" in textures,
      "★ 证伪依据写在注释里（rendertype_cutout.vsh 第 30 行 texCoord0 = UV0）")

# ---- 11.2 up/down 像素去重（省一半显存，但**验证过才省**）----
pe_body = body(tex_c, r"private static boolean pixelsEqual\s*\(")
check(pe_body != "", "有 pixelsEqual()：判 up/down 两张图是不是逐像素相同")
check("Arrays.equals(" in pe_body and "getPixelsRGBA()" in pe_body,
      "★ 用 Arrays.equals(getPixelsRGBA()) 做**全量**逐像素比较（不是抽样）")
check("family.shared = true" in tex_c,
      "像素相同时才标记共享（setDown = upSet）")
check("family.setDown != family.setUp" in tex_c,
      "释放时避免把同一份共享贴图放两遍（shared 时 setDown == setUp）")

# ---- 11.3 分组层：同速度 ⇒ 同帧号 ----
check(re.search(r"MAX_GROUPS\s*=\s*16", groups_c) is not None,
      "MAX_GROUPS = 16（分组数有硬上限，防「每条扶梯一个速度」的存档顶穿帧时间）")
check(re.search(r"SLOTS_PER_GROUP\s*=\s*4", groups_c) is not None,
      "SLOTS_PER_GROUP = 4（平层/斜坡 × 上/下行）")
check(re.search(r"QUANTUM\s*=\s*1000\.0", groups_c) is not None,
      "QUANTUM = 1000（速度量化到 1/1000，避免浮点抖动把同速静默拆成两组）")
check("epoch++" in groups_c,
      "★ 组表变化（新速度 / 配置改 / 退世界）都让 epoch 前进 ⇒ 缓存整片作废")
indexfor_body = body(groups_c, r"public static int indexFor\s*\(")
check("quantize(" in indexfor_body and "speeds[i] == key" in indexfor_body,
      "indexFor 先量化再按值查表（不是按原始浮点比）")
reset_g = body(groups_c, r"public static void reset\s*\(\s*\)")
check("speeds[0] = 0.0" in reset_g and "count = 1" in reset_g,
      "★ reset() 保留第 0 组「不动」（刷停的阶梯靠它固定第 0 帧）")

# ---- 11.4 缓存层：顶点是静态的、分段局部的、不含帧号 ----
build_body = body(cache_c, r"private static void build\s*\(")
check(build_body != "", "有 build()：只在「段需要重建」时写一次顶点")
check("EscalatorStepGroups.indexFor(" in build_body,
      "★ 建几何时按速度查组号（槽 = 速度组 × 贴图族）")
check("model.stopped()" in build_body,
      "★ 刷停的阶梯速度取 0 ⇒ 落第 0 组 ⇒ 帧号恒为 0（旧版那条单独判断自然成立）")
wb_body = body(cache_c, r"private static int writeBlock\s*\(")
check(wb_body != "", "有 writeBlock()：写一个方块的阶梯面")
check(re.search(r"pos\.getX\(\)\s*-\s*cached\.originX", wb_body) is not None,
      "★ 顶点写的是**分段局部坐标**（pos − 段原点，0..16）—— 平移交给 ChunkOffset")
check("LevelRenderer.getLightColor(" in wb_body,
      "每方块查 1 次光照并烘进顶点（光照变化因此要靠自愈刷新兜，见 11.5）")
check(re.search(r"data\[o \+ 3\],\s*data\[o \+ 4\]", wb_body) is not None,
      "★ uv 直接用帧内分量（**不再折帧号** —— 这是几何得以静态化的那一刀）")
# ★ 反向对照：旧的 (band + v) / bandCount 折帧号写法不得复活
check("bandCount" not in wb_body,
      "★ 反向对照：writeBlock 里没有「折进条带」的 bandCount（旧的折帧号写法已删）")
check("VertexBuffer.Usage.STATIC" in cache_c,
      "★ VertexBuffer 用 Usage.STATIC（跨帧复用，不是每帧重传）")
# ---- ★★ 上传必须是 bind() → upload() → unbind()（2026-09-26 崩溃的根因） ----
# VertexBuffer.upload() 自己**不碰 VAO**：它做的是「往当前绑定的 VAO 里写属性指针 + 记 EBO」
# （uploadVertexBuffer → format.setupBufferState()；uploadIndexBuffer → 共享顺序索引缓冲 bind）。
# 少了 bind() ⇒ 我们这份 VertexBuffer 的 VAO 里空空如也 ⇒ 每次 glDrawElements 都报
# GL_INVALID_OPERATION: Invalid VAO/VBO/pointer usage ⇒ 报够一百次后 NVIDIA 驱动在
# nvoglv64.dll 里读空指针把游戏打死（崩溃报告 2026-09-26 12:45 就是这个）。
# 原版的上传体 SectionRenderDispatcher.method_43610（反汇编实测）正是这三句。
slot_body = body(cache_c, r"private static void finishSlot\s*\(")
check(slot_body != "", "有 finishSlot()：收尾一个槽并上传")
_i_bind = slot_body.find("buffer.bind()")
_i_upload = slot_body.find("buffer.upload(")
_i_unbind = slot_body.find("VertexBuffer.unbind()")
check(_i_bind >= 0 and _i_upload > _i_bind and _i_unbind > _i_upload,
      "★★ 上传顺序是 bind() → upload() → unbind()（少了那个 bind() 就是 1.29 第一版的崩溃）")
check("buffer.upload(active.end())" not in slot_body,
      "★ 反向对照：不再有裸的 upload(active.end())（没有 bind() 的 upload 会让 VAO 是空的）")
check("finally" in slot_body,
      "解绑写在 finally 里（上传抛异常也不把我们的 VAO 留在绑定状态）")
check("discardBuilder()" in cache_c and "builder.building()" in cache_c,
      "★ 异常时 discard 共用 builder（不复位会永久卡在「Already building!」）")

# ---- 11.4b 资源重载 / 模式切换：模型重烘必须让几何缓存失效 ----
# 这一条是「两处规则分叉」的典型温床：模型重烘改了顶点里的 uv，而静态几何跨帧复用、
# 看不见这件事（段 revision 没变、epoch 也没变）。整条链路必须连起来：
#   tickReloadCheck() == true  ⇒  onClientTick 里 invalidate()  ⇒  epoch 前进  ⇒  touch() 重建。
reload_body = body(models_c, r"public static boolean tickReloadCheck\s*\(")
check(reload_body != "",
      "★ tickReloadCheck() 返回 boolean（把「我清了模型缓存」这件事报给调用方）")
check("return true" in reload_body and "return false" in reload_body,
      "两条出口分别是 true（清了）/ false（没变）")
client_tick_body = body(renderer_c, r"public static void onClientTick\s*\(")
check("if (EscalatorStepModels.tickReloadCheck())" in client_tick_body.replace("  ", " "),
      "★ onClientTick 真的处理了 true（不是把返回值丢掉）")
check("EscalatorStepGroups.invalidate()" in client_tick_body,
      "★ 模型重烘 ⇒ 前进 epoch（下一帧可见段惰性重建，顺带重算分组）")

# ---- 11.5 失效判据：版本戳 + 组表版本 + 轮转自愈 ----
touch_body = body(cache_c, r"public static void touch\s*\(")
check("cached.built" in touch_body,
      "touch 判「还没建过」（区分「确实没台阶面」与「还没建」）")
check(re.search(r"builtRevision\s*!=\s*section\.revision\(\)", touch_body) is not None,
      "★ 段版本戳变了才重建（相等 ⇒ 整段跳过、一个方块都不碰）")
check(re.search(r"builtEpoch\s*!=\s*epoch", touch_body) is not None,
      "★ 组表版本变了就重建（「哪条扶梯归哪组」可能变了）")
check(re.search(r"REFRESH_FRAMES\s*=\s*60", cache_c) is not None,
      "REFRESH_FRAMES = 60（约 1 秒把所有可见段无条件刷一遍）")
refresh_body = body(cache_c, r"public static int refreshVisible\s*\(")
check("budget" in refresh_body and "/ REFRESH_FRAMES" in refresh_body,
      "★ 预算按「可见顶点数 / 60」缩放（开销恒定在全量重建的 1/60，不随视野规模涨）")
check("refreshCursor" in refresh_body,
      "刷新走游标轮转（不是每帧都从头刷同一个段）")
ev_body = body(cache_c, r"private static void evictOldest\s*\(")
check("lastUsedFrame >= frameStamp" in ev_body,
      "★ LRU 淘汰跳过「本帧用过」的段（它们马上要画，剔了会闪）")
check("releaseBuffers()" in cache_c and ".close()" in cache_c,
      "释放时 close() 掉 GL 缓冲（显存不泄漏）")
# ---- ★ 重建要「原地复用」GL 缓冲，不能每轮 close()+new ----
# 轮转自愈刷新每帧重建「可见顶点数 / 60」的段；如果每次重建都释放再新建，就变成
# 「每帧删掉并新建几十个 GL 缓冲」—— 那正是这套缓存本来要省掉的开销。
# 唯一允许释放的是「这个段被拆空了」那一支（它下面马上就要从索引里消失）。
_rebuild_body = body(cache_c, r"private static void rebuild\s*\(")
_i_rel = _rebuild_body.find("cached.releaseBuffers()")
_i_reset = _rebuild_body.find("resetForRebuild()")
check(_i_reset > 0 and _i_rel > 0 and _i_reset > _i_rel
      and _rebuild_body.count("cached.releaseBuffers()") == 1,
      "★ rebuild() 走 resetForRebuild()，且 releaseBuffers() 只剩「段被拆空」那一支")
check("if (!used[i] && cached.buffers[i] != null)" in cache_c,
      "建几何时把「这一轮没再用到的槽」的 GL 缓冲还回去（速度改了之后旧槽不白占显存）")

# ---- 11.6 绘制层：ChunkOffset（照抄原版 renderSectionLayer）----
# ★ 这里要断言**字符串字面量**本身，所以必须保留字面量剥注释（默认会把它抹成 ""）。
check(re.search(r'CHUNK_OFFSET_UNIFORM\s*=\s*"ChunkOffset"',
                strip_java(renderer, keep_strings=True)) is not None,
      'uniform 名是 "ChunkOffset"（rendertype_cutout.json 里声明过，1.20.4 实测）')
draw_body = body(renderer_c, r"private static int draw\s*\(")
check("chunkOffset.set(" in draw_body and "chunkOffset.upload()" in draw_body,
      "★ 每段设 ChunkOffset + upload（原版画区块那一趟的做法）")
check(re.search(r"cached\.originX\(\)\s*-\s*cameraPos\.x", draw_body) is not None,
      "★ 平移量 = 段原点 − 相机位置（分段局部坐标 ⟶ 相机相对坐标）")
# ★★ 槽内形状必须是「apply() 一次 → 逐段 bind()+draw() → clear() 一次」。
#    两个反例都被实测钉死：
#      (a) 每段 drawWithShader：它收尾自带 ShaderInstance.clear()（glUseProgram(0) + 解绑所有
#          采样器纹理）⇒ 从第二段起 chunkOffset.upload() 撞上「没有程序」⇒
#          GL_INVALID_OPERATION: No active program（崩溃日志里就有这一条）；
#      (b) 第一段 drawWithShader + 其余段 draw()：同样因为 clear() 让后面的段画在空状态上。
_i_apply = draw_body.find("shader.apply()")
_i_draw = draw_body.find("buffer.draw()")
_i_clear = draw_body.find("shader.clear()")
_i_chunk_up = draw_body.find("chunkOffset.upload()")
check(_i_apply > 0 and _i_draw > _i_apply and _i_clear > _i_draw,
      "★★ 形状是 apply() → 逐段 bind()+draw() → clear()（原版 LevelRenderer 那一趟）")
check(0 < _i_apply < _i_chunk_up < _i_draw,
      "★★ ChunkOffset.upload() 夹在 apply() 与 clear() 之间 ⇒ 上传时程序一定绑着（否则报 No active program）")
check(re.search(r"buffer\.bind\(\);\s*buffer\.draw\(\)", draw_body) is not None,
      "★ 每段是 bind() + draw()（draw 才是原版逐段提交静态缓冲用的那个 API）")
check(re.search(r"chunkOffset\.set\(0\.0F,\s*0\.0F,\s*0\.0F\)", draw_body) is not None,
      "★ 循环收尾把 ChunkOffset 复位（原版也这么做，免得留给下一个用同一着色器的绘制）")
check(draw_body.count("shader.apply()") == 1 and draw_body.count("shader.clear()") == 1,
      "★ apply() / clear() 每槽各一次（不是每段一次 —— 那正是 1.29 第一版多出来的开销）")
check("drawWithShader(" in draw_body and "poseStack.pushPose()" in draw_body,
      "拿不到 ChunkOffset 的着色器走 drawWithShader + 矩阵栈兜底（它自带 apply/clear，自洽）")
check("slotPresent" in draw_body,
      "只对「真的有内容」的槽 setupRenderState（空槽不设状态、不画）")
# 每槽一次的 uniform 设置块（逐行对应 VertexBuffer._drawWithShader 里的那一段）
uniforms_body = body(renderer_c, r"private static void applyLayerUniforms\s*\(")
check(uniforms_body != "", "有 applyLayerUniforms()：每槽只做一次的 uniform 设置")
check("setSampler(" in uniforms_body and "getShaderTexture(" in uniforms_body,
      "★ applyLayerUniforms 里有 sampler 循环（RenderSystem._setShaderTexture 只记 id、不填 samplerMap，"
      "少了它方块图集与光照图根本不会被绑上）")
check(re.search(r"SAMPLER_SLOT_COUNT\s*=\s*12", renderer_c) is not None,
      "采样器槽数用常量 12（与 RenderSystem.shaderTextures 长度、原版那两处循环一致）")
for _f in ("MODEL_VIEW_MATRIX", "PROJECTION_MATRIX", "COLOR_MODULATOR",
           "FOG_START", "FOG_END", "FOG_COLOR", "FOG_SHAPE"):
    check(_f in uniforms_body, "applyLayerUniforms 设了 " + _f + "（1.20.4 cutout 声明了它）")
check("RenderSystem.setupShaderLights(shader)" in uniforms_body,
      "调 RenderSystem.setupShaderLights()（原版也是在这句之后 apply()）")
check(uniforms_body.count("!= null") >= 8,
      "每个 uniform 都判空（没在 shader json 里声明的就是 null，跨资源包/跨版本不会 NPE）")

# ----------------------------------------------------------------------
# ★ 11.7 数值等价：新式（每帧一张贴图 + 帧内 v）必须与旧式（竖排条带 + 折帧号）**逐像素一致**。
#   旧式：v_old = (band + vIn) / FRAMES —— 采的是整条竖排图里「第 (band+vIn) 帧高」的位置；
#   新式：第 band 张贴图 = 竖排图里第 band 帧那一段，v_new = vIn 即段内位置。
#   两者指到同一行 ⟺ 裁切偏移量正好是 band × 帧高。
#   这里直接比**行号**（不用抽样）：差 1 行就是帧边界上闪一条缝，肉眼很难发现。
# ----------------------------------------------------------------------
import random

FRAMES = 16
FRAME_PX = 320                      # 一帧 320×320（MTR 4.x 的 escalator_up.png 是 320×5120）
STRIP_H = FRAMES * FRAME_PX

rng = random.Random(20260926)
worst_row_delta = 0
for band in range(FRAMES):
    for _ in range(3000):
        v_in = rng.random()                                  # 帧内 v ∈ [0,1)
        row_old = int((band + v_in) / FRAMES * STRIP_H)       # 旧式：折进整条竖排图
        row_new = band * FRAME_PX + int(v_in * FRAME_PX)      # 新式：先裁帧、再按帧内 v 取行
        worst_row_delta = max(worst_row_delta,
                              abs(min(row_old, STRIP_H - 1) - min(row_new, STRIP_H - 1)))
check(worst_row_delta <= 1,
      "★ 数值等价：新式（每帧一张 + 帧内 v）与旧式（竖排条带 + 折帧号）采到**同一行**",
      "48000 组 (band, vIn) 下最大行号差 %d（≤1 属浮点取整，不是错位）" % worst_row_delta)

# ★ 相位一致性（用户最在意的观感）：同速度的扶梯**任何时刻**都必须完全同相。
#   实现上靠「帧号 = floor((刻 + 部分刻) × 速度/原版速度)」是**纯函数**，
#   而不是「每条扶梯各自累加」。累加式会漂移，表现是「同速的两条扶梯慢慢错开一帧」。
frameof_body = body(renderer_c, r"private static int frameOf\s*\(")
check("(tickCounter + partialTick)" in frameof_body,
      "★ 帧号由 (刻 + 部分刻) 直接算出（纯函数、无状态 ⇒ 同速必然同相、永不漂移）")
check("floorMod(" in frameof_body,
      "帧号取模用 floorMod（负数也不会得到负帧号 ⇒ 不会数组越界）")
check("%" not in frameof_body,
      "★ 反向对照：没有用裸 % 取模（负数会得到负值，负帧号直接越界）")

# 相位一致性可核验地算一遍：同一速度在两台「机器」上必须给出同一个帧号。
def frame_of(tick, partial, speed, vanilla, frames):
    total = int((tick + partial) * (speed / vanilla))
    return total % frames if total >= 0 else total % frames + frames


worst_phase_mismatch = 0
for speed in (0.5, 1.0, 1.35, 2.0, 3.75):
    for tick in range(0, 400):
        a = frame_of(tick, 0.0, speed, 2.0, FRAMES)
        b = frame_of(tick, 0.0, speed, 2.0, FRAMES)
        worst_phase_mismatch = max(worst_phase_mismatch, abs(a - b))
check(worst_phase_mismatch == 0,
      "★ 相位一致性：同速度的两条扶梯在任意刻得到**完全相同**的帧号（差 0）",
      "5 个速度 × 400 刻，最大帧号差 %d" % worst_phase_mismatch)

# 方向帧序：bandFor 必须走 family.order(up)（up 是 0..15、down 是 15..0，像素同一份）
bandfor_body = body(tex_c, r"public static int bandFor\s*\(")
check("order(up)" in bandfor_body,
      "★ bandFor 按方向取帧序（上下行只差帧序，像素共用）")
check("floorMod(" in bandfor_body,
      "bandFor 用 floorMod 索引帧序（不做负数越界）")
order_body = body(tex_c, r"private static int\[\] readFrameOrder\s*\(")
check("identityOrder()" in order_body,
      "读不到 .mcmeta 时退回顺序帧序（坏素材只是动画顺序不同，不会消失）")
check(re.search(r"return identityOrder\(\)", order_body) is not None,
      "★ 帧序越界 / 解析失败一律退回 identityOrder（不留半个坏帧序）")
# ★ 反向对照：bandFor 不得把帧号再折回顶点/条带
check("bandCount" not in bandfor_body,
      "★ 反向对照：bandFor 不产生「条带帧数」这类折帧号用的量")

# ======================================================================
print()
print("===== 12) 【1.26→1.29】性能计数：剔除 / 自愈刷新 / 重建 / 绘制 分开报 =====")
# ======================================================================
# 只看总耗时判断不了瓶颈在哪一边。1.29 把「重建」从「剔除」里分出来单列，是因为
# 它是**随变化偶发**的 —— 混进别处会造成「进新区域那一帧看起来特别慢」的误判。

check("statsCullMsAverage" in renderer_c and "statsRefreshMsAverage" in renderer_c
      and "statsDrawMsAverage" in renderer_c and "statsBuildMsAverage" in renderer_c,
      "剔除 / 自愈刷新 / 绘制 / 重建 各自记滚动均值")
check("statsCullNanosSum" in renderer_c and "statsRefreshNanosSum" in renderer_c
      and "statsDrawNanosSum" in renderer_c and "statsBuildNanosSum" in renderer_c,
      "四段各自累加纳秒（不是只记总数）")
check("statsDrawnVertices" in renderer_c and "statsDrawCalls" in renderer_c,
      "记录上帧交给 GPU 的顶点数与绘制调用次数")
check(re.search(r"recordStats\(\s*frameStartNanos,\s*cullNanos,\s*refreshNanos,\s*drawNanos",
                renderer_c.replace("\n", " ")) is not None,
      "recordStats 的调用点传了「剔除 / 刷新 / 绘制」三段耗时",
      "recordStats( 出现 %d 次" % renderer_c.count("recordStats("))
check("statsBuiltByRevision" in renderer_c and "statsBuiltByRefresh" in renderer_c,
      "重建按来源分两栏（版本戳 / 自愈刷新）—— 稳态下应当接近 0，这正是本次重写要的效果")
sl = body(renderer_c, r"public static String statsLine\s*\(")
check(sl != "" and "statsDistanceCulledSections" in sl and "statsDrawnVertices" in sl
      and "statsDrawCalls" in sl and "statsCullMsAverage" in sl
      and "statsRefreshMsAverage" in sl and "statsDrawMsAverage" in sl,
      "statsLine 的参数里确实带了「三段耗时 + 顶点数 + 绘制调用 + 剔除细分」")
# ★ 断言「格式串里有这些文案」必须**保留字符串字面量**再判
#   （默认的 strip_java 会把 "" 抹掉，直接断言会假红）。
sl_cs = body(strip_java(renderer, keep_strings=True), r"public static String statsLine\s*\(")
check("\u5254\u9664 %.2f" in sl_cs and "\u81ea\u6108\u5237\u65b0 %.2f" in sl_cs
      and "\u7ed8\u5236 %.2f" in sl_cs,
      "statsLine 把三段耗时都打印出来（剔除 / 自愈刷新 / 绘制）")
check("\u91cd\u5efa %.2f" in sl_cs,
      "statsLine 把「其中重建 %.2f」单独列（它是前两段里的子集，不参与相加）")
check("\u592a\u8fdc %d" in sl_cs and "\u89c6\u91ce\u5916 %d" in sl_cs,
      "statsLine 把剔掉的段拆成「太远 / 视野外」两栏")
check("tickCounter++" in renderer_c,
      "每刻递增 tickCounter（帧号 = f(刻) 的时间基准）")

# ★ statsLine 是 String.format —— 占位符与实参数量不一致会在运行时抛
#   MissingFormatArgumentException（也就是「打 /mtrxr 崩一下」）。这里静态核对一次。
def format_specifiers(fmt):
    """数出一个格式串里的 % 占位符个数（跳过 %%）。"""
    n = 0
    i = 0
    while i < len(fmt):
        if fmt[i] == "%":
            if i + 1 < len(fmt) and fmt[i + 1] == "%":
                i += 2
                continue
            n += 1
            i += 1
            while i < len(fmt) and fmt[i] not in "diuoxXeEfgGaAcbshnS%":
                i += 1
        i += 1
    return n


def split_top_level(args_src):
    """按最外层逗号切分实参（跳过括号 / 字符串里的逗号）。"""
    parts = []
    depth = 0
    in_str = False
    cur = []
    i = 0
    while i < len(args_src):
        c = args_src[i]
        if in_str:
            cur.append(c)
            if c == "\\":
                i += 1
                if i < len(args_src):
                    cur.append(args_src[i])
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            cur.append(c)
        elif c in "([":
            depth += 1
            cur.append(c)
        elif c in ")]":
            depth -= 1
            cur.append(c)
        elif c == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    tail = "".join(cur).strip()
    if tail:
        parts.append(tail)
    return parts


# 把 statsLine 里所有字符串字面量按出现顺序拼起来 = 真正的格式串
sl_literals = re.findall(r'"((?:[^"\\]|\\.)*)"', sl_cs)
sl_format = "".join(sl_literals)
spec = format_specifiers(sl_format)
# 方法体里 "return String.format(" 之后的实参列表
m_args = re.search(r"return\s+String\.format\s*\((.*?)\);\s*\}", sl_cs, re.S)
arg_list = split_top_level(m_args.group(1))[1:] if m_args else []   # 第 0 个是格式串本身
check(m_args is not None and spec == len(arg_list),
      "★ statsLine 的格式占位符数 == 实参数（不一致会在 /mtrxr 时抛异常）",
      "占位符 %d 个 / 实参 %d 个" % (spec, len(arg_list)))

# ======================================================================
print()
print("===== 13) 【1.27】周期性全量重扫 → 每 tick 切片（消除 0.5 秒一次尖峰） =====")
# ======================================================================
# 旧版每 10 刻把「整个世界已加载的所有区块」一次扫完 ⇒ 随世界规模增长的周期性尖峰。

check("rescanSlice" in index_c,
      "有 rescanSlice()（每 tick 一小批）")
check("rescanQueue" in index_c and "rescanCursor" in index_c,
      "有游标队列 + 游标（切成片走）")
check("rescanSlice()" in tick_body,
      "tick() 调的是 rescanSlice() 而不是一次性全扫")
# ★ 反向对照
check("rescanAll" not in index_c,
      "★ 反向对照：rescanAll 已经不存在（旧的「一遍全扫」写法已删）")
slice_body = body(index_c, r"private static void rescanSlice\s*\(\s*\)")
check("LOADED.keySet()" in slice_body,
      "切片在走完一轮后重取 LOADED 快照（新加载的区块下一轮纳入）")
check("LOADED.get(" in slice_body and "if (chunk != null)" in slice_body,
      "快照里已卸载的区块取不到就跳过（不 NPE）")
check("RESCAN_SWEEP_TICKS" in slice_body,
      "批大小按 RESCAN_SWEEP_TICKS 自适应（每 tick 开销恒定 —— 这才是 1.27 要到的那件事）")
# ★★ 1.27 的切片把「一遍」拉长成了 RESCAN_INTERVAL × RESCAN_SWEEP_TICKS 刻，而当时的注释写成
#    「一遍墙钟时间与旧版一致（约 RESCAN_INTERVAL 刻）」—— 那是错的。这正是【1.30b】
#    「放下扶梯要等 2~3 秒台阶贴图才出现」的根因。这里把**真实数字打出来**（只报告、不断言），
#    断言在第 15 节：编辑必须走即时路，不许再依赖这一遍。
_m_interval = re.search(r"RESCAN_INTERVAL\s*=\s*(\d+)", index_c)
_m_sweep = re.search(r"RESCAN_SWEEP_TICKS\s*=\s*(\d+)", index_c)
_sweep_ticks = (int(_m_interval.group(1)) * int(_m_sweep.group(1))) if (_m_interval and _m_sweep) else 0
print("    [信息] 定期重扫一遍 = RESCAN_INTERVAL(%s) × RESCAN_SWEEP_TICKS(%s) = %d 刻 = %d ms"
      % (_m_interval.group(1) if _m_interval else "?",
         _m_sweep.group(1) if _m_sweep else "?", _sweep_ticks, _sweep_ticks * 50))
check("rescanQueue.clear()" in reset_body and "rescanCursor = 0" in reset_body,
      "reset() 会清掉游标队列（换维度 / 退世界不留脏游标）")

# ======================================================================
print()
print("===== 14) 【1.28】★ 遮挡剔除：借原版「这帧真要画的段」判「画不画」 =====")
# ======================================================================
# 「玩家看不见的扶梯不渲染」的第三条判据。前两条已经在 1.26/1.27 落地：
#   · 太远     —— 距离（distanceToBoxSqr > maxDistanceSq）
#   · 视野外   —— 视锥（frustum.isVisible(段的紧致盒)）
# 补的这一条是「在视锥里、也在视距内，但被墙 / 地形挡住」。
#
# 判据来源：原版**没有**公开的查询接口（1.20.1 的可见性状态锁在
# RenderChunkStorage.renderInfoMap / RenderChunkInfo.directions 里，都是包私有；
# 公开的只有 countRenderedChunks() 这类总数），所以借
# `LevelRenderer.renderChunksInFrustum` —— 原版 renderChunkLayer 就是遍历它画地形的
# （1.20.1 字节码实测：主循环里没有任何 hasDirection 门控，进列表的段一个不落都会画），
# 这个列表的定义正好就是「原版这一帧真的要画的段」。
#
# ★ 1.20.4 的对应字段叫 visibleSections（元素 SectionRenderDispatcher$RenderSection）。
#   1.20.1 的元素是 LevelRenderer$RenderChunkInfo、段本体在 .chunk 里，两边本来不同名 ——
#   本节的断言一律用 1.20.1 的名字，别拿 1.20.4 的抄。
#
# ★ 这条判据与前两条有个本质差别：**它会因外部状态而静默失效**（遮挡图还没准备好时
# 集合是空的）。失效的两种处理方式后果完全不对称：
#   · 把空集合当成「全部被挡住」⇒ 整个世界一条扶梯都不画（灾难）；
#   · 把空集合当成「判据不可用」并退回视锥剔除 ⇒ 只是少省一点。
# 所以第 14.2 节里专门钉了「空集合必须返回 false」这一条。

# ★ 判「文案里有没有那句话」必须**保留字符串字面量**再判（默认 strip_java 会把 "" 抹掉 ⇒ 假红）
renderer_cs = strip_java(renderer, keep_strings=True)
mode_cmd_cs = strip_java(mode_cmd, keep_strings=True)

# ---- 14.1 access widener：private 字段必须真的被放开 ----
aw = load(AW)
check(re.search(r"accessible\s+field\s+net/minecraft/client/renderer/LevelRenderer\s+"
                r"renderChunksInFrustum\s+Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
                aw) is not None,
      "★ access widener 打开了 LevelRenderer.renderChunksInFrustum（名字 + 描述符都要对）")
# 1.20.1 比 1.20.4 多要两条：元素类本身（包私有）+ 它那个包私有的 chunk 字段。
# 少了任一条 refreshVanillaVisibleKeys 就编译不过（是硬失败不是静默退化）——
# 这两条只是把「为什么必须写」固化进回归，免得将来被当成多余条目删掉。
check(re.search(r"accessible\s+class\s+net/minecraft/client/renderer/"
                r"LevelRenderer\$RenderChunkInfo", aw) is not None,
      "★ access widener 打开了 LevelRenderer$RenderChunkInfo（1.20.1 的元素类是包私有）")
check(re.search(r"accessible\s+field\s+net/minecraft/client/renderer/"
                r"LevelRenderer\$RenderChunkInfo\s+chunk\s+"
                r"Lnet/minecraft/client/renderer/chunk/"
                r"ChunkRenderDispatcher\$RenderChunk;", aw) is not None,
      "★ access widener 打开了 RenderChunkInfo.chunk（段本体所在的包私有字段）")
check("java.lang.reflect" not in renderer_c and "setAccessible(" not in renderer_c,
      "★ 反向对照：走的是 access widener，不是反射 setAccessible（跨版本会炸）")

# ---- 14.2 每帧收集合：段来源与「空集合怎么办」 ----
refresh_body = body(renderer_c, r"private static boolean refreshVanillaVisibleKeys\s*\(")
check(refresh_body != "", "有 refreshVanillaVisibleKeys()（每帧收一份段键集合）")
check("renderChunksInFrustum" in refresh_body and "getOrigin()" in refresh_body,
      "集合来源是 renderChunksInFrustum 里每个 RenderChunkInfo.chunk 的 origin")
check("info.chunk == null" in refresh_body and "info.chunk.getOrigin()" in refresh_body,
      "★ 必须剥到 info.chunk（1.20.1 的元素比 1.20.4 多一层），且判空之后才取 origin")
check("blockToSectionCoord(" in refresh_body,
      "★ origin 是**方块坐标**，必须过一次 blockToSectionCoord 才变成段坐标"
      "（少了这一关就整体偏 16 倍 ⇒ 每个段都查不到 ⇒ 整片扶梯消失）")
check("SectionPos.asLong(" in refresh_body,
      "段键用 SectionPos.asLong 打包（与索引产键处同一套编码）")
check("vanillaVisibleKeys.clear()" in refresh_body,
      "复用同一个集合（clear 而不是每帧 new —— 热路径不制造垃圾）")
check(re.search(r"return\s+!\s*vanillaVisibleKeys\.isEmpty\(\)", refresh_body) is not None,
      "★ 空集合返回 false（「查不到」不等于「被挡住」，否则全世界一条扶梯都不画）")

# ---- 14.3 剔除循环里的接线顺序 ----
check("occlusionActive" in render_body,
      "剔除循环里接了 occlusionActive 开关")
check("vanillaVisibleKeys.contains(section.key())" in render_body,
      "判据是「段键在不在原版可见集里」（O(1) 查询，不是遍历）")
check("occludedSections++" in render_body,
      "被挡住的段单独计数（不混进「视野外」，否则统计行会骗人）")
check("EscalatorRenderMode.isOcclusionCulling()" in render_body,
      "渲染端读总开关（默认开）")
# ★ 顺序对照：视锥判定必须在集合查询**之前**（更便宜的在前，而且用紧致盒更准）
check(render_body.find("frustum.isVisible(") >= 0
      and render_body.find("frustum.isVisible(") < render_body.find("vanillaVisibleKeys.contains("),
      "★ 顺序对照：先视锥、后集合查询（便宜的在前，明显不在视野里的不做那次查询）")

# ---- 14.4 统计行必须能证明判据有没有在干活 ----
check("statsOcclusionActive" in renderer_c and "statsVanillaVisibleSections" in renderer_c,
      "统计里记了「判据本帧是否生效」与「本帧原版可见段数」")
check(re.search(r"\u88ab\u6321\u4f4f %d", sl_cs) is not None,
      "statsLine 打印「被挡住 %d」")
check(re.search(r"\u906e\u6321\u5254\u9664 %s", sl_cs) is not None,
      "★ statsLine 打印遮挡剔除的实时状态（唯一会静默失效的判据，必须看得见）")
check("occlusionStateText" in renderer_c and "\u5df2\u56de\u9000\u89c6\u9525\u5254\u9664" in renderer_cs,
      "状态文案里区分「生效中」与「已回退视锥剔除」（回退不是关闭，画面不缺东西）")

# ---- 14.5 逃生开关：默认开，但必须能一键关掉 ----
check(re.search(r"private\s+static\s+boolean\s+occlusionCulling\s*=\s*true\s*;", mode_c) is not None,
      "★ 遮挡剔除默认值是 true（用户点名要「默认开」）")
occ_body = body(mode_c, r"public static boolean applyOcclusion\s*\(\s*boolean\s+\w+\s*\)")
check(occ_body != "", "EscalatorRenderMode.applyOcclusion(boolean) 存在")
check("save()" in occ_body, "开关会持久化（下次启动保持）")
check("reloadResourcePacks" not in occ_body and "EscalatorStepModels.clear()" not in occ_body,
      "★ 开关不清烘焙缓存、不重载资源包（它只影响一次集合查询，能立刻生效）")
save_body = body(mode_c, r"private static void save\s*\(\s*\)")
check("KEY_ENGINE" in save_body and "KEY_OCCLUSION" in save_body,
      "★ save() 是整份覆盖写：两个配置项都必须写一遍（少写一个会静默重置成默认值）")
load_body = body(mode_c, r"public static void load\s*\(\s*\)")
check("KEY_OCCLUSION" in load_body and "VALUE_OFF" in load_body,
      "load() 会读回遮挡剔除开关（键不存在 ⇒ 按默认「开」）")
check("setOcclusion" in mode_cmd_c and "\u906e\u6321\u5254\u9664" in mode_cmd_cs,
      "指令层能切遮挡剔除（/mtrxr occ on|off）")

# ======================================================================
print()
print("===== 15) 【1.30b】★ 放/拆扶梯要「立刻」进索引：方块变更钩子（修 2~3 秒延迟） =====")
# ======================================================================
# 症状（用户原话）：「玩家放置扶梯时，阶梯贴图会等 2~3 秒出现」。
#
# 根因：索引只有「定期重扫」一条路 —— 而 1.27 的切片让一遍要走
#   RESCAN_INTERVAL × RESCAN_SWEEP_TICKS = 100 刻（5 秒），
# 于是放下扶梯后要等「下次轮到那座区块」：均值 2.5 秒、最坏 5 秒（与「2~3 秒」逐字吻合）。
# 而这期间 MTR 原版那份静止阶梯面**已经被透明标记贴图隐藏** ⇒ 台阶整片是空的，
# 看起来就是「扶梯放着没台阶，过两三秒才冒出来」。
#
# 修法：给索引一条**事件驱动**的即时入口 —— 客户端在 Level.onBlockStateChange
# （Level.setBlock 的末尾）里只记「哪个区块脏了」，下一个客户端刻只重扫那几个区块（≤1 刻）。
# 定期重扫保持不变，降级为兜底。

mix = load(BLOCK_CHANGE_MIXIN) if os.path.exists(BLOCK_CHANGE_MIXIN) else ""
check(bool(mix), "有 src/client/java/smooth/lift/client/mixin/LevelBlockChangeMixin.java")
# 注解里的方法名是**字符串**，所以要 keep_strings=True；注释仍然要剥掉
# （类注释里写着 Level.setBlock / setServerVerifiedBlockState 这些解释性文字）。
mix_c = strip_java(mix, keep_strings=True)

check("@Mixin(Level.class)" in mix_c,
      "mixin 目标是 Level（onBlockStateChange 是 Level.setBlock 末尾那次调用）")
check("onBlockStateChange" in mix_c,
      "注入的方法是 onBlockStateChange")
check('@At("HEAD")' in mix_c,
      "注入点在 HEAD（只当通知用，不改原方法行为）")
check("cancellable" not in mix_c,
      "★ 非 cancellable：绝不干预原版 setBlock 的语义")
check("EscalatorStepIndex.onBlockChanged(" in mix_c,
      "钩子把事件转给 EscalatorStepIndex.onBlockChanged")
# ★ 反向对照：钩子里不许做重活（它可能跑在网络包处理路径上）
for heavy in ("applyChunk", "scanSections", "getChunkNow", "getBlockState"):
    check(heavy not in mix_c,
          "★ 反向对照：mixin 体内不出现 %s（只记账、不扫描；扫描放到下一个客户端刻）" % heavy)
check("isEscalatorStep" not in mix_c,
      "★ 钩子不过滤方块类型（「拆掉扶梯」时新状态是空气，过滤会漏掉那一半）")

mixins_json = load(CLIENT_MIXINS_JSON)
try:
    cfg = json.loads(mixins_json)
except ValueError:
    cfg = {}
check("LevelBlockChangeMixin" in cfg.get("client", []),
      "★ 注册在 smoothlift.client.mixins.json 的 **client** 数组里（专用服务端不加载它）")
check("LevelBlockChangeMixin" not in cfg.get("mixins", []),
      "★ 反向对照：不在「两侧都加载」的 mixins 数组里")

# ---- 15.1 索引侧：即时路的结构 ----
check("dirtyChunks" in index_c and "onBlockChanged" in index_c,
      "索引里有 dirtyChunks + onBlockChanged")
ob_body = body(index_c, r"public static void onBlockChanged\s*\(\s*Level\s+\w+\s*,\s*BlockPos\s+\w+\s*\)")
check(ob_body != "", "onBlockChanged(Level, BlockPos) 存在")
check("instanceof ClientLevel" in ob_body,
      "★ 用 instanceof ClientLevel 认客户端世界（集成服务端的 setBlock 也会走到这里）")
# ★ 必须同时要求「存在」与「在前」：只写 `<` 的话，判据消失时 find 返回 -1，
#   -1 < 任何下标 都为真 ⇒ 这条会**静默变绿**（判据被「改没了」骗过）。
check(0 <= ob_body.find("instanceof ClientLevel") < ob_body.find("dirtyChunks.add"),
      "★ 判据在**记账之前**（顺序反了 = 服务端线程会碰这个静态集合）")
check("ChunkPos.asLong(" in ob_body,
      "记账按**区块**去重（一座扶梯 4 个方块 + 客户端预测/服务端下发各一遍 ⇒ 最多 2 个区块）")
ac_body = body(index_c, r"private static void applyDirtyChunks\s*\(\s*ClientLevel\s+\w+\s*\)")
check(ac_body != "", "有 applyDirtyChunks（每客户端刻抽干脏区块）")
check("getChunkNow(" in ac_body and "getChunk(" not in ac_body,
      "★ 走 getChunkNow（取不到返回 null，**不会凭空加载区块**）")
check("MAX_DIRTY_CHUNKS_PER_TICK" in ac_body,
      "单 tick 有上限（防「一次大量方块变化」把某一刻顶成尖峰）")
check("iterator.remove()" in ac_body,
      "★ 只删**处理过的**（超上限的留到下一刻，而不是一刀清空）")
check("applyChunk(" in ac_body,
      "重扫走统一的 applyChunk 入口（与 CHUNK_LOAD / 定期重扫同一条路）")
# ★★ 最强的一条：即时路必须**不受重扫倒计时管辖**
check(tick_body.find("applyDirtyChunks(level)") >= 0
      and tick_body.find("applyDirtyChunks(level)") < tick_body.find("--countdown"),
      "★★ tick() 里 applyDirtyChunks 在 --countdown **之前**（否则又变回「等定期重扫」）")
check("dirtyChunks.clear()" in reset_body,
      "reset() 清掉待处理集合（换世界 / 换维度后那些坐标没有意义）")

# ---- 15.2 延迟算术（「2~3 秒」的来源；改完必须 ≤1 刻）----
_tick_ms = 1000.0 / 20.0
_sweep_ms = _sweep_ticks * _tick_ms
print("    [信息] 旧路径（只靠定期重扫）：最坏 %.0f ms、均值 %.0f ms —— 与「2~3 秒」一致"
      % (_sweep_ms, _sweep_ms / 2.0))
print("    [信息] 新路径（方块变更钩子）：最坏 1 刻 = %.0f ms" % _tick_ms)
check(_sweep_ticks > 1 and _tick_ms < _sweep_ms,
      "即时路比定期重扫快一个数量级以上（靠事件驱动，不是把片切大）")

# ======================================================================
if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
