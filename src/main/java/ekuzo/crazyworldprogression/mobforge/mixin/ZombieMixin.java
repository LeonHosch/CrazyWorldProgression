package ekuzo.crazyworldprogression.mobforge.mixin;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Zombie.class)
public abstract class ZombieMixin {
	@Inject(method = "isSunSensitive", at = @At("HEAD"), cancellable = true)
	private void mobforge$daylightBehavior(CallbackInfoReturnable<Boolean> info) {
		MobForgeEntityData.burnsInDaylight((Entity) (Object) this).ifPresent(info::setReturnValue);
	}
}
