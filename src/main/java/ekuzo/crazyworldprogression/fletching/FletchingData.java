package ekuzo.crazyworldprogression.fletching;

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

/**
 * World-wide fletching recipes for the fletching table, in the order the window lists them. A world gets a default
 * recipe for each ArrowType the first time it sees that type; after that recipes only change in the admin panel
 * (deleted ones stay deleted).
 */
public final class FletchingData extends SavedData {
    static final Codec<FletchingData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Recipe.CODEC.listOf().optionalFieldOf("recipes", List.of()).forGetter(data -> data.recipes),
            Codec.BOOL.optionalFieldOf("defaults", false).forGetter(data -> data.defaults),
            Codec.STRING.listOf().optionalFieldOf("known_arrows", List.of()).forGetter(data -> data.known)
    ).apply(instance, FletchingData::new));
    /** The arrows that existed before worlds remembered which defaults they got (known_arrows). */
    private static final List<String> FIRST_ARROWS = List.of("BROADHEAD", "BARBED", "BODKIN", "HEAVY", "PIERCING",
            "DISRUPTION", "EXPLOSIVE");
    private static final SavedDataType<FletchingData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("fletching"), FletchingData::new, CODEC, null);

    private final List<Recipe> recipes = new ArrayList<>();
    private boolean defaults;
    /** Arrow types whose default recipe the world already got: a new arrow type gets one, a deleted one does not. */
    private final List<String> known = new ArrayList<>();

    private FletchingData() { }

    private FletchingData(List<Recipe> recipes, boolean defaults, List<String> known) {
        this.recipes.addAll(recipes);
        this.defaults = defaults;
        this.known.addAll(known.isEmpty() && defaults ? FIRST_ARROWS : known);
    }

    static FletchingData get(MinecraftServer server) {
        FletchingData data = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        for (ArrowType type : ArrowType.values()) {
            if (data.known.contains(type.name())) continue;
            data.recipes.add(defaultRecipe(type));
            data.known.add(type.name());
            data.defaults = true;
            data.setDirty();
        }
        // Broadhead moved from Novice to Journeyman: worlds that still have the old default recipe get the new rank.
        if (!data.known.contains("BROADHEAD_JOURNEYMAN")) {
            Recipe fresh = defaultRecipe(ArrowType.BROADHEAD);
            for (int index = 0; index < data.recipes.size(); index++) {
                Recipe recipe = data.recipes.get(index);
                if (recipe.id().equals(fresh.id()) && recipe.rank() == 1 && recipe.costs().equals(fresh.costs()))
                    data.recipes.set(index, fresh);
            }
            data.known.add("BROADHEAD_JOURNEYMAN");
            data.setDirty();
        }
        return data;
    }

    /** Four normal arrows plus the type's materials make four of it. */
    static Recipe defaultRecipe(ArrowType type) {
        List<ReforgeData.Cost> costs = new ArrayList<>();
        costs.add(new ReforgeData.Cost("item", Identifier.withDefaultNamespace("arrow"), 4));
        for (String material : type.defaultMaterials()) {
            int split = material.lastIndexOf(':');
            costs.add(new ReforgeData.Cost("item", Identifier.parse(material.substring(0, split)),
                    Integer.parseInt(material.substring(split + 1))));
        }
        return new Recipe(CrazyWorldProgression.id(type.path()), CrazyWorldProgression.id(type.path()), 4,
                type.defaultRank(), costs);
    }

    List<Recipe> recipes() { return Collections.unmodifiableList(new ArrayList<>(recipes)); }

    void put(Recipe recipe) {
        for (int index = 0; index < recipes.size(); index++)
            if (recipes.get(index).id().equals(recipe.id())) { recipes.set(index, recipe); setDirty(); return; }
        recipes.add(recipe);
        setDirty();
    }

    void remove(Identifier id) {
        if (recipes.removeIf(recipe -> recipe.id().equals(id))) setDirty();
    }

    void move(Identifier id, int direction) {
        for (int index = 0; index < recipes.size(); index++) {
            if (!recipes.get(index).id().equals(id)) continue;
            int target = index + direction;
            if (target < 0 || target >= recipes.size()) return;
            Collections.swap(recipes, index, target);
            setDirty();
            return;
        }
    }

    /** What one craft makes (output × count), the Craftsmanship rank it needs, and its item and currency costs. */
    public record Recipe(Identifier id, Identifier output, int count, int rank, List<ReforgeData.Cost> costs) {
        static final Codec<Recipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Recipe::id),
                Identifier.CODEC.fieldOf("output").forGetter(Recipe::output),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Recipe::count),
                Codec.INT.optionalFieldOf("rank", 1).forGetter(Recipe::rank),
                ReforgeData.Cost.CODEC.listOf().optionalFieldOf("costs", List.of()).forGetter(Recipe::costs)
        ).apply(instance, Recipe::new));

        public Recipe { costs = List.copyOf(costs); }
    }
}
