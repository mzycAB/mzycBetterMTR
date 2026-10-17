package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

public final class EscalatorUtil {
    private static final int MAX_CHAIN_BLOCKS = 4096;
    private static final int MAX_WALK_STEPS = 512;
    /** 并入「另一半」列时最多迭代几轮（通常 2 轮就收敛）。 */
    private static final int MAX_PARTNER_PASSES = 4;
    /** 找伙伴块时允许的竖直错位范围（坡段 / 过渡段 / 两列没对齐时用得上）。 */
    private static final int PARTNER_DY = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 疑似扶梯阶梯方块的缓存（类型判断按类名做，避免编译期依赖 MTR）。 */
    private static Set<Block> escalatorStepBlocks;
    /** 疑似扶梯侧板方块的缓存（同上）。 */
    private static Set<Block> escalatorSideBlocks;

    private EscalatorUtil() {
    }

    public static boolean isEscalator(BlockState state) {
        return state.getBlock().getClass().getName().toLowerCase(Locale.ROOT).contains("escalator");
    }

    /**
     * 是否是「阶梯」方块（MTR 的 BlockEscalatorStep）。
     *
     * <p>只有阶梯方块才有会动的阶梯贴图；两边的侧板（BlockEscalatorSide）用的是静态贴图，
     * 不需要 SmoothLift 逐条渲染，所以这里要和 {@link #isEscalator} 区分开。
     */
    public static boolean isEscalatorStep(BlockState state) {
        return state != null && isEscalatorStepBlock(state.getBlock());
    }

    public static boolean isEscalatorStepBlock(Block block) {
        return escalatorStepBlocks().contains(block);
    }

    /**
     * 是否是「侧板」方块（MTR 的 BlockEscalatorSide）。
     *
     * <p>MTR 放一格扶梯会同时生成台阶块与其正上方的侧板块；石斧调速要对两者一起生效，
     * 因为玩家站在扶梯侧面时，准星点到的往往是侧板而不是台阶。判定同样按类名做，
     * 避免编译期依赖 MTR。
     */
    public static boolean isEscalatorSide(BlockState state) {
        return state != null && escalatorSideBlocks().contains(state.getBlock());
    }

    /**
     * 遍历方块注册表，缓存所有类名里带 escalatorstep 的方块。
     * 用 HashSet&lt;Block&gt; 做 O(1) 判定 —— 区块扫描时每格都要判断，不能每次去 toLowerCase()。
     */
    private static Set<Block> escalatorStepBlocks() {
        Set<Block> cached = escalatorStepBlocks;
        if (cached == null) {
            Set<Block> found = new HashSet<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block.getClass().getName().toLowerCase(Locale.ROOT).contains("escalatorstep")) {
                    found.add(block);
                }
            }
            if (found.isEmpty()) {
                LOGGER.warn("[SmoothLift] 没有在方块注册表里找到扶梯阶梯方块（MTR 未安装或版本不匹配？）");
            }
            escalatorStepBlocks = cached = found;
        }
        return cached;
    }

    /** 同 {@link #escalatorStepBlocks()}，只是匹配 escalatorside。 */
    private static Set<Block> escalatorSideBlocks() {
        Set<Block> cached = escalatorSideBlocks;
        if (cached == null) {
            Set<Block> found = new HashSet<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block.getClass().getName().toLowerCase(Locale.ROOT).contains("escalatorside")) {
                    found.add(block);
                }
            }
            escalatorSideBlocks = cached = found;
        }
        return cached;
    }

    /** 读取指定名字的布尔属性；不存在时返回 fallback。MTR 的属性靠名字访问，避免编译期依赖。 */
    public static boolean getBooleanProperty(BlockState state, String name, boolean fallback) {
        for (Property<?> prop : state.getProperties()) {
            if (name.equals(prop.getName()) && prop.getValueClass() == Boolean.class) {
                Object value = state.getValue(prop);
                if (value instanceof Boolean bool) {
                    return bool;
                }
            }
        }
        return fallback;
    }

    /**
     * 收集与 seed 相连的同一条扶梯链上的所有方块（含左右两列 + 侧板）。
     *
     * <p>MTR 的一条扶梯其实是**两列并排**的阶梯：放一个扶梯会生成
     * {@code side=left} 与 {@code side=right} 两列（right 列永远在 left 列沿 facing 顺时针
     * 一格的位置，见 MTR 的 ItemEscalator 放置逻辑），人看到的是左右各半边。
     * 所以石斧调速时只走一条列会出现「只能半边半边地调」。
     *
     * <p><b>【1.30】伙伴列的找法改回「从链上每一格出发、迭代到收敛」</b>：
     * 旧写法只在 seed 那一格上找「另一半」列，一旦 seed 那一格的伙伴槽位落空
     * （对面那一格被拆掉还没装回去、手工重放时两列没对齐、坡段/过渡段错位），
     * 这条链就会**静默地只收半边** —— 石斧调速只写半边方块，于是
     * 「扶梯阶梯分成左右两半、阶梯动画速度不统一」，而 {@code /futispeed} 是遍历所有坐标
     * 写的，所以指令一下又恢复正常。现在改成：
     * <ol>
     *   <li>先沿 facing 轴走完 seed 所在那一列；</li>
     *   <li>再从**链上每一格**出发、朝垂直于 facing 的方向找「另一半」列，找到就把那一列整条并进来，
     *       反复几轮直到不再出现新的伙伴列 —— 任何一格配得上伙伴，整条两列都能收齐；</li>
     *   <li>竖直窗口放到 ±{@link #PARTNER_DY} 格，并逐级放宽（先严格同 side 相反，
     *       再放开 side 限制，再换相反方向），两列在 Y 上错开也不会漏。</li>
     * </ol>
     *
     * <p>判定伙伴时优先「同 facing + side 相反」，避免把并排的另一条扶梯
     * （例如相邻的另一组左右双列、或上下行并排的那一条）误并入同一条。
     *
     * <p>没有 facing 属性时退化为 6 向泛洪。
     */
    public static Set<BlockPos> collectChain(Level level, BlockPos seed) {
        return collectChain(level, seed, false);
    }

    /**
     * 【1.22】只收**阶梯块**的扶梯链（护栏 / 侧板一律不要）。
     *
     * <p>为什么要单独开一个「只认阶梯」的版本：{@link #walkAxis} 走的是一条线，而阶梯块和护栏
     * （MTR 的 {@code escalator_side}）的类名都带 escalator，两条线是**贴着**的。只要游走的候选里
     * 混进护栏，从扶梯**中段往低处**走时就会被「同一高度的那块护栏」抢走（它在候选表里排第一），
     * 游走于是沿护栏滑走、**永远走不到下半条链** —— 表现出来就是「链只有玩家脚下这一段和它上面那一段」。
     * 只认阶梯块就从根本上避开这件事。
     *
     * <p>端头提示音（{@code EscalatorChimePlayer}）就是靠这个把整条扶梯展开、再按高度分组定两端的：
     * 链要是缺了下半段，「最低那头」就变成玩家脚下，于是**上客端提示音跟着玩家走**、一直不离开 4 格
     * 射程、直到玩家走出扶梯才停（还会和落客端那路重叠）。所以这个调用点必须拿到完整的链。
     */
    public static Set<BlockPos> collectStepChain(Level level, BlockPos seed) {
        return collectChain(level, seed, true);
    }

    /**
     * 【1.30】界面 / 指令显示用的「这条扶梯有多少格」= 链上的**台阶块**数（不含侧板）。
     *
     * <p>为什么要单独数一遍：{@link #collectChain} 现在会把「台阶 + 正上方侧板」都收进来
     * （见 {@link #addVerticalMate}），直接取 {@code chain.size()} 会变成台阶数的两倍，
     * 而玩家看到的一条扶梯只有台阶那么多格。指令反馈里的「共 N 格」用这个数。
     */
    public static int countChainSteps(Level level, BlockPos seed) {
        int steps = 0;
        for (BlockPos pos : collectChain(level, seed)) {
            if (isEscalatorStep(level.getBlockState(pos))) {
                steps++;
            }
        }
        return steps;
    }

    /**
     * @param stepsOnly true = 只认阶梯块（见 {@link #collectStepChain}）；false = 阶梯优先、护栏侧板也收
     */
    private static Set<BlockPos> collectChain(Level level, BlockPos seed, boolean stepsOnly) {
        Set<BlockPos> chain = new LinkedHashSet<>();
        if (stepsOnly ? !isEscalatorStep(level.getBlockState(seed)) : !isEscalator(level.getBlockState(seed))) {
            return chain;
        }

        Direction facing = getFacing(level.getBlockState(seed));
        if (facing == null) {
            floodFill(level, seed, chain, stepsOnly);
            return chain;
        }

        // 1) seed 所在的那一整列。
        walkAxis(level, seed, facing, chain, stepsOnly);
        walkAxis(level, seed, facing.getOpposite(), chain, stepsOnly);

        // 2) 并入相邻的「另一半」列，直到不再出现新的伙伴列（见方法头的【1.30】说明）。
        for (int pass = 0; pass < MAX_PARTNER_PASSES; pass++) {
            int before = chain.size();
            for (BlockPos base : new ArrayList<>(chain)) {
                if (chain.size() >= MAX_CHAIN_BLOCKS) {
                    return chain;
                }
                BlockPos partnerSeed = findPartnerSeed(level, base, facing, stepsOnly);
                if (partnerSeed == null || chain.contains(partnerSeed)) {
                    continue;
                }
                walkAxis(level, partnerSeed, facing, chain, stepsOnly);
                walkAxis(level, partnerSeed, facing.getOpposite(), chain, stepsOnly);
            }
            if (chain.size() == before) {
                break;
            }
        }
        return chain;
    }

    /**
     * 找出 base 这块所在的扶梯「另一半」列上、与它对应（允许上下错位）的那块。
     *
     * <p>MTR 的 right 列 = left 列沿 facing 顺时针一格；因此左半边往顺时针找、右半边往逆时针找
     * （side 读不到时按左半边处理）。首选方向严格匹配失败后逐步放宽：
     * 放开 side 限制 -&gt; 换相反方向（同样先严格再放宽），
     * 保证即使两列在 Y 上错开、或 side 属性异常也仍能配到伙伴列。
     */
    private static BlockPos findPartnerSeed(Level level, BlockPos base, Direction facing, boolean stepsOnly) {
        String side = getSideName(level.getBlockState(base));
        Direction primary = "right".equals(side) ? facing.getCounterClockWise() : facing.getClockWise();
        Direction secondary = primary.getOpposite();

        // 严格匹配（同 facing + side 相反）优先，避免误并并排的另一条扶梯。
        BlockPos found = matchPartner(level, base, primary, facing, side, true, stepsOnly);
        if (found == null) {
            found = matchPartner(level, base, primary, facing, side, false, stepsOnly);
        }
        if (found == null) {
            found = matchPartner(level, base, secondary, facing, side, true, stepsOnly);
        }
        if (found == null) {
            found = matchPartner(level, base, secondary, facing, side, false, stepsOnly);
        }
        return found;
    }

    /**
     * 在 base 沿 dir 方向、竖直 ±{@link #PARTNER_DY} 格范围内找「另一半」列上的块。
     *
     * <p>优先同高（dy=0），再依次试上下各 1、2 格 —— 同高命中时不会串到别的高度。
     *
     * @param requireOppositeSide true 时要求候选块的 side 与 base 相反（用于严格匹配，
     *                            避免误并并排的同向扶梯）
     * @param stepsOnly          true 时只接受阶梯块（提示音用的链不掺侧板）
     */
    private static BlockPos matchPartner(Level level, BlockPos base, Direction dir, Direction facing,
                                         String seedSide, boolean requireOppositeSide, boolean stepsOnly) {
        for (int ring = 0; ring <= PARTNER_DY; ring++) {
            for (int sign = 0; sign < (ring == 0 ? 1 : 2); sign++) {
                int dy = ring == 0 ? 0 : (sign == 0 ? -ring : ring);
                BlockPos pos = base.relative(dir).offset(0, dy, 0);
                BlockState state = level.getBlockState(pos);
                if (stepsOnly ? !isEscalatorStep(state) : !isEscalator(state)) {
                    continue;
                }
                Direction otherFacing = getFacing(state);
                if (otherFacing != null && otherFacing != facing) {
                    continue;
                }
                if (requireOppositeSide && seedSide != null) {
                    String otherSide = getSideName(state);
                    if (otherSide != null && otherSide.equals(seedSide)) {
                        continue;
                    }
                }
                return pos;
            }
        }
        return null;
    }

    /** 读取名为 side 的属性值（LEFT / RIGHT），小写返回；没有返回 null。 */
    private static String getSideName(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("side".equals(prop.getName())) {
                Object value = state.getValue(prop);
                if (value != null) {
                    return value.toString().toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    /**
     * 沿 {@code dir} 一格一格走到底，把路上每一块都收进 {@code out}。
     *
     * <p><b>【1.22】候选顺序改成「阶梯块优先」</b>（见 {@link #nextAlong}）：护栏 / 侧板贴在阶梯的
     * 斜上方，和阶梯挤在同一个候选位置上，必须让阶梯排在前面 —— 否则从扶梯中段往低处走时，
     * 会被「同一高度的那块护栏」抢走（它正好是候选里的第一项），于是游走沿着护栏滑出去、
     * 再也回不到下半条链的阶梯上。
     *
     * <p><b>【1.30】每走一格都顺手把「竖直方向上的另一块」收进来</b>（见 {@link #addVerticalMate}）：
     * 台阶 ↔ 正上方侧板是一对，玩家点台阶还是点侧板都应该整条生效。
     *
     * @param stepsOnly true = 只走阶梯块（候选里没有阶梯就停下，也不收侧板）；false = 阶梯优先，
     *                  实在没阶梯时才退回「只要是扶梯方块就行」（让 {@link #collectChain} 仍能收到侧板）
     */
    private static void walkAxis(Level level, BlockPos start, Direction dir, Set<BlockPos> out, boolean stepsOnly) {
        BlockPos current = start;
        for (int i = 0; i < MAX_WALK_STEPS && out.size() < MAX_CHAIN_BLOCKS; i++) {
            out.add(current);
            addVerticalMate(level, current, out, stepsOnly);

            BlockPos next = nextAlong(level, current.relative(dir), stepsOnly);
            if (next == null) {
                break;
            }
            current = next;
        }
    }

    /**
     * 【1.30】把当前这一格「竖直方向上的另一块」也收进来：台阶 ↔ 正上方的侧板。
     *
     * <p>MTR 放扶梯时台阶块与侧板块是同 x/z、上下相邻的一对；石斧点台阶还是点侧板
     * 都应该整条生效。只按「台阶 ↔ 侧板」这种**不同种类**的竖直邻居来配对，
     * 因此不会把上下层叠的其它扶梯（同种类方块）串进来。
     *
     * <p>{@code stepsOnly} 时不做这件事 —— 提示音要的是「只有阶梯」的链，混进侧板会把
     * 端头定位与射程判断带偏（见 {@link #collectStepChain}）。
     */
    private static void addVerticalMate(Level level, BlockPos current, Set<BlockPos> out, boolean stepsOnly) {
        if (stepsOnly || out.size() >= MAX_CHAIN_BLOCKS) {
            return;
        }
        BlockState state = level.getBlockState(current);
        BlockPos matePos;
        if (isEscalatorStep(state)) {
            matePos = current.above();
            if (!isEscalatorSide(level.getBlockState(matePos))) {
                return;
            }
        } else if (isEscalatorSide(state)) {
            matePos = current.below();
            if (!isEscalatorStep(level.getBlockState(matePos))) {
                return;
            }
        } else {
            return;
        }
        out.add(matePos);
    }

    /**
     * 在「前方那一格、它上面、它下面」三个候选里挑下一块：**先挑阶梯块**，挑不到再看要不要兜底。
     *
     * <p>这一格一格的顺序（同高 → 上一格 → 下一格）与旧版一致，**只改了优先级** —— 旧版是
     * 「只要是扶梯方块，谁在前面算谁」，于是下坡时同高的那块护栏（不是阶梯）会被选中，
     * 游走就离开阶梯线了。
     */
    private static BlockPos nextAlong(Level level, BlockPos ahead, boolean stepsOnly) {
        BlockPos[] candidates = {ahead, ahead.above(), ahead.below()};
        for (BlockPos candidate : candidates) {
            if (isEscalatorStep(level.getBlockState(candidate))) {
                return candidate;
            }
        }
        if (stepsOnly) {
            return null;
        }
        for (BlockPos candidate : candidates) {
            if (isEscalator(level.getBlockState(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static void floodFill(Level level, BlockPos seed, Set<BlockPos> out, boolean stepsOnly) {
        Set<BlockPos> visited = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        while (!queue.isEmpty() && visited.size() < MAX_CHAIN_BLOCKS) {
            BlockPos pos = queue.poll();
            out.add(pos);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                boolean accepted = stepsOnly
                        ? isEscalatorStep(level.getBlockState(neighbor))
                        : isEscalator(level.getBlockState(neighbor));
                if (visited.add(neighbor) && accepted) {
                    queue.add(neighbor);
                }
            }
        }
    }

    /**
     * 读取名为 facing 的属性值；没有返回 null。
     *
     * <p>对扶梯来说这就是**运行轴**：MTR 的 {@code BlockEscalatorStep.onEntityCollision2}
     * 沿这条轴给实体加水平速度（{@code facing=EAST} 时给 x 分量、{@code NORTH} 时给 z 分量），
     * 所以阶梯是沿 facing 这条轴前进的。
     * （提示音靠它把整条扶梯投到一条轴上分出两端，见 {@code EscalatorChimePlayer}。）
     */
    public static Direction getFacing(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("facing".equals(prop.getName()) && prop.getValueClass() == Direction.class) {
                return (Direction) state.getValue(prop);
            }
        }
        return null;
    }
}
