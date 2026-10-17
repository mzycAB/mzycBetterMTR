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
 * {@code EscalatorSpeedManager.buildHelpRoundPacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class HelpRoundSyncPacket {
    private final byte[] body;

    public HelpRoundSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(HelpRoundSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static HelpRoundSyncPacket decode(FriendlyByteBuf buf) {
        return new HelpRoundSyncPacket(buf.readByteArray());
    }

    public static void handle(HelpRoundSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            int defaultHelpRound = data.readVarInt();
            int count = data.readVarInt();
            Map<BlockPos, Integer> helpRound = new HashMap<>();
            for (int i = 0; i < count; i++) {
                helpRound.put(data.readBlockPos(), data.readVarInt());
            }
            // ★【10-03】双维：垂直（y 轴）维紧随水平表之后（读序 = buildHelpRoundPacket 写序）
            int defaultHelpRoundY = data.readVarInt();
            int countY = data.readVarInt();
            Map<BlockPos, Integer> helpRoundY = new HashMap<>();
            for (int i = 0; i < countY; i++) {
                helpRoundY.put(data.readBlockPos(), data.readVarInt());
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientHelpRounds(dimKey, defaultHelpRound, helpRound,
                        defaultHelpRoundY, helpRoundY);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
