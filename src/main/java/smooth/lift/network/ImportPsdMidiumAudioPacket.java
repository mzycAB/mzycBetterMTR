package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;
import java.util.function.Supplier;

/**
 * 【1.19/1.28 / Forge 移植】客户端 -> 服务端：把**某一分类子文件夹**里的一段 OGG **只导入存档音频库**
 * （不改任何设置）。分类由发送的界面决定（每个界面只导入自己的分类，列车音效也走这一条）。
 * buf 顺序：{@code category(utf64) → name(utf128)}，与 Fabric 1.20.4 发送端 / 服务端接收器同序。
 */
public class ImportPsdMidiumAudioPacket {
    private final String category;
    private final String name;

    public ImportPsdMidiumAudioPacket(String category, String name) {
        this.category = category;
        this.name = name;
    }

    public static void encode(ImportPsdMidiumAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.category, 64);
        buf.writeUtf(pkt.name, 128);
    }

    public static ImportPsdMidiumAudioPacket decode(FriendlyByteBuf buf) {
        return new ImportPsdMidiumAudioPacket(buf.readUtf(64), buf.readUtf(128));
    }

    public static void handle(ImportPsdMidiumAudioPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            // ★ handle 语义按 Fabric 1.20.4：导入成功必须补发音频库同步包（客户端右列「已导入」
            //   就是从它来的），UI 结果统一走 noteUiResult（【10-01】）。
            String problem = EscalatorSpeedManager.importAudioToStore(level, pkt.category, pkt.name);
            if (problem == null) {
                SmoothLift.noteUiResult(player, true);
                EscalatorSpeedManager.sendAudioSyncTo(player, level);
            } else {
                SmoothLift.noteUiResult(player, false);
            }
        });
        context.setPacketHandled(true);
    }
}
