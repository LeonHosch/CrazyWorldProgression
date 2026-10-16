package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.Set;

/** Controlled MobForge capabilities; scripts never receive mutable runtime internals. */
public interface MobForgeHandle extends ScriptEntityHandle {
    Identifier mobDefinitionId();
    Set<Identifier> abilityIds();
    Set<String> tags();
    Optional<String> phase();
    MobRuntime runtime();
    void setPhase(String phase);
    boolean castAbility(Identifier abilityId);
    boolean castAbility(Identifier abilityId, Entity target);
}
