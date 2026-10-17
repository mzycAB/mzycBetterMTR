package smooth.lift.mixin.mtr;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 【1.50】屏蔽门（PSD / APG）门值采集那两条 mixin 的**门禁**。
 *
 * <p>{@code smoothlift.psd.mixins.json} 里两条 mixin 各自的目标类是**两个版本的 MTR 独有**的：
 * <ul>
 *   <li>{@code Mtr4PsdDoorMixin} → {@code org.mtr.mod.block.BlockPSDAPGDoorBase$BlockEntityBase}（MTR4 4.0.x）；</li>
 *   <li>{@code Mtr3PsdDoorMixin} → {@code mtr.block.BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase}（MTR3 3.x）。</li>
 * </ul>
 * 玩家完全可能没装 MTR、或者只装了其中一个版本，所以判据**直接取「那个 mixin 自己的目标类在不在」**
 * （比按大版本号猜准：MTR 哪天把类挪了包，对不上就是安全跳过），并且跳过时必须**安静**，
 * 不能让 Mixin 抛「目标类不存在」把游戏带崩。
 *
 * <h2>★★★ 铁律：这个类里**绝对不能**用 {@code Class.forName} / {@code loadClass} 探测类</h2>
 * 本插件跑在 Mixin 的**准备阶段**，比别的模组的 mixin 配置还早；一旦把类 load + link 进类加载器，
 * 那个模组的 mixin 准备时就会抛 {@code MixinTargetAlreadyLoadedException} → **游戏启动即崩**，
 * 而且崩在别人名下。探测一律用**类路径资源**（读 {@code .class} entry 本身），见 {@link #classPresent}。
 * 同一条铁律的完整事故记录见 {@link Mtr3LiftMixinPlugin} 的类注释。
 */
public class PsdDoorMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** null = 还没判定过。 */
    private static Boolean mtr3;
    private static Boolean mtr4;

    @Override
    public void onLoad(String mixinPackage) {
        // 判定放在 onLoad：它一定早于任何 shouldApplyMixin 调用，且整局只跑一次。
        mtr3Present();
        mtr4Present();
    }

    private static boolean mtr3Present() {
        if (mtr3 == null) {
            mtr3 = classPresent("mtr.block.BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase");
            LOGGER.info("[SmoothLift/PsdChime] 屏蔽门门值采集（MTR3 的 TileEntityPSDAPGDoorBase）：{}",
                    mtr3 ? "目标类在，已启用" : "目标类不在，跳过");
        }
        return mtr3;
    }

    private static boolean mtr4Present() {
        if (mtr4 == null) {
            mtr4 = classPresent("org.mtr.mod.block.BlockPSDAPGDoorBase$BlockEntityBase");
            LOGGER.info("[SmoothLift/PsdChime] 屏蔽门门值采集（MTR4 的 BlockEntityBase）：{}",
                    mtr4 ? "目标类在，已启用" : "目标类不在，跳过");
        }
        return mtr4;
    }

    /**
     * 某个类**在不在**类路径上 —— 只看资源，**完全不加载类**（原因见类注释）。
     * 返回 false 是正常情况（没装那个版本的 MTR），不打印堆栈。
     */
    private static boolean classPresent(String name) {
        try {
            String resource = name.replace('.', '/') + ".class";
            ClassLoader loader = PsdDoorMixinPlugin.class.getClassLoader();
            return loader != null && loader.getResource(resource) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 两条 mixin 的门禁各自独立：谁的目标类在，谁生效。
        if (mixinClassName.endsWith("Mtr4PsdDoorMixin")) {
            return mtr4Present();
        }
        if (mixinClassName.endsWith("Mtr3PsdDoorMixin")) {
            return mtr3Present();
        }
        return false;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 不需要做任何事
    }

    @Override
    public List<String> getMixins() {
        // 不用动态追加的 mixin
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
        // 不需要改目标类
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        // 不需要改目标类
    }
}
