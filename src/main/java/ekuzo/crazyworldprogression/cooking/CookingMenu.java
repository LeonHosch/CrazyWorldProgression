package ekuzo.crazyworldprogression.cooking;

import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The cooking menu, opened by a cauldron (stews) or a smoker: three ingredient slots, one slot for the bowl or coal,
 * a dish slot and the player's inventory. Menu button 0 cooks. The station comes with the opening data.
 */
public final class CookingMenu extends AbstractContainerMenu {
    public static final int INGREDIENT_X = 12, INGREDIENT_Y = 22, TOOL_X = 12, TOOL_Y = 50, OUTPUT_X = 76, OUTPUT_Y = 36;
    public static final int INVENTORY_X = 44, INVENTORY_Y = 124;
    public static final ExtendedMenuType<CookingMenu, String> TYPE = Registry.register(BuiltInRegistries.MENU,
            CrazyWorldProgression.id("cooking"),
            new ExtendedMenuType<>((id, inventory, state) -> new CookingMenu(id, inventory, ContainerLevelAccess.NULL,
                    stationOf(state), state), ByteBufCodecs.stringUtf8(262_144).cast()));

    private final Player player;
    private final CookingData.Station station;
    private final Container items = new SimpleContainer(5) {
        @Override public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };
    private final ContainerLevelAccess access;
    private String state;

    CookingMenu(int id, Inventory inventory, ContainerLevelAccess access, CookingData.Station station, String state) {
        super(TYPE, id);
        this.player = inventory.player;
        this.access = access;
        this.station = station;
        this.state = state;
        for (int index = 0; index < 3; index++) addSlot(new Slot(items, index, INGREDIENT_X + index * 18, INGREDIENT_Y));
        addSlot(new Slot(items, 3, TOOL_X, TOOL_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return CookingService.fitsTool(station, stack); }
        });
        addSlot(new Slot(items, 4, OUTPUT_X, OUTPUT_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
    }

    private static CookingData.Station stationOf(String state) {
        try { return CookingData.Station.parse(JsonParser.parseString(state).getAsJsonObject().get("station").getAsString()); }
        catch (RuntimeException exception) { return CookingData.Station.SMOKER; }
    }

    public static void initialize() {
        // Loading the class registers TYPE.
    }

    public CookingData.Station station() { return station; }

    public String state() { return state; }

    public void setState(String state) { this.state = state; }

    List<ItemStack> ingredients() { return List.of(items.getItem(0), items.getItem(1), items.getItem(2)); }

    ItemStack tool() { return items.getItem(3); }

    ItemStack output() { return items.getItem(4); }

    /** Runs with the station's level and position (null when the menu is not tied to a block). */
    <T> T atStation(java.util.function.BiFunction<Level, BlockPos, T> action) {
        return access.evaluate(action).orElseGet(() -> action.apply(null, null));
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == items && player instanceof ServerPlayer serverPlayer)
            CookingNetworking.sendState(serverPlayer, this, "", false);
    }

    @Override
    public boolean clickMenuButton(Player clicker, int button) {
        if (!(clicker instanceof ServerPlayer serverPlayer) || button != 0) return false;
        try {
            String message = atStation((level, pos) -> CookingService.cook(serverPlayer, station, level, pos,
                    ingredients(), tool(), output(), stack -> items.setItem(4, stack)));
            items.setChanged();
            broadcastChanges();
            CookingNetworking.sendState(serverPlayer, this, message, false);
        } catch (IllegalArgumentException exception) {
            CookingNetworking.sendState(serverPlayer, this, exception.getMessage(), true);
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < 5) {
            if (!moveItemStackTo(stack, 5, slots.size(), true)) return ItemStack.EMPTY;
        } else if (CookingService.fitsTool(station, stack)) {
            if (!moveItemStackTo(stack, 3, 4, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, 3, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player clicker) {
        return access.evaluate((level, pos) -> (station == CookingData.Station.STEW
                ? level.getBlockState(pos).is(BlockTags.CAULDRONS) : level.getBlockState(pos).is(Blocks.SMOKER))
                && clicker.isWithinBlockInteractionRange(pos, 4.0), true);
    }

    @Override
    public void removed(Player clicker) {
        super.removed(clicker);
        access.execute((level, pos) -> clearContainer(clicker, items));
    }
}
