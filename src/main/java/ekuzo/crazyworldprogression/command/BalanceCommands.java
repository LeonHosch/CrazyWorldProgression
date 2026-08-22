package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import ekuzo.crazyworldprogression.progression.kingdom.KingdomProgressionService;
import ekuzo.crazyworldprogression.progression.player.PersonalCurrency;
import ekuzo.crazyworldprogression.progression.player.PlayerProgressionService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;
import java.util.UUID;

public final class BalanceCommands {
    // Prevent this static command utility from being instantiated.
    private BalanceCommands() {
    }

    // Register the command that shows every balance available to the executing player.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("balance")
                .executes(BalanceCommands::showAll)
                .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(BalanceCommands::showAllForTargets)));
    }

    // Show the shared balance and all balances owned by the executing player.
    private static int showAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();

        // Kingdom Points are shared globally; the remaining entries belong to the executing player.
        source.sendSuccess(() -> Component.literal("Balances").withStyle(ChatFormatting.BOLD), false);
        sendKingdomPoints(source);
        sendPersonalBalances(source, player.getUUID());
        return 1;
    }

    // Show every balance for one or more admin-selected player profiles.
    private static int showAllForTargets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");

        for (NameAndId profile : profiles) {
            source.sendSuccess(() -> Component.literal("Balances for " + profile.name())
                    .withStyle(ChatFormatting.BOLD), false);
            sendKingdomPoints(source);
            sendPersonalBalances(source, profile.id());
        }
        return profiles.size();
    }

    // Display all three balances that belong to one player UUID.
    private static void sendPersonalBalances(CommandSourceStack source, UUID playerUuid) {
        sendEchelonPoints(source, playerUuid);
        sendFakhrulCurrency(source, playerUuid);
        sendPowerfulSouls(source, playerUuid);
    }

    // Read and display the server-wide Kingdom Points balance.
    private static void sendKingdomPoints(CommandSourceStack source) {
        long balance = KingdomProgressionService.getKingdomPoints(source.getServer());
        sendBalance(source, CurrencyDisplay.KINGDOM_POINTS, balance);
    }

    // Read and display the player's Echelon Points balance.
    private static void sendEchelonPoints(CommandSourceStack source, UUID playerUuid) {
        long balance = PlayerProgressionService.getBalance(
                source.getServer(), playerUuid, PersonalCurrency.ECHELON_POINTS);
        sendBalance(source, CurrencyDisplay.ECHELON_POINTS, balance);
    }

    // Read and display the player's temporary Fakhrul currency balance.
    private static void sendFakhrulCurrency(CommandSourceStack source, UUID playerUuid) {
        long balance = PlayerProgressionService.getBalance(
                source.getServer(), playerUuid, PersonalCurrency.FAKHRUL_CURRENCY);
        sendBalance(source, CurrencyDisplay.FAKHRUL_CURRENCY, balance);
    }

    // Read and display the player's Powerful Souls balance.
    private static void sendPowerfulSouls(CommandSourceStack source, UUID playerUuid) {
        long balance = PlayerProgressionService.getPowerfulSouls(source.getServer(), playerUuid);
        sendBalance(source, CurrencyDisplay.POWERFUL_SOULS, balance);
    }

    // Send one consistently formatted currency balance to a command source.
    static void sendBalance(CommandSourceStack source, CurrencyDisplay currency, long amount) {
        source.sendSuccess(() -> createBalanceMessage(currency, amount), false);
    }

    // Build the shared text component used by every balance command.
    private static Component createBalanceMessage(CurrencyDisplay currency, long amount) {
        return Component.literal(currency.displayName() + ": ").withStyle(currency.color())
                .append(Component.literal(Long.toString(amount))
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
    }

    enum CurrencyDisplay {
        KINGDOM_POINTS("Kingdom Points", ChatFormatting.GOLD),
        ECHELON_POINTS("Echelon Points", ChatFormatting.AQUA),
        FAKHRUL_CURRENCY("Fakhrul Currency", ChatFormatting.LIGHT_PURPLE),
        POWERFUL_SOULS("Powerful Souls", ChatFormatting.DARK_PURPLE);

        private final String displayName;
        private final ChatFormatting color;

        // Store the player-facing name and color for a currency.
        CurrencyDisplay(String displayName, ChatFormatting color) {
            this.displayName = displayName;
            this.color = color;
        }

        // Return the name shown in balance and administration messages.
        String displayName() {
            return displayName;
        }

        // Return the color used for this currency's messages.
        ChatFormatting color() {
            return color;
        }
    }
}
