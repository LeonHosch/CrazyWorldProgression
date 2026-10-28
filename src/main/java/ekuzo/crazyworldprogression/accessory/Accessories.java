package ekuzo.crazyworldprogression.accessory;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.equipment.EquipmentSetService;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Two accessory slots per player, shown in the inventory next to the player model. Any item in the
 * {@code crazy-world-progression:accessories} item tag fits (rings, necklaces, ...), one per slot, and the same
 * item cannot be worn twice. Accessories count as pieces of equipment sets. On death they follow the inventory
 * (dropped without keep-inventory, stored in the raid orb on a wipe). Right-click equips one from the hand, and
 * shift-click moves one from the inventory into a free slot.
 */
public final class Accessories {
    public static final int SLOTS = 2;
    /** Positions in the vanilla inventory screen: right of the player model, above the offhand slot. */
    public static final int SLOT_X = 77, FIRST_SLOT_Y = 26;
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, CrazyWorldProgression.id("accessories"));

    private static final Codec<List<ItemStack>> CODEC = ItemStack.OPTIONAL_CODEC.listOf();
    public static final AttachmentType<List<ItemStack>> STORAGE = AttachmentRegistry.create(
            CrazyWorldProgression.id("accessories"), builder -> builder.persistent(CODEC).copyOnDeath()
                    .initializer(() -> new ArrayList<>(NonNullList.withSize(SLOTS, ItemStack.EMPTY))));

    private Accessories() { }

    public static void initialize() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack held = player.getItemInHand(hand);
            if (!held.is(TAG)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) equipFromHand(serverPlayer, hand);
            return InteractionResult.SUCCESS;
        });
    }

    public static boolean isSlot(Slot slot) { return slot instanceof AccessorySlot; }

    /** Whether {@code stack} may go into {@code index}: an accessory, and not the item another slot already holds. */
    public static boolean fits(List<ItemStack> worn, int index, ItemStack stack) {
        if (!stack.is(TAG)) return false;
        for (int other = 0; other < worn.size(); other++)
            if (other != index && !worn.get(other).isEmpty() && worn.get(other).is(stack.getItem())) return false;
        return true;
    }

    /**
     * Right-click: into the first free slot, or swapped with the worn one of the same item, or with the first
     * slot when both are full.
     */
    static void equipFromHand(ServerPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        List<ItemStack> worn = worn(player);
        int target = -1;
        for (int index = 0; index < SLOTS && target < 0; index++)
            if (!worn.get(index).isEmpty() && worn.get(index).is(held.getItem())) target = index;
        for (int index = 0; index < SLOTS && target < 0; index++)
            if (worn.get(index).isEmpty() && fits(worn, index, held)) target = index;
        if (target < 0 && fits(worn, 0, held)) target = 0;
        if (target < 0) return;
        Container container = container(player);
        ItemStack previous = container.getItem(target);
        container.setItem(target, held.split(1));
        if (held.isEmpty()) player.setItemInHand(hand, previous);
        else if (!previous.isEmpty()) player.getInventory().placeItemBackInInventory(previous);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ARMOR_EQUIP_GOLD.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /**
     * Death without keep-inventory: the worn accessories drop like the rest of the inventory, except those with
     * Divine Protection, which stay worn and use up a charge.
     */
    public static void dropAll(ServerPlayer player) {
        List<ItemStack> worn = worn(player);
        clear(player);
        for (int index = 0; index < worn.size(); index++) {
            ItemStack stack = worn.get(index);
            if (stack.isEmpty()) continue;
            if (ekuzo.crazyworldprogression.divine.DivineProtection.charges(stack) > 0)
                set(player, index, ekuzo.crazyworldprogression.divine.DivineProtection.use(player, stack.copy()));
            else player.drop(stack, true, false);
        }
    }

    public static void clear(Player player) {
        container(player).clearContent();
        if (player instanceof ServerPlayer serverPlayer) EquipmentSetService.refreshIfChanged(serverPlayer);
    }

    /** Sets one slot directly (raid orb restore). */
    public static void set(Player player, int index, ItemStack stack) { container(player).setItem(index, stack); }

    /** The worn accessories (empty stacks for empty slots). */
    public static List<ItemStack> worn(Player player) {
        List<ItemStack> stored = player.getAttachedOrCreate(STORAGE);
        List<ItemStack> result = new ArrayList<>(SLOTS);
        for (int index = 0; index < SLOTS; index++) result.add(index < stored.size() ? stored.get(index) : ItemStack.EMPTY);
        return result;
    }

    /** The player's accessory slots as a container, for the inventory menu. */
    public static Container container(Player player) { return new AccessoryContainer(player); }

    public static Slot slot(Player player, Container container, int index) {
        return new AccessorySlot(container, index, SLOT_X, FIRST_SLOT_Y + index * 18, player);
    }

    private static final class AccessoryContainer implements Container {
        private final Player player;

        private AccessoryContainer(Player player) { this.player = player; }

        private List<ItemStack> storage() {
            List<ItemStack> stored = new ArrayList<>(player.getAttachedOrCreate(STORAGE));
            while (stored.size() < SLOTS) stored.add(ItemStack.EMPTY);
            return stored;
        }

        private void store(List<ItemStack> stored) { player.setAttached(STORAGE, stored); }

        @Override public int getContainerSize() { return SLOTS; }

        @Override public boolean isEmpty() { return storage().stream().allMatch(ItemStack::isEmpty); }

        @Override public ItemStack getItem(int slot) { return storage().get(slot); }

        @Override public ItemStack removeItem(int slot, int amount) {
            List<ItemStack> stored = storage();
            ItemStack removed = stored.get(slot).split(amount);
            if (stored.get(slot).isEmpty()) stored.set(slot, ItemStack.EMPTY);
            store(stored);
            setChanged();
            return removed;
        }

        @Override public ItemStack removeItemNoUpdate(int slot) {
            List<ItemStack> stored = storage();
            ItemStack removed = stored.get(slot);
            stored.set(slot, ItemStack.EMPTY);
            store(stored);
            return removed;
        }

        @Override public void setItem(int slot, ItemStack stack) {
            List<ItemStack> stored = storage();
            stored.set(slot, stack);
            store(stored);
            setChanged();
        }

        @Override public int getMaxStackSize() { return 1; }

        /** Wearing or removing an accessory can switch set bonuses on or off. */
        @Override public void setChanged() {
            if (player instanceof ServerPlayer serverPlayer) EquipmentSetService.refreshIfChanged(serverPlayer);
        }

        @Override public boolean stillValid(Player user) { return user == player; }

        @Override public void clearContent() { store(new ArrayList<>(NonNullList.withSize(SLOTS, ItemStack.EMPTY))); }
    }

    private static final class AccessorySlot extends Slot {
        private final Player player;

        private AccessorySlot(Container container, int index, int x, int y, Player player) {
            super(container, index, x, y);
            this.player = player;
        }

        /** Accessories only, and not the same item that the other slot already holds. */
        @Override public boolean mayPlace(ItemStack stack) { return fits(worn(player), getContainerSlot(), stack); }

        @Override public int getMaxStackSize() { return 1; }

        /** The creative inventory lays slots out on its own and has no place for these. */
        @Override public boolean isActive() { return !player.isCreative(); }
    }
}
