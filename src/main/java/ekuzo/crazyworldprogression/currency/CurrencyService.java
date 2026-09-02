package ekuzo.crazyworldprogression.currency;

import ekuzo.crazyworldprogression.progression.BalanceChange;
import ekuzo.crazyworldprogression.progression.global.GlobalProgressionData;
import ekuzo.crazyworldprogression.progression.player.PlayerProgressionData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

import static ekuzo.crazyworldprogression.currency.CurrencyDefinition.CurrencyScope.GLOBAL;

/** Generic, server-authoritative wallet operations for every registered currency. */
public final class CurrencyService {
    // Prevent instantiation of the server-side wallet facade.
    private CurrencyService() {
    }

    // Route a balance read to the shared global wallet or the requested player's wallet based on the definition.
    public static long getBalance(MinecraftServer server, UUID playerUuid, Identifier currencyId) {
        CurrencyDefinition currency = CurrencyRegistry.require(currencyId);
        return currency.scope() == GLOBAL
                ? GlobalProgressionData.get(server).balance(currencyId)
                : PlayerProgressionData.get(server).balance(playerUuid, currencyId);
    }

    // Add an amount with overflow detection and return both the old and new balances for command feedback.
    public static BalanceChange credit(MinecraftServer server, UUID playerUuid, Identifier currencyId, long amount) {
        requireNonNegative(amount);
        long previous = getBalance(server, playerUuid, currencyId);
        long updated = Math.addExact(previous, amount);
        setRaw(server, playerUuid, currencyId, updated);
        return new BalanceChange(previous, updated);
    }

    // Remove at most the available balance, clamping at zero rather than allowing negative currency.
    public static BalanceChange debit(MinecraftServer server, UUID playerUuid, Identifier currencyId, long amount) {
        requireNonNegative(amount);
        long previous = getBalance(server, playerUuid, currencyId);
        long updated = Math.max(0L, previous - amount);
        setRaw(server, playerUuid, currencyId, updated);
        return new BalanceChange(previous, updated);
    }

    // Replace a wallet balance with an exact validated amount.
    public static BalanceChange setBalance(MinecraftServer server, UUID playerUuid, Identifier currencyId, long amount) {
        requireNonNegative(amount);
        long previous = getBalance(server, playerUuid, currencyId);
        setRaw(server, playerUuid, currencyId, amount);
        return new BalanceChange(previous, amount);
    }

    // Persist an already-validated amount in the storage object selected by the currency's ownership scope.
    private static void setRaw(MinecraftServer server, UUID playerUuid, Identifier currencyId, long amount) {
        CurrencyDefinition currency = CurrencyRegistry.require(currencyId);
        if (currency.scope() == GLOBAL) {
            GlobalProgressionData.get(server).setBalance(currencyId, amount);
        } else {
            if (playerUuid == null) {
                throw new IllegalArgumentException("A player UUID is required for " + currencyId);
            }
            PlayerProgressionData.get(server).setBalance(playerUuid, currencyId, amount);
        }
    }

    // Reject negative API inputs before they can reach persistent state or turn a debit into a credit.
    private static void requireNonNegative(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
    }
}

