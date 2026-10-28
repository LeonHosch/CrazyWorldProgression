package ekuzo.crazyworldprogression.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Plain container slots follow their container's own rules (canPlaceItem), so a filtered chest refuses other items
 * in its window too (ChestFilters).
 */
@Mixin(Slot.class)
public abstract class SlotContainerRuleMixin {
    @Shadow @Final public Container container;

    @Shadow public abstract int getContainerSlot();

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void cwp$containerRule(ItemStack stack, CallbackInfoReturnable<Boolean> info) {
        if (!container.canPlaceItem(getContainerSlot(), stack)) info.setReturnValue(false);
    }
}
