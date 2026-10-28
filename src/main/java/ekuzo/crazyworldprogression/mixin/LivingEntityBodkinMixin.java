package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ekuzo.crazyworldprogression.fletching.ArrowType;
import ekuzo.crazyworldprogression.fletching.FletchedArrowItem;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bodkin arrows ignore part of the target's armor and toughness (ArrowType.BODKIN_ARMOR_IGNORED). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBodkinMixin {
    @WrapOperation(method = "getDamageAfterArmorAbsorb", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/CombatRules;getDamageAfterAbsorb(Lnet/minecraft/world/entity/LivingEntity;FLnet/minecraft/world/damagesource/DamageSource;FF)F"))
    private float cwp$bodkin(LivingEntity entity, float damage, DamageSource source, float armor, float toughness,
                             Operation<Float> original) {
        if (source.getDirectEntity() instanceof AbstractArrow arrow && FletchedArrowItem.type(arrow) == ArrowType.BODKIN) {
            float kept = 1 - ArrowType.BODKIN_ARMOR_IGNORED;
            return original.call(entity, damage, source, armor * kept, toughness * kept);
        }
        return original.call(entity, damage, source, armor, toughness);
    }
}
