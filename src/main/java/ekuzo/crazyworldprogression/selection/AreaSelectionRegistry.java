package ekuzo.crazyworldprogression.selection;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** Optional lifecycle hooks for mods that build workflows on CWP's shared area selector. */
public final class AreaSelectionRegistry {
    private static final List<SelectionListener> LISTENERS = new ArrayList<>();

    // Prevent construction of the process-wide listener registry.
    private AreaSelectionRegistry() {
    }

    // Register a listener that observes selection start, point changes, and clearing.
    public static synchronized void registerListener(SelectionListener listener) {
        LISTENERS.add(listener);
    }

    // Notify listeners with an immutable selection snapshot.
    static synchronized void changed(ServerPlayer player, AreaSelectionService.Selection selection) {
        for (SelectionListener listener : List.copyOf(LISTENERS)) listener.changed(player, selection);
    }

    /** Receives selection changes owned by CWP or another integrating mod. */
    @FunctionalInterface
    public interface SelectionListener {
        // React to a changed selection; null means the player's selection was cleared.
        void changed(ServerPlayer player, AreaSelectionService.Selection selection);
    }
}
