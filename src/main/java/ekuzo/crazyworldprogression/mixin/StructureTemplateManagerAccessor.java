package ekuzo.crazyworldprogression.mixin;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lets pocket templates be loaded and saved off the server thread and then put into Minecraft's cache. */
@Mixin(StructureTemplateManager.class)
public interface StructureTemplateManagerAccessor {
    @Accessor("structureRepository")
    Map<Identifier, Optional<StructureTemplate>> cwp$repository();

    @Accessor("sources")
    List<TemplateSource> cwp$sources();
}
