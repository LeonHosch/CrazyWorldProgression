package ekuzo.crazyworldprogression.currency;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registration point used by dependent mods during common initialization. */
public final class CurrencyRegistry {
    private static final Map<Identifier, CurrencyDefinition> CURRENCIES = new LinkedHashMap<>();
    private static final Map<String, Identifier> COMMAND_OWNERS = new LinkedHashMap<>();

    // Prevent creation of registry instances; all mods share this process-wide registration table.
    private CurrencyRegistry() {
    }

    // Add one definition while rejecting identifiers and command aliases already claimed by another currency.
    public static synchronized CurrencyDefinition register(CurrencyDefinition currency) {
        for (String command : currency.commands()) {
            Identifier owner = COMMAND_OWNERS.get(command);
            if (owner != null) {
                throw new IllegalArgumentException("Currency command '" + command + "' is already owned by " + owner);
            }
        }
        if (CURRENCIES.putIfAbsent(currency.id(), currency) != null) {
            throw new IllegalArgumentException("Duplicate currency id: " + currency.id());
        }
        currency.commands().forEach(command -> COMMAND_OWNERS.put(command, currency.id()));
        return currency;
    }

    // Look up a definition without failing when an optional integration has not registered it.
    public static synchronized CurrencyDefinition get(Identifier id) {
        return CURRENCIES.get(id);
    }

    // Resolve a required definition and fail early with the missing identifier in the error message.
    public static synchronized CurrencyDefinition require(Identifier id) {
        CurrencyDefinition currency = get(id);
        if (currency == null) {
            throw new IllegalArgumentException("Unknown currency: " + id);
        }
        return currency;
    }

    // Return currencies in registration order so commands and GUI balance rows remain deterministic.
    public static synchronized List<CurrencyDefinition> values() {
        return List.copyOf(CURRENCIES.values());
    }
}
