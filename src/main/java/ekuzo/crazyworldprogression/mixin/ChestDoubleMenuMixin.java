package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.smithing.ChestQuality;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A double chest's window has the rows of both halves, scrolling above six (ChestQuality.doubleMenu). */
@Mixin(targets = "net.minecraft.world.level.block.ChestBlock$2$1")
public abstract class ChestDoubleMenuMixin {
    @Redirect(method = "createMenu", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ChestMenu;sixRows(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/Container;)Lnet/minecraft/world/inventory/ChestMenu;"))
    private ChestMenu cwp$doubleMenu(int id, Inventory inventory, Container container) {
        return ChestQuality.doubleMenu(id, inventory, container);
    }
}
