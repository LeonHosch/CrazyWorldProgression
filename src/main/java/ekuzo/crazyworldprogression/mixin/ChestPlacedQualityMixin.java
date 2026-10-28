package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.smithing.ChestQuality;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A chest placed from an item takes the item's quality (Miserable for a crafted chest), ChestQuality. */
@Mixin(BlockEntity.class)
public abstract class ChestPlacedQualityMixin {
    /** And back onto the chest item when it is broken (the chest loot tables copy the quality component). */
    @Inject(method = "collectImplicitComponents", at = @At("TAIL"))
    private void cwp$qualityToItem(net.minecraft.core.component.DataComponentMap.Builder builder, CallbackInfo info) {
        if (!((Object) this instanceof ChestBlockEntity chest)) return;
        Integer quality = chest.getAttached(ChestQuality.QUALITY);
        if (quality != null) builder.set(ekuzo.crazyworldprogression.farming.ProduceQuality.QUALITY, quality);
    }

    @Inject(method = "applyComponentsFromItemStack", at = @At("TAIL"))
    private void cwp$placedQuality(ItemStack stack, CallbackInfo info) {
        if ((Object) this instanceof ChestBlockEntity chest) chest.setAttached(ChestQuality.QUALITY, ChestQuality.of(stack));
    }
}
