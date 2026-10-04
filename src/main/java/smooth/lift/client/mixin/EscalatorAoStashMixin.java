package smooth.lift.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.EscalatorLightRepair;

import java.util.BitSet;

/**
 * 【1.27】AO 路径第一半：把「现在正在画哪一格扶梯」记下来。
 *
 * <p>见 {@link EscalatorLightRepair} 的根因说明。这里只做记录，取值在同包
 * {@link EscalatorAoSampleMixin}（挂 {@code ModelBlockRenderer$Cache.getLightColor}）里。
 *
 * <p><b>为什么必须挂这个内嵌类</b>：AO 的取光全在
 * {@code ModelBlockRenderer$AmbientOcclusionFace.calculate} 内部，而那里拿不到「被渲染的方块」，
 * 只有 {@code calculate} 的第一个参数（被渲染方块的 state）和第三个参数（它自己的坐标）。
 * 于是「谁在画扶梯」这个信息只能在 {@code calculate} 入口处记下来。
 * 该内嵌类是包级私有的，只能用字符串 {@code targets} 指。
 */
@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer$AmbientOcclusionFace")
public abstract class EscalatorAoStashMixin {

    /**
     * @param level     渲染所在世界
     * @param state     <b>被渲染方块</b>的 state（扶梯时就是扶梯方块）
     * @param pos       <b>被渲染方块自己那一格</b>的坐标 —— 借光用的就是它
     * @param direction 这个面朝哪边
     * @param shape     形状数据（AO 内部用，这里不碰）
     * @param flags     遮挡位（AO 内部用，这里不碰）
     * @param shade     是否参与方向明暗
     */
    @Inject(method = "calculate(Lnet/minecraft/world/level/BlockAndTintGetter;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/Direction;"
            + "[FLjava/util/BitSet;Z)V", at = @At("HEAD"))
    private void smoothlift$rememberEscalatorCell(BlockAndTintGetter level, BlockState state, BlockPos pos,
                                                  Direction direction, float[] shape, BitSet flags, boolean shade,
                                                  CallbackInfo ci) {
        EscalatorLightRepair.beginAo(state, pos);
    }
}
