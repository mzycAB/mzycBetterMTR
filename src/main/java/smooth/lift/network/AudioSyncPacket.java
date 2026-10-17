package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.client.SmoothLiftClientEvents;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：分块同步音频库、来源文件夹名单、扶梯-音频绑定与默认音频。
 *
 * <p>与 Fabric 版一致：整份负载（音频库 + 文件夹名单 + 绑定 + 默认音频）先序列化成一个
 * byte[]，再按 {@code EscalatorSpeedManager.AUDIO_CHUNK_SIZE} 切块逐包发送 ——
 * 单包（含 12MB 音频时）会远超网络包上限，必须拆包。
 *
 * <p>客户端把所有块收齐后拼回负载并解析（见 {@code SmoothLiftClientEvents#onAudioSyncChunk}）。
 * 这里用 {@link DistExecutor} 保证专用服上**永远不会**触碰客户端类（否则会 NoClassDefFoundError）。
 */
public class AudioSyncPacket {
    private final String dimension;
    private final int totalChunks;
    private final int chunkIndex;
    private final byte[] chunk;

    public AudioSyncPacket(String dimension, int totalChunks, int chunkIndex, byte[] chunk) {
        this.dimension = dimension;
        this.totalChunks = totalChunks;
        this.chunkIndex = chunkIndex;
        this.chunk = chunk;
    }

    public static void encode(AudioSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeVarInt(pkt.totalChunks);
        buf.writeVarInt(pkt.chunkIndex);
        buf.writeByteArray(pkt.chunk);
    }

    public static AudioSyncPacket decode(FriendlyByteBuf buf) {
        return new AudioSyncPacket(buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    public static void handle(AudioSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        final String dimension = pkt.dimension;
        final int totalChunks = pkt.totalChunks;
        final int chunkIndex = pkt.chunkIndex;
        final byte[] chunk = pkt.chunk;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SmoothLiftClientEvents.onAudioSyncChunk(dimension, totalChunks, chunkIndex, chunk)));
        context.setPacketHandled(true);
    }
}
