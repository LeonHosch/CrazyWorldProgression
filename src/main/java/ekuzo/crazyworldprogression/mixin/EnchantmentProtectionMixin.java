package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ekuzo.crazyworldprogression.progression.equipment.ProtectionStats;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Protection from skill trees and sets (the protection stats beyond enchantments) joins vanilla's protection points. */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentProtectionMixin {
    @ModifyReturnValue(method = "getDamageProtection", at = @At("RETURN"))
    private static float cwp$protectionStat(float points, ServerLevel level, LivingEntity entity, DamageSource source) {
        if (!(entity instanceof ServerPlayer player) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return points;
        return points + ProtectionStats.extraPoints(player, source);
    }
}
