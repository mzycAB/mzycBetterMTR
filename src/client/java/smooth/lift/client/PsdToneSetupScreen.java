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
import java.util.List;

/**
 * 【1.50】石斧右键**屏蔽门**打开的「屏蔽门开关门提示音」选择界面。
 *
 * <p>与直梯那个 {@link LiftToneSetupScreen} 同一套版式（固定区三行从下往上互不重叠、
 * 列表可滚、`默认 / 内置素材(default-c、default-m) / 不播 / 待导入 / 已入库` 五种行），差别只有：
 * <ul>
 *   <li>项从三项（上楼 / 下楼 / 开关门）变成**两项**（开门 / 关门）；</li>
 *   <li>主界面多一个**总开关**按钮（= {@code /pbmmusic on|off}），直梯那个总开关放在指令里；
 *       这一条是为了让「只想静音」的人不用打指令 —— 子开关与总开关在同一个包里传；</li>
 *   <li>没有倍速项（用户需求里屏蔽门只有开 / 关 / 音量三样）；</li>
 *   <li>【1.16】主界面多一行「关门提示音强制等待时长」输入框（= {@code /pbmclosewait <秒>}）——
 *       只在「停站时长不够放完整条关门素材」时才生效（开门音播完等这么多秒再放人声、
 *       门一动就掐断），停站够长时被完全忽略。见
 *       {@link EscalatorSpeedData#defaultPsdCloseWaitSeconds} 与
 *       {@link PsdChimePlayer} 的 {@code tickForcedVoice}。</li>
 * </ul>
 *
 * <h2>「这一串门」怎么定位（【1.21】，【1.27】起粒度升到站台）</h2>
 * 这个界面上改的每一项都落在**一串**屏蔽门上。身份用
 * {@link PsdDoorTracker#runKeyOf} 折算出的**串锚点**（见那个方法的注释）：
 * ★【1.27】首选 = 这一扇门所在的 **MTR 站台**（同一个站台里被实体缺口切开的几段门一起改）；
 * 认不到站台时才回落成「沿水平方向相连的 站台门 + 屏蔽门玻璃 + 玻璃尾部相连的一串」。
 * 播放端 {@link PsdChimePlayer} **读配置**时也用同一个串锚点（{@code door.runKey()}），
 * 所以「改其中一个 = 改一整串」在两边必然一致。
 *
 * <p>★ 别再退回 {@code anchorOf}：那是「一扇门」的身份（左右 + 上下半格折成一格），
 * 一扇门一个值 ⇒ 会变成用户明确不要的「只改右键那一扇」。
 *
 * <p>★ 也别在这里自己算「连通串」：播放端拿的是 {@code runKeyOf} 的结果（1.27 起含站台那一层），
 * 界面若自己走洪水填充，两边会算出两个身份 ⇒ 界面写的设置播放端读不到（静默落回维度默认）。
 *
 * <p>【1.28】音频隔离：每个列表页只列**自己分类**子文件夹（{@code MBM_Audio/pbm/open}、{@code pbm/close}、
 * {@code pbm/midium}、{@code pbm/arrive}）的待导入 / 已存入（见 {@link #categoryForPage}）。
 */
public class PsdToneSetupScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static final int ROW_H = SoundListLayout.ROW_H;
    private static final int LIST_TOP = SoundListLayout.LIST_TOP;
    /**
     * 列表区底部距窗口底部的预留：给「状态文字 + 到站播报行 + 强制等待输入框行 + 默认音量输入框行
     * + 返回/刷新」五行让位。
     *
     * <p>【1.16】从 108 加到 116、{@link #STATUS_Y} 从 -96 挪到 -104：主界面多了一行
     * 「关门提示音强制等待时长」输入框（见 {@link #CLOSEWAIT_Y}）。
     * 【1.17】再加到 142、{@link #STATUS_Y} 挪到 -132：主界面又多了一行「到站播放音频」
     * 按钮 + 「等待几秒后播放」输入框（【1.22-2】后这两行在列表页，主界面不放它们）。
     * ★ 加行时必须**同时**改这两个数 —— 只加行不改预留，列表最后几行会被压在那行下面。
     */
    private static final int BOTTOM_RESERVE = 120;
    // ★【1.22】142 → 120：「强制等待」与「默认音量」两个输入框已并成**同一行**，
    //   主界面固定区少了一行 ⇒ 列表多出一行。加/减行时这个数和
    //   下面 STATUS_Y_MAIN / mainStatusY() 必须一起看。
    private static final int BTN_Y = SoundListLayout.BTN_Y;
    private static final int INPUT_Y = -56;
    /** 【1.16】「关门提示音强制等待时长」输入框那一行的 y（在 {@link #INPUT_Y} 上面一行）。 */
    private static final int CLOSEWAIT_Y = -82;
    // ★【1.22-2】原来这里有一个 MIDIUM_Y（列表页底部那个「不播」按钮的 y）。
    //   该按钮已挪进右列第 0 行 ⇒ 这个常量不再有任何使用点，直接删掉，别留死常量。
    private static final int STATUS_Y = -132;

    /**
     * 【1.22】**列表页**（到站 / 进站播放音频）的状态行 y 与底部预留。
     *
     * <p>为什么单独一套：这两页下面**没有**强制等待 / 默认音量那两行输入框，
     * 却一直沿用主界面的预留 ⇒ 可视区被压得很短，正是用户报的「列表太短」。
     *
     * <p>【1.22-2】第二次收口：页面底部那个「不播」按钮被挪进**右列第 0 行**（用户点名），
     * 底部固定区只剩「状态行 + 返回/刷新」两样 ⇒ 预留 60 → <b>44</b>、状态行 -52 → -42，
     * 列表再长一行。按 {@link #fullyVisible} 的 `y + 20 <= listBottom` 复算：
     * <pre>
     *   GUI 高 240（1080p / 缩放 5）：(240-44-62-20)//22 + 1 = 6 行
     *   GUI 高 270（1080p / 缩放 4）：(270-44-62-20)//22 + 1 = 7 行
     * </pre>
     * 都 ≥ 用户点名的 6 行。
     * ★ 再加/减底部固定区的东西时，这三个数（预留 / 状态行 y / 注释里的行数）必须一起动 ——
     * 只改预留会让状态行压到列表最后一行上（改完等于没改）。
     */
    private static final int STATUS_Y_LIST = SoundListLayout.STATUS_Y_LIST;
    private static final int BOTTOM_RESERVE_LIST = SoundListLayout.BOTTOM_RESERVE_LIST;

    /** 【1.22】「等待秒数: [框]  音量: [框]」那一排的标签与框宽（到站 / 进站两行共用同一套左右对齐）。 */
    private static final String ROW_WAIT_LABEL = "等待秒数:";
    private static final String ROW_LOUD_LABEL = "音量:";
    private static final int ROW_BOX_W = 56;
    /** 【1.22】底部「默认音量 / 强制等待」并排那一排的框宽。 */
    private static final int BOTTOM_LOUD_W = 56;
    private static final int BOTTOM_WAIT_W = 52;
    private static final int BTN_W = 200;

    /**
     * 【1.20】**主界面**状态行的 y（其它页仍用 {@link #STATUS_Y}）。
     *
     * <p>为什么主界面要单独一档：主界面上面是五行**固定在顶部**的控件
     * （开门提示 → 关门提示 → 到站播放音频 + 等待几秒后播放 → 进站播放音频 + 到站前几秒
     * → 【09-28】进站广播(讲述人) + 秒数），
     * 最后一行的下沿是 {@code LIST_TOP + 10 + ROW_H*4 + 20 = 158}（**绝对坐标，不随窗口长**，
     * 【09-28】讲述人那一行正好补在 {@code ROW_H*4} 这一格上 ⇒ 这个下沿数字**没变**）；
     * 而 {@link #STATUS_Y} = -132 是**相对窗口底部**的。GUI 高度 270（1080p / 缩放 4，很常见）
     * 时 -132 正好落在 y=138 —— **屏幕正中**并压住上面那行「到站播放音频」：
     * 用户原话「屏蔽门ui中间有一行白色小字挡住中间播报了。往下挪」。
     *
     * <p>所以主界面挪到 -100：高度 270 时落在 y=170，上面离固定区（下沿 158）12px、
     * 下面离「强制等待」输入框（y=188）8px —— 【1.21】多了一行之后只剩这一档容得下，
     * 再往下就压输入框、再往上就压固定区最后一行。
     * 实际 y 由 {@link #mainStatusY()} 再夹一次，窗口特别矮时也不会压到上下两行。
     */
    private static final int STATUS_Y_MAIN = -100;

    /** 主界面状态行的实际 y：往下挪一档，但上下都不许压到邻居（见 {@link #STATUS_Y_MAIN}）。 */
    private int mainStatusY() {
        // 固定区最后一行 =「进站播放音频 / 到站前几秒」（y = LIST_TOP+10+ROW_H*4，高 20）
        // 【1.21】ROW_H*3 → ROW_H*4：主界面又多了「进站播放音频」那一行（在到站播放音频下面）。
        int fixedBottom = LIST_TOP + 10 + ROW_H * 4 + 20;
        int y = Math.max(this.height + STATUS_Y_MAIN, fixedBottom + 4);
        // 再往下那一档是「强制等待」输入框（高 20）—— 别把字压到它的边框上。
        // ★ 这两个约束（上限 = 别压强制等待框；下限 = 别压上面四行）在**窗口很矮时会打架**：
        //   H ≤ 236 时 H+CLOSEWAIT_Y-14 会小于 fixedBottom+4，直接 min() 会把字顶回上面四行里
        //   （实测 H=200 → y=104，反而盖住「到站播放音频」）。这里让下限赢：宁可贴近强制等待框，
        //   也绝不回到「挡住中间播报」那个用户原始症状上。
        // 【1.22】下面那一档只剩 INPUT_Y 这一行了（强制等待已并进去）。
        int lower = this.height + INPUT_Y - 14;
        return Math.max(fixedBottom + 4, Math.min(y, lower));
    }

    /**
     * 【1.17】到站播报选择列表：左右两列各自的宽度，以及中间竖线的留白。
     *
     * <p>★【1.19】176 → 190：右列每行从「名字 + 一个按钮」变成「名字 + 两个按钮」
     * （用户点名「要在导入的音频的右侧加 2 个按钮，一个是选用，一个是删除」）。
     * 190 是**兼容量出来的**：两列 + 竖线 = 396px，在 427 宽（854×480 窗口 / GUI 缩放 2）
     * 的画布上左列仍有 15px 余量；再宽就会在小窗口上顶出去。
     */
    private static final int COL_W = SoundListLayout.COL_W;
    private static final int COL_GAP = SoundListLayout.COL_GAP;
    /** 右列每行两个行内按钮（「选用」「删除」）各自的宽度，以及它们与名字之间的间隔。 */
    private static final int ROW_BTN_W = SoundListLayout.ROW_BTN_W;
    private static final int ROW_BTN_GAP = SoundListLayout.ROW_BTN_GAP;
    /** 右列每行名字按钮的宽度（剩下的部分）；宽度按 6px/字符 反算可容字符数。 */
    private static final int ROW_NAME_W = SoundListLayout.ROW_NAME_W;
    /**
     * 右列名字的截断上限。
     *
     * <p>★ 取 14 不是随手写的：{@code ROW_NAME_W}(94px) / 6px = 15.6 ⇒ 14 字符（84px）留出余量，
     * 而**最长的那个素材名恰好是 14 字符**（{@code mdoorclose.ogg}）——
     * 这样再给「✓」前面加一个字符（15×6 = 90px）也依然排得下，
     * 「当前正在用的那一段」不会被截成一个看不出是什么的名字。
     */
    private static final int ROW_NAME_CHARS = SoundListLayout.ROW_NAME_CHARS;

    /**
     * 【1.22-2】右列**第 0 行**是什么、已导入音频从第几行开始。
     *
     * <p>用户点名：「到站/进站播放音频 UI 里最下方的『不播』按钮放在已导入存档列表的
     * 最上面一个，只不过这个右边没有删除键，只有选择键」⇒ 右列 = [不播] + stored[0..]，
     * 所以已导入第 i 条落在**第 i + 1 行**。左列不受影响（仍是 pending[i] 落在第 i 行）。
     */
    private static final int RIGHT_COL_FIRST_STORED_ROW = SoundListLayout.RIGHT_COL_FIRST_STORED_ROW;

    /**
     * 列表行文字最多显示几个字符（超出截断加「…」）。
     *
     * <p>★ 取 30 而不是更小，是为了让行尾的「  ✓当前」标记**不会被截掉**：
     * 最长的两行（{@code mdoorclose.ogg（default-m）  ✓当前} = 30 字符、
     * {@code doorclose.ogg（default-c）  ✓当前} = 29 字符）刚好排得下。
     * 之前是 26 ⇒ 这两行一旦是「当前值」，那个「✓」正好落在截断点之后被吃掉，
     * 表现为「点了默认-m 却看不到勾」。宽度上 30 个 ASCII ≈ 180px &lt; {@link #BTN_W}（200）也够。
     */
    private static final int ROW_TEXT_MAX = 30;

    /** 「开关行」的值哨兵（与真正的 audioId 区分开：它不代表任何素材）。 */
    private static final String TOGGLE_SENTINEL = "\u0000TOGGLE";

    private static final int T_HEADER = 0;
    // 【1.18】原来还有个 T_NOTE = 1（「（暂无）」那类灰字小注）。用户点名「ui 里的灰色小字删掉」
    //   ⇒ 那些行连生成带渲染一起删了；空列表就空着，列首标题已经说明这一列是什么。
    private static final int T_PICK = 2;   // 点=设为该项音频（默认 / 不播 / 某段音频）

    private static final class Row {
        final int type;
        final String which;   // open / close；标题行为 null
        final String value;   // 选中的 audioId（default / off / 文件名）；标题行为 null
        final String text;
        final boolean fromFolder;

        Row(int type, String which, String value, String text) {
            this(type, which, value, text, false);
        }

        Row(int type, String which, String value, String text, boolean fromFolder) {
            this.type = type;
            this.which = which;
            this.value = value;
            this.text = text;
            this.fromFolder = fromFolder;
        }
    }

    /** 两项提示音的 id（与指令里的 {@code open|close} 完全一致）。 */
    private static final String[] PAGES = {"open", "close"};

    private final BlockPos pos;
    /**
     * 【1.21】★ 这个界面里改的每一项都落在**一串**屏蔽门上，所以这里存的是**串锚点**
     * （{@link PsdDoorTracker#runKeyOf}），而不是「这一扇门」的锚点
     * （{@link PsdDoorTracker#anchorOf}）。★【1.27】这个身份现在**优先 = MTR 站台**。
     *
     * <p>用户原话：「连在一起的屏蔽门是指站台门，屏蔽门玻璃，屏蔽门玻璃尾部相连的一串屏蔽门；
     * **修改其中一个，就要一起修改这一串**」。播放端读配置时用的也是同一个串锚点
     * （{@code door.runKey()}），两边必然对得上。
     *
     * <p>★ 注意区分：门的**播放**身份（哪一扇门在响 / 门值学习 / 提前量）仍然用
     * {@code anchorOf} 算出的门锚点 —— 那是播放端内部的事，与这个界面无关。
     */
    private final long runKey;
    private final List<String> stored = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();
    /** 【1.23】右列「已导入存档」从第几行开始：不播（第 0 行）+ 默认行之后。广播页 = 1（没有默认行）。 */
    private int rightStoredStartRow = RIGHT_COL_FIRST_STORED_ROW;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    private int maxScroll;
    private int listBottom;
    /**
     * 【1.17】列表区顶部。默认 {@link #LIST_TOP}；到站播报页要用它给两列上方的
     * 「未导入 / 已导入」表头留出一行，所以那一页把它往下挪一点。
     *
     * <p>★ {@link #rowY} / {@link #fullyVisible} / 裁剪区 / 滚动条**全部**走这一个字段 ——
     * 只要有一处还写死 {@code LIST_TOP}，那一页的滚动就会和可见区对不上。
     */
    private int listTop = LIST_TOP;
    private String statusText;

    /**
     * 当前页：0 = 主界面；1/2 = open / close 单项列表；
     * 3 = 【1.17】到站播报选择列表；4 = 【1.21】进站报站选择列表；
     * 5 = 【09-28】进站广播（讲述人）：左列空着，右列只有「关闭 / 开启」两行。
     */
    private int page;

    /** 【1.28】页面 → 音频分类（文件夹 = MBM_Audio/<分类>）。主界面没有列表，返回 null。 */
    private static String categoryForPage(int page) {
        return switch (page) {
            case 1 -> EscalatorSpeedManager.CAT_PSD_OPEN;
            case 2 -> EscalatorSpeedManager.CAT_PSD_CLOSE;
            case 3 -> EscalatorSpeedManager.CAT_PSD_MIDIUM;
            case 4 -> EscalatorSpeedManager.CAT_PSD_ARRIVE;
            default -> null;
        };
    }

    private EditBox openVolumeInput;
    private EditBox closeVolumeInput;
    /** 【1.23】主界面「开门提示」行等待秒数框（秒，[0,+∞)）。 */
    private EditBox openWaitInput;
    /** 【1.23】主界面「关门提示」行等待秒数框（= 原「强制等待」，[0,+∞)）。 */
    private EditBox closeWaitInput;
    /** 【1.17】主界面「等待几秒后播放」输入框（秒，0 ~ +∞）。 */
    private EditBox midiumWaitInput;
    /** 【1.21】主界面「到站前几秒播放」输入框（秒，(-∞, 0]：-10 = 最近一班车还剩 10 秒到站时起播）。 */
    private EditBox arriveLeadInput;
    /** 【1.22】「到站播放音频」那行的**音量**输入框（= /pbmmidiumloud，在等待秒数框右边）。 */
    private EditBox midiumLoudInput;
    /** 【1.22】「进站播放音频」那行的**音量**输入框（= /pbmarriveloud）。 */
    private EditBox arriveLoudInput;
    /**
     * 【09-28】主界面「进站广播(讲述人)」行的秒数框（秒，(-∞, 0]）。
     *
     * <p>★ 它是讲述人**自己**的窗口（用户点名「取消借用进站广播」）—— 与
     * {@link #arriveLeadInput} 各存各的值。讲述人**没有音量框**：text2speech 库不给音量参数
     * （用户确认「只做开/关」）。
     */
    private EditBox narrateLeadInput;

    private static volatile PsdToneSetupScreen OPEN;

    public PsdToneSetupScreen(BlockPos pos) {
        super(Component.literal("选择屏蔽门开关门提示音"));
        this.pos = pos;
        Level level = Minecraft.getInstance().level;
        // 【1.21】串锚点必须与播放端**读配置**时用同一份算法
        //   （【1.27】优先 MTR 站台；认不到才沿水平方向把相连的 站台门 / 屏蔽门玻璃 / 玻璃端
        //    走成一串、取 asLong 最小），
        //   否则会「在甲门配好、乙门响的时候查不到这份设置」。
        this.runKey = level == null ? pos.asLong() : PsdDoorTracker.runKeyOf(level, pos);
    }

    /** 服务端同步回来时刷新列表（界面还开着的情况下）。 */
    public static void notifyToneDataChanged() {
        PsdToneSetupScreen s = OPEN;
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
            // 【1.28】音频隔离：只列**当前页**那个分类（子文件夹）的待导入 / 已存入。
            //   主界面（page 0）没有列表，不加载。
            String category = categoryForPage(page);
            if (category != null) {
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
     * 按下 esc 退出 ui 时立即应用」。所以本类里**一个「应用」按钮都不留**，
     * 全部输入框的落地时机收敛到这一处（与 {@code EscalatorSpeedScreen#onClose} 同一套约定）。
     *
     * <p>★ 顺手把「进子页面前」也接上（见 {@code buildMainPage} 的两个按钮）——
     * 只挂在 onClose 上会漏掉「填完数字直接点『开门设置…』」这条路：
     * 那一跳会重建控件，输入框里的字就丢了。
     */
    @Override
    public void onClose() {
        if (page != 0) {
            // 【1.23】二级菜单（开门/关门/站台/进站广播页）按 Esc = **返回主界面，不落地输入框编辑**；
            //   只有在一级主界面按 Esc 才落地并退出 UI（用户点名：Esc = 返回上一级，没得返回才退出）。
            page = 0;
            scroll = 0;
            init();
        } else {
            applyMainInputs();
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
        // 【1.22】列表页（2/3/4）下面只有状态行 + 返回/刷新，预留单独一套（见 BOTTOM_RESERVE_LIST）。
        //   【1.23】开门/关门页也改成两列列表页 ⇒ 一起进这套几何（一次可见行数与广播页一致）。
        boolean listPage = page >= 1;
        int reserve = listPage ? BOTTOM_RESERVE_LIST : BOTTOM_RESERVE;
        listBottom = Math.max(LIST_TOP + ROW_H, this.height - reserve);
        // 【1.17/1.21】选择列表页两列上方要留一行表头，整体下移（开门/关门页同样，1.23 起）。
        listTop = listPage ? LIST_TOP + ROW_H : LIST_TOP;
        rows.clear();

        if (page == 0) {
            buildMainPage();
        } else if (page == 3) {
            buildArrivalPage();
        } else if (page == 4) {
            buildArrivePage();
        } else if (page == 5) {
            buildNarratePage();
        } else {
            buildTonePage(PAGES[page - 1]);
        }

        // 【1.55】右上角「同步所有」：射程跟着当前页走 ——
        //   0 主界面 = 关门等待 / 开门音量 / 关门音量 / 到站等待+音量 / 进站秒数+音量 / 讲述人秒数；
        //   1/2 = 开门 / 关门提示音素材；3 = 到站播报素材；4 = 进站报站素材；5 = 讲述人开关+秒数。
        //   ★ 输入框一律先落地（{@link #applyMainInputs} 逐个 null 判断，任何页都能安全调）：
        //   弹窗会把本界面重建一次，服务端读的又是存档 —— 不落地，刚填的数字会丢，
        //   而且同步出去的还是旧值。3/4/5 页也有输入框（到站等待 / 进站秒数 / 讲述人秒数），
        //   所以不能只给 0 页落。
        addRenderableWidget(SyncPopupScreen.syncButton(this, "psd", page, runKey,
                this::applyMainInputs));
    }

    // ------------------------------------------------------------------
    // 主界面：2 按钮 + 总开关 + 共用默认音量输入框
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

        // 【1.23】总开关按钮已按点名删除（/pbmmusic on|off 指令仍在）；下面两行按钮整体上移一行。
        // 开门提示 / 关门提示：按钮 96 宽（与站台/进站广播按钮同尺寸），右侧各接一个音量输入框
        // （原 2 级页底部的音量框挪到这里 —— 腾出列表高度，也让 2 级页与广播页完全同构）。
        //   ★【1.17】进子页面前先把输入框落地（否则这一跳会重建控件、用户刚填的字就丢了）。
        //   那一刻还没到 onClose，所以不能只指望它。
        int y = LIST_TOP + 10;
        for (int i = 0; i < PAGES.length; i++) {
            String which = PAGES[i];
            final int targetPage = i + 1;
            addRenderableWidget(Button.builder(
                            Component.literal(psdToneButtonLabel(which)), button -> {
                        applyMainInputs();
                        page = targetPage;
                        scroll = 0;
                        init();
                    })
                    .bounds(cx - BTN_W / 2, y, 96, 20)
                    .build());
            // 【1.23】右侧 = 等待秒数框 + 音量框（版式与站台/进站广播两行同款）。
            EditBox waitBox = new EditBox(this.font, rowWaitBoxX(cx), y, ROW_BOX_W, 20,
                    Component.literal(ROW_WAIT_LABEL));
            // 【1.23】开门等待 (-∞,+∞) 可填负（-2147483648 11 位）；关门仍 [0,999999]（6 位）。
            waitBox.setMaxLength(i == 0 ? 11 : 6);
            waitBox.setValue(String.valueOf(i == 0
                    ? EscalatorSpeedManager.getDoorPsdOpenWaitSeconds(mcLevel(), runKey)
                    : EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(mcLevel(), runKey)));
            addRenderableWidget(waitBox);
            EditBox loudBox = new EditBox(this.font, rowLoudBoxX(cx), y, ROW_BOX_W, 20,
                    Component.literal(ROW_LOUD_LABEL));
            loudBox.setMaxLength(8);
            loudBox.setValue(String.valueOf(
                    EscalatorSpeedManager.getDoorPsdToneVolume(mcLevel(), runKey, which)));
            addRenderableWidget(loudBox);
            if (i == 0) {
                openWaitInput = waitBox;
                openVolumeInput = loudBox;
            } else {
                closeWaitInput = waitBox;
                closeVolumeInput = loudBox;
            }
            y += ROW_H;
        }

        // 【1.17】到站播放音频：左半 = 进入选择列表；右半 = 「等待几秒后播放」输入框。
        //   两者同一行，正是用户要的版式（「『到站播放音频』按钮的右侧是『等待几秒后播放』输入框」）。
        //   【1.22】右半再补一个音量框（用户点名），版式变成：
        //     [到站播放音频]  等待秒数:[框]  音量:[框]
        int midiumY = LIST_TOP + 10 + ROW_H * 2;
        addRenderableWidget(Button.builder(Component.literal("站台广播"), button -> {
                    applyMainInputs();
                    page = 3;
                    scroll = 0;
                    init();
                })
                .bounds(cx - BTN_W / 2, midiumY, 96, 20)
                .build());
        midiumWaitInput = new EditBox(this.font, rowWaitBoxX(cx), midiumY, ROW_BOX_W, 20,
                Component.literal(ROW_WAIT_LABEL));
        midiumWaitInput.setMaxLength(10);
        midiumWaitInput.setValue(String.valueOf(EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(mcLevel(), runKey)));
        addRenderableWidget(midiumWaitInput);
        midiumLoudInput = new EditBox(this.font, rowLoudBoxX(cx), midiumY, ROW_BOX_W, 20,
                Component.literal(ROW_LOUD_LABEL));
        midiumLoudInput.setMaxLength(8);
        midiumLoudInput.setValue(String.valueOf(EscalatorSpeedManager.getDoorPsdMidiumVolume(mcLevel(), runKey)));
        addRenderableWidget(midiumLoudInput);

        // 【1.21】进站播放音频：**放在到站播放音频的下面一行**（用户点名「放在 pbmmidium 功能的下面」）。
        //   版式与上一行逐字对称：左半 = 进入选择列表；右半 = 「到站前几秒播放」输入框 + 音量框。
        //   ★ 秒数允许 (-∞, 0]（用户点名），含义 = **最近一班车还剩 |X| 秒到站**时起播 ——
        //   ★ 只与「到站剩余时间」有关、与开门时刻无关（用户点名更正过这个口径）。
        //   判据见 parseArriveLead / clampPsdArriveSeconds。
        int arriveY = LIST_TOP + 10 + ROW_H * 3;
        addRenderableWidget(Button.builder(Component.literal("进站广播"), button -> {
                    applyMainInputs();
                    page = 4;
                    scroll = 0;
                    init();
                })
                .bounds(cx - BTN_W / 2, arriveY, 96, 20)
                .build());
        arriveLeadInput = new EditBox(this.font, rowWaitBoxX(cx), arriveY, ROW_BOX_W, 20,
                Component.literal(ROW_WAIT_LABEL));
        arriveLeadInput.setMaxLength(12); // -2147483648 共 11 位，留一格余量
        arriveLeadInput.setValue(
                String.valueOf(EscalatorSpeedManager.getDoorPsdArriveSeconds(mcLevel(), runKey)));
        addRenderableWidget(arriveLeadInput);
        arriveLoudInput = new EditBox(this.font, rowLoudBoxX(cx), arriveY, ROW_BOX_W, 20,
                Component.literal(ROW_LOUD_LABEL));
        arriveLoudInput.setMaxLength(8);
        arriveLoudInput.setValue(String.valueOf(EscalatorSpeedManager.getDoorPsdArriveVolume(mcLevel(), runKey)));
        addRenderableWidget(arriveLoudInput);

        // 【09-28】进站广播（讲述人）：**放在进站广播的下面一行**——用户点名「ui 和『进站广播』
        //   样式一样」。版式与上一行同款，但**少了音量框**：text2speech 库不给音量参数
        //   （用户确认「只做开/关」）⇒ 这一行只有 [按钮] + 「等待秒数:」框。
        //   ★ 秒数框是讲述人**自己**的窗口（用户点名「取消借用进站广播」），范围同为 (-∞, 0]。
        //   ★ 开关（关闭 / 开启）不在这里，在二级页右列（用户点名「讲述人的2级菜单…右侧菜单留着
        //   『关闭』和『开启』按钮」）。
        int narrateY = LIST_TOP + 10 + ROW_H * 4;
        addRenderableWidget(Button.builder(Component.literal("进站广播(讲述人)"), button -> {
                    applyMainInputs();
                    page = 5;
                    scroll = 0;
                    init();
                })
                .bounds(cx - BTN_W / 2, narrateY, 96, 20)
                .build());
        narrateLeadInput = new EditBox(this.font, rowWaitBoxX(cx), narrateY, ROW_BOX_W, 20,
                Component.literal(ROW_WAIT_LABEL));
        narrateLeadInput.setMaxLength(12); // -2147483648 共 11 位，留一格余量
        narrateLeadInput.setValue(
                String.valueOf(EscalatorSpeedManager.getDoorPsdNarrateSeconds(mcLevel(), runKey)));
        addRenderableWidget(narrateLeadInput);

        // 【1.23】底部「默认音量」「强制等待」两框与「总开关：…」状态行已按点名删除：
        //   「强制等待」功能上移到「关门提示」行右侧的等待秒数框；共用默认音量改由 /pbmloud 指令调。
    }

    // ---- 【1.22】两排输入框的几何（标签宽度跟字体走，小窗口不会撞在一起）----

    private int rowWaitBoxX(int cx) {
        return cx + 4 + this.font.width(ROW_WAIT_LABEL) + 2;
    }

    private int rowLoudLabelX(int cx) {
        return rowWaitBoxX(cx) + ROW_BOX_W + 8;
    }

    private int rowLoudBoxX(int cx) {
        return rowLoudLabelX(cx) + this.font.width(ROW_LOUD_LABEL) + 2;
    }

    private int bottomLoudBoxX(int cx) {
        return cx + 4 + this.font.width("默认音量") + 2;
    }

    private int bottomWaitLabelX(int cx) {
        return bottomLoudBoxX(cx) + BOTTOM_LOUD_W + 10;
    }

    private int bottomWaitBoxX(int cx) {
        return bottomWaitLabelX(cx) + this.font.width("强制等待") + 2;
    }

    /** 主界面三行输入框的画出来的标签（右对齐到输入框左边 {@value #INPUT_LABEL_RIGHT} 处）。 */
    private static final int INPUT_LABEL_RIGHT = 6;

    /**
     * 【1.17】把主界面三个输入框一次性落地（= 原来三个「应用」按钮干的事）。
     *
     * <p>三个字段互相独立，各判各的合法性；某一项非法**不影响**另外两项落地
     * （用户填错一个不该让另外两个也被丢掉）。非法的那些用一条聊天栏提示说明原因 ——
     * 因为这时界面正在关闭，界面里的状态行已经没人看得见了。
     */
    private void applyMainInputs() {
        // 【1.23】开门/关门提示行：等待秒数（关门行 = 原「强制等待」）+ 单项音量，各判各的合法性。
        applyWaitBox("open", openWaitInput);
        applyWaitBox("close", closeWaitInput);
        if (midiumWaitInput != null) {
            Integer sec = parseMidiumWait(midiumWaitInput.getValue());
            if (sec == null) {
                notifyBadInput("到站播报的等待秒数必须是 0 或更大的整数，已忽略");
            } else if (sec != EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(mcLevel(), runKey)) {
                sendSetMidium(null, sec);
            }
        }
        // 【1.22】到站播报自己那一项的音量（= /pbmmidiumloud）。
        if (midiumLoudInput != null) {
            Integer v = parseVolume(midiumLoudInput.getValue(), false);
            if (v == null) {
                notifyBadInput("到站播报的音量必须是 1~1000 的整数，100 = 原始音量，已忽略");
            } else if (v != EscalatorSpeedManager.getDoorPsdMidiumVolume(mcLevel(), runKey)) {
                sendSetMidiumLoud(v);
            }
        }
        // 【1.22】进站报站自己那一项的音量（= /pbmarriveloud）。
        applyToneBox("open", openVolumeInput);
        applyToneBox("close", closeVolumeInput);
        if (arriveLoudInput != null) {
            Integer v = parseVolume(arriveLoudInput.getValue(), false);
            if (v == null) {
                notifyBadInput("进站报站的音量必须是 1~1000 的整数，100 = 原始音量，已忽略");
            } else if (v != EscalatorSpeedManager.getDoorPsdArriveVolume(mcLevel(), runKey)) {
                sendSetArriveLoud(v);
            }
        }
        // 【1.21】进站报站的秒数：范围 (-∞, 0]（用户点名）。没改动就不发包。
        if (arriveLeadInput != null) {
            Integer lead = parseArriveLead(arriveLeadInput.getValue());
            if (lead == null) {
                notifyBadInput("进站报站的秒数必须是 0 或负整数，"
                        + "-10 = 最近一班车还剩 10 秒到站时起播，已忽略");
            } else if (lead != EscalatorSpeedManager.getDoorPsdArriveSeconds(mcLevel(), runKey)) {
                sendSetArrive(null, lead);
            }
        }
        // 【09-28】讲述人的秒数：范围同为 (-∞, 0]，但**与进站报站各存各的**（独立窗口）。
        if (narrateLeadInput != null) {
            Integer lead = parseArriveLead(narrateLeadInput.getValue());
            if (lead == null) {
                notifyBadInput("讲述人报站的秒数必须是 0 或负整数，"
                        + "-10 = 最近一班车还剩 10 秒到站时开始念，已忽略");
            } else if (lead != EscalatorSpeedManager.getDoorPsdNarrateSeconds(mcLevel(), runKey)) {
                sendSetNarrateLead(lead);
            }
        }
    }

    /** 输入框里的值不合法、又已经离开界面时的提示（界面里那行状态已经看不到了）。 */
    private void notifyBadInput(String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[SmoothLift] " + why), false);
        }
    }

    /**
     * 【1.16】把「关门提示音强制等待时长」发出去 = {@code /pbmclosewait <秒>}。
     *
     * <p>【1.17】签名从「读输入框的按钮回调」改成「收一个已经解析好的值」——
     * 因为按钮没了，落地时机统一收到 {@link #onClose} / {@link #applyMainInputs}
     * （三个输入框共用一条路，谁也不用自己读控件）。
     */
    /** 【1.23】开门/关门行等待秒数框：范围 [0,+∞)，有变就发包（换页/退出时由 applyMainInputs 统一落地）。 */
    private void applyWaitBox(String which, EditBox box) {
        if (box == null) {
            return;
        }
        Integer sec = "open".equals(which) ? parseAnyInt(box.getValue()) : parseCloseWait(box.getValue());
        if (sec == null) {
            notifyBadInput("「" + EscalatorSpeedData.psdToneLabel(which)
                    + "」等待秒数必须是"
                    + ("open".equals(which) ? "整数（(-∞, +∞)）" : " 0~" + EscalatorSpeedData.PSD_CLOSE_WAIT_MAX)
                    + "，已忽略");
            return;
        }
        if ("open".equals(which)) {
            if (sec != EscalatorSpeedManager.getDoorPsdOpenWaitSeconds(mcLevel(), runKey)) {
                sendSetOpenWait(sec);
            }
        } else if (sec != EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(mcLevel(), runKey)) {
            sendSetCloseWait(sec);
        }
    }

    /** 【1.23】把「开门提示」行的等待秒数发出去（= SET_PSD_OPEN_WAIT_CHANNEL）。 */
    private void sendSetOpenWait(int sec) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey);
        buf.writeVarInt(sec);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_OPEN_WAIT_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的开门提示音等待秒数设为 " + sec);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withOpenWaitSeconds(EscalatorSpeedData.clampPsdOpenWaitSeconds(sec)));
        }
    }

    private void sendSetCloseWait(int sec) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 【1.20】这一扇门
        buf.writeVarInt(sec);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_CLOSE_WAIT_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的关门提示音强制等待时长设为 " + sec + " 秒");
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withCloseWaitSeconds(EscalatorSpeedData.clampPsdCloseWaitSeconds(sec)));
        }
    }

    /**
     * 【1.17】把「到站播放音频」的等待秒数（或素材）发出去 = {@code /pbmmidium <名字> <秒>}。
     *
     * @param audioId {@code null} = 只改秒数、素材不动
     */
    private void sendSetMidium(String audioId, int seconds) {
        String name = audioId != null
                ? audioId : EscalatorSpeedManager.getDoorPsdMidiumAudio(mcLevel(), runKey);
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 【1.20】这一扇门
        buf.writeUtf(name, 128);
        buf.writeVarInt(seconds);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_MIDIUM_CHANNEL, buf);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey, t -> {
                EscalatorSpeedData.PsdToneAudio next = t.withMidiumWaitSeconds(
                        EscalatorSpeedData.clampPsdMidiumWaitSeconds(seconds));
                // audioId == null = 只改秒数、素材不动
                return audioId == null
                        ? next : next.withMidium(EscalatorSpeedData.normalizePsdMidiumAudio(audioId));
            });
        }
    }

    /**
     * 【1.21】把「进站播放音频」的素材 / 秒数发出去 = {@code /pbmarrive <名字> <X>}。
     *
     * @param audioId {@code null} = 只改秒数、素材不动
     */
    private void sendSetArrive(String audioId, int leadSeconds) {
        String name = audioId != null
                ? audioId : EscalatorSpeedManager.getDoorPsdArriveAudio(mcLevel(), runKey);
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 【1.21】这一串门（不是这一扇门 —— 用户点名「改一个就改一串」）
        buf.writeUtf(name, 128);
        buf.writeVarInt(leadSeconds);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_ARRIVE_CHANNEL, buf);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey, t -> {
                EscalatorSpeedData.PsdToneAudio next = t.withArriveSeconds(
                        EscalatorSpeedData.clampPsdArriveSeconds(leadSeconds));
                // audioId == null = 只改秒数、素材不动
                return audioId == null
                        ? next : next.withArrive(EscalatorSpeedData.normalizePsdArriveAudio(audioId));
            });
        }
    }

    /** 【1.22】把「到站播报的音量」发出去 = {@code /pbmmidiumloud <音量>}。 */
    private void sendSetMidiumLoud(int v) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 这一串门
        buf.writeVarInt(v);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_MIDIUM_LOUD_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的到站播报音量设为 " + v);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withMidiumVolume(EscalatorSpeedData.clampPsdToneVolume(v)));
        }
    }

    /** 【1.22】把「进站报站的音量」发出去 = {@code /pbmarriveloud <音量>}。 */
    private void sendSetArriveLoud(int v) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 这一串门
        buf.writeVarInt(v);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_ARRIVE_LOUD_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的进站报站音量设为 " + v);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withArriveVolume(EscalatorSpeedData.clampPsdToneVolume(v)));
        }
    }

    /** 【09-28】把讲述人的提前秒数发出去（= SET_PSD_NARRATE_LEAD_CHANNEL）。 */
    private void sendSetNarrateLead(int lead) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 这一串门
        buf.writeVarInt(lead);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_NARRATE_LEAD_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的讲述人报站设为到站前 " + (-lead) + " 秒");
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withNarrateSeconds(EscalatorSpeedData.clampPsdNarrateSeconds(lead)));
        }
    }

    /**
     * 【09-28】把讲述人的**样式**发出去（= SET_PSD_NARRATE_CHANNEL，二级页三行
     * 「关闭 / 开启(上海) / 开启(香港)」的「选择」按钮）。
     *
     * <p>★ 续：这一格从 boolean（开/关）改成 int（0/1/2），与数据层的
     * {@code PsdToneAudio.narrate} 同型；发送端与 {@code SmoothLift} 的接收端同序。
     */
    private void sendSetNarrate(int mode) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 这一串门
        buf.writeVarInt(mode);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_NARRATE_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的进站广播(讲述人)设为"
                + EscalatorSpeedData.psdNarrateModeName(mode));
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withNarrate(EscalatorSpeedData.clampPsdNarrateMode(mode)));
        }
    }

    /** 【1.17】把「共用默认音量」发出去 = {@code /pbmloud <音量>}。 */
    private void sendSetPsdVolume(int v) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 【1.20】这一扇门
        buf.writeVarInt(v);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_CHIME_VOLUME_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的音量设为 " + v);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withVolume(EscalatorSpeedData.clampLiftHelpVolume(v)));
        }
    }

    /** 总开关是否开着（镜像里没有 → 默认开）。 */
    private boolean isMasterEnabled() {
        Level level = mcLevel();
        return level == null || EscalatorSpeedManager.isDoorPsdHelpEnabled(level, runKey);
    }

    private void toggleMaster() {
        boolean next = !isMasterEnabled();
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 【1.20】这一扇门
        buf.writeUtf("master", 32);
        buf.writeBoolean(next);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_TONE_SWITCH_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的屏蔽门提示音总开关" + (next ? "打开" : "关闭"));
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withHelp(next));
        }
        init();
    }

    // ------------------------------------------------------------------
    // 单项列表：素材 + 开关 + 该项音量输入框
    // ------------------------------------------------------------------
    private void buildTonePage(String which) {
        int cx = this.width / 2;
        // 【1.23】右列顺序 = 不播（第 0 行）+ 默认行（开门 1 个 / 关门 2 个）+ 已导入（首字母排序）。
        rightStoredStartRow = 1 + defaultRowCount(which);
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> {
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        rebuildToneRows(which);

        // 左列：存档文件夹里**还没入库**的 OGG —— 点一下 = **只导入存档**（与到站/进站广播页同一语义）。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(Button.builder(Component.literal(truncate(id, 22)),
                            button -> importPending(id))
                    .bounds(leftColX(), y, COL_W, 20)
                    .build());
        }
        // 右列：不播 + 默认（默认只有「选用」、没有「删除」—— 它不是一个音频文件，删无可删）
        String cur = currentValue(mcLevel(), which);
        int offY = rowY(0);
        if (fullyVisible(offY)) {
            boolean offNow = EscalatorSpeedData.PSD_TONE_OFF.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((offNow ? "✓" : "") + "不播"),
                            button -> pick(which, EscalatorSpeedData.PSD_TONE_OFF, false))
                    .bounds(rightColX(), offY, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pick(which, EscalatorSpeedData.PSD_TONE_OFF, false))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, offY, ROW_BTN_W, 20)
                    .build());
        }
        java.util.List<String[]> defaults = defaultRows(which);
        for (int j = 0; j < defaults.size(); j++) {
            int y = rowY(1 + j);
            if (!fullyVisible(y)) {
                continue;
            }
            String value = defaults.get(j)[0];
            String label = defaults.get(j)[1];
            boolean now = value.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((now ? "✓" : "") + label),
                            button -> pick(which, value, false))
                    .bounds(rightColX(), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pick(which, value, false))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, y, ROW_BTN_W, 20)
                    .build());
        }
        // 右列：已导入存档的音频 —— 每行三个控件：名字 / 选用 / 删除。
        for (int i = 0; i < stored.size(); i++) {
            int y = rowY(i + rightStoredStartRow);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            boolean isCurrent = !EscalatorSpeedData.PSD_TONE_OFF.equals(cur) && id.equals(cur);
            addRenderableWidget(Button.builder(
                            Component.literal((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> pick(which, id, false))
                    .bounds(rightColX(), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> pick(which, id, false))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, y, ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteStored(id))
                    .bounds(rightColX() + COL_W - ROW_BTN_W, y, ROW_BTN_W, 20)
                    .build());
        }

        // 【1.23】音量输入框已上移到主界面「开门提示/关门提示」按钮右侧 —— 本页与广播页完全同构。
    }

    /**
     * 【1.23】开门/关门页右列的「默认」行：{@code [0]=audioId、[1]=显示名}。
     * 开门 1 个（默认）；关门 2 个（默认（长）= 整段关门素材、默认（短）= 同素材只播嘀嘀）。
     * ★ 顺序就是用户点名的：不播之后、已导入之前。
     */
    private static java.util.List<String[]> defaultRows(String which) {
        if ("close".equals(which)) {
            return java.util.List.<String[]>of(
                    new String[]{EscalatorSpeedData.PSD_TONE_DEFAULT, "默认（长）"},
                    new String[]{EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S, "默认（短）"});
        }
        return java.util.List.<String[]>of(new String[]{EscalatorSpeedData.PSD_TONE_DEFAULT, "默认"});
    }

    /** 【1.23】右列「默认」行的个数：开门 1、关门 2（见 {@link #defaultRows}）。 */
    private static int defaultRowCount(String which) {
        return "close".equals(which) ? 2 : 1;
    }

    /** 【1.23】主界面开门/关门按钮文案（用户点名：开门提示音设置→开门提示、关门提示音设置→关门提示）。 */
    private static String psdToneButtonLabel(String which) {
        return "open".equals(which) ? "开门提示" : "关门提示";
    }

    // ------------------------------------------------------------------
    // 【1.17】到站播报选择列表：左列 = 未导入存档的音频 / 中间一条竖线 / 右列 = 已导入存档的音频
    //   ★【1.19】左列点一下 = **只导入**；右列每行 = 名字 +「选用」+「删除」三个控件。
    //   版式就是用户点名的那一种（原话：「要在导入的音频的右侧加 2 个按钮，
    //   一个是选用，一个是删除」，且「导入的**不直接选用**」）。
    // ------------------------------------------------------------------

    /** 左列左上角 x。 */
    private int leftColX() {
        return this.width / 2 - COL_GAP / 2 - COL_W;
    }

    /** 右列左上角 x。 */
    private int rightColX() {
        return this.width / 2 + COL_GAP / 2;
    }

    private void buildArrivalPage() {
        int cx = this.width / 2;
        rightStoredStartRow = RIGHT_COL_FIRST_STORED_ROW; // 【1.23】广播页没有默认行
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> {
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        rebuildArrivalRows();

        // 左列：存档文件夹里**还没入库**的 OGG —— 点一下 = **只导入存档**（不改任何设置）。
        //   ★【1.19】用户点名「导入的**不直接选用**，要在导入的音频的右侧加 2 个按钮，
        //   一个是选用，一个是删除」⇒ 左列的点击语义从「导入并选用」收窄成「导入」，
        //   导入完这一条会移到右列，由用户按「选用」显式启用。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(Button.builder(Component.literal(truncate(id, 22)),
                            button -> importPending(id))
                    .bounds(leftColX(), y, COL_W, 20)
                    .build());
        }
        // 右列：**已存入存档**的音频 —— 每行三个控件：名字 / 选用 / 删除。
        //   名字按钮与「选用」做同一件事（都是把它设为到站播报），
        //   留着名字可点是为了顺手：「反正我就是要这一首」时不必再瞄第二个按钮。
        String curMidium = EscalatorSpeedManager.getDoorPsdMidiumAudio(mcLevel(), runKey);
        // 【1.22-2】右列**第 0 行** = 「不播」（用户点名：把原来页面底部那个「不播」按钮挪到
        //   「已导入存档」列表的最上面一个）。★ 它只有「选用」、**没有「删除」** ——
        //   它不是一个音频文件，删无可删；这是它与下面每一行的**唯一**结构差别。
        int midiumOffRowY = rowY(0);
        if (fullyVisible(midiumOffRowY)) {
            boolean midiumOffNow = EscalatorSpeedData.isPsdMidiumOff(curMidium);
            addRenderableWidget(Button.builder(
                            Component.literal((midiumOffNow ? "✓" : "") + "不播"),
                            button -> pickMidium(EscalatorSpeedData.PSD_MIDIUM_OFF))
                    .bounds(rightColX(), midiumOffRowY, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pickMidium(EscalatorSpeedData.PSD_MIDIUM_OFF))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, midiumOffRowY, ROW_BTN_W, 20)
                    .build());
        }
        for (int i = 0; i < stored.size(); i++) {
            int y = rowY(i + RIGHT_COL_FIRST_STORED_ROW);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            boolean isCurrent = !EscalatorSpeedData.isPsdMidiumOff(curMidium) && id.equals(curMidium);
            addRenderableWidget(Button.builder(
                            Component.literal((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> pickMidium(id))
                    .bounds(rightColX(), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> pickMidium(id))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, y, ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteStored(id))
                    .bounds(rightColX() + COL_W - ROW_BTN_W, y, ROW_BTN_W, 20)
                    .build());
        }

        // 【1.22-2】这里原来有一个页面底部的「不播」按钮（当时的理由是「它不属于任何一列」）。
        //   ★ 用户点名改掉：挪进右列第 0 行（见上）。底部固定区因此少了一档，
        //   列表预留也跟着从 {@code BOTTOM_RESERVE_LIST} 里收回一行。
    }

    // ------------------------------------------------------------------
    // 【1.21】进站报站选择列表：版式与到站播报页**逐字相同**（左列 = 未导入 / 竖线 / 右列 = 已导入
    //   且每行「名字 + 选用 + 删除」），差别只有它读写的那一项是「进站报站」。
    //   用户点名：「列表左边是未导入存档的音频，中间一条竖线隔开，列表右边是已经导入存档的音频，
    //   已导入存档的每一个音频右侧分别有一个『选用』按钮和一个『删除』按钮」。
    // ------------------------------------------------------------------

    private void buildArrivePage() {
        int cx = this.width / 2;
        rightStoredStartRow = RIGHT_COL_FIRST_STORED_ROW; // 【1.23】广播页没有默认行
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> {
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        rebuildArriveRows();

        // 左列：还没入库的 OGG —— 点一下 = **只导入存档**（与到站播报页共用同一条导入通道：
        //   它的语义就是「只导入存档、不改任何设置」，与具体是哪一项无关）。
        for (int i = 0; i < pending.size(); i++) {
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = pending.get(i);
            addRenderableWidget(Button.builder(Component.literal(truncate(id, 22)),
                            button -> importPending(id))
                    .bounds(leftColX(), y, COL_W, 20)
                    .build());
        }
        // 右列：已存入存档的音频 —— 每行三个控件：名字 / 选用 / 删除。
        String curArrive = EscalatorSpeedManager.getDoorPsdArriveAudio(mcLevel(), runKey);
        // 【1.22-2】右列第 0 行 = 「不播」（与到站播报页逐字同构，只有读写的项不同）。
        int arriveOffRowY = rowY(0);
        if (fullyVisible(arriveOffRowY)) {
            boolean arriveOffNow = EscalatorSpeedData.isPsdArriveOff(curArrive);
            addRenderableWidget(Button.builder(
                            Component.literal((arriveOffNow ? "✓" : "") + "不播"),
                            button -> pickArrive(EscalatorSpeedData.PSD_ARRIVE_OFF))
                    .bounds(rightColX(), arriveOffRowY, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"),
                            button -> pickArrive(EscalatorSpeedData.PSD_ARRIVE_OFF))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, arriveOffRowY, ROW_BTN_W, 20)
                    .build());
        }
        for (int i = 0; i < stored.size(); i++) {
            int y = rowY(i + RIGHT_COL_FIRST_STORED_ROW);
            if (!fullyVisible(y)) {
                continue;
            }
            String id = stored.get(i);
            boolean isCurrent = !EscalatorSpeedData.isPsdArriveOff(curArrive) && id.equals(curArrive);
            addRenderableWidget(Button.builder(
                            Component.literal((isCurrent ? "✓" : "") + truncate(id, ROW_NAME_CHARS)),
                            button -> pickArrive(id))
                    .bounds(rightColX(), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选用"), button -> pickArrive(id))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, y, ROW_BTN_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteStored(id))
                    .bounds(rightColX() + COL_W - ROW_BTN_W, y, ROW_BTN_W, 20)
                    .build());
        }

        // 【1.22-2】同到站播报页：底部那个「不播」按钮已挪进右列第 0 行。
    }

    /**
     * 【1.22-2】两列共用的行数：**右列第 0 行固定是「不播」**（用户点名把它从页面底部挪进
     * 「已导入存档」列表的最上面一个）⇒ 右列 = 已导入条数 + 1；左列仍是未导入条数。
     * 两列都画在**同一套行坐标**上，所以滚动范围取两者较大者。
     *
     * ★ 这个口径**只在这里写一次**：4 处（两页的 rebuild + 两页的滚动条）都调它，
     *   否则「右列多一行」这件事迟早会有一处漏改（表现：滚动条比例不对 / 最后一行滚不到）。
     */
    private int listRowCount() {
        return Math.max(pending.size(), stored.size() + rightStoredStartRow);
    }

    /** 两列行数取**较大**的那一边算滚动范围（与到站播报页同一套）。 */
    private void rebuildArriveRows() {
        int rowCount = listRowCount();
        int total = rowCount * ROW_H;
        int visible = Math.max(ROW_H, listBottom - listTop);
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** 点一段音频（或「不播」）→ 设为**进站报站**（保留当前秒数）。 */
    private void pickArrive(String id) {
        int lead = EscalatorSpeedManager.getDoorPsdArriveSeconds(mcLevel(), runKey);
        sendSetArrive(id, lead);
        setStatus(EscalatorSpeedData.isPsdArriveOff(id)
                ? "已关闭进站报站"
                : "已把进站报站设为：" + truncate(id, 16));
        init();
    }

    /**
     * 【1.21】进站报站页：两列 + 中间一条竖线 + 表头（与到站播报页同一版式）。
     */
    private void renderArrivePage(GuiGraphics guiGraphics) {
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font,
                Component.literal("进站广播"), cx, 22, 0xFFFFFF);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("未导入存档"),
                leftColX() + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("已导入存档"),
                rightColX() + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);

        // 中间那条竖线
        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);

        // 滚动条
        int rowCount = listRowCount();
        if (maxScroll > 0 && rowCount > 0) {
            int barX = rightColX() + COL_W + 8;
            int trackTop = listTop;
            int trackH = Math.max(ROW_H, listBottom - listTop);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rowCount * ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        // 【1.22】同到站页：状态行下移，列表变长。
        int statusY = this.height + STATUS_Y_LIST;
        String info = statusText;
        if (info == null) {
            Level level = mcLevel();
            String id = EscalatorSpeedManager.getDoorPsdArriveAudio(level, runKey);
            int lead = EscalatorSpeedManager.getDoorPsdArriveSeconds(level, runKey);
            info = EscalatorSpeedData.isPsdArriveOff(id)
                    ? "这一串门当前：不播"
                    : "这一串门当前：" + truncate(id, 20) + "　到站前 " + (-lead) + " 秒";
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), cx, statusY,
                statusText == null ? 0xFFFFFF : 0xFFFF55);
    }

    // ------------------------------------------------------------------
    // 【09-28】进站广播（讲述人）：**没有列表**。
    //   用户点名：「讲述人的2级菜单左侧列表空着就行，因为不用导入什么，右侧菜单留着
    //   『关闭』和『开启』按钮就行，这2个只保留『选择』按钮」。
    //   ⇒ 左列不画任何控件（也没有可导入的东西）；右列 = 三行样式，各接一个「选择」。
    //   ★【09-28 续】用户点名：原来的「开启」改名 **「开启(上海)」**，并**新增一行
    //   「开启(香港)」**（香港档的报站词见 TrainAnnounceNarrator.arriveTextHongKong）；
    //   当时页面上自上而下 = 「关闭 / 开启(上海) / 开启(香港)」。
    //   ★【09-28 续 2】用户点名：**「香港」按钮放在「上海」按钮上面** ⇒
    //   现在自上而下 = 「关闭 / 开启(香港) / 开启(上海)」。
    //   ★ 行的顺序**只由下面 names/modes 两个数组的次序决定**；档位编号
    //     （{@link EscalatorSpeedData#PSD_NARRATE_OFF} = 0 / SHANGHAI = 1 / HONGKONG = 2）
    //     是**存档格式**，与本页的显示次序**无关** —— 调整显示顺序时**不许动**那两个常量。
    //   三行**互斥**，当前生效的那一行打 ✓（生效值 = 门覆盖 > 维度默认）。
    //   讲述人**没有音量**（text2speech 库不给音量参数），所以这里连音量控件都没有。
    // ------------------------------------------------------------------

    private void buildNarratePage() {
        int cx = this.width / 2;
        // 这一页没有列表 ⇒ 也不该有滚动（上一页可能留下 scroll，这里归零）。
        scroll = 0;
        maxScroll = 0;
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> {
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        int mode = EscalatorSpeedManager.getDoorPsdNarrateMode(mcLevel(), runKey);
        // 三行 = 关闭 / 开启(香港) / 开启(上海)；每行只有「名字」+「选择」两个控件
        //   （用户点名「这2个只保留『选择』按钮」⇒ 没有「删除」）。
        //   名字按钮与「选择」做同一件事：顺手点名字就行（与到站 / 进站页同一口味）。
        //   ★【09-28 续 2】「香港」在上、「上海」在下（用户点名）。
        //   ★ 这里**只是显示次序**；names[row] 与 modes[row] 必须**成对**，别只换一个数组。
        String[] names = {"关闭", "开启(香港)", "开启(上海)"};
        int[] modes = {EscalatorSpeedData.PSD_NARRATE_OFF,
                EscalatorSpeedData.PSD_NARRATE_HONGKONG,
                EscalatorSpeedData.PSD_NARRATE_SHANGHAI};
        for (int row = 0; row < names.length; row++) {
            int y = rowY(row);
            if (!fullyVisible(y)) {
                continue;
            }
            int target = modes[row];
            addRenderableWidget(Button.builder(
                            Component.literal((mode == target ? "✓" : "") + names[row]),
                            button -> pickNarrate(target))
                    .bounds(rightColX(), y, ROW_NAME_W, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("选择"),
                            button -> pickNarrate(target))
                    .bounds(rightColX() + ROW_NAME_W + ROW_BTN_GAP, y, ROW_BTN_W, 20)
                    .build());
        }
    }

    /** 点「关闭 / 开启(上海) / 开启(香港)」→ 设为这一串门的讲述人样式（秒数不动）。 */
    private void pickNarrate(int mode) {
        sendSetNarrate(mode);
        init();
    }

    /** 讲述人页：只有标题 + 中间一条竖线 + 状态行（左列空着，用户点名）。 */
    private void renderNarratePage(GuiGraphics guiGraphics) {
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font,
                Component.literal("进站广播(讲述人)"), cx, 22, 0xFFFFFF);
        // 中间那条竖线（与其它列表页同款，让「左空 / 右有」在视觉上一眼分明）
        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);

        int statusY = this.height + STATUS_Y_LIST;
        String info = statusText;
        if (info == null) {
            Level level = mcLevel();
            int mode = EscalatorSpeedManager.getDoorPsdNarrateMode(level, runKey);
            int lead = EscalatorSpeedManager.getDoorPsdNarrateSeconds(level, runKey);
            info = "这一串门当前：" + EscalatorSpeedData.psdNarrateModeName(mode)
                    + "　到站前 " + (-lead) + " 秒"
                    + "（讲述人没有音量、也没有素材）";
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), cx, statusY,
                statusText == null ? 0xFFFFFF : 0xFFFF55);
    }

    /** 两列行数取**较大**的那一边算滚动范围（列之间独立，但共用一个滚动偏移）。 */
    private void rebuildArrivalRows() {
        int rowCount = listRowCount();
        int total = rowCount * ROW_H;
        int visible = Math.max(ROW_H, listBottom - listTop);
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** 【1.19/1.28】点左列一段**未入库**的音频 → 只把它导入**当前页分类**的存档音频库（不改设置）。 */
    private void importPending(String id) {
        String category = categoryForPage(page);
        if (category == null) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(category, 64);
        buf.writeUtf(id, 128);
        ClientPlayNetworking.send(SmoothLift.IMPORT_PSD_MIDIUM_AUDIO_CHANNEL, buf);
        setStatus("已导入存档：" + truncate(id, 16));
        init();
    }

    /** 点一段音频（或「不播」）→ 设为**到站播报**。 */
    private void pickMidium(String id) {
        int seconds = EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(mcLevel(), runKey);
        sendSetMidium(id, seconds);
        setStatus(EscalatorSpeedData.isPsdMidiumOff(id)
                ? "已关闭到站播报"
                : "已把到站播报设为：" + truncate(id, 16));
        init();
    }

    /** 【1.28】从存档移除一段**当前页分类**的音频（服务端会同时清掉引用它的扶梯 / 提示音 / 到站播报）。 */
    private void deleteStored(String id) {
        String category = categoryForPage(page);
        if (category == null) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(category, 64);
        buf.writeUtf(id, 128);
        ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(id);
        setStatus("已请求从存档删除：" + truncate(id, 16));
        init();
    }


    /**
     * 单项列表的音量 = {@code /pbmloud open|close <音量>}。
     *
     * <p>【1.17】触发时机从「那个已被删掉的『应用』按钮」改成「离开这一页 / 退出界面」——
     * 见 {@link #onClose} 与 {@code buildTonePage} 的「返回」。
     */
    /** 【1.23】主界面开门/关门音量框：值有变就发包（换页 / 退出时由 applyMainInputs 统一落地）。 */
    private void applyToneBox(String which, EditBox box) {
        if (box == null) {
            return;
        }
        Integer v = parseVolume(box.getValue(), true);
        if (v == null) {
            notifyBadInput("「" + EscalatorSpeedData.psdToneLabel(which)
                    + "」音量必须是 1~1000 的整数，已忽略");
            return;
        }
        if (v == EscalatorSpeedManager.getDoorPsdToneVolume(mcLevel(), runKey, which)) {
            return; // 没改过就不发包
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 【1.21】runKey = 这一串门（用户点名「改一个就改一串」）
        buf.writeUtf(which, 32);
        buf.writeVarInt(v);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_TONE_VOLUME_CHANNEL, buf);
        setStatus("已把这一串屏蔽门的「" + EscalatorSpeedData.psdToneLabel(which) + "」音量设为 " + v);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withToneVolume(which, EscalatorSpeedData.clampPsdToneVolume(v)));
        }
    }

    private void rebuildToneRows(String which) {
        // 【1.23】开门/关门页改成与广播页同构：右列 = 不播 + 默认 + 已导入（stored），
        //   左列 = 未导入（pending）；行数口径与广播页同一套（见 listRowCount）。
        int rowCount = listRowCount();
        int total = rowCount * ROW_H;
        int visible = Math.max(ROW_H, listBottom - listTop);
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /**
     * 列表开头几行：默认 / 两段可显式选的内置素材（默认-c、默认-m）/【关门页】默认（短）/ 不播。
     *
     * <p>【1.15】第一行写进去的是**这一扇门那一层的 {@code default}**，含义 = 「跟维度默认」：
     * 维度默认本身是 {@code default} 时落内置（**按端别**：开门 → dooropen.ogg、关门 → mdoorclose.ogg），
     * 被 {@code /pbmmusic open|close <名字>} 改过就是玩家选的那段。文案写「默认」而不是
     * 「默认素材」—— 与直梯界面一致（后者会和指令里的 {@code default} 撞名）。
     *
     * <p>另外两段内置素材（{@code default-c} = doorclose.ogg、{@code default-m} = mdoorclose.ogg）
     * 摆出来是为了「用另一段的门音」这个诉求：{@code doorclose.ogg} 没有任何一端缺省它，
     * 不摆就没法在界面里选到（{@code default-m} 与关门端的「默认」是同一段，这里保留显式那一行，
     * 好让玩家一眼看出「关门默认就是哪一段」）。
     *
     * <p>★【1.15】「默认（短）」（{@code default-s}）**只在关门页**出现，理由是它**按端别**
     * 解析（见 {@link EscalatorSpeedData#psdBuiltinKey}）：开门端的默认素材 {@code dooropen.ogg}
     * 本来就没有语音播报段 ⇒ 在开门页它会和第一行「默认」**效果完全相同**，
     * 摆两行一模一样的选项只会让人犯迷糊。所以这一行跟着「有没有播报段」这件事走，只摆在关门页。
     * （指令那边两端都收，写在开门端只是等价于 {@code default}，不会把开门声换成关门素材。）
     */
    private void addPicks(String which) {
        boolean isDefault = isCurrently(which, EscalatorSpeedData.PSD_TONE_DEFAULT);
        boolean isC = isCurrently(which, EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE);
        boolean isM = isCurrently(which, EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M);
        boolean isS = isCurrently(which, EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S);
        boolean isOff = isCurrently(which, EscalatorSpeedData.PSD_TONE_OFF);
        rows.add(new Row(T_PICK, which, EscalatorSpeedData.PSD_TONE_DEFAULT,
                "默认" + (isDefault ? "  ✓当前" : "")));
        rows.add(new Row(T_PICK, which, EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE,
                "doorclose.ogg" + (isC ? "  ✓当前" : "")));
        rows.add(new Row(T_PICK, which, EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M,
                "mdoorclose.ogg" + (isM ? "  ✓当前" : "")));
        if ("close".equals(which)) {
            rows.add(new Row(T_PICK, which, EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S,
                    "默认只播嘀嘀" + (isS ? "  ✓当前" : "")));
        }
        rows.add(new Row(T_PICK, which, EscalatorSpeedData.PSD_TONE_OFF,
                "不播" + (isOff ? "  ✓当前" : "")));
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /** 这扇门、这一项实际生效的 audioId（镜像里没有 → 默认素材）。 */
    private String currentValue(Level level, String which) {
        EscalatorSpeedData.PsdToneAudio tone =
                level == null ? EscalatorSpeedData.PsdToneAudio.NONE
                        : EscalatorSpeedManager.getClientPsdTone(level, runKey);
        return "open".equals(which) ? tone.open() : tone.close();
    }

    private boolean isCurrently(String which, String value) {
        return value.equals(currentValue(mcLevel(), which));
    }

    /** 点列表里某一行：开关行 → 切换**这一扇门**的子开关；待导入行 → 先导入再设为这一项；否则设为该 id。 */
    private void pick(String which, String audioId, boolean fromFolder) {
        if (TOGGLE_SENTINEL.equals(audioId)) {
            toggleToneEnabled(which);
            return;
        }
        if (fromFolder) {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeLong(runKey);
            buf.writeUtf(which, 32);
            buf.writeUtf(audioId, 128);
            ClientPlayNetworking.send(SmoothLift.IMPORT_FOLDER_PSD_TONE_CHANNEL, buf);
            setStatus("正在从文件夹导入并设为「" + EscalatorSpeedData.psdToneLabel(which) + "」："
                    + truncate(audioId, 16));
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey);
        buf.writeUtf(which, 32);
        buf.writeUtf(audioId, 128);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_TONE_CHANNEL, buf);
        setStatus("已选择「" + EscalatorSpeedData.psdToneLabel(which) + "」：" + truncate(audioLabel(audioId), 20));
        refreshAllAfterPick(which, audioId);
    }

    /** 当前维度这一项子开关是否开着（镜像里没有 → 默认开）。 */
    private boolean isToneEnabled(String which) {
        Level level = mcLevel();
        return level == null || EscalatorSpeedManager.isDoorPsdToneEnabled(level, runKey, which);
    }

    private void toggleToneEnabled(String which) {
        boolean next = !isToneEnabled(which);
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeLong(runKey); // 【1.20】这一扇门
        buf.writeUtf(which, 32);
        buf.writeBoolean(next);
        ClientPlayNetworking.send(SmoothLift.SET_PSD_TONE_SWITCH_CHANNEL, buf);
        setStatus("已把这一扇门的「" + EscalatorSpeedData.psdToneLabel(which) + "」" + (next ? "开启" : "关闭"));
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientPsdDoorLocal(level.dimension(), runKey,
                    t -> t.withToneEnabled(which, next));
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
        // ★【1.20】用 withTone 只换这一端的素材：这条记录还带着这一扇门的开关 / 音量 /
        //   强制等待 / 到站播报，整条重建会把它们一起抹掉。
        EscalatorSpeedManager.applyClientPsdDoorLocal(mc.level.dimension(), runKey,
                t -> t.withTone(which, audioId));
        init();
    }

    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> this.statusText = text);
    }

    private int rowY(int index) {
        return listTop + index * ROW_H - scroll;
    }

    private boolean fullyVisible(int y) {
        return y >= listTop && y + 20 <= listBottom;
    }

    /**
     * 解析输入框里的音量；非法 → null。
     *
     * @param allowUnset 单项音量框：{@code -1} 也接受（= 跟随共用默认，与指令
     *                   {@code /pbmloud open -1} 同义）。
     */
    private Integer parseVolume(String s, boolean allowUnset) {
        if (s == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            if (allowUnset && v == EscalatorSpeedData.PSD_TONE_VOLUME_UNSET) {
                return EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
            }
            if (v < EscalatorSpeedData.HELP_VOLUME_MIN || v > EscalatorSpeedData.HELP_VOLUME_MAX) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 【1.23】开门等待秒数：接受任意 int（(-∞,+∞)）；解析失败 → null。 */
    private Integer parseAnyInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 【1.16】解析「关门提示音强制等待时长」输入框里的秒数；非法 → {@code null}。
     *
     * <p>判据走数据层的 {@link EscalatorSpeedData#clampPsdCloseWaitSeconds} 那**同一组上下限**
     * （而不是另写一遍 {0, 60}）：三处（指令参数 / 这个框 / 存档读回）共用一处常量，
     * 哪天要放宽上限只改一个地方。
     */
    private Integer parseCloseWait(String s) {
        if (s == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            if (v < EscalatorSpeedData.PSD_CLOSE_WAIT_MIN || v > EscalatorSpeedData.PSD_CLOSE_WAIT_MAX) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 【1.17】解析「等待几秒后播放」输入框里的秒数；非法 → {@code null}。
     *
     * <p>★ 判据与 {@link #parseCloseWait} **故意不同**：这里**没有上界**。
     * 用户点名「pbimidium 指令和 ui 允许输入 0 到正无穷的数字 [0,+∞)」⇒
     * 只挡负数与 {@code int} 放不下的位数（{@code parseInt} 自己会抛，落到 {@code null}）。
     */
    private Integer parseMidiumWait(String s) {
        if (s == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            if (v < EscalatorSpeedData.PSD_MIDIUM_WAIT_MIN) {
                return null; // 负的「等待」没有意义
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 【1.21】解析「到站前几秒播放」输入框里的秒数；非法 → {@code null}。
     *
     * <p>★ 判据与 {@link #parseMidiumWait} **正好相反**：这一个**只有上界 0**
     * （用户点名的范围 {@code (-∞, 0]}），没有下界 —— 负得再多都是合法的「提前更多秒」。
     * 正值 = 「晚于到站那一刻」没有意义，判非法（与数据层 {@code clampPsdArriveSeconds} 同口径）。
     */
    private Integer parseArriveLead(String s) {
        if (s == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            if (v > EscalatorSpeedData.PSD_ARRIVE_SECONDS_MAX) {
                return null; // 正值：进站报站不能晚于开门音
            }
            return v;
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
        } else if (page == 3) {
            renderArrivalPage(guiGraphics);
        } else if (page == 4) {
            renderArrivePage(guiGraphics);
        } else if (page == 5) {
            renderNarratePage(guiGraphics);
        } else {
            renderTonePage(guiGraphics);
        }
    }

    private void renderMainPage(GuiGraphics guiGraphics) {
        int cx = this.width / 2;
        // 【1.20】单独算：往下挪一档，不再共用 STATUS_Y（见 STATUS_Y_MAIN 的说明）
        int statusY = mainStatusY();

        // 【1.17】两行输入框的**画出来的标签**（不是控件：用户点名不要任何「确认」按钮，
        //   所以原来那对「设置默认音量 / 应用」「强制等待 / 应用」整体撤掉，只留标签文字）。
        // 【1.22】按用户点名改版式：
        //   ① 「等待秒数:」与「音量:」各自画在自己那个输入框**前面**（到站 / 进站两行都有）；
        //   ② 底部的「默认音量」与「强制等待」从上下两行并成**同一行**。
        int midiumY = LIST_TOP + 10 + ROW_H * 2;
        int arriveY = LIST_TOP + 10 + ROW_H * 3;
        // 【09-28】讲述人那一行**只有**「等待秒数:」（没有「音量:」——见 buildMainPage）。
        int narrateY = LIST_TOP + 10 + ROW_H * 4;
        drawInputLabelAt(guiGraphics, ROW_WAIT_LABEL, cx + 4, midiumY);
        drawInputLabelAt(guiGraphics, ROW_LOUD_LABEL, rowLoudLabelX(cx), midiumY);
        drawInputLabelAt(guiGraphics, ROW_WAIT_LABEL, cx + 4, arriveY);
        drawInputLabelAt(guiGraphics, ROW_LOUD_LABEL, rowLoudLabelX(cx), arriveY);
        drawInputLabelAt(guiGraphics, ROW_WAIT_LABEL, cx + 4, narrateY);
        // 【1.23】底部「默认音量」「强制等待」标签与「总开关：…」状态行已按点名删除。
        //   开门/关门两行的「等待秒数:」「音量:」标签（与广播两行同款版式）。
        int toneRowY0 = LIST_TOP + 10;
        int toneRowY1 = LIST_TOP + 10 + ROW_H;
        drawInputLabelAt(guiGraphics, ROW_WAIT_LABEL, cx + 4, toneRowY0);
        drawInputLabelAt(guiGraphics, ROW_LOUD_LABEL, rowLoudLabelX(cx), toneRowY0);
        drawInputLabelAt(guiGraphics, ROW_WAIT_LABEL, cx + 4, toneRowY1);
        drawInputLabelAt(guiGraphics, ROW_LOUD_LABEL, rowLoudLabelX(cx), toneRowY1);
    }

    /**
     * 把一个标签右对齐画在输入框左边（{@link #INPUT_LABEL_RIGHT} 处）。
     *
     * <p>【1.18】颜色从浅灰（{@code 0xC0C0C0}）改成白：用户点名「ui 里的灰色小字删掉」。
     * 这三个标签是**功能性**的（删掉按钮后，输入框旁边就只剩它们能说明这个框是什么），
     * 所以不删、改成白色；界面上不再有任何灰字。
     */
    /** 【1.22】把一个标签**左对齐**画在指定 x（用于「标签在输入框前面」的版式）。 */
    private void drawInputLabelAt(GuiGraphics guiGraphics, String label, int x, int y) {
        guiGraphics.drawString(this.font, Component.literal(label), x, y + 6, 0xFFFFFF, false);
    }

    private void drawInputLabel(GuiGraphics guiGraphics, String label, int y) {
        int right = this.width / 2 - INPUT_LABEL_RIGHT;
        guiGraphics.drawString(this.font, Component.literal(label),
                right - this.font.width(label), y + 6, 0xFFFFFF, false);
    }

    /**
     * 【1.17】到站播报页：两列 + 中间一条竖线。
     *
     * <p>版式照用户原话：「列表左边是未导入存档的音频，中间一条竖线隔开，
     * 列表右边是已经导入存档的音频」。列首的表头也画在这里（它们不是可点控件）。
     */
    private void renderArrivalPage(GuiGraphics guiGraphics) {
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font,
                Component.literal("站台广播"), cx, 22, 0xFFFFFF);

        // 两列表头（【1.18】颜色从灰改白：用户点名「ui 里的灰色小字删掉」）
        //   ★【1.19】文案跟着左列语义改：「点=导入」而不是「点=导入并选用」——
        //   导入与选用已经是两件事（导入走左列，选用走右列的「选用」按钮）。
        guiGraphics.drawCenteredString(this.font,
                Component.literal("未导入存档"),
                leftColX() + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("已导入存档"),
                rightColX() + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);

        // 中间那条竖线
        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);

        // 【1.18】★ 用户点名「ui 里的灰色小字删掉」⇒ 空列的灰色说明
        //   （「（文件夹里没有待导入的 .ogg）」「（存档里还没有音频）」）整段删掉：
        //   列首表头已经写清了这一列是什么，空着本身就说明「没有」，
        //   再补一行灰字只是把界面糊满。

        // 滚动条
        int rowCount = listRowCount();
        if (maxScroll > 0 && rowCount > 0) {
            int barX = rightColX() + COL_W + 8;
            int trackTop = listTop;
            int trackH = Math.max(ROW_H, listBottom - listTop);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rowCount * ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        // 【1.22】本页用 STATUS_Y_LIST（往下挪）：列表因此多出 ~4 行可见（见 BOTTOM_RESERVE_LIST）。
        int statusY = this.height + STATUS_Y_LIST;
        String info = statusText;
        if (info == null) {
            Level level = mcLevel();
            // 【1.20】这一扇门的到站播报（不是本维度的）
            String id = EscalatorSpeedManager.getDoorPsdMidiumAudio(level, runKey);
            info = EscalatorSpeedData.isPsdMidiumOff(id)
                    ? "这一串门当前：不播"
                    : "这一串门当前：" + truncate(id, 20) + "　等待 "
                    + EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(level, runKey)
                    + " 秒";
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), cx, statusY,
                statusText == null ? 0xFFFFFF : 0xFFFF55);
    }

    private void renderTonePage(GuiGraphics guiGraphics) {
        String which = PAGES[page - 1];
        int cx = this.width / 2;
        guiGraphics.drawCenteredString(this.font,
                Component.literal(psdToneButtonLabel(which)), cx, 22, 0xFFFFFF);

        // 两列表头（与广播页同构；【1.18】颜色从灰改白：用户点名删灰字）
        guiGraphics.drawCenteredString(this.font,
                Component.literal("未导入存档"),
                leftColX() + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("已导入存档"),
                rightColX() + COL_W / 2, LIST_TOP + 2, 0xFFFFFF);

        // 中间那条竖线
        guiGraphics.fill(cx, listTop, cx + 1, listBottom, 0x80FFFFFF);

        // 滚动条（与广播页同一套，按 listRowCount 算）
        int rowCount = listRowCount();
        if (maxScroll > 0 && rowCount > 0) {
            int barX = rightColX() + COL_W + 8;
            int trackTop = listTop;
            int trackH = Math.max(ROW_H, listBottom - listTop);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rowCount * ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        int statusY = this.height + STATUS_Y_LIST;
        String info = statusText;
        if (info == null) {
            Minecraft mc = Minecraft.getInstance();
            String cur = truncate(audioLabel(currentValue(mc.level, which)), 18);
            info = psdToneButtonLabel(which) + "　当前：" + cur;
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), cx, statusY,
                statusText == null ? 0xFFFFFF : 0xFFFF55);
    }

    /**
     * 【1.15 · 第十轮】这一项按**当前设置**到底会不会播人声 —— 判据与播放端**同一套**
     * （{@code PsdChimePlayer.resolveTone} + {@code planClose} 的第一道闸门），不另写一份。
     *
     * <p>存在理由：「默认」和「默认（短）」指向**同一段素材**，界面上极易看混；而选了
     * 「默认（短）」时播放端会在算周期**之前**就 return ⇒「停站时间怎么改都没人声」。
     * 把结论直接摆在档位列表最上面，用户就不用去猜、也不用去翻日志。
     *
     * @return 一行短文案；{@code null} = 不需要显示（还没进世界）
     */
    private String psdVoiceVerdict(String which) {
        // ★ 只在**关门页**显示：开门端的开门素材（dooropen.ogg）本来就没有「播报段」这回事，
        //   在开门页写「只播嘀嘀、不会有人声」会把用户吓一跳（那里根本没有「嘀嘀」）。
        if (!"close".equals(which)) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        String id = currentValue(mc.level, which);
        if (EscalatorSpeedData.PSD_TONE_OFF.equals(id)) {
            return "★ 当前「不播」：这一项不会有任何声音";
        }
        String builtin = EscalatorSpeedData.psdBuiltinKey(which, id);
        int split;
        boolean announce;
        if (builtin != null) {
            split = EscalatorAudioPlayer.bundledAnnounceSplitMs(builtin);
            announce = !EscalatorSpeedData.isPsdBuiltinShort(id);
        } else {
            split = EscalatorAudioPlayer.customAnnounceSplitMs(id,
                    EscalatorSpeedManager.getAudioBytes(mc.level, id));
            announce = true; // 导入素材：只要真有「播报 + 嘀嘀」结构就照播（与 resolveTone 一致）
        }
        if (announce && split > 0) {
            return "★ 会播人声：关门时从素材开头整段起播";
        }
        if (!announce) {
            return "★ 只播嘀嘀、不会有人声 → 想听人声请选下面的「默认」";
        }
        return "★ 只播嘀嘀、不会有人声";
    }

    private static String audioLabel(String id) {
        if (EscalatorSpeedData.PSD_TONE_DEFAULT.equals(id)) {
            return "默认";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE.equals(id)) {
            return "doorclose.ogg";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M.equals(id)) {
            return "mdoorclose.ogg";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S.equals(id)) {
            return "默认只播嘀嘀";
        }
        if (EscalatorSpeedData.PSD_TONE_OFF.equals(id)) {
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
