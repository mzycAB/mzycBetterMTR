package smooth.lift.client;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.SmoothLift;
import smooth.lift.network.Packets;
import smooth.lift.network.RequestSyncPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import smooth.lift.client.PictureTextures;
import smooth.lift.client.PsdChimePlayer;
import smooth.lift.client.PsdToneSetupScreen;
import smooth.lift.client.TrainSoundScreen;
import smooth.lift.client.MtrSidingAccess;
import smooth.lift.client.ZhajiChimePlayer;
import smooth.lift.client.ZhajiToneSetupScreen;
import smooth.lift.client.TrainAnnounceNarrator;
import smooth.lift.client.TrainAnnounceSubtitle;
import smooth.lift.client.TrainAnnounceSwitch;
import smooth.lift.network.PictureSyncPacket;
import smooth.lift.network.UiClosePacket;

@Mod.EventBusSubscriber(modid = SmoothLift.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SmoothLiftClientEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 【1.7】服务端音频同步分块拼接缓冲：维度ID → (块索引 → 数据)。 */
    private static final Map<String, Map<Integer, byte[]>> PENDING_SYNC_CHUNKS = new HashMap<>();

    /** 【1.18.1204 / 1.29 移植】服务端地图图片同步分块拼接缓冲（与音频分块同模式，独立 key 避免串批）。 */
    private static final Map<String, Map<Integer, byte[]>> PICTURE_SYNC_CHUNKS = new HashMap<>();

    /**
     * 【10-01 / 1.29 移植】退出设置界面：把「本次界面会话有没有失败」告诉服务端。
     * 服务端收到后回一条「UI执行成功 / UI执行失败」（它自己的失败记录与这里的 ok 取「与」）。
     */
    public static void sendUiClose(boolean ok) {
        Packets.CHANNEL.sendToServer(new UiClosePacket(ok));
    }

    /**
     * 拿着石斧右键扶梯 -> 打开速度输入界面；
     * 【1.45】拿着石斧右键**直梯楼层轨道** -> 打开直梯提示音三列表界面（客户端拦截）。
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!level.isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!event.getEntity().getMainHandItem().is(Items.STONE_AXE)) {
            return;
        }
        if (EscalatorUtil.isEscalator(level.getBlockState(event.getPos()))) {
            Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(event.getPos()));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        // 【1.45】「是楼层轨道」按注册名判（lift_track_floor_*），不依赖 MTR 编译期。
        //   直梯没有稳定 ID，所以把右键到的这格的「竖井列 key (X, Z)」传进界面，
        //   同一条直梯的所有楼层轨道共享同一个 key。
        if (SmoothLift.isLiftTrackFloor(level.getBlockState(event.getPos()))) {
            Minecraft.getInstance().setScreen(new LiftToneSetupScreen(event.getPos()));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
        // 【1.50】拿着石斧右键**屏蔽门** -> 打开开关门提示音界面。
        //   「是屏蔽门」按注册名判（psd_door_* / apg_door_*），不依赖 MTR 编译期。
        if (event.getEntity().getMainHandItem().is(Items.STONE_AXE)
                && SmoothLift.isPsdDoor(level.getBlockState(event.getPos()))) {
            Minecraft.getInstance().setScreen(new PsdToneSetupScreen(event.getPos()));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
        // 【1.57】拿着石斧右键**侧线铁轨的轨道节点**（`mtr:rail`）-> 打开「列车音效」界面。
        //   ★ 两层判据：① 方块是 mtr:rail（同时看命名空间）；② 面向的那条轨道能认到侧线。
        //   认不到侧线就**不开界面**（让原版行为照常），避免同一条侧线两个身份分桶。
        //   ★ 必须把**右键落点**传给识别层：MTR3 那一代没有 getFacingRailAndBlockPos，
        //     「面向哪一段」是本模组照 MTR4 的算法复刻的，原点就是落点这一格；
        //     不传落点只能返回 NO_SIDING（= 右键毫无反应，正是 MTR3 上用户报的症状）。
        if (event.getEntity().getMainHandItem().is(Items.STONE_AXE)
                && SmoothLift.isMtrRail(level.getBlockState(event.getPos()))) {
            long sidingKey = MtrSidingAccess.facingSidingKey(event.getPos());
            if (sidingKey != MtrSidingAccess.NO_SIDING) {
                Minecraft.getInstance().setScreen(new TrainSoundScreen(sidingKey));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
        }
        // 【09-30 / 1.29 移植】拿着石斧右键**闸机**（进站 / 出站） -> 打开闸机提示音界面。
        //   「是哪一侧」按注册名判（ticket_barrier_entrance_1 / ticket_barrier_exit_1），
        //   与播放端 ZhajiChimePlayer 走**同一个** SmoothLift.zhajiWhichOf —— 这里只当门禁用。
        //   构造器只收这一格的坐标（用来算「这一组闸机」是谁），页面永远是一级菜单。
        if (event.getEntity().getMainHandItem().is(Items.STONE_AXE)
                && SmoothLift.zhajiWhichOf(level.getBlockState(event.getPos())) != null) {
            Minecraft.getInstance().setScreen(new ZhajiToneSetupScreen(event.getPos()));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    /** 客户端完全进世界后主动向服务端请求速度 + 音频 + 音量数据。 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Packets.CHANNEL.sendToServer(new RequestSyncPacket());
    }

    /**
     * 【1.24 / Forge 移植】注册 {@code /mtrxr} 系列指令（扶梯阶梯渲染引擎开关）。
     *
     * <p>Fabric 侧这三条指令挂在 {@code ClientCommandRegistrationCallback.EVENT} 上；
     * Forge 1.20.1 的等价物是本事件 —— 它由客户端在**打开聊天框/建立连接时**派发到主 Forge 事件总线，
     * 注册进 {@code getDispatcher()} 的指令只存在于客户端指令树里，纯客户端执行。
     *
     * <p>指令形状与 Fabric 完全一致：
     * <ul>
     *   <li>{@code /mtrxr} —— 查看当前模式 + 性能计数；</li>
     *   <li>{@code /mtrxr on} / {@code /mtrxr off} —— MTR 原版渲染 / SmoothLift 优化引擎；</li>
     *   <li>{@code /mtrxr occ on|off} —— 遮挡剔除开关（应急逃生口）。</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mtrxr")
                .executes(EscalatorRenderModeCommand::show)
                .then(Commands.literal("on")
                        .executes(ctx -> EscalatorRenderModeCommand.set(ctx, false)))
                .then(Commands.literal("off")
                        .executes(ctx -> EscalatorRenderModeCommand.set(ctx, true)))
                .then(Commands.literal("occ")
                        .then(Commands.literal("on")
                                .executes(ctx -> EscalatorRenderModeCommand.setOcclusion(ctx, true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> EscalatorRenderModeCommand.setOcclusion(ctx, false)))));

        // 【1.31.1204】/mtrqx on|off：玩家视角随列车倾斜。
        //   on  -> 视角随列车上下坡一起俯仰（坐上过坡的列车时，地平线随车体倾斜）
        //   off -> 与 MTR 原版逐位一致：视角恒水平（MTR 只搬 yaw、不搬 pitch）
        //   （无参数）-> 查看当前状态
        //   ★ 纯客户端本地行为；MTR3 / MTR4 都走这一个开关（采集端各挂各的）。
        event.getDispatcher().register(Commands.literal("mtrqx")
                .executes(TrainTiltViewCommand::show)
                .then(Commands.literal("on")
                        .executes(ctx -> TrainTiltViewCommand.set(ctx, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> TrainTiltViewCommand.set(ctx, false))));

        // 【1.28.1204 / 1.29 移植】/jsr：讲述人列车报站（文字转语音念站名）的总开关。
        //   ★★【09-30 续 4】指令树拆成两支：/jsr arrive …（进站广播）、/jsr midium …（站台广播）。
        //   两支各自有：on|off（总闸）、on <样式> [word|chat|off]、round AAA BBB（播报范围）。
        //   /jsr（不带参数）显示两支的总览。玩家在 MTR 列车上时，讲述人语音与文字一概不播。
        //   ★【Forge 移植注】与上面 /mtrxr 同一个 dispatcher —— RegisterClientCommandsEvent
        //   的 getDispatcher()（方法参数名是 event，不存在名为 dispatcher 的局部变量）。
        event.getDispatcher().register(Commands.literal("jsr")
                        .executes(TrainAnnounceSwitch::show)
                        // ---- 进站广播的讲述人（原 /jsr 的全部子指令挪到这里）----
                        .then(Commands.literal("arrive")
                                .executes(TrainAnnounceSwitch::show)
                                .then(Commands.literal("on")
                                        .executes(ctx -> TrainAnnounceSwitch.set(ctx, true))
                                        .then(Commands.argument("style", StringArgumentType.word())
                                                .suggests(TrainAnnounceSwitch::suggestStyles)
                                                .executes(ctx -> TrainAnnounceSwitch.setOn(ctx,
                                                        StringArgumentType.getString(ctx, "style"), null))
                                                .then(Commands.argument("text", StringArgumentType.word())
                                                        .suggests(TrainAnnounceSwitch::suggestTextModes)
                                                        .executes(ctx -> TrainAnnounceSwitch.setOn(ctx,
                                                                StringArgumentType.getString(ctx, "style"),
                                                                StringArgumentType.getString(ctx, "text"))))))
                                .then(Commands.literal("off")
                                        .executes(ctx -> TrainAnnounceSwitch.set(ctx, false)))
                                .then(Commands.literal("round")
                                        .then(Commands.argument("xz", IntegerArgumentType.integer(
                                                        EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                .then(Commands.argument("y", IntegerArgumentType.integer(
                                                                EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                        .executes(ctx -> TrainAnnounceSwitch.setRound(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "xz"),
                                                                IntegerArgumentType.getInteger(ctx, "y")))))))
                        // ---- 站台广播的讲述人（同构；样式只有 default 与 userN）----
                        .then(Commands.literal("midium")
                                .executes(TrainAnnounceSwitch::showMidium)
                                .then(Commands.literal("on")
                                        .executes(ctx -> TrainAnnounceSwitch.setMidium(ctx, true))
                                        .then(Commands.argument("style", StringArgumentType.word())
                                                .suggests(TrainAnnounceSwitch::suggestMidiumStyles)
                                                .executes(ctx -> TrainAnnounceSwitch.setMidiumOn(ctx,
                                                        StringArgumentType.getString(ctx, "style"), null))
                                                .then(Commands.argument("text", StringArgumentType.word())
                                                        .suggests(TrainAnnounceSwitch::suggestTextModes)
                                                        .executes(ctx -> TrainAnnounceSwitch.setMidiumOn(ctx,
                                                                StringArgumentType.getString(ctx, "style"),
                                                                StringArgumentType.getString(ctx, "text"))))))
                                .then(Commands.literal("off")
                                        .executes(ctx -> TrainAnnounceSwitch.setMidium(ctx, false)))
                                .then(Commands.literal("round")
                                        .then(Commands.argument("xz", IntegerArgumentType.integer(
                                                        EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                .then(Commands.argument("y", IntegerArgumentType.integer(
                                                                EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                        .executes(ctx -> TrainAnnounceSwitch.setMidiumRound(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "xz"),
                                                                IntegerArgumentType.getInteger(ctx, "y")))))))
                        // 【09-30 续】/jsr define userN <文字>：自定义词两条讲述人共用。
                        .then(Commands.literal("define")
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .suggests(TrainAnnounceSwitch::suggestUserSlots)
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> TrainAnnounceSwitch.defineUserText(ctx,
                                                        StringArgumentType.getString(ctx, "slot"),
                                                        StringArgumentType.getString(ctx, "text")))))));



    }

    /** 断开连接时清空客户端镜像与逐条渲染状态，避免残留上一个世界的数据。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EscalatorSpeedManager.clearClientData();
        PENDING_SYNC_CHUNKS.clear();
        // 【1.7】停掉所有扶梯声音，并清掉链缓存/解码失败记录。
        EscalatorAudioPlayer.onDisconnect();
        // 【1.15】停掉无障碍提示音并清掉定位缓存。
        EscalatorChimePlayer.onDisconnect();
        // 【1.50】停掉屏蔽门提示音 / 到站播报 / 进站报站并清掉定位缓存。
        PsdChimePlayer.onDisconnect();
        // 【09-30 / 1.29 移植】闸机提示音播放器随断线一并停掉。
        ZhajiChimePlayer.onDisconnect();
        EscalatorStepRenderer.onDisconnect();
        // 【1.18.1204 / 1.29 移植】清空图片库与分块拼接缓冲。
        PICTURE_SYNC_CHUNKS.clear();
        PictureTextures.onDisconnect();
        // 【09-29 / 1.29 移植】讲述人字幕与「越界即停」护栏一起复位。
        TrainAnnounceSubtitle.onDisconnect();
        TrainAnnounceNarrator.onDisconnect();
        // 【1.31.1204】视角随列车倾斜的基线与帧号复位（换存档后是另一辆车）。
        TrainTiltView.onDisconnect();
    }

    /** 每客户端刻尾推进动画时钟与阶梯索引（含周期重扫），并更新扶梯声音。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EscalatorStepRenderer.onClientTick(mc);
        // 【1.7】自定义扶梯声音播放器：每 tick 检查附近扶梯并调整音量与位置。
        EscalatorAudioPlayer.onClientTick(mc);
        // 【1.15】香港式无障碍提示音播放器：每 tick 找最近扶梯、按两端距离起停两路提示音。
        EscalatorChimePlayer.onClientTick(mc);
        // 【1.42】直梯（MTR Lift）开关门提示音：关门连播 4 次 liftmusic.ogg、开门连播 2 次。
        //   与上面两个播放器互不影响：那两个只认「扶梯阶梯方块」，本播放器只认 MTR 的直梯对象。
        LiftChimePlayer.onClientTick(mc);
        // 【1.50】屏蔽门（PSD / APG）开关门提示音 + 到站播报 / 进站报站。
        PsdChimePlayer.onClientTick(mc);
        // 【1.18.1204 / 1.29 移植】地图图片纹理：每 tick 轮询图集 sprite 引用，检测资源重载后重贴。
        PictureTextures.onClientTick(mc);
        // 【1.31.1204】玩家视角随列车倾斜：每 tick 认「下车」/ 打诊断心跳。
        TrainTiltView.onClientTick(mc);
    }

    /** 世界渲染到 AFTER_ENTITIES 阶段时逐条绘制阶梯面。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        EscalatorStepRenderer.onRenderLevelStage(event);
    }

    /** 客户端区块加载 -> 扫描建索引。 */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ClientLevel level)) {
            return;
        }
        EscalatorStepIndex.onChunkLoad(level, chunk);
    }

    /** 客户端区块卸载 -> 移出索引。 */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ClientLevel level)) {
            return;
        }
        EscalatorStepIndex.onChunkUnload(level, chunk);
    }

    // ------------------------------------------------------------------
    // 【1.7】服务端音频同步：分块拼装 + 解析 + 应用
    // ------------------------------------------------------------------

    /**
     * 收到一个音频同步分块（由 {@code AudioSyncPacket} 在 PLAY_TO_CLIENT 方向转发到这里）。
     *
     * <p>全部块到齐后把负载拼回一个 byte[] 并按约定顺序解析：
     * 音频库 → 来源文件夹名单 → 扶梯-音频绑定 → 默认音频；
     * 随后应用镜像、停掉旧播放实例（下一 tick 用新数据重放）、并刷新可能开着的音乐选择界面。
     */
    public static void onAudioSyncChunk(String dimId, int totalChunks, int chunkIndex, byte[] chunk) {
        if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks || chunk == null) {
            return;
        }
        Map<Integer, byte[]> chunks = PENDING_SYNC_CHUNKS.computeIfAbsent(dimId, k -> new HashMap<>());
        // ★【1.19】分块重发的自愈：一次重发 = 从 index 0 重新开始。
        //   现在「导入」会立刻触发一次整库重发（几 MB，按 256 个/块切），连着点两次导入就可能交错；
        //   没有这一句，两批分块会在同一个 dimId 下拼起来，拼出垃圾 payload（列表清空或出现乱码名）。
        if (chunkIndex == 0) {
            chunks.clear();
        }
        chunks.put(chunkIndex, chunk);
        if (chunks.size() < totalChunks) {
            return;
        }
        PENDING_SYNC_CHUNKS.remove(dimId);
        int total = 0;
        for (byte[] part : chunks.values()) {
            total += part.length;
        }
        byte[] payload = new byte[total];
        int offset = 0;
        for (int i = 0; i < totalChunks; i++) {
            byte[] part = chunks.get(i);
            if (part == null) {
                return;
            }
            System.arraycopy(part, 0, payload, offset, part.length);
            offset += part.length;
        }
        final ResourceKey<Level> dimKey;
        try {
            dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
        } catch (Exception e) {
            return;
        }
        final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        int audioCount = data.readVarInt();
        final Map<String, byte[]> audioLibrary = new HashMap<>();
        for (int i = 0; i < audioCount; i++) {
            String id = data.readUtf(128);
            byte[] bytes = data.readByteArray();
            if (bytes.length > 0 && bytes.length <= EscalatorSpeedData.MAX_AUDIO_BYTES) {
                audioLibrary.put(id, bytes);
            }
        }
        // 【1.28 / 1.29 移植】分类区：分类数 → (分类名 → 待导入数 → 名字 ×N → 已导入数 → 名字 ×N) × 分类数
        //   顺序同 EscalatorSpeedManager.buildAudioSyncPayload / ALL_CATEGORIES。
        int categoryCount = data.readVarInt();
        final Map<String, Set<String>> folderByCategory = new HashMap<>();
        final Map<String, Set<String>> audioCategoryNames = new HashMap<>();
        for (int c = 0; c < categoryCount; c++) {
            String category = data.readUtf(64);
            int folderCount = data.readVarInt();
            Set<String> folder = new HashSet<>();
            for (int i = 0; i < folderCount; i++) {
                folder.add(data.readUtf(128));
            }
            folderByCategory.put(category, folder);
            int importedCount = data.readVarInt();
            Set<String> imported = new HashSet<>();
            for (int i = 0; i < importedCount; i++) {
                imported.add(data.readUtf(128));
            }
            audioCategoryNames.put(category, imported);
        }
        int bindCount = data.readVarInt();
        final Map<BlockPos, String> blockAudio = new HashMap<>();
        for (int i = 0; i < bindCount; i++) {
            blockAudio.put(data.readBlockPos(), data.readUtf(128));
        }
        // 【1.11】默认扶梯音频（空串 = 没有默认音频）
        String defaultAudioRaw = data.readUtf(128);
        final String defaultAudio = defaultAudioRaw.isEmpty() ? null : defaultAudioRaw;

        Minecraft.getInstance().execute(() -> {
            EscalatorSpeedManager.applyClientAudioData(dimKey, audioLibrary, folderByCategory,
                    audioCategoryNames, blockAudio, defaultAudio);
            long kb = 0L;
            for (byte[] value : audioLibrary.values()) {
                kb += value.length;
            }
            // 「绑定了却没声音」时，这条日志能立刻看出客户端到底有没有拿到音频数据。
            LOGGER.info("[SmoothLift/Audio] 音频同步完成（{}）：已入库 {} 个音频（{}KB）、"
                            + "分类 {} 个、扶梯绑定 {} 处、默认音频 {}",
                    dimKey.location(), audioLibrary.size(), kb / 1024, categoryCount,
                    blockAudio.size(), defaultAudio == null ? "" : defaultAudio);
            EscalatorAudioPlayer.onAudioReloaded();
            AudioSetupScreen.notifyAudioDataChanged();
            // 【1.17 / 1.29 移植】屏蔽门界面的「到站播放音频」也列这两张表，库里增删后跟着刷新。
            PsdToneSetupScreen.notifyToneDataChanged();
            // 【1.57 / 1.29 移植】列车音效的二级页列的也是这两张表。
            TrainSoundScreen.notifyToneDataChanged();
        });
    }

    // ------------------------------------------------------------------
    // 【1.18.1204 / 1.29 移植】地图图片库：分块拼装 + 解析 + 应用
    // ------------------------------------------------------------------

    /**
     * 收到一个图片同步分块（由 {@code PictureSyncPacket} 转发到这里）。
     * 全部块到齐后把负载拼回一个 byte[] 并解析：图片库（名字 → 源图字节）→ 当前显示图片名，
     * 随后交给 {@link PictureTextures} 裁切/缩放/加灰边并写进方块图集（渲染线程执行）。
     */
    public static void onPictureSyncChunk(String dimId, int totalChunks, int chunkIndex, byte[] chunk) {
        if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks || chunk == null) {
            return;
        }
        Map<Integer, byte[]> chunks = PICTURE_SYNC_CHUNKS.computeIfAbsent(dimId, k -> new HashMap<>());
        // 与音频分块同一约定：见到 0 号块 = 一批新的重发，丢掉上一次残留。
        if (chunkIndex == 0) {
            chunks.clear();
        }
        chunks.put(chunkIndex, chunk);
        if (chunks.size() < totalChunks) {
            return;
        }
        PICTURE_SYNC_CHUNKS.remove(dimId);
        int total = 0;
        for (byte[] part : chunks.values()) {
            total += part.length;
        }
        byte[] payload = new byte[total];
        int offset = 0;
        for (int i = 0; i < totalChunks; i++) {
            byte[] part = chunks.get(i);
            if (part == null) {
                return;
            }
            System.arraycopy(part, 0, payload, offset, part.length);
            offset += part.length;
        }
        final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        int pictureCount = data.readVarInt();
        final Map<String, byte[]> pictureLibrary = new HashMap<>();
        for (int i = 0; i < pictureCount; i++) {
            String name = data.readUtf(256);
            // 单张上限与服务端一致（12MB），显式传上限避免无参 readByteArray 的 2MB 截断。
            byte[] bytes = data.readByteArray(EscalatorSpeedData.MAX_PICTURE_BYTES);
            if (bytes.length > 0) {
                pictureLibrary.put(name, bytes);
            }
        }
        final String current = data.readUtf(256);
        Minecraft.getInstance().execute(() -> {
            PictureTextures.applyData(pictureLibrary, current);
            LOGGER.info("[SmoothLift/Picture] 图片数据同步完成（{}）：库 {} 张，当前 {}",
                    dimId, pictureLibrary.size(), current.isEmpty() ? "(无)" : current);
        });
    }
}
