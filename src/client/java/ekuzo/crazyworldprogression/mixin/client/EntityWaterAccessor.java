package ekuzo.crazyworldprogression.mixin.client;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a mini pet's fish copy count as under water, so it swims instead of flopping (MiniPetRenderer). */
@Mixin(Entity.class)
public interface EntityWaterAccessor {
    @Accessor("wasTouchingWater")
    void cwp$setTouchingWater(boolean touching);
}
