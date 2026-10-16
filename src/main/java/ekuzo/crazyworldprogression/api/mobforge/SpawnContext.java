package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** All location and ownership data travels with a spawn, including in pocket worlds. */
public record SpawnContext(ServerLevel world, Vec3 position, float yaw, float pitch,
                           UUID encounterId, UUID pocketInstanceId, UUID scriptExecutionId,
                           Map<Identifier, String> metadata) {
    public SpawnContext {
        Objects.requireNonNull(world, "spawn world");
        Objects.requireNonNull(position, "spawn position");
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("Spawn coordinates and rotation must be finite");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    public static SpawnContext at(ServerLevel world, Vec3 position) {
        return new SpawnContext(world, position, 0, 0, null, null, null, Map.of());
    }
}
