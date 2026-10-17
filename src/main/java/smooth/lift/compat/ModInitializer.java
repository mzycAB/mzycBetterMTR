package smooth.lift.compat;

/** Forge 侧垫片：等价于 Fabric 的 {@code ModInitializer}。入口由 {@code SmoothLiftForge} 调用。 */
public interface ModInitializer {
    void onInitialize();
}