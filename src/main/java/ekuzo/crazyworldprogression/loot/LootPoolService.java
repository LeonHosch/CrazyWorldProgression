package ekuzo.crazyworldprogression.loot;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and rolls editable pools. Each entry in an all-mode pool rolls its chance independently.
 * A one-mode pool (used for nested pools) picks exactly one entry, using the entries' chances as shares.
 */
public final class LootPoolService {
    public static final int MAX_DEPTH = 16;
    private LootPoolService() { }

    /** Random source used by rolling; a parameter so tests can roll deterministically. */
    @FunctionalInterface
    public interface Roller {
        int nextInt(int bound);
    }

    public static Map<String, LootPoolData.Pool> all(MinecraftServer server) {
        return LootPoolData.get(server).values();
    }

    public static LootPoolData.Pool get(MinecraftServer server, Identifier id) {
        return all(server).get(id.toString());
    }

    public static void save(MinecraftServer server, LootPoolData.Pool pool) {
        Map<String, LootPoolData.Pool> proposed = new java.util.HashMap<>(all(server));
        proposed.put(pool.id().toString(), pool);
        validateCatalogue(proposed);
        LootPoolData.get(server).put(pool);
    }

    static void validateCatalogue(Map<String, LootPoolData.Pool> pools) {
        for (LootPoolData.Pool value : pools.values()) validate(value.id(), pools, new HashSet<>(), 0);
    }

    /** Pools that contain the given pool as a nested entry. */
    public static List<LootPoolData.Pool> parents(Map<String, LootPoolData.Pool> pools, Identifier id) {
        return pools.values().stream().filter(pool -> pool.entries().stream()
                .anyMatch(entry -> entry.kind().equals("pool") && entry.target().equals(id))).toList();
    }

    /** True when a pool is nested inside another pool, so the top-level list hides it. */
    public static boolean nested(Map<String, LootPoolData.Pool> pools, Identifier id) {
        return !parents(pools, id).isEmpty();
    }

    /** Free ID for a new nested pool owned by the given parent. */
    public static Identifier nestedId(Map<String, LootPoolData.Pool> pools, Identifier parent) {
        for (int index = 1; ; index++) {
            Identifier candidate = Identifier.fromNamespaceAndPath(parent.getNamespace(),
                    parent.getPath() + "/nested_" + index);
            if (!pools.containsKey(candidate.toString())) return candidate;
        }
    }

    /** Deletes a pool plus the nested pools only it uses, so no orphaned nested pools stay behind. */
    public static void delete(MinecraftServer server, Identifier id) {
        Map<String, LootPoolData.Pool> pools = all(server);
        if (!pools.containsKey(id.toString())) throw new IllegalArgumentException("Unknown loot pool " + id);
        LootPoolData.Pool parent = parents(pools, id).stream().findFirst().orElse(null);
        if (parent != null) throw new IllegalArgumentException("Loot pool is used by " + parent.id());
        Set<String> removed = new java.util.LinkedHashSet<>();
        collectOwned(id, pools, removed);
        for (String value : removed) requireUnusedByScripts(Identifier.parse(value));
        for (String value : removed) LootPoolData.get(server).remove(Identifier.parse(value));
    }

    /** After a nested entry was removed, deletes that nested pool when nothing else uses it. */
    public static void deleteIfUnused(MinecraftServer server, Identifier nestedId) {
        Map<String, LootPoolData.Pool> pools = all(server);
        if (!pools.containsKey(nestedId.toString()) || !parents(pools, nestedId).isEmpty()) return;
        try {
            delete(server, nestedId);
        } catch (IllegalArgumentException ignored) {
            // Still referenced by a script, so it stays available as a top-level pool.
        }
    }

    static void collectOwned(Identifier id, Map<String, LootPoolData.Pool> pools, Set<String> removed) {
        if (!removed.add(id.toString())) return;
        LootPoolData.Pool pool = pools.get(id.toString());
        if (pool == null) return;
        for (LootPoolData.Entry entry : pool.entries()) {
            if (!entry.kind().equals("pool")) continue;
            boolean usedElsewhere = parents(pools, entry.target()).stream()
                    .anyMatch(other -> !removed.contains(other.id().toString()));
            if (!usedElsewhere) collectOwned(entry.target(), pools, removed);
        }
    }

    private static void requireUnusedByScripts(Identifier id) {
        for (MobScript script : MobForge.SCRIPTS.all()) {
            if (script.steps().stream().anyMatch(step -> step instanceof MobScript.LootPoolStep loot
                    && loot.lootPoolId().equals(id)))
                throw new IllegalArgumentException("Loot pool " + id + " is used by script " + script.id());
        }
    }

    private static void validate(Identifier id, Map<String, LootPoolData.Pool> pools,
                                 Set<String> path, int depth) {
        LootPoolData.Pool pool = pools.get(id.toString());
        if (pool == null) throw new IllegalArgumentException("Unknown nested loot pool " + id);
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Loot pools can nest at most " + MAX_DEPTH + " levels");
        if (!path.add(id.toString())) throw new IllegalArgumentException("Circular loot pool reference: " + id);
        if (pool.name().isBlank() || pool.name().length() > 80) throw new IllegalArgumentException("Pool name must be 1–80 characters");
        if (!pool.mode().equals("all") && !pool.mode().equals("one")) throw new IllegalArgumentException("Unknown loot pool mode");
        if (pool.entries().size() > 128) throw new IllegalArgumentException("Pool has more than 128 rewards");
        for (LootPoolData.Entry entry : pool.entries()) {
            if (entry.showInChat() && !entry.kind().equals("item"))
                throw new IllegalArgumentException("Chat announcements are available for item rewards only");
            if (entry.chance() < 0 || entry.chance() > 10000 || entry.weight() < 1 || entry.weight() > 10000)
                throw new IllegalArgumentException("Chance must be between 0% and 100%");
            if (entry.min() < 1 || entry.max() < entry.min() || entry.max() > 2304)
                throw new IllegalArgumentException("Reward amount must be between 1 and 2304");
            switch (entry.kind()) {
                case "item" -> {
                    Item item = BuiltInRegistries.ITEM.getOptional(entry.target()).orElse(null);
                    if (item == null || item == Items.AIR) throw new IllegalArgumentException("Unknown item " + entry.target());
                }
                case "currency" -> {
                    CurrencyDefinition currency = CurrencyRegistry.require(entry.target());
                    if (currency.scope() != CurrencyDefinition.CurrencyScope.PLAYER)
                        throw new IllegalArgumentException("Currency must be player scoped: " + entry.target());
                }
                case "pool" -> validate(entry.target(), pools, path, depth + 1);
                default -> throw new IllegalArgumentException("Unknown reward type " + entry.kind());
            }
        }
        path.remove(id.toString());
    }

    public static void roll(MinecraftServer server, ServerPlayer player, Identifier id) {
        Map<String, LootPoolData.Pool> pools = all(server);
        validate(id, pools, new HashSet<>(), 0);
        List<LootPoolData.Entry> rewards = pick(id, pools, player.getRandom()::nextInt);
        int postmasterItems = 0;
        int droppedItems = 0;
        for (LootPoolData.Entry reward : rewards) {
            Delivery delivery = grant(server, player, reward);
            postmasterItems += delivery.postmasterItems();
            droppedItems += delivery.droppedItems();
        }
        notifyDelivery(player, postmasterItems, droppedItems);
    }

    /** One message per roll for postmaster deliveries, a half-full warning, and items dropped on the ground. */
    private static void notifyDelivery(ServerPlayer player, int postmasterItems, int droppedItems) {
        if (postmasterItems > 0) player.sendSystemMessage(Component.translatableWithFallback(
                "message.crazy-world-progression.postmaster.delivered",
                "Your inventory was full, so %s loot item(s) were sent to your postmaster. %s",
                postmasterItems, postmasterLink()).withStyle(ChatFormatting.YELLOW));
        int used = PostmasterService.usedSlots(player);
        if (used > PostmasterService.SLOTS / 2) player.sendSystemMessage(Component.translatableWithFallback(
                "message.crazy-world-progression.postmaster.half_full",
                "Your postmaster is more than half full (%s/%s slots). Please clear it soon. %s",
                used, PostmasterService.SLOTS, postmasterLink()).withStyle(ChatFormatting.GOLD));
        if (droppedItems > 0) player.sendSystemMessage(Component.translatableWithFallback(
                "message.crazy-world-progression.postmaster.dropped",
                "Your postmaster is full! %s loot item(s) dropped on the ground at your feet. Anyone can pick them up.",
                droppedItems).withStyle(ChatFormatting.RED));
    }

    private static MutableComponent postmasterLink() {
        return Component.translatableWithFallback("message.crazy-world-progression.postmaster.open",
                        "[Open postmaster]")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/postmaster"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("/postmaster"))));
    }

    /** Rolls a validated pool and returns the item and currency rewards it produced. */
    public static List<LootPoolData.Entry> pick(Identifier id, Map<String, LootPoolData.Pool> pools, Roller random) {
        List<LootPoolData.Entry> rewards = new ArrayList<>();
        choose(id, pools, random, rewards, 0);
        return rewards;
    }

    private static void choose(Identifier id, Map<String, LootPoolData.Pool> pools, Roller random,
                               List<LootPoolData.Entry> rewards, int depth) {
        LootPoolData.Pool pool = pools.get(id.toString());
        if (pool == null || pool.entries().isEmpty() || depth > MAX_DEPTH) return;
        if (pool.mode().equals("one")) {
            int total = pool.entries().stream().mapToInt(LootPoolData.Entry::chance).sum();
            if (total <= 0) return;
            int pick = random.nextInt(total);
            for (LootPoolData.Entry entry : pool.entries()) {
                pick -= entry.chance();
                if (pick < 0) { select(entry, pools, random, rewards, depth); break; }
            }
        } else {
            for (LootPoolData.Entry entry : pool.entries()) {
                if (random.nextInt(10000) < entry.chance()) select(entry, pools, random, rewards, depth);
            }
        }
    }

    private static void select(LootPoolData.Entry entry, Map<String, LootPoolData.Pool> pools,
                               Roller random, List<LootPoolData.Entry> rewards, int depth) {
        if (entry.kind().equals("pool")) choose(entry.target(), pools, random, rewards, depth + 1);
        else rewards.add(entry);
    }

    private static Delivery grant(MinecraftServer server, ServerPlayer player, LootPoolData.Entry reward) {
        int amount = reward.min() + player.getRandom().nextInt(reward.max() - reward.min() + 1);
        if (reward.kind().equals("currency")) {
            CurrencyService.credit(server, player.getUUID(), reward.target(), amount);
            return new Delivery(0, 0);
        }
        Item item = BuiltInRegistries.ITEM.getValue(reward.target());
        int stackSize = Math.max(1, item.getDefaultMaxStackSize());
        int stored = 0;
        int dropped = 0;
        int total = amount;
        while (amount > 0) {
            int count = Math.min(amount, stackSize);
            ItemStack stack = new ItemStack(item, count);
            // Only offer what really fits: in creative mode, Inventory.add deletes items that do not fit.
            int fits = Math.min(count, roomFor(player.getInventory().getNonEquipmentItems(), stack));
            if (fits > 0) {
                ItemStack given = stack.split(fits);
                player.getInventory().add(given);
                if (!given.isEmpty()) stack.grow(given.getCount());
            }
            if (!stack.isEmpty()) {
                PostmasterService.DepositResult result = PostmasterService.deposit(player, stack);
                stored += result.stored();
                if (result.overflow() > 0) {
                    dropAtFeet(player, stack.copyWithCount(result.overflow()));
                    dropped += result.overflow();
                }
            }
            amount -= count;
        }
        player.containerMenu.broadcastChanges();
        if (reward.showInChat()) server.getPlayerList().broadcastSystemMessage(announcement(player,
                new ItemStack(item, total)), false);
        return new Delivery(stored, dropped);
    }

    /** "Player just got [Item]" with the item's tooltip on hover; the count is shown when more than one. */
    static Component announcement(ServerPlayer player, ItemStack stack) {
        Component itemName = stack.getDisplayName();
        return stack.getCount() > 1
                ? Component.translatableWithFallback("message.crazy-world-progression.loot.announce_count",
                        "%s just got %sx %s", player.getDisplayName(), stack.getCount(), itemName)
                : Component.translatableWithFallback("message.crazy-world-progression.loot.announce",
                        "%s just got %s", player.getDisplayName(), itemName);
    }

    /** How many of this stack fit into the given inventory slots without anything being lost. */
    static int roomFor(List<ItemStack> slots, ItemStack stack) {
        int room = 0;
        for (ItemStack slot : slots) {
            if (slot.isEmpty()) room += stack.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(slot, stack))
                room += Math.max(0, slot.getMaxStackSize() - slot.getCount());
        }
        return room;
    }

    /** Drops the item at the player's position, not thrown ahead like a Q drop. */
    private static void dropAtFeet(ServerPlayer player, ItemStack stack) {
        ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY() + 0.25, player.getZ(),
                stack, 0.0, 0.1, 0.0);
        entity.setDefaultPickUpDelay();
        player.level().addFreshEntity(entity);
    }

    private record Delivery(int postmasterItems, int droppedItems) { }
}
