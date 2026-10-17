package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.PlatformNameMask;

/**
 * 【10-03 五改 修订】屏蔽门 / 线路牌的**线路图圆圈**站名也走掩码（`%` 后面的别名不显示）。
 *
 * <h2>为什么还有这一条（用户实测报的）</h2>
 * 名字源头 {@code NameColorDataBase.getName()} 已经被掩了，但线路图圆圈的文字**不经过它**：
 * 圆圈画的是 {@code SimplifiedRoutePlatform.getStationName()} —— 一个**字段**（getter 就是
 * {@code getfield stationName}），它随 {@code SimplifiedRoute} 的序列化一起到客户端，
 * 掩码 mixin 拦的是方法、拦不住字段。于是出现用户看到的
 * 「香港|HongKong%市区|CityCenter」整段印在屏蔽门玻璃 / 线路牌的线路图上，
 * 而 MTR 控制板里的线路图（走 {@code getName()}）是对的。
 *
 * <p>→ 把 {@code SimplifiedRoutePlatform.getStationName()} 这一个 getter 也掩掉。
 * 与 {@code Mtr4PidsNameMixin}（掩 {@code ArrivalResponse.getPlatformName()}）同一族：
 * 一个 {@code @Inject} 盖住「读这个名字的所有地方」。
 *
 * <h2>射程</h2>
 * <ul>
 *   <li>屏蔽门玻璃 / 线路牌的线路图圆圈（{@code RouteMapGenerator.generateRouteMap} 用
 *       {@code String.format("%s||%s", stationName, stationId)} 拼标签）—— 修复对象；</li>
 *   <li>「往X / toX」方向箭头**不受影响**：它读 {@code SimplifiedRoutePlatform.getDestination()}，
 *       与本类无关（而且那条已经由 {@code Mtr4RouteArrowAliasMixin} 换成别名）；</li>
 *   <li>PIDS 如果也读它 ⇒ 一并变本名 —— 与「除了屏蔽门终点站，其它地方都用 % 前本名」一致。</li>
 * </ul>
 *
 * <h2>★ 处理器必须是非 static（Mixin 硬判据）</h2>
 * {@code getStationName()} 是**实例方法** ⇒ 这里也是实例方法。
 *
 * <p>handler 里只出现 {@code String} 与 {@link PlatformNameMask}，**不出现任何 MTR 类型**
 * ⇒ 编译期不需要 MTR 依赖；{@code @Pseudo} + {@code PsdDoorMixinPlugin} 门禁保证
 * 「没装 MTR4 / MTR4 换了包名」时安静跳过。
 */
@Pseudo
@Mixin(targets = "org.mtr.core.data.SimplifiedRoutePlatform")
public abstract class Mtr4RouteMapNameMixin {

    @Inject(method = "getStationName()Ljava/lang/String;", at = @At("RETURN"),
            cancellable = true, require = 1, remap = false)
    private void smoothlift$maskRouteMapStationName(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(PlatformNameMask.mask(cir.getReturnValue()));
    }
}
