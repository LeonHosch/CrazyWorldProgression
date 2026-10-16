package ekuzo.crazyworldprogression.api.mobforge;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.Set;

/** Script and content access to one registered mob; all mutations stay on the server thread. */
public final class RegisteredMobHandle extends EntityHandle implements MobForgeHandle {
    public RegisteredMobHandle(Entity entity) { super(entity); mobDefinitionId(); }
    @Override public Identifier mobDefinitionId() {
        return MobForgeEntityData.definition(entity())
                .orElseThrow(() -> new IllegalStateException("Entity " + id + " has no MobForge definition"));
    }
    private MobDefinition definition() {
        Identifier definitionId = mobDefinitionId();
        return MobForge.mobs().get(definitionId)
                .orElseThrow(() -> new IllegalStateException("MobForge definition " + definitionId + " is unavailable for entity " + id));
    }
    @Override public Set<Identifier> abilityIds() { return definition().abilities(); }
    @Override public Set<String> tags() { return definition().tags(); }
    @Override public Optional<String> phase() { return MobForgeEntityData.phase(entity()); }
    @Override public MobRuntime runtime() { return MobRuntime.of(entity()).orElseThrow(); }
    @Override public void setPhase(String phase) {
        String previous = phase().orElse(null);
        if (phase.equals(previous)) return;
        MobForgeEntityData.setPhase(entity(), phase);
        MobForgeEvents.phaseChanged(this, previous, phase);
    }
    @Override public boolean castAbility(Identifier abilityId) {
        return castAbility(abilityId, null);
    }
    @Override public boolean castAbility(Identifier abilityId, Entity target) {
        if (!abilityIds().contains(abilityId)) throw new IllegalArgumentException("Mob " + mobDefinitionId() + " does not have ability " + abilityId);
        MobAbility ability = MobForge.abilities().get(abilityId)
                .orElseThrow(() -> new IllegalStateException("Unknown MobForge ability " + abilityId)).get();
        if (ability == null) throw new IllegalStateException("MobForge ability factory returned null: " + abilityId);
        long cooldown = ability.cooldownTicks();
        if (cooldown < 0 || cooldown > 1_728_000) throw new IllegalStateException("Cooldown for MobForge ability " + abilityId + " must be between 0 and 1,728,000 ticks");
        long now = level.getGameTime();
        Entity entity = entity();
        if (MobForgeEntityData.cooldown(entity, abilityId) > now) return false;
        MobAbilityContext context = new MobAbilityContext(this, target, level, entity.position());
        if (!ability.canCast(context)) return false;
        MobForgeEvents.abilityStarted(this, abilityId);
        try { ability.execute(context); }
        catch (RuntimeException exception) {
            MobForgeEvents.abilityFailed(this, abilityId, exception);
            throw exception;
        }
        if (cooldown > 0) MobForgeEntityData.setCooldown(entity, abilityId, Math.addExact(now, cooldown));
        MobForgeEvents.abilityCompleted(this, abilityId);
        return true;
    }
}
