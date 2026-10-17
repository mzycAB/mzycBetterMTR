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

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：把存档目录下 {@code smoothlift_audio} 文件夹里的一个 OGG
 * 导入存档音频库并绑定到这条扶梯（融入存档，之后删掉原文件仍可播放）。
 */
public class ImportFolderAudioPacket {
    private final BlockPos pos;
    private final String fileName;

    public ImportFolderAudioPacket(BlockPos pos, String fileName) {
        this.pos = pos;
        this.fileName = fileName;
    }

    public static void encode(ImportFolderAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeUtf(pkt.fileName, 128);
    }

    public static ImportFolderAudioPacket decode(FriendlyByteBuf buf) {
        return new ImportFolderAudioPacket(buf.readBlockPos(), buf.readUtf(128));
    }

    public static void handle(ImportFolderAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            String problem = EscalatorSpeedManager.importAudioToStore(level, EscalatorSpeedManager.CAT_FUTI, pkt.fileName);
            if (problem == null) {
                EscalatorSpeedManager.bindAudio(level, pkt.pos, pkt.fileName);
                player.displayClientMessage(
                        Component.literal("已从文件夹导入并与这条扶梯绑定（原文件删除后仍可播放）"), true);
                EscalatorSpeedManager.syncAudioToAll(player.server);
            } else {
                player.displayClientMessage(Component.literal("导入失败：" + problem), true);
            }
        });
        context.setPacketHandled(true);
    }
}
