package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.CharacterSheetClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The inventory with the character sheet open: the window and sheet are centred together (unless the recipe book is
 * open, which places the window itself), and a click on the sheet is not a click outside (which would drop the held
 * item).
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookScreenCharacterSheetMixin<T extends RecipeBookMenu> extends AbstractContainerScreen<T> {
    @Shadow @Final private RecipeBookComponent<?> recipeBookComponent;

    protected RecipeBookScreenCharacterSheetMixin(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Shadow protected abstract ScreenPosition getRecipeBookButtonPosition();

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void cwp$centreWithSheet(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (!((Object) this instanceof InventoryScreen) || recipeBookComponent.isVisible()) return;
        int both = imageWidth + CharacterSheetClient.GAP + CharacterSheetClient.WIDTH;
        int left = CharacterSheetClient.isOpen() && width >= both ? (width - both) / 2 : (width - imageWidth) / 2;
        if (left == leftPos) return;
        leftPos = left;
        ScreenPosition button = getRecipeBookButtonPosition();
        children().stream().filter(ImageButton.class::isInstance).map(ImageButton.class::cast)
                .filter(widget -> widget.getWidth() == 20 && widget.getHeight() == 18).findFirst()
                .ifPresent(widget -> widget.setPosition(button.x(), button.y()));
    }

    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void cwp$sheetIsInside(double mouseX, double mouseY, int left, int top, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof InventoryScreen screen && CharacterSheetClient.inside(screen, mouseX, mouseY)) cir.setReturnValue(false);
    }
}
