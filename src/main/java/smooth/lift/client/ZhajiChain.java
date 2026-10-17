package smooth.lift.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 【09-30 续】「一组闸机」的身份：把**连着的**闸机算成一条，并给它一个稳定的锚点。
 *
 * <h2>「一段」与「一组」</h2>
 * <ul>
 *   <li><b>一段</b>（本类算的东西）= 从任意一格闸机出发，沿 **6 邻域**（上下左右前后）洪水填充
 *       能连到的全部闸机方块。玩家在站台上摆的那一排闸机就是「一段」。</li>
 *   <li><b>一组</b>（配置的单位）= 同一段里**功能相同**的那些：这一段的进站闸机是一组、
 *       这一段的出站闸机是另一组。用户原话「连着的相同功能（进站/出站）闸机为一组，
 *       可以单独调整一组闸机的音效和音量」。</li>
 * </ul>
 * 所以配置身份 = {@code (段锚点, in|out)}；本类只负责给「段锚点」——
 * 见 {@link EscalatorSpeedManager#getZhajiToneAudio(Level, String, long)} 那一层。
 *
 * <h2>锚点 = 段内 {@code BlockPos.asLong} 最小的那一格</h2>
 * 选「最小」而不是「起点」或「最大」，是为了让<<从段内哪一格出发都得到同一个锚点>>：
 * 右键这一段的任何一台闸机、播放端从这一段的任何一格收到声音，算出来的键都相同。
 * （与 {@link PsdDoorTracker#anchorOf(Level, BlockPos)} 取「两格里 asLong 较小的」同一个用意。）
 *
 * <h2>边界与失败姿态</h2>
 * <ul>
 *   <li>没加载的相邻格**不展开**也不报错 —— 闸机就在眼前、区块必然已加载，缺的只可能是
 *       远处没进视距的那一头（展开它反而会把「本段」越算越长）。</li>
 *   <li>整段超过 {@link #MAX_BLOCKS} 时**截断**（取已经填到的那部分算锚点）—— 宁可给一个
 *       可能不完整的锚点，也不要在病态世界里卡住渲染/主线程。</li>
 *   <li>起点不是闸机 → {@link EscalatorSpeedManager#ZHAJI_GROUP_NONE}（调用方退回「维度默认层」）。</li>
 * </ul>
 *
 * <h2>缓存</h2>
 * 同一格反复问（播放端每过一个人问一次）没必要每次重填，所以按「起点坐标 → 锚点」缓存，
 * 存 {@link #CACHE_TTL_TICKS} tick（= 2 秒）——玩家加/拆一格闸机之后，最多 2 秒就自然重算。
 * 断线时 {@link #clear()}（换存档 / 换维度不要再信旧锚点）。
 */
public final class ZhajiChain {

    private ZhajiChain() {
    }

    /**
     * 一次洪水填充最多看多少格（截断上限）。
     *
     * <p>取值理由：真要摆成一条的闸机不会超过几百格；4096 已经远大于任何真实车站的一排，
     * 而它保证了「有人在超平坦世界里铺一整片闸机」时也只是一次性的几千格遍历，不会卡死。
     */
    private static final int MAX_BLOCKS = 4096;

    /** 缓存有效期（tick）。40 tick = 2 秒 —— 加/拆闸机后最多 2 秒重算一次。 */
    private static final long CACHE_TTL_TICKS = 40L;

    /** 起点坐标 → 锚点（带时间戳）。只在客户端主线程用，不需要同步。 */
    private static final Map<BlockPos, CacheEntry> CACHE = new HashMap<>();

    private record CacheEntry(long anchor, long tick) {
    }

    /**
     * 这一格闸机所在**那一段**的锚点（= 段内 {@code asLong} 最小的那格）。
     *
     * @return {@link EscalatorSpeedManager#ZHAJI_GROUP_NONE} = 这一格不是闸机（或世界为空）
     */
    public static long anchorOf(Level level, BlockPos start) {
        if (level == null || start == null) {
            return EscalatorSpeedManager.ZHAJI_GROUP_NONE;
        }
        BlockState startState = level.getBlockState(start);
        if (!SmoothLift.isZhajiBarrier(startState)) {
            return EscalatorSpeedManager.ZHAJI_GROUP_NONE;
        }
        long now = level.getGameTime();
        CacheEntry cached = CACHE.get(start);
        if (cached != null && now - cached.tick() <= CACHE_TTL_TICKS) {
            return cached.anchor();
        }
        long anchor = flood(level, start);
        CACHE.put(start.immutable(), new CacheEntry(anchor, now));
        return anchor;
    }

    /**
     * 从 {@code start} 出发洪水填充整段，返回途中见过的**最小** {@code asLong}。
     *
     * <p>{@code HashSet} 存 {@code asLong} 而不是 {@code BlockPos}：一段几百格时省下几百个装箱对象，
     * 而且 {@code asLong} 本来就是我们要比较的那个量。
     */
    private static long flood(Level level, BlockPos start) {
        long anchor = start.asLong();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(anchor);
        queue.add(start.immutable());
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_BLOCKS) {
            BlockPos pos = queue.poll();
            visited++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        // 只走 6 邻域（面相邻）：斜着相连的两排闸机不是「一排」——
                        // 站台两条平行的闸机线之间往往就隔着一格，用 26 邻域会把它们并成一段。
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!level.isLoaded(next)) {
                            continue; // 没加载：不展开（见类注释的边界说明）
                        }
                        long key = next.asLong();
                        if (!seen.add(key)) {
                            continue;
                        }
                        if (!SmoothLift.isZhajiBarrier(level.getBlockState(next))) {
                            continue;
                        }
                        if (key < anchor) {
                            // 比大小用**普通的 signed 比较**：这里要的只是「一个与起点无关的确定性选择」，
                            // 不是「坐标字典序」（BlockPos.asLong 把 x 放在高位、y 夹在中间再 z，
                            // 且负坐标是补码 —— 但只要有确定性、且客户端只有这一处算锚点，就没问题）。
                            anchor = key;
                        }
                        queue.add(next.immutable());
                    }
                }
            }
        }
        return anchor;
    }

    /** 断线 / 换存档时清缓存（锚点是世界相关的，别跨世界复用）。 */
    public static void clear() {
        CACHE.clear();
    }
}
