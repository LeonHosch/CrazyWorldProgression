package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.social.ChestLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hoppers can't pull out of chests their owner may not open (ChestLocks.hopperMayPull). */
@Mixin(HopperBlockEntity.class)
public abstract class HopperLockMixin {
    @Inject(method = "getSourceContainer", at = @At("RETURN"), cancellable = true)
    private static void cwp$lockedSource(Level level, Hopper hopper, BlockPos pos, BlockState state,
                                         CallbackInfoReturnable<Container> info) {
        if (info.getReturnValue() != null && !ChestLocks.hopperMayPull(level, hopper, pos)) info.setReturnValue(null);
    }
}
