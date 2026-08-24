package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
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
    private BalanceCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("balance").executes(BalanceCommands::showOwn)
                .then(Commands.argument("targets", GameProfileArgument.gameProfile())
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(BalanceCommands::showTargets)));
    }

    private static int showOwn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        showAll(context.getSource(), player.getUUID(), "Balances");
        return 1;
    }

    private static int showTargets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "targets");
        for (NameAndId profile : profiles) showAll(context.getSource(), profile.id(), "Balances for " + profile.name());
        return profiles.size();
    }

    private static void showAll(CommandSourceStack source, UUID playerUuid, String heading) {
        source.sendSuccess(() -> Component.literal(heading).withStyle(ChatFormatting.BOLD), false);
        for (CurrencyDefinition currency : CurrencyRegistry.values()) {
            sendBalance(source, currency, CurrencyService.getBalance(source.getServer(), playerUuid, currency.id()));
        }
    }

    static void sendBalance(CommandSourceStack source, CurrencyDefinition currency, long amount) {
        source.sendSuccess(() -> Component.literal(currency.displayName() + ": ").withStyle(currency.color())
                .append(Component.literal(Long.toString(amount)).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)), false);
    }
}
