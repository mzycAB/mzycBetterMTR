package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.client.RepairNoticeClient;

import java.util.function.Supplier;

/**
 * 【1.37 / Forge 移植】服务端 -&gt; 客户端：**要不要弹「未导入补丁」那个窗**
 * （字段：show · checkEnabled · hasPatch · hasZip，四个布尔）。
 *
 * <p>判定在服务端（存档文件夹在服务端那台机器上，见 {@code RepairSettings}）：
 * show=true ⇒ 客户端在进世界之后弹窗；show=false ⇒ 客户端**什么都不做**。
 * 后面三个布尔只是给日志 / 排障用的，客户端**不用**它们做判定。
 *
 * <p>Fabric 那边这一只是「频道 + 手工读 4 个布尔」；Forge 一个包一个类，
 * 所以在这里把 4 个布尔显式写成字段 —— 语义一致，且客户端那边只认 show。
 */
public class MbmRepairNoticePacket {
    private final boolean show;
    private final boolean checkEnabled;
    private final boolean hasPatch;
    private final boolean hasZip;

    public MbmRepairNoticePacket(boolean show, boolean checkEnabled, boolean hasPatch, boolean hasZip) {
        this.show = show;
        this.checkEnabled = checkEnabled;
        this.hasPatch = hasPatch;
        this.hasZip = hasZip;
    }

    public static void encode(MbmRepairNoticePacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.show);
        buf.writeBoolean(pkt.checkEnabled);
        buf.writeBoolean(pkt.hasPatch);
        buf.writeBoolean(pkt.hasZip);
    }

    public static MbmRepairNoticePacket decode(FriendlyByteBuf buf) {
        return new MbmRepairNoticePacket(buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(MbmRepairNoticePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> RepairNoticeClient.onNotice(pkt.show, pkt.checkEnabled,
                pkt.hasPatch, pkt.hasZip));
        context.setPacketHandled(true);
    }
}
