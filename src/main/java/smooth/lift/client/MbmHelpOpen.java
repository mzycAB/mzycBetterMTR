package smooth.lift.client;

import net.minecraft.client.Minecraft;

/**
 * 【1.34 服务器兼容修复】「打开预设选择界面」的**客户端专属动作**。
 *
 * <h2>为什么必须单独放一个类</h2>
 * 原来这段代码写在 {@code smooth.lift.network.MbmHelpOpenPacket.handle} 的
 * {@code enqueueWork(() -> …)} 里。而 {@code Packets.register()} 会**在 mod 构造期**
 * {@code messageBuilder(MbmHelpOpenPacket.class, …)}（字节码就是 {@code ldc class …}）
 * ⇒ 那个类**必然被加载并被 HotSpot 验证全部方法**（含 lambda 合成方法）
 * ⇒ 方法体里那几条 {@code invokestatic net/minecraft/client/Minecraft.getInstance()}
 * 让验证阶段去解析客户端类 ⇒ **Forge 的 RuntimeDistCleaner 在专用服务器上直接拒绝**：
 * <pre>
 *   Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER
 *   → java.lang.BootstrapMethodError → DeferredWorkQueue 失败 → 服务器启动即崩
 * </pre>
 * ★ 关键：**lambda 体会被编译成宿主类的私有合成方法**，所以「把客户端代码写进 lambda」
 * **不等于**隔离 —— 客户端类型仍然留在发包那个类里。
 * ⇒ 唯一的正解：把客户端代码挪到**另一个类**（本类），发包类里只留一个对它的方法引用
 * （{@code MbmHelpOpen::open}）。方法引用在执行时才解析 owner，专用服务器上
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, …)} 的 supplier 根本不会被求值。
 *
 * <p>判据（可直接复跑）：{@code smooth/lift/network/MbmHelpOpenPacket.class} 的字节码里
 * **不得出现** {@code net/minecraft/client}；出现即复发。见 {@code _tools/check-server-dist.py}。
 */
public final class MbmHelpOpen {

    private MbmHelpOpen() {
    }

    /** 打开「预设选择」界面（只会在客户端被调用）。 */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new MbmHelpScreen()));
    }
}
