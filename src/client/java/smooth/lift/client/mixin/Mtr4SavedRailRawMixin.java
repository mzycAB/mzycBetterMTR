package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.PlatformNameMask;

/**
 * 【10-01 二次订正】MTR4「站台 / 侧线设置」界面：让名字里的 {@code %} 照原样显示。
 *
 * <h2>为什么必须放行</h2>
 * 名字源头已被 {@link Mtr4NameMaskMixin} 掩掉 ⇒ 不开原样窗口的话，用户看到的是
 * {@code 5AB}，编辑完一保存就把 {@code %} 标记**永久弄丢**。
 *
 * <h2>【1.30.1211 移植】4.1.0 的目标变了</h2>
 * MTR 4.1.0（1.21.1）把 {@code SavedRailScreenBase} 连同整条界面链重写成了 Elementa；
 * {@code PlatformScreen}（站台）等编辑界面现在都继承
 * {@code org.mtr.screen.NameColorDataScreenBase}（javap 实测）。
 * 于是本 mixin 与 {@link Mtr4EditNameRawMixin} 一起挂同一条基类：
 * 构造完成 ⇒ 开常驻原样窗口；{@code onScreenClose} ⇒ 关闭。
 * 开 / 关都是幂等布尔，两只 mixin 同时命中也只是各跑一遍，不会打架。
 */
@Pseudo
@Mixin(targets = "org.mtr.screen.NameColorDataScreenBase")
public abstract class Mtr4SavedRailRawMixin {

    /** 界面打开：进入常驻原样窗口（这一步不成就真的会丢 {@code %}，require = 1）。 */
    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void smoothlift$rawOnInit(CallbackInfo ci) {
        PlatformNameMask.enterRawWindowSticky();
    }

    /** 界面关闭：退出常驻原样窗口。 */
    @Inject(method = "onScreenClose()V", at = @At("HEAD"), require = 1, remap = false)
    private void smoothlift$rawOnClose(CallbackInfo ci) {
        PlatformNameMask.exitRawWindowSticky();
    }
}
