package ekuzo.crazyworldprogression.reforge;

import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reforging: an item placed in the enchanting table is given exactly one enchantment rolled from the chosen
 * tier's pool. Pools are shared by all items; entries that do not suit the item (Sharpness on a chestplate) are
 * left out of the roll, and so are nested pools with nothing suitable inside. No experience is used: a tier
 * costs items and player currencies, and needs enough Enchanting Proficiency (renamed by ReforgeBranding).
 */
public final class ReforgeService {
    public static final String PROFICIENCY_STAT = "addEnchantingProficiency";
    public static final int MAX_DEPTH = 16;
    public static final int MAX_ENTRIES = 128;
    /** Enchanting skill XP per reforge: this times the tier's position (1st tier 10, 2nd 20, ...). */
    public static final int XP_PER_TIER = 10;

    private ReforgeService() { }

    public static List<ReforgeData.Tier> tiers(MinecraftServer server) { return ReforgeData.get(server).tiers(); }

    public static ReforgeData.Tier tier(MinecraftServer server, Identifier id) {
        return tiers(server).stream().filter(tier -> tier.id().equals(id)).findFirst().orElse(null);
    }

    public static Map<String, ReforgeData.Pool> pools(MinecraftServer server) { return ReforgeData.get(server).pools(); }

    public static ReforgeData.Pool pool(MinecraftServer server, Identifier id) { return pools(server).get(id.toString()); }

    public static int proficiency(ServerPlayer player) {
        return ekuzo.crazyworldprogression.skills.Proficiency.of(player, PROFICIENCY_STAT);
    }

    // ---------------------------------------------------------------- editing

    public static void createTier(MinecraftServer server, Identifier id, String name) {
        if (tier(server, id) != null) throw new IllegalArgumentException(ReforgeBranding.get().name() + " tier already exists: " + id);
        Identifier root = Identifier.fromNamespaceAndPath(id.getNamespace(), "reforge/" + id.getPath());
        if (pool(server, root) != null) throw new IllegalArgumentException("A pool with this tier's ID still exists");
        ReforgeData data = ReforgeData.get(server);
        data.putPool(new ReforgeData.Pool(root, name, List.of()));
        data.putTier(new ReforgeData.Tier(id, name, data.tiers().size() + 1, List.of(), root));
    }

    public static void saveTier(MinecraftServer server, ReforgeData.Tier tier) {
        if (tier.requiredLevel() < 1) throw new IllegalArgumentException("Required proficiency must be at least 1");
        if (tier.name().isBlank()) throw new IllegalArgumentException("Give the tier a name");
        for (ReforgeData.Cost cost : tier.costs()) {
            if (cost.amount() < 1) throw new IllegalArgumentException("Cost amounts must be at least 1");
            if (cost.kind().equals("currency") && CurrencyRegistry.get(cost.target()) == null)
                throw new IllegalArgumentException("Unknown currency " + cost.target());
        }
        ReforgeData.get(server).putTier(tier);
    }

    public static void deleteTier(MinecraftServer server, Identifier id) {
        ReforgeData.Tier tier = tier(server, id);
        if (tier == null) return;
        ReforgeData data = ReforgeData.get(server);
        data.removeTier(id);
        deletePoolTree(server, tier.pool(), 0);
    }

    public static void moveTier(MinecraftServer server, Identifier id, int direction) {
        ReforgeData.get(server).moveTier(id, direction);
    }

    public static void savePool(MinecraftServer server, ReforgeData.Pool pool) {
        if (pool.entries().size() > MAX_ENTRIES) throw new IllegalArgumentException("A pool can hold at most " + MAX_ENTRIES + " entries");
        for (ReforgeData.Entry entry : pool.entries())
            if (entry.weight() < 0) throw new IllegalArgumentException("Weights cannot be negative");
        ReforgeData.get(server).putPool(pool);
    }

    /** A new nested pool under the parent, with a free ID next to it. */
    public static Identifier createNested(MinecraftServer server, Identifier parent) {
        if (depth(server, parent) >= MAX_DEPTH) throw new IllegalArgumentException("Pools can nest at most " + MAX_DEPTH + " levels");
        Map<String, ReforgeData.Pool> pools = pools(server);
        int number = 1;
        Identifier id;
        do id = Identifier.fromNamespaceAndPath(parent.getNamespace(), parent.getPath() + "/nested_" + number++);
        while (pools.containsKey(id.toString()));
        savePool(server, new ReforgeData.Pool(id, "Nested pool " + (number - 1), List.of()));
        return id;
    }

    /** Deletes a pool and, below it, every nested pool no other pool still uses. */
    public static void deletePoolTree(MinecraftServer server, Identifier id, int depth) {
        ReforgeData.Pool pool = pool(server, id);
        if (pool == null || depth > MAX_DEPTH) return;
        ReforgeData.get(server).removePool(id);
        for (ReforgeData.Entry entry : pool.entries())
            if (entry.kind().equals("pool") && parents(server, entry.target()).isEmpty())
                deletePoolTree(server, entry.target(), depth + 1);
    }

    public static List<ReforgeData.Pool> parents(MinecraftServer server, Identifier id) {
        return pools(server).values().stream().filter(pool -> pool.entries().stream()
                .anyMatch(entry -> entry.kind().equals("pool") && entry.target().equals(id))).toList();
    }

    private static int depth(MinecraftServer server, Identifier id) {
        int depth = 0;
        Identifier current = id;
        while (depth <= MAX_DEPTH) {
            var parent = parents(server, current).stream().findFirst();
            if (parent.isEmpty()) break;
            current = parent.get().id();
            depth++;
        }
        return depth;
    }

    /** The tier whose root pool is this pool or contains it, for the editor's Back button and titles. */
    public static ReforgeData.Tier owningTier(MinecraftServer server, Identifier poolId) {
        Identifier current = poolId;
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            Identifier search = current;
            ReforgeData.Tier tier = tiers(server).stream().filter(value -> value.pool().equals(search)).findFirst().orElse(null);
            if (tier != null) return tier;
            var parent = parents(server, current).stream().findFirst();
            if (parent.isEmpty()) return null;
            current = parent.get().id();
        }
        return null;
    }

    // ---------------------------------------------------------------- rolling

    public record Roll(Holder<Enchantment> enchantment, int level) { }

    /** One weighted pick among the suitable entries, following nested pools; empty when nothing suits the item. */
    public static Optional<Roll> roll(ServerPlayer player, ReforgeData.Pool pool, ItemStack stack, RandomSource random) {
        return roll(player, pool, stack, random, null);
    }

    /** As {@link #roll}, with the focused enchantment's entries weighted by its research multiplier. */
    public static Optional<Roll> roll(ServerPlayer player, ReforgeData.Pool pool, ItemStack stack, RandomSource random,
                                      ResearchService.Focus focus) {
        return pick(player, pools(player.level().getServer()), pool, stack, random, 0, new HashSet<>(), focus);
    }

    /** An entry's weight, multiplied when it is the enchantment the player focuses their research on. */
    private static int weight(ResearchService.Focus focus, ReforgeData.Entry entry) {
        if (!entry.kind().equals("enchantment")) return entry.weight();
        return focus != null && focus.enchantment() != null && focus.enchantment().equals(entry.target())
                ? entry.weight() * focus.multiplier() : entry.weight();
    }

    private static Optional<Roll> pick(ServerPlayer player, Map<String, ReforgeData.Pool> pools, ReforgeData.Pool pool,
                                       ItemStack stack, RandomSource random, int depth, Set<Identifier> visiting,
                                       ResearchService.Focus focus) {
        if (pool == null || depth > MAX_DEPTH || !visiting.add(pool.id())) return Optional.empty();
        try {
            List<ReforgeData.Entry> candidates = new ArrayList<>();
            int total = 0;
            for (ReforgeData.Entry entry : pool.entries()) {
                if (entry.weight() <= 0 || !suits(player, pools, entry, stack, depth, visiting)) continue;
                candidates.add(entry);
                total += weight(focus, entry);
            }
            if (total <= 0) return Optional.empty();
            int ticket = random.nextInt(total);
            for (ReforgeData.Entry entry : candidates) {
                ticket -= weight(focus, entry);
                if (ticket >= 0) continue;
                if (entry.kind().equals("pool"))
                    return pick(player, pools, pools.get(entry.target().toString()), stack, random, depth + 1, visiting, focus);
                return enchantment(player, entry.target()).map(holder -> new Roll(holder, Math.max(1, entry.level())));
            }
            return Optional.empty();
        } finally {
            visiting.remove(pool.id());
        }
    }

    private static boolean suits(ServerPlayer player, Map<String, ReforgeData.Pool> pools, ReforgeData.Entry entry,
                                 ItemStack stack, int depth, Set<Identifier> visiting) {
        if (entry.kind().equals("enchantment"))
            return enchantment(player, entry.target()).map(holder -> holder.value().canEnchant(stack)).orElse(false);
        ReforgeData.Pool nested = pools.get(entry.target().toString());
        if (nested == null || depth >= MAX_DEPTH || visiting.contains(nested.id())) return false;
        visiting.add(nested.id());
        try {
            for (ReforgeData.Entry inner : nested.entries())
                if (inner.weight() > 0 && suits(player, pools, inner, stack, depth + 1, visiting)) return true;
            return false;
        } finally {
            visiting.remove(nested.id());
        }
    }

    public static Optional<Holder<Enchantment>> enchantment(ServerPlayer player, Identifier id) {
        return player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT, id)).map(holder -> holder);
    }

    // ---------------------------------------------------------------- reforging

    /** Reforges the item in place. Returns the message shown to the player; throws when nothing happened. */
    public static Component reforge(ServerPlayer player, int tierIndex, ItemStack stack) {
        return reforge(player, tierIndex, stack, null);
    }

    /** Reforges with the given research focus (from the window; checked against the player's research). */
    public static Component reforge(ServerPlayer player, int tierIndex, ItemStack stack, ResearchService.Focus focus) {
        MinecraftServer server = player.level().getServer();
        List<ReforgeData.Tier> tiers = tiers(server);
        if (tierIndex < 0 || tierIndex >= tiers.size()) throw new IllegalArgumentException("Choose a " + ReforgeBranding.get().name() + " tier");
        ReforgeData.Tier tier = tiers.get(tierIndex);
        if (stack.isEmpty()) throw new IllegalArgumentException("Place an item in the table first");
        if (stack.getCount() != 1) throw new IllegalArgumentException("Place one item at a time");
        if (stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK)) throw new IllegalArgumentException("Books cannot be reforged");
        int proficiency = proficiency(player);
        if (proficiency < tier.requiredLevel())
            throw new IllegalArgumentException(tier.name() + " needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(tier.requiredLevel()) + " "
                    + ReforgeBranding.get().skill() + " (you are " + ekuzo.crazyworldprogression.skills.Proficiency.name(proficiency) + ")");
        ReforgeData.Pool pool = pool(server, tier.pool());
        if (pool == null || roll(player, pool, stack, RandomSource.create(0)).isEmpty())
            throw new IllegalArgumentException("Nothing in " + tier.name() + " fits this item");
        List<String> missing = missingCosts(player, tier);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing " + String.join(", ", missing));
        Roll roll = roll(player, pool, stack, player.getRandom(), focus).orElseThrow();
        payCosts(player, tier);
        EnchantmentHelper.setEnchantments(stack, ItemEnchantments.EMPTY);
        EnchantmentHelper.updateEnchantments(stack, enchantments -> enchantments.set(roll.enchantment(), roll.level()));
        player.level().playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS,
                1.0F, 0.9F + player.getRandom().nextFloat() * 0.2F);
        ekuzo.crazyworldprogression.skills.Skills.add(player, ekuzo.crazyworldprogression.skills.Skills.Skill.ENCHANTING,
                XP_PER_TIER * (tierIndex + 1));
        return Component.literal(ReforgeBranding.get().resultPrefix()).append(Enchantment.getFullname(roll.enchantment(), roll.level()));
    }

    /** Readable list of what the player still lacks for one reforge at this tier. */
    public static List<String> missingCosts(ServerPlayer player, ReforgeData.Tier tier) {
        return missingCosts(player, tier.costs());
    }

    /** Readable list of what the player still lacks to pay these costs. */
    public static List<String> missingCosts(ServerPlayer player, List<ReforgeData.Cost> costs) {
        List<String> missing = new ArrayList<>();
        for (ReforgeData.Cost cost : costs) {
            long have = cost.kind().equals("currency")
                    ? CurrencyService.getBalance(player.level().getServer(), player.getUUID(), cost.target())
                    : countItem(player, cost.target());
            if (have < cost.amount()) missing.add((cost.amount() - have) + "× " + label(cost));
        }
        return missing;
    }

    private static void payCosts(ServerPlayer player, ReforgeData.Tier tier) {
        payCosts(player, tier.costs());
    }

    /** Takes the items from the player's inventory and the currencies from their balance. Check first. */
    public static void payCosts(ServerPlayer player, List<ReforgeData.Cost> costs) {
        for (ReforgeData.Cost cost : costs) {
            if (cost.kind().equals("currency")) {
                CurrencyService.debit(player.level().getServer(), player.getUUID(), cost.target(), cost.amount());
                continue;
            }
            int left = cost.amount();
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(cost.target())) continue;
                int taken = Math.min(left, stack.getCount());
                stack.shrink(taken);
                left -= taken;
            }
            inventory.setChanged();
        }
    }

    public static int countItem(ServerPlayer player, Identifier item) {
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(item)) count += stack.getCount();
        }
        return count;
    }

    public static String label(ReforgeData.Cost cost) {
        if (cost.kind().equals("currency")) {
            var currency = CurrencyRegistry.get(cost.target());
            return currency == null ? cost.target().toString() : currency.displayName();
        }
        return BuiltInRegistries.ITEM.getOptional(cost.target())
                .map(item -> new ItemStack(item).getHoverName().getString()).orElse(cost.target().toString());
    }

    /** Balances of the currencies any tier costs, for the reforge screen. */
    public static Map<Identifier, Long> balances(ServerPlayer player) {
        Map<Identifier, Long> balances = new LinkedHashMap<>();
        for (ReforgeData.Tier tier : tiers(player.level().getServer()))
            for (ReforgeData.Cost cost : tier.costs())
                if (cost.kind().equals("currency")) balances.put(cost.target(),
                        CurrencyService.getBalance(player.level().getServer(), player.getUUID(), cost.target()));
        return balances;
    }
}
