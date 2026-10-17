package ekuzo.crazyworldprogression.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Lets instance building place a template's blocks a slice at a time, and pocket saving fill one over ticks. */
@Mixin(StructureTemplate.class)
public interface StructureTemplateAccessor {
    @Accessor("palettes")
    List<StructureTemplate.Palette> cwp$palettes();

    @Accessor("size")
    void cwp$setSize(Vec3i size);

    /** Sorts a captured block into full blocks, other blocks and block entities, like Minecraft's own capture. */
    @Invoker("addToLists")
    static void cwp$addToLists(StructureTemplate.StructureBlockInfo info, List<StructureTemplate.StructureBlockInfo> full,
                               List<StructureTemplate.StructureBlockInfo> other,
                               List<StructureTemplate.StructureBlockInfo> special) {
        throw new AssertionError();
    }

    /** Joins the three lists in placement order, each sorted bottom-up. */
    @Invoker("buildInfoList")
    static List<StructureTemplate.StructureBlockInfo> cwp$buildInfoList(List<StructureTemplate.StructureBlockInfo> full,
                                                                       List<StructureTemplate.StructureBlockInfo> other,
                                                                       List<StructureTemplate.StructureBlockInfo> special) {
        throw new AssertionError();
    }
}
