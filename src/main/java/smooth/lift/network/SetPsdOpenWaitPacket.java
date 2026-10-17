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
 * 【1.50】客户端 -> 服务端：某一扇屏蔽门的开门提示音等待秒数。buf: key(long)→seconds(varInt)
 */
public class SetPsdOpenWaitPacket {
    private final long key;
    private final int seconds;

    public SetPsdOpenWaitPacket(long key, int seconds) {
        this.key = key;
        this.seconds = seconds;
    }

    public static void encode(SetPsdOpenWaitPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeVarInt(pkt.seconds);
    }

    public static SetPsdOpenWaitPacket decode(FriendlyByteBuf buf) {
        return new SetPsdOpenWaitPacket(buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetPsdOpenWaitPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            EscalatorSpeedManager.setDoorPsdOpenWaitSeconds(level, pkt.key, pkt.seconds);
            int applied = EscalatorSpeedManager.getDoorPsdOpenWaitSeconds(level, pkt.key);
            player.displayClientMessage(Component.literal(
                    "这一扇屏蔽门的开门提示音等待秒数已设为 " + applied), true);
            EscalatorSpeedManager.syncPsdToneToAll(player.server);

        });
        context.setPacketHandled(true);
    }
}
