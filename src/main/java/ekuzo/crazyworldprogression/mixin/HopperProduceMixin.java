package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.farming.ProduceQuality;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Produce a hopper (or hopper minecart) picks up without a quality, such as eggs, becomes Miserable. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperProduceMixin {
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z", at = @At("HEAD"))
    private static void cwp$miserableProduce(Container container, ItemEntity entity, CallbackInfoReturnable<Boolean> info) {
        if (ProduceQuality.get(entity.getItem()) == null && ProduceQuality.gradable(entity.getItem()))
            entity.setItem(ProduceQuality.miserable(entity.getItem().copy()));
    }
}
