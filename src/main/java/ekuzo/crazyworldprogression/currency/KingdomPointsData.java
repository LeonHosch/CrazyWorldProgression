package ekuzo.crazyworldprogression.currency;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class KingdomPointsData extends SavedData {
    private final Map<UUID, Integer> points = new HashMap<>();

    public static final Codec<KingdomPointsData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.unboundedMap(Codec.STRING.xmap(UUID::fromString, UUID::toString), Codec.INT)
                            .optionalFieldOf("points", Collections.emptyMap())
                            .forGetter(data -> data.points)
            ).apply(instance, KingdomPointsData::new)
    );

    public static final SavedDataType<KingdomPointsData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(CrazyWorldProgression.MOD_ID, "kingdom_points"),
            KingdomPointsData::new,
            CODEC,
            null
    );

    public KingdomPointsData() {
    }

    public KingdomPointsData(Map<UUID, Integer> initialPoints) {
        this.points.putAll(initialPoints);
    }

    public static KingdomPointsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public int getPoints(UUID playerUuid) {
        return points.getOrDefault(playerUuid, 0);
    }

    public void setPoints(UUID playerUuid, int amount) {
        points.put(playerUuid, Math.max(0, amount));
        setDirty();
    }

    public int addPoints(UUID playerUuid, int amount) {
        int newAmount = Math.max(0, getPoints(playerUuid) + amount);
        points.put(playerUuid, newAmount);
        setDirty();
        return newAmount;
    }

    public int removePoints(UUID playerUuid, int amount) {
        int newAmount = Math.max(0, getPoints(playerUuid) - amount);
        points.put(playerUuid, newAmount);
        setDirty();
        return newAmount;
    }
}