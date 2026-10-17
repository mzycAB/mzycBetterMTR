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
 * 【1.50】客户端 -> 服务端：某一扇屏蔽门的关门提示音强制等待时长（秒）。buf: key(long)→seconds(varInt)
 */
public class SetPsdCloseWaitPacket {
    private final long key;
    private final int seconds;

    public SetPsdCloseWaitPacket(long key, int seconds) {
        this.key = key;
        this.seconds = seconds;
    }

    public static void encode(SetPsdCloseWaitPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeVarInt(pkt.seconds);
    }

    public static SetPsdCloseWaitPacket decode(FriendlyByteBuf buf) {
        return new SetPsdCloseWaitPacket(buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetPsdCloseWaitPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            EscalatorSpeedManager.setDoorPsdCloseWaitSeconds(level, pkt.key, pkt.seconds);
            int applied = EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(level, pkt.key);
            player.displayClientMessage(Component.literal(
                    "这一扇屏蔽门的关门提示音强制等待时长已设为 " + applied + " 秒"), true);
            EscalatorSpeedManager.syncPsdToneToAll(player.server);

        });
        context.setPacketHandled(true);
    }
}
