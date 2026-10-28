package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.farming.ProduceQuality;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Food from vanilla recipes (furnace, smoker, campfire, crafting) is always Miserable; quality comes from CWP cooking. */
public final class RecipeQualityMixin {
    private RecipeQualityMixin() { }

    @Mixin(SingleItemRecipe.class)
    public abstract static class Single {
        @Inject(method = "assemble(Lnet/minecraft/world/item/crafting/SingleRecipeInput;)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
        private void cwp$miserable(SingleRecipeInput input, CallbackInfoReturnable<ItemStack> info) {
            ProduceQuality.miserable(info.getReturnValue());
        }
    }

    @Mixin(ShapedRecipe.class)
    public abstract static class Shaped {
        @Inject(method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
        private void cwp$miserable(CraftingInput input, CallbackInfoReturnable<ItemStack> info) {
            ProduceQuality.miserable(info.getReturnValue());
        }
    }

    @Mixin(ShapelessRecipe.class)
    public abstract static class Shapeless {
        @Inject(method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
        private void cwp$miserable(CraftingInput input, CallbackInfoReturnable<ItemStack> info) {
            ProduceQuality.miserable(info.getReturnValue());
        }
    }
}
