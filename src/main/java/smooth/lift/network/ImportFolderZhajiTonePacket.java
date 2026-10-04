package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【09-30 / Forge 移植】客户端 -> 服务端:把 MBM_Audio/zhaji/in|out 里的一个 OGG
 * 导入存档并设为对应一侧(或一组)闸机的提示音。
 */
public class ImportFolderZhajiTonePacket {
    private final String which;
    private final long groupKey;
    private final String fileName;

    public ImportFolderZhajiTonePacket(String which, long groupKey, String fileName) {
        this.which = which;
        this.groupKey = groupKey;
        this.fileName = fileName;
    }

    public static void encode(ImportFolderZhajiTonePacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.which, 32);
        buf.writeLong(pkt.groupKey);
        buf.writeUtf(pkt.fileName, 128);
    }

    public static ImportFolderZhajiTonePacket decode(FriendlyByteBuf buf) {
        return new ImportFolderZhajiTonePacket(buf.readUtf(32), buf.readLong(), buf.readUtf(128));
    }

    public static void handle(ImportFolderZhajiTonePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
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
            boolean group = pkt.groupKey != EscalatorSpeedManager.ZHAJI_GROUP_NONE;
            String problem = EscalatorSpeedManager.importAudioToStore(
                    level, EscalatorSpeedManager.zhajiToneCategory(pkt.which), pkt.fileName);
            if (problem == null) {
                boolean ok = group
                        ? EscalatorSpeedManager.setZhajiGroupTone(level, pkt.groupKey, pkt.which, pkt.fileName)
                        : EscalatorSpeedManager.setZhajiToneAudio(level, pkt.which, pkt.fileName);
                if (ok) {
                    SmoothLift.noteUiResult(player, true);
                    EscalatorSpeedManager.syncAudioToAll(level.getServer());
                    EscalatorSpeedManager.syncZhajiToAll(level.getServer());
                } else {
                    SmoothLift.noteUiResult(player, false);
                    EscalatorSpeedManager.syncAudioToAll(level.getServer());
                }
            } else {
                SmoothLift.noteUiResult(player, false);
            }
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
