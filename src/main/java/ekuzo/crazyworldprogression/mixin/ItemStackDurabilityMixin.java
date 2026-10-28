package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.smithing.ItemDurability;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Iron-and-better gear stops at its last durability point and becomes broken instead of disappearing. */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {
    @Inject(method = "applyDamage", at = @At("HEAD"), cancellable = true)
    private void cwp$breakDownInsteadOfBreaking(int newDamage, ServerPlayer player, Consumer<Item> onBreak,
                                               CallbackInfo info) {
        ItemStack self = (ItemStack) (Object) this;
        if (!ItemDurability.isSturdy(self)) return;
        int last = self.getMaxDamage() - 1;
        if (newDamage < last) return;
        info.cancel();
        if (self.getDamageValue() != last) self.setDamageValue(last);
        ItemDurability.breakDown(self);
    }

    /** Any repair that brings a broken item back above its last durability point makes it work again. */
    @Inject(method = "setDamageValue", at = @At("TAIL"))
    private void cwp$repaired(int damage, CallbackInfo info) {
        ItemStack self = (ItemStack) (Object) this;
        if (self.getDamageValue() < self.getMaxDamage() - 1 && ItemDurability.isBrokenDown(self))
            ItemDurability.restore(self);
    }
}
