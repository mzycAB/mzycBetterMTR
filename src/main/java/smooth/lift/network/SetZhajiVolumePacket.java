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
 * 【09-30 / Forge 移植】客户端 -> 服务端:闸机(进站/出站)提示音音量(1~1000,VarInt)。
 */
public class SetZhajiVolumePacket {
    private final String which;
    private final long groupKey;
    private final int volume;

    public SetZhajiVolumePacket(String which, long groupKey, int volume) {
        this.which = which;
        this.groupKey = groupKey;
        this.volume = volume;
    }

    public static void encode(SetZhajiVolumePacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.which, 32);
        buf.writeLong(pkt.groupKey);
        buf.writeVarInt(pkt.volume);
    }

    public static SetZhajiVolumePacket decode(FriendlyByteBuf buf) {
        return new SetZhajiVolumePacket(buf.readUtf(32), buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetZhajiVolumePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            if (group) {
                EscalatorSpeedManager.setZhajiGroupVolume(level, pkt.groupKey, pkt.which, pkt.volume);
            } else {
                EscalatorSpeedManager.setZhajiToneVolume(level, pkt.which, pkt.volume);
            }
            SmoothLift.noteUiResult(player, true);
            EscalatorSpeedManager.syncZhajiToAll(level.getServer());
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
