package ekuzo.crazyworldprogression.currency;

import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Describes a currency without imposing game-specific earning or spending rules. */
public record CurrencyDefinition(
        Identifier id,
        String displayName,
        String abbreviation,
        CurrencyScope scope,
        Identifier icon,
        int iconU,
        int iconV,
        int iconWidth,
        int iconHeight,
        ChatFormatting color,
        List<String> commands
) {
    public CurrencyDefinition {
        if (displayName.isBlank() || abbreviation.isBlank()) {
            throw new IllegalArgumentException("Currency names must not be blank");
        }
        if (iconU < 0 || iconV < 0 || iconWidth <= 0 || iconHeight <= 0) {
            throw new IllegalArgumentException("Currency icon bounds must be positive");
        }
        commands = commands.stream().map(String::trim).filter(command -> !command.isEmpty()).distinct().toList();
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("A currency needs at least one command name");
        }
    }

    public enum CurrencyScope {
        PLAYER,
        GLOBAL
    }
}
