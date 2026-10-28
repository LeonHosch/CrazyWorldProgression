package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ekuzo.crazyworldprogression.loot.FishingLootService;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep vanilla hook movement, criteria, XP, and item spawning while replacing the rolled catch. */
@Mixin(FishingHook.class)
public abstract class FishingHookLootMixin {
    @WrapOperation(method = "retrieve", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<ItemStack> cwp$fishingCatch(LootTable table, LootParams params,
            Operation<ObjectArrayList<ItemStack>> original) {
        FishingHook hook = (FishingHook) (Object) this;
        if (!(hook.level() instanceof ServerLevel level) || !(hook.getPlayerOwner() instanceof ServerPlayer player))
            return original.call(table, params);
        BlockPos pos = hook.blockPosition();
        FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty()) fluid = level.getFluidState(pos.below());
        ObjectArrayList<ItemStack> custom = FishingLootService.catchAt(level, player, pos, fluid);
        if (custom != null) return custom;
        // Vanilla catches: fish get a quality too (FishingLootService grades custom catches itself).
        ObjectArrayList<ItemStack> caught = new ObjectArrayList<>(ekuzo.crazyworldprogression.farming.ProduceQuality.harvest(
                player, original.call(table, params), ekuzo.crazyworldprogression.farming.ProduceQuality.FISH));
        FishingLootService.awardXp(player, caught, java.util.Map.of());
        return caught;
    }
}
