package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Forge 侧垫片：挂到 {@code ChunkEvent.Load / Unload}（只转发客户端侧）。 */
public final class ClientChunkEvents {

    public static final Event<ChunkLoad> CHUNK_LOAD = new Event<>();
    public static final Event<ChunkUnload> CHUNK_UNLOAD = new Event<>();

    private ClientChunkEvents() {
    }

    public interface ChunkLoad {
        void onChunkLoad(ClientLevel level, LevelChunk chunk);
    }

    public interface ChunkUnload {
        void onChunkUnload(ClientLevel level, LevelChunk chunk);
    }
}