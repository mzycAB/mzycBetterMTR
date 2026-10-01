package smooth.lift.client;

import com.mojang.text2speech.Narrator;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;

import java.util.List;

/**
 * 【09-28】「讲述人」列车报站 —— 用**文字转语音**把进站广播念出来。
 *
 * <h2>一、为什么不能走原版那个「讲述人」</h2>
 * 原版 {@code net.minecraft.client.GameNarrator} 要出声，必须**同时**满足两条：
 * <ol>
 *   <li>游戏 设置 → 辅助功能 → 讲述人 = 「全部 / 系统」；</li>
 *   <li>本机装了可用的语音合成引擎。</li>
 * </ol>
 * 任何一条不满足就**静默**（1.20.4 的 {@code GameNarrator} 字节码里
 * {@code shouldNarrateSystem()} 与 {@code narrator.active()} 两道闸门）。
 * 用户实测「没有声音」，就是死在这里。
 *
 * <h2>二、「MTR 报站的那个功能」走的是另一个入口</h2>
 * MTR 自己的报站 {@code mtr.client.IDrawing.narrateOrAnnounce} 是这么干的
 * （反汇编 MTR-fabric-4.0.5 确认）：
 * <pre>
 *   Config.getClient().getTextToSpeechAnnouncements() &amp;&amp; !text.isEmpty()
 *       → com.mojang.text2speech.Narrator.getNarrator().say(text, true)
 * </pre>
 * 它**直接向 text2speech 要一个平台讲述人**（Windows 上就是 SAPI，
 * 见 {@code com.mojang.text2speech.NarratorWindows}），**不查**游戏的辅助功能设置 ——
 * 于是「MTR 报站能响、游戏讲述人不响」在同一个语音引擎上并存。
 * 本类照抄的正是这条入口，这就是用户点名的「MTR 报站的那个功能」。
 *
 * <h2>三、它和「自定义进站广播」的关系</h2>
 * ★ 两条**互不相干**的广播，**可以同时存在**（用户原话：「讲述人的进站广播和自定义的
 * 进站广播不是一个广播，可以同时存在」）。所以：
 * <ul>
 *   <li>自定义进站广播设成「不播」→ **只关它自己**，讲述人照念；</li>
 *   <li>讲述人这一条有**两层**开关：全局 {@link TrainAnnounceSwitch}（{@code /jsr on|off}）
 *       **与**每一串门自己的样式（{@code PsdToneAudio.narrate}，石斧 UI 二级页），两者取「与」；</li>
 *   <li>★【09-28 续】时间**也不再借用**：讲述人有**它自己的**窗口
 *       （{@code getDoorPsdNarrateSeconds}），与自定义进站广播各存各的（用户点名
 *       「取消借用进站广播」）。两条广播**唯一**共用的东西是**声源位置与射程**
 *       —— 本来就只有一个声源。窗口判据在 {@link PsdChimePlayer} 里，本类只管
 *       「念什么、念不念得出来」。</li>
 * </ul>
 *
 * <h2>三之二、念什么（【09-28】用户点名）</h2>
 * <pre>
 *   乘客们，列车马上就要进站了，本次列车终点站：XXX，请乘客们在Y站台有序候车
 * </pre>
 * <ul>
 *   <li>{@code X} = 这一班车的**终点站**（{@code ArrivalResponse.getDestination()}）；</li>
 *   <li>{@code Y} = **站台名/编号**（{@code ArrivalResponse.getPlatformName()}）。</li>
 * </ul>
 * 两个值都取自**同一条** {@code ArrivalResponse}（见 {@link MtrDwellAccess#nearestArrival}）——
 * 也就是 MTR 站台显示屏此刻正在显示的那一条 ⇒ 念出来的与玩家抬头看到的那块屏一致。
 *
 * <h2>三之三、「开启(香港)」那一档（【09-28 续】用户点名）</h2>
 * 石斧二级页的三行（自上而下）= {@link EscalatorSpeedData#PSD_NARRATE_OFF} 关闭 /
 * {@link EscalatorSpeedData#PSD_NARRATE_HONGKONG} 开启(香港) /
 * {@link EscalatorSpeedData#PSD_NARRATE_SHANGHAI} 开启(上海)（上一节那一句）。
 * 香港档的句式见 {@link #arriveTextHongKong}：中英双语两行，名字按 MTR 的
 * {@code 中文|English} 约定拆开，只有一种语言时只念那一种，日文/韩文等其它语言只念英文句
 * 并用名字原文。
 * ★★ 【09-28 续 3】香港档吃的**和上海档是同一个字段** —— 本次列车**终点站**
 * （{@code ArrivalResponse.getDestination()}）。两档**只差句式**，不差名源。
 * 播放端用 {@link #arriveTextForStyle} 按样式选句式（只此一个入口）。
 *
 * <h2>四、实现上的三个注意点</h2>
 * <ol>
 *   <li>{@code Narrator.getNarrator()} **每次都新建一个平台讲述人**（JNA COM 对象），
 *       所以这里**只取一次并缓存** —— 报站是低频事件，但没必要每班车造一个 COM 对象；</li>
 *   <li>取不到（Linux 上没有 flite、引擎初始化失败……）时返回的是
 *       {@code Narrator.EMPTY}，它的 {@code active()} 是 {@code false} —— 用它当判据，
 *       失败只打**一条**诊断日志，不刷屏、不影响其它功能；</li>
 *   <li>{@code say(text, true)} 的 {@code true} = 先清空再念（= MTR 的写法）。
 *       同一时刻两个站台同时报站时，后来的会把前一句掐掉 —— 这是刻意的：
 *       报站要的是「最近这一条」，排队念反而会拖成十几秒的延迟。</li>
 * </ol>
 */
public final class TrainAnnounceNarrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 【09-28】报站词的**开头**。后面按「拿得到什么」接两句，最终句式就是用户点名的：
     * <pre>
     *   乘客们，列车马上就要进站了，本次列车终点站：XXX，请乘客们在Y站台有序候车
     * </pre>
     */
    private static final String ARRIVE_OPENING = "乘客们，列车马上就要进站了";

    /** 终点站那一句的前缀（X = {@code ArrivalResponse.getDestination()}）。 */
    private static final String TERMINUS_LEAD = "，本次列车终点站：";

    /** 站台那一句的前缀（Y = {@code ArrivalResponse.getPlatformName()}）。 */
    private static final String PLATFORM_LEAD = "，请乘客们在";

    /** 站台那一句的尾巴。★ 站台名本身已经含「站台」（用户把站台名写成「1站台」）时不接这个。 */
    private static final String PLATFORM_TAIL = "站台有序候车";

    /** 站台名那一句在「站台名已经含『站台』」时的尾巴（避免念成「1站台站台有序候车」）。 */
    private static final String PLATFORM_TAIL_BARE = "有序候车";

    /**
     * 站台名读不到时的兜底句 —— **整句换成「请乘客们有序候车」**，而不是留下
     * 「请乘客们在站台有序候车」这种缺主语的半截话。
     */
    private static final String NO_PLATFORM_TAIL = "，请乘客们有序候车";

    // ------------------------------------------------------------------
    // 【09-28 续】「开启(香港)」那一档的句式（用户点名）：
    //
    //   前往XXX的列车即将到达，请先让车上的乘客下车
    //   The train to YYY is arriving, please let passengers exit first.
    //
    //   X = 终点站的**中文名**、Y = 终点站的**英文名**（都从「终点站」那一格里拆，
    //   见 arriveTextHongKong）。
    //   ★★ 【09-28 续 3】XXX / YYY = **本次列车终点站**，与上海档同一个字段
    //     （{@code ArrivalResponse.getDestination()}）—— 「前往XX的列车」/「The train
    //     to XX」的 XX 在语义上就是这班车要去的**终点站**，不是玩家所在的站。
    //     ★ 上一版曾误用 {@code Station.getName()}（车站名 = 玩家脚下那个站），
    //     于是把「前往江苏北路」念成了「前往火车站」——用户报的就是这个。
    //   ★ 终点站从 MTR 里读到的是**一个字符串**（{@code ArrivalResponse.getDestination()}
    //     原样返回，不做语言处理）：中英双语靠 MTR 自己的约定「中文|English」用 {@code |}
    //     分隔（MTR 自己也按 {@code \\|} 拆，见 RenderPIDS /
    //     SimplifiedRoutePlatform.getStationName 那段轮播）。
    // ------------------------------------------------------------------

    /** 香港样式：中文句的前缀（后面接终点站**中文名**）。 */
    private static final String HK_CN_LEAD = "前往";

    /** 香港样式：中文句的尾巴。 */
    private static final String HK_CN_TAIL = "的列车即将到达，请先让车上的乘客下车";

    /** 香港样式：英文句的前缀（后面接终点站**英文名**）。 */
    private static final String HK_EN_LEAD = "The train to ";

    /** 香港样式：英文句的尾巴（★ 用户点名的那一句，含句末的句点）。 */
    private static final String HK_EN_TAIL = " is arriving, please let passengers exit first.";

    /** 香港样式两句话之间的分隔（用户把这两句写成了两行 ⇒ 拼成带换行的一句交给语音引擎）。 */
    private static final String HK_LINE_BREAK = "\n";

    /** 名字里分隔「中文名 / 英文名」的记号（= MTR 自己的约定，拆的时候要转义成正则 {@code \\|}）。 */
    private static final String NAME_SPLIT_REGEX = "\\|";

    /** 缓存住的平台讲述人；{@code null} = 还没取过。 */
    private static Narrator narrator;

    /** 「已经试过取讲述人了」——失败不再重试（引擎不会中途出现，重试只会刷日志）。 */
    private static boolean tried;

    /** 「引擎不可用」这条诊断只打一次。 */
    private static boolean warnedUnavailable;

    private TrainAnnounceNarrator() {
    }

    /**
     * 【09-28】拼这一条进站广播的报站词。
     *
     * <p>句式（用户点名）：
     * <pre>
     *   乘客们，列车马上就要进站了，本次列车终点站：XXX，请乘客们在Y站台有序候车
     * </pre>
     * <ul>
     *   <li>{@code X} = 本次列车**终点站**（{@code ArrivalResponse.getDestination()}）；</li>
     *   <li>{@code Y} = **站台名/编号**（{@code ArrivalResponse.getPlatformName()}，
     *       即 {@code Platform.getName()}）。</li>
     * </ul>
     *
     * <p>★【09-29】X / Y 都先过 {@link #chineseOnlyName}：上海档**只念中文名**
     * （用户点名「本次列车开往人民路People road」改为「本次列车开往人民路」——
     * MTR 的双语字段 {@code 中文|English} 整串喂给 TTS 会把英文名也一起念出来）。
     * 名字里没有中文段时保持原文，不丢名字。
     *
     * <p>★ 两个值各自读不到时**只删自己那一小句**，剩下的话仍然是一句完整、能念的中文
     * （不会出现「终点站：，请乘客们在」这种读出来像卡带的半截话）：
     * <ol>
     *   <li>终点站读不到 ⇒ 省掉「，本次列车终点站：X」，句式变成
     *       「乘客们，列车马上就要进站了，请乘客们在Y站台有序候车」；</li>
     *   <li>站台名读不到 ⇒ 尾句整句换成「，请乘客们有序候车」。</li>
     * </ol>
     *
     * @param destination  本次列车终点站；{@code null} / 空白 = 读不到
     * @param platformName 站台名/编号；{@code null} / 空白 = 读不到
     */
    public static String arriveText(String destination, String platformName) {
        StringBuilder text = new StringBuilder(ARRIVE_OPENING);
        String terminus = trimToNull(destination);
        if (terminus != null) {
            text.append(TERMINUS_LEAD).append(chineseOnlyName(terminus));
        }
        String platform = trimToNull(platformName);
        if (platform == null) {
            text.append(NO_PLATFORM_TAIL);
        } else {
            // 站台名同样只取中文名（双语「1号|No.1」之类的字段整串念会带出英文）。
            platform = chineseOnlyName(platform);
            text.append(PLATFORM_LEAD).append(platform);
            // 站台名已经自带「站台」二字（「1站台」）时就别再补一遍，否则会念成「1站台站台」。
            text.append(platform.contains("站台") ? PLATFORM_TAIL_BARE : PLATFORM_TAIL);
        }
        return text.toString();
    }

    /**
     * 【09-29】从 MTR 的双语名字字段里取**中文名**（上海档专用，用户点名）。
     *
     * <p>拆法与 {@link #arriveTextHongKong} 同源（{@code 中文|English} 按 {@code |} 分段），
     * 但这里**只取第一段中文**、不要英文段。整串里没有中文段（纯英文 / 其它语言）时
     * 返回原文 —— 宁可念英文，也不能把名字整个丢掉。
     */
    private static String chineseOnlyName(String rawName) {
        for (String part : rawName.split(NAME_SPLIT_REGEX)) {
            String piece = trimToNull(part);
            if (piece != null && isChinese(piece)) {
                return piece;
            }
        }
        return rawName.trim();
    }

    /**
     * 【09-28 续】拼「开启(香港)」那一档的报站词（用户点名）：
     * <pre>
     *   前往XXX的列车即将到达，请先让车上的乘客下车
     *   The train to YYY is arriving, please let passengers exit first.
     * </pre>
     *
     * <p><b>XXX / YYY 取哪一个名字 —— ★★ 【09-28 续 3】最后定案</b>：
     * **本次列车终点站**（{@code ArrivalResponse.getDestination()}），
     * <b>与上海档同一个字段</b>。两档**只差句式，不差名源**。
     * <ol>
     *   <li><b>语义上就是它</b>：「前往XX的列车」/「The train to XX」的 XX 只能是这班车
     *       要去的终点站 —— 念成玩家脚下那个站名的话，句子的意思就变成了
     *       「有一班车要去你所在的这个站」，那是另一句话。</li>
     *   <li>★★ <b>别再动这个名源</b>。曾经踩过两次，都记在这里免得再踩：
     *       <ul>
     *         <li>喂 {@code ArrivalResponse.getPlatformName()}（{@code Platform.getName()}，
     *             通常是个编号）⇒ 拆不出双语，只念出英文半句
     *             （「The train to 4A is arriving…」）；</li>
     *         <li>喂 {@code Station.getName()}（玩家**所在站**的站名）⇒ 双语是有了，但主语错，
     *             把「前往江苏北路」念成「前往火车站」（用户报的那个 bug）。</li>
     *       </ul>
     *       ⇒ 站台名 / 车站名**都不是**这里的名源，终点站才是。</li>
     *   <li>终点站在 MTR 里是**一个字符串**，中英双语是它自己的约定
     *       {@code 中文名|English name}，用 {@code |} 分隔。反汇编确认：{@code RenderPIDS}
     *       里就是 {@code SimplifiedRoutePlatform.getStationName().split("\\|")}，再按
     *       {@code (tick / 60) % 段数} **轮播**这些段 ⇒ 这就是 MTR 呈现双语名的方式
     *       （{@code NameColorDataBase.getName()} 原样返回 {@code name} 字段、不做语言处理）。</li>
     * </ol>
     *
     * <p><b>拿到终点站名之后怎么拆成 XXX / YYY</b>：
     * <ol>
     *   <li>按 {@code |} 拆成若干段，逐段去首尾空白；</li>
     *   <li>**中文段** = 含汉字、且**不含假名 / 谚文**的那一段（这一条把日文、韩文挡在外面）；</li>
     *   <li>其余段 = 英文段（纯拉丁、纯数字、西里尔 / 阿拉伯……都归这里）。</li>
     * </ol>
     *
     * <p><b>用户点名的三条规矩，本方法逐条落地</b>：
     * <ol>
     *   <li>名字**只有中文** ⇒ 只念中文句（没有英文段 ⇒ 英文句整句不接）；</li>
     *   <li>**只有英文** ⇒ 只念英文句（没有中文段 ⇒ 中文句整句不接）；</li>
     *   <li>**其它语言**（日文 / 韩文……）⇒ 只念英文句，且用的是**名字本身**
     *       （用户给的 {@code ZZZ}）—— 它天然落进「英文段」那个桶，无需特判。</li>
     * </ol>
     * ★ 已知边界：**纯汉字**写的日文站名（例：「東京」）在字形上与中文无从区分 ⇒ 会被当成中文段；
     * 带假名 / 谚文的日韩站名都能正确判成「其它语言」。这是**字形判据的固有上限**（MTR 自己判
     * 「含不含 CJK」也是同一性质），不是漏写。
     *
     * @param destination **本次列车终点站**（MTR {@code 中文|English} 那个字段）；
     *                    {@code null} / 空白 ⇒ 返回 {@code null}（两句都要有名字才拼得出来）
     */
    public static String arriveTextHongKong(String destination) {
        String raw = trimToNull(destination);
        if (raw == null) {
            return null;
        }
        String chinese = null;
        String foreign = null;
        for (String part : raw.split(NAME_SPLIT_REGEX)) {
            String piece = trimToNull(part);
            if (piece == null) {
                continue;
            }
            if (isChinese(piece)) {
                if (chinese == null) {
                    chinese = piece;
                }
            } else if (foreign == null) {
                foreign = piece;
            }
        }
        if (chinese == null && foreign == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (chinese != null) {
            text.append(HK_CN_LEAD).append(chinese).append(HK_CN_TAIL);
        }
        if (foreign != null) {
            if (text.length() > 0) {
                text.append(HK_LINE_BREAK);
            }
            text.append(HK_EN_LEAD).append(foreign).append(HK_EN_TAIL);
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    // 【09-30 续】玩家自定义播报词的**占位符**（用户点名）：
    //
    //   |     → 换行（写模板时敲一个 | 就是换一行，SAPI 念两句、字幕分两行、聊天框发两条）
    //   |SC|  → 当前**车站**名字的中文段（S = Station，C = Chinese）
    //   |SE|  → 当前车站名字的英文段（S = Station，E = English）
    //   |DC|  → 当前**站台**名（编号）的中文段（D = platform Desk，C = Chinese）
    //   |DE|  → 当前站台名（编号）的英文段（D = platform Desk，E = English）
    //
    //   「当前车站」= 屏蔽门所在的那个 MTR 车站（MtrDwellAccess.stationNameForPlatform），
    //   **不是**本次列车终点站；「当前站台」= ArrivalResponse.getPlatformName()
    //   （上海档句式里 Y 那一格，同一个名源）。
    //   名字按 MTR 的「中文|English」约定拆段；请求的那一段不存在时**替成空串**
    //   （句子的完整性由写模板的人负责 —— 宁可短一截，也不把英文塞进「中文段」的槽位）。
    //   ★ 替换顺序是**先长后短**：先换四个长记号、最后把剩下的单个 | 换成换行 ——
    //     反过来的话 |SC| 会被拆成「换行 SC 换行」，记号就再也认不出来了。
    // ------------------------------------------------------------------

    /** 占位符：当前车站名字的中文段。 */
    static final String TOKEN_STATION_CN = "|SC|";
    /** 占位符：当前车站名字的英文段。 */
    static final String TOKEN_STATION_EN = "|SE|";
    /** 占位符：当前站台名（编号）的中文段。 */
    static final String TOKEN_PLATFORM_CN = "|DC|";
    /** 占位符：当前站台名（编号）的英文段。 */
    static final String TOKEN_PLATFORM_EN = "|DE|";
    /**
     * 【09-30 续 2】占位符：**本次列车目的地（终点站）**的中文段（W = Where to，C = Chinese）。
     * 与上海档句式里 X 那一格同一个名源（{@code ArrivalResponse.getDestination()}）。
     */
    static final String TOKEN_DEST_CN = "|WC|";
    /** 【09-30 续 2】占位符：本次列车目的地的英文段。 */
    static final String TOKEN_DEST_EN = "|WE|";
    /**
     * 【09-30 续 2】占位符：**当前线路**名字的中文段（L = Line，C = Chinese）。
     * 线路 = 目标站台所属的那条 {@code Route}（见 {@code MtrDwellAccess.lineNameForPlatform}）。
     */
    static final String TOKEN_LINE_CN = "|LC|";
    /** 【09-30 续 2】占位符：当前线路名字的英文段。 */
    static final String TOKEN_LINE_EN = "|LE|";
    /** 占位符：换行（长记号都换完之后，剩下的单个 | 全是它）。 */
    static final String TOKEN_LINE_BREAK = "|";

    /**
     * 【09-30 续】把玩家自定义播报词里的占位符换成**这一班车这一站台**的实际值。
     *
     * <p>八个长记号（车站 / 站台 / 目的地 / 线路 × 中英）都换完之后，剩下的单个 |
     * 全部是用户点名的「换行」。换完之后若整串只剩空白（模板是空的 / 全是没值可替的记号）
     * 返回 {@code null}，调用方按「这一档拼不出话」跳过。原样文字（不含任何记号）原样通过。
     *
     * @param template     玩家写的模板（{@code TrainAnnounceSwitch.userText} 的原文）
     * @param stationName  当前车站的双语名字（{@code 中文|English}）；读不到 = {@code null}
     * @param platformName 当前站台名（编号）的双语名字；读不到 = {@code null}
     * @param destination  本次列车目的地（终点站）的双语名字；读不到 = {@code null}
     * @param lineName     当前线路的双语名字；读不到 = {@code null}
     * @return 要念的整句（记号已替换、| 已成换行）；{@code null} = 空模板 / 全空白
     */
    public static String expandUserTemplate(String template, String stationName, String platformName,
                                            String destination, String lineName) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String text = template;
        text = text.replace(TOKEN_STATION_CN, namePart(stationName, true));
        text = text.replace(TOKEN_STATION_EN, namePart(stationName, false));
        text = text.replace(TOKEN_PLATFORM_CN, namePart(platformName, true));
        text = text.replace(TOKEN_PLATFORM_EN, namePart(platformName, false));
        text = text.replace(TOKEN_DEST_CN, namePart(destination, true));
        text = text.replace(TOKEN_DEST_EN, namePart(destination, false));
        text = text.replace(TOKEN_LINE_CN, namePart(lineName, true));
        text = text.replace(TOKEN_LINE_EN, namePart(lineName, false));
        // 长记号都换完，剩下的单个 | 全部是用户点名的「换行」。
        text = text.replace(TOKEN_LINE_BREAK, "\n");
        return trimToNull(text.replace("\r", ""));
    }

    /**
     * 【09-30 续】从 MTR 的双语名字字段（{@code 中文|English}）里取**指定语言**的那一段。
     *
     * <p>拆法与 {@link #arriveTextHongKong} 同源：按 {@code |} 分段，中文段 = 含汉字且
     * 不含假名 / 谚文（日韩站名落到英文桶），其余段都算英文段；各取**第一段**。
     * 请求的那一段不存在时返回**空串**（占位符替成空，见 {@link #expandUserTemplate}）。
     */
    private static String namePart(String rawName, boolean chinese) {
        String raw = trimToNull(rawName);
        if (raw == null) {
            return "";
        }
        for (String part : raw.split(NAME_SPLIT_REGEX)) {
            String piece = trimToNull(part);
            if (piece != null && isChinese(piece) == chinese) {
                return piece;
            }
        }
        return "";
    }

    /**
     * 【09-28 续】按**样式**选句式 —— 播放端只有这一个入口，免得再各处写一遍 switch。
     *
     * <p>★★ 【09-28 续 3】两档**只差句式，不差名源**：
     * <ul>
     *   <li>两档的「车是哪一班」都取 {@code destination} = **本次列车终点站**
     *       （{@code ArrivalResponse.getDestination()}）；</li>
     *   <li>上海档**多要一个** {@code platformName} = **站台名/编号**
     *       （{@code ArrivalResponse.getPlatformName()}）—— 它的句式里有
     *       「请乘客们在{@code Y}站台有序候车」，{@code Y} 必须是**站台**；</li>
     *   <li>香港档的句式里没有站台，所以**不需要**站台名。</li>
     * </ul>
     * ★【09-30】新增**玩家自定义档**（userN，档位号 ≥ {@link EscalatorSpeedData#PSD_NARRATE_USER_BASE}）：
     * 玩家写的模板先过 {@link #expandUserTemplate} —— {@code |} 换行、{@code |SC|}/{@code |SE|}
     * 当前车站中英文名、{@code |DC|}/{@code |DE|} 当前站台中英文名、【09-30 续 2】
     * {@code |WC|}/{@code |WE|} 本次列车目的地中英文名、{@code |LC|}/{@code |LE|} 当前线路中英文名
     * （都是用户点名）。换完是空的（没写 / 全是没值可替的记号）⇒ 返回 {@code null}，调用方跳过。
     *
     * @param mode         讲述人样式（{@link EscalatorSpeedData#PSD_NARRATE_OFF} /
     *                     {@code PSD_NARRATE_SHANGHAI} / {@code PSD_NARRATE_HONGKONG} / userN 档）
     * @param destination  本次列车终点站（**两档共用**；{@code null} / 空白 = 读不到）
     * @param platformName 站台名/编号（上海档与 userN 的 {@code |DC|}/{@code |DE|} 用）
     * @param stationName  当前车站名（**只 userN 的 {@code |SC|}/{@code |SE|} 用**；{@code null} = 读不到）
     * @param lineName     当前线路名（**只 userN 的 {@code |LC|}/{@code |LE|} 用**；{@code null} = 读不到）
     * @return 要念的整句；{@code null} = 这一档拼不出话 ⇒ 调用方跳过
     */
    public static String arriveTextForStyle(int mode, String destination, String platformName,
                                            String stationName, String lineName, List<String> userTexts) {
        // 【09-30】玩家自定义档排在最前面判 —— 占位符吃的是**这一班**的实际值。
        //   【10-01 续】userN 模板**按调用方给的那份列表**取：进站讲述人传进站那份、
        //   站台讲述人传站台那份 —— 两条广播的文字各自独立（用户点名「不要共用一个文字列表」）。
        if (EscalatorSpeedData.isPsdNarrateUserStyle(mode)) {
            int idx = EscalatorSpeedData.psdNarrateUserIndex(mode);
            String template = userTexts == null || idx < 0 || idx >= userTexts.size()
                    ? null : userTexts.get(idx);
            return expandUserTemplate(template,
                    stationName, platformName, destination, lineName);
        }
        if (mode == EscalatorSpeedData.PSD_NARRATE_HONGKONG) {
            return arriveTextHongKong(destination);
        }
        return arriveText(destination, platformName);
    }

    /**
     * 【09-28 续】这一段站台名算不算**中文**？（= 含汉字，且**不含**假名 / 谚文）
     *
     * <p>「不含假名 / 谚文」这一条是关键：日文夹汉字（「東京駅」）、韩文夹汉字（「서울驛」）
     * 都因此判成「其它语言」，于是照用户规矩只念英文句。
     */
    private static boolean isChinese(String text) {
        boolean hasHan = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isKana(codePoint) || isHangul(codePoint)) {
                return false;
            }
            if (isHan(codePoint)) {
                hasHan = true;
            }
        }
        return hasHan;
    }

    /** 汉字（CJK 统一表意文字 + 扩展 A + 兼容区，外加「々」「〇」）。 */
    private static boolean isHan(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || codePoint == 0x3005 || codePoint == 0x3007;
    }

    /** 假名（平假名 / 片假名 / 片假名扩展 / 半角片假名）。 */
    private static boolean isKana(int codePoint) {
        return (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0x31F0 && codePoint <= 0x31FF)
                || (codePoint >= 0xFF66 && codePoint <= 0xFF9D);
    }

    /** 谚文（字母 / 兼容字母 / 扩展 A / 音节 / 半角）。 */
    private static boolean isHangul(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3130 && codePoint <= 0x318F)
                || (codePoint >= 0xA960 && codePoint <= 0xA97F)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
                || (codePoint >= 0xFFA0 && codePoint <= 0xFFDC);
    }

    /** {@code null} / 空白 → {@code null}；其余去掉首尾空白。 */
    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 【09-28】把一句话念出来。
     *
     * @return {@code true} = 真的交给语音引擎了；{@code false} = 本机没有可用引擎（静默跳过）
     */
    public static boolean speak(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Narrator n = narrator();
        if (n == null) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                LOGGER.warn("[SmoothLift/TrainAnnounce] 本机取不到文字转语音引擎 ⇒ "
                        + "讲述人报站不会出声（其余功能照常）。"
                        + "MTR 报站用的是同一个引擎，它不响这里也不会响");
            }
            return false;
        }
        try {
            n.say(text, true);
            return true;
        } catch (Throwable t) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                LOGGER.warn("[SmoothLift/TrainAnnounce] 讲述人报站念不出声（{}）；"
                        + "后续不再重复报错", t.toString());
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 【09-29】「越界即停」：玩家走出 /jsr round 范围 ⇒ 正在念的话立刻停。
    //   text2speech 引擎不给回调，但它有 clear()（= 掐掉当前这句，与游戏切讲述人
    //   档位用的是同一个入口）。谁在念、声源在哪由 speak(text, runKey, x, y, z) 记账，
    //   tickRangeGuard 每客户端 tick 核一次「到本串最近一扇」的距离
    //   ★【09-29 续】判据从「起播那扇的固定坐标」改成**现算本串最近门**，理由见下。
    // ------------------------------------------------------------------

    /**
     * 这一班正在念的话的声源位置（{@link #speak(String, long, double, double, double)} 记下的）。
     * ★【09-29 续】它只是**兜底**基准，主基准是下面那个 {@link #activeRunKey}。
     */
    private static double activeX;
    private static double activeY;
    private static double activeZ;

    /**
     * 【09-29 续】声源的**身份**（那一串门的 {@code runKey}）——「越界即停」的主基准。
     *
     * <p>旧版只认「起播那一刻那一扇门」的固定坐标，于是玩家沿站台走到本串另一头就被判越界、
     * 当场掐掉（用户报「在一个站台屏蔽门串范围内跑跑跳跳就有可能停止播放」，
     * LOG1 19:05:33 距声源 16.2 格）。现在每 tick 现算「到本串最近一扇」的距离。
     */
    private static long activeRunKey = Long.MIN_VALUE;

    /** 有没有一句话还在念（真的交给了引擎才算 —— 引擎不可用时不给护栏记账）。 */
    private static boolean active;

    /**
     * 【09-30 续 4】正在念的这句来自哪条讲述人：{@code false} = 进站广播（用 /jsr round 的范围）、
     * {@code true} = 站台广播（用 /jsr midium round 的范围）—— 两份范围各管各的。
     */
    private static boolean activeFromMidium;

    /**
     * 【09-29】带声源坐标的念：交给引擎成功后给「越界即停」护栏记账。
     *
     * <p>护栏之所以放在这里而不是播放端，是因为「正在念」这件事只有本类知道
     * （{@code Narrator} 是本类缓存的私有状态）。
     *
     * @param runKey 声源那一串门的身份（{@link PsdDoorTracker.DoorView#runKey()}）——
     *               护栏按「到本串最近一扇」现算距离，见 {@link #tickRangeGuard}
     * @param x      起播那一刻最近那扇门的锚点 X（这一串不在快照里时兜底）
     * @param y      同上，Y
     * @param z      同上，Z
     * @return {@code true} = 真的交给语音引擎了；{@code false} = 引擎不可用（静默跳过）
     */
    public static boolean speak(String text, long runKey, double x, double y, double z) {
        return speak(text, runKey, x, y, z, false);
    }

    /**
     * 【09-30 续 4】带「来自哪条讲述人」标记的念：站台广播（{@code fromMidium = true}）的
     * 「越界即停」护栏要用它自己的 /jsr midium round 范围，见 {@link #tickRangeGuard}。
     */
    public static boolean speak(String text, long runKey, double x, double y, double z, boolean fromMidium) {
        boolean spoke = speak(text);
        if (spoke) {
            activeRunKey = runKey;
            activeX = x;
            activeY = y;
            activeZ = z;
            active = true;
            activeFromMidium = fromMidium;
        }
        return spoke;
    }

    /**
     * 【09-29】每客户端 tick 核一次：玩家到**这一串门此刻最近的一扇**的水平距离 ≥
     * {@link TrainAnnounceSwitch#narrateRoundXz()} 或垂直距离 ≥
     * {@link TrainAnnounceSwitch#narrateRoundY()}（/jsr round AAA BBB：AAA = 水平
     * x、z 轴，BBB = 垂直 y 轴，任一超出即停）⇒ 立刻 clear()，
     * 用户点名的「一旦玩家超出范围就停止播报」。
     *
     * <p>★【09-29 续】判据由 {@link TrainAnnounceSwitch#narrateDistances} 现算 ——
     * 一串门横跨半个站台，只认「起播那一刻那一扇」的话，玩家沿站台走到本串另一头
     * （离起播那扇已 &gt; round，却离本串另一扇只有两三格）就会被误判越界、
     * 当场掐掉正在念的一句（用户报「在站台屏蔽门串范围内跑跑跳跳就停止播放」）。
     *
     * <p>由 {@link PsdChimePlayer#onClientTick} 在每 tick 最前面调（进不进世界都调 ——
     * 没进世界时直接复位）。
     */
    public static void tickRangeGuard(Minecraft mc) {
        if (!active) {
            return;
        }
        if (mc == null || mc.player == null) {
            active = false;
            return;
        }
        // 【09-30】玩家上了 MTR 列车 ⇒ 正在念的当场掐掉 —— 用户点名「玩家在 mtr 列车上时
        //   不给该玩家播放讲述人语音和文字，无论设置如何；进列车就停止播放（效果差不多
        //   等同于走出 /jsr round 范围）」。判据与列车内音量衰减共用同一个反射口
        //   （PsdChimePlayer.ridingTrain，读 MTR 的 VehicleRidingMovement.ridingVehicleId）。
        if (PsdChimePlayer.ridingTrain()) {
            active = false;
            LOGGER.info("[SmoothLift/TrainAnnounce] 玩家已进入 MTR 列车 ⇒ 停止讲述人播报"
                    + "（下车后恢复；文字那条由 TrainAnnounceSubtitle.tick 同步撤下）");
            clearNow();
            return;
        }
        // ① 基准是「这一串门**此刻**离玩家最近的一扇」（现算），不是起播那一刻冻住的那一扇 ——
        //   否则玩家沿站台走到本串另一头就会被误判越界、当场掐掉（用户报的那个 bug）。
        //   这一串不在快照里时退回固定坐标（见 TrainAnnounceSwitch#narrateDistances）。
        // ② 【1.29.1204】水平 / 垂直两维分开比：水平 ≥ 水平范围 或 垂直 ≥ 垂直范围即停（用户点名）。
        //   【09-30 续 4】进站讲述人用 /jsr round，站台讲述人用 /jsr midium round —— 两份范围。
        int roundXz = activeFromMidium ? TrainAnnounceSwitch.midiumRoundXz() : TrainAnnounceSwitch.narrateRoundXz();
        int roundY = activeFromMidium ? TrainAnnounceSwitch.midiumRoundY() : TrainAnnounceSwitch.narrateRoundY();
        TrainAnnounceSwitch.NarrateDistance d = TrainAnnounceSwitch.narrateDistances(
                activeRunKey, activeX, activeY, activeZ, mc.player.position());
        if (d.horizontal() >= roundXz || d.vertical() >= roundY) {
            active = false;
            LOGGER.info("[SmoothLift/TrainAnnounce] 玩家距本串最近的门的水平 {} 格 / 垂直 {} 格，"
                            + "已超出{}的水平 {} 格或垂直 {} 格 ⇒ 停止播报",
                    String.format("%.1f", d.horizontal()), String.format("%.1f", d.vertical()),
                    activeFromMidium ? " /jsr midium round" : " /jsr round", roundXz, roundY);
            clearNow();
        }
    }

    /** 断线 / 换世界时复位护栏（声音随世界一起没了，不需要再 clear）。 */
    public static void onDisconnect() {
        active = false;
    }

    /** 掐掉当前正在念的那一句（引擎不可用 / 从没念出过时是空操作）。 */
    private static void clearNow() {
        Narrator n = narrator;
        if (n == null) {
            return;
        }
        try {
            n.clear();
        } catch (Throwable t) {
            LOGGER.warn("[SmoothLift/TrainAnnounce] 停止播报失败（{}）", t.toString());
        }
    }

    /**
     * 取一次平台讲述人并缓存。
     *
     * <p>{@code Narrator.getNarrator()} 内部按操作系统挑实现（Windows = SAPI / macOS =
     * NSSpeechSynthesizer / Linux = flite），任一路失败都返回 {@code EMPTY}
     * （{@code active() == false}）⇒ 这里统一判成「没有引擎」。
     */
    private static Narrator narrator() {
        if (narrator != null) {
            return narrator;
        }
        if (tried) {
            return null;
        }
        tried = true;
        try {
            Narrator n = Narrator.getNarrator();
            if (n == null || !n.active()) {
                return null;
            }
            narrator = n;
            LOGGER.info("[SmoothLift/TrainAnnounce] 已接上文字转语音引擎（MTR 报站用的同一个）"
                    + " ⇒ 讲述人进站报站可以出声");
            return narrator;
        } catch (Throwable t) {
            LOGGER.warn("[SmoothLift/TrainAnnounce] 初始化文字转语音引擎失败：{}", t.toString());
            return null;
        }
    }
}
