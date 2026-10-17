package smooth.lift.client;

import smooth.lift.compat.ButtonBuilder;

import smooth.lift.net.SLNet;

import net.minecraft.client.Minecraft;
import smooth.lift.compat.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【09-30】石斧右键闸机打开的「闸机提示音」界面 —— 版式**照抄直梯**
 * （{@link LiftToneSetupScreen}）：一级菜单每项一个「…设置…」按钮 + 右边一个 1~1000 音量框，
 * 二级菜单是左右两列（未导入存档 / 已导入存档）。
 *
 * <h2>与直梯那套的**结构差别**（读代码前先看这一节）</h2>
 * <ul>
 *   <li>项只有 **2 个方向**：{@code in} = 进站闸机、{@code out} = 出站闸机
 *       （不是直梯的四项 up/down/open/close）；</li>
 *   <li>★★ 粒度 = **石斧右键到的那一组闸机**（不是整个维度、也不是「这一台」）：
 *       从右键那一格洪水填充出一**段**连着的闸机（{@link ZhajiChain}），
 *       同一段里功能相同的算**一组**；界面里改的每一项都只落这一组
 *       （用户原话「连着的相同功能（进站/出站）闸机为一组，可以单独调整一组闸机的音效和音量」）。
 *       ⇒ 指令（{@code /zhaji}）才是「改整个维度」，两者射程不同是**有意的**
 *       （与屏蔽门【1.20】「界面只改这一串门、只有指令改全部」同一条规矩）；</li>
 *   <li>★ 两组之间用**维度默认层**回落：本组没设过的项 ⇒ 跟维度默认
 *       （右上角「同步所有」就是「把这一组的值写进默认层」= 同步给所有闸机，与屏蔽门同义）；</li>
 *   <li>★ 没有子开关层：「不播」= 右列第 0 行，直接写素材层（闸机没有第二道闸门）；</li>
 *   <li>★ 石斧右键**任何**闸机（进站 / 出站都算）→ **一律落在「一级菜单」**（【09-30】订正：
 *       原先右键进站直接落在进站页、右键出站直接落在出站页，用户报「右键进去的是二级菜单、
 *       要按 Esc 才到一级菜单」⇒ 现在没有「按右键那一侧决定落哪一页」这回事了，
 *       两侧都在一级菜单的两行里点。构造器收的那个 {@link BlockPos} **只用来算组**、不参与落页）。</li>
 * </ul>
 *
 * <h2>两列版式的几何</h2>
 * 一律取自 {@link SoundListLayout}（唯一来源），本类不写 190 / 22 这类数字。
 */
public class ZhajiToneSetupScreen extends Screen {

    /** 【10-01】本次界面会话里是否出现过失败（退出界面时随 UI_CLOSE 上报给服务端）。 */
    private boolean uiFailed;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    // ------------------------------------------------------------------
    // 两列列表几何：全部指向 SoundListLayout（唯一来源）
    // ------------------------------------------------------------------
    private static final int ROW_H = SoundListLayout.ROW_H;
    private static final int LIST_TOP = SoundListLayout.LIST_TOP;
    private static final int COL_W = SoundListLayout.COL_W;
    private static final int ROW_BTN_W = SoundListLayout.ROW_BTN_W;
    private static final int ROW_NAME_W = SoundListLayout.ROW_NAME_W;
    private static final int ROW_NAME_CHARS = SoundListLayout.ROW_NAME_CHARS;
    private static final int BTN_Y = SoundListLayout.BTN_Y;

    /** 右列**特殊行**的行数（不播 / 默认）⇒ 已存入第 i 条落在第 {@code i + 2} 行。 */
    private static final int RIGHT_SPECIAL_ROWS = 2;

    // ------------------------------------------------------------------
    // 固定区
    // ------------------------------------------------------------------
    private static final int BOTTOM_RESERVE_LIST = SoundListLayout.BOTTOM_RESERVE_LIST;
    private static final int BOTTOM_RESERVE = 108;
    private static final int STATUS_Y = -96;
    private static final int BTN_W = 200;
    private static final int MAIN_GAP = 10;
    private static final int MAIN_LABEL_GAP = 6;
    private static final int MAIN_INPUT_W = 70;
    private static final String VOLUME_LABEL = "音量";

    /** 两个方向（下标 0 = 进站、1 = 出站）；二级页号 = 下标 + 1。 */
    private static final String[] PAGES = {"in", "out"};

    private final List<String> stored = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();
    private int scroll;
    private int maxScroll;
    private int listBottom;
    private int listTop = LIST_TOP;
    private String statusText;

    /** 当前页：0 = 一级菜单（两个「…设置…」按钮 + 各自的音量框）；1 = 进站页；2 = 出站页。 */
    private int page;

    /** 一级菜单上**逐侧**的音量输入框（键 = in/out）。 */
    private final Map<String, EditBox> mainVolumeInputs = new LinkedHashMap<>();

    private static volatile ZhajiToneSetupScreen OPEN;

    /** 石斧右键到的那一格（只用来算「这一组闸机」是谁；界面本身与它无关）。 */
    private final net.minecraft.core.BlockPos pos;

    /** 这一组闸机的组锚点（{@link ZhajiChain#anchorOf}）；算不出来 → {@code ZHAJI_GROUP_NONE}。 */
    private final long groupKey;

    /**
     * ★ 构造器**只收坐标、不收方向**：石斧右键闸机一律落在「一级菜单」（{@code page = 0}）。
     *
     * <p>【09-30 订正】这里原先收一个 {@code which}（右键到的那一侧）并据此落到进站 / 出站页
     * ⇒ 用户报「右键闸机进去的是二级菜单，按 Esc 才到一级菜单」。现在**删掉那个分支**：
     * 落页不再取决于右键到哪一侧，谁也写不出「落到二级页」的调用。
     *
     * <p>【09-30 续】改成收 {@link net.minecraft.core.BlockPos} 是为了算**组**：右键这一格所在的
     * 那一段连着闸机里、功能相同的那些就是本界面要改的射程（见类注释）。
     * 坐标算不算得出组都不影响落页 —— 算不出（世界没加载 / 那一格不是闸机）时退化成
     * 「编辑维度默认层」，与旧行为一致。
     *
     * @param pos 石斧右键到的那一格闸机
     */
    public ZhajiToneSetupScreen(net.minecraft.core.BlockPos pos) {
        super(new net.minecraft.network.chat.TextComponent("闸机提示音设置"));
        this.pos = pos == null ? null : pos.immutable();
        this.page = 0;
        Level level = Minecraft.getInstance().level;
        this.groupKey = (level == null || this.pos == null)
                ? EscalatorSpeedManager.ZHAJI_GROUP_NONE
                : ZhajiChain.anchorOf(level, this.pos);
    }

    /** 服务端同步回来时刷新列表（界面还开着的情况下）。 */
    public static void notifyToneDataChanged() {
        ZhajiToneSetupScreen s = OPEN;
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
        stored.clear();
        pending.clear();
        if (mc.level != null && page > 0) {
            // 音频隔离：每个方向只列自己分类（MBM_Audio/zhaji/in|out）的待导入 / 已存入。
            String category = categoryFor(PAGES[page - 1]);
            stored.addAll(EscalatorSpeedManager.getClientAudioLibraryKeys(mc.level, category));
            pending.addAll(EscalatorSpeedManager.getClientFolderAudioKeys(mc.level, category));
        }
        if (mc.level == null && page > 0) {
            LOGGER.debug("[SmoothLift/Zhaji] 界面在没有世界时打开列表页，列表按空处理");
        }
        Collections.sort(stored);
        Collections.sort(pending);
        buildUi();
    }

    /** 关闭界面 = 落地一级菜单那两个音量框（与直梯同一套「Esc 即应用」）。 */
    @Override
    public void onClose() {
        if (page > 0) {
            // 二级页按 Esc = 返回一级菜单（不落地：这一层没有输入框）。
            page = 0;
            scroll = 0;
            init();
        } else {
            applyMainVolumes();
            if (OPEN == this) {
                OPEN = null;
            }
            // 【10-01】真正「退出界面」（一级菜单）：让服务端把本次界面会话的结果回一条。
            SmoothLiftClient.sendUiClose(!uiFailed);
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void buildUi() {
        clearWidgets();
        boolean listPage = page >= 1;
        int reserve = listPage ? BOTTOM_RESERVE_LIST : BOTTOM_RESERVE;
        listBottom = Math.max(LIST_TOP + ROW_H, this.height - reserve);
        listTop = listPage ? LIST_TOP + ROW_H : LIST_TOP;

        if (page == 0) {
            buildMainPage();
        } else {
            buildTonePage(PAGES[page - 1]);
        }

        // 右上角「同步所有」：射程跟着当前页走（0 = 两侧一起；1/2 = 只有这一侧），
        //   与服务端 SYNC_ZHAJI_WHICH 同序。
        //   ★【09-30 续】key 传**本组锚点** —— 服务端会把本组此刻生效的那一套写进**维度默认层**
        //   （= 推给所有闸机），与屏蔽门 / 直梯的同步弹窗同一套语义。
        addRenderableWidget(SyncPopupScreen.syncButton(this, "zhaji", page, groupKey,
                page > 0 ? null : this::applyMainVolumes));
        // 右上角「打开文件夹」：二级页开自己那一侧的分类子文件夹，一级菜单开 zhaji/ 这一组。
        addRenderableWidget(FolderOpenButton.of(this, FolderOpenButton.audioPath(
                page > 0 ? categoryFor(PAGES[page - 1]) : EscalatorSpeedManager.GROUP_ZHAJI)));
    }

    // ------------------------------------------------------------------
    // 一级菜单：两个「…设置…」按钮 + 各自右边的音量框（照抄直梯版式）
    // ------------------------------------------------------------------
    private void buildMainPage() {
        int cx = this.width / 2;
        addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("返回"), button -> onClose())
                .bounds(cx - 100, this.height + BTN_Y, 96, 20)
                .build());
        addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("刷新"), button -> {
            SLNet.sendToServer(SmoothLift.REQUEST_SYNC_CHANNEL, SLNet.emptyBuf());
            setStatus("已请求刷新，同步回来后自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        mainVolumeInputs.clear();
        int y = LIST_TOP + 10;
        for (int i = 0; i < PAGES.length; i++) {
            String which = PAGES[i];
            final int targetPage = i + 1;
            addRenderableWidget(ButtonBuilder.builder(
                            new net.minecraft.network.chat.TextComponent(toneTitle(which) + "设置…"), button -> {
                        // 跳页会重建控件：先把音量落地，否则刚填的字随控件一起没了。
                        applyMainVolumes();
                        page = targetPage;
                        scroll = 0;
                        init();
                    })
                    .bounds(mainRowStartX(), y, BTN_W, 20)
                    .build());

            EditBox box = new EditBox(this.font, mainRowInputX(), y, MAIN_INPUT_W, 20,
                    new net.minecraft.network.chat.TextComponent(toneTitle(which) + "音量 1~1000"));
            box.setMaxLength(8);
            // ★【09-30 续】框里显示的是**本组此刻生效**的音量（本组没设过 = 维度默认那一份）——
            //   玩家看到的就是「这一组现在多大声」，改一下才写本组。
            box.setValue(String.valueOf(effectiveVolume(which)));
            addRenderableWidget(box);
            mainVolumeInputs.put(which, box);

            y += ROW_H;
        }
    }

    private int mainRowWidth() {
        return BTN_W + MAIN_GAP + this.font.width(VOLUME_LABEL) + MAIN_LABEL_GAP + MAIN_INPUT_W;
    }

    private int mainRowStartX() {
        return this.width / 2 - mainRowWidth() / 2;
    }

    private int mainRowLabelX() {
        return mainRowStartX() + BTN_W + MAIN_GAP;
    }

    private int mainRowInputX() {
        return mainRowLabelX() + this.font.width(VOLUME_LABEL) + MAIN_LABEL_GAP;
    }

    private void notifyBadInput(String why) {
        // 【10-01】不再单独往聊天框打长句：记下「本次界面会话失败过」，
        //   退出界面时由服务端统一回一条「UI执行失败」；细节留在日志里。
        uiFailed = true;
        LOGGER.warn("[SmoothLift/UI] {}", why);
    }

    /** 一级菜单两个音量框一起落地（{@code /zhajiloud in|out <音量>}），空值安全。 */
    private void applyMainVolumes() {
        for (Map.Entry<String, EditBox> e : mainVolumeInputs.entrySet()) {
            applyVolume(e.getKey(), e.getValue());
        }
    }

    private void applyVolume(String which, EditBox box) {
        if (box == null) {
            return;
        }
        Integer v = parseVolume(box.getValue());
        if (v == null) {
            notifyBadInput(toneTitle(which) + "音量必须是 1~1000 的整数（100 = 原始音量），已忽略");
            return;
        }
        if (v == effectiveVolume(which)) {
            return; // 没改，不必发包
        }
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(which, 32);
        // 【09-30 续】第二格 = 本组锚点（服务端据此写「这一组」的音量；哨兵 = 维度默认层）。
        buf.writeLong(groupKey);
        buf.writeVarInt(v);
        SLNet.sendToServer(SmoothLift.SET_ZHAJI_VOLUME_CHANNEL, buf);
        setStatus((inGroup() ? "已请求把本组" : "已请求把") + toneTitle(which) + "的提示音音量设为 " + v);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientZhajiGroupVolumeLocal(level.dimension(), groupKey, which, v);
        }
    }

    // ------------------------------------------------------------------
    // 二级页：左右两列列表
    // ------------------------------------------------------------------
    private void buildTonePage(String which) {
        int cx = this.width / 2;
        addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("返回"), button -> {
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("刷新"), button -> {
            SLNet.sendToServer(SmoothLift.REQUEST_SYNC_CHANNEL, SLNet.emptyBuf());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        rebuildScroll();

        // 左列：本分类文件夹里**还没入库**的 OGG —— 点一下 = 导入存档并设为这一侧。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent(truncate(id, 22)),
                            button -> importPending(which, id))
                    .bounds(SoundListLayout.leftColX(this.width), y, COL_W, 20)
                    .build());
        }

        String cur = currentValue(which);

        // 右列第 0 行：不播（只有「选用」）。
        int y0 = rowY(0);
        if (fullyVisible(y0)) {
            boolean offNow = EscalatorSpeedData.ZHAJI_TONE_OFF.equals(cur);
            addRenderableWidget(ButtonBuilder.builder(
                            new net.minecraft.network.chat.TextComponent((offNow ? "✓" : "") + "不播"),
                            button -> pick(which, EscalatorSpeedData.ZHAJI_TONE_OFF))
                    .bounds(SoundListLayout.rightColX(this.width), y0, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("选用"),
                            button -> pick(which, EscalatorSpeedData.ZHAJI_TONE_OFF))
                    .bounds(SoundListLayout.rowPickX(this.width), y0, ROW_BTN_W, 20)
                    .build());
        }

        // 右列第 1 行：默认（跟 MTR 内置音）。
        int y1 = rowY(1);
        if (fullyVisible(y1)) {
            boolean isDefault = EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(cur);
            addRenderableWidget(ButtonBuilder.builder(
                            new net.minecraft.network.chat.TextComponent((isDefault ? "✓" : "") + "默认"),
                            button -> pick(which, EscalatorSpeedData.ZHAJI_TONE_DEFAULT))
                    .bounds(SoundListLayout.rightColX(this.width), y1, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("选用"),
                            button -> pick(which, EscalatorSpeedData.ZHAJI_TONE_DEFAULT))
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
            boolean isCurrent = !EscalatorSpeedData.ZHAJI_TONE_OFF.equals(cur)
                    && !EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(cur) && id.equals(cur);
            addRenderableWidget(ButtonBuilder.builder(
                            new net.minecraft.network.chat.TextComponent((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> pick(which, id))
                    .bounds(SoundListLayout.rightColX(this.width), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("选用"), button -> pick(which, id))
                    .bounds(SoundListLayout.rowPickX(this.width), y, ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(ButtonBuilder.builder(new net.minecraft.network.chat.TextComponent("删除"), button -> deleteStored(which, id))
                    .bounds(SoundListLayout.rowDeleteX(this.width), y, ROW_BTN_W, 20)
                    .build());
        }
    }

    private void rebuildScroll() {
        int rowCount = listRowCount();
        int total = rowCount * ROW_H;
        int visible = Math.max(ROW_H, listBottom - listTop);
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private int listRowCount() {
        return Math.max(pending.size(), stored.size() + RIGHT_SPECIAL_ROWS);
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /** 二级页对应的音频分类（文件夹 = MBM_Audio/zhaji/<方向>）。 */
    private static String categoryFor(String which) {
        return EscalatorSpeedManager.zhajiToneCategory(which);
    }

    /**
     * 这一侧在**本组**里的原始取值（界面拿它打 ✓）。
     *
     * <p>★ 用「本组的原始值」而不是「生效值」打 ✓：本组没设过时该显示「默认」这一行被选中，
     * 而不是显示维度默认层恰好是什么 —— 否则玩家在同一页面上看到的 ✓ 与「他现在改的是哪一层」不一致
     * （屏蔽门那几个二级页也是这个口径）。生效值另有 {@link #effectiveValue} 供状态行用。
     */
    private String currentValue(String which) {
        Level level = mcLevel();
        if (level == null) {
            return EscalatorSpeedData.ZHAJI_TONE_DEFAULT;
        }
        if (!inGroup()) {
            return EscalatorSpeedManager.getZhajiToneAudio(level, which);
        }
        return EscalatorSpeedManager.clientZhajiGroupRecord(level, groupKey)
                .audioFor(EscalatorSpeedManager.zhajiWhich(which));
    }

    /** 本组这一侧此刻**生效**的素材（本组设过 → 本组；否则 → 维度默认层）。 */
    private String effectiveValue(String which) {
        Level level = mcLevel();
        if (level == null) {
            return EscalatorSpeedData.ZHAJI_TONE_DEFAULT;
        }
        return EscalatorSpeedManager.getZhajiToneAudio(level, which, groupKey);
    }

    /** 本组这一侧此刻**生效**的音量（本组记过 → 本组；否则 → 维度默认层）。 */
    private int effectiveVolume(String which) {
        Level level = mcLevel();
        if (level == null) {
            return EscalatorSpeedData.DEFAULT_ZHAJI_VOLUME;
        }
        return EscalatorSpeedManager.getZhajiToneVolume(level, which, groupKey);
    }

    /** 有没有算出「这一组」（算不出 = 退化成编辑维度默认层，旧行为）。 */
    private boolean inGroup() {
        return groupKey != EscalatorSpeedManager.ZHAJI_GROUP_NONE;
    }

    /** 状态行后缀：本组有没有单独设置过（让玩家知道「下面这些值是本组的还是跟默认的」）。 */
    private String groupNote() {
        if (!inGroup()) {
            return "";
        }
        Level level = mcLevel();
        boolean empty = level == null
                || EscalatorSpeedManager.clientZhajiGroupRecord(level, groupKey).isEmpty();
        return empty ? "　｜　本组未单独设置" : "　｜　本组已单独设置";
    }

    /** 点左列某一行：从文件夹导入并设为本组的这一侧。 */
    private void importPending(String which, String audioId) {
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(which, 32);
        // 【09-30 续】第二格 = 本组锚点（服务端据此把这段素材绑到「这一组」）。
        buf.writeLong(groupKey);
        buf.writeUtf(audioId, 128);
        SLNet.sendToServer(SmoothLift.IMPORT_FOLDER_ZHAJI_TONE_CHANNEL, buf);
        setStatus("正在从文件夹导入并设为" + (inGroup() ? "本组" : "") + toneTitle(which) + "：" + truncate(audioId, 16));
    }

    /** 点右列某一行：把**本组**这一侧设为 {@code audioId}（不播 / 默认 / 某段已存入的音频）。 */
    private void pick(String which, String audioId) {
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(which, 32);
        buf.writeLong(groupKey);
        buf.writeUtf(audioId, 128);
        SLNet.sendToServer(SmoothLift.SET_ZHAJI_TONE_CHANNEL, buf);
        setStatus("已选择" + (inGroup() ? "本组" : "") + toneTitle(which) + "：" + audioLabel(audioId));
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientZhajiGroupToneLocal(level.dimension(), groupKey, which, audioId);
        }
        init();
    }

    /** 从存档移除一段**本页分类**的音频（服务端会同时解绑引用它的那一侧）。 */
    private void deleteStored(String which, String id) {
        FriendlyByteBuf buf = SLNet.buf();
        buf.writeUtf(categoryFor(which), 64);
        buf.writeUtf(id, 128);
        SLNet.sendToServer(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(id);
        setStatus("已请求从存档删除：" + truncate(id, 16));
        init();
    }

    private Level mcLevel() {
        return Minecraft.getInstance().level;
    }

    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> this.statusText = text);
    }

    private int rowY(int index) {
        return SoundListLayout.rowY(listTop, index, scroll);
    }

    private boolean fullyVisible(int y) {
        return y >= listTop && y + 20 <= listBottom;
    }

    /** 解析输入框里的音量（1~1000 整数）；非法 → null。 */
    private Integer parseVolume(String s) {
        if (s == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            return EscalatorSpeedData.clampZhajiVolume(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (page > 0 && maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public void render(com.mojang.blaze3d.vertex.PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        GuiGraphics guiGraphics = new GuiGraphics(poseStack);
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        if (page == 0) {
            renderMainPage(guiGraphics);
        } else {
            renderTonePage(guiGraphics);
        }
    }

    /** 一级菜单：只画每行那个「音量」小标签 + 状态行（与直梯一级页同一版式）。 */
    private void renderMainPage(GuiGraphics guiGraphics) {
        int y = LIST_TOP + 10;
        for (int i = 0; i < PAGES.length; i++) {
            guiGraphics.drawString(this.font, new net.minecraft.network.chat.TextComponent(VOLUME_LABEL),
                    mainRowLabelX(), y + 6, 0xFFFFFF, false);
            y += ROW_H;
        }
        // ★【1.30】用户点名「所有 UI 里按完按钮出现在下方的黄色和白色小字全部删掉」⇒
        //   这一页原来那行灰字常驻信息（「生效 进站：…　出站：…」）+ 黄字操作反馈**都不再画**。
        //   setStatus()/statusText 与 effectiveValue/groupNote 仍保留（同步与聊天回执照旧）。
    }

    /** 二级页：两列 + 中间一条竖线 + 表头（与直梯/屏蔽门那几页同一版式）。 */
    private void renderTonePage(GuiGraphics guiGraphics) {
        String which = PAGES[page - 1];
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font, new net.minecraft.network.chat.TextComponent(toneTitle(which) + "设置"),
                cx, 22, 0xFFFFFF);

        guiGraphics.drawCenteredString(this.font, new net.minecraft.network.chat.TextComponent("未导入存档"),
                SoundListLayout.leftColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, new net.minecraft.network.chat.TextComponent("已导入存档"),
                SoundListLayout.rightColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);

        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);

        int rowCount = listRowCount();
        if (maxScroll > 0 && rowCount > 0) {
            int barX = SoundListLayout.scrollBarX(this.width);
            int trackTop = listTop;
            int trackH = Math.max(ROW_H, listBottom - listTop);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rowCount * ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        // ★【1.30】用户点名「按完按钮出现在下方的黄色和白色小字全部删掉」⇒ 黄字反馈也不再画。
    }

    /** 方向 → 界面用名（与命令回执 {@code zhajiLabel} 同一套叫法）。 */
    private static String toneTitle(String which) {
        return EscalatorSpeedManager.zhajiLabel(which);
    }

    /** 素材 id → 状态行里显示的名字。 */
    private static String audioLabel(String id) {
        if (EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(id)) {
            return "默认";
        }
        if (EscalatorSpeedData.ZHAJI_TONE_OFF.equals(id)) {
            return "不播";
        }
        return id;
    }

    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }
}
