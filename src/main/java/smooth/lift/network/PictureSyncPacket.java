package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.client.SmoothLiftClientEvents;

import java.util.function.Supplier;

/**
 * 【1.18.1204 / Forge 移植】服务端 -> 客户端:地图图片库分块同步（与音频同步同一个信封格式）。
 * 信封：dimId(utf256) → totalChunks(varInt) → chunkIndex(varInt) → chunk(bytes)；
 * 全部块到齐后由 {@link SmoothLiftClientEvents#onPictureSyncChunk} 拼装并解析
 * （负载写序见 {@code EscalatorSpeedManager#buildPicturePayload}）。
 */
public class PictureSyncPacket {
    private final String dimId;
    private final int totalChunks;
    private final int chunkIndex;
    private final byte[] chunk;

    public PictureSyncPacket(String dimId, int totalChunks, int chunkIndex, byte[] chunk) {
        this.dimId = dimId;
        this.totalChunks = totalChunks;
        this.chunkIndex = chunkIndex;
        this.chunk = chunk;
    }

    public static void encode(PictureSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimId, 256);
        buf.writeVarInt(pkt.totalChunks);
        buf.writeVarInt(pkt.chunkIndex);
        buf.writeByteArray(pkt.chunk);
    }

    public static PictureSyncPacket decode(FriendlyByteBuf buf) {
        return new PictureSyncPacket(buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    public static void handle(PictureSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() ->
                SmoothLiftClientEvents.onPictureSyncChunk(pkt.dimId, pkt.totalChunks, pkt.chunkIndex, pkt.chunk));
        context.setPacketHandled(true);
    }
}
