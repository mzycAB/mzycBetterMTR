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
 * {@code EscalatorSpeedManager.buildHelpSpeedPacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class HelpSpeedSyncPacket {
    private final byte[] body;

    public HelpSpeedSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(HelpSpeedSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static HelpSpeedSyncPacket decode(FriendlyByteBuf buf) {
        return new HelpSpeedSyncPacket(buf.readByteArray());
    }

    public static void handle(HelpSpeedSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            int defaultIn = data.readVarInt();
            int countIn = data.readVarInt();
            Map<BlockPos, Integer> blockIn = new HashMap<>();
            for (int i = 0; i < countIn; i++) {
                blockIn.put(data.readBlockPos(), data.readVarInt());
            }
            int defaultOut = data.readVarInt();
            int countOut = data.readVarInt();
            Map<BlockPos, Integer> blockOut = new HashMap<>();
            for (int i = 0; i < countOut; i++) {
                blockOut.put(data.readBlockPos(), data.readVarInt());
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientHelpSpeeds(dimKey, defaultIn, blockIn, defaultOut, blockOut);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
