package ekuzo.crazyworldprogression.farming;

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

/** World-wide crossbreeding recipes: two seeds in, one result out, each pair with its own costs and odds. */
public final class CrossbreedingData extends SavedData {
    static final Codec<CrossbreedingData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Recipe.CODEC.listOf().optionalFieldOf("recipes", List.of()).forGetter(data -> data.recipes)
    ).apply(instance, CrossbreedingData::new));
    private static final SavedDataType<CrossbreedingData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("crossbreeding"), CrossbreedingData::new, CODEC, null);

    private final List<Recipe> recipes = new ArrayList<>();

    private CrossbreedingData() { }

    private CrossbreedingData(List<Recipe> recipes) { this.recipes.addAll(recipes); }

    static CrossbreedingData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    List<Recipe> recipes() { return Collections.unmodifiableList(new ArrayList<>(recipes)); }

    /** Recipes changed: the farming-level lookup of crop blocks is rebuilt on next use. */
    @Override public void setDirty() {
        super.setDirty();
        FarmingLevels.invalidate();
    }

    void put(Recipe recipe) {
        for (int index = 0; index < recipes.size(); index++)
            if (recipes.get(index).id().equals(recipe.id())) { recipes.set(index, recipe); setDirty(); return; }
        recipes.add(recipe);
        setDirty();
    }

    void remove(Identifier id) {
        if (recipes.removeIf(recipe -> recipe.id().equals(id))) setDirty();
    }

    /**
     * first and second are the two input seeds (either order works). failureChance is in hundredths of a percent
     * and only applies while the player has not discovered the result yet. level is the farming level needed to
     * crossbreed, plant and harvest the result.
     */
    public record Recipe(Identifier id, Identifier first, Identifier second, Identifier output, int count,
                         int failureChance, int level, List<ReforgeData.Cost> costs) {
        static final Codec<Recipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("id").forGetter(Recipe::id),
                Identifier.CODEC.fieldOf("first").forGetter(Recipe::first),
                Identifier.CODEC.fieldOf("second").forGetter(Recipe::second),
                Identifier.CODEC.fieldOf("output").forGetter(Recipe::output),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Recipe::count),
                Codec.INT.optionalFieldOf("failure_chance", 0).forGetter(Recipe::failureChance),
                Codec.INT.optionalFieldOf("level", 0).forGetter(Recipe::level),
                ReforgeData.Cost.CODEC.listOf().optionalFieldOf("costs", List.of()).forGetter(Recipe::costs)
        ).apply(instance, Recipe::new));

        public Recipe { costs = List.copyOf(costs); }

        public boolean matches(Identifier a, Identifier b) {
            return (first.equals(a) && second.equals(b)) || (first.equals(b) && second.equals(a));
        }
    }
}
