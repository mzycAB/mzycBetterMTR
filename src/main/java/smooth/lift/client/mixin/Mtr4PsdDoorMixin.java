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
 * 【1.50】把 <b>MTR 4</b> 屏蔽门方块实体的门开合程度喂给 {@link PsdDoorTracker}。
 *
 * <h2>为什么挂在 {@code getDoorValue()} 上</h2>
 * 这个名字不是随便挑的，它是**唯一一个「每帧、每扇门、都会经过」的读口**：
 * MTR4 的 {@code RenderPSDAPGDoor.render} 里就是
 * <pre>
 *   aload_1 / fload_2 / invokevirtual BlockEntityBase.tick:(F)V      ← 自己推进门动画
 *   aload_1 / invokevirtual BlockEntityBase.getDoorValue:()D          ← 再读出来画
 * </pre>
 * （对 4.0.5 的 {@code org/mtr/mod/render/RenderPSDAPGDoor.class} 逐条 javap 核过）。
 * 而 {@code RenderPSDAPGDoor.rendersOutsideBoundingBox2(T)} 直接 {@code return true}，
 * 所以「渲染距离内、但不在视野里」的门同样会走这条路径 ⇒ 玩家背对门时门值也在走。
 *
 * <h2>为什么不需要 refmap 操心</h2>
 * 方法描述符是 {@code ()D} —— <b>纯原语</b>，里面没有任何类名，不需要（也不应该）重映射；
 * 方法名 {@code getDoorValue} 是 MTR 自己的（MTR 不参与原版混淆，且在
 * {@code org.mtr.mod.block} 这个「带 mapping 层」的包里名字两端一致）。
 * 所以这里 {@code remap = false}，写什么运行期就是什么。
 *
 * <p>{@code @Pseudo} + 插件门禁（{@code PsdDoorMixinPlugin}）双保险：没装 MTR4 时**安静跳过**，
 * 既不影响启动，也不会因为「目标类不存在」把游戏带崩。
 */
@Pseudo
@Mixin(targets = "org.mtr.mod.block.BlockPSDAPGDoorBase$BlockEntityBase")
public abstract class Mtr4PsdDoorMixin {

    @Inject(method = "getDoorValue()D", at = @At("RETURN"), remap = false)
    private void smoothlift$reportPsdDoorValue(CallbackInfoReturnable<Double> cir) {
        // BlockEntityAbstractMapping extend net.minecraft.class_2586（= 原版 BlockEntity，
        // 已对 4.0.5 的 javap 确认），所以直接问它自己要坐标即可，**不需要认识任何 MTR 类型**。
        BlockPos pos = ((BlockEntity) (Object) this).getBlockPos();
        PsdDoorTracker.acceptMtr4(pos, cir.getReturnValueD());
    }
}
