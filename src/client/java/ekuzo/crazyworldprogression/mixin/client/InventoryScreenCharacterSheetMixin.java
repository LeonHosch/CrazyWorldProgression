package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.CharacterSheetClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws the character sheet to the right of the inventory; it takes the place of the potion effect list while open. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenCharacterSheetMixin extends AbstractContainerScreen<InventoryMenu> {
    protected InventoryScreenCharacterSheetMixin(InventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void cwp$characterSheet(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo info) {
        CharacterSheetClient.draw((InventoryScreen) (Object) this, graphics, font);
    }

    @Inject(method = "showsActiveEffects", at = @At("HEAD"), cancellable = true)
    private void cwp$noEffectsUnderSheet(CallbackInfoReturnable<Boolean> cir) {
        if (CharacterSheetClient.isOpen()) cir.setReturnValue(false);
    }
}
