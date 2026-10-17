package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.function.Supplier;

/**
 * 【1.18】客户端 -> 服务端：石斧界面里的「提示音音量」输入框。
 * 音量很小，服务端只回发「提示音音量表」小包（{@link HelpVolumeSyncPacket}）。
 */
public class SetHelpVolumePacket {
    private final BlockPos pos;
    private final int volume;

    public SetHelpVolumePacket(BlockPos pos, int volume) {
        this.pos = pos;
        this.volume = volume;
    }

    public static void encode(SetHelpVolumePacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeVarInt(pkt.volume);
    }

    public static SetHelpVolumePacket decode(FriendlyByteBuf buf) {
        return new SetHelpVolumePacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(SetHelpVolumePacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            if (!EscalatorUtil.isEscalator(level.getBlockState(pkt.pos))) {
                return;
            }
            int applied = EscalatorSpeedManager.setHelpVolume(level, pkt.pos, pkt.volume);
            player.displayClientMessage(Component.literal(
                    "这条扶梯的无障碍提示音音量已设为 " + applied + "%"), true);
            EscalatorSpeedManager.syncHelpVolumeToAll(player.server);
        });
        context.setPacketHandled(true);
    }
}
