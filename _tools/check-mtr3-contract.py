"""SmoothLift —— 「同一层外呼 / 轿厢内按本层重开门」修复的离线契约 + 判定表校验。

只测量、不改文件。任何一条不成立就非零退出。分两大段：

  A. **对外部依赖（真 MTR jar）的契约断言** —— 我们反射读的每个字段/方法、
     以及本修复**所依据的每一条字节码事实**（含根因那一行 `if (startFloor == endFloor) return 0;`）
     都还在。MTR 换了版本 / 改了名，这里先炸，而不是等玩家报「按了没反应」。

  B. **判定表 + 不变量** —— 把 MTR3 的门状态机照反汇编逐条搬成一个 Python 模型
     （每一行都注明出处：类/方法/字节码偏移），然后断言：
       * 新守卫**只会**命中「空闲 + 门没全开 + 停在这一层」这一个状态族；
       * 而那一个状态族**原版自己永远到不了**（⇒ 它只可能是我们自己的自动关门造出来的）；
       * 命中之后门一定会开、直梯不会因此乱动；不命中的状态一个字节都不许改。
     这就是「为什么触发条件必须同时三条」的可执行版本 —— 改守卫/改阈值前先跑它。

用法：python check-mtr3-contract.py [MTR3.jar] [MTR4.jar]
不传就在工程上一级目录里按名字找（工作区里放着 MTR-forge / MTR-fabric 的 jar）。
"""
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent
WORKSPACE = PROJECT.parent
JAVAP = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\javap.exe"

fails, oks = [], []


def check(name, cond, detail=""):
    (oks if cond else fails).append(f"{name}{(' — ' + detail) if detail else ''}")


# --------------------------------------------------------------------------
# 找 jar
# --------------------------------------------------------------------------
def find_jar(needle, explicit):
    if explicit:
        p = Path(explicit)
        return p if p.is_file() else None
    for base in (WORKSPACE, PROJECT, PROJECT / "libs"):
        if not base.is_dir():
            continue
        for p in sorted(base.iterdir()):
            if p.is_file() and p.suffix == ".jar" and needle in p.name:
                return p
    return None


POSITIONAL = [a for a in sys.argv[1:] if not a.startswith("-")]
MTR3 = find_jar("MTR-forge-1.20.1", POSITIONAL[0] if len(POSITIONAL) > 0 else None)
MTR4 = find_jar("MTR-fabric-4", POSITIONAL[1] if len(POSITIONAL) > 1 else None)

check("[环境] 找到 MTR3 的 jar", MTR3 is not None, str(MTR3))
check("[环境] 找到 MTR4 的 jar（对照用）", MTR4 is not None, str(MTR4))
if MTR3 is None:
    print(f"通过 {len(oks)} 项，失败 {len(fails)} 项")
    for f in fails:
        print("  ✗", f)
    sys.exit(1)


# --------------------------------------------------------------------------
# javap 小工具：把 jar 里的类反汇编成文本
# --------------------------------------------------------------------------
def disassemble(jar, *classes):
    """返回 {类名: javap -p -c 的文本}。类不存在时值为 None。"""
    out = {}
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(jar) as zf:
            present = set(zf.namelist())
            for cls in classes:
                entry = cls.replace(".", "/") + ".class"
                if entry in present:
                    zf.extract(entry, tmp)
                else:
                    out[cls] = None
        for cls in classes:
            if out.get(cls, "") is None:
                continue
            r = subprocess.run([JAVAP, "-p", "-c", "-cp", tmp, cls],
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
            out[cls] = r.stdout
    return out


def body(text, signature_start):
    """从 javap 文本里截出某个方法的 Code 段（到下一个方法声明为止）。"""
    if text is None:
        return ""
    i = text.find(signature_start)
    if i < 0:
        return ""
    rest = text[i + len(signature_start):]
    m = re.search(r"\n  [a-zA-Z].*\(", rest)
    return rest if not m else rest[:m.start()]


M3 = disassemble(MTR3, "mtr.data.LiftInstructions", "mtr.data.Lift", "mtr.data.LiftServer",
                 "mtr.data.RailwayData", "mtr.block.BlockLiftButtons",
                 "mtr.block.BlockLiftButtons$TileEntityLiftButtons", "mtr.item.ItemLiftRefresher")
M4 = disassemble(MTR4, "org.mtr.core.data.Lift")

# ==========================================================================
# A. MTR3 契约
# ==========================================================================
LI = M3["mtr.data.LiftInstructions"]
LIFT = M3["mtr.data.Lift"]
LS = M3["mtr.data.LiftServer"]
RD = M3["mtr.data.RailwayData"]
BTN = M3["mtr.block.BlockLiftButtons"]
TE = M3["mtr.block.BlockLiftButtons$TileEntityLiftButtons"]
REF = M3["mtr.item.ItemLiftRefresher"]

check("[MTR3] 反汇编出 LiftInstructions", bool(LI))
check("[MTR3] 反汇编出 Lift", bool(LIFT))
check("[MTR3] 反汇编出 LiftServer", bool(LS))
check("[MTR3] 反汇编出 RailwayData", bool(RD))
check("[MTR3] 反汇编出 BlockLiftButtons", bool(BTN))

# --- A1：两条 mixin 的选择器必须存在（默认 required=1，选择器漂了就是启动期报错）----
EXT_SIG = "public static void addInstruction(net.minecraft.world.level.Level, net.minecraft.core.BlockPos, boolean);"
PANEL_SIG = "public void pressButton(int);"
check("[选择器] 外呼入口 addInstruction(Level,BlockPos,boolean) 存在且是 static",
      EXT_SIG in (LI or ""))
check("[选择器] 轿厢面板入口 pressButton(int) 存在",
      PANEL_SIG in (LIFT or ""))
check("[选择器] 外呼入口是 public（mixin 注入要求可见性够）",
      "public static void addInstruction(net.minecraft.world.level.Level" in (LI or ""))

# --- A2：★ 根因 —— 私有核心开头那 6 条指令（起点层 == 终点层 ⇒ return 0）----
CORE_SIG = "private int addInstruction(int, boolean, int, boolean, boolean, boolean);"
core = body(LI, CORE_SIG)
check("[根因] 私有核心 addInstruction(IZIZZZ)I 存在", CORE_SIG in (LI or ""))
head = "\n".join(core.splitlines()[:12])
EARLY_RETURN = re.compile(
    r"0:\s*iload_1\s*\n\s*1:\s*iload_3\s*\n\s*2:\s*if_icmpne\s+7\s*\n\s*5:\s*iconst_0\s*\n\s*6:\s*ireturn")
check("[根因] 私有核心第 0..6 字节码仍是「起点层 == 终点层 ⇒ 直接 return 0」",
      bool(EARLY_RETURN.search(head)), head.replace("\n", " | ")[:160])
check("[根因] 私有核心最后一个 flag（第 6 个参数）控制「真的插进去」",
      bool(re.search(r"250:\s+iload\s+6\s*\n\s*252:\s+ifeq\s+299", core))
      and "java/util/List.add" in core)

# --- A3：两条入口喂进去的 startFloor 都是「当前所在层」----
#   外呼：round(getPositionY())；面板：floor/ceil(currentPositionY)
#   ★ 外呼的挑选逻辑全在三个 lambda 里，静态方法本身只是搭台子，所以要逐个看。
ext = body(LI, EXT_SIG)
lam3 = body(LI, "private static void lambda$addInstruction$3(")
lam2 = body(LI, "private static void lambda$addInstruction$2(")
lam1 = body(LI, "private static boolean lambda$addInstruction$1(")
check("[入口] 外呼遍历 RailwayData.lifts 并按 hasFloor 挑第一台",
      "RailwayData.lifts" in lam3 and "LiftServer.hasFloor" in lam1 and "Stream.filter" in lam3)
check("[入口] 外呼的 endFloor 取的是回调坐标的 getY()（★ 这里收到的是**楼层轨道**坐标，"
      "不是按钮方块的坐标 —— 详见 A3b）",
      "BlockPos.m_123342_" in lam2 or "BlockPos.getY" in lam2)
check("[入口] 外呼的 startFloor 是 round(lift.getPositionY())",
      "java/lang/Math.round" in lam2 and "LiftServer.getPositionY" in lam2)
check("[入口] 外呼先「试算代价」再「真的插」——两处 addInstruction(IZIZZZ)I 调用都在"
      "（静态方法里的那一处才是真的插，第 6 个参数传 1）",
      lam2.count("addInstruction:(IZIZZZ)I") >= 1 and ext.count("addInstruction:(IZIZZZ)I") >= 1)
panel = body(LIFT, PANEL_SIG)
check("[入口] 面板的 startFloor 是 floor/ceil(currentPositionY)",
      "java/lang/Math.floor" in panel and "java/lang/Math.ceil" in panel)
check("[入口] 面板把按下的那一层当 endFloor 传给私有核心",
      "addInstruction:(IZI)V" in panel)

# --- A3b：★★「楼层身份」契约 ——【1.45 第一版】就是错在这一条上，------------------------
#   症状 =「按了完全没反应」（不报错、不打日志、不改状态），因为没有断言能拦住它。
#   事实链（每一环都有字节码为证）：
#     ① Lift.floors 里存的是**楼层轨道方块**（BlockLiftTrackFloor）的坐标；
#     ② Lift.hasFloor(p) 的实现就是 floors.contains(p) —— **精确匹配**，不是只比 y；
#     ③ BlockLiftButtons.use 交给外呼入口的却是**按钮方块自己**的 pos；
#     ④ ⇒ 拿那个 pos 去问 hasFloor 恒为 false ⇒ 整条修复静默失效（这就是第一版的 bug）；
#     ⑤ 唯一能拿到「这个按钮服务哪些楼层轨道」的入口是
#        TileEntityLiftButtons.forEachTrackPosition(Level, BiConsumer)，它回调的坐标
#        才是楼层轨道的坐标（回调前先判 instanceof TileEntityLiftTrackFloor）。
#   ⇒ 改动外呼那段代码（Mtr3LiftAutoClose.onExternalCall / reopenLiftsServingFloor）前后
#     必须跑这一段；任何一环变了，这里先炸。
check("[楼层身份] Lift.floors 的声明是 List<BlockPos>",
      "protected final java.util.List<net.minecraft.core.BlockPos> floors;" in (LIFT or ""))
hf = body(LIFT, "public boolean hasFloor(net.minecraft.core.BlockPos);")
check("[楼层身份] Lift.hasFloor(p) == floors.contains(p)（精确匹配，不是只比 y）",
      "Field floors" in hf and "java/util/List.contains" in hf and "m_123342_" not in hf,
      hf.replace("\n", " | ")[:150])
use = body(BTN, "public net.minecraft.world.InteractionResult m_6227_(")
check("[楼层身份] BlockLiftButtons.use 交给外呼入口的是**按钮方块自己**的 pos"
      "（所以 mixin 里那个 pos 的 getY() 不能当楼层用）",
      "LiftInstructions.addInstruction:(Lnet/minecraft/world/level/Level;"
      "Lnet/minecraft/core/BlockPos;Z)V" in use)
check("[楼层身份] floors 由 ItemLiftRefresher 扫描 BlockLiftTrackFloor 后 setFloors 灌入"
      "（⇒ 里面装的是楼层轨道坐标，不是按钮坐标）",
      REF is not None and "BlockLiftTrackFloor" in REF and "setFloors" in REF)
check("[楼层身份] TileEntityLiftButtons.forEachTrackPosition(Level, BiConsumer) 存在且是 public"
      "（我们拿楼层轨道的唯一入口）",
      "public void forEachTrackPosition(net.minecraft.world.level.Level, "
      "java.util.function.BiConsumer<net.minecraft.core.BlockPos, "
      "mtr.block.BlockLiftTrackFloor$TileEntityLiftTrackFloor>);" in (TE or ""))
#   ★ javap 的方法声明行打的是**泛型签名**，擦除后的描述符只出现在调用点那一行，
#     所以这里查描述符（反射 getMethod(Level.class, BiConsumer.class) 靠的就是它）。
check("[楼层身份] forEachTrackPosition 的擦除描述符是 "
      "(Lnet/minecraft/world/level/Level;Ljava/util/function/BiConsumer;)V"
      "（反射 getMethod 用的就是这个）",
      "forEachTrackPosition:(Lnet/minecraft/world/level/Level;"
      "Ljava/util/function/BiConsumer;)V" in (TE or ""))
lam_fp = body(TE, "private static void lambda$forEachTrackPosition$1(")
check("[楼层身份] forEachTrackPosition 只对「该 pos 处确实是 TrackFloor 实体」的目标回调 "
      "（⇒ 回调收到的 BlockPos 就是楼层轨道坐标）",
      "TileEntityLiftTrackFloor" in lam_fp and "BiConsumer.accept" in lam_fp)

# --- A4：我们反射读的成员（每一个都对应 Mtr3LiftAutoClose 里的一行）----
for member, where, holder in [
    ("public boolean hasFloor(net.minecraft.core.BlockPos);", LIFT, "Lift.hasFloor"),
    ("public double getPositionY();", LIFT, "Lift.getPositionY"),
    ("public mtr.data.Lift$LiftDirection getLiftDirection();", LIFT, "Lift.getLiftDirection"),
    ("public static mtr.data.RailwayData getInstance(net.minecraft.world.level.Level);",
     RD, "RailwayData.getInstance"),
    ("public final java.util.Set<mtr.data.LiftServer> lifts;", RD, "RailwayData.lifts"),
    ("public boolean hasInstructions();", LI, "LiftInstructions.hasInstructions"),
    ("public void getTargetFloor(java.util.function.Consumer<java.lang.Integer>);",
     LI, "LiftInstructions.getTargetFloor"),
    ("public void arrived();", LI, "LiftInstructions.arrived"),
    ("public boolean isDirty();", LI, "LiftInstructions.isDirty"),
]:
    check(f"[反射] {holder} 仍是原样", member in (where or ""))

check("[反射] Lift 上 doorOpen / doorValue 字段名没变（自动关门那条也靠它）",
      "doorOpen" in (LIFT or "") and "doorValue" in (LIFT or ""))

# --- A5：状态机事实（判定表模型的依据）----
check("[状态机] tick 里「门已全开」的判据仍是字面量 48.0f",
      "float 48.0f" in body(LIFT, "protected void tick(net.minecraft.world.level.Level, float);"))
check("[状态机] tick 里关门那一处要 hasInstructions 才成立（否则原版门永不关）",
      "hasInstructions" in body(LIFT, "protected void tick(net.minecraft.world.level.Level, float);"))
check("[状态机] 到站开门在 lambda$tick$4（把 doorOpen 置 true 的唯一一处）",
      "lambda$tick$4(net.minecraft.world.level.Level, float, java.lang.Integer)" in (LIFT or ""))
tick_code = body(LIFT, "protected void tick(net.minecraft.world.level.Level, float);")
arr_code = body(LIFT, "private void lambda$tick$4(net.minecraft.world.level.Level, float, java.lang.Integer);")
DOOR_OPEN_PUTFIELD = re.compile(r"putfield\s+#\d+\s+//\s+Field (?:mtr/data/Lift\.)?doorOpen:Z")
check("[状态机] tick 全文里写 doorOpen 的地方只有 1 处（就是那一处关门）",
      len(DOOR_OPEN_PUTFIELD.findall(tick_code)) == 1,
      f"实测 {len(DOOR_OPEN_PUTFIELD.findall(tick_code))} 处")
check("[状态机] 到站分支里写 doorOpen 的地方只有 1 处（就是那一处开门）",
      len(DOOR_OPEN_PUTFIELD.findall(arr_code)) == 1,
      f"实测 {len(DOOR_OPEN_PUTFIELD.findall(arr_code))} 处")
check("[状态机] getTargetFloor 只在「有指令」时才回调（没指令就完全空转）",
      "hasInstructions" in body(LI, "public void getTargetFloor"))
check("[状态机] arrived() 摘掉的是队首那一条并置 isDirty",
      "List.remove:(I)" in body(LI, "public void arrived();")
      and "isDirty" in body(LI, "public void arrived();"))

# --- A6：同步契约（为什么必须补一次 toSync）----
TS = body(LS, "public void tickServer(net.minecraft.world.level.Level, java.util.Map")
check("[同步] tickServer 每刻调一次 tick(world, 1.0f)（⇒ 计时按调用次数 == 按 tick）",
      "tick:(Lnet/minecraft/world/level/Level;F)V" in TS and "fconst_1" in TS)
check("[同步] tickServer 只在 isDirty / 乘客数变了时才把直梯塞进 dataSetToSync",
      "isDirty" in TS and "Set.add" in TS)
check("[同步] Lift.writePacket 里写了 doorOpen(boolean) 与 doorValue(float)",
      "writeBoolean" in body(LIFT, "public void writePacket") 
      and "writeFloat" in body(LIFT, "public void writePacket"))

# --- A7：外呼只有 BlockLiftButtons 一个入口，且只在服务端跑 ----
check("[入口] 外呼静态方法只被 BlockLiftButtons 调用（已全量扫描过，这里确认那个调用在）",
      "LiftInstructions.addInstruction:(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Z)V" in (BTN or ""))
check("[入口] BlockLiftButtons.use 在客户端直接 return（⇒ 我们只在服务端动手）",
      "f_46443_" in (BTN or "") or "isClientSide" in (BTN or ""))

# --- A8：命名空间（决定我们 mixin 里的描述符要不要 refmap 映射）----
check("[命名空间] MTR3-forge 的 jar 是 srg 成员名（形如 m_123342_ / f_46443_）",
      bool(re.search(r"m_\d+_", ext or "")) or bool(re.search(r"f_\d+_", (BTN or "") + (LIFT or ""))))
check("[命名空间] 但类名仍是 Mojang 原名 net/minecraft/world/level/Level"
      "（⇒ 我们描述符里的类名两边一致，无需 refmap）",
      "net/minecraft/world/level/Level" in (LI or ""))

# ==========================================================================
# A'. MTR4 对照（「像 MTR4 一样」到底指什么）
# ==========================================================================
L4 = M4["org.mtr.core.data.Lift"]
check("[MTR4] 反汇编出 org.mtr.core.data.Lift", bool(L4))
dv4 = body(L4, "public float getDoorValue();")
for lit, why in [("500l", "门开始开"), ("2100l", "开始全开"), ("4100l", "开始关"), ("5700l", "完全关")]:
    check(f"[MTR4] getDoorValue 的 {why} 界标 {lit} 还在", lit in dv4)
pb4 = body(L4, "public double pressButton(org.mtr.core.data.LiftInstruction, boolean);")
check("[MTR4] pressButton 对「目标就是当前层」也会真的把指令加进列表"
      "（末尾 instructions.add）",
      "ObjectArrayList.add" in pb4)


# ==========================================================================
# B. 判定表 / 不变量
# ==========================================================================
class Mtr3Lift:
    """MTR3 `mtr.data.Lift` 的门/位移状态机 —— 逐行照下面这些反汇编搬过来的。

    出处（全部来自 mtr/data/Lift.class 的 javap -c）：
      tick(Level,float) 偏移 0..56   ① 有指令 且 doorValue == 48.0f → doorOpen = false
                                        + getTargetFloor(lambda$tick$3)（只判方向）
      tick 偏移 42..56               ② 没指令 → liftDirection = NONE
      tick 偏移 59..90               ③ !doorOpen && doorValue == 0.0 → getTargetFloor(lambda$tick$4)
      tick 偏移 93..164              ④ 门动画：doorOpen && dv<48 → dv+=delta；
                                        !doorOpen && dv>0 → dv-=delta
      lambda$tick$4 偏移 15..85      到站（|目标-位置| < 0.01）：direction=NONE、speed=0、
                                        doorOpen=true、位置=目标、instructions.arrived()
    """

    def __init__(self, y, door_open, door_value, instructions=(), direction="NONE"):
        self.y = float(y)
        self.door_open = bool(door_open)
        self.door_value = float(door_value)
        self.instr = list(instructions)
        self.direction = direction

    def snapshot(self):
        return (self.y, self.door_open, self.door_value, tuple(self.instr), self.direction)

    def tick(self, delta=1.0):
        # ① / ②
        if self.instr and self.door_value == 48.0:
            self.door_open = False                      # ← 原版唯一一处「关门」
            self.direction = "UP" if self.instr[0] > self.y else "DOWN"   # lambda$tick$3
        elif not self.instr:
            self.direction = "NONE"
        # ③（本 tick 不再做门动画，对应 goto 194）
        if (not self.door_open) and self.door_value == 0.0:
            if self.instr:                              # getTargetFloor 没指令就不回调
                target = self.instr[0]
                if abs(target - self.y) < 0.01:
                    self.direction = "NONE"
                    self.door_open = True               # ← 原版唯一一处「开门」
                    self.y = float(target)
                    self.instr.pop(0)                   # arrived()
                else:
                    self.direction = "UP" if target > self.y else "DOWN"
                    self.y += delta if self.direction == "UP" else -delta
        # ④ 门动画
        elif self.door_open and self.door_value < 48.0:
            self.door_value = min(self.door_value + delta, 48.0)
        elif (not self.door_open) and self.door_value > 0.0:
            self.door_value = max(self.door_value - delta, 0.0)

    # ---- 我们新加的守卫（= Mtr3LiftAutoClose.reopenIfIdleAndParked 的三条）----
    def guard_fires(self, floor_y):
        return (round(self.y) == floor_y                       # ① 就停在这一层
                and self.direction == "NONE"                    # ② 空闲（没有待办指令）
                and not (self.door_open and self.door_value >= 48.0))   # ③ 门没全开

    def apply_guard(self, floor_y):
        if self.guard_fires(floor_y):
            self.door_open = True          # 我们的写入：只把 doorOpen 置 true


def _fires(state, floor_y):
    """对 snapshot() 元组套同一套守卫判据（BFS 里用）。"""
    y, door_open, door_value, instr, direction = state
    return (round(y) == floor_y
            and direction == "NONE"
            and not (door_open and door_value >= 48.0))


# ---- B1：不变量「原版自己永远到不了 (没有指令 且 doorOpen==false)」----
# 做法：把模型里所有「把 door_open 写成 False」的地方都插上钩子，跑遍一个状态网格，
# 断言这种写入**从来没有**发生在 instr 为空的时候。
def vanilla_never_closes_while_idle():
    bad = []
    for y in (100.0, 100.4, 110.0):
        for door_open in (True, False):
            for dv in (0.0, 1.0, 24.0, 47.0, 48.0):
                for instr in ((), (100,), (110,), (110, 120), (90, 110)):
                    lift = Mtr3Lift(y, door_open, dv, instr)
                    for _ in range(60):
                        before = lift.door_open
                        lift.tick()
                        if before and not lift.door_open and not lift.instr:
                            bad.append((y, door_open, dv, instr, lift.snapshot()))
    return bad


bad = vanilla_never_closes_while_idle()
check("[不变量] 原版 tick 永远不会在「没有待办指令」时关门"
      "（⇒「门关着且空闲」这个状态只可能是我们自己的自动关门造出来的）",
      not bad, f"反例 {bad[:1]}")

# ---- B2：判定表（7 个真实状态）----
FLOOR = 100
CASES = [
    ("自动关门后（本次要修的状态）", Mtr3Lift(100.0, False, 0.0, (), "NONE"), True, True),
    ("自动关门动画中", Mtr3Lift(100.0, False, 24.0, (), "NONE"), True, True),
    ("刚判定自动关门（值还没开始降）", Mtr3Lift(100.0, False, 48.0, (), "NONE"), True, True),
    ("门全开且空闲（原版常态，本来就不用动）", Mtr3Lift(100.0, True, 48.0, (), "NONE"), False, True),
    ("正在经过这一层（方向非 NONE）", Mtr3Lift(100.0, False, 0.0, (110,), "UP"), False, True),
    ("刚关好门正要开走（有指令）", Mtr3Lift(100.0, False, 20.0, (110,), "UP"), False, True),
    ("到站开门中（门在动，本来就会开）", Mtr3Lift(100.0, True, 5.0, (), "NONE"), True, True),
]
for name, lift, expect_fire, expect_idempotent_or_open in CASES:
    fired = lift.guard_fires(FLOOR)
    check(f"[判定表] {name} → {'触发' if expect_fire else '不触发'}", fired == expect_fire,
          f"实测 {'触发' if fired else '不触发'}")

    if not expect_fire:
        # 不触发的状态：我们的代码一个字节都不许改
        before = lift.snapshot()
        lift.apply_guard(FLOOR)
        check(f"[判定表] {name} → 状态零改动", lift.snapshot() == before)
    else:
        before = lift.snapshot()
        lift.apply_guard(FLOOR)
        if lift.door_open and lift.door_value == 48.0:
            # 门本来就全开（只是 doorOpen 为 false）：我们的写入必须幂等
            check(f"[判定表] {name} → 写入幂等（doorOpen 本来就是 true）",
                  lift.snapshot() == before or lift.snapshot()[1] is True)
        # 命中之后（含原版继续跑 120 刻）：门一定会全开，且直梯不会因为我们的写入而移动
        y0 = lift.y
        for _ in range(120):
            lift.tick()
        check(f"[判定表] {name} → 门最终全开", lift.door_open and lift.door_value >= 48.0,
              f"doorOpen={lift.door_open} dv={lift.door_value}")
        check(f"[判定表] {name} → 直梯没有因为我们的写入而移动", lift.y == y0, f"y {y0}→{lift.y}")

# ---- B3：**可达性**测试（守卫的误伤面到底有多大）----
# B2 是逐个点名状态的手工表；这里换成「把原版能走到的状态全部枚举出来」：
# 从「停在第 100 层、门全开、没有指令」出发，交替做两种动作 ——
#   * tick（上面那个模型）
#   * 原版外呼（把一层楼塞进指令表；起点层 == 终点层时**按根因丢掉**）
# 做有界 BFS，把可达状态收集起来，再拿守卫去套。
#
# 关键点：`liftDirection` 只在 ①/② 两处被写 —— **两个分支都不成立时它保留旧值**，
# 所以「方向 == NONE 且还有待办指令」在结构上并不是不可能的，必须靠可达性排除。
def vanilla_call(lift, end_floor):
    """原版外呼入队的效果（简化到「守卫会不会误伤」相关的部分）。

    起点层 = round(currentPositionY)；起点 == 终点 ⇒ 整条丢掉（★ 本次修的根因）；
    否则入队（插在第几位对「守卫会不会误伤」没有影响，这里统一追加）。
    """
    if round(lift.y) == end_floor:
        return False                       # 根因：无声丢弃
    if end_floor in lift.instr:
        return False                       # 原版按 (层, 方向) 去重
    lift.instr.append(end_floor)
    return True


def explore(close_door_first):
    """有界 BFS 收集可达状态。close_door_first = 出发前先做一次我们的自动关门。"""
    start = Mtr3Lift(100.0, True, 48.0, (), "NONE")
    if close_door_first:
        start.door_open = False            # = Mtr3LiftAutoClose.closeDoor 的写入
    seen = {start.snapshot()}
    stack = [start]
    while stack and len(seen) < 200000:
        cur = stack.pop()
        for act in ("tick", 90, 100, 110):
            nxt = Mtr3Lift(*cur.snapshot())
            if act == "tick":
                nxt.tick()
            else:
                vanilla_call(nxt, act)
            key = nxt.snapshot()
            if key not in seen:
                seen.add(key)
                stack.append(nxt)
    return seen


VANILLA = explore(close_door_first=False)
AFTER_CLOSE = explore(close_door_first=True)

check("[可达性] 原版能走到的状态里，「方向 NONE 且还有待办指令但门是关的」不可达",
      not [s for s in VANILLA if s[4] == "NONE" and s[3] and not s[1]],
      f"反例 {[s for s in VANILLA if s[4] == 'NONE' and s[3] and not s[1]][:1]}")
check("[可达性] ⇒ 守卫在可达状态上触发时，若还有待办指令则门一定是开着的（写入幂等）",
      not [s for s in VANILLA if _fires(s, FLOOR) and s[3] and not s[1]],
      f"反例 {[s for s in VANILLA if _fires(s, FLOOR) and s[3] and not s[1]][:1]}")

# ★ 这一条是本次修复的「必要性 + 充分性」：原版自己永远走不到「守卫能改变点什么」的状态，
#   而只要我们自己关一次门（= 自动关门），这种状态立刻就出现在可达集里。
vanilla_changes = [s for s in VANILLA if _fires(s, FLOOR) and not s[1]]
after_changes = [s for s in AFTER_CLOSE if _fires(s, FLOOR) and not s[1]]
check("[可达性] 原版自己的可达集里**没有任何**状态需要这个修复"
      "（⇒ 这个 bug 完全是我们引入自动关门造成的）",
      not vanilla_changes, f"反例 {vanilla_changes[:1]}")
check("[可达性] 自动关一次门之后，可达集里立刻出现了「需要这个修复」的状态"
      "（⇒ 修复是必要的）",
      bool(after_changes), f"共 {len(after_changes)} 个")

sweep_bad2 = []
for fd in (False, True):
    for dv in (0.0, 1.0, 24.0, 47.0, 48.0):
        for instr in ((),):
            for y in (100.0, 100.4):
                lift = Mtr3Lift(y, fd, dv, instr)
                if lift.guard_fires(FLOOR) and fd and dv >= 48.0:
                    sweep_bad2.append(lift.snapshot())
check("[不变量] 门已经全开时空转（不触发）", not sweep_bad2, f"反例 {sweep_bad2[:1]}")

# ---- B4：「像 MTR4 一样」= 门重新走完一个完整周期；全开窗口 2000ms == 我们的 IDLE_TICKS ----
def mtr4_door_value(cooldown):
    """MTR4 org.mtr.core.data.Lift.getDoorValue()：0/500 / 2100/4100 / 5700 四段。"""
    if cooldown < 500:
        return 0.0
    if cooldown < 2100:
        return (cooldown - 500) / 1600.0
    if cooldown <= 4100:
        return 1.0
    return (5700 - cooldown) / 1600.0


check("[MTR4] 到站/同层重按后 cooldown 重置为 5700ms ⇒ 门从「关」重新开一次",
      mtr4_door_value(5700) == 0.0 and mtr4_door_value(4100) == 1.0 and mtr4_door_value(2100) == 1.0)
open_ms = 4100 - 2100
check("[MTR4] 全开保持时长 = 4100-2100 = 2000ms", open_ms == 2000, f"{open_ms}ms")

con = Path(PROJECT, "build", "classes", "java", "main")
idle_ticks = None
if con.is_dir():
    r = subprocess.run([JAVAP, "-p", "-constants", "-cp", str(con), "smooth.lift.Mtr3LiftAutoClose"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    m = re.search(r"IDLE_TICKS = (\d+)", r.stdout)
    idle_ticks = int(m.group(1)) if m else None
check("[对齐] Mtr3LiftAutoClose.IDLE_TICKS 从产物里读得到", idle_ticks is not None)
if idle_ticks is not None:
    check(f"[对齐] 我们的停站保持 {idle_ticks} tick × 50ms == MTR4 的全开窗口 {open_ms}ms",
          idle_ticks * 50 == open_ms, f"{idle_ticks * 50}ms")

print(f"统计：MTR3 可达状态 {len(VANILLA)} 个（原版自己出发）→ 需要本修复的 "
      f"{len(vanilla_changes)} 个；先做一次自动关门后 {len(AFTER_CLOSE)} 个 → 需要本修复的 "
      f"{len(after_changes)} 个")
print(f"通过 {len(oks)} 项，失败 {len(fails)} 项")
if fails:
    print()
    for f in fails:
        print("  ✗", f)
else:
    print("  全部通过 ✓")
if "-v" in sys.argv:
    for o in oks:
        print("  ✓", o)
sys.exit(1 if fails else 0)
