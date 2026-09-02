package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillMultiplierService;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    // Multiply all positive vanilla XP-point awards while preserving commands or mechanics that remove experience.
    @ModifyVariable(method = "giveExperiencePoints", at = @At("HEAD"), argsOnly = true)
    private int crazyworldprogression$multiplyExperience(int amount) {
        return SkillMultiplierService.multiplyExperience((ServerPlayer) (Object) this, amount);
    }
}
