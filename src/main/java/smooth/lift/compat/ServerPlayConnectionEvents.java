package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Forge 侧垫片：挂到 {@code PlayerEvent.PlayerLoggedInEvent}。
 *
 * <p>Fabric 的 JOIN 回调第一个参数是 {@code ServerGamePacketListenerImpl}，模组里只用了
 * {@code handler.player}，所以这里把 {@code ServerPlayer.connection} 原样传进去。
 */
public final class ServerPlayConnectionEvents {

    public static final Event<Join> JOIN = new Event<>();

    private ServerPlayConnectionEvents() {
    }

    public interface Join {
        void onPlayReady(ServerGamePacketListenerImpl handler, Object sender, MinecraftServer server);
    }

    /** 兼容用：部分代码把 sender 当 {@code PacketSender} 用，这里给一个空实现占位类型。 */
    public interface PacketSender {
        void sendPacket(Connection connection, Object packet);
    }
}