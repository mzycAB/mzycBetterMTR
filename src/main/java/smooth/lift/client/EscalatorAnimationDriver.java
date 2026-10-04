package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

/**
 * 「当前驱动阶梯动画的扶梯」管理器。
 *
 * <p>为什么需要它：MTR 的扶梯阶梯贴图（escalator_up / escalator_down）是**全地图共享的动画
 * 精灵**，同一时刻整张地图只有一套动画相位。所以「每条扶梯各自动各自的速度」在贴图层面
 * 是无法同时成立的 —— 同一时刻只能选出**一条**扶梯来驱动动画。
 *
 * <p>因此这里维护一个「当前驱动动画的扶梯」，选择规则（按优先级）：
 * <ol>
 *   <li><b>玩家正站在其上</b>的扶梯（乘坐体验最重要）；</li>
 *   <li><b>玩家准星指向</b>的扶梯（射线最远 {@link #MAX_DRIVER_DISTANCE} 格，所以站在远处
 *       看着它也能看到它自己的动画）；</li>
 *   <li><b>上一次选中的扶梯</b>：只要它仍然加载、且玩家离它不超过
 *       {@link #MAX_DRIVER_DISTANCE} 格，就继续由它驱动 —— 这就是「改完之后人走开了，
 *       被修改的扶梯仍然保持自己的阶梯动画」的关键；</li>
 *   <li>附近 {@link #NEARBY_RADIUS} 格内最近的一条扶梯；</li>
 *   <li>都没有 -> 交回维度默认阶梯动画速度（{@code /jietispeed} 设定）。</li>
 * </ol>
 *
 * <p>用石斧右键某条扶梯（打开设置界面）会把它直接设为当前驱动扶梯，于是「刚改完的那条」
 * 立刻成为驱动，离开后也不会掉回全局动画。切换只发生在玩家乘坐/注视/操作了另一条扶梯，
 * 或当前这条被卸载、离得太远时。
 *
 * <p>【1.26】第 4 条「附近兜底」不再逐格扫立方体（旧版半径 16 要扫 33³ ≈ 3.6 万次
 * {@code getBlockState}，而本方法每 tick 都跑），改成查 {@link EscalatorStepIndex} 的分段索引 ——
 * 见 {@link #nearestEscalator}。
 */
public final class EscalatorAnimationDriver {

    /** 视线解析与「保持选中」允许的最大距离（格）。 */
    private static final double MAX_DRIVER_DISTANCE = 64.0;
    /** 「附近兜底」搜索半径（格）。 */
    private static final int NEARBY_RADIUS = 16;

    /** 当前选中的驱动扶梯所在维度；null 表示没有选中。 */
    private static ResourceKey<Level> selectedDimension;
    /** 当前选中的驱动扶梯方块坐标；null 表示没有选中。 */
    private static BlockPos selectedPos;

    private EscalatorAnimationDriver() {
    }

    /** 用石斧右键扶梯（打开设置界面）时调用：把它设为当前驱动动画的扶梯。 */
    public static void select(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        selectedDimension = level.dimension();
        selectedPos = pos.immutable();
    }

    /** 这条扶梯是否正在驱动当前的阶梯动画（供界面提示）。 */
    public static boolean isDriver(Level level, BlockPos pos) {
        return level != null && pos != null && selectedPos != null
                && level.dimension().equals(selectedDimension)
                && selectedPos.equals(pos);
    }

    /** 断开连接/换世界时清空选择，避免残留。 */
    public static void clear() {
        selectedDimension = null;
        selectedPos = null;
    }

    /** 解析当前该采用的阶梯动画速度。 */
    public static double resolveStepAnimationSpeed() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return EscalatorSpeedData.VANILLA_STEP;
        }
        Level level = minecraft.level;
        Player player = minecraft.player;
        if (player == null) {
            return EscalatorSpeedManager.getDefaultStepSpeed(level);
        }

        // 1) 玩家正站在其上的扶梯
        BlockPos ridden = escalatorAt(level, player.blockPosition());
        if (ridden != null) {
            select(level, ridden);
            return speedOf(level, ridden);
        }

        // 2) 准星指向的扶梯（长距离）
        BlockPos looked = lookedAtEscalator(player, level);
        if (looked != null) {
            select(level, looked);
            return speedOf(level, looked);
        }

        // 3) 保持上一次选中的扶梯 —— 人走开后不回落成全局动画
        if (isSelectedUsable(level, player)) {
            return speedOf(level, selectedPos);
        }

        // 4) 附近最近的扶梯
        BlockPos near = nearestEscalator(level, player.blockPosition());
        if (near != null) {
            select(level, near);
            return speedOf(level, near);
        }

        // 5) 没有任何可用扶梯
        clear();
        return EscalatorSpeedManager.getDefaultStepSpeed(level);
    }

    private static double speedOf(Level level, BlockPos pos) {
        return EscalatorSpeedManager.getStepAnimationSpeed(level, pos);
    }

    /** 选中的扶梯是否仍然可用：同维度、没离太远、而且还在（区块卸载时读出来是空气）。 */
    private static boolean isSelectedUsable(Level level, Player player) {
        if (selectedPos == null || selectedDimension == null) {
            return false;
        }
        if (!selectedDimension.equals(level.dimension())) {
            return false;
        }
        double dx = player.getX() - (selectedPos.getX() + 0.5);
        double dy = player.getY() - (selectedPos.getY() + 0.5);
        double dz = player.getZ() - (selectedPos.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz > MAX_DRIVER_DISTANCE * MAX_DRIVER_DISTANCE) {
            return false;
        }
        return EscalatorUtil.isEscalator(level.getBlockState(selectedPos));
    }

    /** 玩家脚下/身上（脚下一格、所在格、上一格）是否为扶梯。 */
    private static BlockPos escalatorAt(Level level, BlockPos feet) {
        if (EscalatorUtil.isEscalator(level.getBlockState(feet))) {
            return feet;
        }
        BlockPos below = feet.below();
        if (EscalatorUtil.isEscalator(level.getBlockState(below))) {
            return below;
        }
        BlockPos above = feet.above();
        if (EscalatorUtil.isEscalator(level.getBlockState(above))) {
            return above;
        }
        return null;
    }

    /** 视线（准星）命中的扶梯，允许较远距离：命中格、其下一格或上一格是扶梯即可。 */
    private static BlockPos lookedAtEscalator(Player player, Level level) {
        HitResult hit = player.pick(MAX_DRIVER_DISTANCE, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = blockHit.getBlockPos();
        if (EscalatorUtil.isEscalator(level.getBlockState(hitPos))) {
            return hitPos;
        }
        BlockPos below = hitPos.below();
        if (EscalatorUtil.isEscalator(level.getBlockState(below))) {
            return below;
        }
        BlockPos above = hitPos.above();
        if (EscalatorUtil.isEscalator(level.getBlockState(above))) {
            return above;
        }
        return null;
    }

    /**
     * 附近最近的扶梯阶梯方块。
     *
     * <p>【1.26】★ 改成走 {@link EscalatorStepIndex} 的分段索引。
     *
     * <p>旧版是「由近到远按球壳逐格 {@code getBlockState}」——半径 16 时要扫
     * {@code 33³ ≈ 3.6 万}次方块读取，而本方法是**每 tick** 被
     * {@link EscalatorStepTicker#tickAndUpload} 调用的（20 次/秒 ⇒ 最高 72 万次/秒）。
     * 玩家此刻不在任何扶梯附近（例如站在站厅里）时正好走不到「提前返回」，
     * 于是每次都把整个 33³ 立方体扫满 —— 这是纯烧 CPU 的一条热路径。
     *
     * <p>现在只查「半径内那几个分段」里已登记过的阶梯方块（分段里只装阶梯，本来就非空），
     * 代价与半径的立方无关。找不到（或索引还没建好）就返回 null，与旧行为一致。
     */
    private static BlockPos nearestEscalator(Level level, BlockPos center) {
        return EscalatorStepIndex.nearestStep(
                new Vec3(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5),
                NEARBY_RADIUS);
    }
}
