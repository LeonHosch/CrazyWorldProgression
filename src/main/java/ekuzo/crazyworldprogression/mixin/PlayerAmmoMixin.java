package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.ammo.AmmoPouch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bows and crossbows shoot from the selected ammo slot first (AmmoPouch). */
@Mixin(Player.class)
public abstract class PlayerAmmoMixin {
    @Inject(method = "getProjectile", at = @At("HEAD"), cancellable = true)
    private void cwp$ammoSlots(ItemStack weapon, CallbackInfoReturnable<ItemStack> info) {
        ItemStack ammo = AmmoPouch.projectileFor((Player) (Object) this, weapon);
        if (ammo != null) info.setReturnValue(ammo);
    }
}
