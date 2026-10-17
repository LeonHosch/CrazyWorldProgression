package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.portals.PocketEditorService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remember every chunk modified in a private pocket build dimension. */
@Mixin(Level.class)
public abstract class PocketBuildTrackingMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void crazyworldprogression$trackPocketBuild(BlockPos pos, BlockState state, int flags,
                                                         int recursionLeft, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValue() && (Object) this instanceof ServerLevel level) {
            PocketEditorService.noteBlockChange(level, pos);
        }
    }
}
