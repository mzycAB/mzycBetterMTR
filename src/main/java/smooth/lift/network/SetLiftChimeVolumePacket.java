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
 * 【1.48】客户端 -> 服务端：石斧 UI 主界面「设置默认音量」= {@code /lifthelploud <音量>}
 * （共用默认，三项跟随）。
 *
 * <p>buf 顺序：{@code volume(VarInt)}。
 */
public class SetLiftChimeVolumePacket {
    private final int volume;

    public SetLiftChimeVolumePacket(int volume) {
        this.volume = volume;
    }

    public static void encode(SetLiftChimeVolumePacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.volume);
    }

    public static SetLiftChimeVolumePacket decode(FriendlyByteBuf buf) {
        return new SetLiftChimeVolumePacket(buf.readVarInt());
    }

    public static void handle(SetLiftChimeVolumePacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            EscalatorSpeedManager.setDefaultLiftHelpVolume(level, pkt.volume);
            int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
            player.displayClientMessage(Component.literal(
                    "本维度直梯提示音默认音量已设为 " + applied + "（100 = 原始音量）"), true);
            EscalatorSpeedManager.syncLiftChimeToAll(player.server);
        });
        context.setPacketHandled(true);
    }
}
