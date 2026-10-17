package ekuzo.crazyworldprogression.portals;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Tags every non-player entity in an instance slot with the lease it belongs to. Entities saved in chunks
 * that were unloaded during cleanup reload with an old tag in a later lease and are removed then.
 */
public final class InstanceEntityLeases {
    static final String PREFIX = "cwp.lease.";

    private InstanceEntityLeases() { }

    public static void initialize() {
        ServerEntityEvents.ENTITY_LOAD.register(InstanceEntityLeases::onLoad);
    }

    private static void onLoad(Entity entity, ServerLevel level) {
        if (entity instanceof Player) return;
        String slot = level.dimension().identifier().toString();
        if (!PortalRegistry.instanceSlots().contains(level.dimension().identifier())) return;
        PortalData.StoredInstance instance = PortalData.get(level.getServer()).instances().get(slot);
        String current = instance == null ? null : lease(instance);
        String tag = entity.entityTags().stream().filter(value -> value.startsWith(PREFIX)).findFirst().orElse(null);
        if (stale(tag, current)) {
            // Removing an entity while its chunk is still being loaded is unsafe, so do it on the next task.
            level.getServer().execute(entity::discard);
        } else if (tag == null && current != null) {
            entity.addTag(current);
        }
    }

    static String lease(PortalData.StoredInstance instance) {
        return PREFIX + instance.createdEpochMillis();
    }

    /** An entity is stale when it carries a lease tag that is not the slot's current lease. */
    static boolean stale(String entityLease, String currentLease) {
        return entityLease != null && !entityLease.equals(currentLease);
    }
}
