package smooth.lift.client;

import com.mojang.blaze3d.audio.OggAudioStream;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import smooth.lift.compat.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 【1.7】自定义扶梯声音播放器。
 *
 * <p>思路：玩家选择的 OGG 音频字节保存在存档（服务端），通过网络同步到客户端镜像。
 * 这里把字节解码成 {@link SoundBuffer} 直接注入 {@code SoundBufferLibrary.cache}，
 * 再构造一个自定义 {@link SoundInstance}（重写 {@code resolve} 绕过原版的事件查找），
 * 用引擎原生的 getCompleteBuffer(computeIfAbsent) 命中缓存播放。
 *
 * <p><b>【1.9】距离按「整条扶梯」算，不是按某个方块算。</b>
 * 绑定信息里只存了玩家用石斧点的那**一个**方块，但一条扶梯往往远长于 16 格：
 * 如果只算到那个方块的距离，玩家站在扶梯另一端（离那个方块十几格外）就会莫名其妙静音。
 * 所以这里用 {@link EscalatorUtil#collectChain} 把整条扶梯（含左右两列与侧板）展开成方块集合，
 * 取「玩家到链上**最近**方块」的距离 —— 也就是**离开整条扶梯超过范围才静音**（默认 16 格）。
 *
 * <p><b>【1.17】别把本类的 16 格与「无障碍提示音」的 4 格搞混</b>（两者是**故意不同**的）：
 * <ul>
 *   <li><b>本类 = 扶梯运行底噪</b>：作用在**整条扶梯**上、默认射程 **16 格**，坐一整程都听得见才对；</li>
 *   <li><b>{@link EscalatorChimePlayer} = 无障碍提示音</b>：作用在**单个扶梯方块**上、默认射程 **4 格**，
 *       只管进出口那几格，这样「哪一头在响」才有导向意义。</li>
 * </ul>
 * （早先提示音错用了 16 格，导致坐一整程都只听见上客端那路，已改回 4 格。）
 *
 * <p><b>【1.24】这两个射程都变成可调的了</b>（{@code /futiround} 改本类、{@code /futihelpround} 改提示音），
 * 但**默认仍是 16 : 4**，上面的「整条 vs 单块」语义不变。判定一律走 {@link #rangeFor}（逐 tick 现算）。
 *
 * <p>音量 = 距离衰减（范围内 1.0 → 0.0）× 这条扶梯自己的音量设定（界面输入 1~1000）。
 * 【1.12】100 = 原始音量，&gt;100 = 放大（最大 1000 = 10×）。
 * 要让 &gt;1 真正响，必须同时拆掉**两层**夹取：
 * <ol>
 *   <li>Minecraft 侧：{@code SoundEngine.calculateVolume} 把增益夹到 [0,1]
 *       → 由 {@code SoundEngineVolumeMixin} 放开到我方声音专用上限；</li>
 *   <li>OpenAL 侧：有效增益被夹到源的 {@code AL_MAX_GAIN}，而它默认 = 1.0
 *       → 开播时把该源的 AL_MAX_GAIN 抬到 {@link #MAX_GAIN}（见 {@code onClientTick}）。</li>
 * </ol>
 * 只做第 1 步不够（有效增益仍会被压回 1.0），只做第 2 步也不够（值根本传不出来）。
 * 没有绑定自定义声音的扶梯不发声（没有实例就不播放，天然静音）；
 * 同一时刻只播放离玩家最近的那条扶梯的声音，避免多音源混杂。
 *
 * <p><b>【1.11】默认扶梯音频（/futimusic）</b>：服务端可以设一个「默认音频」，
 * 于是**没有单独绑定音频**的扶梯也会发声（所有扶梯统一放内置音频那种玩法）。
 * 候选集合因此变成两部分：
 * <ol>
 *   <li>所有单独绑定过音频的扶梯（原有逻辑，链距离）；</li>
 *   <li>玩家附近最近的扶梯方块（只有设了默认音频时才扫描，见 {@link #nearestEscalatorNear}）。</li>
 * </ol>
 * 两条候选按「离玩家最近」取胜。因为「同一条链」的绑定一定会在第 1 步算出
 * ≤ 第 2 步的链距离，所以「单独绑定优先、其余用默认」是天然成立的。
 *
 * <p><b>【1.14】起始淡入</b>：{@code play()} 是「立刻用实例当时的 volume 建源并开播」，
 * 而实例刚从构造函数出来时 volume = 1.0，一 tick 之后才被纠正成「距离衰减 × 用户音量」——
 * 于是声音出现的那一瞬间会先满音量炸一下，听感就是很突兀的一声“咔”。
 * 现在 play() 之前先把音量摆到 {@link #FADE_IN_FLOOR}（≈ -80 dB，听不见但不为 0），
 * 再用 {@link #FADE_IN_TICKS} tick 指数淡入到目标值。
 * 起点**不能取 0**：MC 见音量 ≤ 0 会直接 {@code channel.stop()} 把通道掐掉。
 *
 * <p><b>【1.15】「剪掉开头一截再播」</b>（给屏蔽门关门提示音做「结尾对齐门关上那一刻」用，
 * 见 {@link PsdChimePlayer}）：{@link #offsetPlaybackId} 把起始偏移编进**播放 ID**；
 * {@link #injectPlayback} 解码后按 {@link #sliceHead} 剪出尾部、以播放 ID 算出的缓存 key 注入；
 * 播放实例的 {@code resolve} 用**同一个串**取 {@code Sound.getPath()}，于是正好命中那份尾部。
 * 内置素材也要走这条路（原版事件没法从中间开始播），它们的字节从**模组自己的 jar** 读
 * （{@link #bundledBytes}）—— 内置档在存档同步表里是没有字节的。
 *
 * <p>三个静态入口由 {@link SmoothLiftClient} 注册调用：
 * {@link #onClientTick(Minecraft)}（每 tick）、{@link #onDisconnect()}、{@link #onAudioReloaded()}。
 */
public final class EscalatorAudioPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 【1.24】运行底噪的**默认**可闻范围（格）= {@link EscalatorSpeedData#DEFAULT_ROUND} = 16。
     *
     * <p>现在是可调的（{@code /futiround}），所以**实际判定一律走 {@link #rangeFor}**；
     * 本常量只是把「默认 16 格」这件事在播放器这一侧留个名字，方便对照
     * {@link EscalatorChimePlayer} 那边的默认 4 格（两者故意不同，见 1.17 / 1.24）。
     *
     * <p>注意是「离开**整条扶梯**」的距离，不是到某个方块的距离。
     */
    public static final double DEFAULT_RANGE = EscalatorSpeedData.DEFAULT_ROUND;

    /**
     * 【1.12】最大音量增益 = {@link EscalatorSpeedData#AUDIO_VOLUME_MAX}/{@link EscalatorSpeedData#DEFAULT_AUDIO_VOLUME}
     * = 1000/100 = 10×。原版引擎把音量夹到 [0,1]，超过 1 的部分靠
     * {@code SoundEngineVolumeMixin} 放开到这个上限。
     */
    public static final float MAX_GAIN =
            EscalatorSpeedData.AUDIO_VOLUME_MAX / (float) EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;

    /** {@link #rawEscalatorGain} 的「不是我方声音」哨兵值。 */
    public static final float NOT_ESCALATOR_SOUND = -1.0f;

    /**
     * 【1.14】声音刚出现时的淡入 tick 数（{@value} tick = 0.3 秒）。
     *
     * <p>为什么需要它：{@code SoundManager.play(inst)} 会**立刻**用实例当时的 {@code volume}
     * 建立 OpenAL 源并开播（{@code SoundEngine.play}：{@code getVolume()} → {@code calculateVolume}
     * → {@code Channel.setVolume}），而实例刚从构造函数出来时 {@code volume == 1.0}；
     * 一 tick 之后引擎才把音量更新成「距离衰减 × 用户音量」。于是「声音出现的那一瞬间」
     * 会先以满音量响一下再掉下去 —— 听感就是很突兀的一声“咔”。
     * 现在在 {@code play()} **之前**就把位置/起声音量摆好，再让音量淡入到目标值。
     */
    public static final int FADE_IN_TICKS = 6;

    /**
     * 淡入的起点增益 ≈ -80 dB：听不见，但**必须 &gt; 0**。
     *
     * <p>为什么要有个非零下限：MC 的 {@code SoundEngine} 在音量 ≤ 0 时不是把增益设成 0，
     * 而是直接 {@code channel.stop()} 把整条通道**停掉**（{@code SoundEngine.method_19750}：
     * {@code if (volume <= 0) channel.stop(); else channel.setVolume(volume);}）。
     * 起点取 0 会让声音在出现的第一 tick 就被掐断（然后下一 tick 重建、再被掐断），
     * 结果是彻底没声音 —— 比爆音更糟。
     */
    public static final float FADE_IN_FLOOR = 1.0e-4f;

    /**
     * 【1.12】给音量 Mixin 用：返回这条声音**未夹取**的增益（可 &gt;1）；
     * 不是本模组的扶梯声音时返回 {@link #NOT_ESCALATOR_SOUND}，让 Mixin 交回原版处理。
     *
     * <p><b>【1.20】改成按 {@link GainManagedSound} 接口判定</b>，不再逐个 {@code instanceof}：
     * 运行底噪（本类的 {@code EscalatorSoundInstance}）与无障碍提示音
     * （{@link EscalatorChimePlayer} 的 {@code ChimeInstance}）都实现该接口。
     * 早先只认底噪，提示音被漏掉 → 原版把它夹到 [0,1] → 「提示音音量调到 100 以上没变化」。
     * 以后再加新的扶梯声音，只要实现 {@link GainManagedSound} 就自动享有 &gt;1 的增益。
     */
    public static float rawEscalatorGain(SoundInstance instance) {
        return instance instanceof GainManagedSound ? instance.getVolume() : NOT_ESCALATOR_SOUND;
    }

    /** 正在播放的实例：音频ID → 实例（一个音频同一时刻只播一个实例，防止重复 play 泄漏）。 */
    private static final Map<String, EscalatorSoundInstance> ACTIVE = new HashMap<>();

    /**
     * 【1.9】扶梯链缓存：绑定方块 → 该扶梯链上全部方块。
     *
     * <p>每 tick 都对「所有绑定」做一次链遍历太浪费（扶梯链可能上百格），
     * 所以缓存起来；仅在「绑定集合变化 / 收到新同步 / 断开 / 定期到期」时重建。
     */
    private static final Map<BlockPos, List<BlockPos>> CHAIN_CACHE = new HashMap<>();

    /** 当前缓存对应的绑定集合指纹，用来发现「绑定变了」（如新绑了一条扶梯）。 */
    private static int chainCacheFingerprint = Integer.MIN_VALUE;

    /** 上次重建链缓存的 tick；每 {@link #CHAIN_CACHE_TTL_TICKS} tick 重建一次，兜住世界被改动的情况。 */
    private static long chainCacheBuiltTick = Long.MIN_VALUE;

    /** 链缓存有效期（tick）= 30 秒。 */
    private static final int CHAIN_CACHE_TTL_TICKS = 600;

    /**
     * 【1.11】设了默认音频时用的「附近最近的扶梯方块」缓存。
     *
     * <p>只在 {@code defaultAudio != null} 时才会去扫描，所以没设默认音频的世界
     * （也就是旧存档）**一点额外开销都没有**。扫描半径 = {@link EscalatorSpeedManager#getMaxRound}，
     * 结果缓存 {@link #NEARBY_TTL_TICKS} tick；期间即使玩家移动，
     * 每 tick 仍然用「玩家当前坐标 → 缓存方块」重算距离，只是候选选择最多滞后 0.5 秒。
     */
    private static BlockPos nearbyEscalator;
    private static long nearbyBuiltTick = Long.MIN_VALUE;
    /** 附近扶梯缓存有效期（tick）= 0.5 秒。 */
    private static final int NEARBY_TTL_TICKS = 10;

    /** 解码失败的音频：本次会话内不再反复尝试（避免每 tick 重复解码 + 刷屏）。 */
    private static final Set<String> DECODE_FAILED = new HashSet<>();

    /**
     * 模组自带素材在**自己 jar 内**的路径前缀（= {@code assets/smoothlift/sounds/audio/}）。
     *
     * <p>为什么用「从类路径读自己的资源」而不是问 {@code ResourceManager} 要：
     * <ul>
     *   <li>内置档在 {@link EscalatorSpeedManager#getAudioBytes} 里**没有字节**（那里只有玩家导入的音频），
     *       所以想把内置素材投入「剪头播放」这条注入路径，就必须自己把 ogg 字节拿到手；</li>
     *   <li>类路径读法**不依赖任何会随版本变的方法名**（{@code getResource}/{@code getResourceAsStream}
     *       从 1.19 到 1.21 都一样，而 {@code ResourceManager#getResource} 的返回类型/构造方式
     *       每个大版本都在变），移植到另外几个工程时零改动；</li>
     *   <li>这份字节只用于**注入播放**（剪头那一档）。不剪头时内置档依旧走 sounds.json + 原版资源包，
     *       资源包若能覆盖它，覆盖的也是那条原版路径（原版优先），行为不变。</li>
     * </ul>
     */
    private static final String BUNDLED_AUDIO_PREFIX = "/assets/smoothlift/sounds/audio/";

    /**
     * 【1.15】已量出的音频总时长（ms）：素材键 → ms（{@code -1} = 量不出来，不再重试）。
     *
     * <p>给「把提示音的**结尾**对准某个时刻」用（见 {@link #bundledDurationMs} /
     * {@link #customDurationMs}）：要算「该从第几毫秒开始播」，先得知道整段有多长。
     * 首次真的要解一次 Ogg（量 {@code PCM 字节数 / 帧大小 / 采样率}），之后只查表。
     */
    private static final Map<String, Integer> DURATION_MS = new HashMap<>();

    /**
     * 【1.15】素材里「语音播报」与「嘀嘀声」的分界点（ms）：素材键 → 分界点（{@code -1} = 没有分界）。
     *
     * <p>为什么要在**音频层**算这个：屏蔽门的关门素材是「一整条真实的关门过程录音」——
     * 前面是一段**语音播报**（音节不规则），后面是一串**嘀嘀声**（严格等间隔的脉冲），
     * 中间夹一段安静（实测 mdoorclose.ogg：0~6.6s 语音、6.6~7.35s 静音、7.35~10.81s 嘀嘀）。
     * 这一版把**整段素材**锚在「关门」那一瞬起播（听见的就是「关门人声 → 关门 → 关门嘀嘀」）；
     * 分界点则有两个用处：① 判断这条素材**值不值得**整段起播（有这一段才值得），
     * ② 「只嘀嘀」那一档剪头时**不许剪进人声里**
     * （门只走 4 秒、素材 10.8 秒，两件事的取舍见 {@code PsdChimePlayer} 类注释）。
     *
     * <p>判据是**音频自身的性质**（最后一段够长的安静），与门速、与哪个版本无关，
     * 所以对玩家导入的任何素材都成立，不是给这三个内置文件写死的时间点。
     * 量不出来（素材本来就没有「播报+嘀嘀」这种结构）时返回 {@code -1}，调用方退回老行为。
     */
    private static final Map<String, Integer> ANNOUNCE_SPLIT_MS = new HashMap<>();

    /**
     * 【1.15】找「语音播报 / 嘀嘀声」分界用的安静判据。
     *
     * <p>取 -60dBFS（约 0.001 满量程）当「安静」：提示音的正片实测 RMS 在 0.07~0.16，
     * 而分隔处是**数字静音**（采样值约 1e-5 量级），两者差两个数量级，阈值放中间足够安全。
     *
     * <p>★ 最短安静为什么取 {@value #SPLIT_MIN_SILENCE_MS}ms 而不是更大：**因为真正决定
     * 「哪一段安静是分界」的不是这个阈值，而是下面那条「取**最后**一段合格的安静」**。
     * 实测 {@code mdoorclose.ogg} 的语音段里存在 1150ms 的句间停顿（{@code doorclose.ogg} 里
     * 甚至到 1440ms），两者都**超过** 500ms ⇒ 阈值本身**挡不住**它们；挡住的机制是
     * 「分界点之后那串嘀嘀是 200ms 等间隔、其间安静只有 ~190ms」，所以**最后**一段≥阈值的
     * 安静必定落在「播报结束」而不是语音句读上。
     * 于是这个阈值只负责「比嘀嘀的间隙(190ms)宽、又要比真正的分界窄」——
     * 而真正的分界实测只有 514ms（仅比 500 多 14ms），重新编码一下就可能掉到阈值之下。
     * 取 {@value}（400ms）把余量从 14ms 抬到 114ms，且**不改变**任何现有素材的结果
     * （两个文件的合格安静仍只有「最后那一段」这一处），故是纯增益。
     *
     * <p>另一个条件「安静**后面还剩** {@value #SPLIT_MIN_TAIL_MS}ms 有声内容」是为了保证
     * 分界点之后确实还有「一整串嘀嘀」（实测 3.6~4.1 秒），而不是素材末尾的一点残留。
     */
    private static final float SPLIT_SILENCE_LEVEL = 1.0e-3f;
    private static final int SPLIT_MIN_SILENCE_MS = 400;
    private static final int SPLIT_MIN_TAIL_MS = 1500;

    /**
     * 【1.15】「从第 N 毫秒开始播」这个 N 的**量化步长**（ms）。
     *
     * <p>每剪一个偏移就要在声音引擎缓存里占一个条目（key 里带着偏移），不量化的话同一段音频
     * 会被切出成百上千份。25ms 一档时最长的一段（10.8 秒）最多 ~430 份，而听感上 25ms 的
     * 对齐误差根本听不出来（人耳对「声音与画面对齐」的容差在 **±50ms** 量级）。
     */
    static final int SLICE_STEP_MS = 25;

    /** 上一条「为什么没有声音」的说明；只在状态变化时打印，避免每 tick 刷日志。 */
    private static String lastNote;

    private EscalatorAudioPlayer() {
    }

    /**
     * 每 tick 调用：找到当前维度里离玩家最近的、绑定了自定义声音的扶梯，
     * 注入音频（如未注入）并保证在播，然后按距离更新音量与位置；
     * 没有候选或玩家不在范围内时停掉正在播的实例。
     *
     * <p>每一处「不出声」的分支都会在状态变化时打一条 {@code [SmoothLift/Audio]} 日志，
     * 这样「绑定了却没声音」可以直接从游戏日志里定位到底卡在哪一步。
     */
    public static void onClientTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            stopAll(mc);
            return;
        }
        Map<BlockPos, String> bindings = EscalatorSpeedManager.getClientAudioBindings(mc.level.dimension());
        String defaultAudio = EscalatorSpeedManager.getClientDefaultAudio(mc.level.dimension());
        if (bindings.isEmpty() && defaultAudio == null) {
            stopAll(mc);
            note("当前维度没有任何扶梯绑定了自定义声音（要先在石斧界面里绑定），也没有默认音频"
                    + "（可用 /futimusic default 让所有扶梯播放内置音频）");
            return;
        }
        refreshChainCacheIfNeeded(mc, bindings);

        Vec3 playerPos = mc.player.position();
        BlockPos bestPos = null;
        BlockPos bestNear = null;
        String bestId = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, String> entry : bindings.entrySet()) {
            BlockPos bound = entry.getKey();
            // 【1.9】先在这条扶梯的整条链上找到离玩家最近的那个方块，
            // 再算玩家到它的距离 —— 也就是「离开整条扶梯」的距离，而不是到某个方块的距离。
            BlockPos near = nearestChainPos(bound, playerPos);
            double dist = distanceToBlock(playerPos, near);
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = bound;
                bestNear = near;
                bestId = entry.getValue();
            }
        }
        // 【1.11】默认音频：把「玩家附近最近的扶梯」也当成一个候选。
        // 如果那条扶梯其实单独绑过音频，上面的循环已经用同一条链算出更近的距离并选中它了，
        // 所以这里直接用默认音频不会覆盖掉单独绑定。
        if (defaultAudio != null) {
            BlockPos near = nearestEscalatorNear(mc);
            if (near != null) {
                double dist = distanceToBlock(playerPos, near);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestPos = near;
                    bestNear = near;
                    bestId = defaultAudio;
                }
            }
        }
        if (bestPos == null || bestId == null || bestNear == null) {
            stopAll(mc);
            return;
        }
        // 【09-27 五改】「不播」哨兵：这条扶梯被显式设成静音（石斧界面 → 声音设置 → 不播）。
        //   ★ 必须在下面那两个分支**之前**短路 —— 它不是一个文件、不在音频库里，
        //     若走到 getAudioBytes 就会拿到 null，接着弹出一句「音频还没同步到本客户端」：
        //     那是一句毫无关系、还很误导的话（玩家明明是自己点的「不播」）。
        //   ★ 让「不播」参与「最近一条扶梯」的挑选（它在 blockAudio 里是一条正常绑定），
        //     于是它天然**压过维度默认层** —— 与直梯那套「单独设置 > 默认」语义完全一致。
        if (EscalatorSpeedData.FUTI_AUDIO_OFF.equals(bestId)) {
            stopAll(mc);
            note("最近的这条扶梯被设成了「不播」（石斧界面 → 声音设置 → 不播 可改回）");
            return;
        }
        // 【1.24】可闻范围按**这条扶梯自己的**生效范围算（/futiround 设置）。
        // ★【10-03】范围拆双维：水平（xz）默认 10、垂直（y）默认 5，各算一次线性衰减、取较小；
        //   **任一维越界即静音**（与屏蔽门 /pbmround、/jsr round 的口径完全一致）。
        // 每 tick 现算：范围是网络同步驱动的，绝不能塞进任何「缓存到方块变化为止」的计算里（坑 17）。
        double rangeXz = rangeFor(mc, bestPos);
        double rangeY = rangeYFor(mc, bestPos);
        double dxz = horizontalDistanceToBlock(playerPos, bestNear);
        double dy = verticalDistanceToBlock(playerPos, bestNear);
        if (!(dxz < rangeXz) || !(dy < rangeY)) {
            stopAll(mc);
            note("最近的扶梯整条都在 水平 " + String.format("%.1f", dxz) + " / 垂直 " + String.format("%.1f", dy)
                    + " 格外（离开整条扶梯 水平 " + String.format("%.0f", rangeXz) + "、垂直 "
                    + String.format("%.0f", rangeY) + " 格内才发声，可用 /futiround 调整）");
            return;
        }
        // 【1.8】内置音频走原版资源包加载（sounds.json 已注册 smoothlift:audio/<key>），
        // 不需要字节同步、不需要注入、也没有解码失败这回事。
        boolean builtin = EscalatorSpeedManager.isBuiltinAudio(bestId);
        if (builtin) {
            ResourceLocation event = builtinEvent(bestId);
            if (mc.getSoundManager().getSoundEvent(event) == null) {
                stopAll(mc);
                note("内置音频事件 " + event + " 没注册（模组的 sounds.json 没被加载？）");
                return;
            }
        } else {
            byte[] bytes = EscalatorSpeedManager.getAudioBytes(mc.level, bestId);
            if (bytes == null || bytes.length == 0) {
                stopAll(mc);
                note("绑定的音频 " + bestId + " 还没同步到本客户端（音频数据为空，可点界面里的【刷新】）");
                return;
            }
            if (DECODE_FAILED.contains(bestId)) {
                stopAll(mc);
                note("音频 " + bestId + " 解码失败（MC 只认 Ogg Vorbis），这条扶梯只能静音；详见之前的日志");
                return;
            }
            if (!inject(mc, bestId, bytes, 0)) {
                stopAll(mc);
                note("音频 " + bestId + " 解码失败（MC 只认 Ogg Vorbis：MP3/Opus/FLAC 都会失败）");
                return;
            }
        }

        SoundEngine engine = mc.getSoundManager().soundEngine;
        EscalatorSoundInstance inst = ACTIVE.get(bestId);
        if (inst != null && !engine.instanceToChannel.containsKey(inst)) {
            // 引擎侧已经把这条通道丢了（资源重载 F3+T、换音频输出设备、被 stopAll 等），
            // 实例却还留在 ACTIVE 里 -> 以后永远不会再 play，表现就是「明明绑着却永久静音」。
            ACTIVE.remove(bestId);
            inst = null;
        }
        // 音量 = 距离衰减（离开整条扶梯 range 格内 1.0 -> 0.0）× 这条扶梯自己的音量设定（1~1000）。
        // 【1.12】100 = 原始音量（增益 1.0），1000 = 10× 放大。这里存的是「未夹取」的增益，
        // 引擎每 tick 用实例的 volume/x/y/z 同步到 Channel；>1 的部分靠 SoundEngineVolumeMixin 放行。
        // 【1.24】range 由 /futiround 决定（默认 水平 10 / 垂直 5），见上面的 rangeFor / rangeYFor。
        // ★【10-03】双维：水平 / 垂直各算线性 (1-d/r)，取**较小**（越贴边的那一维先行淡出）。
        float distanceFactor = (float) Math.max(0.0,
                Math.min(1.0 - dxz / rangeXz, 1.0 - dy / rangeY));
        int userVolume = EscalatorSpeedManager.getClientBindingVolume(mc.level.dimension(), bestPos);
        float target = distanceFactor * (userVolume / (float) EscalatorSpeedData.DEFAULT_AUDIO_VOLUME);

        if (inst == null) {
            inst = new EscalatorSoundInstance(bestId);
            inst.startTick = mc.level.getGameTime();
            // 【1.14】play() 之前必须先把音量摆成「淡入起点」：
            // SoundEngine.play 会**立刻**用实例当时的 volume 建源开播，而实例刚从构造函数
            // 出来时 volume == 1.0 —— 于是声音出现的那一瞬会先满音量炸一下再掉到目标音量，
            // 听感就是那声很突兀的“咔”。起点用 FADE_IN_FLOOR（≈ -80 dB，听不见但不是 0）。
            inst.setPosition(new Vec3(bestNear.getX() + 0.5, bestNear.getY() + 0.5, bestNear.getZ() + 0.5), FADE_IN_FLOOR);
            mc.getSoundManager().play(inst);
            if (!engine.instanceToChannel.containsKey(inst)) {
                // play() 没建立通道（例如声音引擎还没加载完），下一 tick 再试。
                note("声音引擎没有接受这条声音（通道未建立），稍后重试：" + bestId);
                return;
            }
            // 静态 Sound 播完一遍就停，这里对底层 Channel 开启循环。
            //
            // 【1.12】顺便把该 OpenAL 源的 AL_MAX_GAIN 抬到 MAX_GAIN：
            // OpenAL 会把「源增益」夹到 AL_MAX_GAIN，而它默认 = 1.0，所以光去掉
            // Minecraft 侧的夹取（见 SoundEngineVolumeMixin）还不够 —— 有效增益依旧被
            // 压回 1.0，表现就是「音量调到 100 以上没变化」。抬到 10 才真正能放大。
            // 该属性是「按源」的、会一直保留，所以开播时设一次即可；引擎每 tick 只改
            // AL_GAIN / pitch / position，不会碰 AL_MAX_GAIN。
            ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
            if (handle != null) {
                handle.execute(ch -> {
                    ch.setLooping(true);
                    AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, MAX_GAIN);
                });
            }
            ACTIVE.put(bestId, inst);
            LOGGER.info("[SmoothLift/Audio] 开始循环播放 {}{}（到最近扶梯的整条链 {} 格）",
                    bestId, builtin ? "（内置）" : "", String.format("%.1f", bestDist));
        }
        // 【1.14】淡入：声音出现后的头 FADE_IN_TICKS tick 内，增益从 FADE_IN_FLOOR 指数升到目标值。
        // 声音挂在「离玩家最近的链方块」上，而不是绑定的那个方块上 ——
        // 否则长扶梯上声音会从十几格外的某个点传过来，听起来方向是错的。
        inst.setPosition(new Vec3(bestNear.getX() + 0.5, bestNear.getY() + 0.5, bestNear.getZ() + 0.5), fadeInGain(mc, inst, target));
        lastNote = null;
    }

    /**
     * 淡入增益：从 {@link #FADE_IN_FLOOR} 升到 {@code target}，共 {@link #FADE_IN_TICKS} tick。
     *
     * <p>用**指数**（等 dB 步进）而不是线性：响度是对数感知的，线性爬升的头几步在听感上
     * 仍是一跳一跳的。下限不能取 0，原因见 {@link #FADE_IN_FLOOR}。
     */
    private static float fadeInGain(Minecraft mc, EscalatorSoundInstance inst, float target) {
        if (target <= FADE_IN_FLOOR) {
            // 目标本来就极轻（例如距离已衰减到接近 0），没有淡入的余地，直接用它。
            return target;
        }
        long elapsed = mc.level.getGameTime() - inst.startTick;
        if (elapsed <= 0) {
            return FADE_IN_FLOOR;
        }
        if (elapsed >= FADE_IN_TICKS) {
            return target;
        }
        double step = Math.pow(target / (double) FADE_IN_FLOOR, 1.0 / FADE_IN_TICKS);
        return (float) (FADE_IN_FLOOR * Math.pow(step, elapsed));
    }

    /** 只在「没有声音的原因」发生变化时打一条日志。 */
    private static void note(String reason) {
        if (reason.equals(lastNote)) {
            return;
        }
        lastNote = reason;
        LOGGER.info("[SmoothLift/Audio] 未播放：{}", reason);
    }

    // ------------------------------------------------------------------
    // 【1.9】「整条扶梯」的链缓存与距离计算
    // ------------------------------------------------------------------

    /**
     * 需要时重建链缓存：绑定集合变了 / 从没建过 / 缓存过期（{@link #CHAIN_CACHE_TTL_TICKS}）。
     * 绑定集变化用「位置集合的哈希」判断，够用且不用比整个 Map。
     */
    private static void refreshChainCacheIfNeeded(Minecraft mc, Map<BlockPos, String> bindings) {
        int fingerprint = bindings.keySet().hashCode();
        long now = mc.level.getGameTime();
        boolean expired = chainCacheBuiltTick == Long.MIN_VALUE
                || now - chainCacheBuiltTick >= CHAIN_CACHE_TTL_TICKS;
        if (fingerprint == chainCacheFingerprint && !expired && CHAIN_CACHE.size() == bindings.size()) {
            return;
        }
        CHAIN_CACHE.clear();
        for (BlockPos bound : bindings.keySet()) {
            CHAIN_CACHE.put(bound, chainOf(mc, bound));
        }
        chainCacheFingerprint = fingerprint;
        chainCacheBuiltTick = now;
    }

    /**
     * 展开某条扶梯的方块集合（缓存未命中时退回「只有绑定方块自己」）。
     * 客户端区块未加载时 {@code collectChain} 会返回空集，这时退化成单方块行为，不会崩。
     */
    private static List<BlockPos> chainOf(Minecraft mc, BlockPos bound) {
        Set<BlockPos> chain = EscalatorUtil.collectChain(mc.level, bound);
        List<BlockPos> list = new ArrayList<>(chain.size() + 1);
        list.addAll(chain);
        if (!chain.contains(bound)) {
            list.add(bound);
        }
        return list;
    }

    /**
     * 这条扶梯整条链上、离玩家**最近**的那个方块。
     *
     * <p>用「点到方块实心体的距离」而不是「到方块中心的距离」：站在扶梯上时距离是 0，
     * 走开一格才从 1 开始涨 —— 这才是「离开扶梯 N 格」的直觉。
     * 拿不到链（区块没加载 / 那个方块已被拆）时退回绑定方块自己。
     */
    private static BlockPos nearestChainPos(BlockPos bound, Vec3 playerPos) {
        List<BlockPos> chain = CHAIN_CACHE.get(bound);
        if (chain == null || chain.isEmpty()) {
            return bound;
        }
        BlockPos best = bound;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : chain) {
            double d = distanceToBlock(playerPos, p);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** 点到方块实心体（1×1×1）的最短距离；玩家在方块内部时返回 0。 */
    private static double distanceToBlock(Vec3 p, BlockPos pos) {
        double dx = Math.max(0.0, Math.max(pos.getX() - p.x, p.x - (pos.getX() + 1.0)));
        double dy = Math.max(0.0, Math.max(pos.getY() - p.y, p.y - (pos.getY() + 1.0)));
        double dz = Math.max(0.0, Math.max(pos.getZ() - p.z, p.z - (pos.getZ() + 1.0)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * ★【10-03】点到方块实心体的**水平（xz）**分量，格 —— 与 {@link #distanceToBlock} 的 dx/dz 同口径
     * （站在方块正上方 / 正下方时水平分量为 0），拆双维后供距离衰减按 xz 那一维单独比。
     */
    private static double horizontalDistanceToBlock(Vec3 p, BlockPos pos) {
        double dx = Math.max(0.0, Math.max(pos.getX() - p.x, p.x - (pos.getX() + 1.0)));
        double dz = Math.max(0.0, Math.max(pos.getZ() - p.z, p.z - (pos.getZ() + 1.0)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** ★【10-03】点到方块实心体的**垂直（y）**分量，格（站在方块正上/正下方那一格内时为 0）。 */
    private static double verticalDistanceToBlock(Vec3 p, BlockPos pos) {
        return Math.max(0.0, Math.max(pos.getY() - p.y, p.y - (pos.getY() + 1.0)));
    }

    // ------------------------------------------------------------------
    // 【1.11】默认音频：玩家附近最近的扶梯（带缓存）
    // ------------------------------------------------------------------

    /**
     * 附近最近的扶梯方块（半径 = 本维度**可能的最大**底噪范围），结果缓存 {@link #NEARBY_TTL_TICKS} tick。
     * 只有设了默认音频时才会被调用，所以旧存档/没设默认音频的世界完全不受影响。
     *
     * <p>【1.24】扫描半径取 {@link EscalatorSpeedManager#getMaxRound}（默认值 + 单独设置的最大值），
     * 这样把范围调大以后也能找到更远的扶梯；真正判定「听不听得见」是在
     * {@link #onClientTick} 里按**那条扶梯自己的**范围再比一次。
     */
    private static BlockPos nearestEscalatorNear(Minecraft mc) {
        long now = mc.level.getGameTime();
        if (nearbyBuiltTick != Long.MIN_VALUE && now - nearbyBuiltTick < NEARBY_TTL_TICKS) {
            return nearbyEscalator;
        }
        nearbyBuiltTick = now;
        nearbyEscalator = scanNearestEscalator(mc);
        return nearbyEscalator;
    }

    /**
     * 在「已加载区块里的扶梯**阶梯**方块」索引里找离玩家最近的那一块；范围内没有则返回 null。
     *
     * <p>【1.24】<b>改成走索引而不是逐格扫立方体</b>。原来的球壳扫描是 O(半径³)：
     * 半径 16 时 33³ ≈ 3.6 万次 {@code getBlockState} 还能接受，但范围现在可以被 {@code /futiround}
     * 调到 128，那就是 257³ ≈ **1700 万**次 —— 每 0.5 秒一次，会直接卡出可见的掉帧。
     * 走索引后代价只跟「已加载的阶梯块数量」有关，与半径无关，和 {@link EscalatorChimePlayer#scan} 同一套做法。
     *
     * <p>只认**阶梯**块（不认护栏/侧板）是刻意的：阶梯块铺满整条扶梯，取最近的那块就足以代表
     * 「最近的扶梯」，而且和提示音那边的定位依据完全一致（见坑 22 的链条展开规则）。
     */
    private static BlockPos scanNearestEscalator(Minecraft mc) {
        List<BlockPos> steps = EscalatorStepIndex.positions();
        if (steps.isEmpty()) {
            return null;
        }
        int radius = EscalatorSpeedManager.getMaxRound(mc.level);
        BlockPos center = mc.player.blockPosition();
        Vec3 eye = mc.player.position();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : steps) {
            if (Math.abs(pos.getX() - center.getX()) > radius
                    || Math.abs(pos.getY() - center.getY()) > radius
                    || Math.abs(pos.getZ() - center.getZ()) > radius) {
                continue;
            }
            double dist = distanceToBlock(eye, pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    /**
     * 【1.24】这条扶梯**生效的**运行底噪可闻范围（格）——**水平（xz）**维：单独设置 &gt; 维度默认
     * （/futiround，★【10-03】初始 10）。
     *
     * <p>每 tick 调用一次，结果直接参与距离衰减与「该不该响」的判定 —— 所以
     * {@code /futiround} 改完**下一个 tick** 就生效，不需要重进世界。
     */
    public static double rangeFor(Minecraft mc, BlockPos pos) {
        return EscalatorSpeedManager.getRound(mc.level, pos);
    }

    /** ★【10-03】同 {@link #rangeFor}，但取**垂直（y 轴）**维（初始 5 格）。 */
    public static double rangeYFor(Minecraft mc, BlockPos pos) {
        return EscalatorSpeedManager.getRoundY(mc.level, pos);
    }

    /** 断开连接：停掉所有扶梯声音。 */
    public static void onDisconnect() {
        stopAll(Minecraft.getInstance());
        DECODE_FAILED.clear();
        DURATION_MS.clear();
        ANNOUNCE_SPLIT_MS.clear();
        clearChainCache();
        lastNote = null;
    }

    /**
     * 服务端音频数据同步完成后：停掉旧实例，下一 tick 用新数据重新播放。
     * 顺带清掉「解码失败」记录 —— 玩家可能是重新导入了一个修好的文件，同名也要再试一次。
     * 【1.15】把量过的时长也一起清掉：同名文件可能被换成了内容不同的另一个（时长变了，
     * 「剪头对准结尾」的偏移必须跟着变），留着旧数就会一直剪错。
     */
    public static void onAudioReloaded() {
        stopAll(Minecraft.getInstance());
        DECODE_FAILED.clear();
        DURATION_MS.clear();
        ANNOUNCE_SPLIT_MS.clear();
        // 绑定/音量都可能刚变过，顺手让链缓存失效，下一 tick 重建。
        clearChainCache();
        lastNote = null;
    }

    private static void clearChainCache() {
        CHAIN_CACHE.clear();
        chainCacheFingerprint = Integer.MIN_VALUE;
        chainCacheBuiltTick = Long.MIN_VALUE;
        nearbyEscalator = null;
        nearbyBuiltTick = Long.MIN_VALUE;
    }

    /**
     * 把 OGG 字节解码成 {@link SoundBuffer} 并注入声音引擎缓存。
     * 缓存 key 必须等于 {@code Sound.getPath()} 的值
     * （{@code Sound.SOUND_LISTER} = FileToIdConverter("sounds", ".ogg")：
     * location -&gt; sounds/xxx.ogg），play() 才会命中 computeIfAbsent，
     * 否则会去资源包加载并失败（静音）。
     *
     * @param audioId 声音缓存的 key 来源；**调进剪头播放时必须带上偏移后缀**
     *                （{@link #offsetPlaybackId}）—— 缓存 key 由它算出来，
     *                两个不同偏移就是两份不同的缓存条目，互不覆盖。
     * @param startMs 从第几毫秒开始播（0 = 整段）。见 {@link #sliceHead}。
     * @return 是否已注入（缓存里已有同样算成功）
     */
    private static boolean inject(Minecraft mc, String audioId, byte[] bytes, int startMs) {
        SoundEngine engine = mc.getSoundManager().soundEngine;
        ResourceLocation key = soundCacheKey(audioId);
        if (engine.soundBuffers.cache.containsKey(key)) {
            return true;
        }
        try (OggAudioStream stream = new OggAudioStream(new ByteArrayInputStream(bytes))) {
            ByteBuffer pcm = stream.readAll();
            AudioFormat format = stream.getFormat();
            int frames = pcm.limit() / Math.max(1, format.getFrameSize());
            int totalMs = sampleRate(format) <= 0 ? 0
                    : (int) Math.round(1000.0 * frames / sampleRate(format));
            // 剪头：startMs 量化过、且调用方已保证 < totalMs，这里再夹一次防越界
            int cutMs = Math.max(0, Math.min(startMs, Math.max(0, totalMs - 1)));
            ByteBuffer data = sliceHead(pcm, format, cutMs);
            SoundBuffer buffer = new SoundBuffer(data, format);
            engine.soundBuffers.cache.put(key, CompletableFuture.completedFuture(buffer));
            if (cutMs > 0) {
                LOGGER.info("[SmoothLift/Audio] {} 解码成功：{} 字节 -> {}Hz {} 声道，{}ms"
                                + "；已剪掉开头 {}ms（余 {}ms）",
                        audioId, bytes.length, sampleRate(format), format.getChannels(), totalMs,
                        cutMs, totalMs - cutMs);
            } else {
                LOGGER.info("[SmoothLift/Audio] {} 解码成功：{} 字节 -> {}Hz {} 声道，{}ms",
                        audioId, bytes.length, sampleRate(format), format.getChannels(), totalMs);
            }
            return true;
        } catch (IOException e) {
            // 解码失败：不注入，该音频不播放（保持静音）。
            // MC 用 stb_vorbis 解码，只认 Ogg 容器 + Vorbis 编码：
            // MP3 改扩展名（"Failed to find Ogg Header"）、Ogg Opus/FLAC 都会在这里失败。
            // ★ 失败记录按**素材**记（去掉偏移后缀）：剪头那档失败 == 整段也播不出来，
            //   记素材才能让 injectAudio 那条老路径也一起放弃重试，不至于每 tick 各失败一次。
            DECODE_FAILED.add(audioId);
            DECODE_FAILED.add(baseOf(audioId));
            LOGGER.warn("[SmoothLift/Audio] 音频 {} 解码失败，这条扶梯将一直静音。原因：{}"
                    + "（MC 只支持 Ogg Vorbis；把 MP3 直接改名成 .ogg 或转成 Ogg Opus 都不行）",
                    audioId, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 【1.15】「剪掉开头一截再播」需要的几个小工具（时长 / 偏移编码 / 切片）
    // ------------------------------------------------------------------

    /** 采样率取整（{@link AudioFormat#getSampleRate()} 是 float，用它做除法要防 0）。 */
    private static int sampleRate(AudioFormat format) {
        return (int) format.getSampleRate();
    }

    /**
     * 【1.15】模组自带素材（{@code dooropen / doorclose / mdoorclose} 这一类）的 ogg 字节。
     *
     * <p>读不到（打包漏了文件 / 开发环境没把 resources 挂上）返回 null —— 调用方一律
     * 退回「不剪头」的老路径，不会因此静音。
     */
    static byte[] bundledBytes(String builtinKey) {
        if (builtinKey == null || builtinKey.isEmpty()) {
            return null;
        }
        try (InputStream in = EscalatorAudioPlayer.class
                .getResourceAsStream(BUNDLED_AUDIO_PREFIX + builtinKey + ".ogg")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift/Audio] 读不到模组自带素材 {}.ogg：{}", builtinKey, e.getMessage());
            return null;
        }
    }

    /**
     * 【1.15】模组自带素材的时长（ms）；读不到/解不出来返回 {@code -1} 并缓存，不再重试。
     *
     * <p>只在**第一次**真从 jar 读字节 + 解一次 Ogg，之后连字节都不再读（先查表后取字节）。
     */
    static int bundledDurationMs(String builtinKey) {
        String cacheKey = "builtin:" + builtinKey;
        Integer cached = DURATION_MS.get(cacheKey);
        return cached != null ? cached : measureAndCache(cacheKey, bundledBytes(builtinKey));
    }

    /**
     * 【1.15】玩家导入素材的时长（ms）。
     *
     * <p>{@code bytes == null}（还没同步到）时**不缓存**，直接返回 {@code -1} —— 下一次数据到了还能再量。
     * 这里不会再解一遍：只要量过一次，后面全是查表（每秒 20 次调用，绝不能每次都解码）。
     */
    static int customDurationMs(String audioId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return -1;
        }
        Integer cached = DURATION_MS.get(audioId);
        return cached != null ? cached : measureAndCache(audioId, bytes);
    }

    /**
     * 【1.15】模组自带素材的「语音播报 / 嘀嘀声」分界点（ms）；没有这种结构返回 {@code -1}。
     *
     * <p>与 {@link #bundledDurationMs} 共用同一次解码与同一张表，所以调用它的代价只是查表。
     */
    static int bundledAnnounceSplitMs(String builtinKey) {
        bundledDurationMs(builtinKey); // 先保证已经解过（没解过这里会解一次），表里才有分界点
        Integer split = ANNOUNCE_SPLIT_MS.get("builtin:" + builtinKey);
        return split != null ? split : -1;
    }

    /** 【1.15】玩家导入素材的「语音播报 / 嘀嘀声」分界点（ms）；没有这种结构返回 {@code -1}。 */
    static int customAnnounceSplitMs(String audioId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return -1;
        }
        customDurationMs(audioId, bytes); // 同上：先保证解过
        Integer split = ANNOUNCE_SPLIT_MS.get(audioId);
        return split != null ? split : -1;
    }

    /** 解码量一次时长并缓存（解不出来缓存 {@code -1}，避免反复重试）。 */
    private static int measureAndCache(String cacheKey, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return -1;
        }
        int ms = -1;
        try (OggAudioStream stream = new OggAudioStream(new ByteArrayInputStream(bytes))) {
            ByteBuffer pcm = stream.readAll();
            AudioFormat format = stream.getFormat();
            int rate = sampleRate(format);
            if (rate > 0) {
                ms = (int) Math.round(1000.0 * (pcm.limit() / Math.max(1, format.getFrameSize())) / rate);
            }
            // 【1.15】同一次解码里顺手找出「语音播报 / 嘀嘀声」的分界点：
            //   这里已经把整段 PCM 解出来了，再扫一遍是纯内存遍历（10.8 秒约 100 万次比较，
            //   一次会话只做一次），比之后再解一遍便宜得多。
            ANNOUNCE_SPLIT_MS.put(cacheKey, rate > 0 ? detectAnnounceSplitMs(pcm, format) : -1);
        } catch (IOException e) {
            ms = -1;
            ANNOUNCE_SPLIT_MS.put(cacheKey, -1);
        }
        DURATION_MS.put(cacheKey, ms);
        return ms;
    }

    /**
     * 【1.15】在一整段 PCM 里找出「语音播报」与「嘀嘀声」的分界点（ms）；找不到返回 {@code -1}。
     *
     * <p>做法：找出**最后一段**够长的安静（≥ {@value #SPLIT_MIN_SILENCE_MS}ms），
     * 且它**后面还剩**至少 {@value #SPLIT_MIN_TAIL_MS}ms 的有声内容，分界点取这段安静的**起点**。
     * 于是「分界点之前」= 语音播报，「分界点之后」= 一整串嘀嘀。
     *
     * <p>★ 靠的是「**最后一段**」这个次序，不是阈值大小：实测语音句间停顿最长到 1440ms，
     * 比阈值还长 —— 它们确实会被判成合格安静，但都会被后面那个真正的分界点**覆盖掉**
     * （循环里 {@code split} 是不断被后一段改写的）。分界点之后那串嘀嘀是 200ms 等间隔、
     * 其间安静只有 ~190ms，不可能再冒出一段 ≥400ms 的安静来把它顶掉。
     *
     * <p>只认 16bit PCM（stb_vorbis 解出来就是这个）。其它位深返回 {@code -1} —— 宁可退回
     * 「不分段」的老行为，也不要去猜一种没验证过的采样格式。
     */
    private static int detectAnnounceSplitMs(ByteBuffer pcm, AudioFormat format) {
        if (format.getSampleSizeInBits() != 16) {
            return -1;
        }
        int rate = sampleRate(format);
        int channels = Math.max(1, format.getChannels());
        int frameSize = Math.max(2, format.getFrameSize());
        if (rate <= 0) {
            return -1;
        }
        ByteBuffer view = pcm.duplicate();
        view.order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int frames = view.remaining() / frameSize;
        if (frames <= 0) {
            return -1;
        }
        int minSilenceFrames = (int) (SPLIT_MIN_SILENCE_MS / 1000.0 * rate);
        int minTailFrames = (int) (SPLIT_MIN_TAIL_MS / 1000.0 * rate);
        int split = -1;
        int runStart = -1;          // 当前这段安静的起点（帧号）；-1 = 现在有声
        for (int i = 0; i < frames; i++) {
            int at = i * frameSize;
            boolean silent = true;
            for (int c = 0; c < channels; c++) {
                short s = view.getShort(at + c * 2);
                if (s > SPLIT_SILENCE_LEVEL * 32767.0f || s < -SPLIT_SILENCE_LEVEL * 32767.0f) {
                    silent = false;
                    break;
                }
            }
            if (silent) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else {
                if (runStart >= 0) {
                    // 这段安静刚刚结束：够长、且后面还剩够多的有声内容 → 是一个合法分界点
                    if (i - runStart >= minSilenceFrames && frames - runStart >= minTailFrames) {
                        split = (int) Math.round(1000.0 * runStart / rate);
                    }
                    runStart = -1;
                }
            }
        }
        // 收尾那段如果也是安静（素材末尾的静音），到这里 runStart 仍 >= 0：
        // 它后面没有剩余有声内容，frames - runStart 必然不够 minTailFrames，自然不会被选中。
        return split;
    }

    /**
     * 【1.15】把「从第 startMs 毫秒开始播」编码进**播放 ID**；{@code startMs <= 0} 原样返回。
     *
     * <p>为什么编码进 ID 而不是另开一个参数：注入要用它算缓存 key、播放实例的 {@code resolve}
     * 要用它算 {@code Sound.getPath()}，两边必须用**同一个串**；把偏移寄托在 ID 上就只有一处真相。
     * 分隔符取 {@code \u0001}：它是控制字符，Windows 文件名里不可能出现，玩家导入的音频名不会撞上。
     */
    static String offsetPlaybackId(String audioId, int startMs) {
        return startMs <= 0 ? audioId : audioId + '\u0001' + startMs;
    }

    /** 播放 ID 里携带的起始偏移（ms）；没有后缀 = 0。 */
    static int offsetOf(String playbackId) {
        int at = playbackId.indexOf('\u0001');
        if (at < 0) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(playbackId.substring(at + 1)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 播放 ID 对应的**素材 ID**（去掉偏移后缀）。 */
    static String baseOf(String playbackId) {
        int at = playbackId.indexOf('\u0001');
        return at < 0 ? playbackId : playbackId.substring(0, at);
    }

    /** 起始偏移量化到最近的 {@link #SLICE_STEP_MS} 整数倍（理由见该字段注释）。 */
    static int quantizeOffset(int ms) {
        if (ms <= 0) {
            return 0;
        }
        return (int) (Math.round(ms / (double) SLICE_STEP_MS) * SLICE_STEP_MS);
    }

    /**
     * 剪掉 PCM 的**开头** cutMs 毫秒，返回余下部分的视图（共享同一块内存，不复制）。
     *
     * <p>按**帧**对齐：{@code bytes = round(cutMs/1000 * 采样率) * 帧大小}。
     * 不对齐的话切点会落在半个采样帧中间，16bit 立体声下就是把左右声道错位、听感是「滋」的一声。
     *
     * <p>返回的 buffer {@code position()=0 / limit()=余下字节数} —— 正是
     * {@code AL10.alBufferData} 期望的形态（它吃 {@code remaining()} 那一段）。
     * {@code SoundBuffer} 只存引用、不碰 position/limit，所以这个视图能直接当整段用。
     *
     * <p>{@code cutMs <= 0} 或「剪完没剩东西」时**原样返回**（宁可不对齐，也不要不出声）。
     */
    private static ByteBuffer sliceHead(ByteBuffer pcm, AudioFormat format, int cutMs) {
        if (cutMs <= 0) {
            return pcm;
        }
        int rate = sampleRate(format);
        if (rate <= 0) {
            return pcm;
        }
        long bytes = Math.round(cutMs / 1000.0 * rate) * Math.max(1, format.getFrameSize());
        if (bytes <= 0 || bytes >= pcm.limit()) {
            return pcm;
        }
        ByteBuffer view = pcm.duplicate();
        view.position((int) bytes);
        ByteBuffer out = view.slice();
        out.order(pcm.order());
        return out;
    }

    /**
     * 【1.39】把一段存档音频注入声音引擎缓存 —— 供**无障碍提示音**播放器复用同一套解码链路
     * （{@link EscalatorChimePlayer} 里选择自定义提示音时走这里，而不是另写一份解码）。
     *
     * <p>与底噪走完全同一条路：Ogg Vorbis 解码 → 塞进 {@code soundBuffers.cache}
     * （key 必须等于 {@code Sound.getPath()}）→ 播放器自己造 {@code Sound} 绕过 sounds.json 查找。
     *
     * <p>★ 已经解码失败过的音频直接返回 false，**不再重试** —— 提示音是每 tick 调用的，
     * 每 tick 重试会把日志刷爆（底噪那边靠 {@link #DECODE_FAILED} 达到同样效果）。
     *
     * @return 是否可用（缓存里已有同样算可用）
     */
    static boolean injectAudio(Minecraft mc, String audioId) {
        if (audioId == null || mc == null || mc.level == null || DECODE_FAILED.contains(audioId)) {
            return false;
        }
        byte[] bytes = EscalatorSpeedManager.getAudioBytes(mc.level, audioId);
        if (bytes == null) {
            return false;
        }
        return inject(mc, audioId, bytes, 0);
    }

    /**
     * 【1.15】按**播放 ID** 注入一段字节，支持「剪掉开头 startMs 毫秒」（偏移编码在 ID 里）。
     *
     * <p>与 {@link #injectAudio} 的区别只有一个：这里**由调用方给字节**。于是
     * <ul>
     *   <li>玩家导入的素材：字节来自存档同步（{@link EscalatorSpeedManager#getAudioBytes}）；</li>
     *   <li>模组自带素材：字节来自自己的 jar（{@link #bundledBytes}）——
     *       内置档在同步表里是没有字节的，原版那条路又没法「从中间开始播」，
     *       所以想让内置素材也享受到剪头对齐，就必须把它拉进这条注入路径。</li>
     * </ul>
     * 两条路共用同一套缓存与解码，{@link #soundLocation} 会按播放 ID（含偏移）算出互不相同的路径。
     */
    static boolean injectPlayback(Minecraft mc, String playbackId, byte[] bytes) {
        if (mc == null || mc.level == null || playbackId == null || bytes == null || bytes.length == 0) {
            return false;
        }
        if (DECODE_FAILED.contains(baseOf(playbackId))) {
            return false; // 同一段素材这次会话里已判死，别再每 tick 重解一遍
        }
        return inject(mc, playbackId, bytes, offsetOf(playbackId));
    }

    /**
     * 音频 ID（= 存档音频文件夹里的文件名）可能包含大写字母、空格或中文，
     * 这种字符串直接塞进 {@link ResourceLocation} 会抛 {@code ResourceLocationException}
     * 并让客户端在渲染线程崩掉。这里把 ID 映射成合法且互不相同的路径：
     * 保留 {@code [a-z0-9._-]}，其余字符转义成 {@code _十六进制_}，末尾拼上 ID 的哈希做区分。
     * 同一个 ID 每次算出的结果都一样（inject 与 resolve 必须用同一个），所以缓存能命中。
     *
     * @return 声音事件的 location（不含前缀与扩展名，如 {@code smoothlift:audio/a_b_1a2b3c4d}）
     */
    static ResourceLocation soundLocation(String audioId) {
        StringBuilder path = new StringBuilder("audio/a");
        int limit = Math.min(audioId.length(), 48);
        for (int i = 0; i < limit; i++) {
            char c = audioId.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                path.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                path.append('_').append((char) ('a' + (c - 'A')));
            } else {
                path.append('_').append(Integer.toHexString(c)).append('_');
            }
        }
        path.append('_').append(Integer.toHexString(audioId.hashCode()));
        return new ResourceLocation("smoothlift", path.toString());
    }

    /**
     * 声音引擎缓存里 {@link SoundBuffer} 的 key：
     * 必须等于 {@code Sound.getPath()}，即 {@code SOUND_LISTER.idToFile(location)}
     * = {@code location.withPath("sounds/" + path + ".ogg")}。
     */
    private static ResourceLocation soundCacheKey(String audioId) {
        ResourceLocation location = soundLocation(audioId);
        return new ResourceLocation(location.getNamespace(), "sounds/" + location.getPath() + ".ogg");
    }

    private static void stopAll(Minecraft mc) {
        if (mc != null && mc.getSoundManager() != null) {
            for (EscalatorSoundInstance inst : ACTIVE.values()) {
                mc.getSoundManager().stop(inst);
            }
        }
        ACTIVE.clear();
    }

    /**
     * 内置音频对应的原版声音事件：{@code smoothlift:audio/<key>}，由模组的
     * {@code assets/smoothlift/sounds.json} 注册，文件在 {@code assets/smoothlift/sounds/audio/<key>.ogg}。
     */
    private static ResourceLocation builtinEvent(String audioId) {
        return new ResourceLocation("smoothlift", "audio/" + EscalatorSpeedManager.builtinKey(audioId));
    }

    /**
     * 自定义声音实例：绑定到一个固定的音频ID。
     *
     * <p>内置音频直接走原版：重写 {@code resolve} 时调用 {@code super} 从注册表
     * （sounds.json）里找事件，引擎自己会从模组资源包读 ogg。
     *
     * <p>玩家放进存档的音频必须重写 {@code resolve} 绕过注册表查找，否则原版默认实现
     * 会因为注册表里没有 smoothlift:audio/&lt;id&gt; 而把 sound 置为 EMPTY_SOUND 并返回 null，
     * play() 会直接放弃。
     */
    private static final class EscalatorSoundInstance extends AbstractSoundInstance implements TickableSoundInstance,
            GainManagedSound {
        private final String audioId;
        private final boolean builtin;

        /** 【1.14】本实例首次播放时的游戏时刻，用于淡入（见 {@link #fadeIn}）。 */
        private long startTick = Long.MIN_VALUE;

        EscalatorSoundInstance(String audioId) {
            super(initialLocation(audioId), SoundSource.BLOCKS);
            this.audioId = audioId;
            this.builtin = EscalatorSpeedManager.isBuiltinAudio(audioId);
            this.looping = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = false;
            this.volume = 1.0f;
            this.pitch = 1.0f;
        }

        /** 内置音频用原版事件 ID；存档音频用 {@link #soundLocation} 映射出的安全路径。 */
        private static ResourceLocation initialLocation(String audioId) {
            return EscalatorSpeedManager.isBuiltinAudio(audioId) ? builtinEvent(audioId) : soundLocation(audioId);
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            if (builtin) {
                return super.resolve(soundManager);
            }
            // location 必须带 "smoothlift:" 前缀，否则默认 namespace 是 minecraft:。
            ResourceLocation name = soundLocation(audioId);
            Sound s = new Sound(name.toString(), 1.0F, 1.0F,
                    1, Sound.Type.FILE, false, false, 0);
            this.sound = s;
            // 引擎会从 WeighedSoundEvents.getSound(random) 里挑一个声源；列表为空则无声，
            // 所以必须把 Sound 也加进去（Sound 实现了 Weighted<Sound>）。
            WeighedSoundEvents events = new WeighedSoundEvents(name, null);
            events.addSound(s);
            return events;
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isStopped() {
            return false;
        }

        @Override
        public boolean canPlaySound() {
            return true;
        }

        void setPosition(Vec3 center, float volume) {
            this.x = center.x;
            this.y = center.y;
            this.z = center.z;
            this.volume = volume;
        }
    }
}
