package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.WorkshopHelperClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Workshop move mode: left click selects a helper and right click deselects, instead of breaking or using. */
@Mixin(Minecraft.class)
public abstract class WorkshopMoveClickMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void crazyworldprogression$selectHelper(CallbackInfoReturnable<Boolean> cir) {
        if (!WorkshopHelperClient.moving()) return;
        WorkshopHelperClient.select();
        cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void crazyworldprogression$keepBlocks(boolean attacking, CallbackInfo ci) {
        if (WorkshopHelperClient.moving()) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void crazyworldprogression$deselectHelper(CallbackInfo ci) {
        if (!WorkshopHelperClient.moving()) return;
        WorkshopHelperClient.deselect();
        ci.cancel();
    }
}
