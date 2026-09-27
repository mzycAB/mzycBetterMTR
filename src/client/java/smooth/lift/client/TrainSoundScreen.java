package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 【1.57】「列车音效」界面 —— **石斧右键侧线铁轨**打开，**一条侧线一份设置**。
 *
 * <h2>一级页（1.56 那套版式，一个像素都没动）</h2>
 *
 * 用户 1.56 的原话：<b>「5 个按钮，按钮后面有音频输入框，范围 1-1000 …… 这个 ui 不要任何其他的文字，
 * 就按钮，音量输入框前面 2 个文字：音量，和界面顶部的：'列车音效' 文字」</b>。
 * 所以一级页**只有**：标题 + 5 行「按钮 + 音量 + 输入框」+ 右上角「同步所有」。
 *
 * <p>★ 这条「除了标题与『音量』不许有第三个字」的约束仍然钉着，而且是**按绘制调用点数**钉的：
 * 一级页路径上文字绘制**调用点合计 == 2**（{@link #render()} 里 1 处标题 +
 * {@link #renderTopPage} 里 1 处循环画的「音量」标签）。
 * 别改成「数运行期文字条数」—— 循环会把 1 个调用点放大成 5 条，
 * 那样**再塞一行文字也变不红**（【1.56】踩过这条判据的写法）。
 *
 * <h2>二级页（【1.57】新增）：与屏蔽门「站台音效」同款两列列表</h2>
 *
 * 用户原话：<b>「点进去的 2 级 ui 和屏蔽门 ui 里的『站台音效』ui 使用相同设计，就是左右两列列表的
 * 那个 ui，只不过设置的是列车运行音效 而不是 站台音效 别搞混了！…… 这个列车运行音效的右侧列表
 * 最上方也有一个默认按钮，只不过这个是『MTR自带音效』按钮，依旧只有选择没有删除，按下之后恢复
 * MTR 列车自带音效。…… 默认 1 秒，自定义输入框放在这个二级菜单最下方。其他按钮 ui 也这样设计」</b>。
 *
 * <p>所以五个音效按钮各自的二级页都是：
 * <pre>
 *   左列 = 文件夹里**还没导入**的 OGG（点一下 = 导入存档）
 *   中间一条竖线
 *   右列 = 第 0 行「MTR自带音效」（★只有「选用」、**没有「删除」**）+ 已导入存档的音效（名字/选用/删除）
 *   最下方 = 「淡入淡出:」秒数输入框（默认 1 秒）
 * </pre>
 *
 * <p>★【09-27 三次改版】**五个二级页各读自己那一项的分类文件夹**
 * （`MBM_Audio/train/run|round|switch|in|out`，见 {@link #PAGE_CATEGORIES}）；
 * 原来是五项共用一个 `pbm/music`（已删）。老存档里的 `pbm/music` 由服务端迁移到 `train/run`。
 *
 * <p>★ 版式的数字**不在这里写**，全部来自 {@link SoundListLayout}
 * （屏蔽门那几个二级页也用同一份）—— 两份各写一遍的话，将来「列宽调一下」只会调一边，
 * 表现是「列车这页比屏蔽门那页窄一点」，而且**不报错**。
 *
 * <h2>★ 身份（{@link #sidingKey}）：一条侧线一份，不是全局</h2>
 *
 * 用户原话：<b>「ui 只修改石斧右键的侧线内的所有列车音效 …… 侧线 A 有 5 列车在运行，
 * 设置这条侧线音效后这 5 辆车同时应用改动。但是旁边的侧线 B 和侧线 B 正在运行的列车不受影响，
 * 只有点击 ui 右上角的『同步所有』按钮才会同步到其他侧线」</b>。
 * ⇒ 本界面的每一项设置都挂在 {@link #sidingKey}（= MTR 侧线 id，由 {@link MtrSidingAccess} 认出来）
 * 上；「同步所有」才跨侧线（域 {@code train} + 这个 key）。
 *
 * <h2>★ 本轮仍然没有数据层（用户点名「这些按钮的功能先不做」）</h2>
 *
 * <ul>
 *   <li>5 个音效按钮**能翻页**了（这是界面本身），但进到页里点「选用」「MTR自带音效」
 *       **不发任何包**，只在状态行诚实地说还没接 —— 绝不写「已设置」（假成功比不实现更糟）。</li>
 *   <li>音量框 / 淡入淡出框的值只落在**本界面的字段**里（{@link #volume} / {@link #fadeSeconds}），
 *       理由是模组惯例「开弹窗/换页之前先把输入框落地」——不落地，弹窗重建界面时刚填的数字就没了。</li>
 *   <li>**左列的「导入存档」与右列的「删除」是真的**：它们本来就是**共用音频存档**上的操作
 *       （与扶梯/直梯/屏蔽门同一只库、同一只包），跟「列车音效怎么响」无关，
 *       不接它们的话左列点了没反应、右列永远是空的，界面就没法验。</li>
 * </ul>
 */
public class TrainSoundScreen extends Screen {

    // ------------------------------------------------------------------
    // 一级页
    // ------------------------------------------------------------------

    /** 五个音效按钮的文字。★ 顺序即用户给的顺序，也是 {@link #KEYS} 的顺序。 */
    private static final String[] LABELS = {
            "列车运行音效",
            "列车转弯音效",
            "列车道岔音效",
            "列车进站音效",
            "列车出站音效",
    };

    /**
     * 五个二级页各自的**射程编号**（发给服务端「同步」用）。
     *
     * <p>★ 必须与服务端 {@link SmoothLift#SYNC_TRAIN_RUN}~{@link SmoothLift#SYNC_TRAIN_DEPART}
     * **同序同内容**（回归里单独成节断言）：页号原样当 scope 发过去，错位是**不报错**的
     * —— 只是同步到了另一项。
     */
    private static final int[] PAGE_SCOPES = {
            SmoothLift.SYNC_TRAIN_RUN,
            SmoothLift.SYNC_TRAIN_TURN,
            SmoothLift.SYNC_TRAIN_SWITCH,
            SmoothLift.SYNC_TRAIN_ARRIVE,
            SmoothLift.SYNC_TRAIN_DEPART,
    };

    /**
     * 五个二级页各自的**音频分类**（★【09-27 三次改版】：原来五项共用一个 `pbm/music`，
     * 现在拆成 `train/run|round|switch|in|out`，与 {@link #LABELS} 同序）。
     *
     * <p>★ 顺序必须与 {@link #LABELS}（按钮文字）严格一致：第 1 个按钮「列车运行音效」→
     * `train/run`，第 2 个「列车转弯音效」→ `train/round`，第 3 个「列车道岔音效」→ `train/switch`，
     * 第 4 个「列车进站音效」→ `train/in`，第 5 个「列车出站音效」→ `train/out`。
     * 错位是**不报错**的，只是列表读错了文件夹（回归里单独成节断言）。
     */
    private static final String[] PAGE_CATEGORIES = {
            EscalatorSpeedManager.CAT_TRAIN_RUN,
            EscalatorSpeedManager.CAT_TRAIN_ROUND,
            EscalatorSpeedManager.CAT_TRAIN_SWITCH,
            EscalatorSpeedManager.CAT_TRAIN_IN,
            EscalatorSpeedManager.CAT_TRAIN_OUT,
    };

    /** 音量框左边那个标签的文字。★ 一级页只有这一种标签，5 行共用同一份文字。 */
    private static final String VOLUME_LABEL = "音量";

    /** 一级页一行的几何。 */
    private static final int VOL_ROW_H = 20;
    private static final int ROW_STEP = 26;
    private static final int BTN_W = 200;
    private static final int BOX_W = 60;
    /** 按钮 -> 标签 的间距。 */
    private static final int GAP_BTN_LABEL = 6;
    /** 标签 -> 输入框 的间距（与「预设选择」界面那个标签一样留 4 px）。 */
    private static final int GAP_LABEL_BOX = 4;
    /** 第一行的 y。上面给标题（y=12）和右上角按钮（y=6~26）留出空间。 */
    private static final int TOP = 40;

    // ------------------------------------------------------------------
    // 二级页
    // ------------------------------------------------------------------

    /** 两列表头。★ 与屏蔽门那几页逐字相同（用户点名「使用相同设计」）。 */
    private static final String COL_HEADER_LEFT = "未导入存档";
    private static final String COL_HEADER_RIGHT = "已导入存档";

    /** 右列**第 0 行**那个特殊项。★ 屏蔽门是「不播」，列车音效是这一句（用户点名）。 */
    private static final String MTR_BUILTIN_LABEL = "MTR自带音效";

    /** 底部那个秒数框的标签与范围。★ 默认 1 秒（用户点名）；0 = 不淡入淡出也合法。 */
    private static final String FADE_LABEL = "淡入淡出:";
    private static final int FADE_BOX_W = 56;
    private static final int FADE_MIN = 0;
    private static final int FADE_MAX = 60;
    private static final int FADE_DEFAULT = 1;

    /** 底部三档的 y（相对 {@code height}，负数往上）：按钮 / 秒数框 / 状态行。 */
    private static final int BTN_Y = SoundListLayout.BTN_Y;
    private static final int FADE_Y = -56;
    private static final int STATUS_Y = -74;
    /** 二级页底部预留：状态行 + 秒数框 + 返回/刷新 三档。 */
    private static final int BOTTOM_RESERVE_FADE = 84;
    /** 一级页底部没有控件，只留一点余量别让列表贴到边。 */
    private static final int BOTTOM_RESERVE_TOP = 40;

    private static final String BACK_LABEL = "返回";
    private static final String REFRESH_LABEL = "刷新";

    /** 标题与页名各自的 y（与屏蔽门界面同一处版位）。 */
    private static final int TITLE_Y = 12;
    private static final int PAGE_NAME_Y = 22;

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 这一份设置属于哪条侧线（MTR 侧线 id）。 */
    private final long sidingKey;

    /** 一级页五行各自的音量输入框（{@code init()} 里重建；二级页时为 null）。 */
    private final EditBox[] volumeInputs = new EditBox[LABELS.length];

    /**
     * 一级页五行各自的音量值（100 = 原始音量）。
     *
     * <p>★ 这是**界面自己的临时状态**，不是配置：数据层还没接。存在的唯一理由是
     * 「弹窗/换页会把本界面重建一次」——不存下来的话，刚填的数字会被重建掉的框吃掉。
     */
    private final int[] volume = new int[LABELS.length];

    /** 五个二级页各自的淡入淡出秒数（同上：界面侧临时状态）。 */
    private final int[] fadeSeconds = new int[LABELS.length];

    /** 二级页的秒数输入框（只属于**当前那一页**）。 */
    private EditBox fadeInput;

    /** 共用音频存档的两张表（与屏蔽门界面同一个来源、同一套包）。 */
    private final List<String> stored = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();

    /** 0 = 一级页；1..5 = 第 {@code page - 1} 个音效的二级页。 */
    private int page;
    private int scroll;
    private int maxScroll;
    private int listTop;
    private int listBottom;

    /** 状态行文字；null = 这一页什么都不提示（一级页永远是 null —— 那一页不许有别的字）。 */
    private String statusText;

    /** 服务端同步回来时刷新列表（界面还开着的情况下）。 */
    private static volatile TrainSoundScreen OPEN;

    public TrainSoundScreen(long sidingKey) {
        super(Component.literal("列车音效"));
        this.sidingKey = sidingKey;
        Arrays.fill(volume, EscalatorSpeedData.DEFAULT_AUDIO_VOLUME);
        Arrays.fill(fadeSeconds, FADE_DEFAULT);
    }

    /** 音频存档增删（服务端同步回来）时刷新列表 —— 与 {@code PsdToneSetupScreen} 同一套。 */
    public static void notifyToneDataChanged() {
        TrainSoundScreen s = OPEN;
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

    // ------------------------------------------------------------------
    // 一级页几何：init() 与 render() 必须算出**同一套**坐标，所以都从这里取
    // ------------------------------------------------------------------

    /** 一行的总宽 = 按钮 + 间距 + 「音量」实测宽 + 间距 + 输入框。 */
    private int rowWidth() {
        return BTN_W + GAP_BTN_LABEL + this.font.width(VOLUME_LABEL) + GAP_LABEL_BOX + BOX_W;
    }

    /** 整行**居中**摆：窄屏上也不会把输入框挤出画面。 */
    private int rowX0() {
        return this.width / 2 - rowWidth() / 2;
    }

    private int labelX() {
        return rowX0() + BTN_W + GAP_BTN_LABEL;
    }

    private int boxX() {
        return labelX() + this.font.width(VOLUME_LABEL) + GAP_LABEL_BOX;
    }

    private static int rowY(int index) {
        return TOP + index * ROW_STEP;
    }

    // ------------------------------------------------------------------
    // 二级页几何（底部那一行）
    // ------------------------------------------------------------------

    /** 秒数框那一对（标签 + 框）整体居中：{@code width/2} 减半宽。 */
    private int fadePairX0() {
        return this.width / 2
                - (this.font.width(FADE_LABEL) + GAP_LABEL_BOX + FADE_BOX_W) / 2;
    }

    private int fadeBoxX() {
        return fadePairX0() + this.font.width(FADE_LABEL) + GAP_LABEL_BOX;
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /** 当前二级页对应的音频分类；一级页没有列表，回落第 1 项（`train/run`）。 */
    private String currentCategory() {
        return PAGE_CATEGORIES[Math.max(0, page - 1)];
    }

    @Override
    protected void init() {
        OPEN = this;
        Minecraft mc = Minecraft.getInstance();
        stored.clear();
        pending.clear();
        if (mc.level != null) {
            // 【1.28】音频隔离：每一页只列**自己那一项**分类（MBM_Audio/train/<哪一项>）的待导入 / 已存入。
            // 【09-27 三次改版】五项各一个分类，不再是共用的 pbm/music。
            String category = currentCategory();
            stored.addAll(EscalatorSpeedManager.getClientAudioLibraryKeys(mc.level, category));
            pending.addAll(EscalatorSpeedManager.getClientFolderAudioKeys(mc.level, category));
        }
        Collections.sort(stored);
        Collections.sort(pending);
        buildUi();
    }

    private void buildUi() {
        clearWidgets();
        // 二级页底部有三档（状态行 / 秒数框 / 返回+刷新），预留比一级页大；
        // 二级页两列上方还要让出一行表头（与屏蔽门那几个二级页同一套算法）。
        boolean listPage = page >= 1;
        int reserve = listPage ? BOTTOM_RESERVE_FADE : BOTTOM_RESERVE_TOP;
        listTop = listPage ? SoundListLayout.LIST_TOP + SoundListLayout.ROW_H : SoundListLayout.LIST_TOP;
        listBottom = Math.max(listTop + SoundListLayout.ROW_H, this.height - reserve);

        if (page == 0) {
            buildTopPage();
        } else {
            buildListPage();
        }

        // 右上角「同步所有」：【1.55】那套弹窗。
        //   ★ 射程跟着当前页走：一级页 = 这条侧线的五项设置；二级页 = 只同步这一项的素材。
        //   ★ key = 这条侧线的身份 ⇒ 「同步所有」才跨侧线（用户点名的语义）。
        //   ★ beforeOpen 先把输入框落地：弹窗会把本界面重建一次，不落地刚填的数字就丢了。
        int scope = page == 0 ? SmoothLift.SYNC_TOP_LEVEL : PAGE_SCOPES[page - 1];
        addRenderableWidget(SyncPopupScreen.syncButton(this, "train", scope, sidingKey,
                this::applyInputs));
    }

    private void buildTopPage() {
        for (int i = 0; i < LABELS.length; i++) {
            final int targetPage = i + 1;

            // 音效按钮：文字就是用户点名的那五条；点一下 = 翻到这一项的二级页。
            addRenderableWidget(Button.builder(Component.literal(LABELS[i]),
                            button -> openSoundPage(targetPage))
                    .bounds(rowX0(), rowY(i), BTN_W, VOL_ROW_H)
                    .build());

            // 音量输入框：1~1000、只收数字、maxLength 4（与模组其它界面同款）。
            EditBox box = new EditBox(this.font, boxX(), rowY(i), BOX_W, VOL_ROW_H,
                    Component.literal(LABELS[i]));
            box.setMaxLength(4);
            box.setValue(String.valueOf(volume[i]));
            box.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
            addRenderableWidget(box);
            volumeInputs[i] = box;
        }
        setInitialFocus(volumeInputs[0]);
    }

    /**
     * 二级页：两列列表 + 底部「淡入淡出」秒数框。
     *
     * <p>★ 右列**第 0 行**是「{@link #MTR_BUILTIN_LABEL}」—— 它**只有「选用」、没有「删除」**
     * （用户点名「依旧只有选择没有删除」；它不是一个音频文件，删无可删）。
     * 这是它与下面每一行的**唯一**结构差别，下面那个循环因此从
     * {@link SoundListLayout#RIGHT_COL_FIRST_STORED_ROW} 行开始。
     */
    private void buildListPage() {
        int cx = this.width / 2;
        int w = this.width;

        addRenderableWidget(Button.builder(Component.literal(BACK_LABEL), button -> {
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal(REFRESH_LABEL), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        // 淡入淡出秒数框（用户点名「自定义输入框放在这个二级菜单最下方」）。
        EditBox fadeBox = new EditBox(this.font, fadeBoxX(), this.height + FADE_Y, FADE_BOX_W, 20,
                Component.literal(FADE_LABEL));
        fadeBox.setMaxLength(4);
        fadeBox.setValue(String.valueOf(fadeSeconds[page - 1]));
        fadeBox.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        addRenderableWidget(fadeBox);
        fadeInput = fadeBox;

        rebuildScroll();

        // 左列：文件夹里**还没入库**的 OGG —— 点一下 = 只导入存档（不改任何设置）。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowYOf(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(Button.builder(Component.literal(truncate(id, 22)),
                            button -> importPending(id))
                    .bounds(SoundListLayout.leftColX(w), y, SoundListLayout.COL_W, 20)
                    .build());
        }

        // 右列第 0 行：「MTR自带音效」。★ 只有名字 + 「选用」，**没有「删除」**。
        int builtinY = rowYOf(0);
        if (fullyVisible(builtinY)) {
            addRenderableWidget(Button.builder(Component.literal(MTR_BUILTIN_LABEL),
                            button -> pickBuiltin())
                    .bounds(SoundListLayout.rightColX(w), builtinY, SoundListLayout.ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pickBuiltin())
                    .bounds(SoundListLayout.rowPickX(w), builtinY, SoundListLayout.ROW_BTN_W, 20)
                    .build());
        }

        // 右列：已导入存档的音效 —— 每行三个控件：名字 / 选用 / 删除。
        for (int i = 0; i < stored.size(); i++) {
            int y = rowYOf(i + SoundListLayout.RIGHT_COL_FIRST_STORED_ROW);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            addRenderableWidget(Button.builder(
                            Component.literal(truncate(id, SoundListLayout.ROW_NAME_CHARS)),
                            button -> pickStored(id))
                    .bounds(SoundListLayout.rightColX(w), y, SoundListLayout.ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> pickStored(id))
                    .bounds(SoundListLayout.rowPickX(w), y, SoundListLayout.ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteStored(id))
                    .bounds(SoundListLayout.rowDeleteX(w), y, SoundListLayout.ROW_BTN_W, 20)
                    .build());
        }

        setInitialFocus(fadeBox);
    }

    private void rebuildScroll() {
        int leftCount = pending.size();
        int rightCount = stored.size() + SoundListLayout.RIGHT_COL_FIRST_STORED_ROW;
        maxScroll = SoundListLayout.maxScroll(listTop, listBottom, leftCount, rightCount);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** 二级页里第 {@code index} 行在屏幕上的 y（已算滚动）。 */
    private int rowYOf(int index) {
        return SoundListLayout.rowY(listTop, index, scroll);
    }

    private boolean fullyVisible(int y) {
        return y >= listTop && y + 20 <= listBottom;
    }

    // ------------------------------------------------------------------
    // 行为
    // ------------------------------------------------------------------

    /**
     * 点了一级页的某一项音效按钮 → 翻到它的二级页。
     *
     * <p>★ 这里**只翻页、不发包**：素材选择这项功能还没接数据层
     * （用户点名「这些按钮的功能先不做」）。先把一级页的输入框落地再翻页，
     * 否则重建控件会把刚填的数字丢掉。
     */
    private void openSoundPage(int targetPage) {
        applyInputs();
        page = targetPage;
        scroll = 0;
        statusText = null;
        init();
    }

    /** 二级页左列：把**本页那一项的分类**（{@code MBM_Audio/train/…}）文件夹里的一段 OGG 导入存档音频库。 */
    private void importPending(String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(currentCategory(), 64);
        buf.writeUtf(id, 128);
        // ★ 这只包的名字里带 psd，但服务端做的事是**按包里的分类纯导入**（importAudioToStore + 补发音频库同步），
        //   与「哪一项用它」无关 —— 见 SmoothLift 里那个接收器的注释。
        ClientPlayNetworking.send(SmoothLift.IMPORT_PSD_MIDIUM_AUDIO_CHANNEL, buf);
        setStatus("已导入存档：" + truncate(id, 16));
        init();
    }

    /** 二级页右列「删除」：从**本页那一项的分类**音频存档移除（服务端会一起清掉引用它的那些设置）。 */
    private void deleteStored(String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(currentCategory(), 64);
        buf.writeUtf(id, 128);
        ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(id);
        setStatus("已请求从存档删除：" + truncate(id, 16));
        init();
    }

    /**
     * 二级页右列第 0 行「MTR自带音效」。
     *
     * <p>★ 用户描述的最终行为是「按下之后恢复 MTR 列车自带音效」——
     * 那需要数据层（本页选择哪一段、以及怎么让列车真的响），**本轮不做**。
     * 所以这里**不发包、不改任何字段**，只在状态行诚实说明，
     * 绝不回一句「已恢复」把自己变成假的成功（这是本项目反复强调的那条底线）。
     */
    private void pickBuiltin() {
        setStatus(MTR_BUILTIN_LABEL + "还没接数据层，这一页先只做了界面");
    }

    /** 二级页右列某一段音频的「选用」。★ 同 {@link #pickBuiltin()}：本轮不发包。 */
    private void pickStored(String id) {
        setStatus("「" + truncate(id, 16) + "」还没接数据层，这一页先只做了界面");
    }

    /** 状态行（二级页才有；一级页永远不画）。 */
    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> this.statusText = text);
    }

    /**
     * 把当前页的输入框收回字段。
     *
     * <p>★ 调用时机有三个，都要：{@link #onClose}（退出 / 返回上一级）、
     * {@link #openSoundPage}（翻页会重建控件）、以及 {@link SyncPopupScreen} 的
     * {@code beforeOpen}（弹窗同样会重建本界面）。
     *
     * <p>★ 现在**只收进内存、不发任何包**：数据层还没接（见类注释）。
     * 两个分支都逐个 null 判断，所以在一级页调它也安全。
     */
    private void applyInputs() {
        for (int i = 0; i < volumeInputs.length; i++) {
            EditBox box = volumeInputs[i];
            if (box == null) {
                continue;
            }
            Integer value = parseVolume(box.getValue());
            if (value != null) {
                volume[i] = value;
            }
        }
        if (fadeInput != null && page >= 1) {
            Integer seconds = parseFade(fadeInput.getValue());
            if (seconds != null) {
                fadeSeconds[page - 1] = seconds;
            }
        }
    }

    /**
     * Esc（以及「返回」按钮之外的那条路）= **返回上一级**：二级页回一级页，一级页才真的退出。
     *
     * <p>与屏蔽门界面 1.23 立的那条一致（用户点名「Esc = 返回上一级，没得返回才退出」）。
     * ★ 区别是这里**返回之前先落地输入框**：二级页有秒数框，不落地就把玩家刚填的秒数留在了
     * 那个即将被销毁的 {@code EditBox} 里。
     */
    @Override
    public void onClose() {
        applyInputs();
        if (page != 0) {
            page = 0;
            scroll = 0;
            statusText = null;
            init();
            return;
        }
        if (OPEN == this) {
            OPEN = null;
        }
        // Screen.onClose() 内部就是 minecraft.setScreen(null)。
        super.onClose();
    }

    /** 回车 = 与 Esc 同义（一级页退出、二级页返回上一级）。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page >= 1 && maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * SoundListLayout.ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // ★ 标题是**两页共用**的唯一一处文字；一级页剩下的那一处「音量」标签在 renderTopPage 里。
        //   于是「一级页除了标题与音量不许有第三个字」这条约束，数出来正好是 2 个调用点。
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);

        if (page == 0) {
            renderTopPage(guiGraphics);
        } else {
            renderListPage(guiGraphics);
        }
    }

    /** 一级页：只有「音量」标签（在循环里，按 {@link #LABELS} 的长度画 5 遍）。 */
    private void renderTopPage(GuiGraphics guiGraphics) {
        // 标签贴在**框左沿外侧 4 px**：按字体实测宽度定位（CJK 在 MC 字体里不是正方形，
        // 写死偏移会歪），纵向与 20 px 高的框居中对齐（与「预设选择」界面同款画法）。
        int labelX = labelX();
        for (int i = 0; i < LABELS.length; i++) {
            guiGraphics.drawString(this.font, Component.literal(VOLUME_LABEL), labelX,
                    rowY(i) + (VOL_ROW_H - this.font.lineHeight) / 2, 0xA0A0A0, false);
        }
    }

    /**
     * 二级页：页名 + 两列表头 + 竖线 + 滚动条 + 状态行 + 秒数框的标签。
     *
     * <p>版式与屏蔽门那几个二级页**同源**（几何全取 {@link SoundListLayout}）。
     */
    private void renderListPage(GuiGraphics guiGraphics) {
        int cx = this.width / 2;
        int w = this.width;

        // 这一页是哪一项（一级页那五个按钮的文字，逐字相同）
        guiGraphics.drawCenteredString(this.font, Component.literal(LABELS[page - 1]), cx,
                PAGE_NAME_Y, 0xFFFFFF);

        // 两列表头（与屏蔽门那几页逐字相同）
        guiGraphics.drawCenteredString(this.font, Component.literal(COL_HEADER_LEFT),
                SoundListLayout.leftColX(w) + SoundListLayout.COL_W / 2,
                SoundListLayout.LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal(COL_HEADER_RIGHT),
                SoundListLayout.rightColX(w) + SoundListLayout.COL_W / 2,
                SoundListLayout.LIST_TOP + 2, 0xFFFFFF);

        // 中间那条竖线
        guiGraphics.fill(SoundListLayout.dividerX(w), listTop,
                SoundListLayout.dividerX(w) + 1, listBottom, 0x80FFFFFF);

        // 滚动条（两列共用一个偏移；范围口径在 SoundListLayout.maxScroll 里只写一次）
        int rowCount = Math.max(pending.size(),
                stored.size() + SoundListLayout.RIGHT_COL_FIRST_STORED_ROW);
        if (maxScroll > 0 && rowCount > 0) {
            int barX = SoundListLayout.scrollBarX(w);
            int trackTop = listTop;
            int trackH = Math.max(SoundListLayout.ROW_H, listBottom - listTop);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rowCount * SoundListLayout.ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        // 秒数框的标签：紧贴框左边（与一级页「音量」同款画法）
        guiGraphics.drawString(this.font, Component.literal(FADE_LABEL), fadePairX0(),
                this.height + FADE_Y + 6, 0xFFFFFF, false);

        // 状态行：只有真正做过什么之后才画（一级页与刚进二级页都不画 —— 不写无信息的字）
        if (statusText != null) {
            guiGraphics.drawCenteredString(this.font, Component.literal(statusText), cx,
                    this.height + STATUS_Y, 0xFFFF55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 小工具（与模组其它界面同一套约定）
    // ------------------------------------------------------------------

    /** 解析音量输入框（1~1000，越界夹取）；空/非法返回 null（视为未改动）。 */
    private static Integer parseVolume(String text) {
        try {
            return EscalatorSpeedData.clampVolume(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析淡入淡出秒数。
     *
     * <p>★ 越界**不夹取、直接当非法**：夹取会让玩家看到「填 999 却变成 60」而不知道为什么
     * （屏蔽门那边对音量也是这个口径：越界 → 提示，不是悄悄改小）。
     */
    private static Integer parseFade(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            return v < FADE_MIN || v > FADE_MAX ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 列表里的名字：超长截断加省略号（控件宽度是按 6 px/字符反算的）。 */
    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, Math.max(0, limit - 1)) + "…";
    }
}
