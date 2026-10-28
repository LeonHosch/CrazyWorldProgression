package ekuzo.crazyworldprogression.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Armor holds at most one enchantment: the anvil offers no result that would give a piece a second one. */
@Mixin(AnvilMenu.class)
public abstract class AnvilOneEnchantmentMixin {
    @Shadow @Final private DataSlot cost;

    @Inject(method = "createResult", at = @At("TAIL"))
    private void cwp$oneEnchantmentOnArmor(CallbackInfo info) {
        var result = ((ItemCombinerMenuAccessor) this).cwp$resultSlots();
        ItemStack stack = result.getItem(0);
        if (stack.isEmpty() || stack.getEnchantments().size() <= 1) return;
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return;
        result.setItem(0, ItemStack.EMPTY);
        cost.set(0);
    }
}
