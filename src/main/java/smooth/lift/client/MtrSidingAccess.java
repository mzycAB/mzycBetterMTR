package smooth.lift.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
 * 真正「哪条侧线 / 里面有哪几列车」在 MTR 的数据里（{@code org.mtr.core.data.Siding}
 * 持有 {@code ObjectArraySet<Vehicle> vehicles}）。本类把那个 id 读出来。
 *
 * <h2>规则不是猜的：三条都已反汇编核实（MTR 4.0.5）</h2>
 *
 * <ol>
 *   <li><b>「黄色」= 侧线铁轨。</b> {@code org.mtr.mod.data.RailType} 的枚举常量
 *       {@code SIDING} 构造时传的是 {@code MapColor.getYellowMapped()}
 *       （速度上限 40、{@code isSavedRail=true}、{@code canAccelerate=false}、形状 {@code QUADRATIC}）；
 *       站台轨道 {@code PLATFORM} 是 {@code getRedMapped()}（红）。
 *       ⇒ 「就是黄色的那个」说的就是 {@code Rail.isSiding()} 为真的那一段。</li>
 *   <li><b>「玩家面向的那一段」不用自己算。</b> {@code MinecraftClientData.getFacingRailAndBlockPos(boolean)}
 *       做的就是这件事：准星落点必须是 {@code BlockNode}（{@code mtr:rail}），
 *       然后取该节点上的轨道、按 {@code yaw + 90} 与 {@code atan2(dz, dx)} 的夹角取**最小**者；
 *       布尔是「要不要把缆车轨道也算进来」（{@code false} = 跳过 {@code Shape.CABLE}）。
 *       ⇒ 轨道连接处连着两段时，拿到的就是玩家面向的那一段。</li>
 *   <li><b>一条侧线 = 一段轨道，认亲判据是「两个端点都落在这条侧线里」。</b>
 *       {@code org.mtr.core.data.Rail.checkOrCreateSavedRail} 的字节码：
 *       若 {@code isSiding}，就在现成的侧线里找
 *       {@code siding.containsPos(rail.position1) && siding.containsPos(rail.position2)} 的那一条，
 *       找不到才 {@code new Siding(position1, position2, railMath.getLength(), transportMode, data)}。
 *       ⇒ 本类照抄这条判据，不自己编一套距离阈值。</li>
 * </ol>
 *
 * <h2>★ 认不到侧线数据时**不开界面**（而不是退化成另一个身份）</h2>
 *
 * 退化成「按轨道自己的 hash 认」看起来更宽容，但会让**同一条侧线有两个身份**：
 * 数据没同步到之前设一次、同步到之后再设一次，就落进两个桶里 —— 这正是【1.28】
 * 踩过的静默分桶（当时是站台数据晚到导致同一站台长期分两桶）。
 * 所以这里的选择是：**认不到就返回 {@link #NO_SIDING}，宁可不打开**，
 * 只打一条日志说明差在哪（可恢复、可诊断），不做「看起来能用但会分叉」的降级。
 *
 * <p>同理，面向的轨道**不是侧线铁轨**时也返回 {@link #NO_SIDING}
 * —— 「黄色」这个判据在运行时就是 {@code Rail.isSiding()}，它是**门禁**，不是提示。
 *
 * <p>绑定失败是**正常路径**（没装 MTR / 装了 MTR 3.x / 被别人换了实现）：整层保持 null，
 * {@link #facingSidingKey()} 一直返回 {@link #NO_SIDING}，只打一次 info，绝不影响其它功能。
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

    private static boolean attempted;
    private static boolean bound;

    private static Method getInstance;
    private static Field sidingIdMapField;
    private static Method getFacingRailAndBlockPos;
    private static Method railIsSiding;
    private static Method railGetPosition1;
    private static Method railGetPosition2;
    private static Method railGetHexId;
    private static Method sidingContainsPos;
    private static Method sidingGetId;

    /** 日志降噪：整层失败只报一次。/ 认不到侧线只报一次。 */
    private static boolean warnedNoData;
    private static boolean warnedNoMatch;

    private MtrSidingAccess() {
    }

    /**
     * 玩家准星面向的那条轨道所属**侧线**的 id；认不到返回 {@link #NO_SIDING}。
     *
     * <p>调用点只该在「石斧右键 {@code mtr:rail}」那一个回调里
     * （它读的是**准星**，和右键落点天然是同一格）。
     */
    public static long facingSidingKey() {
        if (!ensureBound()) {
            return NO_SIDING;
        }
        try {
            Object data = getInstance.invoke(null);
            if (data == null) {
                return NO_SIDING;
            }
            Object pair = getFacingRailAndBlockPos.invoke(data, false);
            if (pair == null) {
                return NO_SIDING;
            }
            Object rail = firstOf(pair);
            if (rail == null) {
                return NO_SIDING;
            }
            // ★ 门禁：面向的必须**是侧线铁轨**（渲染成黄色的那一种），否则不开界面。
            Object isSiding = railIsSiding.invoke(rail);
            if (!Boolean.TRUE.equals(isSiding)) {
                return NO_SIDING;
            }
            Object p1 = railGetPosition1.invoke(rail);
            Object p2 = railGetPosition2.invoke(rail);
            if (p1 == null || p2 == null) {
                return NO_SIDING;
            }
            Object map = sidingIdMapField.get(data);
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
                if (Boolean.TRUE.equals(sidingContainsPos.invoke(siding, p1))
                        && Boolean.TRUE.equals(sidingContainsPos.invoke(siding, p2))) {
                    return ((Number) sidingGetId.invoke(siding)).longValue();
                }
            }
            // 有侧线数据、但没一条认领这条轨道 —— 值得报一次（宁可查得到，也不要静默不开）。
            if (!warnedNoMatch) {
                warnedNoMatch = true;
                LOGGER.info("[SmoothLift/TrainSound] 面向的轨道是侧线铁轨，但{}条侧线里没有一条"
                                + "同时包含它的两个端点（轨道 {}）⇒ 这次不开界面",
                        seen, hexIdOf(rail));
            }
            return NO_SIDING;
        } catch (Throwable t) {
            // 反射调用本身出错（MTR 改了签名等）—— 同样只当「认不到」，别把右键变成崩服。
            if (!warnedNoData) {
                warnedNoData = true;
                LOGGER.info("[SmoothLift/TrainSound] 读 MTR 侧线数据失败（{}），"
                        + "石斧右键侧线铁轨不会开界面", t.toString());
            }
            return NO_SIDING;
        }
    }

    /** 侧线 id 的人类可读形式（只为日志/诊断）。 */
    private static String hexIdOf(Object rail) {
        try {
            Object hex = railGetHexId.invoke(rail);
            return hex == null ? "?" : hex.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /**
     * 可选绑定：拿不到就返回 false（**不是**错误路径，见类注释）。
     *
     * <p>每一项都必绑：这个类只服务「按侧线设列车音效」这一件事，
     * 少任何一环都做不成，所以不做「部分可用」的中间态。
     */
    private static boolean ensureBound() {
        if (attempted) {
            return bound;
        }
        attempted = true;
        try {
            Class<?> mcd = Class.forName("org.mtr.mod.client.MinecraftClientData");
            getInstance = methodInHierarchy(mcd, "getInstance");
            getFacingRailAndBlockPos = methodInHierarchy(mcd, "getFacingRailAndBlockPos", boolean.class);
            sidingIdMapField = fieldInHierarchy(mcd, "sidingIdMap");
            Class<?> rail = Class.forName("org.mtr.core.data.Rail");
            railIsSiding = methodInHierarchy(rail, "isSiding");
            railGetPosition1 = methodInHierarchy(rail, "getPosition1");
            railGetPosition2 = methodInHierarchy(rail, "getPosition2");
            railGetHexId = methodInHierarchy(rail, "getHexId");
            Class<?> savedRail = Class.forName("org.mtr.core.data.SavedRailBase");
            sidingContainsPos = methodInHierarchy(savedRail, "containsPos",
                    Class.forName("org.mtr.core.data.Position"));
            Class<?> named = Class.forName("org.mtr.core.data.NameColorDataBase");
            sidingGetId = methodInHierarchy(named, "getId");
            if (getInstance == null || getFacingRailAndBlockPos == null || sidingIdMapField == null
                    || railIsSiding == null || railGetPosition1 == null || railGetPosition2 == null
                    || railGetHexId == null || sidingContainsPos == null || sidingGetId == null) {
                throw new NoSuchMethodException("MTR 侧线数据层的接口对不上");
            }
            bound = true;
            LOGGER.info("[SmoothLift/TrainSound] 已接上 MTR 侧线数据 —— "
                    + "石斧右键侧线铁轨按「MTR 侧线 id」给列车音效分桶（不再是一条指令打天下）");
            return true;
        } catch (Throwable t) {
            LOGGER.info("[SmoothLift/TrainSound] 读不到 MTR 侧线数据（{}），"
                    + "列车音效界面暂时没有入口（其它功能照常）", t.toString());
            return false;
        }
    }

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
     * 是 {@code protected} 的（在 {@code Rail} 里覆写了 {@code TwoPositionsBase} 的抽象声明），
     * {@code getMethod} 只认 public，会一律返回 null 把整层拖挂。
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

    /** 容器形态兜底：fastutil 的 Map 只能取 values()，Set 是 Iterable。 */
    private static Iterable<?> elementsOf(Object raw) {
        if (raw instanceof java.util.Map<?, ?> map) {
            return map.values();
        }
        if (raw instanceof Iterable<?> iterable) {
            return iterable;
        }
        return null;
    }
}
