package ekuzo.crazyworldprogression.cleanup;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Adjusting time budget for block work (building, clearing). Each tick the budget is
 * tick length − the server's own load − a safety buffer, kept between a minimum and a maximum.
 * <ul>
 *   <li>Scripts always come first: they may use up to {@code script_max_ms} per tick no matter how busy the
 *       server is, so raid and dungeon mechanics never stall.</li>
 *   <li>Instance creation (and wipe rebuilds) come next and always get at least {@code min_budget_ms}.</li>
 *   <li>Instance deletion and other cleanup get what is left, but at least {@code cleanup_floor_ms}.</li>
 * </ul>
 * The server's own load is its average tick time minus the time this budget handed out, so the budget does not
 * shrink because of its own work. Settings live under performance in config/crazy-world-progression.yml.
 */
public final class TickBudget {
    public enum Priority { SCRIPT, CREATION, CLEANUP }

    /** Defaults; replaced by the config file's values. */
    static double bufferMs = 10.0, minMs = 2.0, maxMs = 30.0, scriptMaxMs = 30.0, cleanupFloorMs = 1.0;
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final int CHECK_EVERY = 128;

    private TickBudget() { }

    public static void initialize() {
        load();
        ServerLifecycleEvents.SERVER_STOPPED.register(STATES::remove);
    }

    private static final class State {
        long tick = Long.MIN_VALUE;
        final long[] spent = new long[Priority.values().length];
        double ownAverageNanos;
        double scriptAverageNanos;
        long budgetNanos;
        /** Per priority, averaged over roughly the last 100 ticks, for the performance view. */
        final double[] averageNanos = new double[Priority.values().length];
    }

    /** One piece of block work. Call {@link #more(int)} in the loop and {@link #close()} at the end. */
    public static final class Slice implements AutoCloseable {
        private final State state;
        private final Priority priority;
        private final long start = System.nanoTime();
        private final long deadline;

        private Slice(State state, Priority priority, long allowedNanos) {
            this.state = state;
            this.priority = priority;
            this.deadline = start + Math.max(0L, allowedNanos);
        }

        /** True while this slice may do more work; checks the clock every few iterations. */
        public boolean more(int done) {
            return done % CHECK_EVERY != 0 || System.nanoTime() < deadline;
        }

        public boolean hasTime() { return System.nanoTime() < deadline; }

        @Override public void close() {
            state.spent[priority.ordinal()] += System.nanoTime() - start;
        }
    }

    /** Starts a slice of block work with the given priority for the current tick. */
    public static Slice start(MinecraftServer server, Priority priority) {
        State state = state(server);
        return new Slice(state, priority, allowed(state, priority));
    }

    private static State state(MinecraftServer server) {
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        long tick = server.getTickCount();
        if (state.tick != tick) {
            long own = 0;
            for (long value : state.spent) own += value;
            // Averages over roughly the last 100 ticks, like the server's own average tick time.
            state.ownAverageNanos += (own - state.ownAverageNanos) / 100.0;
            state.scriptAverageNanos += (state.spent[Priority.SCRIPT.ordinal()] - state.scriptAverageNanos) / 20.0;
            for (int i = 0; i < state.spent.length; i++)
                state.averageNanos[i] += (state.spent[i] - state.averageNanos[i]) / 100.0;
            java.util.Arrays.fill(state.spent, 0L);
            state.tick = tick;
            long baseNanos = Math.max(0L, server.getAverageTickTimeNanos() - (long) state.ownAverageNanos);
            state.budgetNanos = budgetNanos(server.tickRateManager().nanosecondsPerTick(), baseNanos);
        }
        return state;
    }

    /** tick length − base load − buffer, kept between minimum and maximum. */
    static long budgetNanos(long tickNanos, long baseNanos) {
        long free = tickNanos - baseNanos - ms(bufferMs);
        return Math.max(ms(minMs), Math.min(ms(maxMs), free));
    }

    /** How long a new slice of this priority may run, given what was already spent this tick. */
    static long allowed(long budget, long[] spent, double scriptAverage, Priority priority) {
        long total = 0;
        for (long value : spent) total += value;
        return switch (priority) {
            // Scripts are never limited by the server load, only by their own cap.
            case SCRIPT -> ms(scriptMaxMs) - spent[Priority.SCRIPT.ordinal()];
            // Creation keeps room for what scripts usually need later in the tick, but always gets the minimum.
            case CREATION -> Math.max(ms(minMs) - spent[Priority.CREATION.ordinal()],
                    budget - (long) scriptAverage - total);
            // Cleanup comes last and only gets a small floor beyond what is left.
            case CLEANUP -> Math.max(ms(cleanupFloorMs) - spent[Priority.CLEANUP.ordinal()],
                    budget - (long) scriptAverage - total);
        };
    }

    private static long allowed(State state, Priority priority) {
        return allowed(state.budgetNanos, state.spent, state.scriptAverageNanos, priority);
    }

    /** Current budget and average time per priority, in milliseconds, for the performance view. */
    public record Stats(double budgetMs, double scriptMs, double creationMs, double cleanupMs) { }

    public static Stats stats(MinecraftServer server) {
        State state = STATES.get(server);
        if (state == null) return new Stats(0, 0, 0, 0);
        return new Stats(state.budgetNanos / 1e6, state.averageNanos[Priority.SCRIPT.ordinal()] / 1e6,
                state.averageNanos[Priority.CREATION.ordinal()] / 1e6, state.averageNanos[Priority.CLEANUP.ordinal()] / 1e6);
    }

    /** True when this priority still has time left in the current tick. */
    public static boolean available(MinecraftServer server, Priority priority) {
        return allowed(state(server), priority) > 0;
    }

    static long ms(double millis) { return (long) (millis * 1_000_000.0); }

    private static void load() {
        ekuzo.crazyworldprogression.config.CwpConfig config = ekuzo.crazyworldprogression.config.CwpConfig.get();
        bufferMs = config.tickBufferMs();
        minMs = config.minBudgetMs();
        maxMs = config.maxBudgetMs();
        scriptMaxMs = config.scriptMaxMs();
        cleanupFloorMs = config.cleanupFloorMs();
    }
}
