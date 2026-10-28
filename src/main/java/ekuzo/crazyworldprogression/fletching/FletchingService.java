package ekuzo.crazyworldprogression.fletching;

import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import ekuzo.crazyworldprogression.skills.Proficiency;
import ekuzo.crazyworldprogression.skills.Skills;
import ekuzo.crazyworldprogression.smithing.SmithingBranding;
import ekuzo.crazyworldprogression.smithing.SmithingService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Fletching at the fletching table: a recipe's costs make its arrows, if the player's Craftsmanship proficiency
 * reaches its rank. Each Craftsmanship level gives {@link #EXTRA_BATCH_PER_LEVEL} chance of a second batch, and every
 * craft gives Craftsmanship XP ({@link Skills#xpFor}, as Fine).
 */
public final class FletchingService {
    /** Chance per Craftsmanship level to make the recipe twice for the price of once (100% at level 10). */
    public static final double EXTRA_BATCH_PER_LEVEL = 0.10;

    private FletchingService() { }

    public static List<FletchingData.Recipe> recipes(MinecraftServer server) { return FletchingData.get(server).recipes(); }

    public static FletchingData.Recipe recipe(MinecraftServer server, Identifier id) {
        return recipes(server).stream().filter(recipe -> recipe.id().equals(id)).findFirst().orElse(null);
    }

    /** Why this can not be crafted right now, or null when it can. */
    public static String problem(ServerPlayer player, FletchingData.Recipe recipe) {
        if (recipe == null) return "This recipe no longer exists";
        if (BuiltInRegistries.ITEM.getOptional(recipe.output()).isEmpty()) return "This recipe has no valid result";
        if (SmithingService.proficiency(player) < recipe.rank())
            return "You need " + Proficiency.name(recipe.rank()) + " " + SmithingBranding.get().name() + " to craft this";
        List<String> missing = ReforgeService.missingCosts(player, recipe.costs());
        if (!missing.isEmpty()) return "Missing " + String.join(", ", missing);
        return null;
    }

    /** Crafts once. Returns the result message; throws when nothing was used up. */
    public static String craft(ServerPlayer player, FletchingData.Recipe recipe) {
        String problem = problem(player, recipe);
        if (problem != null) throw new IllegalArgumentException(problem);
        ReforgeService.payCosts(player, recipe.costs());
        int level = Skills.level(player, Skills.Skill.SMITHING);
        boolean extra = player.getRandom().nextDouble() < level * EXTRA_BATCH_PER_LEVEL;
        Item item = BuiltInRegistries.ITEM.getValue(recipe.output());
        ItemStack made = new ItemStack(item, recipe.count() * (extra ? 2 : 1));
        String name = made.getHoverName().getString();
        int count = made.getCount();
        if (!player.getInventory().add(made)) player.drop(made, false);
        player.containerMenu.broadcastChanges();
        Skills.add(player, Skills.Skill.SMITHING, Skills.xpFor(recipe.rank(), Skills.FINE));
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_WORK_FLETCHER, SoundSource.BLOCKS,
                0.8F, 0.9F + player.getRandom().nextFloat() * 0.2F);
        return "Crafted " + count + " " + name + (extra ? " (" + SmithingBranding.get().name() + " bonus: double batch!)" : "");
    }

    // ---------------------------------------------------------------- editing

    public static void save(MinecraftServer server, FletchingData.Recipe recipe) {
        if (BuiltInRegistries.ITEM.getOptional(recipe.output()).isEmpty() || recipe.output().toString().equals("minecraft:air"))
            throw new IllegalArgumentException("Unknown item " + recipe.output());
        if (recipe.count() < 1 || recipe.count() > 64) throw new IllegalArgumentException("Amount must be from 1 to 64");
        if (recipe.rank() < Proficiency.NOVICE || recipe.rank() > Proficiency.MAX)
            throw new IllegalArgumentException("Pick a rank from Novice to Master");
        if (recipe.costs().size() > 6) throw new IllegalArgumentException("A recipe has at most 6 costs");
        for (ReforgeData.Cost cost : recipe.costs()) {
            if (cost.amount() < 1) throw new IllegalArgumentException("Cost amounts must be at least 1");
            if (cost.kind().equals("currency") ? CurrencyRegistry.get(cost.target()) == null
                    : BuiltInRegistries.ITEM.getOptional(cost.target()).isEmpty())
                throw new IllegalArgumentException("Unknown " + cost.kind() + " " + cost.target());
        }
        FletchingData.get(server).put(recipe);
    }

    public static void delete(MinecraftServer server, Identifier id) { FletchingData.get(server).remove(id); }

    public static void move(MinecraftServer server, Identifier id, int direction) { FletchingData.get(server).move(id, direction); }

    /** Puts a fletched arrow's recipe back to its default (or adds it back if it was deleted). */
    public static void reset(MinecraftServer server, ArrowType type) { FletchingData.get(server).put(FletchingData.defaultRecipe(type)); }
}
