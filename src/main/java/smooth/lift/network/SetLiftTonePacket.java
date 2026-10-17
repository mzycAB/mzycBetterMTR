package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【1.45】客户端 -> 服务端：把某条直梯的某一项提示音设为「音频库里的某段 / 默认素材 / 不播」。
 *
 * <p>直梯没有稳定 ID，所以客户端在右键楼层轨道时已经把「竖井列 key」算好发过来。
 * buf 顺序：{@code key(long), which(utf: up|down|chime), audioId(utf)}。
 */
public class SetLiftTonePacket {
    private final long key;
    private final String which;
    private final String audioId;

    public SetLiftTonePacket(long key, String which, String audioId) {
        this.key = key;
        this.which = which;
        this.audioId = audioId;
    }

    public static void encode(SetLiftTonePacket pkt, FriendlyByteBuf buf) {
        buf.writeLong(pkt.key);
        buf.writeUtf(pkt.which, 32);
        buf.writeUtf(pkt.audioId, 128);
    }

    public static SetLiftTonePacket decode(FriendlyByteBuf buf) {
        return new SetLiftTonePacket(buf.readLong(), buf.readUtf(32), buf.readUtf(128));
    }

    public static void handle(SetLiftTonePacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            if (EscalatorSpeedManager.setServerLiftTone(level, pkt.key, pkt.which, pkt.audioId)) {
                player.displayClientMessage(Component.literal(
                        "已把这条直梯的" + SmoothLift.liftToneLabel(pkt.which, pkt.audioId) + "设为"
                                + (EscalatorSpeedData.LIFT_TONE_OFF.equals(pkt.audioId) ? "「不播」"
                                : EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(pkt.audioId) ? "「默认素材」"
                                : "「" + SmoothLift.truncateForMsg(pkt.audioId, 20) + "」")), true);
                EscalatorSpeedManager.syncLiftToneToAll(player.server);
            } else {
                player.displayClientMessage(Component.literal(
                        "设置失败：音频不存在（先在提示音选择界面里导入 .ogg）"), true);
            }
        });
        context.setPacketHandled(true);
    }
}
