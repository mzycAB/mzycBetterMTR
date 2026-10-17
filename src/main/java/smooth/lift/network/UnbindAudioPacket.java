package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/** 客户端 -> 服务端：解绑某条扶梯的音频（之后该扶梯静音）。 */
public class UnbindAudioPacket {
    private final BlockPos pos;

    public UnbindAudioPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(UnbindAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
    }

    public static UnbindAudioPacket decode(FriendlyByteBuf buf) {
        return new UnbindAudioPacket(buf.readBlockPos());
    }

    public static void handle(UnbindAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            if (EscalatorSpeedManager.unbindAudio(level, pkt.pos)) {
                player.displayClientMessage(Component.literal("已解除这条扶梯的自定义声音（不再播放）"), true);
                EscalatorSpeedManager.syncAudioToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}
