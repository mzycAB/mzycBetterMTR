package smooth.lift.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【Forge 移植 / 1.29】服务端 -> 客户端同步包(裸负载):负载由
 * {@code EscalatorSpeedManager.buildVolumePacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class VolumeSyncPacket {
    private final byte[] body;

    public VolumeSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(VolumeSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static VolumeSyncPacket decode(FriendlyByteBuf buf) {
        return new VolumeSyncPacket(buf.readByteArray());
    }

    public static void handle(VolumeSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            int defaultVolume = data.readVarInt();
            int count = data.readVarInt();
            Map<BlockPos, Integer> volumes = new HashMap<>();
            for (int i = 0; i < count; i++) {
                volumes.put(data.readBlockPos(), data.readVarInt());
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientVolumes(dimKey, volumes, defaultVolume);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
