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
 * 【1.16】客户端 -> 服务端：石斧界面里的「无障碍提示音」开关。
 * 开关极小，服务端只回发「提示音开关表」小包（{@link HelpSyncPacket}）。
 */
public class SetHelpPacket {
    private final BlockPos pos;
    private final boolean enabled;

    public SetHelpPacket(BlockPos pos, boolean enabled) {
        this.pos = pos;
        this.enabled = enabled;
    }

    public static void encode(SetHelpPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.enabled);
    }

    public static SetHelpPacket decode(FriendlyByteBuf buf) {
        return new SetHelpPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(SetHelpPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            EscalatorSpeedManager.setHelp(level, pkt.pos, pkt.enabled);
            boolean applied = EscalatorSpeedManager.isHelpEnabled(level, pkt.pos);
            player.displayClientMessage(Component.literal(
                    "这条扶梯的无障碍提示音已" + (applied ? "开启" : "关闭")), true);
            EscalatorSpeedManager.syncHelpToAll(player.server);
        });
        context.setPacketHandled(true);
    }
}
