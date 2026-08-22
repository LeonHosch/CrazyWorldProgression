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

    // Register commands for inspecting and administering the kingdom election state.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("king")
                .executes(KingCommands::showKing)
                .then(Commands.literal("clear")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(KingCommands::clearKing))
                .then(Commands.literal("vote")
                        .then(Commands.literal("start")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("candidate", GameProfileArgument.gameProfile())
                                        .executes(KingCommands::startVote)))));
    }

    // Show the current king and any candidate with an active vote.
    private static int showKing(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<UUID> electedKing = KingdomProgressionService.getElectedKing(source.getServer());
        Optional<UUID> voteCandidate = KingdomProgressionService.getActiveVoteCandidate(source.getServer());

        source.sendSuccess(() -> electedKing
                .map(uuid -> Component.literal("The current king is " + displayName(source, uuid) + ".")
                        .withStyle(ChatFormatting.GOLD))
                .orElseGet(() -> Component.literal("There is currently no king.").withStyle(ChatFormatting.YELLOW)), false);
        voteCandidate.ifPresent(candidate -> source.sendSuccess(
                () -> Component.literal("A king vote is active for " + displayName(source, candidate) + ".")
                        .withStyle(ChatFormatting.AQUA), false));
        return electedKing.isPresent() ? 1 : 0;
    }

    // Start an election for exactly one selected candidate.
    private static int startVote(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(context, "candidate");
        if (profiles.size() != 1) {
            source.sendFailure(Component.literal("Exactly one candidate must be selected."));
            return 0;
        }

        NameAndId candidate = profiles.iterator().next();

        // Starting a vote records the candidate but does not appoint them as king.
        if (!KingdomProgressionService.startKingVote(source.getServer(), candidate.id())) {
            source.sendFailure(Component.literal("A king vote is already active. Clear it before starting another."));
            return 0;
        }

        source.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("A vote for " + candidate.name() + " to become king has started.")
                        .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    // Clear both the elected king and any unfinished election.
    private static int clearKing(CommandContext<CommandSourceStack> context) {
        KingdomProgressionService.clearKingAndVote(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("The current king and active king vote were cleared.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // Resolve an online player's name and fall back to their UUID while offline.
    private static String displayName(CommandSourceStack source, UUID playerUuid) {
        // Offline profiles fall back to UUID until a persistent election display name is introduced.
        ServerPlayer onlinePlayer = source.getServer().getPlayerList().getPlayer(playerUuid);
        return onlinePlayer == null ? playerUuid.toString() : onlinePlayer.getName().getString();
    }
}
