package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.EchelonClientSettings;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerHotbarDropMixin {
    private int echelon$armedSlot = -1;
    private long echelon$armedUntil;
    private boolean echelon$carriedFromHotbar;

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void echelon$confirmHotbarThrow(Slot slot, int slotId, int button, ContainerInput input,
                                             CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (!EchelonClientSettings.protectHotbar || client.player == null
                || !PortalRegistry.instanceSlots().contains(client.player.level().dimension().identifier())) return;
        boolean hotbarSlot = slot != null && slot.container == client.player.getInventory()
                && slot.getContainerSlot() >= 0 && slot.getContainerSlot() <= 8;
        if (input == ContainerInput.PICKUP) {
            boolean carrying = !((AbstractContainerScreen<?>) (Object) this).getMenu().getCarried().isEmpty();
            if (slot == null && carrying && echelon$carriedFromHotbar) {
                client.player.sendOverlayMessage(Component.literal("Hotbar item protected. Press Q twice on its slot to drop."));
                callback.cancel();
                return;
            }
            if (!carrying) echelon$carriedFromHotbar = hotbarSlot && slot.hasItem();
            else if (slot != null) echelon$carriedFromHotbar = false;
        }
        if (slot == null && input == ContainerInput.THROW && echelon$carriedFromHotbar) {
            client.player.sendOverlayMessage(Component.literal("Hotbar item protected. Return it to a slot and press Q twice."));
            callback.cancel();
            return;
        }
        if (input != ContainerInput.THROW || slot == null || !slot.hasItem()) return;
        long now = System.currentTimeMillis();
        if (echelon$armedSlot == slotId && now <= echelon$armedUntil) {
            echelon$armedSlot = -1;
            return;
        }
        echelon$armedSlot = slotId;
        echelon$armedUntil = now + 2000L;
        client.player.sendOverlayMessage(Component.literal("Press Q again on this slot to drop it."));
        callback.cancel();
    }
}
