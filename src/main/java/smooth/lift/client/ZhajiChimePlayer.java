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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.HashSet;
import java.util.Set;

/**
 * 【09-30】闸机（MTR Ticket Barrier）提示音的**播放端**：把 MTR 自己播的那声「嘀」
 * 换成玩家配置的素材。
 *
 * <h2>怎么接进 MTR</h2>
 * MTR 过闸的声音在 {@code TicketSystem.passThrough} 里发：
 * {@code World.playSound(null, <闸机那一格>, SoundEvents.TICKET_BARRIER|TICKET_BARRIER_CONCESSIONARY,
 * SoundCategory.BLOCKS, 1F, 1F)}（已反汇编核实：坐标就是闸机方块的位置，
 * 由 {@code Level.playSound(..., BlockPos, ...)} 转成**方块中心**）。
 * 那是一个**服务端**调用，客户端只是收到一个普通的声音包 —— 所以这里**不改 MTR**，
 * 而是在**引擎侧**拦：{@link SoundManager#play} 一看到
 * {@code mtr:ticket_barrier} / {@code mtr:ticket_barrier_concessionary}，
 * 就按音源坐标现查那一格的方块：
 * <ul>
 *   <li>不是闸机 → 原样交给原版（别的模组/别的方块的同名声音不受影响）；</li>
 *   <li>是闸机 → 读那一侧（in/out）的配置：
 *     <ul>
 *       <li>{@code default} → 原样交给原版（= 用户要的「跟 MTR 内置」）；</li>
 *       <li>{@code off} → **吃掉**这一声（=「不播」）；</li>
 *       <li>其它 → **吃掉**原声，改播玩家导入的 .ogg（音量 1~1000）。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>★ 为什么在引擎侧拦、而不是 mixin MTR：MTR 在 {@code fabric.mod.json} 里只是
 * {@code suggests}（本模组**编译期不认识它**，见 {@code MtrLiftAccess} 的说明），
 * 而音源坐标带上来的方块身份已经足够判定进/出站 —— 不需要引用任何 MTR 类型，
 * 也天然覆盖 MTR3 / MTR4 两代（注册名那一组两代一致）。
 *
 * <p>★ 音量上限要**成对**放开，而且**这条实例必须是 Tickable 的**（见 {@link ZhajiSoundInstance}）：
 * ① 实例实现 {@link GainManagedSound} ⇒ {@code SoundEngineVolumeMixin} 不再把
 * {@code calculateVolume} 夹到 1.0；② 开播时把该 OpenAL 源的 {@code AL_MAX_GAIN} 抬到 10×。
 *
 * <p>★★【09-30 续】为什么「音量超过 100 调不动」的根因就在这里 —— 不是没放开上限，而是**放开的时机**：
 * {@code SoundEngine.play} 把「建源 + 写 AL_GAIN」与我们的「抬 AL_MAX_GAIN」排在同一条通道队列里，
 * 顺序上**它先、我们后**（javap 实测：play 里先 {@code instanceToChannel.put} 再
 * {@code handle.execute(建源)})。而非 Tickable 的实例，引擎**只在开播那一刻**写一次 AL_GAIN，
 * 于 OpenAL 侧被当时的 {@code AL_MAX_GAIN = 1.0} 压回 1.0×，之后再抬上限也补不回来
 * （Attribution：{@code SoundEngine.tickNonPaused} 只对 {@code tickingSounds} 里的实例每 tick
 * 重写 volume，见 javap offset 115~187）。⇒ 本类实例实现 {@code TickableSoundInstance}，
 * 引擎每 tick 用「已经把上限抬到 10×」的状态重写一次 AL_GAIN，>100 才真的生效。
 *
 * <p>★ 距离衰减**沿用原版**（{@code Attenuation.LINEAR}，与 MTR 自己播法完全一致）——
 * 用户没点名闸机要有可调射程，所以不做「整条一串」那种广播模型（那是屏蔽门的 55 格问题）。
 *
 * <p>★【09-30 续】按**组**取值：玩家配的是「石斧右键的那一组闸机」（见 {@code ZhajiChain}），
 * 播放时用音源坐标算出组锚点，先查这一组、没设过再回落维度默认层。
 */
public final class ZhajiChimePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private ZhajiChimePlayer() {
    }

    /** MTR 过闸的两个声音事件（已从 {@code org.mtr.mod.SoundEvents} 反汇编核实）。 */
    private static final String MTR_NAMESPACE = "mtr";
    private static final String PATH_BARRIER = "ticket_barrier";
    private static final String PATH_BARRIER_CONCESSIONARY = "ticket_barrier_concessionary";

    /** 音量换算基准：100 = 原始音量（1.0×），1000 = 10×。 */
    private static final float VOLUME_BASE = EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;

    /**
     * 自有实例的**占位事件**名 —— 随便一个合法的 smoothlift 事件即可。
     *
     * <p>★ 绝不能拿 {@code mtr:ticket_barrier} 当占位：{@link #intercept} 判的就是这个 location，
     * 我们自己的那一句 {@code mc.getSoundManager().play(inst)} 会再次走进 Mixin ⇒ 无限递归。
     * 真正播什么由 {@link ZhajiSoundInstance#resolve} 决定（它总是返回注入缓存里那一段）。
     */
    private static final ResourceLocation PLACEHOLDER_EVENT =
            new ResourceLocation("smoothlift", "audio/zhaji");

    /** 已经因为「配置了自定义素材但播不出来」警告过的素材名（每个只喊一次，别刷日志）。 */
    private static final Set<String> warnedMissing = new HashSet<>();

    /**
     * 拦一声 MTR 过闸声。
     *
     * @return true = 已经处理掉了（{@link SoundManager#play} 应当不再交给原版）
     */
    public static boolean intercept(SoundInstance instance) {
        if (instance == null || instance.isRelative()) {
            return false;
        }
        if (instance instanceof ZhajiSoundInstance) {
            // ★ 我们自己播出来的那一声 —— 再拦一次就是无限递归（见 PLACEHOLDER_EVENT 的说明）。
            return false;
        }
        if (!isTicketBarrierSound(instance.getLocation())) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return false;
        }
        // 音源坐标 = 方块中心（MTR 走的是 Level.playSound(..., BlockPos, ...) 那条重载）。
        // 【1.18.2 移植】1.20.4 的 BlockPos.containing(double,double,double) 在 1.18.2 不存在，改用 Mth.floor。
        BlockPos pos = new BlockPos(Mth.floor(instance.getX()), Mth.floor(instance.getY()), Mth.floor(instance.getZ()));
        if (!level.isLoaded(pos)) {
            return false; // 那一格还没加载：宁可让原声照播，也不要凭猜判方向
        }
        BlockState state = level.getBlockState(pos);
        String which = SmoothLift.zhajiWhichOf(state);
        if (which == null) {
            return false; // 同名声音但不是闸机（别的模组 / 别的方块）→ 交给原版
        }
        // 【09-30 续】「一组闸机」的身份：从音源这一格洪水填充整段、取段内最小 asLong 当锚点。
        //   同一段的同一功能闸机共用一个键 ⇒ 石斧界面配一组、这一段全组跟着变。
        long groupKey = ZhajiChain.anchorOf(level, pos);
        String audioId = EscalatorSpeedManager.getZhajiToneAudio(level, which, groupKey);
        if (EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(audioId)) {
            return false; // 「跟 MTR 内置」= 原声照播
        }
        if (EscalatorSpeedData.ZHAJI_TONE_OFF.equals(audioId)) {
            return true; // 「不播」= 吃掉这一声
        }
        int volume = EscalatorSpeedManager.getZhajiToneVolume(level, which, groupKey);
        if (play(mc, audioId, pos, volume)) {
            return true; // 自定义素材播出去了 → 原声不要重复响
        }
        // 素材取不到 / 解码失败 → 退回原声（宁可响内置那声，也不要一片死寂）
        return false;
    }

    /** 这一声音到底是 MTR 的过闸声吗？只认 namespace {@code mtr} + 两个精确路径。 */
    private static boolean isTicketBarrierSound(ResourceLocation location) {
        if (location == null || !MTR_NAMESPACE.equals(location.getNamespace())) {
            return false;
        }
        String path = location.getPath();
        return PATH_BARRIER.equals(path) || PATH_BARRIER_CONCESSIONARY.equals(path);
    }

    /**
     * 播一声自定义闸机提示音。
     *
     * @return false = 这段素材这一刻播不出来（没同步到 / 不存在 / 解码失败），调用方应退回原声
     */
    private static boolean play(Minecraft mc, String audioId, BlockPos pos, int volume) {
        if (audioId == null || audioId.isEmpty()) {
            return false;
        }
        if (!EscalatorAudioPlayer.injectAudio(mc, audioId)) {
            if (warnedMissing.add(audioId)) {
                LOGGER.warn("[SmoothLift/Zhaji] 闸机提示音「{}」没法播放（存档里没这段音频、或解码失败），"
                        + "这一声退回 MTR 内置音", audioId);
            }
            return false;
        }
        // 占位事件用闸机那一侧的「内置事件」不行（闸机没有内置素材），随便给一个合法事件即可 ——
        // 反正 resolve() 被 injectedId 接管，音频从注入缓存里取。
        float gain = volume / VOLUME_BASE;
        ZhajiSoundInstance inst = new ZhajiSoundInstance(audioId, pos, gain);
        mc.getSoundManager().play(inst);
        raiseMaxGain(mc, inst);
        return true;
    }

    /**
     * 把该 OpenAL 源的 {@code AL_MAX_GAIN} 抬到 {@link EscalatorAudioPlayer#MAX_GAIN}（10×）。
     *
     * <p>与 {@code PsdChimePlayer.raiseMaxGain} 同一手法，但**调用时机不同**：
     * 这里除了开播那一刻，还在 {@link ZhajiSoundInstance#tick()} 里**每 tick 再来一次** ——
     * 因为开播那一刻我们那次抬升排在引擎「建源 + 写 AL_GAIN」**之后**，
     * 而闸机提示音是一次性短音、引擎只在开播时写一次 AL_GAIN ⇒ 光靠开播那一次，
     * 有效增益已经被 1.0 压住了（见类注释里【09-30 续】那段）。
     * 每 tick 抬一次是幂等的（该属性按源保留），代价可忽略。
     */
    private static void raiseMaxGain(Minecraft mc, ZhajiSoundInstance inst) {
        SoundManager manager = mc.getSoundManager();
        if (manager == null) {
            return;
        }
        SoundEngine engine = manager.soundEngine;
        if (engine == null) {
            return;
        }
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
        if (handle != null) {
            handle.execute(ch -> AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN));
        }
    }

    /** 断开世界时清掉「已警告」集合（换存档后同一段素材要能重新警告一次）+ 组锚点缓存。 */
    public static void onDisconnect() {
        warnedMissing.clear();
        // 组锚点是**世界相关**的（方块位置），换世界/换维度必须重算，别信旧值。
        ZhajiChain.clear();
    }

    /**
     * 一声自定义闸机提示音的播放实例：**一次性**（{@code looping = false}）、**可 tick**。
     *
     * <p>衰减用**原版默认**（{@link SoundInstance.Attenuation#LINEAR}）—— 与 MTR 自己播那声
     * 完全同一条距离曲线，所以「换了个音色」不会顺手把「多远还能听见」也改掉。
     * 素材有确定长度，播完引擎会自己回收通道（{@code tickNonPaused} 扫 {@code instanceToChannel}，
     * 通道停了就把它从表和 {@code tickingSounds} 里一并摘掉 —— javap 实测 offset 289~435）。
     *
     * <h2>★【09-30 续】为什么必须实现 {@link TickableSoundInstance}</h2>
     * <b>这就是「音量超过 100 调不动」的根因所在</b>：引擎只对 {@code tickingSounds} 里的实例
     * 每 tick 重写 volume（{@code SoundEngine.tickNonPaused}，javap offset 115~187）；
     * 非 Tickable 的实例，AL_GAIN 只在开播那一刻写一次，而那一刻 {@code AL_MAX_GAIN} 还是 1.0
     * （我们的抬升排在引擎的 execute 之后）⇒ 有效增益被压回 1.0×。
     * 实现本接口之后，{@link #tick()} 里每 tick 抬一次上限、引擎每 tick 重写一次 AL_GAIN，
     * 界面里的 1000（= 10×）才真的放大。
     *
     * <p>实现 {@link GainManagedSound} 是为了让 {@code SoundEngineVolumeMixin} 认出「这是我方声音」，
     * 从而允许 &gt;1 的增益（见类注释）。
     */
    private static final class ZhajiSoundInstance extends AbstractSoundInstance
            implements TickableSoundInstance, GainManagedSound {

        /** 注入进引擎缓存的那段素材的 ID（= 存档音频名）；{@link #resolve} 靠它算缓存 key。 */
        private final String injectedId;

        ZhajiSoundInstance(String injectedId, BlockPos pos, float volume) {
            // ★ 占位事件用 smoothlift 自己的（见 PLACEHOLDER_EVENT 那段：
            //   用 mtr:ticket_barrier 会让自己播的这一声再被 Mixin 拦住 ⇒ 无限递归）。
            //   真正播什么由下面的 resolve() 决定。
            super(PLACEHOLDER_EVENT, SoundSource.BLOCKS);
            this.injectedId = injectedId;
            this.looping = false;
            this.relative = false;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            this.volume = volume;
            this.pitch = 1.0f;
            // 方块中心（与 MTR 播原声时的坐标口径一致）
            this.x = pos.getX() + 0.5;
            this.y = pos.getY() + 0.5;
            this.z = pos.getZ() + 0.5;
        }

        /**
         * 每 tick 抬一次该源的 {@code AL_MAX_GAIN}。
         *
         * <p>为什么不能只在开播时抬一次：见类注释【09-30 续】那段（引擎写 AL_GAIN 在前、
         * 我们抬上限在后，一次性实例没有第二次机会）。幂等、每 tick 一次的代价可忽略。
         */
        @Override
        public void tick() {
            raiseMaxGain(Minecraft.getInstance(), this);
        }

        /** 素材播完由引擎自己回收（不停它、也不提前掐）：永远返回 false。 */
        @Override
        public boolean isStopped() {
            return false;
        }

        /** 音频从注入缓存里取（仿 {@code PsdChimePlayer.PsdMusicInstance.resolve}）。 */
        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
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
