package smooth.lift.client;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【1.27c】Sodium 光照修复的共享闸门与诊断。
 *
 * <p>用户整合包装了 Sodium 0.5.4（1.20.4）。Sodium 用**自己的一套网格光照管线**
 * （{@code LightDataAccess} / {@code SmoothLightPipeline} / {@code FlatLightPipeline}），
 * 完全不碰原版 {@code ModelBlockRenderer} / {@code AmbientOcclusionFace} ——
 * 所以上一版挂在原版方法上的三个修复 mixin 在 Sodium 下**全线空转**
 * （诊断日志铁证：AO 入口 0 次；唯一一次「平面路径入口」来自我们自己引擎的取光调用）。
 * 于是把同一套「纯黑采样点 ⇒ 借扶梯自己那格的光」判据搬到 Sodium 的取光唯一源头：
 * {@code LightDataAccess.compute(int, int, int)}（全部角点光 / AO 的唯一计算点，两个缓存类都走它）。
 *
 * <p>为什么这次不依赖编译期类型就能写 handler：{@code compute(III)I} 的参数和返回值
 * 全是 int；「借光」只在两条 mixin 目标里就近调用自己 {@code @Shadow} 出来的
 * {@code world} / {@code getLightmap} / {@code get} 完成。两个包名变体
 * （jellysquid 老包名 vs caffeinemc 新包名）各一个薄 mixin，由
 * {@code smooth.lift.client.mixin.sodium.SodiumLightMixinPlugin} 按「目标类在不在类路径」决定
 * 启用，不装 Sodium 就是空配置，没有任何编译期依赖。
 */
public final class SodiumLightRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 重入闸：借光过程中再次进入 compute 必须直接交回原版。 */
    static final ThreadLocal<Boolean> IN_REPAIR = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 一次性诊断：真的把某个纯黑采样点换成扶梯格亮度时打一行。 */
    private static volatile boolean saidFix;

    private SodiumLightRepair() {
    }

    /**
     * 尝试开闸。
     *
     * @return true = 闸本来就合着（正在借光，交回原版）；false = 闸已由本调用打开
     */
    public static boolean tryBegin() {
        if (IN_REPAIR.get()) {
            return true;
        }
        IN_REPAIR.set(Boolean.TRUE);
        return false;
    }

    /** 关闸（与 {@link #tryBegin()} 配对，finally 里调用）。 */
    public static void end() {
        IN_REPAIR.set(Boolean.FALSE);
    }

    /** 一次性诊断日志：Sodium 侧真的改过一次光。 */
    public static void logFix(int x, int y, int z, BlockPos own) {
        if (!saidFix) {
            saidFix = true;
            LOGGER.info("[SmoothLift] 取光修正：Sodium 把纯黑采样点 ({}, {}, {}) 换成扶梯格 {} 的亮度",
                    x, y, z, own);
        }
    }
}