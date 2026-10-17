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
 * {@code SavedRailScreenBase} 是 {@code PlatformScreen}（站台设置）与 {@code SidingScreen}
 * 的公共基类，它把名字读进输入框：
 * {@code textFieldSavedRailNumber.setText2(savedRailBase.getName())}（javap 偏移 44）。
 * 名字源头已被 {@link Mtr4NameMaskMixin} 掩掉 ⇒ 不在这里开原样窗口的话，
 * 用户看到的是 {@code 5AB}，编辑完一保存就把 {@code %} 标记**永久弄丢**。
 *
 * <h2>开窗口的时机</h2>
 * <ul>
 *   <li>{@code init2}（填输入框）—— {@code require = 1}：**这一步不成就真的会丢数据**，
 *       宁可响亮失败（MTR 换了方法名就直接报错），也不要静默地把用户的标记吃掉；</li>
 *   <li>{@code tick2} / {@code render} —— {@code require = 0}：每刻 / 每帧刷新，把窗口续到
 *       界面关闭为止；这两个只是「让界面里别的地方也显示原样」的加分项，缺了不影响保存。</li>
 * </ul>
 * <p>用的是「截止时刻表」而不是配对开关：界面关了以后最长 500ms 自动失效，
 * 崩溃 / 断线 / 换世界都不会把它漏成常开（见 {@code PlatformNameMask} 的类注释）。
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.screen.SavedRailScreenBase")
public abstract class Mtr4SavedRailRawMixin {

    /** 填输入框那一步：必须成功（否则保存会把 {@code %} 吃掉）。 */
    @Inject(method = "init2()V", at = @At("HEAD"), require = 1, remap = false)
    private void smoothlift$rawOnInit(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }

    /** 每刻刷新（界面开着时把窗口续住）。 */
    @Inject(method = "tick2()V", at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$rawOnTick(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }

    /** 每帧刷新。 */
    @Inject(method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;IIF)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$rawOnRender(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }
}
