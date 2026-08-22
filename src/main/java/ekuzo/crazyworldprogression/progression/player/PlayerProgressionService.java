package ekuzo.crazyworldprogression.progression.player;

import ekuzo.crazyworldprogression.progression.BalanceChange;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public final class PlayerProgressionService {
    // Prevent this static progression service from being instantiated.
    private PlayerProgressionService() {
    }

    // Read a regular personal currency balance.
    public static long getBalance(MinecraftServer server, UUID playerUuid, PersonalCurrency currency) {
        return PlayerProgressionData.get(server).balance(playerUuid, currency);
    }

    // Add a non-negative amount to a regular personal currency with overflow checking.
    public static BalanceChange credit(
            MinecraftServer server,
            UUID playerUuid,
            PersonalCurrency currency,
            long amount
    ) {
        requireNonNegative(amount);
        PlayerProgressionData data = PlayerProgressionData.get(server);
        long previous = data.balance(playerUuid, currency);
        long updated = Math.addExact(previous, amount);
        data.setBalance(playerUuid, currency, updated);
        return new BalanceChange(previous, updated);
    }

    // Remove up to the requested amount from a regular personal currency.
    public static BalanceChange debit(
            MinecraftServer server,
            UUID playerUuid,
            PersonalCurrency currency,
            long amount
    ) {
        requireNonNegative(amount);
        PlayerProgressionData data = PlayerProgressionData.get(server);
        long previous = data.balance(playerUuid, currency);
        long updated = Math.max(0L, previous - amount);
        data.setBalance(playerUuid, currency, updated);
        return new BalanceChange(previous, updated);
    }

    // Replace a regular personal currency balance with an exact non-negative value.
    public static BalanceChange setBalance(
            MinecraftServer server,
            UUID playerUuid,
            PersonalCurrency currency,
            long amount
    ) {
        requireNonNegative(amount);
        PlayerProgressionData data = PlayerProgressionData.get(server);
        long previous = data.balance(playerUuid, currency);
        data.setBalance(playerUuid, currency, amount);
        return new BalanceChange(previous, amount);
    }

    // Read a player's spendable Powerful Souls balance.
    public static long getPowerfulSouls(MinecraftServer server, UUID playerUuid) {
        return PlayerProgressionData.get(server).powerfulSouls(playerUuid);
    }

    // Add Powerful Souls for administration without modifying claim eligibility.
    public static BalanceChange creditPowerfulSoulsForAdministration(
            MinecraftServer server,
            UUID playerUuid,
            long amount
    ) {
        // Administrative changes intentionally do not alter the lifetime claim ledger.
        requireNonNegative(amount);
        PlayerProgressionData data = PlayerProgressionData.get(server);
        long previous = data.powerfulSouls(playerUuid);
        long updated = Math.addExact(previous, amount);
        data.setPowerfulSouls(playerUuid, updated);
        return new BalanceChange(previous, updated);
    }

    // Remove Powerful Souls for administration without modifying claim history.
    public static BalanceChange debitPowerfulSoulsForAdministration(
            MinecraftServer server,
            UUID playerUuid,
            long amount
    ) {
        requireNonNegative(amount);
        PlayerProgressionData data = PlayerProgressionData.get(server);
        long previous = data.powerfulSouls(playerUuid);
        long updated = Math.max(0L, previous - amount);
        data.setPowerfulSouls(playerUuid, updated);
        return new BalanceChange(previous, updated);
    }

    // Set Powerful Souls for administration without modifying claim history.
    public static BalanceChange setPowerfulSoulsForAdministration(
            MinecraftServer server,
            UUID playerUuid,
            long amount
    ) {
        requireNonNegative(amount);
        PlayerProgressionData data = PlayerProgressionData.get(server);
        long previous = data.powerfulSouls(playerUuid);
        data.setPowerfulSouls(playerUuid, amount);
        return new BalanceChange(previous, amount);
    }

    // Award one unique Powerful Soul while enforcing duplicate and lifetime limits.
    public static PowerfulSoulClaimResult claimPowerfulSoul(
            MinecraftServer server,
            UUID playerUuid,
            Identifier claimId,
            int maximumClaims
    ) {
        if (maximumClaims < 0) {
            throw new IllegalArgumentException("Maximum claims must not be negative");
        }

        PlayerProgressionData data = PlayerProgressionData.get(server);
        String persistedClaimId = claimId.toString();

        // Spending a soul never removes its claim, so limited rewards cannot be earned repeatedly.
        if (data.hasPowerfulSoulClaim(playerUuid, persistedClaimId)) {
            return PowerfulSoulClaimResult.ALREADY_CLAIMED;
        }
        if (data.powerfulSoulClaimCount(playerUuid) >= maximumClaims) {
            return PowerfulSoulClaimResult.CLAIM_LIMIT_REACHED;
        }

        long updatedBalance = Math.addExact(data.powerfulSouls(playerUuid), 1L);
        data.claimPowerfulSoul(playerUuid, persistedClaimId, updatedBalance);
        return PowerfulSoulClaimResult.CLAIMED;
    }

    // Purchase and persist a skill after validating both required balances.
    public static SkillUnlockResult unlockSkill(
            MinecraftServer server,
            UUID playerUuid,
            Identifier skillId,
            long echelonPointCost,
            long powerfulSoulCost
    ) {
        requireNonNegative(echelonPointCost);
        requireNonNegative(powerfulSoulCost);
        PlayerProgressionData data = PlayerProgressionData.get(server);
        String persistedSkillId = skillId.toString();

        // Validate the complete purchase before deducting either required currency.
        if (data.isSkillUnlocked(playerUuid, persistedSkillId)) {
            return SkillUnlockResult.ALREADY_UNLOCKED;
        }
        long echelonPoints = data.balance(playerUuid, PersonalCurrency.ECHELON_POINTS);
        long powerfulSouls = data.powerfulSouls(playerUuid);
        if (echelonPoints < echelonPointCost) {
            return SkillUnlockResult.INSUFFICIENT_ECHELON_POINTS;
        }
        if (powerfulSouls < powerfulSoulCost) {
            return SkillUnlockResult.INSUFFICIENT_POWERFUL_SOULS;
        }

        data.unlockSkill(
                playerUuid,
                persistedSkillId,
                echelonPoints - echelonPointCost,
                powerfulSouls - powerfulSoulCost
        );
        return SkillUnlockResult.UNLOCKED;
    }

    // Reject negative values before they reach persistent progression state.
    private static void requireNonNegative(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
    }

    public enum PowerfulSoulClaimResult {
        CLAIMED,
        ALREADY_CLAIMED,
        CLAIM_LIMIT_REACHED
    }

    public enum SkillUnlockResult {
        UNLOCKED,
        ALREADY_UNLOCKED,
        INSUFFICIENT_ECHELON_POINTS,
        INSUFFICIENT_POWERFUL_SOULS
    }
}
