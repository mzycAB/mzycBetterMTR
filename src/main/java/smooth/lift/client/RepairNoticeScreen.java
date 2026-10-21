package smooth.lift.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.RepairNotice;
import smooth.lift.SmoothLift;
import smooth.lift.net.SLNet;

import java.net.URI;

/**
 * 【1.37 / Forge1.18.2 移植】「未导入补丁」提示弹窗（**每次进世界**由服务端那只
 * {@code MBM_REPAIR_NOTICE} 包触发）。
 *
 * <h2>用户点名的口径</h2>
 * <blockquote>
 *   「弹窗下方有 3 个按钮：不再显示 / 打开网站 / 确定
 *     点击『不再显示』… 其实就是把 Repair_Check 改为 0
 *     『打开网站』… **不关闭这个弹窗**
 *     『确定』… 关闭弹窗，但是**不更改** Repair_Check 的值，下次启动游戏还弹窗」
 * </blockquote>
 *
 * <h2>三个按钮</h2>
 * <ul>
 *   <li><b>不再显示</b>：发空包给服务端（{@link SmoothLift#MBM_REPAIR_DISMISS_CHANNEL}），
 *       服务端把 {@code <存档>/MBM_settings.txt} 写成 {@code Repair_Check=0}，然后关窗。</li>
 *   <li><b>打开网站</b>：本机开浏览器到 {@link RepairNotice#WEBSITE}，**不关窗**。</li>
 *   <li><b>确定</b>：只关窗，**不发包、不改文件** ⇒ 下次进这个世界还会弹。</li>
 * </ul>
 * ★ ESC 与「确定」同义（关窗、不改文件）。
 *
 * <h2>1.18.2 的 API 差异</h2>
 * 这个版本没有 {@code GuiGraphics}（1.20 才引入），绘制走
 * {@code render(PoseStack, int, int, float)} + {@code drawCenteredString}；
 * 文字用 {@link TextComponent}（{@code Component.literal} 是 1.19+ 才有）。
 */
public class RepairNoticeScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 面板宽度：够放三只按钮并排（3 × 80 + 2 × 4 = 248，左右各留 6）。 */
    private static final int PANEL_W = 260;
    /** 面板高度：标题 14 + 两行正文 + 按钮行 + 上下留白。 */
    private static final int PANEL_H = 104;
    /** 并排三只按钮的宽 / 高 / 间距。 */
    private static final int BTN_W = 80;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;

    /** 关窗之后回到哪个界面；{@code null} = 回到游戏（进世界时弹的那次就是 null）。 */
    private final Screen returnTo;

    /**
     * 构造弹窗。
     *
     * @param returnTo 关窗后要回去的界面；进世界时自动弹出的那次传 {@code null}（回游戏）
     */
    public RepairNoticeScreen(Screen returnTo) {
        super(new TextComponent(RepairNotice.TITLE));
        this.returnTo = returnTo;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int rowY = panelY() + PANEL_H - 10 - BTN_H;
        int totalW = BTN_W * 3 + BTN_GAP * 2;
        int left = cx - totalW / 2;

        // ① 不再显示：写 Repair_Check=0（服务端落盘），然后关窗。
        addRenderableWidget(new Button(left, rowY, BTN_W, BTN_H,
                new TextComponent("不再显示"), button -> {
                    try {
                        SLNet.sendToServer(SmoothLift.MBM_REPAIR_DISMISS_CHANNEL, SLNet.emptyBuf());
                        LOGGER.info("[SmoothLift/Repair] 玩家选择「不再显示」：已请服务端把存档的 MBM_settings.txt 写成 Repair_Check=0");
                    } catch (RuntimeException e) {
                        LOGGER.warn("[SmoothLift/Repair] 没能把「不再显示」发出去（服务端没装本模组？）：{}", e.toString());
                    }
                    // 本会话内也不再弹（服务端写盘是异步的，这里先把界面收掉）。
                    RepairNoticeClient.markDismissed();
                    onClose();
                }));

        // ② 打开网站：本机开浏览器，**不关窗**（用户点名）。
        addRenderableWidget(new Button(left + BTN_W + BTN_GAP, rowY, BTN_W, BTN_H,
                new TextComponent("打开网站"), button -> openWebsite()));

        // ③ 确定：只关窗，不改 Repair_Check（下次启动游戏还弹）。
        addRenderableWidget(new Button(left + (BTN_W + BTN_GAP) * 2, rowY, BTN_W, BTN_H,
                new TextComponent("确定"), button -> onClose()));
    }

    /**
     * 在本机浏览器打开补丁下载网站。
     *
     * <p>★ 走原版 {@link Util#getPlatform()}（与「打开文件夹」按钮同一个入口）。
     * 失败**不弹二次窗、不改文案**：只写日志。
     */
    private void openWebsite() {
        String url = "https://" + RepairNotice.WEBSITE;
        try {
            Util.getPlatform().openUri(URI.create(url));
            LOGGER.info("[SmoothLift/Repair] 已在浏览器打开 {}", url);
        } catch (RuntimeException e) {
            LOGGER.warn("[SmoothLift/Repair] 打开 {} 失败：{}", url, e.toString());
        }
    }

    /** ESC 与「确定」同义：关窗，但**不改** Repair_Check（用户点名：下次启动游戏还弹窗）。 */
    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(returnTo);
    }

    private int panelX() {
        return (this.width - PANEL_W) / 2;
    }

    private int panelY() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        int px = panelX();
        int py = panelY();
        // 面板：先描一圈深色边，再填半透明底 —— 与模组其它界面同一套观感。
        fill(poseStack, px - 2, py - 2, px + PANEL_W + 2, py + PANEL_H + 2, 0xFF000000);
        fill(poseStack, px, py, px + PANEL_W, py + PANEL_H, 0xF0101010);

        // 标题 + 两行正文（文案集中在 RepairNotice，改字只改那一处）。
        drawCenteredString(poseStack, this.font, this.title, this.width / 2, py + 8, 0xFF5555);
        drawCenteredString(poseStack, this.font, new TextComponent(RepairNotice.LINE_1),
                this.width / 2, py + 24, 0xFFFFFF);
        drawCenteredString(poseStack, this.font, new TextComponent(RepairNotice.LINE_2),
                this.width / 2, py + 38, 0xA0A0A0);

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    /** 进世界时弹出来的提示窗**不暂停游戏**（与模组其它界面一致）。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
