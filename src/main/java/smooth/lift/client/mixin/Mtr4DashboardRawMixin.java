package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.PlatformNameMask;

/**
 * 【10-01 二次订正】MTR4「控制板」里那两个**内联改名**入口：填输入框时必须拿到原样名字。
 *
 * <h2>为什么控制板也得放行（而且**只**放行「填的那一下」）</h2>
 * 控制板 `org.mtr.mod.screen.DashboardScreen` 自己带着改名功能。javap 两条：
 * <ul>
 *   <li>{@code startEditingArea} 偏移 20~23：
 *       {@code textFieldName.setText2(area.getName())}；</li>
 *   <li>{@code startEditingRoute} 偏移 25~28：
 *       {@code textFieldName.setText2(route.getName())}；</li>
 * </ul>
 * 配套的落盘在 {@code onDoneEditingArea} / {@code onDoneEditingRoute}：
 * {@code editingArea.setName(IGui.textOrUntitled(textFieldName.getText2()))} ——
 * **把输入框里的文字无条件写回**。填的时候若拿到的是掩码值，用户点一下「完成」就把 {@code %} 永久吃掉。
 *
 * <h2>★ 为什么挂在「开始编辑」这两个方法上，而不是整屏每帧续窗</h2>
 * 用户口径是「**只在**站台设置里显示 {@code %}，控制板要隐藏」。这里只在这两个方法 HEAD 开**一次**
 * 窗口（500ms 后自动过期）⇒ 控制板的列表照旧显示短名；而输入框里的文字在 fill 那一刻
 * 就已经是原样，之后 {@code getText2()} 读的是**控件自己的文本**（不经过 {@code getName()}）
 * ⇒ 窗口过期也不影响保存的正确性。
 *
 * <p>★ 顺手查过第三个入口 {@code startEditingRouteDestination}：它填的是
 * {@code RoutePlatformData.getCustomDestination()}（用户自己敲进输入框的普通字符串字段，
 * 不是 `NameColorDataBase` 的名字）⇒ 本来就没被掩，不需要放行。
 *
 * <p>handler 里只出现 {@code CallbackInfo} 与 {@code PlatformNameMask}，**不出现任何 MTR 类型**
 * ⇒ 编译期不需要 MTR 依赖；{@code @Pseudo} + 插件门禁保证没装 MTR4 时安静跳过。
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.screen.DashboardScreen")
public abstract class Mtr4DashboardRawMixin {

    /** 控制板内联改「车站 / 车厂」名字：填输入框那一步必须拿到原样（否则点完成就丢标记）。 */
    @Inject(method = "startEditingArea(Lorg/mtr/core/data/AreaBase;Z)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void smoothlift$rawOnEditArea(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }

    /** 控制板内联改「线路」名字：同上。 */
    @Inject(method = "startEditingRoute(Lorg/mtr/core/data/Route;Z)V",
            at = @At("HEAD"), require = 1, remap = false)
    private void smoothlift$rawOnEditRoute(CallbackInfo ci) {
        PlatformNameMask.enterRawWindow();
    }
}
