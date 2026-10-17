package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import ekuzo.crazyworldprogression.loot.PostmasterService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Player access to the persistent twenty-slot postmaster. */
public final class PostmasterCommands {
    private PostmasterCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("postmaster")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(context -> {
                    PostmasterService.open(context.getSource().getPlayerOrException());
                    return 1;
                }));
    }
}
