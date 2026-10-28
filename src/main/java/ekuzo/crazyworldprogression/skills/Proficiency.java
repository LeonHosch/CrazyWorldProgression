package ekuzo.crazyworldprogression.skills;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.minecraft.server.level.ServerPlayer;

/**
 * Proficiency ranks, shared by every proficiency stat (cooking, farming, fishing, enchanting, smithing): everyone
 * starts at Novice (1); skill tree nodes add ranks (+1 each) up to Master (5). A requirement of 0 or 1 is met by
 * everyone.
 */
public final class Proficiency {
    public static final int NOVICE = 1;
    public static final int MAX = 5;
    private static final String[] NAMES = {"Novice", "Apprentice", "Journeyman", "Expert", "Master"};

    private Proficiency() { }

    /** The player's rank for a proficiency stat: Novice plus what skill tree nodes add, at most Master. */
    public static int of(ServerPlayer player, String stat) {
        int added = (int) Math.floor(SkillStatRegistry.getTotal(player, stat) + 1e-9);
        return Math.max(NOVICE, Math.min(MAX, NOVICE + added));
    }

    /** Every rank's name, Novice to Master (for dropdowns). */
    public static java.util.List<String> names() { return java.util.List.of(NAMES); }

    /** A rank from its name ("Journeyman") or number ("3"); blank is Novice. Throws for anything else. */
    public static int parse(String value) {
        if (value == null || value.isBlank()) return NOVICE;
        String text = value.trim();
        for (int index = 0; index < NAMES.length; index++) if (NAMES[index].equalsIgnoreCase(text)) return index + 1;
        try {
            int rank = Integer.parseInt(text);
            if (rank >= 0 && rank <= MAX) return Math.max(NOVICE, rank);
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Pick a rank from Novice to Master");
    }

    /** "Novice" (1, and below) to "Master" (5, and above). */
    public static String name(int rank) { return NAMES[Math.max(NOVICE, Math.min(MAX, rank)) - 1]; }
}
