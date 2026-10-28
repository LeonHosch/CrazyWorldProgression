package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.accessory.Accessories;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Adds the two accessory slots, the buff food slot and the three ammo slots after all vanilla slots, so vanilla slot numbers stay the same.
 * Shift-clicking an accessory in the inventory puts it into the first free accessory slot, and buff food into the
 * buff food slot, and arrows into the ammo slots; when it does not fit the click moves it the vanilla way.
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuAccessoryMixin extends AbstractContainerMenu {
    protected InventoryMenuAccessoryMixin(MenuType<?> type, int id) {
        super(type, id);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cwp$addAccessorySlots(Inventory inventory, boolean active, Player owner, CallbackInfo info) {
        var container = Accessories.container(owner);
        for (int index = 0; index < Accessories.SLOTS; index++) addSlot(Accessories.slot(owner, container, index));
        addSlot(ekuzo.crazyworldprogression.accessory.BuffFood.slot(owner, ekuzo.crazyworldprogression.accessory.BuffFood.container(owner)));
        var ammo = ekuzo.crazyworldprogression.ammo.AmmoPouch.container(owner);
        for (int index = 0; index < ekuzo.crazyworldprogression.ammo.AmmoPouch.SLOTS; index++)
            addSlot(ekuzo.crazyworldprogression.ammo.AmmoPouch.slot(owner, ammo, index));
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void cwp$quickMoveAccessory(Player player, int index, CallbackInfoReturnable<ItemStack> info) {
        if (index < 0 || index >= slots.size()) return;
        Slot source = slots.get(index);
        // Ammo slots: out into the inventory (in normal stacks), and arrows from the inventory into them.
        if (ekuzo.crazyworldprogression.ammo.AmmoPouch.isSlot(source)) {
            if (source.hasItem() && moveItemStackTo(source.getItem(), 9, 45, false)) source.setChanged();
            info.setReturnValue(ItemStack.EMPTY);
            return;
        }
        if (source.hasItem() && !player.isCreative() && ekuzo.crazyworldprogression.ammo.AmmoPouch.fits(source.getItem())) {
            Slot first = slots.stream().filter(ekuzo.crazyworldprogression.ammo.AmmoPouch::isSlot).findFirst().orElse(null);
            if (first != null && ekuzo.crazyworldprogression.ammo.AmmoPouch.insert(first.container, source.getItem())) {
                if (source.getItem().isEmpty()) source.setByPlayer(ItemStack.EMPTY);
                source.setChanged();
                info.setReturnValue(ItemStack.EMPTY);
                return;
            }
        }
        if (!ekuzo.crazyworldprogression.accessory.BuffFood.isSlot(source) && source.hasItem()
                && ekuzo.crazyworldprogression.accessory.BuffFood.fits(source.getItem())) {
            for (Slot target : slots) {
                if (!ekuzo.crazyworldprogression.accessory.BuffFood.isSlot(target)) continue;
                ItemStack moving = source.getItem();
                if (target.hasItem() && !ItemStack.isSameItemSameComponents(target.getItem(), moving)) break;
                int room = moving.getMaxStackSize() - target.getItem().getCount();
                if (room <= 0) break;
                ItemStack moved = source.remove(Math.min(room, moving.getCount()));
                if (target.hasItem()) moved.grow(target.getItem().getCount());
                target.setByPlayer(moved);
                source.setChanged();
                info.setReturnValue(ItemStack.EMPTY);
                return;
            }
        }
        if (Accessories.isSlot(source) || !source.hasItem() || !source.getItem().is(Accessories.TAG)) return;
        for (Slot target : slots) {
            if (!Accessories.isSlot(target) || target.hasItem() || !target.mayPlace(source.getItem())) continue;
            target.setByPlayer(source.remove(1));
            source.setChanged();
            info.setReturnValue(ItemStack.EMPTY);
            return;
        }
    }
}
