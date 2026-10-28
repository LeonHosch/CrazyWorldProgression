package ekuzo.crazyworldprogression.reforge;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The reforge menu opened by the enchanting table: one item slot plus the player's inventory. Clicking a menu
 * button with a tier's index reforges the item at that tier. The opening data is the screen state as JSON.
 */
public final class ReforgeMenu extends AbstractContainerMenu {
    public static final int ITEM_X = 46, ITEM_Y = 43, INVENTORY_X = 44, INVENTORY_Y = 124;
    /**
     * The reforge button is tier * FOCUS_SCALE + (index of the focused enchantment in the research list + 1), or
     * + 0 without focus. The focus is chosen in the window only; the server checks it against the research.
     */
    public static final int FOCUS_SCALE = 1_000;
    public static final ExtendedMenuType<ReforgeMenu, String> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("reforge"),
            new ExtendedMenuType<>((id, inventory, state) -> new ReforgeMenu(id, inventory, ContainerLevelAccess.NULL, state),
                    ByteBufCodecs.stringUtf8(1_048_576).cast()));

    private final Container item = new SimpleContainer(1) {
        @Override public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };
    private final ContainerLevelAccess access;
    /** The last screen state from the server (client side only). */
    private String state;

    ReforgeMenu(int id, Inventory inventory, ContainerLevelAccess access, String state) {
        super(TYPE, id);
        this.access = access;
        this.state = state;
        addSlot(new Slot(item, 0, ITEM_X, ITEM_Y) {
            @Override public int getMaxStackSize() { return 1; }
        });
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
    }

    public static void initialize() {
        // Loading the class registers TYPE.
    }

    public String state() { return state; }

    public void setState(String state) { this.state = state; }

    public ItemStack item() { return item.getItem(0); }

    @Override
    public boolean clickMenuButton(Player player, int tierIndex) {
        if (!(player instanceof ServerPlayer serverPlayer)) return false;
        // The button carries the tier and the focus the window picked: tier * FOCUS_SCALE + research index + 1.
        int tier = tierIndex / FOCUS_SCALE;
        ResearchService.Focus focus = ResearchService.focusAt(serverPlayer, tierIndex % FOCUS_SCALE - 1);
        ItemStack stack = item.getItem(0);
        try {
            Component result = ReforgeService.reforge(serverPlayer, tier, stack, focus);
            item.setChanged();
            broadcastChanges();
            ReforgeNetworking.sendState(serverPlayer, result.getString(), false);
        } catch (IllegalArgumentException exception) {
            ReforgeNetworking.sendState(serverPlayer, exception.getMessage(), true);
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (slots.getFirst().hasItem()) return ItemStack.EMPTY;
            ItemStack single = stack.split(1);
            slots.getFirst().setByPlayer(single);
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, Blocks.ENCHANTING_TABLE);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, pos) -> clearContainer(player, item));
    }
}
