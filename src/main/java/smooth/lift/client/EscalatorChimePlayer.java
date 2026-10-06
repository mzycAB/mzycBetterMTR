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
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 【1.15】香港式扶梯「视障人士提升音」。
 *
 * <p>香港地铁的自动扶梯会在两个端头各放一路提示音，提示视障乘客扶梯在哪、往哪个方向走：
 * <ul>
 *   <li><b>上客端（进扶梯那一头）</b>：<b>急促</b>的「咔啪」声（约 **1/10 秒一次 = 10 Hz**），催你尽快踏上；</li>
 *   <li><b>落客端（出扶梯那一头）</b>：<b>缓慢</b>的「咔啪」声（约 **1 秒一次 = 1 Hz**），提示前面就是出口。</li>
 * </ul>
 * <p><b>【1.23】不是「滴滴」电子音，是「咔哒」敲击声</b>：早先用正弦电子「滴滴」是错的 ——
 * 真实港铁扶梯提示音是一串敲击感的「咔/哒」（freesound 388412「Escalator entrance with audible guide」可证）。
 * <p><b>【1.25】按用户听真机的规格修正速率与音色</b>：1.23 那版按录音反推成 7 Hz / 3.5 Hz 的木鱼式敲击，
 * 用户听后给了**真机规格**：入口 **1/10 秒一次（10 Hz）**、出口 **1/3 秒一次（3 Hz）**，音色像**棘轮运行**。
 * <p><b>【1.26】再按用户听感修正</b>：出口改为 **1/2 秒一次（2 Hz）**；音色从「棘轮拨齿」改成
 * **「细金属片弹的‘咔’+‘啪’融合」、更清脆**（2.4~5.2 kHz、衰减 2~6 ms、瞬态 2500~9000 Hz 更冲）。
 * <p><b>【1.27】按用户第三轮听感修正</b>：**去掉声音里的「咚」**（1.26 那个 1500 Hz 低频躯体就是「咚」，
 * 听起来还有点沉闷）—— 全部分音改为 **≥2400 Hz、衰减 1.5~4 ms**，只留金属余韵 + 高频「啪」；
 * 出口再改为 **1 秒一次 = 1 Hz**（2 下/2 s 循环）。速率 10 / 1 Hz 都能整除 2.0 s → 无缝。
 * 改的只是两个 OGG 资源，Java 无需改动。
 *
 * <p><b>【1.31】速率变成可调了</b>（{@code /futihelpspeed in|out <Hz>}）。
 * 两个默认值**没变**（入口 10、出口 1），所以旧存档 / 没设置过的扶梯听起来与 1.27 完全一样。
 * 实现方式是「**换素材 + 调 pitch**」：原版 {@code SoundEngine.calculatePitch} 把 pitch 夹在
 * <b>[0.5, 2.0]</b>（字节码：{@code Mth.clamp(getPitch(), 0.5F, 2.0F)}），一个素材只覆盖
 * 两个八度的**速率**（= 4 倍），所以用若干素材的 [0.5×, 2×] 区间首尾相接，合起来覆盖
 * {@link EscalatorSpeedData#HELP_SPEED_MIN}~{@link EscalatorSpeedData#HELP_SPEED_MAX}
 * （见 {@link #CHIME_1HZ} 那一组常量、{@link #chimeEventFor}、{@link #chimePitchFor}）。
 * 跨素材档时重建实例，同档内只改 pitch 字段（引擎每 tick 都读）。
 * 和开关 / 音量 / 范围一样，速率是**可变条件**，一律逐 tick 现算 + 代次缓存，**绝不进 {@link #describe}**。
 * 注意这是**第四个**作用在同一路提示音上的维度：{@code /futihelp}（响不响）、
 * {@code /futihelploud}（多响）、{@code /futihelpround}（多远还听得见）、{@code /futihelpspeed}（多快）。
 *
 * <p><b>【1.34】两处按用户反馈改的</b>：
 * <ol>
 *   <li><b>速率上限 20 → 100 Hz</b>：素材从三个（1 / 4 / 10 Hz）扩到五个，多出
 *       <b>25 Hz</b>（覆盖 12.5~50）与 <b>50 Hz</b>（覆盖 25~100），于是
 *       1~100 Hz 每一档都**真的**到得了（不会像「只加档位不加素材」那样悄悄卡在 20 Hz）。
 *       素材由 {@code escalator_sounds/_make_chime.py} 生成（CC0，自合成）。</li>
 *   <li><b>开播不再淡入</b>：原来照搬了运行底噪那套「头 6 tick 从 -80 dB 指数淡入」
 *       （见 {@link EscalatorAudioPlayer#FADE_IN_TICKS}），对**连续**底噪是对的，对**脉冲式**
 *       提示音是错的 —— 起点 -80 dB 意味着**触发后先是约 0.3 秒（10 Hz 档）/ 约 1 秒
 *       （1 Hz 档，第一下就落在淡入窗口里）的「空白」**，用户听感就是「音频前面留了空白」。
 *       现在开播**当刻就用目标增益**（不再走 {@link #fadeInGain}），第一个「咔啪」立刻可闻。
 *       不会有爆音：底噪需要淡入是因为它一开播是满音量再被纠正，而这里在 {@code play()} **之前**
 *       就把音量摆成了目标值；提示音本身还带 0.4 ms 起音斜坡（文件层面），起点不会是阶跃。</li>
 * </ol>
 *
 * <p><b>【1.39】提示音可以换成玩家导入的 OGG 了</b>（{@code /futihelpmusic} + 提示音选择界面）。
 * 数据上是与运行底噪**完全对称**的第二套「音频绑定」
 * （{@code defaultHelpAudioIn/Out} / {@code blockHelpAudioIn/Out}），
 * 【1.28】音频隔离后两者分文件夹存放：底噪读 {@code <存档>/MBM_Audio/futi/music/}、提示音读
 * {@code <存档>/MBM_Audio/futi/help/}，各自只列自己分类导入过的音频。取值三种：
 * <ul>
 *   <li>{@link EscalatorSpeedData#HELP_AUDIO_DEFAULT}（{@code default}，初始值，旧存档缺字段也是它）
 *       = **模组原来的提示音**，也就是 1.15~1.34 那套「五档素材 + 速率分档」，行为**逐字节不变**；</li>
 *   <li>{@link EscalatorSpeedData#HELP_AUDIO_OFF}（{@code off}）= **这一头**（或整个默认层）**不播提示音** ——
 *       比 {@code /futihelp off} 更细：可以只让某一条扶梯的某一头哑掉，而不动其它；</li>
 *   <li>音频库里的文件名 = 在这一头**循环播放**这段自定义音频。</li>
 * </ul>
 * ★ <b>速率只对 {@code default} 生效</b>：自定义音频按**原速**循环播（pitch 恒 1.0）——
 * 速率是靠「换素材 + 调 pitch」实现的（见上面 1.31 那段），而素材是玩家自己的，
 * 没法按 1/4/10/25/50 Hz 分档。音量（{@code /futihelploud}）与范围（{@code /futihelpround}）
 * 对两种选择都照常生效。
 * 于是同一路提示音上现在有**五个独立维度**：开关 / 音量 / 范围 / 速率（只对 default）/ 音乐。
 * 「音乐」同样是**可变条件**，一律逐 tick 现算 + 代次缓存（{@link #helpAudioIds}），**绝不进 {@link #describe}**。
 *
 * <p><b>【1.41】「音乐」也分成进 / 出两头了</b>（{@code /futihelpmusic in|out}，形状与
 * {@code /futihelpspeed in|out} 完全一致）：上面每一条语义都**按端头各来一份** ——
 * 上客端可以放一段「进站」音、落客端放另一段，两边互不影响；{@code off} 也跟着细到了单头
 * （只让某一头不播，另一头照常响，见 {@link #updateEnd} / {@link #stopEnd}）。
 * 取值与缓存方式不变：两头一起查、共用一个代次（{@link #helpAudioIds}）。
 * ★ <b>旧存档兼容</b>：1.39 的单一字段在 {@code EscalatorSpeedData#fromTag} 里被**同时**
 * 当作两头初值读入，所以旧存档升级上来听起来与 1.39 **逐字节一致**。
 *
 * 两条音源各**只装在扶梯首、尾那两块扶梯方块上**（各自是「同一位置左右两列」算一块），
 * 所以声音从哪个方向来，就等于扶梯的哪一头在响 ——
 * 向上的扶梯：**最下面第一块**放急促（上客端）、**最上面最后一块**放缓慢（落客端）。
 * <b>整条扶梯中间是不放音源的</b>（对比：{@link EscalatorAudioPlayer} 的运行底噪才是整条一起响）。
 *
 * <p><b>【1.17】射程默认 4 格（不是 16）</b>：本提示音是**装在单个扶梯方块上**的点状音源，默认半径只有 4 格。
 * 早先照抄了扶梯底噪那套 16 格，结果**坐一整程都只听得见上客端那一路急促咔咔**，端头导向的意义全没了。
 * 请分清这两个东西（{@link EscalatorAudioPlayer} 的注释里也有同样的说明）：
 * <ul>
 *   <li><b>无障碍提示音（本类）</b>：装在**首/尾那一块**扶梯方块上，默认射程 <b>4 格</b>，跟块走；</li>
 *   <li><b>扶梯运行底噪（{@link EscalatorAudioPlayer}）</b>：整条扶梯一起响，默认射程 <b>16 格</b>，
 *       按到**整条扶梯**的距离衰减 —— 坐一整程都听得见才是对的。</li>
 * </ul>
 * <p><b>【1.24】两个射程都变成可调了</b>（{@code /futihelpround} 改本类、{@code /futiround} 改底噪），
 * 但**默认值仍是 4 : 16 这一对**；上面的「点状 vs 整条」语义、以及下面这条短扶梯行为都不变。
 * 把提示音射程调大以后两端重叠会更多 —— 那是玩家自己的选择，代码不再额外约束。
 * 短扶梯（首尾间距 &lt; 8 格）上两端各 4 格的覆盖本来就会重叠，站在中间同时听到两头 —— 这是**故意保留**的行为
 * （真实世界的短扶梯也这样），别为了「中段必须静音」去收窄半径。
 *
 * <p><b>范围是「可变条件」，绝不能进缓存</b>（见坑 17）：射程由网络同步驱动、随时会变，
 * 所以它一律在每 tick 路径上现算（{@link #helpRange}，带代次缓存），
 * 并且 {@link #currentTarget} 的 TTL 缓存键里也带上了范围代次 —— 否则玩家站在扶梯上不动时改了射程也不生效。
 *
 * <p><b>哪一头是「上客端」</b>：取决于扶梯在往哪个方向跑。MTR 的扶梯方块有两个布尔属性：
 * <ul>
 *   <li>{@code facing} —— 扶梯的运行轴。{@code BlockEscalatorStep.onEntityCollision2} 就是沿这条轴
 *       给实体 {@code addVelocity}（{@code facing=NORTH} 时给 z 分量、{@code EAST} 时给 x 分量），
 *       所以它一定是「阶梯前进的方向轴」；</li>
 *   <li>{@code direction} —— {@code direction=true} 沿 {@code +facing} 送人，{@code false} 沿 {@code -facing} 送人
 *       （同一段字节码里：{@code facing=NORTH} 时 {@code true} 给 {@code z=-0.1}，{@code facing=EAST} 时 {@code true} 给 {@code x=+0.1}）。</li>
 * </ul>
 * 而 MTR 的斜坡永远沿 {@code +facing} 往上爬（见 {@link #describe} 里从
 * {@code BlockEscalatorBase.getOrientation} 推出来的那张判定表），所以
 * {@code direction=true} 就是**上行扶梯**、{@code false} 就是下行扶梯。
 * 于是：上行扶梯在**低那头**上客，下行扶梯在**高那头**上客。
 *
 * <p>两端的位置不读 {@code orientation} 属性，而是把整条扶梯的阶梯块按**高度**分成两组：
 * 最低那组 = 「最下面」、最高那组 = 「最上面」—— 这正是需求原文的规则，且左右两列同高、
 * 天然落在同一组里，不会再把「左右两列」误当成两个端头（Y 坐标是白纸黑字的物理事实，
 * 不用去猜 MTR 的资源命名）。整条同高的平层步道（Y 分不开）才退回「沿运行轴投影取两端」，
 * 那条轴**优先用 {@code facing}**（有字节码依据：MTR 就是沿 facing 铺阶梯、沿 facing 送人），
 * 只有当 {@code facing} 明显比另一条水平轴窄时才弃用它（那说明它指的是扶梯的**宽度**方向）。
 *
 * <p><b>为什么音频资源必须是单声道</b>：MC 的定位音靠 {@code Channel.setSelfPosition}
 * （= {@code AL_POSITION}）+ {@code setRelative(false)}（= {@code AL_SOURCE_RELATIVE=false}）做左右声像，
 * 而 OpenAL 只对**单声道**缓冲真正做声像；立体声缓冲会被当成「已经混好的成品」而不再定位。
 * 存档里那条 {@code subway_escalator.ogg} 是 2 声道却仍然「能用」，是因为它按环境音用
 * （{@code Attenuation.NONE} + 四面八方的底噪），本来就不需要方向。
 * 本功能的两条 ogg 是**单声道**，由 {@code escalator_sounds/_make_chime.py} 生成。
 *
 * <p><b>为什么自己算音量</b>：若交给原版那套「到声源 16 格线性衰减」，站在扶梯中段就会
 * 两端一样响、混成一团，判不出方向。这里关掉 OpenAL 的距离增益
 * （{@code Attenuation.NONE} → {@code AL_DISTANCE_MODEL=AL_NONE}，注意这只关**增益**，
 * 声源坐标依旧生效、依旧做声像），改由
 * {@link #gain(double, double)} 按「到**该端头**的距离」逐端算（默认 4 格内**按比例线性**衰减到 0，
 * ★【10-03】原为平方，用户点名改为按比例），两端各自衰减。
 *
 * <p><b>静音条件</b>（两个，任一成立就不响，都在 {@link #onClientTick} 里**逐 tick 现算**）：
 * <ol>
 *   <li>扶梯 {@code status=false}（被停掉）—— 和真站里扶梯停了就不响一致；</li>
 *   <li>【1.16】无障碍提示音被关掉 —— 由 {@link EscalatorSpeedManager#isHelpEnabled} 判定，
 *       两层开关：维度默认（{@code /futihelp on|off}）+ 每条扶梯单独设置（石斧设置界面里的开关）。
 *       默认是<b>开</b>，所以 1.15 的旧存档升级上来照旧一直响。</li>
 * </ol>
 * <b>这两个条件绝不能放进 {@link #describe}</b>：那边按 {@code anchor} 缓存几何结果，
 * 玩家站在扶梯上不动时 anchor 不变 → describe 永不再跑 → 开关就「关不掉」（1.16 真的踩了这个坑）。
 *
 * <p><b>【1.18】音量</b>：在距离衰减之上再乘一个「提示音音量百分比 / 100」
 * （{@link EscalatorSpeedManager#getHelpVolume}，两层：{@code /futihelploud <音量>} 维度默认 +
 * 石斧界面里的「提示音音量」输入框）。默认 100 = 原始音量，所以 1.17 的旧存档升级上来音量不变。
 * 查询同样走「anchor + 代次」缓存（见 {@link #helpVolumeFactor}），与开关同一套，绝不放进 {@link #describe}。
 * <b>注意这是「提示音」的音量，不是「扶梯运行底噪」的音量</b>（后者见
 * {@link EscalatorAudioPlayer} 与 {@code /futiloud}），两者互不影响。
 *
 * <p><b>【1.20】音量范围真的能到 1000 了</b>：1.18 只是把系数乘上去，可原版引擎会把实例音量夹到
 * [0,1]（{@code SoundEngine.calculateVolume}），而且 OpenAL 还会把**源增益**夹到 {@code AL_MAX_GAIN}
 * （默认 1.0）—— 两道都在，表现就是「提示音音量调到 100 以上完全没变化」。
 * 现在 {@link ChimeInstance} 实现 {@link GainManagedSound}（让
 * {@code SoundEngineVolumeMixin} 放行到 {@link EscalatorAudioPlayer#MAX_GAIN}），
 * 开播时再把该源的 {@code AL_MAX_GAIN} 抬到同一个上限，两道一起过，1~1000 全部生效。
 *
 * <p><b>【1.22】「上客端一直响到玩家走出扶梯、还和落客端重叠」的根因：链条只展开了一半。</b>
 * 症状是**只有上客端那一路停不下来**（落客端那路正常）。查日志（{@code [SmoothLift/Chime] 端头定位}）
 * 会发现：同一趟扶梯上，随着玩家往上走，日志里的「阶梯块 N 个」在**单调变小**
 * （24 → 20 → 18 → …… → 4），「高度」的下界也跟着玩家一起抬（19 → 20 → 21 → …… → 28）
 * —— 也就是**链只包含玩家脚下这一段和它上面那一段**，下半条链根本没展开。
 * 于是「按高度分组」的**最低那头** = 玩家脚下，上客端音源跟着玩家跑、距离永远 < 4 格、永远不满足
 * 停播条件（{@code gain(dist) <= 0}），直到玩家走出扶梯；走到接近顶部时它和真正固定在顶部的那路
 * 落客端音源相距不到 4 格，两路就叠在一起了。
 *
 * <p>链条为什么只展开一半：{@link EscalatorUtil#collectChain} 的沿轴游走把护栏 / 侧板
 * （MTR 的 {@code escalator_side}，类名同样带 escalator）和阶梯一视同仁，而护栏就贴在阶梯斜上方、
 * 还正好落在「同一高度」这个**第一个**候选位上 —— 从扶梯中段往**低处**走时，游走被同高度的护栏抢走后
 * 就沿着护栏滑出去了。修法见 {@link EscalatorUtil#collectStepChain}：游走**只认阶梯块**。
 * 修好后同一趟扶梯的日志会始终是同一行（例：始终「阶梯块 24 个、高度 19~28」），
 * 两端坐标不再随玩家移动 —— 这正是自查的判据。
 *
 * <p>入口由 {@link SmoothLiftClient} 注册：{@link #onClientTick(Minecraft)}（每 client tick）、
 * {@link #onDisconnect()}。诊断日志前缀 {@code [SmoothLift/Chime]}：开关被关掉 / 恢复时会各打一条。
 */
public final class EscalatorChimePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 听得到提示音的**默认最远距离**（格）= {@link EscalatorSpeedData#DEFAULT_HELP_ROUND} = **4**，
     * 而且算的是**到某一个端头那对左右并列的扶梯方块**的距离。
     *
     * <p><b>【1.24】现在可调了</b>（{@code /futihelpround}，1~128 格），所以实际判定一律走
     * {@link #helpRange}；这个常量只是「镜像还没到」时的兜底锚点。
     *
     * <p><b>这是提示音与「扶梯运行底噪」最关键的区别</b>（1.17 按反馈改小，1.24 起可调）：
     * <ul>
     *   <li><b>无障碍提示音（本类）</b>：只装在**首、尾两块**扶梯方块上的提示音，
     *       默认作用范围只有 **4 格** —— 站到下客端三四格开外就听不见了。正因为它是点状的、局部的，
     *       才有「哪一头在响 = 该往哪边走」的导向意义。</li>
     *   <li><b>扶梯运行底噪（{@link EscalatorAudioPlayer}）</b>：整条扶梯一起响，
     *       默认作用范围 **水平 10 / 垂直 5 格**（★【10-03】由 16 改小）、按**到整条扶梯**的距离衰减
     *       —— 那是「扶梯在运行」的环境音，本来就应该坐一整程都听得见。</li>
     * </ul>
     * 早先这里错用了 16 格（照抄底噪那套），结果**坐一整程都只听得见上客端那一路急促滴答**，
     * 端头导向的意义没了。**改回 4 格**。
     *
     * <p>★【10-03】拆双维：水平（xz）默认 4、垂直（y）默认 5（见 {@link #DEFAULT_RANGE_Y}），
     * 两维各算一次线性衰减、取较小；任一维越界即静音。
     *
     * <p><b>4 格就是默认的全部规则，不再额外收窄</b>：扶梯长于 8 格时两端天然不相交、中段安静；
     * 扶梯短于 8 格时站在中间会同时听到两头的声音 —— 这**正是想要的**（真实世界的短扶梯也是如此）。
     * 「只在首尾两块方块上放音源」+「射程范围内」两条已经足够，别再为「中段必须静音」去砍半径。
     */
    public static final double DEFAULT_RANGE = EscalatorSpeedData.DEFAULT_HELP_ROUND;

    /** ★【10-03】提示音**垂直（y 轴）**默认射程（格）= {@link EscalatorSpeedData#DEFAULT_HELP_ROUND_Y} = 5。 */
    public static final double DEFAULT_RANGE_Y = EscalatorSpeedData.DEFAULT_HELP_ROUND_Y;

    /**
     * 五个**素材**（都是 2 秒一循环的「咔啪」细金属声，只有速率不同）。选哪个看
     * {@link #chimeEventFor}，pitch 看 {@link #chimePitchFor}。
     *
     * <p><b>为什么要有多个素材而不是一个</b>：原版 {@code SoundEngine.calculatePitch} 把 pitch 夹在
     * <b>[0.5, 2.0]</b>，一个素材只能覆盖 4 倍的速率区间。五个素材的 {@code [0.5×, 2×]} 区间
     * 首尾相接，合起来正好覆盖 {@code /futihelpspeed} 允许的
     * <b>[{@link EscalatorSpeedData#HELP_SPEED_MIN}, {@link EscalatorSpeedData#HELP_SPEED_MAX}] Hz</b>：
     * <pre>
     *   1 Hz × [0.5, 2] = [0.5,  2]
     *   4 Hz × [0.5, 2] = [  2,  8]
     *  10 Hz × [0.5, 2] = [  5, 20]
     *  25 Hz × [0.5, 2] = [ 12.5, 50]
     *  50 Hz × [0.5, 2] = [ 25, 100]
     * </pre>
     * 想再快就得再加素材（{@link #chimeEventFor} 与 {@link #chimePitchFor} 两处**必须同步改**）。
     *
     * <p>默认的「入口 10 Hz、出口 1 Hz」正好等于 10 Hz / 1 Hz 素材的原始速率（pitch = 1.0），
     * 所以**默认档与 1.25~1.27 真机定版的声音逐字节一致**，不是「另一个音」。
     * 同理 4 Hz 档的 pitch 也是 1.0，和 1.31 一致。
     *
     * <p>音色由 {@code escalator_sounds/_make_chime.py} 生成，见那边与
     * {@code AUDIO-CREDITS.txt}（CC0，自合成）。
     */
    private static final ResourceLocation CHIME_1HZ =
            new ResourceLocation("smoothlift", "audio/escalator_chime_alight");

    /** 【1.31】4 Hz 素材（2 秒 8 下），覆盖 2~8 Hz。 */
    private static final ResourceLocation CHIME_4HZ =
            new ResourceLocation("smoothlift", "audio/escalator_chime_rate4");

    /** 10 Hz 素材（2 秒 20 下），覆盖 5~20 Hz。1.25 起「入口 1/10 秒一次」用的就是它。 */
    private static final ResourceLocation CHIME_10HZ =
            new ResourceLocation("smoothlift", "audio/escalator_chime_board");

    /**
     * 【1.34】25 Hz 素材（2 秒 50 下），覆盖 12.5~50 Hz。
     *
     * <p>加上它和 {@link #CHIME_50HZ} 才让「速率上限提到 100 Hz」**名副其实** ——
     * 只把档位放宽而不加素材的话，20 Hz 以上会被 pitch 钳制悄悄压回去，
     * 玩家会觉得「调 50 跟调 20 一模一样」。
     */
    private static final ResourceLocation CHIME_25HZ =
            new ResourceLocation("smoothlift", "audio/escalator_chime_rate25");

    /** 【1.34】50 Hz 素材（2 秒 100 下），覆盖 25~100 Hz。到了这一档，听感上已是连续「嗡」声。 */
    private static final ResourceLocation CHIME_50HZ =
            new ResourceLocation("smoothlift", "audio/escalator_chime_rate50");

    /**
     * 「最近的那条扶梯」的缓存时长（tick）= 0.5 秒。
     * 每 tick 重新展开一次整条扶梯链没必要（链可能上百格），而半秒的滞后在走路速度下察觉不到。
     */
    private static final int TARGET_TTL_TICKS = 10;

    /** 两个端头。一个端头同一时刻最多一个实例。 */
    private enum End {
        BOARD,
        ALIGHT
    }

    private static final Map<End, ChimeInstance> ACTIVE = new EnumMap<>(End.class);

    /** 上一次算出的「最近那条扶梯」的两端头（0.5 秒内直接复用，见 {@link #TARGET_TTL_TICKS}）。 */
    private static Target cachedTarget;
    /** 上一次据以算出 {@link #cachedTarget} 的那块阶梯，用来判断「还是同一条扶梯」，省掉重复展开。 */
    private static BlockPos cachedAnchor;
    private static boolean cachedValid;
    private static long cachedTick;
    private static ResourceKey<Level> cachedDimension;

    /** 无障碍开关的上次查询结果 + 它是基于哪一代客户端镜像算的（见 {@link #helpEnabled}）。 */
    private static BlockPos cachedHelpAnchor;
    private static long cachedHelpGeneration = -1L;
    private static boolean cachedHelpValue = true;

    /** 【1.18】提示音音量的上次查询结果 + 代次（见 {@link #helpVolumeFactor}），缓存策略与开关相同。 */
    private static BlockPos cachedVolumeAnchor;
    private static long cachedVolumeGeneration = -1L;
    private static float cachedVolumeFactor = 1.0f;

    /**
     * 【1.24】提示音**范围**的上次查询结果 + 代次（见 {@link #helpRange}），缓存策略与音量相同。
     * ★【10-03】双维：同一组 anchor / 代次同时缓存**水平（xz）**与**垂直（y）**两份值。
     */
    private static BlockPos cachedRangeAnchor;
    private static long cachedRangeGeneration = -1L;
    private static double cachedRangeValue = DEFAULT_RANGE;
    private static double cachedRangeYValue = DEFAULT_RANGE_Y;

    /** 【1.24】算 {@link #currentTarget} 时用的范围代次：范围一改就让 TTL 缓存立刻作废。 */
    private static long cachedRoundGeneration = -1L;

    /**
     * 【1.31】两个端头**速率**（Hz）的上次查询结果 + 代次（见 {@link #helpRates}），
     * 缓存策略与开关 / 音量 / 范围相同。两头一起查、一起缓存（同一只同步包、同一个代次）。
     */
    private static BlockPos cachedRatesAnchor;
    private static long cachedRatesGeneration = -1L;
    private static Rates cachedRatesValue =
            new Rates(EscalatorSpeedData.DEFAULT_HELP_SPEED_IN, EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT);

    /**
     * 【1.41】这条扶梯**实际生效**的提示音「音乐」ID（进 / 出两头）的上次查询结果 + 代次
     * （见 {@link #helpAudioIds}），缓存策略与开关 / 音量 / 范围 / 速率完全相同。
     */
    private static BlockPos cachedHelpAudioAnchor;
    private static long cachedHelpAudioGeneration = -1L;
    private static AudioIds cachedHelpAudioValue =
            new AudioIds(EscalatorSpeedData.HELP_AUDIO_DEFAULT, EscalatorSpeedData.HELP_AUDIO_DEFAULT);

    /** 上一次「该不该响」的判定结果，只用来在状态翻转时打一条日志（不参与播放逻辑）。 */
    private static boolean lastAudible = true;

    private EscalatorChimePlayer() {
    }

    /**
     * 一条扶梯的两端头位置。
     *
     * @param anchor    算这个结果时用的那块阶梯（= 离玩家最近的阶梯块）。用来逐 tick 回查
     *                  「扶梯有没有被停掉」「提示音开关是不是被关了」——两件事都不能缓存进本结果里。
     * @param boardPos  上客端（进扶梯）**那一对左右并列的扶梯方块**的中心，放急促咔咔
     * @param alightPos 落客端（出扶梯）**那一对左右并列的扶梯方块**的中心，放缓慢咔咔
     * @param debug     定位依据的一句话摘要（阶梯块数、两条水平轴的跨度、选中的运行轴、上行/下行），
     *                  只用来在定位结果**变化时**打一条日志。端头定位一旦出问题，这一行就能
     *                  直接看出是几何选轴错了、还是链条压根没展开全。
     */
    private record Target(BlockPos anchor, Vec3 boardPos, Vec3 alightPos, String debug) {
    }

    /**
     * 【1.31】一条扶梯两头的提示音速率（Hz，每秒响几次）。
     *
     * @param in  进入扶梯（上客端）那一路，默认 10
     * @param out 离开扶梯（落客端）那一路，默认 1
     */
    private record Rates(int in, int out) {
    }

    /**
     * 【1.41】一条扶梯两头**该放哪段声音**（提示音「音乐」ID）。
     *
     * <p>与 {@link Rates} 同样「两头一起查、一起缓存」（同一只同步包、同一个代次）。
     * 取值范围三种：{@link EscalatorSpeedData#HELP_AUDIO_DEFAULT}（内置素材 + 速率分档）、
     * {@link EscalatorSpeedData#HELP_AUDIO_OFF}（**这一头**不播）、或音频库里的文件名。
     *
     * @param in  进入扶梯（上客端）那一路
     * @param out 离开扶梯（落客端）那一路
     */
    private record AudioIds(String in, String out) {
    }

    /**
     * 【1.39】一个端头**这次该放哪段声音**（由 {@link #helpAudioId} + {@link #sampleFor} 算出来）。
     *
     * <p>两种来源，播放路径不同：
     * <ul>
     *   <li>{@code customId == null} —— 模组内置的「咔啪」**素材**（{@code default}）：
     *       走原版资源包（{@code sounds.json} 里注册过的 {@code smoothlift:audio/...}），
     *       由 {@link #chimeEventFor} 按速率挑素材、{@link #chimePitchFor} 算 pitch；</li>
     *   <li>{@code customId != null} —— 玩家导入的存档音频：走 {@link EscalatorAudioPlayer}
     *       那套「解码注入 + 自己造 {@code Sound} 绕过 sounds.json」的链路，
     *       <b>按原速循环播（pitch 恒 1.0）</b> —— 素材是玩家自己的，没法按 Hz 分档，
     *       所以 {@code /futihelpspeed} 对自定义提示音不生效（音量与范围照常生效）。</li>
     * </ul>
     *
     * @param event    声音事件 ID（内置素材事件 / 自定义音频映射出的安全路径），也是实例重建的判据
     * @param customId 自定义音频文件名；null = 内置素材
     * @param pitch    播放速率（内置素材按速率分档；自定义音频恒 1.0）
     */
    private record Sample(ResourceLocation event, String customId, float pitch) {
    }

    /**
     * 每客户端刻调用：找到离玩家最近的那条扶梯，按「到两个端头各自的距离」更新/启动/停掉两路提示音。
     *
     * <p><b>为什么「该不该响」的两个条件在这里逐 tick 判、而不是塞进缓存的 {@link #describe} 里</b>：
     * {@link #describe} 算的是几何（端头位置），结果按 {@code anchor} 缓存 —— 因为展开整条链不便宜。
     * 而玩家**站在扶梯上不动**时 {@code anchor} 根本不变，于是缓存的几何结果会一直被直接用，
     * {@code describe} 再也不会被调用。所以任何「会随时间变化的条件」放在 describe 里都**永远不会生效** ——
     * 表现就是「开关关不掉」（真的踩过）。这里把两个条件提出来逐 tick 判断：
     * <ol>
     *   <li>扶梯被停掉（{@code status=false}）；</li>
     *   <li>无障碍提示音被关掉（{@code /futihelp off} 或石斧设置界面里的开关）。</li>
     * </ol>
     * 代价是每 tick 一次方块状态读取 + 一次（带代次缓存的）开关查询，可以忽略；
     * 好处是开关**一个 tick 内**就生效。
     */
    public static void onClientTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            lastAudible = true;
            stopAll(mc);
            return;
        }
        Target target = currentTarget(mc);
        if (target == null) {
            lastAudible = true;
            stopAll(mc);
            return;
        }
        if (!isAudibleNow(mc, target.anchor)) {
            if (lastAudible) {
                LOGGER.info("[SmoothLift/Chime] 这条扶梯的提示音已静音（扶梯被停用 或 无障碍提示音开关=关）");
                lastAudible = false;
            }
            stopAll(mc);
            return;
        }
        if (!lastAudible) {
            LOGGER.info("[SmoothLift/Chime] 这条扶梯的提示音恢复播放");
            lastAudible = true;
        }
        Vec3 player = mc.player.position();
        // 【1.18】提示音音量百分比（1~1000 → 0.01~10.0），同样逐 tick 现算（带代次缓存）。
        float volumeFactor = helpVolumeFactor(mc, target.anchor);
        // 【1.24】可闻范围（格，★【10-03】水平默认 4 / 垂直默认 5）也逐 tick 现算：改完不用重进世界。
        double rangeXz = helpRange(mc, target.anchor);
        double rangeY = helpRangeY(mc, target.anchor);
        // 【1.31】两个端头的速率（Hz）同样逐 tick 现算带代次缓存：/futihelpspeed 改完立刻换速度。
        Rates rates = helpRates(mc, target.anchor);
        // 【1.41】这两头**各放什么声音**同样逐 tick 现算带代次缓存
        //（/futihelpmusic in|out 改完立刻换）：default = 内置「咔啪」素材（按速率分档）；
        //   自定义音频 = 原速循环；某一头是 `off` = **只静掉那一头**（另一头照常响，见 updateEnd）。
        AudioIds ids = helpAudioIds(mc, target.anchor);
        updateEnd(mc, End.BOARD, target.boardPos, ids.in(), rates.in(),
                player, "上客端（进入", volumeFactor, rangeXz, rangeY);
        updateEnd(mc, End.ALIGHT, target.alightPos, ids.out(), rates.out(),
                player, "落客端（离开", volumeFactor, rangeXz, rangeY);
    }

    /**
     * 这条扶梯**现在**该不该响：没被停掉（{@code status != false}）且无障碍提示音开关是「开」。
     *
     * <p>★【1.41】这里只管**整条扶梯**层面的两个条件。「**这一头**被设成 {@code off}」是按端头
     * 单独判的 —— 在 {@link #onClientTick} 里逐端拦（见 {@link #updateEnd}），
     * 所以只让某一头不响时，另一头照常响。
     *
     * <p>两个条件都每 tick 现算，绝不做「算一次就缓存到 anchor 不变为止」的处理
     * （见 {@link #onClientTick}）。★ 提示音音乐是**可变条件**，所以它只能出现在这里
     * 和 {@link #helpAudioIds} / {@link #updateEnd} 里，**绝不能塞进按 anchor 缓存的
     * {@link #describe}**（坑 17）。
     */
    private static boolean isAudibleNow(Minecraft mc, BlockPos anchor) {
        if (mc.level == null) {
            return false;
        }
        BlockState state = mc.level.getBlockState(anchor);
        if (!EscalatorUtil.getBooleanProperty(state, "status", true)) {
            return false;
        }
        return helpEnabled(mc, anchor);
    }

    /**
     * 无障碍提示音开关（维度默认 + 每条扶梯单独设置两层）。
     *
     * <p>只有「客户端镜像换代」（收到 HELP_SYNC、或断开连接清镜像）或「换了一条扶梯」时才真的去查，
     * 其余 tick 直接复用 —— 因为 {@link EscalatorSpeedManager#isHelpEnabled} 在链上有单独设置时要展开整条链，
     * 每 tick 都做没必要。代次由 {@link EscalatorSpeedManager#applyClientHelp} 递增，所以开关一变就立刻重查。
     */
    private static boolean helpEnabled(Minecraft mc, BlockPos anchor) {
        long generation = EscalatorSpeedManager.clientHelpGeneration();
        if (anchor.equals(cachedHelpAnchor) && generation == cachedHelpGeneration) {
            return cachedHelpValue;
        }
        cachedHelpAnchor = anchor;
        cachedHelpGeneration = generation;
        cachedHelpValue = EscalatorSpeedManager.isHelpEnabled(mc.level, anchor);
        return cachedHelpValue;
    }

    /**
     * 【1.18】无障碍提示音的音量系数：{@code 音量/100}（100 → 1.0 = 原始音量，1000 → 10.0 = 10×，1 → 0.01）。
     *
     * <p>缓存策略与 {@link #helpEnabled} 完全一致：只在「客户端镜像换代」或「换了一条扶梯」时才真的去查
     * —— {@link EscalatorSpeedManager#getHelpVolume} 在链上有单独设置时要展开整条链，每 tick 都做没必要。
     * 代次由 {@link EscalatorSpeedManager#applyClientHelpVolume} 递增，所以改音量后下一个 tick 就生效。
     *
     * <p>同样**绝不能**塞进按 anchor 缓存的 {@link #describe}：那样玩家站在扶梯上不动时就永远读不到新值。
     */
    private static float helpVolumeFactor(Minecraft mc, BlockPos anchor) {
        long generation = EscalatorSpeedManager.clientHelpVolumeGeneration();
        if (anchor.equals(cachedVolumeAnchor) && generation == cachedVolumeGeneration) {
            return cachedVolumeFactor;
        }
        cachedVolumeAnchor = anchor;
        cachedVolumeGeneration = generation;
        int volume = EscalatorSpeedManager.getHelpVolume(mc.level, anchor);
        cachedVolumeFactor = Math.max(0.0f, volume / 100.0f);
        return cachedVolumeFactor;
    }

    /**
     * 【1.24】这条扶梯**生效的**提示音可闻范围（格）：单独设置 &gt; 维度默认
     * （{@code /futihelpround}，初始 4）。
     *
     * <p>缓存策略与 {@link #helpEnabled} / {@link #helpVolumeFactor} 完全一致：只在
     * 「客户端镜像换代」或「换了一条扶梯」时才真的去查 —— {@link EscalatorSpeedManager#getHelpRound}
     * 在链上有单独设置时要展开整条链，每 tick 都做没必要。代次由
     * {@link EscalatorSpeedManager#applyClientHelpRounds} 递增，所以改完**下一个 tick** 就生效。
     *
     * <p><b>绝不能塞进按 anchor 缓存的 {@link #describe}</b>（坑 17）：那样玩家站在扶梯上不动时
     * 永远读不到新范围。另外 {@link #currentTarget} 的 TTL 缓存也带了这个代次，
     * 保证「范围一改、那 0.5 秒的几何缓存立刻作废」，而不是等 TTL 自然过期。
     */
    private static double helpRange(Minecraft mc, BlockPos anchor) {
        refreshRangeCache(mc, anchor);
        return cachedRangeValue;
    }

    /** ★【10-03】同 {@link #helpRange}，但取**垂直（y 轴）**维（初始 5 格）。 */
    private static double helpRangeY(Minecraft mc, BlockPos anchor) {
        refreshRangeCache(mc, anchor);
        return cachedRangeYValue;
    }

    /** ★【10-03】一次把**两维**范围都查到缓存里（链上有单独设置时要展开整条链，别分两次查）。 */
    private static void refreshRangeCache(Minecraft mc, BlockPos anchor) {
        long generation = EscalatorSpeedManager.clientHelpRoundGeneration();
        if (anchor.equals(cachedRangeAnchor) && generation == cachedRangeGeneration) {
            return;
        }
        cachedRangeAnchor = anchor;
        cachedRangeGeneration = generation;
        cachedRangeValue = EscalatorSpeedManager.getHelpRound(mc.level, anchor);
        cachedRangeYValue = EscalatorSpeedManager.getHelpRoundY(mc.level, anchor);
    }

    /**
     * 【1.31】这条扶梯**生效的**两个端头提示音速率（Hz）：单独设置 &gt; 维度默认
     * （{@code /futihelpspeed in|out}，初始 10 / 1）。
     *
     * <p>缓存策略与 {@link #helpEnabled} / {@link #helpVolumeFactor} / {@link #helpRange} 完全一致：
     * 只在「客户端镜像换代」或「换了一条扶梯」时才真的去查（查的时候要展开整条链，每 tick 做没必要）。
     * 代次由 {@link EscalatorSpeedManager#applyClientHelpSpeeds} 递增，所以改完**下一个 tick** 就生效。
     *
     * <p><b>绝不能塞进按 anchor 缓存的 {@link #describe}</b>（坑 17）：那样玩家站在扶梯上不动时
     * 永远读不到新速率。
     */
    private static Rates helpRates(Minecraft mc, BlockPos anchor) {
        long generation = EscalatorSpeedManager.clientHelpSpeedGeneration();
        if (anchor.equals(cachedRatesAnchor) && generation == cachedRatesGeneration) {
            return cachedRatesValue;
        }
        cachedRatesAnchor = anchor;
        cachedRatesGeneration = generation;
        cachedRatesValue = new Rates(
                EscalatorSpeedManager.getHelpSpeedIn(mc.level, anchor),
                EscalatorSpeedManager.getHelpSpeedOut(mc.level, anchor));
        return cachedRatesValue;
    }

    /**
     * 【1.41】这条扶梯**实际生效**的提示音「音乐」ID（进 / 出两头）：单独设置 &gt; 维度默认
     * （{@code /futihelpmusic in|out}，初始 = {@code default} = 模组原来的提示音）。**永远非 null**。
     *
     * <p>取值三种：{@link EscalatorSpeedData#HELP_AUDIO_DEFAULT}（内置素材 + 速率分档）、
     * {@link EscalatorSpeedData#HELP_AUDIO_OFF}（**这一头**不播，在 {@link #updateEnd} 里拦掉）、
     * 或音频库里的文件名（在这一头循环播放这段自定义音频）。
     *
     * <p>缓存策略与 {@link #helpEnabled} / {@link #helpVolumeFactor} / {@link #helpRange} /
     * {@link #helpRates} 完全一致：只在「客户端镜像换代」或「换了一条扶梯」时才真的去查
     * （查的时候要顺整条链找，每 tick 做没必要）。代次由
     * {@link EscalatorSpeedManager#applyClientHelpAudio} 递增，所以 {@code /futihelpmusic in|out}
     * 改完**下一个 tick** 就换声音。两头共用一次查询、一个代次（同一只同步包）。
     *
     * <p><b>绝不能塞进按 anchor 缓存的 {@link #describe}</b>（坑 17）：那样玩家站在扶梯上不动时
     * 永远读不到新的提示音 —— 表现就是「指令改了没反应」。
     */
    private static AudioIds helpAudioIds(Minecraft mc, BlockPos anchor) {
        long generation = EscalatorSpeedManager.clientHelpAudioGeneration();
        if (anchor.equals(cachedHelpAudioAnchor) && generation == cachedHelpAudioGeneration) {
            return cachedHelpAudioValue;
        }
        cachedHelpAudioAnchor = anchor;
        cachedHelpAudioGeneration = generation;
        cachedHelpAudioValue = new AudioIds(
                EscalatorSpeedManager.effectiveHelpAudioId(mc.level, anchor, true),
                EscalatorSpeedManager.effectiveHelpAudioId(mc.level, anchor, false));
        return cachedHelpAudioValue;
    }

    /**
     * 【1.41】这一个端头这次该放的声音：{@code default} 走内置素材 + 速率分档，
     * 其它（自定义音频）走 {@link EscalatorAudioPlayer} 那套解码注入链路、原速循环。
     *
     * <p>★ 与 /futihelpspeed 一样，**两头各算各的**：进扶梯那头与出扶梯那头
     * 可以一段是内置「咔啪」、另一段是玩家导入的 OGG，互不影响。
     */
    private static Sample sampleFor(String audioId, int hz) {
        if (EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(audioId)) {
            return new Sample(chimeEventFor(hz), null, chimePitchFor(hz));
        }
        // 自定义音频：文件名 → 合法资源路径（与底噪共用同一个映射，注入/解析必须一致）。
        return new Sample(EscalatorAudioPlayer.soundLocation(audioId), audioId, 1.0f);
    }

    /**
     * 【1.41】维护**一个端头**的提示音：这一头被设成 {@code off}（不播）就停掉它，
     * 否则按 {@link #update} 正常维护（按距离算增益、按需启动/重建/停掉）。
     *
     * <p>★ 这正是 1.41 与 1.39 的差别所在：1.39 的 {@code off} 是「整条扶梯不播」
     * （在 {@link #isAudibleNow} 里一刀拦掉），现在细到了单头 ——
     * 可以只让落客端不响、上客端照常响，反过来也行。
     *
     * @param endLabel 日志 / 文案里的端头名（不含右括号）：「上客端（进入」/「落客端（离开」
     */
    private static void updateEnd(Minecraft mc, End kind, Vec3 pos, String audioId, int hz,
                                  Vec3 player, String endLabel, float volumeFactor,
                                  double rangeXz, double rangeY) {
        if (EscalatorSpeedData.HELP_AUDIO_OFF.equals(audioId)) {
            stopEnd(mc, kind, endLabel);
            return;
        }
        Sample sample = sampleFor(audioId, hz);
        update(mc, kind, pos, sample, player,
                endLabel + "，" + describeSample(sample, hz) + "）", volumeFactor, rangeXz, rangeY);
    }

    /** 【1.41】停掉某一个端头那一路（这一头被设成 {@code off}）。本来就没在播则什么也不做。 */
    private static void stopEnd(Minecraft mc, End kind, String endLabel) {
        ChimeInstance inst = ACTIVE.get(kind);
        if (inst == null) {
            return;
        }
        mc.getSoundManager().stop(inst);
        ACTIVE.remove(kind);
        LOGGER.info("[SmoothLift/Chime] {}）这一路提示音已停（这一头被设为不播）", endLabel);
    }

    /** 【1.39】日志文案用：这一头放的是什么（内置素材带速率，自定义音频直接点名）。 */
    private static String describeSample(Sample sample, int hz) {
        return sample.customId() == null ? hz + " 次/秒" : "自定义 " + sample.customId();
    }

    /**
     * 【1.31】按速率（Hz）挑素材：{@code hz ≤ 2} 用 1 Hz 素材、{@code ≤ 8} 用 4 Hz 素材、
     * {@code ≤ 20} 用 10 Hz 素材、【1.34】{@code ≤ 50} 用 25 Hz 素材、否则用 50 Hz 素材。
     *
     * <p>判据是「让 {@link #chimePitchFor} 算出的 pitch 落在 [0.5, 2.0] 内」——
     * 1 Hz 素材覆盖 [0.5, 2]、4 Hz 素材覆盖 [2, 8]、10 Hz 素材覆盖 [5, 20]、
     * 25 Hz 素材覆盖 [12.5, 50]、50 Hz 素材覆盖 [25, 100]，
     * 首尾相接、略有重叠，正好覆盖 {@link EscalatorSpeedData#HELP_SPEED_MIN}~
     * {@link EscalatorSpeedData#HELP_SPEED_MAX} Hz 的全部取值。
     *
     * <p><b>两处必须同步改</b>：这里选了素材、{@link #chimePitchFor} 必须用**该素材的原始速率**做分母，
     * 否则实际速率 = 素材速率 × pitch ≠ 玩家设的值。
     */
    private static ResourceLocation chimeEventFor(int hz) {
        if (hz <= 2) {
            return CHIME_1HZ;
        }
        if (hz <= 8) {
            return CHIME_4HZ;
        }
        if (hz <= 20) {
            return CHIME_10HZ;
        }
        return hz <= 50 ? CHIME_25HZ : CHIME_50HZ;
    }

    /**
     * 【1.31】按速率（Hz）算 playback pitch（素材原始速率 → 1.0）。
     *
     * <p>原版 {@code SoundEngine} 会把 pitch 夹在 <b>[0.5, 2.0]</b>，所以本函数必须一直落在这个区间里
     * ——{@link #chimeEventFor} 选的素材与这里的分档是一一对应的，两处**必须同步改**。
     * 默认档（进入 10 Hz、离开 1 Hz）算出来正好是 1.0，等于放素材原速、音色与 1.27 定版一致。
     */
    private static float chimePitchFor(int hz) {
        if (hz <= 2) {
            return hz;
        }
        if (hz <= 8) {
            return hz / 4.0f;
        }
        if (hz <= 20) {
            return hz / 10.0f;
        }
        return hz <= 50 ? hz / 25.0f : hz / 50.0f;
    }

    /** 断开连接：停掉所有提示音。 */
    public static void onDisconnect() {        stopAll(Minecraft.getInstance());
        invalidateTarget();
    }

    // ------------------------------------------------------------------
    // 找「最近的那条扶梯」+ 定出两个端头
    // ------------------------------------------------------------------

    private static Target currentTarget(Minecraft mc) {
        long now = mc.level.getGameTime();
        // 【1.24】范围代次进缓存键：/futihelpround 改完立刻重扫，不必等 TTL 自然过期。
        long generation = EscalatorSpeedManager.clientHelpRoundGeneration();
        if (cachedValid
                && cachedDimension != null && cachedDimension.equals(mc.level.dimension())
                && generation == cachedRoundGeneration
                && now - cachedTick < TARGET_TTL_TICKS) {
            return cachedTarget;
        }
        cachedValid = true;
        cachedTick = now;
        cachedRoundGeneration = generation;
        cachedDimension = mc.level.dimension();
        cachedTarget = scan(mc);
        return cachedTarget;
    }

    /**
     * 在「已加载区块里的扶梯阶梯方块」索引里找离玩家最近的**那一块阶梯**，
     * 再用它把这整条扶梯展开、算出两个端头。
     *
     * <p>只做一次廉价的 AABB 预筛 + 方块距离，几千个阶梯块也就是几十微秒，每 0.5 秒才跑一次。
     *
     * <p>【1.24】扫描半径取「本维度**可能的最大**提示音范围」，真正「够不够近」再用**这条扶梯
     * 自己的**范围判一次（见 {@link #helpRange}）。
     *
     * @return 最近的扶梯可达（{@link #helpRange} 格内）且能定出端头时的两端头；否则 null
     */
    private static Target scan(Minecraft mc) {
        List<BlockPos> steps = EscalatorStepIndex.positions();
        if (steps.isEmpty()) {
            return null;
        }
        BlockPos eye = mc.player.blockPosition();
        int limit = EscalatorSpeedManager.getMaxHelpRound(mc.level) + 1;
        BlockPos anchor = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : steps) {
            if (Math.abs(pos.getX() - eye.getX()) > limit
                    || Math.abs(pos.getY() - eye.getY()) > limit
                    || Math.abs(pos.getZ() - eye.getZ()) > limit) {
                continue;
            }
            double dist = distanceToBlock(mc.player.position(), pos);
            if (dist < best) {
                best = dist;
                anchor = pos;
            }
        }
        if (anchor == null) {
            return null;
        }
        // 【1.24】范围按「这条扶梯」算（anchor 已定，链上若有单独设置也能取到）。
        // ★【10-03】双维：改用 anchor 的**水平 / 垂直**分量两维各判一次（同 update() 的口径）。
        double rangeXz = helpRange(mc, anchor);
        double rangeY = helpRangeY(mc, anchor);
        double axz = horizontalDistanceToBlock(mc.player.position(), anchor);
        double ay = verticalDistanceToBlock(mc.player.position(), anchor);
        if (!(axz < rangeXz) || !(ay < rangeY)) {
            return null;
        }
        if (anchor.equals(cachedAnchor) && cachedTarget != null) {
            // 还是这条扶梯、而且上次算出了端头：不用重新展开整条链。
            // 注意必须带上 cachedTarget != null —— describe() 在几何上算不出来时（链太短、轴铺不开、
            // 只剩一个平层……）会返回 null，若这里照样短路，那之后哪怕条件恢复，只要玩家没走动
            // （anchor 不变）就永远算不出端头、永远不响。
            return cachedTarget;
        }
        Target target = describe(mc, anchor);
        if (target != null && (cachedTarget == null
                || !cachedTarget.boardPos().equals(target.boardPos())
                || !cachedTarget.alightPos().equals(target.alightPos()))) {
            // 【1.20】定位结果一变就打一条：日志里能直接看到提示音的两个音源到底在哪、
            // 两端相距多少格。配合下面「离开端头」那条，出问题一眼就能定位到是几何错了
            // 还是其实听到的是别的声音（例如整条一起响的运行底噪）。
            // 【1.21】再加上定位依据（阶梯块数 / 两条水平轴跨度 / 运行轴 / 上行下行）——
            // 「只有一端有声音」这类反馈，靠这一行就能判断是不是链条没展开全。
            LOGGER.info("[SmoothLift/Chime] 端头定位：{}；上客端 {} 落客端 {}（两端相距 {} 格，射程 水平 {} / 垂直 {} 格）",
                    target.debug(), format(target.boardPos()), format(target.alightPos()),
                    String.format("%.1f", target.boardPos().distanceTo(target.alightPos())),
                    String.format("%.0f", rangeXz), String.format("%.0f", rangeY));
        }
        cachedAnchor = anchor;
        return target;
    }

    /** 日志里印坐标用（保留一位小数）。 */
    private static String format(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    /**
     * 由扶梯上任一块阶梯，算出「上客端」与「落客端」**那对左右并列扶梯方块**的世界坐标中心。
     *
     * <p>本方法**只算几何**，绝不做「该不该响」的判断（扶梯停没停、提示音开关开没开）——
     * 那些条件会随时间变化，而本结果按 {@code anchor} 缓存，放进来就永远不会重算，
     * 表现就是「开关关不掉」。它们统一在 {@link #onClientTick} 里逐 tick 判，见那边的说明。
     *
     * <p>步骤：
     * <ol>
     *   <li>只留**阶梯块**（去掉护栏/侧板），否则侧板在 y+1/y+2 上，会把端头中心抬高；</li>
     *   <li>【1.21】两端按**高度**分：最低那一组方块 = 「最下面」，最高那一组 = 「最上面」。
     *       这正是需求的原文规则（「向上的扶梯在最下面第一个扶梯方块放进入扶梯的急促咔咔、
     *       最上面最后一个扶梯方块放出扶梯的缓慢咔咔」），而且**完全不依赖选轴**：
     *       <ul>
     *         <li>左右两列并排的阶梯块**同高**，天然落在同一组里 —— 不会再出现「把左右两列
     *             当成两个端头、中心落在扶梯正中间」那种错法（【1.15】～【1.20】的老 bug）；</li>
     *         <li>弯曲 / 螺旋扶梯、带长平层的扶梯也照样成立（沿某一条直线投影就不成立了）。</li>
     *       </ul></li>
     *   <li>整条都同高（平层移动步道）时 Y 分不开，退回「沿运行轴投影取两端」（见 {@link Axis}）；</li>
     *   <li>哪一头是上客端由 {@code direction} 决定：{@code direction=true} 沿 {@code +facing}
     *       送人，而 MTR 的斜坡永远沿 {@code +facing} 往上爬（依据见下），
     *       所以 {@code true} 就是**上行**、在**低那头**上客；{@code false} 在高那头。</li>
     * </ol>
     *
     * <p>「MTR 的斜坡永远沿 +facing 往上爬」这条不用查属性、也不用信任命名，直接从 MTR 自己的
     * {@code BlockEscalatorBase.getOrientation} 推出来 —— 它按邻居判定朝向：
     * <pre>
     *   前方(facing)同高 &amp;&amp; 后方同高          -&gt; FLAT
     *   前方抬高一格 &amp;&amp; 后方低一格          -&gt; SLOPE          &lt;-- 斜坡的前方永远更高
     *   前方抬高一格 &amp;&amp; 后方同高            -&gt; TRANSITION_BOTTOM
     *   前方同高 &amp;&amp; 后方低一格              -&gt; TRANSITION_TOP
     *   后方有块                            -&gt; LANDING_TOP     &lt;-- 最顶上那段
     *   否则                                -&gt; LANDING_BOTTOM  &lt;-- 最底下那段
     * </pre>
     */
    private static Target describe(Minecraft mc, BlockPos anchor) {
        BlockState anchorState = mc.level.getBlockState(anchor);
        // direction=true 沿 +facing 送人，而斜坡沿 +facing 往上爬 ⇒ direction 就是「是不是上行」。
        boolean movingUp = EscalatorUtil.getBooleanProperty(anchorState, "direction", true);

        List<BlockPos> steps = stepBlocks(mc, anchor);
        if (steps.size() < 2) {
            return null;
        }
        Direction facing = EscalatorUtil.getFacing(anchorState);

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos pos : steps) {
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
        }
        // 两个端头**按「上行」排好**：upBoard = 最低那头、upAlight = 最高那头；
        // 下行扶梯把两者互换即可，不需要再猜方向。
        Vec3 upBoard;
        Vec3 upAlight;
        String how;
        if (maxY - minY >= 1) {
            List<BlockPos> bottom = new ArrayList<>();
            List<BlockPos> top = new ArrayList<>();
            for (BlockPos pos : steps) {
                if (pos.getY() == minY) {
                    bottom.add(pos);
                } else if (pos.getY() == maxY) {
                    top.add(pos);
                }
            }
            upBoard = Axis.center(bottom);
            upAlight = Axis.center(top);
            how = "按高度分组";
        } else {
            // 平层（移动步道）：全部同高，Y 分不开，退回沿运行轴投影取两端。
            Axis axis = Axis.of(facing, steps);
            if (axis == null) {
                return null;
            }
            Vec3 a = axis.low().center();
            Vec3 b = axis.high().center();
            // dir 相对 facing 的符号决定哪一头是「沿 +facing 前进时的起点」（见 Axis.of 第 ④ 步）。
            if (axis.facingSign() < 0) {
                Vec3 swap = a;
                a = b;
                b = swap;
            }
            upBoard = a;
            upAlight = b;
            how = "平层（沿运行轴 " + axis.dir() + " 投影"
                    + (axis.facingSign() == 0 ? "，facing 用不上" : "") + "）";
        }

        Vec3 board = movingUp ? upBoard : upAlight;
        Vec3 alight = movingUp ? upAlight : upBoard;
        if (board.distanceTo(alight) < Axis.MIN_SPAN - 1.0e-6) {
            // 两端落在一处（只有一个平层、或者扶梯短到两端重合）：没法分进出，不响。
            return null;
        }
        return new Target(anchor, board, alight, String.format(
                "阶梯块 %d 个、高度 %d~%d、X 跨度 %.0f、Z 跨度 %.0f、%s、%s",
                steps.size(), minY, maxY,
                Axis.spread(steps, Direction.EAST), Axis.spread(steps, Direction.SOUTH), how,
                movingUp ? "上行（上客端=最低那头）" : "下行（上客端=最高那头）"));
    }

    /**
     * 把整条扶梯里属于**阶梯**的方块挑出来。
     *
     * <p><b>【1.21】必须用 {@link EscalatorUtil#collectStepChain}（只认阶梯块）</b>：普通的
     * {@link EscalatorUtil#collectChain} 会把护栏 / 侧板也算进来，而护栏贴在阶梯斜上方、和阶梯挤在
     * 同一个候选位置上，沿着链走时会「把游走带偏」，于是从扶梯中段往低处走**走不到下半条链** ——
     * 战利品就是「链只有玩家脚下这一段 + 它上面那一段」，「最低那头」于是变成玩家脚下，
     * 上客端提示音跟着玩家走、一直不离开 4 格射程、直到玩家走出扶梯才停（还会和落客端那路重叠）。
     * 只认阶梯块就能拿到完整的链。
     */
    private static List<BlockPos> stepBlocks(Minecraft mc, BlockPos anchor) {
        Set<BlockPos> chain = EscalatorUtil.collectStepChain(mc.level, anchor);
        List<BlockPos> out = new ArrayList<>(chain.size() + 1);
        for (BlockPos pos : chain) {
            if (EscalatorUtil.isEscalatorStep(mc.level.getBlockState(pos))) {
                out.add(pos);
            }
        }
        if (!out.contains(anchor) && EscalatorUtil.isEscalatorStep(mc.level.getBlockState(anchor))) {
            out.add(anchor);
        }
        return out;
    }

    /**
     * 运行轴（= 扶梯的**长度**方向），以及排在它两端的两个端头平面。
     *
     * <p>【1.21】只在**两端同高的平层扶梯（移动步道）**上才用得到 —— 斜坡扶梯的两端直接按
     * {@code Y} 分组就定出来了（见 {@link #describe}），根本不需要选轴。
     * 但平层上 Y 分不开，只能沿一条水平轴投影，所以这里把「选哪条轴」讲清楚。
     *
     * <p><b>选轴规则（【1.20】引入、【1.21】修正）</b>：运行轴**优先用 {@code facing}** ——
     * 因为 {@code facing} 就是 MTR 的运行轴（见类注释里的字节码依据），只有它才带方向信息，
     * 才能判出哪一头是上客端。判据只要求「沿 {@code facing} 铺开的极差 **不比另一条水平轴窄**」，
     * 于是连「两块阶梯的极短扶梯」（两条水平轴一样宽）也会选中 {@code facing}。
     *
     * <p>什么时候弃用 {@code facing}：只有当它**明显比另一条水平轴窄**时 —— 那说明它指的其实是
     * 扶梯的**宽度**方向（横向只有 1 格），而沿宽度投影会把「左右两列」错当成两个端头；
     * 每列的中心是**整列所有方块的平均位置**（≈ 扶梯正中间），于是提示音变成
     * 「从扶梯中间发声、走到哪都差不多响」—— 那正是【1.15】～【1.19】的「中段也有提示音」bug。
     * 这时退回几何选出的长度轴（两条水平轴里铺得最开的那条）。
     *
     * <p>【1.21】修的是 1.20 的过度设计：1.20 把「挑最宽的水平轴」放在最前面、并且要求跨度 ≥ 2 格，
     * 结果**短扶梯整个不出声**（两端极差只有 1 格时直接被判成「没有端头可分」）。
     * 现在「宽度轴误判」只由选轴本身杜绝，跨度门槛退回 1 格，跟【1.15】～【1.19】一致。
     */
    private record Axis(Direction dir, int facingSign, Plane low, Plane high) {

        /**
         * 运行轴的**最小跨度**（格）：两端至少隔开这么多，才谈得上「两个端头」。
         *
         * <p>取值 1.0（= 【1.15】以来一直用的门槛）：只要沿运行轴能铺开 1 格（例如只有两块
         * 阶梯的极短扶梯）就算有两个端头。**别再往上抬** —— 1.20 抬到 2.0 时短扶梯直接整个
         * 不出声；而「沿宽度轴投影」那种误判改由 {@link #of} 的选轴规则杜绝，不需要靠门槛兜底。
         */
        private static final double MIN_SPAN = 1.0;

        static Axis of(Direction facing, List<BlockPos> steps) {
            if (facing != null && facing.getStepX() == 0 && facing.getStepZ() == 0) {
                facing = null;
            }
            // ① 几何事实：两条水平轴里铺得最开的那条就是扶梯的长度方向。
            Direction longAxis = null;
            double longSpread = 0.0;
            for (Direction dir : new Direction[]{Direction.EAST, Direction.SOUTH}) {
                double s = spread(steps, dir);
                if (s > longSpread) {
                    longSpread = s;
                    longAxis = dir;
                }
            }
            if (longAxis == null) {
                return null;
            }
            // ② 优先用 facing（它才是运行轴，而且只有它带方向）。判据是「不比另一条水平轴窄」，
            //    用 >=：两条轴一样宽（极短扶梯）时也选 facing，否则会挑到宽度轴去。
            double facingSpread = facing == null ? -1.0 : spread(steps, facing);
            boolean usableFacing = facing != null && facingSpread >= longSpread - 1.0e-6;
            Direction dir = usableFacing ? facing : longAxis;
            // ③ 沿运行轴铺不开（< MIN_SPAN 格）：分不出「进」「出」两端，不响。
            if (spread(steps, dir) < MIN_SPAN) {
                return null;
            }
            Plane[] planes = planes(steps, dir);
            if (planes == null) {
                return null;
            }
            // ④ 记下 dir 相对 facing 的符号：+1 = 同向、-1 = 反向、0 = 拿不到 facing（此时只能
            //    假定「沿 +dir 走」，配合两端高低仍能判对上行的扶梯）。
            int facingSign = 0;
            if (facing != null && facing.getAxis() == dir.getAxis()) {
                facingSign = dir == facing ? 1 : -1;
            }
            return new Axis(dir, facingSign, planes[0], planes[1]);
        }

        /** 一组方块在 {@code dir} 这条轴上铺开的极差（格）。 */
        private static double spread(List<BlockPos> steps, Direction dir) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (BlockPos pos : steps) {
                double t = project(pos, dir);
                min = Math.min(min, t);
                max = Math.max(max, t);
            }
            return max - min;
        }

        /**
         * 取这条轴两端那两组方块的中心（各组 = **同一个位置上的左 + 右两块**，所以中心就是
         * 「一块扶梯方块」的中心，而不是整条扶梯的某个平均位置）。
         *
         * <p>铺不开（极差 &lt; {@link #MIN_SPAN}）时返回 null，交给调用方判定为「不响」。
         */
        private static Plane[] planes(List<BlockPos> steps, Direction dir) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (BlockPos pos : steps) {
                double t = project(pos, dir);
                min = Math.min(min, t);
                max = Math.max(max, t);
            }
            if (max - min < MIN_SPAN) {
                return null;
            }
            List<BlockPos> low = new ArrayList<>();
            List<BlockPos> high = new ArrayList<>();
            for (BlockPos pos : steps) {
                double t = project(pos, dir);
                if (t < min + 0.5) {
                    low.add(pos);
                }
                if (t > max - 0.5) {
                    high.add(pos);
                }
            }
            if (low.isEmpty() || high.isEmpty()) {
                return null;
            }
            return new Plane[]{new Plane(center(low)), new Plane(center(high))};
        }

        /** 方块在运行轴上的投影（整数格坐标点积，够用且没有取整误差）。 */
        private static double project(BlockPos pos, Direction dir) {
            return (double) pos.getX() * dir.getStepX() + (double) pos.getZ() * dir.getStepZ();
        }

        /** 一组方块的中心（+0.5 落在方块正中心；高度取中心，听感上像装在平层上方的扬声器）。 */
        private static Vec3 center(List<BlockPos> blocks) {
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (BlockPos pos : blocks) {
                x += pos.getX() + 0.5;
                y += pos.getY() + 0.5;
                z += pos.getZ() + 0.5;
            }
            int n = blocks.size();
            return new Vec3(x / n, y / n, z / n);
        }
    }

    /** 端头平面上的中心。 */
    private record Plane(Vec3 center) {
    }

    /** 点到方块实心体（1×1×1）的最短距离；玩家在方块内部时返回 0。 */
    private static double distanceToBlock(Vec3 p, BlockPos pos) {
        double dx = Math.max(0.0, Math.max(pos.getX() - p.x, p.x - (pos.getX() + 1.0)));
        double dy = Math.max(0.0, Math.max(pos.getY() - p.y, p.y - (pos.getY() + 1.0)));
        double dz = Math.max(0.0, Math.max(pos.getZ() - p.z, p.z - (pos.getZ() + 1.0)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** ★【10-03】点到方块实心体的**水平（xz）**分量，格（{@link #distanceToBlock} 的 dx/dz 那一半）。 */
    private static double horizontalDistanceToBlock(Vec3 p, BlockPos pos) {
        double dx = Math.max(0.0, Math.max(pos.getX() - p.x, p.x - (pos.getX() + 1.0)));
        double dz = Math.max(0.0, Math.max(pos.getZ() - p.z, p.z - (pos.getZ() + 1.0)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** ★【10-03】点到方块实心体的**垂直（y）**分量，格。 */
    private static double verticalDistanceToBlock(Vec3 p, BlockPos pos) {
        return Math.max(0.0, Math.max(pos.getY() - p.y, p.y - (pos.getY() + 1.0)));
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    /**
     * 维护一个端头的提示音：按距离（再乘音量百分比）算目标增益，需要时启动/重建实例，超出范围就停掉。
     *
     * @param what         日志里用的中文名（「上客端（进入，10 次/秒）」/「上客端（进入，自定义 x.ogg）」）
     * @param sample       【1.39】该速率 / 该提示音对应的**声音**（见 {@link #sampleFor}）；换素材或换提示音时重建实例
     * @param volumeFactor 【1.18】提示音音量系数（{@link #helpVolumeFactor}，100 → 1.0）
     * @param range        【1.24】这条扶梯的可闻范围（格，见 {@link #helpRange}），逐 tick 现算
     */
    private static void update(Minecraft mc, End kind, Vec3 pos, Sample sample, Vec3 player,
                               String what, float volumeFactor, double rangeXz, double rangeY) {
        // ★【10-03】距离拆双维：水平 = hypot(dx, dz)、垂直 = |dy|；两维各算线性、取较小。
        double dxz = Math.hypot(player.x - pos.x, player.z - pos.z);
        double dy = Math.abs(player.y - pos.y);
        float target = gain(dxz, dy, rangeXz, rangeY) * volumeFactor;
        ChimeInstance inst = ACTIVE.get(kind);
        if (inst != null && !inst.matches(sample)) {
            // 【1.31】速率跨了素材档（如 8 → 9 Hz）要换素材；
            // 【1.39】/futihelpmusic 换了提示音（default ↔ 某段 ogg）也要换。两种都只能重建实例。
            mc.getSoundManager().stop(inst);
            ACTIVE.remove(kind);
            inst = null;
        }
        if (target <= 0.0f) {
            if (inst != null) {
                mc.getSoundManager().stop(inst);
                ACTIVE.remove(kind);
                LOGGER.info("[SmoothLift/Chime] 离开扶梯{}（到该端头 水平 {} / 垂直 {} 格 ≥ 射程 {}/{} 格），停掉这一路",
                        what, String.format("%.1f", dxz), String.format("%.1f", dy),
                        String.format("%.0f", rangeXz), String.format("%.0f", rangeY));
            }
            return;
        }
        SoundEngine engine = mc.getSoundManager().soundEngine;
        if (inst != null && !engine.instanceToChannel.containsKey(inst)) {
            // 引擎侧已经把通道丢了（F3+T 资源重载、换音频输出设备……），
            // 实例却还挂在 ACTIVE 里 -> 以后永远不会再 play。
            ACTIVE.remove(kind);
            inst = null;
        }
        if (inst == null) {
            if (sample.customId() == null) {
                if (mc.getSoundManager().getSoundEvent(sample.event()) == null) {
                    // 模组的 sounds.json 没被加载（例如资源包里缺这几条）。不打日志刷屏，直接不出声。
                    return;
                }
            } else if (!EscalatorAudioPlayer.injectAudio(mc, sample.customId())) {
                // 【1.39】自定义提示音：音频还没同步到、解码失败、或已被删除 —— 同样静默不出声。
                // 解码失败只会 warn 一次（之后由 EscalatorAudioPlayer 的 DECODE_FAILED 挡掉），
                // 所以这里不会每 tick 刷日志。
                return;
            }
            inst = new ChimeInstance(sample);
            inst.setPitch(sample.pitch());
            // 【1.34】play() 之前就把音量摆到**目标增益**（不是淡入起点）：
            // SoundEngine.play 会立刻用实例当时的 volume 建源开播，摆好它开播当刻就是正确音量，
            // 既不会先满音量炸一下（那是底噪需要淡入的原因），也不会有开头的「空白」。
            // 提示音是脉冲式的定位音，用户要的就是「一触发就听得见」；文件本身还带 0.4 ms 起音斜坡，
            // 所以不存在爆音。★ 别再照抄 {@link EscalatorAudioPlayer} 的 6 tick 淡入（踩过：1 Hz 档
            // 第一下正好落在淡入窗口里 ⇒ 触发后约 1 秒完全没声）。
            inst.setPosition(pos, target);
            mc.getSoundManager().play(inst);
            if (!engine.instanceToChannel.containsKey(inst)) {
                // 声音引擎还没接受这条声音，下一 tick 再试
                return;
            }
            // 和 EscalatorAudioPlayer 一样，对底层 Channel 明确开一次循环：
            // 静态缓冲播完一遍就会停，虽然 SoundEngine 自己也会按 isLooping() 设一次
            // AL_LOOPING，但这里再确认一遍，免得提示音只响一遍就永久哑掉。
            //
            // 【1.20】顺便把该源的 AL_MAX_GAIN 抬到 MAX_GAIN（= 10×）：
            // OpenAL 会把「源增益」夹到 AL_MAX_GAIN（默认 = 1.0），所以光让
            // SoundEngineVolumeMixin 按 GainManagedSound 放行还不够 —— 有效增益依旧被压回
            // 1.0×，表现就是「提示音音量调到 100 以上完全没变化」。抬到 10 才真的能放大。
            // 该属性是「按源」的、会一直保留，所以开播时设一次即可；引擎每 tick 只改
            // AL_GAIN / pitch / position，不会碰 AL_MAX_GAIN。
            ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
            if (handle != null) {
                handle.execute(ch -> {
                    ch.setLooping(true);
                    AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN);
                });
            }
            LOGGER.info("[SmoothLift/Chime] 开始播放扶梯{}提示音（到该端头 水平 {} / 垂直 {} 格）",
                    what, String.format("%.1f", dxz), String.format("%.1f", dy));
            ACTIVE.put(kind, inst);
        }
        // 【1.31】同一素材档内的速率变化（如 9 → 12 Hz 都在 10 Hz 素材档）不必重建实例：
        // 原版 SoundEngine 每 tick 都会把实例当前的 pitch 写进 AL_PITCH（并夹到 [0.5,2.0]），
        // 所以直接改字段，下一个 tick 就换速度。
        // 【1.39】自定义提示音的 pitch 恒 1.0（素材是玩家自己的，不能按 Hz 分档）。
        inst.setPitch(sample.pitch());
        // 【1.34】音量直接给目标增益，不再淡入（原因见上面 new ChimeInstance 那段）。
        inst.setPosition(pos, target);
    }

    /**
     * 距离增益：{@code range}（默认 4 格，可被 {@code /futihelpround} 改）内**按比例线性**衰减：
     * {@code 1 - d/range} —— 站在 0 格处 = 100%，每远 1 格减 {@code 100/range}%，
     * 到 {@code range} 格正好 0（出界硬切 0）。
     *
     * <p>本函数**只管距离**；【1.18】用户设的提示音音量由 {@link #helpVolumeFactor}
     * 在调用处再乘上去（{@code target = gain(dist, range) * volumeFactor}）。
     *
     * <p>★★【10-03 用户点名改】原来这里是**平方**衰减 {@code (1-d/r)²}，理由是「脉冲音更『贴块』、
     * 走到下一块阶梯就明显弱一截，好分辨声音从哪头来」。用户点名要求**所有淡入淡出都按比例**
     * （原话例子：「`/futiround` 设 10 ⇒ 距离 0 格 100%、1 格 90%，以此类推」）⇒ 改成线性。
     * ★ 注意这个 `range` 是**提示音自己**的 {@code /futihelpround}，与底噪的 {@code /futiround}
     * 是两套数据（见项目记忆「底噪与提示音两套互不影响」）—— 但曲线形状两边现在一致了。
     *
     * <p>默认射程 4 格时**不做别的收窄**：扶梯长于 8 格时两端天然不相交、中段安静；
     * 扶梯短于 8 格时两端会同时听得见 —— 这是**故意的**（真实世界里的短扶梯也是这样，
     * 站在中间确实两头的声音都能听到）。别为了「中段一定要静音」去砍半径（试过，被否了）。
     * 1.24 起半径由玩家用 {@code /futihelpround} 决定，调大后两端重叠更多，同样是玩家自己的选择。
     */
    private static float gain(double distanceXz, double distanceY, double rangeXz, double rangeY) {
        if (!(rangeXz > 0.0) || !(rangeY > 0.0)
                || !(distanceXz < rangeXz) || !(distanceY < rangeY)) {
            return 0.0f;
        }
        return (float) Math.max(0.0,
                Math.min(1.0 - distanceXz / rangeXz, 1.0 - distanceY / rangeY));
    }

    // 【1.34】这里原来有一个 fadeInGain(...)（抄 EscalatorAudioPlayer 的 6 tick 指数淡入）。
    // 已删除，原因见 update() 里 new ChimeInstance 那一段：对连续底噪正确的淡入，用在
    // 脉冲式提示音上就变成「触发后 0.3~1 秒的空白」。**别再把它加回来。**

    private static void stopAll(Minecraft mc) {
        if (mc != null && mc.getSoundManager() != null) {
            for (ChimeInstance inst : ACTIVE.values()) {
                mc.getSoundManager().stop(inst);
            }
        }
        ACTIVE.clear();
    }

    private static void invalidateTarget() {
        cachedTarget = null;
        cachedAnchor = null;
        cachedValid = false;
        cachedTick = 0L;
        cachedDimension = null;
        cachedHelpAnchor = null;
        cachedHelpGeneration = -1L;
        cachedHelpValue = true;
        cachedVolumeAnchor = null;
        cachedVolumeGeneration = -1L;
        cachedVolumeFactor = 1.0f;
        // 【1.24】范围缓存（含 currentTarget 的 TTL 键）也要清，否则断线重连后沿用旧范围
        cachedRangeAnchor = null;
        cachedRangeGeneration = -1L;
        cachedRangeValue = DEFAULT_RANGE;
        cachedRangeYValue = DEFAULT_RANGE_Y;
        cachedRoundGeneration = -1L;
        // 【1.31】速率缓存也要清，否则断线重连后沿用旧速率
        cachedRatesAnchor = null;
        cachedRatesGeneration = -1L;
        cachedRatesValue = new Rates(EscalatorSpeedData.DEFAULT_HELP_SPEED_IN, EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT);
        // 【1.41】提示音音乐缓存同样要清，否则断线重连后沿用上一个世界的提示音
        cachedHelpAudioAnchor = null;
        cachedHelpAudioGeneration = -1L;
        cachedHelpAudioValue =
                new AudioIds(EscalatorSpeedData.HELP_AUDIO_DEFAULT, EscalatorSpeedData.HELP_AUDIO_DEFAULT);
        lastAudible = true;
    }

    /**
     * 端头提示音实例：循环播一段提示音，位置固定在该端头中心。
     *
     * <p><b>默认提示音（{@code default}）</b>走原版资源包（{@code sounds.json} 里注册过的
     * {@code smoothlift:audio/...}），所以 {@code resolve} 用默认实现即可。
     *
     * <p><b>【1.39】自定义提示音</b>（{@link Sample#customId()} 非 null）是玩家放进存档的 OGG，
     * 注册表里当然没有它，必须像 {@code EscalatorAudioPlayer.EscalatorSoundInstance} 那样
     * 自己造 {@code Sound} 返回（否则原版实现会把 sound 置成 EMPTY_SOUND 并返回 null，
     * {@code play()} 直接放弃 = 静音）；音频字节则由
     * {@link EscalatorAudioPlayer#injectAudio} 事先注入到声音引擎缓存里（key 必须等于
     * {@code Sound.getPath()}，这也是两个类共用 {@link EscalatorAudioPlayer#soundLocation} 的原因）。
     *
     * <p><b>【1.20】实现 {@link GainManagedSound}</b>：让
     * {@code SoundEngineVolumeMixin} 认得出本实例、把音量上限从原版的 1.0 抬到
     * {@link EscalatorAudioPlayer#MAX_GAIN}。不实现的话，{@code /futihelploud} 与界面里的
     * 「提示音音量」超过 100 就完全没有效果（原版 {@code calculateVolume} 把它夹在 [0,1]）。
     */
    private static final class ChimeInstance extends AbstractSoundInstance implements TickableSoundInstance,
            GainManagedSound {

        private final ResourceLocation event;

        /** 【1.39】自定义音频文件名；null = 内置素材（走 sounds.json 查找）。 */
        private final String customId;

        ChimeInstance(Sample sample) {
            super(sample.event(), SoundSource.BLOCKS, RandomSource.create());
            this.event = sample.event();
            this.customId = sample.customId();
            this.looping = true;
            // 关掉 OpenAL 的距离增益（只关增益，不断坐标 —— 声像照旧），音量自己按「到端头」算。
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = false;
            this.volume = 1.0f;
            this.pitch = 1.0f;
        }

        /**
         * 【1.39】这个实例放的还是不是玩家要的那段声音 —— 素材档（{@code event}）或提示音
         * （{@code customId}）任一变了就得重建实例（见 {@code update}）。
         */
        boolean matches(Sample sample) {
            return event.equals(sample.event()) && Objects.equals(customId, sample.customId());
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            if (customId == null) {
                return super.resolve(soundManager);
            }
            // location 必须带 "smoothlift:" 前缀，否则默认 namespace 是 minecraft:。
            ResourceLocation name = EscalatorAudioPlayer.soundLocation(customId);
            Sound s = new Sound(name.toString(), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F),
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

        /**
         * 【1.31】设置 playback pitch（速率 = 素材原始速率 × pitch）。
         *
         * <p>由调用方按 {@link #chimeEventFor} 的素材档保证落进 [0.5, 2.0]（原版会再夹一次）。
         * 每 tick 都设一遍 —— 引擎每 tick 才读一次该字段，所以同档内改速率能立刻生效、不必重建实例。
         */
        void setPitch(float pitch) {
            this.pitch = pitch;
        }
    }
}
