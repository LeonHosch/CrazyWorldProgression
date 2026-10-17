package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.portals.PortalDefinition.Environment;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;

/** Applies portal-owned time and weather to its leased slot without changing the overworld. */
public final class PortalEnvironmentService {
    private PortalEnvironmentService() {
    }

    static void applyTime(MinecraftServer server, PortalService.ActiveInstance instance, Environment environment) {
        Holder<WorldClock> clock = clock(server, instance);
        server.clockManager().setTotalTicks(clock, environment.daytime().ticks());
        server.clockManager().setPaused(clock, !environment.dayNightCycle());
    }

    static void updateTime(MinecraftServer server, PortalService.ActiveInstance instance,
                           Environment previous, Environment updated) {
        Holder<WorldClock> clock = clock(server, instance);
        if (previous.daytime() != updated.daytime()) {
            server.clockManager().setTotalTicks(clock, updated.daytime().ticks());
        }
        if (previous.dayNightCycle() != updated.dayNightCycle()) {
            server.clockManager().setPaused(clock, !updated.dayNightCycle());
        }
    }

    static void restore(MinecraftServer server, PortalService.ActiveInstance instance, Environment environment) {
        Holder<WorldClock> clock = clock(server, instance);
        if (server.clockManager().getTotalTicks(clock) == 0L) applyTime(server, instance, environment);
        ServerLevel level = instance.world(server);
        WeatherData weather = level.getWeatherData();
        level.setRainLevel(weather.isRaining() ? 1.0F : 0.0F);
        level.setThunderLevel(weather.isThundering() ? 1.0F : 0.0F);
    }

    private static Holder<WorldClock> clock(MinecraftServer server, PortalService.ActiveInstance instance) {
        ResourceKey<WorldClock> key = ResourceKey.create(Registries.WORLD_CLOCK, instance.slotDimension());
        return server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(key);
    }

    static void applyWeather(MinecraftServer server, PortalService.ActiveInstance instance, Environment environment) {
        ServerLevel level = instance.world(server);
        WeatherData data = level.getWeatherData();
        boolean raining = environment.weather() != PortalDefinition.Weather.CLEAR;
        boolean thundering = environment.weather() == PortalDefinition.Weather.THUNDER;
        data.setClearWeatherTime(0);
        data.setRainTime(environment.weatherCycle() ? 12_000 : Integer.MAX_VALUE);
        data.setThunderTime(environment.weatherCycle() ? 12_000 : Integer.MAX_VALUE);
        data.setRaining(raining);
        data.setThundering(thundering);
        level.setRainLevel(raining ? 1.0F : 0.0F);
        level.setThunderLevel(thundering ? 1.0F : 0.0F);
        for (ServerPlayer player : level.players()) syncWeather(player);
    }

    static void syncWeather(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        boolean raining = level.getWeatherData().isRaining();
        player.connection.send(new ClientboundGameEventPacket(raining
                ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING, 0.0F));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                level.getRainLevel(1.0F)));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                level.getThunderLevel(1.0F)));
    }

    public static boolean freezesWeather(ServerLevel level) {
        MinecraftServer server = level.getServer();
        PortalData.StoredInstance instance = PortalData.get(server).instances()
                .get(level.dimension().identifier().toString());
        if (instance == null) return false;
        PortalDefinition portal = PortalService.portal(server, instance.portalId());
        return portal != null && !portal.environment().weatherCycle();
    }

    public static boolean advancesWeather(ServerLevel level) {
        MinecraftServer server = level.getServer();
        PortalData.StoredInstance instance = PortalData.get(server).instances()
                .get(level.dimension().identifier().toString());
        if (instance == null) return false;
        PortalDefinition portal = PortalService.portal(server, instance.portalId());
        return portal != null && portal.environment().weatherCycle();
    }

    static void tickIndependentTime(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0
                || server.getGlobalGameRules().get(GameRules.ADVANCE_TIME)) return;
        for (PortalService.ActiveInstance instance : PortalService.activeInstances(server)) {
            PortalDefinition portal = PortalService.portal(server, instance.portalId());
            if (portal != null && portal.environment().dayNightCycle()) {
                server.clockManager().addTicks(clock(server, instance), 20);
            }
        }
    }
}
