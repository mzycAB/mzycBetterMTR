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
 * {@code EscalatorSpeedManager.buildLiftTonePacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class LiftToneSyncPacket {
    private final byte[] body;

    public LiftToneSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(LiftToneSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static LiftToneSyncPacket decode(FriendlyByteBuf buf) {
        return new LiftToneSyncPacket(buf.readByteArray());
    }

    public static void handle(LiftToneSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            int n = data.readVarInt();
            Map<Long, EscalatorSpeedData.LiftToneAudio> tones = new HashMap<>();
            for (int i = 0; i < n; i++) {
                long key = data.readLong();
                String up = data.readUtf(128);
                String down = data.readUtf(128);
                String open = data.readUtf(128);
                String close = data.readUtf(128);
                tones.put(key, new EscalatorSpeedData.LiftToneAudio(up, down, open, close));
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientLiftTone(dimKey, tones);
                smooth.lift.client.LiftToneSetupScreen.notifyToneDataChanged();
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
