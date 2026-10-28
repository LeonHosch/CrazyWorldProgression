package ekuzo.crazyworldprogression.storage;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.smithing.ScrollChestMenu;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Sort and Quick stack buttons: sort the open chest (merge stacks, then order by category, item and amount), quick
 * stack the main inventory into the open chest (only items it already holds), or sort the player's main inventory
 * (the hotbar and fully locked items stay where they are). Each is one pass over at most a few hundred slots on a
 * button click, and a player can use them at most every {@link #COOLDOWN_TICKS} ticks.
 */
public final class SortActions {
    public static final int COOLDOWN_TICKS = 5;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    /** Client to server: "sort_chest", "stack_chest" or "sort_inventory". */
    public record ActionPayload(String action) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(CrazyWorldProgression.id("sort_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ActionPayload::action, ActionPayload::new);
        @Override public Type<ActionPayload> type() { return TYPE; }
    }

    private SortActions() { }

    static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            long now = player.level().getGameTime();
            Long last = LAST.get(player.getUUID());
            if (last != null && now - last < COOLDOWN_TICKS) return;
            LAST.put(player.getUUID(), now);
            switch (payload.action()) {
                case "sort_chest" -> { Container chest = openChest(player); if (chest != null) sort(chest, 0, chest.getContainerSize()); }
                case "stack_chest" -> { Container chest = openChest(player); if (chest != null) quickStack(player, chest); }
                case "sort_inventory" -> sort(player.getInventory(), 9, 36);
                default -> { return; }
            }
            player.containerMenu.broadcastChanges();
        });
    }

    /** The chest behind the open chest window (the whole chest for a scrolling one), or null. */
    private static Container openChest(ServerPlayer player) {
        if (player.containerMenu instanceof ScrollChestMenu scroll) return scroll.whole();
        if (player.containerMenu instanceof ChestMenu chest && chest.stillValid(player)) return chest.getContainer();
        return null;
    }

    /** Sorts the slots from..to of a container; in the player's inventory fully locked items keep their slots. */
    static void sort(Container container, int from, int to) {
        boolean inventory = container instanceof Inventory;
        List<ItemStack> items = new ArrayList<>();
        List<Integer> free = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = container.getItem(slot);
            if (inventory && ItemLocks.lock(stack) == ItemLocks.FULL) continue;
            free.add(slot);
            if (!stack.isEmpty()) items.add(stack.copy());
        }
        // Merge equal stacks.
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : items) {
            for (ItemStack target : merged) {
                if (stack.isEmpty()) break;
                if (!ItemStack.isSameItemSameComponents(target, stack)) continue;
                int moved = Math.min(stack.getCount(), target.getMaxStackSize() - target.getCount());
                if (moved > 0) {
                    target.grow(moved);
                    stack.shrink(moved);
                }
            }
            if (!stack.isEmpty()) merged.add(stack);
        }
        merged.sort(Comparator.<ItemStack>comparingInt(SortActions::categoryOrder)
                .thenComparing(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));
        for (int index = 0; index < free.size(); index++)
            container.setItem(free.get(index), index < merged.size() ? merged.get(index) : ItemStack.EMPTY);
        container.setChanged();
    }

    private static int categoryOrder(ItemStack stack) {
        var tabs = ItemCategories.tabs();
        for (int index = 0; index < tabs.size(); index++)
            if (ItemCategories.of(stack).contains(ItemCategories.id(tabs.get(index)))) return index;
        return tabs.size();
    }

    /** Puts main inventory items (not locked ones) into this chest where it already holds the same item. */
    static void quickStack(ServerPlayer player, Container chest) {
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || ItemLocks.locked(stack) || !Quicksort.contains(chest, stack)) continue;
            Quicksort.insert(chest, stack);
        }
        chest.setChanged();
        player.getInventory().setChanged();
    }
}
