package smooth.lift.client;

import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 【1.42】MTR 直梯（Lift）的**只读**跨版本访问层。
 *
 * <p><b>为什么要有这个类</b>：SmoothLift 的 build.gradle **不依赖 MTR**
 * （fabric.mod.json 里 MTR 只是 {@code suggests}），所以编译期根本没有 MTR 的类型可用。
 * 而 MTR 3.x 与 4.x 的直梯是**两套完全不同的类**，字段/方法名一个都不重合：
 *
 * <pre>
 *                                     MTR 3（Fabric 1.20 / 3.2.2）      MTR 4（Fabric 1.20.4 / 4.0.5）
 *   直梯数据类                        mtr.data.Lift                    org.mtr.core.data.Lift
 *   服务端子类                        mtr.data.LiftServer               —
 *   客户端子类                        mtr.data.LiftClient               —
 *   客户端集合                        mtr.client.ClientData.LIFTS       MinecraftClientData.getInstance()
 *                                       （public static Set）             .liftWrapperList
 *                                       ★ java.util.Set = Iterable        ★ fastutil 的 Map，**不是 Iterable**
 *   门开合程度                        protected float doorValue          public float getDoorValue()
 *                                       0..48，但**可见**全开 = 24          0..1（由 stoppingCoolDown 算）
 *                                       （可见开合度 = min(v/24, 1)）
 *                                       ★ 注意 24..48 是「已全开但数值继续走」的空档
 *   门「该不该开」                    protected boolean doorOpen         （没有对应字段）
 *   车/楼层位置                       getPositionX/Y/Z()                getCurrentFloor().getPosition()
 *   运动方向【1.44】                  getLiftDirection()                getDirection()
 *                                       → LiftDirection                   → LiftDirection
 *                                          NONE / UP / DOWN                  NONE / UP / DOWN
 *                                       ★ 两版都是「每 tick 从待办指令重算」，
 *                                         有目标就是 UP/DOWN、空闲才是 NONE
 * </pre>
 *
 * <p>所以这里用**反射**读两个版本的字段/方法，把结果统一成 {@link LiftView}：
 * 位置 + {@code doorFraction}（0 = 全关，1 = 全开）。
 * <b>反射只在这个类里出现</b>，播放器那边看到的是干净的 Java 类型。
 *
 * <p><b>版本判定</b>：先看 {@code org.mtr.core.data.Lift} 在不在（MTR4），再看
 * {@code mtr.data.Lift}（MTR3）。两个都不在 = 没装 MTR，本功能整体静默关闭
 * （不是报错 —— 模组本来就允许不装 MTR）。
 *
 * <p><b>为什么用「归一化」而不是把两个量级散出去</b>：两个版本的门值量级不同（MTR3 可见全开 = 24、
 * MTR4 = 1），播放器只关心「从全开到动了」「从全关到动了」两个**跳变**，所以这里统一除成本版本的
 * 「**可见**全开值」，两端都变成 1.0 / 0.0，{@link #fraction} 再把结果夹回 [0,1]。
 * ★ MTR3 必须除 **24**（可见全开）而不是 48：除以 48 的话，门在 48→24 那段「空档」里
 * fraction 就从 1.0 掉下来，关门提示音会比门真的开始动**早 1.2 秒**响（见 {@link #bindMtr3}）。
 * 除以 24 后，MTR3 的 fraction 与 MTR4 的 {@code getDoorValue()} 语义完全一致 = 可见开合度。
 */
public final class MtrLiftAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** MTR 版本。 */
    public enum Version {
        /** 没装 MTR，或版本不认识 —— 直梯提示音整体不工作。 */
        NONE,
        /** MTR 3.x（{@code mtr.data.Lift} 那一代）。 */
        MTR3,
        /** MTR 4.x（{@code org.mtr.core.data.Lift} 那一代）。 */
        MTR4
    }

    /**
     * 【1.44】直梯当前**打算往哪走** —— 两版 MTR 各自的「方向」枚举归一化成这三种。
     *
     * <p><b>语义（两版完全一致，都对着字节码核过）</b>：方向是**每 tick 从待办指令重算**出来的，
     * 不是「正在位移」才算：
     * <pre>
     *   MTR3  mtr.data.Lift.tick → lambda$tick$4：
     *           有目标楼层 ⇒ liftDirection = (目标在上 ? UP : DOWN)，否则 NONE
     *   MTR4  org.mtr.core.data.Lift.getDirection()：
     *           instructions 为空 ⇒ NONE；
     *           否则 LiftDirection.fromDifference(目标楼层进度 - 当前位置)
     * </pre>
     * ⇒ <b>「NONE → UP/DOWN」这一个跳变正好等于「刚接到指令、准备朝那个方向走」</b>，
     * 与用户说的「准备移动」同义。而且因为它每 tick 重算、**在移动全程都保持同一个值**，
     * 不会在途中闪烁，所以一个方向只会触发一次（中途经过楼层不会重复触发）。
     *
     * <p>两版枚举都只有 {@code NONE / UP / DOWN} 三个常量（现已各自与真实 jar 对撞过），
     * 所以这里按 {@code toString()} 的名字解析，**不需要在编译期认识任何 MTR 类型**。
     * 认不出来的名字一律当 {@link #NONE}（宁可不出声，也不要按错方向出声）。
     */
    public enum Move {
        NONE,
        UP,
        DOWN;

        /** 把一个 MTR 原生方向枚举解析成 {@link Move}；null / 名字不认识 → {@link #NONE}。 */
        static Move of(Object mtrDirection) {
            if (mtrDirection == null) {
                return NONE;
            }
            String name = mtrDirection.toString();
            if ("UP".equals(name)) {
                return UP;
            }
            if ("DOWN".equals(name)) {
                return DOWN;
            }
            return NONE;
        }
    }

    /**
     * 【1.52】一条直梯**真实轿厢**的几何
     * （= MTR「直梯自定义」界面里那组「高度 / 宽度 / 深度 + 偏移」，两个版本都读得到）。
     *
     * <p>坐标已经换算成**世界坐标**：{@code centerX/centerZ} = 楼层方块位置 + offsetX/Z
     * ⇒ 这是轿厢的**水平中心**，不是楼层方块的那个角（旧版拿方块角当圆心，等于把「轿厢」
     * 画歪了半个厢体）。{@code baseY} = 楼层方块 y + offsetY，即轿厢底面。
     *
     * <p>{@code spacing} = 这条直梯**相邻楼层的最小竖直间距**，它是「行进中基准能偏多少」的上界：
     * {@code getCurrentFloor()} 取的是 {@code railProgress} 所在那一段的**较近一端**
     * （javap 实测 {@code lambda$getCurrentFloor$3}：{@code floors.get(idx - (p < 0.5 ? 1 : 0))}），
     * 所以基准最多偏**一整段**。竖直判定必须留出这个容差，否则会把**正在乘坐的乘客**误判成
     * 轿厢外、让「准备向上/向下」那声在行进途中一路淡下去（那比原来的病更难听）。
     * 拿不到楼层表时为 0 ⇒ 调用方看到 0 就**不做**竖直判定（只按水平盒判）。
     *
     * <p>{@code null} = 反射读不到几何（MTR 改了名 / 只有一层楼）⇒ 调用方回落到旧的
     * 「以楼层方块为心、半径 1.5 格的圆」。**这是可选的增强，读不到不许把功能弄哑。**
     */
    public record Cabin(double centerX, double centerZ, double baseY,
                        double halfWidth, double halfDepth, double height, double spacing) {
    }

    /**
     * 一条直梯的只读快照。
     *
     * @param id           直梯 ID（同一代之间稳定，用来跨 tick 认出「还是那条直梯」）
     * @param x/y/z        车门那一端的世界坐标（MTR3 = 轿厢位置；MTR4 = 轿厢最近的那个楼层）
     * @param doorFraction 门开合程度：0 = 全关，1 = 全开（已按本版本的全开值归一化）
     * @param move         【1.44】当前打算走的方向（NONE = 停着待命）
     * @param cabin        【1.52】真实轿厢几何；{@code null} = 读不到（提示音回落旧的 1.5 格圆）
     */
    public record LiftView(long id, double x, double y, double z, float doorFraction, Move move,
                           Cabin cabin) {
    }

    // ------------------------------------------------------------------
    // 版本判定（只做一次）
    // ------------------------------------------------------------------

    private static boolean initialised;
    private static Version version = Version.NONE;

    /** MTR3：{@code mtr.client.ClientData.LIFTS}（public static Set）。 */
    private static Field mtr3LiftsField;
    /** MTR3：{@code mtr.data.Lift} 上的三个 protected 成员。 */
    private static Field mtr3DoorValueField;
    private static Method mtr3GetPositionX;
    private static Method mtr3GetPositionY;
    private static Method mtr3GetPositionZ;
    private static Method mtr3GetId;
    /**
     * 【1.44】{@code mtr.data.Lift.getLiftDirection()} —— 返回 MTR3 的
     * {@code mtr.data.Lift$LiftDirection}（{@code NONE/UP/DOWN}）。
     *
     * <p><b>拿不到时不致命</b>：它只服务「准备移动提示音」，而 {@link #bindMtr3} 里
     * 那些成员服务的是开关门提示音。所以这里**不**像其它成员那样 throw，
     * 而是留成 null 并打一条 WARN —— 免得将来 MTR 改个名字就把两个功能一起弄哑
     * （症状会是「开关门提示音明明好的，UP/DOWN 却没声音」，很难查）。
     */
    private static Method mtr3GetDirection;
    /** MTR3 门值的「**可见**全开」量级（= 24.0f；见 {@link #bindMtr3} 里的字节码依据）。 */
    private static float mtr3DoorFull = 1.0f;

    /** MTR4：{@code MinecraftClientData.getInstance()} 与 {@code liftWrapperList}。 */
    private static Method mtr4GetInstance;
    private static Field mtr4LiftWrapperList;
    /** MTR4：{@code LiftWrapper.getLift()}。 */
    private static Method mtr4WrapperGetLift;
    /** MTR4：{@code org.mtr.core.data.Lift} 上的公开方法。 */
    private static Method mtr4GetDoorValue;
    private static Method mtr4GetCurrentFloor;
    private static Method mtr4GetId;
    /**
     * 【1.44】{@code org.mtr.core.data.Lift.getDirection()} —— 返回 MTR4 的
     * {@code org.mtr.core.data.LiftDirection}（{@code NONE/UP/DOWN}）。
     * 拿不到时同 {@link #mtr3GetDirection}：只打 WARN、不 throw。
     */
    private static Method mtr4GetDirection;
    /** MTR4：{@code LiftFloor.getPosition()} 与 {@code Position.getX/Y/Z()}。 */
    private static Method mtr4FloorGetPosition;
    private static Method mtr4PosGetX;
    private static Method mtr4PosGetY;
    private static Method mtr4PosGetZ;

    // ------------------------------------------------------------------
    // 【1.52】真实轿厢几何（**可选增强**：读不到只让提示音回落旧的 1.5 格圆，绝不 throw）
    // ------------------------------------------------------------------

    /** MTR3：{@code mtr.data.Lift} 上的 6 个 **public 字段**（不是 getter，javap 实测）。 */
    private static Field mtr3LiftWidth;
    private static Field mtr3LiftDepth;
    private static Field mtr3LiftHeight;
    private static Field mtr3LiftOffsetX;
    private static Field mtr3LiftOffsetY;
    private static Field mtr3LiftOffsetZ;
    /** MTR3：{@code protected final java.util.List<BlockPos> floors}（没有公开遍历方法，只能拿字段）。 */
    private static Field mtr3FloorsField;
    /** MTR3：{@code net.minecraft.core.BlockPos.getY()}（楼层 y 要从 BlockPos 上取）。 */
    private static Method mtr3BlockPosGetY;

    /** MTR4：{@code org.mtr.core.data.Lift} 上的 6 个公开 getter。 */
    private static Method mtr4GetWidth;
    private static Method mtr4GetDepth;
    private static Method mtr4GetHeight;
    private static Method mtr4GetOffsetX;
    private static Method mtr4GetOffsetY;
    private static Method mtr4GetOffsetZ;
    /** MTR4：{@code iterateFloors(Consumer<LiftFloor>)} —— MTR4 只给遍历、不给列表本身。 */
    private static Method mtr4IterateFloors;

    /** 收集一条直梯的全部楼层 y（**复用一份实例**，避免每 tick 分配）。只在渲染线程上跑。 */
    private static final FloorYs FLOOR_YS = new FloorYs();

    /** 【1.52】轿厢几何是否绑齐。缺 → 提示音回落旧的 1.5 格圆（只诊断一次）。 */
    private static boolean cabinGeometryOk;

    /** 反射链路出问题时只打一条日志，之后彻底静默（避免每 tick 刷屏）。 */
    private static boolean broken;

    /**
     * 【1.52】楼层 y 收集器 —— 同时服务两个版本的两条不同路径：
     * MTR4 是「回调式」（{@link #mtr4IterateFloors} 把每个 {@code LiftFloor} 喂给 {@link #accept}），
     * MTR3 是「列表式」（拿到 {@code List<BlockPos>} 后自己遍历、直接 {@link #add}）。
     *
     * <p>用一份可复用的实例而不是每 tick new 一个 lambda，是因为这东西**每帧每条直梯都要用**。
     * 只在渲染线程（客户端 tick）上访问，无并发问题。
     */
    private static final class FloorYs implements Consumer<Object> {
        private final double[] ys = new double[64];
        private int count;
        /** {@code accept} 里要用到的两个取值方法，见 {@link #begin}。 */
        private Method positionGetter;
        private Method yGetter;

        void reset() {
            count = 0;
        }

        /** MTR4 用：告诉 {@code accept} 怎么从一个 {@code LiftFloor} 上取出 y。 */
        void begin(Method positionGetter, Method yGetter) {
            this.positionGetter = positionGetter;
            this.yGetter = yGetter;
            this.count = 0;
        }

        void add(double y) {
            if (count < ys.length && !Double.isNaN(y)) {
                ys[count++] = y;
            }
        }

        @Override
        public void accept(Object floor) {
            if (floor == null || positionGetter == null || yGetter == null) {
                return;
            }
            try {
                Object position = positionGetter.invoke(floor);
                if (position != null) {
                    add(((Number) yGetter.invoke(position)).doubleValue());
                }
            } catch (Throwable ignored) {
                // 单个楼层读失败就跳过它 —— 少一层只影响容差，不影响功能
            }
        }

        /**
         * 相邻楼层的**最小**竖直间距；不足两层 / 全读失败 → 0（调用方据此**不做**竖直判定）。
         *
         * <p>不排序、直接 O(n²) 取最小非零差：层数是个位数，省掉每 tick 的一次数组拷贝。
         */
        double spacing() {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    double d = Math.abs(ys[i] - ys[j]);
                    if (d > 1.0e-6 && d < best) {
                        best = d;
                    }
                }
            }
            return best == Double.MAX_VALUE ? 0.0 : best;
        }
    }

    private MtrLiftAccess() {
    }

    /** 当前 MTR 版本（首次调用时探测，之后缓存）。 */
    public static Version version() {
        if (!initialised) {
            initialised = true;
            version = detect();
            if (version != Version.NONE) {
                LOGGER.info("[SmoothLift/LiftChime] 检测到 {} —— 直梯开关门提示音可用", version);
            } else {
                LOGGER.info("[SmoothLift/LiftChime] 没有检测到 MTR 直梯"
                        + "（既没有 mtr.data.Lift 也没有 org.mtr.core.data.Lift），直梯提示音不工作");
            }
        }
        return version;
    }

    private static Version detect() {
        if (classPresent("org.mtr.core.data.Lift")) {
            try {
                bindMtr4();
                return Version.MTR4;
            } catch (Throwable t) {
                LOGGER.warn("[SmoothLift/LiftChime] 找到 MTR4 的直梯类但反射绑定失败：{}", t.toString());
                broken = true;
                return Version.NONE;
            }
        }
        if (classPresent("mtr.data.Lift")) {
            try {
                bindMtr3();
                return Version.MTR3;
            } catch (Throwable t) {
                LOGGER.warn("[SmoothLift/LiftChime] 找到 MTR3 的直梯类但反射绑定失败：{}", t.toString());
                broken = true;
                return Version.NONE;
            }
        }
        return Version.NONE;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, MtrLiftAccess.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 拿一个成员的「声明类」上的 public 成员（含继承）；找不到返回 null。 */
    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field fieldInHierarchy(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field f = owner.getField(name);
        f.setAccessible(true);
        return f;
    }

    private static void bindMtr3() throws Exception {
        Class<?> clientData = Class.forName("mtr.client.ClientData");
        mtr3LiftsField = staticField(clientData, "LIFTS");

        Class<?> lift = Class.forName("mtr.data.Lift");
        // doorValue / doorOpen 都是 protected，没有公开 getter，只能拿字段。
        mtr3DoorValueField = fieldInHierarchy(lift, "doorValue");
        if (mtr3DoorValueField == null) {
            throw new NoSuchFieldException("mtr.data.Lift.doorValue");
        }
        mtr3GetPositionX = method(lift, "getPositionX");
        mtr3GetPositionY = method(lift, "getPositionY");
        mtr3GetPositionZ = method(lift, "getPositionZ");
        if (mtr3GetPositionX == null || mtr3GetPositionY == null || mtr3GetPositionZ == null) {
            throw new NoSuchMethodException("mtr.data.Lift.getPositionX/Y/Z");
        }
        // id 在父类 NameColorDataBase 上（public long id）。
        mtr3GetId = method(lift, "getId");
        // 【1.44】方向：public getLiftDirection()。**可缺**（缺了只影响「准备移动提示音」）。
        mtr3GetDirection = method(lift, "getLiftDirection");
        if (mtr3GetDirection == null) {
            LOGGER.warn("[SmoothLift/LiftChime] mtr.data.Lift 上没有 getLiftDirection()，"
                    + "「准备向上/向下移动」的 up.ogg / down.ogg 不会响"
                    + "（开关门提示音不受影响）—— 多半是 MTR 改了方法名，需要更新本模组");
        }
        // 全开值 = 24.0f（**不是** 48.0f）。这不是猜的，是 mtr.data.LiftClient.tickClient 的
        // 字节码：它把门交给渲染器时算的就是
        //     renderLift(..., frontCanOpen ? Math.min(doorValue / 24.0f, 1.0f) : 0.0f, ...)
        // 即**可见开合度 = min(doorValue / 24, 1)**（world 侧的 checkDoor 同口径：
        // setOpen(min(round(doorValue), 24))，DOOR_MAX 也正好是 24）。
        // ⇒ doorValue 的 0..48 里，[0, 24] 是**看得见**的开合过程，[24, 48] 是**门已全开、
        //    但数值继续走的空档**（开关门各 1.2 秒看不见动作）。
        // ★ 必须除以 24 而不是 48：若除以 48，门在 48→24 这段「空档」里 fraction 就从 1.0 掉下来，
        //   关门提示音会比**门真的开始动**早整整 24 tick（1.2 秒）响起；除以 24 则
        //   「fraction 离开 1.0」正好等于「门开始可见地关」、「fraction 离开 0」正好等于「门开始
        //   可见地开」，与 MTR4 的 getDoorValue()（本来就是可见开合度 0..1）**语义对齐**。
        mtr3DoorFull = 24.0f;

        // 【1.52】轿厢几何 + 楼层表：**可选增强**，读不到只 WARN 一次
        //   （提示音回落旧的 1.5 格圆），绝不让它把整个直梯功能弄哑。
        //   ★ 这里不能像上面几项那样 throw —— `bindMtr3` 抛异常 = broken = 提示音**全部静音**。
        bindCabinGeometry(true);
    }

    private static void bindMtr4() throws Exception {
        Class<?> mcd = Class.forName("org.mtr.mod.client.MinecraftClientData");
        mtr4GetInstance = method(mcd, "getInstance");
        mtr4LiftWrapperList = staticFieldIn(mcd, "liftWrapperList");
        if (mtr4GetInstance == null || mtr4LiftWrapperList == null) {
            throw new NoSuchMethodException("MinecraftClientData.getInstance/liftWrapperList");
        }
        mtr4LiftWrapperList.setAccessible(true);

        Class<?> wrapper = Class.forName("org.mtr.mod.client.MinecraftClientData$LiftWrapper");
        mtr4WrapperGetLift = method(wrapper, "getLift");
        if (mtr4WrapperGetLift == null) {
            throw new NoSuchMethodException("LiftWrapper.getLift");
        }

        Class<?> lift = Class.forName("org.mtr.core.data.Lift");
        mtr4GetDoorValue = method(lift, "getDoorValue");
        mtr4GetCurrentFloor = method(lift, "getCurrentFloor");
        if (mtr4GetDoorValue == null || mtr4GetCurrentFloor == null) {
            throw new NoSuchMethodException("org.mtr.core.data.Lift.getDoorValue/getCurrentFloor");
        }
        mtr4GetId = method(lift, "getId");
        // 【1.44】方向：public getDirection()。**可缺**，理由同 MTR3 那边。
        mtr4GetDirection = method(lift, "getDirection");
        if (mtr4GetDirection == null) {
            LOGGER.warn("[SmoothLift/LiftChime] org.mtr.core.data.Lift 上没有 getDirection()，"
                    + "「准备向上/向下移动」的 up.ogg / down.ogg 不会响"
                    + "（开关门提示音不受影响）—— 多半是 MTR 改了方法名，需要更新本模组");
        }

        Class<?> floor = Class.forName("org.mtr.core.data.LiftFloor");
        mtr4FloorGetPosition = method(floor, "getPosition");
        Class<?> position = Class.forName("org.mtr.core.data.Position");
        mtr4PosGetX = method(position, "getX");
        mtr4PosGetY = method(position, "getY");
        mtr4PosGetZ = method(position, "getZ");
        if (mtr4FloorGetPosition == null || mtr4PosGetX == null || mtr4PosGetY == null
                || mtr4PosGetZ == null) {
            throw new NoSuchMethodException("LiftFloor.getPosition/Position.getX/Y/Z");
        }

        // 【1.52】轿厢几何 + 楼层遍历：同上，**可选增强**，失败只 WARN。
        bindCabinGeometry(false);
    }

    /**
     * 【1.52】绑「真实轿厢几何」。
     *
     * <p><b>全部成员都是可缺的</b>：任一项拿不到就 {@link #cabinGeometryOk} = false，
     * 快照里 {@code cabin} 给 {@code null}，提示音自动回落旧的「以楼层方块为心、半径 1.5 格的圆」。
     * 因此这里**一律不 throw**（throw 会把 {@code broken} 置位、把提示音全弄哑，得不偿失）。
     */
    private static void bindCabinGeometry(boolean mtr3) {
        try {
            if (mtr3) {
                Class<?> lift = Class.forName("mtr.data.Lift");
                // MTR3 这六个是 **public int 字段**（没有 getter，javap 实测）。
                mtr3LiftWidth = fieldInHierarchy(lift, "liftWidth");
                mtr3LiftDepth = fieldInHierarchy(lift, "liftDepth");
                mtr3LiftHeight = fieldInHierarchy(lift, "liftHeight");
                mtr3LiftOffsetX = fieldInHierarchy(lift, "liftOffsetX");
                mtr3LiftOffsetY = fieldInHierarchy(lift, "liftOffsetY");
                mtr3LiftOffsetZ = fieldInHierarchy(lift, "liftOffsetZ");
                // 楼层表是 protected 字段（MTR3 没有 iterateFloors）；楼层 y 要从 BlockPos 上取。
                mtr3FloorsField = fieldInHierarchy(lift, "floors");
                mtr3BlockPosGetY = method(Class.forName("net.minecraft.core.BlockPos"), "getY");
                cabinGeometryOk = mtr3LiftWidth != null && mtr3LiftDepth != null
                        && mtr3LiftHeight != null && mtr3LiftOffsetX != null
                        && mtr3LiftOffsetZ != null;
            } else {
                Class<?> lift = Class.forName("org.mtr.core.data.Lift");
                mtr4GetWidth = method(lift, "getWidth");
                mtr4GetDepth = method(lift, "getDepth");
                mtr4GetHeight = method(lift, "getHeight");
                mtr4GetOffsetX = method(lift, "getOffsetX");
                mtr4GetOffsetY = method(lift, "getOffsetY");
                mtr4GetOffsetZ = method(lift, "getOffsetZ");
                // MTR4 只给「遍历」不给列表：iterateFloors(Consumer<LiftFloor>)。
                mtr4IterateFloors = method(lift, "iterateFloors", Consumer.class);
                cabinGeometryOk = mtr4GetWidth != null && mtr4GetDepth != null
                        && mtr4GetHeight != null && mtr4GetOffsetX != null
                        && mtr4GetOffsetZ != null;
            }
        } catch (Throwable t) {
            cabinGeometryOk = false;
        }
        if (!cabinGeometryOk) {
            LOGGER.warn("[SmoothLift/LiftChime] 读不到 MTR 的轿厢尺寸（{}）⇒ 直梯提示音"
                    + "「在不在轿厢里」的判定回落旧的 1.5 格圆：宽轿厢里走动时音量仍可能一跳一跳。"
                    + "开关门 / 向上向下提示音本身不受影响", mtr3 ? "MTR3" : "MTR4");
        }
    }

    /** 实例字段可能声明在父类上，逐级找。 */
    private static Field staticFieldIn(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 取当前世界里所有直梯的只读快照。没有 MTR / 反射坏了 / 集合为空时返回空列表。
     *
     * <p>失败一律**吞掉并降级**：直梯提示音是锦上添花的功能，绝不能因为它把一个
     * 反射异常抛到客户端 tick 里（那会每分钟刷几百条崩溃日志）。
     *
     * @param level 客户端世界（只用来做一次空值判断；MTR 的集合本身就是全局的）
     */
    public static List<LiftView> snapshot(Level level) {
        if (level == null || broken || version() == Version.NONE) {
            return List.of();
        }
        try {
            return version == Version.MTR4 ? snapshotMtr4() : snapshotMtr3();
        } catch (Throwable t) {
            broken = true;
            LOGGER.warn("[SmoothLift/LiftChime] 读取直梯数据失败，本功能已停用：{}", t.toString());
            return List.of();
        }
    }

    /**
     * 把一个「装着直梯的容器」变成可 for-each 的视图。
     *
     * <p><b>为什么不能直接 {@code instanceof Iterable}</b>（★ 这里真踩过，症状是「直梯提示音完全没声音」）：
     * 两个版本给的容器类型根本不是一类东西 ——
     * <pre>
     *   MTR3  mtr.client.ClientData.LIFTS        : java.util.Set&lt;LiftClient&gt;            → 是 Iterable ✓
     *   MTR4  MinecraftClientData.liftWrapperList: fastutil Long2ObjectAVLTreeMap      → 不是 Iterable ✗
     * </pre>
     * MTR4 那个是 <b>fastutil 的 Map</b>（{@code Long2ObjectAVLTreeMap} → {@code AbstractLong2ObjectSortedMap}
     * → {@code AbstractLong2ObjectMap} → {@code AbstractLong2ObjectFunction} → {@code Long2ObjectFunction}
     * → {@code fastutil.Function} → {@code java.util.function.Function}，外加 {@code java.util.Map}），
     * 整条继承链里**没有 {@code java.lang.Iterable}**。于是 MTR4 上判定永远失败、{@code snapshot()}
     * 永远返回空表 ⇒ 播放器以为「世界里一条直梯都没有」⇒ 一声不响（MTR3 反而是好的，
     * 所以这个 bug 只在 MTR4 上出现，最容易被误当成「MTR4 不兼容」）。
     *
     * <p>它确实是 {@link java.util.Map}，所以取 {@code values()} 即可（返回 {@code Collection}，
     * 天然是 {@code Iterable}）；MTR3 那种 {@code Set} 直接就是 {@code Iterable}。
     * 两种形态都兜住，将来 MTR 换成别的容器形态也不会再默默变成空表。
     *
     * @return 可迭代视图；形态完全无法识别时返回 {@code null}（调用方会打一条诊断日志）
     */
    private static Iterable<?> elementsOf(Object raw) {
        if (raw instanceof java.util.Map<?, ?> map) {
            return map.values();
        }
        if (raw instanceof Iterable<?> iterable) {
            return iterable;
        }
        return null;
    }

    /** 「容器形态不认识」这种故障只提示一次，避免每 tick 刷屏。 */
    private static boolean warnedCollectionShape;

    /** 首次成功读到直梯集合时打一条（只打一次）：日志里能一眼看出容器是哪种形态。 */
    private static boolean loggedCollectionShape;

    private static void noteCollectionShape(String what, Object raw) {
        if (loggedCollectionShape) {
            return;
        }
        loggedCollectionShape = true;
        LOGGER.info("[SmoothLift/LiftChime] 直梯集合 {} 读到 {}（可迭代视图已建立）",
                what, raw == null ? "null" : raw.getClass().getName());
    }

    private static void warnCollectionShape(String what, Object raw) {
        if (warnedCollectionShape) {
            return;
        }
        warnedCollectionShape = true;
        LOGGER.warn("[SmoothLift/LiftChime] {} 的容器类型无法识别（期望 Map 或 Iterable，实际 {}），"
                        + "直梯提示音不会出声 —— 多半是 MTR 换了数据结构，需要更新本模组",
                what, raw == null ? "null" : raw.getClass().getName());
    }

    private static List<LiftView> snapshotMtr3() throws Exception {
        Object raw = mtr3LiftsField.get(null);
        Iterable<?> lifts = elementsOf(raw);
        if (lifts == null) {
            warnCollectionShape("mtr.client.ClientData.LIFTS", raw);
            return List.of();
        }
        noteCollectionShape("mtr.client.ClientData.LIFTS", raw);
        List<LiftView> out = new ArrayList<>();
        for (Object lift : lifts) {
            if (lift == null) {
                continue;
            }
            Object doorRaw = mtr3DoorValueField.get(lift);
            float doorValue = doorRaw instanceof Float f ? f : 0.0f;
            double x = (Double) mtr3GetPositionX.invoke(lift);
            double y = (Double) mtr3GetPositionY.invoke(lift);
            double z = (Double) mtr3GetPositionZ.invoke(lift);
            out.add(new LiftView(
                    idOf(lift, mtr3GetId, out.size()),
                    x,
                    y,
                    z,
                    fraction(doorValue, mtr3DoorFull),
                    directionOf(lift, mtr3GetDirection),
                    cabinMtr3(lift, x, y, z)));
        }
        return out;
    }

    /**
     * 【1.52】MTR3 的真实轿厢几何：{@code public int liftWidth/liftDepth/liftHeight/liftOffset*}
     * ＋ {@code protected List<BlockPos> floors}（算竖直容差用）。任何一步失败 → {@code null}。
     */
    private static Cabin cabinMtr3(Object lift, double floorX, double floorY, double floorZ) {
        if (!cabinGeometryOk) {
            return null;
        }
        try {
            double halfWidth = intOf(mtr3LiftWidth, lift) / 2.0;
            double halfDepth = intOf(mtr3LiftDepth, lift) / 2.0;
            double height = intOf(mtr3LiftHeight, lift);
            if (!(halfWidth > 0.0) || !(halfDepth > 0.0) || !(height > 0.0)) {
                return null;
            }
            return new Cabin(
                    floorX + intOf(mtr3LiftOffsetX, lift),
                    floorZ + intOf(mtr3LiftOffsetZ, lift),
                    floorY + intOf(mtr3LiftOffsetY, lift),
                    halfWidth, halfDepth, height, spacingMtr3(lift));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** MTR3 的相邻楼层最小间距（{@code List<BlockPos> floors} 里的 y 两两比）。读不到 → 0。 */
    private static double spacingMtr3(Object lift) {
        if (mtr3FloorsField == null || mtr3BlockPosGetY == null) {
            return 0.0;
        }
        try {
            // 这个字段的声明就是 java.util.List<BlockPos>（javap 实测）⇒ 直接按声明转。
            //   ★ 刻意**不**写成「raw instanceof Iterable」：`elementsOf` 那条
            //     「容器的形态绝不能直接判 Iterable」的回归断言是**全文扫 token** 的，
            //     这里再来一次会同名误伤（那边要防的容器是 fastutil Map，判了必空）。
            List<?> floors = (List<?>) mtr3FloorsField.get(lift);
            if (floors == null) {
                return 0.0;
            }
            FLOOR_YS.reset();
            for (Object floor : floors) {
                if (floor != null && mtr3BlockPosGetY.invoke(floor) instanceof Number n) {
                    FLOOR_YS.add(n.doubleValue());
                }
            }
            return FLOOR_YS.spacing();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static List<LiftView> snapshotMtr4() throws Exception {
        Object instance = mtr4GetInstance.invoke(null);
        if (instance == null) {
            return List.of();
        }
        Object raw = mtr4LiftWrapperList.get(instance);
        Iterable<?> wrappers = elementsOf(raw);
        if (wrappers == null) {
            warnCollectionShape("MinecraftClientData.liftWrapperList", raw);
            return List.of();
        }
        noteCollectionShape("MinecraftClientData.liftWrapperList", raw);
        List<LiftView> out = new ArrayList<>();
        for (Object wrapper : wrappers) {
            if (wrapper == null) {
                continue;
            }
            Object lift = mtr4WrapperGetLift.invoke(wrapper);
            if (lift == null) {
                continue;
            }
            Object floor = mtr4GetCurrentFloor.invoke(lift);
            if (floor == null) {
                continue;
            }
            Object position = mtr4FloorGetPosition.invoke(floor);
            if (position == null) {
                continue;
            }
            float doorValue = (Float) mtr4GetDoorValue.invoke(lift);
            double x = ((Number) mtr4PosGetX.invoke(position)).doubleValue();
            double y = ((Number) mtr4PosGetY.invoke(position)).doubleValue();
            double z = ((Number) mtr4PosGetZ.invoke(position)).doubleValue();
            out.add(new LiftView(
                    idOf(lift, mtr4GetId, out.size()),
                    x,
                    y,
                    z,
                    fraction(doorValue, 1.0f),
                    directionOf(lift, mtr4GetDirection),
                    cabinMtr4(lift, x, y, z)));
        }
        return out;
    }

    /**
     * 【1.52】MTR4 的真实轿厢几何：{@code getWidth/getDepth/getHeight/getOffsetX/Y/Z}
     * ＋ {@code iterateFloors}（算竖直容差用）。任何一步失败 → {@code null}。
     *
     * <p>{@code getHeight()} 就是**轿厢高度**（同类渲染器 {@code RenderLifts} 就是拿
     * height/width/depth 搭轿厢那个盒子的，javap 实测），不是竖井总高。
     */
    private static Cabin cabinMtr4(Object lift, double floorX, double floorY, double floorZ) {
        if (!cabinGeometryOk) {
            return null;
        }
        try {
            double halfWidth = ((Number) mtr4GetWidth.invoke(lift)).doubleValue() / 2.0;
            double halfDepth = ((Number) mtr4GetDepth.invoke(lift)).doubleValue() / 2.0;
            double height = ((Number) mtr4GetHeight.invoke(lift)).doubleValue();
            if (!(halfWidth > 0.0) || !(halfDepth > 0.0) || !(height > 0.0)) {
                return null;
            }
            return new Cabin(
                    floorX + numOf(mtr4GetOffsetX, lift),
                    floorZ + numOf(mtr4GetOffsetZ, lift),
                    floorY + numOf(mtr4GetOffsetY, lift),
                    halfWidth, halfDepth, height, spacingMtr4(lift));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** MTR4 的相邻楼层最小间距（借 {@code iterateFloors} 把每个 {@code LiftFloor} 喂给收集器）。 */
    private static double spacingMtr4(Object lift) {
        if (mtr4IterateFloors == null || mtr4FloorGetPosition == null || mtr4PosGetY == null) {
            return 0.0;
        }
        try {
            FLOOR_YS.begin(mtr4FloorGetPosition, mtr4PosGetY);
            mtr4IterateFloors.invoke(lift, FLOOR_YS);
            return FLOOR_YS.spacing();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /** 读一个 public int 字段（缺 → 0）。 */
    private static double intOf(Field field, Object owner) throws IllegalAccessException {
        return field == null ? 0.0 : ((Number) field.get(owner)).doubleValue();
    }

    /** 调一个 double getter（缺 → 0）。 */
    private static double numOf(Method getter, Object owner) throws Exception {
        return getter == null ? 0.0 : ((Number) getter.invoke(owner)).doubleValue();
    }

    /** 拿直梯 ID；拿不到就退化成「这一帧里的第几个」（只在同一次比较内自洽，够用）。 */
    private static long idOf(Object lift, Method getId, int fallbackIndex) {
        if (getId != null) {
            try {
                Object id = getId.invoke(lift);
                if (id instanceof Number n) {
                    return n.longValue();
                }
            } catch (Throwable ignored) {
                // 退化到 fallback
            }
        }
        return -1L - fallbackIndex;
    }

    /** 把门值按本版本的全开值归一化到 [0, 1]（全关 0、全开 1）。 */
    private static float fraction(float doorValue, float full) {
        if (!(full > 0.0f) || Float.isNaN(doorValue)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, doorValue / full));
    }

    /**
     * 【1.44】读一条直梯「打算往哪走」。方法缺失、抛异常、或返回的名字不认识 → {@link Move#NONE}。
     *
     * <p>失败一律降级成「停着」：这样最多是**该响的时候没响**（用户能察觉并反馈），
     * 而不会变成「按错方向响」（那听起来像模组坏了，反而更难定位）。
     */
    private static Move directionOf(Object lift, Method getDirection) {
        if (getDirection == null) {
            return Move.NONE;
        }
        try {
            return Move.of(getDirection.invoke(lift));
        } catch (Throwable ignored) {
            return Move.NONE;
        }
    }
}
