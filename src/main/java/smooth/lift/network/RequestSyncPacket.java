package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/** 客户端 -> 服务端：客户端完全进世界后主动请求全量同步（速度 + 音频 + 音量）。 */
public class RequestSyncPacket {
    public RequestSyncPacket() {
    }

    public static void encode(RequestSyncPacket pkt, FriendlyByteBuf buf) {
    }

    public static RequestSyncPacket decode(FriendlyByteBuf buf) {
        return new RequestSyncPacket();
    }

    public static void handle(RequestSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            EscalatorSpeedManager.syncToAll(player.server);
            // 【1.7/1.9】音频与音量是独立的小/大包，这里一并回发，
            // 否则刚进世界时客户端只有速度、音频绑定为空（表现为"明明绑着却静音"）。
            EscalatorSpeedManager.sendAudioSyncTo(player, player.serverLevel());
            EscalatorSpeedManager.sendVolumeSyncTo(player, player.serverLevel());
            // 【1.16/1.18/1.24】提示音开关 / 提示音音量 / 两个可闻范围 也都要补发，
            // 否则刚进世界时客户端会按默认值响（开了/关了都不生效）。
            EscalatorSpeedManager.sendHelpSyncTo(player, player.serverLevel());
            EscalatorSpeedManager.sendHelpVolumeSyncTo(player, player.serverLevel());
            EscalatorSpeedManager.sendRoundSyncTo(player, player.serverLevel());
            EscalatorSpeedManager.sendHelpRoundSyncTo(player, player.serverLevel());
            // 【1.31/1.41】提示音速率与提示音音乐是两条独立的小包，同样要补发 ——
            //   否则刚进世界时客户端会按默认值播（用 /futihelpspeed、/futihelpmusic 改过的设置看不到）。
            EscalatorSpeedManager.sendHelpSpeedSyncTo(player, player.serverLevel());
            EscalatorSpeedManager.sendHelpAudioSyncTo(player, player.serverLevel());
            // 【1.42/1.45】直梯那两套要**每个维度都发一遍**（设置是按维度存的），
            //   不像上面那些只需要当前维度 —— 与 Fabric 版 REQUEST_SYNC 接收器一致。
            for (net.minecraft.server.level.ServerLevel level : player.server.getAllLevels()) {
                EscalatorSpeedManager.sendLiftChimeSyncTo(player, level);
            }
            for (net.minecraft.server.level.ServerLevel level : player.server.getAllLevels()) {
                EscalatorSpeedManager.sendLiftToneSyncTo(player, level);
            }
        });
        context.setPacketHandled(true);
    }
}
