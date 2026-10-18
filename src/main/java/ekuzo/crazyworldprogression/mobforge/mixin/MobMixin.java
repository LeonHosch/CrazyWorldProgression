package ekuzo.crazyworldprogression.mobforge.mixin;

import ekuzo.crazyworldprogression.mobforge.ai.MobAiRegistry;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void mobforge$tick(CallbackInfo info) { MobAiRegistry.tick((Mob) (Object) this); }

	@Inject(method = "dropFromLootTable", at = @At("HEAD"), cancellable = true)
	private void mobforge$suppressLoot(ServerLevel level, DamageSource source, boolean causedByPlayer, CallbackInfo info) {
		if (MobForgeEntityData.suppressVanillaLoot((Mob) (Object) this)) info.cancel();
	}

	@Inject(method = "getBaseExperienceReward", at = @At("HEAD"), cancellable = true)
	private void mobforge$experience(ServerLevel level, CallbackInfoReturnable<Integer> info) {
		MobForgeEntityData.xp((Mob) (Object) this).ifPresent(info::setReturnValue);
	}

	/**
	 * Minecraft burns every mob whose type is in the burn_in_daylight tag. A template with burns_in_daylight off
	 * switches that off for its mobs; it never makes other mobs burn.
	 */
	@ModifyExpressionValue(method = "aiStep", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Mob;is(Lnet/minecraft/tags/TagKey;)Z"))
	private boolean mobforge$daylightBurning(boolean burns) {
		return burns && MobForgeEntityData.burnsInDaylight((Mob) (Object) this).orElse(true);
	}
}
