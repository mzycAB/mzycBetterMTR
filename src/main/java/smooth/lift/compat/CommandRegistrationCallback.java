package smooth.lift.compat;

import com.mojang.brigadier.CommandDispatcher;

import smooth.lift.compat.Event;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;

/** Forge 侧垫片：挂到 {@code RegisterCommandsEvent}。 */
public interface CommandRegistrationCallback {

    Event<CommandRegistrationCallback> EVENT = new Event<>();

    void register(CommandDispatcher<CommandSourceStack> dispatcher,
                  RegistryAccess registryAccess,
                  Commands.CommandSelection environment);
}