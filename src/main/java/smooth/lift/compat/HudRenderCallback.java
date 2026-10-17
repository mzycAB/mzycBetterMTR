package smooth.lift.compat;

import com.mojang.blaze3d.vertex.PoseStack;

import smooth.lift.compat.Event;

/**
 * Forge 侧垫片：挂到 {@code RenderGameOverlayEvent.Post}（ElementType.ALL）。
 *
 * <p>1.18.2 还没有 {@code GuiGraphics}，回调里拿到的是 {@link PoseStack}。
 */
public interface HudRenderCallback {

    Event<HudRenderCallback> EVENT = new Event<>();

    void onHudRender(PoseStack poseStack, float tickDelta);
}