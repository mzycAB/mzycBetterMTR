package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.RepairNotice;

import java.util.function.Supplier;

/**
 * 【1.37 / Forge 移植】客户端 -&gt; 服务端：进世界后主动问一次「要不要弹补丁提示」（无字段）。
 *
 * <p>为什么除了 JOIN 推送还要这一只请求包：JOIN 时序下服务端推送可能早于客户端频道就绪
 * （本项目的音频同步就踩过这个坑，见 {@code RequestSyncPacket}）。
 */
public class MbmRepairNoticeRequestPacket {
    public MbmRepairNoticeRequestPacket() {
    }

    public static void encode(MbmRepairNoticeRequestPacket pkt, FriendlyByteBuf buf) {
    }

    public static MbmRepairNoticeRequestPacket decode(FriendlyByteBuf buf) {
        return new MbmRepairNoticeRequestPacket();
    }

    public static void handle(MbmRepairNoticeRequestPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            // ★ 判定只有一份（{@link RepairNotice#evaluate}），发包由本类负责：
            //   Forge 的 RepairNotice 不含任何网络代码（五个工程共用的只有判定口径）。
            RepairNotice.Status status = RepairNotice.evaluate(player.serverLevel());
            ServerPlayer target = player;
            Packets.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
                    new MbmRepairNoticePacket(status.show(), status.checkEnabled(),
                            status.hasPatch(), status.hasZip()));
        });
        context.setPacketHandled(true);
    }
}
