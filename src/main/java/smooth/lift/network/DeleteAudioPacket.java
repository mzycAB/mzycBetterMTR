package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：从存档**某个分类**删除一段音频（同时解绑引用它的扶梯，并向所有玩家重发同步）。
 *
 * <p>【1.28 / Forge 移植】分类由发送的界面决定（每个界面只删自己的分类），buf 顺序：
 * {@code category(utf64) → audioId(utf128)}，与 Fabric 1.20.4 发送端 / 服务端接收器同序。
 */
public class DeleteAudioPacket {
    private final String category;
    private final String audioId;

    public DeleteAudioPacket(String category, String audioId) {
        this.category = category;
        this.audioId = audioId;
    }

    public static void encode(DeleteAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.category, 64);
        buf.writeUtf(pkt.audioId, 128);
    }

    public static DeleteAudioPacket decode(FriendlyByteBuf buf) {
        return new DeleteAudioPacket(buf.readUtf(64), buf.readUtf(128));
    }

    public static void handle(DeleteAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            // ★ handle 语义按 Fabric 1.20.4：UI 结果统一走 noteUiResult（【10-01】）。
            boolean uiOk = EscalatorSpeedManager.deleteAudio(level, pkt.category, pkt.audioId);
            SmoothLift.noteUiResult(player, uiOk);
            if (uiOk) {
                SmoothLift.noteUiResult(player, true);
                EscalatorSpeedManager.syncAudioToAll(player.server);
                // 【1.39】提示音那边也可能引用过这一段（共用同一个库），单独设置也要一起刷新
                EscalatorSpeedManager.syncHelpAudioToAll(player.server);
                // 【1.17】「到站播报」也可能正指着这一段 —— 不加这一步会留下一个指向空文件的引用
                if (EscalatorSpeedManager.clearPsdMidiumIfRemoved(player.server, pkt.audioId) > 0) {
                    SmoothLift.noteUiResult(player, true);
                }
                // 【1.21】「进站报站」同理：不一起清就会留下一个指向空文件的引用
                if (EscalatorSpeedManager.clearPsdArriveIfRemoved(player.server, pkt.audioId) > 0) {
                    SmoothLift.noteUiResult(player, true);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}
