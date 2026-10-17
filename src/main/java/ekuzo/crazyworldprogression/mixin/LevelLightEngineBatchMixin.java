package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.portals.LightBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets fast block writing queue the light checks of a whole chunk as one light-thread task instead of one task
 * per block. The server's threaded light engine runs this base method on the light thread.
 */
@Mixin(LevelLightEngine.class)
abstract class LevelLightEngineBatchMixin {
    @Shadow @Final private LightEngine<?, ?> blockEngine;
    @Shadow @Final private LightEngine<?, ?> skyEngine;

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void cwp$checkBatch(BlockPos pos, CallbackInfo ci) {
        if (!(pos instanceof LightBatch batch)) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long packed : batch.positions()) {
            cursor.set(packed);
            if (blockEngine != null) blockEngine.checkBlock(cursor);
            if (skyEngine != null) skyEngine.checkBlock(cursor);
        }
        ci.cancel();
    }
}
