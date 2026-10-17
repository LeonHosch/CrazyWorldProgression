package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import ekuzo.crazyworldprogression.portals.Parties;
import ekuzo.crazyworldprogression.portals.PortalService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /party opens the party screen; the subcommands do the same things from chat (the clickable chat buttons use
 * them). /leave and /partyleave leave the current dungeon.
 */
public final class PartyCommands {
    private PartyCommands() { }

    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (context, builder) ->
            SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("leave").executes(PartyCommands::leave));
        dispatcher.register(Commands.literal("partyleave").executes(context -> run(context, () ->
                PortalService.partyLeave(context.getSource().getPlayerOrException()))));
        dispatcher.register(Commands.literal("party")
                .executes(context -> run(context, () -> Parties.open(context.getSource().getPlayerOrException())))
                .then(Commands.literal("create")
                        .executes(context -> act(context, "create", "", ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> act(context, "create", StringArgumentType.getString(context, "name"), ""))))
                .then(player("invite", "invite"))
                .then(player("accept", "accept_invite"))
                .then(player("decline", "decline_invite"))
                .then(player("join", "join_leader"))
                .then(player("kick", "kick"))
                .then(player("promote", "promote"))
                .then(Commands.literal("requests")
                        .then(player("accept", "accept_request"))
                        .then(player("deny", "deny_request")))
                .then(Commands.literal("leave").executes(context -> act(context, "leave", "", "")))
                .then(Commands.literal("disband").executes(context -> act(context, "disband", "", ""))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> player(String literal, String action) {
        return Commands.literal(literal).then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                .executes(context -> act(context, action, StringArgumentType.getString(context, "player"), "")));
    }

    private static int act(CommandContext<CommandSourceStack> context, String action, String a, String b) {
        return run(context, () -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (action.equals("join_leader")) Parties.joinLeader(player, a);
            else Parties.act(player, action, a, b);
        });
    }

    private static int leave(CommandContext<CommandSourceStack> context) {
        return run(context, () -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!PortalService.leave(player)) throw new IllegalStateException("You are not inside an instance");
            player.sendSystemMessage(Component.literal(
                    "You left the activity. You can return through its portal while your party is still inside."));
        });
    }

    private interface Action { void run() throws Exception; }

    private static int run(CommandContext<CommandSourceStack> context, Action action) {
        try {
            action.run();
            return 1;
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage()));
            return 0;
        }
    }
}
