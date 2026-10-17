package smooth.lift.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;
import smooth.lift.PictureBlocks;
import smooth.lift.SmoothLift;
import smooth.lift.net.SLNet;

/**
 * Forge 1.18.2 的模组入口。
 *
 * <p>1.20.4 那边是 Fabric 的 {@code ModInitializer}/{@code ClientModInitializer}，
 * 这里把两者接到 Forge 的生命周期上：
 * <ul>
 *   <li>构造期：初始化网络层、注册方块/物品/物品组、挂上通用事件桥；</li>
 *   <li>客户端：额外跑 {@code SmoothLiftClient.onInitializeClient()} 并挂客户端事件桥。</li>
 * </ul>
 */
@Mod(SmoothLiftForge.MOD_ID)
public class SmoothLiftForge {

    public static final String MOD_ID = "smooth_lift";

    public SmoothLiftForge() {
        SLNet.init();

        MinecraftForge.EVENT_BUS.register(new ForgeEventBridge());

        // 方块/物品走 Forge 的 DeferredRegister，必须挂到 mod 事件总线，
        // 让构造与注册发生在 RegistryEvent 窗口内（此时注册表才被解冻）。
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        PictureBlocks.init(modEventBus);

        new SmoothLift().onInitialize();

        if (FMLEnvironment.dist.isClient()) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::init);
        }
    }
}