package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.storage.ItemLocks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clicks that would drop, move into a container, or (fully locked) move a locked item are ignored (ItemLocks). */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuLockMixin {
    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void cwp$itemLocks(int slotId, int button, ContainerInput input, Player player, CallbackInfo info) {
        if (ItemLocks.blocks((AbstractContainerMenu) (Object) this, slotId, button, input, player)) info.cancel();
    }
}
