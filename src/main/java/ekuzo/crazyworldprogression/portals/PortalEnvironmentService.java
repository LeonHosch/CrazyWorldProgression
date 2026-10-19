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
        if (environment != null && environment.mobSpawns()) MOB_SPAWNS_ON.add(slot); else MOB_SPAWNS_ON.remove(slot);
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

    /**
     * A script changes its instance's distances: -1 leaves a value, 0 returns it to the portal's setting (or the
     * server's), anything else is chunks. Saving the portal or releasing the slot applies the portal's again.
     */
    public static void scriptDistances(ServerLevel level, int view, int simulation) {
        MinecraftServer server = level.getServer();
        net.minecraft.resources.Identifier slot = level.dimension().identifier();
        PortalData.StoredInstance instance = PortalData.get(server).instances().get(slot.toString());
        if (instance == null) return;
        PortalDefinition portal = PortalService.portal(server, instance.portalId());
        Environment environment = portal == null ? null : portal.environment();
        int defaultView = environment != null && environment.viewDistance() > 0 ? environment.viewDistance()
                : server.getPlayerList().getViewDistance();
        int defaultSimulation = environment != null && environment.simulationDistance() > 0
                ? environment.simulationDistance() : server.getPlayerList().getSimulationDistance();
        int[] current = DISTANCES.getOrDefault(slot, new int[] {defaultView, defaultSimulation});
        int newView = view < 0 ? current[0] : view == 0 ? defaultView : view;
        int newSimulation = simulation < 0 ? current[1] : simulation == 0 ? defaultSimulation : simulation;
        level.getChunkSource().setViewDistance(newView);
        level.getChunkSource().setSimulationDistance(newSimulation);
        DISTANCES.put(slot, new int[] {newView, newSimulation});
        for (ServerPlayer player : level.players()) syncDistances(player, level);
    }

    static void clearDistances() {
        DISTANCES.clear();
        NO_RANDOM_TICKS.clear();
        NO_FIRE_SPREAD.clear();
        MOB_SPAWNS_ON.clear();
    }

    private static final java.util.Set<net.minecraft.resources.Identifier> MOB_SPAWNS_ON = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Instance worlds spawn no vanilla mobs unless their portal allows it. Other worlds are not affected. */
    public static boolean mobSpawnsOff(ServerLevel level) {
        net.minecraft.resources.Identifier dimension = level.dimension().identifier();
        // Runs for every chunk of every world each tick, so only a cheap name check here (slots are portal_instance_N).
        return dimension.getNamespace().equals(ekuzo.crazyworldprogression.CrazyWorldProgression.MOD_ID)
                && dimension.getPath().startsWith("portal_instance_") && !MOB_SPAWNS_ON.contains(dimension);
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
