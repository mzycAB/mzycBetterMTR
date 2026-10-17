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
 * 【09-28 / Forge 移植】客户端 -> 服务端:某一串屏蔽门「进站广播(讲述人)」的提前秒数(独立窗口)。
 */
public class SetPsdNarrateLeadPacket {
    private final long key;
    private final int seconds;

    public SetPsdNarrateLeadPacket(long key, int seconds) {
        this.key = key;
        this.seconds = seconds;
    }

    public static void encode(SetPsdNarrateLeadPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeVarInt(pkt.seconds);
    }

    public static SetPsdNarrateLeadPacket decode(FriendlyByteBuf buf) {
        return new SetPsdNarrateLeadPacket(buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetPsdNarrateLeadPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            EscalatorSpeedManager.setDoorPsdNarrateSeconds(level, pkt.key, pkt.seconds);
            SmoothLift.noteUiResult(player, true);
            EscalatorSpeedManager.syncPsdToneToAll(level.getServer());
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
