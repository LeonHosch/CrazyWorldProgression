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

/** World-owned metadata for structure templates captured and hosted directly by CWP. */
final class PortalTemplateData extends SavedData {
    private static final Codec<Map<String, PortalTemplate>> TEMPLATES_CODEC =
            Codec.unboundedMap(Codec.STRING, PortalTemplate.CODEC);
    static final Codec<PortalTemplateData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TEMPLATES_CODEC.optionalFieldOf("templates", Map.of()).forGetter(data -> data.templates)
    ).apply(instance, PortalTemplateData::new));
    static final SavedDataType<PortalTemplateData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("portal_templates"), PortalTemplateData::new, CODEC, null);

    private final Map<String, PortalTemplate> templates = new LinkedHashMap<>();

    // Create empty template metadata for a new world.
    PortalTemplateData() {
    }

    // Copy decoded template metadata into deterministic mutable storage.
    private PortalTemplateData(Map<String, PortalTemplate> templates) {
        this.templates.putAll(templates);
    }

    // Load the server-wide metadata from overworld storage.
    static PortalTemplateData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Return an immutable template snapshot.
    Map<String, PortalTemplate> values() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(templates));
    }

    // Store captured metadata and mark it for saving.
    void put(PortalTemplate template) {
        templates.put(template.id().toString(), template);
        setDirty();
    }

    // Remove template metadata and mark it for saving.
    boolean remove(Identifier id) {
        if (templates.remove(id.toString()) == null) return false;
        setDirty();
        return true;
    }

    /** Size and relative entry position paired with one world-generated structure NBT file. */
    record PortalTemplate(Identifier id, int sizeX, int sizeY, int sizeZ,
                          int spawnX, int spawnY, int spawnZ, String author) {
        static final Codec<PortalTemplate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(PortalTemplate::id),
                Codec.INT.fieldOf("size_x").forGetter(PortalTemplate::sizeX),
                Codec.INT.fieldOf("size_y").forGetter(PortalTemplate::sizeY),
                Codec.INT.fieldOf("size_z").forGetter(PortalTemplate::sizeZ),
                Codec.INT.fieldOf("spawn_x").forGetter(PortalTemplate::spawnX),
                Codec.INT.fieldOf("spawn_y").forGetter(PortalTemplate::spawnY),
                Codec.INT.fieldOf("spawn_z").forGetter(PortalTemplate::spawnZ),
                Codec.STRING.optionalFieldOf("author", "unknown").forGetter(PortalTemplate::author)
        ).apply(instance, PortalTemplate::new));

        // Reject corrupt metadata before reconstruction can touch an instance dimension.
        PortalTemplate {
            if (id == null || author == null || sizeX < 1 || sizeY < 1 || sizeZ < 1
                    || spawnX < 0 || spawnY < 0 || spawnZ < 0
                    || spawnX >= sizeX || spawnY >= sizeY || spawnZ >= sizeZ) {
                throw new IllegalArgumentException("Invalid portal template metadata");
            }
        }

        // Return the total captured block count for limits and diagnostics.
        long volume() {
            return (long) sizeX * sizeY * sizeZ;
        }
    }
}
