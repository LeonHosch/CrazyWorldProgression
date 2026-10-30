package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.accessory.Accessories;
import ekuzo.crazyworldprogression.accessory.BuffFood;
import ekuzo.crazyworldprogression.ammo.AmmoPouch;
import ekuzo.crazyworldprogression.client.CreativeSlotsClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows the accessory, buff food and ammo slots on the creative inventory tab, in the free space right of the player:
 * accessories and buff food in the top row, the three ammo slots below.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventorySlotsMixin extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {
    protected CreativeInventorySlotsMixin(CreativeModeInventoryScreen.ItemPickerMenu menu, Inventory inventory,
                                          Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "selectTab", at = @At("TAIL"))
    private void cwp$placeExtraSlots(CreativeModeTab tab, CallbackInfo info) {
        if (minecraft == null || minecraft.player == null || tab.getType() != CreativeModeTab.Type.INVENTORY) return;
        var source = minecraft.player.inventoryMenu.slots;
        int accessory = 0, ammo = 0;
        for (int index = 0; index < source.size() && index < menu.slots.size(); index++) {
            Slot target = source.get(index);
            int x, y;
            if (Accessories.isSlot(target)) { x = CreativeSlotsClient.X + 18 * accessory++; y = CreativeSlotsClient.TOP_Y; }
            else if (BuffFood.isSlot(target)) { x = CreativeSlotsClient.X + 36; y = CreativeSlotsClient.TOP_Y; }
            else if (AmmoPouch.isSlot(target)) { x = CreativeSlotsClient.X + 18 * ammo++; y = CreativeSlotsClient.AMMO_Y; }
            else continue;
            Slot wrapper = CreativeSlotsClient.wrap(target, index, x, y);
            if (wrapper != null) menu.slots.set(index, wrapper);
        }
    }

    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void cwp$extraSlotFrames(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (!((CreativeModeInventoryScreen) (Object) this).isInventoryOpen()) return;
        for (Slot slot : menu.slots) {
            if (!CreativeSlotsClient.isExtra(slot)) continue;
            int x = leftPos + slot.x - 1, y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
            graphics.fill(x, y, x + 17, y + 1, 0xFF373737);
            graphics.fill(x, y, x + 1, y + 17, 0xFF373737);
            graphics.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF);
            graphics.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        }
    }
}
