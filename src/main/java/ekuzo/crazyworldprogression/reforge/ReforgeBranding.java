package ekuzo.crazyworldprogression.reforge;

import java.util.Objects;

/**
 * What the reforging system is called. CWP calls it Enchanting; a content mod can rename it in its common
 * initializer (it must run on both client and server), for example:
 *
 * <pre>
 * ReforgeBranding.set(new ReforgeBranding("Soulforging", "Reforge soul", "Soul reforged: "));
 * </pre>
 *
 * The window's look is set separately on the client through {@code ReforgeScreenStyle}. The proficiency stat's
 * name comes from the translation key {@code stat.crazy-world-progression.enchanting_proficiency}, which a mod
 * overrides in its own language files.
 *
 * @param name         the system's name: window title, admin tab, "Needs &lt;name&gt; 3"
 * @param action       the button that rolls a new enchantment
 * @param resultPrefix put before the rolled enchantment in the result message
 * @param skill        the proficiency's short name (the stat and the practice skill): "Needs &lt;skill&gt; 3"
 */
public record ReforgeBranding(String name, String action, String resultPrefix, String skill) {
    public static final ReforgeBranding ENCHANTING = new ReforgeBranding("Enchanting", "Enchant", "Enchanted: ");
    private static volatile ReforgeBranding current = ENCHANTING;

    public ReforgeBranding {
        Objects.requireNonNull(name);
        Objects.requireNonNull(action);
        Objects.requireNonNull(resultPrefix);
        Objects.requireNonNull(skill);
        if (name.isBlank() || action.isBlank() || skill.isBlank()) throw new IllegalArgumentException("Branding names must not be blank");
    }

    /** The proficiency is called like the system. */
    public ReforgeBranding(String name, String action, String resultPrefix) { this(name, action, resultPrefix, name); }

    public static ReforgeBranding get() { return current; }

    public static void set(ReforgeBranding branding) { current = Objects.requireNonNull(branding); }
}
