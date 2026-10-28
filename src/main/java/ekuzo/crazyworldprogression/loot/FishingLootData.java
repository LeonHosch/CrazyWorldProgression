package ekuzo.crazyworldprogression.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** World-owned fishing locations with rulesets. Legacy rule IDs remain internal to ruleset edits. */
public final class FishingLootData extends SavedData {
    static final Codec<FishingLootData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Rule.CODEC.listOf().optionalFieldOf("rules", List.of()).forGetter(data -> data.rules),
            Codec.STRING.listOf().optionalFieldOf("locations", List.of()).forGetter(data -> data.locations())
    ).apply(instance, FishingLootData::new));
    private static final SavedDataType<FishingLootData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("fishing_loot"), FishingLootData::new, CODEC, null);
    private final List<Rule> rules = new ArrayList<>();
    private final Set<String> locations = new LinkedHashSet<>();

    private FishingLootData() { }
    private FishingLootData(List<Rule> rules, List<String> locations) {
        this.rules.addAll(rules);
        this.locations.addAll(locations);
        // Existing worlds only stored rules. Each old biome/pocket becomes one location.
        rules.forEach(rule -> this.locations.add(rule.biome()));
    }

    public static FishingLootData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Any change to fishing places or rules makes cached fishing journals outdated. */
    @Override public void setDirty() {
        super.setDirty();
        REVISION.incrementAndGet();
    }

    /** Bumped on every change here or in LootPoolData; the fishing journal compares it to what a client cached. */
    static final java.util.concurrent.atomic.AtomicInteger REVISION = new java.util.concurrent.atomic.AtomicInteger();

    public List<Rule> rules() { return List.copyOf(rules); }
    public List<String> locations() { return List.copyOf(locations); }

    void addLocation(String location) {
        if (locations.add(location)) setDirty();
    }

    void removeLocation(String location) {
        if (locations.remove(location)) setDirty();
        if (rules.removeIf(rule -> rule.biome().equals(location))) setDirty();
    }

    void put(Rule rule) {
        locations.add(rule.biome());
        rules.removeIf(existing -> existing.id().equals(rule.id()));
        rules.add(rule);
        setDirty();
    }

    void remove(Identifier id) {
        if (rules.removeIf(rule -> rule.id().equals(id))) setDirty();
    }

    /** Empty biome or # selectors are retained for older worlds; new locations use biome or pocket IDs. */
    public record Rule(Identifier id, Identifier pool, String biome, String liquid, String weather, String time,
                       int weight) {
        public Rule(Identifier id, Identifier pool, String biome, String liquid, String weather, String time) {
            this(id, pool, biome, liquid, weather, time, 100);
        }

        public static final Codec<Rule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Rule::id),
                Identifier.CODEC.fieldOf("pool").forGetter(Rule::pool),
                Codec.STRING.optionalFieldOf("biome", "").forGetter(Rule::biome),
                Codec.STRING.optionalFieldOf("liquid", "").forGetter(Rule::liquid),
                Codec.STRING.optionalFieldOf("weather", "any").forGetter(Rule::weather),
                Codec.STRING.optionalFieldOf("time", "any").forGetter(Rule::time),
                Codec.INT.optionalFieldOf("weight", 100).forGetter(Rule::weight)
        ).apply(instance, Rule::new));
    }
}
