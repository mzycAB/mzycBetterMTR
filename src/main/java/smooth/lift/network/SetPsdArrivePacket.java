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
 * 【1.50】客户端 -> 服务端：某一串屏蔽门的进站报站素材 + 秒数。buf: key(long)→name(utf128)→seconds(varInt)
 */
public class SetPsdArrivePacket {
    private final long key;
    private final String name;
    private final int seconds;

    public SetPsdArrivePacket(long key, String name, int seconds) {
        this.key = key;
        this.name = name;
        this.seconds = seconds;
    }

    public static void encode(SetPsdArrivePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.name, 128);
        buf.writeVarInt(pkt.seconds);
    }

    public static SetPsdArrivePacket decode(FriendlyByteBuf buf) {
        return new SetPsdArrivePacket(buf.readLong(), buf.readUtf(128), buf.readVarInt());
    }

    public static void handle(SetPsdArrivePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            String resolved = EscalatorSpeedManager.resolvePsdArriveName(level, EscalatorSpeedManager.CAT_PSD_ARRIVE, pkt.name);
            if (resolved == null) {
                player.displayClientMessage(Component.literal(
                        "进站报站设置失败：找不到名为「" + pkt.name + "」的音频"), true);
                return;
            }
            if (EscalatorSpeedManager.getServerData(level).audioLibrary.size() > libBefore) {
                EscalatorSpeedManager.syncAudioToAll(player.server);
            }
            EscalatorSpeedManager.setDoorPsdArrive(level, pkt.key, resolved, pkt.seconds);
            player.displayClientMessage(Component.literal(
                    "这一串屏蔽门进站报站已设为「" + SmoothLift.psdToneAudioLabel(resolved) + "」"
                            + (pkt.seconds > 0 ? "（等待 " + pkt.seconds + " 秒）" : "")), true);
            EscalatorSpeedManager.syncPsdToneToAll(player.server);

        });
        context.setPacketHandled(true);
    }
}
