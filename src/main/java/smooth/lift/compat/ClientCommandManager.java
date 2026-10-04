package smooth.lift.compat;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Forge 侧垫片：Fabric 的 {@code ClientCommandManager}。
 *
 * <p>Forge 的客户端指令分发器同样是 {@code CommandDispatcher<CommandSourceStack>}，
 * 所以直接转发到 {@link Commands} 即可。
 */
public final class ClientCommandManager {

    private ClientCommandManager() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    public static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) {
        return Commands.argument(name, type);
    }

    /**
     * 垫片：Fabric 的 {@code FabricClientCommandSource.sendFeedback(Text)} 在 Forge 侧对应
     * {@link CommandSourceStack#sendSuccess(Component, boolean)}。
     */
    public static void sendFeedback(CommandSourceStack source, Component text) {
        source.sendSuccess(text, false);
    }
}