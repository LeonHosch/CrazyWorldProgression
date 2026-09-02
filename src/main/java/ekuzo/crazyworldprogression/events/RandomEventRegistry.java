package ekuzo.crazyworldprogression.events;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extension API for dependent-mod event configuration and gameplay lifecycle handlers. */
public final class RandomEventRegistry {
    private static final List<EventSource> SOURCES = new ArrayList<>();
    private static final Map<Identifier, RandomEventHandler> HANDLERS = new LinkedHashMap<>();

    // Prevent instantiation of the process-wide event extension registry.
    private RandomEventRegistry() {
    }

    // Register one dependent mod's bundled random-event YAML file for server-start loading.
    public static synchronized void registerSource(String namespace, String modId, String resourcePath) {
        EventSource source = new EventSource(namespace, modId, resourcePath);
        if (SOURCES.contains(source)) throw new IllegalArgumentException("Duplicate random-event source: " + source);
        SOURCES.add(source);
    }

    // Attach optional game-specific start, restore, and end behavior to one event identifier.
    public static synchronized void registerHandler(Identifier eventId, RandomEventHandler handler) {
        if (eventId == null || handler == null) throw new IllegalArgumentException("Event handler values must not be null");
        if (HANDLERS.putIfAbsent(eventId, handler) != null) {
            throw new IllegalArgumentException("Duplicate random-event handler for " + eventId);
        }
    }

    // Return an immutable resource-source snapshot after all mod initializers have run.
    public static synchronized List<EventSource> sources() {
        return List.copyOf(SOURCES);
    }

    // Report whether an event has content-owned lifecycle behavior attached to its framework state.
    public static synchronized boolean hasHandler(Identifier eventId) {
        return HANDLERS.containsKey(eventId);
    }

    // Return the number of handlers registered by all dependent content mods.
    public static synchronized int handlerCount() {
        return HANDLERS.size();
    }

    // Resolve one optional content-owned lifecycle handler.
    static synchronized RandomEventHandler handler(Identifier eventId) {
        return HANDLERS.get(eventId);
    }

    /** Identifies one dependent mod resource and the namespace used for local event IDs. */
    public record EventSource(String namespace, String modId, String resourcePath) {
        // Reject missing source metadata during mod initialization rather than server startup.
        public EventSource {
            if (namespace == null || namespace.isBlank() || modId == null || modId.isBlank()
                    || resourcePath == null || resourcePath.isBlank()) {
                throw new IllegalArgumentException("Random-event source values must not be blank");
            }
        }
    }

    /** Content-mod hook for applying and cleaning up the gameplay represented by framework state. */
    public interface RandomEventHandler {
        // Apply content behavior immediately after a new event instance becomes active.
        default void onStarted(MinecraftServer server, RandomEventService.ActiveRandomEvent event) {
        }

        // Restore content behavior for an event instance retained across a server restart.
        default void onRestored(MinecraftServer server, RandomEventService.ActiveRandomEvent event) {
        }

        // Remove content behavior after expiry, a manual stop, or removal of its definition.
        default void onEnded(MinecraftServer server, RandomEventService.ActiveRandomEvent event, EndReason reason) {
        }
    }

    /** Explains why the framework ended an active event instance. */
    public enum EndReason {
        DURATION_EXPIRED,
        MANUAL,
        DEFINITION_REMOVED
    }
}
