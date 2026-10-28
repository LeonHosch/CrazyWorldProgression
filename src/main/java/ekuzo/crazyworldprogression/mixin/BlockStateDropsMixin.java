package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.farming.FarmingLevels;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Crossbred crops only drop when a player breaks them. Every block drop (player, water, pistons, explosions, a
 * removed support block) goes through here; without a player as the breaking entity the crop just disappears.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateDropsMixin {
    @Inject(method = "getDrops", at = @At("HEAD"), cancellable = true)
    private void cwp$cropDrops(LootParams.Builder builder, CallbackInfoReturnable<List<ItemStack>> info) {
        BlockState state = (BlockState) (Object) this;
        Entity breaker = builder.getOptionalParameter(LootContextParams.THIS_ENTITY);
        if (!FarmingLevels.dropsFor(builder.getLevel().getServer(), state.getBlock(), breaker)) info.setReturnValue(List.of());
    }

    /** Produce from blocks no player broke (water, pistons, explosions, mobs) is always Miserable; ore a player broke may double. */
    @Inject(method = "getDrops", at = @At("RETURN"), cancellable = true)
    private void cwp$automatedProduce(LootParams.Builder builder, CallbackInfoReturnable<List<ItemStack>> info) {
        if (builder.getOptionalParameter(LootContextParams.THIS_ENTITY) instanceof net.minecraft.world.entity.player.Player player) {
            // The Mining level's chance of double ore (SkillXp.miningBonus).
            var origin = builder.getOptionalParameter(LootContextParams.ORIGIN);
            if (origin != null) info.setReturnValue(ekuzo.crazyworldprogression.skills.SkillXp.miningBonus(builder.getLevel(),
                    net.minecraft.core.BlockPos.containing(origin), (BlockState) (Object) this, player,
                    builder.getOptionalParameter(LootContextParams.TOOL) instanceof ItemStack tool ? tool : null,
                    info.getReturnValue()));
            return;
        }
        for (ItemStack stack : info.getReturnValue()) ekuzo.crazyworldprogression.farming.ProduceQuality.miserable(stack);
    }
}
