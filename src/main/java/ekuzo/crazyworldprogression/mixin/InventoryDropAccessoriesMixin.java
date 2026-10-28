package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.accessory.Accessories;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Death without keep-inventory (and raid kick-outs) drop the worn accessories with the inventory. */
@Mixin(Inventory.class)
public abstract class InventoryDropAccessoriesMixin {
    @Inject(method = "dropAll", at = @At("TAIL"))
    private void cwp$dropAccessories(CallbackInfo info) {
        if (((Inventory) (Object) this).player instanceof ServerPlayer player) {
            Accessories.dropAll(player);
            ekuzo.crazyworldprogression.accessory.BuffFood.dropAll(player);
            ekuzo.crazyworldprogression.ammo.AmmoPouch.dropAll(player);
        }
    }
}
