package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.RepairNotice;

import java.util.function.Supplier;

/**
 * 【1.37 / Forge 移植】客户端 -&gt; 服务端：玩家在弹窗里点了「**不再显示**」（无字段）。
 *
 * <p>服务端收到后把存档里的 {@code MBM_settings.txt} 写成 {@code Repair_Check=0}
 * （见 {@code RepairSettings#setRepairCheck}）⇒ 之后每次进这个存档都不再检测、不再弹。
 *
 * <p>★ 另外两个按钮**不发包**：「打开网站」纯客户端（开浏览器）、
 * 「确定」也只是本地关窗 —— 用户点名「确定…不更改 Repair_Check 的值，下次启动游戏还弹窗」。
 */
public class MbmRepairDismissPacket {
    public MbmRepairDismissPacket() {
    }

    public static void encode(MbmRepairDismissPacket pkt, FriendlyByteBuf buf) {
    }

    public static MbmRepairDismissPacket decode(FriendlyByteBuf buf) {
        return new MbmRepairDismissPacket();
    }

    public static void handle(MbmRepairDismissPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
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
            // 失败只进日志（下一次进世界还会照常弹，比弹一个假的「已不再提示」诚实）。
            RepairNotice.dismiss(player);
        });
        context.setPacketHandled(true);
    }
}
