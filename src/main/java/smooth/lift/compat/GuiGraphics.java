package smooth.lift.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.Component;

/**
 * 垫片：{@code GuiGraphics} 是 1.20 才引入的 GUI 绘制上下文，1.18.2 里 GUI 直接拿
 * {@link PoseStack} 画。本类**继承** {@code PoseStack}，于是
 * <ul>
 *   <li>可以直接当 {@code PoseStack} 传给 {@code super.render(...)} / {@code renderBackground(...)}；</li>
 *   <li>同时提供 1.20 那套 {@code drawString / fill / enableScissor / renderOutline} 实例方法。</li>
 * </ul>
 *
 * <p>构造时把传入的 {@code PoseStack} 栈顶矩阵**拷贝**一份：屏幕的 {@code render} 里
 * 父栈在绘制期间不会被改动，所以拷贝与父栈逐位相同，绘制结果一致。
 */
public class GuiGraphics extends PoseStack {

    public GuiGraphics() {
        super();
    }

    public GuiGraphics(PoseStack source) {
        super();
        if (source != null) {
            this.last().pose().load(source.last().pose());
            this.last().normal().load(source.last().normal());
        }
    }

    /** 1.20 的 {@code pose()}：这里返回自身（本类就是 PoseStack）。 */
    public PoseStack pose() {
        return this;
    }

    // ------------------------------------------------------------------
    // 文字
    // ------------------------------------------------------------------

    /** 带阴影（1.20 的 {@code drawString} 默认就是带阴影的）。 */
    public void drawString(Font font, String text, int x, int y, int color) {
        font.drawShadow(this, text, (float) x, (float) y, color);
    }

    public void drawString(Font font, String text, int x, int y, int color, boolean dropShadow) {
        if (dropShadow) {
            font.drawShadow(this, text, (float) x, (float) y, color);
        } else {
            font.draw(this, text, (float) x, (float) y, color);
        }
    }

    public void drawString(Font font, Component text, int x, int y, int color) {
        font.drawShadow(this, text, (float) x, (float) y, color);
    }

    public void drawString(Font font, Component text, int x, int y, int color, boolean dropShadow) {
        if (dropShadow) {
            font.drawShadow(this, text, (float) x, (float) y, color);
        } else {
            font.draw(this, text, (float) x, (float) y, color);
        }
    }

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        GuiComponent.drawCenteredString(this, font, text, x, y, color);
    }

    public void drawCenteredString(Font font, Component text, int x, int y, int color) {
        GuiComponent.drawCenteredString(this, font, text, x, y, color);
    }

    // ------------------------------------------------------------------
    // 填充 / 描边
    // ------------------------------------------------------------------

    public void fill(int minX, int minY, int maxX, int maxY, int color) {
        GuiComponent.fill(this, minX, minY, maxX, maxY, color);
    }

    public void fill(int minX, int minY, int maxX, int maxY, int z, int color) {
        GuiComponent.fill(this, minX, minY, maxX, maxY, color);
    }

    /** 1px 矩形描边（1.20 的 {@code renderOutline} 语义）。 */
    public void renderOutline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color);
        fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y + 1, x + 1, y + height - 1, color);
        fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    // ------------------------------------------------------------------
    // 裁剪（坐标是 GUI 缩放后的逻辑坐标，需换算到帧缓冲像素）
    // ------------------------------------------------------------------

    public void enableScissor(int x1, int y1, int x2, int y2) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        double scale = minecraft.getWindow().getGuiScale();
        int fbHeight = minecraft.getWindow().getHeight();
        RenderSystem.enableScissor(
                (int) (x1 * scale),
                (int) (fbHeight - y2 * scale),
                (int) ((x2 - x1) * scale),
                (int) ((y2 - y1) * scale));
    }

    public void disableScissor() {
        RenderSystem.disableScissor();
    }
}