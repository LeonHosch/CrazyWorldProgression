package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.farming.ProduceQuality;
import ekuzo.crazyworldprogression.smithing.ChestQuality;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chests by quality (ChestQuality): their item list always has room for six rows, but only the quality's rows are
 * used, and the window has as many rows (ChestPlacedQualityMixin puts the quality back onto the item).
 */
@Mixin(ChestBlockEntity.class)
public abstract class ChestQualityMixin {
    @Shadow private NonNullList<ItemStack> items;

    @Inject(method = "<init>(Lnet/minecraft/world/level/block/entity/BlockEntityType;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("TAIL"))
    private void cwp$capacity(BlockEntityType<?> type, BlockPos pos, BlockState state, CallbackInfo info) {
        items = NonNullList.withSize(ChestQuality.CAPACITY, ItemStack.EMPTY);
    }

    @ModifyArg(method = "loadAdditional", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/NonNullList;withSize(ILjava/lang/Object;)Lnet/minecraft/core/NonNullList;"), index = 0)
    private int cwp$loadCapacity(int size) { return ChestQuality.CAPACITY; }

    @Inject(method = "getContainerSize", at = @At("HEAD"), cancellable = true)
    private void cwp$size(CallbackInfoReturnable<Integer> info) {
        info.setReturnValue(ChestQuality.slots((ChestBlockEntity) (Object) this));
    }

    @Inject(method = "createMenu", at = @At("HEAD"), cancellable = true)
    private void cwp$menu(int id, Inventory inventory, CallbackInfoReturnable<AbstractContainerMenu> info) {
        int rows = ChestQuality.slots((ChestBlockEntity) (Object) this) / 9;
        info.setReturnValue(new ChestMenu(ChestQuality.menuType(rows), id, inventory, (Container) this, rows));
    }
}
