package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillMultiplierService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Block.class)
public abstract class BlockMixin {
    // Multiply loot from vanilla crop-tagged blocks when a server player is present in the block-loot context.
    @Inject(
            method = "getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemInstance;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void crazyworldprogression$multiplyFarmingDrops(
            BlockState state, ServerLevel level, BlockPos pos, BlockEntity blockEntity,
            Entity entity, ItemInstance tool, CallbackInfoReturnable<List<ItemStack>> callback
    ) {
        if (!(entity instanceof ServerPlayer player)) return;
        if (state.is(BlockTags.CROPS)) callback.setReturnValue(SkillMultiplierService.multiplyDrops(
                player, callback.getReturnValue(), SkillMultiplierService.farmingMultiplier(player)));
        // Produce quality (or a ruined harvest) for fully grown crops.
        if (ekuzo.crazyworldprogression.skills.SkillXp.grownProduce(pos, state))
            callback.setReturnValue(ekuzo.crazyworldprogression.farming.ProduceQuality.harvest(
                    player, callback.getReturnValue(), ekuzo.crazyworldprogression.farming.ProduceQuality.CROPS,
                    ekuzo.crazyworldprogression.farming.FarmingLevels.requiredLevel(level.getServer(), state.getBlock())));
    }
}
