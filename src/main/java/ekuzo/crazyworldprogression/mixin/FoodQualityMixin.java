package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ekuzo.crazyworldprogression.farming.ProduceQuality;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Food with a quality restores part of its hunger and saturation (ProduceQuality.NUTRITION and SATURATION). */
@Mixin(FoodProperties.class)
public abstract class FoodQualityMixin {
    @WrapOperation(method = "onConsume", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/food/FoodData;eat(Lnet/minecraft/world/food/FoodProperties;)V"))
    private void cwp$qualityFood(FoodData food, FoodProperties properties, Operation<Void> original,
                                 Level level, LivingEntity entity, ItemStack stack, Consumable consumable) {
        ProduceQuality.Quality quality = ProduceQuality.get(stack);
        if (quality != null) properties = new FoodProperties(
                (int) Math.round(properties.nutrition() * ProduceQuality.NUTRITION[quality.ordinal()]),
                (float) (properties.saturation() * ProduceQuality.SATURATION[quality.ordinal()]), properties.canAlwaysEat());
        original.call(food, properties);
    }
}
