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

/** 客户端 -> 服务端：请求把整条扶梯链的速度设置为指定值。 */
public class SetSpeedPacket {
    private final BlockPos pos;
    private final double speed;

    public SetSpeedPacket(BlockPos pos, double speed) {
        this.pos = pos;
        this.speed = speed;
    }

    public static void encode(SetSpeedPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeDouble(pkt.speed);
    }

    public static SetSpeedPacket decode(FriendlyByteBuf buf) {
        return new SetSpeedPacket(buf.readBlockPos(), buf.readDouble());
    }

    public static void handle(SetSpeedPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            int count = EscalatorSpeedManager.setSpeed(level, pkt.pos, pkt.speed);
            if (count > 0) {
                double applied = EscalatorSpeedManager.getSpeed(level, pkt.pos);
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "已设置 " + count + " 个扶梯方块的速度为 " + EscalatorSpeedData.format(applied) + " 格/秒"),
                        true
                );
                EscalatorSpeedManager.syncToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}
