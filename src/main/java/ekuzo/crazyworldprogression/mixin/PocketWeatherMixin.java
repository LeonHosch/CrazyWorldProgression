package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.portals.PortalRegistry;
import ekuzo.crazyworldprogression.portals.PortalEnvironmentService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/** Give each leased pocket slot its own persisted weather instead of the server-wide weather. */
@Mixin(ServerLevel.class)
public abstract class PocketWeatherMixin {
    @Inject(method = "getWeatherData", at = @At("HEAD"), cancellable = true)
    private void cwp$pocketWeather(CallbackInfoReturnable<WeatherData> result) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (PortalRegistry.instanceSlots().contains(level.dimension().identifier())) {
            result.setReturnValue(level.getDataStorage().computeIfAbsent(WeatherData.TYPE));
        }
    }

    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void cwp$freezePocketWeather(CallbackInfo info) {
        if (PortalEnvironmentService.freezesWeather((ServerLevel) (Object) this)) info.cancel();
    }

    @Redirect(method = "advanceWeatherCycle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object cwp$independentPocketWeather(GameRules rules, GameRule<?> rule) {
        ServerLevel level = (ServerLevel) (Object) this;
        return PortalEnvironmentService.advancesWeather(level) ? Boolean.TRUE : rules.get(rule);
    }
}
