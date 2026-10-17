package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.portals.PortalInventoryPolicy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class PlayerInventoryRuleMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "dropEquipment", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object crazyworldprogression$portalDeathInventory(GameRules rules, GameRule rule) {
        boolean vanilla = (Boolean) rules.get(rule);
        return PortalInventoryPolicy.onDrop((Player) (Object) this, vanilla);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(method = "getBaseExperienceReward", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object crazyworldprogression$portalDeathExperience(GameRules rules, GameRule rule) {
        boolean vanilla = (Boolean) rules.get(rule);
        return PortalInventoryPolicy.onDrop((Player) (Object) this, vanilla);
    }
}
