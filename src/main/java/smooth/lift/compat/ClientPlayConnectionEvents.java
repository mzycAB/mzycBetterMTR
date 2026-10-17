package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.client.Minecraft;

/**
 * Forge 侧垫片：JOIN 挂 {@code ClientPlayerNetworkEvent.LoggedInEvent}，
 * DISCONNECT 挂 {@code ClientPlayerNetworkEvent.LoggedOutEvent}。
 */
public final class ClientPlayConnectionEvents {

    public static final Event<Join> JOIN = new Event<>();
    public static final Event<Disconnect> DISCONNECT = new Event<>();

    private ClientPlayConnectionEvents() {
    }

    public interface Join {
        void onPlayReady(Object handler, Object sender, Minecraft client);
    }

    public interface Disconnect {
        void onPlayDisconnect(Object handler, Minecraft client);
    }
}