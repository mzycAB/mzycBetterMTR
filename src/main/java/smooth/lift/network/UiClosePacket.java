package smooth.lift.network;

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
 * 【10-01 / Forge 移植】客户端 -> 服务端:退出设置界面时上报「本次界面会话有没有失败」;
 * 服务端回一条「UI执行成功 / UI执行失败」(与它自己的失败记录取「与」)。
 */
public class UiClosePacket {
    private final boolean clientOk;

    public UiClosePacket(boolean clientOk) {
        this.clientOk = clientOk;
    }

    public static void encode(UiClosePacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.clientOk);
    }

    public static UiClosePacket decode(FriendlyByteBuf buf) {
        return new UiClosePacket(buf.readBoolean());
    }

    public static void handle(UiClosePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            ServerLevel level = player.serverLevel();
            Boolean serverOk = SmoothLift.consumeUiSessionOk(player);
            boolean ok = pkt.clientOk && (serverOk == null || serverOk);
            player.displayClientMessage(Component.literal(
                    ok ? "UI执行成功" : "UI执行失败"), false);
            context.setPacketHandled(true);
        });
        context.setPacketHandled(true);
    }
}
