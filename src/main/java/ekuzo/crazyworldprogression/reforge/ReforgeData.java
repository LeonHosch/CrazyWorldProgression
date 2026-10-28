package ekuzo.crazyworldprogression.reforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** World-wide reforge (enchanting) tiers: costs, required proficiency, root pool, and their nested enchantment pools. */
public final class ReforgeData extends SavedData {
    static final Codec<ReforgeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Tier.CODEC.listOf().optionalFieldOf("tiers", List.of()).forGetter(data -> data.tiers),
            Codec.unboundedMap(Codec.STRING, Pool.CODEC).optionalFieldOf("pools", Map.of()).forGetter(data -> data.pools)
    ).apply(instance, ReforgeData::new));
    private static final SavedDataType<ReforgeData> TYPE = new SavedDataType<>(
            // Saved as "smithing" because the enchanting tiers were first stored under that name.
            CrazyWorldProgression.id("smithing"), ReforgeData::new, CODEC, null);

    private final List<Tier> tiers = new ArrayList<>();
    private final Map<String, Pool> pools = new LinkedHashMap<>();

    private ReforgeData() { }

    private ReforgeData(List<Tier> tiers, Map<String, Pool> pools) {
        this.tiers.addAll(tiers);
        this.pools.putAll(pools);
    }

    static ReforgeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    List<Tier> tiers() { return Collections.unmodifiableList(new ArrayList<>(tiers)); }

    Map<String, Pool> pools() { return Collections.unmodifiableMap(new LinkedHashMap<>(pools)); }

    void putTier(Tier tier) {
        for (int index = 0; index < tiers.size(); index++)
            if (tiers.get(index).id().equals(tier.id())) { tiers.set(index, tier); setDirty(); return; }
        tiers.add(tier);
        setDirty();
    }

    void removeTier(Identifier id) {
        if (tiers.removeIf(tier -> tier.id().equals(id))) setDirty();
    }

    void moveTier(Identifier id, int direction) {
        for (int index = 0; index < tiers.size(); index++) {
            if (!tiers.get(index).id().equals(id)) continue;
            int target = index + direction;
            if (target < 0 || target >= tiers.size()) return;
            Collections.swap(tiers, index, target);
            setDirty();
            return;
        }
    }

    void putPool(Pool pool) {
        pools.put(pool.id().toString(), pool);
        setDirty();
    }

    void removePool(Identifier id) {
        if (pools.remove(id.toString()) != null) setDirty();
    }

    /** One selectable tier at the enchanting table. */
    public record Tier(Identifier id, String name, int requiredLevel, List<Cost> costs, Identifier pool) {
        static final Codec<Tier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Tier::id),
                Codec.STRING.fieldOf("name").forGetter(Tier::name),
                Codec.INT.optionalFieldOf("required_level", 1).forGetter(Tier::requiredLevel),
                Cost.CODEC.listOf().optionalFieldOf("costs", List.of()).forGetter(Tier::costs),
                Identifier.CODEC.fieldOf("pool").forGetter(Tier::pool)
        ).apply(instance, Tier::new));

        public Tier { costs = List.copyOf(costs); }
    }

    /** What one reforge at a tier costs: an item (kind "item") or a player currency (kind "currency"). */
    public record Cost(String kind, Identifier target, int amount) {
        public static final Codec<Cost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("kind").forGetter(Cost::kind),
                Identifier.CODEC.fieldOf("target").forGetter(Cost::target),
                Codec.INT.fieldOf("amount").forGetter(Cost::amount)
        ).apply(instance, Cost::new));
    }

    /** A pool picks one entry by weight among the entries that suit the item. */
    public record Pool(Identifier id, String name, List<Entry> entries) {
        static final Codec<Pool> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Pool::id),
                Codec.STRING.fieldOf("name").forGetter(Pool::name),
                Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(Pool::entries)
        ).apply(instance, Pool::new));

        public Pool { entries = List.copyOf(entries); }
    }

    /** kind "enchantment" (target = enchantment ID, with level) or "pool" (target = nested pool ID). */
    public record Entry(String kind, Identifier target, int level, int weight) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("kind").forGetter(Entry::kind),
                Identifier.CODEC.fieldOf("target").forGetter(Entry::target),
                Codec.INT.optionalFieldOf("level", 1).forGetter(Entry::level),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(Entry::weight)
        ).apply(instance, Entry::new));
    }
}
