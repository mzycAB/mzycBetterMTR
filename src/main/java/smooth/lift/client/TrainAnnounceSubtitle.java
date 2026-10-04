package smooth.lift.client;

import smooth.lift.compat.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.PoseStack;
import smooth.lift.compat.GuiGraphics;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 【09-29】讲述人进站播报的**屏幕字幕**（{@code /jsr word on} 打开）。
 *
 * <h2>一、它显示什么（用户点名）</h2>
 * 「开启后讲述人进站播报时，在范围内的玩家屏幕上会出现 mtr 列车内的那种字幕，
 * 显示讲述人播报的文字内容」。于是本类的输入**就是**讲述人念的那一串
 * （{@link PsdChimePlayer} 起播那一刻交给 {@link #show}），样式仿 MTR 列车内
 * 那块到站屏：屏幕下方居中一条**白字**（【09-29 续】用户点名去掉那层黑色背景，
 * 现在只画文字、不画任何底条）。
 *
 * <h2>二、两条消失路径（用户点名）</h2>
 * <ol>
 *   <li><b>玩家离开 round</b> —— 每 tick 拿「**这一串门此刻**离玩家最近的那一扇」到玩家的
 *       **水平 / 垂直**两维距离与 {@link TrainAnnounceSwitch#narrateRoundXz()} /
 *       {@link TrainAnnounceSwitch#narrateRoundY()} 分开比（{@code /jsr round AAA BBB}，
 *       水平 ≥ AAA 或垂直 ≥ BBB 即超出），超出就立刻消失（{@link #tick}）。
 *       ★【09-29 续】基准从「起播那一刻那一扇的固定坐标」改成**现算的本串最近门**：
 *       一串门横跨半个站台，冻住一扇的话，玩家沿站台走到本串另一头就会被误判越界
 *       （用户报「在一个站台屏蔽门串范围内跑跑跳跳就有可能停止播放」）。快照短暂变空时
 *       退回那个固定坐标，所以列车进站那一刻仍然**不会**让字幕闪没。</li>
 *   <li><b>播报完</b> —— text2speech 引擎不给「念完了」的回调（{@code Narrator} 接口
 *       只有 say / clear / destroy，反编译确认），这里按**本机 SAPI 实测标定**的语速
 *       估一个显示时长（固定开销 800ms + 汉字 220ms/字 + 其它 80ms/个 + 标点 400ms/个，
 *       标定过程见 {@link #BASE_MS} 那一组常量的注释），到点自己消失。
 *       ★【09-29 续】旧口径（汉字 200、其它 100、标点不计、无固定开销）把上海档算成 7.8 秒，
 *       而实测要 10.75 秒 ⇒ 字幕「播到一半就消失」（用户报）；香港档凑巧算准。
 *       下一班车开播时也会整条替换。</li>
 * </ol>
 * 开关本身关掉（{@code /jsr word off} / 预设联动）时由 {@link TrainAnnounceSwitch}
 * 顺手调 {@link #hide()}，正在显示的字幕当场撤下。
 * <p>★ 所有显示 / 消失都打一条日志（前缀 {@code [SmoothLift/TrainAnnounce] 屏幕字幕…}），
 * 并点名**原因**（播完 / 离开范围 / 开关关闭）—— 用户点名要日志能对上号。
 *
 * <h2>三、标点 → 空格（用户点名）</h2>
 * 「字幕中显示的标点符号改为空格」：播报词里的「，：。、！？」等标点在字幕里一律画成
 * 一个空格（判据见 {@link #isPunct}，按字符的 Unicode 类别判，中英标点都吃）。
 * 连着的标点折叠成一个空格，避免「终点站：人民路」变成「终点站  人民路」那种大空洞。
 * 换行保留：香港档那两行（中文句 / 英文句）在字幕里仍然是两行 —— MTR 列车内屏
 * 中英双行本来就是上下排的。
 *
 * <h2>四、渲染</h2>
 * 挂在 {@link HudRenderCallback} 上（每帧）：先按屏宽把过长的行折行，再整条居中画。
 * 【09-29 续】**不画背景** —— 只有白字（带一点阴影，衬在游戏画面里也能看清）。
 */
public final class TrainAnnounceSubtitle {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 折行时文字可用的最大宽度（屏幕宽减去两侧各 20px）。 */
    private static final int SIDE_MARGIN = 20;

    /** 字幕底边离屏幕底边的距离（避开物品栏那一条）。 */
    private static final int BOTTOM_MARGIN = 62;

    /**
     * 自然消失的估算时长 —— ★【09-29 续】**按本机 SAPI 实测标定**，不再是拍脑袋的语速。
     *
     * <p>text2speech 引擎不给「念完了」的回调（{@code Narrator} 接口只有 say / clear / destroy，
     * 反编译确认），只能估；那就把**现场这两句原样**丢给同一个 SAPI 语音
     * （本机默认 {@code Microsoft Huihui Desktop, zh-CN, rate 0}，与
     * {@code NarratorWindows} 走的 SpVoice 默认档一致）合成成 wav，量长度：
     * <ul>
     *   <li>上海档「乘客们，列车马上就要进站了，本次列车终点站：江苏北路，请乘客们在4A站台有序候车」
     *       = 34 汉字 + 2 字母 + 5 标点（1 行）⇒ 实测 <b>10.75 秒</b>；</li>
     *   <li>香港档「前往江苏北路的列车即将到达，请先让车上的乘客下车\nThe train to North JiangSu Road
     *       is arriving, please let passengers exit first.」
     *       = 23 汉字 + 78 其它 + 3 标点（2 行）⇒ 实测 <b>13.14 秒</b>。</li>
     * </ul>
     * 旧口径（汉字 200ms、其它 100ms、无固定开销、标点先换成空格所以不计）把上海档算成 7.8 秒 ⇒
     * 字幕「播到一半就消失」（用户报）；香港档凑巧算成 12.9 秒 ≈ 实测 13.1 ⇒ 用户觉得
     * 「差不多正好」。**原因是两档构成不同**：上海档几乎全是汉字且逗号多，香港档一大半是英文 ——
     * 汉字被低估、英文被高估，在英文占大头的那一档里刚好抵消掉了。
     *
     * <p>现在四项（对上面两句的误差都 ≤ 0.3 秒）：
     * <ol>
     *   <li><b>{@link #BASE_MS} 800ms</b> —— 每次发声的固定开销（SAPI 起播/收尾；实测短句里
     *       「一」要 1.13 秒、「hello world」要 1.79 秒，都是这个量级）；</li>
     *   <li><b>{@link #MS_PER_CJK} 220ms/字</b> —— 汉字（实测斜率 215~235ms/字）；</li>
     *   <li><b>{@link #MS_PER_OTHER} 80ms/个</b> —— 其它字符：字母、数字、空格（实测英文斜率
     *       80ms/字符 ≈ 12 字符/秒）；</li>
     *   <li><b>{@link #MS_PER_PUNCT} 400ms/个</b> —— 标点：SAPI 在逗号顿号处要停顿，
     *       旧口径把标点先换成空格了，这笔时间整个漏掉（上海档 5 个标点 ≈ 2 秒，正是差的那一截）。</li>
     * </ol>
     * {@link #MIN_DISPLAY_MS} 1000ms 是短句的下限（留一瞬可读，但不许拖长 ——
     * 用户点名「播报完字幕就要消失，而不是一直留着」）。
     */
    private static final long BASE_MS = 800L;
    private static final long MS_PER_CJK = 220L;
    private static final long MS_PER_OTHER = 80L;
    private static final long MS_PER_PUNCT = 400L;
    private static final long MIN_DISPLAY_MS = 1000L;

    /** 正在显示的行（标点已换空格、已折行）；{@code null} / 空 = 没有字幕。 */
    private static List<String> lines;

    /**
     * 播报声源的位置（那一串门里离玩家最近的那扇的锚点）——「离开 round」判据的**兜底**基准。
     *
     * <p>★【09-29 续】它已经不是主判据了，主判据是 {@link #sourceRunKey} 那一串门**此刻**
     * 离玩家最近的一扇（见 {@link #tick}）。这里留着它，只为「这一串这一刻不在门快照里」时
     * 退回旧口径 —— 列车进站那一瞬快照会短暂变空，那时不该把字幕撤掉。
     */
    private static double sourceX;
    private static double sourceY;
    private static double sourceZ;

    /**
     * 【09-29 续】播报声源的**身份**（那一串门的 {@code runKey}）——「离开 round」判据的主基准。
     *
     * <p>旧版把判据冻在「起播那一刻那一扇门」的坐标上，于是玩家沿站台走到本串另一头就被判
     * 越界（用户报「在一个站台屏蔽门串范围内跑跑跳跳就有可能停止播放」，LOG1 19:05:33
     * 距声源 16.2 格）。现在每 tick 现算「到本串最近一扇」的距离，玩家只要还挨着本串
     * 任意一扇就仍在范围内。
     */
    private static long sourceRunKey = Long.MIN_VALUE;

    /**
     * 【09-30 续 4】正在显示的这条字幕来自哪条讲述人：{@code false} = 进站广播（/jsr round）、
     * {@code true} = 站台广播（/jsr midium round）—— 「离开范围就消失」按各自的范围判。
     */
    private static boolean sourceFromMidium;

    /** 自然消失的那一 tick（起播 tick + 估算时长）。 */
    private static long expireTick = Long.MIN_VALUE;

    /**
     * 【09-29 续】这一条字幕估出来的时长（ms）—— 在 {@link #show} 那一刻按**原始播报词**算一次，
     * 之后 {@link #tick} 判到期、日志报数都用它，免得两处各算一遍算出两个数。
     */
    private static long estimateMs = MIN_DISPLAY_MS;

    private TrainAnnounceSubtitle() {
    }

    /**
     * 【09-29】讲述人起播那一刻把播报词挂上屏幕。
     *
     * <p>★ 只在**字幕开着**时才生效；标点换空格在这里一次做完，渲染端只管画。
     *
     * @param rawText 讲述人正在念的那一串（与 {@link TrainAnnounceNarrator#speak} 同一来源）
     * @param runKey  声源那一串门的身份（{@link PsdDoorTracker.DoorView#runKey()}）——
     *                「离开 round」按「到本串最近一扇」现算，见 {@link #tick}
     * @param x       声源位置 X（那一串门里离玩家最近的那扇；兜底用）
     * @param y       声源位置 Y
     * @param z       声源位置 Z
     * @return 有没有真的挂上（字幕关着 / 文本为空 ⇒ {@code false}）
     */
    public static boolean show(String rawText, long runKey, double x, double y, double z) {
        return show(rawText, runKey, x, y, z, false);
    }

    /**
     * 【09-30 续 4】带来源标记的挂字幕：站台广播（{@code fromMidium = true}）的
     * 「离开范围就消失」用它自己的 /jsr midium round 范围，见 {@link #tick}。
     */
    public static boolean show(String rawText, long runKey, double x, double y, double z, boolean fromMidium) {
        if (rawText == null || rawText.isBlank() || !TrainAnnounceSwitch.isWordEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) {
            return false;
        }
        List<String> raw = new ArrayList<>();
        for (String paragraph : rawText.split("\n")) {
            String cleaned = collapseSpaces(stripPunct(paragraph));
            if (!cleaned.isEmpty()) {
                raw.add(cleaned);
            }
        }
        if (raw.isEmpty()) {
            return false;
        }
        lines = new ArrayList<>();
        for (String paragraph : raw) {
            wrap(mc.font, paragraph, mc.getWindow().getGuiScaledWidth() - SIDE_MARGIN * 2, lines);
        }
        sourceRunKey = runKey;
        sourceX = x;
        sourceY = y;
        sourceZ = z;
        sourceFromMidium = fromMidium;
        // ★【09-29 续】按**原始播报词**估（含标点），不是按屏上那几行 —— 标点在字幕里换成了空格，
        //   但它代表 SAPI 的一次停顿，必须计进去，否则上海档那种逗号多的句子会被算短。
        estimateMs = estimateDisplayMs(rawText);
        if (mc.level != null) {
            expireTick = mc.level.getGameTime() + (estimateMs + 49L) / 50L;
        } else {
            expireTick = Long.MIN_VALUE;
        }
        LOGGER.info("[SmoothLift/TrainAnnounce] 屏幕字幕已显示：{} 行，估算约 {} 秒（{}）",
                lines.size(), estimateMs / 1000L, String.join(" / ", lines));
        return true;
    }

    /** 立刻撤下正在显示的字幕（关开关 / 断线时调用；原因只用于日志）。 */
    public static void hide(String why) {
        if (lines != null) {
            lines = null;
            expireTick = Long.MIN_VALUE;
            LOGGER.info("[SmoothLift/TrainAnnounce] 屏幕字幕已消失（{}）", why);
        }
    }

    /** 立刻撤下（不点名原因的调用点用；换世界 / 关开关都点名更好，能带原因就别用这个）。 */
    public static void hide() {
        hide("字幕被撤下");
    }

    /**
     * 【09-29】每客户端 tick 一次：玩家走出播报范围 / 自然到期 ⇒ 字幕消失。
     *
     * <p>由 {@link PsdChimePlayer#onClientTick} 在每 tick 最前面调（它保证只在游戏内跑）。
     * 越界判据走 {@link TrainAnnounceSwitch#narrateDistances}：现算「到这一串门里离玩家最近
     * 那一扇」的水平（hypot(dx,dz)）与垂直（|dy|）两维距离（玩家沿站台走到本串另一头仍在
     * 范围内），这一串不在快照里时退回起播那扇的固定坐标（列车进站那一刻快照短暂变空
     * 也不会让字幕闪没）；任一维 ≥ 对应范围（/jsr round AAA BBB）即消失。
     */
    public static void tick(Minecraft mc) {
        if (lines == null) {
            return;
        }
        if (mc.level == null || mc.player == null || !TrainAnnounceSwitch.isWordEnabled()) {
            hide(mc.level == null || mc.player == null ? "已离开世界"
                    : "文字模式已不是屏幕字幕（/jsr on <样式> word|chat|off）");
            return;
        }
        // 【09-30】玩家上了 MTR 列车 ⇒ 字幕立刻撤下（用户点名「在列车上不给文字，
        //   无论设置如何」）—— 与讲述人语音的「越界即停」同一条反射判据。
        if (PsdChimePlayer.ridingTrain()) {
            hide("玩家已进入 MTR 列车（讲述人语音与文字一并停掉）");
            return;
        }
        // ① 自然到期（估算的播报时长走完 = 用户点名的「播报完字幕就消失」）
        if (expireTick != Long.MIN_VALUE && mc.level.getGameTime() >= expireTick) {
            hide("播报已播完（估算 " + estimateMs / 1000L + " 秒）");
            return;
        }
        // ② 玩家离开 round —— 与讲述人「超范围不播」是同一条判据（AAA = 水平 x、z 轴，
        //   BBB = 垂直 y 轴；水平 ≥ AAA 或垂直 ≥ BBB 即超出）。
        //   ★【09-30 续 4】进站讲述人用 /jsr round、站台讲述人用 /jsr midium round ——
        //   按这条字幕的来源取对应那份范围。
        int roundXz = sourceFromMidium ? TrainAnnounceSwitch.midiumRoundXz() : TrainAnnounceSwitch.narrateRoundXz();
        int roundY = sourceFromMidium ? TrainAnnounceSwitch.midiumRoundY() : TrainAnnounceSwitch.narrateRoundY();
        TrainAnnounceSwitch.NarrateDistance d = TrainAnnounceSwitch.narrateDistances(
                sourceRunKey, sourceX, sourceY, sourceZ, mc.player.position());
        if (d.horizontal() >= roundXz || d.vertical() >= roundY) {
            hide("玩家已离开播报范围（水平 " + String.format("%.1f", d.horizontal())
                    + " 格 ≥ " + roundXz
                    + " 或垂直 " + String.format("%.1f", d.vertical())
                    + " 格 ≥ " + roundY + " 格）");
        }
    }

    /** 渲染（挂在 {@link HudRenderCallback} 上）：屏幕下方居中一行白字，**不画背景**。 */
    public static void render(PoseStack poseStack, float tickDelta) {
        GuiGraphics guiGraphics = new GuiGraphics(poseStack);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        int lineH = font.lineHeight + 2;
        int textH = lines.size() * lineH - 2;
        int y = mc.getWindow().getGuiScaledHeight() - BOTTOM_MARGIN - textH;
        for (String line : lines) {
            guiGraphics.drawString(font, line,
                    (mc.getWindow().getGuiScaledWidth() - font.width(line)) / 2, y, 0xFFFFFF);
            y += lineH;
        }
    }

    /** 注册 HUD 渲染回调（客户端初始化时调一次）。 */
    public static void register() {
        HudRenderCallback.EVENT.register(TrainAnnounceSubtitle::render);
    }

    /** 断线时撤下字幕（换世界后位置与范围都不再可比）。 */
    public static void onDisconnect() {
        hide("已断开连接");
    }

    // ------------------------------------------------------------------
    // 时长估算（text2speech 没有「念完」回调，只能按语速估，见类注释）
    // ------------------------------------------------------------------

    /**
     * 估算这条播报要念多久（ms）—— 常数与标定过程见上面那一组常量的注释。
     *
     * <p>★【09-29 续】入参是**原始播报词**（含标点、含换行），不是屏上那几行：
     * 标点在字幕里被 {@link #stripPunct} 换成了空格，但它代表 SAPI 的一次停顿，
     * 这笔时间必须计进去（旧口径就是在这里漏掉上海档那 ~2 秒的）。
     * 换行只当空白跳过 —— SAPI 不因为换行额外停顿（香港档两句按同一行算也吻合实测）。
     */
    private static long estimateDisplayMs(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return MIN_DISPLAY_MS;
        }
        int cjk = 0;
        int other = 0;
        int punct = 0;
        for (int i = 0; i < rawText.length(); ) {
            int codePoint = rawText.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\r') {
                continue;
            }
            if (isPunct(codePoint)) {
                punct++;
            } else if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        long ms = BASE_MS + cjk * MS_PER_CJK + other * MS_PER_OTHER + punct * MS_PER_PUNCT;
        return Math.max(MIN_DISPLAY_MS, ms);
    }

    /** 汉字（CJK 统一表意文字 + 扩展 A；假名 / 谚文按「其它字符」算 —— 它们念得跟英文一个量级）。 */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    // ------------------------------------------------------------------
    // 文本处理：标点 → 空格、折叠空白、按屏宽折行
    // ------------------------------------------------------------------

    /**
     * 一个字符算不算**标点**（用户点名「字幕中显示的标点符号改为空格」）。
     *
     * <p>按 Unicode 类别判：所有 Punctuation（连接/破折号/开括/闭括/前引/后引/其它）
     * 以及数学符号（「＋－＝」那类全角符号算字不算标点，但半角 + - = 在播报词里
     * 出现时按标点处理更干净）都算。字母、数字、汉字、空格、换行**不算**。
     */
    private static boolean isPunct(int codePoint) {
        if (codePoint == ' ' || codePoint == '\n' || codePoint == '\t') {
            return false;
        }
        switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    /** 把文本里所有标点逐字换成空格（保留换行）。 */
    private static String stripPunct(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            sb.appendCodePoint(isPunct(codePoint) ? ' ' : codePoint);
        }
        return sb.toString();
    }

    /** 连续空白折叠成一个空格、去首尾（「，、」连标点换出来的一串空格合成一个）。 */
    private static String collapseSpaces(String text) {
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
    }

    /** 按可用宽度贪心折行（优先在空格处断，找不到空格才硬切 —— 中英文都照顾到）。 */
    private static void wrap(Font font, String text, int maxWidth, List<String> out) {
        if (font.width(text) <= maxWidth) {
            out.add(text);
            return;
        }
        String remaining = text;
        while (font.width(remaining) > maxWidth) {
            int hard = Mth.clamp(font.plainSubstrByWidth(remaining, maxWidth).length(), 1, remaining.length());
            int cut = hard;
            // 尽量往前找一个空格断行（只在空格离硬切点不太远时才用它，避免一行只剩两个词）
            int space = remaining.lastIndexOf(' ', hard - 1);
            if (space > hard / 2) {
                cut = space;
            }
            out.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) {
            out.add(remaining);
        }
    }
}
