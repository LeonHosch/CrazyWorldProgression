package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.collectibles.MountService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player cannot mount anything for a while after being hit, or while stunned (MountService). */
@Mixin(Entity.class)
public abstract class EntityMountCooldownMixin {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void cwp$mountCooldown(Entity vehicle, boolean force, boolean sendEvent, CallbackInfoReturnable<Boolean> info) {
        if ((Object) this instanceof ServerPlayer player && !MountService.canMount(player, true)) info.setReturnValue(false);
    }
}
