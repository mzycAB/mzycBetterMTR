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
 * 【1.27c】Sodium 光照修复 —— caffeinemc 包名变体（0.5.4+ 改版 / 0.5.8 新构建）。
 *
 * <p>Sodium 在 0.5.4 前后把包名从 {@code me.jellysquid.mods.sodium} 改成
 * {@code net.caffeinemc.mods.sodium}，类结构不变。这个文件与 {@link LightDataAccessJellyMixin}
 * <b>逐字相同，只有 {@code @Mixin(targets=...)} 不同</b>：目标
 * {@code net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess.compute(int,int,int)}。
 *
 * <p>启用与否由 {@link SodiumLightMixinPlugin} 按「目标类在不在」决定；由于本机只能拿到
 * jellysquid 包名的参考 jar 做字节码对撞，caffeinemc 变体按「类结构等价」假设写，
 * 配置里 {@code defaultRequire: 0} —— 万一将来类结构变了，也是<b>安静跳过</b>而不是崩游戏。
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess")
public abstract class LightDataAccessCafMixin {

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