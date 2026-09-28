package smooth.lift.client;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 【1.28.1204】「讲述人」列车报站的开关 —— 指令 {@code /jsr on|off}。
 *
 * <h2>一、它管的是哪一条广播（【1.29】重新定过）</h2>
 * 屏蔽门那一套里有**两条互不相干**的进站广播，**可以同时存在**（用户原话：
 * 「讲述人的进站广播和自定义的进站广播不是一个广播，可以同时存在」；那句里的
 * 「提前播放时间暂时借用」是当时的临时状态，09-28 续已经取消 —— 见下）：
 * <ol>
 *   <li><b>自定义进站广播</b> —— 音频库里的素材（{@code /pbmarrive}/{@code -f}），
 *       自己的开关就是它那一项设成「不播」；</li>
 *   <li><b>讲述人进站广播</b> —— 用文字转语音把到站信息念出来（{@link TrainAnnounceNarrator}），
 *       <b>本类</b>就是它唯一的总开关。</li>
 * </ol>
 * ★ 两者**互不耦合**（【09-28】重新定过）：早先只共用「到站前 N 秒」那个窗口，现在
 * 讲述人有**自己**的窗口（石斧 UI「进站广播(讲述人)」那一行的秒数框 =
 * {@code PsdToneAudio.narrateSeconds}）。**不播 ≠ 取消**：自定义那条设成「不播」
 * 只关它自己，讲述人照念；反过来 {@code /jsr off} 也只让讲述人闭嘴。
 * <p>★【09-28】讲述人从此有**两层**：
 * <ol>
 *   <li>{@code /jsr on|off} —— <b>本类</b>，全局总闸（跨启动记住，写 config 文件）；</li>
 *   <li>石斧 UI 二级页「关闭 / 开启(上海) / 开启(香港)」 —— 每**串**屏蔽门自己的
 *       **样式**（{@code PsdToneAudio.narrate}，三档 int，门覆盖 &gt; 维度默认）。
 *       ★【09-28 续】这一层由「开/关」升级成**三种样式**（用户点名），
 *       「关闭」= {@code PSD_NARRATE_OFF}、另两档 = 两种播报词
 *       （见 {@link TrainAnnounceNarrator#arriveTextForStyle}）。</li>
 * </ol>
 * 播放端两层取「与」：全局关着，任何一串都不念（样式一律当「关闭」处理）；全局开着，
 * 才轮到那一串自己的样式说话 —— 所以**这一层选的是「念哪一句」，不是「念不念」**。
 * <p>1.28 的第一版把 {@code /jsr} 挂在了**自定义那条链路**里（关它 = 整条 {@code tickArriveAnnounce}
 * 短路），于是「自定义那条设了不播」⇒ 讲述人也一起没了 —— 用户报「还是没有声音」就是这个。
 * 09-28 已把两条拆开，判据各在各的地方。
 *
 * <h2>二、为什么是「文字转语音」而不是游戏那个讲述人</h2>
 * 原版讲述人要出声得先满足 设置 → 辅助功能 → 讲述人 = 全部/系统，且本机有语音引擎，
 * 否则静默。本模组照抄的是 MTR 报站的入口（{@code com.mojang.text2speech.Narrator}，
 * 不查那个设置）—— 详见 {@link TrainAnnounceNarrator} 的类注释。
 *
 * <h2>三、配置持久化</h2>
 * {@code config/smoothlift-jsr.properties}，键 {@code trainArriveAnnounce}，
 * 值 {@code on} / {@code off}，**默认开**（键不存在 / 写坏了都按「开」处理 ——
 * 与 1.21 以来「配好了就播」的行为逐位一致）。1.28 的配置文件格式不变，可直接沿用。
 */
public final class TrainAnnounceSwitch {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** config 文件名（放在 FabricLoader 的 config 目录下）。 */
    private static final String CONFIG_FILE = "smoothlift-jsr.properties";
    /** properties 键名。值：{@code off} = 关（/jsr off）；其余一律按「开」处理。 */
    private static final String KEY_ENABLED = "trainArriveAnnounce";
    private static final String VALUE_OFF = "off";

    /** 当前开关。**默认开** —— 装上模组就念，不需要任何指令。 */
    private static boolean enabled = true;

    private TrainAnnounceSwitch() {
    }

    /** 客户端初始化时调用一次：从 config 读回上次的开关。 */
    public static void load() {
        Path file = configFile();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[SmoothLift] 读取列车报站开关配置失败，使用默认（开）: {}", file, e);
            return;
        }
        // 只有明确写成 off 才关；键不存在（老配置）/ 写坏了都按默认「开」处理。
        enabled = !VALUE_OFF.equals(props.getProperty(KEY_ENABLED));
        LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人列车报站：{}（/jsr on|off 切换；"
                + "念什么由模组拼这一班车的终点站与站台编号，什么时候念用它自己的"
                + "「到站前 N 秒」——石斧右键屏蔽门 UI 的「进站广播(讲述人)」那一行）",
                enabled ? "开" : "关");
    }

    /** 讲述人进站广播的总开关。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 【09-28 续 2】「港铁预设」按钮用：把全局总闸设成**开**并立刻落盘。
     *
     * <p>为什么非得由客户端来做：「港铁预设」是**服务端**依次执行一串指令
     * （见 {@code PRESET_CLASSIC_MTR}），而本类是**客户端**配置（config 文件）——
     * 服务端那串指令碰不到它。预设里那一条 {@code pbmnarrate <样式> -f} 只设**样式**
     * （＝「念哪一句」），「念不念」由本总闸管。用户点名「预设也会顺带把总闸打开」，
     * 所以由**客户端**那一下点击顺手打开（见 {@link MbmHelpScreen#sendPreset}）。
     *
     * <p>★【09-28 续 4】只有「经典港铁预设」调这个（它是唯一开讲述人的预设）；
     * 简单港铁 / 空白预设走 {@link #disableForPreset()}。
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
     * <p>与 {@link #enableForPreset()} 严格对称，理由也同源：用户点名「简单港铁预设 和
     * 空白预设 都是要关闭讲述人的」——「关」要**两层一起关**才算关干净：
     * <ol>
     *   <li>**样式层**（服务端）：预设末条 {@code pbmnarrate off -f}；</li>
     *   <li>**总闸层**（本类，客户端 config）：就是这里。</li>
     * </ol>
     * ★ 只关样式层也能「听不见」（总闸与样式取「与」），但存档里总闸仍写着「开」——
     * 玩家之后去石斧 UI 随手挑一档样式，讲述人就会**突然又响**。用户说的是「关闭讲述人」，
     * 所以总闸必须一起关（真正「关干净」），也与经典预设的「两层一起开」对称。
     *
     * @return 有没有真的改动（本来就是关的 ⇒ {@code false}，且不写盘）
     */
    public static boolean disableForPreset() {
        if (!enabled) {
            return false;
        }
        enabled = false;
        save();
        LOGGER.info("[SmoothLift/TrainAnnounce] 应用港铁预设：讲述人列车报站的全局总闸已关闭");
        return true;
    }

    /** {@code /jsr}（无参数）：显示当前状态与说明。 */
    public static int show(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal(
                "[SmoothLift] 讲述人进站广播：" + (enabled ? "开" : "关")
                        + "，/jsr on 开启，/jsr off 关闭，默认开"));
        ctx.getSource().sendFeedback(Component.literal(
                "[SmoothLift] 它和自定义进站广播是两条广播，可以同时存在；"
                        + "自定义那条设成不播不会影响它。念什么由**样式**决定（石斧二级页 / "
                        + "/pbmnarrate）：开启(上海)＝「乘客们，列车马上就要进站了，本次列车终点站：X，"
                        + "请乘客们在Y站台有序候车」（X=这一班车的终点站，Y=站台编号）；"
                        + "开启(香港)＝「前往X的列车即将到达，请先让车上的乘客下车 ⏎ The train to X "
                        + "is arriving, please let passengers exit first.」"
                        + "（X = **本次列车终点站**按 MTR 的「中文|English」拆成中英两句）。"));
        ctx.getSource().sendFeedback(Component.literal(
                "[SmoothLift] 这一条 /jsr 是**全局**开关；每一串屏蔽门还可以各自选样式 +"
                        + "各自设提前秒数 —— 石斧右键那串屏蔽门 →「进站广播(讲述人)」"
                        + "（二级页「关闭 / 开启(香港) / 开启(上海)」，主界面那一行填「到站前 N 秒」）；"
                        + "也可以直接敲 /pbmnarrate off|shanghai|hongkong（加 -f = 所有维度）。"));
        // ★【09-28 续 4】三个预设对讲述人的态度是**定死**的（用户点名），写在这里免得玩家去猜。
        ctx.getSource().sendFeedback(Component.literal(
                "[SmoothLift] mbmhelp 的三个预设：经典港铁预设＝开启(香港)，"
                        + "简单港铁预设 / 空白预设＝**关闭**（按预设时本总闸会一起跟着开/关）。"));
        return 1;
    }

    /** {@code /jsr on|off}：开关讲述人进站广播（立即保存，跨启动保持）。 */
    public static int set(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        if (enabled == on) {
            ctx.getSource().sendFeedback(Component.literal(
                    "[SmoothLift] 讲述人进站广播本来就是" + (on ? "开" : "关") + "的，无需切换"));
            return 1;
        }
        enabled = on;
        save();
        if (on) {
            ctx.getSource().sendFeedback(Component.literal(
                    "[SmoothLift] 讲述人进站广播已开启，已记住，下次启动保持："
                            + "各站台在它自己设定的「剩 N 秒到站」时念出这一班车的终点站与站台编号"
                            + "（N 在石斧右键屏蔽门 →「进站广播(讲述人)」那一行里填）"));
            LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人列车报站已开启（/jsr on）");
        } else {
            ctx.getSource().sendFeedback(Component.literal(
                    "[SmoothLift] 讲述人进站广播已关闭，已记住，下次启动保持："
                            + "只关讲述人这一条（**所有**门串都闭嘴），自定义进站广播照旧，"
                            + "/jsr on 恢复"));
            LOGGER.info("[SmoothLift/TrainAnnounce] 讲述人列车报站已关闭（/jsr off）");
        }
        return 1;
    }

    private static void save() {
        Path file = configFile();
        if (file == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty(KEY_ENABLED, enabled ? "on" : VALUE_OFF);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "mzycBetterMTR train arrive announce switch (/jsr, 1.28.1204)");
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
}
