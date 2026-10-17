package smooth.lift.compat;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** 垫片：{@code Registries} 是 1.19.2 才引入的类，1.18.2 里同名常量挂在 {@link Registry} 上。 */
public final class Registries {

    public static final ResourceKey<Registry<Level>> DIMENSION = Registry.DIMENSION_REGISTRY;

    private Registries() {
    }
}