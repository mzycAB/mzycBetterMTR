package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【1.39】石斧界面里的「选择无障碍提示音」子界面 —— 与 {@link AudioSetupScreen}（选运行底噪）
 * **同一套左右两列版式**，只有语义不同：这里选的是**扶梯两端那路无障碍提示音**放什么声音。
 *
 * <h2>★★【09-27 六改】这一页是「某一头」的列表，端头由**从哪一行进来**决定</h2>
 *
 * 用户原话：
 * <ul>
 *   <li>「"提示音设置"分裂为"提示音(进入)"和"提示音(离开)"2个按钮，所以2级菜单里的
 *       "设置端头"按钮删掉」—— 一级菜单（{@link EscalatorSpeedScreen}）现在有两行入口，
 *       构造参数 {@code editIn} 直接锁定「进入扶梯 / 离开扶梯」的那一头，
 *       <b>本页因此不再有「设置端头」那一行</b>；</li>
 *   <li>「"提示音设置"里的"开关"按钮去掉，因为选择音乐就默认开启，选择不播就默认关闭了，
 *       开关没有存在的必要」—— 原来右列那只「开关：开/关」整行删除；</li>
 *   <li>「所有ui里的"不播"按钮永远放在"默认"按钮上面，"不播"按钮一直叫这个名字，
 *       类似于"不播提示音"的按钮名字都改为"不播"」—— 原来「不播提示音」改名成
 *       <b>「不播」</b>，并挪到「默认提示音」<b>上面</b>。</li>
 * </ul>
 *
 * <pre>
 *   未导入存档              ┆   已导入存档
 *   &lt;ogg 名&gt;[全宽]          ┆   [不播][选用]              ← 右列第 0 行（特殊项，只有「选用」）
 *   …                      ┆   [默认提示音][选用]         ← 右列第 1 行（同上）
 *                          ┆   [名字][选用][删除]         ← 已存入第 i 条落在第 i + {@link #RIGHT_SPECIAL_ROWS} 行
 *                          状态行（当前这一头设了什么 / 反馈）
 *                          返回 / 刷新
 * </pre>
 *
 * <ul>
 *   <li>★ 几何**全部**指向 {@link SoundListLayout}（本类只留别名）；底部预留取
 *       {@link SoundListLayout#BOTTOM_RESERVE_LIST}（44）⇒ 240 px 画布下可见 **6** 行；</li>
 *   <li>★ 原来那段**单列**排版（默认 / 不播 / 待导入 / 已存入各占一整行 + 中间两行说明文字）
 *       与底部那两行灰色小字提示**都已撤掉**；</li>
 *   <li>★ 右列前 {@value #RIGHT_SPECIAL_ROWS} 行是特殊项（不播 / 默认提示音），
 *       已存入第 i 条落在第 i + {@value #RIGHT_SPECIAL_ROWS} 行；</li>
 *   <li>★ 行内会打 ✓ 标出**当前这一头**正在用的那一项。</li>
 * </ul>
 *
 * <p>列表从上到下三段（语义未变，只是搬进了两列）：
 * <ol>
 *   <li><b>不播</b>（{@link EscalatorSpeedData#HELP_AUDIO_OFF}）：这一头单独哑掉
 *       （比 {@code /futihelp off} 更细），其它不受影响。<b>永远排在右列最顶端</b>。</li>
 *   <li><b>默认提示音（模组原声）</b>：= 模组原来的「咔啪」提示音（进扶梯端 10 次/秒、
 *       出扶梯端 1 次/秒，速率可用 {@code /futihelpspeed} 改）。</li>
 *   <li>存档文件夹 {@code MBM_Audio/futi/help} 里的 OGG（无障碍提示音自己的分类子文件夹）：
 *       点 = 导入存档并设为这条扶梯这一头的提示音（之后删原文件仍可播）；
 *       已存入存档的音频（本分类导入过的）：点名字 = 设为提示音；删除 = 从存档移除。</li>
 * </ol>
 *
 * <p>★★【1.41】界面里的每一次「选择 / 清除」都只作用在**当前正在设置的那一头**上
 * （由构造参数 {@link #editIn} 决定），与指令 {@code /futihelpmusic in|out} 是同一套数据 ——
 * 于是「进站一段、出站另一段」在界面上也能配。进 / 出两头的设置**互不影响**。
 * <p>★ 想「改回跟随默认」直接点右列顶端的「默认提示音」行即可（那一行就是默认层）。
 *
 * <p>★ 与运行底噪界面共享同一份音频库，所以「删除」会**同时**影响底噪那边的绑定。
 *
 * <p>★ 速率（{@code /futihelpspeed}）只对「默认提示音」生效：自定义音频按原速循环播
 * （素材是玩家自己的，没法按 Hz 分档）；音量与范围对所有选择都生效。
 *
 * <p>行数可能超过一屏，支持鼠标滚轮滚动；列表右侧有滚动条。
 * 界面在按钮点击后保持打开，只在按 ESC 或「返回」时回到设置界面。
 */
public class HelpAudioSetupScreen extends Screen {

    // ------------------------------------------------------------------
    // 两列列表几何：**全部**指向 SoundListLayout（唯一来源）
    // ------------------------------------------------------------------
    private static final int ROW_H = SoundListLayout.ROW_H;
    private static final int LIST_TOP = SoundListLayout.LIST_TOP;
    private static final int COL_W = SoundListLayout.COL_W;
    private static final int ROW_NAME_W = SoundListLayout.ROW_NAME_W;
    private static final int ROW_NAME_CHARS = SoundListLayout.ROW_NAME_CHARS;
    private static final int ROW_BTN_W = SoundListLayout.ROW_BTN_W;
    private static final int BTN_Y = SoundListLayout.BTN_Y;
    /** 二级页底部预留 = **与屏蔽门 / 直梯同一份常量**（44）⇒ 240 px 画布下 6 行可见。 */
    private static final int BOTTOM_RESERVE_LIST = SoundListLayout.BOTTOM_RESERVE_LIST;

    /**
     * 右列前 {@value} 行是特殊项（不播 / 默认提示音）⇒ 已存入第 i 条落在第 i + 2 行。
     * <p>★【09-27 六改】原来是 4（默认 / 不播 / 设置端头 / 开关），六改删掉「设置端头」
     * 与「开关」两行后剩 2。
     */
    private static final int RIGHT_SPECIAL_ROWS = 2;
    /** 右列第 0 / 1 行的文案（不带任何括号说明）。★ 按用户点名：不播永远在默认上面。 */
    private static final String OFF_ROW_LABEL = "不播";
    private static final String DEFAULT_ROW_LABEL = "默认提示音";

    private final BlockPos pos;

    /**
     * 【09-27 六改】当前**正在设置哪一头**：true = 进入扶梯（上客端），false = 离开扶梯（落客端）。
     *
     * <p>由一级菜单（{@link EscalatorSpeedScreen}）的两个入口在构造时锁定
     * （「提示音(进入)」⇒ true、「提示音(离开)」⇒ false），本页不再提供切换端头的行。
     */
    private final boolean editIn;

    /** 【1.28】已存入存档的音频 ID（文件名）——本分类（MBM_Audio/futi/help）已导入的。 */
    private final List<String> stored = new ArrayList<>();
    /** 本分类存档文件夹里尚未入库、待导入的 OGG 文件名。 */
    private final List<String> pending = new ArrayList<>();

    /** 这条扶梯**当前这一头实际生效**的提示音 ID（单独设置 &gt; 维度默认；永远不会是 null）。 */
    private String effectiveAudioId = EscalatorSpeedData.HELP_AUDIO_DEFAULT;
    /** 上面那个 ID 是不是「本扶梯这一头单独设置」的（否则来自默认层）。只影响文案。 */
    private boolean individualAudio;
    /** 这条扶梯的提示音音量（1~1000；100 = 原始音量），仅用于回显。 */
    private int boundVolume = EscalatorSpeedData.DEFAULT_HELP_VOLUME;
    /** 最近一次操作的反馈文字；非空时在底部用黄色显示。 */
    private String statusText;

    /** 列表滚动像素偏移 / 最大可滚动量 / 列表可视区上下限。 */
    private int scroll;
    private int maxScroll;
    private int listBottom;
    /** 列表区顶部：要让出两列上方那一行表头。 */
    private int listTop = LIST_TOP;

    /** 当前打开的实例；服务端数据同步回来时由 {@link #notifyDataChanged()} 回调刷新。 */
    private static volatile HelpAudioSetupScreen OPEN;

    /**
     * 【09-27 六改】打开「选择无障碍提示音」—— {@code editIn} 指定这一页在配**哪一头**。
     *
     * @param pos    扶梯方块位置
     * @param editIn true = 进入扶梯（上客端）、false = 离开扶梯（落客端）
     */
    public HelpAudioSetupScreen(BlockPos pos, boolean editIn) {
        super(Component.literal("选择无障碍提示音"));
        this.pos = pos;
        this.editIn = editIn;
    }

    /**
     * 服务端提示音数据同步完成（设置/导入/删除/刷新应答）后调用：刷新列表，
     * 并清除「正在导入…」这类占位反馈，让界面回退显示真实的设置状态。
     */
    public static void notifyDataChanged() {
        HelpAudioSetupScreen s = OPEN;
        if (s == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (OPEN != s) {
                return;
            }
            s.statusText = null;
            s.init();
        });
    }

    @Override
    protected void init() {
        OPEN = this;
        Minecraft mc = Minecraft.getInstance();
        // 【1.41】回显「**当前这一头**实际会播什么」，而不是「本方块自己设了什么」——
        // 单独设置优先，其次默认层。
        refreshEndState();

        stored.clear();
        pending.clear();
        if (mc.level != null) {
            // 【1.28】音频隔离：无障碍提示音只列自己分类（MBM_Audio/futi/help）的待导入 / 已存入。
            stored.addAll(EscalatorSpeedManager.getClientAudioLibraryKeys(mc.level,
                    EscalatorSpeedManager.CAT_HELP));
            pending.addAll(EscalatorSpeedManager.getClientFolderAudioKeys(mc.level,
                    EscalatorSpeedManager.CAT_HELP));
        }
        Collections.sort(stored);
        Collections.sort(pending);

        buildUi();
    }

    /**
     * 【1.41】重新回显「**当前正在设置的那一头**实际会播什么」：
     * 单独设置优先，其次维度默认层。收到同步 / 点完按钮后都会走这里。
     */
    private void refreshEndState() {
        Minecraft mc = Minecraft.getInstance();
        effectiveAudioId = mc.level == null
                ? EscalatorSpeedData.HELP_AUDIO_DEFAULT
                : EscalatorSpeedManager.effectiveHelpAudioId(mc.level, pos, editIn);
        individualAudio = mc.level != null
                && EscalatorSpeedManager.hasIndividualHelpAudio(mc.level, pos, editIn);
        boundVolume = mc.level == null
                ? EscalatorSpeedData.DEFAULT_HELP_VOLUME
                : EscalatorSpeedManager.getHelpVolume(mc.level, pos);
    }

    /** 【1.41】当前端头在界面上的短名（「进入扶梯」/「离开扶梯」）。 */
    private String endLabel() {
        return editIn ? "进入扶梯" : "离开扶梯";
    }

    /** 清空并重建控件（滚动、删除、同步回调后都会走到这里）。 */
    private void buildUi() {
        clearWidgets();
        listBottom = Math.max(LIST_TOP + ROW_H, this.height - BOTTOM_RESERVE_LIST);
        // 两列上方要让出一行表头（与屏蔽门 / 直梯那几个二级页同一套算法）。
        listTop = LIST_TOP + ROW_H;
        rebuildScroll();

        // 底部：返回 / 刷新（与屏蔽门 / 直梯二级页同一处版位）。
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(this.width / 2 - 100, this.height + BTN_Y, 96, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(this.width / 2 + 4, this.height + BTN_Y, 96, 20).build());

        // 【1.55】右上角「同步所有」：这是二级菜单，射程只算「这条扶梯的无障碍提示音素材」，
        //   进 / 出两端一起同步（服务端那一支自己会跑两端）。没有输入框 ⇒ beforeOpen 传 null。
        addRenderableWidget(SyncPopupScreen.syncButton(this, "esc", SmoothLift.SYNC_ESC_HELP_AUDIO,
                pos.asLong(), null));

        // 左列：本分类文件夹里**还没入库**的 OGG —— 点一下 = 导入存档并设为这一头的提示音。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(Button.builder(Component.literal(truncate(id, 22)),
                            button -> importFolderAudio(id))
                    .bounds(SoundListLayout.leftColX(this.width), y, COL_W, 20)
                    .build());
        }

        // 右列第 0 行：不播（仅这一头）—— 只有「选用」。
        //   ★【09-27 六改】按用户点名：「不播」永远排在「默认」上面，且名字就叫「不播」。
        int y0 = rowY(0);
        if (fullyVisible(y0)) {
            boolean now = EscalatorSpeedData.HELP_AUDIO_OFF.equals(effectiveAudioId);
            addRenderableWidget(Button.builder(
                            Component.literal((now ? "✓" : "") + OFF_ROW_LABEL),
                            button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_OFF))
                    .bounds(SoundListLayout.rightColX(this.width), y0, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_OFF))
                    .bounds(SoundListLayout.rowPickX(this.width), y0, ROW_BTN_W, 20)
                    .build());
        }

        // 右列第 1 行：默认提示音（模组原声）—— 同上，只有「选用」、没有「删除」。
        int y1 = rowY(1);
        if (fullyVisible(y1)) {
            boolean now = EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(effectiveAudioId);
            addRenderableWidget(Button.builder(
                            Component.literal((now ? "✓" : "") + DEFAULT_ROW_LABEL),
                            button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_DEFAULT))
                    .bounds(SoundListLayout.rightColX(this.width), y1, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> setHelpAudio(EscalatorSpeedData.HELP_AUDIO_DEFAULT))
                    .bounds(SoundListLayout.rowPickX(this.width), y1, ROW_BTN_W, 20)
                    .build());
        }

        // 右列：已存入存档的音频 —— 每行三个控件：名字 / 选用 / 删除。
        for (int i = 0; i < stored.size(); i++) {
            int y = rowY(i + RIGHT_SPECIAL_ROWS);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            boolean isCurrent = individualAudio && id.equals(effectiveAudioId);
            addRenderableWidget(Button.builder(
                            Component.literal((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> setHelpAudio(id))
                    .bounds(SoundListLayout.rightColX(this.width), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> setHelpAudio(id))
                    .bounds(SoundListLayout.rowPickX(this.width), y, ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteAudio(id))
                    .bounds(SoundListLayout.rowDeleteX(this.width), y, ROW_BTN_W, 20)
                    .build());
        }
    }

    /** 两列行数 → 滚动范围（右列 = 两个特殊行 + 已存入）。 */
    private void rebuildScroll() {
        maxScroll = SoundListLayout.maxScroll(listTop, listBottom,
                pending.size(), RIGHT_SPECIAL_ROWS + stored.size());
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** 第 index 行的屏幕 y（含滚动偏移）。 */
    private int rowY(int index) {
        return SoundListLayout.rowY(listTop, index, scroll);
    }

    /** 该行是否完整落在列表可视区内。 */
    private boolean fullyVisible(int y) {
        return y >= listTop && y + 20 <= listBottom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 【1.41】把一个音频（默认提示音 / 不播 / 已存档的某段）设为这条扶梯**当前这一头**的提示音。 */
    private void setHelpAudio(String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(id, 128);
        buf.writeBoolean(editIn);
        ClientPlayNetworking.send(SmoothLift.BIND_HELP_AUDIO_CHANNEL, buf);
        setStatus("已选择「" + endLabel() + "」：" + truncate(helpAudioLabel(id), 18));
    }

    /** 【1.28】从存档删除一段**本分类（MBM_Audio/futi/help）**的音频（服务端会同时解绑引用它的扶梯）。 */
    private void deleteAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(EscalatorSpeedManager.CAT_HELP, 64);
        buf.writeUtf(name, 128);
        ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(name);
        buildUi();
        setStatus("已请求删除：" + truncate(name, 20) + "（底噪与提示音的引用都会解绑）");
    }

    /** 把存档文件夹里的一个文件导入到存档并设为这条扶梯**当前这一头**的提示音（之后删原文件仍可播）。 */
    private void importFolderAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(name, 128);
        buf.writeBoolean(editIn);
        ClientPlayNetworking.send(SmoothLift.IMPORT_FOLDER_HELP_AUDIO_CHANNEL, buf);
        pending.remove(name);
        buildUi();
        setStatus("正在从文件夹导入并设为「" + endLabel() + "」的提示音：" + truncate(name, 16));
    }

    /** 回到渲染线程刷新反馈文字与设置回显。 */
    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            this.statusText = text;
            // 【1.41】回显按「当前这一头」刷新
            refreshEndState();
        });
    }

    @Override
    public void onClose() {
        if (OPEN == this) {
            OPEN = null;
        }
        Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(pos));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font, this.title, cx, 10, 0xFFFFFF);

        // 两列表头（与屏蔽门 / 直梯那几页逐字相同）
        guiGraphics.drawCenteredString(this.font, Component.literal("未导入存档"),
                SoundListLayout.leftColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("已导入存档"),
                SoundListLayout.rightColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);

        // 中间那条竖线
        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);

        // 滚动条（与屏蔽门 / 直梯那几页同一套）
        int rowCount = Math.max(pending.size(), RIGHT_SPECIAL_ROWS + stored.size());
        if (maxScroll > 0 && rowCount > 0) {
            int barX = SoundListLayout.scrollBarX(this.width);
            int trackTop = listTop;
            int trackH = Math.max(ROW_H, listBottom - listTop);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rowCount * ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        // 无反馈时这一行显示「当前正在设置的那一头」的设置状态；有反馈时换成黄色反馈文字。
        String info = statusText;
        if (info == null) {
            String prefix = individualAudio ? "单独设置：" : "跟随默认：";
            info = endLabel() + "　" + prefix + truncate(helpAudioLabel(effectiveAudioId), 18)
                    + "　音量 " + boundVolume + "%";
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), cx,
                this.height + SoundListLayout.STATUS_Y_LIST,
                statusText == null ? 0xFFFFFF : 0xFFFF55);
    }

    /**
     * 提示音 ID 在界面上的显示名（与指令 /futihelpmusic 的反馈文案保持一致）。
     *
     * <p>★ 按用户要求，默认提示音这一项只显示「默认提示音」五个字，不带括号说明
     * （与右列首行的写法统一）。
     */
    private static String helpAudioLabel(String audioId) {
        if (EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(audioId)) {
            return DEFAULT_ROW_LABEL;
        }
        if (EscalatorSpeedData.HELP_AUDIO_OFF.equals(audioId)) {
            return OFF_ROW_LABEL;
        }
        return audioId;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
