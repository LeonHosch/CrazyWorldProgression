package ekuzo.crazyworldprogression.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets piercing arrows set their pierce level (FletchedArrowItem). */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
    @Invoker("setPierceLevel")
    void cwp$setPierceLevel(byte level);
}
