package smooth.lift.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import smooth.lift.SmoothLift;
import smooth.lift.network.Packets;
import smooth.lift.network.SyncSettingsPacket;

import java.util.List;

/**
 * 【1.55】「同步」弹窗 —— 三个界面右上角那个「同步所有」按钮按下去之后弹出来的小面板。
 *
 * <p>三个按钮：
 * <ul>
 *   <li><b>同步所有</b>：把当前这一项的设置写成**默认** ⇒ 没单独设置过的项都跟着变，
 *       单独设置过的项保留自己的值。等同**不带 {@code -f}** 的指令。</li>
 *   <li><b>强制同步</b>：再额外清掉这一项的单独设置 ⇒ 连修改过的一起变成同一个值。
 *       等同**带 {@code -f}** 的指令。</li>
 *   <li><b>取消</b>：什么都不做，回原来的界面。</li>
 * </ul>
 *
 * <p>★ 按 ESC 与「取消」同义（{@code Screen.keyPressed} 里 ESC 走的就是 {@link #onClose()}）。
 *
 * <p>★ 弹窗是**另开一个 Screen**、而不是在原界面上叠一层：叠一层要处理
 * 「底层控件还吃鼠标点击」这件麻烦事（画上去盖住 ≠ 拦住点击）。代价是原界面会被
 * `setScreen` 重建一次 —— 所以打开弹窗前先把输入框落地
 * （见 {@link #syncButton} 的 {@code beforeOpen}），否则刚填的数字会丢。
 *
 * <p>★ `returnTo` 传的是**原来那个 Screen 实例**：`setScreen` 回来时它的 {@code init()}
 * 会重新跑一遍、但 {@code page} 之类的字段还在 ⇒ 二级菜单返回后仍停在二级菜单。
 *
 * <p>界面里**不写任何说明小字**，只有标题 + 三个按钮（与「预设选择」界面同一套口味）。
 * 【10-04 修 3】同步结果不再打长句：成功 / 失败并进本次界面会话的结果，
 * 退出界面时由服务端统一回一条「UI执行成功 / UI执行失败」。
 */
public class SyncPopupScreen extends Screen {

    /** 三个按钮的宽度：与模组其它界面的输入框同宽。 */
    private static final int BTN_W = 200;
    /**
     * 右上角那个入口按钮的宽度：刚好放下「同步所有」四个字 + 余量。
     *
     * <p>★【09-29】改成 {@code public}：右上角现在是**一排**按钮 —— 「打开文件夹」紧挨在
     * 「同步所有」左边（见 {@link FolderOpenButton}）。它必须按同一套宽度 / 边距 / 间距往外推，
     * 所以整排按钮的几何（下面五个常量）都留在这里、由两处共用，谁也别再自己写魔数。
     */
    public static final int ENTRY_W = 76;
    /** 【09-29】右上角那排按钮距窗口右边缘的距离。 */
    public static final int ENTRY_MARGIN = 4;
    /** 【09-29】右上角那排按钮之间的水平间距（「打开文件夹」→「同步所有」）。 */
    public static final int ENTRY_GAP = 4;
    /** 【09-29】右上角那排按钮的 y 与高度（与旧的 6 / 20 逐值相同，只是抽成了常量）。 */
    public static final int ENTRY_Y = 6;
    public static final int ENTRY_H = 20;
    /** 面板尺寸。 */
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 112;

    /** 关掉弹窗之后回到哪个界面。 */
    private final Screen returnTo;
    /** 同步的域：{@code esc} 扶梯 / {@code lift} 直梯 / {@code psd} 屏蔽门（通用选项模式为 null）。 */
    private final String domain;
    /** 射程：0 = 一级菜单，≥1 = 二级菜单的子页编号（通用选项模式不用）。 */
    private final int scope;
    /** 当前这一项的身份：扶梯 = BlockPos.asLong，直梯 = 竖井列 key，屏蔽门 = runKey（通用选项模式不用）。 */
    private final long key;
    /**
     * 【10-04 修 2】PSD 主界面「关门后等待发车」那一项的**门串锚点**（其它域恒为 0）。
     * 服务端读「这一串此刻生效的发车等待」要用它 —— key（车站级 runKey）在门串级表里查不到。
     */
    private final long extraKey;

    /** 【09-30 续 2】弹窗里的选项（「取消」不在这里 —— init 统一补在最后）。 */
    private final List<Option> options;

    /**
     * 【09-30 续 2】弹窗里的一个选项：按钮文字 + 点下去做的事（「取消」由 init 统一补在最后）。
     */
    public record Option(String label, Runnable action) {
    }

    /**
     * 【09-30 续 2】**通用选项**弹窗（mbmhelp 的「兼容模式」用）：按钮 = 调用方给的几条 +
     * 统一补在最后的「取消」，面板观感与「同步」弹窗完全同一套。
     */
    public SyncPopupScreen(Screen returnTo, String title, List<Option> options) {
        super(Component.literal(title));
        this.returnTo = returnTo;
        this.domain = null;
        this.scope = 0;
        this.key = 0;
        this.extraKey = 0;
        this.options = List.copyOf(options);
    }

    public SyncPopupScreen(Screen returnTo, String domain, int scope, long key) {
        this(returnTo, domain, scope, key, 0L);
    }

    /** 【10-04 修 2】带 {@code extraKey}（PSD 主页 = 门串锚点；其它域传 0）。 */
    public SyncPopupScreen(Screen returnTo, String domain, int scope, long key, long extraKey) {
        super(Component.literal("同步"));
        this.returnTo = returnTo;
        this.domain = domain;
        this.scope = scope;
        this.key = key;
        this.extraKey = extraKey;
        this.options = List.of(
                new Option("同步所有", () -> send(false)),
                new Option("强制同步", () -> send(true)));
    }

    /**
     * 【09-30 续 2】造一个**右上角通用入口按钮**（与 {@link #syncButton} 同尺寸同位置：
     * 76×20、贴右上角、距边缘 {@link #ENTRY_MARGIN}），点开一个通用选项弹窗。
     *
     * @param host       宿主界面；也作为弹窗的返回目标
     * @param label      按钮文字（mbmhelp 用「兼容模式」）
     * @param title      弹窗标题
     * @param options    弹窗里的选项（「取消」会自动补在最后）
     * @param beforeOpen 打开弹窗**之前**要做的事（宿主会被重建，待落地的输入框在这里收）
     */
    public static Button entryButton(Screen host, String label, String title,
                                     List<Option> options, Runnable beforeOpen) {
        return Button.builder(Component.literal(label), button -> {
                    if (beforeOpen != null) {
                        beforeOpen.run();
                    }
                    Minecraft.getInstance().setScreen(new SyncPopupScreen(host, title, options));
                })
                .bounds(host.width - ENTRY_MARGIN - ENTRY_W, ENTRY_Y, ENTRY_W, ENTRY_H)
                .build();
    }

    /**
     * 【1.55】造出界面右上角那个「同步所有」入口按钮。
     *
     * <p>放在 {@code (width - ENTRY_MARGIN - ENTRY_W, ENTRY_Y)} —— 模组现有的控件全在中间与底部，
     * 右上角是空的。【09-29】「打开文件夹」占它左边的位置，见 {@link FolderOpenButton}。
     *
     * @param host       宿主界面；也作为弹窗的返回目标
     * @param domain     同步的域：{@code esc} / {@code lift} / {@code psd}
     * @param scope      射程：0 = 一级菜单，≥1 = 二级菜单子页编号
     * @param key        当前这一项的身份
     * @param beforeOpen 打开弹窗**之前**要做的事：把当前页输入框落地。
     *                   传 null 表示这一页没有待落地的输入框。
     *                   ★ 必须落地 —— 弹窗会把宿主界面重建，没落地的手填值会丢，
     *                   而且服务端读「当前这一项的值」时也读不到它。
     */
    public static Button syncButton(Screen host, String domain, int scope, long key, Runnable beforeOpen) {
        return syncButton(host, domain, scope, key, 0L, beforeOpen);
    }

    /** 【10-04 修 2】带 {@code extraKey} 的版本（PSD 主页 = 门串锚点 runAnchor；其它域传 0）。 */
    public static Button syncButton(Screen host, String domain, int scope, long key, long extraKey,
                                    Runnable beforeOpen) {
        return Button.builder(Component.literal("同步所有"), button -> {
                    if (beforeOpen != null) {
                        beforeOpen.run();
                    }
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new SyncPopupScreen(host, domain, scope, key, extraKey));
                })
                .bounds(host.width - ENTRY_MARGIN - ENTRY_W, ENTRY_Y, ENTRY_W, ENTRY_H)
                .build();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = panelY() + 30;
        for (Option option : options) {
            addRenderableWidget(Button.builder(Component.literal(option.label()),
                            button -> option.action().run())
                    .bounds(cx - BTN_W / 2, y, BTN_W, 20)
                    .build());
            y += 26;
        }
        // 「取消」统一补在最后（与 ESC 同义：什么都不做，回原来的界面）。
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(cx - BTN_W / 2, y, BTN_W, 20)
                .build());
    }

    /** buf 顺序必须与服务端 {@code SYNC_SETTINGS_CHANNEL} 的读序一致。 */
    private void send(boolean force) {
        // 【Forge 移植注】Fabric 在这里手写 buf 发 SYNC_SETTINGS_CHANNEL；
        //   Forge 走 SyncSettingsPacket（encode 顺序相同：domain(utf16)→scope(varInt)→force(boolean)
        //   →key(long)→【10-04 修 2】extraKey(long)）。
        Packets.CHANNEL.sendToServer(new SyncSettingsPacket(domain, scope, force, key, extraKey));
        onClose();
    }

    /** 「取消」与 ESC 都走这里：什么都不发，回原来的界面。 */
    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(returnTo == null ? null : returnTo);
    }

    private int panelX() {
        return (this.width - PANEL_W) / 2;
    }

    private int panelY() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 【1.20.1 API】Screen.renderBackground 只有 1 个参数（1.20.4 才是 4 个参数）。
        this.renderBackground(guiGraphics);
        int px = panelX();
        int py = panelY();
        // 面板：先描一圈深色边，再填半透明底 —— 与模组其它界面的「黑底白字」一致
        guiGraphics.fill(px - 2, py - 2, px + PANEL_W + 2, py + PANEL_H + 2, 0xFF000000);
        guiGraphics.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF0101010);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, py + 10, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
