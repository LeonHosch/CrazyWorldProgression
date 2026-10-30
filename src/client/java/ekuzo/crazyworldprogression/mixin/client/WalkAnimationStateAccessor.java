package ekuzo.crazyworldprogression.mixin.client;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a mini pet's mob copy take over the pet's walk cycle (MiniPetRenderer). */
@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {
    @Accessor("speedOld") float cwp$speedOld();
    @Accessor("speedOld") void cwp$setSpeedOld(float value);
    @Accessor("speed") float cwp$speed();
    @Accessor("speed") void cwp$setSpeed(float value);
    @Accessor("position") float cwp$position();
    @Accessor("position") void cwp$setPosition(float value);
}
