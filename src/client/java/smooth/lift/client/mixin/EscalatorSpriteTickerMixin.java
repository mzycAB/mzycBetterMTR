package smooth.lift.client.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteTicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorRenderMode;
import smooth.lift.client.EscalatorStepTicker;

/**
 * 拦截所有动画精灵的 createTicker()：当精灵是 MTR 扶梯阶梯的滚动贴图时，
 * 用 EscalatorStepTicker 替换默认 ticker，让贴图帧按扶梯速度同步推进。
 *
 * <p>【1.24】只在 SmoothLift 优化渲染引擎模式（/mtrxr off）下生效：
 * MTR 原版渲染模式（/mtrxr on）下直接放行，不干预原版动画。
 */
@Mixin(SpriteContents.class)
public abstract class EscalatorSpriteTickerMixin {

    @Inject(method = "createTicker", at = @At("RETURN"), cancellable = true)
    private void smoothlift_syncEscalatorAnimation(CallbackInfoReturnable<SpriteTicker> cir) {
        if (!EscalatorRenderMode.isOptimized()) {
            return;
        }
        SpriteTicker original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        String name = ((SpriteContents) (Object) this).name().toString();
        if (!name.endsWith("escalator_up") && !name.endsWith("escalator_down")
                && !name.contains("escalator_up") && !name.contains("escalator_down")) {
            return;
        }
        cir.setReturnValue(new EscalatorStepTicker(original));
    }
}