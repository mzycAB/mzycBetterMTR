package smooth.lift.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 【1.31.1204】「列车上下坡时，把整个 3D 视角窗口转一个角度」—— 指令 {@code /mtrqx on|off}。
 *
 * <h2>一、用户要的到底是什么（三轮澄清后的最终口径）</h2>
 * <ol>
 *   <li>「视角旋转」= <b>把整个游戏画面（3D 世界）整体转一个角度</b>，就像把窗口转一下
 *       —— 不是去改玩家鼠标控制的视线方向；</li>
 *   <li>旋转角 = <b>列车自身的倾斜角</b>，<b>与视线方向无关</b>（看向哪都转同样大小）
 *       —— 观感 = 列车/地板/窗框在屏幕上水平、地面与地平线变斜；</li>
 *   <li>准星 / 物品栏 / 聊天框这些 <b>HUD 不跟着转</b>（只转 3D 世界）；</li>
 *   <li>「玩家模型底面与列车地板平行」那条<b>保留</b>。</li>
 * </ol>
 * ★★ 第 2 条是【五改】改的：四改写成了「随视线方向变化」（看正前 ≡ 0、看侧窗 = 倾斜角），
 * 结果骑车时默认顺着车头看 ⇒ 横滚恒 0（用户报「并没有旋转」），一扭头又 0↔±17° 乱摆
 * （用户报「莫名其妙抽搐视角」）。五改把算式里的视线投影整段删掉，只留 {@code R^{-1}}。
 *
 * <h2>二、★ 之前错在哪：动了玩家<b>视线方向</b>（已全部删除）</h2>
 * 旧版在客户端 tick 里把玩家的 {@code xRot}/{@code yRot} 也拧了一把（绕车体横向轴转 Δpitch）。
 * 那既不等于「转窗口」，又会让玩家自己的瞄准方向发生偏移（准星慢慢飘），用户明确否掉了。
 * ⇒ 本类现在<b>一个字节都不写玩家视角</b>（{@code setXRot}/{@code setYRot} 在本文件里已不存在）。
 *
 * <h2>三、现在靠哪两条通路（都在渲染侧，只读本类的快照）</h2>
 * <ol>
 *   <li><b>相机</b>（{@code RideTiltCameraRollMixin}）：往 {@code GameRenderer.renderLevel}
 *       的 {@code poseStack} 上乘一个 {@code R^{-1}}（{@code R} = 模型那条用的同一个旋转）
 *       ⇒ 整个 3D 世界（含天空、第一人称手持物）一起反向转进列车坐标系：列车/地板/窗框变水平、
 *       地面与地平线变斜；HUD 画在另一个矩阵栈上（调用方 {@code setIdentity()} 过），天然不转。</li>
 *   <li><b>玩家模型倾斜</b>（{@code RideTiltPlayerRenderMixin}）：把本地玩家模型整体转到列车坐标系
 *       ⇒ 脚底贴合地板（第三人称可见）。</li>
 * </ol>
 * 两条都只读本类的只读接口：{@link #renderTiltPitch()}（列车绝对俯仰角）与 {@link #renderTiltYaw()}
 * （算车体横向轴 {@code r} 用）。★ 两条用的是<b>同一个 {@code R}</b>：模型乘 {@code R}、相机乘
 * {@code R^{-1}}，所以屏幕上「车厢是平的 + 模型站在地板上」两件事同时成立。
 *
 * <h2>四、★ 为什么「转窗口」这件事只能靠相机 roll（对 MC 1.20.4 逐条 javap 核过）</h2>
 * <ol>
 *   <li>{@code Camera.setRotation(yRot, xRot)} 内部是 {@code Quaternionf.rotationYXZ(-yRot, xRot, 0)}
 *       —— <b>roll 那一位写死 0</b>，{@code Camera} 自身也没有任何 roll 入口；</li>
 *   <li>{@code GameRenderer.renderLevel} 的视图矩阵不是从 {@code camera.rotation()} 来的，而是现场用
 *       两个<b>标量</b>拼的：{@code Axis.XP.rotationDegrees(getXRot())} +
 *       {@code Axis.YP.rotationDegrees(getYRot() + 180)} ⇒ 两轴都表达不了 roll；</li>
 *   <li>⇒ 只能在这两条旋转<b>之后</b>往 {@code poseStack} 上再乘一个旋转（那一段之后的
 *       取逆视图矩阵 / 视锥 / 世界 / 手全共用同一个 poseStack）。</li>
 * </ol>
 *
 * <h2>五、★ 转多少（【五改】最终算法：只有 {@code R^{-1}}，不读视线）</h2>
 * 列车横向轴（水平）{@code r = f × u = (-cos yaw, 0, sin yaw)}，{@code f = (sin yaw, 0, cos yaw)}；
 * {@code R} = 绕 {@code r} 转 {@code pitch}（MTR 约定上坡为正），地板法线 {@code n = R·(0,1,0)}。
 * <pre>
 *   模型那条：poseStack.mulPose(R)      // 把玩家转进列车坐标系（脚底贴地板）
 *   相机这条：poseStack.mulPose(R^{-1})  // 把世界反向转进列车坐标系（列车变平、地面变斜）
 * </pre>
 * ⇒ 屏幕上：列车 = {@code R·R^{-1}} = 水平；地面 = {@code R^{-1}} = 斜；模型（自带 {@code R}）回到正立。
 * ★ 角度就是<b>列车倾斜角本身</b>，与「往哪看」无关（算式里只有 {@code pitch} 与 {@code yaw}）。
 * ★ 纯逻辑重放见守卫 §5c：① 屏幕里列车地板横向轴 {@code r} 与竖直方向都被摆平
 * ② 旋转角恒 = {@code |pitch|}（不随视线/仰角变）③ 与模型那条严格互逆。
 * <p>★ 历史（四改，已删）：曾按「视线方向投影」算 {@code roll = ∠(u, n 的投影)} —— 看正前恒 0
 * （骑车里默认姿态 ⇒ 用户看到「没转」），扭头时在 0…±17° 间摆动（⇒「抽搐」）。
 *
 * <h2>六、数据怎么从 MTR 那几帧里取出来</h2>
 * 本模组不把 MTR 当编译依赖，{@code @Inject} 的处理器又只能「全参数逐位匹配」或「只要 CallbackInfo」，
 * 而 {@code PositionAndRotation} 是 MTR 自己的类型、编译期根本写不出来 ⇒ 分两步：
 * <ol>
 *   <li>{@code Mtr4RideTiltPositionMixin} 挂 {@code PositionAndRotation} 的
 *       {@code transformForwards}/{@code transformBackwards}（两个都是 HEAD），把
 *       {@code this.pitch}/{@code this.yaw}（两个 double，可 {@code @Shadow}）交出来 —— {@link #noteTransform};</li>
 *   <li>{@code Mtr4RideTiltMovementMixin} 挂 {@code VehicleRidingMovement.movePlayer(DDD)V} 的 HEAD
 *       —— 那条方法只在「玩家确实被列车带着走」时被调；在那里把第 1 步刚记下的值<b>立刻快照</b>
 *       （见 {@link #noteRideMove()}）。渲染是持续的，晚一步取到的就是别的车。</li>
 * </ol>
 * ★ 快照认账要<b>两条判据</b>：帧号「有新值」<b>且</b>线程「本线程刚写」（渲染线程也在写同一变量）。
 *
 * <h2>七、配置持久化</h2>
 * {@code config/smoothlift-view.properties}：{@code trainTiltView} = on/off，<b>默认 on</b>。
 * 键不存在（老配置 / 写坏了）按默认「on」处理，与 {@link EscalatorRenderMode}、{@link TrainAnnounceSwitch} 同一套读法。
 *
 * <h2>八、「装了没反应」怎么定位（日志按这个顺序看）</h2>
 * <pre>
 *   ① 插件层：[SmoothLift/TiltView] …：目标类在，已放行
 *              [SmoothLift/TiltView] mixin 已注入：RideTiltCameraRollMixin → net.minecraft.class_757
 *      ★ 只有「已放行」没有「已注入」⇒ 注入点对不上，被 require = 0 静默丢弃了。
 *   ② 采集：[SmoothLift/TiltView] 列车俯仰角注入点已命中（首帧 pitch=…°、yaw=…°）
 *           [SmoothLift/TiltView] 骑乘注入点已命中（首帧 列车pitch=…°）
 *   ③ 生效：[SmoothLift/TiltView] 相机随列车横滚注入点已命中（首帧 roll=…°）
 *           [SmoothLift/TiltView] 玩家模型倾斜注入点已命中（首帧 列车pitch=…°）
 *   ④ 心跳：[SmoothLift/TiltView] 骑乘中｜开关=开｜列车pitch=…°｜窗口横滚=…°｜骑乘帧=…
 *      ★ 【五改】「窗口横滚」应当**跟着列车pitch走**（列车一上坡就非零）；
 *        pitch 非 0 而「窗口横滚」恒 0 ⇒ 门闩没开（没在骑 / 采到的俯仰角是别的线程写的）或注入点漏了。
 *        pitch 恒 0.00° ⇒ 车体本身没俯仰（这段轨道是平的）。看「开关=」排除用户关了功能。
 * </pre>
 * 另有 {@code /mtrqx}（无参数）直接在游戏里打印 {@link #diagnostics()}，不必翻日志。
 */
public final class TrainTiltView {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** config 文件名（放在 FabricLoader 的 config 目录下）。 */
    private static final String CONFIG_FILE = "smoothlift-view.properties";
    /** properties 键名。值：{@code off} = 关（/mtrqx off）；其余一律按「开」处理。 */
    private static final String KEY_ENABLED = "trainTiltView";
    private static final String VALUE_ON = "on";
    private static final String VALUE_OFF = "off";

    /**
     * 连续多少个 tick 没有新的骑乘帧就认定「已经不在车上」。
     * 骑乘期间 {@code movePlayer(DDD)} 每 tick 都会被调一次，所以这个阈值只用来「认下车」。
     */
    private static final int RIDE_GAP_TICKS = 20;

    /** 当前是否开启（默认开）。 */
    private static boolean enabled = true;

    // ---------------------------------------------------------------- 采集端（mixin 写，可能不是主线程）

    /** 最近一次 {@code PositionAndRotation} 的 transform 看到的俯仰角（弧度，MTR 约定）。 */
    private static volatile double pendingPitch = Double.NaN;
    /** 同上，偏航角（弧度，MTR 约定）—— 只用来算车体横向轴 r，不参与别的。 */
    private static volatile double pendingYaw = Double.NaN;
    /** 上面两个值是**哪条线程**写进去的 —— 用来把「同线程紧邻的那次搬运」与渲染线程的噪声分开。 */
    private static volatile Thread pendingThread;
    /** 每次 transform 都 +1；「有没有新值」只看它。 */
    private static volatile long pendingStamp;

    /** 「玩家被列车带着走」那一帧快照下来的俯仰 / 偏航角（{@link #noteRideMove()} 写）。 */
    private static volatile double ridePitch = Double.NaN;
    private static volatile double rideYaw = Double.NaN;
    /** 骑乘帧计数：只有**新号码**才值得消费一次。 */
    private static volatile long rideSeq;

    /**
     * 「此刻正骑在车上」的渲染侧标记。
     *
     * <p>为什么不能直接用 {@link #rideGapTicks}：那个字段是**主线程 tick** 在写的，
     * 而两条渲染侧通路跑在**渲染线程**里，两边帧率都不是一回事。这里由 {@link #noteRideMove()}
     * （渲染线程，与搬运同一帧）置位、由 {@link #onClientTick()}（主线程，认到下车时）清位。
     */
    private static volatile boolean rideActive;

    /** 渲染侧每帧算出来的窗口横滚角（度）—— 只用于心跳/诊断，让「有没有真的转」一眼可见。 */
    private static volatile double lastRollDegrees;

    // ---------------------------------------------------------------- 消费端（主线程读写）

    /** 主线程已经消费到的骑乘帧号码。 */
    private static long consumedRideSeq;
    /** 主线程已经消费到的 {@link #pendingStamp} 号码（{@link #noteRideMove()} 用）。 */
    private static long consumedStamp;
    /** 连续多少个 tick 没收到骑乘帧（认「下车」用）。 */
    private static int rideGapTicks = RIDE_GAP_TICKS;

    /**
     * 下车信号：{@code sendUpdate(true)}（mixin，可能不在主线程）置位，
     * 主线程在下一 tick 开头清 {@link #rideActive}（渲染侧下一帧就不再倾斜/横滚）。
     */
    private static volatile boolean rideEndRequested;

    /** 诊断用：只打一次，避免每帧刷屏。 */
    private static boolean transformHookLogged;
    private static boolean rideHookLogged;
    /** 诊断用：{@link #noteRideMove()} 三个拒绝分支各自只报一次（它们每帧都会走到）。 */
    private static boolean noStampLogged;
    private static boolean staleStampLogged;
    private static boolean threadMismatchLogged;
    /** 诊断用：骑乘心跳的计数器（每 {@link #HEARTBEAT_TICKS} 个 tick 打一行）。 */
    private static int heartbeatTicks;
    /** 诊断用：模型倾斜真正生效过（非零角）只报一次。 */
    private static volatile boolean modelTiltLogged;
    /** 诊断用：相机横滚真正生效过（非零角）只报一次。 */
    private static volatile boolean cameraRollLogged;

    /** 骑乘心跳间隔（tick）。20 tick = 1 秒 —— 一眼看出「在骑但没转」，又不会刷屏。 */
    private static final int HEARTBEAT_TICKS = 20;

    /** 诊断串里用来固定小数位的格式化（中文环境下 {@code String.format} 默认也会用 '.'，但显式写死更稳）。 */
    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private TrainTiltView() {
    }

    // ---------------------------------------------------------------- 配置

    /** 客户端初始化时调用一次：从 config 读回上次的选择。 */
    public static void load() {
        Path file = configFile();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift/TiltView] 读取视角配置失败，使用默认（开启）: {}", file, e);
            return;
        }
        // 只有明确写成 off 才关；键不存在（老配置文件）/ 写坏了都按默认「开」处理。
        enabled = !VALUE_OFF.equals(props.getProperty(KEY_ENABLED));
        LOGGER.info("[SmoothLift/TiltView] 列车倾斜视角：{}（{}）",
                enabled ? "开" : "关", enabled ? "/mtrqx off 关闭" : "/mtrqx on 开启");
    }

    /** 是否开启「列车倾斜时整个画面跟着转」。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 开关本功能。
     *
     * @return 开关是否真的变了（没变返回 false，调用方可以跳过「已切换」的提示）
     */
    public static boolean apply(boolean on) {
        if (enabled == on) {
            return false;
        }
        enabled = on;
        save();
        LOGGER.info("[SmoothLift/TiltView] 列车倾斜视角已{}（/mtrqx）", on ? "开启" : "关闭");
        return true;
    }

    /** 开关对应的中文字面（用于指令反馈）。 */
    public static String name() {
        return enabled ? "开启（列车倾斜时整个画面跟着旋转，窗外窗框与地板保持水平）"
                : "关闭（与 MTR 原版一致：画面不随列车倾斜）";
    }

    /**
     * 诊断串 —— 给 {@code /mtrqx}（无参数）显示，同时写进日志。
     *
     * <p>把「装了没反应」一刀切成几段，任何一段都能单独指出来。纯读，不改任何状态。
     */
    public static String diagnostics() {
        return "注入点命中：列车俯仰角=" + (transformHookLogged ? "是" : "否")
                + "、骑乘帧=" + rideSeq
                + "；采样到的列车pitch=" + (Double.isNaN(ridePitch) ? "未采到" : fmt(Math.toDegrees(ridePitch)) + "°")
                + "、当前窗口横滚=" + fmt(lastRollDegrees) + "°"
                + "；相机横滚=" + (cameraRollLogged ? "已生效" : (rideActive ? "待生效（列车还没俯仰）" : "未在车上"))
                + "；玩家模型倾斜=" + (modelTiltLogged ? "已生效" : (rideActive ? "待生效（列车还没俯仰）" : "未在车上"));
    }

    private static void save() {
        Path file = configFile();
        if (file == null) {
            return;
        }
        // ★ 与 EscalatorRenderMode 同一条规矩：这里是「整份覆盖写」，新增配置项时记得也补一行。
        Properties props = new Properties();
        props.setProperty(KEY_ENABLED, enabled ? VALUE_ON : VALUE_OFF);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "mzycBetterMTR rotate the whole view with train tilt (1.31)");
            }
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift/TiltView] 保存视角配置失败: {}", file, e);
        }
    }

    private static Path configFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 采集端（mixin 调）

    /**
     * {@code PositionAndRotation.transformForwards} / {@code transformBackwards} 的 HEAD 调这里
     * —— 把那辆车的俯仰角递出来。
     *
     * <p>★ 这个方法<b>会被所有</b> {@code PositionAndRotation} 调用（不只被骑的那辆车），
     * 所以它只是「记录最新值」，不判断、不换算；判断交给 {@link #noteRideMove()}。
     *
     * @param pitch MTR 的俯仰角（弧度）
     * @param yaw   MTR 的偏航角（弧度）
     */
    public static void noteTransform(double pitch, double yaw) {
        pendingPitch = pitch;
        pendingYaw = yaw;
        pendingThread = Thread.currentThread();
        pendingStamp++;
        if (!transformHookLogged) {
            transformHookLogged = true;
            LOGGER.info("[SmoothLift/TiltView] 列车俯仰角注入点已命中（首帧 pitch={}°、yaw={}°，线程 {}）",
                    Math.toDegrees(pitch), Math.toDegrees(yaw), Thread.currentThread().getName());
        }
    }

    /**
     * {@code VehicleRidingMovement.movePlayer(DDD)V} 的 HEAD 调这里
     * —— <b>玩家确实被列车带着走</b>了，把刚记下的俯仰角快照下来。
     *
     * <p>★ 必须在这里<b>立刻快照</b>：这一刻 {@link #pendingPitch} 一定是这辆被骑的车的，
     * 往后就未必了（渲染还在继续，别的车会覆盖它）。
     *
     * <p>★ 两条判据（都要满足）：值比上次新，<b>而且</b>是同一条线程刚写的。
     */
    public static void noteRideMove() {
        long stamp = pendingStamp;
        if (stamp == 0L) {
            // 还没采到过任何俯仰角（俯仰角注入点没命中 / 首帧）。
            if (!noStampLogged) {
                noStampLogged = true;
                LOGGER.warn("[SmoothLift/TiltView] 骑乘链路在跑，但列车俯仰角从未采到"
                        + "（PositionAndRotation.transformForwards/Backwards 那两个注入点没生效？"
                        + "对照日志里插件的「已放行」与「mixin 已注入」两行）");
            }
            return;
        }
        if (stamp == consumedStamp) {
            // 没有新值（这条链路这一步之前没人 transform 过）。
            if (!staleStampLogged) {
                staleStampLogged = true;
                LOGGER.warn("[SmoothLift/TiltView] 骑乘链路在跑，但本帧没有新的列车俯仰角"
                        + "（movePlayer(DDD) 之前的 transform 没被采到 ⇒ 俯仰角注入点漏了）");
            }
            return;
        }
        if (pendingThread != Thread.currentThread()) {
            // 是别的线程（渲染）留下的值 ⇒ 不是这次搬运用的那个，原样不动。
            if (!threadMismatchLogged) {
                threadMismatchLogged = true;
                LOGGER.warn("[SmoothLift/TiltView] 骑乘链路在跑，但采到的俯仰角来自别的线程"
                        + "（采集线程 {} / 骑乘线程 {}）⇒ 线程判据把这一帧拒了",
                        pendingThread == null ? "null" : pendingThread.getName(),
                        Thread.currentThread().getName());
            }
            return;
        }
        consumedStamp = stamp;
        ridePitch = pendingPitch;
        rideYaw = pendingYaw;
        rideSeq++;
        rideActive = true;
        if (!rideHookLogged) {
            rideHookLogged = true;
            LOGGER.info("[SmoothLift/TiltView] 骑乘注入点已命中（首帧 列车pitch={}°，线程 {}）",
                    Math.toDegrees(ridePitch), Thread.currentThread().getName());
        }
    }

    /**
     * {@code VehicleRidingMovement.sendUpdate(true)} 的 HEAD 调这里 —— MTR 自己解除骑乘了。
     *
     * <p>MTR 在 3 处调 {@code sendUpdate(true)}（tick 里的松开 Shift 下车，以及
     * movePlayer 里两条「人走丢了 / 位置算不出来」的收尾），全部是「不再骑乘」。
     * 这里只置一个信号，真正清 {@link #rideActive} 在主线程的 {@link #onClientTick()} 里做。
     */
    public static void onRideEnd() {
        rideEndRequested = true;
    }

    // ---------------------------------------------------------------- 渲染端（渲染线程读）

    /**
     * 渲染侧只读：这一帧要不要把画面/模型倾斜到列车坐标系上。
     *
     * <p>返回 {@code NaN} = 不倾斜（功能关 / 没在骑 / 还没采到俯仰角）；
     * 否则返回**列车自己的俯仰角**（弧度，MTR 约定：上坡为正）。
     *
     * <p>两条渲染侧通路都读它、各自换算：相机那条乘 {@code R^{-1}}（不需要视线方向，五改起）、
     * 玩家模型那条拿它当绕 {@code r} 的转角。
     */
    public static double renderTiltPitch() {
        if (!enabled || !rideActive) {
            return Double.NaN;
        }
        return ridePitch;
    }

    /**
     * 渲染侧只读：列车自己的偏航角（弧度，MTR 约定）—— 两条倾斜通路都拿它算车体横向轴
     * {@code r = (-cos yaw, 0, sin yaw)}（相机乘 {@code R^{-1}}、模型乘 {@code R}，同一个 {@code r}）。
     */
    public static double renderTiltYaw() {
        return rideYaw;
    }

    /** 模型倾斜真正生效（角度非零）时由渲染端回调一次，只为留一行诊断。 */
    public static void noteModelTilt(double pitch) {
        if (modelTiltLogged) {
            return;
        }
        modelTiltLogged = true;
        LOGGER.info("[SmoothLift/TiltView] 玩家模型倾斜注入点已命中（首帧 列车pitch={}°，"
                + "玩家模型底面已与列车地板平行）", fmt(Math.toDegrees(pitch)));
    }

    /**
     * 相机真的转过（角度非零）时由渲染端每帧回调：记下当前角度 + 首帧留一行正面证据。
     *
     * <p>★ 与「模型倾斜」分开：模型那一路管脚底贴合地板，「整个画面转进列车坐标系」靠这一路。
     * 两条都命中 = 「画面 + 模型一起随列车倾斜」成立。
     * <p>★【五改】这里记的角度 = <b>列车倾斜角</b>本人（与视线方向无关）⇒ 心跳里
     * 「窗口横滚」应当跟着「列车pitch」一起非零，是「到底转没转」最直接的判据。
     */
    public static void noteCameraRoll(double rollDegrees) {
        lastRollDegrees = rollDegrees;
        if (cameraRollLogged) {
            return;
        }
        cameraRollLogged = true;
        LOGGER.info("[SmoothLift/TiltView] 相机随列车横滚注入点已命中（首帧 roll={}°，"
                + "整个 3D 世界已转进列车坐标系：列车/地板水平、地面与地平线变斜）", fmt(rollDegrees));
    }

    // ---------------------------------------------------------------- 消费端（主线程 tick）

    /**
     * 每客户端 tick 调一次（{@code ClientTickEvents.END_CLIENT_TICK}）。
     *
     * <p>★ 本方法<b>不再碰玩家视角的任何一个字节</b>：只负责 ① 认下车（清 {@link #rideActive}）、
     * ② 统计骑乘帧/下车间隔、③ 打诊断心跳。真正的「转画面」全在渲染线程那两条 mixin 里。
     *
     * @param minecraft 客户端实例（由事件回调传入；只用来判「在不在世界里」）
     */
    public static void onClientTick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }

        // 「上一条 tick 就已经没有骑乘帧了」—— 用来把「刚上车」与「一直在车上」分开（只影响诊断日志）。
        boolean idleBefore = rideGapTicks >= RIDE_GAP_TICKS;

        // ① 下车信号 / 长时间收不到骑乘帧 ⇒ 认定已经不在车上（渲染侧下一帧就不再倾斜/横滚）。
        if (rideEndRequested || idleBefore) {
            rideEndRequested = false;
            rideActive = false;
            lastRollDegrees = 0.0;
        }

        // ② 这一 tick 有没有新的骑乘帧？
        long seq = rideSeq;
        if (seq != consumedRideSeq) {
            consumedRideSeq = seq;
            if (idleBefore) {
                // ★ 诊断：证明「玩家确实被列车带着走」这条链路通了。
                LOGGER.info("[SmoothLift/TiltView] 检测到骑乘开始（列车俯仰角 {}，第 {} 个骑乘帧）",
                        Double.isNaN(ridePitch) ? "未采到（俯仰角注入点没通）" : fmt(Math.toDegrees(ridePitch)) + "°",
                        rideSeq);
            }
            rideGapTicks = 0;
        } else if (rideGapTicks < RIDE_GAP_TICKS) {
            rideGapTicks++;
        }

        // ③ 诊断心跳：骑乘中每秒一行（下车立刻停）—— 把「在骑但没转」一步摊开。
        if (rideGapTicks == 0) {
            if (++heartbeatTicks >= HEARTBEAT_TICKS) {
                heartbeatTicks = 0;
                LOGGER.info("[SmoothLift/TiltView] 骑乘中｜开关={}｜列车pitch={}｜窗口横滚={}°｜骑乘帧={}",
                        enabled ? "开" : "关",
                        Double.isNaN(ridePitch) ? "未采到" : fmt(Math.toDegrees(ridePitch)) + "°",
                        fmt(lastRollDegrees),
                        rideSeq);
            }
        } else {
            heartbeatTicks = 0;
        }
    }

    /** 退出世界时复位：上一段骑乘的基线与帧号都不再可比（换存档后是另一辆车）。 */
    public static void onDisconnect() {
        rideSeq = 0L;
        consumedRideSeq = 0L;
        pendingStamp = 0L;
        consumedStamp = 0L;
        pendingThread = null;
        ridePitch = Double.NaN;
        rideYaw = Double.NaN;
        pendingPitch = Double.NaN;
        pendingYaw = Double.NaN;
        rideGapTicks = RIDE_GAP_TICKS;
        rideActive = false;
        rideEndRequested = false;
        lastRollDegrees = 0.0;
        // 诊断用的「只打一次」也一起复位，换世界后能重新确认注入点。
        transformHookLogged = false;
        rideHookLogged = false;
        noStampLogged = false;
        staleStampLogged = false;
        threadMismatchLogged = false;
        modelTiltLogged = false;
        cameraRollLogged = false;
        heartbeatTicks = 0;
    }
}
