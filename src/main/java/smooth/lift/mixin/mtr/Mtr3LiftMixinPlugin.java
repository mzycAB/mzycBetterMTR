package smooth.lift.mixin.mtr;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 【1.42】MTR 3 专用 mixin 的**开关**：只在「装了 MTR 3」时才让 {@link Mtr3LiftDoorMixin} 生效。
 *
 * <p>SmoothLift **不依赖** MTR（build.gradle 里没有 MTR，fabric.mod.json 里 MTR 只是
 * {@code suggests}），所以游戏完全可能没装 MTR、或者装的是 MTR 4。而 {@link Mtr3LiftDoorMixin}
 * 的目标类是 MTR 3 独有的 {@code mtr.data.LiftServer} —— 那个类不存在时必须**安静地跳过**，
 * 而不是让 Mixin 抛「目标类不存在」把游戏带崩。
 *
 * <p>【1.44】起这个插件同时管**两条** mixin，门禁各自独立（见 {@link #shouldApplyMixin}）：
 * <ul>
 *   <li>{@link Mtr3LiftDoorMixin} —— 只在 MTR3 生效；</li>
 *   <li>{@link Mtr4LiftTrackFloorShapeMixin} —— 只在 MTR4 生效
 *       （按它的目标类 {@code org.mtr.mod.block.BlockLiftTrackFloor} 是否存在判定）。</li>
 * </ul>
 *
 * <p>判据（与客户端 {@code MtrLiftAccess} 的版本判定同一套，只是这里只看两个类存不存在）：
 * <ul>
 *   <li>{@code mtr.data.LiftServer} 在 &rarr; 这是 MTR 3（3.x 才有 {@code mtr.*} 包）；</li>
 *   <li>{@code org.mtr.core.data.Lift} 在 &rarr; 这是 MTR 4，**明确不要**应用
 *       （需求原文：加载 mtr4 就不管这一条功能）。</li>
 * </ul>
 * 两个都不在 = 没装 MTR，同样跳过。
 *
 * <h2>★★★ 铁律：这个类里**绝对不能**用 {@code Class.forName} / {@code loadClass} 探测类</h2>
 * 本插件的方法跑在 Mixin 的**准备阶段（prepare）**，比别的模组的 mixin 配置还早。
 * {@code Class.forName(name, false, cl)} 虽然不跑静态初始化，但**已经把类
 * load + link 进类加载器了** —— 之后轮到那个模组的 mixin 准备时，Mixin 会直接抛
 * {@code MixinTargetAlreadyLoadedException: ... target X was loaded too early}，
 * 后果是**游戏启动即崩**（而且是崩在别的模组名下，极难定位）。
 *
 * <p><b>真实事故（2026-09-19 用户崩溃报告，本模组造成的）</b>：整合包里 {@code jsblock}
 * 有个 {@code modded.mtrpatch.LiftMixin} 以 {@code org.mtr.core.data.Lift} 为目标。
 * 本插件当时用 {@code Class.forName} 探测它，日志时序铁证：
 *
 * <pre>
 * [10:20:00] [INFO] [SmoothLift/Mtr3Fix] 未启用直梯自动关门修复（…org.mtr.core.data.Lift=true…）
 * [10:20:01] [ERROR] Mixin prepare for mod jsblock failed preparing modded.mtrpatch.LiftMixin
 *                   … target org.mtr.core.data.Lift was loaded too early.
 * </pre>
 *
 * <p>⇒ 探测「某个类在不在」一律用**类路径资源**（读 {@code .class} 文件本身），
 * 它不触发任何类加载。见 {@link #classPresent}。本工程用
 * {@code _tools/check-mixin-plugin-safety.py} 离线守住这条铁律。
 */
public class Mtr3LiftMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** null = 还没判定过。 */
    private static Boolean mtr3;

    /**
     * 【1.44】MTR4 的「楼层轨道」方块类在不在 —— 决定 {@link Mtr4LiftTrackFloorShapeMixin}
     * 要不要应用。判据直接用**那个 mixin 的目标类本身**，比按 MTR 大版本号猜更准：
     * 只要目标类在就应用，MTR 哪天把方块挪了包也照样对得上（对不上就是安全跳过）。
     */
    private static Boolean mtr4TrackFloor;

    @Override
    public void onLoad(String mixinPackage) {
        // 判定放在 onLoad：它一定早于任何 shouldApplyMixin 调用，且整局只跑一次。
        // ★ 正因为这里「早于一切」，所以探测手段只能是**读资源**（见 classPresent）。
        //   用 Class.forName 会在这个时间点把类定义掉，直接搞崩别人的 mixin 准备。
        mtr3Present();
        mtr4TrackFloorPresent();
    }

    private static boolean mtr3Present() {
        if (mtr3 == null) {
            boolean hasMtr3Class = classPresent("mtr.data.LiftServer");
            boolean hasMtr4Class = classPresent("org.mtr.core.data.Lift");
            mtr3 = hasMtr3Class && !hasMtr4Class;
            if (mtr3) {
                LOGGER.info("[SmoothLift/Mtr3Fix] 检测到 MTR 3（mtr.data.LiftServer）"
                        + "—— 直梯自动关门修复已启用");
            } else {
                LOGGER.info("[SmoothLift/Mtr3Fix] 未启用直梯自动关门修复"
                                + "（mtr.data.LiftServer={}、org.mtr.core.data.Lift={}；"
                                + "MTR4 或没装 MTR 都不需要这个修复）",
                        hasMtr3Class, hasMtr4Class);
            }
        }
        return mtr3;
    }

    /** MTR4 的楼层轨道方块类是否在。没在 = 没装 MTR4（或 MTR 挪了类），跳过那个 mixin。 */
    private static boolean mtr4TrackFloorPresent() {
        if (mtr4TrackFloor == null) {
            mtr4TrackFloor = classPresent("org.mtr.mod.block.BlockLiftTrackFloor");
            LOGGER.info("[SmoothLift/Mtr3Fix] 楼层轨道碰撞箱修复（MTR4）：{}",
                    mtr4TrackFloor ? "目标类在，已启用" : "目标类不在（没装 MTR4），跳过");
        }
        return mtr4TrackFloor;
    }

    /**
     * 某个类**在不在**类路径上 —— 只看资源，**完全不加载类**。
     *
     * <p>★ 这里必须用 {@code getResource} 而不是 {@code Class.forName}：
     * 本方法跑在 Mixin 准备阶段，一旦把类定义进类加载器，就会让别的模组对同一个类的
     * mixin 抛 {@code MixinTargetAlreadyLoadedException}（见类注释里的真实事故）。
     * {@code getResource("a/b/C.class")} 只读 jar 里的那个 entry，不 define 任何类。
     *
     * <p>返回 false 是**正常情况**，不打印堆栈：没装 MTR / 装的是 MTR4 都会走到这里。
     */
    private static boolean classPresent(String name) {
        try {
            String resource = name.replace('.', '/') + ".class";
            ClassLoader loader = Mtr3LiftMixinPlugin.class.getClassLoader();
            return loader != null && loader.getResource(resource) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 【1.44】本配置文件里有两条 mixin，门禁不同：
        //   Mtr3LiftDoorMixin            只管 MTR3（目标 mtr.data.LiftServer 是 MTR3 独有）
        //   Mtr4LiftTrackFloorShapeMixin 只管 MTR4（按目标类本身是否存在判定）
        if (mixinClassName.endsWith("Mtr4LiftTrackFloorShapeMixin")) {
            return mtr4TrackFloorPresent();
        }
        // 【10-03 五改】「关门后等待 X 秒发车」：目标 = org.mtr.core.data.Vehicle（MTR4 独有 ——
        //   MTR3 的列车叫 mtr.data.Train，所以判据同样只按「这个目标类在不在」，绝不 load 类）。
        if (mixinClassName.endsWith("Mtr4TrainDepartHoldMixin")) {
            return mtr4VehiclePresent();
        }
        return mtr3Present();
    }

    /** MTR4 的 {@code org.mtr.core.data.Vehicle} 在不在 —— 决定发车等待那条 mixin 要不要应用。 */
    private static Boolean mtr4Vehicle;

    private static boolean mtr4VehiclePresent() {
        if (mtr4Vehicle == null) {
            mtr4Vehicle = classPresent("org.mtr.core.data.Vehicle");
            LOGGER.info("[SmoothLift/PsdDepart] 关门后等待发车（MTR4 的 org.mtr.core.data.Vehicle）：{}",
                    mtr4Vehicle ? "目标类在，已启用" : "目标类不在（没装 MTR4），跳过");
        }
        return mtr4Vehicle;
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
