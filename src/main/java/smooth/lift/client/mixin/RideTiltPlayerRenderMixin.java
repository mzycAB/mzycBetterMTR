package smooth.lift.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.TrainTiltView;

/**
 * 【1.31.1204 二改】让<b>本地玩家的模型</b>跟着列车一起倾斜 ——
 * 「玩家建模底面始终与列车地板平行」，也就是把玩家整体放进车体那个倾斜坐标系里。
 *
 * <h2>和「视角随列车倾斜」（{@link TrainTiltView} 的主路径）什么关系</h2>
 * 两者合起来才是一次完整的<b>刚性倾斜</b>：整个玩家（身体 + 视线）绕车体的横向轴一起转
 * {@code pitch} 角。
 * <pre>
 *   身体上方向 n = R·u   ⇒ 脚底与地板平行（本类负责）
 *   视线方向   = R·f     ⇒ 上坡时视线抬高（主路径 {@code xRot -= toDegrees(Δpitch)} 负责）
 * </pre>
 * ★ 分工的理由：视角只要改一个标量 {@code xRot}（且必须是**增量 + 下车还回去**），
 * 而模型只能改渲染矩阵（**绝对值、每帧重算、无状态**）—— 两者形态不同，硬凑在一个钩子里反而更脆。
 *
 * <h2>★★★ 为什么挂在 {@code LivingEntityRenderer.render} 的 HEAD 上</h2>
 * <ol>
 *   <li><b>HEAD 处的 {@code poseStack} 恰好只做了「世界平移」</b>（{@code EntityRenderDispatcher}
 *       先把矩阵平移到实体的插值位置，再调 {@code renderer.render(...)}）——
 *       此刻 {@code mulPose} 就是一次**世界系**旋转，而对于「绕玩家脚下那条轴转」来说，
 *       世界系旋转正好 = <b>脚不动、身体歪</b>（旋转中心就是实体原点 = 脚底）。</li>
 *   <li>反例：如果挂到 {@code setupRotations} 的 TAIL（那时矩阵已经被 yaw 转过），
 *       再 {@code mulPose} 会变成**模型局部系**旋转 —— 玩家一扭头，倾斜轴就跟着他的脸转，直接错。</li>
 * </ol>
 *
 * <h2>★ 为什么这个轴可以放心从 MTR 的 {@code yaw} 推出来（不用管它是 a→b 还是 b→a）</h2>
 * MTR 的 {@code PositionAndRotation} 里两个角是**从同一对路径点算的**（javap 核过
 * {@code org.mtr.mod.render.PositionAndRotation}）：
 * <pre>
 *   yaw   = atan2(Δx, Δz)                 // 前进方向 f = (sin yaw, 0, cos yaw)
 *   pitch = atan2(Δy, √(Δx² + Δz²))       // 同一个 a→b 方向上的坡度（上坡为正）
 * </pre>
 * 车体的横向轴（水平）就是 {@code r = f × u}，{@code u} = 世界上方：
 * <pre>
 *   r = (sin yaw, 0, cos yaw) × (0, 1, 0) = (-cos yaw, 0, sin yaw)
 * </pre>
 * ★ 万一 MTR 那两个点到点是「反向」的，则 {@code f → -f} <b>且</b> {@code pitch → -pitch}
 * （同一个 a→b 一起翻），于是 {@code r → -r}、角度 {@code θ → -θ}，
 * <b>两次翻转互相抵消</b> ⇒ 最终旋转矩阵逐字不变。**所以不需要知道 MTR 的 yaw 到底指哪一头。**
 * （而 {@code yaw} 本身的正负号定义是确定的：{@code atan2(sin y, cos y) = y}。）
 *
 * <p>★ 旋转方向用右手定则核过：绕 {@code r} 转 {@code +θ} 时
 * {@code u' = u cos θ - f sin θ}（上坡时身体向后仰，即背朝下坡）——
 * 这与「视线 {@code R·f = f cos θ + u sin θ} 上坡抬高」是同一个 {@code R}，
 * 也就是与主路径那句 {@code xRot -= toDegrees(Δpitch)} 同向。
 *
 * <h2>★ 只认本地玩家</h2>
 * 别人的车俯仰角我们拿不到（数据只来自「本机被带着走」那一帧）⇒ 只处理
 * {@code entity == Minecraft.getInstance().player}。另外第一人称下原版根本不会渲染相机所在的
 * 实体（{@code EntityRenderDispatcher.shouldRender} 会把相机实体剔掉），所以本类<b>只影响第三人称</b>，
 * 第一人称天然不会出奇怪的东西。
 *
 * <h2>★ 铁律：注入体里只许出现「原语 + {@link TrainTiltView} + 纯渲染类」</h2>
 * 不许出现 MTR 类型（编译期没有 MTR 依赖）；判断全在 {@link TrainTiltView} 里做。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class RideTiltPlayerRenderMixin {

    /**
     * 目标方法 {@code LivingEntityRenderer.render(T, float, float, PoseStack, MultiBufferSource, int)V}
     * 是个泛型方法，运行期擦除成 {@code (LivingEntity, float, float, PoseStack, MultiBufferSource, int)V}
     * —— 所以描述符里第一段写的是擦除后的 {@code LivingEntity}。
     *
     * <p>★ 回调参数用 {@link CallbackInfo}：目标方法<b>是 {@code void}</b>。
     * （反例记在 {@code Mtr4RideTiltPositionMixin}：目标有返回值时必须用
     * {@code CallbackInfoReturnable}，否则整条 mixin 被 Mixin 静默拒收。）
     *
     * <p>{@code require = 0}：原版哪天把这个方法挪了/改了描述符，安静跳过而不是崩。
     * 「到底有没有注进去」由插件 {@code postApply} 的
     * {@code [SmoothLift/TiltView] mixin 已注入：…} 一行给正面证据（与另外两条倾斜 mixin 同一套探针）。
     */
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void smoothlift$tiltLocalPlayerWithTrain(LivingEntity entity, float entityYaw, float partialTick,
                                                     PoseStack poseStack, MultiBufferSource bufferSource,
                                                     int packedLight, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player) {
            return; // 别人的车俯仰角拿不到；第一人称下相机实体也不会走到这里
        }
        double pitch = TrainTiltView.renderTiltPitch();
        if (Double.isNaN(pitch) || pitch == 0.0) {
            return; // 功能关 / 没在车上 / 这段轨道是平的 —— 都不动矩阵
        }
        double yaw = TrainTiltView.renderTiltYaw();
        if (Double.isNaN(yaw)) {
            return;
        }
        TrainTiltView.noteModelTilt(pitch);

        // 车体横向轴（世界系、水平）：r = f × u = (-cos yaw, 0, sin yaw)，f = (sin yaw, 0, cos yaw)。
        // 绕它转 pitch ⇒ 世界上方被转到「地板法线」上（脚底贴合地板），且脚不动（旋转中心=实体原点）。
        poseStack.mulPose(new Quaternionf().rotateAxis(
                (float) pitch,
                (float) -Math.cos(yaw), 0.0f, (float) Math.sin(yaw)));
    }
}
