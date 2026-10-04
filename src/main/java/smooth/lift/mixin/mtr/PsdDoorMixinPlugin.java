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
        // 各 mixin 的门禁各自独立：谁的目标类在，谁生效。
        if (mixinClassName.endsWith("Mtr4PsdDoorMixin")) {
            return mtr4Present();
        }
        if (mixinClassName.endsWith("Mtr3PsdDoorMixin")) {
            return mtr3Present();
        }
        // 【10-01】屏蔽门 PIDS 站台名掩码：MTR4 走 ArrivalResponse、MTR3 走 RenderPIDS，
        // 目标类同样是两版本 MTR 独有 ⇒ 复用 mtr4/mtr3 探测（classPresent，绝不 load 类）。
        if (mixinClassName.endsWith("Mtr4PidsNameMixin")) {
            LOGGER.info("[SmoothLift/PidsName] MTR4 站台名掩码（ArrivalResponse.getPlatformName）：{}",
                    mtr4Present() ? "目标类在，已放行" : "目标类不在，跳过");
            return mtr4Present();
        }
        if (mixinClassName.endsWith("Mtr3PidsNameMixin")) {
            LOGGER.info("[SmoothLift/PidsName] MTR3 站台名掩码（RenderPIDS.lambda$getSchedules$0）：{}",
                    mtr3Present() ? "目标类在，已放行" : "目标类不在，跳过");
            return mtr3Present();
        }
        // 【10-01 二次订正】名字掩码改成「在源头掩 + 设置界面开原样窗口」：
        //   判据各自取「那个 mixin 自己的目标类在不在」——仍然只读资源，绝不 load 类
        //   （铁律见本类注释：一旦 load 进类加载器，别的模组的 mixin 准备阶段就会崩）。
        if (mixinClassName.endsWith("Mtr4NameMaskMixin")) {
            return maskTargetPresent("org.mtr.core.data.NameColorDataBase",
                    "MTR4 名字源头掩码（NameColorDataBase.getName，一处盖住站台/车站/线路/车厂/侧线）");
        }
        if (mixinClassName.endsWith("Mtr4SavedRailRawMixin")) {
            return maskTargetPresent("org.mtr.mod.screen.SavedRailScreenBase",
                    "MTR4 站台/侧线设置界面放行原样");
        }
        if (mixinClassName.endsWith("Mtr4EditNameRawMixin")) {
            return maskTargetPresent("org.mtr.mod.screen.EditNameColorScreenBase",
                    "MTR4 车站/车厂/线路设置界面放行原样");
        }
        // 控制板自己也带内联改名（startEditingArea/startEditingRoute 填输入框、
        // onDoneEditing* 无条件写回）⇒ 只在这两个「开始编辑」入口放行一次，
        // 控制板的列表照旧显示短名（窗口 500ms 后自动过期）。
        if (mixinClassName.endsWith("Mtr4DashboardRawMixin")) {
            return maskTargetPresent("org.mtr.mod.screen.DashboardScreen",
                    "MTR4 控制板内联改名放行原样（只在开始编辑那一下）");
        }
        // 【10-03 五改】屏蔽门玻璃上「往X / to X」用**别名**（% 后面那一段）：
        //   目标类 = RouteMapGenerator（MTR4 独有），判据同样是「目标类在不在」，绝不 load 类。
        if (mixinClassName.endsWith("Mtr4RouteArrowAliasMixin")) {
            return maskTargetPresent("org.mtr.mod.client.RouteMapGenerator",
                    "MTR4 屏蔽门玻璃方向箭头用别名（RouteMapGenerator.generateDirectionArrow）");
        }
        // 【10-03 五改 修订】屏蔽门/线路牌的线路图圆圈站名（SimplifiedRoutePlatform.getStationName 是
        //   序列化字段、不经 getName()，得单独掩一层）。
        if (mixinClassName.endsWith("Mtr4RouteMapNameMixin")) {
            return maskTargetPresent("org.mtr.core.data.SimplifiedRoutePlatform",
                    "MTR4 线路图圆圈站名掩码（SimplifiedRoutePlatform.getStationName）");
        }
        // 【1.31.1204】「玩家视角随列车倾斜」（/mtrqx on|off）：两个目标类都是 **MTR4 客户端独有**
        //   （PositionAndRotation 在 org.mtr.mod.render、VehicleRidingMovement 在 org.mtr.mod.client），
        //   判据仍然各自取「那个 mixin 自己的目标类在不在」，只读资源、绝不 load 类（铁律见本类注释）。
        if (mixinClassName.endsWith("Mtr4RideTiltPositionMixin")) {
            return tiltTargetPresent("org.mtr.mod.render.PositionAndRotation",
                    "MTR4 列车俯仰角读取（PositionAndRotation.transformForwards/Backwards）");
        }
        if (mixinClassName.endsWith("Mtr4RideTiltMovementMixin")) {
            return tiltTargetPresent("org.mtr.mod.client.VehicleRidingMovement",
                    "MTR4 骑乘帧识别（VehicleRidingMovement.movePlayer/sendUpdate）");
        }
        // 【1.31.1204 二改】「玩家模型随列车倾斜」那一条：目标类是**原版**的
        //   LivingEntityRenderer#render（永远在），所以门禁借 MTR4 的骑乘链路来判 ——
        //   没装 MTR4 时这条功能本就没有意义（rideActive 永远是 false），跳过最省。
        if (mixinClassName.endsWith("RideTiltPlayerRenderMixin")) {
            return tiltTargetPresent("org.mtr.mod.client.VehicleRidingMovement",
                    "MTR4 玩家模型随列车倾斜（门禁借骑乘链路；目标类是原版 LivingEntityRenderer#render）");
        }
        // 【1.31.1204 二改】「相机随列车横滚（视角上方向 = 列车地板法线）」：目标类同样是**原版**的
        //   GameRenderer#renderLevel（永远在），门禁一样借 MTR4 骑乘链路来判 —— 没装 MTR4 时
        //   这条功能本就没有意义（rideActive 永远是 false），跳过最省。
        if (mixinClassName.endsWith("RideTiltCameraRollMixin")) {
            return tiltTargetPresent("org.mtr.mod.client.VehicleRidingMovement",
                    "MTR4 相机随列车横滚（门禁借骑乘链路；目标类是原版 GameRenderer#renderLevel）");
        }
        return false;
    }

    /**
     * 【1.31.1204】「玩家视角随列车倾斜」那两条 mixin 的门禁：只看**那个 mixin 自己的目标类**在不在。
     *
     * <p>与 {@link #maskTargetPresent} 是同一条路子（只读类路径资源、绝不 load 类），
     * 只是日志标签换成 {@code [SmoothLift/TiltView]} —— 那条功能与 PIDS 站台名掩码不是同一族，
     * 混在一个标签里排查时会看串。
     */
    private static boolean tiltTargetPresent(String className, String what) {
        boolean present = classPresent(className);
        LOGGER.info("[SmoothLift/TiltView] {}（{}）：{}",
                what, className, present ? "目标类在，已放行" : "目标类不在，跳过");
        return present;
    }

    /**
     * 掩码那一族 mixin 的通用门禁：只看**那个 mixin 自己的目标类**在不在，并留一行日志。
     *
     * <p>为什么不用 {@code mtr3Present()}/{@code mtr4Present()}：那两个是按 PSD 门方块判的，
     * 而掩码这族的目标类（核心数据类 / 设置界面）**跟门方块不是同一批**，各判各的才准。
     */
    private static boolean maskTargetPresent(String className, String what) {
        boolean present = classPresent(className);
        LOGGER.info("[SmoothLift/PidsName] {}（{}）：{}",
                what, className, present ? "目标类在，已放行" : "目标类不在，跳过");
        return present;
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
        // 【1.31.1204】诊断信标：「玩家视角随列车倾斜」那两条 mixin 到底有没有**真的注进去**。
        //   ★ shouldApplyMixin 只说明「放行」，放行之后注入还可能因为「注入点对不上」被静默丢弃
        //     （那两条 @Inject 是 require = 0）。这一行是「已注入」的**正面证据**：
        //     日志里只有「已放行」而没有「已注入」⇒ 注入本身失败了。
        if (mixinClassName.endsWith("Mtr4RideTiltPositionMixin")
                || mixinClassName.endsWith("Mtr4RideTiltMovementMixin")
                || mixinClassName.endsWith("RideTiltPlayerRenderMixin")
                || mixinClassName.endsWith("RideTiltCameraRollMixin")) {
            LOGGER.info("[SmoothLift/TiltView] mixin 已注入：{} → {}",
                    mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1), targetClassName);
        }
    }
}
