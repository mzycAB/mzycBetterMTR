package smooth.lift.compat;

import smooth.lift.compat.Event;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** Forge 侧垫片：挂到 {@code PlayerInteractEvent.RightClickBlock}（客户端与服务端都会触发）。 */
public interface UseBlockCallback {

    Event<UseBlockCallback> EVENT = new Event<>();

    InteractionResult interact(Player player, Level world, InteractionHand hand, BlockHitResult hitResult);
}