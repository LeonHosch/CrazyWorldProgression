package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No monsters spawn naturally (or with new chunks, patrols or reinforcements) inside a bonus area. */
@Mixin(Mob.class)
public abstract class BonusAreaSpawnRulesMixin {
    @Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
    private void crazyworldprogression$noMonstersInBonusAreas(LevelAccessor level, EntitySpawnReason reason,
                                                                CallbackInfoReturnable<Boolean> cir) {
        if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION
                && reason != EntitySpawnReason.PATROL && reason != EntitySpawnReason.REINFORCEMENT) return;
        Mob mob = (Mob) (Object) this;
        if (mob.getType().getCategory() != MobCategory.MONSTER || !(level instanceof ServerLevelAccessor accessor)) return;
        ServerLevel server = accessor.getLevel();
        if (BonusAreas.contains(server, mob.getX(), mob.getZ())) cir.setReturnValue(false);
    }
}
