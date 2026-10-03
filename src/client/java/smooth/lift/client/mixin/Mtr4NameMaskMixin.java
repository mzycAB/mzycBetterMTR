package smooth.lift.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.PlatformNameMask;

/**
 * 【10-01 二次订正】MTR4「名字掩码」的总闸：把 {@code %} 包裹的那一段从**所有**读名字的地方抹掉。
 *
 * <h2>为什么挂 {@code NameColorDataBase.getName()}</h2>
 * 逐条 javap（MTR4 4.0.5）确认：MTR4 的名字 getter 只有**这一个**声明处 ——
 * {@code org.mtr.core.data.NameColorDataBase} 里的 {@code public final String getName()}；
 * 而 MTR4 每一种带名字的数据都从它派生：
 * <pre>
 *   NameColorDataBase
 *     ├─ AreaBaseSchema      → AreaBase      → Station / Depot
 *     ├─ SavedRailBaseSchema → SavedRailBase → Platform / Siding
 *     └─ RouteSchema         → Route
 * </pre>
 * 所以这一处 {@code @Inject} 一次盖住站台名 / 车站名 / 线路名 / 车厂名 / 侧线名。
 *
 * <h2>★ 为什么不再只拦 PIDS 那条路（上一轮的错误）</h2>
 * 上一版只掩 {@code ArrivalResponse.getPlatformName()}（= {@code Platform.getName()} 的副本），
 * 结果用户看到屏蔽门上的**线路图圆圈**照旧显示 {@code %%}：圆圈画的是**车站名**，
 * 走的是 {@code RouteMapGenerator.getStationName(long)} → {@code Station.getName()}，
 * 与站台名是两条互不相干的路径。钉在「名字的源头」就没有这个漏网问题。
 *
 * <h2>安全边界（为什么不会把用户的 {@code %} 弄丢）</h2>
 * MTR4 的同步**不走** {@code getName()}：{@code NameColorDataBaseSchema.serializeName}
 * 写的是 {@code getfield name}（原字段），收端 {@code updateData} 也是 {@code putfield name}
 * （两条都已 javap 核过）⇒ 客户端手里那份数据**永远是原样的**。掩码只发生在
 * 「读出来给别人看」的那一刻；设置界面靠 {@link PlatformNameMask#enterRawWindow()}
 * 开一个 500ms 的原样窗口，把标记原样显示、原样存回。
 *
 * <p>handler 里只出现 {@code String} 与 {@code PlatformNameMask}，**不出现任何 MTR 类型**
 * ⇒ 编译期不需要 MTR 依赖；{@code @Pseudo} + {@code PsdDoorMixinPlugin} 门禁保证
 * 「没装 MTR4 / MTR4 换了包名」时安静跳过、绝不崩游戏。
 */
@Pseudo
@Mixin(targets = "org.mtr.core.data.NameColorDataBase")
public abstract class Mtr4NameMaskMixin {

    @Inject(method = "getName()Ljava/lang/String;", at = @At("RETURN"),
            cancellable = true, remap = false)
    private void smoothlift$maskName(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(PlatformNameMask.mask(cir.getReturnValue()));
    }
}
