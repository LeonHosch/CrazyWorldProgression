package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.fletching.ArrowType;
import ekuzo.crazyworldprogression.fletching.FletchedArrowItem;
import ekuzo.crazyworldprogression.fletching.Fletching;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hit effects of fletched arrows (ArrowType): bleeding, slowness, stun or a grapple on the target it hurt; explosive arrows
 * explode where they hit an entity or block (hurting entities, breaking no blocks) and are used up.
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowEffectsMixin {
    @Inject(method = "doPostHurtEffects", at = @At("TAIL"))
    private void cwp$fletchedEffects(LivingEntity target, CallbackInfo info) {
        ArrowType type = FletchedArrowItem.type((AbstractArrow) (Object) this);
        if (type == null) return;
        switch (type) {
            case BROADHEAD -> target.addEffect(new MobEffectInstance(Fletching.BLEEDING, ArrowType.BLEED_TICKS), (AbstractArrow) (Object) this);
            case BARBED -> target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ArrowType.BARBED_SLOW_TICKS, 1), (AbstractArrow) (Object) this);
            case DISRUPTION -> Fletching.stun(target, ArrowType.STUN_TICKS, (AbstractArrow) (Object) this);
            case GRAPPLING -> cwp$grapple(target);
            default -> { }
        }
    }

    /**
     * Puts a target without knockback resistance right in front of the shooter (the way they look), if it fits
     * there.
     */
    private void cwp$grapple(LivingEntity target) {
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (!(arrow.getOwner() instanceof LivingEntity shooter) || shooter == target
                || target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) > 0 || target.level() != shooter.level()) return;
        Vec3 look = shooter.getLookAngle().multiply(1, 0, 1);
        if (look.lengthSqr() < 1.0E-4) look = new Vec3(0, 0, 1);
        Vec3 spot = shooter.position().add(look.normalize().scale(ArrowType.GRAPPLE_DISTANCE + target.getBbWidth() / 2));
        if (!target.level().noCollision(target, target.getBoundingBox().move(spot.subtract(target.position())))) return;
        target.teleportTo(spot.x, spot.y, spot.z);
        target.setDeltaMovement(Vec3.ZERO);
        target.hurtMarked = true;
    }

    @Inject(method = "onHitEntity", at = @At("TAIL"))
    private void cwp$explodeOnEntity(EntityHitResult hit, CallbackInfo info) { cwp$explode(); }

    @Inject(method = "onHitBlock", at = @At("TAIL"))
    private void cwp$explodeOnBlock(BlockHitResult hit, CallbackInfo info) { cwp$explode(); }

    private void cwp$explode() {
        AbstractArrow arrow = (AbstractArrow) (Object) this;
        if (FletchedArrowItem.type(arrow) != ArrowType.EXPLOSIVE || !(arrow.level() instanceof ServerLevel level)) return;
        level.explode(arrow, null, Fletching.ARROW_BLAST, arrow.getX(), arrow.getY(), arrow.getZ(), ArrowType.EXPLOSION_RADIUS, false,
                Level.ExplosionInteraction.NONE);
        arrow.discard();
    }
}
