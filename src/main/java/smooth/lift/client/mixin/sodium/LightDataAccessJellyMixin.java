package smooth.lift.client.mixin.sodium;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorLightRepair;
import smooth.lift.client.SodiumLightRepair;

/**
 * 【1.27c】Sodium 光照修复 —— jellysquid 包名变体（Embeddium 0.3.x 64 位 long 格式）。
 *
 * <p>目标：{@code me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess.compute(int,int,int)}。
 * <b>格式实测（Embeddium 0.3.18+mc1.18.2 反编译）：64 位 long</b> —— {@code compute(III)J}、
 * {@code get(III)J}（抽象）、光图存于低 32 位、{@code unpackLM(long)} 取回、{@code pos} 是
 * {@code private final}。旧版 32 位 int 格式的 {@code getLightmap(int)} 已不存在，
 * 判据一律改用 {@code unpackLM(long) != 0}。caffeinemc 新包名变体见
 * {@link LightDataAccessCafMixin}（同款 long 语义，只有 targets 不同）。
 * 启用与否由 {@link SodiumLightMixinPlugin} 按「目标类在不在」决定，不装 Sodium 时整个配置为空。
 *
 * <p>为什么挂 {@code compute} 而不是 {@code get}：{@code get(int,int,int)} 是抽象方法，
 * 由两个缓存类（{@code light/cache/ArrayLightDataCache}、{@code HashLightDataCache}）实现；
 * 它们缓存未命中时都会调 {@code compute} 现算 —— 所以这里是全部角点光 / AO 数据的
 * 唯一计算点，挂一次就覆盖两条缓存路。handler 只依赖 {@code world}、{@code unpackLM}、
 * {@code get} 三个成员（均为 long 语义），不碰任何 Sodium 编译期类型。
 *
 * <p>判据与原版那套完全一致：<b>只修「取到的光为纯黑」的格子</b>
 * （{@code unpackLM(data)==0}），且只在它 6 邻里能找到一格<b>有光</b>的扶梯时才借那格的光；
 * 其他一律交回原版。重入闸 {@link SodiumLightRepair#tryBegin()} 防 compute → get → compute 递归。
 * 借光用 {@link ThreadLocal} 复用可变坐标，避免慢路径每次分配（区块网格在 worker 线程上编译）。
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess")
public abstract class LightDataAccessJellyMixin {

    @Shadow
    protected BlockAndTintGetter world;

    @Shadow
    public abstract long get(int x, int y, int z);

    @Shadow
    public static int unpackLM(long data) {
        throw new AssertionError("shadow");
    }

    /** 借光用的可变坐标。{@code pos} 字段在目标类里是 private final，不能 shadow，故自备。 */
    private static final ThreadLocal<BlockPos.MutableBlockPos> MPOS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Inject(method = "compute(III)J", at = @At("RETURN"), cancellable = true)
    private void smoothlift$repairDarkCell(int x, int y, int z, CallbackInfoReturnable<Long> cir) {
        // ★ 最快路径：光图（低 32 位）非 0 就不是纯黑 ⇒ 一次静态调用直接交回原版，连重入闸都不开。
        // （compute 是 Sodium 网格编译期每个单元格一次的调用点，这里必须是最短路径。）
        if (unpackLM(cir.getReturnValueJ()) != 0) {
            return;
        }
        if (SodiumLightRepair.tryBegin()) {
            return;
        }
        try {
            BlockPos.MutableBlockPos pos = MPOS.get();
            for (Direction direction : Direction.values()) {
                pos.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                BlockState neighbor = this.world.getBlockState(pos);
                if (!EscalatorLightRepair.isEscalator(neighbor)) {
                    continue;
                }
                long own = this.get(pos.getX(), pos.getY(), pos.getZ());
                // 扶梯自己那格也是纯黑（真在黑屋子里）⇒ 没光可借。
                if (unpackLM(own) != 0) {
                    SodiumLightRepair.logFix(x, y, z, pos.immutable());
                    cir.setReturnValue(own);
                    return;
                }
            }
        } finally {
            SodiumLightRepair.end();
        }
    }
}
