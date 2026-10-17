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
 * 【1.48】客户端 -> 服务端：石斧 UI 单项列表「音量」= {@code /lifthelploud up|down|door <音量>}
 * （该项自己的音量；没单独调过的项跟随共用默认）。
 *
 * <p>buf 顺序：{@code which(utf), volume(VarInt)}。
 */
public class SetLiftToneVolumePacket {
    private final String which;
    private final int volume;

    public SetLiftToneVolumePacket(String which, int volume) {
        this.which = which;
        this.volume = volume;
    }

    public static void encode(SetLiftToneVolumePacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.which, 32);
        buf.writeVarInt(pkt.volume);
    }

    public static SetLiftToneVolumePacket decode(FriendlyByteBuf buf) {
        return new SetLiftToneVolumePacket(buf.readUtf(32), buf.readVarInt());
    }

    public static void handle(SetLiftToneVolumePacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            EscalatorSpeedManager.setDefaultLiftToneVolume(level, pkt.which, pkt.volume);
            int applied = EscalatorSpeedManager.getLiftToneVolume(level, pkt.which);
            player.displayClientMessage(Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(pkt.which) + "音量已设为 "
                            + applied + "（100 = 原始音量）"), true);
            EscalatorSpeedManager.syncLiftChimeToAll(player.server);
        });
        context.setPacketHandled(true);
    }
}
