package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.PlatformNameMask;

/**
 * 【10-01 二次订正】MTR4「车站 / 车厂 / 线路设置」界面：让名字里的 {@code %} 照原样显示。
 *
 * <h2>为什么必须放行（否则会丢数据）</h2>
 * 名字源头已被 {@link Mtr4NameMaskMixin} 掩掉 ⇒ 不开原样窗口的话，用户一进编辑界面
 * 看到的就是 {@code 5AB}，关掉界面时输入框里的短名会被**无条件**写回数据，
 * {@code %} 标记就被**永久覆盖**掉了。
 *
 * <h2>【1.30.1211 移植】4.1.0 的目标变了</h2>
 * MTR 4.1.0（1.21.1）把整套设置界面重写成了 Elementa：
 * {@code EditNameColorScreenBase} 没了，{@code setPositionsAndInit} / {@code tick2} /
 * {@code renderTextFields} / {@code saveData} 这些挂点也都不存在了。
 * 新结构里 {@code StationScreen} / {@code DepotScreen} / {@code PlatformScreen} /
 * {@code DepotScreen} 等全部继承 {@code org.mtr.screen.NameColorDataScreenBase}
 * （javap 实测），所以本 mixin 改挂这条基类：
 * <ul>
 *   <li>构造完成（{@code <init>} RETURN）⇒ 开**常驻**原样窗口（sticky，见
 *       {@link PlatformNameMask#enterRawWindowSticky()}）—— 界面开着期间名字一律原样；</li>
 *   <li>{@code onScreenClose} ⇒ 关掉常驻窗口。开/关都是幂等布尔，
 *       和 {@link Mtr4SavedRailRawMixin} 双保险不冲突。</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "org.mtr.screen.NameColorDataScreenBase")
public abstract class Mtr4EditNameRawMixin {

    /** 界面打开：进入常驻原样窗口（覆盖输入框回填 + 整个编辑期）。 */
    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void smoothlift$rawOnInit(CallbackInfo ci) {
        PlatformNameMask.enterRawWindowSticky();
    }

    /** 界面关闭：退出常驻原样窗口（列表等场景恢复短名显示）。 */
    @Inject(method = "onScreenClose()V", at = @At("HEAD"), require = 1, remap = false)
    private void smoothlift$rawOnClose(CallbackInfo ci) {
        PlatformNameMask.exitRawWindowSticky();
    }
}
