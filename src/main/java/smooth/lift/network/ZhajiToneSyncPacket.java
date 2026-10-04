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
 * {@code EscalatorSpeedManager.buildZhajiPacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class ZhajiToneSyncPacket {
    private final byte[] body;

    public ZhajiToneSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(ZhajiToneSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static ZhajiToneSyncPacket decode(FriendlyByteBuf buf) {
        return new ZhajiToneSyncPacket(buf.readByteArray());
    }

    public static void handle(ZhajiToneSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            String audioIn = data.readUtf(128);
            String audioOut = data.readUtf(128);
            int volumeIn = data.readVarInt();
            int volumeOut = data.readVarInt();
            int groupCount = data.readVarInt();
            Map<Long, EscalatorSpeedData.ZhajiTone> tones = new HashMap<>();
            for (int i = 0; i < groupCount; i++) {
                long groupKey = data.readLong();
                String groupIn = data.readUtf(128);
                String groupOut = data.readUtf(128);
                Integer groupVolumeIn = EscalatorSpeedManager.readDoorOptInt(data);
                Integer groupVolumeOut = EscalatorSpeedManager.readDoorOptInt(data);
                tones.put(groupKey, new EscalatorSpeedData.ZhajiTone(groupIn, groupOut,
                        groupVolumeIn, groupVolumeOut));
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientZhaji(dimKey, audioIn, audioOut, volumeIn, volumeOut, tones);
                smooth.lift.client.ZhajiToneSetupScreen.notifyToneDataChanged();
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
