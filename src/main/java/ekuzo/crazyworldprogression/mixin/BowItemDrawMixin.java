package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ekuzo.crazyworldprogression.progression.equipment.DrawSpeed;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Draw speed: a bow released after N ticks shoots as if it had been drawn N times the multiplier. */
@Mixin(BowItem.class)
public abstract class BowItemDrawMixin {
    @WrapOperation(method = "releaseUsing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/BowItem;getPowerForTime(I)F"))
    private float cwp$drawSpeed(int ticks, Operation<Float> original, @Local(argsOnly = true) LivingEntity entity) {
        return original.call(Math.round(ticks * DrawSpeed.multiplier(entity)));
    }
}
