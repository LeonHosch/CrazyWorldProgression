package ekuzo.crazyworldprogression.divine;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * The Divine Protection menu opened by the content mod's block: one item slot plus the player's inventory. Menu
 * button 0 adds the item's next charge. The window works out costs and requirements from the data sent on opening.
 */
public final class DivineProtectionMenu extends AbstractContainerMenu {
    public static final int ITEM_X = 46, ITEM_Y = 43, INVENTORY_X = 44, INVENTORY_Y = 124;
    public static final ExtendedMenuType<DivineProtectionMenu, String> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("divine_protection"),
            new ExtendedMenuType<>((id, inventory, state) -> new DivineProtectionMenu(id, inventory, ContainerLevelAccess.NULL,
                    ignored -> true, state), ByteBufCodecs.stringUtf8(262_144).cast()));

    private final Container item = new SimpleContainer(1) {
        @Override public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };
    private final ContainerLevelAccess access;
    private final Predicate<BlockState> block;
    /** The last screen state from the server (client side only). */
    private String state;

    DivineProtectionMenu(int id, Inventory inventory, ContainerLevelAccess access, Predicate<BlockState> block, String state) {
        super(TYPE, id);
        this.access = access;
        this.block = block;
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
        try {
            DivineProtection.Result result = DivineProtection.charge(serverPlayer, item.getItem(0));
            item.setChanged();
            broadcastChanges();
            DivineProtectionNetworking.sendState(serverPlayer, result.message(), !result.success());
        } catch (IllegalArgumentException exception) {
            DivineProtectionNetworking.sendState(serverPlayer, exception.getMessage(), true);
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
        return access.evaluate((level, pos) -> block.test(level.getBlockState(pos))
                && clicker.isWithinBlockInteractionRange(pos, 4.0), true);
    }

    @Override
    public void removed(Player clicker) {
        super.removed(clicker);
        access.execute((level, pos) -> clearContainer(clicker, item));
    }
}
