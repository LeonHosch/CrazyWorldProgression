package ekuzo.crazyworldprogression.smithing;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A chest window for more than six rows (two merged chests of a high quality): it shows six rows of the chest at a
 * time; menu buttons 0 and 1 scroll up and down a row (the screen sends them for the mouse wheel and its arrows).
 * Shift-clicking into the chest fills any row, not only the shown ones.
 */
public final class ScrollChestMenu extends ChestMenu {
    public static final int VISIBLE_ROWS = 6;
    /** A plain menu type (chests open through vanilla's menu providers); the row count comes as menu data. */
    public static final net.minecraft.world.inventory.MenuType<ScrollChestMenu> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("scroll_chest"), new net.minecraft.world.inventory.MenuType<>(
                    (id, inventory) -> new ScrollChestMenu(id, inventory, new SimpleContainer(VISIBLE_ROWS * 9), VISIBLE_ROWS, null),
                    net.minecraft.world.flag.FeatureFlags.VANILLA_SET));

    private final DataSlot rows = DataSlot.standalone();
    /** The whole chest (server side only). */
    private final Container whole;
    private final View view;
    private final DataSlot offset = DataSlot.standalone();

    private ScrollChestMenu(int id, Inventory inventory, Container shown, int rows, Container whole) {
        super(TYPE, id, inventory, shown, VISIBLE_ROWS);
        this.rows.set(rows);
        addDataSlot(this.rows);
        this.whole = whole;
        this.view = shown instanceof View value ? value : null;
        addDataSlot(offset);
    }

    /** The server side window over the whole chest. */
    public static ScrollChestMenu open(int id, Inventory inventory, Container whole) {
        return new ScrollChestMenu(id, inventory, new View(whole), whole.getContainerSize() / 9, whole);
    }

    public static void initialize() { }

    /** The whole chest (server side; null on the client). */
    public Container whole() { return whole; }

    /**
     * The whole chest on the server: chests count their viewers by asking open chest windows for their container, so
     * the shown rows alone made the chest think nobody was looking and its lid ran out of step.
     */
    @Override
    public Container getContainer() { return whole != null ? whole : super.getContainer(); }

    public int rows() { return Math.max(VISIBLE_ROWS, rows.get()); }

    /** The first shown row (synced to the client). */
    public int offset() { return offset.get(); }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (view == null || (button != 0 && button != 1)) return false;
        int next = Math.max(0, Math.min(rows() - VISIBLE_ROWS, view.offset + (button == 0 ? -1 : 1)));
        view.offset = next;
        offset.set(next);
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (whole == null || index < VISIBLE_ROWS * 9) return super.quickMoveStack(player, index);
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        // Into the whole chest: first onto matching stacks, then into empty slots.
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++)
            for (int index2 = 0; index2 < whole.getContainerSize() && !stack.isEmpty(); index2++) {
                ItemStack there = whole.getItem(index2);
                if (!whole.canPlaceItem(index2, stack)) continue;
                if (pass == 0 && !there.isEmpty() && ItemStack.isSameItemSameComponents(there, stack)) {
                    int moved = Math.min(stack.getCount(), there.getMaxStackSize() - there.getCount());
                    if (moved > 0) {
                        there.grow(moved);
                        stack.shrink(moved);
                    }
                } else if (pass == 1 && there.isEmpty()) {
                    whole.setItem(index2, stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
                }
            }
        whole.setChanged();
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return stack.getCount() == original.getCount() ? ItemStack.EMPTY : original;
    }

    /** Six rows of the whole chest, starting at a row. */
    private static final class View implements Container {
        private final Container whole;
        int offset;

        View(Container whole) { this.whole = whole; }

        private int at(int slot) { return offset * 9 + slot; }

        @Override public int getContainerSize() { return VISIBLE_ROWS * 9; }
        @Override public boolean isEmpty() { return whole.isEmpty(); }
        @Override public ItemStack getItem(int slot) { return at(slot) < whole.getContainerSize() ? whole.getItem(at(slot)) : ItemStack.EMPTY; }
        @Override public ItemStack removeItem(int slot, int amount) { return whole.removeItem(at(slot), amount); }
        @Override public ItemStack removeItemNoUpdate(int slot) { return whole.removeItemNoUpdate(at(slot)); }
        @Override public void setItem(int slot, ItemStack stack) { whole.setItem(at(slot), stack); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return whole.canPlaceItem(at(slot), stack); }
        @Override public int getMaxStackSize() { return whole.getMaxStackSize(); }
        @Override public void setChanged() { whole.setChanged(); }
        @Override public boolean stillValid(Player player) { return whole.stillValid(player); }
        @Override public void startOpen(net.minecraft.world.entity.ContainerUser user) { whole.startOpen(user); }
        @Override public void stopOpen(net.minecraft.world.entity.ContainerUser user) { whole.stopOpen(user); }
        @Override public void clearContent() { whole.clearContent(); }
    }
}
