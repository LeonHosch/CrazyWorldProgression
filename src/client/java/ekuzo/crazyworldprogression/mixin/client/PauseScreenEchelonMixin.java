package ekuzo.crazyworldprogression.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import ekuzo.crazyworldprogression.client.EchelonSettingsScreen;
import ekuzo.crazyworldprogression.client.TitleSelectScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the Echelon Settings and Titles buttons as one row directly above Save and Quit / Disconnect. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenEchelonMixin {
    @Inject(method = "createPauseMenu", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/CommonComponents;disconnectButtonLabel(Z)Lnet/minecraft/network/chat/Component;"))
    private void echelon$addEchelonRow(CallbackInfo callback, @Local GridLayout.RowHelper helper) {
        Screen screen = (Screen) (Object) this;
        helper.addChild(Button.builder(Component.translatable("menu.crazy-world-progression.echelon_settings"),
                ignored -> Minecraft.getInstance().gui.setScreen(new EchelonSettingsScreen(screen))).width(98).build());
        helper.addChild(Button.builder(Component.translatable("menu.crazy-world-progression.titles"),
                ignored -> Minecraft.getInstance().gui.setScreen(new TitleSelectScreen(screen))).width(98).build());
    }
}
