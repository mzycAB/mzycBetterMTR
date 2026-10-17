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
 * 【1.50】客户端 -> 服务端：某一扇屏蔽门单项（open/close）提示音音量。buf: key(long)→which(utf32)→volume(varInt)
 */
public class SetPsdToneVolumePacket {
    private final long key;
    private final String which;
    private final int volume;

    public SetPsdToneVolumePacket(long key, String which, int volume) {
        this.key = key;
        this.which = which;
        this.volume = volume;
    }

    public static void encode(SetPsdToneVolumePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.which, 32);
        buf.writeVarInt(pkt.volume);
    }

    public static SetPsdToneVolumePacket decode(FriendlyByteBuf buf) {
        return new SetPsdToneVolumePacket(buf.readLong(), buf.readUtf(32), buf.readVarInt());
    }

    public static void handle(SetPsdToneVolumePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (!"open".equals(pkt.which) && !"close".equals(pkt.which)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            EscalatorSpeedManager.setDoorPsdToneVolume(level, pkt.key, pkt.which, pkt.volume);
            int applied = EscalatorSpeedManager.getDoorPsdToneVolume(level, pkt.key, pkt.which);
            player.displayClientMessage(Component.literal(
                    "这一扇屏蔽门「" + EscalatorSpeedData.psdToneLabel(pkt.which) + "」音量已设为 "
                            + applied), true);
            EscalatorSpeedManager.syncPsdToneToAll(player.server);

        });
        context.setPacketHandled(true);
    }
}
