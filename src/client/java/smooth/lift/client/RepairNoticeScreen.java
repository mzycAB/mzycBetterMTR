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
import smooth.lift.RawNet;
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
                    // 【1.21.1】1.21 的 Fabric 网络只认 CustomPacketPayload：走 RawNet 适配层。
                    //   即使发包失败也要把**本地界面**收掉 —— 玩家点了就得有反应。
                    try {
                        ClientPlayNetworking.send(RawNet.c2s(SmoothLift.MBM_REPAIR_DISMISS_CHANNEL,
                                PacketByteBufs.empty()));
                        LOGGER.info("[SmoothLift/Repair] 玩家选择「不再显示」：已请服务端把存档的 MBM_settings.txt 写成 Repair_Check=0");
                    } catch (RuntimeException e) {
                        LOGGER.warn("[SmoothLift/Repair] 没能把「不再显示」发出去（服务端没装本模组？）：{}", e.toString());
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

    /**
     * ★★【1.37 修复：高斯模糊挡住文字】把 1.21 的高斯模糊**去掉**。
     *
     * <h2>原来为什么字会糊</h2>
     * 1.21 的 {@code Screen.render()} **自己就会调一次 {@code renderBackground}**
     * （反汇编第 6 行 {@code invokevirtual renderBackground}），而 1.21 的
     * {@code renderBackground} 里包含 {@code renderBlurredBackground}（对整个背景做高斯模糊）。
     * 原来的写法是「``renderBackground`` → 面板 → 文字 → `super.render()`」⇒
     * super 那一下**又画了一遍背景（含模糊）**，把刚画好的三行字整个糊掉。
     *
     * <h2>现在怎么画</h2>
     * 覆盖 {@code renderBackground}，**只铺一层半透明黑底、不做任何模糊**：
     * <ul>
     *   <li>玩家仍然看得出「这是叠在游戏画面上的弹窗」（背景压暗了），</li>
     *   <li>但字永远不会被模糊糊掉；</li>
     *   <li>面板本身是不透明深色（{@code 0xF0101010}），压在那层黑底之上，对比度更高。</li>
     * </ul>
     *
     * <p>★ 为什么不用「把文字挪到 super.render() 之后」那种修法：那会把面板画在按钮之上
     * （按钮在面板内部、{@code super.render()} 才画它们），结果是**按钮被面板盖住点不到**。
     * 从背景下手才是对症的：问题本来就出在「背景被画了两遍、第二遍带模糊」。
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
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

        // ★ super.render() 会**再调一次** renderBackground —— 但那个方法已经被上面覆盖成
        //   「只铺半透明黑、不模糊」，所以第二遍只是把黑底叠得更实一点，不会糊掉文字；
        //   同时它负责把三只按钮画出来（按钮在面板下半部分，与文字不重叠）。
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
