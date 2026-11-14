package smooth.lift.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/**
 * 【1.39.11201 移植】服务端 -> 客户端同步包（裸负载）：负载由
 * {@code EscalatorSpeedManager.buildLiftBgmPacket} 写出，与 Fabric 侧逐字节一致
 * （{@code dimId(utf256) → audioId(utf128)}）；本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class LiftBgmSyncPacket {
    private final byte[] body;

    public LiftBgmSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(LiftBgmSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static LiftBgmSyncPacket decode(FriendlyByteBuf buf) {
        return new LiftBgmSyncPacket(buf.readByteArray());
    }

    public static void handle(LiftBgmSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));
            String dimId = data.readUtf(256);
            String audioId = data.readUtf(128);
            int bgmVolume = data.readVarInt();
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientLiftBgm(dimKey, audioId, bgmVolume);
                smooth.lift.client.LiftBgmSetupScreen.notifyBgmDataChanged();
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
