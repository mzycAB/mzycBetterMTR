package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;
import java.util.function.Supplier;

/**
 * 【1.53】客户端 -> 服务端：从 MBM_Audio 文件夹导入 OGG 并设为某一扇屏蔽门某项提示音。buf: key(long)→which(utf32)→fileName(utf128)
 */
public class ImportFolderPsdTonePacket {
    private final long key;
    private final String which;
    private final String fileName;

    public ImportFolderPsdTonePacket(long key, String which, String fileName) {
        this.key = key;
        this.which = which;
        this.fileName = fileName;
    }

    public static void encode(ImportFolderPsdTonePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.which, 32);
        buf.writeUtf(pkt.fileName, 128);
    }

    public static ImportFolderPsdTonePacket decode(FriendlyByteBuf buf) {
        return new ImportFolderPsdTonePacket(buf.readLong(), buf.readUtf(32), buf.readUtf(128));
    }

    public static void handle(ImportFolderPsdTonePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            String problem = EscalatorSpeedManager.importAudioToStore(
                    level, "open".equals(pkt.which) ? EscalatorSpeedManager.CAT_PSD_OPEN : EscalatorSpeedManager.CAT_PSD_CLOSE,
                    pkt.fileName);
            if (problem == null) {
                if (EscalatorSpeedManager.setServerPsdTone(level, pkt.key, pkt.which, pkt.fileName)) {
                    player.displayClientMessage(Component.literal(
                            "已从文件夹导入并设为这一扇屏蔽门的"
                                    + EscalatorSpeedData.psdToneLabel(pkt.which)), true);
                    EscalatorSpeedManager.syncAudioToAll(player.server);
                    EscalatorSpeedManager.syncPsdToneToAll(player.server);
                } else {
                    player.displayClientMessage(Component.literal(
                            "设置失败：导入成功但不认识这个音频"), true);
                }
            } else {
                player.displayClientMessage(Component.literal("导入失败：" + problem), true);
            }

        });
        context.setPacketHandled(true);
    }
}
