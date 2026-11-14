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
 * 【1.39.11201】客户端 -> 服务端：石斧直梯 UI 里的「电梯 BGM」选用（{@code /liftbgm <名字>}）。
 *
 * <p>buf 顺序：{@code audioId(utf128)}。
 *
 * <p>★ 成败只记进「本次界面会话」（{@link SmoothLift#noteUiResult}），退出 UI 时统一回一条
 * 「UI执行成功 / UI执行失败」—— 不加任何一句多余的小字（用户点名）。
 */
public class SetLiftBgmPacket {
    private final String audioId;

    public SetLiftBgmPacket(String audioId) {
        this.audioId = audioId;
    }

    public static void encode(SetLiftBgmPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.audioId, 128);
    }

    public static SetLiftBgmPacket decode(FriendlyByteBuf buf) {
        return new SetLiftBgmPacket(buf.readUtf(128));
    }

    public static void handle(SetLiftBgmPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            if (EscalatorSpeedManager.setLiftBgmAudio(level, pkt.audioId)) {
                SmoothLift.noteUiResult(player, true);
                EscalatorSpeedManager.syncLiftBgmToAll(player.server);
            } else {
                SmoothLift.noteUiResult(player, false);
            }
        });
        context.setPacketHandled(true);
    }
}
