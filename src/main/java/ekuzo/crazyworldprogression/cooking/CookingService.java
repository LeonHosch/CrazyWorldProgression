package ekuzo.crazyworldprogression.cooking;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Cooking: up to three ingredients plus a bowl (cauldron stews) or coal (smoker) make the recipe's food. A stew only
 * cooks in a full water cauldron with a lit campfire right below it; the window still opens at any cauldron and
 * says what is missing. Each recipe has a cooking level ({@code addCookingLevel}); below it the player cannot cook it.
 * The Cooking level (Skills) and the proficiency raise the dish's quality; the level also gives a chance to cook an
 * extra batch. Cooking XP comes
 * from the dish's required rank and its quality.
 */
public final class CookingService {
    public static final String LEVEL_STAT = "addCookingLevel";
    /** Chance per Cooking level to cook one extra batch of the dish (50% at level 10). */
    public static final double EXTRA_DISH_PER_LEVEL = 0.05;

    private CookingService() { }

    public static void initialize() {
        SkillStatRegistry.registerNumberStat(LEVEL_STAT, "stat.crazy-world-progression.cooking_level",
                ekuzo.crazyworldprogression.skills.Proficiency.NOVICE, true,
                value -> { }, (player, value) -> { });
    }

    public static int level(ServerPlayer player) { return ekuzo.crazyworldprogression.skills.Proficiency.of(player, LEVEL_STAT); }

    public static List<CookingData.Recipe> recipes(MinecraftServer server) { return CookingData.get(server).recipes(); }

    public static CookingData.Recipe recipe(MinecraftServer server, Identifier id) {
        return recipes(server).stream().filter(recipe -> recipe.id().equals(id)).findFirst().orElse(null);
    }

    public static CookingData.Recipe find(MinecraftServer server, CookingData.Station station, List<ItemStack> slots) {
        List<Identifier> items = ids(slots);
        if (items.isEmpty()) return null;
        return recipes(server).stream().filter(recipe -> recipe.matches(station, items)).findFirst().orElse(null);
    }

    static List<Identifier> ids(List<ItemStack> slots) {
        List<Identifier> items = new ArrayList<>();
        for (ItemStack stack : slots) if (!stack.isEmpty()) items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return items;
    }

    /** Whether the station works: a smoker always; a cauldron when full of water above a lit campfire. */
    public static String stationProblem(CookingData.Station station, Level level, BlockPos pos) {
        if (station == CookingData.Station.SMOKER || level == null || pos == null) return null;
        var cauldron = level.getBlockState(pos);
        boolean water = cauldron.is(Blocks.WATER_CAULDRON) && cauldron.getValue(LayeredCauldronBlock.LEVEL) >= LayeredCauldronBlock.MAX_FILL_LEVEL;
        var below = level.getBlockState(pos.below());
        boolean fire = below.is(BlockTags.CAMPFIRES) && CampfireBlock.isLitCampfire(below);
        if (!water && !fire) return "Fill the cauldron with water and light a campfire below it";
        if (!water) return "Fill the cauldron with water";
        if (!fire) return "Light a campfire below the cauldron";
        return null;
    }

    public static boolean fitsTool(CookingData.Station station, ItemStack stack) {
        return station == CookingData.Station.STEW ? stack.is(Items.BOWL) : stack.is(ItemTags.COALS);
    }

    /** Why this can not be cooked right now, or null when it can. */
    public static String problem(ServerPlayer player, CookingData.Station station, Level level, BlockPos pos,
                                 List<ItemStack> ingredients, ItemStack tool, ItemStack output) {
        if (ingredients.stream().allMatch(ItemStack::isEmpty)) return "Add ingredients";
        CookingData.Recipe recipe = find(player.level().getServer(), station, ingredients);
        if (recipe == null) return "These ingredients do not make anything";
        if (recipe.level() > level(player)) return "You need " + ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()) + " cooking proficiency to cook this";
        String stationProblem = stationProblem(station, level, pos);
        if (stationProblem != null) return stationProblem;
        if (tool.isEmpty() || !fitsTool(station, tool))
            return station == CookingData.Station.STEW ? "Add a bowl" : "Add coal";
        Item result = BuiltInRegistries.ITEM.getOptional(recipe.output()).orElse(null);
        if (result == null) return "This recipe has no valid result";
        // Each dish rolls its own quality, so it is not added onto the last one.
        if (!output.isEmpty()) return "Take the last dish out first";
        return null;
    }

    /** Cooks once: one of each ingredient and one bowl or coal are used up. Throws when nothing was used. */
    public static String cook(ServerPlayer player, CookingData.Station station, Level level, BlockPos pos,
                              List<ItemStack> ingredients, ItemStack tool, ItemStack output,
                              java.util.function.Consumer<ItemStack> setOutput) {
        String problem = problem(player, station, level, pos, ingredients, tool, output);
        if (problem != null) throw new IllegalArgumentException(problem);
        CookingData.Recipe recipe = find(player.level().getServer(), station, ingredients);
        int cookingLevel = ekuzo.crazyworldprogression.skills.Skills.level(player, ekuzo.crazyworldprogression.skills.Skills.Skill.COOKING);
        var quality = ekuzo.crazyworldprogression.farming.ProduceQuality.cook(player.getRandom(), ingredients, cookingLevel,
                level(player));
        boolean extra = player.getRandom().nextDouble() < cookingLevel * EXTRA_DISH_PER_LEVEL;
        for (ItemStack ingredient : ingredients) if (!ingredient.isEmpty()) ingredient.shrink(1);
        tool.shrink(1);
        Item result = BuiltInRegistries.ITEM.getValue(recipe.output());
        ItemStack dish = ekuzo.crazyworldprogression.farming.ProduceQuality.withQuality(new ItemStack(result, recipe.count()), quality);
        if (extra && dish.getCount() * 2 <= dish.getMaxStackSize()) dish.grow(recipe.count());
        else if (extra) player.getInventory().placeItemBackInInventory(dish.copy());
        setOutput.accept(dish);
        player.level().playSound(null, pos == null ? player.blockPosition() : pos,
                station == CookingData.Station.STEW ? SoundEvents.BREWING_STAND_BREW : SoundEvents.SMOKER_SMOKE,
                SoundSource.BLOCKS, 0.8F, 0.9F + player.getRandom().nextFloat() * 0.2F);
        // Most XP for a Master dish of Perfect quality (Skills.xpFor).
        ekuzo.crazyworldprogression.skills.Skills.add(player, ekuzo.crazyworldprogression.skills.Skills.Skill.COOKING,
                ekuzo.crazyworldprogression.skills.Skills.xpFor(recipe.level(), quality.ordinal()));
        return "Cooked " + quality.label() + " " + new ItemStack(result).getHoverName().getString()
                + (extra ? " (Cooking bonus: an extra batch!)" : "");
    }

    // ---------------------------------------------------------------- editing

    public static void create(MinecraftServer server, Identifier id, CookingData.Station station, Identifier output) {
        if (recipe(server, id) != null) throw new IllegalArgumentException("Recipe already exists: " + id);
        CookingData.get(server).put(new CookingData.Recipe(id, station, List.of(), output, 1, 0));
    }

    public static void save(MinecraftServer server, CookingData.Recipe recipe) {
        List<Identifier> items = new ArrayList<>(recipe.ingredients());
        items.add(recipe.output());
        for (Identifier item : items)
            if (BuiltInRegistries.ITEM.getOptional(item).isEmpty() || item.toString().equals("minecraft:air"))
                throw new IllegalArgumentException("Unknown item " + item);
        if (recipe.ingredients().size() > 3) throw new IllegalArgumentException("A recipe has at most 3 ingredients");
        if (recipe.count() < 1 || recipe.count() > 64) throw new IllegalArgumentException("Amount must be from 1 to 64");
        if (recipe.level() < 0) throw new IllegalArgumentException("Cooking level must be 0 or more");
        if (!recipe.ingredients().isEmpty()) for (CookingData.Recipe other : recipes(server))
            if (!other.id().equals(recipe.id()) && other.matches(recipe.station(), recipe.ingredients()))
                throw new IllegalArgumentException("These ingredients already make " + other.output() + " (" + other.id() + ")");
        CookingData.get(server).put(recipe);
    }

    public static void delete(MinecraftServer server, Identifier id) { CookingData.get(server).remove(id); }
}
