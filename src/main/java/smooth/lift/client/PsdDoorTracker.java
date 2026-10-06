package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.SmoothLift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【1.50】MTR 屏蔽门（Platform Screen Door / APG）**门开合程度**的客户端采集器。
 *
 * <h2>数据从哪来</h2>
 * 门值**不在**任何「全局列表」里（不像直梯有 {@code ClientData.LIFTS}），而是存在**方块实体**上：
 * <ul>
 *   <li>MTR4：{@code org.mtr.mod.block.BlockPSDAPGDoorBase$BlockEntityBase.getDoorValue()} → double 0..1；</li>
 *   <li>MTR3：{@code mtr.block.BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase.getOpen(float)} → float。</li>
 * </ul>
 * 两者的调用者都是**渲染器**（MTR4 的 {@code RenderPSDAPGDoor.render} 每帧还会自己调一次
 * {@code tick(partialTick)} 推进门动画，MTR3 同样在 render 里调 {@code getOpen}），而且两个版本的
 * {@code rendersOutsideBoundingBox2} / {@code shouldRenderOffScreen} 都返回 <b>true</b>
 * （= 出了视锥、但在渲染距离内也照样渲染），所以「站在站台上背对着门」时门值一样在走。
 * 本类就挂在那两个方法上（见 {@code Mtr3PsdDoorMixin} / {@code Mtr4PsdDoorMixin}），
 * **每个被渲染到的门每帧回报一次**，不需要我们自己扫方块。
 *
 * <p><b>★★ 但注入点是「基类的方法」，所以电梯门也会回报 —— 必须自己过滤。</b>
 * MTR 的电梯门方块实体继承的正是这个基类（MTR4：{@code BlockLiftDoor$BlockEntity extends
 * BlockPSDAPGDoorBase$BlockEntityBase}；MTR3：{@code BlockLiftDoor$TileEntityLiftDoor extends
 * BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase}），渲染它的也是 {@code RenderPSDAPGDoor}
 * （常量池里有 {@code mtr:textures/block/lift_door_%s_%s_1.png}）。
 * ⇒ {@link #accept} 的第一件事就是按**注册名**筛一次（{@link SmoothLift#isPsdDoor}）：
 * 不是 {@code psd_door*} / {@code apg_door*} 一律**不采集**。
 * 少了这一层，电梯开关门会被当成屏蔽门播提示音（「砰—咚」），
 * 而用户根本没法在界面里关掉它（右键电梯门进不了屏蔽门界面）。
 *
 * <h2>「一扇门」怎么指</h2>
 * 一扇屏蔽门在客户端由**多个方块实体**共同表示（左右各一个，MTR4 还分上/下半格），
 * 而门值由这几个实体**取同一个值**（MTR4 的 {@code getDoorValue()} 就是
 * {@code max(自己的 doorValue, 配对那一格的 doorValue)}）。所以不能拿「读到门值的那一格」当身份，
 * 否则同一扇门会算出好几个 key。这里统一折算成 {@link #anchorOf 锚点}：
 * <ol>
 *   <li>先下移到下半格（{@code half=upper} 时 {@code pos.below()}）；</li>
 *   <li>再按 {@code side}/{@code facing} 找配对的那一格，取两者里 {@code BlockPos.asLong} 较小的；</li>
 * </ol>
 * 左右两侧算出来**一定是同一个坐标**，于是「石斧右键哪一格都是同一扇门」。
 * 石斧界面（{@code PsdToneSetupScreen}）用同一个 {@link #anchorOf} 算 key，两边必然对得上。
 *
 * <h2>MTR3 的量纲要补一个偏移</h2>
 * MTR3 的 {@code getOpen(f)} 返回 {@code openClient / 32}，而 {@code openClient} 收敛到
 * {@code open - 0.1}（字节码：{@code Math.abs((open - 0.1f) - openClient) < 0.95f * f} 时直接吸附），
 * {@code open ∈ [0, 32]}（{@code TrainServer} 里 {@code (int) clamp(doorValue * 64, 0, 32)}，
 * {@code MAX_OPEN_VALUE = 32}）。⇒ 原始返回值区间是 <b>[-0.003125, 0.996875]</b>，
 * 两端都差 0.1/32。不减掉它的话「全开」永远到不了 1.0，关门事件会被漏判。
 */
public final class PsdDoorTracker {

    /**
     * 一条门的只读快照。
     *
     * @param key      门的锚点（{@link #anchorOf} 的结果 = {@code BlockPos.asLong}）；
     *                 ★【1.20】这是**播放身份**——哪扇门在响、门值、学习全按它，
     *                 一串里的每扇门各不相同
     * @param runKey   **配置身份**（{@link #runKeyOf} 的结果）：★【1.27】优先 = **这一扇门所在的
     *                 MTR 站台**（同一站台里被实体缺口切开的几段门共用一个）；认不到站台时才回落成
     *                 「站台门 + 屏蔽门玻璃 + 玻璃尾部相连的一整串连通块」。
     *                 石斧 UI 与 per-串配置表都用它，「改其中一个 = 改这一串」（用户点名）
     * @param x/y/z    锚点方格中心的世界坐标（发声位置）
     * @param fraction 开合程度：0 = 全关，1 = 全开
     * @param platformId 【1.28】这一串**认得出来的 MTR 站台 id**（{@link #accept} 从
     *                 {@link #PLATFORM_CACHE} 解出来的，与 {@code runKey} 是同一份数据）；
     *                 ★【09-28 续 6】**可正可负**（MTR4 id = {@code Random().nextLong()}），
     *                 「还没认到」= {@link MtrDwellAccess#PLATFORM_ID_NONE}（**不是 0**）。
     *                 进站报站查时刻表直接用这份 id ——
     *                 **不再自己再调一次 {@code MtrDwellAccess.platformIdAt}**：
     *                 两条并行的认亲各判一次 4 格边界，会在边界上分叉成
     *                 「身份已是站台、时刻表却认不到 ⇒ 这串永远不响进站报站」。
     * @param door     【1.29】这是**门**还是**幕墙/幕墙尾部**：**门** = 整份快照 + 铃声
     *                 （开关门嘀嘀嘀、关门人声，只从门发声）；**墙** = 只参与
     *                 「到站/进站播报」那一组的**声源与射程**（用户点名：
     *                 「门和幕墙以及幕墙尾部一起播报的是 pbmarrive/pbmmidium，
     *                 只有门的播报是铃声」）。墙成员由 {@link #accept} 从所在连通串
     *                 注册进来（{@code runKey/platformId} 与这扇门**同一份**）。
     */
    public record DoorView(long key, long runKey, double x, double y, double z, float fraction,
                           long platformId, boolean door) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("SmoothLift/PsdChime");

    /**
     * 多久没被报告就把这条门忘掉（tick）。渲染距离内每帧都会报一次，所以正常值远小于它；
     * 定 20 是为了容忍「玩家转个视角 / 世界暂停 / 掉帧」这类短暂空档，同时又能把
     * 真正走远的门及时清掉（否则 Map 会随着跑图无限长大）。
     */
    private static final int STALE_TICKS = 20;

    /** 锚点 → 最近一次报告（值 + 时间戳）。 */
    private static final Map<Long, Entry> LIVE = new HashMap<>();

    /** 当前镜像属于哪个维度：换了维度整表作废（门 key 不含维度，串了会发出莫名其妙的声）。 */
    private static ResourceKey<Level> dimension;

    /** 「还没开始渲染任何一串门」的哨兵（runKey 恒不为它：它要么是 {@code Long.MIN_VALUE+id}，
     *  要么是某个方块坐标的 asLong）。 */
    public static final long RUN_KEY_NONE = 0L;

    /**
     * 【10-03 五改】一扇门（**门锚点** = {@code anchorOf} 的结果）所属「门串」的锚点。
     *
     * <p>门串 = 用户原话「连在一起的屏蔽门，包括门，幕墙，幕墙尾部」——
     * 也就是 {@link #floodRun} 走出来的连通块，锚点取块内 {@code asLong} 最小的那一格
     * （与服务端 / 界面 / 播放端三处**同一个数**，天然稳定）。
     *
     * @return 门串锚点；缓存里还没有（这一帧没采集过）⇒ 回落门锚点本身
     */
    public static long runAnchorOfDoorKey(long doorKey) {
        Long flood = RUN_CACHE.get(doorKey);
        return flood == null || flood == Long.MIN_VALUE ? doorKey : flood;
    }

    /**
     * 【10-03 五改】从任意一格 PSD 方块算出**门串锚点**（石斧 UI 读写「门串级设置」时用）。
     *
     * @return 门串锚点；不是屏蔽门 / 世界为空 ⇒ {@link #RUN_KEY_NONE}
     */
    public static long runAnchorOf(Level level, BlockPos raw) {
        if (level == null || raw == null) {
            return RUN_KEY_NONE;
        }
        BlockState state = level.getBlockState(raw);
        if (!SmoothLift.isPsdDoor(state)) {
            return RUN_KEY_NONE;
        }
        long doorKey = anchorOf(state, raw);
        return runAnchorOfDoorKey(doorKey);
    }

    /**
     * 【10-03 五改】这一串门落在哪个 **MTR 站台**（列车那侧按站台 id 查设置）。
     *
     * <p>先走一次 {@link #runKeyOf}：站台身份会「晚到」MTR 的同步，那一步带 2 秒节流重试，
     * 顺手把 {@code PLATFORM_CACHE} 填上（界面构造时本来就调过它）。
     *
     * @return 站台 id（★ 可正可负）；认不到 ⇒ {@link MtrDwellAccess#PLATFORM_ID_NONE}
     */
    public static long platformIdOf(Level level, BlockPos raw) {
        if (level == null || raw == null) {
            return MtrDwellAccess.PLATFORM_ID_NONE;
        }
        if (RUN_KEY_NONE == runKeyOf(level, raw)) {
            return MtrDwellAccess.PLATFORM_ID_NONE;
        }
        long runAnchor = runAnchorOf(level, raw);
        if (runAnchor == RUN_KEY_NONE) {
            return MtrDwellAccess.PLATFORM_ID_NONE;
        }
        Long recognized = PLATFORM_CACHE.get(runAnchor);
        return recognized == null ? MtrDwellAccess.PLATFORM_ID_NONE : recognized - Long.MIN_VALUE;
    }

    private PsdDoorTracker() {
    }

    /** 一条门的内部状态。 */
    private static final class Entry {
        final long key;
        // ★【1.28】不再是 final：身份会**迁移**（进世界先认成连通串，站台数据晚到约 30 秒后
        //   再认成站台）。建 Entry 时写一次、之后跟着 runKeyOf 刷新 —— 否则同一站台的门
        //   长期分成「旧串身份 + 新站台身份」两桶（LOG6：同一班车同一站台响两遍）。
        long runKey;
        final double x;
        final double y;
        final double z;
        /** 【1.28】认得到的站台 id（★【09-28 续 6】可正可负；{@link MtrDwellAccess#PLATFORM_ID_NONE}
         *  = 还没认到）；与 runKey 同一份数据，随 runKey 一起刷新。 */
        long platformId;
        /** 【1.29】true = 门（铃声 + 播报都参与）；false = 幕墙/幕墙尾部（只参与播报的声源/射程）。 */
        final boolean door;
        float fraction;
        long tick;

        Entry(long key, long runKey, double x, double y, double z, float fraction, long tick,
              long platformId, boolean door) {
            this.key = key;
            this.runKey = runKey;
            this.x = x;
            this.y = y;
            this.z = z;
            this.fraction = fraction;
            this.tick = tick;
            this.platformId = platformId;
            this.door = door;
        }
    }

    // ------------------------------------------------------------------
    // 供 mixin 调用
    // ------------------------------------------------------------------

    /** MTR4：{@code getDoorValue()} 已经是 0..1 的可见开合度，只夹一次。 */
    public static void acceptMtr4(BlockPos rawPos, double doorValue) {
        accept(rawPos, clamp01((float) doorValue));
    }

    /** MTR3：{@code getOpen(f)} 的量纲见类注释，先减掉那个 0.1/32 的偏移再夹。 */
    public static void acceptMtr3(BlockPos rawPos, float open) {
        accept(rawPos, clamp01(open + 0.1f / 32.0f));
    }

    private static void accept(BlockPos rawPos, float fraction) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || rawPos == null) {
            return;
        }
        // ★★【1.15 修复】门禁：只有**真的是屏蔽门**才采集。
        //
        // 为什么必须有这一层（可核验的事实）：
        //   MTR 的**电梯门**方块实体继承的就是本 mixin 注入的那个基类 ——
        //     MTR4：{@code BlockLiftDoor$BlockEntity extends BlockPSDAPGDoorBase$BlockEntityBase}
        //     MTR3：{@code BlockLiftDoor$TileEntityLiftDoor extends
        //            BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase}
        //   （用 {@code javap} 对 4.0.5 / 3.2.2 两个 jar 各确认过一次）。
        //   而 {@code getDoorValue()} / {@code getOpen(F)} 这两个方法的字节码**就写在基类里**，
        //   所以注入点在电梯门上也照跑；渲染电梯门的恰好也是 {@code RenderPSDAPGDoor}
        //   （该类的常量池里有 {@code mtr:textures/block/lift_door_%s_%s_1.png}，
        //   且 {@code InitClient} 给 {@code LIFT_DOOR_EVEN_1 / LIFT_DOOR_ODD_1} 注册的就是它，
        //   构造参数是第 4 档变体）。
        //   ⇒ 电梯门每帧都回报门值。少了这一层，**电梯开关门就会被当成屏蔽门**，
        //     播屏蔽门的开门/关门提示音（听感是「砰—咚」），
        //     而那两个音在石斧界面里根本配不到电梯门上（右键电梯门不是屏蔽门，进不了界面）
        //     ⇒ 用户只能听见声音、无处关掉。这就是「电梯开关门多出一声」的根因。
        //
        // ★ 为什么用**白名单**（isPsdDoor）而不是「排除 lift_door」的黑名单：
        //   「屏蔽门开关门播声音」这个功能的语义就是白名单本身；黑名单只挡得住已知的这一种，
        //   将来 MTR 再加一个继承同一基类的门（或别的方块）会再次污染这条链路，
        //   而且症状同样只是「静静多响一声」，极难再查一次。
        //   这也符合「收紧点放在最靠近数据源头的那一处工具方法」——全项目只有这里一处门禁。
        BlockState state = level.getBlockState(rawPos);
        if (!SmoothLift.isPsdDoor(state)) {
            return;
        }
        if (dimension != level.dimension()) {
            // 换维度 / 刚进世界：整表作废（旧世界的门 key 与新的不是一回事）
            dimension = level.dimension();
            LIVE.clear();
            RUN_CACHE.clear();
            // 【1.27】同一维度里也有「重进世界」这条路（维度没变就不会进来），所以这里主要是
            // 换维度时清站台身份：站台 id 是存档级的，跨维度/跨存档不能复用。
            PLATFORM_CACHE.clear();
            PLATFORM_NEXT_TRY.clear();
            PLATFORM_LOGGED.clear();
            // 【1.28】认站台失败的诊断节流也跟着作废（换了世界，重新说明一次没关系）。
            PLATFORM_FAIL_NEXT_LOG.clear();
            PLATFORM_FAIL_EXPLAINED.clear();
            // 【1.29】本串幕墙/幕墙尾部的位置缓存也作废（换了世界，方块对不上）。
            RUN_WALL_CACHE.clear();
        }
        long key = anchorOf(state, rawPos);
        long runKey = runKeyOf(level, state, rawPos, key);
        // ★★【1.28】把「认得到的站台 id」从认证缓存里解出来，跟 runKey 走同一条链
        //   （不自己再调一次 platformIdAt —— 见 DoorView.platformId 的注释）。
        //   flood 是这一扇门归属连通串的锚点；PLATFORM_CACHE[flood] 里存的 platformKey
        //   编码了站台 id（= platformKey - Long.MIN_VALUE），认到才写。
        //   ★【09-28 续 6】「认不到」= PLATFORM_ID_NONE，**不是 0**：MTR4 的站台 id 是
        //   Random().nextLong()，0 和负数都是合法 id（见 MtrDwellAccess.PLATFORM_ID_NONE）。
        long flood = RUN_CACHE.getOrDefault(key, Long.MIN_VALUE);
        Long recognized = flood == Long.MIN_VALUE ? null : PLATFORM_CACHE.get(flood);
        long platformId = recognized == null
                ? MtrDwellAccess.PLATFORM_ID_NONE : recognized - Long.MIN_VALUE;
        long now = level.getGameTime();
        Entry entry = LIVE.get(key);
        if (entry == null) {
            LIVE.put(key, new Entry(key, runKey, keyX(key) + 0.5, keyY(key) + 0.5, keyZ(key) + 0.5,
                    fraction, now, platformId, true));
        } else {
            entry.fraction = fraction;
            entry.tick = now;
            if (entry.runKey != runKey) {
                // 【1.28】身份迁移（连通串 → 站台）时跟着刷字段，别让旧身份钉死在 Entry 上。
                entry.runKey = runKey;
                entry.platformId = platformId;
            }
        }
        // ★★【1.29】把这一串里的**幕墙 / 幕墙尾部**也注册进快照 —— 但它们不是门：
        //   只当「到站/进站播报」那一组的声源与射程成员（用户点名：「门和幕墙以及幕墙尾部
        //   一起播报的是 pbmarrive/pbmmidium，只有门的播报是铃声」）。铃声侧（detect / 关门
        //   那条链路）按 {@code DoorView.door()} 过滤，墙成员永远不会触碰到它；播报侧
        //   （nearestPerRun / nearestInRun）天然把它们算进「这一串里离玩家最近」。
        //   墙成员的位置是静的（幕墙不动），只要本串还有门活着就跟着刷新 tick，
        //   门全过期时它们也一起被 {@link #snapshot()} 扫掉。
        for (long wallPos : runWalls(level, rawPos, flood)) {
            Entry existing = LIVE.get(wallPos);
            if (existing == null) {
                LIVE.put(wallPos, new Entry(wallPos, runKey,
                        keyX(wallPos) + 0.5, keyY(wallPos) + 0.5, keyZ(wallPos) + 0.5,
                        0.0f, now, platformId, false));
            } else {
                existing.fraction = 0.0f;
                existing.tick = now;
                if (existing.runKey != runKey) {
                    existing.runKey = runKey;
                    existing.platformId = platformId;
                }
            }
        }
    }

    /**
     * 当前**还活着**（最近 {@value #STALE_TICKS} tick 内被渲染到）的门。
     * 顺带把过期的清掉，所以调用方不必自己维护生命周期。
     */
    public static List<DoorView> snapshot() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return List.of();
        }
        long now = level.getGameTime();
        List<DoorView> out = new ArrayList<>();
        java.util.Iterator<Map.Entry<Long, Entry>> it = LIVE.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now - e.tick > STALE_TICKS) {
                it.remove();
                continue;
            }
            out.add(new DoorView(e.key, e.runKey, e.x, e.y, e.z, e.fraction, e.platformId, e.door));
        }
        return out;
    }

    /** 断开连接 / 换世界：全部忘掉。 */
    public static void clear() {
        LIVE.clear();
        RUN_CACHE.clear();
        // 【1.27】站台身份也跟着作废：换了世界，id 不再对应同一批门。
        PLATFORM_CACHE.clear();
        PLATFORM_NEXT_TRY.clear();
        PLATFORM_LOGGED.clear();
        // 【1.28】诊断节流一起作废。
        PLATFORM_FAIL_NEXT_LOG.clear();
        PLATFORM_FAIL_EXPLAINED.clear();
        // 【1.29】本串幕墙/幕墙尾部的位置缓存一起作废。
        RUN_WALL_CACHE.clear();
        dimension = null;
    }

    // ------------------------------------------------------------------
    // 【1.26】「一串门里离玩家最近的那一扇」—— 站台广播（到站播报 / 进站报站）的距离基准与声源
    //        ★【1.27】这里的「一串」= 现在的 runKey = **整个站台**（见 runKeyOf）：
    //        同一站台里被实体缺口切开的几段门都算在内，于是「最近的一扇」是整站台里最近的。
    // ------------------------------------------------------------------

    /**
     * 一串门里**离玩家最近的那一扇**（口径与 {@link #snapshot()} 相同：{@value #STALE_TICKS} tick 内还活着的门）。
     *
     * <p>★ 为什么必须问这一个：一串连在一起的屏蔽门，在**配置**上本来就是同一个身份
     * （{@link #runKeyOf runKey}），而「到站播报 / 进站报站」读的也正是**那一份**按串的配置
     * （{@code getDoorPsdMidiumAudio(mc.level, door.runKey())} 等）⇒ 它本质上是**站台广播**，
     * 一整串只该响一次、且站在站台上**任何**位置都该听得见。
     *
     * <p>不用「快照里第一扇」的理由有两条，都是现场取证（LOG4）钉死的：
     * <ol>
     *   <li>{@link #LIVE} 是 {@code HashMap} ⇒ 迭代序会随增删变化，同一个串一会算出 {@code -33}
     *       一会算出 {@code -28}（同一份日志里两条播报日志的门坐标不同）⇒ 声源在串里随机跳；</li>
     *   <li>以「某一扇」为基准时，玩家站在另一头就离它 &gt; 射程 ⇒ 整串静默（
     *       「一个站台 12 个门，前 3 个后 4 个不响」里的大部分）。</li>
     * </ol>
     *
     * <p>★★【1.27】第 2 条的**真正形状**在 LOG5 里才看清：一个站台常常是好几段连通块，
     * 只把「串」内部的最近门当基准还不够 —— 要连**别的段**一起算，所以 runKey 本身升到了站台。
     * 走到这一步之后，本方法的语义自动变成「整个站台里离玩家最近的那一扇」。
     *
     * @return 最近的那扇门；这一串当前不在快照里（走远了 / 区块卸载 / 换成没装 MTR 的世界）返回 {@code null}
     */
    public static DoorView nearestInRun(long runKey, Vec3 player) {
        Entry best = nearestEntryInRun(runKey, player);
        return best == null ? null
                : new DoorView(best.key, best.runKey, best.x, best.y, best.z, best.fraction,
                best.platformId, best.door);
    }

    /**
     * 到「这一串门」的距离 = 到这一串里**最近**那一扇门的距离。
     *
     * <p>★ 这是站台广播的射程判据：只要玩家在这串门的任意一扇旁边，整串的播报都算「在范围内」，
     * 于是不会被「射程 16 格 &lt;&lt; 一串 55 格」切成两半（现场 LOG4：z=72 的一串 12 扇门，
     * 玩家站哪儿都只有约 6 扇在射程内，其余 {@code gain == 0} 静默跳过 —— 且按设计不刷日志）。
     *
     * @return {@code Double.MAX_VALUE} = 这一串不在快照里（= 走到别处去了，增益自然归 0）；
     *         {@code player == null}（界面/未进入世界）当贴脸处理 = 0，与 {@code refreshVolume} 原口径一致
     */
    public static double nearestDistanceInRun(long runKey, Vec3 player) {
        if (player == null) {
            return 0.0;
        }
        Entry best = nearestEntryInRun(runKey, player);
        return best == null ? Double.MAX_VALUE
                : player.distanceTo(new Vec3(best.x, best.y, best.z));
    }

    /**
     * 上面两个口共用的取数：在 {@link #LIVE} 里筛出这一串还活着的门，取离玩家最近的那个。
     *
     * <p>★ 这里**只用不删**（不顺手清理过期项）—— 本方法会被声音实例的每 tick 路径调用，
     * 而清理是 {@link #snapshot()} 的职责（它每 tick 由主路调一次，已经够了）。
     * 两个口同时改同一张表反而会把迭代中的表改掉。
     */
    private static Entry nearestEntryInRun(long runKey, Vec3 player) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        long now = level.getGameTime();
        Entry best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Entry e : LIVE.values()) {
            if (e.runKey != runKey || now - e.tick > STALE_TICKS) {
                continue;
            }
            double d = player == null ? 0.0
                    : player.distanceToSqr(e.x, e.y, e.z);
            if (best == null || d < bestSqr) {
                best = e;
                bestSqr = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 【10-05】pbm* 指令不带 -f 时的目标：**离玩家最近的那一串屏蔽门**
    //   ★ 返回的 runKey 与石斧 UI（PsdToneSetupScreen）、播放端（PsdChimePlayer）**同一把尺子**
    //     —— 三处必须同一个身份，否则「指令写的播放端读不到」会静默落回维度默认
    //     （PsdToneSetupScreen 类注释里那句警告说的就是这个）。
    // ------------------------------------------------------------------

    /** 平票判据：两扇门到玩家的距离差 ≤ 这个值就算「一样近」（格）。 */
    public static final double NEAREST_TIE = 0.05;

    /**
     * 【10-05】取「离玩家最近的那一串屏蔽门」的配置身份（{@code runKey}）。
     *
     * <p>规则（用户点名）：先取**离玩家最近的那一扇门**；若两扇门距离并列
     * （差 ≤ {@link #NEAREST_TIE}），取**玩家正对着**的那一扇（视线方向点积最大）。
     * 返回它所属的「串」= {@code door.runKey()}。
     *
     * <p>串的粒度与石斧 UI 完全一致（{@code runKeyOf}：首选 MTR 站台，认不到才回落
     * 「门 + 幕墙 + 幕墙尾部」的连通块）。
     *
     * @param playerPos 玩家眼睛/脚位置（用 {@code player.position()} 即可）；null ⇒ 认不到
     * @param look      玩家视线单位向量（{@code player.getLookAngle()}）；null ⇒ 不做「面朝」判
     * @return 最近那一串的 runKey；快照里一扇门都没有 / 世界为空 ⇒ {@link #RUN_KEY_NONE}
     */
    public static long nearestRunKeyTo(Vec3 playerPos, Vec3 look) {
        if (playerPos == null) {
            return RUN_KEY_NONE;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return RUN_KEY_NONE;
        }
        long now = level.getGameTime();
        long bestRunKey = RUN_KEY_NONE;
        double bestSqr = Double.MAX_VALUE;
        double bestDot = -Double.MAX_VALUE;
        for (Entry e : LIVE.values()) {
            if (now - e.tick > STALE_TICKS) {
                continue;
            }
            double dx = e.x - playerPos.x;
            double dy = e.y - playerPos.y;
            double dz = e.z - playerPos.z;
            double sqr = dx * dx + dy * dy + dz * dz;
            double dot = look == null ? 0.0
                    : (dx * look.x + dy * look.y + dz * look.z) / Math.max(Math.sqrt(sqr), 1.0E-6);
            boolean better;
            if (bestRunKey == RUN_KEY_NONE && bestSqr == Double.MAX_VALUE) {
                better = true;
            } else if (sqr < bestSqr - (NEAREST_TIE * NEAREST_TIE)) {
                better = true;
            } else if (sqr > bestSqr + (NEAREST_TIE * NEAREST_TIE)) {
                better = false;
            } else {
                // 距离并列 ⇒ 玩家面朝的那一扇（点积更大）
                better = dot > bestDot;
            }
            if (better) {
                bestRunKey = e.runKey;
                bestSqr = sqr;
                bestDot = dot;
            }
        }
        return bestRunKey;
    }

    // ------------------------------------------------------------------
    // 【10-01】「**同一个 MTR 站台**里离玩家最近的那一扇」—— 开关门提示音（铃声）的音量基准
    //        ★ 与上面 {@link #nearestInRun}（**车站**级 runKey）是**两把尺子**，别合并：
    //          播报是「站台广播」（整个车站只该响一条）⇒ 用车站级；
    //          铃声是「沿站台一排门同时响」的位置短音 ⇒ 必须收窄到**一个站台**。
    // ------------------------------------------------------------------

    /**
     * 【10-01】同一个 MTR 站台（{@code platformId}）里**离玩家最近的那一扇门**。
     *
     * <p>★★ 为什么需要它（现场 LOG013 钉死的，数字可复核）：
     * {@link #runKeyOf runKey} 在【09-30 续 9】升到了**车站级**（同一车站的几层 / 两侧共用一个
     * 配置身份），而【09-30 续 10】把铃声的音量基准定成「**本串**里离玩家最近那一扇」
     * ⇒ 那个「本串」实际是**整个车站**。于是：玩家站在 A 站台的门前（1.6 格），
     * 同一车站 B 站台（另一层 / 轨道另一侧，只隔 11 格）的门**关一下**，
     * 它的增益也是按「玩家身边那扇 A 站台的门」算的 ⇒ **100% 音量**在耳边响
     * ——用户听到的就是「早几秒一个 + 准点一个」的**多出来的提示音**（他说「又犯了一次」）。
     *
     * <p>LOG013 证据：世纪广场 一个车站在 3 层上共 6~7 个站台（#63~#68；#78 与 #68 同坐标重复注册）；
     * 玩家的门口线是 #67（{@code @(-26,-21,41)}，门在 z=43）与 #65（{@code @(-33,-21,34)}，门在 z=32），
     * 两条线**只隔 11 格**。14:56:38 玩家就站在 z=32 那条线旁边（1.6 格），
     * 而日志里 z=43 那条线的关门声标着「距玩家 11.4 / 12.1 / 14.0 格」——**11 格外的门也响了**。
     *
     * <p>收窄到站台之后：自己的站台（含被实体缺口切开的几段）照样**整排一起响、一起淡出**
     * （保住 09-30 续 10 点名的「沿站台一排门同时响」，也仍是「别走一半播报断了」）；
     * 别的站台则按它**自己的**距离衰减到 11 格该有的音量（约 2.6%），不再盖过脚下这扇门。
     *
     * @param platformId 【1.28】这一串认得出来的 MTR 站台 id（{@link MtrDwellAccess#isPlatformKnown}）
     * @return 最近的那扇门；{@code platformId} 认不到、或这个站台此刻不在快照里时返回 {@code null}
     *         —— 调用方据此整串归 0，与 {@link #nearestInRun} 的约定一致
     */
    public static DoorView nearestOnPlatform(long platformId, Vec3 player) {
        Entry best = nearestEntryOnPlatform(platformId, player);
        return best == null ? null
                : new DoorView(best.key, best.runKey, best.x, best.y, best.z, best.fraction,
                best.platformId, best.door);
    }

    /**
     * {@link #nearestOnPlatform} 的取数：与 {@link #nearestEntryInRun} 同构，只把筛子从
     * {@code runKey} 换成 {@code platformId}。
     *
     * <p>★ 站台 id 认不到（= {@link MtrDwellAccess#PLATFORM_ID_NONE}）时**直接返回 null**：
     * 「认不到站台」不能被当成「所有认不到站台的门都是同一个站台」—— 那会把全世界的门并成一把尺子，
     * 正是 09-28 续 6 记下的那个坑（拿 {@code id > 0} 判「认到没认到」）。
     */
    private static Entry nearestEntryOnPlatform(long platformId, Vec3 player) {
        if (!MtrDwellAccess.isPlatformKnown(platformId)) {
            return null; // 认不到站台 ⇒ 没有「同一站台」可言（调用方回落到车站级口径）
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        long now = level.getGameTime();
        Entry best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Entry e : LIVE.values()) {
            if (e.platformId != platformId || now - e.tick > STALE_TICKS) {
                continue;
            }
            double d = player == null ? 0.0
                    : player.distanceToSqr(e.x, e.y, e.z);
            if (best == null || d < bestSqr) {
                best = e;
                bestSqr = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 【10-04 修 1】「同排一起响」的门数 —— 铃声逐门实例的**响度预算**
    // ------------------------------------------------------------------

    /**
     * 【10-04 修 1】这一排此刻**一起响**的门数（铃声的每条实例按它分摊增益）。
     *
     * <p>★★ 为什么必须有：提示音的实例是**逐扇门**起的（每扇一条），而 {@code PsdMusicInstance}
     * 全都是 {@code Attenuation.NONE} 的**等音量**声源（距离增益按整串现算 ⇒ 同一站台的每条
     * 实例拿到同一份音量）。N 扇门一起响 = N 条**同素材**声源在 OpenAL 里相干叠加，
     * 总混音增益就是单扇的 **N 倍** —— 一个 12~56 扇门的站台随便一响就是十几倍。
     * 超过 1.0 的总增益会顶进 OpenAL Soft 的**输出压限器**（把整个输出压回去）：
     * 用户听到的正是「提示音一响，游戏的背景音乐（以及其它所有声音）突然变小」。
     *
     * <p>修法 = **响度预算**：每条实例 ÷ 这一排的门数 ⇒ 合成混音 ≈ 1×（与「音量 100 =
     * 一条满增益声源」的设置语义一致），逐扇门的空间展开（声像）原样保留、总响度不变，
     * 压限器不再被顶起来 —— 音乐不再被压小。
     *
     * <p>分母的口径与音量的「整串」口径**同一把尺子**（【09-30 续 10】）：认到站台 id 时 =
     * **同一个站台**里的门（两侧车门一起开关的另一边不进来）；认不到时回落**车站级 runKey**
     * （站台数据晚到时也按完整的一排算，先响起来再说）。
     *
     * @return ≥ 1：这一排一起响的门数；这一扇不在快照里时返回 1（= 不分摊，照旧单条满额）
     */
    public static int soundingDoorsTogether(PsdDoorTracker.DoorView door) {
        if (door == null) {
            return 1;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return 1;
        }
        boolean byPlatform = MtrDwellAccess.isPlatformKnown(door.platformId());
        long now = level.getGameTime();
        int count = 0;
        for (Entry e : LIVE.values()) {
            if (!e.door || now - e.tick > STALE_TICKS) {
                continue;
            }
            boolean sameWave = byPlatform ? e.platformId == door.platformId()
                    : e.runKey == door.runKey();
            if (sameWave) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    // ------------------------------------------------------------------
    // 门 → 锚点
    // ------------------------------------------------------------------

    /**
     * 把任意一格「屏蔽门方块」折算成这扇门的锚点；返回 {@link BlockPos#asLong()}。
     *
     * <p>不是屏蔽门方块（例如紧贴着的玻璃）时**退化成自身**：宁可这一个 key 落在没人配过的格子上
     * （结果就是「这扇门走维度默认」），也不要就近吸附到某扇真正的门上把它带歪。
     *
     * <p>本重载自带方块状态查询，给**界面侧**（{@code PsdToneSetupScreen} 石斧右键）用；
     * 播放链路已经在 {@link #accept} 那里做过 {@link SmoothLift#isPsdDoor} 门禁，
     * 手里已经有 {@code state}，走下面的私有重载，避免同一格每帧查两次。
     */
    public static long anchorOf(Level level, BlockPos raw) {
        return anchorOf(level.getBlockState(raw), raw);
    }

    /** {@link #anchorOf(Level, BlockPos)} 的实体：{@code state} 由调用方查好传进来。 */
    private static long anchorOf(BlockState state, BlockPos raw) {
        if (!SmoothLift.isPsdDoor(state)) {
            return raw.asLong();
        }
        BlockPos pos = raw;
        // 1) 下移到下半格（只有 MTR4 的 PSD 门有 half；MTR3 没有这个属性，跳过）
        Property<?> half = propertyNamed(state, "half");
        if (half != null && "UPPER".equals(valueName(state, half))) {
            pos = pos.below();
        }
        // 2) 按 side / facing 配对，取 asLong 较小的一格 —— 左右两侧殊途同归
        Property<?> side = propertyNamed(state, "side");
        if (side != null) {
            String sideName = valueName(state, side);
            if ("LEFT".equals(sideName) || "RIGHT".equals(sideName)) {
                Direction facing = facingOf(state);
                if (facing != null) {
                    // 与 MTR getDoorValue() 同一套：RIGHT 走逆时针、LEFT 走顺时针，两边指向同一对
                    Direction dir = "RIGHT".equals(sideName)
                            ? facing.getCounterClockWise() : facing.getClockWise();
                    BlockPos other = pos.relative(dir);
                    if (other.asLong() < pos.asLong()) {
                        pos = other;
                    }
                }
            }
        }
        return pos.asLong();
    }

    /** 取方块状态里名字叫 {@code name} 的属性；没有返回 null。 */
    private static Property<?> propertyNamed(BlockState state, String name) {
        for (Property<?> p : state.getProperties()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 「一串」门 → 配置锚点（【1.20】用户点名：连在一起的屏蔽门 = 站台门 + 屏蔽门玻璃 +
    // 屏蔽门玻璃尾部相连的一串；修改其中一个就要一起修改这一串）
    // ------------------------------------------------------------------

    /** 是不是 MTR 屏蔽门**家族**（门 / 玻璃 / 玻璃端）——一串判定用的白名单。 */
    public static boolean isPsdFamily(BlockState state) {
        if (state == null) {
            return false;
        }
        String path = SmoothLift.registryPathOf(state);
        return path.startsWith("psd_") || path.startsWith("apg_");
    }

    /** 一串里**任意一格**报上来的「串锚点」：沿水平方向把相连的 psd/apg 方块走成一串，取 asLong 最小。 */
    private static final Map<Long, Long> RUN_CACHE = new HashMap<>();

    // ------------------------------------------------------------------
    // 【1.27】串身份的**首选来源**：MTR 站台
    // ------------------------------------------------------------------

    /**
     * 「连通串」→ 它所在 MTR 站台的「配置身份」。**只放认到站台的那些**：认不到
     * （= 没装 MTR4 / 这一串不在任何站台 4 格以内 / 站台数据还没同步到）的一律不进这张表，
     * 于是每次 {@link #runKeyOf} 都会再试一次，见 {@link #PLATFORM_NEXT_TRY} 的节流。
     *
     * <p>★ 键用**连通串的锚点**（{@link #RUN_CACHE} 的值）而不是门的锚点：一个连通串里的门
     * 必然同属一个站台，按串缓存能把 {@link MtrDwellAccess#platformIdAt} 的调用次数从
     * 「每扇门每 2 秒一次」降到「每串每 2 秒一次」（一串约 4 扇门）。
     */
    private static final Map<Long, Long> PLATFORM_CACHE = new HashMap<>();

    /**
     * 「下一次再试认站台」的 tick（键 = **连通串锚点**，与 {@link #PLATFORM_CACHE} 同键）。
     *
     * <p>★ 为什么需要它：MTR 的站台数据是**晚于方块**同步到客户端的（现场 LOG5：进世界 30 秒后
     * 才第一次「认到 MTR 站台」）。若把「认不到」的结果也永久缓存，早进世界的那几秒就会把整场
     * 都钉死成「连通串」身份，用户重启一次世界还可能好一会儿坏一会儿。
     * 也不能每帧都去试 —— {@link MtrDwellAccess#platformIdAt} 要遍历全部站台 + 反射读坐标，
     * 而 {@link #accept} 是**每扇门每帧**都跑。折中：认不到就记一笔「{@value #PLATFORM_RETRY_TICKS}
     * tick 后再试」，2 秒一次，认到为止。
     */
    private static final Map<Long, Long> PLATFORM_NEXT_TRY = new HashMap<>();

    /** 认站台失败的**重试间隔**（tick）。20 tick = 1 秒，这里取 2 秒。 */
    private static final int PLATFORM_RETRY_TICKS = 40;

    /** 已经打过日志的站台身份（一个站台一行，免得 12 扇门刷 12 行）。 */
    private static final Set<Long> PLATFORM_LOGGED = new HashSet<>();

    /** 【1.28】认站台失败的诊断：每个连通串最多每 60 秒打一行（见 {@link #explainPlatformFailure}）。 */
    private static final int PLATFORM_FAIL_LOG_EVERY = 1200;
    private static final Map<Long, Long> PLATFORM_FAIL_NEXT_LOG = new HashMap<>();
    private static final Set<Long> PLATFORM_FAIL_EXPLAINED = new HashSet<>();

    /**
     * 【1.28】认不到站台时，把「最近站台到底有多远」打出来（节流：同一串最多每 60 秒一行）。
     *
     * <p>现场 LOG6：x 轴上有一条线的屏蔽门（z=43 那排）**哪一扇都认不到站台** ⇒
     * 到站 / 进站报报站整条线被跳过，用户报「某些屏蔽门 arrive 直接没有声音」。
     * 没有这一行的话，「附近没有站台」和「站台在 4 格上限外」在日志里完全分辨不出来 ——
     * 两者都只是静默 continue。
     */
    private static void explainPlatformFailure(Level level, BlockPos raw, long doorKey, long flood,
                                               long now) {
        Long next = PLATFORM_FAIL_NEXT_LOG.get(flood);
        if (PLATFORM_FAIL_EXPLAINED.contains(flood) && next != null && now < next) {
            return;
        }
        PLATFORM_FAIL_NEXT_LOG.put(flood, now + PLATFORM_FAIL_LOG_EVERY);
        PLATFORM_FAIL_EXPLAINED.add(flood);
        String why = MtrDwellAccess.nearestPlatformExplain(
                BlockPos.getX(doorKey) + 0.5, BlockPos.getY(doorKey) + 0.5, BlockPos.getZ(doorKey) + 0.5);
        LOGGER.info("[SmoothLift/PsdChime] 屏蔽门 @[{},{},{}] 认不到 MTR 站台 ⇒ 到站播报 / 进站报站"
                        + "会跳过这一串（{}）",
                BlockPos.getX(doorKey), BlockPos.getY(doorKey), BlockPos.getZ(doorKey), why);
    }

    /**
     * 站台 id → 「配置身份」。{@code id} 由 {@link MtrDwellAccess#platformIdAt} 给出，
     * ★【09-28 续 6】**可正可负**（MTR4 是 {@code Random().nextLong()}）—— 所以这里**只能**
     * 对「已认到」的 id 调用（认不到是 {@link MtrDwellAccess#PLATFORM_ID_NONE}，不该传进来）。
     *
     * <p>★ 编码成 {@code Long.MIN_VALUE + id}（64 位回绕）。这是一个**单射**：{@code key} 可逆地
     * 还原出原 id（{@code id == key - Long.MIN_VALUE}），且 {@code id != PLATFORM_ID_NONE}
     * ⇔ {@code key != 0} —— 于是「连通串身份」与「站台身份」共用一个 long 命名空间而不互相
     * 覆盖语义（连通串身份是真实方块的 {@code BlockPos.asLong}）。
     *
     * <p>⚠️【09-28 续 6 更正】旧版注释声称「{@code id > 0} ⇒ key 恒为负 ⇒ 与 asLong 结构上不撞」，
     * 并给出 {@code x ∈ [-33554432, -9572904]} 的区间。★ 这**本来就不成立**：asLong 的
     * {@code (x&0x3FFFFFF) >= 2^25} ⇔ {@code x <= -1} 就会让 asLong 变负，任何负 x 的门都能落进
     * 负 key 区间；而且现在 id 本身可负，这个「区间不相交」的说法更无从谈起。
     * ⇒ 正确表述：两种身份**同域**，只靠「两个 64 位随机值恰好相等」的概率排除（约
     * {@code 2^-64} / 对），万一是同一座站台少一次播报 —— 与原实现同等量级，不做结构担保。
     *
     * @param platformId 已认到的站台 id（任意 64 位值，可正可负）
     */
    private static long platformKey(long platformId) {
        return Long.MIN_VALUE + platformId;
    }

    /** 【09-30 续 9】站台 key → 车站级 runKey 的缓存（车站 id 认不出时回落站台 key 本身）。 */
    private static final Map<Long, Long> STATION_RUN_KEY_CACHE = new HashMap<>();

    /**
     * 【09-30 续 9】把「站台身份」升到「**车站**身份」—— 门串划分 bug 的最后一环。
     *
     * <p>MTR 把一个车站的**每一侧**建成独立的站台对象（LOG11 现场：同名「世纪广场」的
     * 两个站台 @(-26,-21,41) 与 @(-33,-21,34)，各对应一侧门线）⇒ 以站台 id 当门串身份，
     * 两侧门串就永远各自一半、各自的设定（用户点名「一个门串被划分成了 2 部分」）。
     * 升到**车站**后：同站两侧（乃至被缺口切开的多段）门串共用一个 runKey ——
     * 配置 / 设定 / 去重 / 射程四处自动一致。
     *
     * <p>★ **时刻表查询不受影响**：{@code DoorView.platformId} 仍是每侧自己的站台 id
     * （上 / 下行到站信息不会串，见 PsdChimePlayer.tickArriveAnnounce 的取值顺序）。
     *
     * <p>车站 id 认不出（没装 MTR / 绑定失败）⇒ 原样回落站台 key，行为与旧版一致。
     */
    private static long stationRunKey(long platformKey) {
        return STATION_RUN_KEY_CACHE.computeIfAbsent(platformKey, k -> {
            long platformId = k - Long.MIN_VALUE;
            long stationId = MtrDwellAccess.stationIdForPlatform(platformId);
            return MtrDwellAccess.isPlatformKnown(stationId) ? platformKey(stationId) : k;
        });
    }

    /**
     * 算这一串门的**配置身份**（{@code runKey}）。★★【1.27】优先「**MTR 站台**」，
     * 认不到站台才回落「**连通串**」：
     *
     * <ol>
     *   <li>【1.27 新增，首选】这一格在哪个 MTR 站台上（{@link MtrDwellAccess#platformIdAt}，
     *       门到站台中轴线段的垂直距离 ≤ 4 格）⇒ 用 {@link #platformKey 站台身份}。
     *       一个站台的屏蔽门常常被实体缺口切成好几段连通块，以连通串为身份会让每段各自广播、
     *       各自判射程 ⇒ 远端那几段整段静默（现场 LOG5，见 {@link #platformKey} 与
     *       {@link #runKeyOf(Level, BlockState, BlockPos, long)} 里的取证数字）。</li>
     *   <li>【1.20，回落】从任意一格 PSD 方块出发，沿水平方向
     *       （{@code facing} 的左右两侧 = 站台延伸方向）走，把**连续相连**的 psd/apg 家族方块
     *       （门 / 玻璃 / 玻璃端）归成一组，取这组里 {@code asLong} 最小的方块坐标当串锚点。</li>
     * </ol>
     *
     * <p>★ 为什么「同一个身份」是硬要求：石斧 UI 里改音量 / 素材 / 开关时，用户点名
     * 「修改其中一个，就要一起修改这一串」（站台门 + 屏蔽门玻璃 + 玻璃尾部相连的那串）。
     * 播放端每扇门各自取样，但**读配置**全部按这个 key 读 ⇒ 设置一次，整串（1.27 起是整站台）生效。
     *
     * <p>★ 为什么从 {@code raw} 而不是 {@code doorKey} 出发：门格能折到锚点，
     * 玻璃格没有 side/half 折叠（它自己就是唯一身份），从哪一格进入都要把整串走一遍。
     */
    public static long runKeyOf(Level level, BlockPos raw) {
        if (level == null) {
            return raw.asLong();
        }
        return runKeyOf(level, level.getBlockState(raw), raw, anchorOf(level, raw));
    }

    private static long runKeyOf(Level level, BlockState state, BlockPos raw, long doorKey) {
        if (state == null || !isPsdFamily(state)) {
            return raw.asLong();
        }
        // ★★【1.27】首选「**MTR 站台**」当身份，认不到才回落「连通串」。
        //
        // 为什么必须升级（现场 LOG5 钉死的，数字可核验）：
        //   一个站台的屏蔽门**经常不是一串**：站台在实体上被缺口切开（MTR 摆门时会跳过若干格），
        //   LOG5 里三处站台各自对应 2~3 个连通串 ——
        //     站台 6553035176743660435 ↔ 最近门 @[-50,-20,32] / @[-42,-20,32] / @[-4,-20,32]
        //     站台 3090871749406205094 ↔ @[-26,-28,30] / @[-26,-28,47] / @[-26,-28,59]
        //     站台 6322577034832674307 ↔ @[-37,-28,42] / @[-37,-28,59] / @[-37,-28,67]
        //   （判据：日志「认到 MTR 站台」一个串只打一行，同一站台打出多行 ⇒ 那些是**不同的串**。
        //     同一份日志里 z=72 那 12 扇门（间距均匀 5、无缺口）只打出**一行** ⇒ 它才是单串，
        //     这正是 1.26 修好的那一个。）
        //   而「到站播报 / 进站报站」是按 runKey 读配置、按 runKey 去重、再按「这一串里离玩家最近
        //   的那扇门」判射程的 ⇒ 以连通串为单位时，同一站台会被拆成 N 段各自广播、各自判射程：
        //   站在中间那段，前后两段离玩家 > 射程(16) 就**整段静默**（用户原话：
        //   「前面的和后面的屏蔽门都没声音」；而中间那段是响的 ⇒ 三种假设里唯一对得上的）。
        //   ⇒ 身份升到站台之后，这三段自动共用一个 runKey：取到的是整站台最近的门，
        //     站在站台任何位置它都在身边几格内 ⇒ 一整站台只响一条、且处处听得见。
        //
        // ★ 为什么不直接把 1.26 的 broadcast 单拎出来改：runKey 是**配置**身份，用户点名
        //   「改其中一个就要一起改这一串」；一段播报读配置也得读同一份。把身份本身升到站台，
        //   配置 / 去重 / 射程 / 声源四处自动一致，不留特例。
        // ★ 回落用的连通串：纯几何（只看方块），算一次永不变 ⇒ 直接永久缓存。
        //   ★ 缓存键用 doorKey（一扇门一个，左右上下全折进去 ⇒ 同一扇门不同格只算一次）。
        //   它同时也是**站台身份的缓存键**：一个连通串里的门必然同属一个站台，
        //   按串缓存 ⇒ 站台查询从「每扇门每 2 秒一次」降到「每串每 2 秒一次」（少约 4 倍）。
        long flood = RUN_CACHE.computeIfAbsent(doorKey, k -> floodRun(level, raw));
        Long platform = PLATFORM_CACHE.get(flood);
        if (platform != null) {
            // 【09-30 续 9】升到车站级：同站两侧门串共用一个 runKey（见 stationRunKey）。
            return stationRunKey(platform);
        }
        // 站台身份会「晚到」（MTR 的站台数据比方块晚同步），所以认不到时**不写缓存**，
        // 只排一个 2 秒后的重试；认到为止。见 PLATFORM_NEXT_TRY 的注释。
        long now = level.getGameTime();
        Long nextTry = PLATFORM_NEXT_TRY.get(flood);
        if (nextTry == null || now >= nextTry) {
            // ★ 用**锚点**（doorKey）的坐标去问，不用 raw：同一扇门的上下半格/左右半扇会报到
            //   不同的 raw，用锚点才能保证「一扇门只认一次、每次认的都是同一个位置」，
            //   也才与播放链路（进站报站那侧拿的是 DoorView 的锚点坐标）逐位一致。
            //   ★【09-28 续 6】判据是 isPlatformKnown，**不是 id > 0**：MTR4 的站台 id 是
            //   Random().nextLong()，约一半的站台 id 是负数 —— 旧写法把这几座站台整个判成
            //   「认不到」，它们的进站播报在读配置之前就被 continue 掉（LOG12 实证）。
            long id = MtrDwellAccess.platformIdAt(BlockPos.getX(doorKey) + 0.5,
                    BlockPos.getY(doorKey) + 0.5, BlockPos.getZ(doorKey) + 0.5);
            if (MtrDwellAccess.isPlatformKnown(id)) {
                long key = platformKey(id);
                PLATFORM_CACHE.put(flood, key);
                PLATFORM_NEXT_TRY.remove(flood);
                if (PLATFORM_LOGGED.add(key)) {
                    LOGGER.info("[SmoothLift/PsdChime] 屏蔽门 @[{},{},{}] 认到 MTR 站台 id={} ⇒ 这一串的"
                                    + "配置身份改成「整个站台」：同一站台里被实体缺口切开的几段门从此共用"
                                    + "一个身份，到站播报 / 进站报站只响一条、站在站台任何位置都听得见",
                            BlockPos.getX(doorKey), BlockPos.getY(doorKey), BlockPos.getZ(doorKey), id);
                }
                // 【09-30 续 9】再升到车站级（同站两侧共用，见 stationRunKey）。
                return stationRunKey(key);
            }
            // ★★【1.31】认不到站台时，向「最近已经认到站台的连通串」**借用**它的站台身份。
            //   现场（用户原话）：列车行进方向最前面的 2 扇门是独立一串、其它门是另一串 ——
            //   那 2 扇门在站台端头，离站台中轴超过 4 格，platformIdAt 认不到 ⇒ runKey 停留在
            //   连通串身份 ⇒ 进站报报站（tickArriveAnnounce）在**读配置之前**就被「认不到站台」
            //   挡住 continue（1.28 的诊断逻辑，判据现在是 isPlatformKnown），于是 UI 按串设置的
            //   pbmarrive 素材/秒数根本走不到读取那一步 —— 用户：「UI 设置没有用，指令设置可以」
            //   （指令设的是维度默认，主串能读到 ⇒ 有效）。借到之后两串并入同一个站台身份 ⇒
            //   配置 / 去重 / 射程 / 声源四处自动按整站台走。
            long borrowed = borrowPlatformId(doorKey);
            if (MtrDwellAccess.isPlatformKnown(borrowed)) {
                long key = platformKey(borrowed);
                PLATFORM_CACHE.put(flood, key);
                PLATFORM_NEXT_TRY.remove(flood);
                if (PLATFORM_LOGGED.add(key)) {
                    LOGGER.info("[SmoothLift/PsdChime] 屏蔽门 @[{},{},{}] 认不到站台（最近站台在 4 格外），"
                                    + "借用相邻已认站台串的站台 id={} ⇒ 两串并入同一个配置身份，"
                                    + "到站播报 / 进站报站按整站台走",
                            BlockPos.getX(doorKey), BlockPos.getY(doorKey), BlockPos.getZ(doorKey), borrowed);
                }
                // 【09-30 续 9】再升到车站级（同站两侧共用，见 stationRunKey）。
                return stationRunKey(key);
            }
            PLATFORM_NEXT_TRY.put(flood, now + PLATFORM_RETRY_TICKS);
            // 【1.28】认不到也要留脚印：最近站台差多远 / 还是根本没有站台（节流见方法内）。
            explainPlatformFailure(level, raw, doorKey, flood, now);
        }
        return flood;
    }

    /**
     * 【1.31】站台身份认不到时的兜底 —— 向「最近已经认到站台的连通串」借用它的站台 id。
     *
     * <p>为什么会有这一层：连通串身份只代表「物理连在一起的方块」，不保证它落在
     * 某个 MTR 站台的 4 格横向范围内（站台**端头**的出入口门、或转弯贴墙段常常会超）。
     * 这类门在 {@code platformIdAt} 眼里「认不到站台」，于是进站报站被「读配置之前」那道
     * platformId 闸门静默挡住 —— UI 按串设的 pbmarrive 素材/秒数成了死配置。
     *
     * <p>借用的前提（防串台 / 防误认，宁可保持连通串也不认错）：
     * <ul>
     *   <li>距离判据 = {@link #PLATFORM_BORROW_DIST}（锚点与已认串锚点都是
     *       {@code BlockPos.asLong}，直接解码出坐标算 XZ 距离）；</li>
     *   <li>同层判据 = 两个锚点 |Δy| ≤ 8（与 MtrDwellAccess.MAX_DY 同值）；</li>
     *   <li>唯一性判据 = 次近已认串比最近已认串远 ≥ {@link #PLATFORM_BORROW_MARGIN}
     *       （两个候选都差不多近时说明门夹在两个站台之间，不借）。</li>
     * </ul>
     *
     * @return 借到的站台 id（可正可负）；{@link MtrDwellAccess#PLATFORM_ID_NONE} = 没有可借的
     *         （保持连通串身份）
     */
    private static final double PLATFORM_BORROW_DIST = 12.0;
    private static final double PLATFORM_BORROW_MARGIN = 8.0;

    private static long borrowPlatformId(long doorKey) {
        double dx = BlockPos.getX(doorKey);
        double dy = BlockPos.getY(doorKey);
        double dz = BlockPos.getZ(doorKey);
        // ★★【09-30 续 8】先找「同一轴线」上的已认串（z 或 x 相差 ≤ 2 ⇒ 同一条门线）。
        //   一个站台侧被缺口切开的两段**必然共线**；而**另一侧**的站台与它不共线，
        //   不会搅浑水 —— 所以共线借用**不要求**下面那条 margin（两侧站台的已认串
        //   与缺口段距离接近时，margin 会误判成「分不清」⇒ 借不到 ⇒ 门串维持分裂，
        //   正是 LOG10 用户看到的「一个门串两部分、各自的范围和设定」）。
        //   距离封 48：同一条直线上再远就可能是**别的车站**的站台（MTR 相邻车站
        //   间距通常 > 100 格），不能跨站借。取**最近**的那一条共线串。
        long bestCollinear = Long.MIN_VALUE;
        double bestCollinearDist = Double.MAX_VALUE;
        for (Map.Entry<Long, Long> e : PLATFORM_CACHE.entrySet()) {
            long other = e.getKey();
            if (Math.abs(BlockPos.getY(other) - dy) > 8.0) {
                continue;
            }
            boolean collinear = Math.abs(BlockPos.getZ(other) - dz) <= 2.0
                    || Math.abs(BlockPos.getX(other) - dx) <= 2.0;
            if (!collinear) {
                continue;
            }
            double dist = Math.hypot(BlockPos.getX(other) - dx, BlockPos.getZ(other) - dz);
            if (dist <= 48.0 && dist < bestCollinearDist) {
                bestCollinearDist = dist;
                bestCollinear = other;
            }
        }
        if (bestCollinear != Long.MIN_VALUE) {
            return PLATFORM_CACHE.get(bestCollinear) - Long.MIN_VALUE;
        }
        long bestFlood = Long.MIN_VALUE;
        double best = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        for (Map.Entry<Long, Long> e : PLATFORM_CACHE.entrySet()) {
            long other = e.getKey();
            double oy = BlockPos.getY(other);
            if (Math.abs(oy - dy) > 8.0) {   // 同层门槛，与 MtrDwellAccess.MAX_DY 同值（那边是 private）
                continue;
            }
            double odx = BlockPos.getX(other) - dx;
            double odz = BlockPos.getZ(other) - dz;
            double dist = Math.sqrt(odx * odx + odz * odz);
            if (dist < best) {
                second = best;
                best = dist;
                bestFlood = other;
            } else if (dist < second) {
                second = dist;
            }
        }
        if (bestFlood == Long.MIN_VALUE || best > PLATFORM_BORROW_DIST
                || second - best < PLATFORM_BORROW_MARGIN) {
            return MtrDwellAccess.PLATFORM_ID_NONE;
        }
        return PLATFORM_CACHE.get(bestFlood) - Long.MIN_VALUE;
    }

    /**
     * 从任意一格水平走完整串；返回连通组里 {@code asLong} 最小的一格（同一串内任何起点都收敛到它）。
     *
     * <p>★ 起点先折到**下半格**（门与玻璃都有 {@code half=UPPER} 的同伴格）：
     * 从上半格出发会把串锚点抬高一格，同一个物理串会算出两个 key——「改一串」就断成两截。
     */
    /**
     * ★★【09-30 续 8】沿四个方向各最多跨 {@link #FLOOD_BRIDGE} 格**空档**把同一物理串接起来 ——
     *   这是 1.20.1 一路带过来的「门串划分」bug 的修复：MTR 摆门时会在站台中部留出整段空档
     *   （楼梯 / 扶梯井 / 楼梯口，LOG10 现场：同一侧的门被 5~8 格空气断成两截），纯 4 邻接的
     *   洪水填充会把**一个物理串**切成两个连通块 ⇒ 两块各自认站台 / 各自借身份，
     *   认不到的那块就带着**独立的 runKey** —— 独立的范围、独立的设定，「改一串」断成两截。
     *   桥接后：同一侧整条门线（含被空档隔开的段）收敛为**一个**连通块、一个 runKey。
     *
     * <p>走法与 {@link #floodRun} / {@link #runWalls} 共用（三个入口一份实现）：
     * {@code wallsOut != null} 时顺带收集串里的幕墙 / 幕墙尾部（非门的 psd 家族方块）。
     *
     * @return 连通组的串锚点（组内 {@code asLong} 最小的一格；同一串任何起点都收敛到它）
     */
    private static long psdWalk(Level level, BlockPos start, java.util.List<Long> wallsOut) {
        BlockPos pos = start;
        Property<?> half = propertyNamed(level.getBlockState(pos), "half");
        if (half != null && "UPPER".equals(valueName(level.getBlockState(pos), half))) {
            pos = pos.below();
        }
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        queue.add(pos);
        seen.add(pos.asLong());
        long min = pos.asLong();
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (cur.asLong() < min) {
                min = cur.asLong();
            }
            if (wallsOut != null) {
                BlockState cs = level.getBlockState(cur);
                if (isPsdFamily(cs) && !SmoothLift.isPsdDoor(cs)) {
                    wallsOut.add(cur.asLong());
                }
            }
            for (int d = 0; d < 4; d++) {
                // 【09-30 续 8】沿这个方向从紧邻格开始往外扫：psd 家族 = 直接连通；
                //   空档（非 psd 家族）就继续往前扫，最多 FLOOD_BRIDGE 格，尽头仍是 psd 家族
                //   ⇒ 认定是同一个串被缺口隔开，把尽头那格接进来。四个方向各扫各的，
                //   与原 4 邻接走法在「无空档」时逐位等价。
                int sx = d == 2 ? 1 : d == 3 ? -1 : 0;
                int sz = d == 0 ? 1 : d == 1 ? -1 : 0;
                BlockPos scan = cur;
                for (int step = 0; step < FLOOD_BRIDGE; step++) {
                    scan = new BlockPos(scan.getX() + sx, scan.getY(), scan.getZ() + sz);
                    if (isPsdFamily(level.getBlockState(scan))) {
                        if (seen.add(scan.asLong())) {
                            queue.add(scan);
                        }
                        break;
                    }
                }
            }
        }
        return min;
    }

    /**
     * 【09-30 续 8】沿串方向最多跨多少格空档把被隔开的门接回同一串。
     *
     * <p>LOG10 现场的缺口 = 5~8 格（楼梯口两侧的门，门距 4、中间空 5~8）。
     * 上限取 8 还有一个安全余量考量：对面站台的门线与这一侧相距 ≥ 10 格，
     * 桥接**不可能**把两侧的门线接在一起。
     */
    private static final int FLOOD_BRIDGE = 8;

    private static long floodRun(Level level, BlockPos start) {
        return psdWalk(level, start, null);
    }

    /**
     * 【1.29】这一串里**幕墙 / 幕墙尾部**的位置（不含门）。与 {@link #floodRun} 同一次走法，
     * 走完把「非门」的 psd 家族方块（{@code psd_glass*} / {@code apg_glass*} / {@code *_end}）
     * 收成列表。
     *
     * <p>按连通串锚点 {@code flood} 缓存：同一个串只在第一次见时走一次，之后每帧直接取。
     * 幕墙是静态方块，位置不会变；它们的生命周期跟随本串的门（见 {@link #accept} 里
     * 「每扇门每帧刷新本串墙成员的 tick」）。
     */
    private static final Map<Long, java.util.List<Long>> RUN_WALL_CACHE = new HashMap<>();

    private static java.util.List<Long> runWalls(Level level, BlockPos start, long flood) {
        return RUN_WALL_CACHE.computeIfAbsent(flood, k -> {
            // 【09-30 续 8】走法与 {@link #psdWalk}（含跨空档桥接）共用一份 ——
            //   否则门串桥接合并之后，幕墙成员还只收得到**一半**（缺口那边的幕墙漏掉）。
            java.util.List<Long> walls = new java.util.ArrayList<>();
            psdWalk(level, start, walls);
            return walls;
        });
    }

    /** 取某个属性在当前状态下的值，用 {@code toString()} 拿它的名字（枚举常量名，如 UPPER / RIGHT）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(BlockState state, Property<?> property) {
        Object value = state.getValue((Property) property);
        return value == null ? "" : value.toString();
    }

    /** 取 {@code facing} 属性（原版 {@link DirectionProperty}，不需要认识 MTR 的类型）。 */
    private static Direction facingOf(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof DirectionProperty dp && "facing".equals(dp.getName())) {
                return state.getValue(dp);
            }
        }
        return null;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private static int keyX(long key) {
        return BlockPos.getX(key);
    }

    private static int keyY(long key) {
        return BlockPos.getY(key);
    }

    private static int keyZ(long key) {
        return BlockPos.getZ(key);
    }
}
