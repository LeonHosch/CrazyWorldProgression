package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.style.PanelStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Buttons in styled windows are drawn by the window style (PanelStyle), when it draws buttons. */
@Mixin(Button.Plain.class)
public abstract class ButtonPanelStyleMixin {
    @Inject(method = "extractContents", at = @At("HEAD"), cancellable = true)
    private void cwp$styledButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo info) {
        AbstractWidget button = (AbstractWidget) (Object) this;
        if (PanelStyle.isStyled(button) && PanelStyle.get().button(graphics, button, button.isHoveredOrFocused())) info.cancel();
    }
}
