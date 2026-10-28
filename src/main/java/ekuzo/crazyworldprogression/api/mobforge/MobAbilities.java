package ekuzo.crazyworldprogression.api.mobforge;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which abilities a mob carries and how any mob casts one.
 *
 * <p>A mob's ability set is its type's default abilities (the abilities of the MobForge definition registered for
 * its entity type, unless its template switched them off with {@code default_abilities: false}) plus the abilities
 * its template attaches. Mobs use their set on their own in combat; scripts ({@code mob_cast}) can make any mob cast
 * any registered ability.
 */
public final class MobAbilities {
    private MobAbilities() { }

    /** The default abilities of an entity type: those of the MobForge definition registered for it, if any. */
    public static Set<Identifier> defaults(EntityType<?> type) {
        for (MobDefinition definition : MobForge.mobs().all().values())
            if (definition.entityType() == type) return definition.abilities();
        return Set.of();
    }

    /** The abilities this mob uses on its own: defaults (unless switched off) plus attached ones. */
    public static Set<Identifier> of(Entity entity) {
        Set<Identifier> out = new LinkedHashSet<>();
        if (MobForgeEntityData.defaultAbilities(entity)) {
            Optional<MobDefinition> definition = MobForgeEntityData.definition(entity).flatMap(MobForge.mobs()::get);
            out.addAll(definition.map(MobDefinition::abilities).orElseGet(() -> defaults(entity.getType())));
        }
        out.addAll(MobForgeEntityData.attachedAbilities(entity));
        return out;
    }

    public static boolean has(Entity entity, Identifier ability) { return of(entity).contains(ability); }

    /** A handle through which content and scripts let this entity cast abilities. */
    public static MobForgeHandle handle(Entity entity) {
        return MobForgeEntityData.definition(entity).flatMap(MobForge.mobs()::get).isPresent()
                ? new RegisteredMobHandle(entity) : new AbilityCasterHandle(entity);
    }

    /**
     * Casts a registered ability as {@code caster}, whether or not the mob carries it. Returns false while it is on
     * cooldown or when the ability refuses ({@link MobAbility#canCast}).
     */
    public static boolean cast(MobForgeHandle caster, Identifier abilityId, Entity target) {
        MobAbility ability = MobForge.abilities().get(abilityId)
                .orElseThrow(() -> new IllegalStateException("Unknown MobForge ability " + abilityId)).get();
        if (ability == null) throw new IllegalStateException("MobForge ability factory returned null: " + abilityId);
        long cooldown = ability.cooldownTicks();
        if (cooldown < 0 || cooldown > 1_728_000)
            throw new IllegalStateException("Cooldown for MobForge ability " + abilityId + " must be between 0 and 1,728,000 ticks");
        ServerLevel level = caster.world();
        long now = level.getGameTime();
        Entity entity = level.getEntity(caster.uuid());
        if (entity == null || !entity.isAlive()) return false;
        if (MobForgeEntityData.cooldown(entity, abilityId) > now) return false;
        MobAbilityContext context = new MobAbilityContext(caster, target, level, entity.position());
        if (!ability.canCast(context)) return false;
        MobForgeEvents.abilityStarted(caster, abilityId);
        try { ability.execute(context); }
        catch (RuntimeException exception) {
            MobForgeEvents.abilityFailed(caster, abilityId, exception);
            throw exception;
        }
        if (cooldown > 0) MobForgeEntityData.setCooldown(entity, abilityId, Math.addExact(now, cooldown));
        MobForgeEvents.abilityCompleted(caster, abilityId);
        return true;
    }
}
