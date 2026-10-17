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
 * 【1.50】客户端 -> 服务端：某一串屏蔽门的进站报站音量。buf: key(long)→volume(varInt)
 */
public class SetPsdArriveLoudPacket {
    private final long key;
    private final int volume;

    public SetPsdArriveLoudPacket(long key, int volume) {
        this.key = key;
        this.volume = volume;
    }

    public static void encode(SetPsdArriveLoudPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeVarInt(pkt.volume);
    }

    public static SetPsdArriveLoudPacket decode(FriendlyByteBuf buf) {
        return new SetPsdArriveLoudPacket(buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetPsdArriveLoudPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            EscalatorSpeedManager.setDoorPsdArriveVolume(level, pkt.key, pkt.volume);
            int applied = EscalatorSpeedManager.getDoorPsdArriveVolume(level, pkt.key);
            player.displayClientMessage(Component.literal(
                    "这一串屏蔽门的进站报站音量已设为 " + applied), true);
            EscalatorSpeedManager.syncPsdToneToAll(player.server);

        });
        context.setPacketHandled(true);
    }
}
