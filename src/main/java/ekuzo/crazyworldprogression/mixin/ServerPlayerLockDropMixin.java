package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.storage.ItemLocks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Locked items can't be dropped with the drop key (ItemLocks). */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerLockDropMixin {
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void cwp$noLockedDrop(boolean fullStack, CallbackInfo info) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (ItemLocks.locked(player.getInventory().getSelectedItem())) {
            info.cancel();
            player.inventoryMenu.sendAllDataToRemote();
        }
    }
}
