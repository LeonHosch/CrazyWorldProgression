package ekuzo.crazyworldprogression.mixin;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Creates a template palette from blocks captured over several ticks. */
@Mixin(StructureTemplate.Palette.class)
public interface StructureTemplatePaletteInvoker {
    @Invoker("<init>")
    static StructureTemplate.Palette cwp$create(List<StructureTemplate.StructureBlockInfo> blocks) {
        throw new AssertionError();
    }
}
