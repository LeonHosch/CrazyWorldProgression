package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Phantoms don't come for players standing in a bonus area (they are skipped like spectators). */
@Mixin(PhantomSpawner.class)
public abstract class BonusAreaPhantomMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
    private boolean crazyworldprogression$skipPlayersInBonusAreas(boolean spectator, @com.llamalad7.mixinextras.sugar.Local ServerPlayer player) {
        return spectator || BonusAreas.contains(player);
    }
}
