package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.portals.Parties;
import ekuzo.crazyworldprogression.portals.PartyBook;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code unlockAllyHealing} stat: hits on allies (party members, or vanilla teammates) heal them instead of
 * hurting them. The hit itself still lands (knockback, hurt effects), and the heal is what the hit would have
 * taken from the ally after their armor and protection, so better armor means smaller heals. Melee hits only heal
 * when fully charged; projectile hits (bows and other ranged weapons) heal their damage as well.
 * Such a hit also resets the ally's Hubris stacks. The damage is swapped for the heal in {@code PlayerAllyHealMixin}.
 */
public final class AllyHealing {
    public static final String STAT = "unlockAllyHealing";
    /** Vanilla's threshold for a fully charged swing (crits and sweeps). */
    private static final float CHARGED = 0.9F;
    /** Melee swings of the current tick: whether each was charged (read before vanilla resets the charge). */
    private static final Map<UUID, Swing> SWINGS = new ConcurrentHashMap<>();

    private AllyHealing() { }

    public static void initialize() {
        SkillStatRegistry.registerUnlockStat(STAT, "stat.crazy-world-progression.unlock_ally_healing");
        AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            if (player instanceof ServerPlayer healer)
                SWINGS.put(healer.getUUID(), new Swing(healer.level().getServer().getTickCount(),
                        healer.getAttackStrengthScale(0.5F) > CHARGED));
            return InteractionResult.PASS;
        });
        // Party PvP off: members cannot hurt each other, but hits from members who heal allies still land.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer target) || !(source.getEntity() instanceof ServerPlayer attacker)
                    || attacker == target) return true;
            PartyBook.Party party = Parties.book(target.level().getServer()).partyOf(attacker.getUUID());
            if (party == null || party.pvp() || !party.members().contains(target.getUUID())) return true;
            return SkillStatRegistry.getTotal(attacker, STAT) > 0;
        });
    }

    /**
     * The damage an ally takes from {@code source} once armor and protection are applied: returns 0 and heals
     * them when the attacker heals allies, otherwise the damage unchanged.
     */
    public static float apply(ServerPlayer target, DamageSource source, float damage) {
        if (!(source.getEntity() instanceof ServerPlayer healer) || healer == target || !allies(healer, target)
                || SkillStatRegistry.getTotal(healer, STAT) <= 0) return damage;
        boolean maxedHubris = Hubris.stacks(target) >= Hubris.MAX_STACKS;
        // Any hit from a healing ally, charged or not, clears the target's Hubris stacks: a safe way to drop them.
        Hubris.reset(target);
        // The heal is what the hit would have taken: after armor, and with the ally's damage taken stat.
        float heal = damage * DamageTaken.multiplier(target);
        if (source.getDirectEntity() == healer) {
            Swing swing = SWINGS.get(healer.getUUID());
            boolean charged = swing != null && swing.charged()
                    && swing.tick() == healer.level().getServer().getTickCount();
            if (!charged) heal = 0;
        } else if (!(source.getDirectEntity() instanceof Projectile)) return damage;
        if (heal > 0 && target.isAlive()) {
            float overheal = heal - (target.getMaxHealth() - target.getHealth());
            target.heal(heal);
            // Hidden synergy: at full Hubris the part of the heal above max health becomes golden hearts.
            if (maxedHubris && overheal > 0) Hubris.overheal(target, overheal);
            target.level().sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + target.getBbHeight() + 0.3,
                    target.getZ(), Math.max(1, Math.round(heal / 2)), 0.3, 0.2, 0.3, 0);
        }
        return 0;
    }

    static boolean allies(ServerPlayer first, ServerPlayer second) {
        if (first.isAlliedTo(second)) return true;
        PartyBook.Party party = Parties.book(first.level().getServer()).partyOf(first.getUUID());
        return party != null && party.members().contains(second.getUUID());
    }

    private record Swing(int tick, boolean charged) { }
}
