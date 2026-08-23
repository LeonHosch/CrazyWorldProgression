package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class SkillTreeCommands {
    // Prevent this static command utility from being instantiated.
    private SkillTreeCommands() {
    }

    // Register the player command that opens the skill-tree screen.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("skill").executes(SkillTreeCommands::open));
    }

    // Send the executing player a fresh skill-tree snapshot and open instruction.
    private static int open(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SkillTreeNetworking.open(context.getSource().getPlayerOrException());
        return 1;
    }
}
