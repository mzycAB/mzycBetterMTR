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
 * {@code EscalatorSpeedManager.buildHelpAudioPacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class HelpAudioSyncPacket {
    private final byte[] body;

    public HelpAudioSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(HelpAudioSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static HelpAudioSyncPacket decode(FriendlyByteBuf buf) {
        return new HelpAudioSyncPacket(buf.readByteArray());
    }

    public static void handle(HelpAudioSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            String defaultIn = data.readUtf(128);
            int countIn = data.readVarInt();
            Map<BlockPos, String> blockIn = new HashMap<>();
            for (int i = 0; i < countIn; i++) {
                blockIn.put(data.readBlockPos(), data.readUtf(128));
            }
            String defaultOut = data.readUtf(128);
            int countOut = data.readVarInt();
            Map<BlockPos, String> blockOut = new HashMap<>();
            for (int i = 0; i < countOut; i++) {
                blockOut.put(data.readBlockPos(), data.readUtf(128));
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientHelpAudio(dimKey, defaultIn, blockIn, defaultOut, blockOut);
                smooth.lift.client.HelpAudioSetupScreen.notifyDataChanged();
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
