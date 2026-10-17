package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.SmoothLift;

import java.util.function.Supplier;

/**
 * 【补丁】客户端 -&gt; 服务端：把补丁文件夹（MBM_Repair）里那个 zip 里的音频导入存档（无字段）。
 *
 * <p>★ 2026-10-15 用户点名：那个 zip 名字不限、也不需要密码；文件夹里**正好一个** .zip 才导入，
 * 多于一个则服务端什么都不做、也不给提示（见 RepairStore.IMPORT_SILENT）。
 *
 * <p>Forge1.20.1 是一个包一个类（没有 Fabric 那种「频道 id + lambda」的形状），
 * 所以这里只做转发 —— 真正的逻辑在 {@code SmoothLift.serverImportRepair}，
 * **五个工程共用那一份实现**（免得某个平台上导入变成旧版本）。
 */
public class MbmRepairImportPacket {
    public MbmRepairImportPacket() {
    }

    public static void encode(MbmRepairImportPacket pkt, FriendlyByteBuf buf) {
    }

    public static MbmRepairImportPacket decode(FriendlyByteBuf buf) {
        return new MbmRepairImportPacket();
    }

    public static void handle(MbmRepairImportPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            SmoothLift.serverImportRepair(player);
        });
        context.setPacketHandled(true);
    }
}
