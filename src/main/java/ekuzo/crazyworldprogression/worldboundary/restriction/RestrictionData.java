package ekuzo.crazyworldprogression.worldboundary.restriction;

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

/** World-persistent collection of administrator-placed restrictions. */
final class RestrictionData extends SavedData {
    private static final Codec<Map<String, Restriction>> RESTRICTIONS_CODEC =
            Codec.unboundedMap(Codec.STRING, Restriction.CODEC);
    static final Codec<RestrictionData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RESTRICTIONS_CODEC.optionalFieldOf("restrictions", Map.of()).forGetter(data -> data.restrictions)
    ).apply(instance, RestrictionData::new));
    static final SavedDataType<RestrictionData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("restrictions"), RestrictionData::new, CODEC, null);

    private final Map<String, Restriction> restrictions = new LinkedHashMap<>();

    // Create empty restriction storage for a new world.
    RestrictionData() {
    }

    // Copy decoded data into deterministic mutable storage.
    private RestrictionData(Map<String, Restriction> restrictions) {
        this.restrictions.putAll(restrictions);
    }

    // Load the server-wide restriction data from overworld persistence.
    static RestrictionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Return an immutable insertion-ordered restriction snapshot.
    Map<String, Restriction> values() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(restrictions));
    }

    // Store or replace one restriction and schedule world data for saving.
    void put(Restriction restriction) {
        restrictions.put(restriction.id().toString(), restriction);
        setDirty();
    }

    // Remove one restriction by ID and mark changed storage dirty.
    Restriction remove(Identifier id) {
        Restriction removed = restrictions.remove(id.toString());
        if (removed != null) setDirty();
        return removed;
    }
}
