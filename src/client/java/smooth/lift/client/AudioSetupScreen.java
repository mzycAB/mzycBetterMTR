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
 * 石斧界面里的「选择扶梯音乐」子界面。
 *
 * <h2>★★【09-27 六改】右列改为「不播在上、默认在下」，并且删掉内置音频行</h2>
 *
 * <ul>
 *   <li>★ 用户点名「**所有ui里的「不播」按钮永远放在「默认」按钮上面**」⇒
 *       右列第 0 行 = {@link #OFF_ROW_LABEL}「不播」、第 1 行 = {@link #DEFAULT_ROW_LABEL}「默认」
 *       （上一版是反的：默认在 0、不播在 1）。</li>
 *   <li>★ 用户点名「扶梯ui里的「声音设置」里的「默认」和「内置地铁自动扶梯」是一个东西，
 *       删除「内置地铁自动扶梯」按钮」⇒ 右列**不再列模组内置音频**。
 *       理由站得住：模组只有一条内置底噪（{@code builtin:subway_escalator} = 内置 · 地铁自动扶梯），
 *       而「默认」那一层（{@code /futimusic default}）指的就是它 —— 两行点下去结果一样。
 *       ⇒ 想要那条声音就点「默认」；本类因此连 {@code builtin} 字段都删了。</li>
 * </ul>
 *
 * <pre>
 *   未导入存档          ┆   已导入存档
 *   &lt;ogg 名&gt;[全宽]      ┆   [不播][选用]              ← 右列第 0 行（特殊项，只有「选用」）
 *                      ┆   [默认][选用]              ← 右列第 1 行（同上）
 *                      ┆   [名字][选用][删除]        ← 已存入第 i 条落在第 i + {@link #RIGHT_SPECIAL_ROWS} 行
 *                      状态行（当前绑定 / 反馈）
 *                      返回 / 刷新
 * </pre>
 *
 * <h2>版式（与直梯 / 屏蔽门二级菜单同一款左右两列）</h2>
 * <ul>
 *   <li>★ 几何**全部**指向 {@link SoundListLayout}（本类只留别名）—— 与屏蔽门 / 直梯 / 列车
 *       共用同一份数字，别再在本类里写 190 / 22；</li>
 *   <li>★ 底部预留取 {@link SoundListLayout#BOTTOM_RESERVE_LIST}（44）⇒ 240 px 高的画布下
 *       可见 **6** 行（与直梯二级页同一个口径）；</li>
 *   <li>★ 原来底部那只「解绑此扶梯」按钮**升级成右列第 1 行**（「默认」+「选用」）——
 *       语义没变，仍是「清掉这条扶梯的单独绑定、回维度默认层」。</li>
 * </ul>
 *
 * <h2>左列 / 右列各是什么</h2>
 * <ul>
 *   <li><b>左列 = 未导入存档</b>：存档文件夹 {@code MBM_Audio/futi/music} 里的 OGG
 *       （【1.28】只列扶梯底噪这一个分类）。点一下 = **导入存档并绑定**（删原文件仍可播）。</li>
 *   <li><b>右列 = 已导入存档</b>：第 0 行「不播」（这条扶梯静音）、第 1 行「默认」（回维度默认层）；
 *       再往后是已存入存档的音频（点名字 / 点「选用」= 绑定，点「删除」= 从存档移除）。</li>
 * </ul>
 *
 * <p>行数可能超过一屏，支持鼠标滚轮滚动；列表右侧有滚动条。
 * 界面在按钮点击后保持打开，只在按 ESC 或「返回」时回到设置界面。
 */
public class AudioSetupScreen extends Screen {

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

    /** 右列**前 2 行**是特殊项（第 0 行「不播」、第 1 行「默认」）⇒ 已存入第 i 条落在第 i + 2 行。 */
    private static final int RIGHT_SPECIAL_ROWS = 2;
    /**
     * 【09-27 六改】右列**第 0 行**的文案 = 「不播」（这条扶梯静音）。
     *
     * <p>★ 用户点名的全局规范：「所有ui里的「不播」按钮**永远放在「默认」按钮上面**」，
     * 而且「不播」永远就叫这两个字（不许写成「不播提示音」这种带尾巴的名字）。
     */
    private static final String OFF_ROW_LABEL = "不播";
    /** 右列**第 1 行**的文案：回维度默认层（与直梯 / 屏蔽门那一行同一叫法：只有「默认」两个字）。 */
    private static final String DEFAULT_ROW_LABEL = "默认";

    private final BlockPos pos;

    /** 已存入存档的音频 ID（文件名，只含扶梯底噪分类）。 */
    private final List<String> stored = new ArrayList<>();
    /** 存档文件夹里尚未入库、待导入的 OGG 文件名。 */
    private final List<String> pending = new ArrayList<>();

    /** 这条扶梯**实际使用**的音频ID（单独绑定 &gt; 默认音频）；null 表示真的不会出声。 */
    private String boundAudioId;
    /**
     * 上面那个 ID 的来源：true = 本扶梯单独绑定的；false = 来自「默认音频」层
     * （{@code /futimusic -f default}）。既影响文案，也决定右列第 1 行的 ✓ 打在谁身上。
     */
    private boolean individualAudio;
    /** 【1.9】这条扶梯当前的声音音量（1~1000；100 = 原始音量），仅用于回显。 */
    private int boundVolume = EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
    /** 最近一次操作的反馈文字；非空时在底部用黄色显示。 */
    private String statusText;

    /** 列表滚动像素偏移 / 最大可滚动量 / 列表可视区上下限。 */
    private int scroll;
    private int maxScroll;
    private int listBottom;
    /** 列表区顶部：要让出两列上方那一行表头。 */
    private int listTop = LIST_TOP;

    /** 当前打开的实例；服务端数据同步回来时由 {@link #notifyAudioDataChanged()} 回调刷新。 */
    private static volatile AudioSetupScreen OPEN;

    public AudioSetupScreen(BlockPos pos) {
        super(Component.literal("选择扶梯音乐"));
        this.pos = pos;
    }

    /**
     * 服务端音频数据同步完成（导入/绑定/删除/刷新应答）后调用：刷新列表、
     * 并清除“正在导入…”这类占位反馈，让界面回退显示真实的绑定状态。
     */
    public static void notifyAudioDataChanged() {
        AudioSetupScreen s = OPEN;
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
        // 【1.14】回显「这条扶梯实际会播什么」，而不是「本方块自己绑了什么」。
        //   effectiveAudioId = 单独绑定（链上任意方块）优先，其次默认音频；
        //   individualAudio  = 这个结果是不是「单独绑定」来的，用来区分文案。
        boundAudioId = mc.level == null ? null : EscalatorSpeedManager.effectiveAudioId(mc.level, pos);
        individualAudio = mc.level != null && EscalatorSpeedManager.hasIndividualAudio(mc.level, pos);
        boundVolume = mc.level == null
                ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME
                : EscalatorSpeedManager.getVolumeForScreen(mc.level, pos);

        stored.clear();
        pending.clear();
        if (mc.level != null) {
            // 【1.28】音频隔离：只列扶梯底噪分类（MBM_Audio/futi/music）的待导入 / 已存入。
            stored.addAll(EscalatorSpeedManager.getClientAudioLibraryKeys(mc.level, EscalatorSpeedManager.CAT_FUTI));
            pending.addAll(EscalatorSpeedManager.getClientFolderAudioKeys(mc.level, EscalatorSpeedManager.CAT_FUTI));
        }
        Collections.sort(stored);
        Collections.sort(pending);

        buildUi();
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

        // 【1.55】右上角「同步所有」：这是二级菜单，射程只算「这条扶梯的运行底噪素材」。
        //   没有输入框 ⇒ beforeOpen 传 null。
        addRenderableWidget(SyncPopupScreen.syncButton(this, "esc", SmoothLift.SYNC_ESC_AUDIO,
                pos.asLong(), null));
        // 【09-29】右上角「打开文件夹」：本页是二级菜单 ⇒ 精确开这条分类的子文件夹
        //   MBM_Audio/futi/music（把这页左列「未导入存档」的那个文件夹直接摆到玩家面前）。
        addRenderableWidget(FolderOpenButton.of(this,
                FolderOpenButton.audioPath(EscalatorSpeedManager.CAT_FUTI)));

        // 左列：本分类文件夹里**还没入库**的 OGG —— 点一下 = 导入存档并绑定。
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

        // 【六改】右列第 0 行：「不播」= 把这条扶梯的运行底噪**单独哑掉**。
        //   ★ 写进 blockAudio 的是哨兵 FUTI_AUDIO_OFF（"off"）—— 不是文件，所以只有「选用」、没有「删除」。
        //   ★ 它是「单独绑定层」，天然压过维度默认层（/futimusic default 也盖不住它）。
        //   ★ 用户点名「所有ui里的「不播」永远放在「默认」上面」⇒ 它就在第 0 行。
        int yOff = rowY(0);
        if (fullyVisible(yOff)) {
            boolean isOff = individualAudio
                    && EscalatorSpeedData.FUTI_AUDIO_OFF.equals(boundAudioId);
            addRenderableWidget(Button.builder(
                            Component.literal((isOff ? "✓" : "") + OFF_ROW_LABEL),
                            button -> playOff())
                    .bounds(SoundListLayout.rightColX(this.width), yOff, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> playOff())
                    .bounds(SoundListLayout.rowPickX(this.width), yOff, ROW_BTN_W, 20)
                    .build());
        }

        // 【六改】右列第 1 行：「默认」= 清掉这条扶梯的单独绑定、回维度默认层。
        //   （原来在底部的那只「解绑此扶梯」按钮；它不是一个音频文件 ⇒ 只有「选用」、没有「删除」。）
        int yDefault = rowY(1);
        if (fullyVisible(yDefault)) {
            boolean isDefault = !individualAudio;
            addRenderableWidget(Button.builder(
                            Component.literal((isDefault ? "✓" : "") + DEFAULT_ROW_LABEL),
                            button -> unbindAudio())
                    .bounds(SoundListLayout.rightColX(this.width), yDefault, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> unbindAudio())
                    .bounds(SoundListLayout.rowPickX(this.width), yDefault, ROW_BTN_W, 20)
                    .build());
        }

        // 【六改】这里原来是「模组内置音频」一栏（只剩一条「内置 · 地铁自动扶梯」）——
        //   用户点名删掉：它和上面那行「默认」指的是同一条声音。
        // 右列：已存入存档的音频 —— 每行三个控件：名字 / 选用 / 删除。
        int storedStart = RIGHT_SPECIAL_ROWS;
        for (int i = 0; i < stored.size(); i++) {
            int y = rowY(storedStart + i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            boolean isCurrent = individualAudio && id.equals(boundAudioId);
            addRenderableWidget(Button.builder(
                            Component.literal((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> bindAudio(id))
                    .bounds(SoundListLayout.rightColX(this.width), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> bindAudio(id))
                    .bounds(SoundListLayout.rowPickX(this.width), y, ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteAudio(id))
                    .bounds(SoundListLayout.rowDeleteX(this.width), y, ROW_BTN_W, 20)
                    .build());
        }
    }

    /** 两列行数 → 滚动范围（右列 = 特殊行 + 已存入）。 */
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
    // 【1.20.1 API】GuiEventListener.mouseScrolled 是 3 个 double（1.20.2 起才加了横向 scrollX）。
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    /** 点击一段已存档的音频：绑定到这条扶梯。 */
    private void bindAudio(String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(id, 128);
        ClientPlayNetworking.send(SmoothLift.BIND_AUDIO_CHANNEL, buf);
        setStatus("已选择：" + truncate(id, 20));
    }

    /** 右列第 1 行「默认」：清掉这条扶梯的单独绑定，改为跟随维度默认音乐。 */
    private void unbindAudio() {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        ClientPlayNetworking.send(SmoothLift.UNBIND_AUDIO_CHANNEL, buf);
        setStatus("已请求解除这条扶梯的单独绑定（回到维度默认音乐）");
    }

    /**
     * 【09-27 五改】右列第 0 行「不播」：把这条扶梯的运行底噪**单独哑掉**。
     *
     * <p>★ 发的是普通的 {@link SmoothLift#BIND_AUDIO_CHANNEL}，只是 ID 换成哨兵
     * {@link EscalatorSpeedData#FUTI_AUDIO_OFF}（{@code bindAudio} 已为它专门放行）——
     * 于是它落进「单独绑定层」，与「默认」/已存档的音频走**同一条**通路，
     * 不需要任何新的网络包或指令。
     */
    private void playOff() {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(EscalatorSpeedData.FUTI_AUDIO_OFF, 128);
        ClientPlayNetworking.send(SmoothLift.BIND_AUDIO_CHANNEL, buf);
        setStatus("已选择：不播（只让这一条扶梯静音，不受维度默认音乐影响）");
    }

    /** 删除一条已存入存档的音频（服务端会同时解绑引用它的扶梯）。本地乐观移除便于立刻看到。 */
    private void deleteAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(EscalatorSpeedManager.CAT_FUTI, 64);
        buf.writeUtf(name, 128);
        ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(name);
        buildUi();
        setStatus("已请求删除：" + truncate(name, 20));
    }

    /** 把存档文件夹里的一个文件导入到存档并绑定到这条扶梯（之后删原文件仍可播）。 */
    private void importFolderAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(name, 128);
        ClientPlayNetworking.send(SmoothLift.IMPORT_FOLDER_AUDIO_CHANNEL, buf);
        pending.remove(name);
        buildUi();
        setStatus("正在从文件夹导入并绑定：" + truncate(name, 20));
    }

    /** 回到渲染线程刷新反馈文字与绑定回显。 */
    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            this.statusText = text;
            Minecraft mc = Minecraft.getInstance();
            this.boundAudioId = mc.level == null ? null : EscalatorSpeedManager.effectiveAudioId(mc.level, pos);
            this.individualAudio = mc.level != null && EscalatorSpeedManager.hasIndividualAudio(mc.level, pos);
            this.boundVolume = mc.level == null
                    ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME
                    : EscalatorSpeedManager.getVolumeForScreen(mc.level, pos);
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
        this.renderBackground(guiGraphics);
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

        // ★【1.30】用户点名「所有 UI 里按完按钮出现在下方的黄色和白色小字全部删掉」⇒
        //   底部这一行**状态 / 反馈字整段删除**：白字常驻状态（当前绑定 / 使用默认音乐…）
        //   与黄字操作反馈都不再画。setStatus()/statusText 仍保留（改动照旧走同步与聊天回执），
        //   只是界面上不再画字。
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
