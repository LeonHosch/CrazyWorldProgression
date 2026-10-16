package ekuzo.crazyworldprogression.mixin.client;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Creates the render type used to submit CWP's custom translucent portal geometry. */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
    // Wrap a custom render setup in the render type expected by Fabric geometry submission.
    @Invoker("create")
    static RenderType cwp$create(String name, RenderSetup setup) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
