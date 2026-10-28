package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.skills.SkillXp;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers ores and crop fruits a player places, so breaking them again gives no skill XP. */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacedMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void cwp$rememberPlaced(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> info) {
        if (!info.getReturnValue().consumesAction() || !(context.getLevel() instanceof ServerLevel level)
                || context.getPlayer() == null) return;
        SkillXp.placed(level, context.getClickedPos(), level.getBlockState(context.getClickedPos()));
    }
}
