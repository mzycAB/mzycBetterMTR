package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【10-03 五改 / Forge 移植】客户端 -> 服务端:某一串屏蔽门「关门后等待 X 秒发车」。
 *
 * <p>★ 与 {@code SetPsdMidiumNarrateLeadPacket} 的键**不是同一个键空间**:这一项是**门串级**
 * （键 = 门串锚点），还额外带「这一串落在哪个 MTR 站台」（列车那侧按站台 id 反查）。
 */
public class SetPsdDepartDelayPacket {
    private final long runAnchor;
    private final long platformId;
    private final int seconds;

    public SetPsdDepartDelayPacket(long runAnchor, long platformId, int seconds) {
        this.runAnchor = runAnchor;
        this.platformId = platformId;
        this.seconds = seconds;
    }

    public static void encode(SetPsdDepartDelayPacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.runAnchor);
        buf.writeLong(pkt.platformId);
        buf.writeVarInt(pkt.seconds);
    }

    public static SetPsdDepartDelayPacket decode(FriendlyByteBuf buf) {
        return new SetPsdDepartDelayPacket(buf.readLong(), buf.readLong(), buf.readVarInt());
    }

    public static void handle(SetPsdDepartDelayPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            EscalatorSpeedManager.setPsdRunDepartDelaySeconds(level, pkt.runAnchor, pkt.platformId, pkt.seconds);
            SmoothLift.noteUiResult(player, true);
            EscalatorSpeedManager.syncPsdToneToAll(level.getServer());
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
