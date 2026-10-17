package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.PlatformNameMask;

/**
 * 【10-01】把 MTR4 的「站台名」在**屏蔽门 PIDS 显示屏**上做掩码。
 *
 * <h2>挂哪</h2>
 * 直接挂在核心 API {@code org.mtr.core.operation.ArrivalResponse.getPlatformName()}
 * 的返回处。理由：
 * <ul>
 *   <li>MTR4 里**唯一**画出站台名的就是 PIDS 渲染器（{@code RenderPIDS}），
 *       它从 {@code ArrivalResponse.getPlatformName()} 取站台名；</li>
 *   <li>MTR 的地图 / 路线走的是车站名（{@code RouteMapGenerator.getStationName}），
 *       与站台名是两条路 &rarr; 不受影响；</li>
 *   <li>核心 API 极稳、没有 ordinal；handler 里**不出现任何 MTR 类型**
 *       （只碰 {@code String} 与 {@code PlatformNameMask}），编译期无需 MTR 依赖。</li>
 * </ul>
 *
 * <h2>副作用 / 回退</h2>
 * 这样 MTR4 客户端里**所有**读站台名的地方都会拿到掩码后的值（含本模组讲述人报站词）。
 * 若 MTR4 没装（{@code PsdDoorMixinPlugin} 判定 {@code ArrivalResponse} 不在），
 * 本 mixin 安静跳过、不崩游戏。{@code getPlatformName} 是公开稳定 API；本类
 * {@code @Pseudo} + 插件门禁保证不会因「目标类不存在」而崩。
 */
@Pseudo
@Mixin(targets = "org.mtr.core.operation.ArrivalResponse")
public abstract class Mtr4PidsNameMixin {

    @Inject(method = "getPlatformName()Ljava/lang/String;", at = @At("RETURN"),
            cancellable = true, remap = false)
    private void smoothlift$maskPlatformName(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(PlatformNameMask.mask(cir.getReturnValue()));
    }
}
