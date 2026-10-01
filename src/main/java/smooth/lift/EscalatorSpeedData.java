package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每个维度一份，随世界存档自动加载/保存（存在 <世界>/<维度>/data/smoothlift_speeds.dat）。
 *
 * defaultSpeed：本维度未单独调速的扶梯使用的默认运行速度。
 * speeds：每个扶梯方块的运行速度（石斧设置）。
 *
 * 【1.6 起】阶梯动画速度以「每条扶梯单独设置」为主：
 * stepSpeeds：每条扶梯（链上每个方块）单独设置的阶梯动画速度（石斧设置）。
 *             没有单独设置的扶梯回退到「维度默认阶梯动画速度」。
 * axeModified：石斧设置过阶梯动画的扶梯方块集合（用于同步与 /jietispeed 的 f 覆盖）。
 * stepEnabled / stepValue：维度默认阶梯动画速度（未单独设置的扶梯使用）；
 *             stepEnabled=false 表示用 MTR 原版动画（{@link #VANILLA_STEP}）。
 *
 * 【1.7 起】自定义扶梯声音：
 * audioLibrary：音频ID → OGG 文件字节。音频按内容哈希去重存一份，天然支持"一个音频复用多条扶梯"。
 * blockAudio：扶梯方块（x,y,z）→ 音频ID。未绑定的扶梯不播放声音（静音）。
 *             受 NBT 读取大小限制（NbtIo.readCompressed 带 NbtSizeTracker），单音频不超过
 *             {@link #MAX_AUDIO_BYTES}（12MB），超出时服务端拒绝接收。
 *
 * 【1.9 起】扶梯声音音量：
 * blockVolume：扶梯方块（x,y,z）→ 音量（1~1000；【1.12】100 = 原始音量，1000 = 10× 放大）。
 *             只记录被调过的（≠100）方块，未记录的按默认 100 处理，旧存档缺这一段也能正常读。
 *
 * 【1.11 起】默认扶梯音频：
 * defaultAudio：未单独绑定音频的扶梯使用的默认音频 ID（/futimusic 设置）。
 *              null 表示不发声（未绑定的扶梯静音，与 1.10 之前的行为一致）。
 *
 * 【1.16 起】无障碍提示音开关（香港式「视障人士提升音」，见客户端 EscalatorChimePlayer）：
 * defaultHelp：本维度未单独设置的扶梯是否播放提示音（/futihelp on|off 设置）。默认 true = 开。
 * blockHelp：扶梯方块（x,y,z）→ 该条扶梯是否播放提示音（石斧界面里的「无障碍提示音」开关）。
 *             只记录与 {@link #defaultHelp} **不同**的项，未记录的按默认处理，旧存档缺这一段也能正常读
 *             （缺省即 true = 开，与 1.15 的「一直响」行为一致）。
 *             同一维度的默认值 + 单独设置，与 /futispeed、/futimusic、/futiloud 完全对称。
 *
 * 【1.18 起】无障碍提示音**音量**（/futihelploud 与石斧界面设置）：
 * defaultHelpVolume：本维度未单独设置的扶梯，提示音音量用多少（/futihelploud &lt;音量&gt; 设置）。默认 100 = 原始音量。
 * blockHelpVolume：扶梯方块（x,y,z）→ 提示音音量。只记录与 {@link #defaultHelpVolume} **不同**的项，
 *             未记录的按默认处理，旧存档缺这一段也能正常读（缺省即 100 = 原始音量，与 1.17 的「固定音量」一致）。
 *             注意这与 {@link #defaultVolume} / {@link #blockVolume}（扶梯**运行底噪**的音量）是**两件不同的事**：
 *             底噪声作用在整条扶梯上、射程 16 格；提示音装在端头**单块**方块上、射程 4 格（见 EscalatorChimePlayer）。
 *             两套音量的数据与指令（/futiloud vs /futihelploud）互不影响。
 *
 * 【1.24 起】两个「淡入淡出范围」（单位格）——把两个音源的作用半径做成可调的：
 * defaultRound / blockRound：**扶梯运行底噪**（整条扶梯一起响那路）可闻的距离，
 *             /futiround 设置，默认 {@link #DEFAULT_ROUND} = 16 格（整条扶梯一起响的环境音）。
 * defaultHelpRound / blockHelpRound：**无障碍提示音**（端头单块那路）可闻的距离，
 *             /futihelpround 设置，默认 {@link #DEFAULT_HELP_ROUND} = 4 格（点状音源）。
 *             两者与 1.17 / 1.18 定的「16 : 4」语义一致，只是现在**可调**了。
 *             与音量一样只记录与默认**不同**的项，旧存档缺这一段就按默认处理。
 *             <b>这两个范围是「淡入淡出」的半径而不是硬截断</b>：底噪在半径内线性衰减到 0、
 *             提示音在半径内平方衰减到 0，所以「听得见的最远距离」就等于它。
 *             注意 1.24 **没有给它们加石斧界面控件**（用户要求），只能靠指令改。
 *
 * <p>至此，每一个「可调项」都统一是「维度默认 + 每条扶梯单独设置」两层：
 * 速度（defaultSpeed）、阶梯动画（stepValue/stepSpeeds）、音频（defaultAudio/blockAudio）、
 * 底噪音量（defaultVolume/blockVolume）、提示音开关（defaultHelp/blockHelp）、
 * 提示音音量（defaultHelpVolume/blockHelpVolume）、
 * 底噪范围（defaultRound/blockRound）、提示音范围（defaultHelpRound/blockHelpRound）、
 * 提示音速率（defaultHelpSpeedIn/Out + blockHelpSpeedIn/Out）、
 * 提示音音乐（defaultHelpAudioIn/Out + blockHelpAudioIn/Out）。
 *
 * 【1.31 起】无障碍提示音的**速率**（每秒响几次，单位 Hz）——把 1.25~1.27 定死的
 * 「入口 10 Hz / 出口 1 Hz」做成可调（{@code /futihelpspeed in|out <Hz>}）：
 * defaultHelpSpeedIn / blockHelpSpeedIn：**进入扶梯（上客端）**那一路的速率，默认 10 Hz。
 * defaultHelpSpeedOut / blockHelpSpeedOut：**离开扶梯（落客端）**那一路的速率，默认 1 Hz。
 *             两者与开关（defaultHelp）、音量（defaultHelpVolume）、范围（defaultHelpRound）
 *             是**四套互不影响**的数据，都作用在同一路提示音上：
 *             开关决定「响不响」、音量「多响」、范围「多远还听得见」、速率「响得多快」。
 *             也只记录与默认**不同**的项，旧存档缺这一段就按 10 / 1 Hz 处理
 *             （正好等于 1.27 定版音色，等于没改过）。
 *             注意「速率」是靠**换素材 + 调 pitch** 实现的（原版把 pitch 夹在 [0.5,2.0]），
 *             细节见 {@code EscalatorChimePlayer}；取值范围 {@link #HELP_SPEED_MIN}~{@link #HELP_SPEED_MAX}。
 *
 * 【1.39 起】无障碍提示音的**音乐**（用哪段声音当提示音）：
 * defaultHelpAudioIn/Out / blockHelpAudioIn/Out：与 {@code /futimusic} 的运行底噪**完全对称**的
 *             第二套「音频绑定」，【1.28】音频隔离后**分文件夹存放**（提示音读
 *             {@code MBM_Audio/futi/help}、底噪读 {@code MBM_Audio/futi/music}），
 *             字节库 {@link #audioLibrary} 仍是一份，但各自的「已导入注册表」独立。
 *             {@link #HELP_AUDIO_DEFAULT} = 模组原来的提示音（初始值，旧存档缺字段也是它）；
 *             {@link #HELP_AUDIO_OFF} = 这一头不播提示音；其它值 = 音频库里的文件名。
 *             ★ 速率（/futihelpspeed）只对 {@link #HELP_AUDIO_DEFAULT} 生效 ——
 *             自定义音频按原速循环播（素材是玩家自己的，没法按 1/4/10/25/50 Hz 分档）。
 *
 * 【1.41 起】提示音**音乐**也分成**进入 / 离开**两套（形状同 /futihelpspeed 的 in|out）：
 *             进扶梯那一头（上客端）与出扶梯那一头（落客端）可以各放各的声音，
 *             于是「进站播一段、出站播另一段」这种需求不用再靠改素材实现。
 *             1.39 的单一字段（{@code defaultHelpAudio} / {@code blockHelpAudio}）在
 *             {@link #fromTag} 里被**同时**当作两头初值读入 ⇒ 旧存档听感逐字节不变；
 *             保存时再把它当**兼容镜像**写回（= 进扶梯那一头），供回退版本读取。
 *             ★ `off` 的粒度也跟着细了：现在可以只让**这一头**不响、另一头照常响
 *             （在 {@code EscalatorChimePlayer} 里按端头分别拦，见那里 1.41 段）。
 *
 */
public class EscalatorSpeedData extends SavedData {
    public static final String DATA_NAME = "smoothlift_speeds";
    public static final double DEFAULT_SPEED = 1.0;
    public static final double MAX_SPEED = 50.0;

    /** MTR 原版阶梯贴图动画对应的运行速度标定值。阶移动画基准：把速度除以它得到倍率。 */
    public static final double VANILLA_STEP = 0.625;

    /** 单个自定义音频的大小上限（字节）。防止存档 NBT 超限，且避免拖慢声音解码。 */
    public static final int MAX_AUDIO_BYTES = 12 * 1024 * 1024;

    /** 【1.18.1204】单个地图图片的大小上限（字节）。/MBM picture new 时校验，防 NBT 超限。 */
    public static final int MAX_PICTURE_BYTES = 12 * 1024 * 1024;

    /**
     * 【1.9】扶梯声音音量：界面输入范围 1~1000。
     * <p>【1.12】100 = **原始音量（1.0×）**，1000 = **10× 放大**。
     * 原版 {@code SoundEngine.calculateVolume} 会把增益夹到 [0,1]，所以 &gt;100 的放大由
     * 客户端 Mixin（{@code SoundEngineVolumeMixin}）放开上限实现，详见 {@code EscalatorAudioPlayer}。
     */
    public static final int AUDIO_VOLUME_MIN = 1;
    public static final int AUDIO_VOLUME_MAX = 1000;
    /** 默认（= 原始 1.0×）音量。只记录与该值不同的项，未记录即按 100 处理。 */
    public static final int DEFAULT_AUDIO_VOLUME = 100;

    /**
     * 【1.18】无障碍**提示音**音量：范围与 {@link #AUDIO_VOLUME_MIN}~{@link #AUDIO_VOLUME_MAX} 完全一致
     * （1~1000，100 = 原始音量，1000 = 10× 放大）。这里直接引用音频那三个常量，
     * 保证两套音量永远不会因为改了一处而悄悄不一致。
     *
     * <p>提示音的实际增益 = {@code 距离衰减（4 格内平方衰减）× 这个百分比}；
     * &gt;100 的放大同样由客户端 {@code SoundEngineVolumeMixin} 放开 [0,1] 夹取来实现。
     */
    public static final int HELP_VOLUME_MIN = AUDIO_VOLUME_MIN;
    public static final int HELP_VOLUME_MAX = AUDIO_VOLUME_MAX;
    /** 默认提示音音量 = 100（原始音量）。只记录与该值不同的项。 */
    public static final int DEFAULT_HELP_VOLUME = DEFAULT_AUDIO_VOLUME;

    /**
     * 【1.24】两个「淡入淡出范围」的取值区间（单位：格）。
     *
     * <p>上限取 128 是因为扫描半径是按它算的（AABB 预筛 / 逐格 abs 比较），
     * 128 格对任何车站都够用，而代价只有「附近没有扶梯时多扫一点」——
     * 那一段本来就在未加载区块里，开销可以忽略。
     */
    public static final int ROUND_MIN = 1;
    public static final int ROUND_MAX = 128;

    /**
     * 【1.24】**扶梯运行底噪**（{@code EscalatorAudioPlayer}，整条扶梯一起响）的默认可闻范围 = 16 格。
     * 与 1.9 起一直沿用的 {@code MAX_DISTANCE = 16.0} 一致，只是现在可以被 /futiround 改。
     */
    public static final int DEFAULT_ROUND = 16;

    /**
     * 【1.24】**无障碍提示音**（{@code EscalatorChimePlayer}，端头单块）的默认可闻范围 = 4 格。
     * 与 1.17 定的 {@code RANGE = 4.0} 一致。两者**故意不同**（16 : 4），别再混。
     */
    public static final int DEFAULT_HELP_ROUND = 4;

    /**
     * 【1.31】无障碍提示音**速率**的取值区间，单位 **Hz（每秒响几次）**。
     *
     * <p>播放侧靠「**多个素材 × pitch**」拼速率：原版 {@code SoundEngine.calculatePitch} 把 pitch
     * 夹在 <b>[0.5, 2.0]</b>（字节码：{@code Mth.clamp(getPitch(), 0.5F, 2.0F)}），一个素材只覆盖
     * 4 倍的速率区间，所以用 1 / 4 / 10 Hz 三个素材的 {@code [0.5×, 2×]} 区间首尾相接，
     * 合起来正好覆盖 <b>[0.5, 20] Hz</b>。
     *
     * <p><b>【1.34】上限从 20 提到 100</b>（用户反馈「1-20 太小了」）：同时新增了 <b>25 Hz</b> 与
     * <b>50 Hz</b> 两个素材，一度开到 <b>[1, 100] Hz</b>。
     *
     * <p><b>【1.38】上限回落 100 → 50</b>（用户要求「无障碍提示频率范围从 1-1000 改为 1-50」，
     * 经确认指的就是本参数）：高段（50~100 Hz）听感上已经是「一片连续电流声」而不是「一响一响」，
     * 无障碍提示的语义（让人数得清、跟得上扶梯进出）反而丢了，所以砍掉。
     * <b>素材不需要动</b>：tier5 是 50 Hz 原始素材，pitch 钳在 [0.5, 2.0] ⇒ 单独就能覆盖
     * [25, 50] Hz，正好接上 tier4（25 Hz，覆盖 [12.5, 25]）—— 加素材才覆盖得到 100，
     * 而 50 以内不需要，所以这次只改常量即可。
     * ★ 这个常量**必须与素材一起改**：只放宽常量而不加素材，超出的部分会被 pitch 钳制
     * 悄悄压回去，表现是「调 50 跟调 20 一模一样」（见 {@code EscalatorChimePlayer#chimeEventFor}）。
     */
    public static final int HELP_SPEED_MIN = 1;
    public static final int HELP_SPEED_MAX = 50;

    /**
     * 【1.31】**进入扶梯（上客端）**无障碍提示音的默认速率 = **10 Hz**（1/10 秒一响）。
     *
     * <p>与 1.25~1.27 由用户真机听感定下的「入口 1/10 秒一次」完全一致；
     * 它也正好等于 10 Hz 素材的原始速率（pitch = 1.0），所以**默认档不改动任何既有音色**。
     */
    public static final int DEFAULT_HELP_SPEED_IN = 10;

    /**
     * 【1.31】**离开扶梯（落客端）**无障碍提示音的默认速率 = **1 Hz**（1 秒一响）。
     *
     * <p>与 1.27 定下的「出口 1 秒一次」一致，也正好等于 1 Hz 素材的原始速率（pitch = 1.0）。
     */
    public static final int DEFAULT_HELP_SPEED_OUT = 1;

    // ------------------------------------------------------------------
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    // 这是**与扶梯完全无关**的另一件事：MTR 直梯在**关门**时连播 4 次 liftmusic.ogg、
    // **开门**时连播 2 次（固定间隔，见客户端 LiftChimePlayer）；【1.52】这两个次数只对**内置**
    // liftmusic 成立，玩家导入的 ogg 只播一次。「哪条直梯」由客户端按
    // 「离玩家最近的直梯」现算，**不需要在这里存任何按扶梯方块索引的数据** ——
    // 所以这套数据只有「维度默认」一层，没有 blockXxx 那张表，比上面所有设置都轻。
    //
    // ★ 这两条设置**按维度**存（和其它设置一样，一份 ServerLevel 一份 SavedData）。
    //   指令里的 `-f` 因此是「**对所有维度**强制」（见 SmoothLift 里的 lifthelp），
    //   而不是「对这条直梯强制」—— 直梯没有单条粒度的设置。
    // ------------------------------------------------------------------

    /**
     * 【1.42】直梯开关门提示音的**倍速**区间。
     *
     * <p>为什么上限就是 **2.0**、下限就是 **0.5**：倍速是直接写进
     * {@code SoundInstance.pitch} 的，而原版 {@code SoundEngine.calculatePitch} 把它
     * **硬夹在 [0.5F, 2.0F]**（与提示音速率那边的素材分档是同一个限制）。
     * 也就是说填 3.0 只会被悄悄压回 2.0，不如在这里就夹住并如实告诉玩家 ——
     * 否则玩家会得到「填了 3 但听起来和 2 一样」这种最难查的反馈。
     */
    public static final float LIFT_HELP_SPEED_MIN = 0.5f;
    public static final float LIFT_HELP_SPEED_MAX = 2.0f;

    /** 【1.42】默认倍速 = 1.0（原速，和直接听 liftmusic.ogg 完全一样）。 */
    public static final float DEFAULT_LIFT_HELP_SPEED = 1.0f;

    /**
     * 【1.42】同一条提示音**连播时相邻两次的间隔**（秒）。
     *
     * <p>素材本身长 0.859 秒；这里取 0.8 秒（略短于素材）是用户选定的「固定间隔连播」：
     * 4 次关门音 ≈ 2.4 秒、2 次开门音 ≈ 0.8 秒，**不随门运动时长伸缩**。
     * 实际间隔 = {@code 本值 / 倍速}（倍速越快，连播也越密），见客户端 {@code LiftChimePlayer}。
     */
    public static final double LIFT_HELP_INTERVAL_SECONDS = 0.8;

    /** 【1.42】关门时连播几次（**仅内置 liftmusic**；【1.52】玩家导入的 ogg 只播一次）。 */
    public static final int LIFT_HELP_CLOSE_REPEATS = 4;

    /** 【1.42】开门时连播几次（**仅内置 liftmusic**；【1.52】玩家导入的 ogg 只播一次）。 */
    public static final int LIFT_HELP_OPEN_REPEATS = 2;

    /**
     * 【1.43】直梯开关门提示音的**默认音量**（{@code /lifthelploud} 设置）。
     *
     * <p>取值区间直接复用扶梯那两套音量的
     * [{@link #HELP_VOLUME_MIN}, {@link #HELP_VOLUME_MAX}]（= 1~1000，100 = 原始音量、
     * 1000 = 10× 放大）—— 引用同一组常量而不是另写一份数字，保证「运行底噪 / 无障碍提示音 /
     * 直梯提示音」三套音量永远不会因为改了其中一处而悄悄不一致。
     *
     * <p>&gt;100 的放大和另外两套一样，需要客户端成对做两件事
     * （实例实现 {@code GainManagedSound} 让 {@code SoundEngineVolumeMixin} 放行 [0,1] 夹取，
     * 以及开播时把该 OpenAL 源的 {@code AL_MAX_GAIN} 抬到
     * {@code EscalatorAudioPlayer#MAX_GAIN}）—— 只做一件仍然最多 1.0×，见 {@code LiftChimePlayer}。
     */
    public static final int DEFAULT_LIFT_HELP_VOLUME = DEFAULT_HELP_VOLUME;

    /**
     * 【1.39】无障碍提示音「音乐」的哨兵 ID：**模组原来的提示音**（五档「咔啪」素材 + 速率分档）。
     *
     * <p>这是 {@link #defaultHelpAudioIn} / {@link #defaultHelpAudioOut} 的初始值，
     * 也是旧存档缺字段时的取值 ⇒ **1.38 及之前的行为逐字节不变**
     * （进扶梯端 10 Hz、出扶梯端 1 Hz 那套）。
     *
     * <p>它和 {@code /futimusic} 的 {@code default}（内置运行底噪）是**两件不同的事**：
     * 那个是整条扶梯 23 秒的环境音，这个是端头 2 秒一循环的定位提示音。
     */
    public static final String HELP_AUDIO_DEFAULT = "default";

    /**
     * 【1.39】无障碍提示音「音乐」的哨兵 ID：**这条扶梯不播提示音**（比 {@code /futihelp off} 更细 ——
     * 可以只让某一条扶梯哑掉，而不动维度默认与其它扶梯）。
     */
    public static final String HELP_AUDIO_OFF = "off";

    /**
     * 【09-27】扶梯**运行底噪**（{@code /futimusic} 那一路）的「不播」哨兵。
     *
     * <p>它会被写进 {@code blockAudio}（方块 → 音频ID）里，含义是「**这一条扶梯静音**」，
     * 而且**压过维度默认层** —— 否则「解绑」在默认层有声音时根本静不下来。
     *
     * <p>在它之前，「不播」只有默认层那一档（{@code /futimusic off} 把 {@code defaultAudio}
     * 写成 {@code null}），**没法只让某一条扶梯哑掉**；石斧界面「选择扶梯音乐」右列
     * 第 0 行那个「不播」就是绑它。
     *
     * <p>它不带 {@code builtin:} 前缀、也不在音频库里 ⇒ {@code bindAudio} 专门放行，
     * 播放端专门短路（不许走到「音频还没同步」那条提示上去）。
     */
    public static final String FUTI_AUDIO_OFF = "off";

    public double defaultSpeed = DEFAULT_SPEED;
    public final Map<BlockPos, Double> speeds = new HashMap<>();

    /** 石斧自定义过阶梯动画的扶梯方块集合（/jietispeed on|off 默认忽略它们）。 */
    public final Set<BlockPos> axeModified = new HashSet<>();
    /** 石斧给这些扶梯单独设置的阶梯动画速度（仅 stepSpeeds 里的方块有效）。 */
    public final Map<BlockPos, Double> stepSpeeds = new HashMap<>();
    /** 维度默认阶梯动画速度开关：false = MTR 原版动画；true = 使用 stepValue。 */
    public boolean stepEnabled = false;
    /** 维度默认阶梯动画速度值（最后一次 /jietispeed X 设定的值）。 */
    public double stepValue = DEFAULT_SPEED;

    /** 音频ID → OGG 文件字节（按内容哈希去重，一个音频可被多条扶梯复用）。 */
    public final Map<String, byte[]> audioLibrary = new HashMap<>();
    /**
     * 【1.28】音频分类 → 该分类已导入存档的音频名集合。
     *
     * <p>「音频隔离」：每个设置项只读自己分类的文件夹（{@code MBM_Audio/<分类>}），
     * 导入存档后名字记进**这个分类**的集合；各界面的「已存入」列表只列自己分类的名字。
     * 字节本体仍共用 {@link #audioLibrary}（按名字取），分类只决定「哪个界面看得见 / 删哪个」。
     *
     * <p>旧存档（1.27 及以前）没有这张表 ⇒ {@code fromTag} 迁移时把平铺库里的名字放进
     * **所有**分类（旧版是一个共享库，任何界面都能看到，迁移后保持「到处都看得到」）。
     */
    public final Map<String, Set<String>> audioCategoryNames = new HashMap<>();
    /** 扶梯方块（x,y,z）→ 音频ID。未绑定音频的扶梯不播放声音。 */
    public final Map<BlockPos, String> blockAudio = new HashMap<>();

    /** 【1.18.1204】图片ID → 原始图片文件字节（MBM_Picture 文件夹导入，直接融入存档，删原图不影响）。 */
    public final Map<String, byte[]> pictureLibrary = new HashMap<>();
    /** 【1.18.1204】当前显示图片ID（12 个图片方块共用一张图的 4 个切块）；null = 库空，显示白色+灰边。 */
    public String pictureCurrent = null;

    /**
     * 【1.9】扶梯方块（x,y,z）→ 声音音量（1~1000，100 = 原始音量，可放大到 1000 = 10×）。
     * 只记录与 {@link #defaultVolume} **不同**的项（默认情况即 100），未记录的按默认音量处理，
     * 这样 NBT 不会膨胀。
     */
    public final Map<BlockPos, Integer> blockVolume = new HashMap<>();

    /**
     * 【1.12】默认扶梯音量（/futiloud 设置）：**没有单独设置音量**的扶梯使用它。
     * 默认 100（= 原始音量）。与 /futispeed 的 defaultSpeed、/futimusic 的 defaultAudio 完全对称。
     */
    public int defaultVolume = DEFAULT_AUDIO_VOLUME;

    /**
     * 【1.11】默认扶梯音频 ID（/futimusic 设置）：**没有单独绑定音频**的扶梯使用它。
     * 内置音频存 {@code builtin:<key>}，玩家上传的存文件名（如 {@code example.ogg}）。
     * {@code null} = 没有默认音频，未绑定的扶梯保持静音（与 1.10 之前一致，旧存档也兼容）。
     */
    public String defaultAudio;

    /**
     * 【1.16】维度默认无障碍提示音开关（/futihelp on|off）：**没有单独设置**的扶梯是否播提示音。
     * 默认 true = 开（旧存档缺这一段也是开，与 1.15 的「一直响」行为一致）。
     */
    public boolean defaultHelp = true;

    /**
     * 【1.16】扶梯方块（x,y,z）→ 这条扶梯是否播放无障碍提示音。
     * 只记录与 {@link #defaultHelp} **不同**的项（同一开关，多数情况为空），NBT 不会膨胀。
     */
    public final Map<BlockPos, Boolean> blockHelp = new HashMap<>();

    /**
     * 【1.18】维度默认无障碍提示音音量（/futihelploud 设置）：**没有单独设置**的扶梯使用它。
     * 默认 100（= 原始音量）。与 {@link #defaultVolume}（底噪音量）是两套互不影响的数据。
     */
    public int defaultHelpVolume = DEFAULT_HELP_VOLUME;

    /**
     * 【1.18】扶梯方块（x,y,z）→ 这条扶梯的无障碍提示音音量（1~1000，100 = 原始音量）。
     * 只记录与 {@link #defaultHelpVolume} **不同**的项，未记录的按默认音量处理，旧存档缺这一段也能正常读。
     */
    public final Map<BlockPos, Integer> blockHelpVolume = new HashMap<>();

    /**
     * 【1.24】维度默认**扶梯运行底噪**的可闻范围（/futiround 设置，单位格）。
     * 默认 {@link #DEFAULT_ROUND} = 16。与 {@link #defaultHelpRound}（提示音）是两套互不影响的数据。
     */
    public int defaultRound = DEFAULT_ROUND;

    /**
     * 【1.24】扶梯方块（x,y,z）→ 这条扶梯运行底噪的可闻范围（单位格）。
     * 只记录与 {@link #defaultRound} **不同**的项。1.24 **没有石斧界面控件**，所以正常情况下这里是空的，
     * 只有 {@code /futiround -f <X> to <Y>} 在「先把个别扶梯改成别的值」时才会用到（留给以后加 UI）。
     */
    public final Map<BlockPos, Integer> blockRound = new HashMap<>();

    /**
     * 【1.24】维度默认**无障碍提示音**的可闻范围（/futihelpround 设置，单位格）。
     * 默认 {@link #DEFAULT_HELP_ROUND} = 4。与 {@link #defaultRound}（底噪）是两套互不影响的数据。
     */
    public int defaultHelpRound = DEFAULT_HELP_ROUND;

    /**
     * 【1.24】扶梯方块（x,y,z）→ 这条扶梯无障碍提示音的可闻范围（单位格）。
     * 只记录与 {@link #defaultHelpRound} **不同**的项；同样没有界面控件。
     */
    public final Map<BlockPos, Integer> blockHelpRound = new HashMap<>();

    /**
     * 【1.31】维度默认**进入扶梯（上客端）**提示音的速率（/futihelpspeed in 设置，单位 Hz）。
     * 默认 {@link #DEFAULT_HELP_SPEED_IN} = 10（1/10 秒一响）。
     * 与 {@link #defaultHelpSpeedOut}（落客端）是两套互不影响的数据。
     */
    public int defaultHelpSpeedIn = DEFAULT_HELP_SPEED_IN;

    /**
     * 【1.31】扶梯方块（x,y,z）→ 这条扶梯**上客端**提示音的速率（Hz）。
     * 只记录与 {@link #defaultHelpSpeedIn} **不同**的项。没有石斧界面控件，正常情况下这里是空的，
     * 只有 `-f <X> to <Y>` 在「先把个别扶梯改成别的值」时才会用到（留给以后加 UI）。
     */
    public final Map<BlockPos, Integer> blockHelpSpeedIn = new HashMap<>();

    /**
     * 【1.31】维度默认**离开扶梯（落客端）**提示音的速率（/futihelpspeed out 设置，单位 Hz）。
     * 默认 {@link #DEFAULT_HELP_SPEED_OUT} = 1（1 秒一响）。
     */
    public int defaultHelpSpeedOut = DEFAULT_HELP_SPEED_OUT;

    /**
     * 【1.31】扶梯方块（x,y,z）→ 这条扶梯**落客端**提示音的速率（Hz）。
     * 只记录与 {@link #defaultHelpSpeedOut} **不同**的项。
     */
    public final Map<BlockPos, Integer> blockHelpSpeedOut = new HashMap<>();

    /**
     * 【1.41】维度默认**进入扶梯（上客端）**的无障碍提示音「音乐」（{@code /futihelpmusic in} 设置）：
     * 没有单独设置过的扶梯用它。
     *
     * <p>取值只有三种：
     * <ul>
     *   <li>{@link #HELP_AUDIO_DEFAULT}（初始值）= 模组原来的提示音；</li>
     *   <li>{@link #HELP_AUDIO_OFF} = 这一头不播提示音；</li>
     *   <li>玩家导入的音频文件名（如 {@code example.ogg}）= 在这一头循环播放这段音频。</li>
     * </ul>
     *
     * <p>音频字节**不重复存**：用的就是 {@link #audioLibrary}（【1.28】起提示音分类
     * 读自己的子文件夹 {@code MBM_Audio/futi/help}，与运行底噪 {@code futi/music} 分开存放，
     * 但字节库是一份），这里只记「选了哪一个」。
     * 与 {@link #defaultAudio}（运行底噪）是两套互不影响的数据。
     *
     * <p>★【1.41】起「进入扶梯」与「离开扶梯」是**两套独立数据**（形状同
     * {@code /futihelpspeed in|out}），所以进、出两头可以各放各的声音。
     * 1.39 那个单一的 {@code defaultHelpAudio} 字段在读取时**同时**喂给两头
     * （见 {@link #fromTag}），保证旧存档听起来**逐字节不变**。
     */
    public String defaultHelpAudioIn = HELP_AUDIO_DEFAULT;

    /**
     * 【1.41】扶梯方块（x,y,z）→ 这条扶梯**进入扶梯（上客端）**的提示音「音乐」ID（单独设置层）。
     * 只记录与 {@link #defaultHelpAudioIn} **不同的**项；旧存档缺这一段也能正常读（= 跟随默认）。
     */
    public final Map<BlockPos, String> blockHelpAudioIn = new HashMap<>();

    /**
     * 【1.41】维度默认**离开扶梯（落客端）**的无障碍提示音「音乐」（{@code /futihelpmusic out} 设置）。
     * 取值与 {@link #defaultHelpAudioIn} 相同，但**互不影响**。
     */
    public String defaultHelpAudioOut = HELP_AUDIO_DEFAULT;

    /**
     * 【1.41】扶梯方块（x,y,z）→ 这条扶梯**离开扶梯（落客端）**的提示音「音乐」ID（单独设置层）。
     * 只记录与 {@link #defaultHelpAudioOut} **不同的**项。
     */
    public final Map<BlockPos, String> blockHelpAudioOut = new HashMap<>();

    /**
     * 【1.42】直梯开关门提示音开关（{@code /lifthelp} 设置）。
     *
     * <p>{@code true} = 直梯关门连播 {@link #LIFT_HELP_CLOSE_REPEATS} 次、开门连播
     * {@link #LIFT_HELP_OPEN_REPEATS} 次 liftmusic.ogg（**【1.52】这两个次数只对内置素材成立**：
     * 换成玩家导入的 ogg 就只播一次）；{@code false} = 完全不播。
     * 旧存档没有这个字段 → 读到默认 {@code true}（功能默认开）。
     */
    public boolean defaultLiftHelp = true;

    /**
     * 【1.42】直梯开关门提示音的**倍速**（允许小数）。
     * 取值被 {@link #clampLiftHelpSpeed} 夹到
     * [{@link #LIFT_HELP_SPEED_MIN}, {@link #LIFT_HELP_SPEED_MAX}]。旧存档缺字段 → 1.0（原速）。
     *
     * <p>★【1.15】改这个值的指令 {@code /lifthelpspeed} 已按用户要求**删除**（石斧界面本来
     * 也没有入口）⇒ 新存档恒为 1.0。字段与播放端的 pitch 逻辑都保留：一是旧存档里可能
     * 存着 1.0 以外的值（读回来照样按它播），二是 {@code clampLiftHelpSpeed} 还要给
     * 同步包与显示用。
     */
    public float defaultLiftHelpSpeed = DEFAULT_LIFT_HELP_SPEED;

    /**
     * 【1.43】直梯开关门提示音的**音量**（{@code /lifthelploud} 设置）。
     * 取值被 {@link #clampLiftHelpVolume} 夹到 [{@link #HELP_VOLUME_MIN}, {@link #HELP_VOLUME_MAX}]
     * （1~1000，100 = 原始音量、1000 = 10× 放大）。旧存档缺字段 → {@link #DEFAULT_LIFT_HELP_VOLUME}
     * （= 100），也就是「和 1.42 一样响」。
     *
     * <p>和开关 / 倍速一样**只有维度默认一层**（没有按直梯索引的表 —— 直梯是按「离玩家最近的
     * 那一条」现算的，没有方块粒度可言）。
     */
    public int defaultLiftHelpVolume = DEFAULT_LIFT_HELP_VOLUME;

    /**
     * 【1.48】三项提示音**各自的音量**（维度默认；{@link #defaultLiftHelpVolume} 是「共用默认」，
     * 这三个是「某项单独调过」之后的覆盖值）。
     *
     * <ul>
     *   <li>{@code up} = 上楼提示音（准备向上移动那声）；</li>
     *   <li>{@code down} = 下楼提示音（准备向下移动那声）；</li>
     *   <li>{@code open} = 开门提示音（【1.28】从原来的 {@code chime}（开关门）拆成开门 / 关门两路；
     *       **内置素材**开门 2 次连播；【1.52】导入的 ogg 只播一次）；</li>
     *   <li>{@code close} = 关门提示音（**内置素材**关门 4 次连播；【1.52】导入的 ogg 只播一次）。</li>
     * </ul>
     * 取值：**{@link #LIFT_TONE_VOLUME_UNSET}（-1）= 该项没单独调过，跟随 {@link #defaultLiftHelpVolume}**；
     * 否则 = 该项自己的音量（1~1000，100 = 原始音量、1000 = 10×）。旧存档没有这四个字段 → -1
     * （全部跟随共用默认，与 1.47 及以前行为一致；旧 {@code chime} 字段迁移到 open 与 close 两头）。
     */
    public int defaultLiftToneVolumeUp = LIFT_TONE_VOLUME_UNSET;
    public int defaultLiftToneVolumeDown = LIFT_TONE_VOLUME_UNSET;
    public int defaultLiftToneVolumeOpen = LIFT_TONE_VOLUME_UNSET;
    public int defaultLiftToneVolumeClose = LIFT_TONE_VOLUME_UNSET;

    /** 【1.48】「该项没单独调过」的哨兵（不是合法音量，合法区间是 1~1000）。 */
    public static final int LIFT_TONE_VOLUME_UNSET = -1;

    /**
     * 【1.46】三提示音的**独立子开关**（维度默认；{@link #defaultLiftHelp} 是总开关，这三个是「总开关
     * 开着的时候各自还能不能再关一层」）。
     *
     * <ul>
     *   <li>{@code up} = 上楼提示音（准备向上移动那声）；</li>
     *   <li>{@code down} = 下楼提示音（准备向下移动那声）；</li>
     *   <li>{@code open} = 开门提示音（【1.28】从原来的 {@code chime}（开关门）拆开，与关门互不干扰）；</li>
     *   <li>{@code close} = 关门提示音。</li>
     * </ul>
     * 四者独立，缺省全开（{@code true}）。旧存档没有这四个字段 → 读到 {@code true}，
     * 与 1.45 之前的行为完全一致（旧 {@code chime} 字段迁移到 open 与 close 两头）。
     */
    public boolean defaultLiftToneUpEnabled = true;
    public boolean defaultLiftToneDownEnabled = true;
    public boolean defaultLiftToneOpenEnabled = true;
    public boolean defaultLiftToneCloseEnabled = true;

    /**
     * 【1.15】三项提示音的**维度默认素材**（{@code /lifthelp up|down|door <名字>} 设置）。
     *
     * <ul>
     *   <li>{@code up} = 上楼提示音（准备向上移动那声）默认放哪一段；</li>
     *   <li>{@code down} = 下楼提示音（准备向下移动那声）；</li>
     *   <li>{@code open} = 开门提示音（【1.28】从原来的 {@code chime} 拆开；内置素材开门 2 次连播）；</li>
     *   <li>{@code close} = 关门提示音（内置素材关门 4 次连播）。</li>
     * </ul>
     *
     * <p><b>与 {@link #liftToneAudio}（按竖井列单独设置）的关系</b>：
     * 播放端先看这一条直梯（竖井列）有没有单独设置；没有、或者单独设置的那一项正好是
     * {@link #LIFT_TONE_DEFAULT}，就回落到这里的维度默认；维度默认再是 {@code default}
     * 才用模组内置素材。所以「{@code default}」在两层里都是「跟上一层」的意思，
     * 初始值也就是 {@code default} ⇒ 行为与 1.14 及以前完全一致（不设置就用内置素材）。
     *
     * <p>取值语义与 {@link #liftToneAudio} 相同：{@link #LIFT_TONE_DEFAULT} 内置素材 /
     * {@link #LIFT_TONE_OFF} 这一项不播 / 其它 = 音频库文件名。
     * 旧存档没有这四个字段 → {@code default}（旧 {@code chime} 字段迁移到 open 与 close 两头）。
     */
    public String defaultLiftToneAudioUp = LIFT_TONE_DEFAULT;
    public String defaultLiftToneAudioDown = LIFT_TONE_DEFAULT;
    public String defaultLiftToneAudioOpen = LIFT_TONE_DEFAULT;
    public String defaultLiftToneAudioClose = LIFT_TONE_DEFAULT;

    /**
     * 【1.47】直梯提示音（上楼 / 下楼 / 开关门，三项共用一份）的**淡入淡出范围**（格）。
     * 由 {@code /lifthelpround} 设置；首次载入模组默认 {@link #DEFAULT_LIFT_HELP_ROUND} = 4 格
     * （与扶梯无障碍提示音的默认射程一致 —— 点状音源贴块，不是 16 格那种整条环境音）。
     * 取值被 {@link #clampLiftHelpRound} 夹到 [{@link #LIFT_HELP_ROUND_MIN}, {@link #LIFT_HELP_ROUND_MAX}]
     * （1~128，复用扶梯那组范围常量）。旧存档缺字段 → 4（与「第一次载入」同行为）。
     */
    public int defaultLiftHelpRound = DEFAULT_LIFT_HELP_ROUND;

    /** 【1.47】直梯提示音淡入淡出范围下限。 */
    public static final int LIFT_HELP_ROUND_MIN = 1;
    /** 【1.47】直梯提示音淡入淡出范围上限（与扶梯同一组上限：128）。 */
    public static final int LIFT_HELP_ROUND_MAX = 128;
    /** 【1.47】默认 4 格（用户点名「第一次载入模组，默认4格」）。 */
    public static final int DEFAULT_LIFT_HELP_ROUND = 4;

    /**
     * 【1.45】石斧右键直梯楼层轨道换的提示音：竖井列打包坐标 → {@link LiftToneAudio}。
     *
     * <p>「哪条直梯」没有稳定 ID（跨重启会变），所以用「楼层轨道所在竖井的那一列」当身份：
     * 一条直梯的所有楼层轨道共享同一个 (X, Z)，只有 Y 不同 ⇒ key = {@code BlockPos.asLong(x, 0, z)}
     * （Y 固定为 0）。石斧右键任意一层轨道都定位到同一个 key。
     *
     * <p>值：{@code up} = 准备向上移动（up.ogg）、{@code down} = 准备向下移动（down.ogg）、
     * {@code open} = 开门（liftmusic.ogg 连播 2 次）、{@code close} = 关门（liftmusic.ogg 连播 4 次）。
     * 四者互不冲突，可分别选。
     * 每个字段取值有三种语义：
     * <ul>
     *   <li>{@link #LIFT_TONE_DEFAULT} = **跟维度默认**（{@link #defaultLiftToneAudioUp} 等，
     *       维度默认再是 default 时就是模组内置素材）；</li>
     *   <li>{@link #LIFT_TONE_OFF} = 这一条不播提示音；</li>
     *   <li>其它 = 音频库里的文件名（从 {@code MBM_Audio} 导入过的那份）。</li>
     * </ul>
     * 旧存档没有这张表 → 空表（全部走维度默认 = 内置素材，与旧行为一致）。
     */
    public final Map<Long, LiftToneAudio> liftToneAudio = new HashMap<>();

    /** 【1.45】「默认素材」的哨兵值。 */
    public static final String LIFT_TONE_DEFAULT = "default";
    /** 【1.45】「这条直梯不播」的哨兵值。 */
    public static final String LIFT_TONE_OFF = "off";
    /** 【1.45】「设置不存在」的哨兵值（与默认素材同义，旧存档读到它 = 默认）。 */
    public static final String LIFT_TONE_MISSING = "";

    /**
     * 【1.15】四项**全是** {@link #LIFT_TONE_DEFAULT}（都跟维度默认）吗？
     * 单独设置那张表用它决定「这条记录是不是等于没设置，可以直接删掉」。
     * 【1.28】「项」从三项（up/down/chime）拆成四项（up/down/open/close）。
     */
    public static boolean isLiftToneAllDefault(String up, String down, String open, String close) {
        return LIFT_TONE_DEFAULT.equals(up) && LIFT_TONE_DEFAULT.equals(down)
                && LIFT_TONE_DEFAULT.equals(open) && LIFT_TONE_DEFAULT.equals(close);
    }

    // ==================================================================
    // 【1.50】列车屏蔽门（MTR Platform Screen Door / APG）开关门提示音
    //
    // 与直梯那套（defaultLiftHelp / liftToneAudio）**形状完全对称**，只是「项」从
    // 三项（up/down/chime）变成两项（open/close），而且直梯提示音没有方块粒度、
    // 屏蔽门有（右键某一扇门可以对它单独换素材）。
    //
    // ★ 为什么「范围」的默认值是 16 而不是直梯的 4：直梯提示音贴在轿厢/楼层那一格上，
    //   玩家站在梯门口听，4 格够；屏蔽门是一条**长长的站台**，一列车到站时整排门同时开，
    //   玩家常常站在离最近那扇门十几格的地方，4 格会变成「站在站台上什么都听不见」。
    // ==================================================================

    /**
     * 【1.50】屏蔽门提示音**总开关**（{@code /pbmmusic on|off} 或石斧界面）。
     * {@code false} = 开门/关门一律不播。旧存档没有这个字段 → {@code true}（功能默认开）。
     */
    public boolean defaultPsdHelp = true;

    /**
     * 【1.50】屏蔽门「**开门**提示音」的独立子开关（{@code /pbmmusic open on|off}）。
     * 总开关开着时，这一项还能再单独关一层。旧存档缺字段 → {@code true}。
     */
    public boolean defaultPsdToneOpenEnabled = true;

    /** 【1.50】屏蔽门「**关门**提示音」的独立子开关（{@code /pbmmusic close on|off}）。 */
    public boolean defaultPsdToneCloseEnabled = true;

    /**
     * 【1.50】屏蔽门提示音的**共用默认音量**（{@code /pbmloud}，1~1000，100 = 原始音量、
     * 1000 = 10× 放大）。两项没单独调过时都用它。旧存档缺字段 → 100。
     */
    public int defaultPsdHelpVolume = DEFAULT_PSD_HELP_VOLUME;

    /**
     * 【1.50】屏蔽门两项提示音**各自的音量**（{@code /pbmloud open|close <音量>}）。
     * {@link #PSD_TONE_VOLUME_UNSET}（-1）= 该项没单独调过，跟随 {@link #defaultPsdHelpVolume}。
     */
    public int defaultPsdToneVolumeOpen = PSD_TONE_VOLUME_UNSET;
    public int defaultPsdToneVolumeClose = PSD_TONE_VOLUME_UNSET;
    /**
     * 【1.22】到站播报（{@code /pbmmidium}）**自己那一项**的音量。
     * {@link #PSD_TONE_VOLUME_UNSET}（-1）= 没单独调过，跟随这一扇门的共用音量
     * （{@link #defaultPsdHelpVolume}），也就是 1.21 及以前的行为。
     */
    public int defaultPsdMidiumVolume = PSD_TONE_VOLUME_UNSET;
    /**
     * 【1.22】进站报站（{@code /pbmarrive}）**自己那一项**的音量。
     * 语义同 {@link #defaultPsdMidiumVolume}。
     */
    public int defaultPsdArriveVolume = PSD_TONE_VOLUME_UNSET;

    /**
     * 【09-29】屏蔽门提示音的**可闻范围**（{@code /pbmround}，单位格）——**双维**：
     * Xz = 水平（x、z 轴）半径，Y = 垂直（y 轴）半径；任一方向超出即听不见
     * （判据与 {@code /jsr round} 的讲述人报站同一套，见 TrainAnnounceSwitch）。
     * 默认水平 {@link #DEFAULT_PSD_HELP_ROUND_XZ} = 16、垂直 {@link #DEFAULT_PSD_HELP_ROUND_Y} = 5。
     */
    public int defaultPsdHelpRoundXz = DEFAULT_PSD_HELP_ROUND_XZ;
    public int defaultPsdHelpRoundY = DEFAULT_PSD_HELP_ROUND_Y;

    /**
     * 【09-29】「**到站播报**」（{@code /pbmmidium}）自己的可闻范围（格，{@code /pbmmidiumround}）
     * ——**双维**，语义与 {@link #defaultPsdHelpRoundXz}/{@link #defaultPsdHelpRoundY} 同。
     *
     * <p>★ 为什么要跟提示音**分开一份**：三类声音的「该听多远」根本不是一件事 ——
     * 开关门提示音是**机械事件**，站在门口听最合理（默认 16 格）；
     * 而站台广播是**说给整个站台的人听的**，玩家希望它传得远一些。用户在 1.22 那一轮
     * 给三类声音各自加了音量，1.23 把**范围**也拆开，本轮再把范围拆成水平/垂直两维，
     * 理由完全一样。
     */
    public int defaultPsdMidiumRoundXz = DEFAULT_PSD_MIDIUM_ROUND_XZ;
    public int defaultPsdMidiumRoundY = DEFAULT_PSD_MIDIUM_ROUND_Y;

    /**
     * 【09-29】「**进站报站**」（{@code /pbmarrive}）自己的可闻范围（格，{@code /pbmarriveround}）。
     * 语义与 {@link #defaultPsdMidiumRoundXz}/{@link #defaultPsdMidiumRoundY} 同。
     */
    public int defaultPsdArriveRoundXz = DEFAULT_PSD_ARRIVE_ROUND_XZ;
    public int defaultPsdArriveRoundY = DEFAULT_PSD_ARRIVE_ROUND_Y;

    /**
     * 【1.16】关门提示音的**强制等待时长**（秒，{@code /pbmclosewait}，0~60）。
     *
     * <p><b>它只在一种情形下生效</b>：这一轮的停站时长**不够放完整条关门素材**时 ——
     * 这时不再「这一轮干脆没有人声」，而是「**开门音效播完之后再等这么多秒**，
     * 然后把语音播报放出来」；门一进入关门行程（嘀嘀开始响）就**立刻掐断**这段人声，
     * 不管它播到了哪里。停站**够长**时这个值被完全忽略（走原来那套「整段提前播、结尾落在门上」）。
     *
     * <p>默认 {@link #DEFAULT_PSD_CLOSE_WAIT_SECONDS} = 5（用户点名：「第一次加入 mod 时默认为 5 秒」）。
     * 旧存档没有这个字段 → 保持字段初始值，也就是**同样读回 5 秒**（本项不跟随「旧档=旧行为」那条惯例，
     * 因为用户要的就是「装上就有 5 秒」）。
     */
    public int defaultPsdCloseWaitSeconds = DEFAULT_PSD_CLOSE_WAIT_SECONDS;

    /**
     * 【1.17】本维度「**到站播报**」的素材 id（{@code /pbmmidium <名字> <秒>}）。
     *
     * <p>语义：列车到站、屏蔽门**开门音（嘀嘀嘀）播完之后**再等
     * {@link #defaultPsdMidiumWaitSeconds} 秒，播这一条语音 —— 就是真实地铁站台上那种
     * 「到达某某站，请……」的报站广播。
     *
     * <p>★ 与关门提示音那条兜底人声的**唯一本质差别**：**它永远不会被掐断**。
     * 关门那一段人声「门一动就掐」（用户点名），而这一条用户点名
     * 「**即使列车出站也要继续播放，直到播完**」⇒ 播放端不许给它任何停止条件。
     *
     * <p>{@link #PSD_MIDIUM_OFF} = 不播（默认）；其它值 = 音频库里的文件名。
     */
    public String defaultPsdMidiumAudio = PSD_MIDIUM_OFF;

    /**
     * 【1.17】到站播报的**等待秒数**：从「开门音（嘀嘀嘀）播完」那一刻起再等这么多秒。
     *
     * <p>取值 <b>[0, +∞)</b>（用户点名：「允许输入 0 到正无穷的数字」）⇒ 只有下界，
     * 见 {@link #clampPsdMidiumWaitSeconds}。{@code 0} = 开门音一播完就播报。
     *
     * <p>默认 {@link #DEFAULT_PSD_MIDIUM_WAIT_SECONDS} = 0（「开门嘀嘀嘀之后开始播放」）。
     */
    public int defaultPsdMidiumWaitSeconds = DEFAULT_PSD_MIDIUM_WAIT_SECONDS;

    /**
     * 【1.21】本维度「**进站报站**」的素材 id（{@code /pbmarrive <名字> <X>}）。
     *
     * <p>语义：**读 MTR 时刻表**，这个站台「**最近的一班列车**还剩 |X| 秒到站」时播这一条语音
     * （X ∈ (-∞, 0]，例如 X=-10 ⇒ 剩 10 秒到站时起播、X=0 ⇒ 到站那一刻起播）。
     * ★ 只与**到站剩余时间**有关，与门什么时候开、停站多长**无关**。
     * 与 {@link #defaultPsdMidiumAudio} 一样，「即使列车进站也要继续播放，直到播完」
     * ⇒ 播放端**不许**给它任何停止条件。
     *
     * <p>{@link #PSD_ARRIVE_OFF} = 不播（默认）；其它值 = 音频库里的文件名。
     */
    public String defaultPsdArriveAudio = PSD_ARRIVE_OFF;

    /**
     * 【1.21】进站报站的**秒数**：X ∈ (-∞, 0]，含义 = 「最近的一班列车**还剩 |X| 秒到站**时起播」。
     *
     * <p>取值只有**上界 0**（不能为正 = 不能晚于列车到站那一刻），没有下界 ——
     * 见 {@link #clampPsdArriveSeconds}。{@code 0} = 列车到站那一刻起播。
     */
    public int defaultPsdArriveSeconds = DEFAULT_PSD_ARRIVE_SECONDS;

    /**
     * 【09-28】**维度默认**的「进站广播（讲述人）」**样式**（{@link #PSD_NARRATE_OFF} /
     * {@link #PSD_NARRATE_SHANGHAI} / {@link #PSD_NARRATE_HONGKONG}）。
     *
     * <p>与 {@link #defaultPsdArriveSeconds} 那一族同构：某串门单独设过就用它自己的
     * （{@code PsdToneAudio.narrate()} 非 null），没设过才回落到这一个。
     *
     * <p>★ 它在石斧界面上**没有对应的行**（讲述人二级页只有「关闭 / 开启(上海) / 开启(香港)」
     * 三行，用户点名）⇒ 这个维度默认只有「同步所有」弹窗会写它，与
     * {@link #defaultPsdArriveSeconds} 完全同一条路数。
     * ★ 旧存档存的是布尔 {@code defaultPsdNarrateOn} ⇒ 读不到新键就落回默认值
     * {@link #DEFAULT_PSD_NARRATE_MODE} = 开启(上海)，与升级前的可见行为**一致**，不需要迁移。
     */
    public int defaultPsdNarrateMode = DEFAULT_PSD_NARRATE_MODE;

    /**
     * 【09-28】【10-01 续】**维度默认**的「进站广播（讲述人）」秒数：X ∈ (-∞, 0]，
     * 含义与 {@link #defaultPsdArriveSeconds} 逐字相同（最近一班车还剩 |X| 秒到站时开念）。
     * 默认 {@link #DEFAULT_PSD_NARRATE_LEAD_SECONDS} = **-20**（用户点名「默认-20秒」）。
     *
     * <p>★ 与进站报站的那一个**互相独立**：用户点名「取消借用进站广播」。
     */
    public int defaultPsdNarrateSeconds = DEFAULT_PSD_NARRATE_LEAD_SECONDS;

    /**
     * 【09-30 续 3】**维度默认**的「站台广播（讲述人）」**样式**。
     *
     * <p>★【09-30 续 4】站台讲述人**没有**香港 / 上海预设（用户点名删掉 —— 那两句是
     * 进站报站的句式），样式只有「关闭」与 userN ⇒ 维度默认 = **关闭**。
     * 想用就去石斧「站台广播(讲述人)」二级页选一条自定义词（userN）。
     */
    public int defaultPsdMidiumNarrateMode = PSD_NARRATE_OFF;

    /**
     * 【09-30 续 3】**维度默认**的「站台广播（讲述人）」秒数 ∈ [0, +∞)：
     * 含义与 {@link #defaultPsdMidiumWaitSeconds} 逐字相同 —— **开门音播完之后再等 Y 秒**开念
     * （注意与进站讲述人的 (-∞, 0]「到站前提前」方向**相反**：站台广播是开门**之后**的事）。
     * 默认 0 = 开门音一播完就念。
     */
    public int defaultPsdMidiumNarrateSeconds = DEFAULT_PSD_NARRATE_SECONDS;

    /**
     * 【10-01】进站广播（讲述人）的**玩家自定义文字**（user1…userN 的模板原文，按顺序）。
     * **按存档保存** —— 跟着本类写进存档 NBT（与导入的 OGG 同一条约定：存在存档里、
     * 存档挪到别处也能找到；用户点名「不要存在 config 里、不要存档之间互通」）。
     *
     * <p>★ 与站台广播（讲述人）的 {@link #defaultPsdMidiumNarrateUserTexts} **两套分开**
     * （用户点名「不要 2 个共用一个文字列表」）—— 每条广播念自己的那份。
     *
     * <p>★ 条数上限 {@link #MAX_PSD_NARRATE_USER_STYLES}（与档位号空间一致）。
     */
    public List<String> defaultPsdArriveNarrateUserTexts = new ArrayList<>();

    /** 【10-01】站台广播（讲述人）的玩家自定义文字 —— 与 {@link #defaultPsdArriveNarrateUserTexts}
     * 同一条按存档约定，但**独立一份**（站台讲述人念它，进站讲述人不念）。 */
    public List<String> defaultPsdMidiumNarrateUserTexts = new ArrayList<>();

    /**
     * 【1.15】屏蔽门的**维度默认素材**（开门端 / 关门端各一份）：
     * {@code /pbmmusic open|close <名字>} 改的就是它，石斧界面里每扇门的「默认」行也指向它。
     *
     * <p>取值语义（与直梯那套同构，三层）：
     * <ul>
     *   <li>{@link #PSD_TONE_DEFAULT}（指令里写 {@code default}）= **跟内置**：
     *       开门端 → {@code dooropen.ogg}、关门端 → {@code mdoorclose.ogg}；</li>
     *   <li>{@link #PSD_TONE_OFF} = 这个维度不播（与子开关是「与」关系）；</li>
     *   <li>{@link #PSD_TONE_BUILTIN_CLOSE}（{@code default-c}）/
     *       {@link #PSD_TONE_BUILTIN_CLOSE_M}（{@code default-m}）=
     *       **显式**指定某一段内置素材（与端别无关）；</li>
     *   <li>{@link #PSD_TONE_BUILTIN_CLOSE_S}（{@code default-s}，界面上的「默认（短）」）=
     *       按端别的默认素材，但**不播语音播报段**（关门端听起来就是纯粹一串嘀嘀）；</li>
     *   <li>其它 = 音频库文件名（从 {@code MBM_Audio} 导入过的那份）。</li>
     * </ul>
     * ★ 旧存档没有这两个字段 → 读回 {@link #PSD_TONE_DEFAULT}，行为**等于** 1.50 的
     *   「两项都走内置素材」，即旧档不需要任何迁移。
     */
    public String defaultPsdToneAudioOpen = PSD_TONE_DEFAULT;
    public String defaultPsdToneAudioClose = PSD_TONE_DEFAULT;

    /**
     * 【09-30】闸机（MTR 的 Ticket Barrier）的**维度默认素材**：进站 / 出站各一份。
     *
     * <p>{@code /zhaji in|out <名字>} 改的就是它；石斧右键任意一台闸机打开的界面里配的也是它。
     * 取值语义与前两套（直梯 / 屏蔽门）完全一致：
     * <ul>
     *   <li>{@link #ZHAJI_TONE_DEFAULT}（指令里写 {@code default}）= **跟 MTR 内置**
     *       （{@code mtr:ticket_barrier} / {@code mtr:ticket_barrier_concessionary}）；</li>
     *   <li>{@link #ZHAJI_TONE_OFF}（指令里写 {@code off}）= 这一侧不播；</li>
     *   <li>其它 = 音频库文件名（从 {@code MBM_Audio/zhaji/in} 或 {@code .../out} 导入过的那份）。</li>
     * </ul>
     *
     * <p>★★ 粒度（【09-30 续】改过一版，别按旧注释理解）：
     * <ul>
     *   <li><b>维度默认层</b>（= 本字段）= 整个维度这一侧。**只有指令**改它
     *       （{@code /zhaji in|out <名字>} / {@code /zhajiloud}）；
     *       石斧界面右上角「同步所有」的语义是「把这一组的值**写进这一层**」= 同步给所有闸机。</li>
     *   <li><b>逐组层</b>（{@link #zhajiTone}）= 石斧右键到的那**一组**闸机
     *       （连着的、同一功能的那些闸机串）。界面里改的每一项都落这一层；
     *       这一层**没设过**的项回落到上面那一层。</li>
     * </ul>
     * 与屏蔽门【1.20】那次改版同一个理由、同一套形状 —— 用户点名
     * 「石斧右键的（要）全部都是右键的那一组，而不是修改全部，只有指令才是修改全部」。
     *
     * <p>旧存档没有这两个字段 → 读回 {@link #ZHAJI_TONE_DEFAULT}
     * （= 与「模组压根没有这个功能」的行为完全一致，旧档不需要任何迁移）。
     */
    public String defaultZhajiToneAudioIn = ZHAJI_TONE_DEFAULT;
    public String defaultZhajiToneAudioOut = ZHAJI_TONE_DEFAULT;

    /**
     * 【09-30】闸机提示音的音量（进站 / 出站各一份，1~1000，100 = 原始音量、1000 = 10× 放大）。
     *
     * <p>由 {@code /zhajiloud in|out <音量>} 或石斧界面一级菜单那两个输入框设置；
     * 落值与直梯 / 屏蔽门共用 {@link #clampLiftToneVolume}（同一量程、同一夹取）。
     * 旧存档缺字段 → {@link #DEFAULT_ZHAJI_VOLUME}（100）。
     */
    public int defaultZhajiToneVolumeIn = DEFAULT_ZHAJI_VOLUME;
    public int defaultZhajiToneVolumeOut = DEFAULT_ZHAJI_VOLUME;

    /**
     * 【09-30 续】石斧右键**那一组闸机**的提示音设置：组锚点 → {@link ZhajiTone}。
     *
     * <p><b>「一组闸机」是什么</b>：相邻的闸机方块（6 邻域连通）算**一段**；
     * 在**同一段**里，功能相同的那些（进站 / 出站）算**一组**。所以一段里最多两组：
     * 「这一段的进站闸机」「这一段的出站闸机」。用户点名
     * 「连着的相同功能（进站 / 出站）闸机为一组，可以单独调整一组闸机的音效和音量」。
     *
     * <p><b>锚点怎么算</b>（客户端 {@code ZhajiChain.anchorOf}）：从任意一格出发洪水填充整段，
     * 取段内 {@link BlockPos#asLong} 最小的那一格 —— 与从段内哪一格出发无关，
     * 所以「右键这一段的哪一台」都落到同一组。键就存 {@code asLong}（long，省一次字符串编码）。
     *
     * <p><b>两层怎么叠</b>：本表有这一项 → 用它；没有（或该项等于
     * {@link #ZHAJI_TONE_DEFAULT} / {@code null}）→ 回落 {@link #defaultZhajiToneAudioIn} 那一族。
     * 与屏蔽门的 {@link #psdToneAudio} 完全同一套语义（素材用 {@code DEFAULT} 表达「跟」、
     * 音量用装箱 {@code Integer} 的 {@code null} 表达「跟」——两种「跟」并存是有意的，见其注释）。
     *
     * <p>旧存档没有这张表 → 空表（全部走维度默认，与 09-30 之前逐字节一致）。
     */
    public final Map<Long, ZhajiTone> zhajiTone = new HashMap<>();

    /**
     * 【1.50】石斧右键某一扇屏蔽门换的提示音：**门的锚点坐标** → {@link PsdToneAudio}。
     *
     * <p><b>什么是「门的锚点」</b>：MTR 的一扇屏蔽门在客户端其实由**两个方块实体**共同表示
     * （左右各一个，{@code side=left|right}；MTR4 还有上半格，{@code half=upper}），
     * 而门值（{@code getDoorValue()} / {@code getOpen()}）由这两个实体**取同一个值**。
     * 所以「哪一扇门」不能直接用「读到门值的那一格」，否则同一扇门会算出两个 key、
     * 石斧在三格里的哪一格右键就只配得上一格。
     *
     * <p>锚点的算法见 {@code PsdDoorTracker.anchorOf}：先下移到下半格，再按
     * {@code side}/{@code facing} 找配对的那一格，取两者里 {@code BlockPos.asLong} 较小的一格。
     * 左右两侧算出来是**同一个坐标**，所以「哪一格右键都是同一扇门」。
     *
     * <p>取值语义与直梯那套一致：{@link #PSD_TONE_DEFAULT} 内置素材 /
     * {@link #PSD_TONE_OFF} 这扇门不播 / 其它 = 音频库文件名。
     * 旧存档没有这张表 → 空表（全部走维度默认 + 内置素材）。
     */
    public final Map<Long, PsdToneAudio> psdToneAudio = new HashMap<>();

    /** 【1.50】屏蔽门提示音共用默认音量的默认值 = 100（原始音量）。 */
    public static final int DEFAULT_PSD_HELP_VOLUME = DEFAULT_HELP_VOLUME;
    /** 【1.50】屏蔽门「该项没单独调过」的哨兵（不是合法音量，合法区间 1~1000）。 */
    public static final int PSD_TONE_VOLUME_UNSET = LIFT_TONE_VOLUME_UNSET;
    /** 【1.50】屏蔽门提示音默认可闻范围（水平）= 16 格（站台尺度；理由见上面那段注释）。 */
    public static final int DEFAULT_PSD_HELP_ROUND_XZ = 16;
    /** 【09-29】屏蔽门提示音默认可闻范围（垂直）= 5 格（与 {@code /jsr round} 的 y 默认同值）。 */
    public static final int DEFAULT_PSD_HELP_ROUND_Y = 5;
    /** 【1.50】屏蔽门提示音范围上下限（复用扶梯/直梯那一组：1~128）。 */
    public static final int PSD_HELP_ROUND_MIN = ROUND_MIN;
    public static final int PSD_HELP_ROUND_MAX = ROUND_MAX;

    /**
     * 【1.23】到站播报 / 进站报站各自的默认可闻范围 = 16 格。
     *
     * <p>与 {@link #DEFAULT_PSD_HELP_ROUND_XZ} **同值**是刻意的：老存档里没有这两个字段 ⇒
     * 读回字段初始值 16 ⇒ 与 1.22 及以前「三类声音共用提示音那一个范围」的行为逐位相同。
     * ★ 上下限**复用同一组常量**（1~128），不另开一套 —— 三处输入框 / 指令参数校验要是各写一份，
     * 迟早出现「界面上限跟指令上限不是一个数」这种静默不一致。
     */
    public static final int DEFAULT_PSD_MIDIUM_ROUND_XZ = DEFAULT_PSD_HELP_ROUND_XZ;
    public static final int DEFAULT_PSD_MIDIUM_ROUND_Y = DEFAULT_PSD_HELP_ROUND_Y;
    public static final int DEFAULT_PSD_ARRIVE_ROUND_XZ = DEFAULT_PSD_HELP_ROUND_XZ;
    public static final int DEFAULT_PSD_ARRIVE_ROUND_Y = DEFAULT_PSD_HELP_ROUND_Y;
    public static final int PSD_MIDIUM_ROUND_MIN = PSD_HELP_ROUND_MIN;
    public static final int PSD_MIDIUM_ROUND_MAX = PSD_HELP_ROUND_MAX;
    public static final int PSD_ARRIVE_ROUND_MIN = PSD_HELP_ROUND_MIN;
    public static final int PSD_ARRIVE_ROUND_MAX = PSD_HELP_ROUND_MAX;

    /**
     * 【1.16】关门提示音强制等待时长的默认值 = **5 秒**。
     *
     * <p>用户原话：「第一次加入 mod 时 pbmclosewait 默认为 5 秒」。所以它**不是**
     * 「旧档保持旧行为」那一类字段 —— 装上模组的第一刻就是 5 秒。
     */
    public static final int DEFAULT_PSD_CLOSE_WAIT_SECONDS = 5;

    /** 【1.23】开门提示音等待秒数默认 = 0（不等待）。 */
    public static final int DEFAULT_PSD_OPEN_WAIT_SECONDS = 0;
    /**
     * 【1.16】强制等待时长的上下限（秒）。
     *
     * <p>下界 0 = 「不许等」（人声紧跟在开门音之后），保留它是为了让用户能把这套兜底
     * 调成「几乎不播人声」而不是被迫至少等 1 秒；上界 60 = 一分钟，已经远超任何真实停站，
     * 再大只会让「等到了门都关了」这种无意义配置变得不容易被发现。
     */
    public static final int PSD_CLOSE_WAIT_MIN = 0;
    // ★【1.23】用户点名等待秒数范围 [0,+∞)：上限从 60 放宽到 999999（播放端按秒×20 tick 算，long 不溢出）。
    public static final int PSD_CLOSE_WAIT_MAX = 999999;
    public static final int PSD_OPEN_WAIT_MIN = 0;

    // ------------------------------------------------------------------
    // 【1.17】「到站播报」（/pbmmidium <名字> <秒>）
    //
    //   用户原话：「增加开门后的播报，类似于上海地铁到达站后开门之后站台播报的…
    //   这个播报，模组里是开门嘀嘀嘀之后开始播放。指令为 pbmmidium XXX Y
    //   （XXX 是 ogg 名称，Y 是到站后等待几秒开始播放这个音频），
    //   这个 pbmmidium 音频即使列车出站也要继续播放，直到播完」
    //
    //   ★ 与「关门提示音」那一套的**唯一本质差别**：它**永远不会被掐断**。
    //   关门那一段人声是「门一动就掐」，这一条是「车走了也照播到完」——
    //   所以它不能挂在 {@code forcedVoice} 那张表上（那张表的每一条都有停止条件），
    //   必须自己一张表、自己一条 tick 路（见 PsdChimePlayer#tickArrivalAnnounce）。
    // ------------------------------------------------------------------

    /** 【1.17】「到站播报」不播（默认值）。与 {@link #PSD_TONE_OFF} 同串，但语义独立。 */
    public static final String PSD_MIDIUM_OFF = LIFT_TONE_OFF;
    /** 【1.17】「到站播报」默认等待秒数 = 0（开门音一播完就播）。 */
    public static final int DEFAULT_PSD_MIDIUM_WAIT_SECONDS = 0;
    /**
     * 【1.17】到站播报等待秒数的**下界** = 0。
     *
     * <p>★ 上界**故意不设**：用户点名「pbmmidium 指令和 ui 允许输入 0 到正无穷的数字 [0,+∞)」。
     * 所以这里只有一个 {@code max(0, …)}，没有 {@code min}（对比
     * {@link #clampPsdCloseWaitSeconds} 是有上界的）。
     */
    public static final int PSD_MIDIUM_WAIT_MIN = 0;

    // ------------------------------------------------------------------
    // 【1.21】「进站报站」（/pbmarrive <名字> <X>）
    //
    //   用户原话：「增加列车进站报站功能，pbmarrive 指令，这个 pbmarrive 音频即使列车进站
    //   也要继续播放，直到播完」；随后**更正了触发口径**：
    //   「玩家设置的 -X 秒是**最近的一班列车到站的时间**：X=-10 就是最近列车剩余 10 秒到站时
    //   开始播放，这个音乐是通过**列车到站剩余时间**播放的，**而不是开门时间**」
    //   （我先前把 X 理解成「提前开门嘀嘀嘀几秒」，方向偏了 —— X 只与到站剩余时间有关。）
    //
    //   ★ 与「到站播报」的全对称：两者都是**永远不会被掐断**的独立语音，
    //   区别只在**触发时刻**（到站播报 = 开门音之后 + Y 秒；进站播报 = 时刻表说还剩 |X| 秒到站）。
    // ------------------------------------------------------------------

    /** 【1.21】「进站报站」不播（默认值）。与 {@link #PSD_MIDIUM_OFF} 同串，但语义独立。 */
    public static final String PSD_ARRIVE_OFF = LIFT_TONE_OFF;
    /** 【1.21】「进站报站」默认秒数 = 0（列车到站那一刻起播）。 */
    public static final int DEFAULT_PSD_ARRIVE_SECONDS = 0;
    /**
     * 【1.21】进站报站秒数的**上界** = 0。
     *
     * <p>★ 下界**故意不设**：用户点名输入范围是 {@code (-∞, 0]}（只夹上界 0，
     * 正值 = 「晚于到站那一刻」没有意义，折成 0）。对比 {@link #clampPsdMidiumWaitSeconds}
     * 是**只有下界**的 —— 两个 clamp 的不对称是有意的，别「顺手补齐」。
     */
    public static final int PSD_ARRIVE_SECONDS_MAX = 0;

    // ------------------------------------------------------------------
    // 【09-28】「进站广播（讲述人）」——**第三条**独立广播（在开/关门提示音、到站播报、进站报站之外）
    //
    //   ★ 用户点名：「取消借用进站广播，和进站广播一样独立设置时间 (-∞, 0]」。
    //   所以在数据层它与 {@link #DEFAULT_PSD_ARRIVE_SECONDS} 那一族**逐字对称**，
    //   只是各自一份值：改一个不会动另一个。
    //   ★ 但它**没有音量**：文字转语音（{@code com.mojang.text2speech.Narrator}）
    //   接口只有 {@code say(String, boolean)}，没有音量参数 —— 见 PsdChimePlayer 里那一段。
    //   ★【09-28 续】从「开 / 关」升级成**三种播报样式**（用户点名：原来的「开启」改名
    //   「开启(上海)」，并新增「开启(香港)」）：
    //       0 = 关闭（不念）
    //       1 = 开启(上海)：乘客们，列车马上就要进站了，本次列车终点站：X，请乘客们在Y站台有序候车
    //       2 = 开启(香港)：前往X的列车即将到达，请先让车上的乘客下车 ⏎ The train to Y is arriving…
    //   ⇒ 用**一个** int 存三档（互斥）：不会出现「关着但选了香港」这种自相矛盾的两字段状态。
    //   ★ 秒数与样式无关，仍是各存各的一份。
    // ------------------------------------------------------------------

    /** 【09-28】讲述人报站样式 = **关闭**（不念）。 */
    public static final int PSD_NARRATE_OFF = 0;
    /** 【09-28】讲述人报站样式 = **开启(上海)**：现有那一句「乘客们，列车马上就要进站了…」。 */
    public static final int PSD_NARRATE_SHANGHAI = 1;
    /** 【09-28】讲述人报站样式 = **开启(香港)**：中英双语「前往X的列车即将到达…」。 */
    public static final int PSD_NARRATE_HONGKONG = 2;
    /**
     * 【09-30】玩家**自定义报站词**的档位起点：mode 3 = user1、4 = user2……
     * （词的本体存在**客户端**配置 {@code config/smoothlift-jsr.properties}，
     * 存档里只记档位号 —— 同一串门对每个玩家念各自的词，正是「玩家自定义」的含义）。
     */
    public static final int PSD_NARRATE_USER_BASE = 3;
    /**
     * 【09-30】自定义档（userN）的条数上限 —— ui 一行一条、指令里 {@code /jsr on userN}
     * 的 N 也以它为界；同时挡住手改存档写进来的离谱档位号。
     */
    public static final int MAX_PSD_NARRATE_USER_STYLES = 64;
    /**
     * 【09-28】样式的合法上界（{@link #clampPsdNarrateMode} 用；加新样式时改这一个数）。
     * 【09-30】自定义档算进来：2 个内置 + {@link #MAX_PSD_NARRATE_USER_STYLES} 个 userN。
     */
    public static final int PSD_NARRATE_MODE_MAX =
            PSD_NARRATE_USER_BASE + MAX_PSD_NARRATE_USER_STYLES - 1;

    /** 【09-28】【10-01 续】讲述人报站**默认开着**、样式 = **香港**（用户点名「第一次加载模组：
     * 进站广播＝香港预设」；旧值上海，新存档与老布尔迁移都落到这里）。 */
    public static final int DEFAULT_PSD_NARRATE_MODE = PSD_NARRATE_HONGKONG;
    /** 【09-28】讲述人报站的默认秒数 = 0（与进站报站同款默认：列车到站那一刻念）。 */
    public static final int DEFAULT_PSD_NARRATE_SECONDS = 0;
    /** 【10-01】进站讲述人「提前量」**默认 = -20 秒**（用户点名：第一次加载模组就是
     * 「最近一班车还剩 20 秒到站时开念」）。
     * ★ 与 {@link #DEFAULT_PSD_NARRATE_SECONDS} **不是同一个**：那条还被「站台广播（讲述人）」
     * 的等待秒数共用（方向相反 [0,+∞)，改它会把站台讲述人也带成 -20）。 */
    public static final int DEFAULT_PSD_NARRATE_LEAD_SECONDS = -20;
    /**
     * 【09-28】讲述人报站秒数的**上界** = 0（与 {@link #PSD_ARRIVE_SECONDS_MAX} 同口径）。
     *
     * <p>范围 {@code (-∞, 0]}：只夹上界，下界故意不设 —— 与 {@link #clampPsdMidiumWaitSeconds}
     * 的「只有下界」成对，是**有意的**不对称。
     */
    public static final int PSD_NARRATE_SECONDS_MAX = 0;

    /** 【1.50】「用内置素材」的哨兵值（与直梯共用同一个字符串，语义一致）。 */
    public static final String PSD_TONE_DEFAULT = LIFT_TONE_DEFAULT;
    /** 【1.50】「这扇门不播」的哨兵值。 */
    public static final String PSD_TONE_OFF = LIFT_TONE_OFF;

    /**
     * 【09-30】闸机（进站 / 出站）提示音的哨兵值（与直梯 / 屏蔽门共用同一批字符串，语义一致）。
     * <p>★ 这里**只有**「内置」与「不播」两个哨兵，没有子开关字段 —— 闸机没有那层闸门
     * （用户点名的是「设置闸机声音」，不是「开关闸机声音」）。
     */
    public static final String ZHAJI_TONE_DEFAULT = LIFT_TONE_DEFAULT;
    /** 【09-30】「闸机这一侧不播」的哨兵值。 */
    public static final String ZHAJI_TONE_OFF = LIFT_TONE_OFF;
    /** 【09-30】闸机提示音音量缺省 = 100（原始音量）；量程与直梯 / 屏蔽门共用 1~1000。 */
    public static final int DEFAULT_ZHAJI_VOLUME = DEFAULT_HELP_VOLUME;

    // ------------------------------------------------------------------
    // 【1.15】四段**内置**素材的名字（用户在指令里写的那个词）
    //
    //   dooropen.ogg    → default     （开门端的「跟内置」就是它）
    //   mdoorclose.ogg  → default-m   （**关门端的「跟内置」就是它**；也可以显式写在任何一端）
    //   doorclose.ogg   → default-c   （另一段关门素材，只能显式写）
    //   mdoorclose.ogg  → default-s   （「默认（短）」：同一段关门素材，但**不播语音播报段**）
    //
    // ★ 为什么 {@code default} 同时也是「跟上一层」的哨兵：
    //   「跟上一层」在两层上都成立 —— 每扇门写 default = 跟维度默认；维度默认再是 default
    //   = 跟内置。于是最末端的「跟内置」自然落在**端别**上（开门 dooropen / 关门 mdoorclose），
    //   这也正是用户要的「默认音效 = dooropen.ogg（开）/ mdoorclose.ogg（关）」。
    //   代价：「在某一扇门上强制用内置、忽略维度默认」这句话表达不出来（与直梯同一取舍）。
    // ------------------------------------------------------------------

    /** 【1.15】内置**开门**素材在指令里的名字（= {@code dooropen.ogg}）。 */
    public static final String PSD_TONE_BUILTIN_OPEN = PSD_TONE_DEFAULT;
    /** 【1.15】内置**关门**素材在指令里的名字（= {@code doorclose.ogg}）。 */
    public static final String PSD_TONE_BUILTIN_CLOSE = "default-c";
    /**
     * 【1.15】内置**关门备选**素材在指令里的名字（= {@code mdoorclose.ogg}）。
     *
     * <p>★ 它**同时是关门端的默认音效**（{@link #PSD_TONE_BUILTIN_OPEN} 落到关门端时就是这一段）——
     * 用户点名「屏蔽门默认音效改为 mdoorclose.ogg（default-m）」。所以关门端有两个名字指向同一段：
     * {@code default}（跟上一层）与 {@code default-m}（显式）。
     */
    public static final String PSD_TONE_BUILTIN_CLOSE_M = "default-m";

    /**
     * 【1.15】「默认（短）」在指令里的名字 —— **跟内置，但不播开头的语音播报段**。
     *
     * <p>背景：关门素材是一条真实录音 `[语音播报 ~6.7s][静音][嘀嘀 ~3.5s]`，而门只走 4 秒。
     * 【1.15】的做法是**整段锚在「关门」那一瞬**（听到的顺序 = 关门人声 → 关门 → 关门嘀嘀）；
     * 但总有玩家想要**纯嘀嘀**的短版，于是给出这个显式选项：同一段 {@code mdoorclose.ogg}，
     * 只是**关门时不放那段语音播报** ⇒ 听起来就是纯粹的一串嘀嘀（对齐门关上那一刻）。
     *
     * <p>实现上它**不是**另一段音频：素材仍是 {@code mdoorclose.ogg}（见 {@link #psdBuiltinKey}），
     * 差别只在「关门时要不要放素材开头那段播报」这一条播放策略上（见 {@link #isPsdBuiltinShort}）。
     *
     * <p>端别行为：**按端别取该端的默认素材**（开门 → {@code dooropen.ogg}、关门 → {@code mdoorclose.ogg}）。
     * 开门端本来就没有播报段 ⇒ 在开门端它与 {@code default} 等价、是个无副作用的别名；
     * 这样设计是为了**避免**「在开门端写 default-s 却把开门声换成关门素材」这种惊吓
     * （{@code default-c} / {@code default-m} 是显式素材名、与端别无关，那是它们该有的语义）。
     */
    public static final String PSD_TONE_BUILTIN_CLOSE_S = "default-s";

    /**
     * 【1.15】这个 id 是不是四段内置素材的名字之一（{@code default} / {@code default-c} /
     * {@code default-m} / {@code default-s}）—— 是内置名的**一定不是**音频库文件名。
     */
    public static boolean isPsdBuiltinName(String id) {
        return PSD_TONE_BUILTIN_OPEN.equals(id)
                || PSD_TONE_BUILTIN_CLOSE.equals(id)
                || PSD_TONE_BUILTIN_CLOSE_M.equals(id)
                || PSD_TONE_BUILTIN_CLOSE_S.equals(id);
    }

    /**
     * 【1.15】这个内置名是不是**「默认（短）」**（{@link #PSD_TONE_BUILTIN_CLOSE_S}）——
     * 是的话播放端**不播素材开头的语音播报段**，只播后面的嘀嘀。
     *
     * <p>为什么这条判定放在**数据层**：和 {@link #psdBuiltinKey} 一样，「这个 id 是什么意思」
     * 只应该有一处真相。播放端（客户端）只问结论，不自己认识 {@code "default-s"} 这个字面量；
     * 回归脚本也能据此把「谁负责压制播报」钉死在一个方法上。
     *
     * <p>★ 调用点只有 {@code PsdChimePlayer.resolveTone}：命中时把这条素材的 {@code announce}
     * 置 false（**不碰**「播报/嘀嘀分界点」—— 关门端那道「不许剪进播报里」的夹取还要用它），
     * 于是「关门时整段起播」那一支不成立、改走「剪头 ⇒ 结尾落在门上」
     * ⇒ 结果就是**纯嘀嘀、且结尾对齐门关上**。
     */
    public static boolean isPsdBuiltinShort(String id) {
        return PSD_TONE_BUILTIN_CLOSE_S.equals(id);
    }

    /**
     * 【1.15】内置素材名字 → **哪一段内置音频**：{@code dooropen} / {@code doorclose} /
     * {@code mdoorclose}；不是内置名字返回 {@code null}（调用方按「音频库文件名」处理）。
     *
     * <p>{@code default} 是**按端别**取的 —— 开门端 = {@code dooropen}、关门端 = {@code mdoorclose}
     * （★【1.15】关门端从 {@code doorclose} 改成 {@code mdoorclose}：用户点名「屏蔽门默认音效改为
     * mdoorclose.ogg」，而 {@code default} 落到关门端就是「关门端的默认音效」）。
     * {@code default-s}（「默认（短）」）同样**按端别**取，与 {@code default} 落到同一段素材 ——
     * 它与 {@code default} 的差别**只在播放策略**（不分段，见 {@link #isPsdBuiltinShort}），
     * 不在素材本身。
     * 另外两个名字（{@code default-c} / {@code default-m}）与端别无关（显式写在哪一端就用哪一段）。
     * 播放端只拿这个返回值去查声音事件（{@code sounds.json} 里的 audio/dooropen 等），
     * 不在这里认识任何「声音事件」的概念（数据层不依赖客户端）。
     */
    public static String psdBuiltinKey(String which, String id) {
        if (PSD_TONE_BUILTIN_OPEN.equals(id) || PSD_TONE_BUILTIN_CLOSE_S.equals(id)) {
            return "open".equals(which) ? "dooropen" : "mdoorclose";
        }
        if (PSD_TONE_BUILTIN_CLOSE.equals(id)) {
            return "doorclose";
        }
        if (PSD_TONE_BUILTIN_CLOSE_M.equals(id)) {
            return "mdoorclose";
        }
        return null;
    }

    /** 【1.15】屏蔽门两项**全是** {@link #PSD_TONE_DEFAULT}（都跟维度默认）吗？与直梯同源。 */
    public static boolean isPsdToneAllDefault(String open, String close) {
        return PSD_TONE_DEFAULT.equals(open) && PSD_TONE_DEFAULT.equals(close);
    }

    /**
     * 【1.15】屏蔽门版本的 {@link #normalizeLiftToneAudio}：同一套哨兵（空/null → {@code default}），
     * 只是名字更贴近调用点，免得读 PSD 代码时以为在动直梯的东西。
     */
    public static String normalizePsdToneAudio(String audioId) {
        return normalizeLiftToneAudio(audioId);
    }

    /** 【1.50】屏蔽门「哪一项」的中文名（指令反馈 / 石斧界面共用同一套词）。 */
    public static String psdToneLabel(String which) {
        return "open".equals(which) ? "开门提示音" : "关门提示音";
    }

    /**
     * 【1.17】到站播报素材 id 的规范化：null / 空 / {@code off} / {@code none} → {@link #PSD_MIDIUM_OFF}。
     *
     * <p>与 {@link #normalizePsdToneAudio} 的差别：那一个把空值折成 {@code default}（= 跟内置），
     * 因为提示音**永远有内置兜底**；到站播报**没有内置素材**（站台广播不可能随模组分发），
     * 所以空值只能是「不播」。
     */
    public static String normalizePsdMidiumAudio(String audioId) {
        if (audioId == null || audioId.isEmpty()) {
            return PSD_MIDIUM_OFF;
        }
        String lower = audioId.toLowerCase(java.util.Locale.ROOT);
        if ("off".equals(lower) || "none".equals(lower)) {
            return PSD_MIDIUM_OFF;
        }
        return audioId;
    }

    /** 【1.17】到站播报是不是「不播」。 */
    public static boolean isPsdMidiumOff(String audioId) {
        return PSD_MIDIUM_OFF.equals(normalizePsdMidiumAudio(audioId));
    }

    /**
     * 【1.21】进站报站素材 id 的规范化（与 {@link #normalizePsdMidiumAudio} 同构）：
     * null / 空 / {@code off} / {@code none} → {@link #PSD_ARRIVE_OFF}。
     *
     * <p>★ 空值收敛到 {@code off}（**不是** {@code default}）：进站报站和到站播报一样
     * **没有内置素材**（站台广播不可能随模组分发）。合并两个 normalize 的代价是
     * 「进站报站跟着提示音一起回落内置 ⇒ 播一段不存在的音频」（症状：开了没声也不报错）。
     */
    public static String normalizePsdArriveAudio(String audioId) {
        if (audioId == null || audioId.isEmpty()) {
            return PSD_ARRIVE_OFF;
        }
        String lower = audioId.toLowerCase(java.util.Locale.ROOT);
        if ("off".equals(lower) || "none".equals(lower)) {
            return PSD_ARRIVE_OFF;
        }
        return audioId;
    }

    /** 【1.21】进站报站是不是「不播」。 */
    public static boolean isPsdArriveOff(String audioId) {
        return PSD_ARRIVE_OFF.equals(normalizePsdArriveAudio(audioId));
    }

    /**
     * 【1.50】屏蔽门单项音量的夹取：{@link #PSD_TONE_VOLUME_UNSET}（-1 = 跟随共用默认）原样放行，
     * 其余夹到 [{@link #HELP_VOLUME_MIN}, {@link #HELP_VOLUME_MAX}]（1~1000）。
     */
    public static int clampPsdToneVolume(int volume) {
        if (volume == PSD_TONE_VOLUME_UNSET) {
            return PSD_TONE_VOLUME_UNSET;
        }
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
    }

    /** 【1.50】屏蔽门提示音范围的夹取。 */
    public static int clampPsdHelpRound(int round) {
        return Math.max(PSD_HELP_ROUND_MIN, Math.min(PSD_HELP_ROUND_MAX, round));
    }

    /**
     * 【1.23】到站播报范围的夹取（格）。
     *
     * <p>与 {@link #clampPsdHelpRound} **同口径**（1~128）。三条路都走它：指令参数类型
     * （{@code roundArg()}）、存档读回、（UI 只读显示，不写）。
     */
    public static int clampPsdMidiumRound(int round) {
        return Math.max(PSD_MIDIUM_ROUND_MIN, Math.min(PSD_MIDIUM_ROUND_MAX, round));
    }

    /** 【1.23】进站报站范围的夹取（格）；口径同 {@link #clampPsdMidiumRound}。 */
    public static int clampPsdArriveRound(int round) {
        return Math.max(PSD_ARRIVE_ROUND_MIN, Math.min(PSD_ARRIVE_ROUND_MAX, round));
    }

    /**
     * 【1.16】关门提示音强制等待时长的夹取（秒）。
     *
     * <p>★ 夹取放在**数据层**这一处，指令参数类型、UI 输入框、存档读回三条路都走它 ——
     * 否则「填了没用」（被下游悄悄夹掉）与「填了个越界值把行为弄坏」两种症状会同时存在。
     */
    public static int clampPsdCloseWaitSeconds(int seconds) {
        return Math.max(PSD_CLOSE_WAIT_MIN, Math.min(PSD_CLOSE_WAIT_MAX, seconds));
    }

    /** ★【1.23】开门提示音等待秒数：允许**任意整数**（用户点名 (-∞, +∞)），clamp 恒等。
     *   关门强制等待仍 [0, +∞)（PSD_CLOSE_WAIT_MIN/MAX 不适用开门）。 */
    public static int clampPsdOpenWaitSeconds(int seconds) {
        return seconds;
    }

    /**
     * 【1.17】到站播报等待秒数的夹取（秒）。
     *
     * <p>★ 与 {@link #clampPsdCloseWaitSeconds} 的**唯一差别**：**没有上界**。
     * 用户点名「pbmmidium 指令和 ui 允许输入 0 到正无穷的数字 [0,+∞)」⇒ 只夹下界 0，
     * 负值折成 0（负的「等待」没有意义，且会让计划 tick 落到过去）。
     *
     * <p>上界落在 {@code Integer.parseInt} 的类型上界（{@code 2147483647} 秒 ≈ 68 年），
     * 再往上一秒都表达不出来 —— 这已经是「正无穷」在 int 里的全部空间。
     */
    public static int clampPsdMidiumWaitSeconds(int seconds) {
        return Math.max(PSD_MIDIUM_WAIT_MIN, seconds);
    }

    /**
     * 【1.21】进站报站秒数的夹取（秒）。
     *
     * <p>★ 与 {@link #clampPsdMidiumWaitSeconds} **正好相反**：那一个**只有下界 0**，
     * 这一个**只有上界 0**（用户点名的输入范围 {@code (-∞, 0]}）。正值 = 「晚于到站那一刻」
     * 没有意义，折成 0。**不要**给它补一个下界 —— 负无穷是用户明确要的。
     */
    public static int clampPsdArriveSeconds(int seconds) {
        return Math.min(PSD_ARRIVE_SECONDS_MAX, seconds);
    }

    /**
     * 【09-28】讲述人报站秒数的夹取（秒）—— 与 {@link #clampPsdArriveSeconds} **逐字同款**。
     *
     * <p>★ 两份 clamp 长得一样但**不许合并成一个**：它们是两条独立设置的边界，
     * 将来任一侧改范围（例如「讲述人要更早」）只该动自己那一个。
     */
    public static int clampPsdNarrateSeconds(int seconds) {
        return Math.min(PSD_NARRATE_SECONDS_MAX, seconds);
    }

    /**
     * 【09-30 续 3】「站台广播（讲述人）」秒数的夹取 ∈ [0, +∞) —— 与
     * {@link #clampPsdMidiumWaitSeconds} 同一口径（开门音播完**之后**再等 Y 秒，负数没意义）。
     *
     * <p>★ 注意方向与进站讲述人的 {@link #clampPsdNarrateSeconds}（(-∞, 0]，只有上界）
     * **正好相反** —— 两条是独立的设置，别合并。
     */
    public static int clampPsdMidiumNarrateSeconds(int seconds) {
        return Math.max(0, seconds);
    }

    /**
     * 【09-28 续】讲述人报站**样式**的夹取（关 / 上海 / 香港 / 自定义 userN）。
     *
     * <p>挡住三个方向：{@code null} 走不到这里（那一层在「门覆盖 &gt; 维度默认」判据里处理）、
     * 越界（0..{@link #PSD_NARRATE_MODE_MAX} 之外，例如旧存档里的布尔 {@code true}/{@code false}
     * 被别的路径读成 int）折成最近的合法档。★ 上界用 {@link #PSD_NARRATE_MODE_MAX} ——
     * 加样式时只改那一个常量。
     */
    public static int clampPsdNarrateMode(int mode) {
        return Math.max(PSD_NARRATE_OFF, Math.min(PSD_NARRATE_MODE_MAX, mode));
    }

    /** 【09-30】这一档是不是玩家**自定义**档（userN）。 */
    public static boolean isPsdNarrateUserStyle(int mode) {
        return mode >= PSD_NARRATE_USER_BASE;
    }

    /** 【09-30】自定义档的**编号** → 档位号：第 0 条（user1）→ 3、第 1 条（user2）→ 4…… */
    public static int psdNarrateUserMode(int zeroBasedIndex) {
        return PSD_NARRATE_USER_BASE + zeroBasedIndex;
    }

    /** 【09-30】档位号 → 自定义档的**编号**（0 起，user1 → 0）；内置档（0/1/2）返回负数。 */
    public static int psdNarrateUserIndex(int mode) {
        return mode - PSD_NARRATE_USER_BASE;
    }

    /**
     * 【09-28 续】样式的**中文名**（只用于日志 / UI 状态行 / 指令回执，一处定义免得各处各写一份）。
     *
     * @return 「关闭」/「开启(上海)」/「开启(香港)」/「自定义(userN)」；越界值先夹再取名（所以永远有结果）
     */
    public static String psdNarrateModeName(int mode) {
        int m = clampPsdNarrateMode(mode);
        // 【09-30】自定义档排在最前面判 —— 旧版 switch 的 default 分支会把它们全叫成「开启(上海)」。
        if (isPsdNarrateUserStyle(m)) {
            return "自定义(user" + (psdNarrateUserIndex(m) + 1) + ")";
        }
        switch (m) {
            case PSD_NARRATE_OFF:
                return "关闭";
            case PSD_NARRATE_HONGKONG:
                return "开启(香港)";
            default:
                return "开启(上海)";
        }
    }

    /** 维度默认阶梯动画速度：未单独设置阶梯动画的扶梯使用它。 */
    public double defaultStepSpeed() {
        return stepEnabled ? stepValue : VANILLA_STEP;
    }

    /** 该方块所在的扶梯是否被单独设置了阶梯动画速度。 */
    public boolean hasIndividualStep(BlockPos pos) {
        return stepSpeeds.containsKey(pos);
    }

    public static final SavedData.Factory<EscalatorSpeedData> FACTORY =
            new SavedData.Factory<>(EscalatorSpeedData::new, EscalatorSpeedData::fromTag, DataFixTypes.SAVED_DATA_MAP_DATA);

    public static EscalatorSpeedData fromTag(CompoundTag tag) {
        EscalatorSpeedData data = new EscalatorSpeedData();
        if (tag.contains("default")) {
            data.defaultSpeed = clamp(tag.getDouble("default"));
        }
        data.speeds.putAll(readDoubleMap(tag.getCompound("speeds")));
        data.stepSpeeds.putAll(readDoubleMap(tag.getCompound("step")));
        for (String key : tag.getCompound("axe").getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                data.axeModified.add(pos);
            }
        }
        if (tag.contains("stepEnabled")) {
            data.stepEnabled = tag.getBoolean("stepEnabled");
        }
        if (tag.contains("stepValue")) {
            data.stepValue = clamp(tag.getDouble("stepValue"));
        }
        // 【1.7】音频库与扶梯-音频绑定
        CompoundTag audioTag = tag.getCompound("audioLibrary");
        for (String key : audioTag.getAllKeys()) {
            byte[] bytes = audioTag.getByteArray(key);
            if (bytes.length > 0 && bytes.length <= MAX_AUDIO_BYTES) {
                data.audioLibrary.put(key, bytes);
            }
        }
        // 【1.28】音频分类注册表：每分类一个名字列表。
        //   旧存档没有这张表 → 把平铺库里的名字放进**所有**分类（旧版 = 一个共享库，
        //   任何界面都看得到；迁移后保持「到处都看得到」，玩家可自行在新文件夹结构下重新导入）。
        //   【1.28+】分类改过目录（futi / music / help）→ 按 migrateCategoryKey 把旧键迁到新键。
        CompoundTag catTag = tag.getCompound("audioCategoryNames");
        boolean anyCategory = false;
        for (String cat : catTag.getAllKeys()) {
            net.minecraft.nbt.ListTag nameList = catTag.getList(cat, 8);
            Set<String> names = new HashSet<>();
            for (int i = 0; i < nameList.size(); i++) {
                String n = nameList.getString(i);
                if (data.audioLibrary.containsKey(n)) {
                    names.add(n);
                }
            }
            if (!names.isEmpty()) {
                data.audioCategoryNames
                        .computeIfAbsent(EscalatorSpeedManager.migrateCategoryKey(cat), k -> new HashSet<>())
                        .addAll(names);
                anyCategory = true;
            }
        }
        if (!anyCategory && !data.audioLibrary.isEmpty()) {
            for (String cat : EscalatorSpeedManager.ALL_CATEGORIES) {
                data.audioCategoryNames.put(cat, new HashSet<>(data.audioLibrary.keySet()));
            }
        }
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockAudio")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockAudio.put(pos, entry.getValue());
            }
        }
        // 【1.18.1204】地图图片库：与音频库相同的平铺字节存储，直接融入存档。
        CompoundTag picTag = tag.getCompound("pictureLibrary");
        for (String key : picTag.getAllKeys()) {
            byte[] bytes = picTag.getByteArray(key);
            if (bytes.length > 0 && bytes.length <= MAX_PICTURE_BYTES) {
                data.pictureLibrary.put(key, bytes);
            }
        }
        // 【1.18.1204】当前显示图片（旧存档没有这一段 → null = 库空占位纹理）。
        //   引用悬空（指向不在库里的名字）时也按 null 处理，由客户端显示白色+灰边。
        if (tag.contains("pictureCurrent")) {
            String cur = tag.getString("pictureCurrent");
            if (!cur.isEmpty() && data.pictureLibrary.containsKey(cur)) {
                data.pictureCurrent = cur;
            }
        }
        // 【1.9】扶梯声音音量（旧存档没有这一段 → 全部按默认 100 处理）
        data.blockVolume.putAll(readIntMap(tag.getCompound("blockVolume")));
        // 【1.12】默认扶梯音量（旧存档没有这一段 → 100 = 原始音量）
        if (tag.contains("defaultVolume")) {
            data.defaultVolume = clampVolume(tag.getInt("defaultVolume"));
        }
        // 【1.11】默认扶梯音频（旧存档没有这一段 → null = 未绑定的扶梯静音）
        if (tag.contains("defaultAudio")) {
            String id = tag.getString("defaultAudio");
            if (!id.isEmpty()) {
                data.defaultAudio = id;
            }
        }
        // 【1.16】无障碍提示音开关（旧存档没有这一段 → true = 开，与 1.15 的「一直响」一致）
        if (tag.contains("defaultHelp")) {
            data.defaultHelp = tag.getBoolean("defaultHelp");
        }
        data.blockHelp.putAll(readBoolMap(tag.getCompound("blockHelp")));
        // 【1.18】无障碍提示音音量（旧存档没有这一段 → 全部按默认 100 处理，与 1.17 的固定音量一致）
        data.blockHelpVolume.putAll(readHelpVolumeMap(tag.getCompound("blockHelpVolume")));
        if (tag.contains("defaultHelpVolume")) {
            data.defaultHelpVolume = clampHelpVolume(tag.getInt("defaultHelpVolume"));
        }
        // 【1.24】两个淡入淡出范围（旧存档没有这一段 → 底噪 16 格、提示音 4 格，与 1.23 的行为完全一致）
        data.blockRound.putAll(readRoundMap(tag.getCompound("blockRound")));
        if (tag.contains("defaultRound")) {
            data.defaultRound = clampRound(tag.getInt("defaultRound"));
        }
        data.blockHelpRound.putAll(readHelpRoundMap(tag.getCompound("blockHelpRound")));
        if (tag.contains("defaultHelpRound")) {
            data.defaultHelpRound = clampHelpRound(tag.getInt("defaultHelpRound"));
        }
        // 【1.31】无障碍提示音速率（旧存档没有这一段 → 入口 10 Hz、出口 1 Hz，与 1.27 定版音色一致）
        data.blockHelpSpeedIn.putAll(readHelpSpeedInMap(tag.getCompound("blockHelpSpeedIn")));
        if (tag.contains("defaultHelpSpeedIn")) {
            data.defaultHelpSpeedIn = clampHelpSpeed(tag.getInt("defaultHelpSpeedIn"));
        }
        data.blockHelpSpeedOut.putAll(readHelpSpeedOutMap(tag.getCompound("blockHelpSpeedOut")));
        if (tag.contains("defaultHelpSpeedOut")) {
            data.defaultHelpSpeedOut = clampHelpSpeed(tag.getInt("defaultHelpSpeedOut"));
        }
        // 【1.39→1.41】无障碍提示音「音乐」
        // ① 1.39 的旧字段（单一 defaultHelpAudio / blockHelpAudio，两头共用一段声音）
        //    读进来**同时**当作「进 / 出」两头的初值 ⇒ 旧存档听起来逐字节不变；
        // ② 随后用 1.41 的 in / out 字段覆盖各自那一头（新存档两边都写，以新字段为准）。
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockHelpAudio")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockHelpAudioIn.put(pos, entry.getValue());
                data.blockHelpAudioOut.put(pos, entry.getValue());
            }
        }
        if (tag.contains("defaultHelpAudio")) {
            String id = tag.getString("defaultHelpAudio");
            if (!id.isEmpty()) {
                data.defaultHelpAudioIn = id;
                data.defaultHelpAudioOut = id;
            }
        }
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockHelpAudioIn")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockHelpAudioIn.put(pos, entry.getValue());
            }
        }
        if (tag.contains("defaultHelpAudioIn")) {
            String id = tag.getString("defaultHelpAudioIn");
            if (!id.isEmpty()) {
                data.defaultHelpAudioIn = id;
            }
        }
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockHelpAudioOut")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockHelpAudioOut.put(pos, entry.getValue());
            }
        }
        if (tag.contains("defaultHelpAudioOut")) {
            String id = tag.getString("defaultHelpAudioOut");
            if (!id.isEmpty()) {
                data.defaultHelpAudioOut = id;
            }
        }
        // 【1.42】直梯开关门提示音
        //   旧存档没有这两个字段 → 保持字段初始值（开 / 倍速 1.0），也就是「功能默认打开、原速」。
        if (tag.contains("defaultLiftHelp")) {
            data.defaultLiftHelp = tag.getBoolean("defaultLiftHelp");
        }
        if (tag.contains("defaultLiftHelpSpeed")) {
            data.defaultLiftHelpSpeed = clampLiftHelpSpeed(tag.getFloat("defaultLiftHelpSpeed"));
        }
        // 【1.43】音量：旧存档（含只有 1.42 字段的）缺它 → 保持初始值 100 = 原始音量
        if (tag.contains("defaultLiftHelpVolume")) {
            data.defaultLiftHelpVolume = clampLiftHelpVolume(tag.getInt("defaultLiftHelpVolume"));
        }
        // 【1.46】四提示音独立子开关：旧存档缺字段 → 保持 true（与 1.45 之前行为一致）。
        //   【1.28】旧 `chime` 字段迁移到 open 与 close 两头；新存档以 open/close 新字段为准。
        if (tag.contains("defaultLiftToneUpEnabled")) {
            data.defaultLiftToneUpEnabled = tag.getBoolean("defaultLiftToneUpEnabled");
        }
        if (tag.contains("defaultLiftToneDownEnabled")) {
            data.defaultLiftToneDownEnabled = tag.getBoolean("defaultLiftToneDownEnabled");
        }
        if (tag.contains("defaultLiftToneChimeEnabled")) {
            boolean chime = tag.getBoolean("defaultLiftToneChimeEnabled");
            data.defaultLiftToneOpenEnabled = chime;
            data.defaultLiftToneCloseEnabled = chime;
        }
        if (tag.contains("defaultLiftToneOpenEnabled")) {
            data.defaultLiftToneOpenEnabled = tag.getBoolean("defaultLiftToneOpenEnabled");
        }
        if (tag.contains("defaultLiftToneCloseEnabled")) {
            data.defaultLiftToneCloseEnabled = tag.getBoolean("defaultLiftToneCloseEnabled");
        }
        // 【1.15】四提示音的维度默认素材：旧存档缺字段 → default（内置素材，与 1.14 行为一致）。
        //   【1.28】旧 `chime` 字段迁移到 open 与 close 两头。
        if (tag.contains("defaultLiftToneAudioUp")) {
            data.defaultLiftToneAudioUp = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioUp"));
        }
        if (tag.contains("defaultLiftToneAudioDown")) {
            data.defaultLiftToneAudioDown = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioDown"));
        }
        if (tag.contains("defaultLiftToneAudioChime")) {
            String chime = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioChime"));
            data.defaultLiftToneAudioOpen = chime;
            data.defaultLiftToneAudioClose = chime;
        }
        if (tag.contains("defaultLiftToneAudioOpen")) {
            data.defaultLiftToneAudioOpen = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioOpen"));
        }
        if (tag.contains("defaultLiftToneAudioClose")) {
            data.defaultLiftToneAudioClose = normalizeLiftToneAudio(tag.getString("defaultLiftToneAudioClose"));
        }
        // 【1.47】直梯提示音淡入淡出范围：旧存档缺字段 → 默认 4 格（同「第一次载入」）
        if (tag.contains("defaultLiftHelpRound")) {
            data.defaultLiftHelpRound = clampLiftHelpRound(tag.getInt("defaultLiftHelpRound"));
        }
        // 【1.48】四项各自音量：旧存档缺字段 → -1（跟随共用默认）。
        //   【1.28】旧 `chime` 字段迁移到 open / close 两头。
        if (tag.contains("defaultLiftToneVolumeUp")) {
            data.defaultLiftToneVolumeUp = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeUp"));
        }
        if (tag.contains("defaultLiftToneVolumeDown")) {
            data.defaultLiftToneVolumeDown = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeDown"));
        }
        if (tag.contains("defaultLiftToneVolumeChime")) {
            int chime = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeChime"));
            data.defaultLiftToneVolumeOpen = chime;
            data.defaultLiftToneVolumeClose = chime;
        }
        if (tag.contains("defaultLiftToneVolumeOpen")) {
            data.defaultLiftToneVolumeOpen = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeOpen"));
        }
        if (tag.contains("defaultLiftToneVolumeClose")) {
            data.defaultLiftToneVolumeClose = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeClose"));
        }
        // 【1.45】直梯楼层轨道提示音：key → {up, down, open, close} 四个音频 id。
        //   旧存档没有这张表 → 空表（全部走默认素材）。
        //   旧版元素是 `{ "key", "up", "down", "chime" }` ⇒ 迁移时把 chime 喂给 open / close 两头。
        //   格式：ListTag，每个元素是 `{ \"key\": <long>, \"up\": <str>, \"down\": <str>,
        //   \"open\": <str>, \"close\": <str> }`。
        if (tag.contains("liftToneAudio", 9)) {
            for (net.minecraft.nbt.Tag item : tag.getList("liftToneAudio", 10)) {
                CompoundTag entry = (CompoundTag) item;
                long key = entry.getLong("key");
                String up = entry.contains("up") ? entry.getString("up") : "";
                String down = entry.contains("down") ? entry.getString("down") : "";
                String chimeOld = entry.contains("chime") ? entry.getString("chime") : null;
                String open = entry.contains("open") ? entry.getString("open")
                        : (chimeOld != null ? chimeOld : "");
                String close = entry.contains("close") ? entry.getString("close")
                        : (chimeOld != null ? chimeOld : "");
                if (key != 0L && !(up.isEmpty() && down.isEmpty() && open.isEmpty() && close.isEmpty())) {
                    data.liftToneAudio.put(key, new LiftToneAudio(up, down, open, close));
                }
            }
        }
        // 【1.50】屏蔽门提示音：总开关 / 两项子开关 / 共用默认音量 / 两项单独音量 / 范围。
        //   旧存档（≤1.49）一个字段都没有 → 保持字段初始值（开、开、开、100、-1、-1、16），
        //   也就是「功能默认打开、原始音量、站台尺度 16 格」。
        if (tag.contains("defaultPsdHelp")) {
            data.defaultPsdHelp = tag.getBoolean("defaultPsdHelp");
        }
        if (tag.contains("defaultPsdToneOpenEnabled")) {
            data.defaultPsdToneOpenEnabled = tag.getBoolean("defaultPsdToneOpenEnabled");
        }
        if (tag.contains("defaultPsdToneCloseEnabled")) {
            data.defaultPsdToneCloseEnabled = tag.getBoolean("defaultPsdToneCloseEnabled");
        }
        if (tag.contains("defaultPsdHelpVolume")) {
            data.defaultPsdHelpVolume = clampLiftHelpVolume(tag.getInt("defaultPsdHelpVolume"));
        }
        if (tag.contains("defaultPsdToneVolumeOpen")) {
            data.defaultPsdToneVolumeOpen = clampPsdToneVolume(tag.getInt("defaultPsdToneVolumeOpen"));
        }
        if (tag.contains("defaultPsdToneVolumeClose")) {
            data.defaultPsdToneVolumeClose = clampPsdToneVolume(tag.getInt("defaultPsdToneVolumeClose"));
        }
        // 【09-30】闸机（进站 / 出站）提示音：维度默认素材 + 音量（同上，只有这一层）。
        //   ★ 两个键都不在旧档里 ⇒ 保持字段初始值（default / 100）= 「等于没有这个功能」的行为，
        //     旧档不需要任何迁移。
        if (tag.contains("defaultZhajiToneAudioIn")) {
            data.defaultZhajiToneAudioIn = normalizeZhajiToneAudio(tag.getString("defaultZhajiToneAudioIn"));
        }
        if (tag.contains("defaultZhajiToneAudioOut")) {
            data.defaultZhajiToneAudioOut = normalizeZhajiToneAudio(tag.getString("defaultZhajiToneAudioOut"));
        }
        if (tag.contains("defaultZhajiToneVolumeIn")) {
            data.defaultZhajiToneVolumeIn = clampZhajiVolume(tag.getInt("defaultZhajiToneVolumeIn"));
        }
        if (tag.contains("defaultZhajiToneVolumeOut")) {
            data.defaultZhajiToneVolumeOut = clampZhajiVolume(tag.getInt("defaultZhajiToneVolumeOut"));
        }
        // 【09-30 续】闸机「一组闸机」表。旧存档没有它 → 空表（全部走维度默认，行为与 09-30 之前一致）。
        //   格式：ListTag，元素 `{ "k": <long>, "ain": <str>, "aout": <str>, "vin": <int>, "vout": <int> }`。
        //   ★ 音量**缺键 = 跟维度默认**（不是 0、也不是 -1）；读侧只在 contains 时才赋非 null。
        if (tag.contains("zhajiTone", 9)) {
            for (net.minecraft.nbt.Tag item : tag.getList("zhajiTone", 10)) {
                CompoundTag entry = (CompoundTag) item;
                long key = entry.getLong("k");
                String ain = entry.contains("ain") ? entry.getString("ain") : ZHAJI_TONE_DEFAULT;
                String aout = entry.contains("aout") ? entry.getString("aout") : ZHAJI_TONE_DEFAULT;
                Integer vin = entry.contains("vin") ? clampZhajiVolume(entry.getInt("vin")) : null;
                Integer vout = entry.contains("vout") ? clampZhajiVolume(entry.getInt("vout")) : null;
                ZhajiTone tone = new ZhajiTone(normalizeZhajiToneAudio(ain),
                        normalizeZhajiToneAudio(aout), vin, vout);
                if (!tone.isEmpty()) {
                    data.zhajiTone.put(key, tone);
                }
            }
        }
        // 【09-29】屏蔽门三类的可闻范围（格）——拆成水平/垂直两维。旧存档只有一个单值旧键：
        //   ★ 迁移策略与 /jsr round 完全一致 —— 旧单值回退给**水平**，垂直用默认 5
        //   （因为旧单值是「三维距离」，它服务的正是水平站台尺度；垂直站台很少超过 5 格）。
        //   这一版新键写的是 Xz/Y 两个，读回时**新键优先**（老存档没有新键 → 走旧键迁移）。
        if (tag.contains("defaultPsdHelpRoundXz")) {
            data.defaultPsdHelpRoundXz = clampPsdHelpRound(tag.getInt("defaultPsdHelpRoundXz"));
        } else if (tag.contains("defaultPsdHelpRound")) {
            data.defaultPsdHelpRoundXz = clampPsdHelpRound(tag.getInt("defaultPsdHelpRound"));
        }
        if (tag.contains("defaultPsdHelpRoundY")) {
            data.defaultPsdHelpRoundY = clampPsdHelpRound(tag.getInt("defaultPsdHelpRoundY"));
        }
        // 【1.23】到站播报的可闻范围（格）。旧存档只有单值旧键 → 同上：旧值回退给水平。
        if (tag.contains("defaultPsdMidiumRoundXz")) {
            data.defaultPsdMidiumRoundXz = clampPsdMidiumRound(tag.getInt("defaultPsdMidiumRoundXz"));
        } else if (tag.contains("defaultPsdMidiumRound")) {
            data.defaultPsdMidiumRoundXz = clampPsdMidiumRound(tag.getInt("defaultPsdMidiumRound"));
        }
        if (tag.contains("defaultPsdMidiumRoundY")) {
            data.defaultPsdMidiumRoundY = clampPsdMidiumRound(tag.getInt("defaultPsdMidiumRoundY"));
        }
        // 【1.23】进站报站的可闻范围（格）；迁移策略同上。
        if (tag.contains("defaultPsdArriveRoundXz")) {
            data.defaultPsdArriveRoundXz = clampPsdArriveRound(tag.getInt("defaultPsdArriveRoundXz"));
        } else if (tag.contains("defaultPsdArriveRound")) {
            data.defaultPsdArriveRoundXz = clampPsdArriveRound(tag.getInt("defaultPsdArriveRound"));
        }
        if (tag.contains("defaultPsdArriveRoundY")) {
            data.defaultPsdArriveRoundY = clampPsdArriveRound(tag.getInt("defaultPsdArriveRoundY"));
        }
        // 【1.16】关门提示音的强制等待时长（秒）。★ 旧存档**没有**这个键 → 不覆盖，
        //   于是保持字段初始值 = DEFAULT_PSD_CLOSE_WAIT_SECONDS（5 秒）。
        //   这正是用户要的「第一次加入 mod 时默认为 5 秒」——本项**不**走「旧档=旧行为」那套。
        if (tag.contains("defaultPsdCloseWaitSeconds")) {
            data.defaultPsdCloseWaitSeconds = clampPsdCloseWaitSeconds(tag.getInt("defaultPsdCloseWaitSeconds"));
        }
        // 【1.17】到站播报（素材 + 等待秒数）。旧存档没有两个键 →
        //   保持字段初始值（素材 = off、等待 = 0），也就是「默认不播报」——
        //   这一项**走**「旧档=旧行为」那套（用户只点名了 pbmclosewait 的默认 5 秒，
        //   没有给到站播报指定默认；而站台广播本来就没有内置素材，默认只能是「不播」）。
        if (tag.contains("defaultPsdMidiumAudio")) {
            data.defaultPsdMidiumAudio = normalizePsdMidiumAudio(tag.getString("defaultPsdMidiumAudio"));
        }
        if (tag.contains("defaultPsdMidiumWaitSeconds")) {
            data.defaultPsdMidiumWaitSeconds =
                    clampPsdMidiumWaitSeconds(tag.getInt("defaultPsdMidiumWaitSeconds"));
        }
        // 【1.22】到站播报自己那一项的音量（-1 = 跟随共用默认）。
        if (tag.contains("defaultPsdMidiumVolume")) {
            data.defaultPsdMidiumVolume = clampPsdToneVolume(tag.getInt("defaultPsdMidiumVolume"));
        }
        // 【1.21】进站报站（素材 + 秒数）。旧存档没有两个键 → 保持字段初始值
        //   （素材 = off、秒数 = 0），也就是「默认不播」——与到站播报同一条判断。
        if (tag.contains("defaultPsdArriveAudio")) {
            data.defaultPsdArriveAudio = normalizePsdArriveAudio(tag.getString("defaultPsdArriveAudio"));
        }
        if (tag.contains("defaultPsdArriveSeconds")) {
            data.defaultPsdArriveSeconds =
                    clampPsdArriveSeconds(tag.getInt("defaultPsdArriveSeconds"));
        }
        // 【1.22】进站报站自己那一项的音量（-1 = 跟随共用默认）。
        if (tag.contains("defaultPsdArriveVolume")) {
            data.defaultPsdArriveVolume = clampPsdToneVolume(tag.getInt("defaultPsdArriveVolume"));
        }
        // 【09-28】讲述人报站的维度默认（样式 + 秒数）。老存档没有这两个键 ⇒ 保持字段初始值
        //   （开启(香港) / -20 秒 —— 【10-01】用户点名的新默认），与「装上就念、提前 20 秒念」一致。
        if (tag.contains("defaultPsdNarrateMode")) {
            data.defaultPsdNarrateMode =
                    clampPsdNarrateMode(tag.getInt("defaultPsdNarrateMode"));
        } else if (tag.contains("defaultPsdNarrateOn", 1)) {
            // ★ 1.28.1204 那一版在这里存的是**布尔开关**（byte 类型）：直接 getInt 会静默返回 0
            //   ⇒ 把「开着」读成「关闭」。按「开 = 开启(香港) / 关 = 关闭」搬过来
            //   （【10-01】默认样式已是香港，老布尔迁移也落到同一个常量）。
            data.defaultPsdNarrateMode = tag.getBoolean("defaultPsdNarrateOn")
                    ? PSD_NARRATE_HONGKONG : PSD_NARRATE_OFF;
        }
        if (tag.contains("defaultPsdNarrateSeconds")) {
            data.defaultPsdNarrateSeconds =
                    clampPsdNarrateSeconds(tag.getInt("defaultPsdNarrateSeconds"));
        }
        // 【09-30 续 3】「站台广播（讲述人）」的维度默认（样式 + 秒数）。老存档没有这两个键
        //   ⇒ 保持字段初始值（开启(上海) / 0 秒），与进站讲述人的默认口径一致。
        if (tag.contains("defaultPsdMidiumNarrateMode")) {
            data.defaultPsdMidiumNarrateMode =
                    clampPsdNarrateMode(tag.getInt("defaultPsdMidiumNarrateMode"));
        }
        if (tag.contains("defaultPsdMidiumNarrateSeconds")) {
            data.defaultPsdMidiumNarrateSeconds =
                    clampPsdMidiumNarrateSeconds(tag.getInt("defaultPsdMidiumNarrateSeconds"));
        }
        // 【10-01】两条讲述人广播的「玩家自定义文字」：按存档存（ListTag of String）。
        //   老存档没有这两个键 → getList 返回空表 = 「还没有自定义词」（与旧版本行为一致）。
        //   ★ 条数夹到 MAX_PSD_NARRATE_USER_STYLES（手改存档写进超量档位文字也收得住）。
        data.defaultPsdArriveNarrateUserTexts =
                readNarrateUserTexts(tag.getList("defaultPsdArriveNarrateUserTexts", 8));
        data.defaultPsdMidiumNarrateUserTexts =
                readNarrateUserTexts(tag.getList("defaultPsdMidiumNarrateUserTexts", 8));
        // 【1.15】屏蔽门维度默认素材。旧存档没有这两个键 → 读回 default
        //   （= 两项都走内置素材），与 1.50 的行为逐字一致，不需要迁移。
        if (tag.contains("defaultPsdToneAudioOpen")) {
            data.defaultPsdToneAudioOpen = normalizePsdToneAudio(tag.getString("defaultPsdToneAudioOpen"));
        }
        if (tag.contains("defaultPsdToneAudioClose")) {
            data.defaultPsdToneAudioClose = normalizePsdToneAudio(tag.getString("defaultPsdToneAudioClose"));
        }
        // 【1.50】每扇门单独设置的素材：key(锚点打包坐标) → {open, close}。
        //   ★ 空串 = 这一项没设过（与直梯那张表用同一套哨兵：空串 → 默认素材）。
        // 【1.20】同一张表里还存**这一扇门自己的**开关 / 音量 / 强制等待 / 到站播报：
        //   老存档没有那些键 ⇒ 全部读成 null（= 跟维度默认），行为与 1.19 完全一致。
        if (tag.contains("psdToneAudio", 9)) {
            for (net.minecraft.nbt.Tag item : tag.getList("psdToneAudio", 10)) {
                CompoundTag entry = (CompoundTag) item;
                long key = entry.getLong("key");
                String open = normalizePsdToneAudio(entry.contains("open") ? entry.getString("open") : "");
                String close = normalizePsdToneAudio(entry.contains("close") ? entry.getString("close") : "");
                PsdToneAudio v = new PsdToneAudio(open, close,
                        optBool(entry, "help"), optBool(entry, "openEnabled"), optBool(entry, "closeEnabled"),
                        optInt(entry, "volume"), optInt(entry, "openVolume"), optInt(entry, "closeVolume"),
                        optInt(entry, "openWaitSeconds"), optInt(entry, "closeWaitSeconds"),
                        optString(entry, "midium"), optInt(entry, "midiumWaitSeconds"),
                        optInt(entry, "midiumVolume"),
                        optString(entry, "arrive"), optInt(entry, "arriveSeconds"),
                        optInt(entry, "arriveVolume"),
                        // 【09-28】进站广播（讲述人）：样式（0/1/2）+ 秒数（老存档没有这两个键 ⇒ null = 跟维度默认）
                        //   ★ 样式是 int 三档（见 PSD_NARRATE_*）：读出来先夹一次，手改过的存档也能收住。
                        optNarrateMode(entry, "narrate"), optInt(entry, "narrateSeconds"),
                        // 【09-30 续 3】站台广播（讲述人）：同一套档位 + 等待秒数（老存档 ⇒ null = 跟维度默认）
                        optNarrateMode(entry, "midiumNarrate"), optInt(entry, "midiumNarrateSeconds"));
                // ★ 判空改用 isEmpty()：现在「只设了音量、素材两项都是默认」也是一条**有内容**的记录。
                if (key != 0L && !v.isEmpty()) {
                    data.psdToneAudio.put(key, v);
                }
            }
        }
        return data;
    }

    private static Map<BlockPos, Double> readDoubleMap(CompoundTag compound) {
        Map<BlockPos, Double> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clamp(compound.getDouble(key)));
            }
        }
        return out;
    }

    private static Map<String, String> readStringMap(CompoundTag compound) {
        Map<String, String> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            out.put(key, compound.getString(key));
        }
        return out;
    }

    /** 【1.41】把「方块 → 字符串」写成 NBT（键 = {@code x,y,z}）。 */
    private static CompoundTag writeStringMap(Map<BlockPos, String> map) {
        CompoundTag out = new CompoundTag();
        for (Map.Entry<BlockPos, String> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            out.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return out;
    }

    /** 【10-01】读「讲述人自定义文字」列表：夹到 {@link #MAX_PSD_NARRATE_USER_STYLES} 条、逐条去首尾空白。 */
    private static List<String> readNarrateUserTexts(ListTag list) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.size() && out.size() < MAX_PSD_NARRATE_USER_STYLES; i++) {
            String s = list.getString(i);
            out.add(s == null ? "" : s.trim());
        }
        return out;
    }

    /** 【10-01】写「讲述人自定义文字」列表（与 {@link #readNarrateUserTexts} 成对）。 */
    private static ListTag writeNarrateUserTexts(List<String> texts) {
        ListTag out = new ListTag();
        if (texts != null) {
            int n = Math.min(texts.size(), MAX_PSD_NARRATE_USER_STYLES);
            for (int i = 0; i < n; i++) {
                out.add(net.minecraft.nbt.StringTag.valueOf(texts.get(i) == null ? "" : texts.get(i)));
            }
        }
        return out;
    }

    /** 【1.9】读「方块 → 音量」，顺手把越界值夹回 1~1000（防止手改 NBT 后出怪值）。 */
    private static Map<BlockPos, Integer> readIntMap(CompoundTag compound) {
        Map<BlockPos, Integer> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clampVolume(compound.getInt(key)));
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putDouble("default", defaultSpeed);
        tag.put("speeds", writeDoubleMap(speeds));
        CompoundTag axe = new CompoundTag();
        for (BlockPos pos : axeModified) {
            axe.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), "1");
        }
        tag.put("axe", axe);
        tag.putBoolean("stepEnabled", stepEnabled);
        tag.putDouble("stepValue", stepValue);
        tag.put("step", writeDoubleMap(stepSpeeds));
        // 【1.7】音频库与扶梯-音频绑定
        CompoundTag audioTag = new CompoundTag();
        for (Map.Entry<String, byte[]> entry : audioLibrary.entrySet()) {
            audioTag.putByteArray(entry.getKey(), entry.getValue());
        }
        tag.put("audioLibrary", audioTag);
        // 【1.28】音频分类注册表：每分类一个名字列表（与读侧一一对应）。
        net.minecraft.nbt.CompoundTag catTag = new net.minecraft.nbt.CompoundTag();
        for (Map.Entry<String, Set<String>> e : audioCategoryNames.entrySet()) {
            ListTag nameList = new ListTag();
            for (String n : e.getValue()) {
                nameList.add(net.minecraft.nbt.StringTag.valueOf(n));
            }
            catTag.put(e.getKey(), nameList);
        }
        tag.put("audioCategoryNames", catTag);
        CompoundTag bindTag = new CompoundTag();
        for (Map.Entry<BlockPos, String> entry : blockAudio.entrySet()) {
            BlockPos pos = entry.getKey();
            bindTag.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        tag.put("blockAudio", bindTag);
        // 【1.18.1204】地图图片库：与读侧一一对应，原图字节直接进存档 NBT。
        CompoundTag picTag = new CompoundTag();
        for (Map.Entry<String, byte[]> entry : pictureLibrary.entrySet()) {
            picTag.putByteArray(entry.getKey(), entry.getValue());
        }
        tag.put("pictureLibrary", picTag);
        // 【1.18.1204】当前显示图片（null 不写：旧版本读到「没有」＝库空占位纹理）。
        if (pictureCurrent != null) {
            tag.putString("pictureCurrent", pictureCurrent);
        }
        // 【1.9】扶梯声音音量
        tag.put("blockVolume", writeIntMap(blockVolume));
        // 【1.12】默认扶梯音量
        tag.putInt("defaultVolume", defaultVolume);
        // 【1.11】默认扶梯音频
        if (defaultAudio != null && !defaultAudio.isEmpty()) {
            tag.putString("defaultAudio", defaultAudio);
        }
        // 【1.16】无障碍提示音开关
        tag.putBoolean("defaultHelp", defaultHelp);
        tag.put("blockHelp", writeBoolMap(blockHelp));
        // 【1.18】无障碍提示音音量
        tag.putInt("defaultHelpVolume", defaultHelpVolume);
        tag.put("blockHelpVolume", writeIntMap(blockHelpVolume));
        // 【1.24】两个淡入淡出范围（底噪 / 提示音）
        tag.putInt("defaultRound", defaultRound);
        tag.put("blockRound", writeIntMap(blockRound));
        tag.putInt("defaultHelpRound", defaultHelpRound);
        tag.put("blockHelpRound", writeIntMap(blockHelpRound));
        // 【1.31】无障碍提示音速率（入口 / 出口各一套）
        tag.putInt("defaultHelpSpeedIn", defaultHelpSpeedIn);
        tag.put("blockHelpSpeedIn", writeIntMap(blockHelpSpeedIn));
        tag.putInt("defaultHelpSpeedOut", defaultHelpSpeedOut);
        tag.put("blockHelpSpeedOut", writeIntMap(blockHelpSpeedOut));
        // 【1.41】无障碍提示音「音乐」（进 / 出各一套；默认值与音频库共用，这里只存 ID）
        tag.putString("defaultHelpAudioIn", defaultHelpAudioIn);
        tag.put("blockHelpAudioIn", writeStringMap(blockHelpAudioIn));
        tag.putString("defaultHelpAudioOut", defaultHelpAudioOut);
        tag.put("blockHelpAudioOut", writeStringMap(blockHelpAudioOut));
        // ★ 旧字段（1.39，单一值）作为**向后兼容镜像**写一份，值 = **进入扶梯**那一头。
        //   目的只有一个：万一这份存档被回退版本（或不认 in/out 的构建）打开，
        //   至少还能看到进扶梯那套设置，而不是「提示音全没了」。
        //   反过来旧版一旦保存，它会把这一个值当成两头的唯一值写回（out 那份设置丢失）——
        //   这是旧版不认识 out 的必然结果，可以接受。
        tag.putString("defaultHelpAudio", defaultHelpAudioIn);
        tag.put("blockHelpAudio", writeStringMap(blockHelpAudioIn));
        // 【1.42】直梯开关门提示音（只有维度默认一层，没有按方块索引的表）
        tag.putBoolean("defaultLiftHelp", defaultLiftHelp);
        tag.putFloat("defaultLiftHelpSpeed", defaultLiftHelpSpeed);
        // 【1.43】直梯提示音音量（同上，只有维度默认一层）
        tag.putInt("defaultLiftHelpVolume", defaultLiftHelpVolume);
        // 【1.46】四提示音独立子开关（维度默认一层）。【1.28】chime 拆成 open / close。
        //   旧字段（chime）作为**向后兼容镜像**写一份，值 = open 那一头（同上一条 1.41 的注释逻辑）。
        tag.putBoolean("defaultLiftToneUpEnabled", defaultLiftToneUpEnabled);
        tag.putBoolean("defaultLiftToneDownEnabled", defaultLiftToneDownEnabled);
        tag.putBoolean("defaultLiftToneOpenEnabled", defaultLiftToneOpenEnabled);
        tag.putBoolean("defaultLiftToneCloseEnabled", defaultLiftToneCloseEnabled);
        tag.putBoolean("defaultLiftToneChimeEnabled", defaultLiftToneOpenEnabled);
        // 【1.15】四提示音的维度默认素材（default / off / 音频库文件名）
        tag.putString("defaultLiftToneAudioUp", defaultLiftToneAudioUp);
        tag.putString("defaultLiftToneAudioDown", defaultLiftToneAudioDown);
        tag.putString("defaultLiftToneAudioOpen", defaultLiftToneAudioOpen);
        tag.putString("defaultLiftToneAudioClose", defaultLiftToneAudioClose);
        tag.putString("defaultLiftToneAudioChime", defaultLiftToneAudioOpen);
        // 【1.47】直梯提示音淡入淡出范围（四项共用）
        tag.putInt("defaultLiftHelpRound", defaultLiftHelpRound);
        // 【1.48】四项各自音量（-1 = 跟随共用默认）
        tag.putInt("defaultLiftToneVolumeUp", defaultLiftToneVolumeUp);
        tag.putInt("defaultLiftToneVolumeDown", defaultLiftToneVolumeDown);
        tag.putInt("defaultLiftToneVolumeOpen", defaultLiftToneVolumeOpen);
        tag.putInt("defaultLiftToneVolumeClose", defaultLiftToneVolumeClose);
        tag.putInt("defaultLiftToneVolumeChime", defaultLiftToneVolumeOpen);
        // 【1.45】直梯楼层轨道提示音（竖井列 → 四音频 id）
        ListTag toneList = new ListTag();
        for (Map.Entry<Long, LiftToneAudio> entry : liftToneAudio.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("key", entry.getKey());
            LiftToneAudio v = entry.getValue();
            t.putString("up", v.up);
            t.putString("down", v.down);
            t.putString("open", v.open);
            t.putString("close", v.close);
            toneList.add(t);
        }
        tag.put("liftToneAudio", toneList);
        // 【1.50】屏蔽门提示音（总开关 / 两项子开关 / 音量 / 范围 / 每扇门单独素材）
        tag.putBoolean("defaultPsdHelp", defaultPsdHelp);
        tag.putBoolean("defaultPsdToneOpenEnabled", defaultPsdToneOpenEnabled);
        tag.putBoolean("defaultPsdToneCloseEnabled", defaultPsdToneCloseEnabled);
        tag.putInt("defaultPsdHelpVolume", defaultPsdHelpVolume);
        tag.putInt("defaultPsdToneVolumeOpen", defaultPsdToneVolumeOpen);
        tag.putInt("defaultPsdToneVolumeClose", defaultPsdToneVolumeClose);
        // 【09-30】闸机（进站 / 出站）提示音：维度默认素材 + 音量。
        //   ★ 只有这一层（没有「按方块单独设置」那张表）—— 见字段注释里的理由。
        tag.putString("defaultZhajiToneAudioIn", defaultZhajiToneAudioIn);
        tag.putString("defaultZhajiToneAudioOut", defaultZhajiToneAudioOut);
        tag.putInt("defaultZhajiToneVolumeIn", defaultZhajiToneVolumeIn);
        tag.putInt("defaultZhajiToneVolumeOut", defaultZhajiToneVolumeOut);
        // 【09-30 续】闸机「一组闸机」表：ListTag，每个元素 `{ "k": <long 组锚点 asLong>,
        //   "ain": <str>, "aout": <str>, "vin": <int>, "vout": <int> }`。
        //   ★ 音量**只有非 null 才写**（缺键 = 跟维度默认）—— 不用 -1 哨兵，
        //     免得跟 LIFT_TONE_VOLUME_UNSET 那个「跟随共用默认」的语义撞在一起。
        ListTag zhajiToneList = new ListTag();
        for (Map.Entry<Long, ZhajiTone> entry : zhajiTone.entrySet()) {
            ZhajiTone v = entry.getValue();
            if (v == null || v.isEmpty()) {
                continue; // 等于没设置过：不落盘（表里不留空条目）
            }
            CompoundTag t = new CompoundTag();
            t.putLong("k", entry.getKey());
            t.putString("ain", normalizeZhajiToneAudio(v.audioIn()));
            t.putString("aout", normalizeZhajiToneAudio(v.audioOut()));
            if (v.volumeIn() != null) {
                t.putInt("vin", clampZhajiVolume(v.volumeIn()));
            }
            if (v.volumeOut() != null) {
                t.putInt("vout", clampZhajiVolume(v.volumeOut()));
            }
            zhajiToneList.add(t);
        }
        tag.put("zhajiTone", zhajiToneList);
        // 【09-29】三类的可闻范围拆成水平/垂直两维。旧单值旧键**仍写**（= 水平值），
        //   供将来回退 / 老版本读档不被静默丢弃（编写与 TrainAnnounceSwitch 的 KEY_ROUND 同款）。
        tag.putInt("defaultPsdHelpRoundXz", defaultPsdHelpRoundXz);
        tag.putInt("defaultPsdHelpRoundY", defaultPsdHelpRoundY);
        tag.putInt("defaultPsdHelpRound", defaultPsdHelpRoundXz);
        // 【1.23】到站 / 进站播报各自的可闻范围（格）——与上面那一格同形，各存一份
        tag.putInt("defaultPsdMidiumRoundXz", defaultPsdMidiumRoundXz);
        tag.putInt("defaultPsdMidiumRoundY", defaultPsdMidiumRoundY);
        tag.putInt("defaultPsdMidiumRound", defaultPsdMidiumRoundXz);
        tag.putInt("defaultPsdArriveRoundXz", defaultPsdArriveRoundXz);
        tag.putInt("defaultPsdArriveRoundY", defaultPsdArriveRoundY);
        tag.putInt("defaultPsdArriveRound", defaultPsdArriveRoundXz);
        // 【1.16】关门提示音强制等待时长（秒）——停站不够放完人声时的兜底
        tag.putInt("defaultPsdCloseWaitSeconds", defaultPsdCloseWaitSeconds);
        // 【1.17】到站播报（素材 + 等待秒数）
        tag.putString("defaultPsdMidiumAudio", defaultPsdMidiumAudio);
        tag.putInt("defaultPsdMidiumWaitSeconds", defaultPsdMidiumWaitSeconds);
        tag.putInt("defaultPsdMidiumVolume", defaultPsdMidiumVolume);
        // 【1.21】进站报站（素材 + 秒数）
        tag.putString("defaultPsdArriveAudio", defaultPsdArriveAudio);
        tag.putInt("defaultPsdArriveSeconds", defaultPsdArriveSeconds);
        tag.putInt("defaultPsdArriveVolume", defaultPsdArriveVolume);
        // 【09-28】讲述人报站的维度默认（读侧同名同型）
        tag.putInt("defaultPsdNarrateMode", defaultPsdNarrateMode);
        tag.putInt("defaultPsdNarrateSeconds", defaultPsdNarrateSeconds);
        // 【09-30 续 3】「站台广播（讲述人）」的维度默认（读侧同名同型）
        tag.putInt("defaultPsdMidiumNarrateMode", defaultPsdMidiumNarrateMode);
        tag.putInt("defaultPsdMidiumNarrateSeconds", defaultPsdMidiumNarrateSeconds);
        // 【10-01】两条讲述人广播的「玩家自定义文字」（按存档存；读侧同名同型，见 fromTag）
        tag.put("defaultPsdArriveNarrateUserTexts", writeNarrateUserTexts(defaultPsdArriveNarrateUserTexts));
        tag.put("defaultPsdMidiumNarrateUserTexts", writeNarrateUserTexts(defaultPsdMidiumNarrateUserTexts));
        // 【1.15】屏蔽门两项的维度默认素材（/pbmmusic open|close <名字>）
        tag.putString("defaultPsdToneAudioOpen", defaultPsdToneAudioOpen);
        tag.putString("defaultPsdToneAudioClose", defaultPsdToneAudioClose);
        ListTag psdList = new ListTag();
        for (Map.Entry<Long, PsdToneAudio> entry : psdToneAudio.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("key", entry.getKey());
            PsdToneAudio v = entry.getValue();
            t.putString("open", v.open);
            t.putString("close", v.close);
            // 【1.20】这一扇门的其余覆盖项：**只写设过的**（null = 跟维度默认 ⇒ 一个键都不写，
            //   老版本读这份存档时看到的就是一条「只有素材」的记录，行为不变）。
            putOptBool(t, "help", v.help());
            putOptBool(t, "openEnabled", v.openEnabled());
            putOptBool(t, "closeEnabled", v.closeEnabled());
            putOptInt(t, "volume", v.volume());
            putOptInt(t, "openVolume", v.openVolume());
            putOptInt(t, "closeVolume", v.closeVolume());
            putOptInt(t, "openWaitSeconds", v.openWaitSeconds());
            putOptInt(t, "closeWaitSeconds", v.closeWaitSeconds());
            putOptString(t, "midium", v.midium());
            putOptInt(t, "midiumWaitSeconds", v.midiumWaitSeconds());
            putOptInt(t, "midiumVolume", v.midiumVolume());
            putOptString(t, "arrive", v.arrive());
            putOptInt(t, "arriveSeconds", v.arriveSeconds());
            putOptInt(t, "arriveVolume", v.arriveVolume());
            // 【09-28】进站广播（讲述人）：开关 + 秒数（null = 跟维度默认 ⇒ 一个键都不写）
            putOptInt(t, "narrate", v.narrate());
            putOptInt(t, "narrateSeconds", v.narrateSeconds());
            // 【09-30 续 3】站台广播（讲述人）：同一套档位 + 等待秒数
            putOptInt(t, "midiumNarrate", v.midiumNarrate());
            putOptInt(t, "midiumNarrateSeconds", v.midiumNarrateSeconds());
            psdList.add(t);
        }
        tag.put("psdToneAudio", psdList);
        return tag;
    }

    /** 【1.16】无障碍提示音开关：该扶梯**生效**是否播放提示音（单独设置优先，其次维度默认）。 */
    public boolean isHelpEnabled(BlockPos pos) {
        Boolean own = blockHelp.get(pos);
        return own != null ? own : defaultHelp;
    }

    /** 【1.16】该扶梯方块**单独设置**的提示音开关；没单独设置返回 null（用维度默认值）。 */
    public Boolean getIndividualHelp(BlockPos pos) {
        return blockHelp.get(pos);
    }

    /**
     * 【1.16】设置某个扶梯方块的提示音开关。
     * 值等于**维度默认值**（{@link #defaultHelp}）时**删掉记录**，让 NBT 只保留真正被单独设置过的扶梯。
     */
    public void setHelp(BlockPos pos, boolean enabled) {
        if (enabled == defaultHelp) {
            blockHelp.remove(pos);
        } else {
            blockHelp.put(pos, enabled);
        }
    }


    private static CompoundTag writeBoolMap(Map<BlockPos, Boolean> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Boolean> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putBoolean(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    /** 【1.16】读「方块 → 提示音开关」；键不是合法坐标的条目直接跳过。 */
    /**
     * 【1.20】「可选字段」的 NBT 读写小工具：{@code null} = 没设过（不写、读回 null）。
     *
     * <p>为什么单拎出来：{@link PsdToneAudio} 有 9 个覆盖项，逐个 {@code contains} 判断写出来
     * 是一坨；而且「写的时候 null 就不写」与「读的时候没有这个键就是 null」必须是**同一套规定**，
     * 分开写两遍迟早会歪掉一处（本仓「写读成对」那条规矩的落地形态）。
     */
    private static void putOptBool(CompoundTag tag, String key, Boolean v) {
        if (v != null) {
            tag.putBoolean(key, v);
        }
    }

    private static void putOptInt(CompoundTag tag, String key, Integer v) {
        if (v != null) {
            tag.putInt(key, v);
        }
    }

    private static void putOptString(CompoundTag tag, String key, String v) {
        if (v != null) {
            tag.putString(key, v);
        }
    }

    private static Boolean optBool(CompoundTag tag, String key) {
        return tag.contains(key) ? tag.getBoolean(key) : null;
    }

    private static Integer optInt(CompoundTag tag, String key) {
        return tag.contains(key) ? tag.getInt(key) : null;
    }

    /**
     * 【09-28 续】讲述人**样式**的可选读：没有这个键 ⇒ {@code null}（= 跟维度默认）；
     * 有就先过 {@link #clampPsdNarrateMode}（手改过的存档 / 将来样式变多/变少都能收住）。
     *
     * <p>★★ 关键的一格：**1.28.1204 那一版存的是布尔**（NBT 是 byte 类型）。
     * 直接 {@code getInt} 会因为类型不是 int 而**静默返回 0**（= 把「开启」读成「关闭」），
     * 所以这里先看类型：byte ⇒ 按「true = 开启(上海) / false = 关闭」搬过来（那时只有一档），
     * 这样旧档升级后行为**逐字不变**，不需要任何迁移。
     */
    private static Integer optNarrateMode(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        if (tag.contains(key, 1)) {
            // 1 = NBT byte（老档的 boolean）
            return tag.getBoolean(key) ? PSD_NARRATE_SHANGHAI : PSD_NARRATE_OFF;
        }
        return clampPsdNarrateMode(tag.getInt(key));
    }

    private static String optString(CompoundTag tag, String key) {
        return tag.contains(key) ? tag.getString(key) : null;
    }

    private static Map<BlockPos, Boolean> readBoolMap(CompoundTag compound) {
        Map<BlockPos, Boolean> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, compound.getBoolean(key));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 【1.18】无障碍提示音音量（1~1000，100 = 原始音量）
    //
    //   与上面的 blockHelp（开关）成对：开关决定“响不响”，音量决定“多响”。
    //   注意与 blockVolume（扶梯**运行底噪**的音量）是两套互不影响的数据。
    // ------------------------------------------------------------------

    /** 【1.18】把任意输入夹到合法提示音音量范围（与音频音量同一区间 1~1000）。 */
    public static int clampHelpVolume(int volume) {
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
    }

    /** 【1.18】该扶梯方块的提示音音量；没单独设置过就是维度默认（{@link #defaultHelpVolume}，初始 100）。 */
    public int getHelpVolume(BlockPos pos) {
        Integer v = blockHelpVolume.get(pos);
        return v != null ? v : defaultHelpVolume;
    }

    /** 【1.18】该扶梯方块**单独设置**的提示音音量；没单独设置返回 null（用维度默认音量）。 */
    public Integer getIndividualHelpVolume(BlockPos pos) {
        return blockHelpVolume.get(pos);
    }

    /**
     * 【1.18】设置某个扶梯方块的提示音音量。
     * 值等于**维度默认音量**（{@link #defaultHelpVolume}）时**删掉记录**，让 NBT 只保留真正被调过的扶梯。
     */
    public void setHelpVolume(BlockPos pos, int volume) {
        int v = clampHelpVolume(volume);
        if (v == defaultHelpVolume) {
            blockHelpVolume.remove(pos);
        } else {
            blockHelpVolume.put(pos, v);
        }
    }

    /** 【1.18】读「方块 → 提示音音量」，顺手把越界值夹回（防止手改 NBT 后出怪值）。 */
    private static Map<BlockPos, Integer> readHelpVolumeMap(CompoundTag compound) {
        Map<BlockPos, Integer> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clampHelpVolume(compound.getInt(key)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 【1.24】两个「淡入淡出范围」（单位：格）
    //
    //   底噪（/futiround，默认 16 格）与提示音（/futihelpround，默认 4 格）是**两套**数据，
    //   与上面两套音量一一对应，互不影响：
    //     音量决定「多响」，范围决定「多远还听得见」。
    //   注意 1.24 **没给它们加石斧界面控件**，所以「单独设置」这一层目前只能由
    //   `-f <X> to <Y>` 间接产生；数据模型与其它 7 个可调项保持完全一致，方便以后再补 UI。
    // ------------------------------------------------------------------

    /** 【1.24】把任意输入夹到合法**底噪范围**（1~128 格）。 */
    public static int clampRound(int round) {
        return Math.max(ROUND_MIN, Math.min(ROUND_MAX, round));
    }

    /** 【1.24】把任意输入夹到合法**提示音范围**（1~128 格）。 */
    public static int clampHelpRound(int round) {
        return Math.max(ROUND_MIN, Math.min(ROUND_MAX, round));
    }

    /** 【1.24】这条扶梯运行底噪的生效范围；没单独设置过就是维度默认（初始 16 格）。 */
    public int getRound(BlockPos pos) {
        Integer v = blockRound.get(pos);
        return v != null ? v : defaultRound;
    }

    /** 【1.24】这条扶梯**单独设置**的底噪范围；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualRound(BlockPos pos) {
        return blockRound.get(pos);
    }

    /**
     * 【1.24】设置某条扶梯运行底噪的范围。
     * 值等于**维度默认**（{@link #defaultRound}）时**删掉记录**，让 NBT 只保留真正被调过的扶梯。
     */
    public void setRound(BlockPos pos, int round) {
        int v = clampRound(round);
        if (v == defaultRound) {
            blockRound.remove(pos);
        } else {
            blockRound.put(pos, v);
        }
    }

    /** 【1.24】这条扶梯无障碍提示音的生效范围；没单独设置过就是维度默认（初始 4 格）。 */
    public int getHelpRound(BlockPos pos) {
        Integer v = blockHelpRound.get(pos);
        return v != null ? v : defaultHelpRound;
    }

    /** 【1.24】这条扶梯**单独设置**的提示音范围；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualHelpRound(BlockPos pos) {
        return blockHelpRound.get(pos);
    }

    /** 【1.24】设置某条扶梯无障碍提示音的范围；等于维度默认时删掉记录。 */
    public void setHelpRound(BlockPos pos, int round) {
        int v = clampHelpRound(round);
        if (v == defaultHelpRound) {
            blockHelpRound.remove(pos);
        } else {
            blockHelpRound.put(pos, v);
        }
    }

    // ------------------------------------------------------------------
    // 【1.31】无障碍提示音的**速率**（每秒响几次，单位 Hz）
    //
    //   与「上客端 / 落客端」一一对应，所以是**两套**数据：
    //     /futihelpspeed in  <Hz> -> defaultHelpSpeedIn  / blockHelpSpeedIn
    //     /futihelpspeed out <Hz> -> defaultHelpSpeedOut / blockHelpSpeedOut
    //   与开关 / 音量 / 范围一样只记录与默认**不同**的项。
    //   实现上「速率」= 换素材 + 调 pitch（见 EscalatorChimePlayer），
    //   所以默认的 10 / 1 Hz 正好是素材原始速率，等于没改。
    // ------------------------------------------------------------------

    /** 【1.31】把任意输入夹到合法**提示音速率**（{@link #HELP_SPEED_MIN}~{@link #HELP_SPEED_MAX} Hz）。 */
    public static int clampHelpSpeed(int speed) {
        return Math.max(HELP_SPEED_MIN, Math.min(HELP_SPEED_MAX, speed));
    }

    /**
     * 【09-30】闸机素材的归一化 —— 与直梯 / 屏蔽门**同一份实现**（空串 / 未知 → 内置）。
     *
     * <p>三个域（扶梯 / 直梯 / 屏蔽门 / 闸机）共用同一个字符串哨兵（{@code default} / {@code off}），
     * 所以归一化也共用一份；这里只留一个**按域命名**的别名，读代码时不必跳去查「这到底是哪个域的」。
     */
    public static String normalizeZhajiToneAudio(String audioId) {
        return normalizeLiftToneAudio(audioId);
    }

    /**
     * 【09-30】把任意输入夹到合法**闸机提示音音量**（1~1000）。
     *
     * <p>与直梯 / 屏蔽门同一量程、同一实现（100 = 原始音量、1000 = 10× 放大，见
     * {@link #clampLiftToneVolume}）。名称按域分开只是为了读起来清楚，语义完全一致。
     *
     * <p>★ 唯一的差别：**不**放行 {@link #LIFT_TONE_VOLUME_UNSET}（-1）。那个哨兵的含义是
     * 「跟随共用默认音量」，而闸机没有共用默认音量这一层（进 / 出站各自一份、缺省 100）——
     * 放行它会让一个损坏存档里的 -1 一路传到播放端算出负音量。
     */
    public static int clampZhajiVolume(int volume) {
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
    }

    /**
     * 【1.42】把任意输入夹到合法**直梯提示音倍速**
     * （{@link #LIFT_HELP_SPEED_MIN}~{@link #LIFT_HELP_SPEED_MAX}）。
     *
     * <p>非有限值（NaN / ±Inf）一律当作 {@link #DEFAULT_LIFT_HELP_SPEED} 处理 ——
     * {@code Math.max/min} 遇到 NaN 会把 NaN 原样传下去，而一个 NaN 的 pitch 会让
     * OpenAL 那一路直接失效（表现是「设完之后再也没声音」），必须在这里堵掉。
     */
    public static float clampLiftHelpSpeed(float speed) {
        if (Float.isNaN(speed) || Float.isInfinite(speed)) {
            return DEFAULT_LIFT_HELP_SPEED;
        }
        return Math.max(LIFT_HELP_SPEED_MIN, Math.min(LIFT_HELP_SPEED_MAX, speed));
    }

    /**
     * 【1.43】把任意输入夹到合法**直梯提示音音量**
     * （{@link #HELP_VOLUME_MIN}~{@link #HELP_VOLUME_MAX}，即 1~1000）。
     *
     * <p>和 {@link #clampHelpVolume} 是同一套区间，只是各自对应一套独立数据。
     * 夹在数据层而不是只靠指令参数类型，是为了让**存档里被外部改坏的值**（NBT 手改、
     * 旧版本写进来的越界值）在装载时就被纠正 —— 否则一个 100000 会被原版音量夹取
     * 悄悄压回上限，玩家看到的是「填了没用」而不是报错。
     */
    public static int clampLiftHelpVolume(int volume) {
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
    }

    /** 【1.47】直梯提示音淡入淡出范围：夹到 [1, 128]（复用扶梯那组范围常量）。 */
    public static int clampLiftHelpRound(int round) {
        return Math.max(LIFT_HELP_ROUND_MIN, Math.min(LIFT_HELP_ROUND_MAX, round));
    }

    /**
     * 【1.48】单项音量的夹取：{@link #LIFT_TONE_VOLUME_UNSET}（-1 = 跟随共用默认）原样放行，
     * 其余夹到 [{@link #HELP_VOLUME_MIN}, {@link #HELP_VOLUME_MAX}]（1~1000）。
     */
    public static int clampLiftToneVolume(int volume) {
        if (volume == LIFT_TONE_VOLUME_UNSET) {
            return LIFT_TONE_VOLUME_UNSET;
        }
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
    }

    /**
     * 【1.15】把存档里读到的**维度默认素材**规范化一下：
     * {@code null} / 空串一律当成 {@link #LIFT_TONE_DEFAULT}（跟上一层 = 内置素材）。
     *
     * <p>「这个文件名在不在库里」**不在这里判**（读 NBT 时音频库还没装配完，而且删音频
     * 那一路由 {@link #removeAudio} 负责把引用清干净），这里只保证「不会是个空值」——
     * 空值会让播放端走进「既不是 default 也不是 off 的文件名」那一支，表现是静默不响。
     */
    public static String normalizeLiftToneAudio(String audioId) {
        return (audioId == null || audioId.isEmpty()) ? LIFT_TONE_DEFAULT : audioId;
    }

    /** 【1.31】这条扶梯**上客端（进入扶梯）**提示音的生效速率（Hz）；没单独设置过就是维度默认（初始 10）。 */
    public int getHelpSpeedIn(BlockPos pos) {
        Integer v = blockHelpSpeedIn.get(pos);
        return v != null ? v : defaultHelpSpeedIn;
    }

    /** 【1.31】这条扶梯**单独设置**的上客端速率；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualHelpSpeedIn(BlockPos pos) {
        return blockHelpSpeedIn.get(pos);
    }

    /** 【1.31】设置某条扶梯上客端提示音速率；等于维度默认时删掉记录。 */
    public void setHelpSpeedIn(BlockPos pos, int speed) {
        int v = clampHelpSpeed(speed);
        if (v == defaultHelpSpeedIn) {
            blockHelpSpeedIn.remove(pos);
        } else {
            blockHelpSpeedIn.put(pos, v);
        }
    }

    /** 【1.31】这条扶梯**落客端（离开扶梯）**提示音的生效速率（Hz）；没单独设置过就是维度默认（初始 1）。 */
    public int getHelpSpeedOut(BlockPos pos) {
        Integer v = blockHelpSpeedOut.get(pos);
        return v != null ? v : defaultHelpSpeedOut;
    }

    /** 【1.31】这条扶梯**单独设置**的落客端速率；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualHelpSpeedOut(BlockPos pos) {
        return blockHelpSpeedOut.get(pos);
    }

    /** 【1.31】设置某条扶梯落客端提示音速率；等于维度默认时删掉记录。 */
    public void setHelpSpeedOut(BlockPos pos, int speed) {
        int v = clampHelpSpeed(speed);
        if (v == defaultHelpSpeedOut) {
            blockHelpSpeedOut.remove(pos);
        } else {
            blockHelpSpeedOut.put(pos, v);
        }
    }

    /** 【1.31】读「方块 → 上客端速率」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readHelpSpeedInMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampHelpSpeed);
    }

    /** 【1.31】读「方块 → 落客端速率」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readHelpSpeedOutMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampHelpSpeed);
    }

    /** 【1.24】读「方块 → 底噪范围」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readRoundMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampRound);
    }

    /** 【1.24】读「方块 → 提示音范围」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readHelpRoundMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampHelpRound);
    }

    /** 「方块 → 整数」通用读取：键不是合法坐标的条目跳过，值按给定规则夹取。 */
    private static Map<BlockPos, Integer> readClampedIntMap(CompoundTag compound, java.util.function.IntUnaryOperator clamp) {
        Map<BlockPos, Integer> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clamp.applyAsInt(compound.getInt(key)));
            }
        }
        return out;
    }

    /** 该扶梯方块是否绑定了自定义音频。 */
    public boolean hasAudio(BlockPos pos) {
        return blockAudio.containsKey(pos);
    }

    /** 返回该扶梯方块绑定的音频ID；未绑定时返回 null。 */
    public String getAudioId(BlockPos pos) {
        return blockAudio.get(pos);
    }

    /** 把音频ID绑定到扶梯方块。存在性校验由调用方（EscalatorSpeedManager）负责。 */
    public void bindAudio(BlockPos pos, String audioId) {
        if (audioId != null) {
            blockAudio.put(pos, audioId);
        }
    }

    /** 解绑扶梯方块的音频（之后该扶梯静音）。 */
    public void unbindAudio(BlockPos pos) {
        blockAudio.remove(pos);
    }

    /** 删除音频库中的一份音频，同时解绑所有引用它的扶梯（运行底噪与无障碍提示音两套引用一起解）。 */
    public void removeAudio(String audioId) {
        audioLibrary.remove(audioId);
        // 【1.28】分类注册表里也一起摘掉（名字不再属于任何分类 ⇒ 各界面的「已存入」列表不再显示）。
        for (Set<String> names : audioCategoryNames.values()) {
            names.remove(audioId);
        }
        blockAudio.entrySet().removeIf(entry -> entry.getValue().equals(audioId));
        blockHelpAudioIn.entrySet().removeIf(entry -> entry.getValue().equals(audioId));
        blockHelpAudioOut.entrySet().removeIf(entry -> entry.getValue().equals(audioId));
        // 【1.45】直梯楼层轨道提示音也可能引用过这段（共用同一个音频库）：引用它的那一项
        //   退化成「默认素材」而不是留着指向已删除的文件（否则播放端查到库里没有 → 静默不响）。
        //   ★ LiftToneAudio 是 record（字段 final），不能改字段，只能整体替换。
        Map<Long, LiftToneAudio> tones = new HashMap<>();
        for (Map.Entry<Long, LiftToneAudio> entry : liftToneAudio.entrySet()) {
            LiftToneAudio t = entry.getValue();
            String up = audioId.equals(t.up()) ? LIFT_TONE_DEFAULT : t.up();
            String down = audioId.equals(t.down()) ? LIFT_TONE_DEFAULT : t.down();
            String open = audioId.equals(t.open()) ? LIFT_TONE_DEFAULT : t.open();
            String close = audioId.equals(t.close()) ? LIFT_TONE_DEFAULT : t.close();
            if (!(LIFT_TONE_DEFAULT.equals(up) && LIFT_TONE_DEFAULT.equals(down)
                    && LIFT_TONE_DEFAULT.equals(open) && LIFT_TONE_DEFAULT.equals(close))) {
                tones.put(entry.getKey(), new LiftToneAudio(up, down, open, close));
            }
        }
        liftToneAudio.clear();
        liftToneAudio.putAll(tones);
        // 【1.15】维度默认素材也不能留着指向已删除的文件（否则播放端查到库里没有 → 静默不响）。
        if (audioId.equals(defaultLiftToneAudioUp)) {
            defaultLiftToneAudioUp = LIFT_TONE_DEFAULT;
        }
        if (audioId.equals(defaultLiftToneAudioDown)) {
            defaultLiftToneAudioDown = LIFT_TONE_DEFAULT;
        }
        if (audioId.equals(defaultLiftToneAudioOpen)) {
            defaultLiftToneAudioOpen = LIFT_TONE_DEFAULT;
        }
        if (audioId.equals(defaultLiftToneAudioClose)) {
            defaultLiftToneAudioClose = LIFT_TONE_DEFAULT;
        }
        // 【1.15】屏蔽门那一套共用**同一个**音频库，所以也要一起清：
        //   ★ 1.50 漏了这一步 —— 删掉一段音频后，引用它的那扇门会留着一个指向不存在文件的 id，
        //     播放端查库查不到 ⇒ 那扇门静默不响（同一个「悬空引用」家族，直梯那边有做）。
        Map<Long, PsdToneAudio> psdTones = new HashMap<>();
        for (Map.Entry<Long, PsdToneAudio> entry : psdToneAudio.entrySet()) {
            PsdToneAudio t = entry.getValue();
            // ★【1.20】必须用 withTone 改那两项，**不能**再 new 一条只有素材的记录：
            //   这一条现在还带着这扇门的开关 / 音量 / 强制等待 / 到站播报，
            //   重建会把它们一起抹掉（症状：删一段不相干的音频，某扇门的音量设置凭空回到默认）。
            if (audioId.equals(t.open())) {
                t = t.withTone("open", PSD_TONE_DEFAULT);
            }
            if (audioId.equals(t.close())) {
                t = t.withTone("close", PSD_TONE_DEFAULT);
            }
            // 【1.20】这扇门的「到站播报」也可能正指着这一段 —— 不清掉就留下一条指向空文件的引用
            //   （null = 跟维度默认；维度默认那一层由 EscalatorSpeedManager 那边一并清）。
            if (audioId.equals(t.midium())) {
                t = t.withMidium(null);
            }
            // 【1.21】这扇门的「进站报站」同理。
            if (audioId.equals(t.arrive())) {
                t = t.withArrive(null);
            }
            if (!t.isEmpty()) {
                psdTones.put(entry.getKey(), t);
            }
        }
        psdToneAudio.clear();
        psdToneAudio.putAll(psdTones);
        // 维度默认素材同理（否则两个端别一起静默）。
        if (audioId.equals(defaultPsdToneAudioOpen)) {
            defaultPsdToneAudioOpen = PSD_TONE_DEFAULT;
        }
        if (audioId.equals(defaultPsdToneAudioClose)) {
            defaultPsdToneAudioClose = PSD_TONE_DEFAULT;
        }
        // 【1.21】进站报站的维度默认层（素材没有内置兜底，回落成 off）。
        if (audioId.equals(defaultPsdArriveAudio)) {
            defaultPsdArriveAudio = PSD_ARRIVE_OFF;
        }
        // 【09-30】闸机那一套也共用**同一个**音频库 ⇒ 一起清。
        //   ★ 与直梯 / 屏蔽门同一个「悬空引用」家族：不清的话播放端查库查不到 → 该侧静默不响，
        //     而用户看到的是「我明明设了声音」。闸机同侧只有一份，所以两行就够。
        if (audioId.equals(defaultZhajiToneAudioIn)) {
            defaultZhajiToneAudioIn = ZHAJI_TONE_DEFAULT;
        }
        if (audioId.equals(defaultZhajiToneAudioOut)) {
            defaultZhajiToneAudioOut = ZHAJI_TONE_DEFAULT;
        }
        // 【09-30 续】逐组那一层同理：把引用了这段素材的组条目里那一侧回落成「跟维度默认」。
        //   与屏蔽门 psdToneAudio 的清理同一套写法（先收集再整体换，边遍历边改会 CME）。
        Map<Long, ZhajiTone> zhajiTones = new HashMap<>();
        for (Map.Entry<Long, ZhajiTone> entry : zhajiTone.entrySet()) {
            ZhajiTone t = entry.getValue();
            if (t == null) {
                continue;
            }
            if (audioId.equals(t.audioIn())) {
                t = t.withAudio("in", ZHAJI_TONE_DEFAULT);
            }
            if (audioId.equals(t.audioOut())) {
                t = t.withAudio("out", ZHAJI_TONE_DEFAULT);
            }
            if (!t.isEmpty()) {
                zhajiTones.put(entry.getKey(), t);
            }
        }
        zhajiTone.clear();
        zhajiTone.putAll(zhajiTones);
    }

    /** 【1.18.1204】删除图片库中的一份地图图片（只删存档里的，不动 MBM_Picture 文件夹的文件）。 */
    public void removePicture(String pictureId) {
        pictureLibrary.remove(pictureId);
    }

    /**
     * 【1.45】一条直梯的三项提示音设置（可独立选择各自素材，互不冲突）。
     *
     * <p>每个字段的取值语义见 {@link #liftToneAudio}：{@link #LIFT_TONE_DEFAULT} 内置素材 /
     * {@link #LIFT_TONE_OFF} 不播 / 其它 = 音频库文件名。
     */
    /**
     * 【1.45】一扇直梯（竖井列）的**四项提示音素材**（up / down / open / close）。
     *
     * <p>每个字段的取值语义见 {@link #liftToneAudio}：{@link #LIFT_TONE_DEFAULT} 内置素材 /
     * {@link #LIFT_TONE_OFF} 不播 / 其它 = 音频库文件名。
     * 【1.28】原来的 {@code chime}（开关门一体）拆成 {@code open}（开门）与 {@code close}（关门）
     * 两路，各设各的素材、各开各的子开关、各调各的音量。
     */
    public record LiftToneAudio(String up, String down, String open, String close) {
        public static final LiftToneAudio NONE = new LiftToneAudio(
                LIFT_TONE_DEFAULT, LIFT_TONE_DEFAULT, LIFT_TONE_DEFAULT, LIFT_TONE_DEFAULT);
    }

    /**
     * 【09-30 续】石斧右键**那一组闸机**的提示音（一组 = 连着的、同一功能的那些闸机）。
     *
     * <p>字段语义与 {@link PsdToneAudio} 一一对应（闸机只有「进站 / 出站」两项 + 各自音量）：
     * <ul>
     *   <li>素材：{@link #ZHAJI_TONE_DEFAULT} = 本组**跟维度默认**；
     *       {@link #ZHAJI_TONE_OFF} = 本组不播；其它 = 音频库文件名；</li>
     *   <li>音量：装箱 {@code Integer} 的 {@code null} = 本组**跟维度默认**。</li>
     * </ul>
     *
     * <p>★ 为什么不给素材也用「装箱 null」表达「跟」：{@code default} 是**对外语义词**
     * （指令里玩家能写、界面第 1 行就叫「默认」），换掉它会牵动指令解析与两处界面；
     * 这是屏蔽门【1.20】就定下的口径，闸机照抄以免多一套语义（见 {@link PsdToneAudio} 的说明）。
     */
    public record ZhajiTone(String audioIn, String audioOut, Integer volumeIn, Integer volumeOut) {
        /** 一组闸机**什么都没单独设过**（两项素材与两个音量都跟维度默认）。 */
        public static final ZhajiTone NONE = new ZhajiTone(
                ZHAJI_TONE_DEFAULT, ZHAJI_TONE_DEFAULT, null, null);

        /** 四项都等于「跟维度默认」= 等于没设置过（表里这一条可以删掉，表越干净越好查）。 */
        public boolean isEmpty() {
            return (audioIn == null || ZHAJI_TONE_DEFAULT.equals(audioIn))
                    && (audioOut == null || ZHAJI_TONE_DEFAULT.equals(audioOut))
                    && volumeIn == null && volumeOut == null;
        }

        /** 这一组的某一侧素材（原样，不做回落 —— 回落由 Manager 那两层读法负责）。 */
        public String audioFor(String which) {
            return "out".equals(which) ? audioOut : audioIn;
        }

        /** 这一组的某一侧音量（{@code null} = 跟维度默认）。 */
        public Integer volumeFor(String which) {
            return "out".equals(which) ? volumeOut : volumeIn;
        }

        /** 换掉某一侧的素材（另一侧与两个音量原样保留）。 */
        public ZhajiTone withAudio(String which, String id) {
            return "out".equals(which)
                    ? new ZhajiTone(audioIn, id, volumeIn, volumeOut)
                    : new ZhajiTone(id, audioOut, volumeIn, volumeOut);
        }

        /** 换掉某一侧的音量（{@code null} = 恢复「跟维度默认」）。 */
        public ZhajiTone withVolume(String which, Integer v) {
            return "out".equals(which)
                    ? new ZhajiTone(audioIn, audioOut, volumeIn, v)
                    : new ZhajiTone(audioIn, audioOut, v, volumeOut);
        }
    }

    /**
     * 【1.50】一扇屏蔽门的**全套单独设置**（素材 + 开关 + 音量 + 强制等待 + 到站播报）。
     *
     * <p>字段取值语义见 {@link #psdToneAudio}：{@link #PSD_TONE_DEFAULT} 内置素材 /
     * {@link #PSD_TONE_OFF} 这扇门不播 / 其它 = 音频库文件名。
     *
     * <h2>★【1.20】为什么这里从「只有 open/close」扩到 11 个字段</h2>
     * 用户点名：「石斧右键屏蔽门的 ui 里面修改的（要）全部都是玩家右键的连在一起的屏蔽门，
     * 而不是修改全部屏蔽门，只有指令才是修改全部」。在那之前，石斧 UI 里**除了素材**之外
     * 全是写**维度默认**（{@link #defaultPsdHelp} 那一族）⇒ 在甲门改了音量，乙门跟着变。
     * 现在 UI 改的每一项都落到**这扇门自己的这一条记录**上，维度默认那一层退化成的**回落层**，
     * 只由指令（{@code /pbmmusic} 不带 {@code -f}）写。
     *
     * <h2>★ 为什么是「装箱类型 + null」而不是哨兵值</h2>
     * {@code null} = <b>没单独设过 ⇒ 跟维度默认</b>，与 {@link #PSD_TONE_VOLUME_UNSET}（-1）
     * 那套哨兵是**两件事**，不要合并：{@code -1} 在「单项音量」这个字段里是**真实值**
     * （= 跟随共用默认），拿它兼任「没设过」会把「跟随共用默认」与「跟随维度」混成一句
     * （见本仓那条「一个哨兵同时表达两件事」的教训）。
     * 用装箱类型还顺便把「开关」的第三种状态（没设过）表达清楚了。
     *
     * <p>素材两项仍用 {@link #PSD_TONE_DEFAULT} 表达「跟」——那是 1.50 就定下的对外语义
     * （指令里能写 {@code default}），改它会牵动指令解析，所以两种「跟」并存是有意的。
     *
     * <h2>★【09-28 续】{@code narrate} 从 Boolean 改成三档 int</h2>
     * 石斧二级页现在有**三行**（关闭 / 开启(上海) / 开启(香港)），三档互斥
     * （{@link #PSD_NARRATE_OFF} / {@link #PSD_NARRATE_SHANGHAI} / {@link #PSD_NARRATE_HONGKONG}）。
     * 用一个 int 而不是「开关 + 样式」两个字段 —— 后者能拼出「关着但选了香港」这种自相矛盾的状态。
     * 语义仍是「{@code null} = 没单独设过 ⇒ 跟维度默认」，与其它装箱字段一致。
     */
    public record PsdToneAudio(String open, String close,
                               Boolean help, Boolean openEnabled, Boolean closeEnabled,
                               Integer volume, Integer openVolume, Integer closeVolume,
                               Integer openWaitSeconds, Integer closeWaitSeconds,
                               String midium, Integer midiumWaitSeconds, Integer midiumVolume,
                               String arrive, Integer arriveSeconds, Integer arriveVolume,
                               Integer narrate, Integer narrateSeconds,
                               Integer midiumNarrate, Integer midiumNarrateSeconds) {
        /** 一扇门**什么都没单独设过**（全部跟维度默认）。 */
        public static final PsdToneAudio NONE = new PsdToneAudio(
                PSD_TONE_DEFAULT, PSD_TONE_DEFAULT,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);

        /** 只带素材两项的构造（老调用点 / 老存档用）。 */
        public static PsdToneAudio tone(String open, String close) {
            return new PsdToneAudio(open, close,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
        }

        /** 这一条还有没有任何**实际内容**？全空 ⇒ 调用方应把这条记录删掉（表越干净越好查）。 */
        public boolean isEmpty() {
            return (open == null || PSD_TONE_DEFAULT.equals(open))
                    && (close == null || PSD_TONE_DEFAULT.equals(close))
                    && help == null && openEnabled == null && closeEnabled == null
                    && volume == null && openVolume == null && closeVolume == null
                    && openWaitSeconds == null && closeWaitSeconds == null
                    && midium == null && midiumWaitSeconds == null
                    && midiumVolume == null
                    && arrive == null && arriveSeconds == null && arriveVolume == null
                    && narrate == null && narrateSeconds == null
                    && midiumNarrate == null && midiumNarrateSeconds == null;
        }

        /** 换掉某一端的素材（另一项以及全部覆盖项原样保留）。 */
        public PsdToneAudio withTone(String which, String id) {
            return "open".equals(which) ? new PsdToneAudio(id, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds)
                    : new PsdToneAudio(open, id, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        public PsdToneAudio withHelp(Boolean v) {
            return new PsdToneAudio(open, close, v, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        public PsdToneAudio withToneEnabled(String which, Boolean v) {
            return "open".equals(which)
                    ? new PsdToneAudio(open, close, help, v, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds)
                    : new PsdToneAudio(open, close, help, openEnabled, v,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        public PsdToneAudio withVolume(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    v, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        public PsdToneAudio withToneVolume(String which, Integer v) {
            return "open".equals(which)
                    ? new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, v, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds)
                    : new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, v, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        /** 【1.23】开门提示音的「强制等待」秒数（对称项；播放端暂未消费，预留对称）。 */
        public PsdToneAudio withOpenWaitSeconds(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, v, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        public PsdToneAudio withCloseWaitSeconds(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, v, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        public PsdToneAudio withMidium(String v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, v, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        public PsdToneAudio withMidiumWaitSeconds(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, v, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        /** 【1.22】换掉这一串门「到站播报」的音量（-1 = 跟随共用默认）。 */
        public PsdToneAudio withMidiumVolume(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, v,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        /** 【1.21】换掉这扇门的「进站报站」素材（其它项原样保留）。 */
        public PsdToneAudio withArrive(String v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    v, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        /** 【1.21】换掉这一串门的「进站报站」秒数（其它项原样保留）。 */
        public PsdToneAudio withArriveSeconds(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, v, arriveVolume, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        /** 【1.22】换掉这一串门「进站报站」的音量（-1 = 跟随共用默认）。 */
        public PsdToneAudio withArriveVolume(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, v, narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        /**
         * 【1.63】换掉这一串门「讲述人进站广播」的**样式**（其它项原样保留）。
         *
         * <p>【09-28 续】三档互斥：{@link #PSD_NARRATE_OFF} / {@link #PSD_NARRATE_SHANGHAI} /
         * {@link #PSD_NARRATE_HONGKONG}；{@code null} = 抹掉这一串的覆盖 ⇒ 跟维度默认。
         */
        public PsdToneAudio withNarrate(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, v, narrateSeconds, midiumNarrate, midiumNarrateSeconds);
        }

        /** 【1.63】换掉这一串门「讲述人进站广播」的提前秒数（独立窗口，(-∞,0]）。 */
        public PsdToneAudio withNarrateSeconds(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, v, midiumNarrate, midiumNarrateSeconds);
        }

        /**
         * 【09-30 续 3】换掉这一串门「站台广播（讲述人）」的**样式**（其它项原样保留）。
         * 档位编号与进站讲述人共用同一套（{@link #PSD_NARRATE_OFF} / SHANGHAI / HONGKONG /
         * userN = 3+）；{@code null} = 抹掉这一串的覆盖 ⇒ 跟维度默认。
         */
        public PsdToneAudio withMidiumNarrate(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, v, midiumNarrateSeconds);
        }

        /** 【09-30 续 3】换掉这一串门「站台广播（讲述人）」的等待秒数（独立窗口，[0,+∞)）。 */
        public PsdToneAudio withMidiumNarrateSeconds(Integer v) {
            return new PsdToneAudio(open, close, help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds, midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume, narrate, narrateSeconds, midiumNarrate, v);
        }
    }

    // ------------------------------------------------------------------
    // 【1.41】无障碍提示音「音乐」（两层：维度默认 /futihelpmusic in|out + 每条扶梯单独设置）
    //
    // 与运行底噪那套（blockAudio / defaultAudio）**完全对称**，但数据独立：
    // 同一段导入的 OGG 可以「底噪播它、提示音也播它」，也可以只用在一边。
    // 「进入扶梯（上客端）」与「离开扶梯（落客端）」是**两套独立数据**（形状同 /futihelpspeed），
    // 所以每条扶梯最多 2 条记录（一头一条）。读取一律走 EscalatorSpeedManager 的顺链查找
    // （同一条扶梯上任意一块设过就整条算设过）。
    // ------------------------------------------------------------------

    /** 该扶梯方块是否**单独设置**过提示音音乐；{@code in} 为 true = 看进入扶梯（上客端）那一头。 */
    public boolean hasHelpAudio(BlockPos pos, boolean in) {
        return (in ? blockHelpAudioIn : blockHelpAudioOut).containsKey(pos);
    }

    /** 返回该扶梯方块**单独设置**的提示音音乐 ID；没单独设置返回 null（= 跟随维度默认）。 */
    public String getHelpAudioId(BlockPos pos, boolean in) {
        return (in ? blockHelpAudioIn : blockHelpAudioOut).get(pos);
    }

    /** 把提示音音乐 ID 单独设到该扶梯方块。存在性校验由调用方（EscalatorSpeedManager）负责。 */
    public void bindHelpAudio(BlockPos pos, String audioId, boolean in) {
        if (audioId != null) {
            (in ? blockHelpAudioIn : blockHelpAudioOut).put(pos, audioId);
        }
    }

    /** 清掉该扶梯方块的提示音音乐单独设置（回到维度默认）。 */
    public void unbindHelpAudio(BlockPos pos, boolean in) {
        (in ? blockHelpAudioIn : blockHelpAudioOut).remove(pos);
    }

    /** 取该维度的「单独设置」表：{@code in} 为 true = 进入扶梯那一头。 */
    public Map<BlockPos, String> helpAudioOverrides(boolean in) {
        return in ? blockHelpAudioIn : blockHelpAudioOut;
    }

    // ------------------------------------------------------------------
    // 【1.9】扶梯声音音量（1~1000，100 = 原始音量）
    // ------------------------------------------------------------------

    /** 把任意输入夹到合法音量范围 1~1000。 */
    public static int clampVolume(int volume) {
        return Math.max(AUDIO_VOLUME_MIN, Math.min(AUDIO_VOLUME_MAX, volume));
    }

    /** 该扶梯方块的声音音量；没单独设置过就是维度默认音量（{@link #defaultVolume}，初始 100）。 */
    public int getVolume(BlockPos pos) {
        Integer v = blockVolume.get(pos);
        return v != null ? v : defaultVolume;
    }

    /** 该扶梯方块**单独设置**的音量；没单独设置返回 null（用维度默认音量）。 */
    public Integer getIndividualVolume(BlockPos pos) {
        return blockVolume.get(pos);
    }

    /**
     * 设置某个扶梯方块的声音音量。
     * 值等于**维度默认音量**（{@link #defaultVolume}）时**删掉记录**，让 NBT 只保留真正被调过的扶梯
     * （含放大 &gt;100 的）。
     */
    public void setVolume(BlockPos pos, int volume) {
        int v = clampVolume(volume);
        if (v == defaultVolume) {
            blockVolume.remove(pos);
        } else {
            blockVolume.put(pos, v);
        }
    }

    private static CompoundTag writeIntMap(Map<BlockPos, Integer> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Integer> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putInt(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    private static CompoundTag writeDoubleMap(Map<BlockPos, Double> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Double> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putDouble(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    public static double clamp(double speed) {
        return Math.max(0.0, Math.min(MAX_SPEED, speed));
    }

    /** 把 2.0 显示成 2、1.5 显示成 1.5。 */
    public static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static BlockPos parsePos(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}