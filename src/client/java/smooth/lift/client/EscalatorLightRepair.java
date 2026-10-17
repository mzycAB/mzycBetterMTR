package smooth.lift.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorUtil;

/**
 * 【1.27】修**原版 MTR 自带**的「扶梯贴着完整方块 ⇒ 扶梯贴图变黑」。
 *
 * <h2>症状</h2>
 * 扶梯（阶梯 + 侧板/外壳）只要**与一个完整方块相邻**（方块压在它上面、贴在它侧面、
 * 塞在它下面都算），那一处的扶梯贴图变成**纯黑**；把方块拆掉就恢复。
 * 1.20.4 用的是 MTR 4.0.5，现象与 MTR 本体一致 —— <b>不装本模组也复现</b>，是 MTR 的 bug。
 *
 * <h2>根因（1.20.4 字节码实测，不是猜的）</h2>
 * 扶梯的面之所以会黑，是两件事拼出来的：
 *
 * <ol>
 *   <li><b>这些面永远不会被剔除。</b>原版 {@code Block.shouldRenderFace} 的逻辑是：
 *       <pre>
 *   if (state.skipRendering(neighbor, side)) return false;
 *   if (neighbor.canOcclude()) {
 *       VoxelShape thisShape = state.getFaceOcclusionShape(level, pos, side);
 *       if (thisShape.isEmpty()) return true;                       // ← 这里
 *       return Shapes.joinIsNotEmpty(thisShape, neighborShape, ONLY_FIRST);
 *   }
 *   return true;</pre>
 *       而 MTR 的 {@code BlockEscalatorSide.getCullingShape2} <b>返回 {@code VoxelShapes.empty()}</b>
 *       （MTR 映射名 → 原版 {@code Block.getOcclusionShape}，已用 loom 的 mappings.tiny 核过：
 *       {@code method_9571 = getOcclusionShape}）。于是「扶梯自己的遮挡面为空」这条分支命中 ⇒
 *       <b>直接 return true</b>，邻块即使是实心方块也<b>不剔除</b>——面照样画出来。</li>
 *   <li><b>画出来的那个面，取光点在邻块里。</b>原版取光一律按「面朝哪边，就去那一格取」：
 *       <ul>
 *         <li>AO 路径（侧板模型 {@code ambientocclusion} 默认 true 走这条）：
 *             {@code ModelBlockRenderer$AmbientOcclusionFace.calculate(level, state, pos, direction, …)}
 *             内部对每个角点调 {@code Cache.getLightColor(state, level, cornerPos)}；</li>
 *         <li>平面路径（{@code escalator_step_slope_*_base} 写了 {@code "ambientocclusion": false}）：
 *             {@code tesselateWithoutAO} 与 {@code renderModelFaceFlat} 里直接
 *             {@code LevelRenderer.getLightColor(level, state, pos.relative(…) )}。</li>
 *       </ul>
 *       邻块是实心方块 ⇒ 那格的光是 <b>0</b>（光不能传播进不透光方块）⇒ 顶点光图 = 0 ⇒ <b>纯黑</b>。
 *       又因为第 1 条没剔除，这个黑面被实实在在地画了出来。
 *       <p>注意 AO 路径里 {@code calculate} 对「邻格是实心」本来是<b>有</b>一道保护的
 *       （{@code if (isSolidRender(邻格)) 保留扶梯自己那格的光}），但它会被 {@code flags.get(0)}
 *       旁路：当这个面「自己贴齐方块边界」且扶梯的碰撞形状是整格时，{@code flags.get(0)} 为真 ⇒
 *       代码<b>跳过</b>那道检查、直接拿邻格的光 ⇒ 还是 0。所以两条路都要修。</li>
 * </ol>
 *
 * <p>顺带解释了「为什么 MTR 要把遮挡形状设成空」：扶梯几何是跨格的，如果它按整格遮挡，
 * 邻块朝向扶梯的那一面会被剔除，露出扶梯内部 ——「透视方块」。所以**不能**用「给扶梯补一个
 * 遮挡形状」来修（那会换来 X 光漏洞），只能修取光。
 *
 * <h2>修法（本类 = 共用判据；三处 mixin 见同包 mixin 子包）</h2>
 * 只改一件事：<b>扶梯的面取到的光恰好是「纯黑」时，改用那一格扶梯自己的亮度。</b>
 *
 * <p>判据刻意选「<b>采样值 == 0</b>」+「<b>扶梯自己那格有光</b>」，<b>不</b>去判「采样格是不是
 * 被实心方块埋住」：
 * <ul>
 *   <li>「纯黑」就是用户看到的症状本身，口径最窄 —— 只动本来会变成纯黑的面，其余一律交回原版，
 *       连「扶梯边上的火把该照亮哪一面」这种观感细节都不碰；</li>
 *   <li>「是不是被实心方块埋住」要自己去问 {@code isSolidRender}，而采样点是原版自己算出来的
 *       角点（{@code base.offset(corners[i])}）。我们对「哪一个角点落在方块里」的推断一旦和原版
 *       差一格，判据就静默失效 —— 这正是上一版修不干净的原因。改成只看「取到的光是 0」，
 *       就不再依赖任何推断。</li>
 * </ul>
 * 借来的值是**扶梯自己那一格的亮度**：那正是 {@link EscalatorStepCache} 画阶梯面时用的值
 * （{@code LevelRenderer.getLightColor(level, state, pos)}，{@code pos} = 扶梯自己那格），
 * 所以外壳与阶梯面的亮度口径从此一致。
 *
 * <p>扶梯自己那格也是 0（真的在黑屋子里）⇒ 没光可借，保持原版行为，不硬点亮。
 *
 * <h2>★ 重入闸（少了它就会 StackOverflowError）</h2>
 * {@code LevelRenderer.getLightColor(level, pos)} 这个 <b>2 参</b>重载的字节码就是
 * <pre>  return getLightColor(level, level.getBlockState(pos), pos);   // 转头调 3 参</pre>
 * 而 3 参正是被 {@link smooth.lift.client.mixin.EscalatorFlatLightMixin} 挂过的那个方法。
 * 于是「本类借光 → 2 参 → 3 参 → mixin → 本类 ……」会无限递归。
 * {@link #IN_REPAIR} 就是那道闸：借光期间的一切 {@code getLightColor} 都取原版值、不再进本类。
 * 所以本类里**所有**取光都必须写在闸内（{@link #rawLight} 的注释里也标了这条）。
 */
public final class EscalatorLightRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 不需要修正（交回原版）。用负数，和任何合法的 packed light（≥ 0）都不冲突。 */
    public static final int NOT_REPAIRED = -1;

    /** 「当前没有正在画的扶梯」。{@code BlockPos.asLong()} 的取值不可能到 {@code Long.MAX_VALUE}。 */
    private static final long NO_OWN = Long.MAX_VALUE;

    /**
     * AO 路径里「当前正在画的那一格扶梯」的坐标。
     *
     * <p>为什么用 {@code ThreadLocal}：原版区块网格是**在 worker 线程上**编译的
     * （{@code SectionRenderDispatcher} 用 {@code Util.backgroundExecutor}），用一个静态字段
     * 会被多线程互相踩；而原版自己的 {@code ModelBlockRenderer.CACHE} 也是 ThreadLocal，同一套路。
     *
     * <p>为什么是 {@code long[]}：写的是 {@code asLong()}，热路径（每个 AO 面都会写一次）**零分配**。
     */
    private static final ThreadLocal<long[]> AO_OWN = ThreadLocal.withInitial(() -> new long[]{NO_OWN});

    /** 见类注释「重入闸」。值为 {@code Boolean.TRUE} 表示「现在正在借光，别再来一遍」。 */
    private static final ThreadLocal<Boolean> IN_REPAIR = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 「上一次判定为非扶梯」的方块，单条负缓存。
     *
     * <p>{@link #isEscalator} 会被挂在 {@code LevelRenderer.getLightColor} 的入口上，也就是
     * **每渲染一个方块面都要问一次**。绝大多数方块（石头、玻璃、扶梯以外的一切）都要问，
     * 所以这里用「上次那个不是扶梯的方块」做一次引用比较直接短路，
     * 避免每次都去做两次 HashSet 查找。竞态无害：只可能少命中一次快路径，不会判错。
     */
    private static volatile Block lastOther;

    // ------------------------------------------------------------------
    // 一次性诊断：每种事件只打**一行**日志。
    // 只读日志时，「mixin 没挂上」「挂上了但没认出扶梯」「认出了但没改过光」是三种完全不同的
    // 故障，而这三种在现象上一模一样（都是「扶梯还是黑」）。每种一行，正常游玩零噪音。
    // ------------------------------------------------------------------
    private static volatile boolean saidAoEntry;
    private static volatile boolean saidAoHit;
    private static volatile boolean saidAoFix;
    private static volatile boolean saidFlatEntry;
    private static volatile boolean saidFlatFix;

    private EscalatorLightRepair() {
    }

    /**
     * 这个状态是不是 MTR 的扶梯方块（阶梯 {@code BlockEscalatorStep} 或侧板 {@code BlockEscalatorSide}）。
     *
     * <p>复用 {@link EscalatorUtil} 那套「按类名扫注册表并缓存」的判据（不依赖 MTR 编译期），
     * 前面加一层单条负缓存。
     */
    public static boolean isEscalator(BlockState state) {
        if (state == null) {
            return false;
        }
        Block block = state.getBlock();
        if (block == lastOther) {
            return false;
        }
        if (EscalatorUtil.isEscalatorStep(state) || EscalatorUtil.isEscalatorSide(state)) {
            return true;
        }
        lastOther = block;
        return false;
    }

    /**
     * AO 路径：{@code AmbientOcclusionFace.calculate} 每次开始算某一格时，把「这一格」记下来。
     *
     * <p>之所以要在 AO 里单独记：{@code Cache.getLightColor} 拿到的 {@code state} 是
     * <b>采样格自己那一格的方块</b>（字节码里是 {@code level.getBlockState(cornerPos)} 的返回值），
     * 不是正在渲染的扶梯 —— 光看它认不出「现在在画扶梯」。而 {@code calculate} 的第一个参数
     * 才是被渲染方块的状态、第三个参数才是它自己的坐标（{@code renderModelFaceAO} 传的就是这两个）。
     *
     * <p>不需要配套的「退出」清理：{@code Cache.getLightColor} 的调用点全在 {@code calculate}
     * 内部（已用 javap 核过，外层 {@code ModelBlockRenderer} 一次都不调它），
     * 所以这个值在被读到时永远是本格刚写进去的。
     */
    public static void beginAo(BlockState state, BlockPos pos) {
        boolean hit = isEscalator(state);
        AO_OWN.get()[0] = hit ? pos.asLong() : NO_OWN;
        if (!saidAoEntry) {
            saidAoEntry = true;
            LOGGER.info("[SmoothLift] 取光修正：AO 入口已挂上（首格 {}）",
                    state == null ? "null" : state.getBlock().getClass().getName());
        }
        if (hit && !saidAoHit) {
            saidAoHit = true;
            LOGGER.info("[SmoothLift] 取光修正：AO 路径认出扶梯 {} @ {}",
                    state.getBlock().getClass().getName(), pos);
        }
    }

    /**
     * AO 路径的取光修正：采到的光是**纯黑**时，改用「正在画的那一格扶梯」的亮度。
     *
     * @return 修正后的 packed light，或 {@link #NOT_REPAIRED}（交回原版）
     */
    public static int repairAo(BlockAndTintGetter level, BlockPos samplePos) {
        if (IN_REPAIR.get()) {
            return NOT_REPAIRED;
        }
        long own = AO_OWN.get()[0];
        if (own == NO_OWN) {
            return NOT_REPAIRED;
        }
        BlockPos ownPos = BlockPos.of(own);
        // 采的正是「正在画的那一格扶梯」自己 —— 原版值就是对的，一个字都不改。
        // （原版在 calculate 里就会用扶梯自己那一格取一次光，走的就是这里。）
        if (ownPos.equals(samplePos)) {
            return NOT_REPAIRED;
        }
        IN_REPAIR.set(Boolean.TRUE);
        try {
            if (rawLight(level, samplePos) != 0) {
                return NOT_REPAIRED;
            }
            // 采样点自己就是一个扶梯格 ⇒ 那格的光本来就是「扶梯自己那一格的光」，
            // 原版值已经是对的。这一条同时守住**自绘引擎**那条路：
            // EscalatorStepCache.writeBlock 就是拿「扶梯自己那格」来取光的，绝不能被这里改。
            if (isEscalator(level.getBlockState(samplePos))) {
                return NOT_REPAIRED;
            }
            int ownLight = rawLight(level, ownPos);
            // 扶梯自己那格也是全黑（真的在黑屋子里）⇒ 没光可借，保持原版行为。
            if (ownLight == 0) {
                return NOT_REPAIRED;
            }
            if (!saidAoFix) {
                saidAoFix = true;
                LOGGER.info("[SmoothLift] 取光修正：AO 把纯黑采样点 {} 换成扶梯格 {} 的亮度", samplePos, ownPos);
            }
            return ownLight;
        } finally {
            IN_REPAIR.set(Boolean.FALSE);
        }
    }

    /**
     * 平面（非 AO）路径的取光修正。
     *
     * <p>这条路上 {@code LevelRenderer.getLightColor(level, state, pos)} 的 {@code state}
     * 就是被渲染的方块（{@code tesselateWithoutAO} / {@code renderModelFaceFlat} 传的都是它），
     * 所以能直接判；但采样点 {@code pos} 是「面外侧那一格」，<b>不是</b>扶梯自己那一格，
     * 于是要在它周围找一格扶梯来借光（面外侧那一格与扶梯自己那格一定相邻）。
     *
     * <p>取「周围所有扶梯格里最亮的那一格」而不是「找到的第一格」：阶梯块与它正上方的侧板
     * 是一对，两个都挨着采样点，取最亮的可以避免「恰好挑到更暗的那一格、结果仍然接近黑」。
     *
     * @return 修正后的 packed light，或 {@link #NOT_REPAIRED}（交回原版）
     */
    public static int repairFlat(BlockAndTintGetter level, BlockState state, BlockPos samplePos) {
        if (IN_REPAIR.get()) {
            return NOT_REPAIRED;
        }
        if (!isEscalator(state)) {
            return NOT_REPAIRED;
        }
        if (!saidFlatEntry) {
            saidFlatEntry = true;
            LOGGER.info("[SmoothLift] 取光修正：平面路径入口已挂上（被渲染的扶梯 {}）",
                    state.getBlock().getClass().getName());
        }
        IN_REPAIR.set(Boolean.TRUE);
        try {
            // 只有「采到的光是纯黑」才动。这一条同时把「原版本来就对」的绝大多数情况直接放行：
            // 正常光照下采样值不为 0，一次整数比较就退出。
            if (rawLight(level, samplePos) != 0) {
                return NOT_REPAIRED;
            }
            // 采样点自己就是一个扶梯格 ⇒ 原版取的就是「扶梯自己那一格的光」，已经是对的。
            // 这一条也是**自绘引擎的护栏**：{@link EscalatorStepCache} 正是拿扶梯自己那格取光的。
            if (isEscalator(level.getBlockState(samplePos))) {
                return NOT_REPAIRED;
            }
            int best = 0;
            for (Direction direction : Direction.values()) {
                BlockPos candidate = samplePos.relative(direction);
                if (!isEscalator(level.getBlockState(candidate))) {
                    continue;
                }
                int light = rawLight(level, candidate);
                if (light > best) {
                    best = light;
                }
            }
            if (best == 0) {
                return NOT_REPAIRED;
            }
            if (!saidFlatFix) {
                saidFlatFix = true;
                LOGGER.info("[SmoothLift] 取光修正：平面路径把纯黑采样点 {} 换成相邻扶梯格的亮度", samplePos);
            }
            return best;
        } finally {
            IN_REPAIR.set(Boolean.FALSE);
        }
    }

    /**
     * 取原版值。**只能在 {@link #IN_REPAIR} 打开时调用**，否则会绕回自己的 mixin 钩子。
     *
     * <p>这里调的是 2 参数重载 {@code getLightColor(level, pos)}（语义 = 取该格方块状态在该格的光）。
     */
    private static int rawLight(BlockAndTintGetter level, BlockPos pos) {
        return LevelRenderer.getLightColor(level, pos);
    }
}
