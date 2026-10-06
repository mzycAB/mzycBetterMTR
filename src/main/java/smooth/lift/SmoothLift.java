package smooth.lift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.PacketDistributor;
import smooth.lift.network.MbmHelpOpenPacket;
import smooth.lift.network.MbmOpenFolderPacket;
import smooth.lift.network.Packets;
import smooth.lift.network.PsdNearestRequestPacket;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@net.minecraftforge.fml.common.Mod("smooth_lift")
public class SmoothLift {
    public static final String MOD_ID = "smooth_lift";


    /**
     * 【10-01】每个玩家「本次设置界面会话」的结果。
     *
     * <p>界面内部的每一次操作**不再**逐条往聊天框打长句，而是把结果并进这里；
     * 玩家退出界面时（{@link #UI_CLOSE_CHANNEL}）统一回一条「UI执行成功 / UI执行失败」。
     * 只要会话内有一次失败就**保持** false（合并用「与」），后面成功不会把失败抹掉。
     */
    private static final Map<UUID, Boolean> UI_SESSION_OK = new ConcurrentHashMap<>();

    /** 【10-01】记录一次界面操作的结果（见 {@link #UI_SESSION_OK}）。 */
    public static void noteUiResult(ServerPlayer player, boolean ok) {
        if (player == null) {
            return;
        }
        UI_SESSION_OK.merge(player.getUUID(), ok, (a, b) -> a && b);
    }

    /** 客户端 -> 服务端：请求设置某个扶梯的【运行】速度。 */
    public static final ResourceLocation SET_SPEED_CHANNEL = new ResourceLocation("smoothlift", "set_speed");
    /** 客户端 -> 服务端：石斧界面按 ESC 退出时，一次性应用「扶梯速度 + 阶梯速度」的改动。 */
    public static final ResourceLocation APPLY_CHAIN_CHANNEL = new ResourceLocation("smoothlift", "apply_chain");
    /** 客户端 -> 服务端：请求设置某条扶梯的【阶梯动画】速度。 */
    public static final ResourceLocation SET_STEP_SPEED_CHANNEL = new ResourceLocation("smoothlift", "set_step_speed");
    /** 客户端 -> 服务端：请求把某条扶梯的阶梯动画对齐到它的运行速度。 */
    public static final ResourceLocation ALIGN_STEP_CHANNEL = new ResourceLocation("smoothlift", "align_step");
    /** 客户端 -> 服务端：请求把某条扶梯的阶梯动画恢复为 MTR 原版默认。 */
    public static final ResourceLocation RESTORE_STEP_CHANNEL = new ResourceLocation("smoothlift", "restore_step");
    /** 服务端 -> 客户端：同步全部速度与阶梯动画数据。 */
    public static final ResourceLocation SYNC_CHANNEL = new ResourceLocation("smoothlift", "sync");
    /** 客户端 -> 服务端：客户端进世界后主动请求同步（JOIN 时序下服务端推送不可靠）。 */
    public static final ResourceLocation REQUEST_SYNC_CHANNEL = new ResourceLocation("smoothlift", "request_sync");

    /**
     * 【10-01】客户端 -> 服务端：玩家**退出设置界面**时发来（界面内部的逐条操作不再单独提示）。
     * 服务端据此把「本次界面会话」的结果回一条到聊天框：「UI执行成功」或「UI执行失败」。
     */
    public static final ResourceLocation UI_CLOSE_CHANNEL = new ResourceLocation("smoothlift", "ui_close");

    // 【1.7】自定义扶梯声音
    /** 客户端 -> 服务端：把音频绑定到某条扶梯。 */
    public static final ResourceLocation BIND_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "bind_audio");
    /** 客户端 -> 服务端：解绑某条扶梯的音频（之后静音）。 */
    public static final ResourceLocation UNBIND_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "unbind_audio");
    /** 客户端 -> 服务端：从存档删除一段音频（同时解绑所有引用它的扶梯）。 */
    public static final ResourceLocation DELETE_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "delete_audio");
    /** 客户端 -> 服务端：把 <存档>/MBM_Audio 里的一个 OGG 文件导入存档并绑定到扶梯。 */
    public static final ResourceLocation IMPORT_FOLDER_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "import_folder_audio");
    /** 服务端 -> 客户端：分块同步音频库与扶梯-音频绑定。 */
    public static final ResourceLocation AUDIO_SYNC_CHANNEL = new ResourceLocation("smoothlift", "audio_sync");
    /** 【1.18.1204】服务端 -> 客户端：分块同步地图图片库（源图字节，客户端自行切图/缩放/加灰边）。 */
    public static final ResourceLocation PICTURE_SYNC_CHANNEL = new ResourceLocation("smoothlift", "picture_sync");
    /** 【1.9】客户端 -> 服务端：设置某条扶梯的声音音量（1~100）。 */
    public static final ResourceLocation SET_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_volume");
    /** 【1.9】服务端 -> 客户端：同步「扶梯方块 → 声音音量」表（小包，不含音频字节）。 */
    public static final ResourceLocation VOLUME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "volume_sync");
    /** 【1.16】客户端 -> 服务端：开关某条扶梯的无障碍提示音（石斧界面按钮）。 */
    public static final ResourceLocation SET_HELP_CHANNEL = new ResourceLocation("smoothlift", "set_help");
    /** 【1.16】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音开关」表（小包）。 */
    public static final ResourceLocation HELP_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_sync");
    /** 【1.18】客户端 -> 服务端：设置某条扶梯的无障碍提示音音量（石斧界面里的输入框）。 */
    public static final ResourceLocation SET_HELP_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_help_volume");
    /** 【1.18】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音音量」表（小包，不含音频字节）。 */
    public static final ResourceLocation HELP_VOLUME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_volume_sync");
    /** 【1.24】服务端 -> 客户端：同步「扶梯方块 → 运行底噪可闻范围（格）」表（小包）。 */
    public static final ResourceLocation ROUND_SYNC_CHANNEL = new ResourceLocation("smoothlift", "round_sync");
    /** 【1.24】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音可闻范围（格）」表（小包）。 */
    public static final ResourceLocation HELP_ROUND_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_round_sync");
    /** 【1.31】服务端 -> 客户端：同步无障碍提示音**速率**（入口 / 出口两套 Hz，同一只包）。 */
    public static final ResourceLocation HELP_SPEED_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_speed_sync");
    /**
     * 【1.39】客户端 -> 服务端：把一段音频设为某条扶梯的**无障碍提示音音乐**。
     *
     * <p>与 {@link #BIND_AUDIO_CHANNEL}（运行底噪）分开两只包、两套数据：
     * 用的是同一个导入文件夹和同一份音频库，但「哪段声音当提示音」是另一件事。
     */
    public static final ResourceLocation BIND_HELP_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "bind_help_audio");
    /** 【1.39】客户端 -> 服务端：清掉某条扶梯的提示音音乐单独设置（回到维度默认）。 */
    public static final ResourceLocation UNBIND_HELP_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "unbind_help_audio");
    /** 【1.39】客户端 -> 服务端：把 MBM_Audio 文件夹里的一个 OGG 导入存档并设为提示音音乐。 */
    public static final ResourceLocation IMPORT_FOLDER_HELP_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "import_folder_help_audio");
    /** 【1.39】服务端 -> 客户端：同步「默认提示音音乐 + 扶梯方块 → 提示音音乐」（小包）。 */
    public static final ResourceLocation HELP_AUDIO_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_audio_sync");
    /**
     * 【1.42】服务端 -> 客户端：同步**直梯（Lift）开关门提示音**（开关 + 倍速，按维度）。
     *
     * <p>这是「MTR 直梯关门连播 4 次 / 开门连播 2 次 liftmusic.ogg」那套设置的镜像通道，
     * 与上面所有扶梯提示音频道**都是独立的两件事**（数据、指令、播放器都不同）。
     * 包体最小：只有 {@code 维度ID + 开关 + 倍速} 三个值，没有按方块索引的表。
     */
    public static final ResourceLocation LIFT_CHIME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "lift_chime_sync");

    /** 【1.45】客户端 -> 服务端：设置某条直梯的某一项提示音（up / down / chime）。 */
    public static final ResourceLocation SET_LIFT_TONE_CHANNEL = new ResourceLocation("smoothlift", "set_lift_tone");
    /** 【1.48】客户端 -> 服务端：设置**共用默认音量**（石斧 UI 主界面输入框 = /lifthelploud <音量>）。 */
    public static final ResourceLocation SET_LIFT_CHIME_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_lift_chime_volume");
    /** 【1.48】客户端 -> 服务端：设置某一项（up/down/open/close）的**单项音量**（石斧 UI 列表输入框 = /lifthelploud up|down|open|close）。 */
    public static final ResourceLocation SET_LIFT_TONE_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_lift_tone_volume");
    /** 【1.46】客户端 -> 服务端：设置某类提示音（up/down/chime）的**维度默认子开关**（石斧 UI 开关）。 */
    public static final ResourceLocation SET_LIFT_TONE_SWITCH_CHANNEL = new ResourceLocation("smoothlift", "set_lift_tone_switch");
    /** 【1.45】客户端 -> 服务端：把 MBM_Audio 里的一个 OGG 导入并存为某条直梯的某一项提示音。 */
    public static final ResourceLocation IMPORT_FOLDER_LIFT_TONE_CHANNEL = new ResourceLocation("smoothlift", "import_folder_lift_tone");
    /** 【1.45】服务端 -> 客户端：同步「竖井列 → 直梯提示音」表（小包，不含音频字节）。 */
    public static final ResourceLocation LIFT_TONE_SYNC_CHANNEL = new ResourceLocation("smoothlift", "lift_tone_sync");

    // ------------------------------------------------------------------
    // 【09-30】闸机（MTR Ticket Barrier）提示音的频道
    //   进站 / 出站各一份设置，**只有维度默认这一层**（没有「按某一台闸机单独设置」的表，
    //   所以通道数比直梯 / 屏蔽门少一半：不需要 ×_SYNC 之外的第二条小包）。
    // ------------------------------------------------------------------

    /** 【09-30】客户端 -> 服务端：设置某一侧闸机的提示音素材（which = in / out）。 */
    public static final ResourceLocation SET_ZHAJI_TONE_CHANNEL = new ResourceLocation("smoothlift", "set_zhaji_tone");
    /** 【09-30】客户端 -> 服务端：设置某一侧闸机的提示音音量（1~1000）＝ /zhajiloud in|out <音量>。 */
    public static final ResourceLocation SET_ZHAJI_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_zhaji_volume");
    /** 【09-30】客户端 -> 服务端：把 MBM_Audio/zhaji/in|out 里的一个 OGG 导入并存为那一侧的提示音。 */
    public static final ResourceLocation IMPORT_FOLDER_ZHAJI_TONE_CHANNEL = new ResourceLocation("smoothlift", "import_folder_zhaji_tone");
    /** 【09-30】服务端 -> 客户端：同步闸机提示音设置（素材 + 音量，按维度，两侧各一份）。 */
    public static final ResourceLocation ZHAJI_TONE_SYNC_CHANNEL = new ResourceLocation("smoothlift", "zhaji_tone_sync");

    // ------------------------------------------------------------------
    // 【1.50】列车屏蔽门（PSD / APG）开关门提示音的频道
    //   与直梯那一组（LIFT_*）**一一对应**，只是「项」从 up/down/chime 变成 open/close，
    //   并且多了「每扇门单独素材」那一张表（直梯没有方块粒度）。
    // ------------------------------------------------------------------

    /** 【1.50】服务端 -> 客户端：同步屏蔽门提示音设置（总开关 + 音量 + 两项子开关 + 范围，按维度）。 */
    public static final ResourceLocation PSD_CHIME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "psd_chime_sync");
    /** 【1.50】服务端 -> 客户端：同步「门锚点 → 屏蔽门提示音」表（小包，不含音频字节）。 */
    public static final ResourceLocation PSD_TONE_SYNC_CHANNEL = new ResourceLocation("smoothlift", "psd_tone_sync");
    /** 【1.50】客户端 -> 服务端：设置某一扇门的一项提示音（open / close）。 */
    public static final ResourceLocation SET_PSD_TONE_CHANNEL = new ResourceLocation("smoothlift", "set_psd_tone");
    /** 【1.50】客户端 -> 服务端：设置屏蔽门提示音的开关（which = master / open / close）。 */
    public static final ResourceLocation SET_PSD_TONE_SWITCH_CHANNEL = new ResourceLocation("smoothlift", "set_psd_tone_switch");
    /** 【1.50】客户端 -> 服务端：设置屏蔽门提示音的**共用默认音量**（石斧 UI 主界面输入框 = /pbmloud <音量>）。 */
    public static final ResourceLocation SET_PSD_CHIME_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_psd_chime_volume");
    /** 【1.50】客户端 -> 服务端：设置某一项（open/close）的**单项音量**（石斧 UI 列表输入框 = /pbmloud open|close <音量>）。 */
    public static final ResourceLocation SET_PSD_TONE_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_psd_tone_volume");
    /** 【1.50】客户端 -> 服务端：把 MBM_Audio 里的一个 OGG 导入并存为某一扇门的一项提示音。 */
    public static final ResourceLocation IMPORT_FOLDER_PSD_TONE_CHANNEL = new ResourceLocation("smoothlift", "import_folder_psd_tone");
    /**
     * 【1.16】客户端 -> 服务端：设置**关门提示音的强制等待时长**
     * （石斧 UI 主界面「关门提示音强制等待时长」输入框 = {@code /pbmclosewait <秒>}）。
     */
    public static final ResourceLocation SET_PSD_CLOSE_WAIT_CHANNEL = new ResourceLocation("smoothlift", "set_psd_close_wait");

    /** 【1.23】客户端 -> 服务端：设「开门提示」行的等待秒数（对称项）。buf：key(long) → seconds(varInt)。 */
    public static final ResourceLocation SET_PSD_OPEN_WAIT_CHANNEL = new ResourceLocation("smoothlift", "set_psd_open_wait");
    /**
     * 【1.17】客户端 -> 服务端：设置**到站播报**
     * （石斧 UI 主界面「到站播放音频」+「等待几秒后播放」输入框 = {@code /pbmmidium <名字> <秒>}）。
     *
     * <p>buf 顺序：{@code name(utf128) → seconds(varInt)}。
     */
    public static final ResourceLocation SET_PSD_MIDIUM_CHANNEL = new ResourceLocation("smoothlift", "set_psd_midium");
    /**
     * 【1.19】客户端 -> 服务端：把 {@code MBM_Audio} 文件夹里的一段 OGG **只导入存档音频库、
     * 不改变任何设置**（石斧 UI 到站播报页左列「点一下」= 导入）。
     *
     * <p>为什么与 {@link #SET_PSD_MIDIUM_CHANNEL} 分成两条：用户点名「导入的**不直接选用**，
     * 要在导入的音频的右侧加 2 个按钮，一个是选用，一个是删除」—— 导入与选用从此是两件事，
     * 「点一下左列」只负责把素材搬进存档。
     *
     * <p>buf 顺序：{@code name(utf128)}。
     */
    public static final ResourceLocation IMPORT_PSD_MIDIUM_AUDIO_CHANNEL =
            new ResourceLocation("smoothlift", "import_psd_midium_audio");
    /**
     * 【1.21】客户端 -> 服务端：设置**进站报站**
     * （石斧 UI 主界面「进站播放音频」+「到站前秒数」输入框
     * = {@code /pbmarrive <名字> <X>}）。
     *
     * <p>buf 顺序：{@code key(long) → name(utf128) → seconds(varInt)}，与
     * {@link #SET_PSD_MIDIUM_CHANNEL} 同形（{@code seconds} 是**负**的秒数：
     * {@code -10} = 最近一班车还剩 10 秒到站时起播）。
     */
    public static final ResourceLocation SET_PSD_ARRIVE_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_arrive");
    /**
     * 【1.22】客户端 -> 服务端：设置**到站播报自己那一项**的音量
     * （石斧 UI 主界面「到站播放音频」右边那个「音量:」输入框
     * = {@code /pbmmidiumloud <音量>}）。
     *
     * <p>buf 顺序：{@code key(long) → volume(varInt)}，与
     * {@link #SET_PSD_CHIME_VOLUME_CHANNEL} 同形。
     */
    public static final ResourceLocation SET_PSD_MIDIUM_LOUD_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_midium_loud");
    /**
     * 【1.22】客户端 -> 服务端：设置**进站报站自己那一项**的音量
     * （= {@code /pbmarriveloud <音量>}）。buf 同上。
     */
    public static final ResourceLocation SET_PSD_ARRIVE_LOUD_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_arrive_loud");
    /**
     * 【09-28】客户端 -> 服务端：设置**进站广播（讲述人）**的开关
     * （石斧 UI 「进站广播(讲述人)」二级页右列「关闭 / 开启」各自那个「选择」按钮）。
     *
     * <p>buf 顺序：{@code key(long) → on(boolean)}。
     */
    public static final ResourceLocation SET_PSD_NARRATE_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_narrate");
    /**
     * 【09-28】客户端 -> 服务端：设置**进站广播（讲述人）**的提前秒数
     * （石斧 UI 主界面「进站广播(讲述人)」行右侧那个秒数框）。
     *
     * <p>★ 它是一个**独立**窗口（用户点名「取消借用进站广播」）：范围 (-∞, 0]，
     * 含义与进站报站逐字相同（最近一班车还剩 |X| 秒到站时开始念），但两边各存各的。
     *
     * <p>buf 顺序：{@code key(long) → seconds(varInt)}，与 {@link #SET_PSD_OPEN_WAIT_CHANNEL} 同形。
     */
    public static final ResourceLocation SET_PSD_NARRATE_LEAD_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_narrate_lead");
    /**
     * 【09-30 续 3】客户端 -> 服务端：设置**站台广播（讲述人）**的样式
     * （石斧 UI「站台广播(讲述人)」二级页右列「选择」按钮）。
     *
     * <p>与 {@link #SET_PSD_NARRATE_CHANNEL} 同构，只是它写的是 {@code PsdToneAudio.midiumNarrate}
     * （站台播报那条链路的讲述人）；档位编号**共用**同一套（0 关 / 1 上海 / 2 香港 / 3+ userN）。
     *
     * <p>buf 顺序：{@code key(long) → mode(varInt)}。
     */
    public static final ResourceLocation SET_PSD_MIDIUM_NARRATE_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_midium_narrate");
    /**
     * 【09-30 续 3】客户端 -> 服务端：设置**站台广播（讲述人）**的等待秒数
     * （石斧 UI 主界面「站台广播(讲述人)」行右侧那个秒数框）。
     *
     * <p>范围 [0, +∞) —— 与进站讲述人的 (-∞, 0] 方向相反：站台广播是开门**之后**的事，
     * 含义与 pbmmidium 的等待秒数逐字相同（开门音播完之后再等 Y 秒开念）。
     *
     * <p>buf 顺序：{@code key(long) → seconds(varInt)}。
     */
    public static final ResourceLocation SET_PSD_MIDIUM_NARRATE_LEAD_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_midium_narrate_lead");

    // ------------------------------------------------------------------
    // 【10-05】pbm* 指令不带 -f 时的「最近门串」链路
    //   指令跑在服务端线程，而「哪一串最近」只有客户端算得出来（要 MTR 客户端数据 +
    //   渲染每帧上报的 LIVE 快照）⇒ 走一次「S2C 请求 → 客户端算 runKey → C2S 回包 → 服务端落地」。
    //   ★ 玩家看到的**反馈只在回包那一侧给一条**（成功 / 失败）—— 请求侧不回，免得一次操作两条消息。
    //   ★ 门串身份用 {@code PsdDoorTracker.runKeyOf}（与石斧 UI / 播放端同一把尺子）。
    // ------------------------------------------------------------------

    /**
     * 【10-05】服务端 -> 客户端：请客户端算一次「离玩家最近的那一串屏蔽门」。
     *
     * <p>空包 —— 「具体要做哪一件事」留在服务端的待办队列（{@link #PENDING_NEAREST}）里，
     * 这一包只负责触发客户端算 runKey。
     */
    public static final ResourceLocation PSD_NEAREST_REQUEST_CHANNEL =
            new ResourceLocation("smoothlift", "psd_nearest_request");

    /**
     * 【10-05】客户端 -> 服务端：回「最近的那一串」的 runKey。
     *
     * <p>buf 顺序：{@code found(boolean) → runKey(long)}（{@code found == false} 时 runKey 无意义）。
     */
    public static final ResourceLocation PSD_NEAREST_REPLY_CHANNEL =
            new ResourceLocation("smoothlift", "psd_nearest_reply");

    // ------------------------------------------------------------------
    // 【1.53】「预设选择」界面（/MBM help）与三个「港铁预设」
    //   预设的做法是**把用户点名的那几条指令原样派发一遍**
    //   （{@code performPrefixedCommand}），不是把各条的效果手抄成 setter 调用。
    //   这样「界面上按一下」与「自己敲那几条」在语义上**永远是同一件事**：
    //   指令树里 on/off/<名字> 到底落到哪个分支，由 Brigadier 自己决定，不靠人猜。
    // ------------------------------------------------------------------

    /** 【1.53】服务端 -> 客户端：打开「预设选择」界面（无数据）。 */
    public static final ResourceLocation MBM_HELP_OPEN_CHANNEL =
            new ResourceLocation("smoothlift", "mbm_help_open");
    /** 【1.53】客户端 -> 服务端：应用一个「港铁预设」（buf：presetId(utf16)）。 */
    public static final ResourceLocation MBM_PRESET_CHANNEL =
            new ResourceLocation("smoothlift", "mbm_preset");
    /**
     * 【1.53】客户端 -> 服务端：把**模组的所有音量**一起设成同一个值
     * （buf：volume(varInt)，1~1000）。
     *
     * <p>走到 {@code /futiloud -f}、{@code /futihelploud -f}、{@code /lifthelploud -f
     * [up|down|door]}、{@code /pbmloud -f [open|close]}、{@code /pbmmidiumloud -f}、
     * {@code /pbmarriveloud -f} 这一组指令上 —— 同样靠派发指令，不另写一套 setter。
     */
    public static final ResourceLocation MBM_ALL_VOLUME_CHANNEL =
            new ResourceLocation("smoothlift", "mbm_all_volume");

    /**
     * 【09-29】服务端 -&gt; 客户端：请你在**本机**把存档目录下的某个文件夹开出来
     * （buf：{@code relativePath(utf64)}，相对存档根目录，如 {@code MBM_Picture}、
     * {@code MBM_Audio/pbm/arrive}）。
     *
     * <p>为什么必须走网络：文件夹在**存档**里 ⇒ 躺在服务端那台机器上，而「开一个文件夹窗口」
     * 只有客户端能做。单人游戏两边是同一台机器，所以玩家看到的就是自己那个存档文件夹。
     *
     * <p>★ 客户端**不复用**服务端给的绝对路径（那在多人下是错的），只拿这个**相对路径**
     * 接自己的存档根，并且照样过一遍白名单（见 {@code FolderOpenButton.open}）。
     *
     * <p>目前唯一的发送方：{@code /MBM picture fold}（图片导入文件夹）。界面右上角那个
     * 「打开文件夹」按钮不走这一只包 —— 它本来就是客户端算的，直接本地开。
     */
    public static final ResourceLocation MBM_OPEN_FOLDER_CHANNEL =
            new ResourceLocation("smoothlift", "mbm_open_folder");

    // ------------------------------------------------------------------
    // 【1.57】「列车音效」界面（**石斧右键侧线铁轨**打开，不再是指令）
    //
    //   ★【1.57】用户点名改了三件事：
    //     ① 入口：`/MBM train music` **撤销**；改成**石斧右键侧线的轨道节点**
    //        （{@code mtr:rail}，属于侧线时被 MTR 染成黄色 —— 判据见 {@link #isMtrRail}）。
    //        轨道连接处同时连着两段轨道时，开的是**玩家面向的那一段**那个界面
    //        （MTR 自带 {@code MinecraftClientData.getFacingRailAndBlockPos}，客户端直接用）。
    //     ② 射程：**每条侧线各自一份**，不是全局 —— 身份 = MTR 侧线 id
    //        （客户端 {@code MtrSidingAccess} 认出来，随界面带着走；见 {@link #syncSettings}）。
    //     ③ 每个音效按钮点进去是**二级列表页**（与屏蔽门「站台音效」同款两列版式：
    //        左列未导入存档 / 中间竖线 / 右列已导入存档，右列第 0 行 =「MTR自带音效」），
    //        底部一个「淡入淡出:」秒数框。
    //   ★ 用户点名「这些按钮的功能先不做」⇒ 仍然**没有数据层**：{@link #syncTrain} 只回话不写，
    //     不会假装成功（假成功比不实现更糟）。
    // ------------------------------------------------------------------

    /**
     * 【1.57】「列车音效」五个二级页的**射程编号**（等于客户端那五个页的页号）。
     *
     * <p>★ 必须与客户端 {@code TrainSoundScreen} 的「页号 = 序号 + 1、顺序 = 用户给的按钮顺序」
     * **同序同内容**（回归里单独成节断言）。客户端把页号原样当 scope 发下来，
     * 错位是**不报错**的 —— 只是同步到了另一项。
     */
    public static final int SYNC_TRAIN_RUN = 1;
    public static final int SYNC_TRAIN_TURN = 2;
    public static final int SYNC_TRAIN_SWITCH = 3;
    public static final int SYNC_TRAIN_ARRIVE = 4;
    public static final int SYNC_TRAIN_DEPART = 5;

    // ------------------------------------------------------------------
    // 【1.55】「同步」：扶梯 / 直梯 / 屏蔽门三个界面右上角的「同步所有」按钮
    //
    //   数据模型是两层：**维度默认**（或服务端默认）+ **按项单独设置**（若干 Map）。
    //   两个按钮就落在这两层上 ——
    //     · 「同步所有」  = 把**当前这一项**此刻生效的值写进「默认」那一层。
    //       没单独设置过的项都跟着默认走 ⇒ 自动获得同一个值；单独设置过的项
    //       保留自己的值 ⇒ 就是用户说的「不包括修改过的」。**不需要枚举所有项。**
    //     · 「强制同步」  = 同样写默认，**外加清掉这一项的单独设置** —— 正是指令里
    //       `-f` 那一支（如 {@code /futiloud -f}、{@code /pbmmusic open -f}）。
    //       于是连「修改过的」也一起变成同一个值。
    //   ★ 两个按钮的**射程完全一样**，都由界面传来的 {@code scope} 决定；差别只在 force。
    //   ★ 射程 = 玩家当前所在的那一层菜单：一级菜单 = 该域在那一页的全部设定；
    //     二级菜单（单项提示音 / 素材页）= 只同步那一项。详见 {@link #syncSettings}。
    // ------------------------------------------------------------------

    /**
     * 【1.55】客户端 -> 服务端：执行一次「同步」。
     *
     * <p>buf 顺序：{@code domain(utf16) · scope(varInt) · force(boolean) · key(long)}
     * <ul>
     *   <li>{@code domain} = {@code esc} 扶梯 / {@code lift} 直梯 / {@code psd} 屏蔽门；</li>
     *   <li>{@code scope} = 0 一级菜单；≥1 二级菜单（各域的子页编号，见 {@code syncSettings}）；</li>
     *   <li>{@code force} = false 只写默认（等于不带 -f 的指令）；true 连单独设置一起清（等于 -f）；</li>
     *   <li>{@code key} = 当前这一项的身份：扶梯 = {@code BlockPos.asLong}；
     *       直梯 = 竖井列 key（{@code liftToneKey}）；屏蔽门 = {@code PsdDoorTracker.runKeyOf}。</li>
     * </ul>
     */
    public static final ResourceLocation SYNC_SETTINGS_CHANNEL =
            new ResourceLocation("smoothlift", "sync_settings");



    /** 手里拿的是不是 MTR 的扶梯物品（按类名判断，避免编译期依赖 MTR）。 */
    private static boolean isEscalatorItem(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem().getClass().getName().toLowerCase(Locale.ROOT).contains("escalator");
    }

    // ------------------------------------------------------------------
    // /futispeed
    // ------------------------------------------------------------------

    /**
     * /futispeed（不带参数）—— 显示当前扶梯速度。
     *
     * <p>能定位到「玩家当前所在的扶梯」（脚下/身上 → 准星 → 附近最近）就显示那一条的速度，
     * 并标出它是单独设置还是跟随全局；否则显示全局默认值。
     */
    private static int futiShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double global = EscalatorSpeedManager.getGlobalRunSpeed(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(EscalatorSpeedData.format(global)), false);
            return 1;
        }
        double speed = EscalatorSpeedManager.getSpeed(level, pos);
        boolean individual = EscalatorSpeedManager.getIndividualRunSpeed(level, pos) != null;
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(EscalatorSpeedData.format(speed)), false);
        return 1;
    }

    /** 注册 `-f` 分支：`-f X` 与 `-f X to Y`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::futiForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::futiForceFromTo))));
    }

    /** /futispeed X —— 只改全局扶梯的运行速度（阶梯速度一起跟随）。 */
    private static int futiGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setGlobalRunSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    /** /futispeed X to Y —— 只有当前全局运行速度正好是 X 时才改成 Y。 */
    private static int futiFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double current = EscalatorSpeedManager.getGlobalRunSpeed(level);
        if (!EscalatorSpeedManager.same(current, from)) {
            source.sendSuccess(
                    () -> Component.literal("指令执行失败"),
                    false);
            return 0;
        }
        EscalatorSpeedManager.setGlobalRunSpeed(level, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    /** /futispeed -f X —— 强制游戏内所有扶梯运行速度 = X。 */
    private static int futiForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceGlobalRunSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    /** /futispeed -f X to Y —— 把所有运行速度为 X 的扶梯改成 Y。 */
    private static int futiForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean globalMatched = EscalatorSpeedManager.same(EscalatorSpeedManager.getGlobalRunSpeed(level), from);
        int changed = EscalatorSpeedManager.forceRunFromTo(level, from, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        if (!globalMatched && changed == 0) {
            source.sendSuccess(
                    () -> Component.literal("指令执行失败"),
                    false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /jietispeed （只动阶梯速度，绝不动运行速度）
    // ------------------------------------------------------------------

    /**
     * /jietispeed（不带参数）—— 显示当前阶梯速度。
     * 与 {@link #futiShow} 同一套「当前扶梯」定位规则。
     */
    private static int jietiShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isStepEnabled(level);
        double global = EscalatorSpeedManager.getGlobalStepSpeed(level);
        String globalNote = enabled
                ? "全局阶梯速度 " + EscalatorSpeedData.format(global) + " 格/秒"
                : "全局阶梯速度未单独设置，跟随扶梯速度";
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(EscalatorSpeedData.format(global)), false);
            return 1;
        }
        double step = EscalatorSpeedManager.getAnimationSpeed(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(EscalatorSpeedData.format(step)), false);
        return 1;
    }

    /** 注册 `-f` 分支：`-f X` 与 `-f X to Y`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> jietiForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::jietiForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::jietiForceFromTo))));
    }

    /** /jietispeed X —— 只改全局扶梯的阶梯速度。 */
    private static int jietiGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setGlobalStepSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    /** /jietispeed X to Y —— 只有当前全局阶梯速度正好是 X 时才改成 Y。 */
    private static int jietiFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double current = EscalatorSpeedManager.getGlobalStepSpeed(level);
        if (!EscalatorSpeedManager.same(current, from)) {
            source.sendSuccess(
                    () -> Component.literal("指令执行失败"),
                    false);
            return 0;
        }
        EscalatorSpeedManager.setGlobalStepSpeed(level, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    /** /jietispeed -f X —— 强制所有扶梯阶梯速度 = X。 */
    private static int jietiForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceGlobalStepSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    /** /jietispeed -f X to Y —— 把所有阶梯速度为 X 的扶梯改成 Y。 */
    private static int jietiForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean globalMatched = EscalatorSpeedManager.isStepEnabled(level)
                && EscalatorSpeedManager.same(EscalatorSpeedManager.getStepValue(level), from);
        int changed = EscalatorSpeedManager.forceStepFromTo(level, from, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        if (!globalMatched && changed == 0) {
            source.sendSuccess(
                    () -> Component.literal("指令执行失败"),
                    false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futimusic （扶梯音频，数据模型与 /futispeed 完全对称）
    // ------------------------------------------------------------------

    /** 注册 `-f` 分支：`-f <名字>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(SmoothLift::futiMusicNameSuggestions)
                        .executes(SmoothLift::futiMusicForceSet)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(SmoothLift::futiMusicNameSuggestions)
                                        .executes(SmoothLift::futiMusicForceFromTo))));
    }

    /** 音频 ID 在指令反馈里的显示名：内置音频显示中文名 + ID，玩家上传的直接显示文件名。 */
    private static String audioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        String name = EscalatorSpeedManager.displayName(audioId);
        return name.equals(audioId) ? audioId : name;
    }

    /** 【1.23】/futimusic 名字参数补全：default / off / 内置音频 / 已导入存档的音频。 */
    private static CompletableFuture<Suggestions> futiMusicNameSuggestions(
            final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        ServerLevel level = context.getSource().getLevel();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        seen.add("default");
        seen.add("off");
        seen.addAll(EscalatorSpeedManager.builtinAudioIds());
        for (java.util.Map.Entry<String, byte[]> e : EscalatorSpeedManager.getServerData(level).audioLibrary.entrySet()) {
            seen.add(e.getKey());
        }
        for (String s : seen) {
            if (s.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(s);
            }
        }
        return builder.buildFuture();
    }

    /** /futimusic（不带参数）—— 显示当前扶梯播放的音频名。 */
    private static int futiMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String defaultAudio = EscalatorSpeedManager.getDefaultAudio(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(audioLabel(defaultAudio)), false);
            return 1;
        }
        String id = EscalatorSpeedManager.effectiveAudioId(level, pos);
        boolean individual = EscalatorSpeedManager.hasIndividualAudio(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        final boolean own = individual;
        if (id == null) {
            source.sendSuccess(() -> Component.literal("无"), false);
        } else {
            source.sendSuccess(() -> Component.literal(audioLabel(id)), false);
        }
        return 1;
    }

    /** /futimusic &lt;名字&gt; —— 设置**默认**扶梯音频（已单独绑定音频的扶梯不变）。 */
    private static int futiMusicSet(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveAudioName(level, EscalatorSpeedManager.CAT_FUTI, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.setDefaultAudio(level, arg.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        if (arg.off()) {
            source.sendSuccess(() -> Component.literal("指令执行成功"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futimusic &lt;X&gt; to &lt;Y&gt; —— 默认音频正好是 X 时才改成 Y（单独绑定的不动）。 */
    private static int futiMusicFromTo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveAudioName(level, EscalatorSpeedManager.CAT_FUTI, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveAudioName(level, EscalatorSpeedManager.CAT_FUTI, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        String current = EscalatorSpeedManager.getDefaultAudio(level);
        if (!java.util.Objects.equals(current, from.id())) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultAudio(level, from.id(), to.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futimusic -f &lt;名字&gt; —— 强制游戏内**所有**扶梯都用这个音频。 */
    private static int futiMusicForceSet(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveAudioName(level, EscalatorSpeedManager.CAT_FUTI, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int cleared = EscalatorSpeedManager.forceDefaultAudio(level, arg.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        final int clearedCount = cleared;
        if (arg.off()) {
            source.sendSuccess(() -> Component.literal("指令执行成功"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futimusic -f &lt;X&gt; to &lt;Y&gt; —— 把所有音频为 X 的扶梯（含单独绑定的）改成 Y。 */
    private static int futiMusicForceFromTo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveAudioName(level, EscalatorSpeedManager.CAT_FUTI, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveAudioName(level, EscalatorSpeedManager.CAT_FUTI, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.forceReplaceAudioFromTo(level, from.id(), to.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        final int changedCount = changed;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futihelpmusic （无障碍提示音「音乐」，数据模型与 /futimusic 完全对称）
    //
    // 与 /futimusic 的唯一语义差别：
    //   `default` = **模组原来的提示音**（五档「咔啪」素材 + /futihelpspeed 速率），
    //   而不是内置运行底噪；另外多一个 `off` = 这一头不播提示音。
    // 【1.28】音频隔离：提示音只读自己分类的子文件夹 MBM_Audio/futi/help（底噪是 futi/music），
    //   字节库仍共用一份，但「已导入」各自独立：导入一次，只在提示音这边可选。
    //
    // ★【1.41】有 `in`（进入扶梯 / 上客端）与 `out`（离开扶梯 / 落客端）两个子命令，
    //   各是一套**互不影响**的数据（形状与 /futihelpspeed 的 in|out 完全一致）：
    //     /futihelpmusic                       -> 显示这条扶梯两头当前的提示音
    //     /futihelpmusic in|out <名字>         -> 默认提示音 = 名字（已单独设置的扶梯不变）
    //     /futihelpmusic in|out <X> to <Y>     -> 默认提示音正好是 X 时才改成 Y
    //     /futihelpmusic -f in|out <名字>      -> 强制所有扶梯这一头都用它（清掉这一头的单独设置）
    //     /futihelpmusic -f in|out <X> to <Y>  -> 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y
    //   ★ 所以「进站播一段、出站播另一端」不用改素材：给两头各设一段即可；
    //     连 `off` 都细到了单头 —— 可以只让某一头不响、另一头照常响。
    //   ★ 别把 in/out 挪到 -f 之外：与 /futihelpspeed 一样，in/out 在「带 -f」和「不带 -f」
    //     两层下各有一个，位置对齐才好记。
    // ------------------------------------------------------------------

    /** 注册 `-f` 分支：`-f in|out <名字>` 与 `-f in|out <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("in")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> futiHelpMusicForceSet(context, true))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(context -> futiHelpMusicForceFromTo(context, true))))))
                .then(Commands.literal("out")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> futiHelpMusicForceSet(context, false))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(context -> futiHelpMusicForceFromTo(context, false))))));
    }

    /** 提示音音乐 ID 在指令反馈里的显示名。 */
    private static String helpAudioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        if (EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(audioId)) {
            return "模组原来的提示音";
        }
        if (EscalatorSpeedData.HELP_AUDIO_OFF.equals(audioId)) {
            return "不播提示音";
        }
        return audioLabel(audioId);
    }

    /** /futihelpmusic（不带参数）—— 显示这条扶梯**两头**当前用的无障碍提示音。 */
    private static int futiHelpMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String defaultIn = EscalatorSpeedManager.getDefaultHelpAudio(level, true);
        String defaultOut = EscalatorSpeedManager.getDefaultHelpAudio(level, false);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(helpAudioLabel(defaultIn) + ", " + helpAudioLabel(defaultOut)), false);
            return 1;
        }
        String inId = EscalatorSpeedManager.effectiveHelpAudioId(level, pos, true);
        String outId = EscalatorSpeedManager.effectiveHelpAudioId(level, pos, false);
        boolean ownIn = EscalatorSpeedManager.hasIndividualHelpAudio(level, pos, true);
        boolean ownOut = EscalatorSpeedManager.hasIndividualHelpAudio(level, pos, false);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(helpAudioLabel(inId) + ", " + helpAudioLabel(outId)), false);
        return 1;
    }

    /** /futihelpmusic in|out &lt;名字&gt; —— 设置这一头的**默认**提示音（已单独设置的扶梯不变）。 */
    private static int futiHelpMusicSet(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveHelpAudioName(level, EscalatorSpeedManager.CAT_HELP, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.setDefaultHelpAudio(level, arg.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpmusic in|out &lt;X&gt; to &lt;Y&gt; —— 这一头默认提示音正好是 X 时才改成 Y（单独设置的按兵不动）。 */
    private static int futiHelpMusicFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveHelpAudioName(level, EscalatorSpeedManager.CAT_HELP, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveHelpAudioName(level, EscalatorSpeedManager.CAT_HELP, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        String current = EscalatorSpeedManager.getDefaultHelpAudio(level, in);
        if (!java.util.Objects.equals(current, from.id())) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpAudio(level, from.id(), to.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpmusic -f in|out &lt;名字&gt; —— 强制游戏内**所有**扶梯这一头的提示音都用这一段。 */
    private static int futiHelpMusicForceSet(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveHelpAudioName(level, EscalatorSpeedManager.CAT_HELP, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int cleared = EscalatorSpeedManager.forceDefaultHelpAudio(level, arg.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        final int clearedCount = cleared;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpmusic -f in|out &lt;X&gt; to &lt;Y&gt; —— 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpMusicForceFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveHelpAudioName(level, EscalatorSpeedManager.CAT_HELP, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveHelpAudioName(level, EscalatorSpeedManager.CAT_HELP, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.forceReplaceHelpAudioFromTo(level, from.id(), to.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        final int changedCount = changed;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futiloud （扶梯音量，数据模型与 /futispeed、/futimusic 完全对称）
    // ------------------------------------------------------------------

    /** /futiloud 的音量参数：1~1000（100 = 原始音量，1000 = 10× 放大）。 */
    private static IntegerArgumentType volumeArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.AUDIO_VOLUME_MIN,
                EscalatorSpeedData.AUDIO_VOLUME_MAX);
    }

    /** 注册 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::futiLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::futiLoudForceFromTo))));
    }

    /** /futiloud（不带参数）—— 显示当前扶梯音量。 */
    private static int futiLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultVolume(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal("" + global), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getVolume(level, pos);
        boolean individual = EscalatorSpeedManager.hasIndividualVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal("" + volume), false);
        return 1;
    }

    /** /futiloud &lt;音量&gt; —— 设置**默认**扶梯音量（单独设置过音量的扶梯不变）。 */
    private static int futiLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultVolume(level, volume);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futiloud &lt;X&gt; to &lt;Y&gt; —— 默认音量正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultVolume(level, from, to);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futiloud -f &lt;音量&gt; —— 强制游戏内**所有**扶梯都用这个音量（清掉单独设置）。 */
    private static int futiLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultVolume(level, volume);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futiloud -f &lt;X&gt; to &lt;Y&gt; —— 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceVolumeFromTo(level, from, to);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futihelp （无障碍提示音开关，数据模型与 /futispeed、/futiloud 完全对称）
    // ------------------------------------------------------------------

    /** 开关在指令反馈里的显示名。 */
    private static String helpLabel(boolean enabled) {
        return enabled ? "开" : "关";
    }

    /**
     * 注册 `/futihelp` 的 `-f` 分支：`-f on|off` 与 `-f &lt;X&gt; to &lt;Y&gt;`。
     *
     * <p>开关只有两个取值，所以直接用 {@code on}/{@code off} 两个字面量而不是自定义参数类型 ——
     * 这样 Tab 补全能补出全部合法输入，也让「X to Y」只能写出 {@code on to off} / {@code off to on}
     * 两种有意义的形式（与 /futiloud 的 {@code X to Y} 完全对称）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> futiHelpForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> futiHelpForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> futiHelpForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> futiHelpForceFromTo(context, false, true)))));
    }

    /** /futihelp（不带参数）—— 显示当前扶梯的无障碍提示音开关（站在扶梯上显示那一条，否则显示默认）。 */
    private static int futiHelpShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean global = EscalatorSpeedManager.getDefaultHelp(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(global ? "1" : "0"), false);
            return 1;
        }
        boolean enabled = EscalatorSpeedManager.isHelpEnabled(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelp(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(enabled ? "1" : "0"), false);
        return 1;
    }

    /** /futihelp &lt;on|off&gt; —— 设置**默认**开关（已单独设置过的扶梯不变）。 */
    private static int futiHelpGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelp(level, enabled);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelp &lt;X&gt; to &lt;Y&gt; —— 默认开关正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean current = EscalatorSpeedManager.getDefaultHelp(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelp(level, from, to);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelp -f &lt;on|off&gt; —— 强制游戏内**所有**扶梯 = 该开关（清掉单独设置）。 */
    private static int futiHelpForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelp(level, enabled);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelp -f &lt;X&gt; to &lt;Y&gt; —— 把所有开关正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }


    // ------------------------------------------------------------------
    // /futihelploud （无障碍**提示音**音量，数据模型与 /futiloud、/futihelp 完全对称）
    //
    //   注意与 /futiloud 的区别：/futiloud 管的是「扶梯运行底噪」的音量（整条扶梯、射程 16 格）；
    //   /futihelploud 管的是「无障碍提示音」的音量（装在端头单块方块上、射程 4 格）。
    //   两者是**两套独立数据**，改一个不影响另一个。
    // ------------------------------------------------------------------

    /** 注册 `/futihelploud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::futiHelpLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::futiHelpLoudForceFromTo))));
    }

    /** /futihelploud（不带参数）—— 显示当前扶梯的无障碍提示音音量。 */
    private static int futiHelpLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultHelpVolume(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal("" + global), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getHelpVolume(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelpVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal("" + volume), false);
        return 1;
    }

    /** /futihelploud &lt;音量&gt; —— 设置**默认**提示音音量（单独设置过的扶梯不变）。 */
    private static int futiHelpLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelpVolume(level, volume);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelploud &lt;X&gt; to &lt;Y&gt; —— 默认音量正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultHelpVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpVolume(level, from, to);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelploud -f &lt;音量&gt; —— 强制游戏内**所有**扶梯提示音都用这个音量（清掉单独设置）。 */
    private static int futiHelpLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelpVolume(level, volume);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelploud -f &lt;X&gt; to &lt;Y&gt; —— 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpVolumeFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.24】/futiround —— 扶梯运行底噪的淡入淡出范围（默认 16 格）
    //
    //   与 /futiloud（音量）是**两件事**：音量决定「多响」，范围决定「多远还听得见」。
    //   仅指令可改，**没有石斧界面控件**。
    // ------------------------------------------------------------------

    /** /futiround 的范围参数：1~128 格。 */
    private static IntegerArgumentType roundArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX);
    }

    /**
     * 【1.16】{@code /pbmclosewait} 的秒数参数：0~999999 秒（【1.23】按点名放宽到 [0,+∞)）。
     *
     * <p>★ 参数类型这里**再夹一次**（数据层 {@code clampPsdCloseWaitSeconds} 还会夹一次）：
     * 指令层夹是为了让 Brigadier 直接在 Tab 补全/报错上就挡住越界值，
     * 数据层夹是为了挡住「存档被外部改坏」与「UI 输入框绕过参数类型」两条路。
     * 两处都留不是冗余 —— {@code _tools/check-close-wait.py} 把两处都钉住了。
     */
    private static IntegerArgumentType closeWaitArg() {
        return IntegerArgumentType.integer(
                EscalatorSpeedData.PSD_CLOSE_WAIT_MIN, EscalatorSpeedData.PSD_CLOSE_WAIT_MAX);
    }

    /**
     * 【1.17】{@code /pbmmidium} 的等待秒数参数：**0 ~ 正无穷**（{@code [0, +∞)}）。
     *
     * <p>★ 与 {@link #closeWaitArg()} 的差别就是**没有上界** —— 用户点名
     * 「pbmmidium 指令和 ui 允许输入 0 到正无穷的数字」。
     * {@code IntegerArgumentType.integer(0)} 的默认上界就是 {@code Integer.MAX_VALUE}
     * （≈ 68 年），已经是「正无穷」在 int 里的全部空间。
     *
     * <p>下界 0 仍然要显式写：不写就是 {@code Integer.MIN_VALUE}，负值会被接住，
     * 而「等 -3 秒」在语义上没有意义（会让计划 tick 落到过去）。
     */
    private static IntegerArgumentType midiumWaitArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.PSD_MIDIUM_WAIT_MIN);
    }

    /** 【1.17】{@code /pbmmidium} 的素材名参数（带音频库补全，含 {@code off}）。 */
    private static StringArgumentType midiumNameArg() {
        return StringArgumentType.string();
    }

    /**
     * 【1.21】{@code /pbmarrive} 的秒数参数：{@code (-∞, 0]}。
     *
     * <p>★ 与 {@link #midiumWaitArg()}（{@code [0, +∞)}）**正好相反**：那一个只给下界，
     * 这一个只给上界 —— 用户点名「输入框范围：(-无穷,0]」，例子 {@code X=-10} =
     * 「最近一班车还剩 10 秒到站」时起播进站提示音。
     * {@code IntegerArgumentType.integer(Integer.MIN_VALUE, 0)} 就是「负无穷到 0」。
     */
    private static IntegerArgumentType arriveArg() {
        return IntegerArgumentType.integer(Integer.MIN_VALUE, EscalatorSpeedData.PSD_ARRIVE_SECONDS_MAX);
    }

    /** 【1.21】{@code /pbmarrive} 的素材名参数（带音频库补全，含 {@code off}）。 */
    private static StringArgumentType arriveNameArg() {
        return StringArgumentType.string();
    }

    /** 注册 `/futiround` 的 `-f` 分支：`-f <水平> <垂直>` 与 `-f <Xz> <Y> to <新Xz> <新Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("xz", roundArg())
                        .then(Commands.argument("y", roundArg())
                                .executes(SmoothLift::futiRoundForceAll)
                                .then(Commands.literal("to")
                                        .then(Commands.argument("targetXz", roundArg())
                                                .then(Commands.argument("targetY", roundArg())
                                                        .executes(SmoothLift::futiRoundForceFromTo))))));
    }

    /** /futiround（不带参数）—— 显示当前扶梯运行音效的淡入淡出范围（★【10-03】「水平, 垂直」）。 */
    private static int futiRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int globalXz = EscalatorSpeedManager.getDefaultRound(level);
        int globalY = EscalatorSpeedManager.getDefaultRoundY(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(globalXz + ", " + globalY), false);
            return 1;
        }
        int roundXz = EscalatorSpeedManager.getRound(level, pos);
        int roundY = EscalatorSpeedManager.getRoundY(level, pos);
        source.sendSuccess(() -> Component.literal(roundXz + ", " + roundY), false);
        return 1;
    }

    /** /futiround &lt;水平&gt; &lt;垂直&gt; —— 设置**默认**范围（单独设置过的扶梯不变）。 */
    private static int futiRoundGlobal(CommandContext<CommandSourceStack> context) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultRound(level, xz, y);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futiround &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt; —— 默认范围**两维都**正好时才改成新值。 */
    private static int futiRoundFromTo(CommandContext<CommandSourceStack> context) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (EscalatorSpeedManager.getDefaultRound(level) != fromXz
                || EscalatorSpeedManager.getDefaultRoundY(level) != fromY) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultRound(level, fromXz, fromY, toXz, toY);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futiround -f &lt;水平&gt; &lt;垂直&gt; —— 强制游戏内**所有**扶梯音效都用这个范围（清掉单独设置）。 */
    private static int futiRoundForceAll(CommandContext<CommandSourceStack> context) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceDefaultRound(level, xz, y);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futiround -f &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt; —— 生效范围两维都正好是 Xz/Y 的扶梯改成新值。 */
    private static int futiRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceRoundFromTo(level, fromXz, fromY, toXz, toY);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.24】/futihelpround —— 无障碍提示音的淡入淡出范围（默认 4 格）
    //
    //   ★ 与 /futiround 是**两件事**：本指令管的是端头**单块**那块提示音（默认 4 格），
    //     /futiround 管的是整条扶梯一起响的运行底噪（默认 10 格）。数据与指令互不影响。
    // ------------------------------------------------------------------

    /** 注册 `/futihelpround` 的 `-f` 分支：`-f <水平> <垂直>` 与 `-f <Xz> <Y> to <新Xz> <新Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("xz", roundArg())
                        .then(Commands.argument("y", roundArg())
                                .executes(SmoothLift::futiHelpRoundForceAll)
                                .then(Commands.literal("to")
                                        .then(Commands.argument("targetXz", roundArg())
                                                .then(Commands.argument("targetY", roundArg())
                                                        .executes(SmoothLift::futiHelpRoundForceFromTo))))));
    }

    /** /futihelpround（不带参数）—— 显示当前扶梯无障碍提示音的淡入淡出范围（★【10-03】「水平, 垂直」）。 */
    private static int futiHelpRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int globalXz = EscalatorSpeedManager.getDefaultHelpRound(level);
        int globalY = EscalatorSpeedManager.getDefaultHelpRoundY(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(globalXz + ", " + globalY), false);
            return 1;
        }
        int roundXz = EscalatorSpeedManager.getHelpRound(level, pos);
        int roundY = EscalatorSpeedManager.getHelpRoundY(level, pos);
        source.sendSuccess(() -> Component.literal(roundXz + ", " + roundY), false);
        return 1;
    }

    /** /futihelpround &lt;水平&gt; &lt;垂直&gt; —— 设置**默认**范围（单独设置过的扶梯不变）。 */
    private static int futiHelpRoundGlobal(CommandContext<CommandSourceStack> context) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelpRound(level, xz, y);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpround &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt; —— 默认范围**两维都**正好时才改成新值。 */
    private static int futiHelpRoundFromTo(CommandContext<CommandSourceStack> context) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (EscalatorSpeedManager.getDefaultHelpRound(level) != fromXz
                || EscalatorSpeedManager.getDefaultHelpRoundY(level) != fromY) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpRound(level, fromXz, fromY, toXz, toY);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpround -f &lt;水平&gt; &lt;垂直&gt; —— 强制游戏内**所有**提示音都用这个范围（清掉单独设置）。 */
    private static int futiHelpRoundForceAll(CommandContext<CommandSourceStack> context) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceDefaultHelpRound(level, xz, y);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpround -f &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt; —— 生效范围两维都正好是 Xz/Y 的扶梯改成新值。 */
    private static int futiHelpRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpRoundFromTo(level, fromXz, fromY, toXz, toY);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.31】/futihelpspeed —— 无障碍提示音的**速率**（每秒响几次，单位 Hz）
    //
    //   与上面三个提示音指令凑成完整一套、互不影响：
    //     /futihelp      开关    -> 响不响
    //     /futihelploud  音量    -> 多响
    //     /futihelpround 范围    -> 多远还听得见
    //     本指令         速率    -> 响得多快
    //   ★ 与 /futiround（整条扶梯的运行底噪）是两件事：本指令只管端头**单块**那路提示音。
    //   ★ 有 `in`（进入扶梯 / 上客端）与 `out`（离开扶梯 / 落客端）两个子命令，各是一套数据。
    //   ★ 速率靠「换素材 + 调 pitch」实现（原版把 pitch 夹在 [0.5,2.0]），
    //     所以区间是 1~100 Hz（【1.34】上限由 20 提到 100），见 EscalatorSpeedData#HELP_SPEED_MAX。
    // ------------------------------------------------------------------

    /** /futihelpspeed 的速率参数：{@link EscalatorSpeedData#HELP_SPEED_MIN}~{@link EscalatorSpeedData#HELP_SPEED_MAX} Hz（每秒响几次）。 */
    private static IntegerArgumentType helpSpeedArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.HELP_SPEED_MIN, EscalatorSpeedData.HELP_SPEED_MAX);
    }

    /** 指令文案里用的端头名（/futihelpspeed 与 /futihelpmusic 共用）。 */
    public static String helpEndLabel(boolean in) {
        return in ? "进入扶梯" : "离开扶梯";
    }

    /**
     * 【1.45】判定：方块注册名以 {@code lift_track_floor} 开头（MTR3 / MTR4 的「楼层轨道」
     * 注册名都是这个前缀，竖轨 {@code lift_track_vertical_*} 不会误命中）。
     * 服务端和客户端共用这一个判据（石斧右键时两侧都要拦默认交互）。
     */
    public static boolean isLiftTrackFloor(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.startsWith("lift_track_floor");
    }

    /**
     * 【1.50】判定「屏蔽门（可开合的那一格）」—— 按注册名判，不依赖 MTR 编译期。
     *
     * <p>MTR 的屏蔽门方块注册名（MTR3 3.2.2 与 MTR4 4.0.5 的 blockstates 都是这一组）：
     * {@code psd_door} / {@code psd_door_2}（站台幕门，两代样式）、{@code apg_door}
     * （半高安全门 = Automatic Platform Gate，和 PSD 共用同一套方块实体与门值逻辑）。
     *
     * <p><b>刻意不收玻璃与顶板</b>（{@code psd_glass*} / {@code psd_top} / {@code apg_glass*}）：
     * 那些方块的 {@code side}/{@code half} 与门**不是同一对**，拿它们算出来的「门锚点」
     * 会落到另一格上 ⇒ 玩家右键玻璃配好、门开的时候却查不到这份设置（症状是「配了不响」）。
     * 宁可「右键玻璃没反应」，也不要「看起来配上了但不生效」。
     */
    public static boolean isPsdDoor(BlockState state) {
        if (state == null) {
            return false;
        }
        String path = registryPathOf(state);
        return path.startsWith("psd_door") || path.startsWith("apg_door");
    }

    /** 方块注册名（{@code psd_door_2} 之类）；取不到返回空串。 */
    public static String registryPathOf(BlockState state) {
        if (state == null) {
            return "";
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    // ------------------------------------------------------------------
    // 【09-30】闸机（MTR 的 Ticket Barrier）的**方块判据** —— 全部按注册名判，
    //   编译期不认识 MTR（MTR 在 fabric.mod.json 里只是 suggests，见 MtrLiftAccess 的类注释）。
    //
    //   ★ 判据只认**精确路径**（equals，不是 startsWith）：MTR 那一族方块里有四个名字带
    //     ticket_barrier / ticket_processor，用 startsWith("ticket_barrier") 会把
    //     将来可能新增的兄弟方块一起命中（教训：用「名字里含某关键词」筛方块前，先问
    //     「这判据还会命中谁」）。这里四个都写成精确比较，命中面是确定的。
    //
    //   MTR 的注册名（对 4.0.5 的 assets/mtr/lang 核过，中文名就是用户说的那个词）：
    //     进站闸机 = mtr:ticket_barrier_entrance_1     （lang: block.mtr.ticket_barrier_entrance_1 = 进站闸机）
    //     出站闸机 = mtr:ticket_barrier_exit_1         （lang: block.mtr.ticket_barrier_exit_1     = 出站闸机）
    //   ★ 别和「车票处理器」（ticket_processor_*   = 车票处理器（入口/出口））混了：
    //     那是另一族方块，用户点名的是「闸机」，所以这里**不含** processor。
    // ------------------------------------------------------------------

    /** 【09-30】进站闸机（{@code mtr:ticket_barrier_entrance_1}）？ */
    public static boolean isZhajiEntrance(BlockState state) {
        return "ticket_barrier_entrance_1".equals(registryPathOf(state));
    }

    /** 【09-30】出站闸机（{@code mtr:ticket_barrier_exit_1}）？ */
    public static boolean isZhajiExit(BlockState state) {
        return "ticket_barrier_exit_1".equals(registryPathOf(state));
    }

    /** 【09-30】进站**或**出站闸机？ */
    public static boolean isZhajiBarrier(BlockState state) {
        return isZhajiEntrance(state) || isZhajiExit(state);
    }

    /**
     * 【09-30】闸机方向：{@code "in"}（进站）/ {@code "out"}（出站）/ {@code null}（不是闸机）。
     *
     * <p>播放端（{@code ZhajiChimePlayer}）与石斧右键都走这一个函数，
     * 保证「界面上配的那一侧」与「实际响的那一侧」用的是**同一个判据**。
     */
    public static String zhajiWhichOf(BlockState state) {
        if (isZhajiEntrance(state)) {
            return "in";
        }
        if (isZhajiExit(state)) {
            return "out";
        }
        return null;
    }

    /**
     * 【1.57】是不是 MTR 的**轨道节点**方块（{@code mtr:rail}）—— 侧线铁轨在世界里落的那一格。
     *
     * <p>用户原话：<b>「石斧右键侧线铁路轨道连接处（就是黄色的那个）」</b>。
     * 「黄色」是 MTR **渲染时**给侧线铁轨上的色（{@code RailType.SIDING} 的 MapColor = 黄，
     * 已反汇编核实），**不在方块状态里** ⇒ 这里只能认「是轨道节点」，
     * 「这一格是不是侧线」由客户端 {@code MtrSidingAccess} 去读 MTR 的轨道数据（那里有 isSiding）。
     *
     * <p>★★ 判据必须**同时**看命名空间与路径：原版就有一个 {@code minecraft:rail}
     * （普通铁轨），只比路径 {@code "rail"} 会把它一起命中 —— 那样玩家拿石斧右键
     * **普通铁轨**也会开界面。这正是「按注册名筛方块」最容易漏的一问：**这判据还会命中谁？**
     */
    public static boolean isMtrRail(BlockState state) {
        if (state == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "mtr".equals(id.getNamespace()) && "rail".equals(id.getPath());
    }

    /**
     * 【1.45】直梯提示音四项的中文名（石斧界面 / 指令反馈共用同一套词）。
     * {@code which} 不是 up/down/open/close → 返回「提示音」兜底。
     */
    public static String liftToneLabel(String which, String audioId) {
        return switch (which) {
            case "up" -> "上楼提示音";
            case "down" -> "下楼提示音";
            case "open" -> "开门提示音";
            case "close" -> "关门提示音";
            default -> "提示音";
        };
    }

    /** 【1.45】反馈文案里的名字截断（界面 / 指令都用 20 字符上限），过长直接截。 */
    public static String truncateForMsg(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    /** 注册 `/futihelpspeed` 的 `-f` 分支：`-f in|out <Hz>` 与 `-f in|out <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpSpeedForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("in")
                        .then(Commands.argument("hz", helpSpeedArg())
                                .executes(context -> futiHelpSpeedForceAll(context, true))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", helpSpeedArg())
                                                .executes(context -> futiHelpSpeedForceFromTo(context, true))))))
                .then(Commands.literal("out")
                        .then(Commands.argument("hz", helpSpeedArg())
                                .executes(context -> futiHelpSpeedForceAll(context, false))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", helpSpeedArg())
                                                .executes(context -> futiHelpSpeedForceFromTo(context, false))))));
    }

    /** /futihelpspeed（不带参数）—— 显示当前扶梯两头的无障碍提示音速率。 */
    private static int futiHelpSpeedShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int globalIn = EscalatorSpeedManager.getDefaultHelpSpeedIn(level);
        int globalOut = EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(globalIn + ", " + globalOut), false);
            return 1;
        }
        int in = EscalatorSpeedManager.getHelpSpeedIn(level, pos);
        int out = EscalatorSpeedManager.getHelpSpeedOut(level, pos);
        boolean ownIn = EscalatorSpeedManager.hasOwnHelpSpeedIn(level, pos);
        boolean ownOut = EscalatorSpeedManager.hasOwnHelpSpeedOut(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(in + ", " + out), false);
        return 1;
    }

    /** /futihelpspeed in|out &lt;Hz&gt; —— 设置**默认**速率（单独设置过的扶梯不变）。 */
    private static int futiHelpSpeedGlobal(CommandContext<CommandSourceStack> context, boolean in) {
        int hz = IntegerArgumentType.getInteger(context, "hz");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (in) {
            EscalatorSpeedManager.setDefaultHelpSpeedIn(level, hz);
        } else {
            EscalatorSpeedManager.setDefaultHelpSpeedOut(level, hz);
        }
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpspeed in|out &lt;X&gt; to &lt;Y&gt; —— 默认速率正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpSpeedFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        int from = IntegerArgumentType.getInteger(context, "hz");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        if (in) {
            EscalatorSpeedManager.replaceDefaultHelpSpeedIn(level, from, to);
        } else {
            EscalatorSpeedManager.replaceDefaultHelpSpeedOut(level, from, to);
        }
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpspeed -f in|out &lt;Hz&gt; —— 强制**所有**扶梯这一头都用该速率（清掉单独设置）。 */
    private static int futiHelpSpeedForceAll(CommandContext<CommandSourceStack> context, boolean in) {
        int hz = IntegerArgumentType.getInteger(context, "hz");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = in ? EscalatorSpeedManager.forceDefaultHelpSpeedIn(level, hz)
                : EscalatorSpeedManager.forceDefaultHelpSpeedOut(level, hz);
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /futihelpspeed -f in|out &lt;X&gt; to &lt;Y&gt; —— 把速率正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpSpeedForceFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        int from = IntegerArgumentType.getInteger(context, "hz");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = in ? EscalatorSpeedManager.forceReplaceHelpSpeedInFromTo(level, from, to)
                : EscalatorSpeedManager.forceReplaceHelpSpeedOutFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ==================================================================
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    //   /lifthelp                     显示 / on|off / on to off / -f on|off / -f on to off
    //   /lifthelp up|down|door        三提示音子命令（子开关 + 默认素材），详见下面那一段
    //   /lifthelploud                 音量（共用默认 + up|down|door 单项）
    //   /lifthelpround                淡入淡出范围（三项共用）
    //
    //   ★ 为什么是 lifthelp 而不是需求原文里的 futihelp：
    //     那条指令**早就存在**、管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
    //     直接复用会把扶梯那套设置顶掉。直梯提示音是完全另一件事，所以另开一条指令，
    //     扶梯的 futihelp / futihelpspeed 一个字节都不动。
    //
    //   ★ 直梯提示音的「开关 / 音量 / 范围」只有「维度默认」一层数据（没有单条直梯的
    //     单独设置），所以那几条的 `-f` 取「**对所有维度**强制」的含义 ——
    //     见 EscalatorSpeedManager 里那一段的说明。
    //
    //   ★【1.15】`/lifthelpspeed` 已按用户要求**删除**：倍速数据（defaultLiftHelpSpeed）
    //     与播放端的 pitch 逻辑都留着（旧存档里可能存着非 1.0 的值），只是不再提供改它的
    //     入口（石斧界面本来也没有）。显示里仍会报出当前倍速，那是**实际生效的值**。
    // ==================================================================

    /** 倍速在指令反馈里的显示（两位小数就够，1.0 显示成 1）。 */
    private static String liftSpeedLabel(float speed) {
        if (speed == Math.round(speed)) {
            return String.valueOf((int) Math.round(speed));
        }
        return String.format(Locale.ROOT, "%.2f", speed);
    }

    /** /lifthelp（不带参数）—— 显示当前维度生效的直梯提示音开关。 */
    private static int liftHelpShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        float speed = EscalatorSpeedManager.getLiftHelpSpeed(level);
        source.sendSuccess(() -> Component.literal((enabled ? "1" : "0") + ", " + EscalatorSpeedData.format(speed)), false);
        return 1;
    }

    /** /lifthelp &lt;on|off&gt; —— 设置**本维度**的开关。 */
    private static int liftHelpGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelp(level, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp &lt;X&gt; to &lt;Y&gt; —— 本维度开关正好是 X 时才改成 Y。 */
    private static int liftHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelp(level, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftHelpEnabled(level);
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp -f &lt;on|off&gt; —— **所有维度**都设成该开关。 */
    private static int liftHelpForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpAll(source.getServer(), enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里开关正好是 X 的那些改成 Y。 */
    private static int liftHelpForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelploud（不带参数）—— 显示当前维度生效的直梯提示音音量。 */
    private static int liftHelpLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int volume = EscalatorSpeedManager.getLiftHelpVolume(level);
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        source.sendSuccess(() -> Component.literal(volume + ", " + (enabled ? "1" : "0")), false);
        return 1;
    }

    /** /lifthelploud &lt;音量&gt; —— 设置**本维度**的音量。 */
    private static int liftHelpLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpVolume(level, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelploud &lt;X&gt; to &lt;Y&gt; —— 本维度音量正好是 X 时才改成 Y。 */
    private static int liftHelpLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelpVolume(level, from, to)) {
            int current = EscalatorSpeedManager.getLiftHelpVolume(level);
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelploud -f &lt;音量&gt; —— **所有维度**都设成该音量。 */
    private static int liftHelpLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpVolumeAll(source.getServer(), volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftHelpVolume(volume);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelploud -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里音量正好是 X 的那些改成 Y。 */
    private static int liftHelpLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpVolumeAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftHelpVolume(to);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** 注册 `/lifthelploud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::liftHelpLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::liftHelpLoudForceFromTo))));
    }

    // ------------------------------------------------------------------
    // 【1.48】/lifthelploud up|down|open|close：四提示音**各自的**音量
    //   up = 上楼 / down = 下楼 / open = 开门 / close = 关门。
    //   形状与「共用默认音量」一致（<音量> / <X> to <Y> / -f <音量> / -f <X> to <Y>），
    //   只是改的是对应那一项；没单独调过的项跟随共用默认。
    // ------------------------------------------------------------------

    /** 注册某一项（up/down/open/close）的音量子命令树。{@code literal} = 界面用名，{@code which} = 数据用名。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneLoudCommand(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> liftToneLoudGlobal(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> liftToneLoudFromTo(context, which)))));
    }

    /** 【1.48】`-f` 节点下的单项分支：{@code up|down|door <音量>} 与 {@code up|down|door <X> to <Y>}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneLoudForceBranch(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> liftToneLoudForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> liftToneLoudForceFromTo(context, which)))));
    }

    /** /lifthelploud up|down|open|close &lt;音量&gt; —— 设置**本维度**这一项的音量。 */
    private static int liftToneLoudGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftToneVolume(level, which, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftToneVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelploud up|down|open|close &lt;X&gt; to &lt;Y&gt; —— 本维度这一项音量正好是 X 时才改成 Y。 */
    private static int liftToneLoudFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getLiftToneVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultLiftToneVolume(level, which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftToneVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelploud -f up|down|door &lt;音量&gt; —— **所有维度**这一项都设成该音量。 */
    private static int liftToneLoudForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftToneVolumeAll(source.getServer(), which, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftToneVolume(volume);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelploud -f up|down|door &lt;X&gt; to &lt;Y&gt; —— 所有维度里这一项音量正好是 X 的改成 Y。 */
    private static int liftToneLoudForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneVolumeAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftToneVolume(to);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** 【1.48】数据用名 → 指令里的字面名。【1.28】up/down/open/close 全部同名，不再有 door → chime 别名。 */
    private static String literalName(String which) {
        return which;
    }

    // ------------------------------------------------------------------
    // 【1.47】/lifthelpround：直梯提示音（三项共用）淡入淡出范围
    // ------------------------------------------------------------------

    /** 注册 `/lifthelpround` 的 `-f` 分支：`-f <水平> <垂直>` 与 `-f <Xz> <Y> to <新Xz> <新Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("xz", roundArg())
                        .then(Commands.argument("y", roundArg())
                                .executes(SmoothLift::liftHelpRoundForceAll)
                                .then(Commands.literal("to")
                                        .then(Commands.argument("targetXz", roundArg())
                                                .then(Commands.argument("targetY", roundArg())
                                                        .executes(SmoothLift::liftHelpRoundForceFromTo))))));
    }

    /** /lifthelpround（不带参数）—— 显示当前维度生效的淡入淡出范围（★【10-03】「水平, 垂直」）。 */
    private static int liftHelpRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int roundXz = EscalatorSpeedManager.getLiftHelpRound(level);
        int roundY = EscalatorSpeedManager.getLiftHelpRoundY(level);
        source.sendSuccess(() -> Component.literal(roundXz + ", " + roundY), false);
        return 1;
    }

    /** /lifthelpround &lt;水平&gt; &lt;垂直&gt; —— 设置**本维度**的范围。 */
    private static int liftHelpRoundGlobal(CommandContext<CommandSourceStack> context) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpRound(level, xz, y);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelpround &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt; —— 本维度范围两维都正好时才改成新值。 */
    private static int liftHelpRoundFromTo(CommandContext<CommandSourceStack> context) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (EscalatorSpeedManager.getLiftHelpRound(level) != fromXz
                || EscalatorSpeedManager.getLiftHelpRoundY(level) != fromY) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultLiftHelpRound(level, fromXz, fromY, toXz, toY);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelpround -f &lt;水平&gt; &lt;垂直&gt; —— **所有维度**都设成该范围。 */
    private static int liftHelpRoundForceAll(CommandContext<CommandSourceStack> context) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        EscalatorSpeedManager.setDefaultLiftHelpRoundAll(source.getServer(), xz, y);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelpround -f &lt;Xz&gt; &lt;Y&gt; to &lt;新Xz&gt; &lt;新Y&gt; —— 所有维度里范围两维都正好是 Xz/Y 的改成新值。 */
    private static int liftHelpRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpRoundAll(
                source.getServer(), fromXz, fromY, toXz, toY);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** 注册 `/lifthelp` 的 `-f` 分支：`-f on|off` 与 `-f on to off` / `-f off to on`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> liftHelpForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> liftHelpForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> liftHelpForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> liftHelpForceFromTo(context, false, true)))));
    }

    // ------------------------------------------------------------------
    // 【1.46】三提示音的子命令：/lifthelp up / /lifthelp down / /lifthelp door
    //
    //   【1.15】结构改动（用户点名）：原来是三条**顶级**指令
    //   /lifthelpup、/lifthelpdown、/lifthelpchime；现在收进 /lifthelp 下面变成子命令，
    //   并且把 chime 的**对外名字**改成 door（= 开关门）——
    //   数据层（EscalatorSpeedData / Manager / 石斧 UI）继续叫 chime，一个字节都没动，
    //   只有指令这一层做别名映射（literal "door" → which "chime"）。
    //
    //   每一条子命令的形状（与 /lifthelp 本身一致）：
    //     /lifthelp up                      显示：子开关 + 本维度默认素材
    //     /lifthelp up on|off               本维度子开关
    //     /lifthelp up on to off            本维度子开关正好是 on 才改成 off（off to on 同理）
    //     /lifthelp up <名字>               本维度**默认素材**（config：default / none / 导入的 .ogg）
    //     /lifthelp up <名字> to <另一个>   本维度默认素材正好是它才改掉
    //     /lifthelp up -f on|off            强制**所有维度**子开关（含 on to off / off to on）
    //     /lifthelp up -f <名字>            强制**所有维度**默认素材，并清掉按竖井列的单独设置
    //     /lifthelp up -f <名字> to <另一个> 所有维度里默认素材正好是它的改成另一个（单独设置的一起改）
    //
    //   ★ 同一层上「字面量 on/off/-f」与「音频名字参数」会**同时**匹配（StringArgumentType
    //     把 on 也读成一个字符串），Brigadier 取的是**先注册**的那个子节点。
    //     所以下面一律**先 literal 后 argument**，顺序不能调 —— 由
    //     _tools/CmdTreeCheck.java 的 expectNode 断言钉住（`lifthelp up on` 必须落在字面量上）。
    //   ★ 也正因为 on/off 被字面量占了，「这一项不播」在指令里写成 `none`。
    // ------------------------------------------------------------------

    /** 直梯提示音素材 id 在指令反馈里的显示名。 */
    private static String liftToneAudioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(audioId)) {
            return "default";
        }
        if (EscalatorSpeedData.LIFT_TONE_OFF.equals(audioId)) {
            return "none";
        }
        return "「" + audioId + "」";
    }

    /** 【1.15】屏蔽门提示音素材 id 在指令反馈里的显示名。 */
    public static String psdToneAudioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN.equals(audioId)) {
            return "default";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE.equals(audioId)) {
            return "default-c";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M.equals(audioId)) {
            return "default-m";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S.equals(audioId)) {
            return "default-s：同素材但不播语音播报段，只播嘀嘀";
        }
        if (EscalatorSpeedData.PSD_TONE_OFF.equals(audioId)) {
            return "none";
        }
        return "「" + audioId + "」";
    }

    /**
     * 音频名字参数的 Tab 补全：**default / none + 玩家导入的每一个 .ogg 文件名**（字典序）。
     * 服务端算好发给客户端（指令树与补全都在服务端算）。
     *
     * <p>source 为 null 时（{@code _tools/CmdTreeCheck} 故意用 null source 脱离游戏环境解析
     * 真·指令树 —— 见 {@code registerCommands} 的说明）直接给空补全项，
     * 不要在这里抛 NPE：补全异常不会被 Brigadier 的 {@code CommandSyntaxException} 兜住。
     */
    private static CompletableFuture<Suggestions> liftToneNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String category) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.liftToneNameCandidates(source.getLevel(), category)) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    /** /lifthelp up|down|door（不带参数）—— 显示当前维度这项的子开关与默认素材。 */
    private static int liftToneShow(CommandContext<CommandSourceStack> context, String literal, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isLiftToneEnabled(level, which);
        String audio = EscalatorSpeedManager.getLiftToneAudio(level, which);
        source.sendSuccess(() -> Component.literal((enabled ? "1" : "0") + ", " + liftToneAudioLabel(audio)), false);
        return 1;
    }

    // ==================================================================
    // 【09-29 LOG6】「打开这一项」= **用户要听得到** ⇒ 顺手把素材层的「不播」也放行。
    //
    //   用户原话：「只要选择了简单港铁预设就无论怎么设置就是开启不了直梯的开关门滴滴声」。
    //   根因：上一轮把预设里的「不播」从**子开关层**（`lifthelp open -f off`）搬到了
    //   **素材层**（`-f none`）。可用户所有的「打开」动作 —— 石斧 UI 右列那行「开关：切换」
    //   + `/lifthelp open on` —— 全都只动**子开关**，于是素材层那个 `off` 永远没人清
    //   ⇒ 开关显示「开」、就是没声（LOG6 实证：`子开关 open=true close=true`
    //   + `默认素材 open=off close=off` + 8 条「素材就是「不播」…跳过」）。
    //
    //   ★【09-29 · 二改】那只手动的「开关」按钮已按用户点名**整行删掉**（用户原话：
    //     「『不播』代表关闭，『默认』或者玩家导入的就代表开启，不需要一个专门的开关按钮」）。
    //     ⇒ UI 侧的「打开」出口现在只剩 `LiftToneSetupScreen#ensureToneEnabled`
    //       （选「会出声」的素材时顺手开闸门）；「关」由「不播」在**素材层**承担。
    //     这一整段教训不变 —— 只是入口从「按钮」换成了「选素材」。
    //
    //   ⇒ 规矩：**「打开」这一下要把「这一项没声音」的所有成因一起清掉**。
    //     · 放行子开关（原本就有）；
    //     · 若素材是「不播」，一并换回 `default` —— 这才是用户眼里「打开了」。
    //   两种来源都能被这一下治好：① 用户先手动设了「不播」再点「打开」；
    //   ② **早期版本**把预设的「不播」误写在素材层留下的存档（素材成了 `off`）。
    //
    //   ★ 反过来说：「不播」这一项今后**只由用户显式选择**产生，预设不再写它
    //     （预设要静音就关子开关 —— 那一层所有的「打开」入口都够得着）。
    // ==================================================================

    /** 「打开」时顺手清掉素材层的「不播」。@return true = 真的清过（回执据此说明） */
    private static boolean healLiftToneOffAudio(ServerLevel level, String which) {
        return EscalatorSpeedManager.replaceDefaultLiftToneAudio(
                level, which, EscalatorSpeedData.LIFT_TONE_OFF, EscalatorSpeedData.LIFT_TONE_DEFAULT);
    }

    /** 同上，作用于**所有维度**（`-f` 分支）。@return 真的清过的维度数 */
    private static int healLiftToneOffAudioAll(MinecraftServer server, String which) {
        return EscalatorSpeedManager.replaceDefaultLiftToneAudioAll(
                server, which, EscalatorSpeedData.LIFT_TONE_OFF, EscalatorSpeedData.LIFT_TONE_DEFAULT);
    }

    /** 回执后缀：不做静默魔法，清过素材就写出来。 */
    private static String healNote(boolean healed) {
        return healed ? "（这一项的素材本来是「不播」，已一并换回内置素材）" : "";
    }

    private static String healNoteAll(int healed) {
        return healed > 0 ? "（素材是「不播」的那 " + healed + " 个维度已一并换回内置素材）" : "";
    }

    /** /lifthelp up|down|door &lt;on|off&gt; —— 设置**本维度**这项子开关。 */
    private static int liftToneSwitchGlobal(CommandContext<CommandSourceStack> context, String literal,
                                            String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftToneEnabled(level, which, enabled);
        boolean healed = enabled && healLiftToneOffAudio(level, which);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp up|down|door &lt;X&gt; to &lt;Y&gt; —— 本维度这项子开关正好是 X 时才改成 Y。 */
    private static int liftToneSwitchFromTo(CommandContext<CommandSourceStack> context, String which,
                                            boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftToneEnabled(level, which, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftToneEnabled(level, which);
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        boolean healed = to && healLiftToneOffAudio(level, which);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp up|down|door -f &lt;on|off&gt; —— **所有维度**这项子开关都设成该值。 */
    private static int liftToneSwitchForceAll(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftToneEnabledAll(source.getServer(), which, enabled);
        int healed = enabled ? healLiftToneOffAudioAll(source.getServer(), which) : 0;
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp up|down|door -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里这项子开关正好是 X 的那些改成 Y。 */
    private static int liftToneSwitchForceFromTo(CommandContext<CommandSourceStack> context, String which,
                                                 boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneEnabledAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int healed = to ? healLiftToneOffAudioAll(source.getServer(), which) : 0;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /**
     * /lifthelp up|down|door &lt;名字&gt; —— 设置**本维度**这项的默认素材。
     * 名字：{@code default}（模组内置）/ {@code none}（这一项不播）/ 玩家导入的 .ogg 文件名。
     */
    private static int liftToneAudioSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveLiftToneName(level, EscalatorSpeedManager.liftToneCategory(which), name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.setDefaultLiftToneAudio(level, which, arg.id());
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp up|down|door &lt;X&gt; to &lt;Y&gt; —— 本维度默认素材正好是 X 时才改成 Y。 */
    private static int liftToneAudioFromTo(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveLiftToneName(level, EscalatorSpeedManager.liftToneCategory(which), name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveLiftToneName(level, EscalatorSpeedManager.liftToneCategory(which), targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        if (!EscalatorSpeedManager.replaceDefaultLiftToneAudio(level, which, from.id(), to.id())) {
            String current = EscalatorSpeedManager.getLiftToneAudio(level, which);
            // 「不是 X 就没改」按惯例用 sendSuccess（不是错误，只是没命中），与 /futimusic X to Y 一致
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /**
     * /lifthelp up|down|door -f &lt;名字&gt; —— **所有维度**这项的默认素材都设成它，
     * 并清掉「按竖井列单独设置」里的这一项（那些直梯从此跟维度默认）。
     */
    private static int liftToneAudioForceSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveLiftToneName(level, EscalatorSpeedManager.liftToneCategory(which), name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultLiftToneAudioAll(source.getServer(), which, arg.id());
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        EscalatorSpeedManager.syncLiftToneToAll(source.getServer());
        String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** /lifthelp up|down|door -f &lt;X&gt; to &lt;Y&gt; —— 所有维度（含单独设置）里这项是 X 的改成 Y。 */
    private static int liftToneAudioForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveLiftToneName(level, EscalatorSpeedManager.liftToneCategory(which), name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveLiftToneName(level, EscalatorSpeedManager.liftToneCategory(which), targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneAudioAll(
                source.getServer(), which, from.id(), to.id());
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        EscalatorSpeedManager.syncLiftToneToAll(source.getServer());
        String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /**
     * 注册 `/lifthelp` 下面的一条子命令树：{@code up} / {@code down} / {@code open} / {@code close}。
     *
     * @param literal 指令里写的名字（与数据层同名：{@code up} / {@code down} / {@code open} / {@code close}）
     * @param which   数据层用的项目名（同 literal；【1.28】旧的 door → chime 别名已拆成 open / close 两个字面量）
     */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneBranch(String literal, String which) {
        String category = EscalatorSpeedManager.liftToneCategory(which);
        return Commands.literal(literal)
                .executes(context -> liftToneShow(context, literal, which))
                // ★ 顺序即优先级：字面量必须排在字符串参数前面（详见本节开头那段说明）。
                .then(Commands.literal("on")
                        .executes(context -> liftToneSwitchGlobal(context, literal, which, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> liftToneSwitchFromTo(context, which, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> liftToneSwitchGlobal(context, literal, which, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> liftToneSwitchFromTo(context, which, false, true)))))
                .then(Commands.literal("-f")
                        .then(Commands.literal("on")
                                .executes(context -> liftToneSwitchForceAll(context, which, true))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("off")
                                                .executes(context -> liftToneSwitchForceFromTo(context, which, true, false)))))
                        .then(Commands.literal("off")
                                .executes(context -> liftToneSwitchForceAll(context, which, false))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("on")
                                                .executes(context -> liftToneSwitchForceFromTo(context, which, false, true)))))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests((ctx, b) -> liftToneNameSuggestions(ctx, b, category))
                                .executes(context -> liftToneAudioForceSet(context, literal, which))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .suggests((ctx, b) -> liftToneNameSuggestions(ctx, b, category))
                                                .executes(context -> liftToneAudioForceFromTo(context, which))))))
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, b) -> liftToneNameSuggestions(ctx, b, category))
                        .executes(context -> liftToneAudioSet(context, literal, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests((ctx, b) -> liftToneNameSuggestions(ctx, b, category))
                                        .executes(context -> liftToneAudioFromTo(context, literal, which)))));
    }

    // ==================================================================
    // 【1.50】屏蔽门（MTR 平台幕门 / 半高安全门）开关门提示音指令：
    //   /pbmmusic  —— 总开关 + open/close 两项子开关
    //   /pbmloud   —— 共用默认音量 + open/close 两项各自的音量
    //   /pbmround  —— 淡入淡出范围（两项共用一份）
    // 【1.23】另加三条「按条音频各存一份」的范围指令（形状与 /pbmround 同构）：
    //   /pbmmusicround   —— 同上（点名用法；与 /pbmround 读写同一份数据）
    //   /pbmmidiumround  —— 到站播报那条音频的范围
    //   /pbmarriveround  —— 进站报站那条音频的范围
    // 结构与直梯那几套（/lifthelp[up|down|chime] + /lifthelploud + /lifthelpround）**完全对称**，
    // 只是把「up / down / chime 三项」换成「open / close 两项」。
    // ★ 数据是**每个维度一份**（与直梯同），所以：不带 -f 只改当前维度；带 -f 改所有维度。
    // ★ 与 /futihelp（扶梯无障碍提示音）、/lifthelp（直梯）互不影响，各存各的。
    // ==================================================================

    /** 注册 `/pbmmusic` 的 `-f` 分支：`-f on|off` 与 `-f on to off` / `-f off to on`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> pbmMusicForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> pbmMusicForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> pbmMusicForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> pbmMusicForceFromTo(context, false, true)))));
    }

    /**
     * `/pbmmusic open|close` 子树的完整形状：显示 / on|off / X to Y / -f on|off / -f X to Y，
     * 以及【1.15】改素材的 &lt;名字&gt; / &lt;X&gt; to &lt;Y&gt; / -f &lt;名字&gt; / -f &lt;X&gt; to &lt;Y&gt;。
     *
     * <p>★ 字面量（on / off / -f）必须排在字符串参数 {@code name} 前面 —— Brigadier 里
     * 字面量优先于参数匹配，顺序即优先级（与 {@link #liftToneBranch} 完全同一套理由）。
     *
     * @param literal 指令里出现的字面名（open / close）
     * @param which   数据用名（open / close，与 {@code EscalatorSpeedManager} 一致）
     */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmMusicItemCommand(String literal, String which) {
        String category = "open".equals(which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE;
        return Commands.literal(literal)
                .executes(context -> pbmMusicItemShow(context, which))
                .then(Commands.literal("on")
                        .executes(context -> pbmMusicItemGlobal(context, which, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> pbmMusicItemFromTo(context, which, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> pbmMusicItemGlobal(context, which, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> pbmMusicItemFromTo(context, which, false, true)))))
                .then(Commands.literal("-f")
                        .then(Commands.literal("on")
                                .executes(context -> pbmMusicItemForceAll(context, which, true))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("off")
                                                .executes(context -> pbmMusicItemForceFromTo(context, which, true, false)))))
                        .then(Commands.literal("off")
                                .executes(context -> pbmMusicItemForceAll(context, which, false))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("on")
                                                .executes(context -> pbmMusicItemForceFromTo(context, which, false, true)))))
                        // 【1.15】-f <名字> / -f <X> to <Y>
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests((ctx, b) -> psdToneNameSuggestions(ctx, b, category))
                                .executes(context -> pbmMusicItemAudioForceSet(context, literal, which))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .suggests((ctx, b) -> psdToneNameSuggestions(ctx, b, category))
                                                .executes(context -> pbmMusicItemAudioForceFromTo(context, which))))))
                // 【1.15】<名字> / <X> to <Y>（不带 -f = 只改本维度）
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, b) -> psdToneNameSuggestions(ctx, b, category))
                        .executes(context -> pbmMusicItemAudioSet(context, literal, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests((ctx, b) -> psdToneNameSuggestions(ctx, b, category))
                                        .executes(context -> pbmMusicItemAudioFromTo(context, literal, which)))));
    }

    /**
     * 【1.15】屏蔽门素材名参数的 Tab 补全：**四段内置名排最前 + none + 该分类导入的每个 .ogg**。
     * 与 {@link #liftToneNameSuggestions} 同一套「source 为 null 时给空补全」的保护。
     * 【1.28】按分类取（open → pbm/open、close → pbm/close）。
     */
    private static CompletableFuture<Suggestions> psdToneNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String category) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.psdNameCandidates(source.getLevel(), category)) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------
    // 【10-05】pbm* 指令不带 -f 的落点：**离玩家最近的那一串屏蔽门**
    //   指令跑在服务端线程，而「哪一串最近」只有客户端算得出来（要 MTR 客户端数据 +
    //   渲染每帧上报的 LIVE 快照）⇒ 走一次
    //   「S2C 请求（PSD_NEAREST_REQUEST_CHANNEL）→ 客户端算 runKey →
    //     C2S 回包（PSD_NEAREST_REPLY_CHANNEL）→ 服务端落地」。
    //   ★ 回执只在回包那一侧给一条，请求侧不回（否则一次操作两条消息）。
    //   ★ 没有玩家（控制台 / 命令方块）⇒ 算不了「附近」，回落**维度默认**（老行为，脚本照跑）。
    //   ★ 门串身份 = {@code PsdDoorTracker.runKeyOf}（与石斧 UI / 播放端**同一把尺子**）。
    // ------------------------------------------------------------------

    /**
     * 一次「按最近门串落地」的延迟操作。
     *
     * @param runKey 最近那一串的 runKey；{@code null} = 算不出来 ⇒ 由实现回落维度默认
     * @return true = 落地成功（回「指令执行成功」）
     */
    @FunctionalInterface
    private interface PsdNearestOp {
        boolean apply(ServerLevel level, Long runKey);
    }

    /** 待落地的操作 + 它要回执的那个指令源（每玩家一条 FIFO，同一条指令连敲也按序落地）。 */
    private record PendingNearest(CommandSourceStack source, PsdNearestOp op) {
    }

    /** 每玩家一条待办队列。 */
    private static final java.util.Map<java.util.UUID, java.util.ArrayDeque<PendingNearest>> PENDING_NEAREST =
            new java.util.HashMap<>();

    /** 队列上限：客户端不回包时不至于无限堆积（挤掉最老的）。 */
    private static final int PENDING_NEAREST_MAX = 8;

    /**
     * 【10-05】把一次屏蔽门设置落到「离玩家最近的那一串」（不带 -f 的 pbm* 指令都走这里）。
     *
     * @return 1 = 已受理（回执稍后由回包那一侧给）；无玩家时当场落地并回执
     */
    private static int psdNearestRun(CommandSourceStack source, PsdNearestOp op) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            boolean ok = op.apply(source.getLevel(), null);
            source.sendSuccess(() -> Component.literal(ok ? "指令执行成功" : "指令执行失败"), false);
            return ok ? 1 : 0;
        }
        java.util.ArrayDeque<PendingNearest> queue =
                PENDING_NEAREST.computeIfAbsent(player.getUUID(), k -> new java.util.ArrayDeque<>());
        if (queue.size() >= PENDING_NEAREST_MAX) {
            queue.pollFirst();
        }
        queue.addLast(new PendingNearest(source, op));
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PsdNearestRequestPacket());
        return 1;
    }

    /** 「打开」时顺手清掉**这一串**素材层的「不播」（与 {@link #healPsdToneOffAudio} 对称）。 */
    private static boolean healDoorPsdToneOffAudio(ServerLevel level, long runKey, String which) {
        String cur = EscalatorSpeedManager.getDoorPsdToneAudio(level, runKey, which);
        if (EscalatorSpeedData.PSD_TONE_OFF.equals(cur)) {
            return EscalatorSpeedManager.setServerPsdTone(
                    level, runKey, which, EscalatorSpeedData.PSD_TONE_DEFAULT);
        }
        return false;
    }

    /**
     * 【10-05】客户端回包（{@link PsdNearestReplyPacket}）→ 服务端落地 + 回一条统一回执。
     *
     * <p>Forge 侧没有 Fabric 那种 {@code registerGlobalReceiver}，回包由
     * {@code smooth.lift.network.PsdNearestReplyPacket} 的 handle 在服务端线程调到这里。
     */
    public static void onPsdNearestReply(ServerPlayer player, boolean found, long runKey) {
        java.util.ArrayDeque<PendingNearest> queue = PENDING_NEAREST.get(player.getUUID());
        PendingNearest pending = queue == null ? null : queue.pollFirst();
        if (pending == null) {
            // 超时 / 重复回包（队列被上限挤掉）：没有待落地的操作，静默。
            return;
        }
        boolean ok = found && pending.op().apply(player.serverLevel(), runKey);
        Component feedback = Component.literal(ok ? "指令执行成功" : "指令执行失败");
        CommandSourceStack src = pending.source();
        src.sendSuccess(() -> feedback, false);
    }

    /** `/pbmmusic`（不带参数）—— 显示当前维度的总开关与两项子开关。 */
    private static int pbmMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal((EscalatorSpeedManager.isPsdHelpEnabled(level) ? "1" : "0") + ", " + EscalatorSpeedManager.getPsdHelpVolume(level) + ", " + EscalatorSpeedManager.getPsdHelpRoundXz(level) + ", " + EscalatorSpeedManager.getPsdHelpRoundY(level)), false);
        return 1;
    }

    /** `/pbmmusic <on|off>` —— 设置**离玩家最近的那一串**屏蔽门的总开关（不带 -f）。 */
    private static int pbmMusicGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdHelp(level, enabled);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdHelp(level, runKey, enabled);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** `/pbmmusic <X> to <Y>` —— 最近那一串的总开关正好是 X 时才改成 Y（不带 -f）。 */
    private static int pbmMusicFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                if (!EscalatorSpeedManager.replaceDefaultPsdHelp(level, from, to)) {
                    return false;
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.isDoorPsdHelpEnabled(level, runKey) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdHelp(level, runKey, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }

    /** `/pbmmusic -f <on|off>` —— **所有维度**的总开关都设成该值。 */
    private static int pbmMusicForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdHelpAll(source.getServer(), enabled);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/pbmmusic -f <X> to <Y>` —— 所有维度里总开关正好是 X 的那些改成 Y。 */
    private static int pbmMusicForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdHelpAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/pbmmusic open|close`（不带参数）—— 显示当前维度这一项子开关与默认素材。 */
    private static int pbmMusicItemShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isPsdToneEnabled(level, which);
        String audio = EscalatorSpeedManager.getPsdToneAudio(level, which);
        source.sendSuccess(() -> Component.literal((enabled ? "1" : "0") + ", " + psdToneAudioLabel(audio)), false);
        return 1;
    }

    // ★【09-29 LOG6】屏蔽门这边与直梯**同一类陷阱**：「打开」这一下要把「没声音」的成因一起清掉。
    //   空白预设写的是 {@code pbmmusic … -f none}（**素材层**的不播）⇒ 之后 `/pbmmusic open on`
    //   只动子开关、素材仍是「不播」，一样是「开关开着却没声」。
    //   ⇒ 与 {@link #healLiftToneOffAudio} 对称：打开时若素材是「不播」就换回 {@code default}。

    /** 「打开」时顺手清掉屏蔽门素材层的「不播」。@return true = 真的清过 */
    private static boolean healPsdToneOffAudio(ServerLevel level, String which) {
        return EscalatorSpeedManager.replaceDefaultPsdToneAudio(
                level, which, EscalatorSpeedData.PSD_TONE_OFF, EscalatorSpeedData.PSD_TONE_DEFAULT);
    }

    /** 同上，作用于**所有维度**（`-f` 分支）。@return 真的清过的维度数 */
    private static int healPsdToneOffAudioAll(MinecraftServer server, String which) {
        return EscalatorSpeedManager.replaceDefaultPsdToneAudioAll(
                server, which, EscalatorSpeedData.PSD_TONE_OFF, EscalatorSpeedData.PSD_TONE_DEFAULT);
    }

    /** `/pbmmusic open|close <on|off>` —— 设置**最近那一串**这一项子开关（不带 -f）。 */
    private static int pbmMusicItemGlobal(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdToneEnabled(level, which, enabled);
                if (enabled) {
                    healPsdToneOffAudio(level, which);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdToneEnabled(level, runKey, which, enabled);
                if (enabled) {
                    healDoorPsdToneOffAudio(level, runKey, which);
                }
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** `/pbmmusic open|close <X> to <Y>` —— 最近那一串这一项正好是 X 时才改成 Y（不带 -f）。 */
    private static int pbmMusicItemFromTo(CommandContext<CommandSourceStack> context, String which,
                                          boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                if (!EscalatorSpeedManager.replaceDefaultPsdToneEnabled(level, which, from, to)) {
                    return false;
                }
                if (to) {
                    healPsdToneOffAudio(level, which);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.isDoorPsdToneEnabled(level, runKey, which) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdToneEnabled(level, runKey, which, to);
            if (to) {
                healDoorPsdToneOffAudio(level, runKey, which);
            }
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }

    /** `/pbmmusic -f open|close <on|off>` —— **所有维度**这一项子开关都设成该值。 */
    private static int pbmMusicItemForceAll(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdToneEnabledAll(source.getServer(), which, enabled);
        int healed = enabled ? healPsdToneOffAudioAll(source.getServer(), which) : 0;
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/pbmmusic -f open|close <X> to <Y>` —— 所有维度里这一项子开关正好是 X 的改成 Y。 */
    private static int pbmMusicItemForceFromTo(CommandContext<CommandSourceStack> context, String which,
                                               boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdToneEnabledAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int healed = to ? healPsdToneOffAudioAll(source.getServer(), which) : 0;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.15】/pbmmusic open|close <名字>：屏蔽门提示音的**默认素材**（与 /lifthelp up|down|door 对称）。
    //   数据层在维度默认那一份里（open → defaultPsdToneAudioOpen、close → defaultPsdToneAudioClose），
    //   走 chime 同步包下发；-f 还会清/改「按扇门单独设置」那一张表，所以额外再走一次 tone 同步包。
    // ------------------------------------------------------------------

    /**
     * {@code /pbmmusic open|close <名字>} —— 设置**本维度**这一项的默认素材。
     * 名字：{@code default}（跟内置，按端别落 dooropen/mdoorclose）/ {@code default-c}（doorclose.ogg）
     * / {@code default-m}（mdoorclose.ogg）/ {@code none}（这一项不播）/ 玩家导入的 .ogg 文件名。
     */
    private static int pbmMusicItemAudioSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolvePsdToneName(level, "open".equals(which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdToneAudio(lv, which, arg.id());
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else if (!EscalatorSpeedManager.setServerPsdTone(lv, runKey, which, arg.id())) {
                return false;
            } else {
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** {@code /pbmmusic open|close <X> to <Y>} —— 本维度默认素材正好是 X 时才改成 Y。 */
    private static int pbmMusicItemAudioFromTo(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolvePsdToneName(level, "open".equals(which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolvePsdToneName(level, "open".equals(which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (!EscalatorSpeedManager.replaceDefaultPsdToneAudio(lv, which, from.id(), to.id())) {
                    // 「不是 X 就没改」按惯例算失败，与直梯那套一致
                    return false;
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            String current = EscalatorSpeedManager.getDoorPsdToneAudio(lv, runKey, which);
            if (!from.id().equals(current)) {
                return false;
            }
            if (!EscalatorSpeedManager.setServerPsdTone(lv, runKey, which, to.id())) {
                return false;
            }
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }

    /**
     * {@code /pbmmusic open|close -f <名字>} —— **所有维度**这一项的默认素材都设成它，
     * 并清掉「按扇门单独设置」里的这一项（那些门从此跟维度默认）。
     */
    private static int pbmMusicItemAudioForceSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolvePsdToneName(level, "open".equals(which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultPsdToneAudioAll(source.getServer(), which, arg.id());
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** {@code /pbmmusic open|close -f <X> to <Y>} —— 所有维度（含单独设置）里这项是 X 的改成 Y。 */
    private static int pbmMusicItemAudioForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolvePsdToneName(level, "open".equals(which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolvePsdToneName(level, "open".equals(which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.replaceDefaultPsdToneAudioAll(
                source.getServer(), which, from.id(), to.id());
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.50】/pbmloud：屏蔽门提示音音量（1~1000，100 = 原始音量，1000 = 10×）
    //   形状与 /lifthelploud 完全一致，只是「三项」变「两项」。
    // ------------------------------------------------------------------

    /** 注册 `/pbmloud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::pbmLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::pbmLoudForceFromTo))));
    }

    /** `/pbmloud open|close` 的音量子树（不带 -f）：`<音量>` 与 `<X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudItemCommand(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> pbmLoudItemGlobal(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> pbmLoudItemFromTo(context, which)))));
    }

    /** `-f` 节点下的单项分支：`open|close <音量>` 与 `open|close <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudItemForceBranch(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> pbmLoudItemForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> pbmLoudItemForceFromTo(context, which)))));
    }

    /** `/pbmloud`（不带参数）—— 显示当前维度生效的共用默认音量。 */
    private static int pbmLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int volume = EscalatorSpeedManager.getPsdHelpVolume(level);
        source.sendSuccess(() -> Component.literal(volume + ", " + pbmVolumeText(level, "open") + ", " + pbmVolumeText(level, "close")), false);
        return 1;
    }

    /** 单项音量的显示文本：没单独调过就标「跟随共用」。 */
    private static String pbmVolumeText(ServerLevel level, String which) {
        int v = EscalatorSpeedManager.getPsdToneVolume(level, which);
        return EscalatorSpeedManager.hasOwnPsdToneVolume(level, which) ? String.valueOf(v) : v + "跟随共用";
    }

    /** `/pbmloud <音量>` —— 设置**本维度**的共用默认音量。 */
    private static int pbmLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdHelpVolume(lv, volume);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdHelpVolume(lv, runKey, volume);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** `/pbmloud <X> to <Y>` —— 本维度共用音量正好是 X 时才改成 Y。 */
    private static int pbmLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdHelpVolume(lv) != from) {
                    return false;
                }
                EscalatorSpeedManager.replaceDefaultPsdHelpVolume(lv, from, to);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.getDoorPsdHelpVolume(lv, runKey) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdHelpVolume(lv, runKey, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }

    /** `/pbmloud -f <音量>` —— **所有维度**的共用音量都设成该值。 */
    private static int pbmLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdHelpVolumeAll(source.getServer(), volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftHelpVolume(volume);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/pbmloud -f <X> to <Y>` —— 所有维度里共用音量正好是 X 的那些改成 Y。 */
    private static int pbmLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdHelpVolumeAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftHelpVolume(to);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/pbmloud open|close <音量>` —— 设置**本维度**这一项的音量。 */
    private static int pbmLoudItemGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdToneVolume(lv, which, volume);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdToneVolume(lv, runKey, which, volume);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** `/pbmloud open|close <X> to <Y>` —— 本维度这一项音量正好是 X 时才改成 Y。 */
    private static int pbmLoudItemFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdToneVolume(lv, which) != from) {
                    return false;
                }
                EscalatorSpeedManager.replaceDefaultPsdToneVolume(lv, which, from, to);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.getDoorPsdToneVolume(lv, runKey, which) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdToneVolume(lv, runKey, which, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }

    /** `/pbmloud -f open|close <音量>` —— **所有维度**这一项都设成该音量。 */
    private static int pbmLoudItemForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdToneVolumeAll(source.getServer(), which, volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampPsdToneVolume(volume);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/pbmloud -f open|close <X> to <Y>` —— 所有维度里这一项音量正好是 X 的改成 Y。 */
    private static int pbmLoudItemForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdToneVolumeAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampPsdToneVolume(to);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.22】/pbmmidiumloud / /pbmarriveloud：到站播报 / 进站报站
    //   **各自那一项**的音量。形状与 /pbmloud 完全一致。
    // ------------------------------------------------------------------

    /** 这一项的中文名（反馈文案用）。 */
    private static String pbmItemLabel(String which) {
        return "midium".equals(which) ? "到站播报" : "进站报站";
    }

    /** 这一项音量指令的名字（反馈里提示 -f 用哪个指令）。 */
    private static String pbmItemCommand(String which) {
        return "midium".equals(which) ? "/pbmmidiumloud" : "/pbmarriveloud";
    }

    /** 注册 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmItemLoudForce(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> pbmItemLoudForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> pbmItemLoudForceFromTo(context, which)))));
    }

    /** 无参数——显示当前维度这一项生效的音量。 */
    private static int pbmItemLoudShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int v = EscalatorSpeedManager.getPsdItemVolume(level, which);
        boolean own = EscalatorSpeedManager.hasOwnPsdItemVolume(level, which);
        source.sendSuccess(() -> Component.literal("" + v), false);
        return 1;
    }

    /** `<音量>` —— 设置**本维度**这一项的音量。 */
    private static int pbmItemLoudGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            boolean midium = "midium".equals(which);
            if (runKey == null) {
                if (midium) {
                    EscalatorSpeedManager.setDefaultPsdMidiumVolume(lv, volume);
                } else {
                    EscalatorSpeedManager.setDefaultPsdArriveVolume(lv, volume);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                if (midium) {
                    EscalatorSpeedManager.setDoorPsdMidiumVolume(lv, runKey, volume);
                } else {
                    EscalatorSpeedManager.setDoorPsdArriveVolume(lv, runKey, volume);
                }
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** `<X> to <Y>` —— 本维度这一项音量正好是 X 时才改成 Y。 */
    private static int pbmItemLoudFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            boolean midium = "midium".equals(which);
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdItemVolume(lv, which) != from) {
                    return false;
                }
                if (midium) {
                    EscalatorSpeedManager.replaceDefaultPsdMidiumVolume(lv, from, to);
                } else {
                    EscalatorSpeedManager.replaceDefaultPsdArriveVolume(lv, from, to);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            int current = midium
                    ? EscalatorSpeedManager.getDoorPsdMidiumVolume(lv, runKey)
                    : EscalatorSpeedManager.getDoorPsdArriveVolume(lv, runKey);
            if (current != from) {
                return false;
            }
            if (midium) {
                EscalatorSpeedManager.setDoorPsdMidiumVolume(lv, runKey, to);
            } else {
                EscalatorSpeedManager.setDoorPsdArriveVolume(lv, runKey, to);
            }
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }

    /** `-f <音量>` —— **所有维度**这一项都设成该音量。 */
    private static int pbmItemLoudForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = "midium".equals(which)
                ? EscalatorSpeedManager.setDefaultPsdMidiumVolumeAll(source.getServer(), volume)
                : EscalatorSpeedManager.setDefaultPsdArriveVolumeAll(source.getServer(), volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampPsdToneVolume(volume);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `-f <X> to <Y>` —— 所有维度里这一项音量正好是 X 的改成 Y。 */
    private static int pbmItemLoudForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = "midium".equals(which)
                ? EscalatorSpeedManager.replaceDefaultPsdMidiumVolumeAll(source.getServer(), from, to)
                : EscalatorSpeedManager.replaceDefaultPsdArriveVolumeAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampPsdToneVolume(to);
        source.sendSuccess(() -> Component.literal("指令执行成功"),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.50 / 1.23】三类「淡入淡出范围」（格）—— 四条指令共用同一套形状
    //
    //   /pbmround        = 屏蔽门开关门提示音（open / close 两项共用一份）
    //   /pbmmusicround   = 同上（用户点名的名字；与 /pbmround 读写**同一份数据**）
    //   /pbmmidiumround  = 到站播报（/pbmmidium 那条音频）
    //   /pbmarriveround  = 进站报站（/pbmarrive 那条音频）
    //
    //   形状完全同构（都是「一个整数 + to + -f」）：
    //     （无参数）      -> 显示当前维度生效的范围
    //     <范围>          -> 本维度范围 = 范围（1~128）
    //     <X> to <Y>      -> 本维度范围正好是 X 时才改成 Y
    //     -f <范围>       -> 强制**所有维度** = 范围
    //     -f <X> to <Y>   -> 所有维度里范围正好是 X 的改成 Y
    //
    //   ★ 为什么三类要各存一份而不是继续共用提示音那一个值：开关门提示音是**机械事件**
    //     （站在门口听最合理，默认 16 格），站台广播 / 进站报站是**说给整个站台听的**，
    //     用户希望各自能调 —— 与 1.22 把三类**音量**拆开是同一个理由。
    //   ★ 三者默认都是 16 格 ⇒ 老存档、以及没敲过这几条指令时，行为与 1.22 及以前**逐位相同**。
    //   ★ 四条指令的树由 {@link #roundCommand} 一处产出 ⇒ 形状想不一致都难。
    // ------------------------------------------------------------------

    /** 这几条范围指令读写的是**哪一组**字段。 */
    private enum RoundKind {
        /** 屏蔽门开关门提示音（{@code /pbmround}、{@code /pbmmusicround}）。 */
        TONE("屏蔽门提示音"),
        /** 到站播报（{@code /pbmmidiumround}）。 */
        MIDIUM("到站播报"),
        /** 进站报站（{@code /pbmarriveround}）。 */
        ARRIVE("进站报站");

        /** 反馈文案里的那一项名字（与 /pbmloud 那一套用词一致）。 */
        private final String label;

        RoundKind(String label) {
            this.label = label;
        }
    }

    /** 读「本维度」的范围（返回 {水平 xz, 垂直 y}）。 */
    private static int[] roundOf(CommandSourceStack source, RoundKind kind) {
        return switch (kind) {
            case MIDIUM -> new int[]{
                    EscalatorSpeedManager.getPsdMidiumRoundXz(source.getLevel()),
                    EscalatorSpeedManager.getPsdMidiumRoundY(source.getLevel())};
            case ARRIVE -> new int[]{
                    EscalatorSpeedManager.getPsdArriveRoundXz(source.getLevel()),
                    EscalatorSpeedManager.getPsdArriveRoundY(source.getLevel())};
            default -> new int[]{
                    EscalatorSpeedManager.getPsdHelpRoundXz(source.getLevel()),
                    EscalatorSpeedManager.getPsdHelpRoundY(source.getLevel())};
        };
    }

    /** 写「本维度」的范围。 */
    private static void setRound(ServerLevel level, RoundKind kind, int xz, int y) {
        switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.setDefaultPsdMidiumRound(level, xz, y);
            case ARRIVE -> EscalatorSpeedManager.setDefaultPsdArriveRound(level, xz, y);
            default -> EscalatorSpeedManager.setDefaultPsdHelpRound(level, xz, y);
        }
    }

    /** 「本维度范围正好是 X 才改成 Y」；返回成没成。 */
    private static boolean replaceRound(ServerLevel level, RoundKind kind,
                                        int fromXz, int fromY, int toXz, int toY) {
        return switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.replaceDefaultPsdMidiumRound(level, fromXz, fromY, toXz, toY);
            case ARRIVE -> EscalatorSpeedManager.replaceDefaultPsdArriveRound(level, fromXz, fromY, toXz, toY);
            default -> EscalatorSpeedManager.replaceDefaultPsdHelpRound(level, fromXz, fromY, toXz, toY);
        };
    }

    /** 「所有维度 = 范围」；返回改动个数。 */
    private static int setRoundAll(MinecraftServer server, RoundKind kind, int xz, int y) {
        return switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.setDefaultPsdMidiumRoundAll(server, xz, y);
            case ARRIVE -> EscalatorSpeedManager.setDefaultPsdArriveRoundAll(server, xz, y);
            default -> EscalatorSpeedManager.setDefaultPsdHelpRoundAll(server, xz, y);
        };
    }

    /** 「所有维度里范围正好是 X 的改成 Y」；返回改动个数。 */
    private static int replaceRoundAll(MinecraftServer server, RoundKind kind,
                                       int fromXz, int fromY, int toXz, int toY) {
        return switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.replaceDefaultPsdMidiumRoundAll(server, fromXz, fromY, toXz, toY);
            case ARRIVE -> EscalatorSpeedManager.replaceDefaultPsdArriveRoundAll(server, fromXz, fromY, toXz, toY);
            default -> EscalatorSpeedManager.replaceDefaultPsdHelpRoundAll(server, fromXz, fromY, toXz, toY);
        };
    }

    /** 造一条范围指令（含 `-f` 分支）；四条指令都由它产出 ⇒ 形状必然一致。 */
    private static LiteralArgumentBuilder<CommandSourceStack> roundCommand(String literal, RoundKind kind) {
        return Commands.literal(literal)
                .executes(context -> roundShow(context, kind))
                .then(Commands.argument("xz", roundArg())
                        .then(Commands.argument("y", roundArg())
                                .executes(context -> roundGlobal(context, kind))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("targetXz", roundArg())
                                                .then(Commands.argument("targetY", roundArg())
                                                        .executes(context -> roundFromTo(context, kind)))))))
                .then(roundForce(kind));
    }

    /** 范围指令的 `-f` 分支：`-f <XZ> <Y>` 与 `-f <fromXz> <fromY> to <toXz> <toY>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> roundForce(RoundKind kind) {
        return Commands.literal("-f")
                .then(Commands.argument("xz", roundArg())
                        .then(Commands.argument("y", roundArg())
                                .executes(context -> roundForceAll(context, kind))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("targetXz", roundArg())
                                                .then(Commands.argument("targetY", roundArg())
                                                        .executes(context -> roundForceFromTo(context, kind)))))));
    }

    /** （不带参数）—— 显示当前维度生效的可闻范围。 */
    private static int roundShow(CommandContext<CommandSourceStack> context, RoundKind kind) {
        CommandSourceStack source = context.getSource();
        int[] round = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal(round[0] + ", " + round[1]), false);
        return 1;
    }

    /** `<XZ> <Y>` —— 设置**本维度**的范围。 */
    private static int roundGlobal(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        setRound(level, kind, xz, y);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int[] applied = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `<fromXz> <fromY> to <toXz> <toY>` —— 本维度范围正好是前两个数时才改成后两个数。 */
    private static int roundFromTo(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!replaceRound(level, kind, fromXz, fromY, toXz, toY)) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int[] applied = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `-f <XZ> <Y>` —— **所有维度**都设成该范围。 */
    private static int roundForceAll(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int xz = IntegerArgumentType.getInteger(context, "xz");
        int y = IntegerArgumentType.getInteger(context, "y");
        CommandSourceStack source = context.getSource();
        setRoundAll(source.getServer(), kind, xz, y);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int[] applied = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `-f <fromXz> <fromY> to <toXz> <toY>` —— 所有维度里范围正好是前两个数的那些改成后两个数。 */
    private static int roundForceFromTo(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int fromXz = IntegerArgumentType.getInteger(context, "xz");
        int fromY = IntegerArgumentType.getInteger(context, "y");
        int toXz = IntegerArgumentType.getInteger(context, "targetXz");
        int toY = IntegerArgumentType.getInteger(context, "targetY");
        CommandSourceStack source = context.getSource();
        int changed = replaceRoundAll(source.getServer(), kind, fromXz, fromY, toXz, toY);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.16】/pbmclosewait：关门提示音的「强制等待时长」（秒）
    //
    //   语义（只在「停站时长不够放完整条关门素材」时生效）：
    //     开门音效播完 → 等 N 秒 → 播语音播报 → 门一动（嘀嘀开始）就立刻掐断这段人声。
    //   停站够长时这个值被**完全忽略**（走「整段提前播、结尾落在门上」那套）。
    //   默认 5 秒（第一次加入模组时就是这个值）。
    //
    //   形状与 /pbmround 完全同构：显示 / <秒> / <X> to <Y> / -f <秒> / -f <X> to <Y>。
    // ------------------------------------------------------------------

    /** 注册 `/pbmclosewait` 的 `-f` 分支：`-f <秒>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmCloseWaitForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("seconds", closeWaitArg())
                        .executes(SmoothLift::pbmCloseWaitForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", closeWaitArg())
                                        .executes(SmoothLift::pbmCloseWaitForceFromTo))));
    }

    /** `/pbmclosewait`（不带参数）—— 显示当前维度生效的强制等待时长。 */
    private static int pbmCloseWaitShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int seconds = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        source.sendSuccess(() -> Component.literal("" + seconds), false);
        return 1;
    }

    /** `/pbmclosewait <秒>` —— 设置**本维度**的强制等待时长。 */
    private static int pbmCloseWaitGlobal(CommandContext<CommandSourceStack> context) {
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                EscalatorSpeedManager.setDefaultPsdCloseWaitSeconds(lv, seconds);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                EscalatorSpeedManager.setDoorPsdCloseWaitSeconds(lv, runKey, seconds);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** `/pbmclosewait <X> to <Y>` —— 本维度正好是 X 时才改成 Y。 */
    private static int pbmCloseWaitFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "seconds");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return psdNearestRun(source, (lv, runKey) -> {
            if (runKey == null) {
                if (EscalatorSpeedManager.getPsdCloseWaitSeconds(lv) != from) {
                    return false;
                }
                EscalatorSpeedManager.replaceDefaultPsdCloseWaitSeconds(lv, from, to);
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
                return true;
            }
            if (EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(lv, runKey) != from) {
                return false;
            }
            EscalatorSpeedManager.setDoorPsdCloseWaitSeconds(lv, runKey, to);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            return true;
        });
    }

    /** `/pbmclosewait -f <秒>` —— **所有维度**都设成该值。 */
    private static int pbmCloseWaitForceAll(CommandContext<CommandSourceStack> context) {
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdCloseWaitSecondsAll(source.getServer(), seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(source.getLevel());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/pbmclosewait -f <X> to <Y>` —— 所有维度里正好是 X 的那些改成 Y。 */
    private static int pbmCloseWaitForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "seconds");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdCloseWaitSecondsAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.17】/pbmmidium：到站播报（素材名 + 等待秒数）
    //
    //   语义（与关门提示音那一套**互不相干**，两段声音各自独立）：
    //     列车到站 → 屏蔽门**开门音（嘀嘀嘀）播完** → 等 Y 秒 → 播这一段语音播报。
    //   ★ 它**永远不会被掐断**（用户点名「即使列车出站也要继续播放，直到播完」）——
    //     门关不关、车走不走都不影响它。
    //
    //   形状：显示 / <名字> / <名字> <秒> / -f <名字> <秒>。
    // ------------------------------------------------------------------

    /** 注册 `/pbmmidium` 的 `-f` 分支：`-f <名字> <秒>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmMidiumForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("name", midiumNameArg())
                        .suggests(SmoothLift::pbmMidiumNameSuggestions)
                        .then(Commands.argument("seconds", midiumWaitArg())
                                .executes(SmoothLift::pbmMidiumForceAll)));
    }

    /** `/pbmmidium`（不带参数）—— 显示当前维度生效的到站播报。 */
    private static int pbmMidiumShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String id = EscalatorSpeedManager.getPsdMidiumAudio(level);
        int seconds = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
        boolean off = EscalatorSpeedData.isPsdMidiumOff(id);
        source.sendSuccess(() -> Component.literal((off ? "无" : id) + ", " + seconds), false);
        return 1;
    }

    /** `/pbmmidium <名字>` —— 只改素材，保留**最近那一串**当前的等待秒数（不带 -f）。 */
    private static int pbmMidiumSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) -> {
            int seconds = runKey == null
                    ? EscalatorSpeedManager.getPsdMidiumWaitSeconds(lv)
                    : EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(lv, runKey);
            return applyMidiumToRun(source, lv, runKey, resolved, seconds);
        });
    }

    /** `/pbmmidium <名字> <秒>` —— 设置**最近那一串**（不带 -f）。 */
    private static int pbmMidiumGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) ->
                applyMidiumToRun(source, lv, runKey, resolved, seconds));
    }

    /** `/pbmmidium -f <名字> <秒>` —— **所有维度**。 */
    private static int pbmMidiumForceAll(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(source.getLevel(), EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultPsdMidiumAll(source.getServer(), resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdMidiumWaitSeconds(source.getLevel());
        boolean off = EscalatorSpeedData.isPsdMidiumOff(resolved);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /**
     * `本维度` 那条路共用的落地：解析名字 → 落库 → 同步 → 反馈。
     *
     * @param suffix 反馈尾注（`null` = 不带尾注）
     */
    private static int pbmMidiumApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(level, EscalatorSpeedManager.CAT_PSD_MIDIUM, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        EscalatorSpeedManager.setDefaultPsdMidium(level, resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
        boolean off = EscalatorSpeedData.isPsdMidiumOff(resolved);
        String tail = suffix == null ? "" : suffix;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /**
     * 【10-05】站台广播的落地：runKey == null ⇒ 维度默认；否则只写那一串。
     */
    private static boolean applyMidiumToRun(CommandSourceStack source, ServerLevel level, Long runKey,
                                            String resolved, int seconds) {
        if (runKey == null) {
            EscalatorSpeedManager.setDefaultPsdMidium(level, resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        } else {
            EscalatorSpeedManager.setDoorPsdMidium(level, runKey, resolved, seconds);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        }
        return true;
    }

    /** 「这个名字找不到」的统一提示（顺便列出该分类里已有的名字）。 */
    private static void sendUnknownMidiumName(CommandSourceStack source, String name) {
        java.util.List<String> have = EscalatorSpeedManager.psdMidiumSuggestions(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_MIDIUM);
        String list = String.join("、", have);
        source.sendFailure(Component.literal("指令执行失败"));
    }

    /** `/pbmmidium` 素材名参数的 Tab 补全。 */
    private static CompletableFuture<Suggestions> pbmMidiumNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.psdMidiumSuggestions(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_MIDIUM)) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------
    // 【1.21】/pbmarrive：**进站报站**（素材名 + 秒数：-X = 最近一班车还剩 X 秒到站时播）
    //
    //   语义（与到站播报**互相独立**，两段声音各自播各自的）：
    //     时刻表里下一班车还有 |X| 秒到站 → 播这一段语音，一直播到完。
    //   ★ 与 /pbmmidium 的**唯一本质差别**是触发时刻：到站播报在「开门音之后 + Y 秒」，
    //     进站报站在「开门音**之前** |X| 秒」。相同的是「永远不会被掐断」。
    //   ★ 触发靠的是**时刻表**（MTR 自己的到达缓存，见 MtrDwellAccess#nearestArrival），
    //     不是靠猜列车位置/速度 —— 用户点名「看时刻表啊，不要猜」。
    //
    //   形状：显示 / <名字> / <名字> <X> / -f <名字> <X>。
    // ------------------------------------------------------------------

    /** 注册 `/pbmarrive` 的 `-f` 分支：`-f <名字> <X>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmArriveForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("name", arriveNameArg())
                        .suggests(SmoothLift::pbmArriveNameSuggestions)
                        .then(Commands.argument("seconds", arriveArg())
                                .executes(SmoothLift::pbmArriveForceAll)));
    }

    /** `/pbmarrive`（不带参数）—— 显示当前维度生效的进站报站。 */
    private static int pbmArriveShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String id = EscalatorSpeedManager.getPsdArriveAudio(level);
        int seconds = EscalatorSpeedManager.getPsdArriveSeconds(level);
        boolean off = EscalatorSpeedData.isPsdArriveOff(id);
        source.sendSuccess(() -> Component.literal((off ? "无" : id) + ", " + (-seconds)), false);
        return 1;
    }

    /** `/pbmarrive <名字>` —— 只改素材，保留**最近那一串**当前的秒数（不带 -f）。 */
    private static int pbmArriveSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) -> {
            int seconds = runKey == null
                    ? EscalatorSpeedManager.getPsdArriveSeconds(lv)
                    : EscalatorSpeedManager.getDoorPsdArriveSeconds(lv, runKey);
            return applyArriveToRun(source, lv, runKey, resolved, seconds);
        });
    }

    /** `/pbmarrive <名字> <X>` —— 设置**最近那一串**（不带 -f）。 */
    private static int pbmArriveGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        return psdNearestRun(source, (lv, runKey) ->
                applyArriveToRun(source, lv, runKey, resolved, seconds));
    }

    /** `/pbmarrive -f <名字> <X>` —— **所有维度**。 */
    private static int pbmArriveForceAll(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(source.getLevel(), EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultPsdArriveAll(source.getServer(), resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdArriveSeconds(source.getLevel());
        boolean off = EscalatorSpeedData.isPsdArriveOff(resolved);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【09-28 续 2】/pbmnarrate：进站广播（讲述人）的**样式**
    //   为什么只有样式这一格：讲述人的句子是运行时用文字转语音现拼的 ——
    //   **没有音频素材、也没有音量**（com.mojang.text2speech 系列库不给音量/速率参数），
    //   所以与 /pbmmusic、/pbmloud 那几套不同，这里没有「名字」和「音量」两格，
    //   只有「念哪一句」的三档枚举（关 / 上海 / 香港）。
    //   ★ 秒数（到站前 N 秒，(-∞, 0]）**不在这条指令里**：它在石斧右键屏蔽门 UI
    //     主界面「进站广播(讲述人)」那一行的输入框里填。本指令一律**原样保留**秒数；
    //     `-f` 也不动其它维度各自的秒数（见 setDefaultPsdNarrateModeAll 的注释）。
    // ------------------------------------------------------------------

    /** 注册 `/pbmnarrate <样式>`：三个样式各一个字面量分支。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmNarrateStyle(String literal, int mode) {
        return Commands.literal(literal).executes(context -> pbmNarrateGlobal(context, mode));
    }

    /** 注册 `/pbmnarrate -f <样式>`：三个样式各一个字面量分支（-f = 所有维度 + 抹掉按串覆盖）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmNarrateForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("off").executes(
                        context -> pbmNarrateForceAll(context, EscalatorSpeedData.PSD_NARRATE_OFF)))
                .then(Commands.literal("shanghai").executes(
                        context -> pbmNarrateForceAll(context, EscalatorSpeedData.PSD_NARRATE_SHANGHAI)))
                .then(Commands.literal("hongkong").executes(
                        context -> pbmNarrateForceAll(context, EscalatorSpeedData.PSD_NARRATE_HONGKONG)));
    }

    /** `/pbmnarrate`（不带参数）—— 显示本维度生效的讲述人样式与秒数。 */
    private static int pbmNarrateShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int mode = EscalatorSpeedManager.getPsdNarrateMode(level);
        int seconds = EscalatorSpeedManager.getPsdNarrateSeconds(level);
        source.sendSuccess(() -> Component.literal(EscalatorSpeedData.psdNarrateModeName(mode) + ", " + (-seconds)), false);
        return 1;
    }

    /** `/pbmnarrate <样式>` —— 设置**最近那一串**的讲述人样式（不带 -f；★ 秒数原样保留）。 */
    private static int pbmNarrateGlobal(CommandContext<CommandSourceStack> context, int mode) {
        CommandSourceStack source = context.getSource();
        return psdNearestRun(source, (level, runKey) -> {
            if (runKey == null) {
                // 秒数不在本指令里 ⇒ 把本维度**当前**的维度默认秒数原样传回去（别顺手清零）。
                EscalatorSpeedManager.setDefaultPsdNarrate(level, mode,
                        EscalatorSpeedManager.getPsdNarrateSeconds(level));
                EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            } else {
                // 按串那一层：秒数是它自己那一格，本指令只动样式。
                EscalatorSpeedManager.setDoorPsdNarrate(level, runKey, mode);
                EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            }
            return true;
        });
    }

    /** `/pbmnarrate -f <样式>` —— **所有维度**都设成该样式，并抹掉按串覆盖。 */
    private static int pbmNarrateForceAll(CommandContext<CommandSourceStack> context, int mode) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdNarrateModeAll(source.getServer(), mode);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdNarrateMode(source.getLevel());
        String tail = changed == 0 ? "（本来就都是这一档）" : "";
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `本维度` 那条路共用的落地：解析名字 → 落库 → 同步 → 反馈。 */
    private static int pbmArriveApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(level, EscalatorSpeedManager.CAT_PSD_ARRIVE, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        EscalatorSpeedManager.setDefaultPsdArrive(level, resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdArriveSeconds(level);
        boolean off = EscalatorSpeedData.isPsdArriveOff(resolved);
        String tail = suffix == null ? "" : suffix;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /**
     * 【10-05】进站报站的落地：runKey == null ⇒ 维度默认；否则只写那一串。
     */
    private static boolean applyArriveToRun(CommandSourceStack source, ServerLevel level, Long runKey,
                                            String resolved, int seconds) {
        if (runKey == null) {
            EscalatorSpeedManager.setDefaultPsdArrive(level, resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        } else {
            EscalatorSpeedManager.setDoorPsdArrive(level, runKey, resolved, seconds);
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        }
        return true;
    }

    /** 「这个名字找不到」的统一提示（顺便列出该分类里已有的名字）。 */
    private static void sendUnknownArriveName(CommandSourceStack source, String name) {
        java.util.List<String> have = EscalatorSpeedManager.psdArriveSuggestions(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_ARRIVE);
        String list = String.join("、", have);
        source.sendFailure(Component.literal("指令执行失败"));
    }

    /** `/pbmarrive` 素材名参数的 Tab 补全。 */
    private static CompletableFuture<Suggestions> pbmArriveNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.psdArriveSuggestions(source.getLevel(),
                EscalatorSpeedManager.CAT_PSD_ARRIVE)) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    /**
     * 注册全部指令：`/futispeed`、`/jietispeed`、`/futimusic`、`/futiloud`、`/futihelp`、
     * `/futihelploud`、`/futiround`、`/futihelpround`、`/futihelpspeed`、`/futihelpmusic`，
     * 以及【1.42】直梯提示音的 `/lifthelp`、`/lifthelpspeed`、【1.43】`/lifthelploud`，
     * 以及【1.50】屏蔽门提示音的 `/pbmmusic`、`/pbmloud`、`/pbmround`、
     * 【1.16】`/pbmclosewait`。
     *
     * <p>单独抽成一个方法是为了能**脱离游戏环境**直接建一棵 Brigadier 指令树来校验：
     * {@code _tools/CmdTreeCheck.java} 会把整棵树的补全项 dump 出来，确认
     * {@code /futihelp on to off}、{@code /futihelploud -f 200 to 300} 这类分支真的可达、
     * Tab 补全能补得出来。
     */
    /**
     * {@code /MBM music in}：一键把 &lt;存档&gt;/MBM_Audio 文件夹里**所有** .ogg 导入存档音频库。
     *
     * <p>逐条走 {@link EscalatorSpeedManager#importAudioToStore}（同一套 ogg 校验：大小上限、
     * 必须是可播的 Ogg Vorbis）；同名已入库的会被覆盖（幂等）。**不改任何绑定 / 设置** ——
     * 扶梯 / 直梯 / 提示音 / 屏蔽门当前指到哪个音频不受影响，导入完它们就能在对应选择列表里看到。
     *
     * <p>用户点名（【1.23】）：一键导入 MBM_Audio 里的所有音频（ogg 文件）到存档。
     * 【1.53】用户点名把根指令从 {@code /dtmusic} 改成 {@code /MBM music}（方法名保留，
     * 因为它描述的是「批量导入」这件事，与指令怎么写无关）。
     */
    private static int dtMusicImportAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        // 【1.28】批量导入 = 扫**全部分类子文件夹**（MBM_Audio/<分类>），各自入库到各自分类。
        java.util.Map<String, String> found = new java.util.LinkedHashMap<>(); // 文件名 → 分类
        for (String category : EscalatorSpeedManager.ALL_CATEGORIES) {
            for (String name : EscalatorSpeedManager.scanAudioFiles(level, category).keySet()) {
                found.putIfAbsent(name, category);
            }
        }
        if (found.isEmpty()) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int ok = 0;
        int skipped = 0;
        for (java.util.Map.Entry<String, String> e : found.entrySet()) {
            if (EscalatorSpeedManager.importAudioToStore(level, e.getValue(), e.getKey()) == null) {
                ok++;
            } else {
                skipped++;
            }
        }
        String msg = "已导入 " + ok + " 条音频到存档"
                + (skipped > 0 ? "，跳过 " + skipped + " 条" : "");
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        if (ok > 0 && source.getPlayer() != null) {
            // 必须补发音频库同步包：客户端的「已导入」列表（右列）就是从它来的。
            EscalatorSpeedManager.sendAudioSyncTo(source.getPlayer(), level);
        }
        return ok;
    }

    /**
     * {@code /MBM music delete}：从存档音频库删除**所有**已导入的音频。
     *
     * <p>逐条走 {@link EscalatorSpeedManager#deleteAudio}（它内部的 removeAudio 会把引用这段音频的
     * 扶梯绑定 / 无障碍提示音 / 直梯提示音与默认素材 / 屏蔽门单门与维度默认一并退化成默认或「不播」），
     * 再补与单条删除通道相同的三路同步。★ MBM_Audio 文件夹里的 ogg 文件**原样保留**（一个不删）。
     *
     * <p>用户点名（【1.23】）：从存档删除所有导入的 MBM_Audio 里的音频文件，但不删除
     * MBM_Audio 文件夹里的音乐（ogg 文件）。
     * 【1.53】用户点名把根指令从 {@code /dtmusic} 改成 {@code /MBM music delete}。
     */
    private static int dtMusicDeleteAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedData data = EscalatorSpeedManager.getServerData(level);
        // 【1.28】删除所有 = 每个分类的已导入名字都删一遍（同名在多个分类时也逐个清干净）。
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (java.util.Set<String> names : data.audioCategoryNames.values()) {
            ids.addAll(names);
        }
        if (ids.isEmpty()) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int removed = 0;
        int midiumCleared = 0;
        int arriveCleared = 0;
        for (String id : ids) {
            for (String category : EscalatorSpeedManager.ALL_CATEGORIES) {
                if (EscalatorSpeedManager.deleteAudio(level, category, id)) {
                    removed++;
                }
            }
            // 与单条删除通道相同的两路兜底：到站播报 / 进站报站指向已删音频的按各自语义回落。
            midiumCleared += EscalatorSpeedManager.clearPsdMidiumIfRemoved(level.getServer(), id);
            arriveCleared += EscalatorSpeedManager.clearPsdArriveIfRemoved(level.getServer(), id);
        }
        String msg = "已从存档删除 " + removed + " 条音频"
                + (midiumCleared > 0 ? "；" + midiumCleared + " 扇门的到站播报已一并改成「不播」" : "")
                + (arriveCleared > 0 ? "；" + arriveCleared + " 扇门的进站报站已一并改成「不播」" : "");
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        if (removed > 0) {
            EscalatorSpeedManager.syncAudioToAll(level.getServer());
            EscalatorSpeedManager.syncHelpAudioToAll(level.getServer());
            EscalatorSpeedManager.syncPsdChimeToAll(level.getServer());
            if (source.getPlayer() != null) {
                EscalatorSpeedManager.sendAudioSyncTo(source.getPlayer(), level);
            }
        }
        return removed;
    }

    /**
     * {@code /MBM picture fold}：【09-29】在**客户端本机**打开存档里的图片导入文件夹
     * （{@code <存档>/MBM_Picture}）。
     *
     * <p>与界面右上角那个「打开文件夹」按钮是同一件事，只是触发点不同：服务端发**相对路径**过去、
     * 客户端按自己的存档根打开（见 {@link #MBM_OPEN_FOLDER_CHANNEL}）。
     *
     * <p>服务端这一侧只做两件事：确保文件夹存在（否则「打开」会指向一个不存在的目录）、
     * 把相对路径发给执行指令的那个玩家。控制台 / 命令方块（没有玩家）直接失败 ——
     * 文件夹要开在人**自己**的电脑上。
     */
    private static int dtPictureOpenFolder(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.ensurePictureFolder(source.getLevel());
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MbmOpenFolderPacket(EscalatorSpeedManager.PICTURE_FOLDER));
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /**
     * {@code /MBM picture}：【1.18.1204】无参数：反馈当前显示图片的名字。
     *
     * <p>查询「合并视图」的当前显示图片（与同步负载同规则：跳过空维度）。
     * 没有显示任何图片时给出可用的后续指令提示。
     */
    private static int dtPictureQuery(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String current = EscalatorSpeedManager.getMergedPictureCurrent(source.getServer());
        if (current == null) {
            source.sendSuccess(() -> Component.literal("无"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(current), false);
        return 1;
    }

    /**
     * {@code /MBM picture new}：【1.18.1204】把 MBM_Picture 文件夹里的**所有**图片批量导入存档图片库。
     *
     * <p>逐张走 {@link EscalatorSpeedManager#importPictureToStore}（大小上限校验；同名已入库的会被
     * 覆盖，幂等）。★ 图片原始字节直接融入存档（SavedData），之后删除 MBM_Picture 文件夹里的原图
     * 也不受影响。客户端收到同步后把「当前显示图片」切成 4 块、每块缩放到 1024×1024、按角落给
     * 外边缘加 64px 灰边，再写进图片方块图集并烘焙上传。
     *
     * <p>本次导入的图片会接替当前显示：显示库中**字典序最大**的一张（导入完立刻能在方块上看到）。
     */
    private static int dtPictureNewAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        java.util.Map<String, byte[]> found = EscalatorSpeedManager.scanPictureFiles(level);
        if (found.isEmpty()) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        int ok = 0;
        int skipped = 0;
        java.util.TreeSet<String> imported = new java.util.TreeSet<>();
        for (String name : found.keySet()) {
            if (EscalatorSpeedManager.importPictureToStore(level, name) == null) {
                ok++;
                imported.add(name);
            } else {
                skipped++;
            }
        }
        if (ok > 0) {
            // 本次导入的所有图片里字典序最大的一张成为「当前显示图片」。
            EscalatorSpeedManager.setPictureCurrent(level, imported.last());
        }
        String msg = "已导入 " + ok + " 张图片到存档"
                + (skipped > 0 ? "，跳过 " + skipped + " 张" : "")
                + (EscalatorSpeedManager.lastScanRejected > 0
                        ? "，另有 " + EscalatorSpeedManager.lastScanRejected + " 张因体积/尺寸超限被忽略" : "")
                + (ok > 0 ? "；图片方块将显示「" + imported.last() + "」" : "");
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        if (ok > 0) {
            EscalatorSpeedManager.syncPictureToAll(level.getServer());
            if (source.getPlayer() != null) {
                EscalatorSpeedManager.sendPictureSyncTo(source.getPlayer(), level);
            }
        }
        return ok;
    }

    /**
     * {@code /MBM picture <名字>}：【1.18.1204】切换当前显示图片为库中名为 XXX 的图片。
     *
     * <p>只改「当前显示」，不删库、不动 MBM_Picture 文件夹里的原图。
     * 图片方块贴图是全局的，因此对**所有维度**一起切换（避免某维度残留别的当前图）。
     */
    private static int dtPictureSwitch(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        MinecraftServer server = source.getServer();
        boolean found = false;
        for (ServerLevel lv : server.getAllLevels()) {
            if (EscalatorSpeedManager.getServerData(lv).pictureLibrary.containsKey(name)) {
                found = true;
                EscalatorSpeedManager.setPictureCurrent(lv, name);
            }
        }
        if (!found) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        EscalatorSpeedManager.syncPictureToAll(server);
        if (source.getPlayer() != null) {
            EscalatorSpeedManager.sendPictureSyncTo(source.getPlayer(), source.getPlayer().serverLevel());
        }
        return 1;
    }

    /**
     * {@code /MBM picture delete <名字>}：【1.18.1204】只删存档里名为 XXX 的这张图片。
     *
     * <p>只删存档里融入的那份字节（SavedData），**不动** MBM_Picture 文件夹里的原图。
     * 若删的正是「当前显示图片」，则回退显示库中剩下的一张（字典序最大）；所有维度一并处理。
     */
    private static int dtPictureDeleteOne(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        MinecraftServer server = source.getServer();
        int removed = 0;
        for (ServerLevel lv : server.getAllLevels()) {
            EscalatorSpeedData data = EscalatorSpeedManager.getServerData(lv);
            if (data.pictureLibrary.containsKey(name)) {
                EscalatorSpeedManager.deletePictureFromStore(lv, name);
                if (name.equals(data.pictureCurrent)) {
                    EscalatorSpeedManager.setPictureCurrent(lv, EscalatorSpeedManager.largestLibraryKey(lv));
                }
                removed++;
            }
        }
        if (removed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        EscalatorSpeedManager.syncPictureToAll(server);
        if (source.getPlayer() != null) {
            EscalatorSpeedManager.sendPictureSyncTo(source.getPlayer(), source.getPlayer().serverLevel());
        }
        return 1;
    }

    /**
     * {@code /MBM picture delete}：【1.18.1204】清空存档里**所有**融入的图片。
     *
     * <p>只删存档里融入的那份字节（SavedData），**不动** MBM_Picture 文件夹里的原图。
     * 清空后客户端把所有图片方块贴图恢复成「白色 + 64px 灰边」（灰色边框保留，符合用户要求）。
     * 图片方块贴图是全局的，因此**所有维度**的图片库一起清空。
     */
    private static int dtPictureDeleteAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        int count = 0;
        for (ServerLevel lv : server.getAllLevels()) {
            EscalatorSpeedData data = EscalatorSpeedManager.getServerData(lv);
            count += data.pictureLibrary.size();
            data.pictureLibrary.clear();
            data.pictureCurrent = null;
            data.setDirty();
        }
        final int total = count;
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        EscalatorSpeedManager.syncPictureToAll(server);
        if (source.getPlayer() != null) {
            EscalatorSpeedManager.sendPictureSyncTo(source.getPlayer(), source.getPlayer().serverLevel());
        }
        return count > 0 ? 1 : 0;
    }

    // ==================================================================
    // 【1.53】「预设选择」界面：三个「港铁预设」+ 全音量
    //
    // ★★ 预设的实现选择（**这是本功能最关键的一条决定**）：
    //   预设 = 「依次执行若干条指令」，所以服务端就是**把那几条指令原样派发一遍**
    //   （{@code Commands#performPrefixedCommand}），而不是把各条的效果手抄成
    //   setter 调用。理由有两条，第二条才是真正的坑：
    //     1. 手抄 = 同一份规则写在两处（指令一份、预设一份）⇒ 迟早分叉；
    //     2. `/pbmmusic open -f off` 这种写法**在人看来有歧义**（是「子开关关掉」还是
    //        「素材设成不播」？），只有 Brigadier 知道它落到哪个分支（字面量优先 ⇒ 子开关）。
    //        派发指令 = 让 Brigadier 自己决定，界面按一下与手敲的效果**天然一致**。
    //
    // 【1.58】★★ 上面第 2 条踩实了：`pbmmusic open -f off` 确实落到**子开关**，
    //   于是「空白预设」把 open/close 的子开关关掉了；而「港铁预设」那两条
    //   `pbmmusic open -f default` **只写素材、不碰子开关** ⇒ 声音再也回不来
    //   （用户报「点空白预设后开关门声音没了，点港铁预设也设不回来」，LOG8 实证：
    //    空白预设后 `子开关 open=false close=false`，港铁预设后**仍是 false**）。
    //
    //   ⇒ 两条规矩（以后改预设必须同时满足）：
    //     ① **预设要「静音」，就把「不播」写在子开关层**（`lifthelp open -f off`）。
    //        ★ 这一条【09-29 LOG6】才定下来，见下面那一段（此前一度写成「要用素材层的
    //        `none`」，那是错的 —— 会把用户所有「打开」入口全废掉）。
    //     ② **要「恢复出声」的预设必须显式把子开关打开**（`lifthelp open -f on`）——
    //        素材和子开关是**两层**，只写素材不等于出声；否则预设名叫「港铁预设」
    //        却救不回被关掉的开关，名不副实。
    //   ★ 通用问法：**这条指令落到哪一层？**（总开关层 / 子开关层 / 素材层）——
    //     见 crossround 教训：同名 token（`off`）在两层上含义不同，解析函数必须交代落在哪。
    //
    // 【1.58】★★ 上面第 2 条踩实了：`pbmmusic open -f off` 确实落到**子开关**，
    //   于是「空白预设」把 open/close 的子开关关掉了；而「港铁预设」那两条
    //   `pbmmusic open -f default` **只写素材、不碰子开关** ⇒ 声音再也回不来
    //   （用户报「点空白预设后开关门声音没了，点港铁预设也设不回来」，LOG8 实证）。
    //   当时的结论是「所以『不播』要改用素材层的正名 `none`」——
    //
    // 【09-29 LOG6】★★★ 这个结论**只对了一半，而且实现错了**（直梯上直接踩雷）：
    //     · 对的一半：`off` 确实落在子开关层，不是「素材设成不播」。
    //     · **错的那一半**：把一个「静音」预设的「不播」写到**素材层**（`-f none`）之后，
    //       **用户所有「打开」的入口都够不到它** —— 当时的石斧 UI 右列那行「开关：切换」
    //       与 `/lifthelp open on` 动的都是**子开关**，素材层那个 `off` 谁也清不掉。
    //       （★【09-29 · 二改】那只手动「开关」按钮已按用户点名整行删掉；UI 侧「打开」
    //         出口只剩 `ensureToneEnabled`。）于是「开关显示『开』、就是没声」，
    //       用户眼里 = **无论怎么设置都开启不了**（LOG6 实证：`子开关 open=true close=true`
    //       + `默认素材 open=off close=off` + 8 条「素材就是「不播」…跳过」）。
    //   ⇒ 本轮定案（两个域都照这个来）：
    //     ① 预设要静音 = 关**子开关**（那一层才被所有「打开」入口够得着）；
    //     ② 恢复出声的预设 = `<which> -f on` 把子开关打开；
    //     ③ 「选了素材却不出声」（LOG5：子开关关着 ⇒ 播放端第一道门
    //        `!isLiftToneEnabled` 直接短路）由**播放端/UI 侧**修，不再靠改预设：
    //        · `LiftToneSetupScreen#ensureToneEnabled` —— 在 UI 里选**会出声**的素材时，
    //          若子开关是关的就一并打开；
    //        · `SmoothLift#healLiftToneOffAudio` —— 「打开」这一下若发现素材还是「不播」
    //          （用户手动设的，或**早期版本把预设写在素材层**留下的存档），一并换回 `default`；
    //     ④ 预设**不再写素材层的「不播」** ⇒ 素材层永远只有 `default` / `.ogg`，
    //        「不播」只由用户显式选择产生。
    //   ★★ 教训（比这一条 bug 更值钱）：**「层判据」这种规则只写在源码注释里，就等于没写** ——
    //     回归脚本当初只扫了 `pbmmusic` 两行（`PSD_LINES`），直梯那四行从断言底下漏了过去；
    //     后来把直梯那四行「改对」了，可**判据本身是照着上面那个错结论写的**，
    //     于是把一个 bug 换成了另一个、还全绿通过。⇒ 判据要盯**语义**（「打开」够不够得着），
    //     不是盯某个 token 的写法；而且规则一改，判据必须跟着改。
    // ==================================================================

    /** 【1.58】「经典港铁预设」= 依次执行这 **13** 条指令（前 10 条 = 用户点名清单；其余见上面 ①②）。
     *  【1.28】直梯 door 拆成 open / close 两条。
     *  ★ 直梯那 4 条 {@code <which> -f on} = 把**子开关**打开（简单/空白预设会关掉它们，
     *   本预设负责「出声」的那一半）。**不写素材** —— 免得覆盖用户自己导入的 .ogg。
     *  【09-28 续 2】加第 13 条：进站广播（讲述人）= **开启(香港)** —— 用户点名
     *  「进站广播功能增加到 mbmhelp 的『经典港铁预设』里」。
     *  【09-28 续 4】用户点名**三档全定**：只剩本预设开讲述人（**香港**样式）；
     *  另两个预设（简单港铁 / 空白）**一律关闭讲述人** ⇒ 见 `PRESET_SIMPLE_MTR` /
     *  `PRESET_BLANK` 的末条。全局总闸 `/jsr` 由客户端那一下点击联动（`MbmHelpScreen`）。 */
    private static final String[] PRESET_CLASSIC_MTR = {
            "futimusic -f default",
            "futihelp -f on",
            "lifthelp -f on",
            "lifthelp open -f on",
            "lifthelp close -f on",
            "lifthelp up -f on",
            "lifthelp down -f on",
            "pbmclosewait -f 1",
            "pbmmusic open -f default",
            "pbmmusic close -f default-m",
            // ★ 两条 `pbmmusic … -f on` = 把 open/close 的**子开关**打开；没有它们，预设救不回
            //   「子开关被关掉」的存档（见上面 ①②）。
            "pbmmusic open -f on",
            "pbmmusic close -f on",
            // ★【09-28 续 2】进站广播（讲述人）＝开启(香港)。`-f` 与上面同口径（所有维度 + 抹掉按串覆盖）。
            //   它只改**样式**，各维度的「到站前 N 秒」原样不动。
            // ★【10-05 用户点名】范围六条（扶梯底噪 10 5 → 扶梯提示音 5 5 → 直梯提示音 5 5 →
            //   屏蔽门提示音 10 5 → 到站播报 10 5 → 进站报站 10 5）。`-f` 与其它条同口径：
            //   所有维度 + 清掉按串/按方块覆盖。★ 两条**无障碍提示音**归 5 5（用户点名，同首次加载默认）。
            "futiround -f 10 5",
            "futihelpround -f 5 5",
            "lifthelpround -f 5 5",
            "pbmround -f 10 5",
            "pbmmidiumround -f 10 5",
            "pbmarriveround -f 10 5",
"pbmnarrate hongkong -f",
    };

    /** 【1.58】「简单港铁预设」= 依次执行这 **13** 条指令（前 10 条 = 用户点名清单；其余同经典）。
     *  【09-28 续 2】第 13 条进来过（当时是 开启(上海)）。
     *  【09-28 续 4】★★ 用户点名改为 **关闭讲述人**（原话：「简单港铁预设 和 空白预设
     *  都是要关闭讲述人的，经典港铁预设 是讲述人调成香港风格」）⇒ 第 13 条 = `pbmnarrate off -f`。
     *  ★ `off` 在**本类指令里就是正名**（= 「样式 = 关闭」那一档的字面量），
     *  与 `pbmmusic`/`lifthelp` 那套「素材层 / 子开关层」的两层歧义**无关** —— 讲述人只有这一层。
     *  ★★★【09-29 LOG6】第 4/5 条 `lifthelp open|close -f off` = **本预设「关掉直梯开关门提示音」
     *  的正解**：`off` 落到**子开关**层（字面量优先于音频名字参数）。用户报的
     *  「选了简单港铁预设就无论怎么设置都开不了」**不是**因为写 `off`，而是因为上一轮
     *  一度把它改成**素材层**的 `none` —— 那种写法下 UI「开关」行与 `open on` 都够不到它。
     *  ⇒ 本预设保持「关子开关」的写法；「打开了就要出声」由
     *  `LiftToneSetupScreen#ensureToneEnabled` 与 `SmoothLift#healLiftToneOffAudio` 保证。 */
    private static final String[] PRESET_SIMPLE_MTR = {
            "futimusic -f default",
            "futihelp -f off",
            "lifthelp -f on",
            // ★ 这两条 = 本预设的「不播」：关的是**子开关**（不是素材）。子开关层才是所有
            //   「打开」入口（UI 右列第 0 行「开关」/ `/lifthelp open on`）够得着的地方。
            "lifthelp open -f off",
            "lifthelp close -f off",
            "lifthelp up -f on",
            "lifthelp down -f on",
            "pbmclosewait -f 1",
            "pbmmusic open -f default",
            "pbmmusic close -f default-s",
            "pbmmusic open -f on",
            "pbmmusic close -f on",
            // ★【09-28 续 4】进站广播（讲述人）＝**关闭**（用户点名：简单港铁预设要关闭讲述人）。
            // ★【10-05 用户点名】范围六条（扶梯底噪 10 5 → 扶梯提示音 5 5 → 直梯提示音 5 5 →
            //   屏蔽门提示音 10 5 → 到站播报 10 5 → 进站报站 10 5）。`-f` 与其它条同口径：
            //   所有维度 + 清掉按串/按方块覆盖。★ 两条**无障碍提示音**归 5 5（用户点名，同首次加载默认）。
            "futiround -f 10 5",
            "futihelpround -f 5 5",
            "lifthelpround -f 5 5",
            "pbmround -f 10 5",
            "pbmmidiumround -f 10 5",
            "pbmarriveround -f 10 5",
"pbmnarrate off -f",
    };

    /**
     * 【1.58】「空白预设」= 依次执行这 **11** 条指令（前 10 条 = 用户点名清单，逐字照抄）。
     *
     * <p>★「空白」= 一切都关掉：总开关 {@code lifthelp -f off}、四项子开关
     * {@code lifthelp open|close|up|down -f off}、PSD 两项子开关 {@code pbmmusic open|close -f off}。
     * ★★ 两个域的 {@code … -f off} 里 {@code off} 都是**子开关**的字面量（字面量优先于
     * 音频名字参数）—— 这里**正是想要的**：静音就该写在子开关那一层。
     * 写成**素材层**的 {@code none} 会让 UI「开关」行与 {@code … on} 指令都够不到它
     * （直梯侧就是 LOG6 那个 bug）。
     *
     * <p>★★ 【09-28 续 4】第 11 条 `pbmnarrate off -f` = **关闭讲述人**，用户点名
     * 「简单港铁预设 和 空白预设 都是要关闭讲述人的」。★ 上一轮的「空白预设**不加**讲述人那一条」
     * 已被用户明确推翻 —— 空白预设要的是「**关**」，不是「不管」。
     */
    private static final String[] PRESET_BLANK = {
            "futimusic -f off",
            "futihelp -f off",
            "lifthelp -f off",
            // ★ 两个域的「不播」一律关**子开关**（那一层才被所有「打开」入口够得着）。
            "lifthelp open -f off",
            "lifthelp close -f off",
            "lifthelp up -f off",
            "lifthelp down -f off",
            "pbmclosewait -f 1",
            "pbmmusic open -f off",
            "pbmmusic close -f off",
            // ★【09-28 续 4】进站广播（讲述人）＝**关闭**。
            // ★【10-05 用户点名】范围六条（扶梯底噪 10 5 → 扶梯提示音 5 5 → 直梯提示音 5 5 →
            //   屏蔽门提示音 10 5 → 到站播报 10 5 → 进站报站 10 5）。`-f` 与其它条同口径：
            //   所有维度 + 清掉按串/按方块覆盖。★ 两条**无障碍提示音**归 5 5（用户点名，同首次加载默认）。
            "futiround -f 10 5",
            "futihelpround -f 5 5",
            "lifthelpround -f 5 5",
            "pbmround -f 10 5",
            "pbmmidiumround -f 10 5",
            "pbmarriveround -f 10 5",
"pbmnarrate off -f",
    };

    /**
     * 【1.53】把所有音量一起设成同一个值的指令清单（顺序 = 执行顺序）。
     *
     * <p>共 12 条，覆盖模组**全部**音量设置：
     * <ol>
     *   <li>{@code futiloud -f V} —— 扶梯运行底噪（含清掉按方块的单独音量）；</li>
     *   <li>{@code futihelploud -f V} —— 扶梯无障碍提示音（含清掉按方块的单独音量）；</li>
     *   <li>{@code lifthelploud -f V} —— 直梯共用默认音量；</li>
     *   <li>{@code lifthelploud -f up|down|open|close V} —— 直梯四项各自的音量（否则单独调过的那项会盖掉共用值）；</li>
     *   <li>{@code pbmloud -f V} —— 屏蔽门共用默认音量（含清掉按扇门的单独音量）；</li>
     *   <li>{@code pbmloud -f open|close V} —— 屏蔽门两项各自的音量；</li>
     *   <li>{@code pbmmidiumloud -f V} / {@code pbmarriveloud -f V} —— 到站播报 / 进站报站。</li>
     * </ol>
     * ★ 不含「淡入淡出范围」「等待秒数」「播哪段素材」—— 那些不是音量。
     */
    private static String[] allVolumeCommands(int volume) {
        String v = String.valueOf(volume);
        return new String[]{
                "futiloud -f " + v,
                "futihelploud -f " + v,
                "lifthelploud -f " + v,
                "lifthelploud -f up " + v,
                "lifthelploud -f down " + v,
                "lifthelploud -f open " + v,
                "lifthelploud -f close " + v,
                "pbmloud -f " + v,
                "pbmloud -f open " + v,
                "pbmloud -f close " + v,
                "pbmmidiumloud -f " + v,
                "pbmarriveloud -f " + v,
        };
    }

    /**
     * 【1.53】按预设 id 取指令清单；认不出的 id 返回 {@code null}。
     *
     * <p>id 就是界面按钮用的小写字符串（{@code classic} / {@code simple} / {@code blank}）。
     * 认不出时**返回 null 而不是空数组** —— 空数组会让「打错包」表现成「按了没反应」，
     * null 能让上层明确回一句「未知预设」。
     */
    private static String[] presetCommands(String presetId) {
        if ("classic".equals(presetId)) {
            return PRESET_CLASSIC_MTR;
        }
        if ("simple".equals(presetId)) {
            return PRESET_SIMPLE_MTR;
        }
        if ("blank".equals(presetId)) {
            return PRESET_BLANK;
        }
        return null;
    }

    /** 【1.53】预设 id → 玩家看到的按钮名（与界面上的按钮文字**必须一致**，改一处要改两处）。 */
    public static String presetLabel(String presetId) {
        if ("classic".equals(presetId)) {
            return "「经典港铁预设」";
        }
        if ("simple".equals(presetId)) {
            return "「简单港铁预设」";
        }
        if ("blank".equals(presetId)) {
            return "「空白预设」";
        }
        return "「" + presetId + "」";
    }

    /**
     * 【1.53】在服务端**依次执行**若干条指令。
     *
     * <p>★ {@code withSuppressedOutput()} 是必须的：这批指令每条都会
     * {@code sendSuccess} 一句「已强制…」，8~11 条叠起来会把聊天框刷满。压制掉之后由调用方
     * 自己发**一条**汇总。
     *
     * <p>用**玩家自己的**指令源（不是 {@code server.createCommandSourceStack()}）：与「玩家手敲」
     * 完全同构 —— 维度取玩家所在维度、权限取玩家权限。模组的指令都没有
     * {@code .requires(...)}（普通玩家也能敲），所以非 OP 玩家按预设同样生效。
     *
     * @return 成功执行的条数（解析失败的会被 Brigadier 计进异常、不致抛，跳过继续）
     */
    private static int runCommandBatch(ServerPlayer player, String[] commands) {
        CommandSourceStack base = player.createCommandSourceStack().withSuppressedOutput();
        int ok = 0;
        for (String command : commands) {
            // performPrefixedCommand 内部会吃掉解析失败（只记录异常），不会把整批打断。
            player.getServer().getCommands().performPrefixedCommand(base, command);
            ok++;
        }
        return ok;
    }

    /** 【1.53】/MBM help：让这个玩家打开「预设选择」界面（真正的界面在客户端）。 */
    private static int mbmOpenHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MbmHelpOpenPacket());
        return 1;
    }

    /**
     * 【1.53】应用一个港铁预设（界面上按一下按钮走这里）。
     *
     * @param presetId classic / simple / blank
     * @return 执行的指令条数；未知 id 返回 0
     */
    public static int applyPreset(ServerPlayer player, String presetId) {
        String[] commands = presetCommands(presetId);
        if (commands == null) {
            return 0;
        }
        int count = runCommandBatch(player, commands);
        // 【10-01】经典港铁预设：除了末条 `pbmnarrate hongkong -f`（只改样式），
        //   再把「提前量」一起设成 **-20 秒**（用户点名：经典港铁预设 = 进站广播 -20 秒）。
        //   -f 口径 = 所有维度 + 抹掉按串覆盖（setDefaultPsdNarrateLeadAll 内部做）。
        if (count > 0 && "classic".equals(presetId) && player.server != null) {
            if (EscalatorSpeedManager.setDefaultPsdNarrateLeadAll(player.server, -20) > 0) {
                EscalatorSpeedManager.syncPsdChimeToAll(player.server);
            }
        }
        return count;
    }

    /**
     * 【1.53】把模组所有音量一起设成 {@code volume}（界面底部输入框按 ESC 时走这里）。
     */
    public static int applyAllVolumes(ServerPlayer player, int volume) {
        int clamped = EscalatorSpeedData.clampHelpVolume(volume);
        runCommandBatch(player, allVolumeCommands(clamped));
        return clamped;
    }

    // ==================================================================
    // 【1.55】同步：把「当前这一项」的设定同步给同域的其它项
    // ==================================================================

    /** 【1.55】扶梯的二级页编号：1 = 运行底噪素材，2 = 无障碍提示音素材。 */
    public static final int SYNC_ESC_AUDIO = 1;
    public static final int SYNC_ESC_HELP_AUDIO = 2;
    /** 【1.55】一级菜单的编号（三个域通用）：0。 */
    public static final int SYNC_TOP_LEVEL = 0;
    /** 【1.55】直梯二级页编号 1/2/3/4 = up/down/open/close（与界面 {@code PAGES} 同序）。 */
    static final String[] SYNC_LIFT_WHICH = {"up", "down", "open", "close"};
    /** 【1.55】屏蔽门二级页编号 1/2 = open/close 素材。 */
    static final String[] SYNC_PSD_WHICH = {"open", "close"};
    /** 【1.55】屏蔽门二级页编号 3 = 到站播报素材，4 = 进站报站素材。 */
    static final int SYNC_PSD_MIDIUM_PAGE = 3;
    static final int SYNC_PSD_ARRIVE_PAGE = 4;
    /** 【09-28】屏蔽门二级页编号 5 = 进站广播（讲述人）开关 + 秒数。 */
    static final int SYNC_PSD_NARRATE_PAGE = 5;
    /** 【09-30 续 3】屏蔽门二级页编号 6 = 站台广播（讲述人）开关 + 秒数。 */
    static final int SYNC_PSD_MIDIUM_NARRATE_PAGE = 6;

    /**
     * 【09-30】闸机二级页编号 1/2 = 进站 / 出站素材（与界面 {@code PAGES} 同序）。
     *
     * <p>闸机只有两个方向、一两行就是全部，所以没有「一级菜单拆成多页」那种复杂度。
     */
    static final String[] SYNC_ZHAJI_WHICH = {"in", "out"};

    /**
     * 【1.55】「同步」的总入口。
     *
     * <p>两个按钮的**射程完全一样**，都由 {@code scope} 决定；差别只在 {@code force}：
     * <ul>
     *   <li>{@code force=false}「同步所有」—— 只写「默认」那一层，等同**不带 {@code -f}** 的指令。
     *       没单独设置过的项跟着默认走 ⇒ 拿到同一个值；单独设置过的项保留自己的
     *       ⇒ 正是用户说的「不包括修改过的」。**不需要枚举所有项。**</li>
     *   <li>{@code force=true}「强制同步」—— 写默认 + 清掉该项的单独设置，等同**带 {@code -f}**
     *       的指令 ⇒ 连修改过的一起变成同一个值。</li>
     * </ul>
     *
     * <p>射程（{@code scope}）＝ 玩家当前所在的那一层菜单：
     * <pre>
     * 扶梯 0 = 速度 / 阶梯速度 / 声音音量 / 提示音音量 / 无障碍开关          「扶梯设置」页
     *      1 = 运行底噪素材                                           「选择扶梯音乐」页
     *      2 = 无障碍提示音素材，进 + 出两端                              「选择无障碍提示音」页
     * 直梯 0 = 三项提示音素材：上楼 / 下楼 / 开关门                          主界面
     *      1/2/3 = 该项提示音的素材                                    三项各自的列表页
     * 屏蔽门 0 = 关门等待 / 开门音量 / 关门音量 / 到站等待+音量 / 进站秒数+音量   主界面
     *       1/2 = 开门 / 关门提示音素材                                两个提示音列表页
     *       3 = 到站播报素材                                          到站播报页
     *       4 = 进站报站素材                                          进站报站页
     * 列车 0 = 运行 / 转弯 / 道岔 / 进站 / 出站 五项音量                「列车音效」页
     *       ★【1.56】数据层未接（用户点名「功能不着急做」）⇒ 见 {@link #syncTrain}，只回话不写
     * </pre>
     *
     * <p>★ 两条**故意不进射程**的东西，理由都是「那一层不存在同一件事」：
     * <ul>
     *   <li>直梯的「音量 / 子开关」——它们本来就是**维度默认**、不是按项数据，
     *       把默认写回默认等于没写；而且硬写会把「跟随共用默认」的项钉死成固定值。</li>
     *   <li>屏蔽门的「开门等待」——它**没有维度默认字段**，只有按门那一层，
     *       现有数据模型表达不了「同步给没设置过的门」。</li>
     * </ul>
     *
     * <p>★ 反馈文案一律**不带括号**（用户点名的规范）。
     *
     * @return 给玩家看的一句话结果
     */
    public static String syncSettings(MinecraftServer server, ServerLevel level,
                                       String domain, int scope, boolean force, long key) {
        return syncSettings(server, level, domain, scope, force, key, 0L);
    }

    /**
     * 【10-04 修 2】带 **extraKey** 的完整版：PSD 主界面多了一个**门串级**的设置
     * （「关门后等待发车」，键 = 门串锚点 runAnchor），而弹窗原来只带一个 key（车站级 runKey）
     * ⇒ 服务端读不到「这一串此刻生效的发车等待」。PSD 主页的同步按钮把 runAnchor 塞进
     * {@code extraKey} 一起带来；其它域不用它（传 0）。
     */
    public static String syncSettings(MinecraftServer server, ServerLevel level,
                                       String domain, int scope, boolean force, long key, long extraKey) {
        return switch (domain) {
            case "esc" -> syncEscalator(server, level, scope, force, BlockPos.of(key));
            case "lift" -> syncLift(server, level, scope, force, key);
            case "psd" -> syncPsd(server, level, scope, force, key, extraKey);
            case "train" -> syncTrain(server, level, scope, force, key);
            case "zhaji" -> syncZhaji(server, level, scope, force, key);
            default -> "同步失败：未知的范围 " + domain;
        };
    }

    /**
     * 【09-30】闸机域同步。{@code scope}：0 = 进站 + 出站两侧，1 = 进站，2 = 出站。
     *
     * <p>与直梯那一支同一套语义：先取「这一侧此刻**生效**的值」（= 本维度自己那一份，
     * 闸机没有更细的粒度），再按 {@code force} 决定「只改本维度」还是「所有维度都改」。
     */
    private static String syncZhaji(MinecraftServer server, ServerLevel level, int scope, boolean force,
                                    long groupKey) {
        String[] whichs;
        if (scope == SYNC_TOP_LEVEL) {
            whichs = SYNC_ZHAJI_WHICH;
        } else if (scope >= 1 && scope <= SYNC_ZHAJI_WHICH.length) {
            whichs = new String[]{SYNC_ZHAJI_WHICH[scope - 1]};
        } else {
            return "同步失败：未知的闸机页 " + scope;
        }
        // ★【09-30 续】读的是**这一组**（界面右键的那一组）此刻生效的值，写的是**维度默认层** ——
        //   这就是「同步所有」的语义：把这一组这一套推给所有闸机（与屏蔽门 syncPsd 同一套口径）。
        //   groupKey = ZHAJI_GROUP_NONE（指令 / 没有组）时读回默认层自己，等于只改维度范围。
        for (String which : whichs) {
            String id = EscalatorSpeedManager.getZhajiToneAudio(level, which, groupKey);
            int volume = EscalatorSpeedManager.getZhajiToneVolume(level, which, groupKey);
            if (force) {
                EscalatorSpeedManager.setZhajiToneAudioAll(server, which, id);
                EscalatorSpeedManager.setZhajiToneVolumeAll(server, which, volume);
            } else {
                EscalatorSpeedManager.setZhajiToneAudio(level, which, id);
                EscalatorSpeedManager.setZhajiToneVolume(level, which, volume);
            }
        }
        EscalatorSpeedManager.syncZhajiToAll(server);
        StringBuilder summary = new StringBuilder();
        for (String which : whichs) {
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append(EscalatorSpeedManager.zhajiLabel(which)).append(" ")
                    .append(zhajiAudioLabel(EscalatorSpeedManager.getZhajiToneAudio(level, which)))
                    .append("（音量 ").append(EscalatorSpeedManager.getZhajiToneVolume(level, which)).append("）");
        }
        // ★ 反馈文案**不带括号**（用户点名的规范）；「本组」只在真的带了组时才说。
        String from = groupKey == EscalatorSpeedManager.ZHAJI_GROUP_NONE ? "" : "本组";
        return (force ? "已强制同步" + from + "闸机提示音：" : "已把" + from + "闸机提示音设为默认：") + summary
                + (force ? " —— 所有维度都改成这一套" : " —— 单独设置过的组保持不动");
    }

    /** 【1.55】扶梯域同步。{@code pos} = 打开界面的那条扶梯上的方块。 */
    private static String syncEscalator(MinecraftServer server, ServerLevel level,
                                        int scope, boolean force, BlockPos pos) {
        if (scope == 0) {
            // 五个值各读一次「这一条扶梯此刻生效的值」= 单独设置优先、否则维度默认
            double run = EscalatorSpeedManager.getSpeed(level, pos);
            double step = EscalatorSpeedManager.getAnimationSpeed(level, pos);
            int volume = EscalatorSpeedManager.getVolumeForScreen(level, pos);
            int helpVolume = EscalatorSpeedManager.getHelpVolume(level, pos);
            boolean help = EscalatorSpeedManager.isHelpEnabled(level, pos);
            if (force) {
                EscalatorSpeedManager.forceGlobalRunSpeed(level, run);
                EscalatorSpeedManager.forceGlobalStepSpeed(level, step);
                EscalatorSpeedManager.forceDefaultVolume(level, volume);
                EscalatorSpeedManager.forceDefaultHelpVolume(level, helpVolume);
                EscalatorSpeedManager.forceDefaultHelp(level, help);
            } else {
                EscalatorSpeedManager.setGlobalRunSpeed(level, run);
                EscalatorSpeedManager.setGlobalStepSpeed(level, step);
                EscalatorSpeedManager.setDefaultVolume(level, volume);
                EscalatorSpeedManager.setDefaultHelpVolume(level, helpVolume);
                EscalatorSpeedManager.setDefaultHelp(level, help);
            }
            // 速度走全量包，另外四项各有专用包 —— 与「拆扶梯」那条清理路径同一组
            EscalatorSpeedManager.syncToAll(server);
            EscalatorSpeedManager.syncVolumeToAll(server);
            EscalatorSpeedManager.syncHelpToAll(server);
            EscalatorSpeedManager.syncHelpVolumeToAll(server);
            String values = "速度 " + EscalatorSpeedData.format(run)
                    + " · 音量 " + volume + " · 提示音音量 " + helpVolume
                    + " · 无障碍" + (help ? "开" : "关");
            return force
                    ? "已强制同步扶梯：" + values + " —— 所有扶梯都改成这一套，单独设置过的也一起改"
                    : "已同步扶梯：" + values + " 已设为默认 —— 单独设置过的扶梯保持不动";
        }
        if (scope == SYNC_ESC_AUDIO) {
            String id = EscalatorSpeedManager.getBlockAudioId(level, pos);
            if (id == null) {
                id = EscalatorSpeedManager.getDefaultAudio(level);
            }
            if (force) {
                EscalatorSpeedManager.forceDefaultAudio(level, id);
            } else {
                EscalatorSpeedManager.setDefaultAudio(level, id);
            }
            EscalatorSpeedManager.syncAudioToAll(server);
            return force
                    ? "已强制同步扶梯声音：所有扶梯都改用 " + audioLabel(id) + "，单独绑定过的也一起改"
                    : "已同步扶梯声音：" + audioLabel(id) + " 已设为默认 —— 单独绑定过的扶梯保持不动";
        }
        if (scope == SYNC_ESC_HELP_AUDIO) {
            // 进 / 出两端各一个默认字段，都要同步
            String in = EscalatorSpeedManager.getHelpAudioForScreen(level, pos, true);
            String out = EscalatorSpeedManager.getHelpAudioForScreen(level, pos, false);
            if (force) {
                EscalatorSpeedManager.forceDefaultHelpAudio(level, in, true);
                EscalatorSpeedManager.forceDefaultHelpAudio(level, out, false);
            } else {
                EscalatorSpeedManager.setDefaultHelpAudio(level, in, true);
                EscalatorSpeedManager.setDefaultHelpAudio(level, out, false);
            }
            EscalatorSpeedManager.syncHelpAudioToAll(server);
            return force
                    ? "已强制同步扶梯提示音素材：两端都改成 进 " + audioLabel(in)
                        + " / 出 " + audioLabel(out) + "，所有扶梯都照此"
                    : "已同步扶梯提示音素材为默认：进 " + audioLabel(in)
                        + " / 出 " + audioLabel(out) + " —— 单独设置过的扶梯保持不动";
        }
        return "同步失败：未知的扶梯页 " + scope;
    }

    /** 【1.55】直梯域同步。{@code key} = 那条直梯的竖井列 key。 */
    private static String syncLift(MinecraftServer server, ServerLevel level,
                                   int scope, boolean force, long key) {
        String[] whichs;
        if (scope == 0) {
            whichs = SYNC_LIFT_WHICH;
        } else if (scope >= 1 && scope <= SYNC_LIFT_WHICH.length) {
            whichs = new String[]{SYNC_LIFT_WHICH[scope - 1]};
        } else {
            return "同步失败：未知的直梯页 " + scope;
        }
        for (String which : whichs) {
            String id = EscalatorSpeedManager.toneField(
                    EscalatorSpeedManager.getServerLiftTone(level, key), which);
            if (id == null || EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(id)) {
                // 这一项写着「跟维度默认」⇒ 它此刻生效的就是维度默认本身，原样取回来
                id = EscalatorSpeedManager.getLiftToneAudio(level, which);
            }
            if (force) {
                EscalatorSpeedManager.setDefaultLiftToneAudioAll(server, which, id);
            } else {
                EscalatorSpeedManager.setDefaultLiftToneAudio(level, which, id);
            }
        }
        EscalatorSpeedManager.syncLiftToneToAll(server);
        if (scope == 0) {
            return force
                    ? "已强制同步直梯提示音素材：所有直梯的上楼 / 下楼 / 开关门都改用这条直梯的设置"
                    : "已同步直梯提示音素材为默认：没单独设置过的直梯跟着变，单独设置过的保持不动";
        }
        String label = EscalatorSpeedManager.liftToneEnabledLabel(whichs[0]);
        return force
                ? "已强制同步直梯" + label + "素材：所有直梯都改用这条直梯的设置"
                : "已同步直梯" + label + "素材为默认：单独设置过的直梯保持不动";
    }

    /** 【1.55】屏蔽门域同步。{@code key} = 那串门的 {@code runKey}；【10-04 修 2】{@code extraKey} = 门串锚点（主页）。 */
    private static String syncPsd(MinecraftServer server, ServerLevel level,
                                  int scope, boolean force, long key, long extraKey) {
        EscalatorSpeedData data = EscalatorSpeedManager.getServerData(level);
        if (scope == 0) {
            int closeWait = EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(level, key);
            int openVolume = EscalatorSpeedManager.getDoorPsdToneVolume(level, key, "open");
            int closeVolume = EscalatorSpeedManager.getDoorPsdToneVolume(level, key, "close");
            int midiumWait = EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(level, key);
            int midiumVolume = EscalatorSpeedManager.getDoorPsdMidiumVolume(level, key);
            int arriveSeconds = EscalatorSpeedManager.getDoorPsdArriveSeconds(level, key);
            int arriveVolume = EscalatorSpeedManager.getDoorPsdArriveVolume(level, key);
            // 【10-04 修 2】「关门后等待发车」是**门串级**的：按弹窗带来的门串锚点（extraKey，
            //   没带就退回车站级键）读这一串**此刻生效**的值，然后与其它项同一套语义推下去 ——
            //   这就是用户点名的「强制同步把等待时间同步到每一个门串」。
            long runAnchor = extraKey != 0L ? extraKey : key;
            int departDelay = EscalatorSpeedManager.getPsdRunDepartDelaySecondsResolved(level, runAnchor, key);
            // 到站 / 进站的「素材 + 等待秒数」挤在同一只 API 里 ⇒ 把当前默认素材原样写回去，
            // 素材不变、只改秒数。素材值必须在开始写之前读，否则会被自己改掉。
            String midiumAudio = data.defaultPsdMidiumAudio;
            String arriveAudio = data.defaultPsdArriveAudio;
            if (force) {
                EscalatorSpeedManager.setDefaultPsdCloseWaitSecondsAll(server, closeWait);
                EscalatorSpeedManager.setDefaultPsdToneVolumeAll(server, "open", openVolume);
                EscalatorSpeedManager.setDefaultPsdToneVolumeAll(server, "close", closeVolume);
                EscalatorSpeedManager.setDefaultPsdMidiumAll(server, midiumAudio, midiumWait);
                EscalatorSpeedManager.setDefaultPsdMidiumVolumeAll(server, midiumVolume);
                EscalatorSpeedManager.setDefaultPsdArriveAll(server, arriveAudio, arriveSeconds);
                EscalatorSpeedManager.setDefaultPsdArriveVolumeAll(server, arriveVolume);
                EscalatorSpeedManager.setDefaultPsdDepartDelayAll(server, departDelay);
            } else {
                EscalatorSpeedManager.setDefaultPsdCloseWaitSeconds(level, closeWait);
                EscalatorSpeedManager.setDefaultPsdToneVolume(level, "open", openVolume);
                EscalatorSpeedManager.setDefaultPsdToneVolume(level, "close", closeVolume);
                EscalatorSpeedManager.setDefaultPsdMidium(level, midiumAudio, midiumWait);
                EscalatorSpeedManager.setDefaultPsdMidiumVolume(level, midiumVolume);
                EscalatorSpeedManager.setDefaultPsdArrive(level, arriveAudio, arriveSeconds);
                EscalatorSpeedManager.setDefaultPsdArriveVolume(level, arriveVolume);
                EscalatorSpeedManager.setDefaultPsdDepartDelay(level, departDelay);
            }
            EscalatorSpeedManager.syncPsdChimeToAll(server);
            EscalatorSpeedManager.syncPsdToneToAll(server);
            String values = "关门等待 " + closeWait + "s · 开门音量 " + openVolume
                    + " · 关门音量 " + closeVolume + " · 到站等待 " + midiumWait
                    + "s · 到站音量 " + midiumVolume + " · 进站提前 " + arriveSeconds
                    + "s · 进站音量 " + arriveVolume + " · 发车等待 " + departDelay + "s";
            String tail = " ● 开门等待没有默认值，未同步";
            return force
                    ? "已强制同步屏蔽门：" + values + " —— 所有门串都改成这一套" + tail
                    : "已同步屏蔽门：" + values + " 已设为默认 —— 单独设置过的门串保持不动" + tail;
        }
        if (scope >= 1 && scope <= SYNC_PSD_WHICH.length) {
            String which = SYNC_PSD_WHICH[scope - 1];
            EscalatorSpeedData.PsdToneAudio record = EscalatorSpeedManager.psdDoorRecord(level, key);
            String id = "open".equals(which) ? record.open() : record.close();
            if (id == null || EscalatorSpeedData.PSD_TONE_DEFAULT.equals(id)) {
                // 这一项写着「跟维度默认」⇒ 它此刻生效的就是维度默认本身
                id = EscalatorSpeedManager.getPsdToneAudio(level, which);
            }
            if (force) {
                EscalatorSpeedManager.setDefaultPsdToneAudioAll(server, which, id);
            } else {
                EscalatorSpeedManager.setDefaultPsdToneAudio(level, which, id);
            }
            EscalatorSpeedManager.syncPsdToneToAll(server);
            String name = "open".equals(which) ? "开门" : "关门";
            return force
                    ? "已强制同步屏蔽门" + name + "提示音：" + audioLabel(id) + " —— 所有门串都照此"
                    : "已同步屏蔽门" + name + "提示音：" + audioLabel(id)
                        + " 已设为默认 —— 单独设置过的门串保持不动";
        }
        if (scope == SYNC_PSD_MIDIUM_PAGE) {
            String id = EscalatorSpeedManager.getDoorPsdMidiumAudio(level, key);
            if (force) {
                EscalatorSpeedManager.setDefaultPsdMidiumAll(server, id, data.defaultPsdMidiumWaitSeconds);
            } else {
                EscalatorSpeedManager.setDefaultPsdMidium(level, id, data.defaultPsdMidiumWaitSeconds);
            }
            EscalatorSpeedManager.syncPsdToneToAll(server);
            return force
                    ? "已强制同步到站播报：" + audioLabel(id) + " —— 所有门串都照此"
                    : "已同步到站播报：" + audioLabel(id) + " 已设为默认 —— 单独设置过的门串保持不动";
        }
        if (scope == SYNC_PSD_ARRIVE_PAGE) {
            String id = EscalatorSpeedManager.getDoorPsdArriveAudio(level, key);
            if (force) {
                EscalatorSpeedManager.setDefaultPsdArriveAll(server, id, data.defaultPsdArriveSeconds);
            } else {
                EscalatorSpeedManager.setDefaultPsdArrive(level, id, data.defaultPsdArriveSeconds);
            }
            EscalatorSpeedManager.syncPsdToneToAll(server);
            return force
                    ? "已强制同步进站报站：" + audioLabel(id) + " —— 所有门串都照此"
                    : "已同步进站报站：" + audioLabel(id) + " 已设为默认 —— 单独设置过的门串保持不动";
        }
        if (scope == SYNC_PSD_NARRATE_PAGE) {
            // ★ 讲述人没有素材、也没有音量（text2speech 库不给音量参数）⇒ 同步的是「样式 + 秒数」。
            int mode = EscalatorSpeedManager.getDoorPsdNarrateMode(level, key);
            int seconds = EscalatorSpeedManager.getDoorPsdNarrateSeconds(level, key);
            if (force) {
                EscalatorSpeedManager.setDefaultPsdNarrateAll(server, mode, seconds);
            } else {
                EscalatorSpeedManager.setDefaultPsdNarrate(level, mode, seconds);
            }
            EscalatorSpeedManager.syncPsdChimeToAll(server);
            String values = "讲述人 " + EscalatorSpeedData.psdNarrateModeName(mode)
                    + "、到站前 " + (-seconds) + " 秒";
            return force
                    ? "已强制同步讲述人进站广播：" + values + " —— 所有门串都照此"
                    : "已同步讲述人进站广播：" + values + " 已设为默认 —— 单独设置过的门串保持不动";
        }
        if (scope == SYNC_PSD_MIDIUM_NARRATE_PAGE) {
            // 【09-30 续 3】站台广播（讲述人）：同样只有「样式 + 等待秒数」可同步。
            int mode = EscalatorSpeedManager.getDoorPsdMidiumNarrateMode(level, key);
            int seconds = EscalatorSpeedManager.getDoorPsdMidiumNarrateSeconds(level, key);
            if (force) {
                EscalatorSpeedManager.setDefaultPsdMidiumNarrateAll(server, mode, seconds);
            } else {
                EscalatorSpeedManager.setDefaultPsdMidiumNarrate(level, mode, seconds);
            }
            EscalatorSpeedManager.syncPsdChimeToAll(server);
            String values = "讲述人 " + EscalatorSpeedData.psdNarrateModeName(mode)
                    + "、开门音播完 " + seconds + " 秒后";
            return force
                    ? "已强制同步讲述人站台广播：" + values + " —— 所有门串都照此"
                    : "已同步讲述人站台广播：" + values + " 已设为默认 —— 单独设置过的门串保持不动";
        }
        return "同步失败：未知的屏蔽门页 " + scope;
    }

    /**
     * 【1.56】列车音效域同步。
     *
     * <p>★ 用户点名「功能不着急做」⇒ 这一套**还没有数据层**：5 个音量目前只是「列车音效」界面上的
     * 几个输入框，5 个按钮也还没接素材绑定。所以这里**故意一个字段都不写**，只回一条诚实的话。
     *
     * <p>★ 为什么不「假装成功」：这一段的唯一作用是**告诉玩家到底发生了什么**。
     * 「按了同步、回一句已同步」而实际什么都没变，是最难查的那种坑（哨兵说谎）。
     * 宁可明说「还没接」。
     *
     * <p>接数据层时这里按 {@link #syncPsd} 的形状补：{@code scope} 取
     * {@link #SYNC_TRAIN_RUN}~{@link #SYNC_TRAIN_DEPART} 之一（与界面五个页的页号同序同内容），
     * {@code key} = 侧线 id，每项一个「维度默认音量」，force 支走 {@code *All} 版本清掉按项覆盖。
     *
     * <p>★ 现在按 scope 分档回话，是为了**接数据层时一眼看出域有没有接错**
     * （五个 scope 的文案各不相同 ⇒ 点「列车转弯音效」页按同步，回话里就该出现「转弯」二字）。
     * 这条也被回归断言（{@code check-1.57}）——接数据层时别把这层区分删掉。
     */
    private static String syncTrain(MinecraftServer server, ServerLevel level,
                                    int scope, boolean force, long key) {
        String which = switch (scope) {
            case SYNC_TRAIN_RUN -> "列车运行音效";
            case SYNC_TRAIN_TURN -> "列车转弯音效";
            case SYNC_TRAIN_SWITCH -> "列车道岔音效";
            case SYNC_TRAIN_ARRIVE -> "列车进站音效";
            case SYNC_TRAIN_DEPART -> "列车出站音效";
            default -> "列车音效";
        };
        return which + "还没接数据层，暂时没有可同步的设置";
    }

    /**
     * 【1.53】建 `/MBM` 这棵树。抽成方法是为了能用**两个字面量**（{@code MBM} / {@code mbm}）
     * 各 build 一棵独立的新树 —— Brigadier 的 {@code register} 拒收同一个 builder 两次。
     *
     * <pre>
     * /MBM                 = /MBM help
     * /MBM help            打开帮助界面
     * /MBM music in        批量导入 MBM_Audio 里的所有 ogg
     * /MBM music delete    清空存档音频库（不动文件夹）
     * /MBM picture fold    在本机打开图片导入文件夹 MBM_Picture（【09-29】）
     * </pre>
     *
     * <p>★【1.57】用户点名**撤销** {@code /MBM train music}（列车音效界面的入口改成了
     * **石斧右键侧线铁轨**，见 {@link #isMtrRail}）。
     * 这里**故意不留** {@code train} 这个分类节点 —— 留一个空壳节点会让
     * {@code /MBM train} 看起来「存在」，也容易被人当成「界面坏了」。
     * 回归里有一条专门断言它**解析失败**（{@code expectNotParsed}），别顺手加回来。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> mbmTree(String literal) {
        return Commands.literal(literal)
                .executes(SmoothLift::mbmOpenHelp)
                .then(Commands.literal("help")
                        .executes(SmoothLift::mbmOpenHelp))
                .then(Commands.literal("music")
                        .then(Commands.literal("in")
                                .executes(SmoothLift::dtMusicImportAll))
                        .then(Commands.literal("delete")
                                .executes(SmoothLift::dtMusicDeleteAll)))
                .then(Commands.literal("picture")
                        .executes(SmoothLift::dtPictureQuery)
                        .then(Commands.literal("new")
                                .executes(SmoothLift::dtPictureNewAll))
                        // 【09-29】fold：在**本机**打开图片导入文件夹（MBM_Picture）。
                        //   ★ 它是**字面量** ⇒ Brigadier 会先试字面量再试下面的 `name` 参数，
                        //     所以 `/MBM picture fold` 一定落在这一支上（不会被当成一张叫 fold 的图）。
                        .then(Commands.literal("fold")
                                .executes(SmoothLift::dtPictureOpenFolder))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (String k : EscalatorSpeedManager
                                            .getServerPictureLibraryKeys(ctx.getSource().getLevel())) {
                                        builder.suggest(k);
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(SmoothLift::dtPictureSwitch))
                        .then(Commands.literal("delete")
                                .executes(SmoothLift::dtPictureDeleteAll)
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (String k : EscalatorSpeedManager
                                                    .getServerPictureLibraryKeys(ctx.getSource().getLevel())) {
                                                builder.suggest(k);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(SmoothLift::dtPictureDeleteOne))));
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /futispeed：全局扶梯运行速度（只作用于「全局扶梯」＝没被石斧改过、或改完仍与全局一致的）
        //   （无参数）   -> 显示当前扶梯速度（站在扶梯上就显示那一条，否则显示全局默认）
        //   X           -> 全局运行速度 = X
        //   X to Y      -> 只有当前全局运行速度正好是 X 时才改成 Y（没有速度为 X 的就不改）
        //   -f X        -> 强制游戏内所有扶梯运行速度 = X（不管有没有被改过）
        //   -f X to Y   -> 把所有运行速度为 X 的扶梯（含被改过的）改成 Y
        dispatcher.register(Commands.literal("futispeed")
            .executes(SmoothLift::futiShow)
            .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                .executes(SmoothLift::futiGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::futiFromTo))))
            .then(futiForce("-f"))
        );

        // 【1.53】/MBM：模组自己的「总入口」。
        //   （无参数）      -> 与 /MBM help 相同（打开帮助界面）
        //   help           -> 打开「预设选择」界面（三个港铁预设 + 全音量输入框）
        //   music in       -> 一键把 MBM_Audio 文件夹里**所有** .ogg 导入存档音频库（不改任何绑定/设置）
        //   music delete   -> 从存档音频库删除**所有**已导入的音频（引用它的扶梯/直梯/提示音/屏蔽门
        //                     一并退化成默认或静音；★ MBM_Audio 文件夹里的 ogg 文件原样保留，一个不删）
        //
        // ★【1.23】这两个批量指令原来叫 `/dtmusic in` / `/dtmusic delete`；【1.53】用户点名
        //   改成 `/MBM music in` / `/MBM music delete`（**旧名不保留**，是一次干净改名）。
        // ★ 同时注册小写别名 `mbm`：MC 的指令字面量是**大小写敏感**的，只写 `MBM` 时
        //   `/mbm help` 会报「未知指令」。两个名字指向同一棵树（各自 build 一份新节点）。
        dispatcher.register(mbmTree("MBM"));
        dispatcher.register(mbmTree("mbm"));

        // /jietispeed：全局扶梯阶梯速度（**永远不动运行速度**）
        //   （无参数）   -> 显示当前阶梯速度
        //   X           -> 全局阶梯速度 = X
        //   X to Y      -> 只有当前全局阶梯速度正好是 X 时才改成 Y
        //   -f X        -> 强制所有扶梯阶梯速度 = X
        //   -f X to Y   -> 把所有阶梯速度为 X 的扶梯改成 Y
        dispatcher.register(Commands.literal("jietispeed")
            .executes(SmoothLift::jietiShow)
            .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                .executes(SmoothLift::jietiGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::jietiFromTo))))
            .then(jietiForce("-f"))
        );

        // /futimusic：扶梯音频（数据模型与 futispeed 完全对称）
        //   （无参数）     -> 显示当前扶梯播放的音频名
        //   <名字>         -> 默认音频 = 名字（已单独绑过音频的扶梯不变）
        //   <X> to <Y>     -> 默认音频正好是 X 时才改成 Y（单独绑定的一律不动）
        //   -f <名字>      -> 强制游戏内所有扶梯都用这个名字的音频
        //   -f <X> to <Y>  -> 把所有音频为 X 的扶梯（含单独绑定的）改成 Y
        // 名字可以是 `default`（模组内置音频）、`off`（清除默认音频），
        // 或玩家上传的音频文件名（要带后缀，如 example.ogg）。
        dispatcher.register(Commands.literal("futimusic")
            .executes(SmoothLift::futiMusicShow)
            .then(Commands.argument("name", StringArgumentType.string())
                .suggests(SmoothLift::futiMusicNameSuggestions)
                .executes(SmoothLift::futiMusicSet)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(SmoothLift::futiMusicNameSuggestions)
                        .executes(SmoothLift::futiMusicFromTo))))
            .then(futiMusicForce("-f"))
        );

        // /futiloud：扶梯音量（数据模型与 /futispeed、/futimusic 完全对称）
        //   （无参数）     -> 显示当前扶梯音量
        //   <音量>         -> 默认音量 = 音量（单独设置过音量的扶梯不变）
        //   <X> to <Y>     -> 默认音量正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <音量>      -> 强制游戏内所有扶梯都用这个音量（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y
        // 音量范围 1~1000：100 = 原始音量，1000 = 10× 放大（>100 才谈得上"放大"）。
        dispatcher.register(Commands.literal("futiloud")
            .executes(SmoothLift::futiLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::futiLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::futiLoudFromTo))))
            .then(futiLoudForce("-f"))
        );

        // /futihelp：无障碍提示音（香港式「视障人士提升音」）开关，数据模型与 /futispeed 完全对称。
        //   （无参数）    -> 显示当前扶梯的提示音开关
        //   on | off      -> 默认开关 = on/off（单独设置过的扶梯不变）
        //   on to off     -> 默认开关正好是 on 时才改成 off（单独设置的一律不动）
        //   -f on | off   -> 强制游戏内所有扶梯 = on/off（清掉单独设置）
        //   -f on to off  -> 把所有生效开关正好是 on 的扶梯（含单独设置的）改成 off
        // 打开/关闭的是「进扶梯一端急促咔咔、出扶梯一端缓慢咔咔」这路提示音；
        // 扶梯被停掉（status=false）时本来就静音，与本开关无关。
        dispatcher.register(Commands.literal("futihelp")
            .executes(SmoothLift::futiHelpShow)
            .then(Commands.literal("on")
                .executes(context -> futiHelpGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> futiHelpFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> futiHelpGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> futiHelpFromTo(context, false, true)))))
            .then(futiHelpForce("-f"))
        );


        // /futihelploud：无障碍**提示音**音量（数据模型与 /futiloud、/futihelp 完全对称）。
        //   （无参数）     -> 显示当前扶梯的提示音音量
        //   <音量>         -> 默认音量 = 音量（单独设置过音量的扶梯不变）
        //   <X> to <Y>     -> 默认音量正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <音量>      -> 强制游戏内所有扶梯提示音都用这个音量（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y
        // 音量范围 1~1000：100 = 原始音量，1000 = 10× 放大。
        // ★ 这与 /futiloud 是两件事：/futiloud = 扶梯**运行底噪**（整条扶梯、射程 16 格）的音量，
        //   本指令 = 无障碍**提示音**（端头单块、射程 4 格）的音量。数据与指令互不影响。
        dispatcher.register(Commands.literal("futihelploud")
            .executes(SmoothLift::futiHelpLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::futiHelpLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::futiHelpLoudFromTo))))
            .then(futiHelpLoudForce("-f"))
        );

        // /futiround：扶梯**运行底噪**（整条扶梯一起响）的淡入淡出范围（单位格，默认 16）。
        //   （无参数）     -> 显示当前扶梯的底噪范围
        //   <范围>         -> 默认范围 = 范围（单独设置过的扶梯不变）
        //   <X> to <Y>     -> 默认范围正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <范围>      -> 强制游戏内所有扶梯都用这个范围（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y
        // 每一维 1~128 格。★ 这与 /futihelpround 是两件事：本指令管**整条扶梯**的运行底噪
        //   （★【10-03】双维默认 水平 10 / 垂直 5），/futihelpround 管端头**单块**的无障碍提示音
        //   （默认 水平 4 / 垂直 5）。数据与指令互不影响。
        dispatcher.register(Commands.literal("futiround")
            .executes(SmoothLift::futiRoundShow)
            .then(Commands.argument("xz", roundArg())
                .then(Commands.argument("y", roundArg())
                    .executes(SmoothLift::futiRoundGlobal)
                    .then(Commands.literal("to")
                        .then(Commands.argument("targetXz", roundArg())
                            .then(Commands.argument("targetY", roundArg())
                                .executes(SmoothLift::futiRoundFromTo))))))
            .then(futiRoundForce("-f"))
        );

        // /futihelpround：无障碍**提示音**（端头单块）的淡入淡出范围（单位格，★【10-03】双维：
        //   水平 xz 默认 4、垂直 y 默认 5）。
        //   （无参数）           -> 显示当前扶梯的提示音范围（「水平, 垂直」）
        //   <水平> <垂直>        -> 默认范围 = 该二维（单独设置过的扶梯不变）
        //   <Xz> <Y> to <新Xz> <新Y> -> 默认范围**两维都**正好是前两个数时才改成后两个数
        //   -f <水平> <垂直>     -> 强制游戏内所有扶梯提示音都用这个范围（清掉单独设置）
        //   -f <Xz> <Y> to <新Xz> <新Y> -> 把生效范围两维都正好是的扶梯（含单独设置的）改成新值
        // 每一维 1~128 格。★ 与 /futiround 是两件事（见上）。
        dispatcher.register(Commands.literal("futihelpround")
            .executes(SmoothLift::futiHelpRoundShow)
            .then(Commands.argument("xz", roundArg())
                .then(Commands.argument("y", roundArg())
                    .executes(SmoothLift::futiHelpRoundGlobal)
                    .then(Commands.literal("to")
                        .then(Commands.argument("targetXz", roundArg())
                            .then(Commands.argument("targetY", roundArg())
                                .executes(SmoothLift::futiHelpRoundFromTo))))))
            .then(futiHelpRoundForce("-f"))
        );

        // /futihelpspeed：无障碍**提示音**（端头单块）的**速率**（单位 Hz，每秒响几次，1~20）。
        //   （无参数）            -> 显示这条扶梯两头当前的速率
        //   in|out <Hz>          -> 默认速率 = Hz（单独设置过的扶梯不变）
        //   in|out <X> to <Y>    -> 默认速率正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f in|out <Hz>       -> 强制游戏内所有扶梯这一头都用该速率（清掉单独设置）
        //   -f in|out <X> to <Y> -> 把这一头速率正好是 X 的扶梯（含单独设置的）改成 Y
        // `in` = 进入扶梯（上客端，默认 10 次/秒）、`out` = 离开扶梯（落客端，默认 1 次/秒）。
        // ★ 与 /futihelp（开关）、/futihelploud（音量）、/futihelpround（范围）是四件独立的事；
        //   与 /futiround（整条扶梯的运行底噪）也是两件事。
        // 速率上限 100 是因为靠「5 个素材 × pitch」拼速率（原版把 pitch 夹在 [0.5,2.0]）。
        // ★ 命令结构：`in`/`out` 分别在「带 -f」和「不带 -f」两层下面，即
        //   /futihelpspeed in 5          （默认速率）
        //   /futihelpspeed -f in 5       （强制所有扶梯，清掉单独设置）
        //   各都带 `<X> to <Y>`。别把 in/out 和 -f 摆平级之外的地方。
        dispatcher.register(Commands.literal("futihelpspeed")
            .executes(SmoothLift::futiHelpSpeedShow)
            .then(Commands.literal("in")
                .then(Commands.argument("hz", helpSpeedArg())
                    .executes(context -> futiHelpSpeedGlobal(context, true))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", helpSpeedArg())
                            .executes(context -> futiHelpSpeedFromTo(context, true))))))
            .then(Commands.literal("out")
                .then(Commands.argument("hz", helpSpeedArg())
                    .executes(context -> futiHelpSpeedGlobal(context, false))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", helpSpeedArg())
                            .executes(context -> futiHelpSpeedFromTo(context, false))))))
            .then(futiHelpSpeedForce("-f"))
        );

        // /futihelpmusic：无障碍**提示音**播放哪一段声音（数据模型与 /futimusic 完全对称）。
        //   （无参数）            -> 显示这条扶梯两头当前用的提示音
        //   in|out <名字>         -> 默认提示音 = 名字（已单独设置过的扶梯不变）
        //   in|out <X> to <Y>     -> 默认提示音正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f in|out <名字>      -> 强制游戏内所有扶梯这一头都用这段提示音（清掉这一头的单独设置）
        //   -f in|out <X> to <Y>  -> 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y
        // 名字可以是 `default`（模组原来的提示音）、`off`（这一头不播提示音），
        // 或本分类导入过的音频文件名（可省略 .ogg 后缀；【1.28】起提示音读 MBM_Audio/futi/help
        // 子文件夹，与底噪 futi/music 分开）。
        // `in` = 进入扶梯（上客端）、`out` = 离开扶梯（落客端），两头各有一套数据。
        // ★ 与 /futihelp（开关）、/futihelploud（音量）、/futihelpround（范围）、
        //   /futihelpspeed（速率）是五件独立的事：本指令只管「用哪段声音」。
        // ★ 速率只对 `default` 生效：自定义音频按原速循环播（素材是玩家自己的，没法按 Hz 分档），
        //   音量与范围对所有选择都生效。
        // ★ 命令结构：`in`/`out` 分别在「带 -f」和「不带 -f」两层下面（与 /futihelpspeed 完全一致），即
        //   /futihelpmusic in x.ogg        （默认提示音，进扶梯那头）
        //   /futihelpmusic -f out x.ogg    （强制所有扶梯的落客端）
        //   各都带 `<X> to <Y>`。别把 in/out 摆到别的地方去。
        dispatcher.register(Commands.literal("futihelpmusic")
            .executes(SmoothLift::futiHelpMusicShow)
            .then(Commands.literal("in")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(context -> futiHelpMusicSet(context, true))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .executes(context -> futiHelpMusicFromTo(context, true))))))
            .then(Commands.literal("out")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(context -> futiHelpMusicSet(context, false))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .executes(context -> futiHelpMusicFromTo(context, false))))))
            .then(futiHelpMusicForce("-f"))
        );

        // 【1.42】/lifthelp：**直梯（Lift）**开关门提示音（liftmusic.ogg）开关。
        //   （无参数）       -> 显示当前维度的开关
        //   on | off         -> 本维度开关 = on/off
        //   on to off        -> 本维度开关正好是 on 时才改成 off
        //   -f on | off      -> 强制**所有维度** = on/off
        //   -f on to off     -> 所有维度里开关正好是 on 的改成 off
        //   up | down | door -> 【1.15】三提示音子命令（子开关 + 默认素材），见下面那一节
        // ★ 与 /futihelp 是两件事：那条管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
        //   本指令管的是**直梯**关门/开门时连播 liftmusic.ogg（关门 4 次、开门 2 次）。
        // ★ 直梯提示音只有「维度默认」一层数据，所以 -f 的含义是「对所有维度」而不是
        //   「对所有直梯」—— 见 EscalatorSpeedManager 里 1.42 那一段。
        // ★ 命令结构：`on`/`off` 在「不带 -f」和「带 -f」两层下面各有一套（与 /futihelp 完全一致）。
        //   `up`/`down`/`door` 三个子节点也是「不带 -f」和「带 -f」各一套，且都带 <名字> 音频分支。
        dispatcher.register(Commands.literal("lifthelp")
            .executes(SmoothLift::liftHelpShow)
            .then(Commands.literal("on")
                .executes(context -> liftHelpGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> liftHelpFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> liftHelpGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> liftHelpFromTo(context, false, true)))))
            .then(liftHelpForce("-f"))
            // 【1.15】提示音子命令；【1.28】原来的 door（= chime，开关门一体）拆成 open / close 两个字面量。
            //   literal 与 which 同名（up / down / open / close）。
            .then(liftToneBranch("up", "up"))
            .then(liftToneBranch("down", "down"))
            .then(liftToneBranch("open", "open"))
            .then(liftToneBranch("close", "close"))
        );

        // 【1.43】/lifthelploud：**直梯**开关门提示音的**音量**（1~1000，100 = 原始音量，
        //   1000 = 10× 放大，与扶梯那两套音量的区间完全一致）。
        //   （无参数）       -> 显示当前维度的音量
        //   <音量>           -> 本维度音量 = 音量
        //   <X> to <Y>       -> 本维度音量正好是 X 时才改成 Y
        //   -f <音量>        -> 强制**所有维度** = 音量
        //   -f <X> to <Y>    -> 所有维度里音量正好是 X 的改成 Y
        // ★ 这是**第三套**互不影响的音量：/futiloud = 扶梯运行底噪（整条、射程 16 格）、
        //   /futihelploud = 扶梯无障碍提示音（端头单块、射程 4 格）、本指令 = 直梯开关门提示音。
        //   三者各存各的，改一个不影响另外两个。
        dispatcher.register(Commands.literal("lifthelploud")
            .executes(SmoothLift::liftHelpLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::liftHelpLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::liftHelpLoudFromTo))))
            // 【1.48】四提示音各自的音量（不带 -f 的主分支）：
            //   /lifthelploud up 200           本维度上楼提示音音量 = 200
            //   /lifthelploud up 200 to 300    本维度上楼提示音音量正好是 200 时才改成 300
            //   （down = 下楼、open = 开门、close = 关门；没单独调过的项跟随共用默认）
            .then(liftToneLoudCommand("up", "up"))
            .then(liftToneLoudCommand("down", "down"))
            .then(liftToneLoudCommand("open", "open"))
            .then(liftToneLoudCommand("close", "close"))
            // 【1.48】-f 合并成**一个**节点：下面既有共用音量（<音量>），也有四项分支（up|down|open|close）。
            //   /lifthelploud -f 200        所有维度共用默认音量 = 200
            //   /lifthelploud -f up 200     所有维度上楼提示音音量 = 200
            //   /lifthelploud -f up 200 to 300
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::liftHelpLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::liftHelpLoudForceFromTo))))
                .then(liftToneLoudForceBranch("up", "up"))
                .then(liftToneLoudForceBranch("down", "down"))
                .then(liftToneLoudForceBranch("open", "open"))
                .then(liftToneLoudForceBranch("close", "close")))
        );

        // 【1.47】/lifthelpround：直梯提示音（上楼 / 下楼 / 开关门，**三项共用一份**）的
        //   淡入淡出范围（格）。首次载入模组默认 4 格。
        //   （无参数）      -> 显示当前维度的范围
        //   <水平> <垂直>    -> 本维度范围 = 该值（1~128，复用扶梯那组范围常量）
        //   <Xz> <Y> to <新Xz> <新Y> -> 本维度范围**两维都**正好是前两个数时才改成后两个
        //   -f <水平> <垂直> -> 强制**所有维度** = 该值
        //   -f <Xz> <Y> to <新Xz> <新Y> -> 所有维度里范围两维都正好是前两个数的改成后两个
        // 与 /futiround / /futihelpround（扶梯）是**互不影响**的两件事：这里是直梯那一路。
        dispatcher.register(Commands.literal("lifthelpround")
            .executes(SmoothLift::liftHelpRoundShow)
            .then(Commands.argument("xz", roundArg())
                .then(Commands.argument("y", roundArg())
                    .executes(SmoothLift::liftHelpRoundGlobal)
                    .then(Commands.literal("to")
                        .then(Commands.argument("targetXz", roundArg())
                            .then(Commands.argument("targetY", roundArg())
                                .executes(SmoothLift::liftHelpRoundFromTo))))))
            .then(liftHelpRoundForce("-f"))
        );

        // 【1.50】/pbmmusic：**屏蔽门（MTR 平台幕门 / 半高安全门）**开关门提示音。
        //   （无参数）            -> 显示当前维度的总开关 + open/close 两项子开关
        //   on | off              -> 本维度总开关 = on/off
        //   on to off             -> 本维度总开关正好是 on 时才改成 off（off to on 同理）
        //   -f on | off           -> 强制**所有维度** = on/off
        //   -f on to off          -> 所有维度里总开关正好是 on 的改成 off
        //   open  | close         -> 显示该项子开关 + 默认素材
        //   open on | close off   -> 本维度该项子开关
        //   open on to off        -> 本维度该项子开关正好是 on 时才改成 off
        //   open -f on | off      -> **所有维度**该项子开关
        //   open -f on to off     -> 所有维度里该项子开关正好是 on 的改成 off
        //   【1.15】改素材（与 /lifthelp up|down|door 同一套形状，只是「三项」变「两项」）：
        //   open  <名字>          -> 本维度该项**默认素材**
        //   open  <X> to <Y>      -> 本维度该项默认素材正好是 X 时才改成 Y
        //   open  -f <名字>       -> 强制**所有维度**该项默认素材（并清掉按扇门的单独设置）
        //   open  -f <X> to <Y>   -> 所有维度（含单独设置）里该项是 X 的改成 Y
        //   名字：default（跟内置）/ default-c（doorclose.ogg）/ default-m（mdoorclose.ogg）
        //         / none（这一项不播）/ 导入过的 .ogg 文件名。default 落到维度默认那一层时
        //         按**端别**取内置：开门 dooropen.ogg、关门 mdoorclose.ogg。
        // ★ 与 /lifthelp（直梯 liftmusic）是两件事：本指令管的是**屏蔽门**开关门时那一下。
        //   两者各存各的，改一个不影响另一个。
        // ★ 开关只有两个取值，所以用 on/off 两个字面量而不是自定义参数类型；素材名是开的字符串参数，
        //   与 /lifthelp 一样把字面量（on/off/-f）**排在字符串参数前面**（Brigadier 字面量优先，
        //   顺序即优先级，详见 liftToneBranch 上方那段说明）。这也是为什么「不播」推荐写 none 而不是 off。
        dispatcher.register(Commands.literal("pbmmusic")
            .executes(SmoothLift::pbmMusicShow)
            .then(Commands.literal("on")
                .executes(context -> pbmMusicGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> pbmMusicFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> pbmMusicGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> pbmMusicFromTo(context, false, true)))))
            .then(pbmMusicForce("-f"))
            .then(pbmMusicItemCommand("open", "open"))
            .then(pbmMusicItemCommand("close", "close"))
        );

        // 【1.50】/pbmloud：**屏蔽门**开关门提示音的音量（1~1000，100 = 原始音量，1000 = 10×）。
        //   （无参数）              -> 显示当前维度生效的共用音量（并标出两项是否跟随共用）
        //   <音量>                  -> 本维度共用音量 = 音量
        //   <X> to <Y>              -> 本维度共用音量正好是 X 时才改成 Y
        //   -f <音量>               -> 强制**所有维度** = 音量
        //   -f <X> to <Y>           -> 所有维度里共用音量正好是 X 的改成 Y
        //   open|close <音量>       -> 本维度该项自己的音量（没单独调过的项跟随共用默认）
        //   open|close <X> to <Y>   -> 本维度该项音量正好是 X 时才改成 Y
        //   -f open|close <音量>    -> 所有维度该项音量
        //   -f open|close <X> to <Y>
        // ★ 这是与 /futiloud（扶梯底噪）、/futihelploud（扶梯无障碍提示音）、/lifthelploud（直梯）
        //   **互不影响**的第 4 套音量，各存各的。
        dispatcher.register(Commands.literal("pbmloud")
            .executes(SmoothLift::pbmLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::pbmLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::pbmLoudFromTo))))
            .then(pbmLoudItemCommand("open", "open"))
            .then(pbmLoudItemCommand("close", "close"))
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::pbmLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::pbmLoudForceFromTo))))
                .then(pbmLoudItemForceBranch("open", "open"))
                .then(pbmLoudItemForceBranch("close", "close")))
        );

        // 【1.22】/pbmmusicloud：屏蔽门**开关门提示音素材**那一套音量的别名。
        //   与 /pbmloud **完全同构、同一份数据**（新增它只是给"音量"这件事一个与素材指令
        //   /pbmmusic 对称的名字）；/pbmloud 原样保留，行为一个字没改。
        dispatcher.register(Commands.literal("pbmmusicloud")
            .executes(SmoothLift::pbmLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::pbmLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::pbmLoudFromTo))))
            .then(pbmLoudItemCommand("open", "open"))
            .then(pbmLoudItemCommand("close", "close"))
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::pbmLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::pbmLoudForceFromTo))))
                .then(pbmLoudItemForceBranch("open", "open"))
                .then(pbmLoudItemForceBranch("close", "close")))
        );

        // 【1.22】/pbmmidiumloud：**到站播报**素材自己的音量（1~1000）。
        //   形状与 /pbmloud 同构，只是"共用 + 两项"变成"这一项"。
        //     （无参数）      -> 显示当前维度生效的到站播报音量
        //     <音量>          -> 本维度 = 音量
        //     <X> to <Y>      -> 本维度正好是 X 时才改成 Y
        //     -f <音量>       -> 强制**所有维度** = 音量
        //     -f <X> to <Y>   -> 所有维度里正好是 X 的改成 Y
        dispatcher.register(Commands.literal("pbmmidiumloud")
            .executes(context -> pbmItemLoudShow(context, "midium"))
            .then(Commands.argument("volume", volumeArg())
                .executes(context -> pbmItemLoudGlobal(context, "midium"))
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(context -> pbmItemLoudFromTo(context, "midium")))))
            .then(pbmItemLoudForce("-f", "midium"))
        );

        // 【1.22】/pbmarriveloud：**进站报站**素材自己的音量（1~1000）。形状同上。
        dispatcher.register(Commands.literal("pbmarriveloud")
            .executes(context -> pbmItemLoudShow(context, "arrive"))
            .then(Commands.argument("volume", volumeArg())
                .executes(context -> pbmItemLoudGlobal(context, "arrive"))
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(context -> pbmItemLoudFromTo(context, "arrive")))))
            .then(pbmItemLoudForce("-f", "arrive"))
        );

        // 【1.50 / 1.23】四条「淡入淡出范围」（格）指令 —— 形状、上下限（1~128）、反馈句式完全一致。
        //   默认都是 16 格（车站尺度，比直梯的 4 格大得多：一列车到站时整排门都要能听见）。
        //   /pbmround        = 屏蔽门开关门提示音（open / close 两项共用一份）
        //   /pbmmusicround   = 同上（用户点名的名字；与 /pbmround 读写同一份数据）
        //   /pbmmidiumround  = 到站播报
        //   /pbmarriveround  = 进站报站
        //   树由 roundCommand 一处产出 —— 想不一致都难。
        dispatcher.register(roundCommand("pbmround", RoundKind.TONE));
        dispatcher.register(roundCommand("pbmmusicround", RoundKind.TONE));
        dispatcher.register(roundCommand("pbmmidiumround", RoundKind.MIDIUM));
        dispatcher.register(roundCommand("pbmarriveround", RoundKind.ARRIVE));

        // 【1.16】/pbmclosewait：**屏蔽门关门提示音**的「强制等待时长」（秒，0~60，默认 5）。
        //
        //   它是一套**兜底**，只在「这一轮的停站时长不够放完整条关门素材」时才生效：
        //     开门音效播完 → 等 N 秒 → 播语音播报 → 门一动（嘀嘀开始）立刻掐断这段人声。
        //   停站**够长**时它被完全忽略（那条路是「整段提前播、结尾落在门上」）。
        //
        //   （无参数）      -> 显示当前维度生效的秒数 + 这条语义的一句话说明
        //   <秒>            -> 本维度 = 秒
        //   <X> to <Y>      -> 本维度正好是 X 时才改成 Y
        //   -f <秒>         -> 强制**所有维度** = 秒
        //   -f <X> to <Y>   -> 所有维度里正好是 X 的改成 Y
        // ★ 与 /pbmround 形状同构（都是「一个整数 + to + -f」），改动时两处对着看。
        dispatcher.register(Commands.literal("pbmclosewait")
            .executes(SmoothLift::pbmCloseWaitShow)
            .then(Commands.argument("seconds", closeWaitArg())
                .executes(SmoothLift::pbmCloseWaitGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", closeWaitArg())
                        .executes(SmoothLift::pbmCloseWaitFromTo))))
            .then(pbmCloseWaitForce("-f"))
        );

        // 【1.17】/pbmmidium：**到站播报**（列车到站、开门音播完后再等 Y 秒播这一段语音）。
        //   与关门提示音那套互不相干；★ 这段声音**永远不会被掐断**（车出站也照播到完）。
        //   `/pbmmidium`                 -> 显示
        //   `/pbmmidium <名字>`          -> 只改素材（保留当前秒数）
        //   `/pbmmidium <名字> <秒>`     -> 本维度（秒数 0 ~ 正无穷）
        //   `/pbmmidium -f <名字> <秒>`  -> 所有维度
        dispatcher.register(Commands.literal("pbmmidium")
            .executes(SmoothLift::pbmMidiumShow)
            .then(Commands.argument("name", midiumNameArg())
                .suggests(SmoothLift::pbmMidiumNameSuggestions)
                .executes(SmoothLift::pbmMidiumSetNameOnly)
                .then(Commands.argument("seconds", midiumWaitArg())
                    .executes(SmoothLift::pbmMidiumGlobal)))
            .then(pbmMidiumForce("-f"))
        );

        // 【1.21】/pbmarrive：**进站报站**（时刻表里最近的一班车还剩 |X| 秒到站时播这一段语音）。
        //   与到站播报互不相干；★ 这段声音同样**永远不会被掐断**（车进站后也照播到完）。
        //   `/pbmarrive`                 -> 显示
        //   `/pbmarrive <名字>`          -> 只改素材（保留当前秒数）
        //   `/pbmarrive <名字> <X>`      -> 本维度（X ∈ (-∞, 0]）
        //   `/pbmarrive -f <名字> <X>`   -> 所有维度
        dispatcher.register(Commands.literal("pbmarrive")
            .executes(SmoothLift::pbmArriveShow)
            .then(Commands.argument("name", arriveNameArg())
                .suggests(SmoothLift::pbmArriveNameSuggestions)
                .executes(SmoothLift::pbmArriveSetNameOnly)
                .then(Commands.argument("seconds", arriveArg())
                    .executes(SmoothLift::pbmArriveGlobal)))
            .then(pbmArriveForce("-f"))
        );

        // 【09-28 续 2】/pbmnarrate：**进站广播（讲述人）的样式**（关闭 / 开启(上海) / 开启(香港)）。
        //   讲述人的句子是运行时现拼的 —— 没有音频素材、也没有音量，所以只有**样式**这一格。
        //   `/pbmnarrate`            -> 显示本维度生效的样式与秒数
        //   `/pbmnarrate <样式>`      -> 本维度（off / shanghai / hongkong）
        //   `/pbmnarrate -f <样式>`   -> 所有维度 + 抹掉按串覆盖（与其它 -f 同口径）
        //   ★ 秒数（到站前 N 秒）**不在本指令里**（在石斧 UI 那一行填），本指令一律原样保留秒数。
        //   ★ 两层别混：本指令 = 「念哪一句」；/jsr = 「念不念」的全局总闸（客户端配置）。
        //   ★ 样式用**字面量**分支（不用字符串参数）⇒ 打错样式时 Brigadier 当场拒绝、
        //     补全里也能看到三个候选（与 /futihelp on|off 同一口味）。
        //   ★ 这条指令的意义之一：让「港铁预设」能把它写成**一条普通指令**
        //     （预设 = 一串指令，见 PRESET_CLASSIC_MTR / PRESET_SIMPLE_MTR）。
        dispatcher.register(Commands.literal("pbmnarrate")
            .executes(SmoothLift::pbmNarrateShow)
            .then(pbmNarrateStyle("off", EscalatorSpeedData.PSD_NARRATE_OFF))
            .then(pbmNarrateStyle("shanghai", EscalatorSpeedData.PSD_NARRATE_SHANGHAI))
            .then(pbmNarrateStyle("hongkong", EscalatorSpeedData.PSD_NARRATE_HONGKONG))
            .then(pbmNarrateForce("-f"))
        );

        // 【09-30】/zhaji：**闸机（MTR Ticket Barrier）**的进站 / 出站提示音。
        //   （无参数）            -> 显示当前维度进 / 出站两侧的素材
        //   in | out              -> 显示这一侧
        //   in|out <名字>         -> 本维度这一侧素材 = 名字
        //   in|out <X> to <Y>     -> 本维度这一侧素材正好是 X 时才改成 Y
        //   -f in|out <名字>      -> 强制**所有维度**这一侧 = 名字
        //   -f in|out <X> to <Y>  -> 所有维度里这一侧素材正好是 X 的改成 Y
        // ★ 与前两套（直梯 / 屏蔽门）最大的不同：闸机**只有维度默认这一层**、而且只有两个方向
        //   （in = 进站闸机、out = 出站闸机，按方块注册名认，不依赖 MTR 编译期）。
        //   「不播」就直接把那一侧设成 off（名字参数写 off 即可）—— 闸机没有第二道子开关。
        // ★ 素材名字的补全按**方向**取分类：in → zhaji/in、out → zhaji/out
        //   （= MBM_Audio\zhaji\in、MBM_Audio\zhaji\out）。
        dispatcher.register(Commands.literal("zhaji")
            .executes(SmoothLift::zhajiShow)
            .then(zhajiToneBranch("in", "in"))
            .then(zhajiToneBranch("out", "out"))
            .then(Commands.literal("-f")
                .then(zhajiToneForceLeaf("in", "in"))
                .then(zhajiToneForceLeaf("out", "out")))
        );

        // 【09-30】/zhajiloud：闸机提示音的**音量**（1~1000，100 = 原始音量、1000 = 10×，
        //   与扶梯/直梯/屏蔽门那几套区间完全一致）。进 / 出站各一份。
        //   （无参数）            -> 显示当前维度进 / 出站两侧的音量
        //   in | out              -> 显示这一侧音量
        //   in|out <音量>         -> 本维度这一侧音量 = 音量
        //   in|out <X> to <Y>     -> 本维度这一侧音量正好是 X 时才改成 Y
        //   -f in|out <音量>      -> 强制**所有维度**这一侧 = 音量
        //   -f in|out <X> to <Y>  -> 所有维度里这一侧音量正好是 X 的改成 Y
        dispatcher.register(Commands.literal("zhajiloud")
            .executes(SmoothLift::zhajiLoudShow)
            .then(zhajiLoudBranch("in", "in"))
            .then(zhajiLoudBranch("out", "out"))
            .then(Commands.literal("-f")
                .then(zhajiLoudForceLeaf("in", "in"))
                .then(zhajiLoudForceLeaf("out", "out")))
        );
    }

    // ==================================================================
    // 【09-30】闸机（MTR Ticket Barrier）提示音指令 /zhaji、/zhajiloud
    //
    //   ★ 与前两套（/lifthelp、/pbmmusic 系列）的关系：形状照抄，域数最少 ——
    //     只有两个方向（in = 进站闸机、out = 出站闸机），而且都只有「维度默认」一层
    //     （没有按方块粒度、没有子开关）。所以这里没有 key、没有 on/off 开关分支。
    //
    //   ★ 「不播」怎么表达：直接把那一侧素材设成 off ——
    //     `/zhaji in off`（名字参数补全里也给了 none）。这就是闸机侧「关掉」的唯一写法。
    // ==================================================================

    /** 闸机素材的显示名（命令回执用；不播显示成「不播」而不是内部值 off）。 */
    private static String zhajiAudioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        if (EscalatorSpeedData.ZHAJI_TONE_DEFAULT.equals(audioId)) {
            return "default（内置）";
        }
        if (EscalatorSpeedData.ZHAJI_TONE_OFF.equals(audioId)) {
            return "不播";
        }
        return "「" + audioId + "」";
    }

    /** 闸机素材名参数的 Tab 补全（分类按方向取：in → zhaji/in、out → zhaji/out）。 */
    private static CompletableFuture<Suggestions> zhajiNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, String which) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        String category = EscalatorSpeedManager.zhajiToneCategory(which);
        for (String candidate : EscalatorSpeedManager.liftToneNameCandidates(source.getLevel(), category)) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    /** `/zhaji in|out`：本维度这一侧（不带参数 = 显示不修改）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> zhajiToneBranch(String literal, String which) {
        return Commands.literal(literal)
                .executes(context -> zhajiToneShow(context, which))
                // ★ 顺序即优先级：字符串参数放最后（与 /lifthelp 的说明一致）。
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, b) -> zhajiNameSuggestions(ctx, b, which))
                        .executes(context -> zhajiToneAudioSet(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests((ctx, b) -> zhajiNameSuggestions(ctx, b, which))
                                        .executes(context -> zhajiToneAudioFromTo(context, which)))));
    }

    /** `/zhaji -f in|out <名字>` 与 `-f in|out <X> to <Y>`（所有维度）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> zhajiToneForceLeaf(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((ctx, b) -> zhajiNameSuggestions(ctx, b, which))
                        .executes(context -> zhajiToneAudioForceSet(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests((ctx, b) -> zhajiNameSuggestions(ctx, b, which))
                                        .executes(context -> zhajiToneAudioForceFromTo(context, which)))));
    }

    /** `/zhajiloud in|out <音量>` 与 `in|out <X> to <Y>`（本维度）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> zhajiLoudBranch(String literal, String which) {
        return Commands.literal(literal)
                .executes(context -> zhajiLoudShow(context, which))
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> zhajiLoudGlobal(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> zhajiLoudFromTo(context, which)))));
    }

    /** `/zhajiloud -f in|out <音量>` 与 `-f in|out <X> to <Y>`（所有维度）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> zhajiLoudForceLeaf(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> zhajiLoudForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> zhajiLoudForceFromTo(context, which)))));
    }

    /** `/zhaji`（不带参数）—— 显示当前维度进 / 出站两侧的素材。 */
    private static int zhajiShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal(zhajiAudioLabel(EscalatorSpeedManager.getZhajiToneAudio(level, "in")) + ", " + zhajiAudioLabel(EscalatorSpeedManager.getZhajiToneAudio(level, "out")) + ", " + EscalatorSpeedManager.getZhajiToneVolume(level, "in") + ", " + EscalatorSpeedManager.getZhajiToneVolume(level, "out")), false);
        return 1;
    }

    /** `/zhaji in|out`（不带参数）—— 显示当前维度这一侧。 */
    private static int zhajiToneShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal(zhajiAudioLabel(EscalatorSpeedManager.getZhajiToneAudio(level, which)) + ", " + EscalatorSpeedManager.getZhajiToneVolume(level, which)), false);
        return 1;
    }

    /** `/zhaji in|out <名字>` —— 设置本维度这一侧的素材。 */
    private static int zhajiToneAudioSet(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveZhajiToneName(level, which, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        if (!EscalatorSpeedManager.setZhajiToneAudio(level, which, arg.id())) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/zhaji in|out <X> to <Y>` —— 本维度这一侧素材正好是 X 时才改成 Y。 */
    private static int zhajiToneAudioFromTo(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveZhajiToneName(level, which, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveZhajiToneName(level, which, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        if (!EscalatorSpeedManager.replaceZhajiToneAudio(level, which, from.id(), to.id())) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/zhaji -f in|out <名字>` —— **所有维度**这一侧都设成它。 */
    private static int zhajiToneAudioForceSet(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveZhajiToneName(level, which, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.setZhajiToneAudioAll(source.getServer(), which, arg.id());
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/zhaji -f in|out <X> to <Y>` —— 所有维度里这一侧素材正好是 X 的改成 Y。 */
    private static int zhajiToneAudioForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveZhajiToneName(level, which, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveZhajiToneName(level, which, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal("指令执行失败"));
            return 0;
        }
        int changed = EscalatorSpeedManager.replaceZhajiToneAudioAll(
                source.getServer(), which, from.id(), to.id());
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/zhajiloud`（不带参数）—— 显示当前维度进 / 出站两侧的音量。 */
    private static int zhajiLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal(EscalatorSpeedManager.getZhajiToneVolume(level, "in") + ", " + EscalatorSpeedManager.getZhajiToneVolume(level, "out")), false);
        return 1;
    }

    /** `/zhajiloud in|out`（不带参数）—— 显示当前维度这一侧的音量。 */
    private static int zhajiLoudShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int volume = EscalatorSpeedManager.getZhajiToneVolume(level, which);
        source.sendSuccess(() -> Component.literal("" + volume), false);
        return 1;
    }

    /** `/zhajiloud in|out <音量>` —— 设置本维度这一侧的音量。 */
    private static int zhajiLoudGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setZhajiToneVolume(level, which, volume);
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        int applied = EscalatorSpeedManager.getZhajiToneVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/zhajiloud in|out <X> to <Y>` —— 本维度这一侧音量正好是 X 时才改成 Y。 */
    private static int zhajiLoudFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getZhajiToneVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceZhajiToneVolume(level, which, from, to);
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        int applied = EscalatorSpeedManager.getZhajiToneVolume(level, which);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/zhajiloud -f in|out <音量>` —— **所有维度**这一侧都设成该音量。 */
    private static int zhajiLoudForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setZhajiToneVolumeAll(source.getServer(), which, volume);
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        int applied = EscalatorSpeedData.clampZhajiVolume(volume);
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }

    /** `/zhajiloud -f in|out <X> to <Y>` —— 所有维度里这一侧音量正好是 X 的改成 Y。 */
    private static int zhajiLoudForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceZhajiToneVolumeAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncZhajiToAll(source.getServer());
        int applied = EscalatorSpeedData.clampZhajiVolume(to);
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal("指令执行失败"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("指令执行成功"), false);
        return 1;
    }


    // ==================================================================
    // 【Forge 移植】模组生命周期与服务端事件(对应 Fabric onInitialize 里的
    // CommandRegistrationCallback / UseBlockCallback / ServerTickEvents /
    // PlayerBlockBreakEvents / ServerPlayConnectionEvents.JOIN / ServerLifecycleEvents,
    // 以及全部 ServerPlayNetworking 接收器 —— 接收器一律移到 smooth.lift.network 包类里)。
    // ==================================================================

    public SmoothLift() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        // 【1.18.1204】地图图片方块(shsubwaypicture 命名空间,12 个方块 + 物品组):
        //   Forge 的注册时机是 mod event bus 上的 RegisterEvent,见 PictureBlocks.register(IEventBus)。
        PictureBlocks.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(Packets::register);
    }

    /** 命令注册:对应 Fabric 的 CommandRegistrationCallback(EVENT.register)。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    /**
     * 服务端兜底:石斧右键扶梯 / 屏蔽门 / 直梯楼层轨道 / 闸机时取消原版交互
     * (正常情况下客户端已拦截,不会发包);右键扶梯方块还要记链补扫(【1.30】)。
     */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        // 【1.30】右键 = 可能在放 / 延长扶梯。这里只记下点了哪一格,
        // 下一个服务端刻(见 onServerTick)再去看实际落了哪些方块、把整条链的单独设置补齐。
        if (isEscalatorItem(event.getItemStack())
                || EscalatorUtil.isEscalator(event.getLevel().getBlockState(event.getPos()))) {
            EscalatorSpeedManager.scheduleChainReconcile(event.getLevel(), event.getPos());
        }
        if (event.getEntity().getMainHandItem().is(Items.STONE_AXE)) {
            BlockState state = event.getLevel().getBlockState(event.getPos());
            // 四类方块 = 客户端会开设置界面的目标:扶梯 / 屏蔽门 / 直梯楼层轨道 / 闸机。
            if (EscalatorUtil.isEscalator(state) || isPsdDoor(state) || isLiftTrackFloor(state)
                    || isZhajiBarrier(state)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
        }
    }

    /** 【1.30】服务端刻收尾:处理上一刻记下的「刚放了扶梯方块」的位置。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EscalatorSpeedManager.tickPendingReconcile(event.getServer());
    }

    /** 扶梯方块被破坏时清除对应记录(对应 Fabric 的 PlayerBlockBreakEvents.AFTER)。 */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!EscalatorUtil.isEscalator(event.getState())) {
            return;
        }
        // 有记录被清除才广播,避免拆没有配置过的扶梯也重发全量同步包
        if (!EscalatorSpeedManager.removeSpeed(level, event.getPos())) {
            return;
        }
        // 速度 / 音频绑定 / 底噪音量 / 提示音开关 / 提示音音量 / 两个可闻范围 / 提示音速率
        // 八类数据都要同步,否则客户端会残留旧的速度与声音
        EscalatorSpeedManager.syncToAll(level.getServer());
        EscalatorSpeedManager.syncAudioToAll(level.getServer());
        EscalatorSpeedManager.syncVolumeToAll(level.getServer());
        EscalatorSpeedManager.syncHelpToAll(level.getServer());
        EscalatorSpeedManager.syncHelpVolumeToAll(level.getServer());
        EscalatorSpeedManager.syncRoundToAll(level.getServer());
        EscalatorSpeedManager.syncHelpRoundToAll(level.getServer());
        EscalatorSpeedManager.syncHelpSpeedToAll(level.getServer());
    }

    /** 玩家进入游戏时同步全部数据(对应 Fabric 的 ServerPlayConnectionEvents.JOIN)。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 【10-01】换世界/重进时清掉上一次界面会话残留的结果
            UI_SESSION_OK.remove(player.getUUID());
            // 【10-05】顺带清掉上一次残留的「最近门串」待办
            PENDING_NEAREST.remove(player.getUUID());
            EscalatorSpeedManager.syncToAll(player.getServer());
            EscalatorSpeedManager.syncAudioToAll(player.getServer());
            EscalatorSpeedManager.syncVolumeToAll(player.getServer());
            EscalatorSpeedManager.syncHelpToAll(player.getServer());
            EscalatorSpeedManager.syncHelpVolumeToAll(player.getServer());
            EscalatorSpeedManager.syncRoundToAll(player.getServer());
            EscalatorSpeedManager.syncHelpRoundToAll(player.getServer());
            EscalatorSpeedManager.syncHelpSpeedToAll(player.getServer());
            EscalatorSpeedManager.syncHelpAudioToAll(player.getServer());
            // 【1.42】直梯开关门提示音(开关 + 倍速)
            EscalatorSpeedManager.syncLiftChimeToAll(player.getServer());
            // 【1.45】直梯楼层轨道提示音(石斧右键设置的三列表)
            EscalatorSpeedManager.syncLiftToneToAll(player.getServer());
            // 【1.50】屏蔽门开关门提示音(开关 + 每扇门单独素材)
            EscalatorSpeedManager.syncPsdChimeToAll(player.getServer());
            EscalatorSpeedManager.syncPsdToneToAll(player.getServer());
            // 【09-30】闸机提示音(进站 / 出站各一份素材 + 音量)
            EscalatorSpeedManager.syncZhajiToAll(player.getServer());
            // 【1.18.1204】地图图片库:进世界时若不推送,客户端图片库为空,重进存档就要重新导入
            EscalatorSpeedManager.syncPictureToAll(player.getServer());
        }
    }

    /** 【10-05】玩家登出时清掉「最近门串」待办队列（免得 Map 越攒越大）。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING_NEAREST.remove(player.getUUID());
        }
    }

    /** 服务端启动时确保存档来源文件夹存在(音频 MBM_Audio;【1.18.1204】地图图片 MBM_Picture)。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        EscalatorSpeedManager.ensureAudioFolder(event.getServer().overworld());
        EscalatorSpeedManager.ensurePictureFolder(event.getServer().overworld());
        // 【10-03 五改】把服务端实例交给「关门后等待 X 秒发车」那一条：
        //   MTR 的列车模拟要按 runKey 查本模组的存档设置，而它可能跑在 MTR 自己的线程上
        //   ⇒ 那边**不在**注入体里碰任何存档 API，只从这里拿一个实例、每站查一次。
        PsdDepartHold.setServer(event.getServer());
    }

    /** 【10-03 五改】服务端停了就清掉 —— 免得重进另一个存档时拿旧世界的实例去查设置。 */
    @SubscribeEvent
    public void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        PsdDepartHold.setServer(null);
    }

    /** 【10-01 / Forge】取走并清掉该玩家本次界面会话的结果(UiClosePacket 用)。 */
    public static Boolean consumeUiSessionOk(ServerPlayer player) {
        return UI_SESSION_OK.remove(player.getUUID());
    }

}

