package ekuzo.crazyworldprogression.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Mobs never drop their loot table on the ground; their loot pool is rolled for the killer instead, and its vanilla
 * entry rolls the loot table there (MobLoot).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityNoLootDropMixin {
    @Inject(method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;ZLnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true)
    private void cwp$noGroundLoot(ServerLevel level, DamageSource source, boolean playerKill, ResourceKey<LootTable> table,
                                  Consumer<ItemStack> output, CallbackInfo info) {
        if (!((Object) this instanceof Player)) info.cancel();
    }
}
