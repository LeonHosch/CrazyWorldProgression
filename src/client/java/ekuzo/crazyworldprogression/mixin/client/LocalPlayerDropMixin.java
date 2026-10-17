package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.EchelonClientSettings;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerDropMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void echelon$protectInstanceHotbar(boolean wholeStack, CallbackInfoReturnable<Boolean> result) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!EchelonClientSettings.protectHotbar
                || !PortalRegistry.instanceSlots().contains(player.level().dimension().identifier())
                || player.getInventory().getSelectedItem().isEmpty()) return;
        player.sendOverlayMessage(Component.literal("Hotbar protected in instances. Open inventory and press Q twice on the slot to drop."));
        result.setReturnValue(false);
    }
}
