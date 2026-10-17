package smooth.lift.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.TrainTiltView;

/**
 * 【1.31.1201 移植】MTR3 的「玩家视角随列车倾斜」（/mtrqx on|off）采集端。
 *
 * <h2>为什么挂在 VehicleRidingClient.setOffsets 上（对 MTR3 3.2.2 的
 * {@code mtr/data/VehicleRidingClient.class} 逐条 javap 核过）</h2>
 * MTR3 没有 MTR4 那套 {@code PositionAndRotation}/{@code VehicleRidingMovement}：
 * 骑乘姿态由服务端直接下发，落在 {@code setOffsets(UUID, x, y, z, yaw, pitch, …)} 里
 * —— 只有「本地玩家在骑车」时才会收到自己那一份（方法体里用
 * {@code riderId.equals(Minecraft.getInstance().player.getUUID())} 判过）。
 * ⇒ 一个钩子同时拿到「骑乘帧」和「列车俯仰/偏航」（第 5、6 个 float 参），
 * 与 MTR4 那两条采集 mixin 喂同一个 {@link TrainTiltView}。
 *
 * <p>★ 下车：MTR3 没有 {@code sendUpdate(true)} 那条链路；不再收到 setOffsets 后，
 * {@link TrainTiltView#onClientTick()} 的 {@code RIDE_GAP_TICKS} 间隙兜底会自动清
 * {@code rideActive}（渲染侧下一帧就不再倾斜/横滚）。
 *
 * <p>★ 铁律：处理器必须<b>非 static</b>（目标 {@code setOffsets} 是实例方法）；
 * 注入体里只许出现「原语 + {@link TrainTiltView} + vanilla（Minecraft/UUID）」，
 * 不许出现 MTR 类型。{@code @Pseudo} + 插件门禁保证没装 MTR3 时安静跳过。
 */
@Pseudo
@Mixin(targets = "mtr.data.VehicleRidingClient")
public abstract class Mtr3RideTiltMixin {

    /**
     * 目标方法 {@code setOffsets(UUID, x, y, z, yaw, pitch, …)} 是「全参数逐位匹配」那条通路
     * （原语 + Runnable + CallbackInfo），描述符逐字抄 javap。
     */
    @Inject(method = "setOffsets(Ljava/util/UUID;DDDFFDIZZZZZZFFZZLjava/lang/Runnable;)V",
            at = @At("HEAD"), require = 0, remap = false)
    private void smoothlift$noteRideMove(java.util.UUID riderId, double x, double y, double z,
                                         float yaw, float pitch, double d7, int i8,
                                         boolean b9, boolean b10, boolean b11, boolean b12,
                                         float f13, float f14, boolean b15, boolean b16,
                                         java.lang.Runnable runnable, CallbackInfo ci) {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !riderId.equals(player.getUUID())) {
            return; // 不是本地玩家的骑乘帧
        }
        TrainTiltView.noteTransform((double) pitch, (double) yaw);
        TrainTiltView.noteRideMove();
    }
}
