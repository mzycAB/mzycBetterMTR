package smooth.lift.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.client.TrainTiltView;

/**
 * 【1.31.1211 移植】列车上下坡时，把<b>整个 3D 画面</b>转一个角度
 * —— 角度 = <b>列车自身的倾斜角</b>，绕 <b>车体横向轴</b>，<b>与视线方向无关</b>。
 *
 * <h2>★ 为什么 1.21.1 的注入点跟 1.20.4 不一样（对 1.21.1 官方混淆 + mojang mapping 逐条核过）</h2>
 * 1.20.4 是往 {@code GameRenderer.renderLevel(float,long,PoseStack)} 里最后一条
 * {@code mulPose(Quaternionf)}（相机 Y 旋转）<b>之后</b>再补乘 {@code R^{-1}}。
 * 1.21.1 把渲染管线重构了：
 * <ul>
 *   <li>{@code GameRenderer.renderLevel} 签名变成 {@code renderLevel(DeltaTracker)}，且
 *       GameRenderer 里 <b>PoseStack / mulPose 调用已消失</b>（改成 Matrix4f 直算）；</li>
 *   <li>视图矩阵由 {@code new Matrix4f().rotation(camera.rotation().conjugate())} 现场构造，
 *       作为参数传给 {@code LevelRenderer.renderLevel(DeltaTracker, boolean, Camera, GameRenderer,
 *       LightTexture, Matrix4f view, Matrix4f projection)}；</li>
 *   <li>该 view 参数被两处消费：<b>①</b> {@code new Frustum(view, projection)}（视锥，offset 265-273）；
 *       <b>②</b> {@code RenderSystem.getModelViewStack().pushMatrix().mul(view)}（世界渲染，offset 703-718）。</li>
 * </ul>
 * ⇒ 在 {@code LevelRenderer.renderLevel} 的 HEAD 直接给 view 矩阵右乘 {@code R^{-1}}，
 * 视锥与世界<b>同吃一个矩阵</b>，两处一起带上横滚（与 1.20.4「在 poseStack 上补旋转」语义一致）。
 *
 * <h2>正解算式（与 1.20.4 / 玩家模型那条完全同一个 R）</h2>
 * <pre>
 *   模型那条：poseStack.mulPose(R)      // 玩家转进列车坐标系（脚底贴地板）
 *   相机这条：view = view · R^{-1}      // 世界反向转进列车坐标系（列车变平、地面变斜）
 * </pre>
 * {@code R = rotateAxis(pitch, r)}，{@code r = f × u = (-cos yaw, 0, sin yaw)}（车体横向轴，水平）。
 * 角度就是<b>列车倾斜角本身</b>，与「往哪看」无关（算式里只有 {@code pitch} 与 {@code yaw}）。
 *
 * <p>★ 铁律：注入体里只许出现「原语 + {@link TrainTiltView} + 纯渲染类（Matrix4f/JOML）」——
 * 不许出现 MTR 类型。取列车俯仰/偏航只走 {@link TrainTiltView} 的只读接口。
 */
@Mixin(LevelRenderer.class)
public abstract class RideTiltCameraRollMixin {

    /**
     * {@code require = 0}：原版改了这段代码就安静跳过（不崩），由
     * {@code TrainTiltView.noteCameraRoll} 的「注入点已命中」首帧日志 + 插件 {@code postApply}
     * 的「mixin 已注入」信标给正面证据。
     */
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;"
            + "Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"), require = 0)
    private void smoothlift$rollCameraWithTrain(DeltaTracker deltaTracker, boolean renderBlockLayer,
                                                Camera camera, GameRenderer gameRenderer,
                                                LightTexture lightTexture, Matrix4f viewMatrix,
                                                Matrix4f projectionMatrix, CallbackInfo ci) {
        if (viewMatrix == null) {
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
        // 视图矩阵右乘 R^{-1} ⇒ 视锥与世界一起转进列车坐标系（列车/地板/窗框水平、地面与地平线变斜）。
        // ★ 与视线方向无关（不读相机）⇒ 只要在坡上就一定转，且扭头不会抽。
        viewMatrix.mul(new Matrix4f().rotation(new Quaternionf().rotateAxis(
                (float) -pitch,
                (float) -Math.cos(yaw), 0.0F, (float) Math.sin(yaw))));
        TrainTiltView.noteCameraRoll(Math.toDegrees(pitch));
    }
}
