package ekuzo.crazyworldprogression.loot;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
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

/** Validates and rolls editable pools. Each entry in an all-mode pool rolls independently. */
public final class LootPoolService {
    private LootPoolService() { }

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

    public static void delete(MinecraftServer server, Identifier id) {
        if (get(server, id) == null) throw new IllegalArgumentException("Unknown loot pool " + id);
        for (LootPoolData.Pool pool : all(server).values()) {
            if (pool.entries().stream().anyMatch(entry -> entry.kind().equals("pool") && entry.target().equals(id))) {
                throw new IllegalArgumentException("Loot pool is used by " + pool.id());
            }
        }
        for (MobScript script : MobForge.SCRIPTS.all()) {
            if (script.steps().stream().anyMatch(step -> step instanceof MobScript.LootPoolStep loot
                    && loot.lootPoolId().equals(id)))
                throw new IllegalArgumentException("Loot pool is used by script " + script.id());
        }
        LootPoolData.get(server).remove(id);
    }

    private static void validate(Identifier id, Map<String, LootPoolData.Pool> pools,
                                 Set<String> path, int depth) {
        LootPoolData.Pool pool = pools.get(id.toString());
        if (pool == null) throw new IllegalArgumentException("Unknown nested loot pool " + id);
        if (depth > 16) throw new IllegalArgumentException("Loot pools can nest at most 16 levels");
        if (!path.add(id.toString())) throw new IllegalArgumentException("Circular loot pool reference: " + id);
        if (pool.name().isBlank() || pool.name().length() > 80) throw new IllegalArgumentException("Pool name must be 1–80 characters");
        if (!pool.mode().equals("all") && !pool.mode().equals("one")) throw new IllegalArgumentException("Unknown loot pool mode");
        if (pool.entries().size() > 128) throw new IllegalArgumentException("Pool has more than 128 rewards");
        for (LootPoolData.Entry entry : pool.entries()) {
            if (entry.showInChat() && !entry.kind().equals("item"))
                throw new IllegalArgumentException("Chat announcements are available for item rewards only");
            if (entry.chance() < 0 || entry.chance() > 10000 || entry.weight() < 1 || entry.weight() > 10000)
                throw new IllegalArgumentException("Chance must be 0–100%; weight must be 1–10000");
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
        List<LootPoolData.Entry> rewards = new ArrayList<>();
        choose(id, pools, player, rewards, 0);
        int postmasterItems = 0;
        int droppedItems = 0;
        for (LootPoolData.Entry reward : rewards) {
            Delivery delivery = grant(server, player, reward);
            postmasterItems += delivery.postmasterItems();
            droppedItems += delivery.droppedItems();
        }
        if (postmasterItems > 0) player.sendSystemMessage(Component.literal(
                postmasterItems + " loot item(s) went to your postmaster because your inventory was full. Use /postmaster to collect them."));
        int used = PostmasterService.usedSlots(player);
        if (used > PostmasterService.SLOTS / 2) player.sendSystemMessage(Component.literal(
                "Warning: your postmaster is more than half full (" + used + "/" + PostmasterService.SLOTS + " slots)."));
        if (droppedItems > 0) player.sendSystemMessage(Component.literal(
                "Your postmaster was full. " + droppedItems + " loot item(s) dropped on the floor and anyone can pick them up."));
    }

    private static void choose(Identifier id, Map<String, LootPoolData.Pool> pools, ServerPlayer player,
                               List<LootPoolData.Entry> rewards, int depth) {
        LootPoolData.Pool pool = pools.get(id.toString());
        if (pool.entries().isEmpty()) return;
        if (pool.mode().equals("one")) {
            int total = pool.entries().stream().mapToInt(LootPoolData.Entry::weight).sum();
            int pick = player.getRandom().nextInt(total);
            for (LootPoolData.Entry entry : pool.entries()) {
                pick -= entry.weight();
                if (pick < 0) { select(entry, pools, player, rewards, depth); break; }
            }
        } else {
            for (LootPoolData.Entry entry : pool.entries()) {
                if (player.getRandom().nextInt(10000) < entry.chance()) select(entry, pools, player, rewards, depth);
            }
        }
    }

    private static void select(LootPoolData.Entry entry, Map<String, LootPoolData.Pool> pools,
                               ServerPlayer player, List<LootPoolData.Entry> rewards, int depth) {
        if (entry.kind().equals("pool")) choose(entry.target(), pools, player, rewards, depth + 1);
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
        ItemStack display = new ItemStack(item);
        while (amount > 0) {
            int count = Math.min(amount, stackSize);
            ItemStack stack = new ItemStack(item, count);
            player.addItem(stack);
            if (!stack.isEmpty()) {
                PostmasterService.DepositResult result = PostmasterService.deposit(player, stack);
                stored += result.stored();
                if (result.overflow() > 0) {
                    player.drop(stack.copyWithCount(result.overflow()), false);
                    dropped += result.overflow();
                }
            }
            amount -= count;
        }
        if (reward.showInChat()) server.getPlayerList().broadcastSystemMessage(
                Component.literal(player.getGameProfile().name() + " hat '")
                        .append(display.getStyledHoverName()).append("' bekommen"), false);
        return new Delivery(stored, dropped);
    }

    private record Delivery(int postmasterItems, int droppedItems) { }
}
