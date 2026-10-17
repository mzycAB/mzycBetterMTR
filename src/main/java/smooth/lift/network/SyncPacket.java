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
 * {@code EscalatorSpeedManager.buildSyncPacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class SyncPacket {
    private final byte[] body;

    public SyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(SyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static SyncPacket decode(FriendlyByteBuf buf) {
        return new SyncPacket(buf.readByteArray());
    }

    public static void handle(SyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            int dimCount = data.readVarInt();
            for (int i = 0; i < dimCount; i++) {
                String dimId = data.readUtf(256);
                double defaultSpeed = data.readDouble();
                boolean stepEnabled = data.readBoolean();
                double stepValue = data.readDouble();
                int speedCount = data.readVarInt();
                Map<BlockPos, Double> speeds = new HashMap<>();
                for (int j = 0; j < speedCount; j++) {
                    speeds.put(data.readBlockPos(), data.readDouble());
                }
                int stepCount = data.readVarInt();
                Map<BlockPos, Double> stepSpeeds = new HashMap<>();
                for (int j = 0; j < stepCount; j++) {
                    stepSpeeds.put(data.readBlockPos(), data.readDouble());
                }
                try {
                    ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                    EscalatorSpeedManager.applyClientData(dimKey, defaultSpeed, speeds, stepSpeeds,
                            stepEnabled, stepValue);
                } catch (Exception ignored) {
                }
            }
        });
        context.setPacketHandled(true);
    }
}
