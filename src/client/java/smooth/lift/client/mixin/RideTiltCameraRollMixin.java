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
 * 【1.31.1204 五改★】列车上下坡时，把<b>整个 3D 画面</b>转一个角度
 * —— 角度 = <b>列车自身的倾斜角</b>，绕 <b>车体横向轴</b>，<b>与视线方向无关</b>。
 *
 * <h2>一、用户要的是什么（LOGf7/LOGf8 两轮澄清后的最终口径）</h2>
 * 原话（LOGf7）：「视角旋转指的是旋转玩家游戏窗口，<b>旋转角度=列车倾斜角度</b>，懂了吗？
 * 直接旋转玩家整个游戏窗口。任务栏之类的保持不变。就可以做到视角转动的效果。
 * 之前的方法错了。但是玩家建模底面平行于列车地板可以保留。」
 * 原话（三改，描述观感）：「玩家乘坐列车看向列车侧面车窗外，列车上下坡时，玩家视角对于地面来说
 * 应该是斜的，也就是<b>平行于窗框或者列车地板</b>。」
 * <p>⇒ 观感目标 = <b>列车/地板/窗框在屏幕上变成水平，地面与地平线变成斜的</b>；角度恰为列车倾斜角。
 *
 * <h2>二、★★ 四改错在哪（LOGf8 的实锤）</h2>
 * 四改把横滚算成「当前视线的函数」（{@code roll = p·sin(az)} 一族）：
 * <pre>
 *   看正前（az=0）  → roll ≡ 0        ← 骑在车里默认就是顺着车头看 ⇒ 用户看到「并没有旋转」
 *   看侧窗（az=90） → roll = p
 *   一扭头          → roll 在 0…±17° 之间摆 ⇒ 世界来回歪 = 用户说的「莫名其妙抽搐视角」
 * </pre>
 * 证据（LOGf8）：{@code 列车pitch=8.52°} 而 {@code 窗口横滚=0.00°} 一路不变
 * ⇒ 注入点在跑、算式每帧都返回 0（不是没注入）。
 * <p>根因：<b>「把窗口转一个固定角度」被写成了「按视线方向投影」</b>。用户要的是前者。
 *
 * <h2>三、★ 正解：乘 <b>{@code R^{-1}}</b>（与模型倾斜那条恰好互逆）</h2>
 * 模型那条（{@code RideTiltPlayerRenderMixin}）把玩家模型转进列车坐标系：
 * {@code R = rotateAxis(pitch, r)}，{@code r = f × u = (-cos yaw, 0, sin yaw)}（车体横向轴，水平）。
 * 既然世界里的列车本身是 {@code R} 歪的，相机只要把世界<b>反向转回去</b>，屏幕上列车就平了：
 * <pre>
 *   poseStack.mulPose(R^{-1})，  R^{-1} = rotateAxis(-pitch, r)
 * </pre>
 * ⇒ 列车（含地板/窗框）= {@code R·R^{-1}} = 平的；地面 = {@code R^{-1}} = 斜的；玩家模型
 * （自己已经带了 {@code R}）也恰好回到正立。**这就是「视角平行于窗框/地板」。**
 * <p>★ 与四改的关键差别：算式里<b>只剩 {@code pitch} 和 {@code yaw}</b>，<b>不再读相机视线</b>
 * ⇒ 角度与「往哪看」无关 ⇒ ① 只要列车在坡上就一定转（不会再看正前不转）
 * ② 扭头时画面纹丝不动（不会抽）。
 * <p>★ 符号只有一处自由：{@code R^{-1}} 由 {@code r} 与 {@code pitch} 唯一确定，没有「左右侧窗
 * 各一个方向」的二义（那正是四改沿视线投影时的老毛病）。
 *
 * <h2>四、为什么只能在这里补（对 MC 1.20.4 逐条 javap 核过）</h2>
 * <ol>
 *   <li>{@code Camera.setRotation(yRot, xRot)} 内部是 {@code Quaternionf.rotationYXZ(-yRot, xRot, 0)}
 *       —— <b>roll 那一位写死 0</b>，{@code Camera} 自己也没有任何 roll 入口。</li>
 *   <li>{@code GameRenderer.renderLevel} 的视角矩阵不是从 {@code camera.rotation()} 来的，
 *       而是现场用两个<b>标量</b>拼的：
 *       <pre>
 *   poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));          // 第 3 处 mulPose(Quaternionf)
 *   poseStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0f)); // 第 4 处 ← 本类钩在这条之后
 *       </pre>
 *       两个标量都表达不了 roll ⇒ 只能在这两条旋转<b>之后</b>往矩阵上再乘一个旋转。</li>
 *   <li>★ 注入点选择：这一段之后的
 *       {@code new Matrix3f(poseStack.last().normal()).invert() → RenderSystem.setInverseViewRotationMatrix}、
 *       {@code LevelRenderer.prepareCullFrustum}、{@code LevelRenderer.renderLevel}（世界）、
 *       以及 {@code renderItemInHand}（第一人称手）<b>全部共用同一个 poseStack</b>
 *       ⇒ 视锥剔除、世界、手三者一起带上这个旋转，不会各转各的。</li>
 *   <li>★ <b>HUD 为什么不受影响</b>：{@code renderLevel} 自己<b>不 push/pop</b>，但它的调用方
 *       {@code GameRenderer.render} 在返回后立刻 {@code pushPose() + setIdentity()}（1.20.4 里
 *       offset 426 / 431）再 draw GUI ⇒ 我们的旋转<b>漏不进任务栏/准星/物品栏</b>
 *       （javap 核过：GUI 是从单位矩阵画的）。</li>
 * </ol>
 *
 * <h2>五、铁律</h2>
 * 注入体里只许出现「原语 + {@link TrainTiltView} + 纯渲染类（PoseStack/JOML）」——
 * 不许出现 MTR 类型（编译期没有 MTR 依赖）。取列车俯仰/偏航只走 {@link TrainTiltView} 的只读接口。
 * ★ <b>本类不需要相机对象</b>（五改起）：算式与视线无关，所以连 {@code Minecraft}/{@code Camera} 都不引。
 */
@Mixin(GameRenderer.class)
public abstract class RideTiltCameraRollMixin {

    /**
     * {@code ordinal = 3}：{@code renderLevel} 里 {@code PoseStack.mulPose(Quaternionf)} 共 4 处
     * （428/467 困惑-眩晕分支、501 相机 X、523 相机 Y）—— 第 4 处（0 基 3）才是相机的 Y 旋转。
     * ★ 它后面紧跟着取逆视图矩阵、算视锥、渲染世界、画手 ⇒ 在这里补旋转，三处一起带上。
     *
     * <p>{@code require = 0}：原版改了这段代码就安静跳过（不崩），由
     * {@code TrainTiltView.noteCameraRoll} 的「注入点已命中」首帧日志 + 插件 {@code postApply}
     * 的「mixin 已注入」信标给正面证据（与另外两条倾斜 mixin 同一套探针）。
     */
    @Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",
                    ordinal = 3, shift = At.Shift.AFTER),
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
