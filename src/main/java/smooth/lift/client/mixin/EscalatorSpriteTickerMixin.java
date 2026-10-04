package smooth.lift.client.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.Tickable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorRenderMode;
import smooth.lift.client.EscalatorStepTicker;

/**
 * 拦截动画精灵的 getAnimationTicker()：当精灵是 MTR 扶梯阶梯的滚动贴图时，
 * 用 EscalatorStepTicker 替换默认 ticker，让贴图帧按扶梯速度同步推进。
 *
 * <p>1.20.4 拦的是 {@code SpriteContents.createTicker()}（返回 {@code SpriteTicker}）；
 * 1.18.2 没有 SpriteContents/SpriteTicker，动画由 {@code TextureAtlasSprite.getAnimationTicker()}
 * 返回的 {@link Tickable} 驱动 —— {@code TextureAtlas.cycleAnimationFrames()} 在加载期
 * 取一次该 ticker 存进 {@code animatedTextures}，之后每 tick 调它的 {@code tick()}，
 * 所以在这里换掉返回值即可（与 1.20.4 语义一致）。
 *
 * <p>【1.24】只在 SmoothLift 优化渲染引擎模式（/mtrxr off）下生效：
 * MTR 原版渲染模式（/mtrxr on）下直接放行，不干预原版动画。
 */
@Mixin(TextureAtlasSprite.class)
public abstract class EscalatorSpriteTickerMixin {

    @Inject(method = "getAnimationTicker", at = @At("RETURN"), cancellable = true)
    private void smoothlift_syncEscalatorAnimation(CallbackInfoReturnable<Tickable> cir) {
        if (!EscalatorRenderMode.isOptimized()) {
            return;
        }
        Tickable original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        String name = ((TextureAtlasSprite) (Object) this).getName().toString();
        if (!name.endsWith("escalator_up") && !name.endsWith("escalator_down")
                && !name.contains("escalator_up") && !name.contains("escalator_down")) {
            return;
        }
        cir.setReturnValue(new EscalatorStepTicker(original));
    }
}