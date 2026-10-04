package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.client.Minecraft;

/** Forge 侧垫片：挂到 {@code TickEvent.ClientTickEvent}（Phase.END）。 */
public final class ClientTickEvents {

    public static final Event<EndTick> END_CLIENT_TICK = new Event<>();

    private ClientTickEvents() {
    }

    public interface EndTick {
        void onEndTick(Minecraft client);
    }
}