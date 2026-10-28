package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.equipment.BloodPact;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The blood pact: heals other than life steal and halved food healing hurt the player instead. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHealMixin {
    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void cwp$bloodPact(float amount, CallbackInfo info) {
        if (!((Object) this instanceof ServerPlayer player) || amount <= 0 || !player.isAlive()
                || !BloodPact.reversesHeal(player)) return;
        info.cancel();
        player.hurtServer(player.level(), player.damageSources().magic(), amount);
    }
}
