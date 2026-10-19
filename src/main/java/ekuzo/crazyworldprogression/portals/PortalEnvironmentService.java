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

    /**
     * View and simulation distance of the instance's world: the portal's values, or the server's own settings
     * (also used when the slot is released).
     */
    static void applyDistances(MinecraftServer server, net.minecraft.resources.Identifier slot, Environment environment) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, slot));
        if (level == null) return;
        if (environment != null && !environment.randomTicks()) NO_RANDOM_TICKS.add(slot); else NO_RANDOM_TICKS.remove(slot);
        if (environment != null && !environment.fireSpread()) NO_FIRE_SPREAD.add(slot); else NO_FIRE_SPREAD.remove(slot);
        int view = environment != null && environment.viewDistance() > 0 ? environment.viewDistance()
                : server.getPlayerList().getViewDistance();
        int simulation = environment != null && environment.simulationDistance() > 0 ? environment.simulationDistance()
                : server.getPlayerList().getSimulationDistance();
        int[] current = DISTANCES.get(slot);
        if (current != null && current[0] == view && current[1] == simulation) return;
        level.getChunkSource().setViewDistance(view);
        level.getChunkSource().setSimulationDistance(simulation);
        if (view == server.getPlayerList().getViewDistance() && simulation == server.getPlayerList().getSimulationDistance())
            DISTANCES.remove(slot);
        else DISTANCES.put(slot, new int[] {view, simulation});
        for (ServerPlayer player : level.players()) syncDistances(player, level);
    }

    static void clearDistances() {
        DISTANCES.clear();
        NO_RANDOM_TICKS.clear();
        NO_FIRE_SPREAD.clear();
    }

    private static final java.util.Set<net.minecraft.resources.Identifier> NO_RANDOM_TICKS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<net.minecraft.resources.Identifier> NO_FIRE_SPREAD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Instance worlds whose portal turned random ticks off. */
    public static boolean randomTicksOff(ServerLevel level) {
        return !NO_RANDOM_TICKS.isEmpty() && NO_RANDOM_TICKS.contains(level.dimension().identifier());
    }

    /** Instance worlds whose portal turned fire spread off. */
    public static boolean fireSpreadOff(ServerLevel level) {
        return !NO_FIRE_SPREAD.isEmpty() && NO_FIRE_SPREAD.contains(level.dimension().identifier());
    }

    /** Distances CWP set on instance worlds that differ from the server's; Minecraft has no getter for them. */
    private static final java.util.Map<net.minecraft.resources.Identifier, int[]> DISTANCES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A player's client only learns the view and simulation distance when it logs in, so it is told again when
     * entering a world whose distances differ (an instance, or back out of one).
     */
    static void syncDistances(ServerPlayer player, ServerLevel level) {
        MinecraftServer server = level.getServer();
        int[] distances = DISTANCES.getOrDefault(level.dimension().identifier(),
                new int[] {server.getPlayerList().getViewDistance(), server.getPlayerList().getSimulationDistance()});
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(distances[0]));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket(distances[1]));
    }

    static void restore(MinecraftServer server, PortalService.ActiveInstance instance, Environment environment) {
        applyDistances(server, instance.slotDimension(), environment);
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
