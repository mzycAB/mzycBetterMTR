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
 * 【1.9】客户端 -> 服务端：设置某条扶梯的声音音量（1~1000，100 = 原始音量，1000 = 10× 放大）。
 * 音量很小，服务端只回发「音量表」小包（{@link VolumeSyncPacket}），不必重发整个音频库。
 */
public class SetVolumePacket {
    private final BlockPos pos;
    private final int volume;

    public SetVolumePacket(BlockPos pos, int volume) {
        this.pos = pos;
        this.volume = volume;
    }

    public static void encode(SetVolumePacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeVarInt(pkt.volume);
    }

    public static SetVolumePacket decode(FriendlyByteBuf buf) {
        return new SetVolumePacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(SetVolumePacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            int applied = EscalatorSpeedManager.setVolume(level, pkt.pos, pkt.volume);
            player.displayClientMessage(Component.literal("这条扶梯的音量已设为 " + applied + "%"), true);
            EscalatorSpeedManager.syncVolumeToAll(player.server);
        });
        context.setPacketHandled(true);
    }
}
