package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.equipment.Hubris;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player's projectile hitting any entity (mobs, armor stands, boats, ...) keeps Hubris in combat. */
@Mixin(Projectile.class)
public abstract class ProjectileHitMixin {
    @Inject(method = "onHit", at = @At("HEAD"))
    private void cwp$hubrisHit(HitResult result, CallbackInfo info) {
        // Hitting yourself counts too (it still resets the stacks if it hurts).
        if (result instanceof EntityHitResult && ((Projectile) (Object) this).getOwner() instanceof ServerPlayer owner)
            Hubris.markHit(owner);
    }
}
