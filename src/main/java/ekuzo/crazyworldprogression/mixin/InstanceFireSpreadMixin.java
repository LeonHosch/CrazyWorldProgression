package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.portals.PortalEnvironmentService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Instances whose portal turns fire spread off: fire neither spreads nor burns blocks, like doFireTick false. */
@Mixin(FireBlock.class)
abstract class InstanceFireSpreadMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cwp$noFireSpread(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo info) {
        if (PortalEnvironmentService.fireSpreadOff(level)) info.cancel();
    }
}
