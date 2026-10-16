package ekuzo.crazyworldprogression.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Minecraft's pipeline registry for CWP's depth-writing translucent portal surface. */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    // Register a custom pipeline alongside Minecraft's world-rendering pipelines.
    @Invoker("register")
    static RenderPipeline cwp$register(RenderPipeline pipeline) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
