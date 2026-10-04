package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import smooth.lift.compat.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【1.50】MTR 屏蔽门（平台幕门 / 半高安全门）**开门 / 关门**提示音。
 *
 * <h2>怎么知道「门在开」还是「门在关」</h2>
 * 不看 MTR 的任何布尔量，只看**门开合程度的变化方向**（与 {@link LiftChimePlayer} 同一套判据）：
 * {@link PsdDoorTracker.DoorView#fraction()} 从「全开」（1.0）掉下来 = 开始关门；
 * 从「全关」（0.0）涨上去 = 开始开门。
 * 门值来自 {@link PsdDoorTracker}（挂在 MTR 渲染器必经的两个读口上，每帧每扇门回报一次），
 * 所以两个跳变点都是**单帧内可观测**的，不会漏。
 *
 * <p><b>事件 = 「门机开始动作」，不是「门走完了」。</b>真实屏蔽门的声音（解锁「咔」+ 放气「嘶」）
 * 就发生在起动那一瞬，之后是匀速滑完剩下的行程；等它滑到另一端才响，听感上会慢半拍。
 * 由此推出两个**刻意的**行为（{@code _tools/check-psd-anchor.py} 第 4 节把这两条钉住了）：
 * <ul>
 *   <li>只开一半又收回去（0→0.5→0）→ 响 1 次**开门**（门机确实起动过），不响关门；</li>
 *   <li>从中途（区块刚加载、门已经在 0.5）继续滑到全开 → **不响**（它没离开过全关那个静止位）。</li>
 * </ul>
 *
 * <p>「全开 / 全关」的容差取 {@value #EDGE}（而不是直梯那种 1e-3）有两个理由：
 * <ol>
 *   <li>MTR3 的门值是从 {@code open ∈ [0,32]} 反算出来的**阶梯**量，两端各差 0.1/32；
 *       3.125e-2 量级的台阶意味着「差不多全开」本身就有千分之几的抖动；</li>
 *   <li>容差只影响「早多少开始响」——0.02 对应约 1.6% 的门程，听感上仍在「门刚要动」那一瞬。</li>
 * </ol>
 *
 * <h2>【1.15】关门端：提示音的**结尾**要落在「门正好关上」那一刻</h2>
 * 用户原话（为这件事点名过两次）：「关门时要屏蔽门提示音播完那一刻屏蔽门正好关上，
 * 而不是屏蔽门开始关门才播放关门提示音」。
 *
 * <p><b>两条路都指向同一件事，别只想到其中一条。</b>关门素材 10.8 秒，而门自己只走 4 秒
 * （MTR4，量出来的，见下）。素材比门程长得多，于是：
 * <ul>
 *   <li><b>路 A（剪头）</b>：从第 {@code 素材时长 − 门程} 毫秒起播，余下的部分正好盖住整段门程，
 *       结尾自然落在门上。**一个数都不用猜**，适用于「只要嘀嘀」那一档
 *       （人声已经被剪掉，剪点就落在嘀嘀段里）；</li>
 *   <li><b>路 B（提前量）</b>：要让**整段**素材（人声一起）的**结尾**撞上门关上，
 *       起播就只能提前到 {@code 门关上 − 素材时长} —— 也就是**在门开始关之前约 6.8 秒**。
 *       这条路才保得住人声，代价是关门时刻必须**预测**（见下）。</li>
 * </ul>
 * 「起播 = 门开始关」（整段从关门那一瞬播）那一版被用户明确否掉过：门在第 4 秒就关上了，
 * 而提示音还剩 6.8 秒 —— 他要的是**声音的结尾**撞上**门关上**，不是声音的起点撞上门开始关。
 *
 * <h2>路 A：剪头 —— 「门还要走多久」是量出来的，不是一个写死的常数</h2>
 * 两个大版本的门速差一倍以上：
 * <ul>
 *   <li>MTR4（{@code BlockPSDAPGDoorBase$BlockEntityBase.tick(F)} 字节码，
 *       {@code doorValue += partialTick*20/3200*2}）⇒ 每 tick 走 1/80 ⇒ **80 tick = 4000ms**；</li>
 *   <li>MTR3（{@code TileEntityPSDAPGDoorBase.getOpen(F)} 字节码，{@code openClient} 每帧
 *       最多走 {@code 0.95*partialTick}，量纲是 {@code openClient/32}）⇒ 32/0.95 ≈ 33.7 tick
 *       ⇒ **约 1684ms**（服务端目标值走得更慢时以实测为准）。</li>
 * </ul>
 * 所以「门还要走多久」由**门值在两个 tick 之间的变化**现算：
 * {@code 剩余毫秒 = 当前门值 / 每 tick 门值变化 * 50}。
 *
 * <p><b>但要量出速度就必须等一个 tick，这会不会让声音晚出来？不会。</b>
 * 第 1 tick 只记下（门值 f₀，时刻 t₀）；第 2 tick 得到 f₁ 与 Δ，于是
 * {@code 剩余 = f₁/Δ * 50}，从 {@code 素材时长 − 剩余} 处开始播。
 * 与「一开始就知道速度、在 t₀ 立刻播」相比：播放时刻晚了一个 tick（Δt），
 * 但剪掉的头部也恰好少了 Δt，**任意时刻听到的内容完全相同**（
 * {@code start + (t − t₁) ≡ D − 剩余 + (t − t₀)}）。也就是说这一 tick 只用于**测量**，听不出来。
 *
 * <p><b>量不出来就退回「从头播」</b>（宁可不对齐，也不要不出声）：门在这一 tick 没动
 * （红石锁着 / 被卡住）时最多等 {@value #ALIGN_MAX_WAIT_TICKS} tick；门反向（关到一半又开回去）、
 * 门走出渲染距离、或素材本身不比门程长（例如开门端的 2.28 秒素材）时一律整段播放。
 *
 * <p>★ 这套对齐**只做关门端**，开门端一个字节都没动 —— 一来用户只点了关门，
 * 二来开门素材（2.28 秒）本来就比 MTR4 的 4 秒门程短，要「对齐」只能推迟，那才是真的慢半拍。
 *
 * <p>★ 它**也不是每一条关门素材都走**：素材里带「语音播报」、而玩家也要播报时，
 * 剪头就等于把整段播报剪掉（剪点 ≈ 素材时长 − 门程 ≈ 6.8 秒，正好整段盖住语音）——
 * 用户为这个报过「只剩嘀嘀了」。于是这一档改走下面那条**提前量**的路。
 *
 * <h2>路 B：提前量 —— 让**结尾**（而不是起点）落在门上，且整段素材都在</h2>
 * 想让「人声 + 嘀嘀」整段都在、又让结尾撞上门关上，解只有一个：
 * {@code 起播时刻 = 门关上那一刻 − 素材时长}，也就是**在门开始关之前约 6.8 秒**起播。
 *
 * <p><b>而 MTR 没有任何「即将关门」的预警信号</b> —— 反汇编确认过三处：
 * {@code BlockPSDAPGDoorBase$BlockEntityBase.tick(F)} 只有 {@code doorValue / doorTarget /
 * doorOverrideValue} 三个状态，红石一断**立刻**开始关门，没有预告阶段；
 * 车辆侧的 {@code org.mtr.core.data.Vehicle} 与 {@code org.mtr.mod.data.PersistentVehicleData}
 * 里的 {@code doorCooldown} 都是**关完之后**的冷却计时，同样不预告；
 * {@code RenderVehicleHelper} 只在门真动起来之后才把门值写给渲染器。
 * ⇒ 这个提前量只能**自己预测**：拿这扇门**上一轮**的实测周期
 * （{@link #noteCycle} 记下的「开门 → 全关」走了多少 tick）当这一轮的估计。
 * MTR 的停站时长来自时刻表（同一站固定），所以下一轮 ≈ 上一轮，误差在 1 tick 量级。
 *
 * <p>排出去的地方是**开门**那一瞬（{@link #planClose} —— 那是这一轮唯一至少能观测到的起点），
 * 到点的消费点是 {@link #firePlannedClose}（每 tick 一次）。下面几种情况一律**不猜**、
 * 当场退回路 A（「结尾落在门上」这条硬要求**永远**成立，只是那一轮没有人声）：
 * 还没学到周期（第一次到这一站）、素材比整轮还长（提前量算成负数）、
 * 这一档不要人声（{@code default-s}）、这一项此刻不播。
 *
 * <p>预测天然会有误差，所以还留了一道**事后**保险：关门那一刻如果发现整段提示音
 * **早就播完**了（说明这一轮停站比上一轮长），当场回到路 A 补一声
 * （{@link #detect} 里那段）。反过来「提示音还在响」才是预期情形 —— 什么都不做，
 * 让它自然收尾，结尾正好落在门上。分岔只在 {@link #detect} / {@link #planClose} 两处。
 *
 * <h2>【1.15 · 第六轮】「学到的周期」必须**跨门共用** —— 否则一走动就永远只有嘀嘀</h2>
 * 用户原话：「我现在即使停站时间远远超过关门音频播放时间也只有嘀嘀嘀了，怎么回事？
 * ……时间足够为什么也没人声了？」
 *
 * <p>根因不在算术里，在**判据的键**上：周期是按**门锚点**（{@code BlockPos.asLong}）存的，
 * 而它只在「站在同一扇门前看多趟车」时才成立。玩家**一动就不是同一扇门**了：
 * <ul>
 *   <li>坐在车上跑线 ⇒ 每一站的屏蔽门是**不同的方块位置** ⇒ 每一站都是「第一次见这扇门」
 *       ⇒ 查不到周期 ⇒ {@link #planClose} 直接 return ⇒ **每一站都只有嘀嘀**，
 *       停 60 秒也一样（复现见 {@code _tools/check-psd-predict.py} 第 6a 节）；</li>
 *   <li>沿站台走两步、最近的门换一扇 ⇒ 同理。</li>
 * </ul>
 * 而**停站时长属于「这一站」，不属于「这扇门」**：门程（0→1 的 80 tick）全世界一样，
 * 停站时长来自线路时刻表。所以周期应该**跨门共用**（{@link #globalCycleTicks}）：
 * 本扇门有自己的实测值就用自己的（最准），没有就借用别的门学到的。第 6b 节钉住
 * 「修好之后第 2 站起都有人声、且结尾仍落在门上」。
 *
 * <p><b>借用会带来一个本来的设计里不存在的风险，必须一起堵掉：</b>借来的那个比这一轮**长**时，
 * 整段提示音会一直响到门全关**之后**（门口已经关上、嘀嘀还在响），正好违反用户那条硬要求。
 * 所以关门那一刻会拿**跨门实测的门程**（{@link #globalTravelTicks}）比一次
 * 「整段还要响多久 vs 门还要多久才全关」，拖过头就把那条整段**掐掉**、改用路 A 补一声
 * （只嘀嘀、结尾仍落在门上）。容差 {@value #OVERHANG_SLACK_TICKS} tick 是为了
 * **不误伤正常情形**：周期准确时整段的结尾本来就落在门全关前约 1 tick，第 6d 节断言
 * 「周期准确 ⇒ 掐掉次数必须为 0」。路 B 与路 A 的分工到这里变成：
 * <b>路 B 负责「人声」，路 A 负责「结尾落在门上」这条硬要求永远成立。</b>
 *
 * <h2>【1.15】语音播报与嘀嘀声：顺序由**提前量**保证</h2>
 * 用户点名的顺序是
 * <pre>开门 + 开门嘀嘀嘀 → 等待（停站）→ 关门人声 → 关门 → 关门嘀嘀嘀</pre>
 * 更早那版是 {@code 开门+嘀嘀 → 关门人声 → 等待 → 关门嘀嘀嘀}（人声在**开门**那一瞬就起，
 * 见早已删除的 {@code startAnnouncement}）；上一版又改成「整段从关门那一瞬起播」，
 * 被用户否掉（门在第 4 秒就关上，提示音还剩 6.8 秒）。现在这一版靠**提前量**
 * 把用户点名的顺序和「结尾撞上门关上」两条一起满足 —— 推导如下。
 *
 * <p>关门素材是一条**真实录音**，实测结构是
 * {@code [语音播报 0~6707ms][静音 6707~7222ms][嘀嘀 7222~10806ms]}（全长 10806ms）。
 * 起播提前 6806ms（= 10806 − 4000，正是让结尾落在门上所需的量）⇒
 * <ul>
 *   <li>人声 0~6707ms 落在**等待**段里，在「门开始关」之前约 100ms 结束；</li>
 *   <li>静音 6707~7222ms 横跨「门开始关」那一瞬（听感上就是门一动、先静一下）；</li>
 *   <li>嘀嘀 7222~10806ms 盖住剩下的门程，最后一个嘀嘀**正好落在门全关**那一 tick。</li>
 * </ul>
 * 与用户要的那一行完全对上，而且**不需要剪辑**（整段播，只是起播点提前）。
 *
 * <p><b>停站太短呢？</b>用户明确允许「如果时间不够可以重叠开关门声音」。
 * 提前量算成负数（整轮塞不下整条素材）时 {@link #planClose} 干脆不排，当场退回路 A：
 * 剪掉人声、只留嘀嘀，结尾照样落在门上 —— 宁可这一轮没有人声，
 * 也绝不让门在提示音之前关上。
 *
 * <p><b>「结尾对齐门关上」这条老行为去哪了？</b>一个字节都没改，它就是**路 A**。
 * 内置名 {@code default-s}（石斧界面上的「默认（短）」）与**没有两段结构**的素材
 * （玩家导入的任意音频、开门端的 {@code dooropen.ogg}）始终走 {@link #resumeClose}
 * 那套「剪头 → 结尾落在门上」的算式。
 * 分界点不是写死的时间，而是**从素材里量出来的**（{@link EscalatorAudioPlayer#bundledAnnounceSplitMs}：
 * 找**最后一段** ≥400ms 的安静、且其后至少还有 1500ms 有声内容）。素材没有这种结构时
 * 这一步整个跳过，行为与最早那版完全一致 —— 于是玩家导入的任意素材都不会被这套逻辑弄坏。
 *
 * <p><b>不想要这段播报的玩家有显式开关</b>：内置名 {@code default-s}（「默认（短）」）
 * ＝ 素材、时长、分界点全都与 {@code default} 一样，**只是关门时不放那段语音播报**
 * ⇒ 听起来就是纯粹一串嘀嘀、而且结尾仍然对齐门关上。判定在数据层
 * {@link EscalatorSpeedData#isPsdBuiltinShort}，翻译成 {@link Tone#announce()} = false
 * 见 {@link #resolveTone}。
 *
 * <h2>连在一起的每一扇站台门都各自发声</h2>
 * 一列车到站时**整排门同时开**，玩家听到的应该是「沿站台一排门一起响」，而不是一个孤零零的
 * 声源。所以 {@link #onClientTick} 对快照里的**每一扇**门调一次 {@link #detect}，每扇门带**自己的**
 * 坐标与距离。
 *
 * <p>★【1.23】这里原来是「只让离玩家最近的那一扇发声」（与 {@link EscalatorChimePlayer} /
 * {@link LiftChimePlayer} 沿用同一个约定），用户报的症状正是它的直接后果：
 * 「只有一整条连在一起的屏蔽门的中间有声音」—— 那条跑里只剩一个点在响。
 * 三处之所以能一起改这个约定：距离衰减 {@link #gain} 在生效范围外硬截 0，
 * 每 tick 真正起播的实例只与「玩家周围十几格」有关（典型 4~6 扇），不会有界地随门数增长。
 *
 * <p>而**玻璃幕墙 / 幕墙尾部 / 顶部本来就不会发声** —— {@link PsdDoorTracker#accept} 那道
 * {@code isPsdDoor} 白名单只放行 {@code psd_door*} / {@code apg_door*}，
 * {@code psd_glass*} / {@code psd_glass_end*} / {@code apg_glass} / {@code psd_top}
 * 在数据源头就被丢掉了，所以这份快照里本来就只有真正的门。
 *
 * <p>门值仍然**每扇都记**（{@link #lastDoor}）：只有真正发生跳变（开 / 关的那一瞬）才响。
 *
 * <p>★★【1.25】「一整排都听得见」还有**第二个必要条件**：距离衰减曲线必须够平。
 * 原来 {@link #gain} 抄的是扶梯那套**平方**曲线 {@code (1 - d/r)²}
 * （那边的理由是「一头一个、要让人分得清声音从哪头来」），在 16 格范围里 8 格只剩 25%、
 * 12 格 6%、15 格 0.4%（= -48dB）⇒ 一串 46 格长的屏蔽门照样只有近处几扇听得见。
 * 现场日志（LOG3）里用户已经把音量调到 1000（10×）来对抗它，结果近的门被放到 8 倍（炸）、
 * 15.5 格那扇的增益仍是 0.00098 —— 正是他报的「有的屏蔽门声音大，有的还是没有声音」。
 * 现在改成**线性** {@code 1 - d/r}（原版 {@code SoundEngine} 自己也是线性），
 * 并且三类声音的距离增益都改成**每 tick 现算**（不再在起播那一刻算死），
 * 于是「范围 = 真的听得见的半径」、走近了还会继续变响。
 *
 * <h2>音量 &gt; 100 需要成对做两件事</h2>
 * 与直梯那套完全一致（只做一件仍然最多 1.0×）：
 * <ol>
 *   <li>{@link PsdMusicInstance} 实现 {@link GainManagedSound} —— 让
 *       {@code SoundEngineVolumeMixin} 把该实例的 {@code calculateVolume} 上限从原版的 1.0
 *       抬到 {@link EscalatorAudioPlayer#MAX_GAIN}（= 10×）；</li>
 *   <li>开播时把该 OpenAL 源的 {@code AL_MAX_GAIN} 也抬到同一个值（见 {@link #play}）。</li>
 * </ol>
 * 音量在 {@code play()} **之前**就摆进实例，所以开播当刻就是正确音量（既不炸一下，也没有开头空白）。
 *
 * <p>入口由 {@code SmoothLiftClient} 注册：{@link #onClientTick(Minecraft)}、{@link #onDisconnect()}。
 * 诊断日志前缀 {@code [SmoothLift/PsdChime]}：只在「真的响了一下」和「开关被关掉」时各打一条。
 */
public final class PsdChimePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 【1.15】三段**内置音频**（四个指令名），由 {@code assets/smoothlift/sounds.json} 注册：
     * <ul>
     *   <li>{@code smoothlift:audio/dooropen} —— {@code dooropen.ogg}，指令名 {@code default}
     *       （以及 {@code default-s}）；开门端没设置过时就用它；</li>
     *   <li>{@code smoothlift:audio/mdoorclose} —— {@code mdoorclose.ogg}，指令名 {@code default-m}
     *       （以及 {@code default-s}）；**关门端没设置过时就用它**
     *       （用户点名：屏蔽门默认音效 = mdoorclose.ogg）；</li>
     *   <li>{@code smoothlift:audio/doorclose} —— {@code doorclose.ogg}，指令名 {@code default-c}；
     *       只能显式选（没有哪一端缺省它）。</li>
     * </ul>
     *
     * <p>★ {@code default-s}（「默认（短）」）**不占用第四个声音事件**：它在数据层被解析成
     * 与 {@code default} 相同的音频 key（按端别），差别只在「关门时不放语音播报」这条播放策略上
     * （见 {@link EscalatorSpeedData#isPsdBuiltinShort} 与 {@link #resolveTone}）。
     * 名字 → 文件是 {@link EscalatorSpeedData#psdBuiltinKey}（数据层）判的，
     * 这里只做「档 → 声音事件」的最后一步映射，两边都不重复对方的判断。
     *
     * <p>★ 1.50 用的是自己合成的 {@code psd_open/psd_close}，【1.15】起换成上面三段
     * 用户提供的素材（那两个合成文件已从 assets 删除，别再把它们加回来）。
     */
    private static final ResourceLocation PSD_DOOR_OPEN =
            new ResourceLocation("smoothlift", "audio/dooropen");
    private static final ResourceLocation PSD_DOOR_CLOSE =
            new ResourceLocation("smoothlift", "audio/doorclose");
    private static final ResourceLocation PSD_MDOOR_CLOSE =
            new ResourceLocation("smoothlift", "audio/mdoorclose");

    /** 内置档（{@code dooropen} / {@code doorclose} / {@code mdoorclose}）→ 声音事件。 */
    private static ResourceLocation builtinEvent(String builtinKey) {
        return switch (builtinKey) {
            case "dooropen" -> PSD_DOOR_OPEN;
            case "mdoorclose" -> PSD_MDOOR_CLOSE;
            default -> PSD_DOOR_CLOSE;
        };
    }

    /** 判定「全开 / 全关」的容差（理由见类注释）。 */
    private static final float EDGE = 0.02f;

    /** 每扇门上一次看到的门开合程度：门锚点 → [0,1]。用来发现「跳变」。 */
    private static final Map<Long, Float> lastDoor = new HashMap<>();

    /**
     * 【1.15】等了一 tick 还没播出去的**关门**提示音：门锚点 → 请求（见 {@link #resumeClose}）。
     *
     * <p>为什么必须挂起一 tick：要把提示音的结尾对准「门关上」，就得知道门**还要走多久**，
     * 而门速只能从「门值在两个时刻之间的变化」量出来。挂起一 tick 不会让声音晚出来
     * （剪掉的头部同步少一 tick，见类注释里的推导）。
     */
    private static final Map<Long, Pending> pendingClose = new HashMap<>();

    /**
     * 【1.15】每扇门「上一轮从**开门**到**全关**走了多少 tick」（= 停站时长 + 门程）。
     *
     * <p>为什么需要它：用户点名要的是「关门提示音 + 嘀嘀**全部播完之后**，屏蔽门**正好关上**」。
     * 素材 10.8 秒、门自己只走 4 秒（MTR4），所以要让**整条素材的结尾**落在门上，
     * 就必须在门**开始关之前** {@code 素材时长 − 门程} ≈ 6.8 秒起播 —— 而 MTR
     * **没有任何关门预警信号**（反汇编见类注释），这个提前量只能**自己预测**。
     *
     * <p>预测的根据是**实测周期**：MTR 的停站时长来自时刻表（同一站固定），
     * 同一扇门下一轮的「开门 → 全关」几乎等于上一轮 ⇒ 按上一轮提前，误差在 1 tick 量级。
     * <p>【1.15 · 第六轮】本扇门没有实测值时改**借用**跨门共用的那一份（见 {@link #globalCycleTicks}）——
     * 「停站时长属于这一站、不属于这扇门」，否则玩家一走动就永远没有参照。
     * <p>【1.15 · 第八轮】实测值之前又插了一层：**直接读 MTR 时刻表**（{@link MtrDwellAccess}）。
     * 实测学习的两个先天缺陷它都没有 —— ① 第一次停站就有值；② 给出的是**这一站自己的**
     * 停站时长，不会被别的站串味。实测值仍然优先于它（实测值里还含着「这一站是不是被
     * 信号憋住了」这类时刻表不知道的真实情况）。
     * <p>三个来源都没有时（刚进世界 + 读不到站台数据）不猜：关门那一瞬退回「剪头对齐结尾」
     * （见 {@link #resumeClose}），保证「结尾落在门上」这条硬要求**永远**成立。
     *
     * <p>★ 这张表只记**本扇门**的实测值，是**最准**的那一份（见 {@link #planClose} 的三级来源）。
     * 它**不按帧快照裁剪**（不参与 {@code retainAll}）：玩家走远把区块卸了、
     * 或最近的门换成另一扇，都不该把学到的停站时长丢掉，否则每次走开都要重新学一轮。
     * 键是门的锚点，总量由世界里 PSD 门的数量封顶。
     */
    private static final Map<Long, Long> learnedCycleTicks = new HashMap<>();

    /**
     * 【1.15 · 第十轮】这一轮**用时刻表算出来的**周期：门锚点 → tick。
     *
     * <p>存在的唯一目的是**交叉核对**：时刻表说「这一站停 30 秒」，但 MTR 在运行时
     * 可能把停站改短（侧线的「晚点缩短停站时间」）或改长（「早到延长停站」），
     * 于是实测跑出来的周期和预测差一截。这一条差异是「反推公式说是对的、实际却不对」
     * 那条缝的**唯一可观测证据**，所以要在 {@link #noteCycle} 里拿它跟实测值对一次
     * （见 {@link #TIMETABLE_DISAGREE_TICKS}）。
     *
     * <p>用完即删（{@code remove}）：只对这一轮有意义。
     */
    private static final Map<Long, Long> timetableCycleTicks = new HashMap<>();
    /**
     * 【1.15 · 第十二轮】时刻表 × 实测 的两条结论各**每扇门只喊一次** ——
     * 因为时刻表现在是**每次开门都读**（见 {@code planClose} ①），
     * 这条分歧/一致会反复出现，不加去重就会把日志刷满。
     */
    private static final Set<Long> timetableDisagreeWarned = new HashSet<>();
    private static final Set<Long> timetableAgreeLogged = new HashSet<>();

    /**
     * 【1.15 · 第十轮】「时刻表的周期」与「实测的周期」差多少就算**对不上**。
     *
     * <p>取 60 tick = 3 秒：正常误差只来自「关门被信号憋住一点点」这种几十毫秒级的抖动，
     * 而「晚点缩短停站」砍掉的是**成秒**的量级。3 秒既不会误报，又能在「30 秒被砍成
     * 几秒」时立刻喊出来。
     */
    private static final long TIMETABLE_DISAGREE_TICKS = 60L;

    /** 这一轮**开门**发生在哪一 tick：门锚点 → tick（用来在「变成全关」那一刻算出周期）。 */
    private static final Map<Long, Long> cycleOpenTick = new HashMap<>();

    /**
     * 已经排好、等着「提前量到点」的那条**整段**关门提示音：门锚点 → 起播的游戏刻。
     *
     * <p>排的地方是**开门**那一瞬（{@link #planClose}），到点的消费点是
     * {@link #firePlannedClose}（每 tick 一次，排在 {@link #resumeClose} 之前）。
     */
    private static final Map<Long, Long> plannedCloseStart = new HashMap<>();

    /**
     * 这一轮**已经提前播过**整段关门提示音的门：门锚点 → **实际起播的那一 tick**。
     *
     * <p>必须记着：提前播出**发生在关门之前**，等真的检测到「门开始关」时，
     * 那条素材正在响、而且正好还剩一个门程 ⇒ 让它自然收尾，结尾就落在门上
     * （这也正是「不是开始关门才播放提示音」的实现方式）。
     *
     * <p>★ 存的是**起播 tick**而不是一个布尔：预测会有误差，关门那一刻还要拿它算
     * 「这条素材**本该**在哪一 tick 播完」（{@code 起播 + 素材时长}）——
     * <ul>
     *   <li>还没播完（{@code now < 起点 + 时长}）⇒ 预期情形，什么都不做，让它收尾；</li>
     *   <li>**早就播完**了（这一轮停站比上一轮长）⇒ 当场退回「剪头」补一声，
     *       绝不让这一轮关门凭空没声音（见 {@link #detect}）。</li>
     * </ul>
     * 光记布尔就区分不出这两者，也就补不了那一声。
     */
    private static final Map<Long, Long> plannedCloseFired = new HashMap<>();

    /**
     * 【1.15 · 第六轮】**跨门共用**的「开门 → 全关」周期（tick）；{@code <= 0} = 还没学到。
     *
     * <p>为什么必须有它（用户报「停站时间远远超过素材时长，也只有嘀嘀嘀」的根因）：
     * {@link #learnedCycleTicks} 的键是**门锚点**，于是「学到的东西」被切成了互不相通的小格子。
     * 玩家**一动就不是同一扇门**了：
     * <ul>
     *   <li>坐在车上跑线 ⇒ 每一站的屏蔽门是**不同的方块位置** ⇒ 每一站都是「第一次见这扇门」
     *       ⇒ {@code learnedCycleTicks.get(新 key) == null} ⇒ {@link #planClose} 直接 return
     *       ⇒ **永远只有嘀嘀**（哪怕那一站停 60 秒）；</li>
     *   <li>沿站台走两步 / 最近的门换一扇 ⇒ 同理。</li>
     * </ul>
     * 而**停站时长属于「这一站」，不属于「这扇门」**：门程（0→1 的 80 tick）全世界一样，
     * 停站时长来自线路时刻表，同一条线上各站通常就是同一个值。所以「上一轮学到的周期」
     * 应该**跨门共用**：本扇门有自己的值就用自己的（最准），没有就借用别的门的。
     *
     * <p>★ 借用会带来一个**本来的设计里不存在**的风险：借来的那个比这一轮**长**时，
     * 提示音会拖过「门全关」（门都关上了、嘀嘀还在响）。所以配套加了
     * {@link #globalTravelTicks} + {@link #OVERHANG_SLACK_TICKS} 那条判据（见 {@link #resumeClose}）。
     *
     * <p>★【1.15 · 第八轮】它现在是**第三级兜底**（{@link #planClose} 里的顺序是：本扇门实测 →
     * MTR 时刻表 → 这一个）。原因：它的错处和它的对处一样明显 —— 「同一条线上各站通常
     * 就是同一个值」只是**通常**。用户点名问过 A 站 10 秒 / B 站 20 秒 / C 站 30 秒 这种
     * 时刻表：那时借来的值对 B、C 就是错的（会导致人声被提前掐掉或拖过门全关）。
     * 所以**只要读得到时刻表，就不借**；借只发生在「没装 MTR4 / 站台数据还没同步完」。
     */
    private static long globalCycleTicks = -1L;

    /**
     * 【1.15 · 第六轮】跨门共用的**门程**（门开始关 → 全关，tick）；{@code <= 0} = 还没学到。
     *
     * <p>它是 {@link #resumeClose} 里「这条整段会不会拖过门全关」那条判据的尺子。
     * 为什么不用「当场量出来的门速」当尺子：那个量是从**两个 tick** 的差值算出来的，
     * 掉一帧就会差一倍；而门程是从**一整轮**算出来的常量，跨门还都相等 ⇒ 稳。
     * 拿它当「还剩多久门才全关」的估计，判据就不会被单帧噪声误触发。
     */
    private static long globalTravelTicks = -1L;

    /** 这一轮**门开始关**发生在哪一 tick：门锚点 → tick（用来在「全关」那一刻算出上面的门程）。 */
    private static final Map<Long, Long> closeStartTick = new HashMap<>();

    /**
     * 已经提前播出去、**可能还在响**的那条整段提示音：门锚点 → 播放实例。
     *
     * <p>留这个句柄只为一件事：借来的周期偏长时，这条素材会一直响到门全关之后 ——
     * 那时要**掐掉**它、改用剪头补一声（见 {@link #resumeClose}）。
     * 素材自己播完时句柄会失效，对失效句柄调停播是个空操作，不需要精确回收。
     */
    private static final Map<Long, PsdMusicInstance> wholePlaying = new HashMap<>();

    /**
     * 【1.16】「停站不够放完人声」那一轮排下的**兜底人声**：门锚点 → 计划/句柄。
     *
     * <h2>它解决什么</h2>
     * 用户原话：「如果停站时间不够放完整个音频，那就固定开门音效播放完后等待 X 秒之后播放，
     * 车门关闭的嘀嘀嘀不影响，但是一旦到关门时间嘀嘀嘀开始播放，就立即切断正在播放的人声提示，
     * 不管播放到哪里。」
     *
     * <h2>与「提前量」（{@link #plannedCloseStart}）的分工</h2>
     * 两者**互斥**，判据就是同一条：{@code 周期 > 素材时长}。
     * <ul>
     *   <li>**够长** ⇒ 走提前量：整段素材提前起播，人声落在等待段、嘀嘀落在门程上，
     *       结尾正好撞上门关上。这一档**完全不看** {@link #cachedCloseWaitSeconds}；</li>
     *   <li>**不够** ⇒ 走这一张表：既然塞不下，就别再想着对齐了 —— 开门音播完等 X 秒
     *       把**语音播报**放出来，能听多少听多少，门一动立刻掐断。</li>
     * </ul>
     * ★ 用户专门点名过「停站够长时要**忽略**这个等待时间」—— 所以它只在
     * {@link #planClose} 那一个分支里被读到，其余任何地方都不许读（读错一处就变成
     * 「够长也硬等」）。{@code _tools/check-close-wait.py} 把这条钉住了。
     */
    private static final Map<Long, ForcedVoice> forcedVoice = new HashMap<>();

    /**
     * 【1.16】兜底人声的状态。
     *
     * <p>不是 record 是因为 {@link #instance} 与 {@link #fired} 要在播放那一刻回填 ——
     * 而「计划」与「句柄」必须放在**同一条**记录里：分开两张表时，掐断那一侧只拿到句柄、
     * 拿不到 {@code voiceEndTick}，就会退化成「只能等门关」这一条判据。
     */
    private static final class ForcedVoice {

        /** 到这一 tick 就该起播（= 开门那一 tick + 开门素材时长 + 等待秒数）。 */
        private final long startTick;

        /**
         * 到这一 tick 就该把这段人声收掉（= 起播 + {@code splitMs}）。
         *
         * <p>为什么要「播完就收」：素材在语音播报之后还有约 0.5 秒静音和 17 个嘀嘀，
         * 而这一档的嘀嘀**由关门端那条剪头路负责**。不收的话两处嘀嘀会叠在一起响。
         * 收在 {@code splitMs} 处而不是「一直响」正好把语音与嘀嘀切成互不重叠的两段。
         */
        private final long voiceEndTick;

        private final Tone tone;
        private final PsdDoorTracker.DoorView door;
        private final float volume;

        /** 已经处理过「到点」这一步（不管播成没播成）。 */
        private boolean fired;

        /** 正在响的那个实例；{@code null} = 还没播 / 已收掉 / 播不出。 */
        private PsdMusicInstance instance;

        private ForcedVoice(long startTick, long voiceEndTick, Tone tone,
                            PsdDoorTracker.DoorView door, float volume) {
            this.startTick = startTick;
            this.voiceEndTick = voiceEndTick;
            this.tone = tone;
            this.door = door;
            this.volume = volume;
        }
    }

    /**
     * 【1.17】「**到站播报**」（{@code /pbmmidium <名字> <秒>}）：**串锚点**（{@code runKey}） → 计划。
     *
     * <p>★★【1.26】键从「门锚点」改成「**串锚点**」—— 一整串连在一起的屏蔽门只排**一条**。
     * 理由：它读的配置本来就是按串的（{@code getDoorPsdMidiumAudio(mc.level, door.runKey())}），
     * 「每扇门各排一份」排出来的 N 份逐位相同（纯重复、还是 N 条音频流同时响）；
     * 而每份的射程又只按「本扇门」算 ⇒ 一串 12 扇 55 格长时，站在任何位置都只有约 6 扇在
     * 范围内、其余 `gain == 0` 静默跳过（现场 LOG4「后 4 个 midium 不响」的根因）。
     * 现在：距离与声源都取「本串里离玩家最近的那一扇」，只要玩家在这串门的任意一扇旁边，
     * 这一串的到站播报就成立 —— 站台广播的语义。
     *
     * <h2>它解决什么</h2>
     * 用户原话：「增加开门后的播报，类似于上海地铁到达站后开门之后站台播报的
     * 『上海是中国共产党的诞生地，参观一大会址……』这个播报，模组里是开门嘀嘀嘀之后开始播放。
     * 指令为 pbmmidium XXX Y（XXX 是 ogg 名称，Y 是到站后等待几秒开始播放这个音频），
     * 这个 pbmmidium 音频**即使列车出站也要继续播放，直到播完**。」
     *
     * <h2>★ 与 {@link #forcedVoice}（关门兜底人声）的**唯一本质差别**：永不掐断</h2>
     * 那两个「等待 N 秒」很容易被看成一件事，但生命周期**正好相反**：
     * <ul>
     *   <li>{@link #forcedVoice}：门一动**立刻掐断**（用户点名）；{@link #reset} 时清计划 + 停实例；</li>
     *   <li>本表：<b>没有任何停止条件</b> —— 播到素材自然结束（用户点名「车出站也照播」）；
     *       {@link #reset} 时<b>什么都不做</b>。</li>
     * </ul>
     *
     * ★ 正因为「没有任何停止条件」，这一张表**不能**复用 {@code forcedVoice} 那条路：
     * 那条路的每一处（{@code false→true} 跳变、{@code tickForcedVoice} 的
     * {@code doorMoving}）都会把它掐断。分开一张表 + 分开一条 tick 路，是**结构性**的保证，
     * 不是靠标志位绕开的。
     *
     * <h2>为什么它能「车出站也继续播」</h2>
     * 计划里存的是**排计划那一刻的 {@link PsdDoorTracker.DoorView}（本串离玩家最近的那一扇）
     * 与音量**，播放时只取它的 x/y/z 当声源位置（{@link #play} 的全部用法）—— 于是门离开了
     * 渲染快照也照样能起播；起播之后实例就已经交到原版 {@code SoundEngine} 手上（一次性、不循环），
     * 只要**没人去停它**，它就会响到素材自然结束。
     */
    private static final Map<Long, Arrival> arrivalVoice = new HashMap<>();

    /**
     * 【1.17】到站播报的状态。
     *
     * <p>**没有** {@code instance} 字段 —— 这是刻意的：不持有句柄，就没有「谁能停它」这个问题。
     * 它唯一的归宿是「播完」。
     */
    private static final class Arrival {

        /** 到这一 tick 就该起播（= 开门那一 tick + 开门素材时长 + 等待秒数）。 */
        private final long startTick;

        /**
         * 到这一 tick 就把它从表里摘掉。
         *
         * <p>★ 摘掉**只是回收表项**，与声音无关：素材已经在播了，条目不摘只会让这张表
         * 越积越长（等待秒数允许正无穷，条目可能挂很久）。所以这里用「起播 + 素材时长」，
         * 素材时长量不到时退化成 {@code startTick}（起播那一刻的下一 tick 就摘）。
         */
        private final long endTick;

        private final Tone tone;

        /**
         * 【1.26】声源那一扇门 = **排这条计划那一刻、这一串里离玩家最近的那扇**
         * （{@link PsdDoorTracker#nearestInRun}），不是「碰巧先开门的那扇」。
         *
         * <p>★ 它只被用来取 x/y/z 当声源位置（{@link #play} 的全部用法）—— 所以门后来
         * 离开渲染快照也照样能起播；「车出站也继续播」就靠这一点。
         */
        private final PsdDoorTracker.DoorView door;

        /**
         * 【1.26】这条计划属于哪一串（= {@code arrivalVoice} 的键）。
         *
         * <p>★ 存它的理由：这条声音的距离增益按**整串**算（站台广播，见
         * {@link PsdMusicInstance#refreshVolume}），而日志里点明「等待几秒」也要按串去读配置 ——
         * 用门锚点 {@code door.key()} 去读 per-串的配置会**静默落回维度默认**
         * （读得到、但读的不是这一串设过的那一份）。
         */
        private final long runKey;

        private final float volume;

        /** 已经处理过「到点」这一步（不管播成没播成）。 */
        private boolean fired;

        private Arrival(long startTick, long endTick, Tone tone,
                        PsdDoorTracker.DoorView door, long runKey, float volume) {
            this.startTick = startTick;
            this.endTick = endTick;
            this.tone = tone;
            this.door = door;
            this.runKey = runKey;
            this.volume = volume;
        }
    }

    // ------------------------------------------------------------------
    // 【09-30 续 3】「站台广播（讲述人）」—— 与「进站广播（讲述人）」功能一模一样的
    //   一条讲述人，只是挂在**站台播报（pbmmidium）**的时间窗口上：开门音播完 + 等 Y 秒开念。
    //   结构照抄 {@link #arrivalVoice} 那一套：开门那一瞬排一条计划（一整串只排一条），
    //   到点在 tick 里把话交给 {@link TrainAnnounceNarrator}（讲述人念完就完，无需停止条件）。
    //   ★ 与自定义到站播报**互相独立**：素材设成「不播」不影响讲述人照念，反之亦然。
    // ------------------------------------------------------------------

    /** 【09-30 续 3】串锚点（runKey）→ 这串门还没开念的站台讲述人计划。 */
    private static final Map<Long, MidiumNarratorPlan> midiumNarratorVoice = new HashMap<>();

    /**
     * 【09-30 续 3】一条站台讲述人计划。
     *
     * <p>★★【10-01】它**不再**在起播那一 tick 就摘表项 —— 与 {@link Arrival} 一样要留到
     * 「这一班车的开门周期过完」为止。原因是「同一份话不叠两遍」那道守卫
     * （{@code midiumNarratorVoice.containsKey}）窗口被压成了**零**：见
     * {@link #MIDIUM_NARRATE_HOLD_TICKS} 里的 LOG013 数字。现在起播只做两件事
     * —— 记 {@link #fired} 与 {@link #spokenTick}，表项由 {@link #tickMidiumNarrator} 在
     * 「念过 + 守卫窗口走完 + 这一串的门已经全关」之后才摘。
     */
    private static final class MidiumNarratorPlan {

        /** 到这一 tick 就该开念（= 开门那一 tick + 开门素材时长 + 等待秒数）。 */
        private final long startTick;

        /**
         * 声源那一扇门 = 排计划那一刻**触发这一扇门所属的那个 MTR 站台**里离玩家最近的那扇
         * （【10-01 续】；站台认不到时回落「车站里离玩家最近的那扇」）。
         * ★ 它是这条广播的位置 / 范围基准：别的站台离玩家远 ⇒ 出范围 ⇒ 这一条根本排不出来
         * （见 {@link #planMidiumNarrator} 里那段「为什么要收窄到站台」）。
         */
        private final PsdDoorTracker.DoorView source;

        /** 这条计划属于哪一串（= {@code midiumNarratorVoice} 的键；日志与范围判据都用它）。 */
        private final long runKey;

        /**
         * 排计划那一刻认到的 MTR 站台 id（{@link PsdDoorTracker.DoorView#platformId()}）——
         * 起播时拿它去查时刻表（终点站 / 站台名）与车站 / 线路名，拼上海 / 香港 / userN 的句子。
         */
        private final long platformId;

        /** 【10-01】这一条已经念过了（起播那一 tick 置位）。没念过时表项只负责「挡住第二条」。 */
        private boolean fired;

        /** 【10-01】开念的那一 tick；{@code -1} = 还没念（守卫窗口从这一刻起算）。 */
        private long spokenTick = -1L;

        private MidiumNarratorPlan(long startTick, PsdDoorTracker.DoorView source, long runKey, long platformId) {
            this.startTick = startTick;
            this.source = source;
            this.runKey = runKey;
            this.platformId = platformId;
        }
    }

    /**
     * 【10-01】站台讲述人**开念之后**还要把这一串挡住多久（tick）——「同一班车只念一次」的守卫窗口。
     *
     * <p>★★ 为什么必须有这段窗口（现场 LOG013，数字可复核）：
     * 旧实现是「起播那一 tick 就把表项摘掉」。而 {@link #planMidiumNarrator} 的等待基线
     * {@code openPlayable} 在**触发的那扇门不在玩家射程里**时是 {@code null} ⇒ 起点按 0 算 ⇒
     * {@code startTick == 现在}，也就是**当场开念、当场摘表**。于是同一串里后开门的另外几扇门
     * （每扇门各自一条开门沿，LOG013 里隔 2 tick / 8 秒）只要在那之后再报一次沿，
     * {@code containsKey} 已经是空的 ⇒ **再排一条、再念一遍**。
     * 用户听到的正是「提前播报一次 + 播报 2 次」。
     *
     * <p>LOG013 的两次实测（同一份话，一字不差）：
     * <ul>
     *   <li>{@code 14:56:33} 第 {@code 32927497} tick 与第 {@code 32927499} tick —— 隔 **2 tick**
     *       （两条计划都来自 z=43 那条线，声源同为 {@code @[-16,-20,32]}，句子同为
     *       「终点站 城东机场北 / 站台 2-2」）；</li>
     *   <li>{@code 14:55:43} 与 {@code 14:55:53} —— 隔 **8 秒**（声源同为 {@code @[-38,-12,72]}，
     *       句子同为「终点站 城南新区 / 站台 8-1」）。</li>
     * </ul>
     *
     * <p>取 {@value}（10 秒）的理由：要盖住上面那 8 秒的那一对，又不能长到把**下一班车**吞掉。
     * 10 秒正好是 MTR 的默认停站时长（{@link MtrDwellAccess} 读到的 {@code dwellTime} 默认
     * {@code 10000ms}）—— 一个停站周期内同一串只该开一次口。
     * ★ 这不是「猜一个魔法数」：反向对照见 {@code _tools/check-psd-midium-once.py}
     * —— 它把 LOG013 两条实测计划（tick {@code 32927497} / {@code 32927499}）灌进一个纯逻辑
     * 复现器，再**把「念完放回表里」这一步去掉**（≡ 旧实现），断言那两对重复**必须复现**。
     * ★ 反向对照的杠杆是「**放回表里**」这一步，**不是**把 {@value} 改成 0 ——
     * 窗口 = 0 时下面那条「这一串的门已全关」照样挡住，而 LOG013 那一刻门正开着（停站中），
     * 所以只调 0 复现不出来。当初真正造成重复的动作是「起播即摘表」本身。
     *
     * <p>★ 表项还额外受「这一串的门是否已全关」约束（周期长的站台自然挡得更久），
     * 上限见 {@link #MIDIUM_NARRATE_HOLD_MAX_TICKS}。
     */
    private static final int MIDIUM_NARRATE_HOLD_TICKS = 200;

    /**
     * 【10-01】守卫窗口的**硬上限**（tick）。
     *
     * <p>{@value}（60 秒）是兜底：门卡住不关（红石锁着 / 停站异常长 / 快照里再也看不到这些门）时，
     * 上面那条「这一串的门已经全关」可能**永远**不成立 ⇒ 表项就会一直挡着、
     * 之后整场都再也念不出来。上限一到就照摘，宁可多念一次也不要永久哑掉。
     */
    private static final int MIDIUM_NARRATE_HOLD_MAX_TICKS = 1200;

    /**
     * 【1.15 · 第六轮】「整段会拖过门全关」这条判据的容差（tick）。
     *
     * <p>{@value}（0.4 秒）的取法：周期准确时，整段的结尾**本来**就落在门全关前约 1 tick
     * （{@code leadTicks} 向上取整带来的量级），所以「会不会拖过头」的判据必须留出这个余量；
     * 而真正要拦的错位是「整个停站时长差了一截」（几十 tick 起），留 {@value} 既拦得住
     * 真错位、又不会被单帧噪声或取整误差误触发（对照组见
     * {@code _tools/check-psd-predict.py} 第 6d 节：周期准确时掐掉次数必须为 0）。
     */
    private static final int OVERHANG_SLACK_TICKS = 8;

    /**
     * 【1.15】挂起的关门提示音最多等几 tick 再按原样播。
     *
     * <p>取 {@value}（0.25 秒）的理由：门真在动的话第 2 个 tick 就量到了，正常只等 1 tick；
     * 等满 5 tick 说明门压根没动（红石锁着、被挡住）或这一帧没被渲染，此时再等下去
     * 只会让提示音越来越晚，不如立刻按原样播（与 1.50 的行为一致）。
     */
    private static final int ALIGN_MAX_WAIT_TICKS = 5;

    /**
     * 【1.15】排好的提前量「过期」判据：到点之后最多晚这么多个 tick 还来得及播。
     *
     * <p>排完到到点之间本来是**等着**（提前量最长可达一整轮），到点那一刻起就只差
     * 一两个 tick 的执行延迟 —— 所以「晚得比 {@value}（2 秒）还多」说明这一轮早就过去了，
     * 排的这条已经作废（典型情形：玩家走远、区块卸载又回来，中间门已经开关过一轮）。
     * 这时**不补播**：补播只会让一条本该落在门上的提示音落在别处，交给关门那一刻的剪头。
     */
    private static final int PLAN_GRACE_TICKS = 40;

    /**
     * 【1.15】一次「要播的提示音」的全部材料：事件 / 素材来源 / 素材时长。
     *
     * <p>单独算出来是为了让**两条路**（立刻播 / 先量门速下一 tick 再播）共用同一份判断 ——
     * 「内置档还是自定义档」「时长多少」只在 {@link #resolveTone} 里判一次。
     *
     * @param event      内置档的声音事件；自定义档时只是**占位**（{@link PsdMusicInstance#resolve}
     *                   会换成注入的那段），但必须非 null
     * @param customId   音频库文件名；{@code null} = 内置档
     * @param builtinKey 内置档的 key（{@code dooropen / doorclose / mdoorclose}）；自定义档为 null
     * @param durationMs 素材总时长（ms）；{@code <= 0} = 没量出来（这一项就不剪头，照原样播）
     * @param splitMs    【1.15】素材里「语音播报 / 嘀嘀声」的分界点（ms）；{@code <= 0} = 素材里**没有**
     *                   这种结构（例如开门用的 {@code dooropen.ogg}，或玩家导入的任意素材）。
     *                   见 {@link EscalatorAudioPlayer#bundledAnnounceSplitMs} —— 它是**两件事共用**的判据：
     *                   ① 「这条素材要不要从关门那一瞬**整段**起播」（与 {@code announce} 一起判）；
     *                   ② 关门端「不许剪进播报里」那道夹取。
     * @param announce   【1.15】**关门那一瞬要不要把这条素材开头的语音播报放出来**。
     *                   <p>★ 它和 {@code splitMs} 是**两件独立的事**，别合并：
     *                   前者是「玩家的意愿」（「默认（短）」{@code default-s} 就是把它关掉），
     *                   后者是「素材有没有、在哪」。合并成一个 {@code -1} 会顺手把关门端那道
     *                   「不许剪进播报里」的夹取也一起关掉 —— 对**嘀嘀段比门程短**的导入素材
     *                   就会在关门时漏出一截语音（与「只播嘀嘀」的意愿相反）。
     *                   所以这里分开存：{@code announce=false} 只让关门端改走
     *                   「剪头对齐结尾」那条路，夹取照旧按 {@code splitMs > 0} 生效。
     */
    private record Tone(ResourceLocation event, String customId, String builtinKey, int durationMs,
                        int splitMs, boolean announce) {
    }

    /**
     * 【1.15】挂起中的关门提示音（{@link #pendingClose} 的值）。
     *
     * @param tick      发现「门开始关」的那个 tick（用来算经过了几 tick）
     * @param fraction  那一刻的门值（与 {@code tick} 一起构成量速度的两个采样点之一）
     * @param tone      要播的素材
     * @param door      这一扇门（发声位置 + 日志标识；用快照，不再随门移动）
     * @param volume    【1.25】这一项的音量系数（**不含**距离增益：距离增益由播放实例每 tick 现算）
     * @param whole     【1.15 · 第六轮】此刻**已经在响**的那条整段提示音的句柄；
     *                  {@code null} = 没有（正常走「剪头对齐结尾」）
     * @param wholeEndTick 那条整段**本该**播完的那一 tick（{@code 起播 + 素材时长}）——
     *                     判据要拿它跟「门还剩多久才全关」比（见 {@link #resumeClose}）
     */
    private record Pending(long tick, float fraction, Tone tone, PsdDoorTracker.DoorView door,
                           float volume, PsdMusicInstance whole, long wholeEndTick) {
    }

    /**
     * 【1.15】「这一扇门这一项**此刻**要播的东西」—— 通过全部前置检查之后的结果。
     *
     * <p>存在的理由：这两件事有**三个**调用点（开门那一瞬 / 关门那一瞬 / 提前量到点），
     * 而它们都要先过同一道闸（子开关 → 这一扇门是否设为不播 → 音量算不算得出来）。
     * 把闸做进 {@link #resolvePlayable} 一处，返回 {@code null} 就代表「不播」，
     * 调用点不必各写一份判据（写三份必然有一份会和另外两份走偏）。
     *
     * @param tone       要播的素材（内置档还是自定义档已在 {@link #resolveTone} 里定死）
     * @param volume     【1.25】这一项的音量系数（**不含**距离增益 —— 距离增益由播放实例每 tick
     *                   现算，所以「走近了会变响」；射程判定仍在 {@link #resolvePlayable} 里做）
     * @param toneVolume 这一项**生效**的音量设定值（只为日志：玩家报「填了没用」时一眼可辨
     *                   这一项是走了自己的值还是在跟随共用默认）
     */
    private record Playable(Tone tone, float volume, int toneVolume) {
    }

    // ------------------------------------------------------------------
    // 「开关 / 音量 / 范围」的查询缓存（策略同 LiftChimePlayer：只在代次变了时才真查）
    // ------------------------------------------------------------------

    private static long cachedGeneration = -1L;
    private static boolean cachedEnabled = true;
    private static int cachedVolume = EscalatorSpeedData.DEFAULT_PSD_HELP_VOLUME;
    // 【09-29】范围拆双维：每类各存「水平（x、z 轴）」与「垂直（y 轴）」两份，
    //   与 /jsr round 的 AAA BBB 同构 —— 判据 = 任一方向超出即越界（见 gain）。
    private static double cachedRoundXz = EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_XZ;
    private static double cachedRoundY = EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_Y;

    /**
     * 【1.23】到站播报 / 进站报站各自的「淡入淡出范围」（{@code /pbmmidiumround}、
     * {@code /pbmarriveround}）。与 {@link #cachedRoundXz} 同一套策略：只在代次变了时才真查一次。
     *
     * <p>★ 为什么三类要各存一份：开关门提示音是**机械事件**（站在门口听最合理），
     * 站台广播 / 进站报站是**说给整个站台听的**，用户希望各自能调 —— 与 1.22 把三类
     * **音量**拆开是同一个理由。三者默认都是水平 16 / 垂直 5 ⇒ 不设时行为与旧版逐位相同。
     * 【09-29】每类的范围拆成 {@code Xz} / {@code Y} 两份（判据见 {@link #gain}）。
     */
    private static double cachedMidiumRoundXz = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_XZ;
    private static double cachedMidiumRoundY = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_Y;

    /** 见 {@link #cachedMidiumRoundXz}。 */
    private static double cachedArriveRoundXz = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_XZ;
    private static double cachedArriveRoundY = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_Y;

    /**
     * 【1.16】关门提示音**强制等待时长**（秒）的缓存 —— 与 {@link #cachedRoundXz} 同一套策略：
     * 只在同步包到达（代次变了）时才真查一次。
     *
     * <p>播放端只在一个分支里读它（{@link #planClose} 的「停站塞不下整条素材」那一支），
     * 但那一支是**每扇门每次开门**都会走到的，所以不能每次现查服务端设置。
     */
    private static int cachedCloseWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS;

    /**
     * 【1.17】「到站播报」的素材 id 缓存（{@code off} = 不播）。同 {@link #cachedCloseWaitSeconds}
     * 一套策略：只在同步包到达（代次变了）时才真查一次。
     *
     * <p>与关门那一套**互不相干** —— 它服务的是「开门音播完之后那段站台广播」，
     * 关门提示音怎么设都不影响它。
     */
    private static String cachedMidiumAudio = EscalatorSpeedData.PSD_MIDIUM_OFF;

    /**
     * 【1.17】「到站播报」的等待秒数缓存（0 ~ +∞，{@link EscalatorSpeedData#clampPsdMidiumWaitSeconds}）。
     *
     * <p>起算点是「**开门音播完**那一刻」（不是门开那一刻）——
     * 用户原话「模组里是开门嘀嘀嘀之后开始播放」。
     */
    private static int cachedMidiumWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS;

    /** 「每扇门单独素材」镜像的代数（服务端同步回来时刷新本地缓存用）。 */
    private static long cachedToneGeneration = -1L;

    /** 上一次「因为开关关掉而静音」时打的日志只打一次。 */
    private static boolean lastEnabled = true;

    /** {@code sounds.json} 里没注册某个事件时只提示一次（资源包缺条目 / 装错 jar）。 */
    private static final Set<ResourceLocation> warnedMissingEvents = new HashSet<>();

    /** 【1.17】已经为「到站播报素材不在库里」告警过的文件名（同一个名字只刷一条日志）。 */
    private static final Set<String> warnedMissingMidium = new HashSet<>();

    /** 【1.21】已经为「进站报站素材不在库里」告警过的文件名。 */
    private static final Set<String> warnedMissingArrive = new HashSet<>();

    // ------------------------------------------------------------------
    // 【1.21】「进站报站」（/pbmarrive）
    //
    //   用户原话：「增加列车进站报站功能，pbmarrive 指令，这个 pbmarrive 音频
    //   即使列车进站也要继续播放，直到播完」；随后**更正了触发口径**：
    //   「玩家设置的 -X 秒是**最近的一班列车到站的时间**：X=-10 就是最近列车剩余 10 秒到站时
    //   开始播放，这个音乐是通过**列车到站剩余时间**播放的，**而不是开门时间**」
    //   「连在一起的屏蔽门…修改其中一个，就要一起修改这一串」。
    //
    //   ★ 触发**只看「最近一班车还剩几秒到站」**（用户点名「看时刻表啊，不要猜」）：
    //   时刻表里下一班的剩余到站毫秒 ≤ |X| 秒 ⇒ 起播（X=-10 ⇒ 剩 10 秒时起播）。
    //   与「门什么时候开」「停站多长」**完全无关** —— 门这一侧只用来认出「这串门属于哪个站台」。
    //   剩余毫秒见 {@link MtrDwellAccess#nearestArrival}。
    //
    //   ★ 与「到站播报」共享的设计（**故意的**）：自己一张表、自己一条 tick 路、
    //   起播之后**没有任何 stop** ⇒ 车进站了也照播到完。
    //   差别只有触发时刻（到站播报 = 开门音之后 + Y 秒；进站播报 = 时刻表说还剩 |X| 秒到站）。
    //
    //   ★ 表按**串锚点**（{@code runKey}）存，不按门：一串门是一个配置单位，
    //   一串里几十扇门同时看见也只该播一次。
    // ------------------------------------------------------------------

    /** 【1.21】每串门最多多久查一次时刻表（tick）—— 阈值以秒计，0.5 秒的节流足够。 */
    private static final int ARRIVE_POLL_TICKS = 10;

    // 【1.21】「已经晚了」的判据直接用 {@link MtrDwellAccess#ARRIVAL_PAST_MS}
    //   （同一件事只留一个数字）：时刻表那一侧已经把这些条目滤掉了，这里再判一次
    //   是为了让窗口语义自洽 —— 客户端卡顿 / 玩家刚走进视距时，还能补上刚停稳的那一班。

    /**
     * 【1.21】判「还是同一班车」的容差（ms）。
     *
     * <p>拿它与「这次算出来的到站时刻」比：差值在容差内 ⇒ 这一班已经播过了，不再播。
     * 没有这条守卫的话，只要「还剩 |X| 秒到站」那个窗口还开着（比如 X=-50 时窗口宽 50 秒），
     * 每一 tick 都会重新满足条件 ⇒ 播报被反复起播。
     */
    private static final long ARRIVE_SAME_TRAIN_MS = 10_000L;

    /** 【1.21】串锚点 → 这一串的进站报站状态。 */
    private static final Map<Long, Arrive> arriveVoice = new HashMap<>();

    // 【09-30 续 9】原「串锚点 → 认到的站台 id」缓存（arrivePlatform）已删：
    //   runKey 升到车站级后，两侧门串共用一个 runKey，按 runKey 缓存站台 id 会把
    //   A 侧的站台 id 串给 B 侧（时刻表串台）；站台 id 现在直接按每侧自己的
    //   DoorView.platformId 取（见 tickArriveAnnounce）。

    /** 【1.21】串锚点 → 上一次查时刻表的游戏刻（节流用）。 */
    private static final Map<Long, Long> arriveLastPoll = new HashMap<>();

    /** 【1.28】进站报站「认不到站台」诊断的节流间隔（tick）：同一串最多每 60 秒一行。 */
    private static final long ARRIVE_FAIL_LOG_EVERY = 1200L;

    /** 【1.28】串锚点 → 上一次打「认不到站台」诊断的游戏刻（节流用，见 tickArriveAnnounce）。 */
    private static final Map<Long, Long> arriveFailNextLog = new HashMap<>();

    /**
     * 【1.21】进站报站的状态。
     *
     * <p>**没有** {@code instance} 字段 —— 与 {@link Arrival} 同一条理由：
     * 不持有句柄，就没有「谁能停它」这个问题。
     */
    private static final class Arrive {

        /** 上一次**为哪一班车**播过（到站时刻 ms）；{@link Long#MIN_VALUE} = 从没播过。 */
        private long firedArrival = Long.MIN_VALUE;

        /**
         * 【09-28】讲述人那一条**自己**的「这一班车念过了」记账。
         *
         * <p>★ 必须与 {@link #firedArrival} 分开：两条广播各有各的窗口（用户点名
         * 「取消借用进站广播」）⇒ 自定义那条的窗口可能是 {@code -30}、讲述人这条是 {@code 0}，
         * 用同一格记账会让先开窗的那一条把另一条整班车压掉。
         */
        private long firedArrivalNarrate = Long.MIN_VALUE;

        /** 上一次起播的游戏刻（日志里点明「起播时时刻表还剩多少毫秒」，方便用户核对）。 */
        private long firedTick = Long.MIN_VALUE;
    }

    /** 「这扇门这一项不播」的哨兵（与「没设置」区分开：没设置 = 内置素材）。 */
    private static final String STOP_SENTINEL = "\u0000STOP";

    private PsdChimePlayer() {
    }

    /**
     * 每客户端刻调用。整体流程：读设置 → 取全部在渲染距离内的门 → 逐门比较门值发现跳变。
     */
    public static void onClientTick(Minecraft mc) {
        // 【09-29】字幕的消失判据与讲述人「越界即停」护栏排在最前：它们不依赖门快照
        //   （字幕判据是播报声源的**固定坐标**，列车进站那一刻门快照短暂变空也不受影响），
        //   进不进世界都得跑 —— 没进世界时它们自己会撤下 / 复位。
        TrainAnnounceSubtitle.tick(mc);
        TrainAnnounceNarrator.tickRangeGuard(mc);
        if (mc.level == null || mc.player == null) {
            reset(mc);
            return;
        }
        // 0) 【1.23】推进「列车内倍率」的 1 秒斜坡。排在最前面、且**不走任何早退分支** ——
        //    它是玩家自己的状态（在不在车里），与「这一 tick 有没有门、提示音开没开」无关；
        //    放在这里才能保证「哪怕这一刻一条声音都没在播，斜坡也在按 tick 走」，
        //    于是下一声起播时倍率已经是当前值（不会从旧值补爬一段）。
        updateTrainRamp(mc);

        // 1) 设置逐 tick 现算（带代次缓存）：指令改完下一个 tick 就生效，不用重进世界。
        //    psdHelpEnabled 内部已经会把「开关 / 音量 / 范围」三项一起刷新，
        //    所以这里**不要**再单独调一次 refreshSettings（那是个恒等调用）。
        // 【1.20】总开关也按门存了 ⇒ 早退条件从「维度默认关」放宽成
        //   「维度默认关 **且** 没有任何一扇门自己开着」（否则「单独打开某一扇门」是死配置）。
        //   逐扇门的判断在 resolvePlayable 第 ⓪ 道闸门上。
        boolean enabled = psdHelpEnabled(mc);
        if (!enabled && !EscalatorSpeedManager.hasAnyDoorPsdHelpOn(mc.level)) {
            if (lastEnabled) {
                LOGGER.info("[SmoothLift/PsdChime] 屏蔽门开关门提示音已关闭（/pbmmusic on 可打开）");
                lastEnabled = false;
            }
            reset(mc);
            return;
        }
        if (!lastEnabled) {
            LOGGER.info("[SmoothLift/PsdChime] 屏蔽门开关门提示音已恢复播放");
            lastEnabled = true;
        }

        // 1.5) 【1.17】到站播报：**排在取门快照之前**，而且**不走任何早退分支**。
        //   ★ 这是「车出站也继续播」能不能成立的关键：列车开走之后
        //   `PsdDoorTracker.snapshot()` 会变空、下面那条 `doors.isEmpty()` 会 `return`，
        //   如果把它排在那之后，播报压根不会被触发/回收（甚至会在车开走那一刻被 reset 掉）。
        //   它只依赖自己那张表和 `mc.level.getGameTime()`，与门在不在快照里无关。
        tickArrivalAnnounce(mc);

        // 2) 取全部「还活着」的门（这份快照里只有真正的站台门，幕墙在数据源头就被滤掉了）。
        List<PsdDoorTracker.DoorView> doors = PsdDoorTracker.snapshot();
        // 1.7) 【1.21】进站报站：同样排在 `doors.isEmpty()` 早退**之前**。
        //   ★ 与到站播报的差别：它**需要**门快照（要拿门的位置当声源），
        //   所以只能排在快照之后；但「车进站了也继续播到完」不靠这条 tick 路 ——
        //   靠的是「起播之后没人停它」（下面 tickArriveAnnounce 里一句 stop 都没有）。
        //   【1.28.1204】/jsr 总开关关着时，方法开头第一道闸就短路（连时刻表查询都不跑）。
        tickArriveAnnounce(mc, doors);
        // 【09-30 续 3】站台广播（讲述人）：与到站播报同一套「排计划 → 到点起播」结构，
        //   排在门快照之后 —— 起播那一 tick 还要拿快照做「多站台就近压制」的判据。
        tickMidiumNarrator(mc, doors);
        if (doors.isEmpty()) {
            // 【1.15】门一下子全没了（走远 / 区块卸载）时，挂起中的关门提示音要先冲出去 ——
            // 否则那一声就凭空消失了。冲的方式就是「量不到门速」的兜底：按原样从头播一次。
            resumeClose(mc, doors);
            reset(mc);
            return;
        }
        Vec3 player = mc.player.position();

        // 3) 记录/更新每扇门的状态；开门 / 关门提示音**每一扇各调一次、距离按整串算**。
        //
        //    【1.15】noteCycle 对**所有**门都记（「上一轮走了多久」是提前量的唯一来源，
        //    每扇门都得各自学各自的那一份）—— 这是数据采集，不出声。
        //
        //    ★★提示音「怎么发声」这条口径改过三版，**当前版 =【09-30 续 10】**（见下面那段）：
        //      ·【1.23】~【1.26】每一扇各调一次 + **本扇门**距离 ⇒ 现场（LOG8）听感是灾难：
        //        一个站台十几扇门，同一声 dooropen / 嘀嘀前后错开几百毫秒叠十来遍 ——
        //        用户听到的「早几秒一个 + 准点一个」的「多出来的提示音」主要就是它；
        //      ·【09-30 续 6】改成 **一串只播一个**（detect 只对本串最近那扇调）——
        //        用户随后点名「忽视之前我说的，直接按照 fabric 1.20.1 的做」；
        //      ·【09-30 续 7】于是回到「**每一扇**各调一次 + 本扇门距离」（1.20.1 口径）——
        //        可长度 53~175 格的门串被 16 格射程切成好几截，就是这一轮用户报的「切成两半」；
        //      ·【09-30 续 10】**当前**：每一扇照旧各调一次（保住「沿站台一排门同时响」），
        //        但距离改成**本串里离玩家最近的那一扇**，整串同增益、一起淡出。
        //    「一串」的身份 = runKey（【1.27】起就是站台）。
        //    ★ 玻璃幕墙 / 幕墙尾部能进这份快照、但**只当播报成员**（【1.29】用户点名：
        //    「门和幕墙以及幕墙尾部一起播报的是 pbmarrive/pbmmidium，只有门的播报是铃声」）：
        //    PsdDoorTracker 会把本串里非门的家族方块注册成 door()==false 的快照项，
        //    到站/进站播报（nearestPerRun / nearestInRun）把它们算进「本串最近」，
        //    铃声链路则被上面 / 下面几条 `!door.door()` 过滤掉 ——「幕墙不发声」由结构保证。
        long gameTime = mc.level.getGameTime();
        Set<Long> seen = new HashSet<>();
        // ★★【09-30 续 10】门提示音的距离口径**并回「按串算」**（用户点名「修成连在一起的门串
        //   播报，别走一半播报断了」）：快照里**每一扇**门照旧各调一次 detect（这一层的结构
        //   没动，见上一条），但实例每 tick 现算的距离不再是「本扇门」而是**本串里离玩家最近
        //   那一扇门** —— 与到站 / 进站播报同一把尺子（`play` 尾两参给 door.runKey() + 单维）。
        //   为什么必须这样：站台门串实测跨度 53~175 格（LOG16：78 个站台里 24 个 ≥100 格），
        //   而提示音射程默认只有 16 格 ⇒ 按「本扇门」算的话，玩家站在站台任何位置都只有身边
        //   约 6 扇在响、其余整段静默 ——「车头响、中间渐弱、车尾整段没声音」正是用户报的
        //   「切成两半」。按「本串最近门」算 ⇒ 只要人在这一串旁边，整串按同一个增益一起响；
        //   走离这一串则整串**一起**淡出（nearestInRun 取不到 ⇒ 增益 0），不再剩半截。
        //   ⇒ 实例数**没有变多**：{@code resolvePlayable} 里那道
        //   {@code gain(distance, ROUND_TONE) * volume <= 0 ⇒ return} 判的仍是**本扇门**的
        //   距离 —— 那只是「要不要为这扇建实例」的闸（典型 4~6 扇），不是音量；
        //   音量由实例每 tick 按**串**现算。
        //   ★【09-30 续 7】的「按本扇门算」与【09-30 续 6】的「一串只播一个」都不是当前口径：
        //   前者把一串按 16 格切成好几截，后者会把「沿站台一排门同时响」的听感整个抹掉。
        //   ★ noteCycle（周期 / 门程学习）仍然对**所有**门跑 —— 那是数据采集，不出声。
        //   ★ 玻璃幕墙 / 幕墙尾部能进这份快照、但**只当播报成员**（【1.29】用户点名）：
        //   PsdDoorTracker 会把本串里非门的家族方块注册成 door()==false 的快照项，
        //   到站/进站播报（nearestPerRun / nearestInRun）把它们算进「本串最近」，
        //   铃声链路则被 `!door.door()` 过滤掉 ——「幕墙不发声」由结构保证。
        for (PsdDoorTracker.DoorView door : doors) {
            // 【1.29】铃声只属于门：幕墙 / 幕墙尾部（door()==false）不进铃声链路。
            if (!door.door()) {
                continue;
            }
            seen.add(door.key());
            float prev = lastDoor.containsKey(door.key())
                    ? lastDoor.get(door.key()) : door.fraction();
            noteCycle(door, prev, door.fraction(), gameTime);
            double dist = player.distanceTo(new Vec3(door.x(), door.y(), door.z()));
            detect(mc, door, prev, dist);
            lastDoor.put(door.key(), door.fraction());
        }
        // 清掉这一帧已经不在这份快照里的门（走远了 / 被删了），避免 Map 无限长大。
        lastDoor.keySet().retainAll(seen);

        // 4) 【1.16】「停站不够放完人声」那一轮的兜底人声：到点就播、播完就收、门一动就掐断。
        //    排在下面两条之前：它一旦被掐断，紧接着那声关门嘀嘀（由 resumeClose 那条剪头路放）
        //    才不会被压在人声上面。
        tickForcedVoice(mc, doors);
        // 5) 【1.15】把到点的「整段关门提示音」播出去（提前量已经由开门那一瞬排好）。
        //    排在挂起那条路之前：两条路互斥（排过的门不会再进 pendingClose），这里只是把顺序定死。
        firePlannedClose(mc, doors);
        // 6) 【1.15】把上一 tick 挂起的**关门**提示音播出去（用这一刻的门值量出门速 → 算剪头偏移）。
        //    放在门的循环之后：挂起用的是上一 tick 的门值，这里用的是这一 tick 的，才是「两个采样点」。
        resumeClose(mc, doors);
    }

    /**
     * 【1.15】维护「开门 → 全关」这一轮的长度 —— 提前量的**唯一来源**（见 {@link #learnedCycleTicks}）。
     *
     * <p>对**所有**门都记，不只是最近那扇：最近的门会换（玩家走两步、列车动一格），
     * 而每扇门的停站时长要各自学各自那一份。
     */
    private static void noteCycle(PsdDoorTracker.DoorView door, float prev, float now, long gameTime) {
        long key = door.key();
        if (prev <= EDGE && now > EDGE) {
            cycleOpenTick.put(key, gameTime);                 // 这一轮开门了
            // 新一轮开始：上一轮的「已提前播过」标记不能留到这一轮，
            // 否则这一轮关门时会被它误吞掉一声（正常情况下全关那一刻已经清过，这里是保险）。
            plannedCloseFired.remove(key);
            wholePlaying.remove(key);
            // 【1.16】上一轮那条「兜底人声」的计划同样作废：它挂在**上一轮**的开门时刻上
            //   （起播点在开门后的几百 tick 处），留到这一轮就会在停站中途到点 ⇒ 凭空响一段人声。
            //   ★ 这里只摘计划、不停声音：正常路径上那条声音早就在「播完」或「门开始关」时
            //   被 tickForcedVoice 收掉了（两处都在下一次开门之前必然发生）。
            forcedVoice.remove(key);
            // 【1.15 · 第六轮】上一轮如果没跑到「全关」（中途走了 / 区块卸载），
            //   留下的那个「门开始关」的时刻就作废了 —— 不清掉的话，这一轮全关时
            //   拿它算出来的「门程」会是一个荒谬的大数，把跨门共用的门程带歪。
            closeStartTick.remove(key);
        } else if (prev > EDGE && now <= EDGE) {              // 变成「全关」= 这一轮跑完了
            Long openedAt = cycleOpenTick.remove(key);
            if (openedAt != null && gameTime > openedAt) {
                long cycle = gameTime - openedAt;
                Long before = learnedCycleTicks.put(key, cycle);
                // 只在「学到新值 / 变化超过 1 秒」时打日志：正常每站一轮都会刷新一次，
                // 全打会刷屏；变化大说明停站时长改了（换车次 / 时刻表变了），值得留一条。
                if (before == null || Math.abs(before - cycle) > 20) {
                    LOGGER.info("[SmoothLift/PsdChime] 门 @{} 学到这一轮「开门 → 全关」= {}ms"
                                    + "（下一轮按它排提前量，让关门提示音的结尾落在门上）",
                            posText(door), cycle * 50L);
                }
                // ★【1.15 · 第十轮】交叉核对：这一轮如果排提前量用的是**时刻表**（只有这一扇门
                //   的**第一次**停站会走那一支），现在有了实测值，对一次。
                //   对不上 = **MTR 在运行时改掉了停站时长**，这是「反推公式明明说该有声音、
                //   实际却没有」那条缝的唯一现场证据：
                //     · 侧线设置「晚点缩短停站时间」→ 停站被砍短（可以砍到只剩约 4.2 秒）；
                //     · 侧线设置「早到延长停站」    → 停站被拉长；
                //     · 被信号憋住（前方闭塞区间不空闲）→ 只会更晚。
                //   注意：**实测值永远优先**（它是①），所以下一轮起会自动改用实测值，
                //   不需要用户做任何事 —— 这里只是把原因说清楚。
                Long predicted = timetableCycleTicks.remove(key);
                if (predicted != null) {
                    long diff = predicted - cycle;
                    if (Math.abs(diff) > TIMETABLE_DISAGREE_TICKS) {
                        if (timetableDisagreeWarned.add(key)) {
                            LOGGER.warn("[SmoothLift/PsdChime] 门 @{} 时刻表算的周期 {}ms 与实测 {}ms "
                                            + "差了 {}ms ⇒ **站台数据里的停站时长与实际不符**："
                                            + "头号来路是「停站时长刚改过、客户端那一份还没同步过来」（这时"
                                            + "「认到站台」那条会一直显示旧值，看下面第 ① 条清单）；"
                                            + "其次是侧线设置「晚点缩短停站时间」会把停站砍短、"
                                            + "「早到延长停站」会拉长；被信号憋住只会更晚。"
                                            + "从下一轮起按「时刻表与实测里更大的那个」排，用户不需要做任何操作",
                                    posText(door), predicted * 50L, cycle * 50L, Math.abs(diff) * 50L);
                        }
                    } else if (timetableAgreeLogged.add(key)) {
                        LOGGER.info("[SmoothLift/PsdChime] 门 @{} 交叉核对通过：时刻表算的 {}ms "
                                        + "与实测 {}ms 一致（差 {}ms）",
                                posText(door), predicted * 50L, cycle * 50L, Math.abs(diff) * 50L);
                    }
                }
                // 【1.15 · 第六轮】同时记进「跨门共用」那一份：下一个站**第一次**见到的门
                //   就靠它排提前量（否则每一站都从零开始学 ⇒ 永远只有嘀嘀，见 globalCycleTicks）。
                if (globalCycleTicks <= 0 || Math.abs(globalCycleTicks - cycle) > 20) {
                    LOGGER.info("[SmoothLift/PsdChime] 跨门共用周期更新为 {}ms —— "
                                    + "别的屏蔽门第一次停站时会按它排提前量",
                            cycle * 50L);
                    globalCycleTicks = cycle;
                }
                // 【1.15 · 第六轮】门程（门开始关 → 全关）：判「整段会不会拖过门全关」要用它。
                Long closedFrom = closeStartTick.get(key);
                if (closedFrom != null && gameTime > closedFrom) {
                    long travel = gameTime - closedFrom;
                    if (globalTravelTicks <= 0 || Math.abs(globalTravelTicks - travel) > 5) {
                        LOGGER.info("[SmoothLift/PsdChime] 跨门共用门程（关门 → 全关）= {}ms",
                                travel * 50L);
                        globalTravelTicks = travel;
                    }
                }
            }
            plannedCloseFired.remove(key);                    // 这一轮彻底结束
            // 【1.15】这一轮既然已经全关，那这一轮的提前量计划就**永远错过了**：
            //   它要么已经播过（上面刚清掉标记），要么就是没排上 / 排晚了。
            //   留着它会在**下一轮停站**的中途到点 ⇒ 门正好全开，于是凭空响一声关门提示音
            //   （典型来路：门中途离开快照又回来，`lastDoor` 被 retainAll 剪过 ⇒ 这一轮的全关
            //   跳变没被观测到，计划就一直挂着）。这里摘掉，等于把「计划只对排它的那一轮有效」
            //   这条语义显式化。
            plannedCloseStart.remove(key);
            wholePlaying.remove(key);
            closeStartTick.remove(key);
            // 【09-30 续 5】**挂起中的关门请求也必须摘掉** —— 这是 LOG14 现场那条
            // 「多出来的开门-关门提示音」的根因：门开始关时挂起的「量门速」请求
            // （pendingClose），如果这一轮的全关跳变是**出站那一刻门值瞬间跳到全关**
            // 被观测到的（真实关门采样还没拿到），请求就一直挂在表里；等到**下一班车**
            // 进站门值再一动，它才被补播 —— 而且那时单帧量出的「门还剩」是垃圾值，
            // 剪头算式算出负数 ⇒ **整段素材连人声一起放出来**，听感就是「早几秒多出
            // 一声开门-关门提示音」。全关 = 这一轮彻底结束，这一轮的关门请求一并作废。
            pendingClose.remove(key);
            // 【1.16】这一轮既然全关，兜底人声那一轮也结束了（正常早在「门开始关」时就收掉了；
            // 这里是「那一轮的全关跳变没被观测到」时的保险）。
            forcedVoice.remove(key);
        }
    }

    /**
     * 【1.15】开门那一瞬，把这一轮的**整段**关门提示音排到「提前量到点」那一 tick。
     *
     * <p>提前量 = 素材时长 − **上一轮实测周期**。推导：结尾要落在
     * {@code 开时刻 + 周期}（= 下一轮门全关的时刻），所以起播时刻 = 开时刻 + 周期 − 素材时长。
     *
     * <p>下面几种情况**什么都不排**，直接交给关门那一瞬的「剪头对齐结尾」
     * （{@link #resumeClose}）—— 那条路保证「结尾正好落在门上」永远成立：
     * <ul>
     *   <li>三个来源（本扇门实测 / MTR 时刻表 / 跨门借用）**一个都拿不到** ——
     *       也就是「刚进世界 + 读不到站台数据」这唯一一种组合；</li>
     *   <li>素材比整轮还长（{@code 周期 ≤ 素材时长}）⇒ 提前量是负数，排不出来；</li>
     *   <li>这一档不要人声（{@code default-s}）或素材没有两段结构 ⇒ 本来就该走剪头；</li>
     *   <li>这一项此刻不播（子开关 / 这一扇门设为不播 / 音量算出 0）。</li>
     * </ul>
     * <p>★ 第一条的判据经历过两轮缩小，值得记住结论：
     * <ol>
     *   <li>【1.15 · 第六轮】「在这扇门前没停过」→「**这个世界**里没跑完过一轮」：
     *       本扇门没有实测值时**借用**跨门共用的那一份（见 {@link #globalCycleTicks}），
     *       否则玩家一走动（跑线 / 换站台）就永远停在「第一次见这扇门」上，每一站都只有嘀嘀；</li>
     *   <li>【1.15 · 第八轮】再缩到「三个来源都没有」：**MTR 时刻表**（{@link MtrDwellAccess}）能在
     *       任何一次停站之前就给出**这一站自己的**周期，于是「跨门借用」退居兜底。
     *       这一层才是真正对用户那句「不要靠猜」的回答 —— 前两轮都还是在猜，
     *       只是一个猜得比一个准。</li>
     * </ol>
     */
    private static void planClose(Minecraft mc, PsdDoorTracker.DoorView door, Playable playable,
                                  Playable openPlayable) {
        Tone tone = playable.tone();
        int duration = tone.durationMs();
        long now = mc.level.getGameTime();
        // ★ 只有「带人声、且要人声」那一档需要提前量：它长（10.8 秒 >> 门程 4 秒），不提前必然晚；
        //   纯嘀嘀那档剪个头就能把结尾对上，不需要猜，也就不会猜错。
        if (!tone.announce() || tone.splitMs() <= 0 || duration <= 0) {
            // ★★【1.15 · 第十轮】这里以前是**静默 return**。后果非常坏：用户报
            //   「停站时间怎么改都没人声」查了三轮都查不出来 —— 因为看日志只看到
            //   「开始开门 → 播放 …」，完全看不出是被这道闸门拦下的，而这道闸门
            //   **与停站时长毫无关系**（所以调停站时长当然永远没用）。
            //   现在把「是哪一条」和「该怎么办」一起打出来，并且**点名**档位名 ——
            //   「默认（短）」和「默认」用的是同一段素材，界面上极易看混。
            if (!tone.announce()) {
                // 内置档里只有「默认（短）」（default-s）会把 announce 置 false（见 resolveTone）。
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开门：★ 这一轮**不会有人声播报** —— "
                                + "关门提示音这一项选的是「默认（短）」（default-s），"
                                + "它与「默认」是同一段素材、按设计只播嘀嘀。"
                                + "想听人声：石斧界面把它换成「默认」，"
                                + "或 /pbmmusic close default",
                        posText(door));
            } else if (tone.splitMs() <= 0) {
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开门：★ 这一轮**不会有人声播报** —— "
                                + "素材「{}」里没有「播报 + 嘀嘀」两段结构（分界点 {}ms）。"
                                + "换一段带语音播报的素材就会有人声",
                        posText(door), toneLabel(tone), tone.splitMs());
            } else {
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开门：★ 这一轮**不会有人声播报** —— "
                                + "素材「{}」的时长量不到（{}ms），算不出提前量",
                        posText(door), toneLabel(tone), duration);
            }
            return;
        }
        // 周期的三个来源，**按可信度从高到低**取（顺序本身就是设计，别调换）：
        //
        //   ① 本扇门自己的实测值 —— 最准：它连「这一站实际是不是被信号憋住了」都算进去了
        //      （MTR 发车要满足「前方闭塞区间空闲」，被憋住时门会一直开着，周期比时刻表长）。
        //   ② 【1.15 · 第八轮】MTR **时刻表**（站台数据里的停站时长 → 周期）—— 不用等实测，
        //      **第一次停站就有**，而且是**这一站自己的**值 ⇒ 用户点名的「不要靠猜」。
        //      ★ 它必须排在 ③ 前面：停站时长是**每个站台各自的**属性（A 站 10 秒、B 站 20 秒），
        //      拿别的站学到的周期来排这一站的提前量必然错一截 —— 那才是真的「猜」。
        //   ③ 跨门借来的（第六轮加的兜底）—— 只在 ① ② 都拿不到时用（刚进世界、
        //      或没装 MTR4 读不到站台数据）。它保证「哪怕一个新世界，第二站就有声音」。
        //
        //   三个都没有 ⇒ 这一轮不排提前量，交给关门那一刻的「剪头对齐结尾」
        //   （那条路保证「结尾落在门上」永远成立，代价是这一轮没有人声）。
        // 素材时长 → tick（向上取整）。判据与起播时刻都用它。
        long leadTicks = (duration + 49) / 50;
        long travel = globalTravelTicks > 0 ? globalTravelTicks : MtrDwellAccess.DEFAULT_TRAVEL_TICKS;
        long ownLearned = learnedCycleTicks.getOrDefault(door.key(), -1L);
        long borrowed = globalCycleTicks;

        // ① MTR **时刻表**（这一站自己的、稳定的值）——**每次开门都读**。
        //   ★【1.15 · 第十二轮】以前只在「本扇门没有实测值」时才读，后果非常坏：
        //   停站时长改过之后本扇门的历史值还是旧的那一份（偏短），于是**永远看不到你改的那个数**
        //   —— 现场（LOG2，用户改完 30 秒后）：门一直拿旧的 6400ms 判「塞不下」，
        //   直到后面某一站碰巧被车流憋久了才学到长周期。这就是「不稳定」的来源。
        long dwellMs = MtrDwellAccess.dwellMsAt(door.x(), door.y(), door.z());
        long fromTimetable = MtrDwellAccess.cycleTicksForDwell(dwellMs, travel);
        if (fromTimetable > 0L) {
            // 记下来，等这一轮跑完（noteCycle 学到实测值）时交叉核对一次：
            //   两者差太多 ⇒ 停站时长被改过 / 站台数据没同步（见 TIMETABLE_DISAGREE_TICKS）。
            timetableCycleTicks.put(door.key(), fromTimetable);
        }

        // ② 周期 = **所有可得证据里最大的那一个**（时刻表 / 本扇门实测 / 跨门实测）。
        //   ★【第十二轮】为什么取「最大」而不是「最可信的那一个」：同一个站台实测到的周期
        //   可以是 4s / 15s / 24s / 26s（车流会把停站**掐短**），也就是说**只会短、不会长**。
        //   而两种估错的代价**不对称**：
        //     · 估短 ⇒ 周期塞不下素材 ⇒ 这一轮**直接放弃**（一定没有声音）；
        //     · 估长 ⇒ 起播那一刻门若已经关上，{@code firePlannedClose} 会把这次计划丢掉
        //              （不会错放）；门若还开着就正常播完，结尾照样落在门上。
        //   ⇒ 宁可估长。这一条直接决定「改完停站时长还要不要等一两站才有声音」。
        long best = -1L;
        int winner = -1;                                   // 0=时刻表 1=本扇门实测 2=跨门实测
        if (fromTimetable > best) {
            best = fromTimetable;
            winner = 0;
        }
        if (ownLearned > best) {
            best = ownLearned;
            winner = 1;
        }
        if (borrowed > best) {
            best = borrowed;
            winner = 2;
        }
        if (best <= 0L) {
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开门：拿不到周期"
                            + "（本扇门没实测过 / MTR 时刻表读不到 / 跨门共用的也没有），"
                            + "这一轮不排提前量（关门时剪头对齐结尾，这一轮无人声）", posText(door));
            return;
        }
        String source = winner == 0 ? "MTR 时刻表（这一站停站 " + dwellMs + "ms）"
                : winner == 1 ? "本扇门上一轮实测 " + ownLearned * 50L + "ms"
                : "跨门学到的 " + borrowed * 50L + "ms";

        if (best <= leadTicks) {
            // ★★【1.16】这一支**以前是「这一轮干脆没有人声」**（只提示把停留时长设到多少）。
            //   用户点名要求把它改成**兜底**：「停站时间不够放完整个音频，那就固定开门音效
            //   播放完后等待 X 秒之后播放，车门关闭的嘀嘀嘀不影响，但是一旦到关门时间嘀嘀嘀
            //   开始播放，就立即切断正在播放的人声提示，不管播放到哪里。」
            //
            //   语义（与用户那三句一一对应）：
            //     · 「+X 秒」= 从**开门音效播完那一刻**起算（不是从门开算），见 openDurMs；
            //     · 「嘀嘀嘀不影响」= 嘀嘀仍然由**关门端那条剪头路**负责（结尾精确落在门上），
            //       所以这段人声必须在 {@code splitMs} 处收掉，绝不让它自己的嘀嘀响出来；
            //     · 「立即切断、不管播到哪里」= 门一进入关门行程就停（见 tickForcedVoice）。
            //
            //   ★ 停站**够长**时走的是上面那条「整段提前播」的路，那里**根本不读**
            //   cachedCloseWaitSeconds —— 这就是用户要的「够长就忽略这个等待时间」。
            long needDwell = MtrDwellAccess.minDwellForMaterialMs(duration, travel);
            long openDurMs = openPlayable != null ? openPlayable.tone().durationMs() : 0L;
            long openTicks = openDurMs > 0L ? (openDurMs + 49L) / 50L : 0L;
            // 【1.20】强制等待也按门存了：先把**这一扇门**的生效值取回这个字段
            //   （没单独设过时它返回的就是维度默认，由 getDoorPsdCloseWaitSeconds 负责回落）。
            //   赋值回字段是有意的：在这条路上它就是「这一轮要用的等待秒数」，
            //   下面那行夹取与日志都读它 —— 每一处用之前都重新取，所以不会串到别的门。
            cachedCloseWaitSeconds =
                    EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(mc.level, door.runKey());
            long waitTicks = Math.max(0, cachedCloseWaitSeconds) * 20L;   // 1 秒 = 20 tick
            long startTick = now + openTicks + waitTicks;
            long voiceEndTick = startTick + (tone.splitMs() + 49L) / 50L;
            forcedVoice.put(door.key(), new ForcedVoice(startTick, voiceEndTick, tone, door, playable.volume()));
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开门：周期 {}ms（{}）塞不下整条素材 {}ms ⇒ 走**强制等待**："
                            + "开门音（{}ms）播完后再等 {} 秒（{} tick），"
                            + "即 {} tick 后（约 {}ms）播**语音播报**；"
                            + "门一开始关就把这段人声掐断（不管播到哪里），关门嘀嘀照旧对齐门关上{}",
                    posText(door), best * 50L, source, duration,
                    openDurMs, cachedCloseWaitSeconds, waitTicks,
                    startTick - now, (startTick - now) * 50L,
                    needDwell > 0L
                            ? "。想让**整段**（人声+嘀嘀）都播完：把这一站的停留时长设到 ≥ " + needDwell
                            + "ms（" + (needDwell / 1000L) + " 秒）—— 改的是上面「认到站台」那一条；"
                            + "等待时长用 /pbmclosewait <秒> 或石斧界面的输入框调"
                            : "");
            return;
        }
        if (winner == 2) {
            // 「改这一站的停站时间完全没用」的真正现场：用的是**别的站**学到的周期。
            LOGGER.warn("[SmoothLift/PsdChime] 门 @{} 开门：这一站读不到能用的时刻表 ⇒ 周期借用「别的站」"
                            + "学到的 {}ms（本扇门实测 {}ms、时刻表 {}ms）。"
                            + "**改这一站的停站时间不会有任何效果**，"
                            + "请先查上面「认站台 / 读站台」那几条日志",
                    posText(door), borrowed * 50L,
                    ownLearned > 0L ? ownLearned * 50L : -1L,
                    fromTimetable > 0L ? fromTimetable * 50L : -1L);
        } else if (winner != 0 && fromTimetable > 0L) {
            // ★【第十二轮】用户点名的现场：时刻表算出来偏短（数据没同步到客户端 / 本扇门历史值旧），
            //   而实测证明这一站的门其实开得更久 ⇒ 按实测排（下一段日志会说明为什么「取大」是安全的）。
            LOGGER.warn("[SmoothLift/PsdChime] 门 @{} 开门：**时刻表只算出 {}ms**（这一站停站 {}ms、"
                            + "塞不下素材 {}ms），但{}证明这一站的门开得更久（{}ms）⇒ 按 {}ms 排。"
                            + "常见来路：① 停站时长刚改过、本扇门历史值还是旧的；"
                            + "② 新的停站时长还没同步到这个客户端",
                    posText(door), fromTimetable * 50L, dwellMs, duration,
                    winner == 1 ? "本扇门上一轮实测" : "别的门的实测",
                    best * 50L, best * 50L);
        }
        if (winner == 0) {
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开门：周期取自 {}", posText(door), source);
        }
        long fireTick = now + best - leadTicks;
        plannedCloseStart.put(door.key(), fireTick);
        LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开门 → 按周期 {}ms（{}）排出关门提示音："
                        + "{} tick（{}ms）后**整段**起播，结尾对准门关上那一刻",
                posText(door), best * 50L, source,
                best - leadTicks, (best - leadTicks) * 50L);
    }

    /**
     * 【1.15】把到点的「整段关门提示音」播出去（每 tick 一次，排在 {@link #resumeClose} 之前）。
     *
     * <p>四种情况**丢掉这次请求、绝不补播**（补播只会让一条本该落在门上的提示音落在别处）：
     * 门已经不在快照里（走出渲染距离 / 被拆）；到点时门已不是「全开」（这一轮被打断，
     * 比如被反复开关）；已经晚过 {@value #PLAN_GRACE_TICKS} tick（这一轮早过去了）；
     * 这一项此刻不播。前两种交给**关门那一刻的剪头**，第三条则什么都不做。
     */
    private static void firePlannedClose(Minecraft mc, List<PsdDoorTracker.DoorView> doors) {
        if (plannedCloseStart.isEmpty()) {
            return;
        }
        long now = mc.level.getGameTime();
        Map<Long, PsdDoorTracker.DoorView> live = new HashMap<>();
        for (PsdDoorTracker.DoorView door : doors) {
            // 【1.29】幕墙 / 幕墙尾部不是门：关门提示音这条链路只认门
            //   （用户点名：「只有连在一起的屏蔽门的门的播报是铃声」）。
            if (!door.door()) {
                continue;
            }
            live.put(door.key(), door);
        }
        Iterator<Map.Entry<Long, Long>> it = plannedCloseStart.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            PsdDoorTracker.DoorView door = live.get(entry.getKey());
            if (door == null) {
                it.remove();
                continue;
            }
            if (now < entry.getValue()) {
                continue;
            }
            it.remove();
            if (now > entry.getValue() + PLAN_GRACE_TICKS) {
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 的提前量早就过了（晚 {} tick，中间这一轮多半已经开过了），"
                                + "不补播，交给关门那一刻的剪头", posText(door), now - entry.getValue());
                continue;
            }
            if (door.fraction() < 1.0f - EDGE) {
                // 【第十轮】这一支的**典型来路**：排提前量时按「时刻表说的停站时长」算，
                //   而 MTR 实际上把停站砍短了（侧线「晚点缩短停站时间」）⇒ 等我们到点时门早关上了。
                //   这就是「人声明明该有、实际没有」的那一刻，所以提示要指向真正的原因。
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 的提前量到点了，但门已不是全开（当前 {}）"
                                + "⇒ 这一轮不提前播，交给关门那一刻的剪头。"
                                + "（常见原因：这一轮实际停站比排提前量时用的那个值**短** ——"
                                + " MTR 的「晚点缩短停站时间」或上一轮留下的旧值；下一轮会改用实测值）",
                        posText(door), String.format("%.2f", door.fraction()));
                continue;
            }
            Vec3 p = mc.player.position();
            double distance = p.distanceTo(new Vec3(door.x(), door.y(), door.z()));
            Playable playable = resolvePlayable(mc, door, "close", distance);
            if (playable == null) {
                continue;
            }
            PsdMusicInstance instance = play(mc, playable.tone(), door, playable.volume(), 0);
            boolean ok = instance != null;
            if (ok) {
                // 记「实际起播的那一 tick」：关门那一刻要靠它算「这条素材本该何时播完」，
                // 从而区分「还在响（预期）」与「早播完了（停站比上一轮长，要补一声）」（见 detect）。
                plannedCloseFired.put(door.key(), now);
                // 【1.15 · 第六轮】同时留一个句柄：借来的周期偏长时，关门那一刻要
                //   **掐掉**这条还在响的素材、改用剪头补一声（见 resumeClose）。
                wholePlaying.put(door.key(), instance);
            }
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 提前量到点 → {} {} **整段**"
                            + "（距玩家 {} 格；音量 {}，维度默认 {}；"
                            + "结尾应落在门关上那一刻）",
                    posText(door), ok ? "播放" : "播不出（素材缺失 / 解码失败）",
                    toneLabel(playable.tone()), String.format("%.1f", distance),
                    playable.toneVolume(), cachedVolume);
        }
    }

    /**
     * 【1.16】「停站不够放完人声」那一轮的兜底人声：**到点播、播完收、门一动掐**（每 tick 一次）。
     *
     * <p>三条结束路径都在这里，缺一条就会留下「无主的声音」：
     * <ol>
     *   <li><b>到点了但门已经不在全开</b> ⇒ 不播（这一轮的人声窗口已经没了），并说清两个来路
     *       （等待设得比停站长 / 这一轮停站太短）；</li>
     *   <li><b>语音播报播完</b>（{@code now >= voiceEndTick}）⇒ 收掉它。
     *       收在分界点而不是「让它自然播完」是**必须**的：素材在语音之后还有 0.5 秒静音和
     *       17 个嘀嘀，而这一档的嘀嘀由关门端剪头路负责 ⇒ 不收就会两处嘀嘀叠响；</li>
     *   <li><b>门开始关</b>（{@code fraction} 掉出「全开」）⇒ 立刻掐断，不管播到哪里。</li>
     * </ol>
     *
     * <p>门从快照里消失（走出渲染距离 / 被拆）时也收掉：那段人声属于**这一扇门**，
     * 门都看不到了还让它拖到下一次开门，就会变成一段不知道从哪来的语音。
     */
    private static void tickForcedVoice(Minecraft mc, List<PsdDoorTracker.DoorView> doors) {
        if (forcedVoice.isEmpty()) {
            return;
        }
        long now = mc.level.getGameTime();
        Map<Long, PsdDoorTracker.DoorView> live = new HashMap<>();
        for (PsdDoorTracker.DoorView door : doors) {
            // 【1.29】幕墙 / 幕墙尾部不是门：关门提示音这条链路只认门
            //   （用户点名：「只有连在一起的屏蔽门的门的播报是铃声」）。
            if (!door.door()) {
                continue;
            }
            live.put(door.key(), door);
        }
        Iterator<Map.Entry<Long, ForcedVoice>> it = forcedVoice.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, ForcedVoice> entry = it.next();
            long key = entry.getKey();
            ForcedVoice plan = entry.getValue();
            PsdDoorTracker.DoorView door = live.get(key);
            if (door == null) {
                it.remove();
                stopForcedInstance(mc, plan, "门已不在渲染距离内（被拆 / 走远了）");
                continue;
            }
            if (!plan.fired) {
                if (now < plan.startTick) {
                    continue; // 还没到点
                }
                plan.fired = true;
                if (door.fraction() < 1.0f - EDGE) {
                    it.remove();
                    LOGGER.info("[SmoothLift/PsdChime] 门 @{} 的强制等待到点了，但门已经不在全开（当前 {}）"
                                    + "⇒ 这一轮的人声**不补播**。常见来路：① 等待时长设得比这一轮停站还长"
                                    + "（/pbmclosewait 调小些）；② 这一轮停站太短。"
                                    + "停站够长的那一轮会走「整段提前播」，与这个值无关",
                            posText(door), String.format("%.2f", door.fraction()));
                    continue;
                }
                // 从**第 0 毫秒**起播：人声就在素材开头，这一段不需要剪头。
                // 尾巴（静音 + 嘀嘀）由 next tick 的 voiceEndTick 那一支收掉。
                plan.instance = play(mc, plan.tone, door, plan.volume, 0);
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 强制等待到点 → {} {} 的**语音播报段**"
                                + "（从 0ms 起播、到 {}ms 止；门一动就掐断）",
                        posText(door), plan.instance != null ? "播放" : "播不出（素材缺失 / 解码失败）",
                        toneLabel(plan.tone), plan.tone.splitMs());
                // 落到下面的收尾判断（分界点极短时同一 tick 就该收）
            }
            boolean voiceDone = now >= plan.voiceEndTick;
            boolean doorMoving = door.fraction() < 1.0f - EDGE;
            if (voiceDone || doorMoving) {
                it.remove();
                stopForcedInstance(mc, plan, doorMoving
                        ? "门开始关了 ⇒ 立刻掐断，不管播到哪里"
                        : "语音播报已经播完 ⇒ 收掉（剩下的静音和嘀嘀由关门端对齐播）");
            }
        }
    }

    /**
     * 【1.16】按门锚点掐断那段兜底人声（{@link #detect} 关门那一分支调用）。
     *
     * <p>门已经不在计划表里时什么都不做（正常情形：它早就播完或已经收掉了）。
     */
    private static void cutForcedVoice(Minecraft mc, long key, String why) {
        ForcedVoice plan = forcedVoice.remove(key);
        if (plan != null) {
            stopForcedInstance(mc, plan, why);
        }
    }

    /**
     * 【1.16】停掉那一轮兜底人声的实例（顺手把句柄置 null，让它只可能被停一次）。
     *
     * <p>没播出去过（{@code instance == null}）时不打印 —— 那种情况的来路已经在别处说过了，
     * 这里再补一条只会让人以为「刚掐断了一段声音」。
     */
    private static void stopForcedInstance(Minecraft mc, ForcedVoice plan, String why) {
        if (plan.instance == null) {
            return;
        }
        PsdMusicInstance inst = plan.instance;
        plan.instance = null;
        if (mc == null) {
            return; // 换世界那条路上没有 mc 可用了：声音随世界一起没了，只清句柄
        }
        stop(mc, inst);
        LOGGER.info("[SmoothLift/PsdChime] 门 @{} 的强制等待人声 → 停（{}）", posText(plan.door), why);
    }

    // ------------------------------------------------------------------
    // 【1.17】到站播报：排计划 / 每 tick 起播与回收
    //
    //   ★ 全程**没有任何「停」的动作** —— 这张表里唯一的删除是「已经播完、回收表项」。
    //     这就是它区别于 forcedVoice 的全部要害，也正是用户点名的那条
    //     「即使列车出站也要继续播放，直到播完」。
    // ------------------------------------------------------------------

    /**
     * 【1.17】开门那一瞬排一条到站播报；★【1.26】它现在是**按串**的一条（一整串只排一条）。
     *
     * <p>起算点 = **开门音（嘀嘀嘀）播完** + Y 秒，即
     * {@code startTick = 开门那一 tick + ceil(开门素材时长 / 50) + Y × 20}。
     * 用「开门素材时长」而不是「门开那一刻」是因为用户描述的是
     * 「模组里是开门嘀嘀嘀**之后**开始播放」—— 从门开那一刻起算会让两者叠在一起响。
     *
     * <p>★ {@code openPlayable == null}（本维度把开门音设成了「不播」）时**不是撤销**，
     * 而是把起点退化成「开门那一刻 + Y 秒」—— 站台广播与开门音是**两个独立配置**，
     * 一个关掉不该把另一个也取消（与 {@code planClose} 不复用开门那一份是同一条教训）。
     *
     * <p>★★【1.26 用户现场】「一个站台连在一起的屏蔽门一共 12 个门，后 4 个 midium 不响」。
     * 根因不是门没采到、也不是别的分支，而是**把它当成了「每扇门的位置音」**：
     * <ul>
     *   <li>它读的配置本来就是**按串**的（{@code getDoorPsdMidiumAudio(mc.level, door.runKey())}），
     *       所以「每扇门各排一份」排出来的 N 份**逐位相同** —— 纯重复；</li>
     *   <li>而每份的射程判定又是「玩家↔**这一扇**门」，一整串 12 扇、55 格长（现场 z=72 那串
     *       x = -58…-3、间距 5），站在任何位置都只有约 6 扇在 16 格内 ⇒
     *       **射程外的那些 `gain == 0` 直接 return**（按设计静默，所以日志里连一行都没有，
     *       这正是「没有日志 ≠ 没执行」的又一例）。</li>
     * </ul>
     * ⇒ 改法：**一整串只排一条**（键用 {@code runKey}，还没播完就不再排第二条），
     * 距离与声源都取「这一串里离玩家**最近**的那一扇」—— 站台广播的语义。
     * ★【09-30 续 10 更正】上面那句「开关门提示音**不跟着改**」**已作废**：提示音后来也并进
     * 这条口径（它同样是「一串同声」，按本扇门算会把一串切成好几截，见 onClientTick 那段）。
     * 两者现在只差一个「单维 / 双维」的换算口径（见 {@link #play} 的 {@code chainDoubleDim}）。
     *
     * <p>★★【1.27 用户现场之二】「z 轴的修好了，x 轴的还是老样子，前面的和后面的屏蔽门都没声音」。
     * 1.26 只把粒度做到「连通串」，而**一个站台常常是好几个连通串**（站台被实体缺口切开，
     * 现场 LOG5 三处站台各对应 2~3 个串）⇒ 每段各自排一条、各自判射程，站在中间那段时前后两段
     * 都在 16 格外 ⇒ **整段静默**。⇒ 真正的粒度是**站台**，已由 {@link PsdDoorTracker#runKeyOf}
     * 落地（runKey 现在优先 = MTR 站台身份），本方法一行不用再动 —— 它拿到的 runKey 已经是整站台，
     * 于是「一串只排一条」自动变成「一站台只排一条」，「最近的一扇」自动变成「整站台最近的」。
     */
    private static void planArrivalAnnounce(Minecraft mc, PsdDoorTracker.DoorView door,
                                            Playable openPlayable) {
        long runKey = door.runKey();
        // ★【1.26】这一串（= 1.27 起：这个**站台**）已经有一条还没播完的播报 ⇒ 不排第二条
        //   （同一份配置排 N 份 = 同一段 13.8 秒的广播叠 N 遍，听感是相位噪声，还白占 N 条音频流）。
        //   表项由 tickArrivalAnnounce 在「起播 + 素材时长」那一 tick 摘掉 ⇒ 摘掉后自然能再排。
        if (arrivalVoice.containsKey(runKey)) {
            return;
        }
        // 【1.20】到站播报也按门存了 ⇒ 先把**这一扇门**的生效值取回这两个字段
        //   （没单独设过时它们就是维度默认 / 维度值，由 getDoorPsd* 负责回落）。
        //   下面那些判据与算式一行都不用改。
        cachedMidiumAudio = EscalatorSpeedManager.getDoorPsdMidiumAudio(mc.level, runKey);
        cachedMidiumWaitSeconds =
                EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(mc.level, runKey);
        if (EscalatorSpeedData.isPsdMidiumOff(cachedMidiumAudio)) {
            return;
        }
        Tone tone = resolveMidiumTone(mc, cachedMidiumAudio);
        if (tone == null || tone.durationMs() <= 0) {
            return; // 素材不在库里 / 时长量不到 —— resolveMidiumTone 已经记过日志
        }
        // 【1.20】音量也走这一扇门的口径（维度默认只是它的回落层）。
        // 【1.22】这里存的是**音量系数**（不乘距离增益）：距离增益由播放实例每 tick 现算，
        //   这样玩家走远时正在响的那一条会跟着淡出。
        float volume = volumeFactor(EscalatorSpeedManager.getDoorPsdMidiumVolume(mc.level, runKey));
        // ★【1.26】射程与声源都按**整串**算：只要玩家在这一串的任意一扇门旁边（≤ 用户设的范围），
        //   这一串的到站播报就成立。站在站台哪一扇门附近都听得见，这才是「站台广播」。
        Vec3 player = mc.player == null ? null : mc.player.position();
        PsdDoorTracker.DoorView source = PsdDoorTracker.nearestInRun(runKey, player);
        if (source == null) {
            return; // 这一串此刻不在快照里（走远了 / 区块卸载）—— 与「射程外」同一条静默约定
        }
        double distanceXz = player == null ? 0.0
                : Math.hypot(player.x() - source.x(), player.z() - source.z());
        double distanceY = player == null ? 0.0
                : Math.abs(player.y() - source.y());
        if (gain(distanceXz, distanceY, ROUND_MIDIUM) * volume <= 0.0f) {
            return; // 站在这一串的可闻范围外：这是常态，不刷日志
        }
        long now = mc.level.getGameTime();
        long openDurMs = openPlayable != null ? openPlayable.tone().durationMs() : 0L;
        long openTicks = openDurMs > 0L ? (openDurMs + 49L) / 50L : 0L;
        long waitTicks = Math.max(0, cachedMidiumWaitSeconds) * 20L;
        long startTick = now + openTicks + waitTicks;
        long durationTicks = (tone.durationMs() + 49L) / 50L;
        arrivalVoice.put(runKey,
                new Arrival(startTick, startTick + durationTicks, tone, source, runKey, volume));
        LOGGER.info("[SmoothLift/PsdChime] 一串门 @{} 开门 → 排到站播报「{}」：{} tick 后（约 {}ms）起播"
                        + "（= 开门音 {}ms 播完 + 等 {} 秒；声源取这一串里离玩家最近的门 @{}，"
                        + "距玩家 水平 {} 格、垂直 {} 格）；★ 这段声音**不会被掐断**，出站也播到完；"
                        + "★ 一整串只排这一条（还没播完不会再排第二条）",
                posText(door), toneLabel(tone), startTick - now, (startTick - now) * 50L,
                openDurMs, cachedMidiumWaitSeconds, posText(source),
                String.format("%.1f", distanceXz), String.format("%.1f", distanceY));
    }

    /**
     * 【1.17】每 tick 走一遍到站播报：到点起播、播完回收表项。
     *
     * <p>★ 它**不接收门快照**，也**不查门在不在** —— 这是刻意的：
     * 列车出站时门会从快照里消失，而计划里存着开门那一刻的坐标与音量，
     * 拿它当声源位置就够起播了（{@link #play} 只用 {@code door} 的 x/y/z）。
     *
     * <p>★ 也没有任何 {@code stop(...)} —— 起播之后实例就交到原版 {@code SoundEngine} 手上，
     * 一次性、不循环，没人停它就会响到自然结束。{@link #reset} 同样不许碰这张表（见那里的注释）。
     */
    private static void tickArrivalAnnounce(Minecraft mc) {
        if (arrivalVoice.isEmpty()) {
            return;
        }
        long now = mc.level.getGameTime();
        Iterator<Map.Entry<Long, Arrival>> it = arrivalVoice.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Arrival> entry = it.next();
            Arrival plan = entry.getValue();
            if (!plan.fired && now >= plan.startTick) {
                plan.fired = true;
                PsdMusicInstance inst = play(mc, plan.tone, plan.door, plan.volume, 0, true,
                        ROUND_MIDIUM, plan.runKey, true);
                LOGGER.info("[SmoothLift/PsdChime] 到站播报（这一串的门 @{} 里离玩家最近的一扇起播）"
                                + " → {}「{}」（{}ms，等待 {} 秒）；"
                                + "★ 不设停止条件：门关、车走都照播到完",
                        posText(plan.door), inst != null ? "播放" : "播不出（素材缺失 / 解码失败）",
                        toneLabel(plan.tone), plan.tone.durationMs(),
                        EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(mc.level, plan.runKey));
            }
            // 播完就回收表项（与声音无关，只是别让这张表无限长大：等待秒数允许正无穷）
            if (plan.fired && now >= plan.endTick) {
                it.remove();
            }
        }
    }

    /**
     * 【1.21】每 tick 走一遍进站报站：到点起播、同一班车只播一次。
     *
     * <p>节流：一串门最多 {@link #ARRIVE_POLL_TICKS} 刻查一次时刻表；
     * 站台只认一次（认到了就缓存）。
     *
     * <p>★ 这个方法里**一句 {@code stop(...)} 都没有**，也**不接收 gameTime 之外的门状态** ——
     * 「即使列车进站也要继续播放，直到播完」是靠**结构**保证的（不持句柄 ⇒ 没有「谁能停它」），
     * 不是靠某个标志位。{@link #reset} 同样不许碰这张表。
     *
     * <p>★★【1.26】一串门只算一次，而且算在**离玩家最近的那一扇**上（{@link #closerThan}）——
     * 它同时是「声源位置」与「射程判据」用的那一扇。
     * 旧写法是「快照里第一个碰到的那个 {@code runKey}」，而 {@link PsdDoorTracker} 的
     * {@code LIVE} 是 {@code HashMap} ⇒ 迭代序会变：现场 LOG4 里同一个串认站台时记的是
     * {@code -33}、两次播报记的都是 {@code -28}（声源在串里随机跳）。
     * 更致命的是**射程**：以串里某一扇为基准算距离，玩家走到站台另一头就不止 16 格 ⇒
     * 整串静默 —— 用户原话「前 3 个和后 4 个 arrive 不响」。
     *
     * <p>★★【1.27】这里「一串」的粒度已由 {@link PsdDoorTracker#runKeyOf} 升到**整个站台**：
     * 于是「每一串只算一次」自动变成「每一站台只算一次」，声源与射程都按整站台算 ——
     * 用户原话「x 轴的还是老样子，前面的和后面的屏蔽门都没声音」就是这么消掉的。
     */
    private static void tickArriveAnnounce(Minecraft mc, List<PsdDoorTracker.DoorView> doors) {
        if (mc.level == null || doors.isEmpty()) {
            return;
        }
        // 【1.28.1204】/jsr 总开关。
        //   ★【1.29】它现在**只管讲述人那一条**（见下面 ③-B），不再把整条进站报站链路一起关掉 ——
        //   用户原话：「讲述人的进站广播和自定义的进站广播不是一个广播，可以同时存在」。
        //   ⇒ 关 /jsr 只让讲述人闭嘴，自定义进站广播（音频库素材）照旧；反过来，
        //     自定义那条设成「不播」也不会再连累讲述人。
        //   ★【09-28】讲述人从此有**两层**开关：这一层全局 /jsr **与** 每串门自己的
        //   {@code PsdToneAudio.narrate}（石斧 UI 二级页「关闭 / 开启」），两者取「与」。
        //   ★★【09-30】玩家在 MTR 列车上 ⇒ 讲述人**语音和文字一概不播，无论设置如何**
        //   （用户点名；效果等同于走出 /jsr round —— 正在念/正在显示的那一下由
        //   tickRangeGuard / Subtitle.tick 同一 tick 撤下）。自定义进站广播**不受影响**
        //   （它只减音量，见 TRAIN_VOLUME_FACTOR 那一套）。
        boolean ridingNow = ridingTrain();
        boolean narrateGlobal = TrainAnnounceSwitch.isEnabled() && !ridingNow;
        // 【09-30】全局样式覆盖（/jsr on default-HK|default-SH|userN）：-1 = default（跟门串）。
        int styleOverride = TrainAnnounceSwitch.styleModeOverride();
        long now = mc.level.getGameTime();
        Vec3 player = mc.player == null ? null : mc.player.position();
        // ★【1.26】先把门快照归并成「每一串里离玩家最近的那一扇」。
        //   ★【1.27】runKey 已经是**站台**身份 ⇒ 归并结果就是「每一站台里离玩家最近的那一扇」，
        //   同一站台被缺口切开的几段门会进同一个桶（这正是修复点，本方法一行没改）。
        //   LinkedHashMap：迭代序跟着门快照走（日志好读）；每一串各自独立判据，顺序不影响结果。
        Map<Long, PsdDoorTracker.DoorView> nearestPerRun = new LinkedHashMap<>();
        for (PsdDoorTracker.DoorView d : doors) {
            PsdDoorTracker.DoorView cur = nearestPerRun.get(d.runKey());
            if (cur == null || closerThan(player, d, cur)) {
                nearestPerRun.put(d.runKey(), d);
            }
        }
        // ★★【09-29】讲述人的范围从这一版起是**自己的**（/jsr round AAA BBB，客户端配置），
        //   不再借用 /pbmarriveround —— 自定义进站广播（③-A）照旧用它自己的范围。
        //   同时落地用户点名的第 3 条：**玩家同时处于多个讲述人 round 内时，
        //   以玩家到屏蔽门距离最近的那一串的播报为准** —— 其余的串保持安静。
        //   候选 = 这一串的讲述人样式开着 && 玩家在它的范围内（【1.29.1204】水平 < AAA
        //   且 垂直 < BBB，任一超出即不候选）；
        //   nearestNarrateRun = 候选里**三维欧氏距离**最小的那个 runKey（下面 ③-B 只对它开口）——
        //   距离量纲不变，只当「选哪串」的尺子，范围判定走上面的两维判据。
        //   ★ 被压下去的串**不记账**（firedArrivalNarrate 不动）：玩家走远、最近的串
        //   出了 round 之后，它仍可按它自己的窗口补播这一班 —— 「以最近的为准」
        //   管的是「此刻谁开口」，不是「这一班谁独占」。
        int narrateRoundXz = TrainAnnounceSwitch.narrateRoundXz();
        int narrateRoundY = TrainAnnounceSwitch.narrateRoundY();
        long nearestNarrateRun = Long.MIN_VALUE;
        if (narrateGlobal) {
            double bestNarrateDistance = Double.MAX_VALUE;
            for (Map.Entry<Long, PsdDoorTracker.DoorView> run : nearestPerRun.entrySet()) {
                if (EscalatorSpeedManager.getDoorPsdNarrateMode(mc.level, run.getKey())
                        == EscalatorSpeedData.PSD_NARRATE_OFF) {
                    continue; // 这一串的讲述人样式关着：它不在候选里，也不压制别人
                }
                PsdDoorTracker.DoorView door = run.getValue();
                // 【1.29.1204】范围判据两维各算各的（同一条判据，见 TrainAnnounceSwitch）：
                //   水平（x、z 轴）< 水平范围 且 垂直（y 轴）< 垂直范围 才算「在范围内」。
                if (TrainAnnounceSwitch.isOutsideNarrateRange(run.getKey(),
                        door.x(), door.y(), door.z(), player)) {
                    continue; // 玩家已越界（水平 ≥ AAA 或垂直 ≥ BBB）：这一串不在候选里
                }
                double distance = player == null ? 0.0
                        : player.distanceTo(new Vec3(door.x(), door.y(), door.z()));
                if (distance < bestNarrateDistance) {
                    bestNarrateDistance = distance;
                    nearestNarrateRun = run.getKey();
                }
            }
        }
        for (Map.Entry<Long, PsdDoorTracker.DoorView> run : nearestPerRun.entrySet()) {
            long runKey = run.getKey();
            PsdDoorTracker.DoorView door = run.getValue();
            String audio = EscalatorSpeedManager.getDoorPsdArriveAudio(mc.level, runKey);
            // 【1.29】「不播」只关**自定义**那一条（= customOff 这个局部标志），
            //   不再像 1.28 那样把这一串整个 `continue` 掉 —— 讲述人那一条要照念。
            //   ★【09-28】讲述人也有自己的开关了（门串覆盖 > 维度默认），与 /jsr 取「与」。
            //   两条都关着时才真的没事可做，此时保持老口径：顺手把状态表清掉。
            boolean customOff = EscalatorSpeedData.isPsdArriveOff(audio);
            //   ★【09-28 续】这里取的是**样式**（0 关 / 1 上海 / 2 香港 / 3+ 自定义 userN）：
            //   ③-B 要按样式选句式，只看开/关会把香港档也念成上海词。
            //   ★★【09-30】全局样式覆盖（/jsr on default-HK|default-SH|userN）：门串自己
            //   开着（≠ 关闭）时**全局说了算**（「念哪一句」）；门串自己关着 → 仍按「关闭」
            //   —— 门串的「关闭」是它自己的否决权，全局样式不越过。
            int doorNarrateMode = EscalatorSpeedManager.getDoorPsdNarrateMode(mc.level, runKey);
            int narrateMode = !narrateGlobal
                    ? EscalatorSpeedData.PSD_NARRATE_OFF
                    : doorNarrateMode == EscalatorSpeedData.PSD_NARRATE_OFF
                    ? EscalatorSpeedData.PSD_NARRATE_OFF
                    : styleOverride >= 0 ? styleOverride : doorNarrateMode;
            boolean narrateOn = narrateMode != EscalatorSpeedData.PSD_NARRATE_OFF;
            if (customOff && !narrateOn) {
                arriveVoice.remove(runKey);
                continue;
            }
            Long last = arriveLastPoll.get(runKey);
            if (last != null && now - last < ARRIVE_POLL_TICKS) {
                continue;
            }
            arriveLastPoll.put(runKey, now);
            // ① 认站台（认到就缓存；认不到下一轮再试 —— 站台数据可能还没同步完）
            // ★【1.28】优先用**同一条身份链**上的站台 id：PsdDoorTracker 认到过站台时，
            //   DoorView.platformId 就是那个 id（与 runKey 编码出的 platformKey 是同一份数据）。
            //   以前这里自己再调一次 MtrDwellAccess.platformIdAt（用门坐标再认一次亲），
            //   与身份那一侧是**两条并行认亲**：一旦某扇门在 4 格边界上两边判出不同结果，
            //   「身份已是站台、时刻表却认不到」⇒ 这一串**永远不响进站报站**（LOG6 现场：
            //   x 轴 z=43 那排门的身份一直回落连通串，进站报站那边也就一路静默、连日志都没有）。
            //   ★【09-28 续 6】「认不到」的哨兵是 PLATFORM_ID_NONE，**不是 0 / 负数**：
            //   MTR4 站台 id = Random().nextLong()，约一半是负数（LOG12 现场：开往南区南方向
            //   的 4 号线站台 id 为负 ⇒ 旧判据 platformId <= 0 把「认到了」误判成「认不到」，
            //   整个方向没有进站播报）。判据一律用 MtrDwellAccess.isPlatformKnown。
            //   ★★【09-30 续 9】runKey 已升到**车站级**（同站两侧门串共用一个身份）⇒
            //   这里的站台 id **必须按每侧自己的 DoorView.platformId 取** —— 上 / 下行的
            //   时刻表不能串（ arrivePlatform 那份「按 runKey 缓存」是站台级时代的产物，
            //   车站级 runKey 下两侧共用一份缓存会把 A 侧的站台 id 串给 B 侧，已删）。
            long platformId = door.platformId();
            if (!MtrDwellAccess.isPlatformKnown(platformId)) {
                // 身份链还没认到（站台数据没同步完）才退回自己认一次（1.21 的老路）。
                platformId = MtrDwellAccess.platformIdAt(door.x(), door.y(), door.z());
                if (!MtrDwellAccess.isPlatformKnown(platformId)) {
                    // ★【1.28】诊断（节流：同一串每 60 秒一行）—— 用户报「某些屏蔽门
                    //   arrive 直接没有声音」时，这行会点名**为什么**：站台数据没同步、
                    //   附近没站台、还是站台在 4 格上限外差几格。
                    long tickNow = mc.level.getGameTime();
                    Long nextLog = arriveFailNextLog.get(runKey);
                    if (nextLog == null || tickNow >= nextLog) {
                        arriveFailNextLog.put(runKey, tickNow + ARRIVE_FAIL_LOG_EVERY);
                        LOGGER.info("[SmoothLift/PsdChime] 进站报站：串 @{} 认不到 MTR 站台"
                                        + " ⇒ 这一串不响进站报站（{}）",
                                posText(door),
                                MtrDwellAccess.nearestPlatformExplain(door.x(), door.y(), door.z()));
                    }
                    continue;
                }
                LOGGER.info("[SmoothLift/PsdChime] 进站报站：串 @{} 认到 MTR 站台 id={}",
                        posText(door), platformId);
            }
            // ② 时刻表：下一班（还没走远的）还有多少毫秒到站
            //   ★【09-28】顺手把这一班车的**终点站 / 站台名**一起取回来（同一个 ArrivalResponse，
            //   就是 MTR 站台那块屏正在显示的那一条）—— 讲述人报站词要念这两个值。
            MtrDwellAccess.ArrivalInfo arrival = MtrDwellAccess.nearestArrival(platformId);
            if (arrival == null) {
                continue; // 读不到时刻表（没装 MTR4 / 还没同步到）—— 静默跳过，不刷日志
            }
            long remainMs = arrival.remainingMs;
            // 【09-28】★★ 两条广播各有**自己的**窗口（用户点名「取消借用进站广播」）：
            //   自定义那条用 getDoorPsdArriveSeconds，讲述人这条用 getDoorPsdNarrateSeconds。
            //   阈值口径逐字相同（X=-10 ⇒「最近一班车还剩 10 秒到站」时起播），但两边各存各的值 ——
            //   所以窗口区间要各算各的，不能像 1.29 那样只算一次共用。
            int customThresholdSeconds = EscalatorSpeedManager.getDoorPsdArriveSeconds(mc.level, runKey);
            int narrateThresholdSeconds = EscalatorSpeedManager.getDoorPsdNarrateSeconds(mc.level, runKey);
            /** 车已经过站超过补播宽限（两条共用：这是「时刻表本身过期」，与听哪条广播无关）。 */
            boolean late = remainMs < -MtrDwellAccess.ARRIVAL_PAST_MS;
            /** 自定义那条此刻进没进它自己的窗口（素材设成「不播」时永远 false）。 */
            boolean customInWindow = !customOff && !late
                    && remainMs <= (long) (-customThresholdSeconds) * 1000L;
            /** 讲述人那条此刻进没进它自己的窗口（开关关着时永远 false）。 */
            boolean narrateInWindow = narrateOn && !late
                    && remainMs <= (long) (-narrateThresholdSeconds) * 1000L;
            if (!customInWindow && !narrateInWindow) {
                continue; // 两条都还没进窗口 / 车已经过站超过补播宽限（这一轮过去了）
            }
            // ③ 同一班车只播一次：这次算出来的**绝对**到站时刻与上次播的那一班比。
            //   ★【09-28】记账**分开**（两条各有各的窗口 ⇒ 各记各的，见 Arrive#firedArrivalNarrate）：
            //   用同一格的话，先开窗的那一条会把另一条整班车压掉。
            long arrivalMs = System.currentTimeMillis() + remainMs;
            Arrive state = arriveVoice.computeIfAbsent(runKey, k -> new Arrive());
            /** 自定义那条还要为这一班车播？（进了窗口 **且** 这一班还没为它播过） */
            boolean customTodo = customInWindow && !sameTrainAs(state.firedArrival, arrivalMs);
            /** 讲述人那条还要为这一班车念？（【09-30】在列车上时 narrateGlobal=false ⇒ 永远 false。） */
            boolean narrateTodo = narrateInWindow && !sameTrainAs(state.firedArrivalNarrate, arrivalMs);
            if (!customTodo && !narrateTodo) {
                continue; // 进窗口的那几条都已经为这一班车播过了
            }
            // 【09-28】声源与射程仍然**共用**：同一条串里离玩家最近的那一扇。
            //   这是两条广播里唯一允许共用的东西 —— **位置**（本来就只有一个声源）。
            double distanceXz = player == null
                    ? 0.0 : Math.hypot(player.x() - door.x(), player.z() - door.z());
            double distanceY = player == null
                    ? 0.0 : Math.abs(player.y() - door.y());
            /**
             * 【1.29.1204】讲述人**自己**的范围判据：玩家到这串最近门的**水平 &lt; 水平范围 且
             *   垂直 &lt; 垂直范围**（/jsr round AAA BBB：水平 ≥ AAA 或垂直 ≥ BBB 即超出）。
             *   与自定义进站广播的范围（③-A 里现算的 {@code gain(distanceXz, distanceY, ROUND_ARRIVE)}，
             *   即 {@code /pbmarriveround}）是**两份**配置 —— 用户点名「jsr round AAA BBB
             *   指令调整进站讲述人播报的范围」，不再借用 /pbmarriveround 那一份。
             */
            boolean narrateInRange = !TrainAnnounceSwitch.isOutsideNarrateRange(
                    run.getKey(), door.x(), door.y(), door.z(), player);

            // ③-A 自定义进站广播（音频库里的素材）—— 只在**它自己**开着**且这一班还没播过**时走。
            //   ★【1.29】它设成「不播」时下面整个跳过，但**不影响** ③-B（讲述人）。
            //   ★【09-28】改成 customTodo：还要「这一班车还没为自定义这条播过」。
            if (customTodo) {
                Tone tone = resolveArriveTone(mc, audio);
                if (tone != null && tone.durationMs() > 0) {
                    // 【1.22】同到站播报：只算音量系数，距离增益交给实例每 tick。
                    float volume = volumeFactor(
                            EscalatorSpeedManager.getDoorPsdArriveVolume(mc.level, runKey));
                    if (gain(distanceXz, distanceY, ROUND_ARRIVE) * volume > 0.0f) {
                        PsdMusicInstance inst = play(mc, tone, door, volume, 0, true,
                                ROUND_ARRIVE, runKey, true);
                        state.firedArrival = arrivalMs; // ★ 自定义这条**自己**的记账
                        state.firedTick = now;
                        LOGGER.info("[SmoothLift/PsdChime] 进站报站·自定义"
                                        + "（这一串的门 @{} 里离玩家最近的一扇起播）"
                                        + " → {}「{}」（{}ms）：配置「剩 {} 秒到站时起播」，"
                                        + "实际起播时时刻表还剩 {}ms（第 {} tick）；"
                                        + "★ 不设停止条件：车进站、门开关都照播到完",
                                posText(door), inst != null ? "播放" : "播不出（素材缺失 / 解码失败）",
                                toneLabel(tone), tone.durationMs(), -customThresholdSeconds, remainMs, now);
                    }
                    // 物质在库里但玩家站在可闻范围外：**不**标记「播过」，走近了还能补上这一段
                }
            }

            // ③-B 【09-28】讲述人进站广播（文字转语音）—— **独立的一条**。
            //   ★ 判据里**没有** `customOff`：自定义那条不播、素材没导入、音量 0，都不关它的事；
            //   它只认 ① 全局 /jsr **与** 这一串门自己的开关、② 玩家在不在这一串的可闻范围内。
            //   ★ 窗口是**它自己**的（getDoorPsdNarrateSeconds），与自定义那条各存各的
            //   —— 用户点名「取消借用进站广播」。
            //   ★ 用 MTR 报站的同一个入口念（com.mojang.text2speech.Narrator，不查游戏辅助功能
            //   设置）—— 详见 TrainAnnounceNarrator。
            //   ★ 念什么 = 按这一串门**生效的样式**选句式（TrainAnnounceNarrator.arriveTextForStyle）：
            //     开启(上海) →「乘客们，列车马上就要进站了，本次列车终点站：X，请乘客们在Y站台有序候车」；
            //     开启(香港) →「前往X的列车即将到达，请先让车上的乘客下车 ⏎ The train to X is arriving…」
            //   ★★【09-28 续 3】两档**只差句式，不差名源** —— X 都是**本次列车终点站**
            //     （ArrivalResponse.getDestination()，MTR 的双语 中文|English 就在这个字段上），
            //     只有上海档**多要一个** Y = 站台名（ArrivalResponse.getPlatformName()）。
            //     ★ 上一版曾让香港档去吃**车站名**（Station.getName() = 玩家所在那个站）⇒ 把
            //     「前往江苏北路」念成「前往火车站」（用户报的 bug）；再上一版吃**站台名**
            //     （「4A」单语）⇒ 只念得出英文半句。两个都不是这里的名源。
            //   ★ 名字读不到时香港档拼不出话 ⇒ text == null ⇒ 这一条跳过（但仍记账，别每 tick 重试）。
            //   ★★【09-29】两个新增判据：
            //     ① narrateInRange —— 范围用**讲述人自己的** /jsr round AAA BBB（水平 AAA、
            //        垂直 BBB，任一超出即不念；不再借用进站报站的范围）；
            //     ② runKey == nearestNarrateRun —— 玩家同时处于多个讲述人 round 内时，
            //        **只让离玩家最近的那一串开口**（用户点名「以玩家到屏蔽门距离最近的那个
            //        播报为准」）。其余的串不记账，玩家走远后仍可按自己的窗口补播。
            if (narrateTodo && narrateInRange && runKey == nearestNarrateRun) {
                // 【09-30 续】本车站名提前到起播那一刻取（仍是低频查询）：自定义档的占位符
                //   |SC| / |SE| 要用它替换（见 TrainAnnounceNarrator.expandUserTemplate），
                //   下面的日志继续共用同一个值。
                String stationName = MtrDwellAccess.stationNameForPlatform(platformId);
                // 【09-30 续 2】|LC| / |LE| 还要**线路名** —— 只在自定义档才去扫
                //   （routes × routeData 的遍历，低频起播时刻才跑，省一次无用反射）。
                String lineName = EscalatorSpeedData.isPsdNarrateUserStyle(narrateMode)
                        ? MtrDwellAccess.lineNameForPlatform(platformId) : null;
                String text = TrainAnnounceNarrator.arriveTextForStyle(
                        narrateMode, arrival.destination, arrival.platformName, stationName,
                        lineName, TrainAnnounceSwitch.arriveUserTexts());
                // 【09-29】speak 带上声源身份（runKey）与坐标：护栏每 tick 现算
                //   「到这一串门此刻最近的一扇」的距离，超出 /jsr round 的那一刻
                //   把正在念的话当场掐掉（clear()）。
                //   ★【09-29 续】坐标只是兜底 —— 主判据是 runKey，否则玩家沿站台走到
                //   本串另一头就会被误判越界（见 TrainAnnounceSwitch#narrateDistances）。
                boolean spoke = text != null
                        && TrainAnnounceNarrator.speak(text, runKey, door.x(), door.y(), door.z());
                // 【09-29】字幕与念是**同一个事件**：这一班要念（text 拼得出来），
                //   屏幕上就挂同一句话（标点已换空格，见 TrainAnnounceSubtitle）；
                //   玩家离开 round / 自然念完 ⇒ 字幕消失。字幕开没开由 show 自己判
                //   （【09-30】/jsr on <样式> 的文字地点不是 word 时它是空操作）。
                if (text != null) {
                    TrainAnnounceSubtitle.show(text, runKey, door.x(), door.y(), door.z());
                    // 【09-30】文字出现地点 = chat ⇒ 同一句话**发进聊天框**（用户点名
                    //   「word/chat 指的是讲述人文字出现地点，屏幕中/聊天框」；
                    //   【09-30 续】按点名删掉「[讲述人]」前缀，聊天框里就是播报文字本身）。
                    //   与字幕互斥（TextMode 三档取一）；ridingNow 时整段不会走到这里
                    //   （narrateTodo 恒 false），所以聊天框不会在列车上冒出来。
                    if (TrainAnnounceSwitch.textMode() == TrainAnnounceSwitch.TextMode.CHAT
                            && mc.player != null) {
                        for (String line : text.split("\n")) {
                            if (!line.isBlank()) {
                                mc.player.displayClientMessage(new net.minecraft.network.chat.TextComponent(line.trim()), false);
                            }
                        }
                    }
                }
                // 走到这里就算「这一班车已处理」：引擎没装 / 这一档没拼出话，都不该每 tick 重试。
                state.firedArrivalNarrate = arrivalMs; // ★ 讲述人这条**自己**的记账
                state.firedTick = now;
                // ★【09-28 续 3】日志里附上**本车站名**（【09-30 续】它在上面已经取好了 ——
                //   自定义档的 |SC| / |SE| 占位符也用它）。
                LOGGER.info("[SmoothLift/PsdChime] 进站报站·讲述人{}"
                                + "（这一串的门 @{} 里离玩家最近的一扇起播）"
                                + " → {}「{}」（终点站 {}、站台 {}、本车站 {}）："
                                + "配置「剩 {} 秒到站时起播」，实际起播时时刻表还剩 {}ms（第 {} tick）；"
                                + "★ 与自定义进站广播是两条互不相干的广播，可以同时存在",
                        EscalatorSpeedData.psdNarrateModeName(narrateMode),
                        posText(door),
                        text == null ? "跳过（这一档拼不出话，例如终点站读不到）"
                                : (spoke ? "念出" : "念不出（本机没有可用语音引擎）"),
                        text == null ? "" : text,
                        arrival.destination == null ? "读不到" : arrival.destination,
                        arrival.platformName == null ? "读不到" : arrival.platformName,
                        stationName == null ? "读不到" : stationName,
                        -narrateThresholdSeconds, remainMs, now);
            }
        }
        // 只保留这一帧还看得见的串（这两张表是可重算的缓存，别让它们无限长大）
        arriveLastPoll.keySet().retainAll(nearestPerRun.keySet());
        arriveFailNextLog.keySet().retainAll(nearestPerRun.keySet());
    }

    /**
     * 【09-28】「记录的那一班车」与「这次算出来的这一班」是不是同一班（容差 {@link #ARRIVE_SAME_TRAIN_MS}）。
     *
     * <p>两条广播（自定义 / 讲述人）各存各的记录（见 {@link Arrive#firedArrival} /
     * {@link Arrive#firedArrivalNarrate}），所以判据收成这一个函数 —— 免得两处各写一遍
     * 「{@code != MIN_VALUE && |差| <= 容差}」而某天改容差时只改一处。
     *
     * @param recorded 那一条自己的记录（{@link Long#MIN_VALUE} = 从没播过）
     */
    private static boolean sameTrainAs(long recorded, long arrivalMs) {
        return recorded != Long.MIN_VALUE && Math.abs(arrivalMs - recorded) <= ARRIVE_SAME_TRAIN_MS;
    }

    // ------------------------------------------------------------------
    // 【09-30 续 3】站台广播（讲述人）：排计划 + 到点开念。
    // ------------------------------------------------------------------

    /**
     * 【09-30 续 3】开门那一瞬给这一串门排一条**站台讲述人**计划（与
     * {@link #planArrivalAnnounce} 同一时刻、同一套「一串只排一条」的规矩）。
     *
     * <p>排计划的四道闸（任一不过就安静地不排，与进站讲述人同一口径）：
     * <ol>
     *   <li>这一串还没排过（{@code midiumNarratorVoice} 里没有它）；</li>
     *   <li>全局 /jsr 开着，且玩家**不在 MTR 列车上**（讲述人语音文字在车上不播，用户点名）；</li>
     *   <li>这一串门的站台讲述人**样式**开着（门覆盖 &gt; 维度默认）；</li>
     *   <li>玩家在这一串的 /jsr round 范围内（讲述人自己的范围，与 pbmmidium 的范围是两份）。</li>
     * </ol>
     * 起算点与自定义到站播报**逐字相同**：{@code 开门音播完 + 等待秒数}（等待秒数是
     * 站台讲述人**自己的**那一份，与 pbmmidium 的各存各的）。
     */
    private static void planMidiumNarrator(Minecraft mc, PsdDoorTracker.DoorView door,
                                           Playable openPlayable) {
        long runKey = door.runKey();
        // ★★【10-01】这道守卫是「同一班车只排一条」的第一道闸，键 = runKey
        //   （**车站级**身份 = 配置身份：站台讲述人的样式 / 范围都按它读）。
        //   ★★【10-01 续】注意键的**粒度**与「播报范围」的粒度**是两件事**：闸的粒度仍是
        //   runKey（一次开门周期只排一条，够了 —— 真正会念的那一条必然在玩家所在的那个
        //   站台上，见下面的声源收窄），而「谁能出声 / 谁让位」用的是**站台**粒度
        //   （{@link #isNearestMidiumNarrateRun} 与 {@link #sameBroadcastScope}）。
        //   ★ 它只在**表里还有记录**时才起作用。旧实现是「起播那一 tick 就把表项摘掉」，
        //   于是这道闸的窗口被压成了零 ⇒ 同一串里后开门的那几扇门能再排一条、再念一遍
        //   （LOG013 那两对重复就是这么来的，数字见 {@link #MIDIUM_NARRATE_HOLD_TICKS}）。
        //   现在念完由 {@link #tickMidiumNarrator} 把记录带着 fired 标记**放回表里**，
        //   这道闸才真正挡得住 —— 键与放回时的键必须是同一个（都是 runKey）。
        if (midiumNarratorVoice.containsKey(runKey)) {
            return; // 这一串已有一条「还没念完 / 刚念过还在守卫窗口内」的记录 —— 不排第二条
        }
        // 【09-30 续 4】总闸是站台讲述人**自己的**（/jsr midium on|off），与进站讲述人分开。
        if (!TrainAnnounceSwitch.isMidiumEnabled() || ridingTrain()) {
            return;
        }
        // 【09-30 续 4】站台讲述人的样式只有「关闭」与 userN 两类 —— 香港 / 上海预设已删
        //   （用户点名），存档里残留的 1/2 档一律当「关闭」处理。
        int doorMode = EscalatorSpeedManager.getDoorPsdMidiumNarrateMode(mc.level, runKey);
        if (doorMode == EscalatorSpeedData.PSD_NARRATE_OFF
                || !EscalatorSpeedData.isPsdNarrateUserStyle(doorMode)) {
            return;
        }
        Vec3 player = mc.player == null ? null : mc.player.position();
        // ★★【10-01 续】声源 = **触发这一扇门所属的那个 MTR 站台**里离玩家最近的门。
        //
        //   旧写法取 {@code PsdDoorTracker.nearestInRun(runKey, player)} = 整个**车站**最近的那扇门
        //   —— 而 runKey 自【09-30 续 9】起是**车站级**（同站几层 / 两侧共用一个配置身份）
        //   ⇒ 那个「最近的门」几乎必然**贴着玩家**（就在玩家脚下这条门线上）⇒ 下面那道
        //   {@code /jsr midium round} 判据的**水平与垂直两维都恒等于 0** ⇒ 永远不越界。
        //
        //   后果（LOG114 现场，数字可复核）：玩家站在 8-2 站台，**2-2 站台**（另一条门线 ——
        //   垂直差 8 格 / 水平差 40 格）的列车开门时，那条播报也拿到「玩家脚下那扇门」当声源
        //   ⇒ 满音量在耳边念一遍（15:49:06 念的正是 2-2），而玩家自己的 8-2 是 15:49:20 开门、
        //   15:49:22 才念 ⇒ 用户听到的就是「站台广播**提前播了一次**、开门又播一次」。
        //   ⇒ 声源收窄到「这一扇门自己的站台」之后，`/jsr midium round`（默认 水平 16 / 垂直 5）
        //   才真的按「层 / 平台」分得开：别的站台离玩家远 ⇒ 出范围 ⇒ **不排**（静默，与
        //   「射程外」同一条约定）。本平台上离玩家最近的那扇仍然就在身边 ⇒ 站台内
        //   「整排一起响、一起淡出」的老语义（【续 50】）不受影响。
        long platformId = door.platformId();
        PsdDoorTracker.DoorView source;
        if (MtrDwellAccess.isPlatformKnown(platformId)) {
            source = PsdDoorTracker.nearestOnPlatform(platformId, player);
            if (source == null) {
                return; // 这个站台此刻不在快照里（走远了 / 区块卸载）—— 与「这一串不在快照里」同一条约定
            }
        } else {
            // 站台认不到 ⇒ 回落车站级声源（与铃声响度那边**同一条约定**：认不到就不冒充「同一个站台」）
            source = PsdDoorTracker.nearestInRun(runKey, player);
            if (source == null) {
                return; // 这一串此刻不在快照里（走远了 / 区块卸载）
            }
        }
        // 【09-30 续 4】范围是站台讲述人**自己的** /jsr midium round（与进站讲述人的两份配置）。
        if (TrainAnnounceSwitch.isOutsideMidiumNarrateRange(runKey,
                source.x(), source.y(), source.z(), player)) {
            return; // 玩家在范围外：不排（这是常态，不刷日志）
        }
        long now = mc.level.getGameTime();
        // ★★【10-01】等待基线**不能**只看「触发排计划的那一扇门」的 openPlayable —— 它常常是 null
        //   （那一扇门不在玩家射程里：{@link #resolvePlayable} 对范围外的门按设计既不起播也不刷日志），
        //   于是「开门音播完」被算成 0ms ⇒ startTick == 现在 ⇒ **当场开念**（用户说的「提前播报一次」），
        //   并且因为旧实现当场就把表项摘了，同一串后开门的几扇门还会**再念一遍**
        //   （两次实测见 {@link #MIDIUM_NARRATE_HOLD_TICKS}）。
        //   改成本串（= 这个车站）**「开门音」这一项生效的素材时长**：与哪一扇门触发无关；
        //   在射程内触发的正常情形下，它与旧写法取的**是同一份配置的同一个时长**，行为不变。
        long openDurMs = midiumOpenBeepMs(mc, runKey, openPlayable);
        long openTicks = openDurMs > 0L ? (openDurMs + 49L) / 50L : 0L;
        int waitSeconds = EscalatorSpeedManager.getDoorPsdMidiumNarrateSeconds(mc.level, runKey);
        long startTick = now + openTicks + Math.max(0, waitSeconds) * 20L;
        midiumNarratorVoice.put(runKey, new MidiumNarratorPlan(startTick, source, runKey, platformId));
        LOGGER.info("[SmoothLift/PsdChime] 一串门 @{} 开门（站台 id={}）→ 排站台广播(讲述人)："
                        + "{} tick 后开念（= 开门音 {}ms 播完 + 等 {} 秒；"
                        + "声源取**这个站台**里离玩家最近的门 @{}）；★ 讲述人与自定义到站播报互相独立",
                posText(door), platformId, startTick - now, openDurMs, waitSeconds, posText(source));
    }

    /**
     * 【09-30 续 3】每 tick 走一遍站台讲述人计划：到点开念、念完**再守一个窗口**才摘表项。
     *
     * <p>★★【10-01】表项的收口改成两段，与 {@link #tickArrivalAnnounce} 的 {@code endTick} 同一条思路
     * （旧版是「起播那一 tick 就摘」，等于把「同一份话不叠两遍」那道守卫的窗口压成 0 ——
     * 用户报的「又是提前播报一次 / 播报 2 次」就是它，数字见 {@link #MIDIUM_NARRATE_HOLD_TICKS}）：
     * <ol>
     *   <li>判据没过（开关 / 样式 / 范围 / 多站台就近压制）⇒ 照旧**立刻摘**：
     *       「这一班的开门周期就处理这一次」，与声音无关；</li>
     *   <li>真念了 ⇒ 标 {@code fired} + {@code spokenTick} 并把记录**放回表里**，
     *       由 {@link #midiumHoldOver} 在「守卫窗口走完 + 这一串的门全关（或硬上限）」时摘。</li>
     * </ol>
     *
     * <p>起播那一刻再核四件事（排计划之后状态可能已经变了）：
     * 全局开关与列车状态、样式是否仍开着（顺带套全局样式覆盖，与进站讲述人同一条规则）、
     * 玩家是否仍在 /jsr round 范围内、以及**多站台就近压制** —— 这一时刻范围内还有别的
     * 站台讲述人候选时，只让离玩家最近的那一串开口（与进站讲述人同一条规则）。
     *
     * <p>念什么 = 按**站台讲述人的样式**选句式（与进站讲述人共用
     * {@link TrainAnnounceNarrator#arriveTextForStyle}）：上海 / 香港档吃这一班的
     * 终点站与站台名（起播时刻查一次时刻表），userN 档把玩家模板里的占位符换成实际值；
     * 文字出现地点（字幕 / 聊天框）也与进站讲述人共用同一套开关。
     */
    private static void tickMidiumNarrator(Minecraft mc, List<PsdDoorTracker.DoorView> doors) {
        if (midiumNarratorVoice.isEmpty()) {
            return;
        }
        long now = mc.level.getGameTime();
        // 【10-01】念完之后要**放回**表里的那几条（守卫窗口内继续挡住同一串的后续门沿）。
        //   ★★ 必须攒到 while 之外再 put：迭代中途 put 会改 HashMap 的 modCount ⇒ 下一轮
        //   {@code next()} 直接 CME（本表通常只有 1~2 条，但绝不靠「反正只有一条」来兜）。
        //   ★ 时机也对：本方法排在门循环**之前**（见 onClientTick），放回去的记录当帧就能挡住
        //   同一 tick 里随后开门的那几扇门。
        Map<Long, MidiumNarratorPlan> respawn = null;
        Iterator<Map.Entry<Long, MidiumNarratorPlan>> it = midiumNarratorVoice.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, MidiumNarratorPlan> entry = it.next();
            MidiumNarratorPlan plan = entry.getValue();
            if (plan.fired) {
                // 【10-01】已经念过：这一条只剩「继续挡住这一串」的作用，到点（守卫窗口走完 +
                //   这一串的门已全关，或撞上硬上限）才摘 —— 见 MIDIUM_NARRATE_HOLD_TICKS。
                if (midiumHoldOver(doors, plan, now)) {
                    it.remove();
                }
                continue;
            }
            if (now < plan.startTick) {
                continue;
            }
            it.remove(); // 还没念：这一班的开门周期就处理这一次（摘表项 = 回收，与声音无关）；
                         // ★ 真念成了的话，末尾会把它**带着 fired 标记放回去**（守卫窗口）。
            // 【09-30 续 4】总闸是站台讲述人自己的（/jsr midium on|off）。
            if (!TrainAnnounceSwitch.isMidiumEnabled() || ridingTrain()) {
                continue; // 等待期间总闸被关 / 玩家上了车 —— 这一班不念了
            }
            // 这一串的样式此刻还开着吗（关着 = 门串自己的否决权，全局样式也不越过它）。
            // 【09-30 续 4】站台讲述人只有「关闭」与 userN —— 残留的上海 / 香港档当「关闭」。
            int doorMode = EscalatorSpeedManager.getDoorPsdMidiumNarrateMode(mc.level, plan.runKey);
            if (doorMode == EscalatorSpeedData.PSD_NARRATE_OFF
                    || !EscalatorSpeedData.isPsdNarrateUserStyle(doorMode)) {
                continue;
            }
            // 全局样式覆盖（/jsr midium on default|userN）：门串开着时全局说了算。
            int styleOverride = TrainAnnounceSwitch.midiumStyleModeOverride();
            int mode = styleOverride >= 0 ? styleOverride : doorMode;
            Vec3 player = mc.player == null ? null : mc.player.position();
            if (TrainAnnounceSwitch.isOutsideMidiumNarrateRange(plan.runKey,
                    plan.source.x(), plan.source.y(), plan.source.z(), player)) {
                LOGGER.info("[SmoothLift/PsdChime] 站台广播(讲述人)：串 @{} 起播时刻玩家已在"
                                + " /jsr midium round 范围外 ⇒ 这一班不念",
                        posText(plan.source));
                continue;
            }
            // 多站台就近压制：这一时刻范围内还有别的**站台**的讲述人候选时，只让最近的那个开口。
            // ★【10-01 续】粒度是「站台」（不是 runKey = 车站）—— 见 isNearestMidiumNarrateRun 的注释。
            if (!isNearestMidiumNarrateRun(mc.level, doors, plan, player)) {
                LOGGER.info("[SmoothLift/PsdChime] 站台广播(讲述人)：玩家同时在多个站台的讲述人"
                                + "范围内，以最近的那个站台为准 ⇒ 站台 id={}（声源 @{}）这一班让位",
                        plan.platformId, posText(plan.source));
                continue;
            }
            // 终点站 / 站台名（上海 / 香港档的句料）与车站 / 线路名（userN 的 |SC| 等占位符）——
            //   都是「起播那一刻才查」的低频查询。
            MtrDwellAccess.ArrivalInfo arrival = MtrDwellAccess.isPlatformKnown(plan.platformId)
                    ? MtrDwellAccess.nearestArrival(plan.platformId) : null;
            String stationName = MtrDwellAccess.stationNameForPlatform(plan.platformId);
            String lineName = EscalatorSpeedData.isPsdNarrateUserStyle(mode)
                    ? MtrDwellAccess.lineNameForPlatform(plan.platformId) : null;
            String text = TrainAnnounceNarrator.arriveTextForStyle(mode,
                    arrival == null ? null : arrival.destination,
                    arrival == null ? null : arrival.platformName,
                    stationName, lineName, TrainAnnounceSwitch.midiumUserTextsList());
            boolean spoke = text != null
                    && TrainAnnounceNarrator.speak(text, plan.runKey,
                    plan.source.x(), plan.source.y(), plan.source.z(), true);
            // 文字出现地点与进站讲述人同一套：字幕（word）互斥、聊天框（chat）按行发，
            // off = 只出语音 —— 但开关是站台讲述人**自己的**（/jsr midium on <样式> word|chat|off）。
            // 列车上那一支上面已经拦掉，这里不会再冒文字。
            if (text != null) {
                TrainAnnounceSubtitle.show(text, plan.runKey,
                        plan.source.x(), plan.source.y(), plan.source.z(), true);
                if (TrainAnnounceSwitch.midiumTextMode() == TrainAnnounceSwitch.TextMode.CHAT
                        && mc.player != null) {
                    for (String line : text.split("\n")) {
                        if (!line.isBlank()) {
                            mc.player.displayClientMessage(new net.minecraft.network.chat.TextComponent(line.trim()), false);
                        }
                    }
                }
            }
            LOGGER.info("[SmoothLift/PsdChime] 站台广播·讲述人{}（这一串的门 @{} 里离玩家最近的一扇起播）"
                            + " → {}「{}」（终点站 {}、站台 {}）："
                            + "配置「开门音播完 {} 秒后开念」（第 {} tick）；"
                            + "★ 与进站广播的讲述人是两条互不相干的广播，可以同时存在",
                    EscalatorSpeedData.psdNarrateModeName(mode),
                    posText(plan.source),
                    text == null ? "跳过（这一档拼不出话）"
                            : (spoke ? "念出" : "念不出（本机没有可用语音引擎）"),
                    text == null ? "" : text,
                    arrival == null || arrival.destination == null ? "读不到" : arrival.destination,
                    arrival == null || arrival.platformName == null ? "读不到" : arrival.platformName,
                    EscalatorSpeedManager.getDoorPsdMidiumNarrateSeconds(mc.level, plan.runKey),
                    now);
            // ★★【10-01】念过了 ⇒ 带着 fired 标记**放回表里**（攒到循环外再 put，见 respawn）。
            //   为什么不能就地摘掉：摘掉 ⇔ 「这一串没有待念的计划」⇔ 同一串里后开门的那几扇门
            //   可以再排一条、再念一遍 —— 用户听到的「播报 2 次」就是它（LOG013 实测 2 tick / 8 秒
            //   两对重复，数字见 MIDIUM_NARRATE_HOLD_TICKS）。窗口由 midiumHoldOver 收口。
            plan.fired = true;
            plan.spokenTick = now;
            if (respawn == null) {
                respawn = new HashMap<>();
            }
            respawn.put(plan.runKey, plan);
        }
        if (respawn != null) {
            midiumNarratorVoice.putAll(respawn);
        }
    }

    /**
     * 【10-01】这条**已经念过**的守卫记录可以摘了吗。
     *
     * <p>两个条件都要满足（或撞上硬上限）：
     * <ol>
     *   <li><b>守卫窗口走完</b>：{@code 现在 - spokenTick >= }{@link #MIDIUM_NARRATE_HOLD_TICKS}
     *       （10 秒）—— 这是「同一班车只念一次」的主力；</li>
     *   <li><b>这个站台的门已经全关</b>：还在开着（{@code fraction > }{@value #EDGE}）就继续挡着，
     *       于是停站特别长的站台自然挡得更久，不会在门还开着的时候又念一遍；
     *       ★【10-01 续】这里从「这一串（= 车站）」收窄到「**这条计划自己的那个站台**」
     *       （{@link #sameBroadcastScope}）—— 车站里**别的**站台有一扇门卡着不关时，不该
     *       替这个站台一直占着守卫（那会把下一班车的播报也吃掉）；</li>
     *   <li><b>硬上限</b>：{@code 现在 - spokenTick >= }{@link #MIDIUM_NARRATE_HOLD_MAX_TICKS}
     *       （60 秒）—— 门卡住不关 / 这些门已经不在快照里时的兜底，宁可多念一次也不要永久哑掉。</li>
     * </ol>
     *
     * <p>★ 门快照为空（车出站、区块卸载）⇒ 第 2 条按「已全关」算（没有门还开着），
     * 于是只剩第 1 条的 10 秒窗口 —— 与进站播报「车走了照样能把这一条收掉」同一条思路。
     */
    private static boolean midiumHoldOver(List<PsdDoorTracker.DoorView> doors,
                                          MidiumNarratorPlan plan, long now) {
        if (now < plan.spokenTick + MIDIUM_NARRATE_HOLD_TICKS) {
            return false;
        }
        if (now >= plan.spokenTick + MIDIUM_NARRATE_HOLD_MAX_TICKS) {
            return true;
        }
        if (doors != null) {
            for (PsdDoorTracker.DoorView d : doors) {
                if (sameBroadcastScope(d, plan) && d.fraction() > EDGE) {
                    return false; // 这个站台还有门开着 ⇒ 这一班车还没走完，继续挡着
                }
            }
        }
        return true;
    }

    /**
     * 【10-01 续】这一扇门与这条站台广播计划**是不是同一个播报范围** —— 判据与
     * {@link #planMidiumNarrator} 取声源时逐条对齐：
     *
     * <ul>
     *   <li>计划认得站台（{@link MtrDwellAccess#isPlatformKnown}）⇒ 比 **站台 id**
     *       （与 {@link PsdDoorTracker#nearestOnPlatform} 同一把尺子）；</li>
     *   <li>认不到 ⇒ 回落 **runKey**（= 车站级配置身份）—— 与「认不到就不冒充同一个站台」
     *       那条老约定一致（{@link PsdDoorTracker#nearestOnPlatform} 的注释）。</li>
     * </ul>
     *
     * <p>★ 为什么不能用 {@code runKey} 一把尺子量到底：runKey 是**车站级**
     * （【09-30 续 9】）⇒ 同一个车站的 6~7 个站台共用一个 runKey ⇒ 「这个站台的门全关了没有」
     * 会被**别的站台**那扇卡住不关的门一直答「没关」⇒ 守卫窗口被硬拖到 60 秒硬上限，
     * 把玩家自己站台的下一班车也一起吃掉（LOG114 里 15:48:23 那次开门**没有任何排计划日志**
     * 就是这一类：车站里另一条门线还留着几扇「刚看到就是开着」的门）。
     */
    private static boolean sameBroadcastScope(PsdDoorTracker.DoorView door, MidiumNarratorPlan plan) {
        return MtrDwellAccess.isPlatformKnown(plan.platformId)
                ? door.platformId() == plan.platformId
                : door.runKey() == plan.runKey;
    }

    /**
     * 【10-01】站台讲述人（以及到站播报）的等待基线 —— 「**开门音**播完」到底是多少毫秒。
     *
     * <p>★ 为什么要单独取一次，而不是直接用触发那一扇门的 {@code openPlayable}：
     * 它常常是 {@code null} —— {@link #resolvePlayable} 对**不在玩家射程里**的门按设计
     * 既不建实例也不刷日志（那条路是常态）。旧写法把 null 当成 0ms ⇒ 「开门音播完 0ms 后再等
     * N 秒」⇒ {@code startTick == 现在} ⇒ **当场开念**，也就是用户报的「提前播报一次」。
     *
     * <p>★ 口径 = 这一串（{@code runKey}）**「开门音」这一项此刻生效的素材时长**，
     * 与哪一扇门触发的无关。射程内触发的正常情形下它与旧写法取的是同一份配置的同一个时长
     * ⇒ 行为不变；射程外触发时它给出**正确**的起点，不再提前。
     *
     * <p>★ 开门音这一项被关掉 / 设成「不播」⇒ 返回 0（没有「播完」可等，起点就是开门那一瞬）。
     * 这与 {@link #resolvePlayable} 返回 {@code null} 的三种「不播」原因一一对应，但**不刷日志**
     * （{@code resolveTone} 本身不打日志，与真正起播那条路取的也是同一个时长）。
     */
    private static long midiumOpenBeepMs(Minecraft mc, long runKey, Playable openPlayable) {
        if (openPlayable != null) {
            return Math.max(0L, openPlayable.tone().durationMs());
        }
        if (!EscalatorSpeedManager.isDoorPsdHelpEnabled(mc.level, runKey)
                || !EscalatorSpeedManager.isDoorPsdToneEnabled(mc.level, runKey, "open")) {
            return 0L;
        }
        String customId = psdToneCustomId(mc, runKey, "open");
        if (STOP_SENTINEL.equals(customId)) {
            return 0L; // 这一串把「开门音」设成「不播」—— 没有可等的播完点
        }
        Tone tone = resolveTone(mc, "open", customId);
        return tone == null ? 0L : Math.max(0L, tone.durationMs());
    }

    /**
     * 【09-30 续 3】这条计划是不是「此刻范围内离玩家最近的那个**站台**」的播报。
     *
     * <p>★★【10-01 续】候选的**粒度**必须是「站台」，不能是「runKey」：runKey 自
     * 【09-30 续 9】起是**车站级** ⇒ 按 runKey 归并时候整个车站（6~7 个站台）会塌成
     * **一个**候选 ⇒ {@code nearest == runKey} **恒真** ⇒ 这道「多站台就近压制」形同虚设
     * （它写在【09-30 续 3】，可在车站级身份落地之后就再没生效过）。
     * 后果正是用户报的那条：站在 A 站台，**B 站台**（同层 11 格外的另一条门线 / 别层）
     * 的列车开门时 B 的播报也照念 —— 听起来就是「站台广播提前播了一次」。
     *
     * <p>判据与进站讲述人的 {@code nearestNarrateRun} 同构：候选 = 门快照里**每一个站台**
     * （认不到站台的按 runKey 归并，见 {@link #sameBroadcastScope}）里离玩家最近的一扇，
     * 样式开着且玩家在它的 /jsr round 范围内的那些里，三维欧氏距离最小的那个。
     * 门快照为空（车刚走、区块卸载）或一个候选都没有时放行 —— 压制只该发生在
     * 「确实有别的站台离玩家更近」的场合。
     */
    private static boolean isNearestMidiumNarrateRun(Level level, List<PsdDoorTracker.DoorView> doors,
                                                     MidiumNarratorPlan plan, Vec3 player) {
        if (doors == null || doors.isEmpty()) {
            return true;
        }
        PsdDoorTracker.DoorView nearest = null;
        double best = Double.MAX_VALUE;
        for (PsdDoorTracker.DoorView d : doors) {
            // 候选 = 这一扇门所在那个站台的站台讲述人样式开着（只有 userN 档算数），
            // 且玩家在它的 /jsr midium round 范围内（站台讲述人自己的范围）。
            int mode = EscalatorSpeedManager.getDoorPsdMidiumNarrateMode(level, d.runKey());
            if (mode == EscalatorSpeedData.PSD_NARRATE_OFF
                    || !EscalatorSpeedData.isPsdNarrateUserStyle(mode)) {
                continue;
            }
            if (TrainAnnounceSwitch.isOutsideMidiumNarrateRange(d.runKey(),
                    d.x(), d.y(), d.z(), player)) {
                continue;
            }
            double distance = player == null ? 0.0
                    : player.distanceTo(new Vec3(d.x(), d.y(), d.z()));
            if (nearest == null || distance < best) {
                best = distance;
                nearest = d;
            }
        }
        // 一个候选都没有（快照空 / 样式全关 / 全部越界）⇒ 不压制，这一条照念。
        return nearest == null || sameBroadcastScope(nearest, plan);
    }

    /**
     * 【1.26】候选门 {@code cand} 比当前选中的 {@code cur} 更靠近玩家吗。
     *
     * <p>{@code player == null} 时返回 false（保持先来的那一扇）—— 与「界面 / 还没进世界」
     * 时距离按 0 算的老口径一致：宁可不动，也不要凭 null 猜一个位置。
     */
    private static boolean closerThan(Vec3 player, PsdDoorTracker.DoorView cand,
                                      PsdDoorTracker.DoorView cur) {
        if (player == null) {
            return false;
        }
        return player.distanceToSqr(cand.x(), cand.y(), cand.z())
                < player.distanceToSqr(cur.x(), cur.y(), cur.z());
    }

    /**
     * 【1.21】把音频库里的一个文件名解析成可播的 {@link Tone}（进站报站用）。
     *
     * <p>与 {@link #resolveMidiumTone} **同形**（同样没有内置档分支 —— 进站报站没有内置素材），
     * 分开只是为了日志能点名是哪一项。
     */
    private static Tone resolveArriveTone(Minecraft mc, String audioId) {
        byte[] bytes = EscalatorSpeedManager.getAudioBytes(mc.level, audioId);
        if (bytes == null) {
            if (warnedMissingArrive.add(audioId)) {
                LOGGER.warn("[SmoothLift/PsdChime] 进站报站的素材「{}」不在本维度的音频库里"
                        + "（可能还没同步到 / 已被删除），这一项不会出声", audioId);
            }
            return null;
        }
        return new Tone(PSD_DOOR_CLOSE, audioId, null,
                EscalatorAudioPlayer.customDurationMs(audioId, bytes),
                EscalatorAudioPlayer.customAnnounceSplitMs(audioId, bytes),
                true);
    }

    /**
     * 【1.17】把音频库里的一个文件名解析成可播的 {@link Tone}。
     *
     * <p>与 {@link #resolveTone} 走**同一个**建 Tone 的形状（自定义档：event 只是占位，
     * 真正决定播什么的是 {@code customId}，见 {@link PsdMusicInstance#resolve}），
     * 差别只有一条：这里**没有内置档分支** —— 到站播报没有内置素材。
     *
     * @return {@code null} = 库里没有这段音频（客户端还没同步到 / 名字被删了）
     */
    private static Tone resolveMidiumTone(Minecraft mc, String audioId) {
        byte[] bytes = EscalatorSpeedManager.getAudioBytes(mc.level, audioId);
        if (bytes == null) {
            if (warnedMissingMidium.add(audioId)) {
                LOGGER.warn("[SmoothLift/PsdChime] 到站播报的素材「{}」不在本维度的音频库里"
                        + "（可能还没同步到 / 已被删除），这一项不会出声", audioId);
            }
            return null;
        }
        return new Tone(PSD_DOOR_CLOSE, audioId, null,
                EscalatorAudioPlayer.customDurationMs(audioId, bytes),
                EscalatorAudioPlayer.customAnnounceSplitMs(audioId, bytes),
                true);
    }

    /**
     * 看这一扇门的门值有没有发生「开始关 / 开始开」的跳变，有就**放一次**对应素材。
     *
     * <p>【1.15】两支的动作完全不对称，别把它们看成一件事：
     * <ul>
     *   <li><b>开门</b>：播开门素材，并且**顺手把这一轮的整段关门提示音排出去**
     *       （{@link #planClose}）—— 关门没有预警信号，「提前量」只能趁开门先排；</li>
     *   <li><b>关门</b>：先看有没有排过 —— 排过且还在响 ⇒ **什么都不做**
     *       （让它自然收尾，结尾正好落在门上）；排过但早播完了 ⇒ 退回剪头补一声；
     *       没排过 ⇒ 走剪头（{@link #resumeClose}）。</li>
     * </ul>
     *
     * @param prev 上一次看到的门值（首次看到时 == 当前值，因此不会误触发）
     */
    private static void detect(Minecraft mc, PsdDoorTracker.DoorView door, float prev,
                               double distance) {
        float fraction = door.fraction();
        boolean closing = prev >= 1.0f - EDGE && fraction < 1.0f - EDGE;
        boolean opening = prev <= EDGE && fraction > EDGE;
        if (!closing && !opening) {
            return;
        }
        if (opening) {
            // ★★【1.15 · 第七轮】开门这一瞬要做**两件互不相干**的事，必须各解析各的（这是用户
            //   连报三次「时间足够也没人声」的**真正根因**）：
            //
            //   ① 排「关门提示音」的提前量 —— 它服务的是**关门**那一段声音，所以必须用
            //      `"close"` 解析（关门那一项自己的素材、时长、分界点）。
            //      ⛔ 这里曾经复用了开门那一份（`resolvePlayable(..., which=open, ...)` 的结果
            //      直接喂给 planClose）。而开门素材 `dooropen.ogg` 只有 **2283ms**、且
            //      `detectAnnounceSplitMs -> **-1**`（它根本没有「播报 + 嘀嘀」两段结构，就是
            //      两段机械声），于是 planClose 在 `!tone.announce() || tone.splitMs() <= 0` 这道
            //      闸门上**每次都直接 return** ⇒ 提前量**从来没排出来过** ⇒ 每一站都只有嘀嘀、
            //      停多久都一样、第二次第三次都一样。离线证据：`_tools/_measure_split.py`
            //      对 dooropen.ogg 实测分界点 = **-1**，对 mdoorclose.ogg = 6707。
            //      ★ 教训：**「哪一项的设置服务哪一项的声音」不能靠调用点顺手复用** ——
            //      两个方向是两个独立配置项，谁服务谁必须写在解析那一步。
            //   ② 播「开门提示音」—— 用 `"open"` 解析。它自己关着就不响，与 ① **无关**
            //      （判断「开门音要不要响」不该顺带把关门音的提前量也取消掉）。
            //   【1.16】开门这一份现在要**先**解析：它的时长是「强制等待」的起点
            //   （用户要的是「开门音效**播放完**后再等 X 秒」，所以必须知道开门素材多长）。
            Playable openPlayable = resolvePlayable(mc, door, "open", distance);
            Playable closePlayable = resolvePlayable(mc, door, "close", distance);
            if (closePlayable != null) {
                planClose(mc, door, closePlayable, openPlayable);
            }
            // 【1.17】到站播报排计划 —— ★ 必须排在下面那条 `openPlayable == null` 的早退**之前**：
            //   它跟「这一维度要不要放开门音」是**两个独立配置**（自己由 cachedMidiumAudio 决定），
            //   把开门音设成「不播」不该顺带把站台广播也取消掉。
            //   `openPlayable` 为 null 时只影响「等待的起点从哪算」——见 planArrivalAnnounce。
            //   ★【1.26】不再把这一扇门的距离传进去：到站播报是**这一串**的广播，
            //   距离与声源都由它自己取「本串最近那一扇」（那一扇不是这一扇）。
            planArrivalAnnounce(mc, door, openPlayable);
            // 【09-30 续 3】站台广播（讲述人）：同样排在 `openPlayable == null` 的早退之前 ——
            //   它与自定义到站播报是两条独立的广播，素材「不播」不影响讲述人照念。
            planMidiumNarrator(mc, door, openPlayable);
            if (openPlayable == null) {
                return; // 开门那一项关着 / 这一扇门设为不播 / 音量算得 0 —— 原因已在 resolvePlayable 里记过日志
            }
            boolean played = play(mc, openPlayable.tone(), door, openPlayable.volume(), 0) != null;
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开始开门 → {} {}（距玩家 {} 格；音量 {}，维度默认 {}）",
                    posText(door), played ? "播放" : "播不出（素材缺失 / 解码失败）",
                    toneLabel(openPlayable.tone()),
                    String.format("%.1f", distance),
                    openPlayable.toneVolume(), cachedVolume);
            return;
        }
        // ---------- 以下都是关门端 ----------
        // ★【1.16】门一动 ⇒ **立刻**掐断正在播的那段「强制等待人声」，不管它播到哪里
        //   （用户原话：「一旦到关门时间嘀嘀嘀开始播放，就立即切断正在播放的人声提示」）。
        //   ★ 这一刀必须放在 resolvePlayable **之前**：它与「关门这一项此刻播不播」无关 ——
        //   关门音被关掉时这段人声**照样**要断，否则一段无主的人声会一直压着门关上。
        //   ★【1.23】这一刀掐的是**这扇门自己**的兜底人声（按 door.key() 存），
        //   而 detect 现在对快照里**每一扇**门都跑一次，所以「人声那扇门自己开始关」时
        //   当场就被掐断；另一个覆盖点是 tickForcedVoice（它对**所有**门再做一遍门值检查），
        //   两处合起来才覆盖「人声那扇门早已不在快照里」的情形。
        cutForcedVoice(mc, door.key(), "门开始关了");
        Playable playable = resolvePlayable(mc, door, "close", distance);
        if (playable == null) {
            return; // 子开关关着 / 这一扇门设为不播 / 音量算得 0 —— 原因已在 resolvePlayable 里记过日志
        }
        Tone tone = playable.tone();
        long now = mc.level.getGameTime();
        // 【1.15 · 第六轮】门一开始关，这一刻就是「门程」的起点（全关那一刻要用它算门程）。
        closeStartTick.put(door.key(), now);
        // 【1.15 · 第六轮】同时把这一轮的提前量计划摘掉：它的前提是「门还全开着就起播」，
        //   门都开始关了还没到点 ⇒ 这一轮**永远错过了**。不摘的话，当借来的周期偏长时
        //   这一条会在**门正在关的中途**到点（那时门值已掉出「全开」判据，通常会被
        //   firePlannedClose 拦掉；但门刚开始关的头一两个 tick 门值还在容差内，会漏过去），
        //   于是整段和剪头那一声叠在一起。
        plannedCloseStart.remove(door.key());
        // 【1.15】排过「整段提前播」的门：这一瞬**什么都不做** —— 那条素材正好还剩一个门程，
        //   让它自然收尾，结尾就落在门上。这就是用户要的「不是开始关门才播放提示音」。
        Long firedAt = plannedCloseFired.get(door.key());
        if (firedAt != null) {
            PsdMusicInstance whole = wholePlaying.get(door.key());
            long soundEnd = firedAt + (tone.durationMs() + 49) / 50;   // 这条素材本该在哪一 tick 播完
            if (now < soundEnd) {
                // 还在响。正常情况下它会在门全关那一刻收尾 ⇒ 什么都不做；
                // 但周期是**可能借来的**（见 globalCycleTicks），借的那个比这一轮长时它会拖过门全关。
                // 这里量不到「门还剩多久」（要下一 tick 才有第二个采样点），所以挂给 resumeClose 去判。
                pendingClose.put(door.key(), new Pending(now, fraction, tone, door,
                        playable.volume(), whole, soundEnd));
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开始关门 → 整段关门提示音已在提前量那一点起播，"
                                + "还剩约 {}ms 播完（预计收在门关上这一刻）；下一 tick 核对一次",
                        posText(door), (soundEnd - now) * 50L);
                return;
            }
            // 停站比上一轮长 ⇒ 提示音**早就播完**了门才关。这一声不能凭空没了：
            //   退回「路 A」补一次 —— 剪掉人声、只留嘀嘀，结尾仍然落在门上（见 resumeClose）。
            plannedCloseFired.remove(door.key());
            wholePlaying.remove(door.key());
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开始关门 → 提前播的整段提示音早就播完了"
                            + "（这一轮停站比上一轮长 {} tick），退回剪头对齐结尾",
                    posText(door), now - soundEnd);
        }
        if (tone.durationMs() > 0) {
            // 【1.15】「路 A」：先把请求挂起**一 tick**，用「门值在这两个 tick 之间的变化」量出门速，
            //   从而把提示音的结尾对准门关上的那一刻（见类注释）。
            //   量不出来（例如门这一帧没被渲染）时由 resumeClose 兜底按原样播，不会没声音。
            pendingClose.put(door.key(), new Pending(now, fraction, tone, door,
                    playable.volume(), null, 0L));
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开始关门 → 先量一 tick 门速再播 {}"
                            + "（距玩家 {} 格；音量 {}，维度默认 {}）",
                    posText(door), toneLabel(tone),
                    String.format("%.1f", distance),
                    playable.toneVolume(), cachedVolume);
            return;
        }
        boolean played = play(mc, tone, door, playable.volume(), 0) != null;
        // 日志把「生效音量」和「共用默认」一起打出来 —— 玩家报「填了音量没用」时，
        // 一眼就能看出这一项到底是走了自己的音量，还是在跟随共用默认。
        // ★ 关门端走到这一行只剩一种情况：**时长没量出来**（所以既不排提前量、也不剪头）。
        //   正常关门一定在上面挂起、由 resumeClose 打「从 XXXms 起」那条日志 ——
        //   这正是「对齐没生效」时唯一能从日志里看出来的线索。
        LOGGER.info("[SmoothLift/PsdChime] 门 @{} 开始{} → {} {}（距玩家 {} 格；"
                        + "音量 {}，维度默认 {}）",
                posText(door), closing ? "关门" : "开门", played ? "播放" : "播不出（素材缺失 / 解码失败）",
                toneLabel(tone), String.format("%.1f", distance),
                playable.toneVolume(), cachedVolume);
    }

    /**
     * 【1.15】「这一扇门这一项**此刻**要播的东西」—— 前置检查的**唯一**一处实现
     * （三个调用点共用：开门那一瞬 / 关门那一瞬 / 提前量到点，见 {@link Playable}）。
     *
     * <p>顺序就是「最省事的先判」，而且三道闸的日志措辞都与「现在还不该播」无关 ——
     * 提前量那一路是在**开门**时被调用的，那里还没有任何门在动，措辞不能写成「开始开门/关门」。
     *
     * @return {@code null} = 不播（子开关 / 这一扇门设为不播 / 音量 0 / 站在范围外）；
     *         非 null 时 {@link Playable} 里的东西可以直接交给 {@link #play}
     */
    private static Playable resolvePlayable(Minecraft mc, PsdDoorTracker.DoorView door, String which,
                                            double distance) {
        // ⓪ 【1.20】这一扇门**自己**的总开关（石斧 UI 那个按钮改的就是它）。
        //   ★ 为什么不能只靠 onClientTick 那道全局早退：总开关现在按门存，可能出现
        //   「维度默认关着、某扇门自己开着」——只判维度默认会让那扇门永远不响。
        if (!EscalatorSpeedManager.isDoorPsdHelpEnabled(mc.level, door.runKey())) {
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 的提示音总开关是关的，跳过", posText(door));
            return null;
        }
        // ① 这一项（open / close）有没有被单独关掉（指令 /pbmmusic open|close on|off 或石斧 UI 的开关行）
        if (!EscalatorSpeedManager.isDoorPsdToneEnabled(mc.level, door.runKey(), which)) {
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 的「{}」子开关是关的，跳过",
                    posText(door), EscalatorSpeedData.psdToneLabel(which));
            return null;
        }
        // ② 这一扇门这一项是不是被设成「不播」
        String customId = psdToneCustomId(mc, door.runKey(), which);
        if (STOP_SENTINEL.equals(customId)) {
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 的「{}」设为「不播」，跳过",
                    posText(door), EscalatorSpeedData.psdToneLabel(which));
            return null;
        }
        // ③ 这一项**生效**的音量（单项没调过 → 回落共用默认）。
        //   ★★【1.25】这里只留**音量系数**（不含距离增益）：距离增益交给实例每 tick 现算
        //   （见 PsdMusicInstance.refreshVolume），与到站 / 进站那两类报站音完全一致。
        //   以前是把增益**一次算死**在这里（factorOnly=false）⇒ 门开那一刻玩家站在 15 格外，
        //   这条提示音就永远只有 0.4% 音量，哪怕下一秒走到门跟前也不会变响 ——
        //   正是用户说的「走近屏蔽门听声音，有的还是没有声音」的另一半。
        //   ★ 那一道「在不在射程内」的闸仍然留在这里：范围外的门**连声音实例都不建**，
        //   既不占通道也不刷日志（这是常态）。
        int toneVolume = EscalatorSpeedManager.getDoorPsdToneVolume(mc.level, door.runKey(), which);
        float volume = volumeFactor(toneVolume);
        if (gain(distance, ROUND_TONE) * volume <= 0.0f) {
            return null; // 站在范围外 / 音量设为 0：这是常态，不刷日志
        }
        // 【1.15】内置档（{@code dooropen}/{@code doorclose}/{@code mdoorclose}）→ 对应事件；
        //   null = 玩家导入的音频（customId 就是文件名，走 injectAudio 分支）。
        //   两条路的判断、字节来源、素材时长都在 resolveTone 里判一次（Tone 注释里有理由）。
        return new Playable(resolveTone(mc, which, customId), volume, toneVolume);
    }

    /**
     * 【1.15】把「这一扇门这一项要播的东西」解析成一个 {@link Tone}。
     *
     * <p>内置还是自定义**只认** {@link EscalatorSpeedData#psdBuiltinKey}（数据层那唯一一处判断），
     * 这里只做「档 → 事件」与「档 → 字节」的最后一步。
     *
     * <p>时长在这一步就量出来（关门端算剪头偏移要用），两个来源各走各的：
     * 内置档从**模组自己的 jar** 读（{@link EscalatorAudioPlayer#bundledBytes}），
     * 自定义档用存档同步过来的字节。量不到的代价只是「这一项不剪头」，不影响能不能出声。
     *
     * <p>【1.15】★ 「默认（短）」（{@code default-s}）在这里被翻译成 {@code announce = false}：
     * 素材、时长、分界点**全都与 {@code default} 一样**（所以走的是同一条剪头算式），
     * 唯一的差别是 {@link #detect} 不会再在**关门**时整段起播那段语音播报
     * ⇒ 听起来就是**纯粹一串嘀嘀**、而且结尾仍然对齐门关上（正是最早那版的行为）。
     *
     * <p>★ 为什么不是「把 {@code splitMs} 压成 {@code -1}」：那样会**连带**关掉
     * {@link #resumeClose} 里那道「不许剪进播报里」的夹取（它的判据也是 {@code split > 0}）。
     * 对内置素材看不出区别（它的起点本来就落在分界点之后），但对**嘀嘀段比门程短**的导入素材，
     * 就会在关门时漏出一截语音 —— 与玩家选「短」的意愿正好相反。两件事分开存才不会有这个副作用。
     */
    private static Tone resolveTone(Minecraft mc, String which, String customId) {
        String builtin = EscalatorSpeedData.psdBuiltinKey(which, customId);
        if (builtin != null) {
            return new Tone(builtinEvent(builtin), null, builtin,
                    EscalatorAudioPlayer.bundledDurationMs(builtin),
                    EscalatorAudioPlayer.bundledAnnounceSplitMs(builtin),
                    !EscalatorSpeedData.isPsdBuiltinShort(customId));
        }
        byte[] bytes = EscalatorSpeedManager.getAudioBytes(mc.level, customId);
        return new Tone(PSD_DOOR_CLOSE, customId, null,
                EscalatorAudioPlayer.customDurationMs(customId, bytes),
                EscalatorAudioPlayer.customAnnounceSplitMs(customId, bytes),
                true); // 导入素材：只要它真有「播报+嘀嘀」结构（splitMs > 0）就照播
    }

    /** 日志 / 反馈里显示的素材名（内置档带「（内置）」，与指令反馈同一套写法）。 */
    private static String toneLabel(Tone tone) {
        return tone.customId() != null ? tone.customId() : (tone.builtinKey() + ".ogg（内置）");
    }

    /**
     * 【1.15】把上一 tick 挂起的关门提示音播出去 —— 用这一刻的门值算出**门速**，
     * 再决定从素材的第几毫秒开始播（让结尾正好落在门关上那一刻）。
     *
     * <p>★ 挂到这里的有三种关门（{@link #detect} 里那几支）：
     * <ul>
     *   <li><b>「只要嘀嘀」那一档</b>（{@code default-s} / 素材没有两段结构）—— 常态；</li>
     *   <li><b>带人声那一档的兜底</b> —— 提前量没排上（这个世界里还没学到周期 / 整轮塞不下素材），
     *       或提前播完得太早（停站比上一轮长）。这时剪头会落在**分界点之后**
     *       （{@code duration − 门程 ≈ 6806ms > split 6707ms}）⇒ 人声不会被放出来，
     *       听到的就是**结尾仍然对齐门关上**的一串嘀嘀。</li>
     *   <li>【1.15 · 第六轮】<b>「整段还在响、但会拖过门全关」</b> —— 周期是**借来的**
     *       （{@link #globalCycleTicks}），借的那个比这一轮长时就会出现。这一支会先把那条
     *       整段**掐掉**（{@link #stop}）再走剪头，否则门都关上了、嘀嘀还在响。
     *       容差 {@value #OVERHANG_SLACK_TICKS} tick：周期准确时整段的结尾本来就落在
     *       门全关前约 1 tick，判据不许在那时误伤（否则会平白把好端端的一条剪掉）。</li>
     * </ul>
     * 素材没有两段结构（导入的纯嘀嘀音频、开门端的 {@code dooropen.ogg}）时这套算式
     * 也会算出「没得剪」而退回整段播。
     *
     * <p>三种兜底一律退回「整段从头播」，绝不因为对齐算不出来而静音：
     * <ul>
     *   <li>门这一 tick 没动（红石锁着 / 被挡）→ 再等，最多 {@value #ALIGN_MAX_WAIT_TICKS} tick；</li>
     *   <li>门反向（关到一半又开回去）→ 立刻整段播一次（「门机起动过」这个语义不能丢）；</li>
     *   <li>门不再出现在快照里（走出渲染距离 / 被拆）或等到超时 → 立刻整段播一次。</li>
     * </ul>
     */
    private static void resumeClose(Minecraft mc, List<PsdDoorTracker.DoorView> doors) {
        if (pendingClose.isEmpty()) {
            return;
        }
        long now = mc.level.getGameTime();
        Map<Long, PsdDoorTracker.DoorView> live = new HashMap<>();
        for (PsdDoorTracker.DoorView door : doors) {
            // 【1.29】幕墙 / 幕墙尾部不是门：关门提示音这条链路只认门
            //   （用户点名：「只有连在一起的屏蔽门的门的播报是铃声」）。
            if (!door.door()) {
                continue;
            }
            live.put(door.key(), door);
        }
        Iterator<Map.Entry<Long, Pending>> it = pendingClose.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Pending> entry = it.next();
            Pending pending = entry.getValue();
            long elapsed = now - pending.tick();
            if (elapsed <= 0) {
                continue; // 同一 tick 内不会被调两次；留着当保险
            }
            PsdDoorTracker.DoorView door = live.get(entry.getKey());
            if (door == null || elapsed > ALIGN_MAX_WAIT_TICKS) {
                it.remove();
                if (pending.whole() != null) {
                    // 【1.15 · 第六轮】门已经看不到（走出渲染距离 / 被拆）或一直没动，
                    //   而那条**整段**正在响 —— 不要再叠一条上去，就让它自己响完。
                    LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关门：整段提示音正在响，门已量不到"
                                    + "（等了 {} tick），不再叠一声", posText(pending.door()), elapsed);
                    continue;
                }
                play(mc, pending.tone(), pending.door(), pending.volume(), fallbackStartMs(pending.tone()));
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关门提示音 {} 量不到门速"
                                + "（门不在渲染距离内 / 等了 {} tick 还是没动），按兜底起点播",
                        posText(pending.door()), toneLabel(pending.tone()), elapsed);
                continue;
            }
            float drop = pending.fraction() - door.fraction();
            if (drop < 0.0f) {
                // 关到一半又开回去：关门这个动作确实起动过，照旧响一次（不剪头）
                it.remove();
                if (pending.whole() != null) {
                    LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关到一半又开回去，整段提示音正在响，不再叠一声",
                            posText(door));
                    continue;
                }
                play(mc, pending.tone(), pending.door(), pending.volume(), fallbackStartMs(pending.tone()));
                LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关到一半又开回去 → {} 按兜底起点播一次",
                        posText(door), toneLabel(pending.tone()));
                continue;
            }
            if (drop == 0.0f) {
                continue; // 还没动，再等一 tick（超时由上面那一支兜底）
            }
            it.remove();
            double perTick = drop / elapsed;                                   // 门值 / tick
            double remainMs = door.fraction() / perTick * 50.0;                // 1 tick = 50ms
            // 【09-30 续 5】单帧量出的门速可能被污染 —— 采样间隔越长越不准，而且
            // 「列车出站那一刻门值瞬间跳到全关」会让两个采样点根本不是同一次关门动作的。
            // 物理上「门还要走的时间」**不可能超过一整段门程**（关门起点 → 全关），
            // 所以用跨门实测的门程当上限封住它：超了就说明量到的是垃圾，按门程算。
            // 不封的话 remainMs 会被量成比素材还长 ⇒ 剪头算式算出负数 ⇒
            // **整段素材连人声一起放出来**（LOG14 21:31:37 那条「整段（素材 10806ms
            // 不长于门程）」就是这一支的现场 —— 用户听到的「早几秒多出的提示音」）。
            long travelMs = (globalTravelTicks > 0 ? globalTravelTicks
                    : MtrDwellAccess.DEFAULT_TRAVEL_TICKS) * 50L;
            if (remainMs > travelMs) {
                remainMs = travelMs;
            }
            // 【1.15 · 第六轮】这条**整段**已经在响了 —— 它「本该」在门全关那一刻收尾。
            //   周期可能是**借来的**（见 globalCycleTicks），借的那个比这一轮长时，
            //   它会一直响到门全关**之后**（门口已经关上、嘀嘀还在响）—— 那就违反用户那条
            //   「提示音播完那一刻门正好关上」的硬要求了。所以这里比一次：
            //     整段还要响多久  vs  门还要多久才全关（用**跨门实测的门程**当尺子，不是单帧速度）
            //   拖过头就**掐掉**它、改用剪头补一声（只嘀嘀、结尾仍落在门上）。
            if (pending.whole() != null) {
                long overhang = pending.wholeEndTick() - (now + globalTravelTicks);
                if (globalTravelTicks > 0 && overhang > OVERHANG_SLACK_TICKS) {
                    stop(mc, pending.whole());
                    wholePlaying.remove(entry.getKey());
                    plannedCloseFired.remove(entry.getKey());
                    LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关门：提前播的整段**会拖过门全关**约 {}ms"
                                    + "（跨门借来的周期比这一轮长），掐掉它、改用剪头对齐结尾",
                            posText(door), overhang * 50L);
                    // 落到下面的剪头算式
                } else {
                    LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关门：整段还剩约 {}ms、门还要 {}ms 才全关"
                                    + "⇒ 会收在门关上那一刻，不动它",
                            posText(door), (pending.wholeEndTick() - now) * 50L,
                            globalTravelTicks > 0 ? globalTravelTicks * 50L : -1L);
                    continue;
                }
            }
            int rawStart = (int) Math.round(pending.tone().durationMs() - remainMs);
            // 【1.15】剪头不许剪进**语音播报**里：素材的「嘀嘀」段比门程还短时（{@code rawStart}
            //   落到了分界点之前），宁可让嘀嘀早播完，也不要顺手把那段语音放出来 ——
            //   走到这一档只有两种意愿：「不要播报」（选了「默认（短）」，见 {@link #resolveTone}），
            //   或者**提前量没排上/排晚了**（兜底，见方法注释）—— 两种都**不该**在这里漏出人声。
            //   素材没有两段结构（split ≤ 0）时这一支恒不成立。内置素材也恒不成立
            //   （它的剪点 6806ms 本来就在分界点 6707ms 之后）—— 这是留给**导入素材**的保险：
            //   哪天判据被改成「也让 announce=false 的导入素材走这一档」，它必须还在。
            int split = pending.tone().splitMs();
            if (split > 0 && rawStart < split) {
                rawStart = split;
            }
            int startMs = EscalatorAudioPlayer.quantizeOffset(rawStart);
            playAligned(mc, pending, startMs, remainMs);
        }
    }

    /**
     * 【09-30 续 5】量不出对齐偏移时的兜底起点：「默认（短）」这类**不要人声**的档
     * 从嘀嘀段起点播 —— 绝不把语音播报漏出来（与 {@link #resumeClose} 剪头里那道
     * 「不许剪进播报里」的夹取同一条原则；原来这两个兜底是**整段从头播**，
     * 会把 6.7 秒的人声整个放出来）。带人声的档维持从头播：对齐不上是对齐问题，
     * 不是意愿问题。
     */
    private static int fallbackStartMs(Tone tone) {
        return tone.splitMs() > 0 && !tone.announce()
                ? EscalatorAudioPlayer.quantizeOffset(tone.splitMs()) : 0;
    }

    /**
     * 【1.15】按「剪掉开头 {@code startMs} 毫秒」播一次；剪不动（没得剪 / 只剩几十毫秒）
     * 就退回整段从头播。
     *
     * <p>「素材不比门程长」（{@code startMs <= 0}）是最常见的一支：开门端的 2.28 秒素材
     * 对 MTR4 的 4 秒门程就是这种情况 —— 它本来就该整段播，别去动它。
     */
    private static void playAligned(Minecraft mc, Pending pending, int startMs, double remainMs) {
        Tone tone = pending.tone();
        int duration = tone.durationMs();
        if (startMs <= 0 || startMs > duration - EscalatorAudioPlayer.SLICE_STEP_MS) {
            play(mc, tone, pending.door(), pending.volume(), 0);
            LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关门 → 播放 {} 整段"
                            + "（素材 {}ms 不长于门程，没有可剪的头部）",
                    posText(pending.door()), toneLabel(tone), duration);
            return;
        }
        if (play(mc, tone, pending.door(), pending.volume(), startMs) == null) {
            return; // 剪头那档播不出来：play 内部已经退回整段并记了日志
        }
        LOGGER.info("[SmoothLift/PsdChime] 门 @{} 关门 → 播放 {} 从 {}ms 起"
                        + "（余 {}ms；门还剩约 {}ms，结尾正好落在门关上那一刻）",
                posText(pending.door()), toneLabel(tone), startMs, duration - startMs,
                (int) Math.round(remainMs));
    }

    /**
     * 【1.15】这一扇门这一项**实际要播的素材**（两层回落的结果）：
     *
     * <ol>
     *   <li>先看这一扇门的单独设置（石斧界面 / {@code /pbmmusic} 都存在同一张表里）；</li>
     *   <li>它的值是空或 {@code default}（= 跟上一层）→ 取**维度默认素材**
     *       （{@code /pbmmusic open|close <名字>}）；</li>
     *   <li>维度默认还是 {@code default} → 返回 {@code default}，由调用方按**端别**落到内置
     *       （开门 dooropen.ogg / 关门 mdoorclose.ogg）。</li>
     * </ol>
     *
     * <p>返回 {@link EscalatorSpeedData#PSD_TONE_OFF} 时调用方静默跳过；
     * 返回 {@code default} / {@code default-c} / {@code default-m} 时是内置；
     * 其余 = 音频库文件名。
     */
    private static String psdToneCustomId(Minecraft mc, long key, String which) {
        // 素材镜像的代数缓存：只在有同步变化时才真查（与直梯那边同一套策略）。
        long generation = EscalatorSpeedManager.clientPsdToneGeneration();
        if (generation != cachedToneGeneration) {
            cachedToneGeneration = generation;
        }
        if (mc.level == null) {
            return EscalatorSpeedData.PSD_TONE_DEFAULT;
        }
        // ① 这一扇门的单独设置
        EscalatorSpeedData.PsdToneAudio tone = EscalatorSpeedManager.getClientPsdTone(mc.level, key);
        String value = EscalatorSpeedManager.psdToneField(tone, which);
        // ② 空 / default（含「没设过」）= 跟维度默认
        if (value == null || value.isEmpty() || EscalatorSpeedData.PSD_TONE_DEFAULT.equals(value)) {
            value = EscalatorSpeedManager.getPsdToneAudio(mc.level, which);
        }
        if (EscalatorSpeedData.PSD_TONE_OFF.equals(value)) {
            return STOP_SENTINEL;
        }
        if (value == null || value.isEmpty()) {
            value = EscalatorSpeedData.PSD_TONE_DEFAULT;
        }
        return value;
    }

    /**
     * 【1.50】提示音音量百分比 → 增益系数：{@code 音量 / 100}
     * （100 → 1.0 = 原始音量，1000 → 10.0 = 10×，1 → 0.01）。
     *
     * <p>这里再夹一次（而不是只靠指令参数类型）是为了挡住**存档里被外部改坏的值**：
     * 一个越界的音量乘进增益后会被原版那两道夹取压回去，表现是「填了没用」而不是报错。
     * 上限 10.0 正好等于 {@link EscalatorAudioPlayer#MAX_GAIN}，三处必须同步。
     */
    private static float volumeFactor(int volume) {
        return EscalatorSpeedData.clampLiftHelpVolume(volume) / 100.0f;
    }

    /**
     * 距离增益：{@code round} 格内由 1 **线性**衰减到 0。
     *
     * <p>★★【1.25】这里**故意**与 {@link EscalatorChimePlayer} / {@link LiftChimePlayer}
     * 的平方曲线（{@code (1 - d/r)²}）不同 —— 两边的需求本来就是**相反**的：
     * <ul>
     *   <li>扶梯提示音一头一个，**要让人听出声音从哪头来** ⇒ 平方衰减更「贴块」，
     *       走到下一块阶梯上就明显弱一截（那边注释里写清了这条理由）；</li>
     *   <li>屏蔽门是**一串同声**：站台上每扇门播的是**同一条**提示音，没有任何方向信息要分辨，
     *       要的恰恰是「整条串都听得见」。平方曲线在 16 格范围里 8 格就只剩 25%、
     *       12 格 6%、15 格 0.4%（= -48dB，等于没声）⇒ 一串 46 格长的屏蔽门
     *       **只有近处几扇听得见**。现场日志（LOG3）：用户把音量调到 1000（10×）之后
     *       近的门被放到 8 倍（炸），15.5 格那扇的增益仍然只有 0.00098 —— 他听到的正是
     *       「有的屏蔽门声音大，有的还是没有声音」。</li>
     * </ul>
     * 线性也正是**原版 {@code SoundEngine} 自己的做法**（16 格内 {@code 1 - d/16}），
     * 所以「范围 R 格内线性淡出」对玩家来说是最不意外的那一种语义：范围 = 真的听得见的半径。
     *
     * <p>★【1.23】范围**按类别取**：开关门提示音 / 到站播报 / 进站报站各有一份
     * （{@code /pbmround}、{@code /pbmmidiumround}、{@code /pbmarriveround}）。
     * 所以这里**故意不提供单参重载** —— 让每个调用点都必须写清「我这一声算哪一类」，
     * 而不是顺手用默认那份（本项目栽过「调用点漏改」这种形态，见项目记忆第 6 条）。
     *
     * <p>★【1.26】【09-29】传进来的 {@code distanceXz} / {@code distanceY} 是什么意思：
     * 玩家 ↔ **这一串里离玩家最近的那一扇门**（{@link PsdDoorTracker#nearestInRun}）。
     * ★★【09-30 续 10】这里原先还分「位置音（玩家 ↔ **这一扇门**，开关门提示音走它）」与
     * 「站台广播」两种；前者已**整个删除**，现在两类声音都按串算（见 {@link #play} 的
     * {@code chainRunKey}），本重载与下面的单维重载只差「双维 / 单维」的换算方式。
     * 这一层只负责「一组已经算好的距离 → 一个增益」，不自己决定是哪一种。
     *
     * <p>★【09-29】范围**拆双维**（与 {@code /jsr round AAA BBB} 同构）：水平用
     * {@code Math.hypot(dx, dz)}、垂直用 {@code |dy|}，**任一方向超出即越界（增益 0）**；
     * 范围内两维各自线性衰减、取**较小**的那个作为增益 —— 越贴边的那一维先行淡出，
     * 语义 = 「范围 = 真的听得见的半径（在水平 / 垂直各自成立）」。
     */
    private static float gain(double distanceXz, double distanceY, int roundKind) {
        double roundXz = roundXzFor(roundKind);
        double roundY = roundYFor(roundKind);
        if (!(distanceXz < roundXz) || !(distanceY < roundY)) {
            return 0.0f;
        }
        return (float) Math.max(0.0,
                Math.min(1.0 - distanceXz / roundXz, 1.0 - distanceY / roundY));
    }

    /**
     * 【09-30 续 7】**单维**距离增益 —— 门提示音专用：
     * 距离 = 玩家到**本串最近那一扇门**的**三维**距离
     * （★★【09-30 续 10】原先这里是「到本扇门」，那条口径已作废，见 {@link #play}），
     * 范围内线性淡出（{@code 1 - d/round}），出界（≥ round）硬切 0。
     *
     * <p>为什么提示音用单维（而不是播报那一套双维）：1.20.4 中途给提示音试过双维
     * （水平 16 / 垂直 5 任一越界即 0），会把「走上天桥 / 楼梯几格」也判成越界 ——
     * 用户现场（LOG9）「稍微走远点就没声音」。1.20.1 就是单一半径，这是用户要的那一版。
     * 到站 / 进站播报**不变**，仍用双维口径（那是 {@code /jsr round AAA BBB} 点名要的）。
     *
     * @param distance  玩家到**本串最近那一扇门**的三维距离（格）
     * @param roundKind 只接受 {@link #ROUND_TONE}（播报两类走双维重载）
     */
    private static float gain(double distance, int roundKind) {
        double round = roundXzFor(roundKind);
        if (!(distance < round)) {
            return 0.0f;
        }
        return (float) Math.max(0.0, 1.0 - distance / round);
    }

    /** 【1.23】三类「淡入淡出范围」的身份 —— 见 {@link #gain(double, double, int)}。 */
    private static final int ROUND_TONE = 0;
    private static final int ROUND_MIDIUM = 1;
    private static final int ROUND_ARRIVE = 2;

    /** 【09-29】取这一类当前生效的**水平（x、z 轴）**范围（格）。 */
    private static double roundXzFor(int roundKind) {
        return switch (roundKind) {
            case ROUND_MIDIUM -> cachedMidiumRoundXz;
            case ROUND_ARRIVE -> cachedArriveRoundXz;
            default -> cachedRoundXz;
        };
    }

    /** 【09-29】取这一类当前生效的**垂直（y 轴）**范围（格）。 */
    private static double roundYFor(int roundKind) {
        return switch (roundKind) {
            case ROUND_MIDIUM -> cachedMidiumRoundY;
            case ROUND_ARRIVE -> cachedArriveRoundY;
            default -> cachedRoundY;
        };
    }

    /**
     * 放一下（**一次性**，不循环）。
     *
     * <p>{@code play()} 之前先把位置和音量摆好：{@code SoundEngine.play} 会立刻用实例当时的
     * volume 建源开播，摆好它开播当刻就是正确音量（既不会先满音量炸一下，也没有开头空白）。
     * 这里刻意**不做淡入** —— 提示音是脉冲式的，见 {@link EscalatorChimePlayer} 里那条教训。
     *
     * <p>【1.15】多了一条「剪头」路：{@code startMs > 0} 时**内置档也要走注入**
     * （原版事件是从头开始播的，没法从中间开始），见 {@link #playbackId}。
     * 剪头那档失败时**退回整段**，绝不让它静音 —— 宁可不对齐，也不要没声音。
     *
     * @param startMs 从素材第几毫秒开始播（0 = 整段）
     * @return 真正交给声音引擎的那个实例（{@code null} = 素材缺失 / 解码失败 / 事件没注册）。
     *         返回实例而不是 boolean 只是省一层包装：调用方判 null 就知道成没成，
     *         而实例在手边，将来要停它 / 改它就不必回引擎里再查一遍。
     */
    private static PsdMusicInstance play(Minecraft mc, Tone tone, PsdDoorTracker.DoorView door,
                                         float volume, int startMs) {
        // ★★【09-30 续 10】提示音改成**按整串算距离**（用户点名「修成连在一起的门串播报，
        //   别走一半播报断了」）：距离 = 玩家到**本串里离最近那一扇门**，单维线性淡出。
        //   于是站台上站哪儿、走到门串的哪一段都听得见，不会被 16 格射程切成好几截；
        //   走离这一串（最近那扇也超出射程）时整串**一起**淡出，不会剩下半截还在响。
        //   ★【09-30 续 7】那一版「按本扇门算距离」的位置音口径就此作废（见 play 的 javadoc）。
        //   ★【10-01】再补一格 {@code door.platformId()}：**同一个站台**里离玩家最近的那扇门
        //   才算这把尺子（{@link PsdMusicInstance#chainPlatformId} 里有 LOG013 的数字）。
        //   不收窄的话，同一车站别的站台（LOG013 里只隔 11 格）的门会按玩家脚边那扇门的
        //   音量响满 100% —— 用户听到「早几秒一个 + 准点一个」的多出来的提示音。
        return play(mc, tone, door, volume, startMs, false, ROUND_TONE, door.runKey(), false,
                door.platformId());
    }

    /**
     * 【1.22】完整版：多一个「要不要吃列车内衰减」开关（【1.23】再多一个「算哪一类范围」）。
     *
     * <p>★【1.25】**传进来的 {@code volume} 一律只是「音量系数」（不含距离增益）** ——
     * 距离增益由实例每 tick 现算（{@link PsdMusicInstance#refreshVolume}），
     * 于是「离得远就轻、走近了就变响、走出范围就听不见」对三类声音都成立。
     * 原来这里还有一个 {@code factorOnly} 形参，{@code false} = 把增益一次算死、播出去就不再变 ——
     * 开关门提示音走的正是那一条 ⇒ 门开那一刻站在 15 格外，这条声音就**永远**只有 0.4% 音量。
     * 现在全仓没有任何调用点会传 {@code false}，所以把这个形参**整个删掉**：
     * 留着它就等于留一条「看着还在、其实没人走」的死路径（本项目栽过这个形态）。
     *
     * @param trainAttenuated true = 这条声音在玩家坐进 / 离开 MTR 列车时按
     *                        {@link #TRAIN_VOLUME_FACTOR} 衰减（★【1.28】进出列车
     *                        **立即**在 1.0 ↔ {@link #TRAIN_VOLUME_FACTOR} 之间切换 ——
     *                        用户点名「不需要缓冲」，旧版 1 秒线性过渡已删除）
     *                        （【1.23】原来是下一 tick 直接到位）。
     * @param roundKind       【1.23】这条声音按哪一类的「淡入淡出范围」算距离增益：
     *                        {@link #ROUND_TONE} / {@link #ROUND_MIDIUM} / {@link #ROUND_ARRIVE}。
     *                        ★ 与 {@code trainAttenuated} 是**两个独立**的开关 ——
     *                        它们眼下恰好同进同出（两类报站音都带列车衰减、提示音都不带），
     *                        但判据完全不同，所以不合并成一个（本项目栽过「一个哨兵表达两件事」）。
     * @param chainRunKey     【1.26】距离按**哪一串门**算：取玩家到**这一串里最近那一扇门**的距离
     *                        （{@link PsdDoorTracker#nearestInRun}）。
     *                        <p>★★【09-30 续 10】这里以前还有一支「哨兵值 = 按 {@code door}
     *                        自己那一格算（位置音）」的口径（开关门提示音走它），现已连同那个哨兵
     *                        现已**整个删除**：屏蔽门提示音与站台广播一样是「一串同声」，
     *                        按「本扇门」算就会把一条连在一起的门串按 16 格切成好几截
     *                        （车头那几扇响、中间渐弱、车尾整段静默），这正是用户报的那个症状。
     *                        现在两类声音都按**串**算距离，区别只剩下面那一格：单维还是双维。
     * @param chainDoubleDim  【09-30 续 10】串距离按哪一套射程口径换算增益：
     *                        {@code true} = 双维（水平 {@code roundXz} / 垂直 {@code roundY}，
     *                        任一越界即 0）—— 到站播报 / 进站报站走这条（{@code /jsr round AAA BBB}
     *                        点名要的）；{@code false} = 单维（只用 {@code roundXz} 对**三维**距离
     *                        线性淡出）—— 开关门提示音走这条（照抄 1.20.1：走上天桥 / 楼梯几格
     *                        不该被判成越界）。
     *                        ★ 不拿 {@code roundKind} 推出这件事：范围类别与「哪套口径」是两个判据，
     *                        合并 ⇔ 以后新加一类范围会**悄悄**换掉口径。
     */
    private static PsdMusicInstance play(Minecraft mc, Tone tone, PsdDoorTracker.DoorView door,
                                         float volume, int startMs,
                                         boolean trainAttenuated, int roundKind,
                                         long chainRunKey, boolean chainDoubleDim) {
        // ★【10-01】站台广播（到站 / 进站）走这条：距离基准 = **整个车站**里离玩家最近的门
        //   （它本来就是站台广播）。铃声那条走下面多一个形参的重载（收窄到**同一个站台**）。
        return play(mc, tone, door, volume, startMs, trainAttenuated, roundKind,
                chainRunKey, chainDoubleDim, MtrDwellAccess.PLATFORM_ID_NONE);
    }

    /**
     * 【10-01】{@code chainPlatformId} 版：距离基准收窄到**同一个 MTR 站台**。
     *
     * <p>★ 只有**铃声**（开关门提示音 / 关门人声）该走这条 —— 理由与数字见
     * {@link PsdMusicInstance#chainPlatformId}：{@code runKey} 是**车站级**，一个车站可以有好几层、
     * 好几个站台（LOG013：世纪广场 6~7 个站台跨 3 层，其中两条门线只隔 11 格），
     * 不收窄就会让别站台的门按「玩家脚边那扇门」的音量响满 100%。
     *
     * @param chainPlatformId 这一扇门认得的 MTR 站台 id（{@link PsdDoorTracker.DoorView#platformId}）；
     *                        认不到时传 {@link MtrDwellAccess#PLATFORM_ID_NONE} ⇒ 回落整串（车站）口径
     * @see PsdMusicInstance#chainPlatformId
     */
    private static PsdMusicInstance play(Minecraft mc, Tone tone, PsdDoorTracker.DoorView door,
                                         float volume, int startMs,
                                         boolean trainAttenuated, int roundKind,
                                         long chainRunKey, boolean chainDoubleDim,
                                         long chainPlatformId) {
        Vec3 pos = new Vec3(door.x(), door.y(), door.z());
        String playbackId = startMs > 0 ? playbackId(tone, startMs) : null;
        if (playbackId != null) {
            byte[] bytes = tone.customId() == null
                    ? EscalatorAudioPlayer.bundledBytes(tone.builtinKey())
                    : EscalatorSpeedManager.getAudioBytes(mc.level, tone.customId());
            if (bytes == null || !EscalatorAudioPlayer.injectPlayback(mc, playbackId, bytes)) {
                playbackId = null; // 剪头那档播不出来 → 退回整段从头播
            }
        }
        if (playbackId == null) {
            if (tone.customId() == null) {
                if (mc.getSoundManager().getSoundEvent(tone.event()) == null) {
                    if (warnedMissingEvents.add(tone.event())) {
                        LOGGER.warn("[SmoothLift/PsdChime] 声音事件 {} 没注册（模组的 sounds.json 没被加载？），"
                                + "这个屏蔽门提示音不会出声", tone.event());
                    }
                    return null;
                }
            } else if (!EscalatorAudioPlayer.injectAudio(mc, tone.customId())) {
                // 没同步到 / 解码失败 / 已被删除 → 静默跳过（日志已在 injectAudio 内部处理过）
                return null;
            }
        }
        // 第二个参数 = 「要注入的那一段」的 ID：null 走原版事件（内置整段），非 null 走注入。
        // 剪头播放时它带着偏移后缀，resolve 会用同一个串算出 {@code Sound.getPath()}，缓存才命中。
        // 【1.22】实例多持一份「声源位置 + 音量系数 + 列车衰减开关 + 范围类别 + 剩余 tick数」：
        //   前者让它能每 tick 重算距离增益，后者让它能自己从
        //  引擎的 tickingSounds 里退场（一次性声音没有别的退场信号）。
        // 【1.26】再多一份「按哪一串算距离」（站台广播 / 开关门提示音都按**串**算）。
        // 【09-30 续 10】再多一份「串距离按单维还是双维换算」（提示音单维、播报双维）。
        long playableMs = Math.max(0L, tone.durationMs() - Math.max(0, startMs));
        PsdMusicInstance inst = new PsdMusicInstance(tone.event(),
                playbackId != null ? playbackId : tone.customId(),
                pos, volume, trainAttenuated, roundKind, chainRunKey, chainDoubleDim,
                chainPlatformId, (int) ((playableMs + 49L) / 50L) + 20);
        inst.refreshVolume(mc);
        mc.getSoundManager().play(inst);
        raiseMaxGain(mc, inst);
        return inst;
    }

    /**
     * 【1.15】剪头播放用的**播放 ID**：内置档用内置 key、自定义档用文件名，后缀携带偏移毫秒数。
     *
     * <p>注入用的缓存 key 与播放实例 {@code resolve} 里的 {@code Sound.getPath()} 都由这一个串
     * 算出来（{@link EscalatorAudioPlayer#soundLocation}），所以两边必须**同一个串** —— 见
     * {@link EscalatorAudioPlayer#offsetPlaybackId}。
     */
    private static String playbackId(Tone tone, int startMs) {
        return EscalatorAudioPlayer.offsetPlaybackId(
                tone.customId() != null ? tone.customId() : tone.builtinKey(), startMs);
    }

    /**
     * 把该 OpenAL 源的 {@code AL_MAX_GAIN} 抬到 {@link EscalatorAudioPlayer#MAX_GAIN}。
     *
     * <p>音量上限要**成对**放开，只做一半就还是「调到 100 以上完全没变化」：
     * ① 实例实现 {@link GainManagedSound} → Mixin 不再把 {@code calculateVolume} 夹到 1.0；
     * ② 这里把源自己的上限也抬起来 —— 否则 OpenAL 会把**有效增益**压回 1.0×（它默认就是 1.0）。
     * 该属性是按源保留的（引擎每 tick 只改 AL_GAIN / pitch / position，不碰它），开播时设一次即可。
     */
    private static void raiseMaxGain(Minecraft mc, PsdMusicInstance inst) {
        SoundEngine engine = mc.getSoundManager().soundEngine;
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
        if (handle != null) {
            handle.execute(ch -> AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN));
        }
    }

    /**
     * 【1.15 · 第六轮】掐掉一条**正在响**的提示音。
     *
     * <p>唯一的调用点是「提前播出去的整段**会拖过门全关**」那一支（见 {@link #resumeClose}）——
     * 那里必须让它立刻停下，否则门都关上了、嘀嘀还在响，正好违反用户那条硬要求。
     *
     * <p>素材自己已经播完时原版引擎早把它从表里摘掉了，{@code stop} 拿着一个失效引用
     * 什么都不做（不是异常、也不会误伤同期在响的别的音）。
     */
    private static void stop(Minecraft mc, PsdMusicInstance inst) {
        if (inst != null) {
            mc.getSoundManager().stop(inst);
        }
    }

    // ------------------------------------------------------------------
    // 【1.22】距离淡入淡出 + 列车内衰减
    // ------------------------------------------------------------------

    /**
     * 【1.22】玩家坐在 MTR 列车里时，车厢报站类提示音的音量倍率：
     * 减少 80% ⇒ 剩 20%（用户点名：「pbmmidium 和 pbmarrive 的音量减少 80%」）。
     */
    public static final float TRAIN_VOLUME_FACTOR = 0.2f;

    // ★【1.28】旧版有个 TRAIN_RAMP_TICKS=20（进出列车 1 秒线性过渡，1.23 加的）——
    //   已被点名删除：「玩家在列车内减少 80% 声音，离开列车立刻恢复到 100%，不需要缓冲」。
    //   updateTrainRamp 现在直接在 1.0 ↔ TRAIN_VOLUME_FACTOR 之间**立即**切换。

    /** 【1.22】MTR 的 {@code VehicleRidingMovement.ridingVehicleId}（private static long）。 */
    private static java.lang.reflect.Field ridingField;
    private static boolean ridingFieldResolved;

    /** 【1.23→1.28】当前生效的列车内倍率（1.0 = 不在车里，{@link #TRAIN_VOLUME_FACTOR} = 已在车里）。 */
    private static float trainRamp = 1.0f;

    /**
     * 【1.22】玩家是不是坐在 **MTR 列车**里。
     *
     * <p>MTR **不是**本模块的编译依赖（build.gradle 里只有 loader + api），
     * 所以只能反射。反射失败（没装 MTR / MTR 改了字段名）时**只是衰减不生效**，
     * 不会影响其它任何功能（这条路径不能报错）。
     *
     * <p>读到 0 ⇒ 没坐车：静态块**不**初始化该字段，而 long 默认就是 0。
     * ★【09-30】从 private 改成 public —— 讲述人那套「上车即停」
     * （{@link TrainAnnounceNarrator#tickRangeGuard} / {@link TrainAnnounceSubtitle#tick}）
     * 与本类的列车内音量衰减**共用同一个判据**，别再抄一份反射。
     */
    public static boolean ridingTrain() {
        java.lang.reflect.Field f = ridingField;
        if (!ridingFieldResolved) {
            ridingFieldResolved = true;
            try {
                Class<?> c = Class.forName("org.mtr.mod.client.VehicleRidingMovement");
                f = c.getDeclaredField("ridingVehicleId");
                f.setAccessible(true);
                ridingField = f;
            } catch (Throwable t) {
                ridingField = null;
                LOGGER.info("[SmoothLift/PsdChime] 没找到 MTR 的 VehicleRidingMovement.ridingVehicleId"
                        + " —— 列车内音量衰减不生效，其它功能不受影响");
                return false;
            }
        }
        if (f == null) {
            return false;
        }
        try {
            return f.getLong(null) != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 【1.28】每客户端 tick 刷新「列车内倍率」—— **立即**在 1.0 ↔ {@link #TRAIN_VOLUME_FACTOR}
     * 之间切换，不再有 1 秒过渡（用户点名：「在列车内减少 80% 声音，离开列车立刻恢复到 100%，
     * 不需要缓冲」；旧版 {@code TRAIN_RAMP_TICKS}=20 的线性斜坡已按点名删除）。
     *
     * <p>仍然要每 tick 都跑一遍（不只是实例自己 tick 时）——
     * {@link #onClientTick} 在声音没播时也推进，于是下一次起播拿到的倍率就是当前值；
     * {@link PsdMusicInstance#refreshVolume} 也调它，正在响的那条声音跟着即时变。
     * 直接每 tick 投影当前状态 ⇒ 天然幂等（同一个 tick 被调几次结果都一样），
     * 也天然免疫「读档时人在车里」这类初始状态 —— 第一次调就落在当前车厢状态上。
     *
     * <p>反射拿不到 MTR（没装 / 改了字段名）时 {@link #ridingTrain} 恒为 false
     * ⇒ 倍率恒为 1.0，等于这条特性不存在，其余功能不受影响。
     */
    static void updateTrainRamp(Minecraft mc) {
        if (mc == null || mc.level == null) {
            return;
        }
        trainRamp = ridingTrain() ? TRAIN_VOLUME_FACTOR : 1.0f;
    }

    /** 【1.28】当前生效的列车内倍率（1.0 = 不在车里，{@link #TRAIN_VOLUME_FACTOR} = 已在车里）。 */
    static float trainRamp() {
        return trainRamp;
    }

    /** 日志里用的门的短标识（锚点坐标，比一长串 asLong 好认）。 */
    private static String posText(PsdDoorTracker.DoorView door) {
        return "[" + (int) Math.floor(door.x()) + "," + (int) Math.floor(door.y()) + ","
                + (int) Math.floor(door.z()) + "]";
    }

    /**
     * 清空「门值记录」、挂起中的关门请求、以及**还没到点的提前量**。
     * 注意**不清** {@link #lastEnabled}（那是「日志只打一次」的状态）。
     *
     * <p>调用点都是「这一套不该再跑」的场合（功能被关掉 / 没门可看 / 断线），
     * 所以挂起中的请求直接丢掉是对的；正常路径上挂起只会存在一 tick，由 {@link #resumeClose} 消费。
     * 【1.15】正在播的那条提示音**不在这里停** —— 它是一次性实例（{@code looping = false}），
     * 原版引擎自己回收通道；在这里停掉反而会把用户想听的那段话掐断。
     *
     * <p>★ 四张「学习 / 预测」表各有各的存活期，别一并清掉：
     * <ul>
     *   <li>{@link #learnedCycleTicks} / {@link #cycleOpenTick} / {@link #globalCycleTicks} /
     *       {@link #globalTravelTicks} —— **留着**：玩家走远、区块卸载、最近的门换成另一扇，
     *       都不该把学到的停站时长丢掉（否则每次走开都要重学一轮，而「跨门共用」那一份
     *       正是为了让**第一次见到的门**也能排出提前量）。</li>
     *   <li>{@link #plannedCloseStart} —— **清掉**：这条计划挂在「某一轮停站」上，
     *       而这里被调用恰恰意味着「这门/这功能暂时看不到了」，计划已经失去参照，
     *       留着只会在回来时补播一声不知道属于哪一轮的提示音。
     *       清掉后关门那一刻会自然退回到剪头（结尾落在门上这条硬要求照样成立）。</li>
     *   <li>{@link #plannedCloseFired} / {@link #wholePlaying} —— **留着**：它们是「别重复播」
     *       与「必要时掐掉」的句柄，而这里并不停正在响的那条素材；清掉反而会让关门那一刻
     *       再叠一声，或在需要掐掉时找不到句柄。</li>
     *   <li>【1.16】{@link #forcedVoice} —— **清掉，而且要把正在响的那条停掉**。
     *       它与 {@code wholePlaying} 不同：那条「整段」是**这一扇门本来就该有的**声音，
     *       而这条兜底人声是我们**主动插进去**的，reset() 的三个来路（没进世界 / 功能被关 /
     *       门全看不见）都意味着它已经失去归属。</li>
     * </ul>
     * 真正换世界时由 {@link #onDisconnect} 把四张表一起清掉（那时位置与时刻表都不再可比）。
     */
    private static void reset(Minecraft mc) {
        lastDoor.clear();
        pendingClose.clear();
        plannedCloseStart.clear();
        // 【1.16】兜底人声：**计划**一定清（它挂在某一扇门的某一轮上，参照已经没了）；
        //   正在响的那条也**停掉** —— 与「关门嘀嘀不在这里停」不同，这条声音是我们主动插进去
        //   的一段语音，而 reset() 的三个来路（没进世界 / 功能被关 / 门全看不见）都意味着
        //   它已经失去归属；留着只会在下一次开门之前变成一段不知道从哪来的声音。
        for (ForcedVoice plan : forcedVoice.values()) {
            stopForcedInstance(mc, plan, "这一套提示音暂时不跑了（reset）");
        }
        forcedVoice.clear();
        // 【1.17】到站播报 **不在这里清、也不在这里停** —— 这是它与兜底人声唯一的分歧，
        //   也是用户点名要的那条语义：「即使列车出站也要继续播放，直到播完」。
        //   ★ reset() 的第三个来路正是「门全看不见」（车开走、区块卸载）—— 恰好就是
        //   「列车出站」那一刻。在这里顺手清掉它，功能立刻就废了。
        //   它自己由 tickArrivalAnnounce 在「起播 + 素材时长」那一 tick 摘掉（只回收表项）。
        //   ★ 换世界（onDisconnect）仍然清 —— 那时位置与声音都随旧世界一起结束了。
        // 【1.21】进站报站走**完全一样**的规矩：不在这里清、不在这里停。
        //   它的三个来路里同样包含「门全看不见」（车进站那一刻门会短暂消失），
        //   在这里顺手清就等于「车一进站就把播报掐了」—— 与用户点名要的语义正好相反。
    }

    /** 断开连接：清掉全部缓存、挂起请求与**学习到的周期**（换世界后位置与时刻表都不再可比）。 */
    public static void onDisconnect() {
        reset(null);
        learnedCycleTicks.clear();
        cycleOpenTick.clear();
        plannedCloseFired.clear();
        globalCycleTicks = -1L;
        globalTravelTicks = -1L;
        closeStartTick.clear();
        wholePlaying.clear();
        // 【1.17】到站播报：换世界时**才**清（声音随旧世界一起没了，计划里的坐标也不再可比）。
        arrivalVoice.clear();
        // 【09-30 续 3】站台广播（讲述人）的计划同理（换世界后串锚点、站台 id 全都不再可比）。
        midiumNarratorVoice.clear();
        // 【1.21】进站报站同理（换世界后串锚点、站台 id、时刻表全都不再可比）。
        arriveVoice.clear();
        arriveLastPoll.clear();
        arriveFailNextLog.clear();
        PsdDoorTracker.clear();
        // 【10-03 五改】「显示名 → 别名」表也跟着作废：换了世界，同名的车站可能是另一座
        //   （沿用旧表只会让屏蔽门那一句「往X」印出上一个世界的别名）。
        PlatformNameMask.clear();
        cachedGeneration = -1L;
        cachedEnabled = true;
        cachedVolume = EscalatorSpeedData.DEFAULT_PSD_HELP_VOLUME;
        // 【09-29】范围拆双维：复位时每类各回两份默认
        cachedRoundXz = EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_XZ;
        cachedRoundY = EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND_Y;
        cachedMidiumRoundXz = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_XZ;
        cachedMidiumRoundY = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND_Y;
        cachedArriveRoundXz = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_XZ;
        cachedArriveRoundY = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND_Y;
        cachedCloseWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS;
        cachedMidiumAudio = EscalatorSpeedData.PSD_MIDIUM_OFF;
        cachedMidiumWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS;
        cachedToneGeneration = -1L;
        lastEnabled = true;
        // 【1.28】列车内倍率：换世界后复位 —— 下一 tick 由 updateTrainRamp 直接
        //   投影到当前车厢状态（进存档时人在车里也一次到位，不会先响一声大的）。
        trainRamp = 1.0f;
    }

    /** 屏蔽门提示音总开关（带代次缓存：只在同步包到达 / 断线时才真的查一次）。 */
    private static boolean psdHelpEnabled(Minecraft mc) {
        refreshSettings(mc);
        return cachedEnabled;
    }

    private static void refreshSettings(Minecraft mc) {
        long generation = EscalatorSpeedManager.clientPsdChimeGeneration();
        if (generation == cachedGeneration) {
            return;
        }
        cachedGeneration = generation;
        cachedEnabled = EscalatorSpeedManager.isPsdHelpEnabled(mc.level);
        cachedVolume = EscalatorSpeedManager.getPsdHelpVolume(mc.level);
        // 【09-29】范围拆双维：每类各取「水平（x、z 轴）」与「垂直（y 轴）」两份
        cachedRoundXz = EscalatorSpeedManager.getPsdHelpRoundXz(mc.level);
        cachedRoundY = EscalatorSpeedManager.getPsdHelpRoundY(mc.level);
        // 【1.23】到站播报 / 进站报站各自的淡入淡出范围（三条 round 指令各写一份）
        cachedMidiumRoundXz = EscalatorSpeedManager.getPsdMidiumRoundXz(mc.level);
        cachedMidiumRoundY = EscalatorSpeedManager.getPsdMidiumRoundY(mc.level);
        cachedArriveRoundXz = EscalatorSpeedManager.getPsdArriveRoundXz(mc.level);
        cachedArriveRoundY = EscalatorSpeedManager.getPsdArriveRoundY(mc.level);
        // 【1.16】关门提示音的强制等待时长（秒）：只有「停站塞不下整条素材」那一支会读它
        cachedCloseWaitSeconds = EscalatorSpeedManager.getPsdCloseWaitSeconds(mc.level);
        // 【1.17】到站播报：素材 + 等待秒数（每次开门都要用，不能现查服务端设置）
        cachedMidiumAudio = EscalatorSpeedManager.getPsdMidiumAudio(mc.level);
        cachedMidiumWaitSeconds = EscalatorSpeedManager.getPsdMidiumWaitSeconds(mc.level);
    }

    /**
     * 提示音（内置档由 {@code event} 决定，自定义档走 {@code customId}）的播放实例：
     * **一次性**（{@code looping = false}），播完由原版 {@code SoundEngine} 自己回收通道，
     * 不需要 {@code TickableSoundInstance}。
     *
     * <p>{@code attenuation = NONE} 表示「关掉 OpenAL 的距离增益，只保留声像」——
     * 音量由 {@link #gain} 自己按「到门的距离」算（再乘 {@link #volumeFactor}），
     * 这样「多远开始听不见」完全由 {@code /pbmround} 说了算，
     * 不受原版 16 格与声音来源滑块的组合影响。
     */
    private static final class PsdMusicInstance extends AbstractSoundInstance
            implements TickableSoundInstance, GainManagedSound {

        /**
         * 要**注入**播放的那一段的 ID（{@code null} = 走原版事件，即内置整段）。
         *
         * <p>【1.15】值有两种：玩家导入素材的文件名，或「内置 key + 偏移后缀」的播放 ID
         * （{@link EscalatorAudioPlayer#offsetPlaybackId}）。两者对这里都没区别 ——
         * 反正 {@link #resolve} 只拿它算 {@code Sound.getPath()}，必须与注入时的缓存 key 同源。
         */
        private final String injectedId;

        /**
         * 【1.22】音量系数（**不含**距离增益）。
         *
         * <p>★【1.25】三类声音现在一律这样传：距离增益不由调用方算死，
         * 而是每 tick 用 {@link #refreshVolume} 现算（理由见 {@code play} 的注释）。
         */
        private final float baseVolume;

        /** 【1.22】true = 玩家在 MTR 列车里时打 {@link #TRAIN_VOLUME_FACTOR}。 */
        private final boolean trainAttenuated;

        /**
         * 【1.23】这条声音的距离增益按**哪一类**范围算（{@link #ROUND_TONE} /
         * {@link #ROUND_MIDIUM} / {@link #ROUND_ARRIVE}）。
         *
         * <p>★ 存的是**类别**而不是当时的数值：范围是随时可改的（{@code /pbmarriveround 8}
         * 下一个 tick 就该生效），每 tick 现取才能跟着变。
         */
        private final int roundKind;

        /**
         * 【1.26】距离按**哪一串门**算：取玩家到**这一串里最近那一扇门**的距离
         * （{@link PsdDoorTracker#nearestInRun}）。
         *
         * <p>★ 为什么必须这样算：一串门在**配置**上就是同一个身份（{@code runKey}），
         * 一整串 12 扇可以长到 55 格、长站台更到 175 格，而默认范围只有 16 格 ——
         * 按「本扇门」算距离的话，站在站台任何位置都只有约 6 扇在范围内，其余增益为 0
         * （现场 LOG4 的「后 4 个不响」、LOG16 的「车头响、中间渐弱、车尾整段静默」）。
         * 按「本串最近的门」算 ⇒ 只要玩家在这串门的任意一扇旁边，整串都成立。
         *
         * <p>★★【09-30 续 10】开关门提示音**也**并进这条口径（原先它按本扇门算、由哨兵
         * {@code NO_BROADCAST_RUN} 标记）；那个哨兵已整个删除，本字段现在永远是**真的 runKey**。
         */
        private final long chainRunKey;

        /**
         * 【10-01】距离基准的**站台收窄**：非 {@link MtrDwellAccess#PLATFORM_ID_NONE} 时，
         * 「最近的一扇门」只在**同一个 MTR 站台**里取（{@link PsdDoorTracker#nearestOnPlatform}）；
         * {@link MtrDwellAccess#PLATFORM_ID_NONE} = 老口径（整串 = 车站，{@link #chainRunKey}）。
         *
         * <p>★★ 为什么必须收窄（现场 LOG013，数字可复核）：
         * {@code runKey} 在【09-30 续 9】升到了**车站级**，而【09-30 续 10】把铃声的音量基准定成
         * 「**本串**里离玩家最近那一扇」⇒ 「本串」实际是**整个车站**。于是同一车站另一个站台
         * （另一层 / 轨道另一侧，只隔 11 格）的门一响，它的增益也按**玩家脚步边那扇门**算 ⇒
         * 100% 音量在耳边炸开：用户听到「早几秒一个 + 准点一个」的**多出来的提示音**（他说「又犯了一次」）。
         * LOG013 里 14:56:38 玩家站在 z=32 那条线旁（1.6 格），z=43 那条线的关门声却标着
         * 「距玩家 11.4 / 12.1 / 14.0 格」——11 格外的门也以满音量响了。
         *
         * <p>★ 与 {@link #chainRunKey} 是**两把尺子**，不许合并成一个：
         * 播报（到站 / 进站）是站台广播 ⇒ 整个车站只该响一条 ⇒ 保持车站级；
         * 铃声（开关门嘀嘀 / 关门人声）是「沿站台一排门同时响」的位置短音 ⇒ 必须是一个站台。
         * 收窄后自己的站台（含被实体缺口切开的几段）仍整排一起响、一起淡出（09-30 续 10 点名的口径）。
         */
        private final long chainPlatformId;

        /**
         * 【09-30 续 10】串距离按**哪一套射程口径**换算增益：
         * {@code true} = 双维（水平 {@code roundXz} / 垂直 {@code roundY}，任一越界即 0）
         * —— 到站播报 / 进站报站；{@code false} = 单维（只用 {@code roundXz} 对**三维**距离
         * 线性淡出）—— 开关门提示音（照抄 1.20.1：走上天桥 / 楼梯几格不该被判成越界）。
         *
         * <p>★ 与 {@link #roundKind} 是两个独立判据：范围**类别**（提示音 / 到站 / 进站）里不含
         * 「用哪套口径」这个信息，拿它推会在以后新加一类范围时**悄悄**换掉口径。
         */
        private final boolean chainDoubleDim;

        /**
         * 【1.22】还剩几个客户端 tick 就自己退场。
         *
         * <p>一次性声音**没有别的退场信号**：{@code isStopped()} 一直返回 false 就会让它永远留在
         * 引擎的 {@code tickingSounds} 里（每次播报漏一条，一场游戏下来就是千条）；
         * 这里按素材实长预算（+20 tick 宽容），到点自己停。
         */
        private int remainingTicks;

        /**
         * 【1.30】报站淡入淡出的**逼近比例**（每 tick = 50ms）：{@code 0.18} ⇒ 时间常数 ≈ 250ms
         * （从 0 到 90% 约 0.6 秒，走远 / 跨射程 / 进出列车都不再是一拍跳变）。
         *
         * <p>★ 这是「按差距**比例**逼近」的数字，不是「每 tick 加固定值」—— 固定值限速会跟
         * 音量大小强相关（音量 1000 的用户等到天荒地老，1.23 踩过那条）；比例法永不受
         * {@code baseVolume} 大小影响，任何音量下听感一致。
         */
        private static final float VOLUME_SMOOTH_PER_TICK = 0.18f;

        /** 【1.30】当前平滑音量（只对报站类实例使用；初值 0 = 新实例从静音淡入）。 */
        private float smoothedVolume;

        /** {@code injectedId != null} = 玩的是注入进引擎缓存的那一段（不读资源包）。 */
        PsdMusicInstance(ResourceLocation event, String injectedId, Vec3 pos, float volume,
                         boolean trainAttenuated, int roundKind, long chainRunKey,
                         boolean chainDoubleDim, long chainPlatformId, int remainingTicks) {
            super(event, SoundSource.BLOCKS);
            this.injectedId = injectedId;
            this.baseVolume = volume;
            this.trainAttenuated = trainAttenuated;
            this.roundKind = roundKind;
            this.chainRunKey = chainRunKey;
            this.chainDoubleDim = chainDoubleDim;
            this.chainPlatformId = chainPlatformId;
            this.remainingTicks = Math.max(1, remainingTicks);
            this.looping = false;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = false;
            this.volume = volume;
            this.pitch = 1.0f;
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
        }

        /**
         * 【1.22】每客户端 tick：递减剩余时长 + 现算音量。
         *
         * <p>音量**不在播放前一次性算完**（旧行为）—— 原版引擎每 tick
         * 会用 {@code calculateVolume(instance)} 重新取一次音量，所以这里改它就立刻生效
         * （扶梯那套淡入就是同一个道理）。
         *
         * <p>★【1.25】**三类声音现在都走这一条**（原来开关门提示音有一个
         * {@code factorOnly=false} 的分支，播出去就不再变 —— 那条路已整个删除）。
         */
        @Override
        public void tick() {
            if (remainingTicks > 0) {
                remainingTicks--;
            }
            refreshVolume(Minecraft.getInstance());
        }

        /**
         * 现算一次音量：距离增益 × 音量系数（× 列车内倍率）。
         *
         * <p>★【1.25】只剩这一条路 —— 每次调用都真算，不再有「不算、直接用传进来的音量」的分支。
         *
         * <p>★【1.26】距离口径：玩家到**本串最近那一扇门**（{@link #chainRunKey}）。
         * 后者让「一串 12 扇、55 格长的屏蔽门」站在哪一扇旁边都听得见，
         * 而不是只有本扇门 16 格内的那几扇。
         *
         * <p>★★【09-30 续 10】开关门提示音也并进这条口径，两类声音只差 `chainDoubleDim`
         * 那一格（单维 / 双维）。原先那支「按 {@code pos}（本扇门坐标）算」的口径已删除。
         */
        void refreshVolume(Minecraft mc) {
            Vec3 p = mc.player == null ? null : mc.player.position();
            // ★★【09-30 续 10】两类声音**都**按「本串里离玩家最近那一扇门」算距离
            //   （用户点名「修成连在一起的门串播报，别走一半播报断了」）：提示音与站台广播
            //   共用同一把尺子，差别只剩下面那一格 —— 单维（提示音）还是双维（播报）。
            //   ★ 旧版提示音按**本扇门**算（哨兵 NO_BROADCAST_RUN）⇒ 一条 55~175 格长的门串
            //   只有玩家 ±16 格内那几扇响、其余整段静默，用户看到的就是「切成两半」。
            float v;
            if (p == null) {
                // 界面 / 未进入世界：当贴脸处理（与旧口径一致 —— 那时也走「距离 0 ⇒ 增益 1」）。
                v = baseVolume;
            } else {
                // ★★【10-01】「本串最近的门」这把尺子按**声音类别**分两种（见 #chainPlatformId）：
                //   铃声（开关门嘀嘀 / 关门人声）收窄到**同一个 MTR 站台**（用户点名的
                //   「沿站台一排门同时响」= **一个站台**，而 runKey 现在是**整个车站**，
                //   世纪广场 一个车站有 6~7 个站台跨 3 层 ⇒ 不收窄就等于「别的站台也按你脚边
                //   那扇门的音量响」，就是他报的「多出来的提示音」）；
                //   播报（到站 / 进站）保持车站级（它本来就是站台广播，整个车站只该响一条）。
                PsdDoorTracker.DoorView nearest = MtrDwellAccess.isPlatformKnown(chainPlatformId)
                        ? PsdDoorTracker.nearestOnPlatform(chainPlatformId, p)
                        : PsdDoorTracker.nearestInRun(chainRunKey, p);
                if (nearest == null) {
                    // 这一串 / 这个站台此刻不在快照里（走远了 / 区块卸载）⇒ 整串**一起**归 0。
                    //   ★ 与旧版的关键差别：这里不会出现「近的半截还在响、远的半截已静默」。
                    v = 0.0f;
                } else if (chainDoubleDim) {
                    // 站台广播（到站 / 进站）：双维口径。
                    //   ★【09-29】水平 = hypot(dx,dz)、垂直 = |dy|，任一方向超出即越界（见 gain）。
                    double dxz = Math.hypot(p.x() - nearest.x(), p.z() - nearest.z());
                    double dy = Math.abs(p.y() - nearest.y());
                    v = gain(dxz, dy, roundKind) * baseVolume;
                } else {
                    // 开关门提示音：单维口径 —— 只用 roundXz 对**三维**距离线性淡出。
                    double d = p.distanceTo(new Vec3(nearest.x(), nearest.y(), nearest.z()));
                    v = gain(d, roundKind) * baseVolume;
                }
            }
            // ★【1.30】报站（到站 / 进站，长音）的淡入淡出平滑：target 每 tick 现算照旧，
            //   但**距离部分落到引擎的音量不直接跳**，而是向 target 逼近（每 tick 走 18% 差距）。
            //   于是：起播 = 从静音淡入、走远 / 跨射程 = 按时间常数淡出，不再有 50ms 硬切。
            //   ★ 只对报站类平滑；铃声是 4 秒内的位置短音，保持立即跟随（清脆，不糊）。
            //   初值 0 ⇒ 新实例从 0 爬向 target，第一次 refreshVolume 就成立（不用哨兵）。
            if (roundKind == ROUND_MIDIUM || roundKind == ROUND_ARRIVE) {
                v = smoothedVolume + (v - smoothedVolume) * VOLUME_SMOOTH_PER_TICK;
                smoothedVolume = v;
            }
            if (trainAttenuated) {
                // ★★【1.31】列车倍率在**平滑之后**乘 —— 进出列车那一档保持【1.28】点名的
                //   「立即」：玩家在列车内 -80%（剩 0.2）、离开列车**立刻**恢复 100%、
                //   **不要淡入淡出**（用户原话）。平滑只拦「距离变化」这一路（走远/起播），
                //   不拦列车挡 —— 否则 1.30 的 250ms 时间常数会把「离开列车立刻恢复」拖成
                //   约半秒的爬升，等于功能不见了。
                updateTrainRamp(mc);
                v *= trainRamp();
            }
            this.volume = v;
        }

        @Override
        public boolean isStopped() {
            return remainingTicks <= 0;
        }

        @Override
        public boolean canPlaySound() {
            return true;
        }

        /** 注入素材时把事件解析成「引擎缓存里那段注入的音频」（仿扶梯 ChimeInstance）。 */
        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            if (injectedId == null) {
                return super.resolve(soundManager);
            }
            ResourceLocation name = EscalatorAudioPlayer.soundLocation(injectedId);
            Sound s = new Sound(name.toString(), 1.0F, 1.0F,
                    1, Sound.Type.FILE, false, false, 0);
            this.sound = s;
            WeighedSoundEvents events = new WeighedSoundEvents(name, null);
            events.addSound(s);
            return events;
        }
    }
}
