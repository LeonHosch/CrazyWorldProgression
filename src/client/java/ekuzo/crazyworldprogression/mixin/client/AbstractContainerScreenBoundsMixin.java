package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.CharacterSheetClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Lets the character sheet find where a container window sits. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenBoundsMixin implements CharacterSheetClient.ContainerBounds {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow @Final protected int imageWidth;
    @Shadow @Final protected int imageHeight;

    @Override public int cwp$leftPos() { return leftPos; }
    @Override public int cwp$topPos() { return topPos; }
    @Override public int cwp$imageWidth() { return imageWidth; }
    @Override public int cwp$imageHeight() { return imageHeight; }
}
