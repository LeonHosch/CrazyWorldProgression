package ekuzo.crazyworldprogression.fletching;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * The fletching menu opened by a fletching table: only the player's inventory. Menu button n crafts recipe n (in the
 * order FletchingData lists them); the window shows recipes and costs from the state sent on opening.
 */
public final class FletchingMenu extends AbstractContainerMenu {
    public static final int INVENTORY_X = 69, INVENTORY_Y = 140;
    public static final ExtendedMenuType<FletchingMenu, String> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("fletching"),
            new ExtendedMenuType<>((id, inventory, state) -> new FletchingMenu(id, inventory, ContainerLevelAccess.NULL, state),
                    ByteBufCodecs.stringUtf8(262_144).cast()));

    private final ContainerLevelAccess access;
    /** The last screen state from the server (client side only). */
    private String state;

    FletchingMenu(int id, Inventory inventory, ContainerLevelAccess access, String state) {
        super(TYPE, id);
        this.access = access;
        this.state = state;
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
    }

    public static void initialize() {
        // Loading the class registers TYPE.
    }

    public String state() { return state; }

    public void setState(String state) { this.state = state; }

    @Override
    public boolean clickMenuButton(Player clicker, int button) {
        if (!(clicker instanceof ServerPlayer player)) return false;
        var recipes = FletchingService.recipes(player.level().getServer());
        if (button < 0 || button >= recipes.size()) return false;
        try {
            String message = FletchingService.craft(player, recipes.get(button));
            FletchingNetworking.sendState(player, message, false);
        } catch (IllegalArgumentException exception) {
            FletchingNetworking.sendState(player, exception.getMessage(), true);
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        // Between the hotbar and the rest of the inventory, as in other windows without their own slots.
        boolean hotbar = index >= 27;
        if (!moveItemStackTo(stack, hotbar ? 0 : 27, hotbar ? 27 : 36, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player clicker) {
        return access.evaluate((level, pos) -> level.getBlockState(pos).is(Blocks.FLETCHING_TABLE)
                && clicker.isWithinBlockInteractionRange(pos, 4.0), true);
    }
}
