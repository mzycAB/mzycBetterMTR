package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.SmoothLift;
import java.util.function.Supplier;

/**
 * 【10-05】客户端 -> 服务端：回「最近的那一串」的 runKey。
 *
 * <p>buf 顺序：{@code found(boolean) → runKey(long)}（{@code found == false} 时 runKey 无意义）。
 */
public class PsdNearestReplyPacket {
    private final boolean found;
    private final long runKey;

    public PsdNearestReplyPacket(boolean found, long runKey) {
        this.found = found;
        this.runKey = runKey;
    }

    public static void encode(PsdNearestReplyPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.found);
        buf.writeLong(pkt.runKey);
    }

    public static PsdNearestReplyPacket decode(FriendlyByteBuf buf) {
        return new PsdNearestReplyPacket(buf.readBoolean(), buf.readLong());
    }

    public static void handle(PsdNearestReplyPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            SmoothLift.onPsdNearestReply(player, pkt.found, pkt.runKey);
        });
        context.setPacketHandled(true);
    }
}
