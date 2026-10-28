package ekuzo.crazyworldprogression.mixin;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A placed chest only joins a neighbour into a double chest when both have the same quality (ChestQuality). */
@Mixin(ChestBlock.class)
public abstract class ChestSingleMixin {
    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void cwp$single(BlockPlaceContext context, CallbackInfoReturnable<BlockState> info) {
        BlockState state = info.getReturnValue();
        if (state == null || !state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return;
        var partner = context.getLevel().getBlockEntity(ChestBlock.getConnectedBlockPos(context.getClickedPos(), state));
        if (ekuzo.crazyworldprogression.smithing.ChestQuality.placed(partner)
                != ekuzo.crazyworldprogression.smithing.ChestQuality.of(context.getItemInHand()))
            info.setReturnValue(state.setValue(ChestBlock.TYPE, ChestType.SINGLE));
    }
}
