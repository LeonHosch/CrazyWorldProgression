package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.divine.DivineProtection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dead player's items with Divine Protection are kept for the respawn instead of dropping. */
@Mixin(Inventory.class)
public abstract class InventoryDivineProtectionMixin {
    @Inject(method = "dropAll", at = @At("HEAD"))
    private void crazyworldprogression$keepProtected(CallbackInfo ci) {
        if (((Inventory) (Object) this).player instanceof ServerPlayer player && player.isDeadOrDying())
            DivineProtection.preserveForRespawn(player);
    }
}
