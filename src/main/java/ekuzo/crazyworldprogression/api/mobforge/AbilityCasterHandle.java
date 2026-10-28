package ekuzo.crazyworldprogression.api.mobforge;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.Set;

/** Ability access for a mob without a MobForge definition (a vanilla mob, a template mob, a content mob). */
public final class AbilityCasterHandle extends EntityHandle implements MobForgeHandle {
    public AbilityCasterHandle(Entity entity) { super(entity); }

    /** The entity type's ID, as there is no definition. */
    @Override public Identifier mobDefinitionId() { return BuiltInRegistries.ENTITY_TYPE.getKey(entity().getType()); }
    @Override public Set<Identifier> abilityIds() { return MobAbilities.of(entity()); }
    @Override public Set<String> tags() { return Set.copyOf(entity().entityTags()); }
    @Override public Optional<String> phase() { return MobForgeEntityData.phase(entity()); }
    @Override public MobRuntime runtime() {
        Entity entity = entity();
        return new MobRuntime(entity.getUUID(), mobDefinitionId(), MobForgeEntityData.phase(entity),
                MobForgeEntityData.encounter(entity), MobForgeEntityData.pocket(entity), MobForgeEntityData.execution(entity));
    }
    @Override public void setPhase(String phase) {
        String previous = phase().orElse(null);
        if (phase.equals(previous)) return;
        MobForgeEntityData.setPhase(entity(), phase);
        MobForgeEvents.phaseChanged(this, previous, phase);
    }
    @Override public boolean castAbility(Identifier abilityId) { return castAbility(abilityId, null); }
    @Override public boolean castAbility(Identifier abilityId, Entity target) { return MobAbilities.cast(this, abilityId, target); }
}
