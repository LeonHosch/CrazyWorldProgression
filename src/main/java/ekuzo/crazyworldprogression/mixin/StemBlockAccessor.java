package ekuzo.crazyworldprogression.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StemBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The fruit and attached stem of a stem crop (melon, pumpkin), for farming levels. */
@Mixin(StemBlock.class)
public interface StemBlockAccessor {
    @Accessor("fruit")
    ResourceKey<Block> cwp$fruit();

    @Accessor("attachedStem")
    ResourceKey<Block> cwp$attachedStem();
}
