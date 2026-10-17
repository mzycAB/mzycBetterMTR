package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import smooth.lift.network.Packets;
import smooth.lift.network.PsdNearestReplyPacket;

/**
 * 【1.34 服务器兼容修复】「算一次离玩家最近的屏蔽门串」的**客户端专属动作**。
 *
 * <h2>为什么必须单独放一个类</h2>
 * 原来这段代码写在 {@code smooth.lift.network.PsdNearestRequestPacket.handle} 里，
 * 而 {@code Packets.register()} 在 **mod 构造期**就会 {@code ldc class PsdNearestRequestPacket}
 * ⇒ 该类被加载并被验证**全部方法**（含 lambda 合成方法）⇒ 方法体里的
 * {@code Minecraft.getInstance()} / {@code Minecraft.f_91074_}（玩家字段）让验证去解析
 * 客户端类 ⇒ Forge 的 {@code RuntimeDistCleaner} 在专用服务器上直接拒绝 ⇒ **开服即崩**。
 *
 * <p>★ 注意 lambda 体会编译成宿主类的私有合成方法，「写进 lambda」并不构成隔离；
 * 必须把客户端代码挪到**另一个类**（本类），发包类里只留一个方法引用。
 * 判据：{@code smooth/lift/network/PsdNearestRequestPacket.class} 里**不得出现**
 * {@code net/minecraft/client}（见 {@code _tools/check-server-dist.py}）。
 */
public final class PsdNearestRequestHandler {

    private PsdNearestRequestHandler() {
    }

    /** 算一次最近的门串并立刻回包（只会在客户端被调用）。 */
    public static void handle() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            LocalPlayer player = mc.player;
            long runKey = player == null
                    ? PsdDoorTracker.RUN_KEY_NONE
                    : PsdDoorTracker.nearestRunKeyTo(player.position(), player.getLookAngle());
            boolean found = runKey != PsdDoorTracker.RUN_KEY_NONE;
            Packets.CHANNEL.sendToServer(new PsdNearestReplyPacket(found, found ? runKey : 0L));
        });
    }
}
