package ekuzo.crazyworldprogression.mixin.client;

import net.minecraft.world.entity.animal.fish.TropicalFish;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets a mini pet's tropical fish copy get colours (MiniPetRenderer); a new fish is plain grey and white. */
@Mixin(TropicalFish.class)
public interface TropicalFishVariantInvoker {
    @Invoker("setPackedVariant")
    void cwp$setPackedVariant(int variant);
}
