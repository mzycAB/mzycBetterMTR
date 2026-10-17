package smooth.lift.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorAudioPlayer;

/**
 * 【1.12】放开扶梯声音的音量上限（让界面里的 &gt;100 真正能放大）。
 *
 * <p>原版 {@code SoundEngine.calculateVolume(SoundInstance)} 等价于
 * {@code Mth.clamp(instance.getVolume() * 声音来源滑块, 0.0F, 1.0F)} —— 增益被硬夹在 [0,1]，
 * 所以不管实例的音量填多大，实际最多只有 1.0×，表现就是「音量调到 100 以上完全没变化」。
 *
 * <p>这里**只对 SmoothLift 自己的扶梯声音**动手：复刻原版算法（实例音量 × 对应声音来源的滑块音量），
 * 但把上限从 1.0 抬到 {@link EscalatorAudioPlayer#MAX_GAIN}（= 10×）。
 * 于是界面里 100 = 原始音量（1.0×）、1000 = 10× 放大；其它声音一律交回原版，行为零变化。
 *
 * <p>注入点选 {@code calculateVolume(SoundInstance)} 而不是 {@code Mth.clamp} 那一处，
 * 是因为前者拿得到 {@link SoundInstance}（能判断是不是我方声音），且 play() 与每 tick 的
 * 音量更新都会经过它，一处生效、两边覆盖。
 *
 * <p>Forge 版说明：本类只注册在 {@code smooth_escalator.mixins.json} 的 {@code "client"} 数组里，
 * 因此服务端（专用服）不会加载它 —— 它引用的 {@code Minecraft} 等类都只在客户端存在。
 * 方法名用 official（mojmap）名书写，由 mixin AP 依据 {@code -AdefaultObfuscationEnv=searge}
 * 生成 srg 重映射条目写进 {@code smooth_escalator.refmap.json}。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineVolumeMixin {

    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at = @At("RETURN"), cancellable = true)
    private void smoothlift$raiseEscalatorVolumeCap(SoundInstance instance, CallbackInfoReturnable<Float> cir) {
        float raw = EscalatorAudioPlayer.rawEscalatorGain(instance);
        if (raw < 0.0f) {
            return; // 不是本模组的扶梯声音：保持原版行为
        }
        // 复刻原版 SoundEngine.getVolume(SoundSource)：MASTER / null → 1.0，其余取滑块值。
        SoundSource source = instance.getSource();
        float sourceVolume = (source == null || source == SoundSource.MASTER)
                ? 1.0f
                : Minecraft.getInstance().options.getSoundSourceVolume(source);
        cir.setReturnValue(Mth.clamp(raw * sourceVolume, 0.0F, EscalatorAudioPlayer.MAX_GAIN));
    }
}
