package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.RepairNotice;
import smooth.lift.SmoothLift;

import java.net.URI;

/**
 * 【1.37】「未导入补丁」提示弹窗（**每次进世界**由服务端那只 {@code MBM_REPAIR_NOTICE} 包触发）。
 *
 * <h2>一、用户点名的口径</h2>
 * <blockquote>
 *   「每一次启动游戏时如果检测到存档未导入补丁包，就弹窗提示：
 *     未导入补丁！某些功能可能被限制！补丁下载网站:mzyc.pages.dev
 *     弹窗下方有 3 个按钮：不再显示 / 打开网站 / 确定
 *     点击『不再显示』就将此改动写入存档文件夹里的 MBM_settings … 其实就是把 Repair_Check 改为 0
 *     『打开网站』按钮点击后弹出浏览器进入网站，**不关闭这个弹窗**
 *     『确定』按钮点击后关闭弹窗，但是**不更改** Repair_Check 的值，下次启动游戏还弹窗
 *     如果玩家导入补丁之后就默认不弹窗」
 * </blockquote>
 *
 * <h2>二、三个按钮各自做什么</h2>
 * <ul>
 *   <li><b>不再显示</b>：发一只空包给服务端（{@link SmoothLift#MBM_REPAIR_DISMISS_CHANNEL}），
 *       服务端把 {@code <存档>/MBM_settings.txt} 写成 {@code Repair_Check=0}，然后关窗。
 *       从此这个存档**连检测都不做**（判定见 {@link RepairNotice#evaluate}）。</li>
 *   <li><b>打开网站</b>：本机开浏览器到 {@link RepairNotice#WEBSITE}，**不关窗**
 *       （用户点名要留着窗，玩家可以照着网站下完补丁再点确定）。</li>
 *   <li><b>确定</b>：只关窗，**不发包、不改文件** ⇒ 下次进这个世界还会弹。</li>
 * </ul>
 * ★ ESC 与「确定」同义（关窗、不改文件）—— 与应用户「不想现在处理」的直觉一致，
 * 而且绝不能把「随手 ESC」变成永久静音。
 *
 * <h2>三、为什么判定不在这里</h2>
 * 「要不要弹」只由服务端算（{@link RepairNotice}）：存档文件夹与 {@code MBM_settings.txt}
 * 都在服务端那台机器上，客户端够不着。这个界面只负责**显示 + 三个动作**。
 */
public class RepairNoticeScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 面板宽度：够放三只按钮并排（3 × 80 + 2 × 4 = 248，左右各留 6）。 */
    private static final int PANEL_W = 260;
    /** 面板高度：标题 14 + 三行正文 + 按钮行 + 上下留白。 */
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
        super(Component.literal(RepairNotice.TITLE));
        this.returnTo = returnTo;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int rowY = panelY() + PANEL_H - 10 - BTN_H;
        int totalW = BTN_W * 3 + BTN_GAP * 2;
        int left = cx - totalW / 2;

        // ① 不再显示：写 Repair_Check=0（服务端落盘），然后关窗。
        addRenderableWidget(Button.builder(Component.literal("不再显示"), button -> {
                    // ★ 先查频道再发：客户端没装 / 还没握手完时 send 会抛 IllegalStateException
                    //   （见 ClientPlayNetworking.send 的反汇编：不在游戏里就 throw）。
                    //   即使发包失败也要把**本地界面**收掉 —— 玩家点了就得有反应。
                    if (ClientPlayNetworking.canSend(SmoothLift.MBM_REPAIR_DISMISS_CHANNEL)) {
                        ClientPlayNetworking.send(SmoothLift.MBM_REPAIR_DISMISS_CHANNEL, PacketByteBufs.empty());
                        LOGGER.info("[SmoothLift/Repair] 玩家选择「不再显示」：已请服务端把存档的 MBM_settings.txt 写成 Repair_Check=0");
                    } else {
                        LOGGER.warn("[SmoothLift/Repair] 频道不可用，没能把「不再显示」发出去（服务端没装本模组？）");
                    }
                    // 本会话内也不再弹（服务端写盘是异步的，这里先把界面收掉）。
                    RepairNoticeClient.markDismissed();
                    onClose();
                })
                .bounds(left, rowY, BTN_W, BTN_H)
                .build());

        // ② 打开网站：本机开浏览器，**不关窗**（用户点名）。
        addRenderableWidget(Button.builder(Component.literal("打开网站"), button -> openWebsite())
                .bounds(left + BTN_W + BTN_GAP, rowY, BTN_W, BTN_H)
                .build());

        // ③ 确定：只关窗，不改 Repair_Check（下次启动游戏还弹）。
        addRenderableWidget(Button.builder(Component.literal("确定"), button -> onClose())
                .bounds(left + (BTN_W + BTN_GAP) * 2, rowY, BTN_W, BTN_H)
                .build());
    }

    /**
     * 在本机浏览器打开补丁下载网站。
     *
     * <p>★ 走原版 {@link Util#getPlatform()}（与「打开文件夹」按钮同一个入口，见
     * {@link FolderOpenButton}），不自己起 {@code ProcessBuilder} —— 各平台实现（Windows / macOS /
     * Linux 的 xdg-open 分支）原版已经处理好，模组再写一套只会多一套坑。
     *
     * <p>失败**不弹二次窗、不改文案**：只写日志（点一下没反应玩家会自己再点，
     * 而弹一个「打开失败」的窗反而是用户没要的界面）。
     */
    private void openWebsite() {
        String url = "https://" + RepairNotice.WEBSITE;
        try {
            Util.getPlatform().openUri(URI.create(url));
            LOGGER.info("[SmoothLift/Repair] 已在浏览器打开 {}", url);
        } catch (RuntimeException e) {
            // ★ 不弹二次窗、不改文案：点一下没反应玩家会自己再点，
            //   而弹一个「打开失败」的窗反而是用户没要的界面（只记日志）。
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
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 【1.20.1】这里的 renderBackground 只有 (GuiGraphics) 一个参数（1.20.4 才有四参重载）。
        this.renderBackground(guiGraphics);
        int px = panelX();
        int py = panelY();
        // 面板：先描一圈深色边，再填半透明底 —— 与模组其它界面（SyncPopupScreen）同一套观感。
        guiGraphics.fill(px - 2, py - 2, px + PANEL_W + 2, py + PANEL_H + 2, 0xFF000000);
        guiGraphics.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF0101010);

        // 标题 + 两行正文（文案集中在 RepairNotice，改字只改那一处）。
        // ★ 用户点名的三行文案：
        //     未导入补丁！               ← 标题（this.title）
        //     某些功能可能被限制！        ← LINE_1
        //     补丁下载网站:mzyc.pages.dev ← LINE_2
        //   颜色：标题红、第一行白、网址灰 —— 与模组「黑底白字 + 少量强调」的观感一致。
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, py + 8, 0xFF5555);
        guiGraphics.drawCenteredString(this.font, Component.literal(RepairNotice.LINE_1),
                this.width / 2, py + 24, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal(RepairNotice.LINE_2),
                this.width / 2, py + 38, 0xA0A0A0);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 进世界时弹出来的提示窗**不暂停游戏**（与模组其它界面一致；玩家可以边看边走动，
     * 单人存档里也不会因为一个提示窗把世界冻住）。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
