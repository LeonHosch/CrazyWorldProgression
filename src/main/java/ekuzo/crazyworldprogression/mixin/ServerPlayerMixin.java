package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillMultiplierService;
import ekuzo.crazyworldprogression.portals.PortalInventoryPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    // Multiply all positive vanilla XP-point awards while preserving commands or mechanics that remove experience.
    @ModifyVariable(method = "giveExperiencePoints", at = @At("HEAD"), argsOnly = true)
    private int crazyworldprogression$multiplyExperience(int amount) {
        return SkillMultiplierService.multiplyExperience((ServerPlayer) (Object) this, amount);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "restoreFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object crazyworldprogression$restorePortalInventory(GameRules rules, GameRule rule,
                                                                   ServerPlayer previous, boolean alive) {
        boolean vanilla = (Boolean) rules.get(rule);
        return PortalInventoryPolicy.onRestore(previous, alive, vanilla);
    }
}
