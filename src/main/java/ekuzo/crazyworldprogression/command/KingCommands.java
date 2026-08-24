package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import ekuzo.crazyworldprogression.progression.kingdom.KingdomProgressionService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public final class KingCommands {
    // Prevent this static command utility from being instantiated.
    private KingCommands() {
    }

    // Register commands for inspecting and administering the kingdom's king.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("king")
                .executes(KingCommands::showKing)
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(KingCommands::setKing)))
                .then(Commands.literal("clear")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(KingCommands::clearKing)));
    }

    // Show the current king.
    private static int showKing(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<UUID> electedKing = KingdomProgressionService.getElectedKing(source.getServer());

        source.sendSuccess(() -> electedKing
                .map(uuid -> Component.literal("The current king is " + displayName(source, uuid) + ".")
                        .withStyle(ChatFormatting.GOLD))
                .orElseGet(() -> Component.literal("There is currently no king.").withStyle(ChatFormatting.YELLOW)), false);
        return electedKing.isPresent() ? 1 : 0;
    }

    // Immediately replace the current king with exactly one selected player.
    private static int setKing(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "player");
        if (profiles.size() != 1) {
            source.sendFailure(Component.literal("Exactly one player must be selected."));
            return 0;
        }

        NameAndId player = profiles.iterator().next();
        KingdomProgressionService.setKing(source.getServer(), player.id());
        source.sendSuccess(() -> Component.literal(player.name() + " is now the king.")
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    // Clear the current king.
    private static int clearKing(CommandContext<CommandSourceStack> context) {
        KingdomProgressionService.clearKing(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("The current king was cleared.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // Resolve an online player's name and fall back to their UUID while offline.
    private static String displayName(CommandSourceStack source, UUID playerUuid) {
        // Offline profiles fall back to UUID because only the king's UUID is persisted.
        ServerPlayer onlinePlayer = source.getServer().getPlayerList().getPlayer(playerUuid);
        return onlinePlayer == null ? playerUuid.toString() : onlinePlayer.getName().getString();
    }
}
