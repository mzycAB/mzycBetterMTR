package smooth.lift.compat;

import smooth.lift.compat.Event;

/** Forge 侧垫片：AFTER_ENTITIES 挂到 {@code RenderLevelStageEvent}（AFTER_ENTITIES 对应 AFTER_PARTICLES 之前）。 */
public final class WorldRenderEvents {

    public static final Event<AfterEntities> AFTER_ENTITIES = new Event<>();

    private WorldRenderEvents() {
    }

    public interface AfterEntities {
        void onEnd(WorldRenderContext context);
    }
}