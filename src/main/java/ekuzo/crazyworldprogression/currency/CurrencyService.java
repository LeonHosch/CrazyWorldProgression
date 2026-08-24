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
    private CurrencyService() {
    }

    public static long getBalance(MinecraftServer server, UUID playerUuid, Identifier currencyId) {
        CurrencyDefinition currency = CurrencyRegistry.require(currencyId);
        return currency.scope() == GLOBAL
                ? GlobalProgressionData.get(server).balance(currencyId)
                : PlayerProgressionData.get(server).balance(playerUuid, currencyId);
    }

    public static BalanceChange credit(MinecraftServer server, UUID playerUuid, Identifier currencyId, long amount) {
        requireNonNegative(amount);
        long previous = getBalance(server, playerUuid, currencyId);
        long updated = Math.addExact(previous, amount);
        setRaw(server, playerUuid, currencyId, updated);
        return new BalanceChange(previous, updated);
    }

    public static BalanceChange debit(MinecraftServer server, UUID playerUuid, Identifier currencyId, long amount) {
        requireNonNegative(amount);
        long previous = getBalance(server, playerUuid, currencyId);
        long updated = Math.max(0L, previous - amount);
        setRaw(server, playerUuid, currencyId, updated);
        return new BalanceChange(previous, updated);
    }

    public static BalanceChange setBalance(MinecraftServer server, UUID playerUuid, Identifier currencyId, long amount) {
        requireNonNegative(amount);
        long previous = getBalance(server, playerUuid, currencyId);
        setRaw(server, playerUuid, currencyId, amount);
        return new BalanceChange(previous, amount);
    }

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

    private static void requireNonNegative(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
    }
}

