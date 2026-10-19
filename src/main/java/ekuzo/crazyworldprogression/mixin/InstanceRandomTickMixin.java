package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.portals.PortalEnvironmentService;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Instances whose portal turns random ticks off tick their chunks with a random tick speed of 0. */
@Mixin(ServerLevel.class)
abstract class InstanceRandomTickMixin {
    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cwp$randomTickSpeed(int speed) {
        return speed > 0 && PortalEnvironmentService.randomTicksOff((ServerLevel) (Object) this) ? 0 : speed;
    }
}
