package smooth.lift.forge;

import smooth.lift.compat.CommandRegistrationCallback;
import smooth.lift.compat.ServerLifecycleEvents;
import smooth.lift.compat.ServerTickEvents;
import smooth.lift.compat.PlayerBlockBreakEvents;
import smooth.lift.compat.UseBlockCallback;
import smooth.lift.compat.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/** 通用侧（客户端 + 服务端）的 Forge 事件 → Fabric 垫片事件 的转发桥。 */
public final class ForgeEventBridge {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandRegistrationCallback.EVENT.invoke(callback -> callback.register(
                event.getDispatcher(),
                null,
                event.getEnvironment()));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.invoke(callback -> callback.onEndTick(server));
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ServerLifecycleEvents.SERVER_STARTED.invoke(callback -> callback.onServerStarted(event.getServer()));
    }

    /** 【10-03 五改】转发服务端停止事件（「关门后等待发车」用它清掉旧世界的服务端实例）。 */
    @SubscribeEvent
    public void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        ServerLifecycleEvents.SERVER_STOPPED.invoke(callback -> callback.onServerStopped(event.getServer()));
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ServerPlayConnectionEvents.JOIN.invoke(callback ->
                callback.onPlayReady(player.connection, null, player.getServer()));
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        Level level = (Level) event.getWorld();
        Player player = event.getPlayer();
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        PlayerBlockBreakEvents.AFTER.invoke(callback ->
                callback.afterBlockBreak(level, player, pos, state, blockEntity));
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (UseBlockCallback.EVENT.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        Level world = event.getWorld();
        BlockHitResult hit = event.getHitVec();
        final InteractionResult[] outcome = {InteractionResult.PASS};
        UseBlockCallback.EVENT.invoke(callback ->
                outcome[0] = callback.interact(player, world, event.getHand(), hit));
        if (outcome[0] == InteractionResult.FAIL) {
            event.setUseBlock(Event.Result.DENY);
            event.setUseItem(Event.Result.DENY);
            event.setCanceled(true);
        }
    }
}