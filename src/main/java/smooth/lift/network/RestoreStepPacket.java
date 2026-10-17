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

/** 客户端 -> 服务端：请求清除整条扶梯链的单独阶梯动画设置，重新跟随维度默认（石斧）。 */
public class RestoreStepPacket {
    private final BlockPos pos;

    public RestoreStepPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(RestoreStepPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
    }

    public static RestoreStepPacket decode(FriendlyByteBuf buf) {
        return new RestoreStepPacket(buf.readBlockPos());
    }

    public static void handle(RestoreStepPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            int count = EscalatorSpeedManager.clearStepSpeed(level, pkt.pos);
            if (count > 0) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "已清除这条扶梯（" + count + " 格）的单独阶梯动画设置，改为跟随维度默认（运行速度不变）"),
                        true
                );
                EscalatorSpeedManager.syncToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}