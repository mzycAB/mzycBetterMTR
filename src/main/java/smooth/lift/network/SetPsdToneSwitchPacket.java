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
 * 【1.50】客户端 -> 服务端：某一扇屏蔽门的提示音开关。buf: key(long)→which(utf32: master|open|close)→enabled(boolean)
 */
public class SetPsdToneSwitchPacket {
    private final long key;
    private final String which;
    private final boolean enabled;

    public SetPsdToneSwitchPacket(long key, String which, boolean enabled) {
        this.key = key;
        this.which = which;
        this.enabled = enabled;
    }

    public static void encode(SetPsdToneSwitchPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.which, 32);
        buf.writeBoolean(pkt.enabled);
    }

    public static SetPsdToneSwitchPacket decode(FriendlyByteBuf buf) {
        return new SetPsdToneSwitchPacket(buf.readLong(), buf.readUtf(32), buf.readBoolean());
    }

    public static void handle(SetPsdToneSwitchPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            if ("master".equals(pkt.which)) {
                EscalatorSpeedManager.setDoorPsdHelp(level, pkt.key, pkt.enabled);
                player.displayClientMessage(Component.literal(
                        "这一扇屏蔽门的开关门提示音已" + (pkt.enabled ? "开启" : "关闭")), true);
            } else if ("open".equals(pkt.which) || "close".equals(pkt.which)) {
                EscalatorSpeedManager.setDoorPsdToneEnabled(level, pkt.key, pkt.which, pkt.enabled);
                player.displayClientMessage(Component.literal(
                        "这一扇屏蔽门的「" + EscalatorSpeedData.psdToneLabel(pkt.which) + "」已"
                                + (pkt.enabled ? "开启" : "关闭")), true);
            } else {
                return;
            }
            EscalatorSpeedManager.syncPsdToneToAll(player.server);

        });
        context.setPacketHandled(true);
    }
}
