package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【1.41】客户端 -> 服务端：清掉这条扶梯**某一头**的提示音音乐单独设置（回到维度默认）。
 *
 * <p>buf 顺序：{@code pos, in} —— {@code in = true} 表示「进入扶梯（上客端）」那一头。
 */
public class UnbindHelpAudioPacket {
    private final BlockPos pos;
    private final boolean in;

    public UnbindHelpAudioPacket(BlockPos pos, boolean in) {
        this.pos = pos;
        this.in = in;
    }

    public static void encode(UnbindHelpAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.in);
    }

    public static UnbindHelpAudioPacket decode(FriendlyByteBuf buf) {
        return new UnbindHelpAudioPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(UnbindHelpAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            if (EscalatorSpeedManager.unbindHelpAudio(level, pkt.pos, pkt.in)) {
                player.displayClientMessage(Component.literal(
                        "这条扶梯" + SmoothLift.helpEndLabel(pkt.in) + "的无障碍提示音已改回跟随默认"), true);
                EscalatorSpeedManager.syncHelpAudioToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}
