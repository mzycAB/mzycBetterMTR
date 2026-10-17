package smooth.lift.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorLightRepair;

/**
 * 【1.27】AO 路径第二半：采样格被实心方块埋住时，改用扶梯自己那一格的亮度。
 *
 * <p>见 {@link EscalatorLightRepair} 的根因说明。AO 路径的每一个顶点亮度都由
 * {@code AmbientOcclusionFace.calculate} 内部的 {@code Cache.getLightColor} 给出（javap 数过，
 * 10 个调用点全在里面），所以只要在这一处拦下就能覆盖 AO 路径的全部取光。
 *
 * <p><b>为什么是 {@code @Inject(HEAD)} 而不是 {@code @Redirect}</b>：
 * {@code @Redirect} 的 handler 第一个参数必须是 receiver 类型 {@code ModelBlockRenderer$Cache}，
 * 而那个类是包级私有、命名空间外写不出来；{@code @Inject} 不需要写 receiver 类型。
 *
 * <p>判定用的是 {@code calculate} 入口处记下的「当前那一格扶梯」（{@link EscalatorAoStashMixin}），
 * <b>不是</b>这里收到的 {@code sampleState} —— 后者是采样格自己那一格的方块，
 * 在 AO 里往往就是那块把面埋住的实心方块，认不出扶梯。
 */
@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer$Cache")
public abstract class EscalatorAoSampleMixin {

    /**
     * @param sampleState 采样格自己那一格的 state（本类不用它做判据，只为签名对齐）
     * @param level       渲染所在世界
     * @param samplePos   采样格坐标（原版就在这里取到 0，于是画出黑面）
     */
    @Inject(method = "getLightColor(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/BlockAndTintGetter;"
            + "Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void smoothlift$repairBuriedSample(BlockState sampleState, BlockAndTintGetter level, BlockPos samplePos,
                                               CallbackInfoReturnable<Integer> cir) {
        int repaired = EscalatorLightRepair.repairAo(level, samplePos);
        if (repaired != EscalatorLightRepair.NOT_REPAIRED) {
            cir.setReturnValue(repaired);
        }
    }
}
