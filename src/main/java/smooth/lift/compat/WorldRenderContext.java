package smooth.lift.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * Forge 侧垫片：Fabric 的 {@code WorldRenderContext}。
 *
 * <p>1.18.2 里 {@code MatrixStack} 已改名 {@link PoseStack}，{@code Frustum} 在
 * {@code net.minecraft.client.renderer.culling} 下。本模组只用到下面这几个成员。
 */
public interface WorldRenderContext {

    PoseStack matrixStack();

    Matrix4f projectionMatrix();

    float tickDelta();

    ClientLevel world();

    Camera camera();

    Frustum frustum();
}