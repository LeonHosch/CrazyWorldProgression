package ekuzo.crazyworldprogression.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * World-owned loot pool catalogue.
 * Format 2 stores a pick-one pool's odds in each entry's chance (as relative shares); format 1 used weights.
 */
public final class LootPoolData extends SavedData {
    static final int FORMAT = 2;
    static final Codec<LootPoolData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Pool.CODEC).optionalFieldOf("pools", Map.of())
                    .forGetter(data -> data.pools),
            Codec.INT.optionalFieldOf("format", 1).forGetter(data -> FORMAT)
    ).apply(instance, LootPoolData::new));
    private static final SavedDataType<LootPoolData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("loot_pools"), LootPoolData::new, CODEC, null);
    private final Map<String, Pool> pools = new LinkedHashMap<>();

    LootPoolData() { }
    private LootPoolData(Map<String, Pool> pools, int format) {
        pools.forEach((key, pool) -> this.pools.put(key, format < 2 ? migrateWeights(pool) : pool));
        if (format < FORMAT && !pools.isEmpty()) setDirty();
    }
    /** Old pick-one pools ignored chance and used integer weights; keep their odds by turning weights into shares. */
    static Pool migrateWeights(Pool pool) {
        if (!pool.mode().equals("one")) return pool;
        int total = pool.entries().stream().mapToInt(Entry::weight).sum();
        if (total <= 0) return pool;
        List<Entry> entries = pool.entries().stream().map(entry -> new Entry(entry.kind(), entry.target(),
                (int) Math.max(1, Math.round(entry.weight() * 10000.0 / total)), entry.weight(), entry.min(),
                entry.max(), entry.showInChat())).toList();
        return new Pool(pool.id(), pool.name(), pool.mode(), entries);
    }
    public static LootPoolData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
    public Map<String, Pool> values() { return Map.copyOf(pools); }
    void put(Pool pool) { pools.put(pool.id().toString(), pool); setDirty(); }
    void remove(Identifier id) { pools.remove(id.toString()); setDirty(); }

    public record Pool(Identifier id, String name, String mode, List<Entry> entries) {
        public static final Codec<Pool> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Pool::id),
                Codec.STRING.fieldOf("name").forGetter(Pool::name),
                Codec.STRING.fieldOf("mode").forGetter(Pool::mode),
                Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(Pool::entries)
        ).apply(instance, Pool::new));
        public Pool { entries = List.copyOf(entries); }
    }

    public record Entry(String kind, Identifier target, int chance, int weight, int min, int max,
                        boolean showInChat) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("kind").forGetter(Entry::kind),
                Identifier.CODEC.fieldOf("target").forGetter(Entry::target),
                Codec.INT.optionalFieldOf("chance", 10000).forGetter(Entry::chance),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(Entry::weight),
                Codec.INT.optionalFieldOf("min", 1).forGetter(Entry::min),
                Codec.INT.optionalFieldOf("max", 1).forGetter(Entry::max),
                Codec.BOOL.optionalFieldOf("show_in_chat", false).forGetter(Entry::showInChat)
        ).apply(instance, Entry::new));

        public Entry(String kind, Identifier target, int chance, int weight, int min, int max) {
            this(kind, target, chance, weight, min, max, false);
        }
    }
}
