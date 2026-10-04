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
 * 【1.50】客户端 -> 服务端：某一串屏蔽门的到站播报素材 + 等待秒数。buf: key(long)→name(utf128)→seconds(varInt)
 */
public class SetPsdMidiumPacket {
    private final long key;
    private final String name;
    private final int seconds;

    public SetPsdMidiumPacket(long key, String name, int seconds) {
        this.key = key;
        this.name = name;
        this.seconds = seconds;
    }

    public static void encode(SetPsdMidiumPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.name, 128);
        buf.writeVarInt(pkt.seconds);
    }

    public static SetPsdMidiumPacket decode(FriendlyByteBuf buf) {
        return new SetPsdMidiumPacket(buf.readLong(), buf.readUtf(128), buf.readVarInt());
    }

    public static void handle(SetPsdMidiumPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            int libBefore = EscalatorSpeedManager.getServerData(level).audioLibrary.size();
            String resolved = EscalatorSpeedManager.resolvePsdMidiumName(level, EscalatorSpeedManager.CAT_PSD_MIDIUM, pkt.name);
            if (resolved == null) {
                player.displayClientMessage(Component.literal(
                        "到站播报设置失败：找不到名为「" + pkt.name + "」的音频"), true);
                return;
            }
            if (EscalatorSpeedManager.getServerData(level).audioLibrary.size() > libBefore) {
                EscalatorSpeedManager.syncAudioToAll(player.server);
            }
            EscalatorSpeedManager.setDoorPsdMidium(level, pkt.key, resolved, pkt.seconds);
            player.displayClientMessage(Component.literal(
                    "这一串屏蔽门到站播报已设为「" + SmoothLift.psdToneAudioLabel(resolved) + "」"
                            + (pkt.seconds > 0 ? "（等待 " + pkt.seconds + " 秒）" : "")), true);
            EscalatorSpeedManager.syncPsdToneToAll(player.server);

        });
        context.setPacketHandled(true);
    }
}
