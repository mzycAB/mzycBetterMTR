package smooth.lift.compat;

/** Forge 侧垫片：Fabric 的 {@code ModelLoadingPlugin}。 */
public interface ModelLoadingPlugin {

    void onInitializeModelLoader(Context plugin);

    static void register(ModelLoadingPlugin plugin) {
        plugin.onInitializeModelLoader(new Context() {
            @Override
            public ModelModifier.BeforeBake modifyModelBeforeBake() {
                return ModelModifier.BEFORE_BAKE::add;
            }
        });
    }

    interface Context {
        ModelModifier.BeforeBake modifyModelBeforeBake();
    }
}