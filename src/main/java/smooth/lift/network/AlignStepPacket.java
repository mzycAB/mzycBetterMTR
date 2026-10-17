package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.function.Supplier;

/** 客户端 -> 服务端：请求把整条扶梯链的阶梯动画对齐到它自己的运行速度（石斧）。 */
public class AlignStepPacket {
    private final BlockPos pos;

    public AlignStepPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(AlignStepPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
    }

    public static AlignStepPacket decode(FriendlyByteBuf buf) {
        return new AlignStepPacket(buf.readBlockPos());
    }

    public static void handle(AlignStepPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            int count = EscalatorSpeedManager.alignStepToRunning(level, pkt.pos);
            if (count > 0) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "已把这条扶梯（" + count + " 格）的阶梯动画对齐到运行速度"),
                        true
                );
                EscalatorSpeedManager.syncToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}