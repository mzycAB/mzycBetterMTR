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
 * {@code EscalatorSpeedManager.buildRoundPacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class RoundSyncPacket {
    private final byte[] body;

    public RoundSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(RoundSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static RoundSyncPacket decode(FriendlyByteBuf buf) {
        return new RoundSyncPacket(buf.readByteArray());
    }

    public static void handle(RoundSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            int defaultRound = data.readVarInt();
            int count = data.readVarInt();
            Map<BlockPos, Integer> round = new HashMap<>();
            for (int i = 0; i < count; i++) {
                round.put(data.readBlockPos(), data.readVarInt());
            }
            // ★【10-03】双维：垂直（y 轴）维紧随水平表之后（读序 = buildRoundPacket 写序）
            int defaultRoundY = data.readVarInt();
            int countY = data.readVarInt();
            Map<BlockPos, Integer> roundY = new HashMap<>();
            for (int i = 0; i < countY; i++) {
                roundY.put(data.readBlockPos(), data.readVarInt());
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientRounds(dimKey, defaultRound, round, defaultRoundY, roundY);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
