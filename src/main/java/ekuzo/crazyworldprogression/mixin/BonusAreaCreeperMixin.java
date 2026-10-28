package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** A creeper exploding inside a bonus area still hurts, but breaks no blocks. */
@Mixin(Creeper.class)
public abstract class BonusAreaCreeperMixin {
    @ModifyArg(method = "explodeCreeper", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)V"),
            index = 5)
    private Level.ExplosionInteraction crazyworldprogression$noBlockDamageInBonusAreas(Level.ExplosionInteraction interaction) {
        Creeper creeper = (Creeper) (Object) this;
        return creeper.level() instanceof ServerLevel level && BonusAreas.contains(level, creeper.getX(), creeper.getZ())
                ? Level.ExplosionInteraction.NONE : interaction;
    }
}
