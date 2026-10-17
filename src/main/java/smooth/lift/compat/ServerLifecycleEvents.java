package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.server.MinecraftServer;

/** Forge 侧垫片：挂到 {@code ServerStartedEvent} / {@code ServerStoppedEvent}。 */
public final class ServerLifecycleEvents {

    public static final Event<ServerStarted> SERVER_STARTED = new Event<>();
    /** 【10-03 五改】服务端停了要通知「关门后等待发车」清掉旧实例（见 {@code PsdDepartHold}）。 */
    public static final Event<ServerStopped> SERVER_STOPPED = new Event<>();

    private ServerLifecycleEvents() {
    }

    public interface ServerStarted {
        void onServerStarted(MinecraftServer server);
    }

    public interface ServerStopped {
        void onServerStopped(MinecraftServer server);
    }
}
