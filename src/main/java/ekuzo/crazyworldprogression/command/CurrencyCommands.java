package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.progression.BalanceChange;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;
import java.util.UUID;

public final class CurrencyCommands {
    private CurrencyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (CurrencyDefinition currency : CurrencyRegistry.values()) registerCurrency(dispatcher, currency);
    }

    private static void registerCurrency(CommandDispatcher<CommandSourceStack> dispatcher, CurrencyDefinition currency) {
        String primaryName = currency.commands().getFirst();
        var primary = dispatcher.register(buildCommand(primaryName, currency));
        for (String alias : currency.commands().subList(1, currency.commands().size())) {
            dispatcher.register(Commands.literal(alias).executes(context -> showOwn(context, currency)).redirect(primary));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildCommand(String name, CurrencyDefinition currency) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(name)
                .executes(context -> showOwn(context, currency));
        if (currency.scope() == CurrencyDefinition.CurrencyScope.PLAYER) {
            command.then(Commands.argument("targets", GameProfileArgument.gameProfile())
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> showTargets(context, currency)));
        }
        return command.then(mutation("give", currency, ChangeType.GIVE, 1L))
                .then(mutation("take", currency, ChangeType.TAKE, 1L))
                .then(mutation("set", currency, ChangeType.SET, 0L));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mutation(String name, CurrencyDefinition currency,
                                                                        ChangeType type, long minimum) {
        var amount = Commands.argument("amount", LongArgumentType.longArg(minimum));
        if (currency.scope() == CurrencyDefinition.CurrencyScope.GLOBAL) {
            amount.executes(context -> change(context, currency, type, null));
        } else {
            amount.then(Commands.argument("targets", GameProfileArgument.gameProfile())
                    .executes(context -> changeTargets(context, currency, type)));
        }
        return Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(amount);
    }

    private static int showOwn(CommandContext<CommandSourceStack> context, CurrencyDefinition currency)
            throws CommandSyntaxException {
        UUID player = currency.scope() == CurrencyDefinition.CurrencyScope.GLOBAL
                ? null
                : context.getSource().getPlayerOrException().getUUID();
        BalanceCommands.sendBalance(context.getSource(), currency,
                CurrencyService.getBalance(context.getSource().getServer(), player, currency.id()));
        return 1;
    }

    private static int showTargets(CommandContext<CommandSourceStack> context, CurrencyDefinition currency)
            throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        for (NameAndId profile : profiles) {
            context.getSource().sendSuccess(() -> Component.literal("Balance for " + profile.name()).withStyle(ChatFormatting.BOLD), false);
            BalanceCommands.sendBalance(context.getSource(), currency,
                    CurrencyService.getBalance(context.getSource().getServer(), profile.id(), currency.id()));
        }
        return profiles.size();
    }

    private static int changeTargets(CommandContext<CommandSourceStack> context, CurrencyDefinition currency, ChangeType type)
            throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        int changed = 0;
        for (NameAndId profile : profiles) changed += change(context, currency, type, profile.id());
        return changed;
    }

    private static int change(CommandContext<CommandSourceStack> context, CurrencyDefinition currency,
                              ChangeType type, UUID playerUuid) {
        long amount = LongArgumentType.getLong(context, "amount");
        try {
            BalanceChange result = switch (type) {
                case GIVE -> CurrencyService.credit(context.getSource().getServer(), playerUuid, currency.id(), amount);
                case TAKE -> CurrencyService.debit(context.getSource().getServer(), playerUuid, currency.id(), amount);
                case SET -> CurrencyService.setBalance(context.getSource().getServer(), playerUuid, currency.id(), amount);
            };
            context.getSource().sendSuccess(() -> Component.literal(currency.displayName() + ": "
                    + result.previousBalance() + " -> " + result.newBalance()).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (ArithmeticException exception) {
            context.getSource().sendFailure(Component.literal("The resulting balance would be too large."));
            return 0;
        }
    }

    private enum ChangeType { GIVE, TAKE, SET }
}
