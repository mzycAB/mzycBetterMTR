package smooth.lift.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 暴露服务端已加载区块持有者迭代器，供 /jietispeed 指令扫描全部扶梯。 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Invoker("getChunks")
    Iterable<ChunkHolder> smoothlift_getChunks();
}