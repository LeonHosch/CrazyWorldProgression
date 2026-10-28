package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.social.ChestLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player placed a block: chests and barrels get their lock, hoppers their owner (ChestLocks). */
@Mixin(Block.class)
public abstract class BlockPlacedLockMixin {
    @Inject(method = "setPlacedBy", at = @At("TAIL"))
    private void cwp$lockPlaced(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack, CallbackInfo info) {
        ChestLocks.placed(level, pos, placer);
    }
}
