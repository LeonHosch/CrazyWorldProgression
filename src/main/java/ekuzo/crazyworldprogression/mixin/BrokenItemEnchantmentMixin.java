package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.smithing.ItemDurability;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A broken item's enchantments do nothing until it is resmithed. */
@Mixin(EnchantmentHelper.class)
public abstract class BrokenItemEnchantmentMixin {
    @Inject(method = "runIterationOnItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/enchantment/EnchantmentHelper$EnchantmentVisitor;)V",
            at = @At("HEAD"), cancellable = true)
    private static void cwp$skipBroken(ItemStack stack, EnchantmentHelper.EnchantmentVisitor visitor, CallbackInfo info) {
        if (ItemDurability.isBrokenDown(stack)) info.cancel();
    }

    @Inject(method = "runIterationOnItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/enchantment/EnchantmentHelper$EnchantmentInSlotVisitor;)V",
            at = @At("HEAD"), cancellable = true)
    private static void cwp$skipBrokenInSlot(ItemStack stack, EquipmentSlot slot, LivingEntity entity,
                                             EnchantmentHelper.EnchantmentInSlotVisitor visitor, CallbackInfo info) {
        if (ItemDurability.isBrokenDown(stack)) info.cancel();
    }
}
