package ekuzo.crazyworldprogression.api.mobforge;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/** Immutable view of the minimal state persisted on a spawned entity. */
public record MobRuntime(UUID entityId, Identifier definitionId, Optional<String> phase,
                         Optional<UUID> encounterId, Optional<UUID> pocketInstanceId,
                         Optional<UUID> scriptExecutionId) {
    public static Optional<MobRuntime> of(Entity entity) {
        return MobForgeEntityData.definition(entity).map(id -> new MobRuntime(entity.getUUID(), id,
                MobForgeEntityData.phase(entity), MobForgeEntityData.encounter(entity),
                MobForgeEntityData.pocket(entity), MobForgeEntityData.execution(entity)));
    }
}
