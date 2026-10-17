package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.client.PsdNearestRequestHandler;

import java.util.function.Supplier;

/**
 * 【10-05】服务端 -> 客户端：请客户端算一次「离玩家最近的那一串屏蔽门」（空包）。
 *
 * <p>客户端算完立刻回 {@link PsdNearestReplyPacket}（found + runKey）；服务端那一侧的
 * 待办队列（{@code SmoothLift.PENDING_NEAREST}）负责真正落地。
 *
 * <p>★★【1.34 服务器兼容修复】本类**不得**出现任何 {@code net.minecraft.client.*} 类型 ——
 * {@code Packets.register()} 在 mod 构造期就会 {@code ldc class PsdNearestRequestPacket}，
 * 类一被加载就会被验证全部方法（含 lambda 合成方法），方法体里那几条
 * {@code Minecraft.getInstance()} / {@code Minecraft.f_91074_} 会让验证去解析客户端类
 * ⇒ Forge 的 {@code RuntimeDistCleaner} 在专用服务器上直接拒绝 ⇒ **开服即崩**
 * （实测日志：{@code at smooth.lift.network.Packets.register(Packets.java:293)}）。
 * <p>客户端代码已挪到 {@link smooth.lift.client.PsdNearestRequestHandler}。
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
        // 【1.34 修复】客户端动作交给 smooth.lift.client.PsdNearestRequestHandler；
        //   专用服务器上 DistExecutor 的 supplier 不会被求值 ⇒ 本类永不触碰客户端类。
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> PsdNearestRequestHandler::handle));
        context.setPacketHandled(true);
    }
}
