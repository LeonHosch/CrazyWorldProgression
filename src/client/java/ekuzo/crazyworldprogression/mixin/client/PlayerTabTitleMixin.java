package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.TitleClient;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabTitleMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void echelon$colorTabName(PlayerInfo info, CallbackInfoReturnable<Component> result) {
        var display = TitleClient.get(info.getProfile().id());
        if (display != null) result.setReturnValue(Component.literal(result.getReturnValue().getString())
                .withColor(TitleClient.color(display)));
    }
}
