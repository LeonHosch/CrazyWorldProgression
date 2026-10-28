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
    /** The abilities this mob uses on its own (its definition's, unless switched off, plus attached ones). */
    @Override public Set<Identifier> abilityIds() { definition(); return MobAbilities.of(entity()); }
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
    /** Scripts may make the mob cast any registered ability, also one it does not carry. */
    @Override public boolean castAbility(Identifier abilityId, Entity target) {
        return MobAbilities.cast(this, abilityId, target);
    }
}
