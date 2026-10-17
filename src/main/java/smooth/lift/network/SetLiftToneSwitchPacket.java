package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/**
 * 【1.46】客户端 -> 服务端：石斧 UI 里的「三类提示音开关」——改的是**维度默认子开关**
 * （总开关 /lifthelp 之外各自还能再关一层）。
 *
 * <p>buf 顺序：{@code which(utf), enabled(boolean)}。
 */
public class SetLiftToneSwitchPacket {
    private final String which;
    private final boolean enabled;

    public SetLiftToneSwitchPacket(String which, boolean enabled) {
        this.which = which;
        this.enabled = enabled;
    }

    public static void encode(SetLiftToneSwitchPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.which, 32);
        buf.writeBoolean(pkt.enabled);
    }

    public static SetLiftToneSwitchPacket decode(FriendlyByteBuf buf) {
        return new SetLiftToneSwitchPacket(buf.readUtf(32), buf.readBoolean());
    }

    public static void handle(SetLiftToneSwitchPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            if (!"up".equals(pkt.which) && !"down".equals(pkt.which) && !"chime".equals(pkt.which)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            EscalatorSpeedManager.setDefaultLiftToneEnabled(level, pkt.which, pkt.enabled);
            player.displayClientMessage(Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(pkt.which) + "已"
                            + (pkt.enabled ? "开启" : "关闭")), true);
            EscalatorSpeedManager.syncLiftChimeToAll(player.server);
        });
        context.setPacketHandled(true);
    }
}
