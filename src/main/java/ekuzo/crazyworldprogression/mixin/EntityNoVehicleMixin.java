package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MobForge mobs and entity types in {@code #crazy-world-progression:no_vehicles} can't be put into boats or minecarts
 * (no trapping a boss in a boat). Forced riding (commands, scripts) still works.
 */
@Mixin(Entity.class)
public abstract class EntityNoVehicleMixin {
    @Unique
    private static final TagKey<EntityType<?>> NO_VEHICLES = TagKey.create(Registries.ENTITY_TYPE, CrazyWorldProgression.id("no_vehicles"));

    @Inject(method = "canRide", at = @At("HEAD"), cancellable = true)
    private void crazyworldprogression$noVehicles(Entity vehicle, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!(vehicle instanceof VehicleEntity) || self instanceof Player) return;
        if (self.getType().builtInRegistryHolder().is(NO_VEHICLES) || MobForgeEntityData.template(self).isPresent()) cir.setReturnValue(false);
    }
}
