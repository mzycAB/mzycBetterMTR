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

/**
 * 客户端 -> 服务端：石斧界面按确认键时，一次性应用「扶梯速度 + 阶梯速度」的改动。
 * <ul>
 *   <li>setRun=true  -> 设运行速度，并让阶梯速度跟随运行速度；</li>
 *   <li>setStep=true -> 只设阶梯速度，运行速度不动。</li>
 * </ul>
 * 两个改动合成一个包发送，顺序固定、不会出现「先设阶梯又被清掉」的竞态。
 */
public class ApplyChainPacket {
    private final BlockPos pos;
    private final boolean setRun;
    private final double run;
    private final boolean setStep;
    private final double step;

    public ApplyChainPacket(BlockPos pos, boolean setRun, double run, boolean setStep, double step) {
        this.pos = pos;
        this.setRun = setRun;
        this.run = run;
        this.setStep = setStep;
        this.step = step;
    }

    public static void encode(ApplyChainPacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.pos);
        buf.writeBoolean(pkt.setRun);
        buf.writeDouble(pkt.run);
        buf.writeBoolean(pkt.setStep);
        buf.writeDouble(pkt.step);
    }

    public static ApplyChainPacket decode(FriendlyByteBuf buf) {
        return new ApplyChainPacket(buf.readBlockPos(), buf.readBoolean(), buf.readDouble(),
                buf.readBoolean(), buf.readDouble());
    }

    public static void handle(ApplyChainPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            int count = EscalatorSpeedManager.applyChain(level, pkt.pos, pkt.setRun, pkt.run, pkt.setStep, pkt.step);
            if (count > 0) {
                StringBuilder message = new StringBuilder("已更新这条扶梯（" + count + " 格）");
                if (pkt.setRun) {
                    message.append("：扶梯速度 ").append(EscalatorSpeedData.format(pkt.run));
                    if (!pkt.setStep) {
                        message.append("（阶梯速度跟随）");
                    }
                }
                if (pkt.setStep) {
                    message.append(pkt.setRun ? "，" : "：")
                            .append("阶梯速度 ").append(EscalatorSpeedData.format(pkt.step));
                }
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(message.toString()),
                        true
                );
                EscalatorSpeedManager.syncToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}