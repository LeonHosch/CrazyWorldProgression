package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiConsumer;

/** Chickens never lay eggs on the ground; players collect them by hand (AnimalProduce). */
@Mixin(Chicken.class)
public abstract class ChickenEggMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/animal/chicken/Chicken;dropFromGiftLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/resources/ResourceKey;Ljava/util/function/BiConsumer;)Z"))
    private boolean cwp$noGroundEggs(Chicken chicken, ServerLevel level, ResourceKey<LootTable> table,
                                     BiConsumer<ServerLevel, ItemStack> output, Operation<Boolean> original) {
        return false;
    }
}
