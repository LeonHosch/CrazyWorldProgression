package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.storage.ChestFilters;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** A filtered chest only takes items of its categories, from hoppers too (ChestFilters). */
@Mixin(ChestBlockEntity.class)
public abstract class ChestFilterMixin implements Container {
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return ChestFilters.allows((ChestBlockEntity) (Object) this, stack);
    }
}
