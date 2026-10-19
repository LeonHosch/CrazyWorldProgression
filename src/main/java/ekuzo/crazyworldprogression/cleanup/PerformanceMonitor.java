package ekuzo.crazyworldprogression.cleanup;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where the server's tick time goes: the whole tick, each world (overworld, every instance and workshop), and
 * CWP's own block work. Used by /cwp performance and the admin panel's Performance page.
 */
public final class PerformanceMonitor {
    private static final Map<MinecraftServer, Map<Identifier, WorldTiming>> WORLDS = new WeakHashMap<>();

    private PerformanceMonitor() { }

    /** Averages over roughly the last 100 ticks and the slowest of the last 100. */
    private static final class WorldTiming {
        long started;
        double averageNanos;
        final long[] recent = new long[100];
        int next;
    }

    public static void initialize() {
        ServerTickEvents.START_LEVEL_TICK.register(level -> timing(level).started = System.nanoTime());
        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            WorldTiming timing = timing(level);
            if (timing.started == 0L) return;
            long spent = System.nanoTime() - timing.started;
            timing.averageNanos += (spent - timing.averageNanos) / 100.0;
            timing.recent[timing.next] = spent;
            timing.next = (timing.next + 1) % timing.recent.length;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(WORLDS::remove);
    }

    private static WorldTiming timing(ServerLevel level) {
        return WORLDS.computeIfAbsent(level.getServer(), ignored -> new HashMap<>())
                .computeIfAbsent(level.dimension().identifier(), ignored -> new WorldTiming());
    }

    /** One world's share of the tick, with what is in it. */
    public record World(Identifier id, double averageMs, double peakMs, int entities, int chunks) { }

    /** The whole tick: average, 95th percentile and slowest of the last 100 ticks, and ticks per second. */
    public record Tick(double averageMs, double p95Ms, double peakMs, double tps) { }

    public static Tick tick(MinecraftServer server) {
        long[] times = server.getTickTimesNanos().clone();
        long count = Arrays.stream(times).filter(time -> time > 0).count();
        if (count == 0) return new Tick(0, 0, 0, 20);
        long[] filled = Arrays.stream(times).filter(time -> time > 0).sorted().toArray();
        double average = Arrays.stream(filled).average().orElse(0) / 1e6;
        double p95 = filled[(int) Math.min(filled.length - 1, Math.round(filled.length * 0.95))] / 1e6;
        double peak = filled[filled.length - 1] / 1e6;
        double tickLength = server.tickRateManager().nanosecondsPerTick() / 1e6;
        double tps = Math.min(1000.0 / tickLength, 1000.0 / Math.max(average, tickLength));
        return new Tick(average, p95, peak, tps);
    }

    /** Worlds sorted by tick time, slowest first. Counting entities walks each world's entity list once. */
    public static List<World> worlds(MinecraftServer server) {
        Map<Identifier, WorldTiming> timings = WORLDS.getOrDefault(server, Map.of());
        List<World> result = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            WorldTiming timing = timings.get(level.dimension().identifier());
            double peak = 0;
            if (timing != null) for (long value : timing.recent) peak = Math.max(peak, value / 1e6);
            int entities = 0;
            for (var ignored : level.getAllEntities()) entities++;
            result.add(new World(level.dimension().identifier(), timing == null ? 0 : timing.averageNanos / 1e6, peak,
                    entities, level.getChunkSource().getLoadedChunksCount()));
        }
        result.sort(Comparator.comparingDouble(World::averageMs).reversed());
        return result;
    }
}
