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
 * 【1.50】客户端 -> 服务端：把某一扇屏蔽门的某一项提示音素材设为「音频库某段 / 默认 / 不播」。buf: key(long)→which(utf32)→audioId(utf128)
 */
public class SetPsdTonePacket {
    private final long key;
    private final String which;
    private final String audioId;

    public SetPsdTonePacket(long key, String which, String audioId) {
        this.key = key;
        this.which = which;
        this.audioId = audioId;
    }

    public static void encode(SetPsdTonePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.which, 32);
        buf.writeUtf(pkt.audioId, 128);
    }

    public static SetPsdTonePacket decode(FriendlyByteBuf buf) {
        return new SetPsdTonePacket(buf.readLong(), buf.readUtf(32), buf.readUtf(128));
    }

    public static void handle(SetPsdTonePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            if (EscalatorSpeedManager.setServerPsdTone(level, pkt.key, pkt.which, pkt.audioId)) {
                player.displayClientMessage(Component.literal(
                        "已把这一扇屏蔽门的" + EscalatorSpeedData.psdToneLabel(pkt.which) + "设为"
                                + (EscalatorSpeedData.PSD_TONE_OFF.equals(pkt.audioId) ? "「不播」"
                                : SmoothLift.psdToneAudioLabel(pkt.audioId))), true);
                EscalatorSpeedManager.syncPsdToneToAll(player.server);
            } else {
                player.displayClientMessage(Component.literal(
                        "设置失败：音频不存在"), true);
            }

        });
        context.setPacketHandled(true);
    }
}
