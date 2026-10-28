package ekuzo.crazyworldprogression.smithing;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The repair menu opened by an anvil: one item slot plus the player's inventory. Menu button 0 repairs the item.
 * The window works out tier, costs and success rate itself from the data sent on opening; the server only checks
 * a repair when it is requested.
 */
public final class SmithingMenu extends AbstractContainerMenu {
    public static final int ITEM_X = 46, ITEM_Y = 43, INVENTORY_X = 44, INVENTORY_Y = 124;
    public static final ExtendedMenuType<SmithingMenu, String> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("smithing"),
            new ExtendedMenuType<>((id, inventory, state) -> new SmithingMenu(id, inventory, ContainerLevelAccess.NULL, state),
                    ByteBufCodecs.stringUtf8(262_144).cast()));

    private final Player player;
    private final Container item = new SimpleContainer(1) {
        @Override public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };
    private final ContainerLevelAccess access;
    /** The last screen state from the server (client side only). */
    private String state;

    SmithingMenu(int id, Inventory inventory, ContainerLevelAccess access, String state) {
        super(TYPE, id);
        this.player = inventory.player;
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
    public boolean clickMenuButton(Player clicker, int button) {
        if (!(clicker instanceof ServerPlayer serverPlayer) || button != 0) return false;
        ItemStack stack = item.getItem(0);
        try {
            SmithingService.Result result = SmithingService.resmith(serverPlayer, stack);
            broadcastChanges();
            SmithingNetworking.sendState(serverPlayer, stack, result.message(), !result.success());
        } catch (IllegalArgumentException exception) {
            SmithingNetworking.sendState(serverPlayer, stack, exception.getMessage(), true);
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (slots.getFirst().hasItem()) return ItemStack.EMPTY;
            slots.getFirst().setByPlayer(stack.split(1));
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player clicker) {
        return access.evaluate((level, pos) -> level.getBlockState(pos).is(BlockTags.ANVIL)
                && clicker.isWithinBlockInteractionRange(pos, 4.0), true);
    }

    @Override
    public void removed(Player clicker) {
        super.removed(clicker);
        access.execute((level, pos) -> clearContainer(clicker, item));
    }
}
