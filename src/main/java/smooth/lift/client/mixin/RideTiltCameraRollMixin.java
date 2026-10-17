package smooth.lift.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.TrainTiltView;

/**
 * 【1.31.1204 五改(定版)】列车上下坡时，把<b>整个 3D 画面</b>转一个角度 ——
 * 角度 = <b>列车自身的倾斜角</b>，绕 <b>车体横向轴</b>，<b>与视线方向无关</b>。
 *
 * <p>★【LOG11123/LOG1182 终版说明】六改一度改成「相机俯仰」，用户复测后明确要回<b>窗口横滚</b>
 * （看向窗外时地平线应随列车倾斜旋转 = fabric 各版本的行为），模型「底面平行地板」同样保留。
 * 本文件回到五改的 {@code view = cam · R^{-1}} 方案：车厢地板在屏幕上保持水平、地平线随坡倾斜。
 *
 * <p>★【LOGhj1】ordinal 用 <b>-1</b>（最后一个 mulPose = 相机 Y 旋转）：forge 修补版 renderLevel
 * 比原版多一处旋转（ViewportEvent 的相机横滚 Axis.ZP），ordinal=3 会落错位被随后的相机 Y 盖掉。
 * 1.18.2 的四元数类型是 com.mojang.math.Quaternion（target 描述符见该版本文件）。
 *
 * <h2>为什么只能在这里补（对 1.20.x 逐条 javap 核过）</h2>
 * {@code Camera.setRotation(yRot, xRot)} 内部是 {@code Quaternionf.rotationYXZ(-yRot, xRot, 0)}
 * —— <b>roll 那一位写死 0</b>，{@code Camera} 自己也没有任何 roll 入口；而视锥、世界、第一人称手
 * 全共用 renderLevel 里这一份 {@code poseStack} ⇒ 在相机 Y 旋转<b>之后</b>补乘 {@code R^{-1}}，
 * 三处一起带上，不会各转各的。HUD 由调用方另建单位矩阵 ⇒ 不受影响。
 *
 * <p>★ 铁律：注入体里只许出现「原语 + {@link TrainTiltView} + 纯渲染类（PoseStack/JOML）」——
 * 不许出现 MTR 类型。取列车俯仰/偏航只走 {@link TrainTiltView} 的只读接口。
 */
@Mixin(GameRenderer.class)
public abstract class RideTiltCameraRollMixin {

    /**
     * {@code require = 0}：原版改了这段代码就安静跳过（不崩），由
     * {@code TrainTiltView.noteCameraRoll} 的「注入点已命中」首帧日志 + 插件 {@code postApply}
     * 的「mixin 已注入」信标给正面证据。
     */
    @Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",
                    ordinal = -1, shift = At.Shift.AFTER),
            require = 0)
    private void smoothlift$rollCameraWithTrain(float partialTick, long finishNanoTime, PoseStack poseStack,
                                                CallbackInfo ci) {
        if (poseStack == null) {
            return;
        }
        double pitch = TrainTiltView.renderTiltPitch();
        if (Double.isNaN(pitch) || pitch == 0.0) {
            return; // 功能关 / 没在车上 / 这段轨道是平的 —— 都不动矩阵
        }
        double yaw = TrainTiltView.renderTiltYaw();
        if (Double.isNaN(yaw)) {
            return;
        }
        // R^{-1}：R = 绕车体横向轴 r = (-cos yaw, 0, sin yaw) 转 pitch（与模型倾斜同一个 R）。
        // 把世界反向转进列车坐标系 ⇒ 列车/地板/窗框在屏幕上水平、地面与地平线变斜。
        // ★ 与视线方向无关（不读相机）⇒ 只要在坡上就一定转，且扭头不会抽。
        poseStack.mulPose(new Quaternionf().rotateAxis(
                (float) -pitch,
                (float) -Math.cos(yaw), 0.0F, (float) Math.sin(yaw)));
        TrainTiltView.noteCameraRoll(Math.toDegrees(pitch));
    }
}