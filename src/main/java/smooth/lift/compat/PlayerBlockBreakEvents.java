package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Forge 侧垫片：挂到 {@code BlockEvent.BreakEvent}（服务端，方块已被破坏之后）。 */
public final class PlayerBlockBreakEvents {

    public static final Event<After> AFTER = new Event<>();

    private PlayerBlockBreakEvents() {
    }

    public interface After {
        void afterBlockBreak(Level world, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity);
    }
}