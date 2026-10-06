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
 * 【09-30 续 3 / Forge 移植】客户端 -> 服务端:某一串屏蔽门「站台广播(讲述人)」的样式。
 */
public class SetPsdMidiumNarratePacket {
    private final long key;
    private final int mode;

    public SetPsdMidiumNarratePacket(long key, int mode) {
        this.key = key;
        this.mode = mode;
    }

    public static void encode(SetPsdMidiumNarratePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeVarInt(pkt.mode);
    }

    public static SetPsdMidiumNarratePacket decode(FriendlyByteBuf buf) {
        return new SetPsdMidiumNarratePacket(buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetPsdMidiumNarratePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            EscalatorSpeedManager.setDoorPsdMidiumNarrate(level, pkt.key, pkt.mode);
            SmoothLift.noteUiResult(player, true);
            EscalatorSpeedManager.syncPsdToneToAll(level.getServer());
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
