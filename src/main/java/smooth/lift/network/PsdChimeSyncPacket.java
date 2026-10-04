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
import smooth.lift.client.TrainAnnounceSwitch;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【Forge 移植 / 1.29】服务端 -> 客户端同步包(裸负载):负载由
 * {@code EscalatorSpeedManager.buildPsdChimePacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class PsdChimeSyncPacket {
    private final byte[] body;

    public PsdChimeSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(PsdChimeSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static PsdChimeSyncPacket decode(FriendlyByteBuf buf) {
        return new PsdChimeSyncPacket(buf.readByteArray());
    }

    public static void handle(PsdChimeSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            boolean enabled = data.readBoolean();
            int volume = data.readVarInt();
            boolean openEnabled = data.readBoolean();
            boolean closeEnabled = data.readBoolean();
            int roundXz = data.readVarInt();
            int roundY = data.readVarInt();
            int toneVolumeOpen = data.readVarInt();
            int toneVolumeClose = data.readVarInt();
            String toneAudioOpen = data.readUtf(128);
            String toneAudioClose = data.readUtf(128);
            int closeWaitSeconds = data.readVarInt();
            String midiumAudio = data.readUtf(128);
            int midiumWaitSeconds = data.readVarInt();
            String arriveAudio = data.readUtf(128);
            int arriveSeconds = data.readVarInt();
            int midiumVolume = data.readVarInt();
            int arriveVolume = data.readVarInt();
            int midiumRoundXz = data.readVarInt();
            int midiumRoundY = data.readVarInt();
            int arriveRoundXz = data.readVarInt();
            int arriveRoundY = data.readVarInt();
            int narrateMode = data.readVarInt();
            int narrateSeconds = data.readVarInt();
            int midiumNarrateMode = data.readVarInt();
            int midiumNarrateSeconds = data.readVarInt();
            // 【10-01】两条讲述人广播的玩家自定义文字（末尾追加；写侧同序）→ 客户端讲述人镜像
            java.util.List<String> arriveNarrateUserTexts = EscalatorSpeedManager.readNarrateUserTexts(data);
            java.util.List<String> midiumNarrateUserTexts = EscalatorSpeedManager.readNarrateUserTexts(data);
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientPsdChime(dimKey, enabled, volume,
                        openEnabled, closeEnabled, roundXz, roundY,
                        toneVolumeOpen, toneVolumeClose,
                        toneAudioOpen, toneAudioClose, closeWaitSeconds,
                        midiumAudio, midiumWaitSeconds, arriveAudio, arriveSeconds,
                        midiumVolume, arriveVolume,
                        midiumRoundXz, midiumRoundY, arriveRoundXz, arriveRoundY,
                        narrateMode, narrateSeconds, midiumNarrateMode, midiumNarrateSeconds);
                // 【10-01】两条讲述人广播的按存档自定义文字（镜像进客户端，供叙述/编辑/指令共用）
                TrainAnnounceSwitch.applyNarrateUserTexts(arriveNarrateUserTexts, midiumNarrateUserTexts);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
