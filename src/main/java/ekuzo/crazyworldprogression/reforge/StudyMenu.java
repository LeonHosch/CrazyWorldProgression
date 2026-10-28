package ekuzo.crazyworldprogression.reforge;

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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** The study table opened by a lectern: one enchanted-book slot plus the player's inventory. Button 0 studies it. */
public final class StudyMenu extends AbstractContainerMenu {
    public static final int ITEM_X = 46, ITEM_Y = 43, INVENTORY_X = 44, INVENTORY_Y = 124;
    public static final ExtendedMenuType<StudyMenu, String> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("study"),
            new ExtendedMenuType<>((id, inventory, state) -> new StudyMenu(id, inventory, ContainerLevelAccess.NULL, state),
                    ByteBufCodecs.stringUtf8(262_144).cast()));

    private final Player player;
    private final Container book = new SimpleContainer(1) {
        @Override public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };
    private final ContainerLevelAccess access;
    private String state;

    StudyMenu(int id, Inventory inventory, ContainerLevelAccess access, String state) {
        super(TYPE, id);
        this.player = inventory.player;
        this.access = access;
        this.state = state;
        addSlot(new Slot(book, 0, ITEM_X, ITEM_Y) {
            @Override public int getMaxStackSize() { return 1; }
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.ENCHANTED_BOOK); }
        });
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
    }

    static void initialize() {
        // Loading the class registers TYPE.
    }

    public String state() { return state; }

    public void setState(String state) { this.state = state; }

    public ItemStack book() { return book.getItem(0); }

    @Override
    public boolean clickMenuButton(Player clicker, int button) {
        if (!(clicker instanceof ServerPlayer serverPlayer) || button != 0) return false;
        try {
            String result = ResearchService.study(serverPlayer, book.getItem(0));
            book.setChanged();
            broadcastChanges();
            ResearchService.sendState(serverPlayer, book(), result, false);
        } catch (IllegalArgumentException exception) {
            ResearchService.sendState(serverPlayer, book(), exception.getMessage(), true);
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
            if (slots.getFirst().hasItem() || !stack.is(Items.ENCHANTED_BOOK)) return ItemStack.EMPTY;
            slots.getFirst().setByPlayer(stack.split(1));
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player clicker) {
        return stillValid(access, clicker, Blocks.LECTERN);
    }

    @Override
    public void removed(Player clicker) {
        super.removed(clicker);
        access.execute((level, pos) -> clearContainer(clicker, book));
    }
}
