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
 * 【1.53】客户端 -> 服务端：MBM help 底部全音量框。buf: volume(varInt)
 */
public class MbmAllVolumePacket {
    private final int volume;

    public MbmAllVolumePacket(int volume) {
        this.volume = volume;
    }

    public static void encode(MbmAllVolumePacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.volume);
    }

    public static MbmAllVolumePacket decode(FriendlyByteBuf buf) {
        return new MbmAllVolumePacket(buf.readVarInt());
    }

    public static void handle(MbmAllVolumePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            int applied = SmoothLift.applyAllVolumes(player, pkt.volume);
            player.displayClientMessage(Component.literal(
                    "模组所有音量已一起设为 " + applied + "（100 = 原始音量）"), false);

        });
        context.setPacketHandled(true);
    }
}
