package smooth.lift.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorLightRepair;

/**
 * 【1.27】「平面（非 AO）」路径：采样格被实心方块埋住时，改用附近那一格扶梯的亮度。
 *
 * <p>见 {@link EscalatorLightRepair} 的根因说明。这条路走的是
 * {@code ModelBlockRenderer.tesselateWithoutAO} 与 {@code renderModelFaceFlat} 里的直接调用
 * {@code LevelRenderer.getLightColor(level, state, pos.relative(quad.getDirection()))}：
 * <ul>
 *   <li>扶梯的斜坡模型 {@code escalator_step_slope_*_base} 写了 {@code "ambientocclusion": false}，
 *       一定走这条；</li>
 *   <li>玩家在视频设置里关掉「平滑光照」时，整台扶梯都走这条。</li>
 * </ul>
 *
 * <p>这里的 {@code state} 正是<b>被渲染的方块</b>（原版传的就是它），所以能直接认出扶梯；
 * 采样点 {@code pos} 是面外侧那一格，扶梯自己那一格必在它周围（最多差一个对角），
 * 由 {@link EscalatorLightRepair#repairFlat} 去找。
 *
 * <p>只对扶梯状态生效；其它方块一次引用比较就退出（见 {@link EscalatorLightRepair#isEscalator}
 * 里的单条负缓存），所以挂在 {@code getLightColor} 这个超热方法上没有可测量的代价。
 */
@Mixin(LevelRenderer.class)
public abstract class EscalatorFlatLightMixin {

    /**
     * @param level     渲染所在世界
     * @param state     <b>被渲染方块</b>的 state
     * @param samplePos 采样格坐标（原版就在这里取到 0，于是画出黑面）
     */
    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private static void smoothlift$repairBuriedFlatSample(BlockAndTintGetter level, BlockState state,
                                                          BlockPos samplePos,
                                                          CallbackInfoReturnable<Integer> cir) {
        int repaired = EscalatorLightRepair.repairFlat(level, state, samplePos);
        if (repaired != EscalatorLightRepair.NOT_REPAIRED) {
            cir.setReturnValue(repaired);
        }
    }
}
