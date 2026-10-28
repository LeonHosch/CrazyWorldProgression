package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.equipment.BloodPact;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Natural regeneration from food goes through the blood pact (halved instead of reversed). */
@Mixin(FoodData.class)
public abstract class FoodDataHealMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V"))
    private void cwp$foodHeal(ServerPlayer player, float amount) {
        BloodPact.foodHeal(player, amount);
    }
}
