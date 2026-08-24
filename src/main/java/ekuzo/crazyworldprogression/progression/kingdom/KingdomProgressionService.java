package ekuzo.crazyworldprogression.progression.kingdom;

import ekuzo.crazyworldprogression.progression.BalanceChange;
import ekuzo.crazyworldprogression.progression.player.PersonalCurrency;
import ekuzo.crazyworldprogression.progression.player.PlayerProgressionService;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.Set;
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

    // Immediately appoint a player as king, replacing the current king when present.
    public static void setKing(MinecraftServer server, UUID playerUuid) {
        KingdomProgressionData.get(server).setKing(playerUuid);
    }

    // Clear the current king.
    public static void clearKing(MinecraftServer server) {
        KingdomProgressionData.get(server).clearKing();
    }

    // Let the elected king purchase one global technology when all requirements pass.
    public static TechnologyUnlockResult unlockTechnology(
            MinecraftServer server,
            UUID actingPlayer,
            String technologyId,
            long kingdomPointCost,
            long fakhrulCurrencyCost
    ) {
        requireNonNegative(kingdomPointCost);
        requireNonNegative(fakhrulCurrencyCost);
        KingdomProgressionData data = KingdomProgressionData.get(server);

        // Technology purchases are global but can only be authorized by the elected king.
        if (data.electedKing().filter(actingPlayer::equals).isEmpty()) {
            return TechnologyUnlockResult.NOT_ELECTED_KING;
        }
        if (data.isTechnologyUnlocked(technologyId)) {
            return TechnologyUnlockResult.ALREADY_UNLOCKED;
        }
        if (data.kingdomPoints() < kingdomPointCost) {
            return TechnologyUnlockResult.INSUFFICIENT_KINGDOM_POINTS;
        }
        long fakhrulCurrency = PlayerProgressionService.getBalance(
                server,
                actingPlayer,
                PersonalCurrency.FAKHRUL_CURRENCY
        );
        if (fakhrulCurrency < fakhrulCurrencyCost) {
            return TechnologyUnlockResult.INSUFFICIENT_FAKHRUL_CURRENCY;
        }

        // Both SavedData objects are changed on the server thread as one logical purchase.
        data.unlockTechnology(technologyId, data.kingdomPoints() - kingdomPointCost);
        PlayerProgressionService.debit(
                server,
                actingPlayer,
                PersonalCurrency.FAKHRUL_CURRENCY,
                fakhrulCurrencyCost
        );
        return TechnologyUnlockResult.UNLOCKED;
    }

    // Return all globally persisted technology keys.
    public static Set<String> getUnlockedTechnologies(MinecraftServer server) {
        return KingdomProgressionData.get(server).unlockedTechnologies();
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
        INSUFFICIENT_KINGDOM_POINTS,
        INSUFFICIENT_FAKHRUL_CURRENCY
    }
}
