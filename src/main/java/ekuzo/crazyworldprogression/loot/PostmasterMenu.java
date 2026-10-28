package ekuzo.crazyworldprogression.loot;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A chest layout with exactly twenty claimable storage slots (the last row's seven are sealed). It has its own menu
 * type so the client can draw the post office window (PostmasterScreen); the opening data is the used slot count.
 */
public final class PostmasterMenu extends ChestMenu {
    public static final ExtendedMenuType<PostmasterMenu, Integer> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("postmaster"),
            new ExtendedMenuType<>((id, inventory, used) -> new PostmasterMenu(id, inventory, new SimpleContainer(27), used),
                    ByteBufCodecs.VAR_INT.cast()));
    private final Container storage;
    private final int used;

    PostmasterMenu(int id, Inventory inventory, Container storage, int used) {
        super(TYPE, id, inventory, storage, 3);
        this.storage = storage;
        this.used = used;
        for (int index = 0; index < 27; index++) {
            boolean reserved = index >= PostmasterService.SLOTS;
            slots.set(index, new Slot(storage, index, 8 + index % 9 * 18, 18 + index / 9 * 18) {
                @Override public boolean mayPickup(Player player) { return !reserved; }
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean isHighlightable() { return !reserved; }
            });
        }
    }

    static void initialize() {
        // Loading the class registers TYPE.
    }

    /** Occupied slots when the window opened. */
    public int used() { return used; }

    /** Whether the storage slot is one of the sealed ones past the twenty usable slots. */
    public static boolean sealed(int index) { return index >= PostmasterService.SLOTS && index < 27; }

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
