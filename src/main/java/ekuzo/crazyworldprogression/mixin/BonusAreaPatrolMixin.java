package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pillager patrols don't spawn inside a bonus area. */
@Mixin(PatrolSpawner.class)
public abstract class BonusAreaPatrolMixin {
    @Inject(method = "spawnPatrolMember", at = @At("HEAD"), cancellable = true)
    private void crazyworldprogression$noPatrolsInBonusAreas(ServerLevel level, BlockPos pos, RandomSource random, boolean leader,
                                                               CallbackInfoReturnable<Boolean> cir) {
        if (BonusAreas.contains(level, pos.getX() + 0.5, pos.getZ() + 0.5)) cir.setReturnValue(false);
    }
}
