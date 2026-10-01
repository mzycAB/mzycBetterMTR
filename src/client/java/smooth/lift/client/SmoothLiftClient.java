package smooth.lift.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.SmoothLift;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SmoothLiftClient implements ClientModInitializer {

    /**
     * 【10-01】退出设置界面：把「本次界面会话有没有失败」告诉服务端。
     *
     * <p>界面内部的逐条操作**不再**单独往聊天框打长句；服务端收到本包后回一条
     * 「UI执行成功 / UI执行失败」（它自己的失败记录与这里的 ok 取「与」）。
     */
    public static void sendUiClose(boolean ok) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(ok);
        ClientPlayNetworking.send(SmoothLift.UI_CLOSE_CHANNEL, buf);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 【1.7】服务端音频同步分块拼接缓冲：维度ID → (块索引 → 数据)。 */
    private static final Map<String, Map<Integer, byte[]>> PENDING_SYNC_CHUNKS = new HashMap<>();

    /** 【1.18.1204】服务端地图图片同步分块拼接缓冲（与音频分块同模式，独立 key 避免串批）。 */
    private static final Map<String, Map<Integer, byte[]>> PICTURE_SYNC_CHUNKS = new HashMap<>();

    /**
     * 【1.45】判定「直梯楼层轨道」（按注册名前缀，两端共用主类的判据，避免各写一份走样）。
     * 见 {@link smooth.lift.SmoothLift#isLiftTrackFloor}。
     */
    private static boolean isLiftTrackFloor(net.minecraft.world.level.block.state.BlockState state) {
        return smooth.lift.SmoothLift.isLiftTrackFloor(state);
    }

    @Override
    public void onInitializeClient() {
        // 【1.24】先读回上次的扶梯阶梯渲染引擎模式（/mtrxr on|off），之后所有门控都读它。
        EscalatorRenderMode.load();

        // 【1.28.1204】先读回上次的「列车报站」总开关（/jsr on|off，默认开）。
        TrainAnnounceSwitch.load();

        // 【1.24】用代码注入透明标记贴图替代 18 个资源覆盖 JSON（MTR 阶梯模型烘烤前替换 #step）
        EscalatorModelOverride.register();

        // 【1.24】/mtrxr on|off：切换扶梯阶梯渲染引擎
        //   on  -> MTR 原版渲染（返回默认静止阶梯，兼容性最好）
        //   off -> SmoothLift 优化渲染引擎（逐条扶梯独立阶梯动画）
        //   （无参数）-> 查看当前模式
        // 【1.28】/mtrxr occ on|off：开关遮挡剔除（默认开）。这是应急逃生口 ——
        //   万一某张图里扶梯因「段被判成被遮挡」而整片消失，用它立刻救回来。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("mtrxr")
                        .executes(EscalatorRenderModeCommand::show)
                        .then(ClientCommandManager.literal("on")
                                .executes(ctx -> EscalatorRenderModeCommand.set(ctx, false)))
                        .then(ClientCommandManager.literal("off")
                                .executes(ctx -> EscalatorRenderModeCommand.set(ctx, true)))
                        .then(ClientCommandManager.literal("occ")
                                .then(ClientCommandManager.literal("on")
                                        .executes(ctx -> EscalatorRenderModeCommand.setOcclusion(ctx, true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(ctx -> EscalatorRenderModeCommand.setOcclusion(ctx, false))))));

        // 【1.28.1204】/jsr：讲述人列车报站（文字转语音念站名）的总开关。
        //   ★★【09-30 续 4】指令树拆成两支（用户点名「指令要分为 jsr arrive/midium」）：
        //     /jsr arrive … —— **进站广播**的讲述人（原来的 /jsr 整棵树原样挪到这里）；
        //     /jsr midium … —— **站台广播**的讲述人（同构的一棵，样式没有 default-HK / default-SH
        //       —— 站台讲述人只有 default 与 userN）。
        //   两支各自有：on|off（总闸）、on <样式> [word|chat|off]（全局样式 + 文字出现地点）、
        //   round AAA BBB（播报范围）。/jsr（不带参数）显示两支的总览。
        //   ★ 玩家在 MTR 列车上时，讲述人语音与文字一概不播（无论设置如何）。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("jsr")
                        .executes(TrainAnnounceSwitch::show)
                        // ---- 进站广播的讲述人（原 /jsr 的全部子指令挪到这里）----
                        .then(ClientCommandManager.literal("arrive")
                                .executes(TrainAnnounceSwitch::show)
                                .then(ClientCommandManager.literal("on")
                                        .executes(ctx -> TrainAnnounceSwitch.set(ctx, true))
                                        .then(ClientCommandManager.argument("style", StringArgumentType.word())
                                                .suggests(TrainAnnounceSwitch::suggestStyles)
                                                .executes(ctx -> TrainAnnounceSwitch.setOn(ctx,
                                                        StringArgumentType.getString(ctx, "style"), null))
                                                .then(ClientCommandManager.argument("text", StringArgumentType.word())
                                                        .suggests(TrainAnnounceSwitch::suggestTextModes)
                                                        .executes(ctx -> TrainAnnounceSwitch.setOn(ctx,
                                                                StringArgumentType.getString(ctx, "style"),
                                                                StringArgumentType.getString(ctx, "text"))))))
                                .then(ClientCommandManager.literal("off")
                                        .executes(ctx -> TrainAnnounceSwitch.set(ctx, false)))
                                .then(ClientCommandManager.literal("round")
                                        .then(ClientCommandManager.argument("xz", IntegerArgumentType.integer(
                                                        EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer(
                                                                EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                        .executes(ctx -> TrainAnnounceSwitch.setRound(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "xz"),
                                                                IntegerArgumentType.getInteger(ctx, "y")))))))
                        // ---- 站台广播的讲述人（同构；样式只有 default 与 userN）----
                        .then(ClientCommandManager.literal("midium")
                                .executes(TrainAnnounceSwitch::showMidium)
                                .then(ClientCommandManager.literal("on")
                                        .executes(ctx -> TrainAnnounceSwitch.setMidium(ctx, true))
                                        .then(ClientCommandManager.argument("style", StringArgumentType.word())
                                                .suggests(TrainAnnounceSwitch::suggestMidiumStyles)
                                                .executes(ctx -> TrainAnnounceSwitch.setMidiumOn(ctx,
                                                        StringArgumentType.getString(ctx, "style"), null))
                                                .then(ClientCommandManager.argument("text", StringArgumentType.word())
                                                        .suggests(TrainAnnounceSwitch::suggestTextModes)
                                                        .executes(ctx -> TrainAnnounceSwitch.setMidiumOn(ctx,
                                                                StringArgumentType.getString(ctx, "style"),
                                                                StringArgumentType.getString(ctx, "text"))))))
                                .then(ClientCommandManager.literal("off")
                                        .executes(ctx -> TrainAnnounceSwitch.setMidium(ctx, false)))
                                .then(ClientCommandManager.literal("round")
                                        .then(ClientCommandManager.argument("xz", IntegerArgumentType.integer(
                                                        EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer(
                                                                EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX))
                                                        .executes(ctx -> TrainAnnounceSwitch.setMidiumRound(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "xz"),
                                                                IntegerArgumentType.getInteger(ctx, "y")))))))
                        // 【09-30 续】/jsr define userN <文字>：自定义词两条讲述人共用。
                        .then(ClientCommandManager.literal("define")
                                .then(ClientCommandManager.argument("slot", StringArgumentType.word())
                                        .suggests(TrainAnnounceSwitch::suggestUserSlots)
                                        .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> TrainAnnounceSwitch.defineUserText(ctx,
                                                        StringArgumentType.getString(ctx, "slot"),
                                                        StringArgumentType.getString(ctx, "text"))))))));

        // 【09-29】讲述人字幕的 HUD 渲染（/jsr word on 时才有内容可画）。
        TrainAnnounceSubtitle.register();

        // 逐条扶梯独立的阶梯动画：注册区块索引 + 世界渲染回调
        EscalatorStepRenderer.register();
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorStepRenderer::onClientTick);

        // 【1.7】自定义扶梯声音播放器：每 tick 检查附近扶梯并调整音量
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorAudioPlayer::onClientTick);

        // 【1.15】香港式扶梯视障人士提示音：进扶梯一端急促咔咔、出扶梯一端缓慢咔咔（【1.23】改为敲击声）
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorChimePlayer::onClientTick);

        // 【1.42】直梯（MTR Lift）开关门提示音：关门连播 4 次 liftmusic.ogg、开门连播 2 次。
        // 与上面两个播放器互不影响：那两个只认「扶梯阶梯方块」，本播放器只认 MTR 的直梯对象。
        ClientTickEvents.END_CLIENT_TICK.register(LiftChimePlayer::onClientTick);

        // 【1.50】MTR 屏蔽门（平台幕门）开关门提示音：默认开门 → dooropen.ogg、关门 → mdoorclose.ogg
        // （【1.15】起三段内置音频，指令名分别是 default / default-c / default-m，
        //  另有 default-s =「默认（短）」（同一段素材但不播语音播报段）—— 见 PsdChimePlayer）。
        // 门值从哪里来：见 PsdDoorTracker（挂在两个 MTR 版本的屏蔽门渲染读口上，每帧每扇门回报一次）。
        // 只认「屏蔽门方块」的注册名，所以与上面三个播放器互不影响。
        ClientTickEvents.END_CLIENT_TICK.register(PsdChimePlayer::onClientTick);

        // 【1.18.1204】地图图片纹理：每 tick 轮询图集 sprite 引用，检测资源重载后重贴
        ClientTickEvents.END_CLIENT_TICK.register(PictureTextures::onClientTick);

        // 拿着石斧右键扶梯 -> 打开速度输入界面
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!EscalatorUtil.isEscalator(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(pos));
            return InteractionResult.FAIL;
        });

        // 【1.45】拿着石斧右键**直梯楼层轨道** -> 打开直梯提示音三列表界面。
        //   「是楼层轨道」按注册名判（lift_track_floor_*），不依赖 MTR 编译期。
        //   直梯没有稳定 ID，所以把右键到的这格的「竖井列 key (X, Z)」传进界面，
        //   同一条直梯的所有楼层轨道共享同一个 key。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!isLiftTrackFloor(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new LiftToneSetupScreen(pos));
            return InteractionResult.FAIL;
        });

        // 【1.50】拿着石斧右键**屏蔽门** -> 打开开关门提示音界面（与直梯那份布局一致：开关 + 两个列表 + 音量）。
        //   「是屏蔽门」按注册名判（psd_door_* / apg_door_*），不依赖 MTR 编译期；
        //   玻璃 / 上半格被排除，保证「右键哪一格都是同一扇门」（key 与播放端同一个 anchorOf）。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!SmoothLift.isPsdDoor(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new PsdToneSetupScreen(pos));
            return InteractionResult.FAIL;
        });

        // 【09-30】拿着石斧右键**闸机**（进站 / 出站） -> 打开闸机提示音界面。
        //   用户原话：「石斧右键出/入站闸机打开 ui，可以设置闸机声音，出站闸机设置出站声音，
        //   进站闸机设置进站声音」。
        //   ★【09-30 订正】用户报「右键闸机进去的是二级菜单，按 Esc 才到一级菜单，
        //   应该右键闸机直接进入一级菜单才对」⇒ 落页与「右键到哪一侧」**彻底解耦**：
        //   构造器只收**这一格的坐标**（用来算「这一组闸机」是谁），页面永远是一级菜单。
        //   ★【09-30 续】界面配的是**这一组**（= 连着的、同一功能的那些闸机），
        //   而不是整个维度的所有闸机（那是指令的射程）；「一起调整」由右上角同步按钮负责。
        //   「是哪一侧」按注册名判（ticket_barrier_entrance_1 / ticket_barrier_exit_1），
        //   与播放端 ZhajiChimePlayer 走**同一个** SmoothLift.zhajiWhichOf —— 这里只当门禁用。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (SmoothLift.zhajiWhichOf(world.getBlockState(pos)) == null) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new ZhajiToneSetupScreen(pos));
            return InteractionResult.FAIL;
        });

        // 【1.57】拿着石斧右键**侧线铁轨的轨道节点**（`mtr:rail`）-> 打开「列车音效」界面。
        //   用户原话：「石斧右键侧线铁路轨道连接处（就是黄色的那个）打开 ui 功能，
        //   如果连接处同时连接两段轨道，就打开玩家面向的那个轨道的 ui」。
        //
        //   ★ 两层判据，都要过：
        //     ① 方块是 `mtr:rail`（{@link SmoothLift#isMtrRail}）—— 那里**同时看命名空间**，
        //        只比路径 "rail" 会把原版的 minecraft:rail 一起命中；
        //     ② 面向的那条轨道 `Rail.isSiding()` 为真、且能认到它属于哪条侧线
        //        （{@link MtrSidingAccess#facingSidingKey()}，「黄色 = isSiding」「面向的那段」
        //        「一条侧线 = 一段轨道」三件事都在那里的注释里反汇编核过）。
        //
        //   ★ 认不到侧线就**不开界面**（传 PASS，让原版行为照常）。退化成「按轨道自己的 hash 认」
        //     会让同一条侧线有两个身份、静默分桶（【1.28】踩过），宁可不打开。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!SmoothLift.isMtrRail(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            long sidingKey = MtrSidingAccess.facingSidingKey();
            if (sidingKey == MtrSidingAccess.NO_SIDING) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new TrainSoundScreen(sidingKey));
            return InteractionResult.FAIL;
        });

        // 客户端完全进世界后主动向服务端请求速度数据。
        // 服务端侧的 ServerPlayConnectionEvents.JOIN 推送发生在玩家连接建立过程中
        // （早于频道握手完成），此时发的包可能被客户端丢弃，导致进游戏后速度显示为默认。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
        });

        // 断开连接时清空客户端镜像，避免残留上一个世界的速度数据
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EscalatorSpeedManager.clearClientData();
            PENDING_SYNC_CHUNKS.clear();
            PICTURE_SYNC_CHUNKS.clear();
            PictureTextures.onDisconnect();
            EscalatorAudioPlayer.onDisconnect();
            EscalatorChimePlayer.onDisconnect();
            LiftChimePlayer.onDisconnect();
            PsdChimePlayer.onDisconnect();
            EscalatorAnimationDriver.clear();
            EscalatorStepRenderer.onDisconnect();
            // 【09-29】讲述人字幕与「越界即停」护栏一起复位（换世界后位置与范围都不再可比）。
            TrainAnnounceSubtitle.onDisconnect();
            TrainAnnounceNarrator.onDisconnect();
        });

        // 接收服务端同步的全部速度+阶梯动画数据
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            int dimCount = buf.readVarInt();
            final Map<ResourceKey<Level>, SyncEntry> parsed = new HashMap<>();
            for (int i = 0; i < dimCount; i++) {
                String dimId = buf.readUtf(256);
                double defaultSpeed = buf.readDouble();
                boolean stepEnabled = buf.readBoolean();
                double stepValue = buf.readDouble();
                int speedCount = buf.readVarInt();
                Map<BlockPos, Double> speeds = new HashMap<>();
                for (int j = 0; j < speedCount; j++) {
                    speeds.put(buf.readBlockPos(), buf.readDouble());
                }
                int stepCount = buf.readVarInt();
                Map<BlockPos, Double> stepSpeeds = new HashMap<>();
                for (int j = 0; j < stepCount; j++) {
                    stepSpeeds.put(buf.readBlockPos(), buf.readDouble());
                }
                try {
                    parsed.put(EscalatorSpeedManager.parseDimensionKey(dimId),
                            new SyncEntry(defaultSpeed, speeds, stepSpeeds, stepEnabled, stepValue));
                } catch (Exception ignored) {
                }
            }
            client.execute(() -> {
                for (Map.Entry<ResourceKey<Level>, SyncEntry> entry : parsed.entrySet()) {
                    EscalatorSpeedManager.applyClientData(entry.getKey(),
                            entry.getValue().defaultSpeed, entry.getValue().speeds, entry.getValue().stepSpeeds,
                            entry.getValue().stepEnabled, entry.getValue().stepValue);
                }
            });
        });

        // 【1.7】接收服务端分块同步的音频库与扶梯-音频绑定，全部块到齐后应用
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.AUDIO_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int totalChunks = buf.readVarInt();
            int chunkIndex = buf.readVarInt();
            byte[] chunk = buf.readByteArray();
            if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
                return;
            }
            Map<Integer, byte[]> chunks = PENDING_SYNC_CHUNKS.computeIfAbsent(dimId, k -> new HashMap<>());
            // ★【1.19】一次重发 = 从 index 0 重新开始 ⇒ 见到 0 就把上一次的残留丢掉。
            //   为什么现在才需要：改版后**导入音频会立刻触发一次整库重发**（见
            //   SET_PSD_MIDIUM_CHANNEL / IMPORT_PSD_MIDIUM_AUDIO_CHANNEL 的 receiver），
            //   而包长是「几 MB 的字节 + 256 个/块」。上一次还没传完就再点一次导入，
            //   两批分块会在同一个 key 下拼起来 ⇒ 拼出的 payload 是垃圾（读序错位、格式崩），
            //   表现是「列表变成一堆乱码名字」或整表清空。TCP 保序，所以 0 号块先到，
            //   在 0 号块上清空即可把每一批重发都变回独立的一批。
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
            // 【1.28】分类区：分类数 → (分类名 → 待导入数 → 名字 ×N → 已导入数 → 名字 ×N) × 分类数
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
            client.execute(() -> {
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
                // 【1.17】屏蔽门界面的「到站播放音频」也列出这两张表（未导入 / 已导入），
                //   库里增删之后它得跟着刷新，否则刚导入的那条会一直挂在左边。
                PsdToneSetupScreen.notifyToneDataChanged();
                // 【1.57】列车音效的二级页列的也是这两张表（左列未导入 / 右列已导入）——
                //   同一个理由，库里增删之后它也得跟着刷新。
                TrainSoundScreen.notifyToneDataChanged();
            });
        });

        // 【1.18.1204】接收服务端分块同步的「地图图片库 + 当前图片名」，全部块到齐后
        //   交给 PictureTextures 裁切/缩放/加灰边并写进方块图集（渲染线程执行）。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.PICTURE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int totalChunks = buf.readVarInt();
            int chunkIndex = buf.readVarInt();
            byte[] chunk = buf.readByteArray();
            if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
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
            client.execute(() -> {
                PictureTextures.applyData(pictureLibrary, current);
                LOGGER.info("[SmoothLift/Picture] 图片数据同步完成（{}）：库 {} 张，当前 {}",
                        dimId, pictureLibrary.size(), current.isEmpty() ? "(无)" : current);
            });
        });

        // 【1.9】接收服务端同步的「扶梯方块 → 声音音量」表（小包，不含音频字节）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.VOLUME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultVolume = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> volumes = new HashMap<>();
            for (int i = 0; i < count; i++) {
                volumes.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            final int defVol = defaultVolume;
            client.execute(() -> EscalatorSpeedManager.applyClientVolumes(dimKey, volumes, defVol));
        });

        // 【1.16】接收服务端同步的「扶梯方块 → 无障碍提示音开关」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            boolean defaultHelp = buf.readBoolean();
            int count = buf.readVarInt();
            final Map<BlockPos, Boolean> help = new HashMap<>();
            for (int i = 0; i < count; i++) {
                help.put(buf.readBlockPos(), buf.readBoolean());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelp(dimKey, defaultHelp, help);
                // 「开关关不掉」时先看这条日志：没打出来 = 同步包根本没到（装错 jar / 频道没注册）；
                // 打出来了但还响 = 去看 [SmoothLift/Chime] 那两条「已静音 / 恢复播放」。
                LOGGER.info("[SmoothLift/Help] 无障碍提示音开关已同步（{}）：默认 {}、单独设置 {} 处",
                        dimKey.location(), defaultHelp ? "开" : "关", help.size());
            });
        });

        // 【1.18】接收服务端同步的「扶梯方块 → 无障碍提示音音量」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_VOLUME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultHelpVolume = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> helpVolume = new HashMap<>();
            for (int i = 0; i < count; i++) {
                helpVolume.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            final int defVolume = defaultHelpVolume;
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpVolume(dimKey, defVolume, helpVolume);
                LOGGER.info("[SmoothLift/HelpVolume] 无障碍提示音音量已同步（{}）：默认 {}、单独设置 {} 处",
                        dimKey.location(), defVolume, helpVolume.size());
            });
        });

        // 【1.24】接收服务端同步的「扶梯方块 → 运行底噪可闻范围（格）」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.ROUND_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultRound = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> round = new HashMap<>();
            for (int i = 0; i < count; i++) {
                round.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientRounds(dimKey, defaultRound, round);
                LOGGER.info("[SmoothLift/Round] 扶梯音效淡入淡出范围已同步（{}）：默认 {} 格、单独设置 {} 处",
                        dimKey.location(), defaultRound, round.size());
            });
        });

        // 【1.24】接收服务端同步的「扶梯方块 → 无障碍提示音可闻范围（格）」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_ROUND_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultHelpRound = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> helpRound = new HashMap<>();
            for (int i = 0; i < count; i++) {
                helpRound.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpRounds(dimKey, defaultHelpRound, helpRound);
                LOGGER.info("[SmoothLift/HelpRound] 无障碍提示音淡入淡出范围已同步（{}）：默认 {} 格、单独设置 {} 处",
                        dimKey.location(), defaultHelpRound, helpRound.size());
            });
        });

        // 【1.31】接收服务端同步的无障碍提示音**速率**（入口 / 出口两套 Hz，同一只包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_SPEED_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultIn = buf.readVarInt();
            int countIn = buf.readVarInt();
            final Map<BlockPos, Integer> blockIn = new HashMap<>();
            for (int i = 0; i < countIn; i++) {
                blockIn.put(buf.readBlockPos(), buf.readVarInt());
            }
            int defaultOut = buf.readVarInt();
            int countOut = buf.readVarInt();
            final Map<BlockPos, Integer> blockOut = new HashMap<>();
            for (int i = 0; i < countOut; i++) {
                blockOut.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpSpeeds(dimKey, defaultIn, blockIn, defaultOut, blockOut);
                LOGGER.info("[SmoothLift/HelpSpeed] 无障碍提示音速率已同步（{}）：默认 进入 {} 次/秒、离开 {} 次/秒，"
                                + "单独设置 {} / {} 处",
                        dimKey.location(), defaultIn, defaultOut, blockIn.size(), blockOut.size());
            });
        });

        // 【1.41】接收服务端同步的「默认提示音音乐 + 扶梯方块 → 提示音音乐」表
        //   （小包，进 / 出两套 —— 顺序同 buildHelpAudioPacket：默认in, 表in, 默认out, 表out）
        //   音频字节仍然来自 AUDIO_SYNC 那一份（同一个库），这里只发「选了哪一个」。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_AUDIO_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            String defaultIn = buf.readUtf(128);
            int countIn = buf.readVarInt();
            final Map<BlockPos, String> blockIn = new HashMap<>();
            for (int i = 0; i < countIn; i++) {
                blockIn.put(buf.readBlockPos(), buf.readUtf(128));
            }
            String defaultOut = buf.readUtf(128);
            int countOut = buf.readVarInt();
            final Map<BlockPos, String> blockOut = new HashMap<>();
            for (int i = 0; i < countOut; i++) {
                blockOut.put(buf.readBlockPos(), buf.readUtf(128));
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpAudio(dimKey, defaultIn, blockIn, defaultOut, blockOut);
                LOGGER.info("[SmoothLift/HelpAudio] 无障碍提示音已同步（{}）：默认 进入 {}、离开 {}，单独设置 {} / {} 处",
                        dimKey.location(), defaultIn, defaultOut, blockIn.size(), blockOut.size());
                HelpAudioSetupScreen.notifyDataChanged();
            });
        });

        // 【1.42】接收服务端同步的**直梯开关门提示音**设置（开关 + 倍速 + 【1.43】音量 + 【1.46】三子开关 + 【1.47】范围，按维度；最小的包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.LIFT_CHIME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            boolean enabled = buf.readBoolean();
            float speed = buf.readFloat();
            // 【1.43】音量 —— 读的顺序必须与 EscalatorSpeedManager.buildLiftChimePacket 的写序一致
            int volume = buf.readVarInt();
            // 【1.46】四提示音独立子开关（追加在音量后面，顺序与写侧一致：up → down → open → close）
            boolean upEnabled = buf.readBoolean();
            boolean downEnabled = buf.readBoolean();
            boolean openEnabled = buf.readBoolean();
            boolean closeEnabled = buf.readBoolean();
            // 【1.47】淡入淡出范围（包尾追加）
            int round = buf.readVarInt();
            // 【1.48】四项各自音量（-1 = 跟随共用默认）
            int toneVolumeUp = buf.readVarInt();
            int toneVolumeDown = buf.readVarInt();
            int toneVolumeOpen = buf.readVarInt();
            int toneVolumeClose = buf.readVarInt();
            // 【1.15】四项的维度默认素材（default / off / 音频库文件名）—— 读序同 buildLiftChimePacket
            String toneAudioUp = buf.readUtf(128);
            String toneAudioDown = buf.readUtf(128);
            String toneAudioOpen = buf.readUtf(128);
            String toneAudioClose = buf.readUtf(128);
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientLiftChime(dimKey, enabled, speed, volume,
                        upEnabled, downEnabled, openEnabled, closeEnabled, round,
                        toneVolumeUp, toneVolumeDown, toneVolumeOpen, toneVolumeClose,
                        toneAudioUp, toneAudioDown, toneAudioOpen, toneAudioClose);
                LOGGER.info("[SmoothLift/LiftChime] 直梯提示音设置已同步（{}）：{}、倍速 {}、音量 {}、"
                                + "子开关 up={} down={} open={} close={}、范围 {} 格、"
                                + "单项音量 up={} down={} open={} close={}、"
                                + "默认素材 up={} down={} open={} close={}",
                        dimKey.location(), enabled ? "开" : "关", speed, volume,
                        upEnabled, downEnabled, openEnabled, closeEnabled, round,
                        toneVolumeUp, toneVolumeDown, toneVolumeOpen, toneVolumeClose,
                        toneAudioUp, toneAudioDown, toneAudioOpen, toneAudioClose);
            });
        });

        // 【1.45】接收服务端同步的**直梯楼层轨道提示音**（竖井列 → up/down/open/close 四音频 id）。
        //   播放端（LiftChimePlayer）按「最近直梯的竖井列」查这份镜像；打开石斧界面时也要读它。
        //   ★ 顺序同 buildLiftTonePacket：dimId → 条数 → (key, up, down, open, close) × N。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.LIFT_TONE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int n = buf.readVarInt();
            final Map<Long, EscalatorSpeedData.LiftToneAudio> tones = new HashMap<>();
            for (int i = 0; i < n; i++) {
                long key = buf.readLong();
                String up = buf.readUtf(128);
                String down = buf.readUtf(128);
                String open = buf.readUtf(128);
                String close = buf.readUtf(128);
                tones.put(key, new EscalatorSpeedData.LiftToneAudio(up, down, open, close));
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientLiftTone(dimKey, tones);
                int count = tones.size();
                LOGGER.info("[SmoothLift/LiftChime] 直梯楼层轨道提示音已同步（{}）：{} 条",
                        dimKey.location(), count);
                LiftToneSetupScreen.notifyToneDataChanged();
            });
        });

        // 【1.50】接收服务端同步的**屏蔽门开关门提示音**设置（按维度；最小的包）。
        //   ★ 读序必须与 EscalatorSpeedManager.buildPsdChimePacket 的写序严格一致：
        //     dimId → 总开关 → 共用默认音量 → open子开关 → close子开关 → 范围
        //     → open单独音量 → close单独音量 → 【1.15】open默认素材 → close默认素材
        //     → 【1.16】关门提示音强制等待时长（秒）
        //     → 【1.17】到站播报素材 id → 到站播报等待秒数
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.PSD_CHIME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            boolean enabled = buf.readBoolean();
            int volume = buf.readVarInt();
            boolean openEnabled = buf.readBoolean();
            boolean closeEnabled = buf.readBoolean();
            // 【09-29】范围拆双维：水平（x、z 轴）原位第 1 格，垂直（y 轴）第 2 格
            int roundXz = buf.readVarInt();
            int roundY = buf.readVarInt();
            int toneVolumeOpen = buf.readVarInt();
            int toneVolumeClose = buf.readVarInt();
            // 【1.15】两项的维度默认素材（末尾追加，写侧同序）
            String toneAudioOpen = buf.readUtf(128);
            String toneAudioClose = buf.readUtf(128);
            // 【1.16】关门提示音的强制等待时长（秒，末尾再追加一格，写侧同序）
            int closeWaitSeconds = buf.readVarInt();
            // 【1.17】到站播报：素材 id + 等待秒数（末尾再追加两格，写侧同序）
            String midiumAudio = buf.readUtf(128);
            int midiumWaitSeconds = buf.readVarInt();
            // 【1.21】进站报站：素材 id + 秒数（末尾再追加两格，写侧同序）
            String arriveAudio = buf.readUtf(128);
            int arriveSeconds = buf.readVarInt();
            // 【1.22】到站 / 进站播报各自那一项的音量（末尾再追加两格，写侧同序）
            int midiumVolume = buf.readVarInt();
            int arriveVolume = buf.readVarInt();
            // 【1.23】【09-29】到站 / 进站播报各自的**可闻范围**（末尾再追加，写侧同序）：
            //   每项第 1 格水平（x、z 轴）原位，第 2 格垂直（y 轴）紧跟
            int midiumRoundXz = buf.readVarInt();
            int midiumRoundY = buf.readVarInt();
            int arriveRoundXz = buf.readVarInt();
            int arriveRoundY = buf.readVarInt();
            // 【09-28】「进站广播（讲述人）」维度默认：样式（0/1/2）+ 秒数（末尾再追加两格，写侧同序）
            //   ★ 续：第 1 格由 readBoolean 改成 readVarInt（三档样式）—— 格子数不变，写侧同序改。
            int narrateMode = buf.readVarInt();
            int narrateSeconds = buf.readVarInt();
            // 【09-30 续 3】「站台广播（讲述人）」维度默认：样式 + 等待秒数（末尾再追加两格，写侧同序）
            int midiumNarrateMode = buf.readVarInt();
            int midiumNarrateSeconds = buf.readVarInt();
            // 【10-01】两条讲述人广播的玩家自定义文字（末尾追加；写侧同序）→ 客户端讲述人镜像。
            //   ★ 放在 applyClientPsdChime 之外：文字列表是「当前维度的按存档词」，
            //   由 TrainAnnounceSwitch 统一持有（叙述人 / /jsr / 编辑页三处共用）。
            java.util.List<String> arriveNarrateUserTexts = EscalatorSpeedManager.readNarrateUserTexts(buf);
            java.util.List<String> midiumNarrateUserTexts = EscalatorSpeedManager.readNarrateUserTexts(buf);
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientPsdChime(dimKey, enabled, volume,
                        openEnabled, closeEnabled, roundXz, roundY,
                        toneVolumeOpen, toneVolumeClose,
                        toneAudioOpen, toneAudioClose, closeWaitSeconds,
                        midiumAudio, midiumWaitSeconds, arriveAudio, arriveSeconds,
                        midiumVolume, arriveVolume,
                        midiumRoundXz, midiumRoundY, arriveRoundXz, arriveRoundY,
                        narrateMode, narrateSeconds, midiumNarrateMode, midiumNarrateSeconds);
                LOGGER.info("[SmoothLift/PsdChime] 屏蔽门提示音设置已同步（{}）：{}、音量 {}、"
                                + "子开关 open={} close={}、范围 提示音 水平{}垂{} 格 / 到站 水平{}垂{} 格 / 进站 水平{}垂{} 格、"
                                + "单项音量 open={} close={} 到站={} 进站={}、"
                                + "默认素材 open={} close={}、关门强制等待 {} 秒、到站播报 {}（等待 {} 秒）、"
                                + "进站报站 {}（提前 {} 秒）、讲述人 {}（提前 {} 秒；全局样式 {}、文字 {}）、"
                                + "站台讲述人 {}（开门音播完 {} 秒）",
                        dimKey.location(), enabled ? "开" : "关", volume,
                        openEnabled, closeEnabled,
                        roundXz, roundY, midiumRoundXz, midiumRoundY, arriveRoundXz, arriveRoundY,
                        toneVolumeOpen, toneVolumeClose, midiumVolume, arriveVolume,
                        toneAudioOpen, toneAudioClose, closeWaitSeconds,
                        midiumAudio, midiumWaitSeconds, arriveAudio, arriveSeconds,
                        EscalatorSpeedData.psdNarrateModeName(narrateMode), narrateSeconds,
                        TrainAnnounceSwitch.styleLabel(TrainAnnounceSwitch.style()),
                        TrainAnnounceSwitch.textModeLabel(TrainAnnounceSwitch.textMode()),
                        EscalatorSpeedData.psdNarrateModeName(midiumNarrateMode), midiumNarrateSeconds);
                // 【10-01】两条讲述人广播的按存档自定义文字（镜像进客户端，供叙述/编辑/指令共用）
                TrainAnnounceSwitch.applyNarrateUserTexts(arriveNarrateUserTexts, midiumNarrateUserTexts);
            });
        });

        // 【1.50】接收服务端同步的**每扇屏蔽门单独素材**（门锚点 → open/close 两音频 id）。
        //   播放端（PsdChimePlayer）按「最近那扇门的锚点」查这份镜像；打开石斧界面时也读它。
        //   ★ 顺序同 buildPsdTonePacket：dimId → 条数 → (key, open, close) × N。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.PSD_TONE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int n = buf.readVarInt();
            final Map<Long, EscalatorSpeedData.PsdToneAudio> tones = new HashMap<>();
            for (int i = 0; i < n; i++) {
                long key = buf.readLong();
                String open = buf.readUtf(128);
                String close = buf.readUtf(128);
                // 【1.20】这一扇门的其余覆盖项 —— ★ 读序必须与 buildPsdTonePacket 的写序一致。
                //   先读进局部变量再构造：参数求值顺序虽然也保证是从左到右，但读写成对这种东西
                //   写成一串嵌套调用以后没人看得出来哪一行对哪一行。
                Boolean help = EscalatorSpeedManager.readDoorOptBool(buf);
                Boolean openEnabled = EscalatorSpeedManager.readDoorOptBool(buf);
                Boolean closeEnabled = EscalatorSpeedManager.readDoorOptBool(buf);
                Integer volume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer openVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer closeVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer openWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer closeWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                String midium = EscalatorSpeedManager.readDoorOptString(buf);
                Integer midiumWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                // 【1.21】进站报站（读序同 buildPsdTonePacket 写序）
                String arrive = EscalatorSpeedManager.readDoorOptString(buf);
                Integer arriveSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                // 【1.22】到站 / 进站各自那一项的音量（读序同 buildPsdTonePacket 写序）
                Integer midiumVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer arriveVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                // 【09-28】「进站广播（讲述人）」这一串门的覆盖项（读序同 buildPsdTonePacket 写序）
                //   ★ 续：样式那一格由 readDoorOptBool 改成 readDoorOptInt（三档 0/1/2）。
                Integer narrate = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer narrateSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                // 【09-30 续 3】「站台广播（讲述人）」这一串门的覆盖项（读序同 buildPsdTonePacket 写序）
                Integer midiumNarrate = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer midiumNarrateSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                tones.put(key, new EscalatorSpeedData.PsdToneAudio(open, close,
                        help, openEnabled, closeEnabled,
                        volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds,
                        midium, midiumWaitSeconds, midiumVolume,
                        arrive, arriveSeconds, arriveVolume,
                        narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds));
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientPsdTone(dimKey, tones);
                LOGGER.info("[SmoothLift/PsdChime] 屏蔽门单独素材已同步（{}）：{} 条",
                        dimKey.location(), tones.size());
                PsdToneSetupScreen.notifyToneDataChanged();
            });
        });

        // 【09-30】接收服务端同步的**闸机提示音**（一个维度 → 维度默认那一层 + 逐组那一层）。
        //   播放端（ZhajiChimePlayer）按音源坐标算组锚点，先查逐组表再回落默认层；界面也读它们。
        //   ★ 顺序同 buildZhajiPacket：dimId → audioIn → audioOut → volumeIn → volumeOut
        //     → 条数 N → (组锚点 long, audioIn utf128, audioOut utf128, volIn?, volOut?) × N。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.ZHAJI_TONE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            String audioIn = buf.readUtf(128);
            String audioOut = buf.readUtf(128);
            int volumeIn = buf.readVarInt();
            int volumeOut = buf.readVarInt();
            // 【09-30 续】逐组那一段。两格音量用「可选 int」编解码（缺格 = 跟维度默认）；
            //   readDoorOptInt 是屏蔽门那一套留下的**通用**可选 int 读法（同一对 writeDoorOptInt）。
            int groupCount = buf.readVarInt();
            final Map<Long, EscalatorSpeedData.ZhajiTone> tones = new HashMap<>();
            for (int i = 0; i < groupCount; i++) {
                long groupKey = buf.readLong();
                String groupIn = buf.readUtf(128);
                String groupOut = buf.readUtf(128);
                Integer groupVolumeIn = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer groupVolumeOut = EscalatorSpeedManager.readDoorOptInt(buf);
                tones.put(groupKey, new EscalatorSpeedData.ZhajiTone(groupIn, groupOut,
                        groupVolumeIn, groupVolumeOut));
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientZhaji(dimKey, audioIn, audioOut, volumeIn, volumeOut, tones);
                // ★ 闸机这一域原先**一条日志都没有**（用户 09-30 报的「LOG7 里看不到闸机设置」），
                //   现在与屏蔽门 / 直梯同一规格打一行，排查时能直接看到生效值。
                LOGGER.info("[SmoothLift/Zhaji] 闸机提示音已同步（{}）：默认 进站={} 出站={}、"
                                + "音量 进站={} 出站={}、单独设置 {} 组",
                        dimKey.location(), audioIn, audioOut, volumeIn, volumeOut, tones.size());
                ZhajiToneSetupScreen.notifyToneDataChanged();
            });
        });

        // 【1.53】服务端让我们打开「预设选择」界面（`/MBM help` / `/MBM` 的执行端在服务端，
        //   界面在客户端，所以要走这一只空包）。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.MBM_HELP_OPEN_CHANNEL,
                (client, handler, buf, responseSender) -> client.execute(
                        () -> Minecraft.getInstance().setScreen(new MbmHelpScreen())));

        // 【09-29】服务端让我们打开存档里的某个**导入来源**文件夹（`/MBM picture fold` 走这里）。
        //   载荷是**相对存档根目录**的路径（如 MBM_Picture / MBM_Audio/pbm/arrive）——
        //   客户端自己接本机的存档根，再走 FolderOpenButton 的白名单 + 建目录 + 开窗口那一条路；
        //   界面右上角那个「打开文件夹」按钮不经过这只包（它本来就在客户端算），
        //   但两边落到的是**同一个** FolderOpenButton.open。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.MBM_OPEN_FOLDER_CHANNEL,
                (client, handler, buf, responseSender) -> {
                    String relativePath = buf.readUtf(64);
                    client.execute(() -> FolderOpenButton.open(relativePath));
                });

    }

    private record SyncEntry(double defaultSpeed, Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds,
                             boolean stepEnabled, double stepValue) {
    }
}
