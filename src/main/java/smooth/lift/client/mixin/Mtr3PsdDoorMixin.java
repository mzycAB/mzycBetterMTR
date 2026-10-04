package smooth.lift.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.PsdDoorTracker;

/**
 * 【1.50】把 <b>MTR 3</b> 屏蔽门方块实体的门开合程度喂给 {@link PsdDoorTracker}。
 *
 * <p>MTR3 的方块实体叫 {@code TileEntityPSDAPGDoorBase}（注意不是 MTR4 的
 * {@code BlockEntityBase}），而且**没有** {@code getDoorValue()}，只有
 * {@code getOpen(float partialTick)} —— {@code mtr.render.RenderPSDAPGDoor.render} 里正是
 * {@code invokevirtual ...TileEntityPSDAPGDoorBase.getOpen:(F)F}（对 3.2.2 的 javap 逐条核过）。
 *
 * <p>它返回的是 {@code openClient / 32}，量纲与「全开 = 1.0」差一个
 * {@code 0.1/32} 的偏移，所以这里交给 {@link PsdDoorTracker#acceptMtr3} 归一化
 * （详细推导在 {@code PsdDoorTracker} 的类注释里）。
 *
 * <p>描述符 {@code (F)F} 同样是纯原语 ⇒ {@code remap = false}，源码字符串原样进发布 jar。
 */
@Pseudo
@Mixin(targets = "mtr.block.BlockPSDAPGDoorBase$TileEntityPSDAPGDoorBase")
public abstract class Mtr3PsdDoorMixin {

    @Inject(method = "getOpen(F)F", at = @At("RETURN"), remap = false)
    private void smoothlift$reportPsdDoorOpen(float partialTick, CallbackInfoReturnable<Float> cir) {
        // BlockEntityMapper extend net.minecraft.class_2586（= 原版 BlockEntity，已 javap 确认）
        BlockPos pos = ((BlockEntity) (Object) this).getBlockPos();
        PsdDoorTracker.acceptMtr3(pos, cir.getReturnValueF());
    }
}
