package ekuzo.crazyworldprogression.titles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;

/** World-owned equipped title IDs; definitions come from advancement JSON resources. */
public final class TitleData extends SavedData {
    private static final Codec<TitleData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("equipped", Map.of())
                    .forGetter(data -> data.equipped)
    ).apply(instance, TitleData::new));
    private static final SavedDataType<TitleData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("titles"), TitleData::new, CODEC, null);
    private final Map<String, String> equipped = new LinkedHashMap<>();

    private TitleData() { }
    private TitleData(Map<String, String> equipped) {
        this.equipped.putAll(equipped);
    }
    public static TitleData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    public String equipped(String playerId) { return equipped.getOrDefault(playerId, ""); }
    public void equip(String playerId, String id) {
        if (id.isEmpty()) equipped.remove(playerId);
        else equipped.put(playerId, id);
        setDirty();
    }
}
