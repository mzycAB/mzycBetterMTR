package smooth.lift.client.mixin.sodium;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 【1.27c】Sodium 光照修复两条 mixin 的**门禁**。
 *
 * <p>{@code smoothlift.sodium.mixins.json} 里两条 mixin 的目标是 Sodium 两个包名变体的
 * 同一个类：
 * <ul>
 *   <li>{@code LightDataAccessJellyMixin} → {@code me.jellysquid.mods.sodium…LightDataAccess}（老包名）；</li>
 *   <li>{@code LightDataAccessCafMixin}   → {@code net.caffeinemc.mods.sodium…LightDataAccess}（新包名）。</li>
 * </ul>
 * Sodium 0.5.4 的 1.20.4 构建和 0.5.8 用的都是新包名；老包名仅供 0.5.x 早期构建。
 * 玩家完全可能没装 Sodium（这时两条都不该动），判据直接取「各自目标类在不在类路径」，
 * 并且跳过时必须**安静**（配置 {@code required: false} + {@code defaultRequire: 0}）。
 *
 * <h2>★★★ 铁律：只能用类路径资源探测，禁止 {@code Class.forName}</h2>
 * 与 {@code PsdDoorMixinPlugin} 同一条铁律：本插件跑在 Mixin 准备阶段，一旦把 Sodium 的类
 * load 进来，Sodium 自己的 mixin 准备时会抛 {@code MixinTargetAlreadyLoadedException} →
 * 游戏启动即崩。探测一律用 {@code ClassLoader.getResource("….class")}（见 {@link #classPresent}）。
 *
 * <p>（2026-09-27 实录：玩家的整合包有 sodium 0.5.4 + iris 1.6.13(着色器关)。Sodium 全权接管
 * 方块网格光照，原版 {@code ModelBlockRenderer} / {@code AmbientOcclusionFace} 压根不会被调用
 * —— 日志里「AO 入口已挂上」0 次即是证据。此门禁的存在就是为了让修复在两种环境都成立。）
 */
public class SodiumLightMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** null = 还没判定过。 */
    private static Boolean jelly;
    private static Boolean caf;

    @Override
    public void onLoad(String mixinPackage) {
        // 判定放在 onLoad：一定早于任何 shouldApplyMixin 调用，且整局只跑一次。
        jellyPresent();
        cafPresent();
    }

    private static boolean jellyPresent() {
        if (jelly == null) {
            jelly = classPresent("me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess");
            LOGGER.info("取光修正：Sodium 光照修复（jellysquid 包名 {}）：{}",
                    "me/jellysquid/mods/sodium/client/model/light/data/LightDataAccess",
                    jelly ? "目标类在，已启用" : "目标类不在，跳过");
        }
        return jelly;
    }

    private static boolean cafPresent() {
        if (caf == null) {
            caf = classPresent("net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess");
            LOGGER.info("取光修正：Sodium 光照修复（caffeinemc 包名 {}）：{}",
                    "net/caffeinemc/mods/sodium/client/model/light/data/LightDataAccess",
                    caf ? "目标类在，已启用" : "目标类不在，跳过");
        }
        return caf;
    }

    /** 某个类**在不在**类路径上 —— 只看资源，**完全不加载类**（原因见类注释）。 */
    private static boolean classPresent(String name) {
        try {
            String resource = name.replace('.', '/') + ".class";
            ClassLoader loader = SodiumLightMixinPlugin.class.getClassLoader();
            return loader != null && loader.getResource(resource) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("LightDataAccessJellyMixin")) {
            return jellyPresent();
        }
        if (mixinClassName.endsWith("LightDataAccessCafMixin")) {
            return cafPresent();
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