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
 * 【1.27c】Sodium 光照修复 —— jellysquid 包名变体（0.5.x 老构建）。
 *
 * <p>目标：{@code me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess.compute(int,int,int)}。
 * Sodium 版本升级时把包名换成了 {@code net.caffeinemc.mods.sodium}，类结构不变 ——
 * 同款逻辑的 <b>caffeinemc 变体</b> 见 {@link LightDataAccessCafMixin}（两个文件逐字相同，只有 targets 不同）。
 * 启用与否由 {@link SodiumLightMixinPlugin} 按「目标类在不在」决定，不装 Sodium 时整个配置为空。
 *
 * <p>为什么挂 {@code compute} 而不是 {@code get}：{@code get(int,int,int)} 是抽象方法，
 * 由两个缓存类实现；它们缓存未命中时都会调 {@code compute} 现算 —— 所以这里是全部角点
 * 光 / AO 数据的唯一计算点，挂一次就覆盖两条缓存路。参数与返回值全是 int，
 * 因此 handler 不需要任何 Sodium 类型；上下文（{@code world}、{@code getLightmap}、
 * {@code get}）用 {@code @Shadow} 直接引用目标自身成员。
 *
 * <p>判据与原版那套完全一致：<b>只修「取到的光为纯黑」的格子</b>（{@code getLightmap(data)==0}），
 * 且只在它 6 邻里能找到一格<b>有光的扶梯</b>时才借那格的光；其他一律交回原版。
 * 重入闸 {@link SodiumLightRepair#tryBegin()} 防 compute → get → compute 递归。
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess")
public abstract class LightDataAccessJellyMixin {

    @Shadow
    protected BlockAndTintGetter world;

    @Shadow
    protected BlockPos.MutableBlockPos pos;

    @Shadow
    protected abstract int get(int x, int y, int z);

    @Shadow
    public static int getLightmap(int data) {
        throw new AssertionError("shadow");
    }

    @Inject(method = "compute(III)I", at = @At("RETURN"), cancellable = true)
    private void smoothlift$repairDarkCell(int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
        int data = cir.getReturnValueI();
        // ★ 最快路径：光域 = 低 12 位（BL=b0-3 / SL=b4-7 / LU=b8-11，与 getLightmap==0 等价）。
        // 只要有一路光就不算纯黑 ⇒ 一次 int 与运算直接交回原版，连重入闸都不开。
        // （compute 是 Sodium 网格编译期每个单元格一次的调用点，这里必须是最短路径。）
        if ((data & 0xFFF) != 0) {
            return;
        }
        if (SodiumLightRepair.tryBegin()) {
            return;
        }
        try {
            for (Direction direction : Direction.values()) {
                this.pos.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                BlockState neighbor = this.world.getBlockState(this.pos);
                if (!EscalatorLightRepair.isEscalator(neighbor)) {
                    continue;
                }
                int own = this.get(this.pos.getX(), this.pos.getY(), this.pos.getZ());
                // 扶梯自己那格也是纯黑（真在黑屋子里）⇒ 没光可借。
                if ((own & 0xFFF) != 0) {
                    SodiumLightRepair.logFix(x, y, z, this.pos.immutable());
                    cir.setReturnValue(own);
                    return;
                }
            }
        } finally {
            SodiumLightRepair.end();
        }
    }
}