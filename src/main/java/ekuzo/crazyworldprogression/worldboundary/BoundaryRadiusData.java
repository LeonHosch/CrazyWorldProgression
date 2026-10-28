package ekuzo.crazyworldprogression.worldboundary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;

/** World-persistent radius pairs keyed by dimension and exact Advanced World Boundary level. */
final class BoundaryRadiusData extends SavedData {
    static final Codec<AdvancedWorldBoundary.Radii> RADII_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("inner").forGetter(AdvancedWorldBoundary.Radii::inner),
            Codec.DOUBLE.fieldOf("outer").forGetter(AdvancedWorldBoundary.Radii::outer)
    ).apply(instance, AdvancedWorldBoundary.Radii::new));
    static final Codec<BoundaryRadiusData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, RADII_CODEC).optionalFieldOf("radii", Map.of())
                    .forGetter(data -> data.values)
    ).apply(instance, BoundaryRadiusData::new));
    static final SavedDataType<BoundaryRadiusData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("advanced_world_boundary"), BoundaryRadiusData::new, CODEC, null);

    private final Map<String, AdvancedWorldBoundary.Radii> values = new LinkedHashMap<>();

    BoundaryRadiusData() { }

    private BoundaryRadiusData(Map<String, AdvancedWorldBoundary.Radii> values) {
        this.values.putAll(values);
    }

    static BoundaryRadiusData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    Map<String, AdvancedWorldBoundary.Radii> values() {
        return Map.copyOf(values);
    }

    void put(String key, AdvancedWorldBoundary.Radii radii) {
        values.put(key, radii);
        setDirty();
    }

    boolean remove(String key) {
        if (values.remove(key) == null) return false;
        setDirty();
        return true;
    }
}
