package ekuzo.crazyworldprogression.buildings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;

/** World-owned catalogue of independent, saved building snapshots. */
final class BuildingData extends SavedData {
    static final Codec<BuildingData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Building.CODEC).optionalFieldOf("buildings", Map.of())
                    .forGetter(data -> data.buildings),
            Codec.unboundedMap(Codec.STRING, Placement.CODEC.listOf()).optionalFieldOf("placements", Map.of())
                    .forGetter(data -> data.placements)
    ).apply(instance, BuildingData::new));
    private static final SavedDataType<BuildingData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("buildings"), BuildingData::new, CODEC, null);
    private final Map<String, Building> buildings = new LinkedHashMap<>();
    private final Map<String, java.util.List<Placement>> placements = new LinkedHashMap<>();

    BuildingData() { }
    private BuildingData(Map<String, Building> buildings, Map<String, java.util.List<Placement>> placements) {
        this.buildings.putAll(buildings);
        placements.forEach((key, value) -> this.placements.put(key, java.util.List.copyOf(value)));
    }
    static BuildingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    Map<String, Building> values() { return Map.copyOf(buildings); }
    void put(Building building) { buildings.put(building.id().toString(), building); setDirty(); }
    boolean remove(Identifier id) {
        if (buildings.remove(id.toString()) == null) return false;
        setDirty(); return true;
    }

    java.util.List<Placement> placements(Identifier dimension) {
        return placements.getOrDefault(dimension.toString(), java.util.List.of());
    }
    void addPlacement(Identifier dimension, Placement placement) {
        var updated = new java.util.ArrayList<>(placements(dimension));
        updated.removeIf(value -> value.origin() == placement.origin());
        updated.add(placement);
        placements.put(dimension.toString(), java.util.List.copyOf(updated));
        setDirty();
    }
    void removePlacement(Identifier dimension, long origin) {
        var updated = new java.util.ArrayList<>(placements(dimension));
        if (updated.removeIf(value -> value.origin() == origin)) {
            if (updated.isEmpty()) placements.remove(dimension.toString());
            else placements.put(dimension.toString(), java.util.List.copyOf(updated));
            setDirty();
        }
    }

    record Placement(Identifier id, long origin, int sizeX, int sizeY, int sizeZ) {
        static final Codec<Placement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Placement::id),
                Codec.LONG.fieldOf("origin").forGetter(Placement::origin),
                Codec.INT.fieldOf("size_x").forGetter(Placement::sizeX),
                Codec.INT.fieldOf("size_y").forGetter(Placement::sizeY),
                Codec.INT.fieldOf("size_z").forGetter(Placement::sizeZ)
        ).apply(instance, Placement::new));
    }

    record Building(Identifier id, int sizeX, int sizeY, int sizeZ, String author) {
        static final Codec<Building> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Building::id),
                Codec.INT.fieldOf("size_x").forGetter(Building::sizeX),
                Codec.INT.fieldOf("size_y").forGetter(Building::sizeY),
                Codec.INT.fieldOf("size_z").forGetter(Building::sizeZ),
                Codec.STRING.optionalFieldOf("author", "unknown").forGetter(Building::author)
        ).apply(instance, Building::new));
        Building {
            if (id == null || author == null || sizeX < 1 || sizeY < 1 || sizeZ < 1
                    || (long) sizeX * sizeY * sizeZ > BuildingService.MAX_VOLUME)
                throw new IllegalArgumentException("Invalid saved building");
        }
        long volume() { return (long) sizeX * sizeY * sizeZ; }
    }
}
