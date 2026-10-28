package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.equipment.DrawSpeed;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draw speed: crossbows load in their charge time divided by the multiplier (loading, sounds and pull animation). */
@Mixin(CrossbowItem.class)
public abstract class CrossbowItemDrawMixin {
    @Inject(method = "getChargeDuration", at = @At("RETURN"), cancellable = true)
    private static void cwp$drawSpeed(ItemStack stack, LivingEntity entity, CallbackInfoReturnable<Integer> info) {
        float multiplier = DrawSpeed.multiplier(entity);
        if (multiplier != 1.0F) info.setReturnValue(Math.max(1, Math.round(info.getReturnValueI() / multiplier)));
    }
}
