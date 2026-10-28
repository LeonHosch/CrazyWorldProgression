package ekuzo.crazyworldprogression.mixin.client;

import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import ekuzo.crazyworldprogression.client.ChestQualityStateHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives chest render states a quality (ChestRendererQualityMixin). */
@Mixin(ChestRenderState.class)
public abstract class ChestRenderStateQualityMixin implements ChestQualityStateHolder {
    @Unique private int cwp$quality = -1;

    @Override public int cwp$quality() { return cwp$quality; }

    @Override public void cwp$setQuality(int quality) { cwp$quality = quality; }
}
