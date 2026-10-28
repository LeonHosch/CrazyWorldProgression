package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.RespawnFeedbackClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Instant respawn: the death screen is never opened; the player respawns right away instead. */
@Mixin(Gui.class)
public abstract class GuiInstantRespawnMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void crazyworldprogression$instantRespawn(Screen screen, CallbackInfo ci) {
        if (RespawnFeedbackClient.respawnInsteadOf(screen)) ci.cancel();
    }
}
