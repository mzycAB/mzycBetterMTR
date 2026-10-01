package smooth.lift.client;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * 【1.29.1204】「讲述人」列车报站的开关 —— 指令 {@code /jsr on|off}；
 * 【09-29 续】再添两个子指令：{@code /jsr round AAA BBB}（播报范围）与 {@code /jsr word on|off}（字幕）。
 *
 * <h2>零点五、【09-30】指令改版（用户点名，整套替换旧的 {@code /jsr word on|off}）</h2>
 * <pre>
 *   /jsr                          查看当前状态
 *   /jsr on                       开启讲述人语音（样式 / 文字模式都不动）
 *   /jsr off                      关闭讲述人语音
 *   /jsr on &lt;样式&gt;               开启 + 选**全局样式**：
 *                                   default    = 交还给每一串门自己的设置（石斧 UI / pbmnarrate）
 *                                   default-HK = 香港预设（全局都念香港那句）
 *                                   default-SH = 上海预设（全局都念上海那句）
 *                                   user1 / user2 / … = 玩家自定义报站词（词存在本机配置，
 *                                   在石斧讲述人页里增删改）
 *   /jsr on &lt;样式&gt; word|chat|off  开启 + 样式 + **文字出现地点**：
 *                                   word = 屏幕字幕（MTR 列车内那种，屏幕下方）
 *                                   chat = 聊天框（讲述人念的那句发进聊天框）
 *                                   off  = 关掉讲述人文字（字幕和聊天框都不出，只出语音）
 * </pre>
 * 例：{@code /jsr on default-HK word}、{@code /jsr on user1 chat}、{@code /jsr on default off}。
 * <ul>
 *   <li>全局样式**不越过**某一串门自己的「关闭」—— 那一档是门串的否决权（关 /jsr 或
 *       石斧选「关闭」都彻底安静）；门串开着（上海 / 香港 / 自定义任一档）时，
 *       全局样式决定「念哪一句」。</li>
 *   <li>【09-30】玩家坐在 MTR 列车上时，讲述人**语音和文字一概不出口**（用户点名
 *       「无论设置如何」）；正在念 / 正在显示的那一下也会立刻停（效果与走出
 *       /jsr round 范围相同），见 {@link TrainAnnounceNarrator#tickRangeGuard} 与
 *       {@link TrainAnnounceSubtitle#tick}。</li>
 * </ul>
 *
 * <h2>零、【09-29】jsr round 管的事</h2>
 * <b>{@code /jsr round AAA BBB}</b>（AAA / BBB = 1~128 格）：讲述人进站播报的**范围**，
 * **两个方向各管各的** —— AAA 是**水平（x、z 轴）**范围，BBB 是**垂直（y 轴）**范围；
 * 玩家到「这一串屏蔽门里最近的那一扇」的**水平距离 ≥ AAA** 或 **垂直距离 ≥ BBB**，
 * 任一超出 ⇒ 不念；正在念的话立刻停。**第一次装模组默认水平 16 格、垂直 5 格**（用户点名）；
 * 老玩家用旧指令 {@code /jsr round X} 改过范围的，升级后水平垂直**都沿用旧值** ⇒ 行为逐位不变。
 *
 * <h2>一、它管的是哪一条广播（【1.29】重新定过）</h2>
 * 屏蔽门那一套里有**两条互不相干**的进站广播，**可以同时存在**：
 * <ol>
 *   <li><b>自定义进站广播</b> —— 音频库里的素材（{@code /pbmarrive}/{@code -f}），
 *       自己的开关就是它那一项设成「不播」；</li>
 *   <li><b>讲述人进站广播</b> —— 用文字转语音把到站信息念出来（{@link TrainAnnounceNarrator}），
 *       <b>本类</b>就是它唯一的总开关。</li>
 * </ol>
 * ★ 两者**互不耦合**：早先只共用「到站前 N 秒」那个窗口，现在讲述人有**自己**的窗口
 * （石斧 UI「进站广播(讲述人)」那一行的秒数框 = {@code PsdToneAudio.narrateSeconds}）。
 * **不播 ≠ 取消**：自定义那条设成「不播」只关它自己，讲述人照念；反过来 {@code /jsr off}
 * 也只让讲述人闭嘴。
 * <p>★【09-28】讲述人从此有**两层**：
 * <ol>
 *   <li>{@code /jsr on|off} —— <b>本类</b>，全局总闸（跨启动记住，写 config 文件）；</li>
 *   <li>石斧 UI 二级页「关闭 / 开启(香港) / 开启(上海) / 自定义(userN)」 —— 每**串**屏蔽门
 *       自己的**样式**（{@code PsdToneAudio.narrate}，门覆盖 &gt; 维度默认）。</li>
 * </ol>
 * 播放端两层取「与」：全局关着，任何一串都不念；全局开着，才轮到那一串自己的样式说话。
 * 【09-30】{@code /jsr on <样式>} 在两层之间又加了一层**全局样式覆盖**（不越过门串的「关闭」）。
 *
 * <h2>二、为什么是「文字转语音」而不是游戏那个讲述人</h2>
 * 原版讲述人要出声得先满足 设置 → 辅助功能 → 讲述人 = 全部/系统，且本机有语音引擎，
 * 否则静默。本模组照抄的是 MTR 报站的入口（{@code com.mojang.text2speech.Narrator}，
 * 不查那个设置）—— 详见 {@link TrainAnnounceNarrator} 的类注释。
 *
 * <h2>三、配置持久化</h2>
 * {@code config/smoothlift-jsr.properties}：
 * <ul>
 *   <li>{@code trainArriveAnnounce} = on/off —— 语音总闸，**默认开**；</li>
 *   <li>{@code trainArriveAnnounceStyle} = default/default-HK/default-SH/userN —— 全局样式，
 *       默认 default（跟门串）；</li>
 *   <li>{@code trainArriveAnnounceText} = word/chat/off —— 文字出现地点，默认 off；
 *       【迁移】旧键 {@code trainArriveAnnounceWord=on} 在新键缺失时折成 word；</li>
 *   <li>{@code trainArriveAnnounceUser.1} ~ {@code .N} —— 玩家自定义报站词（user1~userN）；</li>
 *   <li>{@code trainArriveAnnounceRoundXz} / {@code trainArriveAnnounceRoundY} —— 播报范围，
 *       旧单值键 {@code trainArriveAnnounceRound} 迁移后水平垂直都沿用旧值。</li>
 * </ul>
 * ★【09-30】配置读写改走 UTF-8（旧版是 ISO-8859-1 把中文转成反斜杠 u 转义）——
 * 自定义报站词是玩家手打的中文，直接明文存着才看得懂；旧文件里的转义仍能正常读回。
 */
public final class TrainAnnounceSwitch {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** config 文件名（放在 FabricLoader 的 config 目录下）。 */
    private static final String CONFIG_FILE = "smoothlift-jsr.properties";
    /** properties 键名。值：{@code off} = 关（/jsr off）；其余一律按「开」处理。 */
    private static final String KEY_ENABLED = "trainArriveAnnounce";
    /** 【1.29.1204】旧版单值范围（格）的键名（/jsr round X，1.28.1204 用过）—— 读到它做迁移。 */
    private static final String KEY_ROUND = "trainArriveAnnounceRound";
    /** 【1.29.1204】讲述人播报**水平（x、z 轴）**范围（格）的键名（/jsr round AAA BBB 的 AAA）。 */
    private static final String KEY_ROUND_XZ = "trainArriveAnnounceRoundXz";
    /** 【1.29.1204】讲述人播报**垂直（y 轴）**范围（格）的键名（/jsr round AAA BBB 的 BBB）。 */
    private static final String KEY_ROUND_Y = "trainArriveAnnounceRoundY";
    /** 【09-29】旧版字幕开关的键名（/jsr word on|off）—— 只读：迁移成 {@link #KEY_TEXT}。 */
    private static final String KEY_WORD = "trainArriveAnnounceWord";
    /** 【09-30】全局**样式**的键名（default / default-HK / default-SH / userN）—— 进站讲述人。 */
    private static final String KEY_STYLE = "trainArriveAnnounceStyle";
    /** 【09-30】**站台**讲述人全局样式的键名（default / userN —— 站台讲述人没有 SH / HK 预设）。 */
    private static final String KEY_M_STYLE = "trainMidiumAnnounceStyle";
    /** 【09-30 续 4】**站台**讲述人文字出现地点的键名（word / chat / off）。 */
    private static final String KEY_M_TEXT = "trainMidiumAnnounceText";
    /** 【09-30 续 4】**站台**讲述人总闸的键名（/jsr midium on|off）。 */
    private static final String KEY_M_ENABLED = "trainMidiumAnnounce";
    /** 【09-30 续 4】**站台**讲述人播报**水平（x、z 轴）**范围的键名（/jsr midium round AAA BBB）。 */
    private static final String KEY_M_ROUND_XZ = "trainMidiumAnnounceRoundXz";
    /** 【09-30 续 4】**站台**讲述人播报**垂直（y 轴）**范围的键名（/jsr midium round AAA BBB）。 */
    private static final String KEY_M_ROUND_Y = "trainMidiumAnnounceRoundY";
    /** 【09-30】**文字出现地点**的键名（word / chat / off）。 */
    private static final String KEY_TEXT = "trainArriveAnnounceText";
    /** 【09-30】自定义报站词的键前缀：user1 → trainArriveAnnounceUser.1、user2 → ….2……
     * 【10-01 续】只读：旧版本把词写进 config，本轮起改存**存档**；本键仅用于一次性迁移（见
     * {@link #KEY_USER_TEXT_MIGRATED} 与 {@link #applyNarrateUserTexts}）。 */
    private static final String KEY_USER_PREFIX = "trainArriveAnnounceUser.";
    /** 【10-01】一次性迁移标记：config 里的旧自定义词已经搬进**第一个**同步回来的存档。 */
    private static final String KEY_USER_TEXT_MIGRATED = "trainArriveAnnounceUserMigrated";
    private static final String VALUE_OFF = "off";

    // ---- 样式与文字模式的**词面**（指令里敲的那几个词，只在这里定义一份）----

    /** {@code /jsr on default}：全局不选样式，交还给每一串门自己的设置。 */
    public static final String STYLE_DEFAULT = "default";
    /** {@code /jsr on default-HK}：全局香港预设。 */
    public static final String STYLE_DEFAULT_HK = "default-HK";
    /** {@code /jsr on default-SH}：全局上海预设。 */
    public static final String STYLE_DEFAULT_SH = "default-SH";
    /** 自定义档在指令里的前缀（user1 / user2 / …）。 */
    public static final String USER_TOKEN_PREFIX = "user";
    /** 文字模式：屏幕字幕（MTR 列车内那种）。 */
    public static final String TEXT_WORD = "word";
    /** 文字模式：聊天框。 */
    public static final String TEXT_CHAT = "chat";

    /**
     * 【09-30】讲述人**文字**出现地点的三档（{@code /jsr on <样式> word|chat|off}）。
     *
     * <p>off = 字幕和聊天框都不出、只出语音（用户点名「off 指的是关闭讲述人文字
     * 聊天框模式或者屏幕文字模式」）。语音本身归 {@code /jsr on|off} 管，与这三档无关。
     */
    public enum TextMode {WORD, CHAT, OFF}

    /** 【1.29.1204】未设置时讲述人**水平（x、z 轴）**范围的默认值 = 16 格
     * （= {@code /pbmarriveround} 的默认，见类注释 —— 没改过范围的老玩家行为不变）。 */
    private static final int DEFAULT_ROUND_XZ = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_XZ;
    /** 【1.29.1204】未设置时讲述人**垂直（y 轴）**范围的默认值 = **5 格**
     * （用户点名「第一次加进去默认 x,z 轴范围=16、y 轴范围=5」）。 */
    private static final int DEFAULT_ROUND_Y = 5;

    /** 当前开关。**默认开** —— 装上模组就念，不需要任何指令。 */
    private static boolean enabled = true;

    /**
     * 【1.29.1204】讲述人播报的**水平（x、z 轴）**范围（格）。**默认 16** ⇒ 没设过时行为与上一版一致；
     * 旧配置（单值）迁移后这里取旧值。取值夹在 [ROUND_MIN, ROUND_MAX]。
     */
    private static int roundXz = DEFAULT_ROUND_XZ;

    /**
     * 【1.29.1204】讲述人播报的**垂直（y 轴）**范围（格）。**默认 5**（用户点名）；
     * 旧配置（单值）迁移后这里**取旧值**（= 与水平同值 ⇒ 行为逐位不变）。
     */
    private static int roundY = DEFAULT_ROUND_Y;

    /** 【09-30】全局**样式**（default = 跟门串；default-HK / default-SH / userN = 全局覆盖）。 */
    private static String style = STYLE_DEFAULT;

    /** 【09-30】讲述人文字出现地点。**默认聊天框 chat**（用户点名「第一次加载模组：进站广播
     * 聊天框播报」；旧版默认 off = 只出语音）。 */
    private static TextMode textMode = TextMode.CHAT;

    // ------------------------------------------------------------------
    // 【09-30 续 4】「站台广播（讲述人）」的全局层 —— 与进站讲述人**完全平行**的一套
    //   （总闸 / 样式 / 文字地点 / 范围），指令挂在 /jsr midium 下。
    //   ★ 站台讲述人**没有**香港 / 上海预设（用户点名删掉）⇒ 样式只认 default 与 userN；
    //     总闸默认开（与进站讲述人一致），但每串门的站台讲述人样式默认关 ⇒ 不配置就安静。
    // ------------------------------------------------------------------

    /** 【09-30 续 4】站台讲述人总闸。**默认关**（用户点名「站台广播默认不开启」；配合
     * 每串门样式默认关 ⇒ 不配置就安静 —— 与旧版「总闸默认开但门串默认关」听感一致，
     * 只是 /jsr midium 的状态行如实显示「关」）。 */
    private static boolean midiumEnabled = false;

    /** 【09-30 续 4】站台讲述人的全局样式（default = 跟门串；userN = 全局都念这条）。 */
    private static String midiumStyle = STYLE_DEFAULT;

    /** 【09-30 续 4】站台讲述人的文字出现地点。**默认关**。 */
    private static TextMode midiumTextMode = TextMode.OFF;

    /** 【09-30 续 4】站台讲述人播报的**水平（x、z 轴）**范围（格）。默认 16（与进站讲述人一致）。 */
    private static int midiumRoundXz = DEFAULT_ROUND_XZ;

    /** 【09-30 续 4】站台讲述人播报的**垂直（y 轴）**范围（格）。默认 5（与进站讲述人一致）。 */
    private static int midiumRoundY = DEFAULT_ROUND_Y;

    /**
     * 【09-30】玩家自定义报站词（第 i 条 = user{i+1}，与档位号
     * {@code PSD_NARRATE_USER_BASE + i} 一一对应）。
     * 【10-01 续】**进站广播（讲述人）**那一份 —— 改存**存档**（服务端同步回来的镜像，
     * 见 {@link #applyNarrateUserTexts}）；增删改走 C2S 发回服务端落库，不再写 config。
     */
    private static final List<String> userTexts = new ArrayList<>();

    /**
     * 【10-01】**站台广播（讲述人）**的玩家自定义文字 —— 与 {@link #userTexts} 两套分开
     * （用户点名「不要 2 个共用一个文字列表」）：站台讲述人念它，进站讲述人不念。
     * 同样按存档存（服务端镜像 + C2S 回写）。
     */
    private static final List<String> midiumUserTexts = new ArrayList<>();

    /** 【10-01】config 旧词是否已经搬进某个存档（一次性迁移标记，见 load / save）。 */
    private static boolean userTextMigrated = false;

    private TrainAnnounceSwitch() {
    }

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    /** 客户端初始化时调用一次：从 config 读回上次的全部设置。 */
    public static void load() {
        Path file = configFile();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStreamReader in = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift] 读取列车报站开关配置失败，使用默认（开）: {}", file, e);
            return;
        }
        // 只有明确写成 off 才关；键不存在（老配置）/ 写坏了都按默认「开」处理。
        enabled = !VALUE_OFF.equals(props.getProperty(KEY_ENABLED));
        // 【1.29.1204】范围：新版双键「水平 xz / 垂直 y」**各管各的**，缺哪个用哪个的默认
        // （水平 16 / 垂直 5），再夹到合法区间（手改配置也收得住）。
        // 【向后兼容】老配置只写过单值键 trainArriveAnnounceRound（1.28.1204 的 /jsr round X）：
        // 新键缺失时回退到旧键，把旧值**同时**赋给水平与垂直 ⇒ 老玩家升级后行为逐位不变。
        String legacy = props.getProperty(KEY_ROUND);
        int legacyRound = legacy == null ? -1 : clampRound(parseQuietly(legacy, DEFAULT_ROUND_XZ));
        roundXz = readRound(props, KEY_ROUND_XZ, legacyRound < 0 ? DEFAULT_ROUND_XZ : legacyRound);
        roundY = readRound(props, KEY_ROUND_Y, legacyRound < 0 ? DEFAULT_ROUND_Y : legacyRound);
        // 【09-30】全局样式：认不出的词（手改坏了 / 指向已删掉的 userN）一律退回 default。
        String styleRaw = trimToNull(props.getProperty(KEY_STYLE));
        style = styleRaw != null && isValidStyleToken(styleRaw) ? styleRaw : STYLE_DEFAULT;
        // 【09-30 续 4】站台讲述人的全局层（样式只认 default / userN —— SH / HK 不存在于站台讲述人）。
        //   【10-01】默认关（用户点名「站台广播默认不开启」）—— 键缺失时按「关」处理，不再默认开。
        String mEnRaw = trimToNull(props.getProperty(KEY_M_ENABLED));
        midiumEnabled = mEnRaw != null && !VALUE_OFF.equals(mEnRaw);
        String mStyleRaw = trimToNull(props.getProperty(KEY_M_STYLE));
        midiumStyle = mStyleRaw != null && isValidMidiumStyleToken(mStyleRaw) ? mStyleRaw : STYLE_DEFAULT;
        String mTextRaw = trimToNull(props.getProperty(KEY_M_TEXT));
        midiumTextMode = mTextRaw == null ? TextMode.OFF : parseTextMode(mTextRaw);
        midiumRoundXz = readRound(props, KEY_M_ROUND_XZ, DEFAULT_ROUND_XZ);
        midiumRoundY = readRound(props, KEY_M_ROUND_Y, DEFAULT_ROUND_Y);
        // 【09-30】文字出现地点：新键优先；缺失时迁移旧键 trainArriveAnnounceWord（on → word）。
        //   【10-01】新玩家没有旧键时**默认聊天框 chat**（用户点名「进站广播聊天框播报」）。
        String textRaw = trimToNull(props.getProperty(KEY_TEXT));
        if (textRaw == null) {
            textMode = "on".equals(props.getProperty(KEY_WORD)) ? TextMode.WORD : TextMode.CHAT;
        } else {
            textMode = parseTextMode(textRaw);
        }
        // 【09-30】自定义报站词：按编号升序收进来（编号有洞也没关系，顺序就是 user1、user2…）。
        userTexts.clear();
        TreeMap<Integer, String> numbered = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(KEY_USER_PREFIX)) {
                continue;
            }
            Integer index = parseIndex(key.substring(KEY_USER_PREFIX.length()));
            if (index != null && index >= 1) {
                numbered.put(index, props.getProperty(key, ""));
            }
        }
        userTexts.addAll(numbered.values());
        // 【10-01】一次性迁移标记：config 旧词还没搬进任何存档时此位为 false。
        //   （旧文件没有这个键 ⇒ false ⇒ 下一次同步回来时把上面的 legacy 词送进存档。）
        userTextMigrated = "true".equalsIgnoreCase(trimToNull(props.getProperty(KEY_USER_TEXT_MIGRATED)));
        LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人列车报站：{}（/jsr on|off 切换）；"
                        + "全局样式：{}（/jsr on default-HK|default-SH|user1…user{}|default 切换）；"
                        + "文字出现地点：{}（/jsr on <样式> word|chat|off）；"
                        + "范围：水平（x、z 轴）{} 格、垂直（y 轴）{} 格（/jsr round AAA BBB）；"
                        + "自定义词 {} 条（石斧讲述人页增删改）。"
                        + "★ 玩家在 MTR 列车上时讲述人语音与文字一律不播；"
                        + "站台讲述人：{}（/jsr midium on|off）、样式 {}（/jsr midium on default|userN…）、"
                        + "文字 {}、范围 水平 {} 格 / 垂直 {} 格",
                enabled ? "开" : "关",
                styleLabel(style), userTexts.size(), textModeLabel(textMode),
                roundXz, roundY, userTexts.size(),
                midiumEnabled ? "开" : "关",
                midiumStyleLabel(midiumStyle), textModeLabel(midiumTextMode),
                midiumRoundXz, midiumRoundY);
    }

    /** 配置键的编号尾巴（“trainArriveAnnounceUser.3” → 3）；不是正整数返回 null。 */
    private static Integer parseIndex(String tail) {
        if (tail == null || tail.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(tail.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code null} / 空白 → {@code null}；其余去掉首尾空白。 */
    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 【09-29】把配置里的整数读回来；读不到 / 不是数字就返回给定的默认值。 */
    private static int parseQuietly(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 夹到 [{@link EscalatorSpeedData#ROUND_MIN}, {@link EscalatorSpeedData#ROUND_MAX}]。 */
    private static int clampRound(int value) {
        return Math.max(EscalatorSpeedData.ROUND_MIN,
                Math.min(EscalatorSpeedData.ROUND_MAX, value));
    }

    /**
     * 【1.29.1204】读一个范围键：缺键用给定默认；写坏了（非数字）也退回默认；再夹到合法区间。
     */
    private static int readRound(Properties props, String key, int fallback) {
        String raw = props.getProperty(key);
        return raw == null ? fallback : clampRound(parseQuietly(raw, fallback));
    }

    // ------------------------------------------------------------------
    // 样式 / 文字模式 / 自定义词 —— 状态与换算
    // ------------------------------------------------------------------

    /** 全局样式当前是哪一档（default / default-HK / default-SH / userN）。 */
    public static String style() {
        return style;
    }

    /** 样式词 → 中文名（default = 「跟门串设置」，其余与档位同名）。 */
    public static String styleLabel(String token) {
        if (STYLE_DEFAULT_HK.equals(token)) {
            return EscalatorSpeedData.psdNarrateModeName(EscalatorSpeedData.PSD_NARRATE_HONGKONG)
                    + "（全局）";
        }
        if (STYLE_DEFAULT_SH.equals(token)) {
            return EscalatorSpeedData.psdNarrateModeName(EscalatorSpeedData.PSD_NARRATE_SHANGHAI)
                    + "（全局）";
        }
        if (STYLE_DEFAULT.equals(token)) {
            return "default（跟每一串门自己的设置）";
        }
        int index = userIndexOf(token);
        return index < 0 ? token
                : EscalatorSpeedData.psdNarrateModeName(EscalatorSpeedData.psdNarrateUserMode(index))
                + "（全局）";
    }

    /** 文字模式的中文名（状态行 / 指令回执用）。 */
    public static String textModeLabel(TextMode mode) {
        return switch (mode) {
            case WORD -> "屏幕字幕（word）";
            case CHAT -> "聊天框（chat）";
            case OFF -> "关（不出文字，只出语音）";
        };
    }

    /** 文字模式词（word / chat / off）→ 枚举；认不出返回 {@link TextMode#OFF}（收得住）。 */
    private static TextMode parseTextMode(String token) {
        return TEXT_WORD.equalsIgnoreCase(token) ? TextMode.WORD
                : TEXT_CHAT.equalsIgnoreCase(token) ? TextMode.CHAT
                : TextMode.OFF;
    }

    /**
     * 这个样式词**认不认得**（default / default-HK / default-SH / 现存的有效 userN）。
     */
    public static boolean isValidStyleToken(String token) {
        if (STYLE_DEFAULT.equals(token) || STYLE_DEFAULT_HK.equals(token)
                || STYLE_DEFAULT_SH.equals(token)) {
            return true;
        }
        return userIndexOf(token) >= 0;
    }

    /**
     * {@code "user3"} → 编号 2（0 起）；不是现存的自定义档返回 -1。
     * N 以**当前**条数为准 —— 删掉一条之后，越界的 userN 自动失效（样式退回跟门串）。
     */
    public static int userIndexOf(String token) {
        if (token == null || !token.startsWith(USER_TOKEN_PREFIX)
                || token.length() <= USER_TOKEN_PREFIX.length()) {
            return -1;
        }
        Integer n;
        try {
            n = Integer.valueOf(token.substring(USER_TOKEN_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
        int index = n - 1;
        return index >= 0 && index < userTexts.size() ? index : -1;
    }

    /**
     * 【09-30】全局样式折成的**档位号**（播放端用它盖住门串的样式）。
     *
     * @return {@code -1} = default（跟门串，不覆盖）；否则 1（上海）/ 2（香港）/ 3+（userN）
     */
    public static int styleModeOverride() {
        if (STYLE_DEFAULT_HK.equals(style)) {
            return EscalatorSpeedData.PSD_NARRATE_HONGKONG;
        }
        if (STYLE_DEFAULT_SH.equals(style)) {
            return EscalatorSpeedData.PSD_NARRATE_SHANGHAI;
        }
        int index = userIndexOf(style);
        return index < 0 ? -1 : EscalatorSpeedData.psdNarrateUserMode(index);
    }

    /** 文字出现地点当前是哪一档。 */
    public static TextMode textMode() {
        return textMode;
    }

    /**
     * 换文字出现地点（指令 / 预设用）。离开 word 的那一下顺手撤掉还在屏幕上的字幕 ——
     * 开关的语义是「立刻生效」，不是「等下一班」。
     */
    public static void setTextMode(TextMode next) {
        if (next == null || next == textMode) {
            return;
        }
        textMode = next;
        if (next != TextMode.WORD) {
            TrainAnnounceSubtitle.hide("文字模式已不是屏幕字幕");
        }
        save();
    }

    /** 屏幕字幕（word 档）开没开 —— 渲染端与字幕 tick 仍问这一句（与旧口径同名兼容）。 */
    public static boolean isWordEnabled() {
        return textMode == TextMode.WORD;
    }

    // ------------------------------------------------------------------
    // 【09-30 续 4】站台讲述人的全局层（/jsr midium …）—— 与进站讲述人那一套逐字平行，
    //   差别只有两处：样式**没有** default-HK / default-SH（用户点名「站台广播里的香港和
    //   上海预设删掉」），且默认值/键名独立。
    // ------------------------------------------------------------------

    /** 站台讲述人的全局总闸。 */
    public static boolean isMidiumEnabled() {
        return midiumEnabled;
    }

    /** 站台讲述人的全局样式词（default / userN）。 */
    public static String midiumStyle() {
        return midiumStyle;
    }

    /** 样式词 → 中文名（站台版：default = 「跟门串设置」，userN = 自定义档；没有 SH / HK）。 */
    public static String midiumStyleLabel(String token) {
        if (STYLE_DEFAULT.equals(token)) {
            return "default（跟每一串门自己的设置）";
        }
        int index = userIndexOf(token);
        return index < 0 ? token
                : EscalatorSpeedData.psdNarrateModeName(EscalatorSpeedData.psdNarrateUserMode(index))
                + "（全局）";
    }

    /** 站台讲述人的全局样式折成的档位号；{@code -1} = default（跟门串，不覆盖）。 */
    public static int midiumStyleModeOverride() {
        int index = midiumUserIndexOf(midiumStyle);
        return index < 0 ? -1 : EscalatorSpeedData.psdNarrateUserMode(index);
    }

    /** 站台讲述人的文字出现地点。 */
    public static TextMode midiumTextMode() {
        return midiumTextMode;
    }

    /** 换站台讲述人的文字出现地点（离开 word 的那一下顺手撤掉还在屏幕上的字幕）。 */
    public static void setMidiumTextMode(TextMode next) {
        if (next == null || next == midiumTextMode) {
            return;
        }
        midiumTextMode = next;
        if (next != TextMode.WORD) {
            TrainAnnounceSubtitle.hide("站台讲述人文字模式已不是屏幕字幕");
        }
        save();
    }

    /** 站台讲述人当前生效的**水平（x、z 轴）**范围（格）。 */
    public static int midiumRoundXz() {
        return midiumRoundXz;
    }

    /** 站台讲述人当前生效的**垂直（y 轴）**范围（格）。 */
    public static int midiumRoundY() {
        return midiumRoundY;
    }

    /** 站台讲述人的范围判据：与进站讲述人同一条「水平 ≥ AAA 或 垂直 ≥ BBB 即出界」。 */
    public static boolean isOutsideMidiumNarrateRange(long runKey, double x, double y, double z, Vec3 player) {
        NarrateDistance d = narrateDistances(runKey, x, y, z, player);
        return d.horizontal() >= midiumRoundXz || d.vertical() >= midiumRoundY;
    }

    /**
     * 站台讲述人的样式词认不认得：只有 {@code default} 与现存的 userN ——
     * 香港 / 上海预设**不存在**于站台讲述人（用户点名删掉）。
     */
    public static boolean isValidMidiumStyleToken(String token) {
        if (STYLE_DEFAULT.equals(token)) {
            return true;
        }
        return midiumUserIndexOf(token) >= 0;
    }

    /** 站台讲述人的样式下拉建议（default + 现存 userN —— 没有 SH / HK）。 */
    public static CompletableFuture<Suggestions> suggestMidiumStyles(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        builder.suggest(STYLE_DEFAULT);
        for (int i = 0; i < midiumUserTexts.size(); i++) {
            builder.suggest(USER_TOKEN_PREFIX + (i + 1));
        }
        return builder.buildFuture();
    }

    /** {@code /jsr midium}（无参数）：显示站台讲述人的全部状态。 */
    public static int showMidium(CommandContext<FabricClientCommandSource> ctx) {
        say(ctx, Component.literal(
                "[SmoothLift] 站台广播（讲述人）语音：" + (midiumEnabled ? "开" : "关")
                    + "（/jsr midium on 开启，/jsr midium off 关闭，默认关）。"
                    + "全局样式：" + midiumStyleLabel(midiumStyle)
                    + "（/jsr midium on default|user1…user" + midiumUserTextCount()
                    + " 选择，default = 交还给每串门自己的设置）。"
                    + "★ 站台讲述人没有香港 / 上海预设（那两句是进站报站的句式）"));
        say(ctx, Component.literal(
                "[SmoothLift] 站台讲述人文字出现地点：" + textModeLabel(midiumTextMode)
                        + "（/jsr midium on <样式> word|chat|off 修改）。"
                        + "播报范围：水平 " + midiumRoundXz + " 格、垂直 " + midiumRoundY
                        + " 格（/jsr midium round AAA BBB 调整，1~128）"));
        say(ctx, Component.literal(
                "[SmoothLift] 站台讲述人挂在**站台播报（pbmmidium）**的时间窗口上：开门音播完 + 等 Y 秒开念"
                        + "（Y 在石斧主界面「站台广播(讲述人)」那一行里填，范围 [0,+∞)）。"
                        + "每串门还可以各自选样式 —— 石斧右键屏蔽门 →「站台广播(讲述人)」二级页"
                        + "（右列只有「关闭」与自定义 userN，没有香港 / 上海）"));
        say(ctx, Component.literal(
                "[SmoothLift] 与进站广播的讲述人（/jsr arrive …）是两条互不相干的广播，可以同时存在；"
                        + "mbmhelp 的三个预设都会顺带把站台讲述人关掉（用户点名）"));
        return 1;
    }

    /** {@code /jsr midium on|off}：开关站台讲述人语音（立即保存，跨启动保持）。 */
    private static int setMidiumImpl(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        if (midiumEnabled == on) {
            say(ctx, Component.literal(
                    "[SmoothLift] 站台广播（讲述人）本来就是" + (on ? "开" : "关") + "的，无需切换"));
            return 1;
        }
        midiumEnabled = on;
        save();
        say(ctx, Component.literal(
                "[SmoothLift] 站台广播（讲述人）已" + (on ? "开启" : "关闭")
                        + "，已记住，下次启动保持：" + (on
                        ? "各站台在「开门音播完 Y 秒」时念出自己样式的那一句（Y 在石斧主界面那一行里填）"
                        : "只关站台讲述人这一条，进站广播的讲述人（/jsr arrive）与自定义到站播报照旧")));
        LOGGER.info("[SmoothLift/TrainAnnounce] 站台广播（讲述人）已{}（/jsr midium {}）",
                on ? "开启" : "关闭", on ? "on" : "off");
        return 1;
    }

    /**
     * {@code /jsr midium on [样式] [word|chat|off]}：一句话定下「开 + 全局样式 + 文字地点」。
     * 样式只认 default 与 userN —— 敲 default-HK / default-SH 会被明确拒绝（站台讲述人没有这两档）。
     */
    private static int setMidiumOnImpl(CommandContext<FabricClientCommandSource> ctx, String styleToken, String textToken) {
        if (styleToken != null && !isValidMidiumStyleToken(styleToken)) {
            say(ctx, Component.literal(
                    STYLE_DEFAULT_HK.equals(styleToken) || STYLE_DEFAULT_SH.equals(styleToken)
                            ? "[SmoothLift] 站台广播（讲述人）没有「" + styleToken + "」这一档 —— 香港 / 上海"
                            + "预设只属于进站广播的讲述人（/jsr arrive …），站台讲述人只有 default 与 userN"
                            : "[SmoothLift] 未知的站台讲述人样式「" + styleToken + "」。可用：default（跟每串门"
                            + "自己的设置）" + (midiumUserTexts.isEmpty() ? "；还没有自定义词（先去石斧「站台广播(讲述人)」页新增）"
                            : "、user1 ~ user" + midiumUserTextCount() + "（自定义词）")));
            return 0;
        }
        TextMode nextText = midiumTextMode;
        if (textToken != null) {
            if (!TEXT_WORD.equalsIgnoreCase(textToken) && !TEXT_CHAT.equalsIgnoreCase(textToken)
                    && !VALUE_OFF.equalsIgnoreCase(textToken)) {
                say(ctx, Component.literal(
                        "[SmoothLift] 未知的文字模式「" + textToken + "」。可用：word（屏幕中）、"
                                + "chat（聊天框）、off（关闭讲述人文字）"));
                return 0;
            }
            nextText = parseTextMode(textToken);
        }
        boolean changed = false;
        if (!midiumEnabled) {
            midiumEnabled = true;
            changed = true;
        }
        if (styleToken != null && !styleToken.equals(midiumStyle)) {
            midiumStyle = styleToken;
            changed = true;
        }
        if (nextText != midiumTextMode) {
            midiumTextMode = nextText;
            if (nextText != TextMode.WORD) {
                TrainAnnounceSubtitle.hide("站台讲述人文字模式已不是屏幕字幕");
            }
            changed = true;
        }
        if (changed) {
            save();
        }
        LOGGER.info("[SmoothLift/TrainAnnounce] /jsr midium on：样式 {}、文字 {}（语音总闸开）",
                midiumStyle, midiumTextMode);
        say(ctx, Component.literal(
                "[SmoothLift] 站台广播（讲述人）已开启（已记住）：样式＝" + midiumStyleLabel(midiumStyle)
                        + "；文字出现地点＝" + textModeLabel(midiumTextMode)));
        return 1;
    }

    /**
     * {@code /jsr midium round <AAA> <BBB>}：站台讲述人自己的播报范围（1~128，越界夹取）。
     * AAA = 水平（x、z 轴），BBB = 垂直（y 轴）—— 与进站讲述人的 /jsr round 是两份配置。
     */
    private static int setMidiumRoundImpl(CommandContext<FabricClientCommandSource> ctx, int valueXz, int valueY) {
        int clampedXz = clampRound(valueXz);
        int clampedY = clampRound(valueY);
        if (clampedXz != valueXz || clampedY != valueY) {
            say(ctx, Component.literal(
                    "[SmoothLift] 范围已夹取到合法区间（1~128）"));
        }
        int beforeXz = midiumRoundXz;
        int beforeY = midiumRoundY;
        midiumRoundXz = clampedXz;
        midiumRoundY = clampedY;
        save();
        boolean changed = beforeXz != midiumRoundXz || beforeY != midiumRoundY;
        say(ctx, Component.literal(
                "[SmoothLift] 站台讲述人播报范围" + (changed ? "已设为 " : "仍为 ")
                        + "水平（x、z 轴）" + midiumRoundXz + " 格、垂直（y 轴）" + midiumRoundY + " 格，"
                        + "已记住，下次启动保持（与进站讲述人的 /jsr round 是两份配置）"));
        LOGGER.info("[SmoothLift/TrainAnnounce] 站台讲述人播报范围：水平 {} → {} 格、垂直 {} → {} 格（/jsr midium round）",
                beforeXz, midiumRoundXz, beforeY, midiumRoundY);
        return 1;
    }

    /**
     * 【09-30 续 4】「mbmhelp 三个预设」按钮用：把**站台**讲述人关掉并立刻落盘
     * （用户点名「这三个预设都不需要站台讲述人广播」）。与进站讲述人的
     * {@link #disableForPreset()} 各管各的总闸。
     *
     * @return 有没有真的改动（本来就是关的 ⇒ {@code false}，且不写盘）
     */
    public static boolean disableMidiumForPreset() {
        if (!midiumEnabled) {
            return false;
        }
        midiumEnabled = false;
        save();
        LOGGER.info("[SmoothLift/TrainAnnounce] 应用港铁预设：站台广播（讲述人）已关闭");
        return true;
    }

    /** 自定义词条数（user1 ~ userN 的 N）。 */
    public static int userTextCount() {
        return userTexts.size();
    }

    /** 第 {@code idx0}（0 起）条自定义词；越界 / 没写过 / 空白返回 {@code null}。 */
    public static String userText(int idx0) {
        if (idx0 < 0 || idx0 >= userTexts.size()) {
            return null;
        }
        String text = userTexts.get(idx0);
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * 【09-30 续】第 {@code idx0} 条自定义词的**原文**（空位返回空串）。
     *
     * <p>UI 输入框（石斧讲述人页，每条一个）要用它做初值 —— 空位也必须显示出输入框
     * 让玩家往里写，所以这里不把空串折成 {@code null}。
     */
    public static String userTextRaw(int idx0) {
        if (idx0 < 0 || idx0 >= userTexts.size()) {
            return "";
        }
        String text = userTexts.get(idx0);
        return text == null ? "" : text;
    }

    /** 改第 {@code idx0} 条自定义词（**进站广播**那份；空串 = 留一个「填了才生效」的空位）；越界忽略。
     * 【10-01】改完**发回服务端存进存档**（随存档走），不再写 config。 */
    public static void setUserText(int idx0, String text) {
        if (idx0 < 0 || idx0 >= userTexts.size()) {
            return;
        }
        String next = text == null ? "" : text.trim();
        if (next.equals(userTexts.get(idx0))) {
            return; // 没改动就不发
        }
        userTexts.set(idx0, next);
        sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_ARRIVE);
    }

    /**
     * 追加一条自定义词（**进站广播**那份，= 新的 userN）。达到上限时**不加也不发**，返回 -1。
     *
     * @return 新那条的编号（0 起）；达到上限 = -1
     */
    public static int addUserText(String text) {
        if (userTexts.size() >= EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES) {
            return -1;
        }
        userTexts.add(text == null ? "" : text.trim());
        sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_ARRIVE);
        return userTexts.size() - 1;
    }

    /** 删掉第 {@code idx0} 条自定义词（**进站广播**那份；后面的 userN 序号整体前移一位）；越界忽略。 */
    public static void deleteUserText(int idx0) {
        if (idx0 < 0 || idx0 >= userTexts.size()) {
            return;
        }
        userTexts.remove(idx0);
        sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_ARRIVE);
    }

    // ------------------------------------------------------------------
    // 【10-01】「站台广播（讲述人）」的自定义文字 —— 与进站广播**两套分开**
    //   （用户点名「不要 2 个共用一个文字列表」）。同一套按存档约定：
    //   镜像来自服务端同步（applyNarrateUserTexts），增删改走 C2S 回写。
    // ------------------------------------------------------------------

    /** 站台讲述人自定义词条数（user1 ~ userN 的 N）。 */
    public static int midiumUserTextCount() {
        return midiumUserTexts.size();
    }

    /** 【10-01】进站广播（讲述人）的整份自定义文字（讲述人句式按这份取 userN 模板）。 */
    public static List<String> arriveUserTexts() {
        return userTexts;
    }

    /** 【10-01】站台广播（讲述人）的整份自定义文字 —— 与 {@link #arriveUserTexts()} 两套分开。 */
    public static List<String> midiumUserTextsList() {
        return midiumUserTexts;
    }

    /** 第 {@code idx0}（0 起）条站台自定义词；越界 / 没写过 / 空白返回 {@code null}。 */
    public static String midiumUserText(int idx0) {
        if (idx0 < 0 || idx0 >= midiumUserTexts.size()) {
            return null;
        }
        String text = midiumUserTexts.get(idx0);
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** 第 {@code idx0} 条站台自定义词的**原文**（空位返回空串，供 UI 输入框做初值）。 */
    public static String midiumUserTextRaw(int idx0) {
        if (idx0 < 0 || idx0 >= midiumUserTexts.size()) {
            return "";
        }
        String text = midiumUserTexts.get(idx0);
        return text == null ? "" : text;
    }

    /** {@code "user3"} → 编号 2（0 起，按**站台**那份的当前条数）；不是现存的自定义档返回 -1。 */
    public static int midiumUserIndexOf(String token) {
        if (token == null || !token.startsWith(USER_TOKEN_PREFIX)
                || token.length() <= USER_TOKEN_PREFIX.length()) {
            return -1;
        }
        Integer n;
        try {
            n = Integer.valueOf(token.substring(USER_TOKEN_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
        int index = n - 1;
        return index >= 0 && index < midiumUserTexts.size() ? index : -1;
    }

    /** 改第 {@code idx0} 条**站台**自定义词（空串 = 空位）；越界忽略。 */
    public static void setMidiumUserText(int idx0, String text) {
        if (idx0 < 0 || idx0 >= midiumUserTexts.size()) {
            return;
        }
        String next = text == null ? "" : text.trim();
        if (next.equals(midiumUserTexts.get(idx0))) {
            return; // 没改动就不发
        }
        midiumUserTexts.set(idx0, next);
        sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_MIDIUM);
    }

    /** 追加一条**站台**自定义词；达到上限返回 -1。 */
    public static int addMidiumUserText(String text) {
        if (midiumUserTexts.size() >= EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES) {
            return -1;
        }
        midiumUserTexts.add(text == null ? "" : text.trim());
        sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_MIDIUM);
        return midiumUserTexts.size() - 1;
    }

    /** 删掉第 {@code idx0} 条**站台**自定义词（序号整体前移）；越界忽略。 */
    public static void deleteMidiumUserText(int idx0) {
        if (idx0 < 0 || idx0 >= midiumUserTexts.size()) {
            return;
        }
        midiumUserTexts.remove(idx0);
        sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_MIDIUM);
    }

    /**
     * 【10-01】把某条讲述人广播的**整份**自定义词发回服务端（存进存档 → 全量同步回来）。
     *
     * <p>★ 为什么不是写 config：用户点名「讲述人自定义文字要存在**游戏存档**里，
     * 存档挪到别处也能找到、不要存档之间互通、别放别处」—— 存档在服务端，所以编辑
     * 一律 C2S 落库（与导入 OGG 到存档音频库同一条路）。
     *
     * <p>★ 不在世界（mc.level == null）时不发 —— 编辑页只在世界里开，这里只是兜底。
     */
    private static void sendNarrateTexts(int target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }
        List<String> src = target == EscalatorSpeedManager.NARRATE_TEXTS_MIDIUM
                ? midiumUserTexts : userTexts;
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(target);
        buf.writeVarInt(src.size());
        for (String s : src) {
            buf.writeUtf(s == null ? "" : s, 256);
        }
        ClientPlayNetworking.send(SmoothLift.SET_PSD_NARRATE_TEXTS_CHANNEL, buf);
    }

    /**
     * 【10-01】服务端同步回来整两份自定义词（进站 / 站台），更新客户端镜像。
     *
     * <p>★ 附带做**一次性迁移**：config 里还躺着旧版写的自定义词、且存档两份都是空
     * （还没人存过词）时，把它们送进**这一个**存档的「进站广播」那份 —— 只搬一次
     * （{@link #userTextMigrated} 置位并落盘）⇒ 不会每个存档都塞同一份全局词
     * （用户点名「不要存档之间互通」）。站台那份**不搬**：旧版只有一份共享词，
     * 搬过去 = 隐性串用（用户点名「两条分开」）。
     */
    public static void applyNarrateUserTexts(List<String> arrive, List<String> midium) {
        if (arrive == null) {
            arrive = java.util.Collections.emptyList();
        }
        if (midium == null) {
            midium = java.util.Collections.emptyList();
        }
        if (!userTextMigrated && arrive.isEmpty() && midium.isEmpty() && !userTexts.isEmpty()) {
            // 老 config 词还躺着：搬进第一个存档（进站广播那份），就地发一次再标记。
            List<String> legacy = new ArrayList<>(userTexts);
            sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_ARRIVE);
            userTextMigrated = true;
            save();
            userTexts.clear();
            userTexts.addAll(legacy);
            midiumUserTexts.clear();
            LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人自定义词已从 config 搬进存档（一次性，进站广播那份）：{} 条",
                    legacy.size());
            return;
        }
        userTexts.clear();
        int max = EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES;
        for (int i = 0; i < arrive.size() && i < max; i++) {
            userTexts.add(arrive.get(i) == null ? "" : arrive.get(i).trim());
        }
        midiumUserTexts.clear();
        for (int i = 0; i < midium.size() && i < max; i++) {
            midiumUserTexts.add(midium.get(i) == null ? "" : midium.get(i).trim());
        }
    }

    // ------------------------------------------------------------------
    // 指令回执与执行
    // ------------------------------------------------------------------

    /** 讲述人进站广播的总开关。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 【09-28 续 2】「港铁预设」按钮用：把全局总闸设成**开**并立刻落盘。
     *
     * <p>为什么非得由客户端来做：「港铁预设」是**服务端**依次执行一串指令，
     * 而本类是**客户端**配置（config 文件）—— 服务端那串指令碰不到它。
     * 用户点名「预设也会顺带把总闸打开」，所以由**客户端**那一下点击顺手打开。
     *
     * @return 有没有真的改动（本来就是开的 ⇒ {@code false}，且不写盘）
     */
    public static boolean enableForPreset() {
        if (enabled) {
            return false;
        }
        enabled = true;
        save();
        LOGGER.info("[SmoothLift/TrainAnnounce] 应用港铁预设：讲述人列车报站的全局总闸已打开");
        return true;
    }

    /**
     * 【09-28 续 4】「简单港铁预设 / 空白预设」按钮用：把全局总闸设成**关**并立刻落盘。
     *
     * @return 有没有真的改动（本来就是关的 ⇒ {@code false}，且不写盘）
     */
    public static boolean disableForPreset() {
        if (!enabled) {
            return false;
        }
        enabled = false;
        save();
        TrainAnnounceSubtitle.hide("应用预设：讲述人已整体关闭");
        LOGGER.info("[SmoothLift/TrainAnnounce] 应用港铁预设：讲述人列车报站的全局总闸已关闭");
        return true;
    }

    /** 【09-29】/jsr 无参数：样式下拉建议（default 两档 + 现存 userN）。 */
    public static CompletableFuture<Suggestions> suggestStyles(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        builder.suggest(STYLE_DEFAULT);
        builder.suggest(STYLE_DEFAULT_HK);
        builder.suggest(STYLE_DEFAULT_SH);
        for (int i = 0; i < userTexts.size(); i++) {
            builder.suggest(USER_TOKEN_PREFIX + (i + 1));
        }
        return builder.buildFuture();
    }

    /**
     * 【09-30 续】{@code /jsr define userN <文字>}：把玩家写的那句话写进第 N 条自定义词。
     *
     * <p>与石斧 UI 的输入框（每条一个，可随时改）写的是**同一份存储**：指令写完，
     * 下次打开讲述人页就能在 userN 那个输入框里看到它。N 超过现有条数时**先补空位**
     * （中间缺的 userK 是空输入框，玩家之后随便填）—— 这正是「user1、user2 以此类推」
     * 的槽位语义。
     */
    private static int defineUserTextImpl(CommandContext<FabricClientCommandSource> ctx, String slot, String text) {
        int n = parseUserSlot(slot);
        if (n < 1) {
            say(ctx, Component.literal(
                    "[SmoothLift] 第一个参数必须是 user1、user2…（现在收到的是「" + slot + "」）。"
                            + "例：/jsr define user1 各位乘客请注意"));
            return 0;
        }
        if (n > EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES) {
            say(ctx, Component.literal(
                    "[SmoothLift] 自定义词最多 " + EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES
                            + " 条（user1 ~ user" + EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES + "）"));
            return 0;
        }
        while (userTexts.size() < n) {
            userTexts.add("");
        }
        String value = text == null ? "" : text.trim();
        userTexts.set(n - 1, value);
        // 【10-01】自定义词改存**存档**（C2S 落库 → 同步回来），不再写 config。
        sendNarrateTexts(EscalatorSpeedManager.NARRATE_TEXTS_ARRIVE);
        String preview = value.length() <= 20 ? value : value.substring(0, 20) + "…";
        say(ctx, Component.literal(
                "[SmoothLift] 已写入 user" + n + "（进站广播那份，随存档保存）：「"
                        + (value.isEmpty() ? "（空）" : preview) + "」。"
                        + "让某一串门念它：石斧讲述人页 user" + n + " 行右侧点「选择」"
                        + "（全局都念它则 /jsr on user" + n + "）"));
        LOGGER.info("[SmoothLift/TrainAnnounce] /jsr define：user{} = 「{}」", n, preview);
        return 1;
    }

    /** {@code "user3"} → 3；不是这个形状返回 -1（**不要求**现存 —— define 允许往后新建槽位）。 */
    private static int parseUserSlot(String token) {
        if (token == null || !token.startsWith(USER_TOKEN_PREFIX)
                || token.length() <= USER_TOKEN_PREFIX.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(token.substring(USER_TOKEN_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 【09-30 续】/jsr define 的槽位建议：现存 user1..userN，外加下一个空位 userN+1。 */
    public static CompletableFuture<Suggestions> suggestUserSlots(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        for (int i = 0; i < userTexts.size(); i++) {
            builder.suggest(USER_TOKEN_PREFIX + (i + 1));
        }
        if (userTexts.size() < EscalatorSpeedData.MAX_PSD_NARRATE_USER_STYLES) {
            builder.suggest(USER_TOKEN_PREFIX + (userTexts.size() + 1));
        }
        return builder.buildFuture();
    }

    /** 【09-30】文字模式下拉建议（word / chat / off）。 */
    public static CompletableFuture<Suggestions> suggestTextModes(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        builder.suggest(TEXT_WORD);
        builder.suggest(TEXT_CHAT);
        builder.suggest(VALUE_OFF);
        return builder.buildFuture();
    }

    /** {@code /jsr arrive}（无参数）：显示**进站**讲述人的状态与说明。 */
    public static int show(CommandContext<FabricClientCommandSource> ctx) {
        say(ctx, Component.literal(
                "[SmoothLift] 进站广播（讲述人）语音：" + (enabled ? "开" : "关")
                        + "（/jsr arrive on 开启，/jsr arrive off 关闭，默认开）。"
                        + "全局样式：" + styleLabel(style)
                        + "（/jsr arrive on default-HK|default-SH|user1…user" + userTexts.size()
                        + "|default 选择，default = 交还给每串门自己的设置）"));
        say(ctx, Component.literal(
                "[SmoothLift] 进站讲述人文字出现地点：" + textModeLabel(textMode)
                        + "（/jsr arrive on <样式> word|chat|off 修改：word = 屏幕中、"
                        + "chat = 聊天框、off = 关闭讲述人文字只出语音）。"
                        + "★ 站台广播的讲述人是另一条：/jsr midium …"));
        say(ctx, Component.literal(
                "[SmoothLift] 播报范围：水平（x、z 轴）" + roundXz + " 格、垂直（y 轴）" + roundY
                        + " 格（/jsr round AAA BBB 调整，AAA / BBB = 1~128；第一次装模组"
                        + "默认水平 16、垂直 5）。玩家到最近那扇屏蔽门的水平距离 ≥ AAA 或"
                        + "垂直距离 ≥ BBB 就不播；正在念的话立刻停。"
                        + "同时处于多串门的范围内时，以距离最近的那一串的播报为准"));
        say(ctx, Component.literal(
                "[SmoothLift] ★ 玩家在 MTR 列车上时，讲述人语音与文字（字幕 / 聊天框）"
                        + "一概不播，无论设置如何；上车那一刻正在播的也会立刻停。"));
        say(ctx, Component.literal(
                "[SmoothLift] 自定义报站词：" + userTexts.size() + " 条"
                        + "（石斧右键屏蔽门 →「进站广播(讲述人)」右列「添加预设」按钮新增一条；"
                        + "每条一个输入框、随时可改，右侧「选择」= 这一串门念它、「删除」= 删掉这一条；"
                        + "/jsr define userN <文字> 也能写；"
                        + (userTexts.isEmpty() ? "还没有自定义词"
                        : "现有：" + userTextSummary()) + "）"));
        // 【09-30 续】自定义词的占位符（用户点名）：| 换行、|SC|/|SE| 当前车站中英文名、
        //   |DC|/|DE| 当前站台中英文名、【09-30 续 2】|WC|/|WE| 本次列车目的地中英文名、
        //   |LC|/|LE| 当前线路中英文名 —— 念出来那一刻才替换成实际值。
        say(ctx, Component.literal(
                "[SmoothLift] 自定义词里可用占位符（播报那一刻替换成实际值）："
                        + "| = 换行；|SC|/|SE| = 当前车站名 中/英；|DC|/|DE| = 当前站台 中/英；"
                        + "|WC|/|WE| = 本次列车目的地 中/英；|LC|/|LE| = 当前线路名 中/英。"
                        + "例：欢迎光临|SC|站，本次列车开往|WC|"));
        say(ctx, Component.literal(
                "[SmoothLift] 它和自定义进站广播是两条广播，可以同时存在；"
                        + "自定义那条设成不播不会影响它。每一串门还可以各自选样式 +"
                        + "各自设提前秒数 —— 石斧右键那串屏蔽门 →「进站广播(讲述人)」"
                        + "（二级页「关闭 / 开启(香港) / 开启(上海) / 自定义(userN)」），"
                        + "也可以直接敲 /pbmnarrate off|shanghai|hongkong（加 -f = 所有维度）。"));
        // ★ 三个预设对讲述人的态度是**定死**的（用户点名）：经典港铁 = 样式香港 + 文字 word，
        //   另两个 = 关讲述人与文字。
        say(ctx, Component.literal(
                "[SmoothLift] mbmhelp 的三个预设：经典港铁预设＝开启(香港) + 文字 word"
                        + "，简单港铁预设 / 空白预设＝**关闭**讲述人与文字"
                        + "（按预设时总闸和文字模式会一起跟着开/关）。"));
        return 1;
    }

    /** 状态行里自定义词的摘要（最多列 3 条，每条截 12 字，多了加「等 N 条」）。 */
    private static String userTextSummary() {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(3, userTexts.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append("；");
            }
            String text = userTexts.get(i);
            sb.append("user").append(i + 1).append("「")
                    .append(text.length() <= 12 ? text : text.substring(0, 12) + "…").append("」");
        }
        if (userTexts.size() > shown) {
            sb.append("；等 ").append(userTexts.size()).append(" 条");
        }
        return sb.toString();
    }

    /** {@code /jsr on|off}：开关讲述人进站广播（立即保存，跨启动保持）。 */
    private static int setImpl(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        if (enabled == on) {
            say(ctx, Component.literal(
                    "[SmoothLift] 讲述人进站广播本来就是" + (on ? "开" : "关") + "的，无需切换"));
            return 1;
        }
        enabled = on;
        save();
        if (on) {
            say(ctx, Component.literal(
                    "[SmoothLift] 讲述人进站广播已开启，已记住，下次启动保持："
                            + "各站台在它自己设定的「剩 N 秒到站」时念出这一班车的终点站与站台编号"
                            + "（N 在石斧右键屏蔽门 →「进站广播(讲述人)」那一行里填）；"
                            + "样式与文字地点可用 /jsr on <样式> <word|chat|off> 一起改"));
            LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人列车报站已开启（/jsr on）");
        } else {
            say(ctx, Component.literal(
                    "[SmoothLift] 讲述人进站广播已关闭，已记住，下次启动保持："
                            + "只关讲述人这一条（**所有**门串都闭嘴），自定义进站广播照旧，"
                            + "/jsr on 恢复"));
            LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人列车报站已关闭（/jsr off）");
        }
        return 1;
    }

    /**
     * 【09-30】{@code /jsr on [样式] [word|chat|off]}：一句话把「开 + 全局样式 + 文字地点」都定下。
     *
     * <p>两个参数都可省：只给样式 = 文字地点不动；只给文字地点 = 样式必须写
     * {@code default}（用户点名的句式 {@code /jsr on default word|chat|off}）。
     * 语音总闸在这条指令里**一定是开**（指令就叫 on）。
     *
     * @param styleToken 样式词（default / default-HK / default-SH / userN）；{@code null} = 不动
     * @param textToken  文字模式词（word / chat / off）；{@code null} = 不动
     */
    private static int setOnImpl(CommandContext<FabricClientCommandSource> ctx, String styleToken, String textToken) {
        // 先都验完再动手：样式合法而文字不合法时，不该把样式先改一半。
        if (styleToken != null && !isValidStyleToken(styleToken)) {
            say(ctx, Component.literal(
                    "[SmoothLift] 未知的讲述人样式「" + styleToken + "」。可用：default（跟每串门"
                            + "自己的设置）、default-HK（香港预设）、default-SH（上海预设）"
                            + (userTexts.isEmpty() ? "；还没有自定义词（先去石斧讲述人页新增）"
                            : "、user1 ~ user" + userTexts.size() + "（自定义词）")));
            return 0;
        }
        TextMode nextText = textMode;
        if (textToken != null) {
            nextText = parseTextMode(textToken);
            // parseTextMode 认不出的一律折成 OFF —— off 本身就是合法值，这里只拦「拼错但不巧不是 off」：
            // word/chat 之外的词都提示一次，免得打错字悄悄把文字关掉还以为设上了。
            if (!TEXT_WORD.equalsIgnoreCase(textToken) && !TEXT_CHAT.equalsIgnoreCase(textToken)
                    && !VALUE_OFF.equalsIgnoreCase(textToken)) {
                say(ctx, Component.literal(
                        "[SmoothLift] 未知的文字模式「" + textToken + "」。可用：word（屏幕中）、"
                                + "chat（聊天框）、off（关闭讲述人文字）"));
                return 0;
            }
        }
        boolean changed = false;
        if (!enabled) {
            enabled = true;
            changed = true;
        }
        if (styleToken != null && !styleToken.equals(style)) {
            style = styleToken;
            changed = true;
        }
        if (nextText != textMode) {
            textMode = nextText;
            if (nextText != TextMode.WORD) {
                TrainAnnounceSubtitle.hide("文字模式已不是屏幕字幕");
            }
            changed = true;
        }
        if (changed) {
            save();
        }
        LOGGER.info("[SmoothLift/TrainAnnounce] /jsr on：样式 {}、文字 {}（语音总闸开）",
                style, textMode);
        say(ctx, Component.literal(
                "[SmoothLift] 讲述人进站广播已开启（已记住）：样式＝" + styleLabel(style)
                        + "；文字出现地点＝" + textModeLabel(textMode)
                        + "。/jsr off 关闭语音；/jsr 查看全部状态"));
        say(ctx, Component.literal(
                "[SmoothLift] 提示：样式选 default = 每一串门念自己的设置（石斧 UI / pbmnarrate）；"
                        + "default-HK / default-SH / userN = 全局都念这一句（门串自己的「关闭」"
                        + "仍然算数）。玩家在 MTR 列车上时语音与文字一律不播"));
        return 1;
    }

    // ------------------------------------------------------------------
    // /jsr round AAA BBB —— 播报范围（格）
    // ------------------------------------------------------------------

    /** 讲述人播报当前生效的**水平（x、z 轴）**范围（格）。播放端用它判「水平在不在范围内」。 */
    public static int narrateRoundXz() {
        return roundXz;
    }

    /** 讲述人播报当前生效的**垂直（y 轴）**范围（格）。播放端用它判「垂直在不在范围内」。 */
    public static int narrateRoundY() {
        return roundY;
    }

    /**
     * 【1.29.1204】「玩家到播报声源」的**水平 / 垂直两维距离**，分开算。
     *
     * <p>水平 = {@code Math.hypot(dx, dz)}，垂直 = {@code Math.abs(dy)}；各维都取
     * 「现算本串最近门」与「起播那扇固定坐标」两者的**较小值** —— 这一串这一刻不在门快照里时
     * （列车进站那瞬的空档、或玩家走到渲染距离外）自动退回固定坐标，不会因快照闪一下就把播报撤掉。
     *
     * @param runKey 播报声源那一串门的身份；{@link Long#MIN_VALUE} = 调用方没提供 ⇒ 只用固定坐标
     * @param x      起播那一刻最近那扇门的锚点 X（兜底用）
     * @param y      同上，Y
     * @param z      同上，Z
     * @param player 玩家位置；{@code null}（未进世界）当贴脸处理 = 0
     * @return 水平 / 垂直两维距离（格）
     */
    public static NarrateDistance narrateDistances(long runKey, double x, double y, double z, Vec3 player) {
        if (player == null) {
            return new NarrateDistance(0.0, 0.0);
        }
        double frozenXz = Math.hypot(player.x() - x, player.z() - z);
        double frozenY = Math.abs(player.y() - y);
        if (runKey != Long.MIN_VALUE) {
            PsdDoorTracker.DoorView nearest = PsdDoorTracker.nearestInRun(runKey, player);
            if (nearest != null) {
                double liveXz = Math.hypot(player.x() - nearest.x(), player.z() - nearest.z());
                double liveY = Math.abs(player.y() - nearest.y());
                return new NarrateDistance(Math.min(liveXz, frozenXz), Math.min(liveY, frozenY));
            }
        }
        return new NarrateDistance(frozenXz, frozenY);
    }

    /**
     * 【1.29.1204】越界即停 / 不念的判据：**水平 ≥ 水平范围 或 垂直 ≥ 垂直范围**。
     *
     * @return {@code true} = 已在播报范围之外（正在念的该停、候选不该起播）
     */
    public static boolean isOutsideNarrateRange(long runKey, double x, double y, double z, Vec3 player) {
        NarrateDistance d = narrateDistances(runKey, x, y, z, player);
        return d.horizontal() >= roundXz || d.vertical() >= roundY;
    }

    /**
     * 【1.29.1204】水平 / 垂直两维距离的小包（{@link #narrateDistances} 的返回）。
     *
     * @param horizontal 水平（x、z 轴）距离（格，= hypot(dx, dz)）
     * @param vertical   垂直（y 轴）距离（格，= |dy|）
     */
    public record NarrateDistance(double horizontal, double vertical) {
    }

    /**
     * {@code /jsr round <AAA> <BBB>}：设置讲述人播报范围并立即落盘（1~128，越界夹取）。
     * AAA = 水平（x、z 轴）范围，BBB = 垂直（y 轴）范围。
     */
    private static int setRoundImpl(CommandContext<FabricClientCommandSource> ctx, int valueXz, int valueY) {
        int clampedXz = clampRound(valueXz);
        int clampedY = clampRound(valueY);
        if (clampedXz != valueXz || clampedY != valueY) {
            say(ctx, Component.literal(
                    "[SmoothLift] 范围已夹取到合法区间（1~128）"));
        }
        int beforeXz = roundXz;
        int beforeY = roundY;
        roundXz = clampedXz;
        roundY = clampedY;
        save();
        boolean changed = beforeXz != roundXz || beforeY != roundY;
        say(ctx, Component.literal(
                "[SmoothLift] 讲述人播报范围" + (changed ? "已设为 " : "仍为 ")
                        + "水平（x、z 轴）" + roundXz + " 格、垂直（y 轴）" + roundY + " 格，"
                        + "已记住，下次启动保持：玩家到最近那扇屏蔽门水平超过 " + roundXz
                        + " 格或垂直超过 " + roundY + " 格就不播，正在念的也会立刻停"
                        + "（同时处于多串门范围内时，以最近的那一串为准；玩家在 MTR 列车上也不播）"));
        LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人播报范围：水平 {} → {} 格、垂直 {} → {} 格（/jsr round）",
                beforeXz, roundXz, beforeY, roundY);
        return 1;
    }

    // ------------------------------------------------------------------
    // 预设联动（文字模式档）
    // ------------------------------------------------------------------

    /**
     * 【09-29】「经典港铁预设」按钮用：把讲述人**文字**设成屏幕字幕（word）并立刻落盘。
     * 【09-30】实现从「字幕布尔开关」升级成「文字出现地点三档」，预设仍指 word 那一档。
     *
     * @return 有没有真的改动（本来就是 word ⇒ {@code false}，且不写盘）
     */
    public static boolean enableWordForPreset() {
        if (textMode == TextMode.WORD) {
            return false;
        }
        textMode = TextMode.WORD;
        save();
        LOGGER.info("[SmoothLift/TrainAnnounce] 应用经典港铁预设：讲述人文字设为屏幕字幕（word）");
        return true;
    }

    /**
     * 【10-01】「经典港铁预设」按钮用：把讲述人**文字**设成**聊天框（chat）**并立刻落盘。
     * 用户点名「mbm help 里的经典港铁预设：进站广播 … 聊天框播报」（替代旧版的屏幕字幕）。
     *
     * @return 有没有真的改动（本来就是 chat ⇒ {@code false}，且不写盘）
     */
    public static boolean enableChatForPreset() {
        if (textMode == TextMode.CHAT) {
            return false;
        }
        textMode = TextMode.CHAT;
        save();
        LOGGER.info("[SmoothLift/TrainAnnounce] 应用经典港铁预设：讲述人文字设为聊天框（chat）");
        return true;
    }

    /**
     * 【09-29】「简单港铁预设 / 空白预设」按钮用：把讲述人**文字**关掉（off）并立刻落盘。
     *
     * @return 有没有真的改动（本来就是 off ⇒ {@code false}，且不写盘）
     */
    public static boolean disableWordForPreset() {
        if (textMode == TextMode.OFF) {
            return false;
        }
        textMode = TextMode.OFF;
        save();
        TrainAnnounceSubtitle.hide("应用预设：讲述人文字随讲述人一起关闭");
        LOGGER.info("[SmoothLift/TrainAnnounce] 应用预设：讲述人文字已关闭（off）");
        return true;
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    private static void save() {
        Path file = configFile();
        if (file == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty(KEY_ENABLED, enabled ? "on" : VALUE_OFF);
        props.setProperty(KEY_ROUND_XZ, String.valueOf(roundXz));
        props.setProperty(KEY_ROUND_Y, String.valueOf(roundY));
        // 【1.29.1204】旧单值键也顺手写一份（= 水平值）：万一回退到 1.28 旧版，老逻辑读到它行为不变。
        props.setProperty(KEY_ROUND, String.valueOf(roundXz));
        // 【09-30】新三族键：样式 / 文字地点 / 自定义词（词按 1..N 连续重编号）。
        props.setProperty(KEY_STYLE, style);
        props.setProperty(KEY_TEXT, textMode == TextMode.WORD ? TEXT_WORD
                : textMode == TextMode.CHAT ? TEXT_CHAT : VALUE_OFF);
        // 旧字幕键同步写一份（word=on / 其余=off），回退到旧版时字幕开关也能对上。
        props.setProperty(KEY_WORD, textMode == TextMode.WORD ? "on" : VALUE_OFF);
        // 【10-01】自定义词**不再写 config** —— 改存存档（服务端 EscalatorSpeedData NBT，
        //   见 SET_PSD_NARRATE_TEXTS_CHANNEL 与 applyNarrateUserTexts）。config 只留一次性
        //   迁移标记；旧文件里的 trainArriveAnnounceUser.N 键会随这次保存自然消失。
        props.setProperty(KEY_USER_TEXT_MIGRATED, String.valueOf(userTextMigrated));
        // 【09-30 续 4】站台讲述人的全局层（总闸 / 样式 / 文字地点 / 范围）。
        props.setProperty(KEY_M_ENABLED, midiumEnabled ? "on" : VALUE_OFF);
        props.setProperty(KEY_M_STYLE, midiumStyle);
        props.setProperty(KEY_M_TEXT, midiumTextMode == TextMode.WORD ? TEXT_WORD
                : midiumTextMode == TextMode.CHAT ? TEXT_CHAT : VALUE_OFF);
        props.setProperty(KEY_M_ROUND_XZ, String.valueOf(midiumRoundXz));
        props.setProperty(KEY_M_ROUND_Y, String.valueOf(midiumRoundY));
        try {
            Files.createDirectories(file.getParent());
            // ★【09-30】改用 UTF-8 写：自定义词是玩家手打的中文，明文存着才看得懂
            //   （Properties.store(OutputStream) 会按 ISO-8859-1 把中文全转成反斜杠 u 转义）。
            try (OutputStreamWriter out = new OutputStreamWriter(
                    Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                props.store(out, "mzycBetterMTR train arrive announce switch (/jsr, 1.29.1204)");
            }
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift] 保存列车报站开关配置失败: {}", file, e);
        }
    }

    private static Path configFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 【10-01】指令回执统一化（与模组其它指令逐字一致）
    //   动作类 → 只回「指令执行成功 / 指令执行失败」（方法的详细文案转进日志）
    //   查询类 → 只回值（show / showMidium 原样不动）
    // ------------------------------------------------------------------

    /** 统一回执文案（与 SmoothLift 里服务端指令的文案逐字一致）。 */
    private static final String CMD_OK = "指令执行成功";
    private static final String CMD_FAIL = "指令执行失败";

    /** 动作处理器执行期间为 true ⇒ {@link #say} 静默（详细文案只进日志，不回给玩家）。 */
    private static boolean quiet;

    /** 所有回执的唯一出口；quiet 时只落日志（信息不丢，只是不刷屏）。 */
    private static void say(CommandContext<FabricClientCommandSource> ctx, Component text) {
        if (quiet) {
            LOGGER.info("[SmoothLift/TrainAnnounce] {}", text.getString());
            return;
        }
        ctx.getSource().sendFeedback(text);
    }

    /**
     * 动作类指令的统一收尾：handler 全程静默，跑完只回一句。
     * <p>返回 1 ⇒ 「指令执行成功」；返回 0 或抛异常 ⇒ 「指令执行失败」。
     */
    private static int action(CommandContext<FabricClientCommandSource> ctx, java.util.function.IntSupplier body) {
        int result;
        quiet = true;
        try {
            result = body.getAsInt();
        } catch (RuntimeException e) {
            quiet = false;
            LOGGER.warn("[SmoothLift/TrainAnnounce] 指令执行出错：{}", e.toString());
            ctx.getSource().sendFeedback(Component.literal(CMD_FAIL));
            return 0;
        }
        quiet = false;
        ctx.getSource().sendFeedback(Component.literal(result == 1 ? CMD_OK : CMD_FAIL));
        return result;
    }

    /** 【10-01】{@code /jsr arrive on|off}：动作类 —— 只回「指令执行成功 / 指令执行失败」。 */
    public static int set(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        return action(ctx, () -> setImpl(ctx, on));
    }

    /** 【10-01】{@code /jsr arrive on <样式> [word|chat|off]}：动作类 —— 只回「指令执行成功 / 指令执行失败」。 */
    public static int setOn(CommandContext<FabricClientCommandSource> ctx, String styleToken, String textToken) {
        return action(ctx, () -> setOnImpl(ctx, styleToken, textToken));
    }

    /** 【10-01】{@code /jsr round AAA BBB}：动作类 —— 只回「指令执行成功 / 指令执行失败」。 */
    public static int setRound(CommandContext<FabricClientCommandSource> ctx, int valueXz, int valueY) {
        return action(ctx, () -> setRoundImpl(ctx, valueXz, valueY));
    }

    /** 【10-01】{@code /jsr midium on|off}：动作类 —— 只回「指令执行成功 / 指令执行失败」。 */
    public static int setMidium(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        return action(ctx, () -> setMidiumImpl(ctx, on));
    }

    /** 【10-01】{@code /jsr midium on <样式> [word|chat|off]}：动作类 —— 只回「指令执行成功 / 指令执行失败」。 */
    public static int setMidiumOn(CommandContext<FabricClientCommandSource> ctx, String styleToken, String textToken) {
        return action(ctx, () -> setMidiumOnImpl(ctx, styleToken, textToken));
    }

    /** 【10-01】{@code /jsr midium round AAA BBB}：动作类 —— 只回「指令执行成功 / 指令执行失败」。 */
    public static int setMidiumRound(CommandContext<FabricClientCommandSource> ctx, int valueXz, int valueY) {
        return action(ctx, () -> setMidiumRoundImpl(ctx, valueXz, valueY));
    }

    /** 【10-01】{@code /jsr define userN <文字>}：动作类 —— 只回「指令执行成功 / 指令执行失败」。 */
    public static int defineUserText(CommandContext<FabricClientCommandSource> ctx, String slot, String text) {
        return action(ctx, () -> defineUserTextImpl(ctx, slot, text));
    }
}
