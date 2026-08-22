package ekuzo.crazyworldprogression.progression.kingdom;

import ekuzo.crazyworldprogression.progression.BalanceChange;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

public final class KingdomProgressionService {
    // Prevent this static progression service from being instantiated.
    private KingdomProgressionService() {
    }

    // Read the server-wide Kingdom Points balance.
    public static long getKingdomPoints(MinecraftServer server) {
        return KingdomProgressionData.get(server).kingdomPoints();
    }

    // Add a non-negative amount to Kingdom Points with overflow checking.
    public static BalanceChange addKingdomPoints(MinecraftServer server, long amount) {
        requireNonNegative(amount);
        KingdomProgressionData data = KingdomProgressionData.get(server);
        long previous = data.kingdomPoints();
        long updated = Math.addExact(previous, amount);
        data.setKingdomPoints(updated);
        return new BalanceChange(previous, updated);
    }

    // Remove up to the requested amount from the shared balance.
    public static BalanceChange removeKingdomPoints(MinecraftServer server, long amount) {
        requireNonNegative(amount);
        KingdomProgressionData data = KingdomProgressionData.get(server);
        long previous = data.kingdomPoints();
        long updated = Math.max(0L, previous - amount);
        data.setKingdomPoints(updated);
        return new BalanceChange(previous, updated);
    }

    // Replace the shared Kingdom Points balance with an exact non-negative value.
    public static BalanceChange setKingdomPoints(MinecraftServer server, long amount) {
        requireNonNegative(amount);
        KingdomProgressionData data = KingdomProgressionData.get(server);
        long previous = data.kingdomPoints();
        data.setKingdomPoints(amount);
        return new BalanceChange(previous, amount);
    }

    // Return the elected king when the kingdom currently has one.
    public static Optional<UUID> getElectedKing(MinecraftServer server) {
        return KingdomProgressionData.get(server).electedKing();
    }

    // Appoint the winning player as king and close the active vote.
    public static void electKing(MinecraftServer server, UUID playerUuid) {
        // Resolving a successful vote appoints the king and closes the active election.
        KingdomProgressionData.get(server).electKing(playerUuid);
    }

    // Return the candidate in the currently active king vote.
    public static Optional<UUID> getActiveVoteCandidate(MinecraftServer server) {
        return KingdomProgressionData.get(server).activeVoteCandidate();
    }

    // Start a king vote when no other candidate is active.
    public static boolean startKingVote(MinecraftServer server, UUID candidateUuid) {
        KingdomProgressionData data = KingdomProgressionData.get(server);

        // Only one candidate can be active until the election system resolves or clears the vote.
        if (data.activeVoteCandidate().isPresent()) {
            return false;
        }
        data.startKingVote(candidateUuid);
        return true;
    }

    // Clear both the current king and unfinished vote state.
    public static void clearKingAndVote(MinecraftServer server) {
        KingdomProgressionData.get(server).clearKingAndVote();
    }

    // Let the elected king purchase one global technology when all requirements pass.
    public static TechnologyUnlockResult unlockTechnology(
            MinecraftServer server,
            UUID actingPlayer,
            Identifier technologyId,
            long cost
    ) {
        requireNonNegative(cost);
        KingdomProgressionData data = KingdomProgressionData.get(server);

        // Technology purchases are global but can only be authorized by the elected king.
        if (data.electedKing().filter(actingPlayer::equals).isEmpty()) {
            return TechnologyUnlockResult.NOT_ELECTED_KING;
        }
        if (data.isTechnologyUnlocked(technologyId.toString())) {
            return TechnologyUnlockResult.ALREADY_UNLOCKED;
        }
        if (data.kingdomPoints() < cost) {
            return TechnologyUnlockResult.INSUFFICIENT_KINGDOM_POINTS;
        }

        data.unlockTechnology(technologyId.toString(), data.kingdomPoints() - cost);
        return TechnologyUnlockResult.UNLOCKED;
    }

    // Reject negative values before they reach persistent kingdom state.
    private static void requireNonNegative(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
    }

    public enum TechnologyUnlockResult {
        UNLOCKED,
        NOT_ELECTED_KING,
        ALREADY_UNLOCKED,
        INSUFFICIENT_KINGDOM_POINTS
    }
}
