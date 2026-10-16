package ekuzo.crazyworldprogression.mobforge.mixin;

import ekuzo.crazyworldprogression.mobforge.ai.MobAiRegistry;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
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
}
