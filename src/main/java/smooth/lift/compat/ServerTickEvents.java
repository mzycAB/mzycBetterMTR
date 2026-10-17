package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.server.MinecraftServer;

/** Forge 侧垫片：挂到 {@code TickEvent.ServerTickEvent}（Phase.END）。 */
public final class ServerTickEvents {

    public static final Event<EndTick> END_SERVER_TICK = new Event<>();

    private ServerTickEvents() {
    }

    public interface EndTick {
        void onEndTick(MinecraftServer server);
    }
}