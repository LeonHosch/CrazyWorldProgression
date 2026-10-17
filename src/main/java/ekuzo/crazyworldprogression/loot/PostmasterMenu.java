package ekuzo.crazyworldprogression.loot;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Vanilla chest presentation with exactly twenty claimable storage slots. */
final class PostmasterMenu extends ChestMenu {
    private final Container storage;

    PostmasterMenu(int id, Inventory inventory, Container storage) {
        super(MenuType.GENERIC_9x3, id, inventory, storage, 3);
        this.storage = storage;
        for (int index = 0; index < 27; index++) {
            boolean reserved = index >= PostmasterService.SLOTS;
            slots.set(index, new Slot(storage, index, 8 + index % 9 * 18, 18 + index / 9 * 18) {
                @Override public boolean mayPickup(Player player) { return !reserved; }
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean isHighlightable() { return !reserved; }
            });
        }
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= PostmasterService.SLOTS)
            return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = moveItemStackTo(stack, 27, slots.size(), true);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override public void clicked(int index, int button, ContainerInput action, Player player) {
        if (index >= PostmasterService.SLOTS && index < 27) return;
        super.clicked(index, button, action, player);
    }

    @Override public void removed(Player player) {
        super.removed(player);
        PostmasterService.closed(player, storage);
    }
}
