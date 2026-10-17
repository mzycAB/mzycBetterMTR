package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【补丁】客户端 -&gt; 服务端：删除已经融入存档的补丁素材（无字段）。
 *
 * <p>Forge1.20.1 是一个包一个类（没有 Fabric 那种「频道 id + lambda」的形状），
 * 所以这里只做转发 —— 真正的逻辑在 {@code SmoothLift.serverDeleteRepair}，
 * **五个工程共用那一份实现**（免得某个平台上导入变成旧版本）。
 */
public class MbmRepairDeletePacket {
    public MbmRepairDeletePacket() {
    }

    public static void encode(MbmRepairDeletePacket pkt, FriendlyByteBuf buf) {
    }

    public static MbmRepairDeletePacket decode(FriendlyByteBuf buf) {
        return new MbmRepairDeletePacket();
    }

    public static void handle(MbmRepairDeletePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            SmoothLift.serverDeleteRepair(player);
        });
        context.setPacketHandled(true);
    }
}
