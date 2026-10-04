package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
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
 * 【1.45】石斧右键直梯楼层轨道打开的「直梯无障碍提示音」选择界面。
 *
 * <h2>★★【09-27 二改】音量控件搬到一级菜单；二级菜单只剩「选素材」</h2>
 *
 * 用户原话：「直梯ui应该是在一级菜单里的上楼下楼开门关门的按钮右边设置单独音量，
 * 下面的音量输入框删掉。而且二级菜单的列表中间的字和下面的音量输入框删掉，
 * 列表长度是6个选项的长度才对。」
 *
 * <h3>一级菜单（{@code page == 0}）—— 每项一个音量框，钉在各自按钮的右边</h3>
 * <pre>
 *   上楼提示音设置…  音量 [ 100 ]
 *   下楼提示音设置…  音量 [ 100 ]
 *   开门提示音设置…  音量 [ 100 ]
 *   关门提示音设置…  音量 [ 100 ]
 *   状态行（默认音量 + 四项当前素材）
 *   返回 / 刷新
 * </pre>
 * <ul>
 *   <li>★ 原来底部那个**共用默认音量**输入框**已删** —— 音量改成**逐项**给（{@code /lifthelploud up|down|open|close}）；</li>
 *   <li>输入框在按钮**右边**，中间一行「音量」小标签（不是按钮，画出来的）；</li>
 *   <li>四项一起在 {@link #applyMainVolumes()} 里落地（关闭界面 / 跳进二级页 / 关弹窗之前）。</li>
 * </ul>
 *
 * <h3>二级菜单（{@code page == 1..4}）—— 只负责选素材</h3>
 * <pre>
 *   未导入存档        ┆   已导入存档
 *   &lt;ogg 名&gt;[全宽]    ┆   [开关…][切换]        ← 占 {@link #RIGHT_SPECIAL_ROWS}=3 行
 *   …                ┆   [不播][选用] / [默认][选用]
 *                    ┆   [名字][选用][删除]    ← 已存入第 i 条落在第 i + 3 行
 *   返回 / 刷新
 * </pre>
 * <ul>
 *   <li>★ **底部那行状态字与音量输入框都删了** —— 这一页不再有输入框，也就没有「中间那行字」；</li>
 *   <li>★ **列表长度按「6 个选项」算**：底部预留取 {@link SoundListLayout#BOTTOM_RESERVE_LIST}
 *       (= 44，与屏蔽门同一份常量)，表头仍占一行 ⇒ 240 px 高的画布下恰好可见 **6** 行；</li>
 *   <li>唯一的例外：**操作反馈**（{@code statusText}）非空时仍会在 {@link SoundListLayout#STATUS_Y_LIST}
 *       那行画一下 —— 否则「点删除 / 点刷新」会**没有任何回执**。平时这一行是空的。</li>
 * </ul>
 *
 * <h2>两列版式的几何：**全部**取自 {@link SoundListLayout}（唯一来源）</h2>
 * 别再在本类里写 190 / 22 这类数字；本类只留「别名」。
 *
 * <h2>「这一条」怎么定位</h2>
 * 直梯没有跨重启稳定的 ID，所以右键到的那个楼层轨道方格的**竖井列 (X, Z)** 就是身份：
 * 同一条直梯的所有楼层轨道共享 X/Z、只有 Y 不同（{@link EscalatorSpeedManager#liftToneKey(int, int)}）。
 *
 * <p>【1.28】音频隔离：每个单项页只列自己分类的子文件夹（{@code MBM_Audio/lift/<项>}）。
 */
public class LiftToneSetupScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    // ------------------------------------------------------------------
    // 两列列表几何：**全部**指向 SoundListLayout（唯一来源）
    // ------------------------------------------------------------------
    private static final int ROW_H = SoundListLayout.ROW_H;
    private static final int LIST_TOP = SoundListLayout.LIST_TOP;
    private static final int COL_W = SoundListLayout.COL_W;
    private static final int ROW_BTN_W = SoundListLayout.ROW_BTN_W;
    private static final int ROW_BTN_GAP = SoundListLayout.ROW_BTN_GAP;
    private static final int ROW_NAME_W = SoundListLayout.ROW_NAME_W;
    private static final int ROW_NAME_CHARS = SoundListLayout.ROW_NAME_CHARS;
    private static final int BTN_Y = SoundListLayout.BTN_Y;

    /**
     * 右列**特殊行**的行数（开关 / 不播 / 默认）⇒ 已存入第 i 条落在第 {@code i + 3} 行。
     *
     * <p>★ 与屏蔽门的 {@code RIGHT_COL_FIRST_STORED_ROW}(1) 不同，只因为直梯多一行「开关」。
     */
    private static final int RIGHT_SPECIAL_ROWS = 3;

    // ------------------------------------------------------------------
    // 固定区
    // ------------------------------------------------------------------
    /**
     * 二级页底部预留 = **与屏蔽门同一份常量**（{@link SoundListLayout#BOTTOM_RESERVE_LIST}，44）。
     *
     * <p>★【09-27 二改】二级页不再有音量输入框、平时也没有状态字 ⇒ 只需给「状态行(偶发) + 返回/刷新」留地方。
     * 取 44 的**实际收益** = 列表区从 {@code height-108-62} 涨到 {@code height-44-62}：
     * 240 px 高的画布下，可见行数从 **3** 行变成 **6** 行（用户点名「列表长度是 6 个选项的长度才对」）。
     */
    private static final int BOTTOM_RESERVE_LIST = SoundListLayout.BOTTOM_RESERVE_LIST;
    /** 一级页底部预留：状态行 + 返回/刷新（输入框已经搬到按钮右边，不再占底部）。 */
    private static final int BOTTOM_RESERVE = 108;
    /** 一级页状态行的 y（相对 {@code height}）。 */
    private static final int STATUS_Y = -96;
    /** 一级页每个「…设置…」按钮的宽度。 */
    private static final int BTN_W = 200;
    /** 一级页：按钮 → 「音量」标签的空隙。 */
    private static final int MAIN_GAP = 10;
    /** 一级页：「音量」标签 → 输入框的空隙。 */
    private static final int MAIN_LABEL_GAP = 6;
    /** 一级页：每个音量输入框的宽度。 */
    private static final int MAIN_INPUT_W = 70;
    /** 一级页每行输入框左边那个标签的文字（画出来的，不是按钮）。 */
    private static final String VOLUME_LABEL = "音量";

    /** 【1.46】「开关行」的值哨兵（与真正的 audioId 区分开：它不代表任何素材）。 */
    private static final String TOGGLE_SENTINEL = "\u0000TOGGLE";

    private static final String[] PAGES = {"up", "down", "open", "close"};

    private final BlockPos pos;
    private final long key;
    private final List<String> stored = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();
    private int scroll;
    private int maxScroll;
    private int listBottom;
    /** 列表区顶部：列表页要给两列上方的表头让出一行，见 {@link #buildUi()}。 */
    private int listTop = LIST_TOP;
    private String statusText;

    /** 当前页：0 = 一级菜单（四个「…设置…」按钮 + 各自的音量框）；1/2/3/4 = up/down/open/close 二级页。 */
    private int page;

    /**
     * 【09-27 二改】一级菜单上**逐项**的音量输入框（键 = {@code up/down/open/close}）。
     *
     * <p>★ 四项**同时存在**（不像旧版那样一页只留一个输入框），
     * 所以用 map 存；落地统一走 {@link #applyMainVolumes()}。
     */
    private final Map<String, EditBox> mainVolumeInputs = new LinkedHashMap<>();

    /** 【1.28】二级页对应的音频分类（文件夹 = MBM_Audio/<分类>）。 */
    private static String categoryFor(String which) {
        return switch (which) {
            case "up" -> EscalatorSpeedManager.CAT_LIFT_UP;
            case "down" -> EscalatorSpeedManager.CAT_LIFT_DOWN;
            case "open" -> EscalatorSpeedManager.CAT_LIFT_OPEN;
            case "close" -> EscalatorSpeedManager.CAT_LIFT_CLOSE;
            default -> EscalatorSpeedManager.CAT_LIFT_OPEN;
        };
    }

    private static volatile LiftToneSetupScreen OPEN;

    public LiftToneSetupScreen(BlockPos pos) {
        super(Component.literal("选择直梯无障碍提示音"));
        this.pos = pos;
        this.key = EscalatorSpeedManager.liftToneKey(pos.getX(), pos.getZ());
    }

    /** 服务端同步回来时刷新列表（界面还开着的情况下）。 */
    public static void notifyToneDataChanged() {
        LiftToneSetupScreen s = OPEN;
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
        if (mc.level != null) {
            // 【1.28】音频隔离：只列**当前单项页**那个分类（子文件夹）的待导入 / 已存入。
            //   一级菜单（page 0）没有列表，不加载。
            if (page > 0) {
                String category = categoryFor(PAGES[page - 1]);
                stored.addAll(EscalatorSpeedManager.getClientAudioLibraryKeys(mc.level, category));
                pending.addAll(EscalatorSpeedManager.getClientFolderAudioKeys(mc.level, category));
            }
        }
        Collections.sort(stored);
        Collections.sort(pending);
        buildUi();
    }

    /**
     * 【1.17】关闭界面 = **应用所有输入框**。
     *
     * <p>用户原话：「从现在开始删除所有 ui 里的『确认』按钮，所有 ui 里的输入框都会在玩家
     * 按下 esc 退出 ui 时立即应用」。所以本类里**一个「应用」按钮都不留**。
     *
     * <p>★【09-27 二改】输入框只剩**一级菜单那四个音量框**；二级菜单已经没有输入框了。
     * 所以「落地」只在一级菜单这一层做（{@link #applyMainVolumes()}）；
     * 从二级页按 Esc / 点「返回」只是回一级，不落地（那一层没东西可落）。
     */
    @Override
    public void onClose() {
        if (page > 0) {
            // 【1.23】二级菜单按 Esc = **返回一级菜单，不落地输入框编辑**（Esc = 返回上一级）。
            page = 0;
            scroll = 0;
            init();
        } else {
            applyMainVolumes();
            if (OPEN == this) {
                OPEN = null;
            }
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
        // 列表页两列上方要让出一行表头（与屏蔽门那几个二级页同一套算法）。
        listTop = listPage ? LIST_TOP + ROW_H : LIST_TOP;

        if (page == 0) {
            buildMainPage();
        } else {
            buildTonePage(PAGES[page - 1]);
        }

        // 【1.55】右上角「同步所有」：射程跟着当前页走 ——
        //   一级菜单 page=0 = 这条直梯的四项提示音素材（上楼/下楼/开门/关门）；
        //   二级页 page=1/2/3/4 = 只有该项的素材。与服务端 SYNC_LIFT_WHICH 同序。
        //   ★ beforeOpen 把一级页那四个音量框落地（弹窗会重建界面）；
        //     二级页没有输入框 ⇒ 传 null（屏蔽门那几个页也是这么传的）。
        addRenderableWidget(SyncPopupScreen.syncButton(this, "lift", page, key,
                page > 0 ? null : this::applyMainVolumes));
        // 【09-29】右上角「打开文件夹」：二级页开自己那一项的分类子文件夹（lift/up|down|open|close），
        //   一级菜单开 lift/ 这一组（玩家一眼看到四个分类文件夹）。
        addRenderableWidget(FolderOpenButton.of(this, FolderOpenButton.audioPath(
                page > 0 ? categoryFor(PAGES[page - 1]) : EscalatorSpeedManager.GROUP_LIFT)));
    }

    // ------------------------------------------------------------------
    // 一级菜单：【09-27 二改】四个「…设置…」按钮 + 各自右边的音量框
    // ------------------------------------------------------------------
    private void buildMainPage() {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(cx - 100, this.height + BTN_Y, 96, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        // 四行：[上楼提示音设置…] 音量[ 100 ]
        mainVolumeInputs.clear();
        int y = LIST_TOP + 10;
        for (int i = 0; i < PAGES.length; i++) {
            String which = PAGES[i];
            final int targetPage = i + 1;
            addRenderableWidget(Button.builder(
                            Component.literal(liftToneTitle(which) + "设置…"), button -> {
                        // 【1.17】跳页会重建控件：先把这一页的音量落地，否则刚填的字随控件一起没了。
                        applyMainVolumes();
                        page = targetPage;
                        scroll = 0;
                        init();
                    })
                    .bounds(mainRowStartX(), y, BTN_W, 20)
                    .build());

            EditBox box = new EditBox(this.font, mainRowInputX(), y, MAIN_INPUT_W, 20,
                    Component.literal(liftToneTitle(which) + "音量 1~1000"));
            box.setMaxLength(8);
            box.setValue(String.valueOf(EscalatorSpeedManager.getLiftToneVolume(mcLevel(), which)));
            addRenderableWidget(box);
            mainVolumeInputs.put(which, box);

            y += ROW_H;
        }
    }

    /** 一级页一行的总宽（按钮 + 空隙 + 「音量」标签 + 空隙 + 输入框）。 */
    private int mainRowWidth() {
        return BTN_W + MAIN_GAP + this.font.width(VOLUME_LABEL) + MAIN_LABEL_GAP + MAIN_INPUT_W;
    }

    /** 一级页一行的起点 x（整行居中）。 */
    private int mainRowStartX() {
        return this.width / 2 - mainRowWidth() / 2;
    }

    /** 一级页「音量」标签的 x（按钮右边）。 */
    private int mainRowLabelX() {
        return mainRowStartX() + BTN_W + MAIN_GAP;
    }

    /** 一级页音量输入框的 x（「音量」标签右边）。 */
    private int mainRowInputX() {
        return mainRowLabelX() + this.font.width(VOLUME_LABEL) + MAIN_LABEL_GAP;
    }

    /**
     * 【1.17】把当前页的输入框落地。空值的「落地时机」= 关闭界面（{@link #onClose}）
     * 或即将跳去另一页（重建控件前）。
     *
     * <p>取值非法时用一条聊天栏提示说明原因 —— 这时界面可能正在关闭，
     * 界面里那行状态已经没人看得见了。
     */
    private void notifyBadInput(String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[SmoothLift] " + why), false);
        }
    }

    /**
     * 【09-27 二改】一级菜单上**四个**音量框一起落地（{@code /lifthelploud up|down|open|close <音量>}）。
     *
     * <p>由 {@link #onClose}、「进二级页」的按钮、「同步所有」弹窗（beforeOpen）三处调用 ⇒ 必须**空值安全**
     * （二级页里 {@code mainVolumeInputs} 是空的），并且每个框**只在真的改了**才发包。
     */
    private void applyMainVolumes() {
        for (Map.Entry<String, EditBox> e : mainVolumeInputs.entrySet()) {
            applyVolume(e.getKey(), e.getValue());
        }
    }

    /**
     * 单个音量框落地（{@link #applyMainVolumes()} 的逐项版本）。
     *
     * <p>{@code box} 为 null（该页没有这个框）时直接跳过 ⇒ 空值安全。
     */
    private void applyVolume(String which, EditBox box) {
        if (box == null) {
            return;
        }
        Integer v = parseVolume(box.getValue());
        if (v == null) {
            notifyBadInput("「" + liftToneTitle(which)
                    + "」音量必须是 1~1000 的整数（100 = 原始音量），已忽略");
            return;
        }
        if (v == EscalatorSpeedManager.getLiftToneVolume(mcLevel(), which)) {
            return; // 没改，不必发包
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(which, 32);
        buf.writeVarInt(v);
        ClientPlayNetworking.send(SmoothLift.SET_LIFT_TONE_VOLUME_CHANNEL, buf);
        setStatus("已请求把「" + liftToneTitle(which) + "」音量设为 " + v);
        // 本地镜像直接改，服务端会再同步权威值回来
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientLiftToneVolumeLocal(level.dimension(), which, v);
        }
    }

    // ------------------------------------------------------------------
    // 二级页：左右两列列表（与屏蔽门同款）—— 【09-27 二改】不再有音量输入框
    // ------------------------------------------------------------------
    private void buildTonePage(String which) {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> {
            // 二级页没有输入框，回一级即可（一级的音量框不受影响）。
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        rebuildScroll();

        // 左列：本分类文件夹里**还没入库**的 OGG —— 点一下 = 导入存档并设为这一项。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(Button.builder(Component.literal(truncate(id, 22)),
                            button -> importPending(which, id))
                    .bounds(SoundListLayout.leftColX(this.width), y, COL_W, 20)
                    .build());
        }

        String cur = currentValue(mcLevel(), which);

        // 右列第 0 行：开关（这一项在本维度的子开关）—— 名字 + 「切换」两个控件。
        int y0 = rowY(0);
        if (fullyVisible(y0)) {
            boolean on = isToneEnabled(which);
            addRenderableWidget(Button.builder(
                            Component.literal((on ? "✓" : "") + "开关：" + (on ? "开" : "关")),
                            button -> toggleToneEnabled(which))
                    .bounds(SoundListLayout.rightColX(this.width), y0, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("切换"),
                            button -> toggleToneEnabled(which))
                    .bounds(SoundListLayout.rowPickX(this.width), y0, ROW_BTN_W, 20)
                    .build());
        }

        // 右列第 1 行：不播（只有「选用」、没有「删除」—— 它不是一个文件，删无可删）。
        int y1 = rowY(1);
        if (fullyVisible(y1)) {
            boolean offNow = EscalatorSpeedData.LIFT_TONE_OFF.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((offNow ? "✓" : "") + "不播"),
                            button -> pick(which, EscalatorSpeedData.LIFT_TONE_OFF, false))
                    .bounds(SoundListLayout.rightColX(this.width), y1, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pick(which, EscalatorSpeedData.LIFT_TONE_OFF, false))
                    .bounds(SoundListLayout.rowPickX(this.width), y1, ROW_BTN_W, 20)
                    .build());
        }

        // 右列第 2 行：默认 —— 同上，只有「选用」。
        //   ★【09-27 三改】文案从「默认（跟维度默认）」缩成 **「默认」**（用户点名；与屏蔽门那页同一叫法）。
        int y2 = rowY(2);
        if (fullyVisible(y2)) {
            boolean isDefault = EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((isDefault ? "✓" : "") + "默认"),
                            button -> pick(which, EscalatorSpeedData.LIFT_TONE_DEFAULT, false))
                    .bounds(SoundListLayout.rightColX(this.width), y2, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pick(which, EscalatorSpeedData.LIFT_TONE_DEFAULT, false))
                    .bounds(SoundListLayout.rowPickX(this.width), y2, ROW_BTN_W, 20)
                    .build());
        }

        // 右列：已存入存档的音频 —— 每行三个控件：名字 / 选用 / 删除。
        for (int i = 0; i < stored.size(); i++) {
            int y = rowY(i + RIGHT_SPECIAL_ROWS);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            boolean isCurrent = !EscalatorSpeedData.LIFT_TONE_OFF.equals(cur)
                    && !EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(cur) && id.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> pick(which, id, false))
                    .bounds(SoundListLayout.rightColX(this.width), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> pick(which, id, false))
                    .bounds(SoundListLayout.rowPickX(this.width), y, ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteStored(which, id))
                    .bounds(SoundListLayout.rowDeleteX(this.width), y, ROW_BTN_W, 20)
                    .build());
        }
    }

    /** 两列行数取**较大**的那一边算滚动范围（与屏蔽门那几页同一套口径）。 */
    private void rebuildScroll() {
        int rowCount = listRowCount();
        int total = rowCount * ROW_H;
        int visible = Math.max(ROW_H, listBottom - listTop);
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** 列表总行数：左列 pending.size()、右列 stored.size() + 特殊行数，取较大者。 */
    private int listRowCount() {
        return Math.max(pending.size(), stored.size() + RIGHT_SPECIAL_ROWS);
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /** 当前竖井列、这一项实际生效的 audioId（镜像里没有 → 默认）。 */
    private String currentValue(Level level, String which) {
        EscalatorSpeedData.LiftToneAudio tone =
                level == null ? EscalatorSpeedData.LiftToneAudio.NONE
                        : EscalatorSpeedManager.getClientLiftTone(level, key);
        return switch (which) {
            case "up" -> tone.up();
            case "down" -> tone.down();
            case "open" -> tone.open();
            case "close" -> tone.close();
            default -> EscalatorSpeedData.LIFT_TONE_DEFAULT;
        };
    }

    /** 点左列某一行：从文件夹导入并设为这一项。 */
    private void importPending(String which, String audioId) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(key);
        buf.writeUtf(which, 32);
        buf.writeUtf(audioId, 128);
        ClientPlayNetworking.send(SmoothLift.IMPORT_FOLDER_LIFT_TONE_CHANNEL, buf);
        setStatus("正在从文件夹导入并设为「" + liftToneTitle(which) + "」：" + truncate(audioId, 16));
    }

    /** 点右列某一行：开关哨兵 → 切换子开关；否则把这一项设为 {code audioId}（默认 / 不播 / 某段音频）。 */
    private void pick(String which, String audioId, boolean fromFolder) {
        if (TOGGLE_SENTINEL.equals(audioId)) {
            toggleToneEnabled(which);
            return;
        }
        if (fromFolder) {
            importPending(which, audioId);
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(key);
        buf.writeUtf(which, 32);
        buf.writeUtf(audioId, 128);
        ClientPlayNetworking.send(SmoothLift.SET_LIFT_TONE_CHANNEL, buf);
        String label = audioLabel(audioId);
        setStatus("已选择「" + liftToneTitle(which) + "」：" + truncate(label, 20));
        refreshAllAfterPick(which, audioId);
    }

    /** 【1.28】从存档移除一段**本页分类**的音频（服务端会同时解绑引用它的那些直梯）。 */
    private void deleteStored(String which, String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(categoryFor(which), 64);
        buf.writeUtf(id, 128);
        ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(id);
        setStatus("已请求从存档删除：" + truncate(id, 16));
        init();
    }

    /** 【1.46】当前维度这项子开关是否开着（镜像里没有 → 默认开）。 */
    private boolean isToneEnabled(String which) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null || EscalatorSpeedManager.isLiftToneEnabled(mc.level, which);
    }

    /** 【1.46】切换维度默认子开关（发到服务端，镜像等同步回来刷新）。 */
    private void toggleToneEnabled(String which) {
        boolean next = !isToneEnabled(which);
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(which, 32);
        buf.writeBoolean(next);
        ClientPlayNetworking.send(SmoothLift.SET_LIFT_TONE_SWITCH_CHANNEL, buf);
        setStatus("已请求把「" + liftToneTitle(which) + "」" + (next ? "开启" : "关闭") + "（同步回来后生效）");
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientLiftToneSwitchLocal(level.dimension(), which, next);
        }
        init();
    }

    private Level mcLevel() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level;
    }

    /** 点完行后本地马上反映（服务端同步回来会再校准一次）。 */
    private void refreshAllAfterPick(String which, String audioId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        EscalatorSpeedData.LiftToneAudio cur =
                EscalatorSpeedManager.getClientLiftTone(mc.level, key);
        String up = "up".equals(which) ? audioId : cur.up();
        String down = "down".equals(which) ? audioId : cur.down();
        String open = "open".equals(which) ? audioId : cur.open();
        String close = "close".equals(which) ? audioId : cur.close();
        EscalatorSpeedManager.applyClientLiftToneLocal(mc.level.dimension(), key,
                new EscalatorSpeedData.LiftToneAudio(up, down, open, close));
        init();
    }

    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            this.statusText = text;
        });
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
            return EscalatorSpeedData.clampLiftHelpVolume(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page > 0 && maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        if (page == 0) {
            renderMainPage(guiGraphics);
        } else {
            renderTonePage(guiGraphics);
        }
    }

    /** 一级菜单：只画状态行 + 每行那个「音量」小标签（都是画出来的，不是按钮）。 */
    private void renderMainPage(GuiGraphics guiGraphics) {
        int cx = this.width / 2;

        // 每行按钮右边的「音量」标签
        int y = LIST_TOP + 10;
        for (int i = 0; i < PAGES.length; i++) {
            guiGraphics.drawString(this.font, Component.literal(VOLUME_LABEL),
                    mainRowLabelX(), y + 6, 0xFFFFFF, false);
            y += ROW_H;
        }

        // 【七改】按用户点名：「默认音量 … ｜ 上楼 … ｜ 下楼 … ｜ 开门 … ｜ 关门 …」那一行**整段删掉**。
        //   现在只在有操作反馈（statusText 非空）时才画一行黄色的；平时这一页不再有常驻信息行。
        if (statusText != null) {
            guiGraphics.drawCenteredString(this.font, Component.literal(statusText), cx,
                    this.height + STATUS_Y, 0xFFFF55);
        }
    }

    /**
     * 【09-27 二改】二级页：两列 + 中间一条竖线 + 表头（与 {@link PsdToneSetupScreen} 同一版式）。
     *
     * <p>★ **不再画**那一行常驻信息字，也**不再画**音量输入框的标签
     * （这一页已经没有输入框了）。唯一的例外见下：操作反馈。
     */
    private void renderTonePage(GuiGraphics guiGraphics) {
        String which = PAGES[page - 1];
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font, Component.literal(liftToneTitle(which) + "设置"),
                cx, 22, 0xFFFFFF);

        // 两列表头（与屏蔽门那几页逐字相同）
        guiGraphics.drawCenteredString(this.font, Component.literal("未导入存档"),
                SoundListLayout.leftColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("已导入存档"),
                SoundListLayout.rightColX(this.width) + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);

        // 中间那条竖线
        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);

        // 滚动条（与屏蔽门那几页同一套，按 listRowCount 算）
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

        // ★ 操作反馈（点删除 / 点刷新 / 选了素材）仍然要能看见 —— 平时这一行是空的。
        if (statusText != null) {
            guiGraphics.drawCenteredString(this.font, Component.literal(statusText), cx,
                    this.height + SoundListLayout.STATUS_Y_LIST, 0xFFFF55);
        }
    }

    private static String liftToneTitle(String which) {
        return switch (which) {
            case "up" -> "上楼提示音";
            case "down" -> "下楼提示音";
            case "open" -> "开门提示音";
            case "close" -> "关门提示音";
            default -> "提示音";
        };
    }

    /** 素材 id → 状态行里显示的名字。【09-27 三改】default 的文案改成 **「默认」**
     *  （用户点名，与屏蔽门那页同一份叫法）。
     *  <p>★ 但**不能**写成「默认素材」：它指的是**竖井列这一层**的 default（= 跟维度默认），
     *  不是模组内置素材那一段 —— 后者会和指令里的 {@code default} 撞名。 */
    private static String audioLabel(String id) {
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(id)) {
            return "默认";
        }
        if (EscalatorSpeedData.LIFT_TONE_OFF.equals(id)) {
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
