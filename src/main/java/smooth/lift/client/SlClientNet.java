package smooth.lift.client;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import smooth.lift.net.SLNet;

/**
 * ★★【1.35】{@code SLNet} 的**客户端派发端** —— 专用服务器绝不能碰的那一半。
 *
 * <h2>这个类为什么必须存在</h2>
 * 1.34 在 Forge1.20.1 上真实崩过一次（见 {@code MbmHelpOpenPacket} 的类注释）：
 * 专用服务器启动时 {@code SLNet.init()} 会在 mod 构造期执行
 * {@code messageBuilder(ToClient.class, …)}（字节码就是 {@code ldc class SLNet$ToClient}）
 * ⇒ HotSpot 会**验证该类的全部方法**，包括 lambda 合成方法；
 * 那个 lambda 体里原来写着 {@code Minecraft.getInstance()} ⇒ 验证阶段就要解析
 * {@code net/minecraft/client/Minecraft} ⇒ Forge 的 {@code RuntimeDistCleaner} 直接拒绝：
 * <pre>
 * Attempted to load class net/minecraft/client/Minecraft for invalid dist DEDICATED_SERVER
 * </pre>
 * ★ 关键教训：「把客户端代码写进 {@code enqueueWork} 的 lambda」**不是**隔离 ——
 * lambda 体会被编译成**宿主类的私有合成方法**，宿主类一样会被加载 / 验证。
 *
 * <h2>现在怎么分工</h2>
 * <ul>
 *   <li>{@link SLNet.ToClient#handle} 里只剩
 *       {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SlClientNet.deliver(...))}
 *       —— 本类只作为**方法引用**出现，专用服务器上那个 supplier 不会被求值 ⇒ 不会加载本类；</li>
 *   <li>真正 {@code Minecraft.getInstance()} + 回调派发只写在**本类**里 ⇒ 只有客户端会执行。</li>
 * </ul>
 * ★ 判据：{@code SLNet$ToClient.class} 的字节里**不许**出现 {@code net/minecraft/client}
 * （守卫脚本 {@code _tools/check-server-dist.py} 盯着这一条）。
 */
public final class SlClientNet {

    private SlClientNet() {
    }

    /**
     * 把一条服务端发来的原始载荷交给客户端注册的回调。
     *
     * <p>★ 只允许在**客户端主线程**调用（{@code enqueueWork} 已经保证了这一点）。
     *
     * @param channel 频道 id（与 {@code SLNet.registerClient} 注册时同一个）
     * @param data    原始载荷（{@code ToClient} 解出来的那段字节）
     */
    public static void deliver(ResourceLocation channel, byte[] data) {
        SLNet.ClientReceiver receiver = SLNet.clientReceiver(channel);
        if (receiver == null) {
            return;
        }
        receiver.receive(Minecraft.getInstance(), null,
                new FriendlyByteBuf(Unpooled.wrappedBuffer(data)), null);
    }
}
