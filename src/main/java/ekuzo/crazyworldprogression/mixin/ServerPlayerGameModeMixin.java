package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.creative.CreativeAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every game mode change, however it happens, swaps creative's own inventory in or out. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerGameModeMixin {
    @Unique private GameType cwp$modeBefore;

    @Inject(method = "setGameMode", at = @At("HEAD"))
    private void cwp$rememberMode(GameType mode, CallbackInfoReturnable<Boolean> info) {
        cwp$modeBefore = ((ServerPlayer) (Object) this).gameMode();
    }

    @Inject(method = "setGameMode", at = @At("RETURN"))
    private void cwp$swapInventory(GameType mode, CallbackInfoReturnable<Boolean> info) {
        if (info.getReturnValueZ() && cwp$modeBefore != null)
            CreativeAccess.changed((ServerPlayer) (Object) this, cwp$modeBefore, mode);
        cwp$modeBefore = null;
    }
}
