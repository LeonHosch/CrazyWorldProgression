package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.ItemLockClient;
import ekuzo.crazyworldprogression.storage.ItemLocks;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item locks in inventory windows: the lock key (L) on a hovered item of the player's own inventory cycles its lock,
 * and locked items get a padlock mark in the corner of their slot (yellow half, red full).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenLockMixin {
    @Shadow protected Slot hoveredSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void cwp$cycleLock(KeyEvent event, CallbackInfoReturnable<Boolean> info) {
        if (!ItemLockClient.key().matches(event) || hoveredSlot == null || !hoveredSlot.hasItem()
                || !(hoveredSlot.container instanceof Inventory)) return;
        ClientPlayNetworking.send(new ItemLocks.CyclePayload(hoveredSlot.index));
        info.setReturnValue(true);
    }

    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void cwp$lockMark(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo info) {
        int lock = ItemLocks.lock(slot.getItem());
        if (lock > 0) ItemLockClient.drawMark(graphics, slot.x, slot.y, lock);
    }
}
