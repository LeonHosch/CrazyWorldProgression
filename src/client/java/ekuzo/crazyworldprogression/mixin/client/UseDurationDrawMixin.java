package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.progression.equipment.DrawSpeed;
import net.minecraft.client.renderer.item.properties.numeric.UseDuration;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draw speed: the bow pull animation keeps pace with the faster draw. */
@Mixin(UseDuration.class)
public abstract class UseDurationDrawMixin {
    @Inject(method = "useDuration", at = @At("RETURN"), cancellable = true)
    private static void cwp$drawSpeed(ItemStack stack, LivingEntity entity, CallbackInfoReturnable<Integer> info) {
        if (!(stack.getItem() instanceof BowItem)) return;
        float multiplier = DrawSpeed.multiplier(entity);
        if (multiplier != 1.0F) info.setReturnValue(Math.round(info.getReturnValueI() * multiplier));
    }
}
