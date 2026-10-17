package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【1.41】客户端 -> 服务端：把存档 {@code smoothlift_audio} 文件夹里的一个 OGG 导入存档
 * 并设为这条扶梯**某一头**的无障碍提示音音乐。
 *
 * <p>与运行底噪共用同一个文件夹与同一个库：导入一次，两边都能选。
 * 导入后音频融入存档，删掉原文件仍可播放。
 *
 * <p>buf 顺序：{@code pos, fileName, in}。
 */
public class ImportFolderHelpAudioPacket {
    private final BlockPos pos;
    private final String fileName;
    private final boolean in;

    public ImportFolderHelpAudioPacket(BlockPos pos, String fileName, boolean in) {
        this.pos = pos;
        this.fileName = fileName;
        this.in = in;
    }

    public static void encode(ImportFolderHelpAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeUtf(pkt.fileName, 128);
        buf.writeBoolean(pkt.in);
    }

    public static ImportFolderHelpAudioPacket decode(FriendlyByteBuf buf) {
        return new ImportFolderHelpAudioPacket(buf.readBlockPos(), buf.readUtf(128), buf.readBoolean());
    }

    public static void handle(ImportFolderHelpAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!EscalatorUtil.isEscalator(level.getBlockState(pkt.pos))) {
                return;
            }
            String problem = EscalatorSpeedManager.importAudioToStore(level, EscalatorSpeedManager.CAT_HELP, pkt.fileName);
            if (problem == null) {
                EscalatorSpeedManager.bindHelpAudio(level, pkt.pos, pkt.fileName, pkt.in);
                player.displayClientMessage(Component.literal(
                        "已从文件夹导入并设为这条扶梯" + SmoothLift.helpEndLabel(pkt.in)
                                + "的无障碍提示音（原文件删除后仍可播放）"), true);
                // 音频库变了（底噪那边也要看到这段新音频）⇒ 两个包一起发。
                EscalatorSpeedManager.syncAudioToAll(player.server);
                EscalatorSpeedManager.syncHelpAudioToAll(player.server);
            } else {
                player.displayClientMessage(Component.literal("导入失败：" + problem), true);
            }
        });
        context.setPacketHandled(true);
    }
}
