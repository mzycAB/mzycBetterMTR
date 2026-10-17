package smooth.lift.compat;

import java.nio.file.Path;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/** Forge 侧垫片：只实现本模组用到的那几个 FabricLoader 成员。 */
public final class FabricLoader {

    private static final FabricLoader INSTANCE = new FabricLoader();

    private FabricLoader() {
    }

    public static FabricLoader getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}