package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 【10-01 / Forge 移植】客户端 -> 服务端：把某条讲述人广播的**整份自定义文字**写进存档。
 * 与 Fabric 的 {@code SET_PSD_NARRATE_TEXTS_CHANNEL} 语义一致（用户点名：自定义文字
 * **按存档保存**——不存 config、不存档间互通，随存档走；两条广播各一份互不共用）。
 */
public class SetPsdNarrateTextsPacket {
    private final int target;
    private final List<String> texts;

    public SetPsdNarrateTextsPacket(int target, List<String> texts) {
        this.target = target;
        this.texts = texts == null ? new ArrayList<>() : new ArrayList<>(texts);
    }

    public static void encode(SetPsdNarrateTextsPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.target);
        buf.writeVarInt(pkt.texts.size());
        for (String s : pkt.texts) {
            buf.writeUtf(s == null ? "" : s, 256);
        }
    }

    public static SetPsdNarrateTextsPacket decode(FriendlyByteBuf buf) {
        int target = buf.readVarInt();
        int n = buf.readVarInt();
        List<String> texts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            texts.add(buf.readUtf(256));
        }
        return new SetPsdNarrateTextsPacket(target, texts);
    }

    public static void handle(SetPsdNarrateTextsPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            int changed = EscalatorSpeedManager.setPsdNarrateUserTextsAll(player.server, pkt.target, pkt.texts);
            SmoothLift.noteUiResult(player, true);
            if (changed > 0) {
                // ★ 必须全量同步：客户端编辑器/讲述人读的就是镜像，不补就还是旧词。
                EscalatorSpeedManager.syncPsdChimeToAll(player.server);
            }
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}