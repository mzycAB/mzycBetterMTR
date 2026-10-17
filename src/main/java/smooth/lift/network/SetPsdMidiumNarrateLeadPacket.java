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
 * 【09-30 续 3 / Forge 移植】客户端 -> 服务端:某一串屏蔽门「站台广播(讲述人)」的等待秒数。
 */
public class SetPsdMidiumNarrateLeadPacket {
    private final long key;
    private final int seconds;

    public SetPsdMidiumNarrateLeadPacket(long key, int seconds) {
        this.key = key;
        this.seconds = seconds;
    }

    public static void encode(SetPsdMidiumNarrateLeadPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeVarInt(pkt.seconds);
    }

    public static SetPsdMidiumNarrateLeadPacket decode(FriendlyByteBuf buf) {
        return new SetPsdMidiumNarrateLeadPacket(buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetPsdMidiumNarrateLeadPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            EscalatorSpeedManager.setDoorPsdMidiumNarrateSeconds(level, pkt.key, pkt.seconds);
            SmoothLift.noteUiResult(player, true);
            EscalatorSpeedManager.syncPsdToneToAll(level.getServer());
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
