package ekuzo.crazyworldprogression.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import ekuzo.crazyworldprogression.client.CwpSettingsScreen;
import ekuzo.crazyworldprogression.client.TitleSelectScreen;
import ekuzo.crazyworldprogression.client.WaypointScreen;
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

/** Adds the settings and Titles buttons as one row directly above Save and Quit / Disconnect. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenSettingsMixin {
    @Inject(method = "createPauseMenu", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/CommonComponents;disconnectButtonLabel(Z)Lnet/minecraft/network/chat/Component;"))
    private void cwp$addSettingsRow(CallbackInfo callback, @Local GridLayout.RowHelper helper) {
        Screen screen = (Screen) (Object) this;
        // Titles, parties and waypoints only while their parts are on (CwpModule).
        boolean titles = ekuzo.crazyworldprogression.config.CwpModule.TITLES.enabled();
        Button settings = Button.builder(ekuzo.crazyworldprogression.client.CwpClientSettings.menuTitle(),
                ignored -> Minecraft.getInstance().gui.setScreen(new CwpSettingsScreen(screen))).width(titles ? 98 : 204).build();
        if (titles) {
            helper.addChild(settings);
            helper.addChild(Button.builder(Component.translatable("menu.crazy-world-progression.titles"),
                    ignored -> Minecraft.getInstance().gui.setScreen(new TitleSelectScreen(screen))).width(98).build());
        } else {
            helper.addChild(settings, 2);
        }
        if (!ekuzo.crazyworldprogression.config.CwpModule.PORTALS.enabled()) return;
        helper.addChild(Button.builder(Component.translatable("menu.crazy-world-progression.party"),
                ignored -> ekuzo.crazyworldprogression.client.PartyScreen.requestOpen()).width(204).build(), 2);
        helper.addChild(Button.builder(Component.translatable("menu.crazy-world-progression.waypoint"),
                ignored -> Minecraft.getInstance().gui.setScreen(new WaypointScreen(screen))).width(204).build(), 2);
    }
}
