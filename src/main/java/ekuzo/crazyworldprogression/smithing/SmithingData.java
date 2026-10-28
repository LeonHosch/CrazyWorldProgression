package ekuzo.crazyworldprogression.smithing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** World-wide smithing tiers for resmithing at the anvil: per tier a failure chance and what each item costs. */
public final class SmithingData extends SavedData {
    static final Codec<SmithingData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Tier.CODEC.listOf().optionalFieldOf("tiers", List.of()).forGetter(data -> data.tiers)
    ).apply(instance, SmithingData::new));
    private static final SavedDataType<SmithingData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("anvil_smithing"), SmithingData::new, CODEC, null);

    private final List<Tier> tiers = new ArrayList<>();

    private SmithingData() { }

    private SmithingData(List<Tier> tiers) { this.tiers.addAll(tiers); }

    static SmithingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    List<Tier> tiers() { return Collections.unmodifiableList(new ArrayList<>(tiers)); }

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

    /**
     * failureChance is in hundredths of a percent (2500 = 25%). items lists what resmithing each item costs at this
     * tier; items not listed fall back to the nearest lower tier that lists them.
     */
    public record Tier(Identifier id, String name, int requiredLevel, int failureChance, List<ItemCost> items) {
        static final Codec<Tier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Tier::id),
                Codec.STRING.fieldOf("name").forGetter(Tier::name),
                Codec.INT.optionalFieldOf("required_level", 1).forGetter(Tier::requiredLevel),
                Codec.INT.optionalFieldOf("failure_chance", 0).forGetter(Tier::failureChance),
                ItemCost.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Tier::items)
        ).apply(instance, Tier::new));

        public Tier { items = List.copyOf(items); }

        public ItemCost item(Identifier item) {
            return items.stream().filter(entry -> entry.item().equals(item)).findFirst().orElse(null);
        }
    }

    /** What resmithing one item costs (items and player currencies, as in reforge tiers). */
    public record ItemCost(Identifier item, List<ReforgeData.Cost> costs) {
        static final Codec<ItemCost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("item").forGetter(ItemCost::item),
                ReforgeData.Cost.CODEC.listOf().optionalFieldOf("costs", List.of()).forGetter(ItemCost::costs)
        ).apply(instance, ItemCost::new));

        public ItemCost { costs = List.copyOf(costs); }
    }
}
