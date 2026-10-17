package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 「已加载区块里有哪些扶梯阶梯方块」的索引。
 *
 * <h2>为什么要有它</h2>
 * 逐条驱动阶梯动画需要每帧遍历视野内的阶梯方块，不可能每帧去扫区块，所以这里建一份索引，
 * 靠两条路跟上玩家的增删：
 * <ol>
 *   <li><b>【1.30b】即时路（主）</b>：{@link #onBlockChanged} —— 方块一变就把所在区块记下来，
 *       下一个客户端刻只重扫那几个区块（≤1 刻）。触发点是客户端 {@code Level.onBlockStateChange}
 *       （{@code Level.setBlock} 的末尾），由 {@code smooth.lift.client.mixin.LevelBlockChangeMixin}
 *       钩住；它同时覆盖「本地玩家操作的客户端预测」与「服务端下发的方块更新」两条路。</li>
 *   <li><b>兜底路</b>：区块加载（{@code CHUNK_LOAD}）+ 每 {@link #RESCAN_INTERVAL} 刻取一片的
 *       定期重扫（覆盖不走 {@code Level.setBlock} 的改动路径）。</li>
 * </ol>
 * 扫描用 {@link LevelChunkSection#maybeHas} 先按方块调色板做一次廉价的预筛，
 * 只有真的可能含有扶梯阶梯方块的分段才会逐格遍历。
 *
 * <h2>【1.26】为什么从「一整个平铺数组」改成分段稀疏结构</h2>
 * 旧版把全部阶梯方块摊平成一个 {@code List<BlockPos>} + 一个**平行的** {@code AABB[]}
 * （每个方块一个盒子，给渲染端逐块做视锥剔除）。两个代价都随城市规模爆炸：
 * <ul>
 *   <li><b>每帧</b>要遍历**世界上所有已加载**的阶梯方块（几千到几万），逐块算距离 + 逐块
 *       {@code Frustum.isVisible}。看不到的方块也一个不落 —— 这是帧时间随城市线性增长的主因；</li>
 *   <li><b>每次索引变化</b>（区块加载、重扫发现有增删）都要整表重建：{@code new AABB(...)}
 *       再 {@code inflate(...)} ⇒ **每个方块 2 个对象**。进世界时几百个区块陆续加载，
 *       等于几百次 O(总阶梯数) 的重建 + 上百万次临时对象分配 ⇒ 进世界那一下明显掉帧/卡顿，
 *       而且 GC 压力一直挂在后台。</li>
 * </ul>
 * 现在改成：**只登记「真的含阶梯方块」的 16³ 分段**（{@link Section}），每段一个缓存的
 * {@link AABB}、一个方块数组。于是
 * <ul>
 *   <li>渲染每帧只做 <b>段数</b> 次距离 + 视锥判定（一个城市通常几百到几千段，
 *       而不是几万个方块），段被剔掉 ⇒ 段内方块**一次都不碰**；</li>
 *   <li>区块加载/重扫只动**本区块的 24 个段**，不再触发全表重建，
 *       也不再产生「每方块 2 个 AABB」的分配风暴；</li>
 *   <li>{@link #positions()} 仍在（音频侧「找最近扶梯」用），但它现在是**按需**从分段拼出来的。</li>
 * </ul>
 *
 * <h2>【1.26】★「索引被清空过一次就再也回不来」—— /mtrxr 切换后阶梯整片消失的根因</h2>
 * 旧版的 {@link #reset()} 会把「已加载区块登记表」一起清空（{@code LOADED.clear()}），而
 * {@link #tick(ClientLevel)} 在登记表为空时**直接 return**，之后唯一能往里加东西的只有
 * 区块加载事件（Forge 侧是 {@code ChunkEvent$Load}，见 {@link SmoothLiftClientEvents}）—— 可那个事件只对**新加载**的区块触发，已经在场的
 * 区块永远不会再触发一次。于是 {@code reset()} 一旦被调用，索引就**永久为空**。
 *
 * <p>当时 {@code /mtrxr} 切渲染引擎时正好调了它（见 {@link EscalatorRenderMode#apply}），
 * 所以：{@code /mtrxr off} → 索引清空 → 渲染端「没有阶梯可画」直接返回，而 MTR 原版那份静止
 * 阶梯面**已经被全透明标记贴图隐藏**（见 {@link EscalatorModelOverride}）⇒ **阶梯整片消失**；
 * 退出重进存档时所有区块重新加载、{@code CHUNK_LOAD} 重新触发 ⇒ 阶梯又回来了。
 *
 * <p>现在两道防线：
 * <ol>
 *   <li>切引擎只调 {@link #invalidate()}（保留区块登记，只作废内容并立刻重扫），
 *       真正的 {@link #reset()} 只留给「退出世界 / 换维度」；</li>
 *   <li>{@link #tick(ClientLevel)} 自愈：登记表为空时主动去客户端区块缓存里把当前**真正**
 *       加载着的区块重新登记一遍（{@link #rediscoverChunks}），任何路径清空过都能自己恢复。</li>
 * </ol>
 */
public final class EscalatorStepIndex {

    /** 分段边长（与 Minecraft 的 chunk section 一致）。 */
    public static final int SECTION_SIZE = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static final Predicate<BlockState> IS_STEP = EscalatorUtil::isEscalatorStep;
    /**
     * 定期重扫的**取片间隔**（刻）。★ 注意它不再等于「重扫一遍的周期」——
     * 一遍的墙钟时间 = {@code RESCAN_INTERVAL × RESCAN_SWEEP_TICKS}，见下。
     */
    private static final int RESCAN_INTERVAL = 10;
    /**
     * 【1.27】★ 把「一遍全量重扫」切成多少片。
     *
     * <p>旧版是每 {@link #RESCAN_INTERVAL} 刻把**整个世界已加载的所有区块**一次扫完 ——
     * 那是一个**随世界规模增长的周期性尖峰**：在几百上千个区块的存档里，每 0.5 秒
     * 集中做一次扫段（遍历每个含阶梯的段的 16³ 格），帧时间会规律性地顶起来一下。
     * 这种「周期性顿一下」比均匀的慢更惹人注意，也正是「卡」的常见来源。
     *
     * <p>现在改成**游标切片**：每 {@link #RESCAN_INTERVAL} 刻取一片、一片覆盖
     * {@code 1/RESCAN_SWEEP_TICKS} 的已加载区块 ⇒ 每 tick 的开销恒定，尖峰没了。
     *
     * <p>★★ 但代价必须写清楚（这正是【1.30b】要修的 bug）：**一遍要走
     * {@code RESCAN_INTERVAL × RESCAN_SWEEP_TICKS} = 100 刻（5 秒）**。如果索引只有这一条路了，
     * 玩家放下扶梯后，那座区块就得等「下次轮到它」才被扫到 —— 均匀分布，均值 2.5 秒、
     * 最坏 5 秒，与用户报的「扶梯放下去要等 2~3 秒台阶贴图才出现」逐字吻合。
     *
     * <p>★ 所以**别再靠加大这里的片来缩短延迟**（尖峰会原样回来）。正确做法是让索引有一条
     * **事件驱动**的即时入口 —— 见 {@link #onBlockChanged}；这一遍降级为兜底。
     */
    private static final int RESCAN_SWEEP_TICKS = 10;
    /**
     * 【1.30b】单 tick 最多重扫多少个「刚被改动的区块」。
     *
     * <p>正常编辑（放 / 拆一座扶梯）只碰 1~2 个区块，这个上限根本用不到；它防的是
     * 「一次大量方块变化」（{@code /fill}、结构模组粘贴）把所有区块堆到同一刻重扫 ——
     * 那会制造一个和 1.27 要消掉的同款尖峰。超出的留到下一刻，只是晚 50 毫秒。
     */
    private static final int MAX_DIRTY_CHUNKS_PER_TICK = 16;
    /** 分段盒子向外扩一点，避免贴边的方块被视锥误剔（会直接表现为「露空」）。 */
    private static final double BOX_INFLATE = 1.0;
    /** 客户端区块缓存半径的上限（原版是「有效渲染距离 + 3」，这里留足余量）。 */
    private static final int MAX_REDISCOVER_RADIUS = 40;
    /** 【1.29】内容指纹（FNV-1a 64 位）的初始向量与质数。 */
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    /** 一个 16³ 分段里登记到的阶梯方块。只在「真的含阶梯」时才存在。 */
    public static final class Section {
        /**
         * 【1.27】★ **段内实际方块的紧致包围盒**（已 {@link #BOX_INFLATE} 外扩）。
         *
         * <p>旧版这里是**整块 16³ 立方体**再外扩 1 格 —— 判据里说「剔除粒度 = 16³ 分段」，
         * 但盒子是整块立方体时这句等于没生效：一个只装了 3 个阶梯方块的段，也报出一个
         * 18×18×18 的盒子。于是
         * <ul>
         *   <li>距离判定失真：立方体的「最近面」可能比真实阶梯近 16 格；
         *   <li>视锥判定几乎恒为真：相机正前方任意一个 18³ 盒子都相交。
         * </ul>
         * 结果就是「以为在剔除，其实一个都没剔掉」，每帧照样去碰段里的每一个方块。
         *
         * <p>改成紧致盒之后，一条细长的扶梯（一列直梯段 / 一段斜梯）的盒子就只有它自己
         * 那么点大 —— 转头、走位、距离判定才真的能把整段甩掉。这是「玩家看不到的就不渲染」
         * 能落地的前提。
         */
        private final AABB box;
        private final BlockPos[] positions;
        /**
         * 【1.28】本段的段键（{@link SectionPos#asLong(int, int, int)}），由 {@link #scanSections}
         * 在建段时一并传入。
         *
         * <p>为什么必须存下来：遮挡剔除要拿它去做「原版这帧要不要画这个段」的集合查询，
         * 那个查询是逐段、逐帧做的。如果渲染端自己从 {@code positions[0]} 现算，
         * 等于每帧每段多一次移位/打包；而且「段键」的算法一旦分叉（比如别处改成
         * 另一种编码），就会出现「索引里登记得进去、遮挡查询永远查不到」这种
         * 静默失效 —— 表现为整片扶梯凭空消失。存成字段，编码只在一处产生。
         */
        private final long key;
        /** 紧致盒的中心（拿不到视锥时的兜底剔除用）。 */
        private final double centerX;
        private final double centerY;
        private final double centerZ;
        /**
         * 【1.29】本段的**原点**（段最小方块坐标 = {@link SectionPos#minBlockX()} 等）。
         *
         * <p>静态几何缓存里的顶点写的是**分段局部坐标**（0..16），绘制时由原版
         * {@code ChunkOffset} uniform 补上 {@code 原点 − 相机位置}（见
         * {@code EscalatorStepCache} 的注释）。所以渲染端每帧都要这个原点，
         * 而且必须和建缓存时用的是同一个 —— 存成字段、只在这里算一次，
         * 避免「两处各自从段键移位算」将来分叉。
         */
        private final int originX;
        private final int originY;
        private final int originZ;
        /**
         * 【1.29】本段内容的**版本戳**（{@link #nextRevision()} 发的唯一号）。
         *
         * <p>渲染端每帧只做一件事来判断「这段的静态几何还能不能用」：
         * {@code 缓存里的 revision == 段当前 revision}。相等就整段跳过、一个方块都不碰；
         * 不等才重建那一份顶点缓冲。这是「每帧遍历全世界方块」被彻底删掉的依据。
         *
         * <p>注意它只在 {@link #applyChunk} **真的把新内容存进表里**时才换新值：
         * 重扫发现内容没变时保留旧 {@code Section} 对象，于是 revision 不变、缓存不失效。
         */
        private final long revision;
        /**
         * 【1.29】内容的**指纹**（FNV-1a 64 位，逐方块折进 {@link Block#getId}）。
         *
         * <p>光比位置是不够的：扶梯的「刷子刷停」、朝向、上下行改变时**方块坐标一个都没动**，
         * 但模型/贴图族变了 —— 只比位置就会把旧几何一直用下去（表现是「刷停了台阶还在动」
         * 或者「改了方向台阶还朝原来那边」）。所以指纹里必须带上方块状态。
         *
         * <p>用 64 位而不是 int：这里判错一次的代价是「静默用错几何」，而重建一次的成本
         * 只有几毫秒。宁可指纹贵一点，也不要碰撞。
         */
        private final long signature;

        private Section(long key, int originX, int originY, int originZ,
                        long revision, long signature, BlockPos[] positions) {
            this.key = key;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.revision = revision;
            this.signature = signature;
            this.positions = positions;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos p : positions) {
                int x = p.getX();
                int y = p.getY();
                int z = p.getZ();
                if (x < minX) {
                    minX = x;
                }
                if (y < minY) {
                    minY = y;
                }
                if (z < minZ) {
                    minZ = z;
                }
                if (x > maxX) {
                    maxX = x;
                }
                if (y > maxY) {
                    maxY = y;
                }
                if (z > maxZ) {
                    maxZ = z;
                }
            }
            // 方块占满 [x, x+1)，所以上界要 +1；再外扩 BOX_INFLATE 兜住模型略微出格的部分
            // （方块模型的几何是可以超出 0..16 的，宁可多测一点也不能把可见的剔掉）。
            this.box = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0)
                    .inflate(BOX_INFLATE);
            this.centerX = (minX + maxX + 1) * 0.5;
            this.centerY = (minY + maxY + 1) * 0.5;
            this.centerZ = (minZ + maxZ + 1) * 0.5;
        }

        /** 段包围盒（只读，渲染端不要改它）。 */
        public AABB box() {
            return box;
        }

        /**
         * 段键（{@link SectionPos#asLong(int, int, int)}）。
         *
         * <p>给渲染端做「原版这帧要不要画这个段」的集合查询用 —— 见字段注释里
         * 为什么要存成字段而不是现算。
         */
        public long key() {
            return key;
        }

        /** 本段内所有阶梯方块（只读）。 */
        public BlockPos[] positions() {
            return positions;
        }

        /** 【1.29】本段的原点（段最小方块坐标）。静态几何缓存的局部坐标系原点。 */
        public int originX() {
            return originX;
        }

        public int originY() {
            return originY;
        }

        public int originZ() {
            return originZ;
        }

        /**
         * 【1.29】本段内容的版本戳。渲染端拿它和缓存里记的值比：
         * <b>相等就说明静态几何一个字都不用改</b>。
         */
        public long revision() {
            return revision;
        }

        /** 【1.29】本段内容指纹（只给索引内部判断「内容到底变没变」用）。 */
        private long signature() {
            return signature;
        }

        /** 段中心的 X（拿不到视锥时的兜底剔除用）。 */
        public double centerX() {
            return centerX;
        }

        public double centerY() {
            return centerY;
        }

        public double centerZ() {
            return centerZ;
        }
    }

    private static ResourceKey<Level> dimension;

    /** 段键（{@link SectionPos#asLong(int, int, int)}）→ 段。**只装非空段**。 */
    private static final Map<Long, Section> SECTIONS = new HashMap<>();
    /** 区块键 → 该区块这一轮登记的段键。区块卸载 / 重扫时按它精确摘除，不用全表扫。 */
    private static final Map<Long, long[]> CHUNK_SECTIONS = new HashMap<>();
    /** 已加载区块。用 Map（而不是旧的 List）⇒ 登记/摘除/查重都是 O(1)，不再 O(区块数)。 */
    private static final Map<Long, LevelChunk> LOADED = new HashMap<>();
    /**
     * 【1.27】重扫的游标队列：`LOADED` 键的快照 + 走到哪了。
     * 每 {@link #RESCAN_INTERVAL} 刻取一片（见 {@link #rescanSlice()}），把周期性尖峰摊平。
     */
    private static final List<Long> rescanQueue = new ArrayList<>();
    private static int rescanCursor;
    /**
     * 【1.30b】「刚有方块被改动过」的区块键（{@link ChunkPos#asLong(BlockPos)}）。
     *
     * <p>由 {@link #onBlockChanged} 填、每客户端刻由 {@link #applyDirtyChunks} 抽干。
     * 用**区块键**而不是坐标：同一座扶梯一次要写 4 个方块（两列台阶 + 它们正上方的侧板），
     * 而且客户端预测与服务端下发会各来一遍 —— 按区块去重后，一次放置最多只重扫 2 个区块。
     */
    private static final Set<Long> dirtyChunks = new HashSet<>();

    /** 已登记的阶梯方块总数（增量维护，避免每次去累加段长度）。 */
    private static int stepCount;
    /** 已登记的非空段数（= {@link #SECTIONS} 的大小）。 */
    private static int sectionCount;

    /**
     * 【1.29】段内容版本戳的**发号器**。
     *
     * <p>它只增不减（连 {@link #reset()} 也不清零），因为「版本戳永远不重复」是渲染端
     * 「拿缓存里的号和当前段比一比」这条判据唯一的前提：一旦有号被重复使用，
     * 一段被拆掉重建后可能**恰好拿到旧号**，于是缓存被误判成有效 ⇒ 用错几何。
     * 单调发号器从根本上排掉这种可能（long 到宇宙热寂也用不完）。
     */
    private static long revisionCounter;

    private static List<BlockPos> flattened = List.of();
    private static boolean flattenedDirty = true;
    private static int countdown = RESCAN_INTERVAL;

    private EscalatorStepIndex() {
    }

    /**
     * Forge 版没有 {@code ClientChunkEvents} 这个 Fabric 事件 —— 区块加载 / 卸载由
     * {@link SmoothLiftClientEvents} 的 {@code ChunkEvent$Load / ChunkEvent$Unload}
     * 直接转发到 {@link #onChunkLoad(ClientLevel, LevelChunk)} / {@link #onChunkUnload}，
     * 所以这里**不需要注册任何东西**。
     *
     * <p>留这个空方法是为了与 Fabric 侧保持同名调用点（各平台入口都调一次 {@code register()}），
     * 避免将来「Fabric 那边加了注册、Forge 这边忘了加」这类静默分叉。
     */
    public static void register() {
        // Forge：见上方注释，区块事件在 SmoothLiftClientEvents 里挂。
    }

    /**
     * 【1.30b】某个方块刚被改过 —— 由 {@code LevelBlockChangeMixin} 在
     * {@code Level.onBlockStateChange} 里调用（客户端）。
     *
     * <h2>为什么必须有这条「即时」通道</h2>
     * 在这之前，索引**只靠定期重扫**跟上增删，而 1.27 把「一遍全量重扫」切成片之后，
     * 一遍要 {@code RESCAN_INTERVAL × RESCAN_SWEEP_TICKS} = 100 刻（5 秒）才走完
     * （每 10 刻才取一片、一片只覆盖 1/10 的已加载区块）。于是玩家放下扶梯后，那座区块
     * 要**等下次轮到它**才被扫到 —— 均匀分布，均值 2.5 秒、最坏 5 秒，正好是用户报的
     * 「扶梯放下去要等 2~3 秒台阶贴图才出现」。
     *
     * <p>而这期间 MTR 原版那份静止阶梯面**已经被全透明标记贴图隐藏**（见
     * {@link EscalatorModelOverride}），所以症状表现为「扶梯放下去是空的，过两三秒台阶才冒出来」。
     *
     * <h2>为什么钩这一处</h2>
     * {@code Level.onBlockStateChange} 是 {@code Level.setBlock} 的末尾（1.20.4 字节码实测），
     * 因此它**同时覆盖两条路**：① 客户端对本地玩家操作的预测（MTR 的 {@code ItemEscalator}
     * 走 {@code World.setBlockState → Level.setBlockAndUpdate → setBlock → onBlockStateChange}）；
     * ② 服务端下发的方块更新（{@code ClientLevel.setServerVerifiedBlockState → Level.setBlock}）。
     * 选它而不是 {@code LevelChunk} 那一层，是因为它只在「世界 API 改方块」时触发，
     * 区块反序列化不会经过它。
     *
     * <p>这里**只记账、不扫描**：扫描放到下一个客户端刻（{@link #applyDirtyChunks}）。
     * 一是因为钩子可能跑在网络包处理路径上，二是「一次划一片区域」会触发成百上千次，
     * 去重后只扫一次才划算。
     */
    public static void onBlockChanged(Level level, BlockPos pos) {
        if (pos == null || !(level instanceof ClientLevel)) {
            // 集成服务端的 setBlock 也会走到这里（本模组这份是**客户端** mixin，在专用服务端
            // 上根本不加载；但单人游戏里同一个 Level 类被服务端世界复用）——
            // 那份世界与我们无关，更要紧的是**不能从服务端线程去碰这个静态集合**。
            return;
        }
        dirtyChunks.add(ChunkPos.asLong(pos));
    }

    /**
     * 所有**非空分段**（渲染端每帧遍历它）。
     *
     * <p>返回的是内部集合的实时视图，**不要改它**、也不要跨帧保存。
     */
    public static Collection<Section> sections() {
        return SECTIONS.values();
    }

    /** 当前登记的阶梯方块总数（性能计数用）。 */
    public static int stepCount() {
        return stepCount;
    }

    /** 当前登记的非空分段数（性能计数用）。 */
    public static int sectionCount() {
        return sectionCount;
    }

    /**
     * 当前维度已加载区块里的所有扶梯阶梯方块（平铺视图）。
     *
     * <p>只有「找最近的扶梯」这类每 0.5 秒才跑一次的用途才用它 —— 渲染端请直接用
     * {@link #sections()}（分段级剔除），否则又退化成「每帧遍历全世界的方块」。
     */
    public static List<BlockPos> positions() {
        if (!flattenedDirty) {
            return flattened;
        }
        int total = 0;
        for (Section section : SECTIONS.values()) {
            total += section.positions.length;
        }
        List<BlockPos> all = new ArrayList<>(total);
        for (Section section : SECTIONS.values()) {
            for (BlockPos pos : section.positions) {
                all.add(pos);
            }
        }
        flattened = all;
        flattenedDirty = false;
        return flattened;
    }

    /**
     * 距给定点最近的阶梯方块（只查半径内的分段，找不到返回 null）。
     *
     * <p>【1.26】替代 {@code EscalatorAnimationDriver} 里那个 O(半径³) 的球壳扫描
     * （半径 16 就要 33³ ≈ 3.6 万次 {@code getBlockState}，而它每 tick 都跑）。
     * 走分段后代价只跟「半径内的非空段数」有关，与半径的立方无关。
     */
    public static BlockPos nearestStep(Vec3 eye, int radius) {
        double x = eye.x;
        double y = eye.y;
        double z = eye.z;
        int minSectionX = (int) Math.floor((x - radius) / SECTION_SIZE);
        int maxSectionX = (int) Math.floor((x + radius) / SECTION_SIZE);
        int minSectionY = (int) Math.floor((y - radius) / SECTION_SIZE);
        int maxSectionY = (int) Math.floor((y + radius) / SECTION_SIZE);
        int minSectionZ = (int) Math.floor((z - radius) / SECTION_SIZE);
        int maxSectionZ = (int) Math.floor((z + radius) / SECTION_SIZE);

        BlockPos best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Map.Entry<Long, Section> entry : SECTIONS.entrySet()) {
            long key = entry.getKey();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            if (sx < minSectionX || sx > maxSectionX
                    || sy < minSectionY || sy > maxSectionY
                    || sz < minSectionZ || sz > maxSectionZ) {
                continue;
            }
            for (BlockPos pos : entry.getValue().positions) {
                double dx = pos.getX() + 0.5 - x;
                double dy = pos.getY() + 0.5 - y;
                double dz = pos.getZ() + 0.5 - z;
                double dist = dx * dx + dy * dy + dz * dz;
                if (dist < bestSqr) {
                    bestSqr = dist;
                    best = pos;
                }
            }
        }
        return best;
    }

    public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (!acceptDimension(level)) {
            return;
        }
        long key = chunk.getPos().toLong();
        if (LOADED.put(key, chunk) != null) {
            // 已经登记过（同一区块重复触发 CHUNK_LOAD）：内容没变，不必再扫一遍。
            return;
        }
        applyChunk(chunk);
    }

    public static void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        if (dimension != null && !dimension.equals(level.dimension())) {
            return;
        }
        LOADED.remove(chunk.getPos().toLong());
        dropChunkSections(chunk.getPos().toLong());
    }

    /** 每客户端刻调用：换维度就清空，并周期性地全量重扫所有已加载区块跟上增删。 */
    public static void tick(ClientLevel level) {
        if (level == null) {
            reset();
            return;
        }
        if (!acceptDimension(level)) {
            reset();
            dimension = level.dimension();
            return;
        }
        if (LOADED.isEmpty()) {
            // 【1.26】自愈：登记表被清空过（换维度、退出世界、或旧版 reset 留下的坑）时，
            // 主动从客户端区块缓存里把当前真正加载着的区块重新登记一遍 —— 否则索引会
            // 永久为空（CHUNK_LOAD 只对新加载的区块触发，在场区块不会再触发），
            // 表现就是「阶梯整片消失，只有退出重进存档才回来」。
            rediscoverChunks(level);
            if (LOADED.isEmpty()) {
                return;
            }
        }
        // 【1.30b】先把「刚被改动的区块」处理掉 —— 这一步**在重扫倒计时之前、也不看它**，
        // 所以玩家放下扶梯后最迟下一刻就进索引（渲染端同帧就把这一段几何建好）。
        // 定期重扫因此退化为兜底（它一遍要 100 刻，见 RESCAN_SWEEP_TICKS 的注释）。
        applyDirtyChunks(level);
        if (--countdown > 0) {
            return;
        }
        countdown = RESCAN_INTERVAL;
        rescanSlice();
    }

    /**
     * 只作废内容并立刻重扫，**保留已加载区块登记**。
     *
     * <p>切渲染引擎（{@code /mtrxr}）用这个 —— 索引内容跟渲染模式无关（它只是坐标表），
     * 完全不需要清空。**别在这里调 {@link #reset()}**：那会把区块登记也清掉，
     * 而 {@code CHUNK_LOAD} 不会再为已经在场的区块触发 ⇒ 索引永久为空 ⇒ 阶梯整片消失。
     */
    public static void invalidate() {
        flattenedDirty = true;
        countdown = 0;
    }

    /** 退出世界 / 换维度：全部丢掉，等区块重新加载。 */
    public static void reset() {
        dimension = null;
        SECTIONS.clear();
        CHUNK_SECTIONS.clear();
        LOADED.clear();
        rescanQueue.clear();
        rescanCursor = 0;
        // 【1.30b】待处理的「刚改动的区块」也一起丢掉：换了世界/维度之后那些坐标没有意义，
        // 而且新的世界会由 CHUNK_LOAD 从头登记。
        dirtyChunks.clear();
        stepCount = 0;
        sectionCount = 0;
        flattened = List.of();
        flattenedDirty = false;
        countdown = RESCAN_INTERVAL;
    }

    private static boolean acceptDimension(ClientLevel level) {
        if (dimension == null) {
            dimension = level.dimension();
            return true;
        }
        return dimension.equals(level.dimension());
    }

    /**
     * 把「当前真正加载着的区块」重新登记一遍（自愈用）。
     *
     * <p>直接问客户端区块缓存（{@link ChunkSource#getChunkNow(int, int)}，取不到返回 null，
     * **不会**凭空加载区块），在玩家周围按半径扫一遍 —— 一次几百上千次哈希查找，
     * 而且只在登记表为空时才会走到。
     */
    private static void rediscoverChunks(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        int radius = Math.max(2, minecraft.options.getEffectiveRenderDistance()) + 3;
        radius = Math.min(radius, MAX_REDISCOVER_RADIUS);
        ChunkPos center = new ChunkPos(minecraft.player.blockPosition());
        ChunkSource source = level.getChunkSource();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = source.getChunkNow(center.x + dx, center.z + dz);
                if (chunk != null) {
                    onChunkLoad(level, chunk);
                }
            }
        }
        if (!LOADED.isEmpty()) {
            LOGGER.info("[SmoothLift] 扶梯阶梯索引重新登记了 {} 个已加载区块（{} 个分段 / {} 个阶梯方块）",
                    LOADED.size(), sectionCount, stepCount);
        }
    }

    /**
     * 【1.30b】处理「刚被改动的区块」：只重扫 {@link #dirtyChunks} 里点名的几个区块。
     *
     * <p>这是【1.30b】修复的主路径 —— 它**不受 {@link #RESCAN_INTERVAL} 倒计时管辖**，
     * 所以玩家放下扶梯后最迟下一刻索引里就有它了（旧行为：要等定期重扫轮到那座区块，
     * 均值 2.5 秒）。
     *
     * <p>每 tick 最多处理 {@link #MAX_DIRTY_CHUNKS_PER_TICK} 个，并把**处理过的从集合里删掉**
     * （剩下的下一刻继续）—— 这样「一次改动几千个方块」不会把某一刻顶起来。
     *
     * <p>取区块走 {@link ChunkSource#getChunkNow(int, int)}（取不到返回 null、**不会凭空加载区块**），
     * 顺手把客户端缓存里那份**活着的实例**登记回 {@link #LOADED}：区块被重发时会换成一个新
     * {@code LevelChunk} 实例，用缓存里那份才能读到最新内容。
     */
    private static void applyDirtyChunks(ClientLevel level) {
        if (dirtyChunks.isEmpty()) {
            return;
        }
        ChunkSource source = level.getChunkSource();
        int processed = 0;
        Iterator<Long> iterator = dirtyChunks.iterator();
        while (iterator.hasNext() && processed < MAX_DIRTY_CHUNKS_PER_TICK) {
            long chunkKey = iterator.next();
            iterator.remove();
            processed++;
            LevelChunk chunk = source.getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                // 区块已经不在客户端缓存里（刚被卸载）：它的段已经被 CHUNK_UNLOAD 摘干净了。
                continue;
            }
            LOADED.put(chunkKey, chunk);
            applyChunk(chunk);
        }
    }

    /**
     * 【1.27】重扫的**一片**：每 {@link #RESCAN_INTERVAL} 刻取一片，一片处理
     * {@code ceil(已加载区块数 / RESCAN_SWEEP_TICKS)} 个区块。
     *
     * <p>游标走到队尾就重取一次快照（`LOADED.keySet()`）再从头走 —— 队列里的键可能在
     * 扫描期间因为区块卸载而失效，取不到就跳过（下一轮快照自然不再包含它）。
     * 新加载的区块最迟在下一轮快照里加入。
     *
     * <p>★ 墙钟代价（曾经写错过，别再照旧注释理解）：**一遍 = 取片间隔 × 片数 =
     * {@link #RESCAN_INTERVAL} × {@link #RESCAN_SWEEP_TICKS} = 100 刻（5 秒）**。
     * 每 tick 的开销恒定（这正是 1.27 要的：不再有 0.5 秒一次的尖峰），
     * 但这一遍**很慢** —— 所以「改完立刻要看见」这条需求**不许**再压到它身上，
     * 走 {@link #onBlockChanged} 那条即时路（本方法现在只是兜底）。
     */
    private static void rescanSlice() {
        if (rescanCursor >= rescanQueue.size()) {
            rescanQueue.clear();
            rescanQueue.addAll(LOADED.keySet());
            rescanCursor = 0;
            if (rescanQueue.isEmpty()) {
                return;
            }
        }
        int total = rescanQueue.size();
        int budget = Math.max(1, (total + RESCAN_SWEEP_TICKS - 1) / RESCAN_SWEEP_TICKS);
        for (int i = 0; i < budget && rescanCursor < total; i++) {
            LevelChunk chunk = LOADED.get(rescanQueue.get(rescanCursor++));
            if (chunk != null) {
                applyChunk(chunk);
            }
        }
    }

    /** 摘掉某个区块登记过的所有段。 */
    private static void dropChunkSections(long chunkKey) {
        long[] previous = CHUNK_SECTIONS.remove(chunkKey);
        if (previous == null) {
            return;
        }
        for (long key : previous) {
            Section removed = SECTIONS.remove(key);
            if (removed != null) {
                stepCount -= removed.positions.length;
                sectionCount--;
            }
        }
        flattenedDirty = true;
    }

    /**
     * 重新扫描一个区块，把它登记过的段替换成最新内容。
     *
     * <p>只动这一个区块的段 —— 旧版是「整表重建」，几百个区块陆续加载时等于 O(总阶梯数) × 区块数。
     */
    private static void applyChunk(LevelChunk chunk) {
        long chunkKey = chunk.getPos().toLong();
        long[] previousKeys = CHUNK_SECTIONS.remove(chunkKey);

        Map<Long, Section> fresh = scanSections(chunk);

        boolean changed = false;
        // ① 旧段：不在新的结果里 ⇒ 这个区块的这段被拆空了。
        if (previousKeys != null) {
            for (long key : previousKeys) {
                if (!fresh.containsKey(key)) {
                    Section removed = SECTIONS.remove(key);
                    if (removed != null) {
                        stepCount -= removed.positions.length;
                        sectionCount--;
                        changed = true;
                    }
                }
            }
        }
        // ② 新段：内容变了才替换（内容没变就保留旧对象，连 AABB 和 revision 都不用重造）。
        for (Map.Entry<Long, Section> entry : fresh.entrySet()) {
            Section old = SECTIONS.get(entry.getKey());
            if (old != null && sameContent(old, entry.getValue())) {
                continue;
            }
            if (old != null) {
                stepCount -= old.positions.length;
                sectionCount--;
            }
            SECTIONS.put(entry.getKey(), entry.getValue());
            stepCount += entry.getValue().positions.length;
            sectionCount++;
            changed = true;
        }
        // ③ 登记这个区块这一轮的段键。
        if (fresh.isEmpty()) {
            CHUNK_SECTIONS.remove(chunkKey);
        } else {
            long[] keys = new long[fresh.size()];
            int index = 0;
            for (Long key : fresh.keySet()) {
                keys[index++] = key;
            }
            CHUNK_SECTIONS.put(chunkKey, keys);
        }
        if (changed) {
            flattenedDirty = true;
        }
    }

    /**
     * 【1.29】两段内容是否完全一致（位置 + 方块状态指纹）。
     *
     * <p>旧版只比位置。那会漏掉一类**不动坐标的改变**：扶梯被刷子刷停（{@code status=false}）、
     * 朝向/上下行被改 —— 方块还是那些方块，但模型与贴图族全变了。静态几何缓存一旦漏判，
     * 表现是「刷停了台阶照样滚」这种**看起来很玄**的现象，所以这里连状态指纹一起比。
     */
    private static boolean sameContent(Section a, Section b) {
        return a.signature == b.signature && samePositions(a.positions, b.positions);
    }

    private static boolean samePositions(BlockPos[] a, BlockPos[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    /** 扫一个区块，返回它所有**非空**分段（键 = {@link SectionPos#asLong(int, int, int)}）。 */
    private static Map<Long, Section> scanSections(LevelChunk chunk) {
        Map<Long, Section> found = new HashMap<>();
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos chunkPos = chunk.getPos();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        int bottom = chunk.getMinBuildHeight();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(IS_STEP)) {
                continue;
            }
            int baseY = bottom + index * SECTION_SIZE;
            List<BlockPos> list = new ArrayList<>();
            // 【1.29】顺带算内容指纹。这里本来就要读每一个格子的 BlockState（判 it 是不是台阶），
            // 所以折指纹几乎不要钱 —— 但它让「坐标没动、状态动了」也能被识别出来。
            long signature = FNV_OFFSET;
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    for (int x = 0; x < SECTION_SIZE; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (EscalatorUtil.isEscalatorStep(state)) {
                            list.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                            signature = fnv(signature, Block.getId(state));
                        }
                    }
                }
            }
            if (!list.isEmpty()) {
                long key = SectionPos.asLong(baseX >> 4, baseY >> 4, baseZ >> 4);
                found.put(key, new Section(key, baseX, baseY, baseZ,
                        nextRevision(), signature, list.toArray(new BlockPos[0])));
            }
        }
        return found;
    }

    /** 【1.29】发一个全新的版本戳（只增不减，见 {@link #revisionCounter}）。 */
    private static long nextRevision() {
        return ++revisionCounter;
    }

    /** 【1.29】FNV-1a 64 位：把方块状态 id 折进指纹。 */
    private static long fnv(long hash, int value) {
        hash ^= value & 0xFFFFFFFFL;
        return hash * FNV_PRIME;
    }
}
