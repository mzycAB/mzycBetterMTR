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
 * {@code EscalatorSpeedManager.buildHelpPacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class HelpSyncPacket {
    private final byte[] body;

    public HelpSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(HelpSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static HelpSyncPacket decode(FriendlyByteBuf buf) {
        return new HelpSyncPacket(buf.readByteArray());
    }

    public static void handle(HelpSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            boolean defaultHelp = data.readBoolean();
            int count = data.readVarInt();
            Map<BlockPos, Boolean> help = new HashMap<>();
            for (int i = 0; i < count; i++) {
                help.put(data.readBlockPos(), data.readBoolean());
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientHelp(dimKey, defaultHelp, help);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
