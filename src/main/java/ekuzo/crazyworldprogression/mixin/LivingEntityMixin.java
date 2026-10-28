package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillMultiplierService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Consumer;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    // Wrap vanilla mob-loot output so the credited player's CWP drop multiplier applies to every generated stack.
    @ModifyArg(
            method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;ZLnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;JLjava/util/function/Consumer;)V"),
            index = 2
    )
    private Consumer<ItemStack> crazyworldprogression$multiplyMobDrops(Consumer<ItemStack> original) {
        Player creditedPlayer = ((LivingEntity) (Object) this).getLastHurtByPlayer();
        // No player killed it: its meat is Miserable.
        if (!(creditedPlayer instanceof ServerPlayer serverPlayer))
            return stack -> original.accept(stack.is(ekuzo.crazyworldprogression.farming.ProduceQuality.MEAT)
                    ? ekuzo.crazyworldprogression.farming.ProduceQuality.miserable(stack) : stack);
        double multiplier = SkillMultiplierService.mobDropMultiplier(serverPlayer);
        // Meat gets its quality (or is ruined) once per kill, then the multiplier copies it.
        Consumer<ItemStack> graded = ekuzo.crazyworldprogression.farming.ProduceQuality.butcher(serverPlayer, original);
        return stack -> SkillMultiplierService.appendMultiplied(serverPlayer.getRandom(), graded, stack, multiplier);
    }
}
