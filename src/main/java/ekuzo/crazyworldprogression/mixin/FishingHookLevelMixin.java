package ekuzo.crazyworldprogression.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import ekuzo.crazyworldprogression.loot.FishingJournal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Fishing level (Skills) works like extra Lure: each level takes {@link FishingJournal#BITE_TICKS_PER_LEVEL} ticks off the
 * wait for a fish to bite (vanilla waits 5 to 30 seconds), up to {@link FishingJournal#MAX_BITE_TICKS}.
 */
@Mixin(FishingHook.class)
public abstract class FishingHookLevelMixin {
    @ModifyExpressionValue(method = "catchingFish", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/projectile/FishingHook;lureSpeed:I"))
    private int cwp$fishingLevelLure(int lureSpeed) {
        if (!(((FishingHook) (Object) this).getPlayerOwner() instanceof ServerPlayer player)) return lureSpeed;
        return lureSpeed + Math.min(FishingJournal.MAX_BITE_TICKS, ekuzo.crazyworldprogression.skills.Skills.level(player,
                ekuzo.crazyworldprogression.skills.Skills.Skill.FISHING) * FishingJournal.BITE_TICKS_PER_LEVEL);
    }
}
