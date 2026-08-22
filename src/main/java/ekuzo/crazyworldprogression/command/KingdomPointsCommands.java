package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import ekuzo.crazyworldprogression.command.BalanceCommands.CurrencyDisplay;
import ekuzo.crazyworldprogression.progression.BalanceChange;
import ekuzo.crazyworldprogression.progression.kingdom.KingdomProgressionService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class KingdomPointsCommands {
    // Prevent this static command utility from being instantiated.
    private KingdomPointsCommands() {
    }

    // Register balance and administration commands for the shared Kingdom Points balance.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Kingdom Points have no player target because the balance belongs to the whole server.
        var primary = dispatcher.register(buildCommand("kingdompoints"));
        dispatcher.register(Commands.literal("kp")
                .executes(KingdomPointsCommands::showBalance)
                .redirect(primary));
    }

    // Build the shared KP command tree used by both the long name and alias.
    private static LiteralArgumentBuilder<CommandSourceStack> buildCommand(String name) {
        return Commands.literal(name)
                .executes(KingdomPointsCommands::showBalance)
                .then(Commands.literal("give")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                .executes(KingdomPointsCommands::givePoints)))
                .then(Commands.literal("take")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                .executes(KingdomPointsCommands::takePoints)))
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                .executes(KingdomPointsCommands::setPoints)));
    }

    // Show the current server-wide Kingdom Points balance.
    private static int showBalance(CommandContext<CommandSourceStack> context) {
        long balance = KingdomProgressionService.getKingdomPoints(context.getSource().getServer());
        BalanceCommands.sendBalance(context.getSource(), CurrencyDisplay.KINGDOM_POINTS, balance);
        return 1;
    }

    // Add Kingdom Points to the global balance for administration and debugging.
    private static int givePoints(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long amount = LongArgumentType.getLong(context, "amount");
        try {
            BalanceChange change = KingdomProgressionService.addKingdomPoints(source.getServer(), amount);
            sendBalanceChange(source, "Added", amount, change.newBalance());
            return 1;
        } catch (ArithmeticException exception) {
            source.sendFailure(Component.literal("Could not add Kingdom Points: the balance would be too large."));
            return 0;
        }
    }

    // Remove as many requested Kingdom Points as the global balance contains.
    private static int takePoints(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long requestedAmount = LongArgumentType.getLong(context, "amount");
        BalanceChange change = KingdomProgressionService.removeKingdomPoints(source.getServer(), requestedAmount);
        long removedAmount = change.previousBalance() - change.newBalance();
        sendBalanceChange(source, "Removed", removedAmount, change.newBalance());
        return 1;
    }

    // Replace the global Kingdom Points balance with an exact value.
    private static int setPoints(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        long amount = LongArgumentType.getLong(context, "amount");
        KingdomProgressionService.setKingdomPoints(source.getServer(), amount);
        source.sendSuccess(() -> Component.literal("Set the Kingdom Points balance to " + amount + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // Report a successful global balance mutation to the command source and server operators.
    private static void sendBalanceChange(CommandSourceStack source, String action, long amount, long newBalance) {
        source.sendSuccess(() -> Component.literal(action + " " + amount
                + " Kingdom Points. (New balance: " + newBalance + ")").withStyle(ChatFormatting.GREEN), true);
    }
}
