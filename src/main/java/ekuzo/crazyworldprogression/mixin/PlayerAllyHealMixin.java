package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import ekuzo.crazyworldprogression.progression.equipment.AllyHealing;
import ekuzo.crazyworldprogression.progression.equipment.DamageTaken;
import ekuzo.crazyworldprogression.progression.equipment.Hubris;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Damage after armor and protection: ally healing turns it into a heal (see AllyHealing), then the damage taken stat
 * is multiplied in last, and any damage that remains resets Hubris.
 */
@Mixin(Player.class)
public abstract class PlayerAllyHealMixin {
    @ModifyExpressionValue(method = "actuallyHurt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getDamageAfterMagicAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"))
    private float cwp$allyHeal(float damage, ServerLevel level, DamageSource source, float amount) {
        if (!((Object) this instanceof ServerPlayer player)) return damage;
        float taken = AllyHealing.apply(player, source, damage);
        if (taken <= 0) return taken;
        taken *= DamageTaken.multiplier(player);
        Hubris.onDamaged(player);
        return taken;
    }
}
