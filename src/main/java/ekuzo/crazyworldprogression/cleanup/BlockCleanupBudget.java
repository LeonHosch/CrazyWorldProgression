package ekuzo.crazyworldprogression.cleanup;

import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.WeakHashMap;

/** Shared number of block positions cleanup may inspect during one server tick. */
public final class BlockCleanupBudget {
    public static final int PER_TICK = 8_192;
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();

    private BlockCleanupBudget() { }

    public static int reserve(MinecraftServer server, int requested) {
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        long tick = server.getTickCount();
        if (state.tick != tick) {
            state.tick = tick;
            state.used = 0;
        }
        int granted = Math.min(Math.max(0, requested), PER_TICK - state.used);
        state.used += granted;
        return granted;
    }

    public static void clear(MinecraftServer server) { STATES.remove(server); }

    private static final class State {
        private long tick = Long.MIN_VALUE;
        private int used;
    }
}
