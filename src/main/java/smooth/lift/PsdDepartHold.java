package smooth.lift;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 【10-03 五改】「**关门后等待 X 秒发车**」的服务端取值层 —— 列车那一侧唯一需要反射的东西。
 *
 * <h2>这一项到底改什么（★ 用户点名「真的改变列车发车」）</h2>
 * 不是音频、不是界面：它改的是 **MTR 自己的列车时刻**。
 * MTR4 的时序（{@code org.mtr.core.data.Vehicle} 逐条 javap 核过，4.0.5）：
 * <pre>
 *   停稳                t = 0
 *   开门指令            t = 1000ms   （DOOR_DELAY）
 *   关门指令            t = closeAt = max(D/2, D - 4200)
 *   门走完              t = closeAt + 3200ms（DOOR_MOVE_TIME，MTR 自己的模型）
 *   真实发车            t = closeAt + 4200ms（doorCooldown 数到 0；4200 = 3200 + 1000）
 * </pre>
 * 所以「把发车时刻挪 X 秒」唯一干净的落点 = **发车倒计时 {@code doorCooldown}**
 * （{@code Vehicle} 的 private long 字段，{@code Vehicle.simulate} 每 tick 维护它，
 * {@code startUp(JJ)V} 里 {@code if (doorCooldown != 0) return;} 就是发车闸门）。
 * 本模组在 {@code startUp} 的 HEAD 上把它改成
 * {@code max(0, doorCooldown - DOOR_DELAY_MS + X*1000)}：
 * <ul>
 *   <li>{@code X > 0} ⇒ 倒计时变长 ⇒ 门关完之后再等 X 秒才发车；</li>
 *   <li>{@code X < 0} ⇒ 倒计时变短 ⇒ 提前 |X| 秒发车；够大（|X| &gt; 3.2 秒）时
 *       <b>门还没关完列车就走了</b> —— 正是用户说的「还没关门就发车或者门没关完就发车」；</li>
 *   <li>{@code X == 0} ⇒ 一个字节都不写（MTR 原样）。</li>
 * </ul>
 * ★ 参照点 = <b>MTR 自己的「门走完」</b>（关门指令 + 3200ms）。它与「屏幕上看到门真的合上」
 * （本模组客户端实测约 1.45 秒，且跟帧率走）不是一个数 —— 见类注释末尾的说明。
 *
 * <h2>为什么取值放在这里、而不是直接读 EscalatorSpeedManager</h2>
 * MTR 的列车模拟默认跑在**服务端主线程**（{@code Config.getServer().getUseThreadedSimulation()}
 * 默认 false ⇒ {@code Main.manualTick()}），但**可以为 true**（那时它跑在 MTR 自己的 10ms 线程池上）。
 * 而 {@code EscalatorSpeedManager} 读的是存档数据（{@code SavedData} + 普通 Map），
 * 只在服务端主线程上安全。所以本条链路的规矩与 {@code Mtr3LiftAutoClose} 一致：
 * <b>注入体里只读一个普通的 {@code long} 字段</b>，真正的取值（反射 + 查档）在
 * {@link #offsetMsFor} 里做，且**只在服务端主线程**（{@code SERVER_STARTED} 之后）用。
 *
 * <h2>身份：这是哪一座车站</h2>
 * 界面写下的 key = {@code PsdDoorTracker} 的 runKey = **车站级** {@code Long.MIN_VALUE + stationId}
 * （认不到车站 id 时回落站台级 {@code + platformId}）。列车这边两个都试：
 * <ol>
 *   <li>{@code Vehicle.vehicleExtraData.getThisStationId()} → {@code Long.MIN_VALUE + id}；</li>
 *   <li>认不到（或那一条没设过）再试 {@code getThisPlatformId()} → {@code Long.MIN_VALUE + id}。</li>
 * </ol>
 * 两个都读不到 ⇒ 返回 0（= 什么也不做，行为与加这个功能之前完全一样）。
 *
 * <h2>★ 已知边界（都要在报告里对用户说清）</h2>
 * <ul>
 *   <li><b>参照点是 MTR 的 4200ms 模型</b>，不是「屏幕上门合上那一刻」（客户端实测约 1.45 秒）。
 *       两者差多少取决于帧率，所以「X 秒」的观感可能与 MTR 的模型差一截。</li>
 *   <li><b>被信号憋住的列车拿不到这一档</b>：偏移只在「关门那一刻那一次 {@code startUp}」写
 *       （判据 {@code doorCooldown == 4200}），被憋住的列车第一次进 {@code startUp} 时倒计时
 *       已经不是 4200 ⇒ 静默退回 MTR 原样（宁可不动，也不要按错的基准乱挪）。</li>
 *   <li><b>司机按住开门覆盖</b>时 MTR 每 tick 把倒计时重新置回 4200 ⇒ 偏移会被抹掉。</li>
 *   <li>{@code doorCooldown} **不进存档** ⇒ 等待期间存盘 / 重进世界，这一次等待就没了。</li>
 *   <li>MTR 会把「晚点」算进 {@code deviation}（{@code updateDeviation()}），
 *       之后可能根据侧线的「晚点时缩短停站」把时间补回来一部分。</li>
 * </ul>
 */
public final class PsdDepartHold {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * MTR 的 {@code DOOR_DELAY}（ms）：关门指令到「MTR 认为门开始动」的那 1000ms。
     *
     * <p>与 {@code MtrDwellAccess.DOOR_DELAY_MS} 同值同源。发车倒计时 {@code doorCooldown}
     * 的 4200 = 门程 3200 + 这个 1000 ⇒ 要把「X 秒」量到**门走完**那一刻，就得减掉它。
     */
    public static final long DOOR_DELAY_MS = 1000L;

    /**
     * 偏移量的硬上界（秒）—— 用户范围是 {@code (-∞, +∞)}，但真让它生效必须有硬顶：
     * 一个 {@code X = 2e9} 会把 {@code doorCooldown} 变成约 68 年，那列车就永远停在站台上了。
     *
     * <p>取 1 小时：远大于任何真实停站需求（哪怕当作场景道具也够），又绝对不会把车钉死。
     * ★ 负值方向不需要对称的硬顶：{@code max(0, …)} 天然把它夹在「立刻发车」上。
     */
    public static final long MAX_HOLD_SECONDS = 3600L;

    private PsdDepartHold() {
    }

    // ------------------------------------------------------------------
    // 服务端实例 / 反射绑定
    // ------------------------------------------------------------------

    /** 当前服务端实例（由 {@code SmoothLift} 在 {@code SERVER_STARTED} 里塞进来）。 */
    private static volatile MinecraftServer server;

    /** 服务端起来 / 关掉时更新；关掉置 null（避免拿旧世界的数据算）。 */
    public static void setServer(MinecraftServer value) {
        server = value;
    }

    /** 反射绑定：只做一次；任何一步失败都只是「这一项不生效」。 */
    private static boolean bound;
    private static boolean available;
    private static Field vehicleExtraDataField;
    private static Method getThisStationId;
    private static Method getThisPlatformId;
    /** 「反射链路坏过一次」只打一条日志。 */
    private static boolean warned;

    private static synchronized void bind() {
        if (bound) {
            return;
        }
        bound = true;
        try {
            Class<?> vehicle = Class.forName("org.mtr.core.data.Vehicle");
            vehicleExtraDataField = vehicle.getField("vehicleExtraData");
            Class<?> extra = Class.forName("org.mtr.core.data.VehicleExtraData");
            getThisStationId = extra.getMethod("getThisStationId");
            getThisPlatformId = extra.getMethod("getThisPlatformId");
            available = true;
            LOGGER.info("[SmoothLift/PsdDepart] 「关门后等待 X 秒发车」已就绪"
                    + "（身份 = VehicleExtraData.getThisStationId/getThisPlatformId，"
                    + "参照点 = MTR 自己的关门指令 + {}ms 门程）", 4200L - DOOR_DELAY_MS);
        } catch (Throwable t) {
            available = false;
            LOGGER.info("[SmoothLift/PsdDepart] 读不到 MTR 列车身份（{}）—— "
                    + "「关门后等待 X 秒发车」这一项不会生效，其余功能不受影响", t.toString());
        }
    }

    /**
     * 这一列车**当前停靠的那一站**配的偏移毫秒数；没有配置 / 读不到 ⇒ {@code 0}。
     *
     * <p>只在 {@code startUp} 的开门那一刻被调一次（每站一次），不在每 tick 的热路上。
     *
     * @param vehicle MTR 的 {@code org.mtr.core.data.Vehicle} 实例（本模组编译期没有它的类型，
     *                所以这里收 {@code Object}；拿不到就返回 0）
     */
    public static long offsetMsFor(Object vehicle) {
        if (!available && bound) {
            return 0L;
        }
        if (!bound) {
            bind();
            if (!available) {
                return 0L;
            }
        }
        MinecraftServer current = server;
        if (current == null || vehicle == null) {
            return 0L;
        }
        try {
            Object extra = vehicleExtraDataField.get(vehicle);
            if (extra == null) {
                return 0L;
            }
            // 【10-03 五改 修订】键改成**站台 id**：那两项设置现在是「门串级」的
            //   （用户点名「每个屏蔽门串独有」），而列车只知道「我停在哪个站台」——
            //   写设置时客户端把这一串落在哪个站台一起存了下来（PsdRunSetting.platformId），
            //   这里就按站台 id 反查（见 EscalatorSpeedManager.getPsdDepartDelayForPlatform）。
            long platformId = ((Number) getThisPlatformId.invoke(extra)).longValue();
            long seconds = lookupByPlatform(current, platformId);
            if (seconds == 0L) {
                // 兜底：站台 id 还没写进记录时（旧档迁移值挂在车站级键下），回落「车站级」那条路。
                long stationId = ((Number) getThisStationId.invoke(extra)).longValue();
                seconds = lookupByRunKey(current, Long.MIN_VALUE + stationId);
            }
            if (seconds == 0L) {
                return 0L;
            }
            long clamped = Math.max(-MAX_HOLD_SECONDS, Math.min(MAX_HOLD_SECONDS, seconds));
            return clamped * 1000L;
        } catch (Throwable t) {
            available = false;
            if (!warned) {
                warned = true;
                LOGGER.warn("[SmoothLift/PsdDepart] 读列车停靠身份失败，「关门后等待 X 秒发车」本局停用：{}",
                        t.toString());
            }
            return 0L;
        }
    }

    /**
     * 在**所有维度**里查这一个键配的秒数（{@code 0} = 没设过 / 设成 0）。
     *
     * <p>★ 为什么要跨维度扫：列车的模拟只知道自己的维度编号，而本模组的数据是按维度存的
     * （两个维度里同一条键才会撞车，而键里含 {@code Random().nextLong()} 出来的站台 id
     * 或方块坐标 ⇒ 实际不可能撞）。每站只调一两次，代价可忽略。
     */
    private static long lookupByPlatform(MinecraftServer current, long platformId) {
        try {
            for (ServerLevel level : current.getAllLevels()) {
                int seconds = EscalatorSpeedManager.getPsdDepartDelayForPlatform(level, platformId);
                if (seconds != 0) {
                    return seconds;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就是 0（不生效），不影响列车本身
        }
        return 0L;
    }

    /** 旧档兼容：按**车站级旧键**（{@code Long.MIN_VALUE + stationId}）直接查门串级那张表。 */
    private static long lookupByRunKey(MinecraftServer current, long stationKey) {
        try {
            for (ServerLevel level : current.getAllLevels()) {
                int seconds = EscalatorSpeedManager.getPsdRunDepartDelaySeconds(level, stationKey);
                if (seconds != 0) {
                    return seconds;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就是 0（不生效），不影响列车本身
        }
        return 0L;
    }

    /** 真改过一次就记一条日志（第一次 + 每 50 次）—— 「到底生没生效」只看这一条。 */
    private static int appliedCount;

    /**
     * 注入体改完 {@code doorCooldown} 之后回报一行（节流）。
     *
     * <p>★ 为什么要有：这一项**没有任何界面反馈**（改的是列车，不是声音），
     * 用户唯一能核对的证据就是这行日志里的新倒计时毫秒数 —— 与「MTR 原版 4200」一比就知道
     * 生效没生效、生效了多少。
     */
    public static void noteApplied(long newCooldownMs) {
        appliedCount++;
        if (appliedCount == 1 || appliedCount % 50 == 0) {
            LOGGER.info("[SmoothLift/PsdDepart] 已把这一次停站的发车倒计时改成 {}ms"
                            + "（MTR 原版 4200ms；含门程 3200ms）—— 累计 {} 次",
                    newCooldownMs, appliedCount);
        }
    }
}
