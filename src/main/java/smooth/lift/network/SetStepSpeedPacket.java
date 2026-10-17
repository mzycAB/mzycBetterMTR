package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.function.Supplier;

/** 客户端 -> 服务端：请求把整条扶梯链的阶梯动画速度设置为指定值（石斧）。 */
public class SetStepSpeedPacket {
    private final BlockPos pos;
    private final double step;

    public SetStepSpeedPacket(BlockPos pos, double step) {
        this.pos = pos;
        this.step = step;
    }

    public static void encode(SetStepSpeedPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeDouble(pkt.step);
    }

    public static SetStepSpeedPacket decode(FriendlyByteBuf buf) {
        return new SetStepSpeedPacket(buf.readBlockPos(), buf.readDouble());
    }

    public static void handle(SetStepSpeedPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            int count = EscalatorSpeedManager.setStepSpeed(level, pkt.pos, pkt.step);
            if (count > 0) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "已把这条扶梯（" + count + " 格）的阶梯动画速度设为 "
                                        + EscalatorSpeedData.format(pkt.step) + " 格/秒"),
                        true
                );
                EscalatorSpeedManager.syncToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}