package smooth.lift.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import smooth.lift.SmoothLift;

/**
 * 【1.24 / Forge 移植】客户端初始化：读回上次的扶梯阶梯渲染引擎模式。
 *
 * <p>Fabric 侧这件事在 {@code SmoothLiftClient.onInitializeClient()} 的第一行做
 * （{@code EscalatorRenderMode.load()}）。Forge 的对应时机是 {@link FMLClientSetupEvent}
 * —— 它是**模组事件总线**（不是游戏事件总线），所以必须单独一个
 * {@code bus = Bus.MOD} 的订阅者，不能塞进 {@code SmoothLiftClientEvents}（那个是 FORGE 总线）。
 *
 * <p><b>★ 这不是唯一的读取点，也不可能是</b>：Forge 的首次模型烘焙时机跟客户端初始化事件的
 * 先后关系不保证，而 {@link EscalatorModelOverride} 在烘模型时就要读
 * {@link EscalatorRenderMode#isOptimized()}。所以 {@code isOptimized()} 自己带懒加载兜底
 * （见 {@code EscalatorRenderMode.configLoaded}），这里只是把「启动就写一行日志」这件事
 * 提前到正常路径上，方便排查。
 */
@Mod.EventBusSubscriber(modid = SmoothLift.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SmoothLiftClientSetup {

    private SmoothLiftClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 【1.24】先读回上次的扶梯阶梯渲染引擎模式（/mtrxr on|off），之后所有门控都读它。
        EscalatorRenderMode.load();
        // 【09-30 / 1.29 移植】读回讲述人列车报站的全部设置（/jsr …，config/smoothlift-jsr.properties）。
        //   Fabric 侧在 SmoothLiftClient.onInitializeClient() 里调；Forge 的对应时机就是这里。
        TrainAnnounceSwitch.load();
    }
}
