package ekuzo.crazyworldprogression.worldboundary.bonus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** World-persistent bonus areas and their spawn radius per area level. */
public final class BonusAreaData extends SavedData {
    /** An extra bonus area: a circle around (x, z) in one dimension. */
    public record Spot(String dimension, double x, double z, double radius) {
        static final Codec<Spot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("dimension").forGetter(Spot::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(Spot::x),
                Codec.DOUBLE.fieldOf("z").forGetter(Spot::z),
                Codec.DOUBLE.fieldOf("radius").forGetter(Spot::radius)
        ).apply(instance, Spot::new));
    }

    static final Codec<BonusAreaData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("level_radii", Map.of()).forGetter(data -> {
                Map<String, Double> out = new LinkedHashMap<>();
                data.levelRadii.forEach((level, radius) -> out.put(Integer.toString(level), radius));
                return out;
            }),
            Codec.unboundedMap(Codec.STRING, Spot.CODEC).optionalFieldOf("spots", Map.of()).forGetter(data -> data.spots)
    ).apply(instance, BonusAreaData::new));
    private static final SavedDataType<BonusAreaData> TYPE = new SavedDataType<>(CrazyWorldProgression.id("bonus_areas"),
            BonusAreaData::new, CODEC, null);

    final TreeMap<Integer, Double> levelRadii = new TreeMap<>();
    final Map<String, Spot> spots = new LinkedHashMap<>();

    private BonusAreaData() { }

    private BonusAreaData(Map<String, Double> radii, Map<String, Spot> spots) {
        radii.forEach((level, radius) -> levelRadii.put(Integer.parseInt(level), radius));
        this.spots.putAll(spots);
    }

    static BonusAreaData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    void changed() { setDirty(); }
}
