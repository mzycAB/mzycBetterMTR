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
 * {@code EscalatorSpeedManager.buildPsdTonePacket} 写出,与 Fabric 侧逐字节一致;
 * 本类 handle 按 Fabric 版客户端接收器的读序解析。
 */
public class PsdToneSyncPacket {
    private final byte[] body;

    public PsdToneSyncPacket(byte[] body) {
        this.body = body;
    }

    public static void encode(PsdToneSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeByteArray(pkt.body);
    }

    public static PsdToneSyncPacket decode(FriendlyByteBuf buf) {
        return new PsdToneSyncPacket(buf.readByteArray());
    }

    public static void handle(PsdToneSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(pkt.body));

            String dimId = data.readUtf(256);
            int n = data.readVarInt();
            Map<Long, EscalatorSpeedData.PsdToneAudio> tones = new HashMap<>();
            for (int i = 0; i < n; i++) {
                long key = data.readLong();
                String open = data.readUtf(128);
                String close = data.readUtf(128);
                Boolean help = EscalatorSpeedManager.readDoorOptBool(data);
                Boolean openEnabled = EscalatorSpeedManager.readDoorOptBool(data);
                Boolean closeEnabled = EscalatorSpeedManager.readDoorOptBool(data);
                Integer volume = EscalatorSpeedManager.readDoorOptInt(data);
                Integer openVolume = EscalatorSpeedManager.readDoorOptInt(data);
                Integer closeVolume = EscalatorSpeedManager.readDoorOptInt(data);
                Integer openWaitSeconds = EscalatorSpeedManager.readDoorOptInt(data);
                Integer closeWaitSeconds = EscalatorSpeedManager.readDoorOptInt(data);
                String midium = EscalatorSpeedManager.readDoorOptString(data);
                Integer midiumWaitSeconds = EscalatorSpeedManager.readDoorOptInt(data);
                String arrive = EscalatorSpeedManager.readDoorOptString(data);
                Integer arriveSeconds = EscalatorSpeedManager.readDoorOptInt(data);
                Integer midiumVolume = EscalatorSpeedManager.readDoorOptInt(data);
                Integer arriveVolume = EscalatorSpeedManager.readDoorOptInt(data);
                Integer narrate = EscalatorSpeedManager.readDoorOptInt(data);
                Integer narrateSeconds = EscalatorSpeedManager.readDoorOptInt(data);
                Integer midiumNarrate = EscalatorSpeedManager.readDoorOptInt(data);
                Integer midiumNarrateSeconds = EscalatorSpeedManager.readDoorOptInt(data);
                tones.put(key, new EscalatorSpeedData.PsdToneAudio(open, close,
                        help, openEnabled, closeEnabled,
                        volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds,
                        midium, midiumWaitSeconds, midiumVolume,
                        arrive, arriveSeconds, arriveVolume,
                        narrate, narrateSeconds, midiumNarrate, midiumNarrateSeconds));
            }
            // 【10-03 五改】**门串级**设置（关门后等待发车）—— 同一份包末尾追加的一段（读序同 Fabric）。
            int runCount = data.readVarInt();
            Map<Long, EscalatorSpeedData.PsdRunSetting> runSettings = new HashMap<>();
            for (int i = 0; i < runCount; i++) {
                long runAnchor = data.readLong();
                long platformId = data.readLong();
                Integer departDelaySeconds = EscalatorSpeedManager.readDoorOptInt(data);
                runSettings.put(runAnchor, new EscalatorSpeedData.PsdRunSetting(
                        departDelaySeconds,
                        EscalatorSpeedData.psdPlatformKnown(platformId) ? platformId : null));
            }
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
                EscalatorSpeedManager.applyClientPsdTone(dimKey, tones);
                // 【10-03 五改】门串级那一张也跟着落地（与上面那张表无关，各存各的）。
                EscalatorSpeedManager.applyClientPsdRun(dimKey, runSettings);
                smooth.lift.client.PsdToneSetupScreen.notifyToneDataChanged();
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
