package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import ekuzo.crazyworldprogression.command.BalanceCommands.CurrencyDisplay;
import ekuzo.crazyworldprogression.progression.BalanceChange;
import ekuzo.crazyworldprogression.progression.player.PersonalCurrency;
import ekuzo.crazyworldprogression.progression.player.PlayerProgressionService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PersonalCurrencyCommands {
    private static final int MAX_EXPLICIT_TARGETS = 32;

    private static final CurrencyOperations ECHELON_POINTS = regularCurrency(
            CurrencyDisplay.ECHELON_POINTS, PersonalCurrency.ECHELON_POINTS);
    private static final CurrencyOperations FAKHRUL_CURRENCY = regularCurrency(
            CurrencyDisplay.FAKHRUL_CURRENCY, PersonalCurrency.FAKHRUL_CURRENCY);
    private static final CurrencyOperations POWERFUL_SOULS = new CurrencyOperations(
            CurrencyDisplay.POWERFUL_SOULS,
            PlayerProgressionService::getPowerfulSouls,
            PlayerProgressionService::creditPowerfulSoulsForAdministration,
            PlayerProgressionService::debitPowerfulSoulsForAdministration,
            PlayerProgressionService::setPowerfulSoulsForAdministration
    );

    // Prevent this static command utility from being instantiated.
    private PersonalCurrencyCommands() {
    }

    // Register balance and administration commands for every player-owned currency.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // All personal currencies use the same command tree; only their storage operations differ.
        registerCurrency(dispatcher, "ep", "echelonpoints", ECHELON_POINTS);
        registerCurrency(dispatcher, "fc", "fakhrul", FAKHRUL_CURRENCY);
        registerCurrency(dispatcher, "ps", "powerfulsouls", POWERFUL_SOULS);
    }

    // Register one currency under a short command and a forwarding long alias.
    private static void registerCurrency(
            CommandDispatcher<CommandSourceStack> dispatcher,
            String shortName,
            String longName,
            CurrencyOperations currency
    ) {
        // The short command owns the tree; the long name forwards both execution and admin subcommands.
        var primary = dispatcher.register(buildCommand(shortName, currency));
        dispatcher.register(Commands.literal(longName)
                .executes(context -> showBalance(context, currency))
                .redirect(primary));
    }

    // Build the balance, give, take, and set branches for one personal currency.
    private static LiteralArgumentBuilder<CommandSourceStack> buildCommand(
            String name,
            CurrencyOperations currency
    ) {
        // Balance checks are public, while direct mutations are reserved for debugging and administration.
        return Commands.literal(name)
                .executes(context -> showBalance(context, currency))
                .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> showTargetBalances(context, currency)))
                .then(buildMultiTargetCommand("give", currency, ChangeType.GIVE, 1L))
                .then(buildMultiTargetCommand("take", currency, ChangeType.TAKE, 1L))
                .then(buildMultiTargetCommand("set", currency, ChangeType.SET, 0L));
    }

    // Build an amount-first mutation branch that accepts up to 32 names or selectors.
    private static LiteralArgumentBuilder<CommandSourceStack> buildMultiTargetCommand(
            String commandName,
            CurrencyOperations currency,
            ChangeType changeType,
            long minimumAmount
    ) {
        return Commands.literal(commandName)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("amount", LongArgumentType.longArg(minimumAmount))
                        .then(buildMutationTarget(currency, changeType, 1)));
    }

    // Build one optional target level and recursively append the remaining target levels.
    private static ArgumentBuilder<CommandSourceStack, ?> buildMutationTarget(
            CurrencyOperations currency,
            ChangeType changeType,
            int targetIndex
    ) {
        String argumentName = "target" + targetIndex;
        ArgumentBuilder<CommandSourceStack, ?> target = Commands
                .argument(argumentName, GameProfileArgument.gameProfile())
                .executes(context -> changeMultipleTargetBalances(
                        context, currency, changeType, targetIndex));

        if (targetIndex < MAX_EXPLICIT_TARGETS) {
            target.then(buildMutationTarget(currency, changeType, targetIndex + 1));
        }
        return target;
    }

    // Show the executing player's balance for the selected currency.
    private static int showBalance(
            CommandContext<CommandSourceStack> context,
            CurrencyOperations currency
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        BalanceCommands.sendBalance(
                source,
                currency.display(),
                currency.reader().get(source.getServer(), player.getUUID())
        );
        return 1;
    }

    // Show one personal currency balance for every admin-selected player profile.
    private static int showTargetBalances(
            CommandContext<CommandSourceStack> context,
            CurrencyOperations currency
    ) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");

        for (NameAndId profile : profiles) {
            source.sendSuccess(() -> Component.literal("Balance for " + profile.name())
                    .withStyle(ChatFormatting.BOLD), false);
            BalanceCommands.sendBalance(
                    source,
                    currency.display(),
                    currency.reader().get(source.getServer(), profile.id())
            );
        }
        return profiles.size();
    }

    // Collect every explicit name or selector and mutate each unique player's balance.
    private static int changeMultipleTargetBalances(
            CommandContext<CommandSourceStack> context,
            CurrencyOperations currency,
            ChangeType changeType,
            int targetCount
    ) throws CommandSyntaxException {
        Map<UUID, NameAndId> uniqueProfiles = new LinkedHashMap<>();
        for (int targetIndex = 1; targetIndex <= targetCount; targetIndex++) {
            Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(
                    context, "target" + targetIndex);
            profiles.forEach(profile -> uniqueProfiles.putIfAbsent(profile.id(), profile));
        }

        return changeBalances(
                context.getSource(),
                uniqueProfiles.values(),
                LongArgumentType.getLong(context, "amount"),
                currency,
                changeType
        );
    }

    // Apply one already-parsed balance operation to a collection of player profiles.
    private static int changeBalances(
            CommandSourceStack source,
            Collection<NameAndId> profiles,
            long requestedAmount,
            CurrencyOperations currency,
            ChangeType changeType
    ) {
        int changedPlayers = 0;

        for (NameAndId profile : profiles) {
            try {
                // Apply each target independently so one overflowing balance does not block the others.
                BalanceChange change = mutation(currency, changeType)
                        .apply(source.getServer(), profile.id(), requestedAmount);
                long changedAmount = changeType == ChangeType.TAKE
                        ? change.previousBalance() - change.newBalance()
                        : requestedAmount;
                sendAdminMessage(source, currency.display(), profile, changeType, changedAmount, change.newBalance());
                notifyOnlinePlayer(source, currency.display(), profile, changeType, changedAmount, change.newBalance());
                changedPlayers++;
            } catch (ArithmeticException exception) {
                source.sendFailure(Component.literal("Could not update " + currency.display().displayName()
                        + " for " + profile.name() + ": the balance would be too large."));
            }
        }
        return changedPlayers;
    }

    // Select the storage mutation that corresponds to a command action.
    private static BalanceMutation mutation(CurrencyOperations currency, ChangeType changeType) {
        return switch (changeType) {
            case GIVE -> currency.give();
            case TAKE -> currency.take();
            case SET -> currency.set();
        };
    }

    // Report a successful administrative change to the command source and server operators.
    private static void sendAdminMessage(
            CommandSourceStack source,
            CurrencyDisplay currency,
            NameAndId profile,
            ChangeType changeType,
            long amount,
            long newBalance
    ) {
        String message = switch (changeType) {
            case GIVE -> "Gave " + amount + " " + currency.displayName() + " to " + profile.name();
            case TAKE -> "Took " + amount + " " + currency.displayName() + " from " + profile.name();
            case SET -> "Set " + profile.name() + "'s " + currency.displayName() + " to " + newBalance;
        };
        source.sendSuccess(() -> Component.literal(message + ". (New balance: " + newBalance + ")")
                .withStyle(ChatFormatting.GREEN), true);
    }

    // Notify an affected player when they are currently online.
    private static void notifyOnlinePlayer(
            CommandSourceStack source,
            CurrencyDisplay currency,
            NameAndId profile,
            ChangeType changeType,
            long amount,
            long newBalance
    ) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayer(profile.id());
        if (target == null) {
            return;
        }

        String message = switch (changeType) {
            case GIVE -> "You received " + amount + " " + currency.displayName();
            case TAKE -> amount + " " + currency.displayName() + " were removed";
            case SET -> "Your " + currency.displayName() + " balance was set to " + newBalance;
        };
        target.sendSystemMessage(Component.literal(message + ". (Total: " + newBalance + ")")
                .withStyle(currency.color()));
    }

    // Adapt a regular PersonalCurrency to the generic command operations.
    private static CurrencyOperations regularCurrency(
            CurrencyDisplay display,
            PersonalCurrency currency
    ) {
        return new CurrencyOperations(
                display,
                (server, playerUuid) -> PlayerProgressionService.getBalance(server, playerUuid, currency),
                (server, playerUuid, amount) -> PlayerProgressionService.credit(server, playerUuid, currency, amount),
                (server, playerUuid, amount) -> PlayerProgressionService.debit(server, playerUuid, currency, amount),
                (server, playerUuid, amount) -> PlayerProgressionService.setBalance(server, playerUuid, currency, amount)
        );
    }

    private enum ChangeType {
        GIVE,
        TAKE,
        SET
    }

    private record CurrencyOperations(
            CurrencyDisplay display,
            BalanceReader reader,
            BalanceMutation give,
            BalanceMutation take,
            BalanceMutation set
    ) {
    }

    @FunctionalInterface
    private interface BalanceReader {
        // Read one player's current balance from the server progression state.
        long get(MinecraftServer server, UUID playerUuid);
    }

    @FunctionalInterface
    private interface BalanceMutation {
        // Apply one balance mutation and return its before-and-after values.
        BalanceChange apply(MinecraftServer server, UUID playerUuid, long amount);
    }
}
