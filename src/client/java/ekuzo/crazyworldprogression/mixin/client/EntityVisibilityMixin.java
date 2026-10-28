package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.PortalPlayerVisibility;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityVisibilityMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void cwp$hidePlayers(Entity entity, Frustum frustum, double x, double y, double z,
                                     CallbackInfoReturnable<Boolean> result) {
        if (entity instanceof Player player && PortalPlayerVisibility.hide(player)) result.setReturnValue(false);
    }
}
