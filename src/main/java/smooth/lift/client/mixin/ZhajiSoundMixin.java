package smooth.lift.client.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.ZhajiChimePlayer;

/**
 * 【09-30】把 MTR 的**过闸声**换成玩家配置的素材（见 {@link ZhajiChimePlayer} 的类注释）。
 *
 * <p>注入点选 {@code SoundManager.play(SoundInstance)}（而不是 {@code SoundEngine.play}）：
 * 那是「一声声音进入引擎」的**唯一公开入口**，{@code SoundManager} 与
 * {@code SoundEngine} 两级都叫 {@code play}，走上面那一级能一次覆盖两条路
 * （{@code play} 与 {@code playDelayed} 都从 {@code soundEngine} 转发，
 * 但延迟那一支很少用于这种即时音，仍然一并拦上以确保不漏）。
 *
 * <p>{@link ZhajiChimePlayer#intercept} 返回 true 才取消 —— 返回 false 一律原样交给原版，
 * 所以非闸机声音、以及闸机配成 {@code default} 的那一档，行为零变化。
 */
@Mixin(SoundManager.class)
public abstract class ZhajiSoundMixin {

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At("HEAD"), cancellable = true)
    private void smoothlift$replaceTicketBarrierSound(SoundInstance instance, CallbackInfo ci) {
        if (ZhajiChimePlayer.intercept(instance)) {
            ci.cancel();
        }
    }

    @Inject(method = "playDelayed(Lnet/minecraft/client/resources/sounds/SoundInstance;I)V",
            at = @At("HEAD"), cancellable = true)
    private void smoothlift$replaceTicketBarrierSoundDelayed(SoundInstance instance, int delay, CallbackInfo ci) {
        if (ZhajiChimePlayer.intercept(instance)) {
            ci.cancel();
        }
    }
}
