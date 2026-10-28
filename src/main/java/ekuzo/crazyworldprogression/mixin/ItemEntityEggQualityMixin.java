package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.farming.ProduceQuality;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An egg a player picks up gets its quality, or cracks (ProduceQuality). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityEggQualityMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void cwp$gradeEgg(Player player, CallbackInfo info) {
        if (player instanceof ServerPlayer serverPlayer && !ProduceQuality.pickUpEgg(serverPlayer, (ItemEntity) (Object) this)) info.cancel();
    }
}
