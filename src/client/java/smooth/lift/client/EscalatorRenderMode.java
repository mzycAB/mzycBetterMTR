package smooth.lift.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 【1.24】扶梯阶梯渲染引擎的「模式开关」：MTR 原版渲染 vs SmoothLift 优化渲染引擎。
 *
 * <p>语义与指令 {@code /mtrxr} 一一对应（用户原话：「mtrxr on 就用 mtr 原版渲染，
 * mtrxr off 就用优化的 mod 渲染引擎」）：
 * <ul>
 *   <li>{@code /mtrxr on}  → 本类 {@code optimized = false} —— 完全交给 MTR 原版渲染；</li>
 *   <li>{@code /mtrxr off} → 本类 {@code optimized = true}  —— SmoothLift 优化引擎接管。</li>
 * </ul>
 *
 * <p>选择会持久化到 {@code config/smoothlift-render.properties}，下次启动保持。
 *
 * <p>【1.28】同一个配置文件里还多了一项 {@code escalatorOcclusionCulling}（遮挡剔除，默认
 * {@code on}，对应 {@code /mtrxr occ on|off}）。它和上面的「引擎模式」是<b>两个独立的轴</b>：
 * 引擎模式决定「扶梯阶梯由谁画」，遮挡剔除决定「看不见的段画不画」。所以这个开关不需要重烘模型、
 * 也不需要重载资源包 —— 见 {@link #applyOcclusion(boolean)}。
 *
 * <p>切换模式时要做三件事（顺序有讲究，见 {@link #apply(boolean)}）：
 * <ol>
 *   <li>先改内存状态 —— 之后 {@link EscalatorModelOverride} 在模型烘焙时的注入判断、
 *       {@link EscalatorStepRenderer} 的渲染入口门控全部读这里；</li>
 *   <li>清掉所有已烘焙缓存 —— 旧模式的 {@code StepModel}（归一化 uv）和方块索引不能跨模式复用；</li>
 *   <li>最后 {@code reloadResourcePacks()} —— 重建 modelManager 重烘模型。
 *       （{@code EscalatorModelOverride} 按新模式注入/不注入 MARKER 贴图，
 *       烘焙结果因此真正变成对应模式的渲染源。）</li>
 * </ol>
 */
public final class EscalatorRenderMode {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** config 文件名（放在 FabricLoader 的 config 目录下）。 */
    private static final String CONFIG_FILE = "smoothlift-render.properties";
    /** properties 键名。值：{@code optimized} = 优化引擎（/mtrxr off）；{@code mtr} = 原版（/mtrxr on）。 */
    private static final String KEY_ENGINE = "escalatorRenderEngine";
    private static final String VALUE_OPTIMIZED = "optimized";
    private static final String VALUE_MTR = "mtr";
    /**
     * 【1.28】遮挡剔除（被墙 / 地形挡住的段不画）的键名。值：{@code on} / {@code off}。
     * 默认 {@code on}。
     */
    private static final String KEY_OCCLUSION = "escalatorOcclusionCulling";
    private static final String VALUE_ON = "on";
    private static final String VALUE_OFF = "off";

    /** 当前是否使用 SmoothLift 优化渲染引擎（false = MTR 原版渲染）。默认优化引擎。 */
    private static boolean optimized = true;

    /**
     * 【1.28】是否启用遮挡剔除（把「原版这帧不画」的段整段跳过）。默认开。
     *
     * <p>为什么默认开、又为什么必须留一个关：这条判据是「看不到的扶梯就整段不渲染」里
     * 唯一一条依赖**外部状态**（原版遮挡图）的，而原版没有公开的查询接口，我们是借
     * {@code LevelRenderer.visibleSections} 来判断的。万一在某个整合包里那个列表的语义
     * 跟预期不一致，表现会是**整片扶梯凭空消失**（因为 MTR 原版那份静止阶梯面已经被
     * 透明标记贴图隐藏了，我们不画就是真的什么都没有）。
     * 所以：默认开（用户要的就是这个效果），但留一个 {@code /mtrxr occ off} 能立刻自救。
     */
    private static boolean occlusionCulling = true;

    private EscalatorRenderMode() {
    }

    /** 客户端初始化时调用一次：从 config 读回上次的选择。 */
    public static void load() {
        Path file = configFile();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift] 读取渲染模式配置失败，使用默认（优化引擎）: {}", file, e);
            return;
        }
        String value = props.getProperty(KEY_ENGINE);
        optimized = !VALUE_MTR.equals(value);
        // 只有明确写成 off 才关；键不存在（老配置文件）/ 写坏了都按默认「开」处理。
        occlusionCulling = !VALUE_OFF.equals(props.getProperty(KEY_OCCLUSION));
        LOGGER.info("[SmoothLift] 扶梯阶梯渲染引擎：{}（{}）；遮挡剔除：{}",
                optimized ? "SmoothLift 优化引擎" : "MTR 原版",
                optimized ? "/mtrxr off" : "/mtrxr on",
                occlusionCulling ? "开（/mtrxr occ off 关闭）" : "关");
    }

    /** 是否使用 SmoothLift 优化渲染引擎（false = MTR 原版渲染）。 */
    public static boolean isOptimized() {
        return optimized;
    }

    /** 【1.28】是否启用遮挡剔除。 */
    public static boolean isOcclusionCulling() {
        return occlusionCulling;
    }

    /**
     * 【1.28】开关遮挡剔除。
     *
     * <p>和 {@link #apply(boolean)} 不同，这里**不需要**清烘焙缓存、也不需要重载资源包：
     * 它只影响渲染端每帧的一次集合查询，不改变任何模型烘焙结果，也不动索引内容。
     * 所以可以在游戏里随手开关、立刻生效。
     *
     * @return 开关是否真的变了（没变返回 false，调用方可以跳过「已切换」的提示）
     */
    public static boolean applyOcclusion(boolean on) {
        if (occlusionCulling == on) {
            return false;
        }
        occlusionCulling = on;
        save();
        LOGGER.info("[SmoothLift] 扶梯阶梯遮挡剔除已{}", on ? "开启" : "关闭");
        return true;
    }

    /** 是否处于「MTR 原版渲染」模式（即 /mtrxr on）。 */
    public static boolean useMtr() {
        return !optimized;
    }

    /**
     * 把模式切到 {@code optimize}（true = 优化引擎 /mtrxr off；false = MTR 原版 /mtrxr on）。
     *
     * @return 模式是否真的变了（没变返回 false，调用方可以跳过重载提示）
     */
    public static boolean apply(boolean optimize) {
        if (optimized == optimize) {
            return false;
        }
        optimized = optimize;
        save();
        // 【1.24】旧模式的烘焙缓存（归一化 uv 的 StepModel）一律作废。
        EscalatorStepModels.clear();
        EscalatorAnimationDriver.clear();
        // 【1.26】★ 这里**只能**作废索引内容，绝不能 reset()。
        // 索引里装的是「哪些坐标是阶梯方块」——它与渲染模式无关，本来就不用清；
        // 而 reset() 会把「已加载区块登记表」一起清掉，可 CHUNK_LOAD 只对**新加载**的区块
        // 触发、已经在场的区块永远不会再触发一次 ⇒ 索引永久为空、渲染端「没有阶梯可画」，
        // 再加上 MTR 原版那份静止阶梯面已被透明标记贴图隐藏 ⇒ **阶梯整片消失**，
        // 只有退出重进存档（所有区块重新加载）才恢复。
        // 详见 EscalatorStepIndex 的类注释。
        EscalatorStepIndex.invalidate();
        // 重建 modelManager 重烘模型：模型注入逻辑（EscalatorModelOverride）会按新模式决定
        // 是否给 MTR 阶梯模型注入透明标记贴图，烘焙结果因此与新模式同步。
        Minecraft.getInstance().reloadResourcePacks();
        LOGGER.info("[SmoothLift] 扶梯阶梯渲染引擎已切换为：{}", optimized ? "SmoothLift 优化引擎" : "MTR 原版");
        return true;
    }

    /** 模式对应的中文字面（用于指令反馈）。 */
    public static String name() {
        return optimized ? "SmoothLift 优化引擎" : "MTR 原版渲染";
    }

    private static void save() {
        Path file = configFile();
        if (file == null) {
            return;
        }
        // ★ 这里是「整份覆盖写」：新建 Properties 再 store，所以**每一个配置项都必须写一遍**。
        // 少写一个键就等于把那一项悄悄重置成默认值 —— 而且下次启动才暴露。
        // 新增配置项时记得同步加一行，并在 load() 里给出「键不存在时的默认值」。
        Properties props = new Properties();
        props.setProperty(KEY_ENGINE, optimized ? VALUE_OPTIMIZED : VALUE_MTR);
        props.setProperty(KEY_OCCLUSION, occlusionCulling ? VALUE_ON : VALUE_OFF);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "mzycBetterMTR escalator step render engine (1.24 / occlusion 1.28)");
            }
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift] 保存渲染模式配置失败: {}", file, e);
        }
    }

    private static Path configFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        } catch (Throwable t) {
            return null;
        }
    }
}