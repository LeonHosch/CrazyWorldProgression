package ekuzo.crazyworldprogression.currency;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registration point used by dependent mods during common initialization. */
public final class CurrencyRegistry {
    private static final Map<Identifier, CurrencyDefinition> CURRENCIES = new LinkedHashMap<>();
    private static final Map<String, Identifier> COMMAND_OWNERS = new LinkedHashMap<>();

    private CurrencyRegistry() {
    }

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

    public static synchronized CurrencyDefinition get(Identifier id) {
        return CURRENCIES.get(id);
    }

    public static synchronized CurrencyDefinition require(Identifier id) {
        CurrencyDefinition currency = get(id);
        if (currency == null) {
            throw new IllegalArgumentException("Unknown currency: " + id);
        }
        return currency;
    }

    public static synchronized List<CurrencyDefinition> values() {
        return List.copyOf(CURRENCIES.values());
    }
}
