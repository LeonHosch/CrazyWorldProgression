package ekuzo.crazyworldprogression.smithing;

import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;

/**
 * Resmithing at the anvil: a damaged item is fully restored for the costs its entry lists. The tier is not
 * chosen; it is the highest tier the player's Smithing Proficiency reaches. An item that tier does not list
 * uses the nearest lower tier that does. The player's tier's failure chance applies; a failure uses up the
 * costs and leaves the item as it was. A repair rolls the item's quality (see {@link #rollQuality}), which sets its
 * durability ({@link ItemDurability#setQuality}); an undamaged item can still be repaired to reroll it until it is
 * Perfect. The new quality replaces the old one, so a repair can also make it worse. Every attempt gives Smithing
 * (Craftsmanship) level XP by the rank of the tier that lists the item and the rolled quality (Miserable on a failure).
 */
public final class SmithingService {
    public static final String PROFICIENCY_STAT = "addSmithingProficiency";
    /** How much each Craftsmanship level lowers a tier's failure chance (hundredths of a percent: 2% per level). */
    public static final int FAILURE_DROP_PER_LEVEL = 200;

    /** A tier's failure chance for this player, after their Craftsmanship level (hundredths of a percent). */
    public static int failureChance(ServerPlayer player, SmithingData.Tier tier) {
        return Math.max(0, tier.failureChance() - FAILURE_DROP_PER_LEVEL
                * ekuzo.crazyworldprogression.skills.Skills.level(player, ekuzo.crazyworldprogression.skills.Skills.Skill.SMITHING));
    }

    private SmithingService() { }

    /** Tiers in the order the admin set, which is also the order they unlock in. */
    public static List<SmithingData.Tier> tiers(MinecraftServer server) { return SmithingData.get(server).tiers(); }

    public static SmithingData.Tier tier(MinecraftServer server, Identifier id) {
        return tiers(server).stream().filter(tier -> tier.id().equals(id)).findFirst().orElse(null);
    }

    public static int proficiency(ServerPlayer player) {
        return ekuzo.crazyworldprogression.skills.Proficiency.of(player, PROFICIENCY_STAT);
    }

    /** The highest tier the player's proficiency reaches, or null below the first tier. */
    public static SmithingData.Tier playerTier(ServerPlayer player) {
        int level = proficiency(player);
        return tiers(player.level().getServer()).stream().filter(tier -> tier.requiredLevel() <= level)
                .max(Comparator.comparingInt(SmithingData.Tier::requiredLevel)).orElse(null);
    }

    /** The item's entry: in the player's tier, else the nearest lower tier that lists it. */
    public record Resolution(SmithingData.Tier tier, SmithingData.Tier source, SmithingData.ItemCost cost) { }

    public static Resolution resolve(ServerPlayer player, ItemStack stack) {
        SmithingData.Tier current = playerTier(player);
        if (current == null || stack.isEmpty()) return null;
        Identifier item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        int level = proficiency(player);
        return tiers(player.level().getServer()).stream()
                .filter(tier -> tier.requiredLevel() <= level && tier.item(item) != null)
                .max(Comparator.comparingInt(SmithingData.Tier::requiredLevel))
                .map(source -> new Resolution(current, source, source.item(item))).orElse(null);
    }

    /** Why this item cannot be resmithed right now, or null when it can (costs aside). */
    public static String problem(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return "Place an item on the anvil";
        if (ChestQuality.isChest(stack)) return chestProblem(player, stack);
        if (playerTier(player) == null) return "You need " + SmithingBranding.get().name() + " Proficiency to " + SmithingBranding.get().action().toLowerCase(java.util.Locale.ROOT);
        if (!stack.isDamageableItem()) return "This item has no durability";
        if (stack.getCount() != 1) return "Place one item at a time";
        if (resolve(player, stack) == null) return "Your " + SmithingBranding.get().name() + " tier cannot " + SmithingBranding.get().action().toLowerCase(java.util.Locale.ROOT) + " this item";
        if (stack.getDamageValue() == 0 && !ItemDurability.isBrokenDown(stack)
                && ItemDurability.quality(stack) == ekuzo.crazyworldprogression.farming.ProduceQuality.Quality.PERFECT)
            return "This item is already Perfect";
        return null;
    }

    /** Why this chest can't be upgraded now (costs aside), or null. */
    private static String chestProblem(ServerPlayer player, ItemStack stack) {
        if (stack.getCount() != 1) return "Place one chest at a time";
        int quality = ChestQuality.of(stack);
        if (quality >= ChestQuality.STEPS.size()) return "This chest is already Perfect";
        int rank = ChestQuality.STEPS.get(quality).rank();
        if (proficiency(player) < rank)
            return "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(rank) + " " + SmithingBranding.get().name();
        return null;
    }

    /**
     * Upgrades a chest item one quality (one more row of slots); it always works and gives Craftsmanship XP for the
     * rank it needs and the new quality.
     */
    private static Result upgradeChest(ServerPlayer player, ItemStack stack) {
        int quality = ChestQuality.of(stack);
        ChestQuality.Step step = ChestQuality.STEPS.get(quality);
        List<String> missing = ReforgeService.missingCosts(player, step.costs());
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing " + String.join(", ", missing));
        ReforgeService.payCosts(player, step.costs());
        stack.set(ekuzo.crazyworldprogression.farming.ProduceQuality.QUALITY, quality + 1);
        ekuzo.crazyworldprogression.skills.Skills.add(player, ekuzo.crazyworldprogression.skills.Skills.Skill.SMITHING,
                ekuzo.crazyworldprogression.skills.Skills.xpFor(step.rank(), quality + 1));
        player.level().playSound(null, player.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 0.8F, 1.0F);
        var name = ekuzo.crazyworldprogression.farming.ProduceQuality.Quality.of(quality + 1).label();
        return new Result(true, "Upgraded to a " + name + " chest (" + ChestQuality.slotsFor(quality + 1) + " slots)");
    }

    /** Resmiths the item in place (or upgrades a chest). Returns the result message; throws when nothing was used up. */
    public static Result resmith(ServerPlayer player, ItemStack stack) {
        String problem = problem(player, stack);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (ChestQuality.isChest(stack)) return upgradeChest(player, stack);
        Resolution resolution = resolve(player, stack);
        List<ReforgeData.Cost> costs = resolution.cost().costs();
        List<String> missing = ReforgeService.missingCosts(player, costs);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing " + String.join(", ", missing));
        ReforgeService.payCosts(player, costs);
        boolean failed = player.getRandom().nextInt(10_000) < failureChance(player, resolution.tier());
        var quality = failed ? ekuzo.crazyworldprogression.farming.ProduceQuality.Quality.MISERABLE : rollQuality(player);
        ekuzo.crazyworldprogression.skills.Skills.add(player, ekuzo.crazyworldprogression.skills.Skills.Skill.SMITHING,
                ekuzo.crazyworldprogression.skills.Skills.xpFor(resolution.source().requiredLevel(), quality.ordinal()));
        var level = player.level();
        if (failed) {
            level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_DESTROY, SoundSource.BLOCKS, 0.7F, 1.2F);
            return new Result(false, "The " + SmithingBranding.get().action().toLowerCase(java.util.Locale.ROOT) + " failed. The materials are lost.");
        }
        ItemDurability.restore(stack);
        ItemDurability.setQuality(stack, quality);
        level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8F,
                0.9F + player.getRandom().nextFloat() * 0.2F);
        return new Result(true, SmithingBranding.get().resultPrefix() + stack.getHoverName().getString()
                + " (" + quality.label() + ")");
    }

    /**
     * A repair's quality, like produce quality: around an average that rises with the Craftsmanship level plus twice
     * the proficiency ranks above Novice.
     */
    public static ekuzo.crazyworldprogression.farming.ProduceQuality.Quality rollQuality(ServerPlayer player) {
        int score = ekuzo.crazyworldprogression.skills.Skills.level(player, ekuzo.crazyworldprogression.skills.Skills.Skill.SMITHING)
                + 2 * (proficiency(player) - ekuzo.crazyworldprogression.skills.Proficiency.NOVICE);
        return ekuzo.crazyworldprogression.farming.ProduceQuality.roll(player.getRandom(), score);
    }

    public record Result(boolean success, String message) { }

    // ---------------------------------------------------------------- editing

    public static void createTier(MinecraftServer server, Identifier id, String name) {
        if (tier(server, id) != null) throw new IllegalArgumentException(SmithingBranding.get().name() + " tier already exists: " + id);
        SmithingData data = SmithingData.get(server);
        data.putTier(new SmithingData.Tier(id, name, data.tiers().size() + 1, 0, List.of()));
    }

    public static void saveTier(MinecraftServer server, SmithingData.Tier tier) {
        if (tier.name().isBlank()) throw new IllegalArgumentException("Give the tier a name");
        if (tier.requiredLevel() < 1) throw new IllegalArgumentException("Required proficiency must be at least 1");
        if (tier.failureChance() < 0 || tier.failureChance() > 10_000)
            throw new IllegalArgumentException("Failure chance must be from 0 to 100%");
        for (SmithingData.ItemCost item : tier.items())
            for (ReforgeData.Cost cost : item.costs()) {
                if (cost.amount() < 1) throw new IllegalArgumentException("Cost amounts must be at least 1");
                if (cost.kind().equals("currency") && CurrencyRegistry.get(cost.target()) == null)
                    throw new IllegalArgumentException("Unknown currency " + cost.target());
            }
        SmithingData.get(server).putTier(tier);
    }

    public static void deleteTier(MinecraftServer server, Identifier id) { SmithingData.get(server).removeTier(id); }

    public static void moveTier(MinecraftServer server, Identifier id, int direction) {
        SmithingData.get(server).moveTier(id, direction);
    }
}
