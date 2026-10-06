package smooth.lift.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

import smooth.lift.client.PsdDoorTracker;

/**
 * 【10-05】服务端 -> 客户端：请客户端算一次「离玩家最近的那一串屏蔽门」（空包）。
 *
 * <p>客户端算完立刻回 {@link PsdNearestReplyPacket}（found + runKey）；服务端那一侧的
 * 待办队列（{@code SmoothLift.PENDING_NEAREST}）负责真正落地。
 */
public class PsdNearestRequestPacket {

    public PsdNearestRequestPacket() {
    }

    public static void encode(PsdNearestRequestPacket pkt, FriendlyByteBuf buf) {
        // 空包，无负载
    }

    public static PsdNearestRequestPacket decode(FriendlyByteBuf buf) {
        return new PsdNearestRequestPacket();
    }

    public static void handle(PsdNearestRequestPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                net.minecraft.client.player.LocalPlayer player = mc.player;
                long runKey = player == null
                        ? PsdDoorTracker.RUN_KEY_NONE
                        : PsdDoorTracker.nearestRunKeyTo(player.position(), player.getLookAngle());
                boolean found = runKey != PsdDoorTracker.RUN_KEY_NONE;
                Packets.CHANNEL.sendToServer(new PsdNearestReplyPacket(found, found ? runKey : 0L));
            });
        });
        context.setPacketHandled(true);
    }
}
