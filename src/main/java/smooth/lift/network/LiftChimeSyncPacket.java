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
 * {@code EscalatorSpeedManager.buildLiftChimePacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class LiftChimeSyncPacket {
    private final byte[] body;

    public LiftChimeSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(LiftChimeSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static LiftChimeSyncPacket decode(FriendlyByteBuf buf) {
        return new LiftChimeSyncPacket(buf.readByteArray());
    }

    public static void handle(LiftChimeSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            boolean enabled = data.readBoolean();
            float speed = data.readFloat();
            int volume = data.readVarInt();
            boolean upEnabled = data.readBoolean();
            boolean downEnabled = data.readBoolean();
            boolean openEnabled = data.readBoolean();
            boolean closeEnabled = data.readBoolean();
            int round = data.readVarInt();
            // 【10-03】双维：垂直（y 轴）范围紧跟水平范围（读序 = buildLiftChimePacket 写序）
            int roundY = data.readVarInt();
            int toneVolumeUp = data.readVarInt();
            int toneVolumeDown = data.readVarInt();
            int toneVolumeOpen = data.readVarInt();
            int toneVolumeClose = data.readVarInt();
            String toneAudioUp = data.readUtf(128);
            String toneAudioDown = data.readUtf(128);
            String toneAudioOpen = data.readUtf(128);
            String toneAudioClose = data.readUtf(128);
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientLiftChime(dimKey, enabled, speed, volume,
                        upEnabled, downEnabled, openEnabled, closeEnabled, round, roundY,
                        toneVolumeUp, toneVolumeDown, toneVolumeOpen, toneVolumeClose,
                        toneAudioUp, toneAudioDown, toneAudioOpen, toneAudioClose);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
