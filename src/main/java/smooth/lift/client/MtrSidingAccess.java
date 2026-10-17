package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 【1.57】「石斧右键的那条**侧线铁轨**属于哪一条侧线」—— 只读、可降级的 MTR 跨版本访问层。
 *
 * <h2>为什么要有这个类</h2>
 *
 * 用户点名：列车音效界面要**按侧线**设置 ——<b>「ui 只修改石斧右键的侧线内的所有列车音效
 * ……侧线 A 有 5 列车在运行，设置这条侧线音效后这 5 辆车同时应用改动。但是旁边的侧线 B
 * 和侧线 B 正在运行的列车不受影响」</b>。
 * 所以界面必须知道「我这一份设置是给哪条侧线的」—— 也就是**侧线的身份**。
 *
 * <p>这个身份只能是 MTR 自己的：屏幕上的黄色只说明「这是一段侧线铁轨」，
 * 真正「哪条侧线 / 里面有哪几列车」在 MTR 的数据里（MTR4 {@code org.mtr.core.data.Siding}
 * 持有 {@code vehicles}；MTR3 {@code mtr.data.Siding} 持有 {@code trains}）。本类把那个 id 读出来。
 *
 * <h2>★ 两代 MTR 是两套完全不同的类（用户报「MTR3 打不开界面」的根因）</h2>
 *
 * <pre>
 *                                  MTR 3（Fabric 1.20.1 / 3.2.2）            MTR 4（Fabric 1.20.4 / 4.0.5）
 *   客户端数据容器                  mtr.client.ClientData（全静态字段）       MinecraftClientData.getInstance()
 *   侧线集合                        ClientData.SIDINGS  Set&lt;Siding&gt;          sidingIdMap
 *   轨道集合                        ClientData.RAILS    Map&lt;BlockPos,
 *                                                       Map&lt;BlockPos,Rail&gt;&gt;  positionsToRail
 *   「面向的轨道」                  **没有**这个 API，本类自己算                MinecraftClientData.getFacingRailAndBlockPos(boolean)
 *   轨道是不是侧线                  rail.railType == RailType.SIDING         Rail.isSiding()（读布尔字段）
 *   轨道两个端点                    Rail 上没有端点，只能从 RAILS 的键取       Rail.getPosition1()/getPosition2()
 *   保存轨道里含不含某格            SavedRailBase.containsPos(BlockPos)      SavedRailBase.containsPos(Position)
 *   侧线 id                         NameColorDataBase.id（public long 字段）   NameColorDataBase.getId()
 * </pre>
 *
 * 所以本类按 {@code MtrLiftAccess} 同款做法做**版本判定**：认到哪一代就走哪一代的绑定，
 * 一代绑定失败不影响另一代（{@link Version}）。MTR3 那一条路之前整段缺失 ⇒
 * {@code facingSidingKey} 一直返回 {@link #NO_SIDING} ⇒ 石斧右键侧线节点**什么都不发生**
 * （症状正是用户报的「MTR3 里打不开、MTR4 可以」）。
 *
 * <h2>判据不是猜的：都已反汇编核实</h2>
 *
 * <h3>两代通用</h3>
 * <ol>
 *   <li><b>「黄色」= 侧线铁轨。</b> MTR3 {@code mtr.data.RailType.SIDING}、MTR4
 *       {@code org.mtr.mod.data.RailType.SIDING} 构造时都传黄色（速度上限 40、{@code hasSavedRail=true}）；
 *       站台轨道 {@code PLATFORM} 是红色。⇒ 「就是黄色的那个」说的就是侧线铁轨。</li>
 *   <li><b>一条侧线 = 一段轨道，认亲判据是「两个端点都落在这条侧线里」。</b>
 *       MTR4 {@code Rail.checkOrCreateSavedRail}、MTR3 {@code RailwayData.addRail} 的字节码里
 *       都是「在现成的侧线里找 {@code containsPos(p1) && containsPos(p2)} 的那一条，找不到才 new」。
 *       ⇒ 本类照抄这条判据，不自己编距离阈值。</li>
 * </ol>
 *
 * <h3>MTR4 路径（原样保留，用户已确认可用）</h3>
 * <p>{@code MinecraftClientData.getFacingRailAndBlockPos(false)} 自己做「准星落在 BlockNode 上 →
 * 取该节点上的轨道、按 {@code yaw+90} 与 {@code atan2(dz,dx)} 夹角取最小者」。
 *
 * <h3>MTR3 路径（本次新增）</h3>
 * <ol>
 *   <li><b>面向的轨道自己算。</b> MTR3 没有 {@code getFacingRailAndBlockPos}，所以照 MTR4 那条
 *       反汇编出来的算法复刻：遍历 {@code ClientData.RAILS}，对每个「一端是右键那格」的轨道，
 *       算 {@code Math.abs(toDegrees(atan2(dz, dx)) - (yaw + 90)) % 360}（&gt;180 取补角），
 *       取最小者；缆车轨道（{@code CABLE_CAR} / {@code CABLE_CAR_STATION}）跳过，
 *       与 MTR4 的 {@code includeCable=false} 同义。</li>
 *   <li><b>端点从 RAILS 的键取。</b> MTR3 的 {@code mtr.data.Rail} 没有 {@code getPosition1/2}
 *       （javap 核实过），轨道两端就是 {@code RAILS} 这层嵌套 Map 的两个键。
 *       ★ 而且 {@code RailwayData.addRail} 只写单向（{@code rails.get(p1).put(p2, rail)}），
 *       所以本类**两个方向都扫**（键里任意一端等于右键那格就算命中），
 *       否则从「尾端」那一格右键会一条都找不到。</li>
 *   <li><b>门禁是 {@code rail.railType == RailType.SIDING}。</b> MTR3 的 {@code Rail} 没有
 *       {@code isSiding()}，侧线身份就在 public 字段 {@code railType} 上。</li>
 * </ol>
 *
 * <h2>★ 认不到侧线数据时**不开界面**（而不是退化成另一个身份）</h2>
 *
 * 退化成「按轨道自己的 hash 认」会让**同一条侧线有两个身份**（【1.28】踩过的静默分桶）。
 * 所以这里的选择是：**认不到就返回 {@link #NO_SIDING}，宁可不打开**，只打一条日志说明差在哪
 * （可恢复、可诊断），不做「看起来能用但会分叉」的降级。
 *
 * <p>绑定失败是**正常路径**（没装 MTR / 版本不认识 / 被别人换了实现）：整层保持 null，
 * {@link #facingSidingKey(BlockPos)} 一直返回 {@link #NO_SIDING}，只打一次 info，绝不影响其它功能。
 */
public final class MtrSidingAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 「认不到侧线」的哨兵。
     *
     * <p>★ 用 {@link Long#MIN_VALUE} 而不是 {@code -1L}：本项目的身份普遍是
     * {@code BlockPos.asLong()} 这类**位拼**结果，{@code -1L} 是一个**合法**取值
     * （【1.26】的 {@code NO_BROADCAST_RUN} 就是为这件事从 {@code -1L} 改成 {@code Long.MIN_VALUE}）。
     */
    public static final long NO_SIDING = Long.MIN_VALUE;

    /** MTR 版本（首次调用时探测，之后缓存）。 */
    public enum Version {
        /** 没装 MTR，或版本不认识 —— 列车音效界面没有入口。 */
        NONE,
        /** MTR 3.x（{@code mtr.client.ClientData} 那一代）。 */
        MTR3,
        /** MTR 4.x（{@code org.mtr.mod.client.MinecraftClientData} 那一代）。 */
        MTR4
    }

    private static boolean initialised;
    private static Version version = Version.NONE;

    // ------------------------------------------------------------------
    // MTR4（{@code org.mtr.*}）—— 原样保留
    // ------------------------------------------------------------------

    private static Method m4GetInstance;
    private static Field m4SidingIdMapField;
    private static Method m4GetFacingRailAndBlockPos;
    private static Method m4RailIsSiding;
    private static Method m4RailGetPosition1;
    private static Method m4RailGetPosition2;
    private static Method m4RailGetHexId;
    private static Method m4SidingContainsPos;
    private static Method m4SidingGetId;

    // ------------------------------------------------------------------
    // MTR3（{@code mtr.*}）—— 本次新增
    // ------------------------------------------------------------------

    /** MTR3：{@code mtr.client.ClientData.RAILS}（public static Map）。 */
    private static Field m3RailsField;
    /** MTR3：{@code mtr.client.ClientData.SIDINGS}（public static Set）。 */
    private static Field m3SidingsField;
    /** MTR3：{@code mtr.data.Rail.railType}（public 字段）。 */
    private static Field m3RailTypeField;
    /** MTR3：{@code mtr.data.RailType.SIDING} 这个枚举常量本体（用身份比较）。 */
    private static Object m3SidingType;
    /** MTR3：{@code mtr.data.SavedRailBase.containsPos(BlockPos)}。 */
    private static Method m3ContainsPos;
    /** MTR3：{@code mtr.data.NameColorDataBase.id}（public long 字段）。 */
    private static Field m3IdField;
    /** MTR3：缆车轨道两型（与 MTR4 的 {@code includeCable=false} 同义，跳过）。可缺。 */
    private static Object m3CableCar;
    private static Object m3CableCarStation;

    /** 日志降噪：整层失败只报一次 / 认不到侧线只报一次。 */
    private static boolean warnedNoData;
    private static boolean warnedNoMatch;

    private MtrSidingAccess() {
    }

    /** 当前 MTR 版本（首次调用时探测，之后缓存）。 */
    public static Version version() {
        if (!initialised) {
            initialised = true;
            version = detect();
            if (version == Version.NONE) {
                LOGGER.info("[SmoothLift/TrainSound] 没认到 MTR 侧线数据层（MTR3 找 mtr.client.ClientData、"
                        + "MTR4 找 org.mtr.mod.client.MinecraftClientData 都没成），"
                        + "列车音效界面暂时没有入口（其它功能照常）");
            } else {
                LOGGER.info("[SmoothLift/TrainSound] 已接上 MTR {} 侧线数据 —— "
                        + "石斧右键侧线铁轨按「MTR 侧线 id」给列车音效分桶", version);
            }
        }
        return version;
    }

    private static Version detect() {
        // MTR4 先判：两代的「客户端数据容器」类名完全不重合，判定不会歧义。
        if (classPresent("org.mtr.mod.client.MinecraftClientData")) {
            try {
                bindMtr4();
                return Version.MTR4;
            } catch (Throwable t) {
                LOGGER.warn("[SmoothLift/TrainSound] 找到 MTR4 的侧线类但反射绑定失败：{}", t.toString());
                return Version.NONE;
            }
        }
        if (classPresent("mtr.client.ClientData")) {
            try {
                bindMtr3();
                return Version.MTR3;
            } catch (Throwable t) {
                LOGGER.warn("[SmoothLift/TrainSound] 找到 MTR3 的侧线类但反射绑定失败：{}", t.toString());
                return Version.NONE;
            }
        }
        return Version.NONE;
    }

    /**
     * 玩家准星面向的那条轨道所属**侧线**的 id；认不到返回 {@link #NO_SIDING}。
     *
     * @param clickedPos 石斧右键到的 {@code mtr:rail} 轨道节点那一格（MTR3 自己算「面向哪段」时要它；
     *                   MTR4 那条路读准星，用不到这个参数，传 null 也无妨）
     *
     * <p>调用点只该在「石斧右键 {@code mtr:rail}」那一个回调里
     * （它读的是**准星**，和右键落点天然是同一格）。
     */
    public static long facingSidingKey(BlockPos clickedPos) {
        return switch (version()) {
            case MTR4 -> facingSidingKeyMtr4();
            case MTR3 -> facingSidingKeyMtr3(clickedPos);
            default -> NO_SIDING;
        };
    }

    // ==================================================================
    // MTR4 路径
    // ==================================================================

    private static long facingSidingKeyMtr4() {
        try {
            Object data = m4GetInstance.invoke(null);
            if (data == null) {
                return NO_SIDING;
            }
            Object pair = m4GetFacingRailAndBlockPos.invoke(data, false);
            if (pair == null) {
                return NO_SIDING;
            }
            Object rail = firstOf(pair);
            if (rail == null) {
                return NO_SIDING;
            }
            // ★ 门禁：面向的必须**是侧线铁轨**（渲染成黄色的那一种），否则不开界面。
            if (!Boolean.TRUE.equals(m4RailIsSiding.invoke(rail))) {
                return NO_SIDING;
            }
            Object p1 = m4RailGetPosition1.invoke(rail);
            Object p2 = m4RailGetPosition2.invoke(rail);
            if (p1 == null || p2 == null) {
                return NO_SIDING;
            }
            Object map = m4SidingIdMapField.get(data);
            Iterable<?> sidings = elementsOf(map);
            if (sidings == null) {
                return NO_SIDING;
            }
            int seen = 0;
            for (Object siding : sidings) {
                if (siding == null) {
                    continue;
                }
                seen++;
                // 与 MTR 自己的判据逐字一致：两个端点都要落在这条侧线里。
                if (Boolean.TRUE.equals(m4SidingContainsPos.invoke(siding, p1))
                        && Boolean.TRUE.equals(m4SidingContainsPos.invoke(siding, p2))) {
                    return ((Number) m4SidingGetId.invoke(siding)).longValue();
                }
            }
            // 有侧线数据、但没一条认领这条轨道 —— 值得报一次（宁可查得到，也不要静默不开）。
            if (!warnedNoMatch) {
                warnedNoMatch = true;
                LOGGER.info("[SmoothLift/TrainSound] (MTR4) 面向的轨道是侧线铁轨，但{}条侧线里没有一条"
                        + "同时包含它的两个端点（轨道 {}）⇒ 这次不开界面", seen, hexIdOfMtr4(rail));
            }
            return NO_SIDING;
        } catch (Throwable t) {
            warnOnce(t);
            return NO_SIDING;
        }
    }

    // ==================================================================
    // MTR3 路径
    // ==================================================================

    private static long facingSidingKeyMtr3(BlockPos clickedPos) {
        if (clickedPos == null) {
            return NO_SIDING;
        }
        try {
            Object railsRaw = m3RailsField.get(null);
            if (!(railsRaw instanceof Map<?, ?> rails) || rails.isEmpty()) {
                return NO_SIDING;
            }

            // ★ 复刻 MTR4 MinecraftClientData.getFacingRailAndBlockPos 的选择算法：
            //   yaw + 90 与 atan2(dz, dx) 的夹角，取最小者（>180 取补角）。
            float yawPlus90 = 90.0f;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                yawPlus90 = mc.player.getYRot() + 90.0f;
            }

            Object bestRail = null;
            Object bestOther = null;
            double bestDiff = 720.0;
            for (Map.Entry<?, ?> outer : rails.entrySet()) {
                Object a = outer.getKey();
                if (!(outer.getValue() instanceof Map<?, ?> inner)) {
                    continue;
                }
                for (Map.Entry<?, ?> e : inner.entrySet()) {
                    Object b = e.getKey();
                    Object rail = e.getValue();
                    if (rail == null) {
                        continue;
                    }
                    // ★ 两个方向都认：MTR3 的 RAILS 只写单向（addRail 的字节码），
                    //   只查 rails.get(pos) 的话从「尾端」那一格右键会一条都找不到。
                    Object other;
                    if (clickedPos.equals(a)) {
                        other = b;
                    } else if (clickedPos.equals(b)) {
                        other = a;
                    } else {
                        continue;
                    }
                    if (!(other instanceof BlockPos otherPos)) {
                        continue;
                    }
                    if (isCableRailMtr3(rail)) {
                        continue;
                    }
                    double angle = Math.abs(Math.toDegrees(Math.atan2(
                            otherPos.getZ() - clickedPos.getZ(),
                            otherPos.getX() - clickedPos.getX())) - yawPlus90) % 360.0;
                    double diff = angle > 180.0 ? 360.0 - angle : angle;
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestRail = rail;
                        bestOther = otherPos;
                    }
                }
            }
            if (bestRail == null || bestOther == null) {
                return NO_SIDING;
            }

            // ★ 门禁：面向的必须**是侧线铁轨**（渲染成黄色的那一种），否则不开界面。
            if (m3RailTypeField.get(bestRail) != m3SidingType) {
                return NO_SIDING;
            }

            Object sidingsRaw = m3SidingsField.get(null);
            if (!(sidingsRaw instanceof Iterable<?> sidings)) {
                return NO_SIDING;
            }
            int seen = 0;
            for (Object siding : sidings) {
                if (siding == null) {
                    continue;
                }
                seen++;
                // 与 MTR 自己的判据逐字一致：两个端点都要落在这条侧线里。
                if (Boolean.TRUE.equals(m3ContainsPos.invoke(siding, clickedPos))
                        && Boolean.TRUE.equals(m3ContainsPos.invoke(siding, bestOther))) {
                    return ((Number) m3IdField.get(siding)).longValue();
                }
            }
            if (!warnedNoMatch) {
                warnedNoMatch = true;
                LOGGER.info("[SmoothLift/TrainSound] (MTR3) 面向的轨道是侧线铁轨，但{}条侧线里没有一条"
                        + "同时包含它的两个端点（节点 {}）⇒ 这次不开界面", seen, clickedPos);
            }
            return NO_SIDING;
        } catch (Throwable t) {
            warnOnce(t);
            return NO_SIDING;
        }
    }

    /** MTR3：这条轨道是不是缆车（与 MTR4 的 {@code includeCable=false} 同义，要跳过）。 */
    private static boolean isCableRailMtr3(Object rail) {
        try {
            Object type = m3RailTypeField.get(rail);
            return type != null && (type == m3CableCar || type == m3CableCarStation);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 反射调用本身出错（MTR 改了签名等）—— 只当「认不到」，别把右键变成崩服，并且只报一次。 */
    private static void warnOnce(Throwable t) {
        if (!warnedNoData) {
            warnedNoData = true;
            LOGGER.info("[SmoothLift/TrainSound] 读 MTR 侧线数据失败（{}），"
                    + "石斧右键侧线铁轨不会开界面", t.toString());
        }
    }

    /** MTR4 侧线 id 的人类可读形式（只为日志/诊断）。 */
    private static String hexIdOfMtr4(Object rail) {
        try {
            Object hex = m4RailGetHexId.invoke(rail);
            return hex == null ? "?" : hex.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    // ==================================================================
    // 绑定
    // ==================================================================

    /**
     * 绑定 MTR4：{@code org.mtr.mod.client.MinecraftClientData} + {@code org.mtr.core.data.*}。
     *
     * <p>每一项都必绑：这个类只服务「按侧线设列车音效」这一件事，少任何一环都做不成，
     * 所以不做「部分可用」的中间态。
     */
    private static void bindMtr4() throws Exception {
        Class<?> mcd = Class.forName("org.mtr.mod.client.MinecraftClientData");
        m4GetInstance = methodInHierarchy(mcd, "getInstance");
        m4GetFacingRailAndBlockPos = methodInHierarchy(mcd, "getFacingRailAndBlockPos", boolean.class);
        m4SidingIdMapField = fieldInHierarchy(mcd, "sidingIdMap");
        Class<?> rail = Class.forName("org.mtr.core.data.Rail");
        m4RailIsSiding = methodInHierarchy(rail, "isSiding");
        m4RailGetPosition1 = methodInHierarchy(rail, "getPosition1");
        m4RailGetPosition2 = methodInHierarchy(rail, "getPosition2");
        m4RailGetHexId = methodInHierarchy(rail, "getHexId");
        Class<?> savedRail = Class.forName("org.mtr.core.data.SavedRailBase");
        m4SidingContainsPos = methodInHierarchy(savedRail, "containsPos",
                Class.forName("org.mtr.core.data.Position"));
        Class<?> named = Class.forName("org.mtr.core.data.NameColorDataBase");
        m4SidingGetId = methodInHierarchy(named, "getId");
        if (m4GetInstance == null || m4GetFacingRailAndBlockPos == null || m4SidingIdMapField == null
                || m4RailIsSiding == null || m4RailGetPosition1 == null || m4RailGetPosition2 == null
                || m4RailGetHexId == null || m4SidingContainsPos == null || m4SidingGetId == null) {
            throw new NoSuchMethodException("MTR4 侧线数据层的接口对不上");
        }
    }

    /**
     * 绑定 MTR3：{@code mtr.client.ClientData} + {@code mtr.data.*}。
     *
     * <p>★ {@code containsPos} 用「名字 + 参数个数」找，**不写参数类型**：
     * MTR3 的 {@code SavedRailBase.containsPos(net.minecraft.class_2338)} 收的是**被混淆的 MC 类型**，
     * 写死类名在开发环境（yarn 名）与生产环境（intermediary 名）会二选一失效；
     * 而调用时传进去的就是从 {@code RAILS} 键 / 右键落点拿到的真 BlockPos，不需要知道它的类名。
     */
    private static void bindMtr3() throws Exception {
        Class<?> clientData = Class.forName("mtr.client.ClientData");
        m3RailsField = staticField(clientData, "RAILS");
        m3SidingsField = staticField(clientData, "SIDINGS");
        Class<?> rail = Class.forName("mtr.data.Rail");
        m3RailTypeField = fieldInHierarchy(rail, "railType");
        Class<?> railType = Class.forName("mtr.data.RailType");
        m3SidingType = optionalStaticField(railType, "SIDING");
        Class<?> savedRail = Class.forName("mtr.data.SavedRailBase");
        m3ContainsPos = methodByArity(savedRail, "containsPos", 1);
        Class<?> named = Class.forName("mtr.data.NameColorDataBase");
        m3IdField = fieldInHierarchy(named, "id");
        if (m3RailsField == null || m3SidingsField == null || m3RailTypeField == null
                || m3SidingType == null || m3ContainsPos == null || m3IdField == null) {
            throw new NoSuchMethodException("MTR3 侧线数据层的接口对不上");
        }
        // 可缺：缆车两型（拿不到就不跳过缆车，不影响侧线识别）。
        m3CableCar = optionalStaticField(railType, "CABLE_CAR");
        m3CableCarStation = optionalStaticField(railType, "CABLE_CAR_STATION");
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, MtrSidingAccess.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ==================================================================
    // 反射小工具
    // ==================================================================

    /**
     * 取 fastutil {@code Pair} 的左边。
     *
     * <p>反射拿到的是 {@code Object}，看不到泛型 ⇒ 两种取法都试一遍：
     * fastutil 自己的 {@code left()} 与它实现的 {@link java.util.Map.Entry#getKey()}。
     * 两条都不行就返回 null（当「没有」处理，不抛）。
     */
    private static Object firstOf(Object pair) {
        Object viaLeft = invokeNoArgs(pair, "left");
        return viaLeft != null ? viaLeft : invokeNoArgs(pair, "getKey");
    }

    private static Object invokeNoArgs(Object target, String name) {
        try {
            Method m = methodInHierarchy(target.getClass(), name);
            return m == null ? null : m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 沿继承链找方法（含 {@code protected}）。
     *
     * <p>★ 不用 {@code getMethod}：本类要取的 {@code Rail.getPosition1/getPosition2}
     * 是 {@code protected} 的，{@code getMethod} 只认 public，会一律返回 null 把整层拖挂。
     */
    private static Method methodInHierarchy(Class<?> owner, String name, Class<?>... params) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /**
     * 沿继承链按「名字 + 参数个数」找方法（含 {@code protected}）。
     *
     * <p>用在参数类型是**被混淆的 MC 类型**时（MTR3 {@code containsPos(BlockPos)}）：
     * 写死类名会在开发/生产环境二选一失效，而这里只需要一个「收一格」的方法。
     */
    private static Method methodByArity(Class<?> owner, String name, int arity) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
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

    /** 可选 public static 字段：拿不到返回 null（不抛）。 */
    private static Object optionalStaticField(Class<?> owner, String name) {
        try {
            return owner.getField(name).get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 容器形态兜底：fastutil 的 Map 只能取 values()，Set 是 Iterable。 */
    private static Iterable<?> elementsOf(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return map.values();
        }
        if (raw instanceof Iterable<?> iterable) {
            return iterable;
        }
        return null;
    }
}