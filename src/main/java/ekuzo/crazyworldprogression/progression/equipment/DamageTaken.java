package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code addDamageTaken} stat as a factor: +10 takes 10% more, -25 takes 25% less, never below 0. It multiplies
 * the damage last, after armor, protection and absorption checks (PlayerAllyHealMixin).
 */
public final class DamageTaken {
    private DamageTaken() { }

    public static float multiplier(ServerPlayer player) {
        return (float) Math.max(0.0, 1.0 + SkillStatRegistry.getTotal(player, SkillStatRegistry.DAMAGE_TAKEN) / 100.0);
    }
}
