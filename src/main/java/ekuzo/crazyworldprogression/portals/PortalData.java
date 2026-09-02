package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent placed portal definitions and temporary instance-slot leases. */
final class PortalData extends SavedData {
    private static final Codec<Map<String, PortalDefinition>> PORTALS_CODEC =
            Codec.unboundedMap(Codec.STRING, PortalDefinition.CODEC);
    private static final Codec<Map<String, StoredInstance>> INSTANCES_CODEC =
            Codec.unboundedMap(Codec.STRING, StoredInstance.CODEC);
    static final Codec<PortalData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PORTALS_CODEC.optionalFieldOf("portals", Map.of()).forGetter(data -> data.portals),
            INSTANCES_CODEC.optionalFieldOf("instances", Map.of()).forGetter(data -> data.instances)
    ).apply(instance, PortalData::new));
    static final SavedDataType<PortalData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("portals"), PortalData::new, CODEC, null);

    private final Map<String, PortalDefinition> portals = new LinkedHashMap<>();
    private final Map<String, StoredInstance> instances = new LinkedHashMap<>();

    // Create empty portal and instance state for a new world.
    PortalData() {
    }

    // Copy decoded maps into deterministic mutable storage.
    private PortalData(Map<String, PortalDefinition> portals, Map<String, StoredInstance> instances) {
        this.portals.putAll(portals);
        this.instances.putAll(instances);
    }

    // Load the server-wide data from overworld storage.
    static PortalData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Return an immutable snapshot of placed portals.
    Map<String, PortalDefinition> portals() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(portals));
    }

    // Return an immutable snapshot of active instance leases.
    Map<String, StoredInstance> instances() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(instances));
    }

    // Create or replace one placed portal.
    void putPortal(PortalDefinition portal) {
        portals.put(portal.id().toString(), portal);
        setDirty();
    }

    // Remove one portal definition.
    boolean removePortal(Identifier id) {
        if (portals.remove(id.toString()) == null) return false;
        setDirty();
        return true;
    }

    // Persist a newly allocated or restored instance lease under its source portal.
    void putInstance(StoredInstance instance) {
        instances.put(instance.portalId().toString(), instance);
        setDirty();
    }

    // Release the instance leased by one source portal.
    StoredInstance removeInstance(Identifier portalId) {
        StoredInstance removed = instances.remove(portalId.toString());
        if (removed != null) setDirty();
        return removed;
    }

    /** One temporary reconstructed template occupying a reusable registered dimension slot. */
    record StoredInstance(Identifier portalId, Identifier templateId, Identifier slotDimension,
                          long createdEpochMillis, long expiresEpochMillis,
                          int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
        static final Codec<StoredInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("portal").forGetter(StoredInstance::portalId),
                Identifier.CODEC.fieldOf("template").forGetter(StoredInstance::templateId),
                Identifier.CODEC.fieldOf("slot_dimension").forGetter(StoredInstance::slotDimension),
                Codec.LONG.fieldOf("created_epoch_millis").forGetter(StoredInstance::createdEpochMillis),
                Codec.LONG.fieldOf("expires_epoch_millis").forGetter(StoredInstance::expiresEpochMillis),
                Codec.INT.fieldOf("origin_x").forGetter(StoredInstance::originX),
                Codec.INT.fieldOf("origin_y").forGetter(StoredInstance::originY),
                Codec.INT.fieldOf("origin_z").forGetter(StoredInstance::originZ),
                Codec.INT.fieldOf("size_x").forGetter(StoredInstance::sizeX),
                Codec.INT.fieldOf("size_y").forGetter(StoredInstance::sizeY),
                Codec.INT.fieldOf("size_z").forGetter(StoredInstance::sizeZ)
        ).apply(instance, StoredInstance::new));

        // Reject incomplete leases that cannot be safely cleaned later.
        StoredInstance {
            if (portalId == null || templateId == null || slotDimension == null
                    || expiresEpochMillis <= createdEpochMillis || sizeX < 1 || sizeY < 1 || sizeZ < 1) {
                throw new IllegalArgumentException("Invalid temporary portal instance");
            }
        }

        // Return a copy whose shortened deadline survives a restart during forced batched cleanup.
        StoredInstance expiringAt(long epochMillis) {
            return new StoredInstance(portalId, templateId, slotDimension, createdEpochMillis,
                    Math.max(createdEpochMillis + 1L, epochMillis), originX, originY, originZ,
                    sizeX, sizeY, sizeZ);
        }
    }
}
