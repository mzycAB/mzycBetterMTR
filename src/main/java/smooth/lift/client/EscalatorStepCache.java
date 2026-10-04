package smooth.lift.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 扶梯阶梯面「静态几何」缓存 —— 【1.29】全量重写的核心。
 *
 * <h2>它替掉的是什么</h2>
 * 旧版（1.24–1.28）每帧都要把视野内**每一个阶梯方块**的 ~380 个顶点重新算一遍、写进
 * {@code BufferBuilder}（MTR 把 45° 斜面雕成 50 个元素 / 95 个阶梯面，所以一个方块就是
 * 380 个顶点）。那笔开销随「视野内阶梯方块数」线性增长，是扶梯一多就掉帧的主因。
 *
 * <p>而其中**真正随时间变的只有一个数**：这一帧该显示第几帧贴图。它已经在
 * {@link EscalatorStepTextures} 里被搬到了「这一批绘制绑哪张帧贴图」上，
 * 于是顶点数据**与时间无关**了 —— 可以缓存。
 *
 * <h2>缓存的粒度与坐标系</h2>
 * 键 = {@code 16³ 分段键}（{@code EscalatorStepIndex.Section.key()}），
 * 槽 = {@code 速度组 × 贴图族}（{@link EscalatorStepGroups#SLOT_COUNT} 个）。
 *
 * <p>顶点写的是**分段局部坐标**（0..16），绘制时由原版的 {@code ChunkOffset} uniform 补上
 * {@code 原点 − 相机位置}。这一点是照着原版 {@code LevelRenderer.renderSectionLayer} 抄的：
 * 它画区块也是「顶点存段内坐标 + 每段设一次 ChunkOffset」，而不是每段推一次矩阵栈。
 * 两个好处：
 * <ul>
 *   <li>顶点是小数（0..16），float 精度极好，远距离不会抖；</li>
 *   <li>每帧每段只多**一次 uniform 上传**，没有任何矩阵乘法。</li>
 * </ul>
 *
 * <h2>什么时候重建</h2>
 * <ol>
 *   <li><b>段版本戳变了</b>（{@code Section.revision()}）：方块被增删，或者**坐标没动但状态变了**
 *       （刷子刷停、改朝向）。索引每 10 刻重扫一遍并比对内容指纹，所以编辑最多 0.5 秒后生效
 *       —— 与旧版行为一致，但现在渲染端**不需要为此遍历任何方块**，只是比一个 long。</li>
 *   <li><b>组表版本变了</b>（{@link EscalatorStepGroups#epoch()}）：出现了新的动画速度、
 *       速度配置被改、退出世界、资源重载。此时「哪条扶梯归哪一组」可能变了，整片作废。</li>
 *   <li><b>轮转自愈刷新</b>：见下面「为什么必须保留一遍定期重建」。</li>
 * </ol>
 *
 * <h2>为什么必须保留一遍「定期重建」（这条很容易被当成冗余优化掉，但会引入一个坏 bug）</h2>
 * 顶点里烘焙了**每方块一次的光照**（{@code LevelRenderer.getLightColor}）。它的取值会变：
 * 旁边放一根火把、拆掉一盏灯、红石灯点亮……而<b>这些变化不会改变区块里任何一个方块的状态</b>
 * （方块本身没变，变的是它收到的光），所以上面第 1 条的版本戳**看不到它**。
 *
 * <p>原版是靠「光照更新就把这个段标脏、重新编译网格」解决的。我们没有接管那套脏标记，
 * 于是如果只做 1、2，表现会是「**火把照亮了扶梯外壳，却没照亮台阶面**」——
 * 一个很具体、一定会被用户报回来的观感 bug。
 *
 * <p>所以这里加一道**轮转刷新**：每帧按「本帧可见顶点总数 / {@value #REFRESH_FRAMES}」的预算
 * 重建一小部分可见段。于是
 * <ul>
 *   <li>光照这类漏判最多 {@value #REFRESH_FRAMES} 帧（约 1 秒）内自愈；</li>
 *   <li>成本恒定在全量重建的 {@code 1/{@value #REFRESH_FRAMES}}，与视野规模无关；
 *       视野很小的时候预算按顶点数自动变小，不会出现「小场景里每帧全量重建」这种把优化做反的情况。</li>
 * </ul>
 * 一句话：**正确性靠定期刷新兜底，响应速度靠版本戳** —— 编辑是即时的，光照这类慢变量允许 1 秒延迟。
 *
 * <h2>最坏一帧不会比旧版更差</h2>
 * 走进一个新区域时，所有可见段都要现建，那一帧的开销 ≈ 旧版**每一帧**的开销。
 * 也就是说这次重写**不可能把最坏情况做坏**，只是把「每帧都付」变成「只在变化时付」。
 *
 * <h2>显存 / GL 对象上限</h2>
 * 缓存段数有上限（{@value #MAX_CACHED_SECTIONS}），超了按「最久没用过」淘汰并
 * {@code VertexBuffer.close()} 释放 GL 缓冲，不会无限涨。本帧可见的段永不淘汰。
 */
public final class EscalatorStepCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 最多缓存多少个分段。一座城市里「含扶梯的分段」通常远不到这个数；
     * 到了上限就按 LRU 淘汰，保证显存与 GL 对象数有硬顶。
     */
    private static final int MAX_CACHED_SECTIONS = 1536;

    /**
     * 轮转自愈刷新的周期（帧）：让每个可见段大约每这么多帧被无条件重建一次。
     * 60 帧 ≈ 1 秒，足以让光照变化看起来是「立刻」的。
     */
    private static final int REFRESH_FRAMES = 60;

    /** 重建用的 BufferBuilder 初始容量（字节）。会随需要增长，这里只是免掉早期扩容。 */
    private static final int BUILDER_CAPACITY = 1 << 18;

    /** 颜色通道归一化：{@code (argb >> n) & 0xFF} 之后乘它得到 0..1。 */
    static final float COLOR_CHANNEL_INV = 1.0F / 255.0F;

    /**
     * 一个分段的缓存：每个「速度组 × 贴图族」一个 {@link VertexBuffer}。
     *
     * <p>{@code buffers} 按 {@code EscalatorStepGroups.SLOT_COUNT} 固定开数组（默认 64），
     * 用不到的槽是 {@code null}。之所以不做成 Map：槽号是密集小整数，
     * 遍历时不需要任何哈希/装箱，而遍历是每帧都要做的事。
     */
    public static final class CachedSection {
        private final long key;
        private final int originX;
        private final int originY;
        private final int originZ;
        /**
         * 这个段里的阶梯方块坐标。**缓存里存的是引用**：段对象换了（内容变了）时
         * {@link #rebuild} 会用新的数组覆盖它，所以轮转刷新用的永远是最后一次已知的坐标。
         */
        private BlockPos[] positions = EMPTY;
        /** 建这份几何时的 {@code Section.revision()}；和当前段不一致就重建。 */
        private long builtRevision = Long.MIN_VALUE;
        /** 建这份几何时的组表版本；不一致就重建。 */
        private long builtEpoch = Long.MIN_VALUE;
        /** 最后一次被本帧用到的时间戳（LRU 淘汰依据，也用来判「本帧可见」）。 */
        private long lastUsedFrame;
        /** 是否已经建过一次（区分「建好了、这段确实没台阶面」与「还没建」）。 */
        private boolean built;

        private final VertexBuffer[] buffers = new VertexBuffer[EscalatorStepGroups.SLOT_COUNT];
        private final int[] vertexCounts = new int[EscalatorStepGroups.SLOT_COUNT];
        /** 有内容的槽号（紧凑列表，绘制与统计只遍历它）。 */
        private final int[] slots = new int[EscalatorStepGroups.SLOT_COUNT];
        private int slotCount;

        private CachedSection(long key, int originX, int originY, int originZ) {
            this.key = key;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        public int originX() {
            return originX;
        }

        public int originY() {
            return originY;
        }

        public int originZ() {
            return originZ;
        }

        /** 本段有内容的槽数（绘制时按它遍历）。 */
        public int slotCount() {
            return slotCount;
        }

        public int slotAt(int index) {
            return slots[index];
        }

        public VertexBuffer buffer(int slot) {
            return buffers[slot];
        }

        public int vertexCount(int slot) {
            return vertexCounts[slot];
        }

        /** 本段所有槽的顶点数之和（刷新预算与统计用）。 */
        int totalVertices() {
            int total = 0;
            for (int i = 0; i < slotCount; i++) {
                total += vertexCounts[slots[i]];
            }
            return total;
        }

        /**
         * 重建前清掉「上一轮的槽表」，但**保留**已分配的 GL 缓冲（原地复用）。
         *
         * <p>和 {@link #releaseBuffers()} 的区别很关键：重建是「同一份缓冲换内容」，
         * {@code VertexBuffer.upload()} 本来就能原地重写（原版区块重建也是这么做的）。
         * 如果每次重建都 close() 再 new，轮转自愈刷新（每帧重建 1/60 的可见段）就会变成
         * 「每帧删掉并新建几十个 GL 缓冲」—— 那正是这套缓存本来要省掉的开销。
         */
        private void resetForRebuild() {
            for (int i = 0; i < EscalatorStepGroups.SLOT_COUNT; i++) {
                vertexCounts[i] = 0;
            }
            slotCount = 0;
        }

        private void releaseBuffers() {
            for (int i = 0; i < EscalatorStepGroups.SLOT_COUNT; i++) {
                if (buffers[i] != null) {
                    final VertexBuffer buffer = buffers[i];
                    // GL 删除必须发生在渲染线程：onDisconnect 由 Netty 连接线程触发
                    // （SmoothLiftClient 的连接断开回调），直接 close() 会报
                    // "Rendersystem called from wrong thread"，并可能连带破坏渲染线程的
                    // LWJGL MemoryStack 状态（崩溃日志里紧随其后的 Out of stack space）。
                    // 延迟到渲染线程下一帧执行；引用先置空，不会出现并发使用。
                    try {
                        RenderSystem.recordRenderCall(buffer::close);
                    } catch (Exception e) {
                        // 渲染循环已停止（如游戏关闭中）：缓冲交由驱动回收，不阻塞清理。
                        try {
                            buffer.close();
                        } catch (Exception ignore) {
                        }
                    }
                    buffers[i] = null;
                }
                vertexCounts[i] = 0;
            }
            slotCount = 0;
        }
    }

    private static final BlockPos[] EMPTY = new BlockPos[0];

    private static final Map<Long, CachedSection> CACHE = new HashMap<>(1024);

    /** 本帧可见的缓存段（数组跨帧复用，避免每帧新建列表）。 */
    private static CachedSection[] visible = new CachedSection[256];
    private static int visibleCount;

    /** 全量重建共用的 {@link BufferBuilder}（重建很稀疏，没必要每次新建一个原生缓冲）。 */
    private static BufferBuilder builder;

    private static long frameStamp;
    /** 轮转刷新的游标（在 {@link #visible} 上走）。 */
    private static int refreshCursor;

    // ---- 可核验计数 ----
    private static int statsBuiltByRevision;
    private static int statsBuiltByRefresh;
    private static int statsBuiltVertices;
    private static int statsDrawnVertices;
    /**
     * 重建几何用掉的纳秒（剔除/刷新两处都记在这里）。
     *
     * <p>必须单独计时：重建是「随变化偶发」的，如果把它混进「剔除」或「绘制」里，
     * 一旦某帧住进新区域就会看到一个巨大的假耗时，从而误判瓶颈在哪一边。
     */
    private static long statsBuildNanos;

    private EscalatorStepCache() {
    }

    /** 本帧开始：清空可见列表与计数。 */
    public static void beginFrame(long stamp) {
        frameStamp = stamp;
        visibleCount = 0;
        statsBuiltByRevision = 0;
        statsBuiltByRefresh = 0;
        statsBuiltVertices = 0;
        statsBuildNanos = 0L;
    }

    /**
     * 登记一个「本帧可见」的段，必要时重建它的几何。
     *
     * <p>这是每帧每段唯一要做的事：比两个 long（段版本戳、组表版本）——
     * 相等就直接进可见列表，<b>一个方块都不碰</b>。这就是「玩家看不见的不渲染、
     * 看得见但没变化的也不重算」在代码上的样子。
     */
    public static void touch(EscalatorStepIndex.Section section, ClientLevel level, long epoch) {
        CachedSection cached = CACHE.get(section.key());
        if (cached == null) {
            cached = new CachedSection(section.key(), section.originX(), section.originY(),
                    section.originZ());
            // 先打上「本帧用过」再插入：否则刚插进来的这一份会因为 lastUsedFrame 还是 0
            // 而被 LRU 当成最老的一份当场淘汰掉。
            cached.lastUsedFrame = frameStamp;
            CACHE.put(section.key(), cached);
            addVisible(cached);
            if (CACHE.size() > MAX_CACHED_SECTIONS) {
                evictOldest();
            }
            rebuild(cached, section.positions(), section.revision(), level, epoch, false);
            return;
        }
        cached.lastUsedFrame = frameStamp;
        addVisible(cached);
        if (!cached.built
                || cached.builtRevision != section.revision()
                || cached.builtEpoch != epoch) {
            rebuild(cached, section.positions(), section.revision(), level, epoch, false);
        }
    }

    /**
     * 【1.29】轮转自愈刷新：按「可见顶点总数 / {@value #REFRESH_FRAMES}」的预算，
     * 每帧无条件重建一小部分可见段。
     *
     * <p>存在的理由是**顶点里烘焙了每方块一次的光照**，而光照变化不会改方块状态、
     * 因而版本戳看不见它（详见类注释）。这一遍让这类漏判最多 1 秒内自愈。
     *
     * <p>预算按顶点数缩放而不是按段数，是为了让开销恒定在「全量重建的 1/60」：
     * 视野小的时候预算自动变小，不会出现「小场景里每帧全量重建」。
     *
     * @return 本次刷新重建的段数（性能计数用）
     */
    public static int refreshVisible(ClientLevel level, long epoch) {
        if (visibleCount == 0 || level == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < visibleCount; i++) {
            total += visible[i].totalVertices();
        }
        int budget = Math.max(1, total / REFRESH_FRAMES);
        int rebuilt = 0;
        int consumed = 0;
        int index = refreshCursor;
        if (index < 0 || index >= visibleCount) {
            index = 0;
        }
        // 从游标往后扫，扫过一轮就停 —— 单帧开销有硬顶，不会因为预算太小而空转一圈。
        while (rebuilt < visibleCount && consumed < budget) {
            if (index >= visibleCount) {
                index = 0;
            }
            CachedSection cached = visible[index++];
            int before = cached.totalVertices();
            rebuild(cached, cached.positions, cached.builtRevision, level, epoch, true);
            consumed += Math.max(1, before);
            rebuilt++;
        }
        refreshCursor = index < visibleCount ? index : 0;
        return rebuilt;
    }

    /** 本帧可见的缓存段（只读；长度可能大于有效元素数，用 {@link #visibleCount()} 截取）。 */
    public static CachedSection[] visibleArray() {
        return visible;
    }

    public static int visibleCount() {
        return visibleCount;
    }

    public static int cachedSectionCount() {
        return CACHE.size();
    }

    /** 已分配的顶点缓冲个数（GL 对象数）。 */
    public static int bufferCount() {
        int count = 0;
        for (CachedSection cached : CACHE.values()) {
            count += cached.slotCount;
        }
        return count;
    }

    /** 缓存里顶点总数（显存占用估算用）。 */
    public static int cachedVertexCount() {
        int total = 0;
        for (CachedSection cached : CACHE.values()) {
            total += cached.totalVertices();
        }
        return total;
    }

    public static int statsBuiltByRevision() {
        return statsBuiltByRevision;
    }

    public static int statsBuiltByRefresh() {
        return statsBuiltByRefresh;
    }

    public static int statsBuiltVertices() {
        return statsBuiltVertices;
    }

    /** 上帧真正交给 GPU 的顶点数（= 缓存里的顶点数，不再逐帧重写）。 */
    public static int statsDrawnVertices() {
        return statsDrawnVertices;
    }

    /** 上帧重建几何用掉的纳秒。 */
    public static long statsBuildNanos() {
        return statsBuildNanos;
    }

    /** 记一下本帧交出去的顶点数（统计用；由绘制循环累加）。 */
    public static void recordDrawnVertices(int vertices) {
        statsDrawnVertices = vertices;
    }

    /**
     * 丢掉一个段的缓存（段被拆空 / 区块卸载）。会释放它的 GL 缓冲。
     *
     * <p>正常情况下不必显式调用：段从索引里消失后就不会再被 {@link #touch}，
     * 于是会在 LRU 淘汰时自然回收。这里留给「明确知道它没了」的路径用。
     */
    public static void drop(long sectionKey) {
        CachedSection cached = CACHE.remove(sectionKey);
        if (cached != null) {
            cached.releaseBuffers();
        }
    }

    /** 整片作废（退出世界 / 换维度 / 切渲染模式 / 资源重载）。**必须释放 GL 缓冲**。 */
    public static void invalidateAll() {
        for (CachedSection cached : CACHE.values()) {
            cached.releaseBuffers();
        }
        CACHE.clear();
        visibleCount = 0;
        refreshCursor = 0;
        statsDrawnVertices = 0;
    }

    private static void addVisible(CachedSection cached) {
        if (visibleCount == visible.length) {
            CachedSection[] grown = new CachedSection[visible.length * 2];
            System.arraycopy(visible, 0, grown, 0, visible.length);
            visible = grown;
        }
        visible[visibleCount++] = cached;
    }

    /**
     * LRU：淘汰一个「本帧没被看到」的最老分段（并释放它的 GL 缓冲）。
     *
     * <p>「本帧没被看到」直接用 {@code lastUsedFrame < frameStamp} 判断 —— 每个可见段在
     * {@link #touch} 里都刚把自己的时间戳写成 {@code frameStamp}，所以这是一个零成本判据，
     * 不需要额外维护一个「本帧可见」集合。本帧可见的段一个都不淘汰（它们马上要画）。
     */
    private static void evictOldest() {
        long bestKey = Long.MIN_VALUE;
        long bestFrame = Long.MAX_VALUE;
        for (Map.Entry<Long, CachedSection> entry : CACHE.entrySet()) {
            CachedSection cached = entry.getValue();
            if (cached.lastUsedFrame >= frameStamp) {
                continue;
            }
            if (cached.lastUsedFrame < bestFrame) {
                bestFrame = cached.lastUsedFrame;
                bestKey = entry.getKey();
            }
        }
        if (bestFrame == Long.MAX_VALUE) {
            return;
        }
        drop(bestKey);
    }

    /**
     * 重建一个段的几何。
     *
     * @param revision 段当前版本戳（轮转刷新时沿用上一次的，因为它没有变）
     */
    private static void rebuild(CachedSection cached, BlockPos[] positions, long revision,
                                ClientLevel level, long epoch, boolean refreshPass) {
        if (level == null) {
            return;
        }
        try {
            cached.positions = positions != null ? positions : EMPTY;
            if (cached.positions.length == 0) {
                // 段被拆空了：放掉缓冲。它很快就会从索引里消失。
                cached.releaseBuffers();
                cached.builtRevision = revision;
                cached.builtEpoch = epoch;
                cached.built = true;
                return;
            }
            long start = System.nanoTime();
            // 只清「槽表」，不释放 GL 缓冲 —— 见 CachedSection.resetForRebuild 的说明。
            cached.resetForRebuild();
            BlockColors blockColors = Minecraft.getInstance().getBlockColors();
            build(cached, level, blockColors);
            statsBuildNanos += System.nanoTime() - start;
            cached.builtRevision = revision;
            cached.builtEpoch = epoch;
            cached.built = true;
            if (refreshPass) {
                statsBuiltByRefresh++;
            } else {
                statsBuiltByRevision++;
            }
            statsBuiltVertices += cached.totalVertices();
        } catch (Throwable t) {
            // 建失败就退回「没建」，下一帧会再试 —— 绝不让一次异常把这一段永久标记成「已建」。
            cached.built = false;
            discardBuilder();
            LOGGER.error("[SmoothLift] 构建扶梯阶梯静态几何失败（这一段本帧不画，下一帧会重试）", t);
        }
    }

    /**
     * 真正写顶点的地方 —— **全类只有这里会在「某段需要重建」时跑一次**，
     * 旧版是每帧每个方块都跑。
     *
     * <p>写进去的是：<b>分段局部坐标 + 帧内 uv + 每方块一次的光照 + 方向明暗</b>。
     * 注意 uv **不再折帧号**（旧版是 {@code (帧号 + 帧内v) / 帧数}）—— 现在帧号由
     * 「绑定哪张帧贴图」表达，顶点里只留帧内分量，这正是几何得以静态化的那一刀。
     *
     * <p>也不做 CPU 背面剔除：静态缓冲里剔不了（相机一动「哪面朝后」就变了），
     * 交给 GPU 的 {@code CULL} 状态（本渲染类型和原版 cutout 一样开着）。
     */
    private static void build(CachedSection cached, ClientLevel level, BlockColors blockColors) {
        BlockPos[] positions = cached.positions;
        int count = positions.length;
        int[] blockSlots = scratchSlots(count);
        boolean[] used = scratchUsed();
        int usedCount = 0;

        // 第一趟：定每个方块进哪个槽（槽 = 速度组 × 贴图族）。只算一次，写顶点时直接用。
        for (int i = 0; i < count; i++) {
            blockSlots[i] = -1;
            BlockPos pos = positions[i];
            BlockState state = level.getBlockState(pos);
            EscalatorStepModels.StepModel model = EscalatorStepModels.get(state);
            if (model == null) {
                continue;
            }
            // 「刷子刷停」的阶梯是静止的：归到第 0 组（代表速度 0 ⇒ 帧号恒为 0），
            // 于是旧版那条 model.stopped() 的单独判断在这里自然成立，不需要额外分支。
            double speed = model.stopped()
                    ? 0.0
                    : EscalatorSpeedManager.getAnimationSpeed(level, pos);
            int group = EscalatorStepGroups.indexFor(speed);
            boolean flat = model.flat() && EscalatorStepTextures.flatAvailable();
            int slot = slotOf(group, flat, model.up());
            blockSlots[i] = slot;
            if (!used[slot]) {
                used[slot] = true;
                cached.slots[usedCount++] = slot;
            }
        }

        // 第二趟：按槽分组写顶点。一个槽一趟，共用同一个 BufferBuilder。
        for (int s = 0; s < usedCount; s++) {
            int slot = cached.slots[s];
            BufferBuilder active = builder();
            active.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            int verts = 0;
            for (int i = 0; i < count; i++) {
                if (blockSlots[i] != slot) {
                    continue;
                }
                verts += writeBlock(active, level, blockColors, cached, positions[i]);
            }
            finishSlot(cached, slot, active, verts);
        }
        cached.slotCount = usedCount;

        // 这一轮没再用到的槽：把它的 GL 缓冲还回去。会走到这里的有「扶梯改了速度、
        // 换了组」这类情况 —— 旧槽的缓冲留在这里没有任何用处，白占显存。
        // （槽号是密集小整数，最多 SLOT_COUNT 个，这一趟是常数开销。）
        for (int i = 0; i < EscalatorStepGroups.SLOT_COUNT; i++) {
            if (!used[i] && cached.buffers[i] != null) {
                cached.buffers[i].close();
                cached.buffers[i] = null;
            }
        }
    }

    /** 写一个方块的阶梯面，返回写了几个顶点。 */
    private static int writeBlock(BufferBuilder active, ClientLevel level, BlockColors blockColors,
                                  CachedSection cached, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        EscalatorStepModels.StepModel model = EscalatorStepModels.get(state);
        if (model == null) {
            return 0;
        }
        // 分段局部坐标：方块相对段原点是 0..15，再加顶点自身的 0..1 ⇒ 0..16，float 精度极好。
        float px = pos.getX() - cached.originX;
        float py = pos.getY() - cached.originY;
        float pz = pos.getZ() - cached.originZ;
        int packedLight = LevelRenderer.getLightColor(level, state, pos);
        float[] data = model.data();
        float[] shades = model.shades();
        int[] tints = model.tints();
        int quadCount = model.quadCount();
        int verts = 0;
        for (int q = 0; q < quadCount; q++) {
            int base = q * EscalatorStepModels.StepModel.FLOATS_PER_QUAD;
            float cr = 1.0F;
            float cg = 1.0F;
            float cb = 1.0F;
            int tintIndex = tints[q];
            if (tintIndex >= 0 && blockColors != null) {
                int color = blockColors.getColor(state, level, pos, tintIndex);
                cr = ((color >> 16) & 0xFF) * COLOR_CHANNEL_INV;
                cg = ((color >> 8) & 0xFF) * COLOR_CHANNEL_INV;
                cb = (color & 0xFF) * COLOR_CHANNEL_INV;
            }
            float shade = shades[q];
            cr *= shade;
            cg *= shade;
            cb *= shade;
            for (int v = 0; v < 4; v++) {
                int o = base + v * EscalatorStepModels.StepModel.FLOATS_PER_VERTEX;
                active.vertex(
                        px + data[o], py + data[o + 1], pz + data[o + 2],
                        cr, cg, cb, 1.0F,
                        data[o + 3], data[o + 4],
                        OverlayTexture.NO_OVERLAY, packedLight,
                        data[o + 5], data[o + 6], data[o + 7]);
                verts++;
            }
        }
        return verts;
    }

    /**
     * 收尾一个槽：上传到它的 {@link VertexBuffer}。
     *
     * <h3>★★ 为什么必须先 {@code bind()} 再 {@code upload()}（1.29 第一版漏了，是个会打死驱动的 bug）</h3>
     * {@code VertexBuffer.upload()} 自己**完全不碰 VAO**，它做的是「<b>往当前绑定的那个 VAO 里</b>
     * 写属性指针、并把索引缓冲记进去」：
     * <pre>
     *   uploadVertexBuffer():  if (格式变了) { _glBindBuffer(GL_ARRAY_BUFFER, vertexBufferId);
     *                                           format.setupBufferState(); }  // ← 写进“当前 VAO”
     *   uploadIndexBuffer():   顺序索引模式 ⇒ RenderSystem.getSequentialBuffer(mode).bind(indexCount)
     *                                                                         // ← EBO 也记进“当前 VAO”
     * </pre>
     * 于是：上传时如果绑的是**别人**的 VAO（或者 0），这份 VertexBuffer 自己的 VAO 里就什么都没有。
     * 之后 {@code glDrawElements} 每次都会报
     * {@code GL_INVALID_OPERATION: Invalid VAO/VBO/pointer usage}，
     * 报够一百来次之后 NVIDIA 驱动直接在 {@code nvoglv64.dll} 里读空指针把游戏打死。
     * （实测崩溃报告 2026-09-26 12:45：{@code nvoglv64.dll+0xc370c8} 在 {@code glDrawElements}，
     * 调用栈 {@code EscalatorStepRenderer.draw → VertexBuffer.drawWithShader}。）
     *
     * <p>原版的上传体 {@code SectionRenderDispatcher.method_43610}（反汇编实测）就三句：
     * <pre>
     *   if (buffer.isInvalid()) { rendered.release(); return; }
     *   buffer.bind();
     *   buffer.upload(rendered);
     *   VertexBuffer.unbind();
     * </pre>
     * 这里照抄它的形状。{@code finally} 里解绑 VAO，是为了万一上传抛异常也不要让整个渲染
     * 管线继续用我们这个 VAO 画别的东西。
     */
    private static void finishSlot(CachedSection cached, int slot, BufferBuilder active, int verts) {
        if (verts == 0) {
            cached.vertexCounts[slot] = 0;
            return;
        }
        VertexBuffer buffer = cached.buffers[slot];
        if (buffer == null) {
            // 构造 VertexBuffer 必须在渲染线程（它当场 gen 顶点缓冲 / 索引缓冲 / VAO），
            // 这里正是渲染线程。重建时这一份会被复用，不会每次 new。
            buffer = new VertexBuffer();
            cached.buffers[slot] = buffer;
        }
        active.end();
        buffer.bind();  // ★ 必须：upload 只是「往当前 VAO 里写属性指针 + 记 EBO」
        try {
            buffer.upload(active);
        } finally {
            VertexBuffer.unbind();
        }
        cached.vertexCounts[slot] = verts;
    }

    private static BufferBuilder builder() {
        if (builder == null) {
            builder = new BufferBuilder(BUILDER_CAPACITY);
        }
        return builder;
    }

    /**
     * 万一中途抛异常，把共用 builder 复位。
     *
     * <p>不复位的话它会一直停在「building」状态，下一次 {@code begin()} 会抛
     * {@code Already building!} —— 于是「一次异常」会变成「每帧都异常」的永久故障。
     */
    private static void discardBuilder() {
        if (builder != null && builder.building()) {
            builder.discard();
        }
    }

    /** 槽号 = 速度组 × 4 + 贴图族（族 = 平层?2:0 | 上行?1:0），与画法一一对应。 */
    public static int slotOf(int group, boolean flat, boolean up) {
        return group * EscalatorStepGroups.SLOTS_PER_GROUP
                + (flat ? 2 : 0) + (up ? 1 : 0);
    }

    /** 从槽号反解速度组（绘制时按组算帧号用）。 */
    public static int groupOfSlot(int slot) {
        return slot / EscalatorStepGroups.SLOTS_PER_GROUP;
    }

    /** 从槽号反解「是不是上行」。 */
    public static boolean upOfSlot(int slot) {
        return (slot & 1) != 0;
    }

    /** 从槽号反解「是不是平层族」。 */
    public static boolean flatOfSlot(int slot) {
        return (slot & 2) != 0;
    }

    // ---- 复用的小缓冲区（避免重建时产生垃圾） ----
    private static int[] scratchSlots = new int[64];
    private static final boolean[] SCRATCH_USED = new boolean[EscalatorStepGroups.SLOT_COUNT];

    private static int[] scratchSlots(int size) {
        if (scratchSlots.length < size) {
            scratchSlots = new int[size + (size >> 1) + 8];
        }
        return scratchSlots;
    }

    private static boolean[] scratchUsed() {
        Arrays.fill(SCRATCH_USED, false);
        return SCRATCH_USED;
    }
}
