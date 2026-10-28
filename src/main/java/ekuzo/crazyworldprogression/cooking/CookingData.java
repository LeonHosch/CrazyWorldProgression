package ekuzo.crazyworldprogression.cooking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** World-wide cooking recipes for the cauldron (stews) and the smoker. */
public final class CookingData extends SavedData {
    static final Codec<CookingData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Recipe.CODEC.listOf().optionalFieldOf("recipes", List.of()).forGetter(data -> data.recipes)
    ).apply(instance, CookingData::new));
    private static final SavedDataType<CookingData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("cooking"), CookingData::new, CODEC, null);

    private final List<Recipe> recipes = new ArrayList<>();

    private CookingData() { }

    private CookingData(List<Recipe> recipes) { this.recipes.addAll(recipes); }

    static CookingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
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

    /** Where a recipe is cooked: a full water cauldron over a lit campfire (with a bowl), or a smoker (with coal). */
    public enum Station {
        STEW("Cauldron"), SMOKER("Smoker");

        private final String label;

        Station(String label) { this.label = label; }

        public String label() { return label; }

        public String key() { return name().toLowerCase(Locale.ROOT); }

        public static Station parse(String value) {
            for (Station station : values())
                if (station.key().equalsIgnoreCase(value.trim()) || station.label.equalsIgnoreCase(value.trim())) return station;
            throw new IllegalArgumentException("Station must be stew or smoker");
        }
    }

    /**
     * One to three ingredients (any order, one of each per slot), what they make, and the cooking level needed.
     * Each cook also uses one bowl (stew) or one coal (smoker).
     */
    public record Recipe(Identifier id, Station station, List<Identifier> ingredients, Identifier output, int count,
                         int level) {
        static final Codec<Recipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Recipe::id),
                Codec.STRING.xmap(Station::parse, Station::key).fieldOf("station").forGetter(Recipe::station),
                Identifier.CODEC.listOf().optionalFieldOf("ingredients", List.of()).forGetter(Recipe::ingredients),
                Identifier.CODEC.fieldOf("output").forGetter(Recipe::output),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Recipe::count),
                Codec.INT.optionalFieldOf("level", 0).forGetter(Recipe::level)
        ).apply(instance, Recipe::new));

        public Recipe { ingredients = List.copyOf(ingredients); }

        /** Whether these slot items (empty slots left out) are exactly this recipe's ingredients, in any order. */
        public boolean matches(Station at, List<Identifier> items) {
            if (at != station || items.size() != ingredients.size()) return false;
            List<Identifier> remaining = new ArrayList<>(ingredients);
            for (Identifier item : items) if (!remaining.remove(item)) return false;
            return remaining.isEmpty();
        }
    }
}
