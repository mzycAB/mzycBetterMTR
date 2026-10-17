package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * MTR 3 直梯「门」相关的两处修复的反射层：
 * <ul>
 *   <li>【1.42】{@link #IDLE_TICKS 空闲自动关门} —— MTR3 原版停在某层的门**永远不关**；</li>
 *   <li>【1.45】{@link #onExternalCall 同一层外呼} / {@link #onPanelCall 轿厢内按本层} ——
 *       原版会把「按的层 == 直梯现在停的层」的请求**无声丢掉**。</li>
 * </ul>
 * 两者都由同一个 mixin 挂载（{@code smooth.lift.mixin.mtr.Mtr3LiftDoorMixin} 等），
 * 也共用同一份字段绑定，所以放在一个类里。
 *
 * <h2>【1.42】为什么需要自动关门（根因）</h2>
 * MTR 3.2.2 的 {@code mtr.data.Lift.tick(World, float)} 字节码（javap 反汇编）是这样的：
 *
 * <pre>
 *   // ① 有指令 且 门已全开 → 关门，并设定运行方向
 *   if (liftInstructions.hasInstructions() &amp;&amp; doorValue == 48.0f) {   // ldc_w 48.0f
 *       doorOpen = false;
 *       liftInstructions.getTargetFloor(this::lambda$tick$3);
 *   } else if (!liftInstructions.hasInstructions()) {
 *       liftDirection = NONE;
 *   }
 *   // ② 门已关到底 且 有指令 → 运行（到站时 doorOpen = true、liftInstructions.arrived()）
 *   if (!doorOpen &amp;&amp; doorValue == 0.0f) { liftInstructions.getTargetFloor(lambda$tick$4); }
 *   else if (...) { doorValue += / -= delta; }          // 开/关门动画
 *   frontCanOpen = checkDoor(world, true);
 * </pre>
 *
 * <p>注意 ① 的**附加条件**：{@code hasInstructions()}。也就是说 —— 电梯停在某层、门全开
 * （{@code doorOpen=true}、{@code doorValue=48}）、**没有任何待执行指令**（刚到的这一层
 * 已经被 {@code arrived()} 从指令表里摘掉了）时，① 不成立、② 也不成立，
 * 于是 {@code doorOpen} 永远保持 true、{@code doorValue} 永远停在 48 ——
 * <b>这就是「直梯所在那一层的门一直不关」的根因</b>（不是同步问题、不是渲染问题）。
 *
 * <p>MTR 4 没有这个毛病：它的 {@code getDoorValue()} 由 {@code stoppingCoolDown} 直接算出来 ——
 * 全开只保持 {@code DOOR_OPEN_TIME = 2000ms}（{@code 2100 → 4100} 那一段），
 * 到点自动转成关门（{@code (5700 - cooldown) / 1600}）。所以「像 MTR4 那样自动关门」
 * = 给 MTR3 补一个**停站保持计时**，到点把 {@code doorOpen} 置回 false，让 MTR3 自己的
 * 关门动画（② 里那条 {@code doorValue -= delta}）把门关上。
 *
 * <h3>为什么 D 值取 40 tick</h3>
 * MTR4 的全开保持时间就是 {@code DOOR_OPEN_TIME = 2000ms}，而 {@code tickServer} 每服务端刻
 * 调一次 {@code tick(world, 1.0f)}（{@code LiftServer.tickServer} 字节码里 {@code fconst_1}），
 * 40 tick = 2000ms，与 MTR4 逐毫秒对齐。
 *
 * <h3>为什么 {@code DOOR_FULL} 是 48 而不是 {@code Lift.DOOR_MAX}</h3>
 * {@code Lift.DOOR_MAX = 24}（ConstantValue 实测），但那是**物理门**的满开值
 * （{@code checkDoor} 里 {@code setOpen(min(round(doorValue), 24))}）；
 * {@code tick} 里「门已全开」用的判据是**字面量 48.0f**（{@code ldc_w #535}），
 * 也就是 24 的两倍。照 DOOR_MAX 写会永远等不到「全开」，计时器永远数不起来。
 *
 * <h2>【1.45】同一层按外呼 / 按本层为什么没反应（根因）</h2>
 *
 * <p>MTR3 里「按某个楼层」最终都汇到 {@code mtr.data.LiftInstructions} 的私有核心
 * {@code int addInstruction(int startFloor, boolean isUp, int endFloor, boolean a, boolean b, boolean c)}
 * （{@code c} = 「真的插进去」，{@code false} 时是**只算代价的试算**）。
 * 它**第一件事**就是（javap 反汇编 {@code mtr/data/LiftInstructions.class}）：
 *
 * <pre>
 *   0: iload_1      // startFloor
 *   1: iload_3      // endFloor
 *   2: if_icmpne 7
 *   5: iconst_0
 *   6: ireturn      // ← 起点层 == 终点层 ⇒ 直接返回 0：不插指令、不改状态、毫无反馈
 * </pre>
 *
 * <p>而两条入口喂进去的 {@code startFloor} 都是「这条直梯**当前**所在的层」：
 * <ul>
 *   <li><b>外呼</b> {@code static addInstruction(Level, BlockPos, boolean)}：
 *       {@code startFloor = round(lift.getPositionY())}、{@code endFloor = blockPos.getY()}（按钮方块那一格）；</li>
 *   <li><b>轿厢内面板</b> {@code Lift.pressButton(int floor)}：
 *       {@code startFloor = isUp ? floor(currentPositionY) : ceil(currentPositionY)}、{@code endFloor = floor}。</li>
 * </ul>
 * ⇒ <b>只要按的层正好是直梯现在停的层，整条请求就被这一行丢掉</b>。
 *
 * <p><b>MTR3 原版为什么看不出这个毛病</b>：因为原版「停在某层的门永远不关」（= 上面 ① 的根因），
 * 「同一层 + 门是关的」这个状态**根本到不了**（用户按按钮时门本来就开着，丢不丢指令看不出来）。
 * ⇒ <b>是 ① 这个修复把状态造出来了，所以这个坑必须由我们一起填。</b>
 *
 * <p><b>MTR4 没有这个毛病</b>：{@code org.mtr.core.data.Lift.pressButton(LiftInstruction, boolean)}
 * 对「目标就是当前层」的请求会正常走到末尾的 {@code instructions.add(instr)}
 * （字节码 316 段），于是 {@code tick} 里「目标 == 当前位置」⇒ 到站分支
 * ⇒ {@code stoppingCoolDown = 5700} ⇒ {@code getDoorValue()} 让门**重新走完一个完整周期**
 * （5700→4100 开、4100→2100 全开 2000ms、2100→500 关）。
 * <b>本修复就是让 MTR3 的这两条入口也表现出「门重新开一次」。</b>
 *
 * <h3>怎么补：只「重开门」，绝不碰 MTR 的指令表</h3>
 * 两个候选：（A）替 MTR3 把丢掉的那条指令插回 {@code LiftInstructions.instructions}；
 * （B）直接把 {@code doorOpen} 置回 true，让 MTR3 自己的开门动画（tick 里
 * {@code doorValue += delta}）把门推开。**选 B**：
 * <ol>
 *   <li>（A）要动 MTR 的私有列表 + 包级私有的 {@code LiftInstruction} 构造器，改的是
 *       **MTR 的调度状态**；插入位置/方向算错的话症状是「直梯乱跑」，比「门不开」难查得多；</li>
 *   <li>{@code doorOpen} 本来就是 MTR 自己表达「这次停站门该开着」的那个量
 *       （到站分支 {@code lambda$tick$4} 开门就是 {@code putfield doorOpen true}），
 *       置它 = 复述 MTR 自己的到站动作，语义最贴；</li>
 *   <li>（B）不需要知道指令表的内部结构，MTR 以后改指令表也不用跟着改。</li>
 * </ol>
 *
 * <h3>★ 触发条件必须**同时**满足三条（少一条都会误伤）</h3>
 * <pre>
 *   ① 直梯就停在这一层：Math.round(lift.getPositionY()) == 按钮那层的 y
 *      （外呼还要 &amp;&amp; lift.hasFloor(blockPos) —— 这正是原版挑直梯用的那个判据）
 *   ② 这台直梯是**空闲**的：getLiftDirection() == NONE
 *   ③ 它的门**没有全开**：!(doorOpen &amp;&amp; doorValue &gt;= 48)
 * </pre>
 *
 * <p>逐条说为什么不能少：
 * <ul>
 *   <li><b>①</b>：{@code round(currentPositionY) == 层 y} 就是原版那个 early return 的判据本身
 *       —— 我们只接管「原版会丢掉」的那一种输入。{@code hasFloor(pos)} 是原版筛直梯用的那一个
 *       （{@code lifts.stream().filter(lift -&gt; lift.hasFloor(pos)).findFirst()}）；
 *       少了它会把**同一高度、但属于别的井道**的直梯门一起打开。</li>
 *   <li><b>②</b>：{@code liftDirection} 每 tick 由 {@code tick} 从待办指令重算
 *       （没指令 ⇒ NONE；有目标 ⇒ UP/DOWN；到站 ⇒ NONE；只有这三处会写它）。少了它会误伤两种状态：
 *       　(a) 直梯**正在经过**这一层（连续位移里恰好 {@code round(位置) == 层 y}）⇒ 会在半空把门推开；
 *       　(b) 直梯刚关好门**正要开走**（① 号机制：有指令 且 doorValue==48 ⇒ doorOpen=false，
 *       　　 同时 getTargetFloor 把方向置成 UP/DOWN）⇒ 门会「关一半又开、开完又关」弹一下然后照走，
 *       　　 用户看到「按了有用但电梯还是走了」，比没反应更迷惑。</li>
 *   <li><b>③</b>：门已经全开当然不用动；同时它把「刚被 ① 关掉、{@code doorValue} 还没开始降」
 *       那一瞬（{@code 48 &amp;&amp; !doorOpen}）**算进要重开的情形** —— 那一刻门在视觉上还是全开的，
 *       {@code doorValue} 要再走 24 刻（1.2 秒）才看得见开始关（见
 *       {@code MtrLiftAccess} 里「[24,48] 是空档」那段），用户这时按按钮当然希望它别关。</li>
 * </ul>
 *
 * <p><b>反过来，「原版已经处理好」的状态天然全被排除</b>：门全开且空闲（原版常态）被 ③ 排除；
 * 别的层被 ① 排除；在走 / 正要走被 ② 排除。⇒ 能触发本修复的，
 * <b>只可能是 ① 号修复自己造出来的「停在这一层、门已关、没有指令」这一个状态</b>。
 *
 * <h3>★ 为什么还要「补一次同步」（{@link #consumePendingSync}）</h3>
 *
 * <p>我们是**在 tick 之外**（玩家点方块的交互处理里 / 收包处理里）改的 {@code doorOpen}。
 * 而 MTR3 只在 {@code LiftServer.tickServer} 里、且**只在**
 * {@code liftInstructions.isDirty() || 乘客数变了} 时才把这条直梯塞进 {@code dataSetToSync}
 * 发给客户端；{@code doorOpen}/{@code doorValue} 又正好都在 {@code Lift.writePacket} 的包里
 * （{@code writeBoolean} / {@code writeFloat} 各一处）。
 * ⇒ 不补同步的话，<b>服务端门开了、客户端那边门还关着</b>。
 *
 * <p>这跟 ① 号修复踩过的是同一个坑（当时是关门方向），所以这里复用同一套做法：
 * 先记在小本本上（{@link #needsSyncNow}），由 {@code Mtr3LiftDoorMixin} 在下一次
 * {@code tickServer} 里加进 {@code toSync} 并把停站计时清零（这样门会有完整的
 * {@link #IDLE_TICKS} 刻全开时间，跟 MTR4 的 2000ms 对齐）。
 *
 * <h2>★ 为什么这个类【不在】{@code smooth.lift.mixin.mtr} 包里（Forge 1.20.1 移植时改的）</h2>
 *
 * <p>Fabric 1.20.4 参考源把它放在 {@code smooth.lift.mixin.mtr}（= mixin 配置的
 * {@code "package"}）。那是一个**虚拟命名空间**：Mixin 的类加载守卫（{@code MixinProcessor.applyMixins}）
 * 对「名字以某个 mixin 包开头」的类一律判定
 * {@code packageMatch == true}，然后 —— 只要它不是 {@code InjectionPoint} /
 * {@code ITargetSelectorDynamic} 的子类 —— 直接抛
 * {@code IllegalClassLoadError: … is in a defined mixin package … and cannot be referenced directly}。
 *
 * <p>三类名字各自的结果（已对着 mixin-0.8.5 字节码核过）：
 * <ul>
 *   <li>{@code Mtr3LiftDoorMixin} / {@code Mtr4LiftTrackFloorShapeMixin} / 本类的两个 mixin 兄弟：**是 mixin**，
 *       Mixin 直接读它们的 {@code .class} 资源做字节码合并，从不经类加载器 ⇒ 安全；</li>
 *   <li>{@code Mtr3LiftMixinPlugin}：Mixin 用**自己的**类加载器实例化配置插件 ⇒ 安全
 *       （所以「插件实现类」是允许留在 mixin 包里的）；</li>
 *   <li>本类：普通工具类，被合并进 {@code mtr.data.LiftServer} / {@code mtr.data.LiftInstructions} /
 *       {@code mtr.data.Lift} 的 {@code invokestatic} 引用时会由**游戏类加载器正常解析** ⇒ 命中守卫 ⇒ 抛错。</li>
 * </ul>
 *
 * <p>这个错误最要命的地方是**只在装了 MTR3 时才出现**（目标类 {@code mtr.data.LiftServer} 是 MTR3 独有，
 * MTR4 环境下那条 mixin 被门禁跳过、本类永远不会被加载）。而且它抛在服务端 tick 里 ⇒ tick 线程死
 * ⇒ 玩家表现是「破坏方块后又被推回」，不是崩溃报告。⇒ 本类**必须**待在 mixin 包之外。
 */
public final class Mtr3LiftAutoClose {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 停站保持的 tick 数（MTR4 的 DOOR_OPEN_TIME = 2000ms）。 */
    public static final int IDLE_TICKS = 40;

    /** MTR3 里「门已全开」的判据值 = {@code mtr.data.Lift.tick} 里的 48.0f（**不是** DOOR_MAX）。 */
    public static final float DOOR_FULL = 48.0f;

    /** MTR3 的运动方向枚举里表示「空闲」的那一个常量名。 */
    private static final String DIRECTION_NONE = "NONE";

    private static boolean bound;
    private static boolean available;

    private static Field doorOpenField;
    private static Field doorValueField;
    private static Field idField;

    /** 【1.45】「同一层按按钮」修复所需的成员；拿不到时只有这个修复停用，不影响自动关门。 */
    private static boolean callFixBound;
    private static boolean callFixUsable;

    /** 【1.45】{@code mtr.data.RailwayData.getInstance(Level)}。 */
    private static Method railwayGetInstance;
    /** 【1.45】{@code mtr.data.RailwayData.lifts}（{@code public final Set<LiftServer>}）。 */
    private static Field railwayLifts;
    /** 【1.45】{@code mtr.data.Lift.hasFloor(BlockPos)} —— 原版挑直梯用的那个判据。 */
    private static Method liftHasFloor;
    /** 【1.45】{@code mtr.data.Lift.getPositionY()}。 */
    private static Method liftGetPositionY;
    /** 【1.45】{@code mtr.data.Lift.getLiftDirection()}。 */
    private static Method liftGetDirection;

    /**
     * 【1.45】按钮方块实体上的 {@code forEachTrackPosition(Level, BiConsumer)} —— 唯一能拿到
     * 「这个按钮服务哪些**楼层轨道**」的入口。**按类缓存**（活实例的 {@code getClass()} 上取，
     * 不写死 {@code mtr.block.BlockLiftButtons$TileEntityLiftButtons} 这个内部类名）。
     */
    private static Class<?> buttonsEntityClass;
    private static Method forEachTrackPosition;

    /** 【1.45】外呼诊断：只在外呼路径上打一次，把「联动了几层 / 匹配到几台直梯 / 首台的状态」写清楚。 */
    private static boolean callDiagLogged;
    private static int callFloorsSeen;
    private static int callCandidatesSeen;
    private static String callFirstCandidate = "";

    /**
     * 【1.45】「门被我们从 tick 之外打开、还没同步给客户端」的直梯。
     *
     * <p><b>用 identity 集合</b>：MTR 的 {@code DataBase} 有基于 id 的 {@code equals}，
     * 用普通 HashSet 会在「两台直梯 id 相同」这种理论上不该发生的情况里串味；
     * 我们这里要的本来就是「**这一个对象**」。
     *
     * <p>条目只在「真的把门打开了」时加入，并在下一次 {@code tickServer} 就被取走
     * （见 {@link #consumePendingSync}）。直梯在那一瞬间被拆掉的话会留一条 ——
     * 所以 {@link #reopenIfIdleAndParked} 里有个「超过 32 条就清空」的兜底，
     * 保证它不可能无限长。
     */
    private static final Set<Object> needsSyncNow =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** 本次会话累计自动关了几次；只用来在第一次和每次整百次时打一条日志。 */
    private static int closedCount;

    /** 【1.45】本次会话累计因为「同一层按按钮」重开了几次门；同样只用来打日志。 */
    private static int reopenedCount;

    private Mtr3LiftAutoClose() {
    }

    /** 惰性绑定一次；调用方每次都要先问 {@link #available()}。 */
    private static synchronized void bind() {
        if (bound) {
            return;
        }
        bound = true;
        try {
            Class<?> lift = Class.forName("mtr.data.Lift");
            doorOpenField = lift.getDeclaredField("doorOpen");
            doorOpenField.setAccessible(true);
            doorValueField = lift.getDeclaredField("doorValue");
            doorValueField.setAccessible(true);
            try {
                idField = lift.getField("id");
                idField.setAccessible(true);
            } catch (Throwable ignored) {
                // id 只用于日志，拿不到就算了
            }
            available = true;
            LOGGER.info("[SmoothLift/Mtr3Fix] MTR3 直梯自动关门已就绪"
                    + "（停站保持 {} tick = {}ms，与 MTR4 的 DOOR_OPEN_TIME 对齐；全开判据 doorValue >= {}）",
                    IDLE_TICKS, IDLE_TICKS * 50, DOOR_FULL);
        } catch (Throwable t) {
            available = false;
            LOGGER.warn("[SmoothLift/Mtr3Fix] 无法访问 mtr.data.Lift 的字段，本功能停用：{}", t.toString());
        }
        bindCallFix();
    }

    /**
     * 【1.45】绑定「同一层按按钮」修复要用的成员。
     *
     * <p>单独 try / 单独一个可用位：这些成员缺一个只应该让**这一个**修复停用
     * （打一条 WARN 并说清症状），不能把「空闲自动关门」一起弄哑。
     */
    private static void bindCallFix() {
        if (callFixBound) {
            return;
        }
        callFixBound = true;
        try {
            Class<?> railway = Class.forName("mtr.data.RailwayData");
            railwayGetInstance = railway.getMethod("getInstance", Level.class);
            railwayGetInstance.setAccessible(true);
            railwayLifts = railway.getField("lifts");
            railwayLifts.setAccessible(true);

            Class<?> lift = Class.forName("mtr.data.Lift");
            liftHasFloor = lift.getMethod("hasFloor", BlockPos.class);
            liftHasFloor.setAccessible(true);
            liftGetPositionY = lift.getMethod("getPositionY");
            liftGetPositionY.setAccessible(true);
            liftGetDirection = lift.getMethod("getLiftDirection");
            liftGetDirection.setAccessible(true);

            callFixUsable = true;
            LOGGER.info("[SmoothLift/Mtr3Fix] 「同一层外呼 / 轿厢内按本层也能开门」修复已就绪"
                    + "（原版 LiftInstructions 的私有核心在起点层 == 终点层时直接 return 0，整条请求被无声丢弃）");
        } catch (Throwable t) {
            callFixUsable = false;
            LOGGER.warn("[SmoothLift/Mtr3Fix] 无法绑定「同一层按按钮」修复所需的 MTR 成员，该修复停用"
                    + "（症状：直梯自动关门后，在外呼面板按它所在的那一层仍然没反应）：{}", t.toString());
        }
    }

    /** 反射链路是否可用。 */
    public static boolean available() {
        if (!bound) {
            bind();
        }
        return available;
    }

    /**
     * 这条直梯现在是不是「门全开且停着」——也就是**该开始数停站保持时间**的状态。
     *
     * <p>判据只有 {@code doorOpen && doorValue >= 48}：
     * <ul>
     *   <li>门正在动（{@code doorValue} 在 0~48 之间）→ false，计时归零；</li>
     *   <li>门刚被①关掉（{@code doorOpen=false}）→ false，计时归零；</li>
     *   <li>有指令、门全开 → MTR3 自己会在同一次 tick 里把 {@code doorOpen} 置 false，
     *       所以调用方看到的也是 false ⇒ **不会和原版逻辑抢着关门**。</li>
     * </ul>
     */
    public static boolean isDoorFullyOpen(Object lift) {
        try {
            return doorOpenField.getBoolean(lift)
                    && doorValueField.getFloat(lift) >= DOOR_FULL;
        } catch (Throwable t) {
            available = false;
            LOGGER.warn("[SmoothLift/Mtr3Fix] 读取直梯门状态失败，本功能停用：{}", t.toString());
            return false;
        }
    }

    /**
     * 把 {@code doorOpen} 置回 false，让 MTR3 自己的关门动画接手
     * （下一 tick 起 {@code doorValue} 会以 1/tick 递减到 0）。
     */
    public static void closeDoor(Object lift) {
        try {
            doorOpenField.setBoolean(lift, false);
            closedCount++;
            if (closedCount == 1 || closedCount % 100 == 0) {
                LOGGER.info("[SmoothLift/Mtr3Fix] 直梯 #{} 空闲停站超时，已自动关门（累计 {} 次）",
                        idOf(lift), closedCount);
            }
        } catch (Throwable t) {
            available = false;
            LOGGER.warn("[SmoothLift/Mtr3Fix] 写直梯门状态失败，本功能停用：{}", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // 【1.45】同一层外呼 / 轿厢内按本层：把原版丢掉的那次请求接住
    // ------------------------------------------------------------------

    /**
     * <b>外呼入口</b>：{@code mtr.data.LiftInstructions.addInstruction(Level, BlockPos, boolean)}
     * 跑完之后调一次（就在那个静态方法的 TAIL）。
     *
     * <p>这个静态方法是**唯一**的外呼入口（{@code mtr.block.BlockLiftButtons.use} 里那一段
     * {@code LiftInstructions.addInstruction(world, pos, hitY - floor(hitY) > 0.25)}，
     * 上半格 = 上行按钮、下半格 = 下行按钮），只在服务端跑。
     *
     * <p>★★ <b>这里是最容易写错的一处 ——【1.45】第一版就错在这上面，症状正是「按了完全没反应」：
     * 传进来的 {@code pos} 是<b>按钮方块</b>的坐标，而 {@code Lift.floors} 里存的是
     * <b>楼层轨道方块</b>（{@code mtr.block.BlockLiftTrackFloor}）的坐标；
     * {@code Lift.hasFloor(pos)} 的实现就是 {@code floors.contains(pos)} —— <b>精确匹配</b>。
     * 拿按钮的 pos 去问 {@code hasFloor} 恒为 false ⇒ 循环每次都 {@code continue} ⇒
     * 整条修复一声不响地什么都不做（不报错、不打日志、不改状态）。</b></p>
     *
     * <p>正确做法照原版抄：按钮方块实体自己登记了「我服务哪些楼层轨道」
     * （{@code TileEntityLiftButtons.forEachTrackPosition}）。
     * <b>只有它给出的 {@code trackFloorPos} 才能拿去问 {@code hasFloor}、也才能取 {@code getY()}。</b>
     * 原版 {@code lambda$addInstruction$2} 里正是这么算的（{@code thisFloor = trackFloorPos.getY()}）。</p>
     *
     * <p>这里不是「重写原版的挑选算法」，只是**补一遍原版刚丢掉的那种输入**：
     * 用原版自己的 {@code hasFloor(trackFloorPos)} 筛出服务这一层的直梯，
     * 再要求它正好停在这一层、空闲、门没全开（三条判据见 {@link #reopenIfIdleAndParked}）。
     *
     * @param level 服务端世界（原版在方法里也是这么拿 {@code RailwayData} 的）
     * @param pos   按钮方块的位置（原版那个 {@code blockPos}；★ 它**不是**楼层轨道的坐标）
     */
    public static void onExternalCall(Level level, BlockPos pos) {
        if (level == null || pos == null || !callFixReady()) {
            return;
        }
        try {
            // ★ 楼层身份必须从按钮方块实体问，不能拿 pos 自己猜（见上面那段 ★★）
            Object buttons = level.getBlockEntity(pos);
            if (buttons == null) {
                return;
            }
            Method forEach = forEachTrackPositionOf(buttons.getClass());
            if (forEach == null) {
                // 拿不到那个方法时 forEachTrackPositionOf 已经打过 WARN，这里放弃这次外呼
                return;
            }
            Object railway = railwayGetInstance.invoke(null, level);
            if (railway == null) {
                return;
            }
            Object raw = railwayLifts.get(railway);
            if (!(raw instanceof Iterable<?> lifts)) {
                return;
            }
            forEach.invoke(buttons, level,
                    (BiConsumer<Object, Object>) (trackFloorPos, trackFloorEntity) ->
                            reopenLiftsServingFloor(lifts, trackFloorPos));
            logCallDiagnostic(pos);
        } catch (Throwable t) {
            breakCallFix(t);
        }
    }

    /**
     * 某个**楼层轨道坐标**上：把「服务这一层 + 正好停在这一层 + 门没全开」的直梯门重新打开。
     *
     * <p>由 {@link #onExternalCall} 通过按钮方块实体的 {@code forEachTrackPosition} 逐层回调；
     * 每个 {@code trackFloorPos} 都是**楼层轨道方块**的坐标，所以可以直接喂给
     * {@code hasFloor} 与 {@code getY()}（这正是第一版写错的地方）。
     */
    private static void reopenLiftsServingFloor(Iterable<?> lifts, Object trackFloorPos) {
        if (!(trackFloorPos instanceof BlockPos floorPos)) {
            return;
        }
        callFloorsSeen++;
        try {
            for (Object lift : lifts) {
                if (lift == null) {
                    continue;
                }
                // 原版挑直梯的同一个判据（lifts.stream().filter(lift -> lift.hasFloor(trackFloorPos))）——
                // 少了它会把同一高度、但属于别的井道的直梯一起开门。
                if (!Boolean.TRUE.equals(liftHasFloor.invoke(lift, floorPos))) {
                    continue;
                }
                callCandidatesSeen++;
                rememberFirstCandidate(lift, floorPos);
                reopenIfIdleAndParked(lift, floorPos.getY());
            }
        } catch (Throwable t) {
            breakCallFix(t);
        }
    }

    /**
     * 取按钮方块实体上的 {@code forEachTrackPosition(Level, BiConsumer)}。
     *
     * <p>刻意**不用** {@code Class.forName("mtr.block.BlockLiftButtons$TileEntityLiftButtons")}：
     * 直接从活着的那个实例的 {@code getClass()} 上取 —— 既不写死内部类名，也不会在没人按按钮时
     * 把 MTR 的方块实体类提前加载进来（顺便避开「准备期加载类」那一整类事故）。
     * 取到之后按类缓存，之后每次外呼只在缓存里比一下引用。
     *
     * @return 拿不到返回 {@code null}（已打 WARN；唯一后果是「同一层外呼」修复停用，自动关门不受影响）
     */
    private static Method forEachTrackPositionOf(Class<?> buttonsClass) {
        Method cached = forEachTrackPosition;
        if (cached != null && buttonsEntityClass == buttonsClass) {
            return cached;
        }
        try {
            Method m = buttonsClass.getMethod("forEachTrackPosition", Level.class, BiConsumer.class);
            m.setAccessible(true);
            buttonsEntityClass = buttonsClass;
            forEachTrackPosition = m;
            return m;
        } catch (Throwable t) {
            LOGGER.warn("[SmoothLift/Mtr3Fix] 按钮方块实体上没有 forEachTrackPosition(Level, BiConsumer)，"
                            + "「同一层外呼也能开门」修复停用（唯一后果：直梯自动关门后按同一层外呼仍无反应）：{}",
                    t.toString());
            return null;
        }
    }

    /** 记下本次会话第一台候选直梯的状态，供 {@link #logCallDiagnostic} 打一行诊断。 */
    private static void rememberFirstCandidate(Object lift, BlockPos floorPos) {
        if (!callFirstCandidate.isEmpty()) {
            return;
        }
        try {
            callFirstCandidate = "id=" + idOf(lift)
                    + "、轨道层=" + floorPos.getY()
                    + "、round(直梯y)=" + Math.round(((Number) liftGetPositionY.invoke(lift)).doubleValue())
                    + "、方向=" + liftGetDirection.invoke(lift)
                    + "、doorOpen=" + doorOpenField.getBoolean(lift)
                    + "、doorValue=" + doorValueField.getFloat(lift);
        } catch (Throwable ignored) {
            // 诊断字符串本身绝不允许影响功能
        }
    }

    /**
     * 每次游戏只打一条的外呼诊断。
     *
     * <p>加它的理由就是【1.45】第一版那个 bug：守卫写错时**没有任何输出**，玩家只能报
     * 「按了没反应」，而这句话区分不出「没联动到楼层轨道」「没匹配到直梯」
     * 「匹配到了但三条判据没过」这三种完全不同的原因。这一行把三者一次说清。
     */
    private static void logCallDiagnostic(BlockPos pos) {
        if (callDiagLogged) {
            return;
        }
        callDiagLogged = true;
        try {
            LOGGER.info("[SmoothLift/Mtr3Fix] 外呼诊断（每次游戏只打这一条）：按钮 {} 联动到 {} 个楼层轨道，"
                            + "匹配到 {} 台直梯{}",
                    pos, callFloorsSeen, callCandidatesSeen,
                    callFirstCandidate.isEmpty() ? "" : "；首台：" + callFirstCandidate);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * <b>轿厢内面板入口</b>：{@code mtr.data.Lift.pressButton(int floor)} 跑完之后调一次（TAIL）。
     *
     * <p>面板走的是 {@code PacketTrainDataGuiServer.receivePressLiftButtonC2S}
     * → {@code Lift.pressButton(int)}，喂给私有核心的 {@code startFloor} 是
     * {@code floor(currentPositionY)} / {@code ceil(currentPositionY)}，
     * 所以「按的就是自己停的这层」同样会在 early return 那儿被丢掉 —— 这里补上。
     *
     * <p>没有 {@code BlockPos} 可用（面板只传一个 int），所以只按
     * {@code round(currentPositionY) == floor} 判「就是这一层」——
     * 这正是原版那次 early return 用的同一个判据，不会多也不会少。
     *
     * @param lift   目标直梯（就是 {@code pressButton} 的 {@code this}）
     * @param floorY 按下的楼层（MTR3 的面板传的是层的 y 坐标）
     */
    public static void onPanelCall(Object lift, int floorY) {
        if (lift == null || !callFixReady()) {
            return;
        }
        try {
            reopenIfIdleAndParked(lift, floorY);
        } catch (Throwable t) {
            breakCallFix(t);
        }
    }

    /**
     * 三条判据全中就把门重新打开（并把这条直梯记进 {@link #needsSyncNow}）。
     *
     * @return 真的改了门状态返回 true
     */
    private static boolean reopenIfIdleAndParked(Object lift, int floorY) throws Exception {
        if (!available) {
            // 连 doorOpen 都读不到，重开门当然也做不了
            return false;
        }
        // ① 就停在这一层
        if (Math.round(((Number) liftGetPositionY.invoke(lift)).doubleValue()) != floorY) {
            return false;
        }
        // ② 空闲（没有待办指令、也不是正在走）—— 见类注释里「少一条都会误伤」
        Object direction = liftGetDirection.invoke(lift);
        if (direction == null || !DIRECTION_NONE.equals(direction.toString())) {
            return false;
        }
        // ③ 门没全开（门已经全开就没什么可做的）
        if (isDoorFullyOpen(lift)) {
            return false;
        }
        doorOpenField.setBoolean(lift, true);
        if (needsSyncNow.size() > 32) {
            // 兜底：正常永远不会走到（条目在下一次 tickServer 就被取走）
            needsSyncNow.clear();
        }
        needsSyncNow.add(lift);
        reopenedCount++;
        if (reopenedCount == 1 || reopenedCount % 100 == 0) {
            LOGGER.info("[SmoothLift/Mtr3Fix] 直梯 #{} 停在第 {} 层时收到同一层的外呼/本层按键，已重新开门"
                    + "（累计 {} 次）", idOf(lift), floorY, reopenedCount);
        }
        return true;
    }

    /**
     * <b>由 {@code Mtr3LiftDoorMixin} 在 {@code tickServer} 的 TAIL 调用</b>：
     * 这条直梯的门是不是被我们从 tick 之外打开过？是则返回 true 并把它从本本上划掉。
     *
     * <p>调用方拿到 true 之后必须做两件事（见那个 mixin）：
     * <ol>
     *   <li>把这条直梯塞进 {@code dataSetToSync} —— 否则客户端收不到「门开了」；</li>
     *   <li>把停站计时清零 —— 让重开的这次门也有完整的 {@link #IDLE_TICKS} 刻全开时间。</li>
     * </ol>
     */
    public static boolean consumePendingSync(Object lift) {
        return !needsSyncNow.isEmpty() && needsSyncNow.remove(lift);
    }

    private static boolean callFixReady() {
        if (!bound) {
            bind();
        }
        return callFixUsable;
    }

    /** 「同一层按按钮」修复出问题时只停用它自己，并只打一条日志（避免每 tick 刷屏）。 */
    private static void breakCallFix(Throwable t) {
        callFixUsable = false;
        LOGGER.warn("[SmoothLift/Mtr3Fix] 「同一层外呼 / 轿厢内按本层」修复出错，已停用该修复：{}", t.toString());
    }

    private static Object idOf(Object lift) {
        if (idField == null) {
            return "?";
        }
        try {
            return idField.get(lift);
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
