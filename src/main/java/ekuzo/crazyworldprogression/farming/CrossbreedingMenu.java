package ekuzo.crazyworldprogression.farming;

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
import net.minecraft.world.level.block.Blocks;

/**
 * The crossbreeding menu opened by a composter: two seed slots, one result slot and the player's inventory. Menu
 * button 0 crossbreeds. Recipes are never sent to the client, so unknown results stay secret: whenever the seeds
 * change, the server works out what the window may show and sends it (CrossbreedingNetworking.state).
 */
public final class CrossbreedingMenu extends AbstractContainerMenu {
    public static final int FIRST_X = 18, SEED_Y = 30, SECOND_X = 66, OUTPUT_X = 42, OUTPUT_Y = 56;
    public static final int INVENTORY_X = 44, INVENTORY_Y = 124;
    public static final ExtendedMenuType<CrossbreedingMenu, String> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("crossbreeding"),
            new ExtendedMenuType<>((id, inventory, state) -> new CrossbreedingMenu(id, inventory, ContainerLevelAccess.NULL, state),
                    ByteBufCodecs.stringUtf8(262_144).cast()));

    private final Player player;
    private final Container seeds = new SimpleContainer(3) {
        @Override public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };
    private final ContainerLevelAccess access;
    /** The last screen state from the server (client side only). */
    private String state;

    CrossbreedingMenu(int id, Inventory inventory, ContainerLevelAccess access, String state) {
        super(TYPE, id);
        this.player = inventory.player;
        this.access = access;
        this.state = state;
        addSlot(new Slot(seeds, 0, FIRST_X, SEED_Y));
        addSlot(new Slot(seeds, 1, SECOND_X, SEED_Y));
        addSlot(new Slot(seeds, 2, OUTPUT_X, OUTPUT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
    }

    public static void initialize() {
        // Loading the class registers TYPE.
    }

    public String state() { return state; }

    public void setState(String state) { this.state = state; }

    public ItemStack first() { return seeds.getItem(0); }

    public ItemStack second() { return seeds.getItem(1); }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == seeds && player instanceof ServerPlayer serverPlayer)
            CrossbreedingNetworking.sendState(serverPlayer, this, "", false);
    }

    ItemStack output() { return seeds.getItem(2); }

    @Override
    public boolean clickMenuButton(Player clicker, int button) {
        if (!(clicker instanceof ServerPlayer serverPlayer) || button != 0) return false;
        try {
            CrossbreedingService.Result result = CrossbreedingService.breed(serverPlayer, first(), second(), output(),
                    stack -> seeds.setItem(2, stack));
            seeds.setChanged();
            broadcastChanges();
            CrossbreedingNetworking.sendState(serverPlayer, this, result.message(), !result.success());
        } catch (IllegalArgumentException exception) {
            CrossbreedingNetworking.sendState(serverPlayer, this, exception.getMessage(), true);
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < 3) {
            if (!moveItemStackTo(stack, 3, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, 2, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player clicker) {
        return access.evaluate((level, pos) -> level.getBlockState(pos).is(Blocks.COMPOSTER)
                && clicker.isWithinBlockInteractionRange(pos, 4.0), true);
    }

    @Override
    public void removed(Player clicker) {
        super.removed(clicker);
        access.execute((level, pos) -> clearContainer(clicker, seeds));
    }
}
