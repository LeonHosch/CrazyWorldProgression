package ekuzo.crazyworldprogression.smithing;

import java.util.Objects;

/**
 * What resmithing at the anvil is called, like ReforgeBranding for the enchanting table. Set it in a common
 * initializer to rename it. The proficiency stat's name is the translation key
 * {@code stat.crazy-world-progression.smithing_proficiency}.
 *
 * @param name         window title, admin tab, "Smithing tier"
 * @param action       the button that resmiths the item
 * @param resultPrefix put before the item's name after a successful resmith
 */
public record SmithingBranding(String name, String action, String resultPrefix) {
    public static final SmithingBranding SMITHING = new SmithingBranding("Craftsmanship", "Repair", "Repaired: ");
    private static volatile SmithingBranding current = SMITHING;

    public SmithingBranding {
        Objects.requireNonNull(name);
        Objects.requireNonNull(action);
        Objects.requireNonNull(resultPrefix);
        if (name.isBlank() || action.isBlank()) throw new IllegalArgumentException("Branding names must not be blank");
    }

    public static SmithingBranding get() { return current; }

    public static void set(SmithingBranding branding) { current = Objects.requireNonNull(branding); }
}
