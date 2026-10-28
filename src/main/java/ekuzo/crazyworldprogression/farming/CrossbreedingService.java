package ekuzo.crazyworldprogression.farming;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Crossbreeding: two seeds plus the pair's costs make the pair's result. Results the player has not bred before are
 * unknown: the window does not show them and the recipe's failure chance applies (a failure uses up the seeds and
 * costs). Once bred, a result is known and always works. Every result has a farming level; below it the player
 * cannot breed, plant or harvest it (see FarmingLevels), and seeds above the player's level cannot be analysed as
 * inputs either.
 */
public final class CrossbreedingService {
    public static final String LEVEL_STAT = "addFarmingLevel";
    /** Recipe IDs the player has bred successfully at least once. */
    public static final AttachmentType<List<String>> DISCOVERED = AttachmentRegistry.create(
            CrazyWorldProgression.id("crossbreeding_discovered"),
            builder -> builder.persistent(Codec.STRING.listOf()).copyOnDeath().initializer(List::of));

    private CrossbreedingService() { }

    public static void initialize() {
        SkillStatRegistry.registerNumberStat(LEVEL_STAT, "stat.crazy-world-progression.farming_level",
                ekuzo.crazyworldprogression.skills.Proficiency.NOVICE, true,
                value -> { }, (player, value) -> { });
    }

    public static int level(ServerPlayer player) { return ekuzo.crazyworldprogression.skills.Proficiency.of(player, LEVEL_STAT); }

    public static List<CrossbreedingData.Recipe> recipes(MinecraftServer server) {
        return CrossbreedingData.get(server).recipes();
    }

    public static CrossbreedingData.Recipe recipe(MinecraftServer server, Identifier id) {
        return recipes(server).stream().filter(recipe -> recipe.id().equals(id)).findFirst().orElse(null);
    }

    public static CrossbreedingData.Recipe find(MinecraftServer server, Identifier a, Identifier b) {
        return recipes(server).stream().filter(recipe -> recipe.matches(a, b)).findFirst().orElse(null);
    }

    /** The farming level an item needs: the lowest level of any recipe that breeds it, or 0 for normal items. */
    public static int requiredLevel(MinecraftServer server, Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return recipes(server).stream().filter(recipe -> recipe.output().equals(id))
                .mapToInt(CrossbreedingData.Recipe::level).min().orElse(0);
    }

    public static boolean discovered(ServerPlayer player, CrossbreedingData.Recipe recipe) {
        return player.getAttachedOrCreate(DISCOVERED).contains(recipe.id().toString());
    }

    private static void discover(ServerPlayer player, CrossbreedingData.Recipe recipe) {
        Set<String> known = new LinkedHashSet<>(player.getAttachedOrCreate(DISCOVERED));
        if (known.add(recipe.id().toString())) player.setAttached(DISCOVERED, List.copyOf(known));
    }

    /** Why these inputs cannot be crossbred right now (costs aside), or null when they can. */
    public static String problem(ServerPlayer player, ItemStack a, ItemStack b, ItemStack output) {
        if (a.isEmpty() || b.isEmpty()) return "Place two seeds";
        MinecraftServer server = player.level().getServer();
        int level = level(player);
        for (ItemStack input : List.of(a, b)) {
            int needed = requiredLevel(server, input.getItem());
            if (needed > level) return "You need " + ekuzo.crazyworldprogression.skills.Proficiency.name(needed) + " farming proficiency to analyse " + input.getHoverName().getString();
        }
        CrossbreedingData.Recipe recipe = find(server, key(a), key(b));
        if (recipe == null) return "These seeds do not crossbreed";
        if (recipe.level() > level) return "You need " + ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()) + " farming proficiency for this crossbreed";
        Item result = BuiltInRegistries.ITEM.getOptional(recipe.output()).orElse(null);
        if (result == null) return "This crossbreed has no valid result";
        if (!output.isEmpty() && (!output.is(result) || output.getCount() + recipe.count() > output.getMaxStackSize()))
            return "Take the last result out first";
        return null;
    }

    /** Uses one of each input and the costs; returns the result message. Throws when nothing was used up. */
    public static Result breed(ServerPlayer player, ItemStack a, ItemStack b, ItemStack output,
                               java.util.function.Consumer<ItemStack> setOutput) {
        String problem = problem(player, a, b, output);
        if (problem != null) throw new IllegalArgumentException(problem);
        CrossbreedingData.Recipe recipe = find(player.level().getServer(), key(a), key(b));
        List<String> missing = ReforgeService.missingCosts(player, recipe.costs());
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing " + String.join(", ", missing));
        ReforgeService.payCosts(player, recipe.costs());
        boolean known = discovered(player, recipe);
        a.shrink(1);
        b.shrink(1);
        var level = player.level();
        if (!known && player.getRandom().nextInt(10_000) < recipe.failureChance()) {
            level.playSound(null, player.blockPosition(), SoundEvents.COMPOSTER_FILL, SoundSource.BLOCKS, 0.8F, 0.6F);
            return new Result(false, "The crossbreed failed. The seeds and materials are lost.");
        }
        Item result = BuiltInRegistries.ITEM.getValue(recipe.output());
        if (output.isEmpty()) setOutput.accept(new ItemStack(result, recipe.count()));
        else output.grow(recipe.count());
        discover(player, recipe);
        level.playSound(null, player.blockPosition(), SoundEvents.COMPOSTER_READY, SoundSource.BLOCKS, 0.8F,
                0.9F + player.getRandom().nextFloat() * 0.2F);
        return new Result(true, (known ? "Bred " : "Discovered ") + new ItemStack(result).getHoverName().getString());
    }

    public record Result(boolean success, String message) { }

    static Identifier key(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()); }

    // ---------------------------------------------------------------- editing

    public static void create(MinecraftServer server, Identifier id, Identifier first, Identifier second, Identifier output) {
        if (recipe(server, id) != null) throw new IllegalArgumentException("Crossbreed already exists: " + id);
        if (find(server, first, second) != null)
            throw new IllegalArgumentException("These two seeds already have a crossbreed");
        save(server, new CrossbreedingData.Recipe(id, first, second, output, 1, 0, 0, List.of()));
    }

    public static void save(MinecraftServer server, CrossbreedingData.Recipe recipe) {
        for (Identifier item : List.of(recipe.first(), recipe.second(), recipe.output()))
            if (BuiltInRegistries.ITEM.getOptional(item).isEmpty() || item.toString().equals("minecraft:air"))
                throw new IllegalArgumentException("Unknown item " + item);
        CrossbreedingData.Recipe other = find(server, recipe.first(), recipe.second());
        if (other != null && !other.id().equals(recipe.id()))
            throw new IllegalArgumentException("These two seeds already have a crossbreed: " + other.id());
        if (recipe.count() < 1 || recipe.count() > 64) throw new IllegalArgumentException("Amount must be from 1 to 64");
        if (recipe.failureChance() < 0 || recipe.failureChance() > 10_000)
            throw new IllegalArgumentException("Failure chance must be from 0 to 100%");
        if (recipe.level() < 0) throw new IllegalArgumentException("Farming level must be 0 or more");
        for (ReforgeData.Cost cost : recipe.costs()) {
            if (cost.amount() < 1) throw new IllegalArgumentException("Cost amounts must be at least 1");
            if (cost.kind().equals("currency") && CurrencyRegistry.get(cost.target()) == null)
                throw new IllegalArgumentException("Unknown currency " + cost.target());
        }
        CrossbreedingData.get(server).put(recipe);
    }

    public static void delete(MinecraftServer server, Identifier id) { CrossbreedingData.get(server).remove(id); }

    /** Costs as readable text, for the admin panel. */
    public static String describeCosts(List<ReforgeData.Cost> costs) {
        if (costs.isEmpty()) return "free";
        List<String> parts = new ArrayList<>();
        for (ReforgeData.Cost cost : costs) parts.add(cost.amount() + "× " + ReforgeService.label(cost));
        return String.join(", ", parts);
    }
}
