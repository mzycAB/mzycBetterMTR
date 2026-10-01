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
 * {@code EditNameColorScreenBase} 是 {@code EditStationScreen} / {@code EditDepotScreen} /
 * {@code EditRouteScreen} 的公共基类。javap 两条：
 * <ul>
 *   <li>{@code setPositionsAndInit} 偏移 65：{@code textFieldName.setText2(data.getName())}
 *       —— 把名字填进输入框；</li>
 *   <li>{@code saveData()} 偏移 8~11：{@code data.setName(textFieldName.getText2())}
 *       —— **无条件**把输入框里的文字写回数据（{@code onClose2} 会调它）。</li>
 * </ul>
 * 名字源头已被 {@link Mtr4NameMaskMixin} 掩掉 ⇒ 不开原样窗口的话，用户一进这个界面
 * 看到的就是 {@code 5AB}，关掉界面时 {@code %} 标记就被**永久覆盖**掉了。
 *
 * <h2>开窗口的时机</h2>
 * <ul>
 *   <li>{@code setPositionsAndInit} —— {@code require = 1}：填输入框那一步不成就真的会丢数据，
 *       宁可响亮失败也不要静默吃掉用户的标记；</li>
 *   <li>{@code tick2} / {@code renderTextFields} / {@code saveData} —— {@code require = 0}：
 *       续窗口 + 保存那一刻也保证原样；缺了不影响「字段里已经是原样」这个事实。</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.screen.EditNameColorScreenBase")
public abstract class Mtr4EditNameRawMixin {

    /** 填输入框那一步：必须成功（否则关界面时会把 {@code %} 覆盖掉）。 */
    @Inject(method = "setPositionsAndInit(III)V", at = @At("HEAD"), require = 1, remap = false)
    private void smoothlift$rawOnInit(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }

    /** 每刻刷新（界面开着时把窗口续住）。 */
    @Inject(method = "tick2()V", at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$rawOnTick(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }

    /** 每帧刷新（{@code renderTextFields} 由子类的 render 每帧调）。 */
    @Inject(method = "renderTextFields(Lorg/mtr/mapping/mapper/GraphicsHolder;)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$rawOnRender(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }

    /** 保存那一刻。 */
    @Inject(method = "saveData()V", at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$rawOnSave(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }
}
