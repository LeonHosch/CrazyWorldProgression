package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import ekuzo.crazyworldprogression.portals.InstanceInvites;
import ekuzo.crazyworldprogression.portals.PortalGroups;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.RaidService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player party commands: /leave, /partyleave, and /party invite|accept|leave. Inside a running raid,
 * /party invite sends a raid invite; outside, it forms a group before travel (same as /portal group).
 */
public final class PartyCommands {
    private PartyCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("leave").executes(PartyCommands::leave));
        dispatcher.register(Commands.literal("partyleave").executes(PartyCommands::partyLeave));
        dispatcher.register(Commands.literal("party")
                .then(Commands.literal("invite").then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getOnlinePlayerNames(), builder))
                        .executes(PartyCommands::invite)))
                .then(Commands.literal("accept").then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getOnlinePlayerNames(), builder))
                        .executes(PartyCommands::accept)))
                .then(Commands.literal("leave").executes(context -> run(context, () ->
                        PortalGroups.leave(context.getSource().getPlayerOrException())))));
    }

    private static int leave(CommandContext<CommandSourceStack> context) {
        return run(context, () -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!PortalService.leave(player)) throw new IllegalStateException("You are not inside an instance");
            player.sendSystemMessage(Component.literal(
                    "You left the activity. You can return through its portal while your party is still inside."));
        });
    }

    private static int partyLeave(CommandContext<CommandSourceStack> context) {
        return run(context, () -> PortalService.partyLeave(context.getSource().getPlayerOrException()));
    }

    private static int invite(CommandContext<CommandSourceStack> context) {
        return run(context, () -> {
            ServerPlayer inviter = context.getSource().getPlayerOrException();
            ServerPlayer target = target(context);
            var instance = PortalService.currentInstance(inviter);
            if (instance != null && RaidService.isRaidInstance(inviter.level().getServer(), instance))
                InstanceInvites.invite(inviter, target);
            else PortalGroups.invite(inviter, target);
        });
    }

    private static int accept(CommandContext<CommandSourceStack> context) {
        return run(context, () -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            ServerPlayer inviter = target(context);
            if (InstanceInvites.hasInviteFrom(player.level().getServer(), player.getUUID(), inviter.getUUID()))
                InstanceInvites.accept(player, inviter);
            else PortalGroups.accept(player, inviter);
        });
    }

    private static ServerPlayer target(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException("Player is not online: " + name);
        return target;
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
