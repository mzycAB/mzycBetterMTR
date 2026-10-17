package smooth.lift;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * 【1.42】MTR 3 直梯「空闲时车门自动关闭」的反射存取层。
 *
 * <h2>为什么需要这个功能（根因）</h2>
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
 * <h2>为什么 D 值取 40 tick</h2>
 * MTR4 的全开保持时间就是 {@code DOOR_OPEN_TIME = 2000ms}，而 {@code tickServer} 每服务端刻
 * 调一次 {@code tick(world, 1.0f)}（{@code LiftServer.tickServer} 字节码里 {@code fconst_1}），
 * 40 tick = 2000ms，与 MTR4 逐毫秒对齐。
 *
 * <h2>为什么 {@code DOOR_FULL} 是 48 而不是 {@code Lift.DOOR_MAX}</h2>
 * {@code Lift.DOOR_MAX = 24}（ConstantValue 实测），但那是**物理门**的满开值
 * （{@code checkDoor} 里 {@code setOpen(min(round(doorValue), 24))}）；
 * {@code tick} 里「门已全开」用的判据是**字面量 48.0f**（{@code ldc_w #535}），
 * 也就是 24 的两倍。照 DOOR_MAX 写会永远等不到「全开」，计时器永远数不起来。
 *
 * <h2>为什么用反射而不是 {@code @Shadow}</h2>
 * {@code doorOpen} / {@code doorValue} 是 {@code mtr.data.Lift} 上的 {@code protected} 字段，
 * 而我们要挂的钩子是子类 {@code mtr.data.LiftServer.tickServer}（只有它带「要同步给哪些玩家」那个
 * Set）。用 {@code @Shadow} 去影子一个**父类**字段虽然 Mixin 支持，但一旦语义有偏差就是
 * 启动期崩溃；而这两个字段每 tick 只读两次、写一次，反射的开销（Field 已缓存）在
 * 「几十条直梯 × 20 tick/s」下完全不可测量。**用可靠性换掉这点开销是划算的。**
 *
 * <h2>★★ 铁律：本类**必须留在 {@code smooth.lift} 包**，绝不能挪进 {@code smooth.lift.mixin.mtr}</h2>
 * 本类不是 {@code @Mixin}，但它被 {@code Mtr3LiftDoorMixin} 的**注入处理器**调用。
 * Mixin 会把「注入进来的那次调用」写进目标类 {@code mtr.data.LiftServer} 的字节码里，
 * 而目标类的常量池解析用的是**游戏类加载器**。Mixin 对自己的 {@code @Mixin} 类有缓存、
 * 放行这种自引用；但对「只是放在 mixin 包里、却不是 @Mixin 的普通类」没有缓存 ——
 * 于是那次调用会去 {@code loadClass} 本类，Mixin 立刻抛：
 *
 * <pre>
 * IllegalClassLoadError: smooth.lift.mixin.mtr.Mtr3LiftAutoClose is in a defined mixin
 * package smooth.lift.mixin.mtr.* owned by smoothlift.mtr.mixins.json and cannot be
 * referenced directly
 *     at mtr.data.LiftServer.handler$...$autoCloseIdleDoor(LiftServer.java:574)
 * </pre>
 *
 * 表现是**游戏在刚进世界、服务端 tick 到第一条直梯时直接崩**（2026-10-01 实测）。
 * 1.20.1 那一版当时就踩过并已挪到 {@code smooth.lift}；1.20.4 直到 10-01 还留在旧位置 ⇒ 一并订正。
 * 一句话：**{@code smooth.lift.mixin.mtr} 这个包里只许放 {@code @Mixin} 类**（同规见
 * {@link smooth.lift.client.PlatformNameMask} 的类注释）。
 */
public final class Mtr3LiftAutoClose {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 停站保持的 tick 数（MTR4 的 DOOR_OPEN_TIME = 2000ms）。 */
    public static final int IDLE_TICKS = 40;

    /** MTR3 里「门已全开」的判据值 = {@code mtr.data.Lift.tick} 里的 48.0f（**不是** DOOR_MAX）。 */
    public static final float DOOR_FULL = 48.0f;

    private static boolean bound;
    private static boolean available;

    private static Field doorOpenField;
    private static Field doorValueField;
    private static Field idField;

    /** 本次会话累计自动关了几次；只用来在第一次和每次整百次时打一条日志。 */
    private static int closedCount;

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
