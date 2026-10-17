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
 * 【09-30 / Forge 移植】客户端 -> 服务端:闸机(进站/出站)提示音设置。groupKey 为组锚点,
 * {@code ZHAJI_GROUP_NONE} 表示改维度默认层(即「所有闸机」)。
 */
public class SetZhajiTonePacket {
    private final String which;
    private final long groupKey;
    private final String audioId;

    public SetZhajiTonePacket(String which, long groupKey, String audioId) {
        this.which = which;
        this.groupKey = groupKey;
        this.audioId = audioId;
    }

    public static void encode(SetZhajiTonePacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.which, 32);
        buf.writeLong(pkt.groupKey);
        buf.writeUtf(pkt.audioId, 128);
    }

    public static SetZhajiTonePacket decode(FriendlyByteBuf buf) {
        return new SetZhajiTonePacket(buf.readUtf(32), buf.readLong(), buf.readUtf(128));
    }

    public static void handle(SetZhajiTonePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            boolean ok = group
                    ? EscalatorSpeedManager.setZhajiGroupTone(level, pkt.groupKey, pkt.which, pkt.audioId)
                    : EscalatorSpeedManager.setZhajiToneAudio(level, pkt.which, pkt.audioId);
            if (ok) {
                SmoothLift.noteUiResult(player, true);
                EscalatorSpeedManager.syncZhajiToAll(level.getServer());
            } else {
                SmoothLift.noteUiResult(player, false);
            }
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
