package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.client.MbmHelpOpen;

import java.util.function.Supplier;

/**
 * 【1.53】服务端 -> 客户端：请求打开「预设选择」界面（无负载）。
 *
 * <p>★★【1.34 服务器兼容修复】本类**不得**出现任何 {@code net.minecraft.client.*} 类型。
 * 原因：{@code Packets.register()} 在 **mod 构造期**就会 {@code ldc class MbmHelpOpenPacket}
 * ⇒ 本类被加载并被 HotSpot 验证**全部方法**（含 lambda 合成方法）⇒ 一旦方法体里出现
 * {@code invokestatic net/minecraft/client/Minecraft.getInstance()}（返回类型是客户端类），
 * 验证阶段就要解析客户端类 ⇒ Forge 的 {@code RuntimeDistCleaner} 拒绝
 * （{@code Attempted to load class … for invalid dist DEDICATED_SERVER}）⇒ 服务器启动即崩。
 * <p>★ 把客户端代码「写进 enqueueWork 的 lambda」**不是**隔离（lambda 体是本类的私有合成方法）；
 * 必须挪到另一个类（{@link smooth.lift.client.MbmHelpOpen}），这里只留一个方法引用。
 * 判据：本类字节码里出现 {@code net/minecraft/client} 即复发（{@code _tools/check-server-dist.py}）。
 */
public class MbmHelpOpenPacket {

    public MbmHelpOpenPacket() {
    
    }

    public static void encode(MbmHelpOpenPacket pkt, FriendlyByteBuf buf) {
        // 空包，无负载
    }

    public static MbmHelpOpenPacket decode(FriendlyByteBuf buf) {
        return new MbmHelpOpenPacket();
    }

    public static void handle(MbmHelpOpenPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        // 【1.34 修复】客户端动作交给 smooth.lift.client.MbmHelpOpen；
        //   专用服务器上 DistExecutor 的 supplier 不会被求值 ⇒ 本类永不触碰客户端类。
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> MbmHelpOpen::open));
        context.setPacketHandled(true);
    }
}
