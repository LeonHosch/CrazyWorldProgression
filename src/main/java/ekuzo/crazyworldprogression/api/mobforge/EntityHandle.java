package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/** UUID-based handle does not keep an unloaded entity alive in memory. */
public class EntityHandle implements ScriptEntityHandle {
    protected final ServerLevel level;
    protected final UUID id;
    private boolean knownDead;
    public EntityHandle(Entity entity) {
        if (!(entity.level() instanceof ServerLevel world)) throw new IllegalArgumentException("Script handle requires a server world");
        level = world;
        id = entity.getUUID();
        knownDead = !entity.isAlive();
    }
    public EntityHandle(ServerLevel level, UUID id) { this.level = Objects.requireNonNull(level); this.id = Objects.requireNonNull(id); }
    protected Entity entity() {
        Entity entity = level.getEntity(id);
        if (entity == null) throw new IllegalStateException("Runtime entity " + id + " is unavailable in " + level.dimension().identifier());
        return entity;
    }
    @Override public UUID uuid() { return id; }
    @Override public Identifier entityId() { return BuiltInRegistries.ENTITY_TYPE.getKey(entity().getType()); }
    /**
     * A mob in a chunk that is not loaded (scripts in the open world, far from players) still counts as alive; its
     * death is reported through {@link #markDead()}.
     */
    @Override public boolean isAlive() {
        if (knownDead) return false;
        Entity current = level.getEntity(id);
        if (current == null) return true;
        knownDead = !current.isAlive();
        return !knownDead;
    }
    /** Health of an unloaded mob is unknown and counts as 0 (of 0). */
    @Override public double health() {
        Entity entity = level.getEntity(id);
        if (entity == null) return 0;
        return entity instanceof LivingEntity living ? living.getHealth() : (entity.isAlive() ? 1 : 0);
    }
    @Override public double maxHealth() {
        Entity entity = level.getEntity(id);
        if (entity == null) return 0;
        return entity instanceof LivingEntity living ? living.getMaxHealth() : 1;
    }
    @Override public ServerLevel world() { return level; }
    @Override public Vec3 position() { return entity().position(); }
    @Override public void teleport(Vec3 position) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
            throw new IllegalArgumentException("Teleport position must be finite");
        entity().teleportTo(position.x, position.y, position.z);
    }
    @Override public void kill() { entity().kill(level); knownDead = true; }
    /** Called from the server death event so wait can distinguish death from chunk unload. */
    public void markDead() { knownDead = true; }
}
