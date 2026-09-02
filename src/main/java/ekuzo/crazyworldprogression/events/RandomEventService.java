package ekuzo.crazyworldprogression.events;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ekuzo.crazyworldprogression.events.RandomEventDefinition.DurationClock.REAL_TIME;

/** Server-authoritative scheduler and persistent state API for registered random events. */
public final class RandomEventService {
    private static final long MINECRAFT_PHASE_TICKS = 12_000L;

    // Prevent instantiation of the static event service.
    private RandomEventService() {
    }

    // Register restoration and once-per-second scheduling callbacks with Fabric's server lifecycle.
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(RandomEventService::restoreActiveEvents);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) tick(server);
        });
    }

    // Return whether one event currently has a persisted active instance.
    public static boolean isActive(MinecraftServer server, Identifier eventId) {
        return RandomEventData.get(server).active(eventId.toString()) != null;
    }

    // Return one active event snapshot for gameplay, rendering synchronization, or integration logic.
    public static Optional<ActiveRandomEvent> activeEvent(MinecraftServer server, Identifier eventId) {
        return Optional.ofNullable(RandomEventData.get(server).active(eventId.toString()))
                .map(stored -> snapshot(eventId, stored));
    }

    // Return every active event in deterministic insertion order.
    public static List<ActiveRandomEvent> activeEvents(MinecraftServer server) {
        List<ActiveRandomEvent> result = new ArrayList<>();
        RandomEventData.get(server).activeEvents().forEach((id, stored) ->
                result.add(snapshot(parseStoredIdentifier(id), stored)));
        return List.copyOf(result);
    }

    // Force-start a configured event for commands, tests, or application-controlled story logic.
    public static boolean startNow(MinecraftServer server, Identifier eventId) {
        RandomEventDefinition definition = RandomEventManager.definition(eventId);
        if (definition == null) throw new IllegalArgumentException("Unknown random event '" + eventId + "'");
        return start(server, definition);
    }

    // Manually end an active event and notify its content-owned cleanup handler.
    public static boolean stop(MinecraftServer server, Identifier eventId) {
        return end(server, eventId, RandomEventRegistry.EndReason.MANUAL);
    }

    // End persisted instances whose definitions disappeared during an operator-requested configuration reload.
    public static int reconcileDefinitions(MinecraftServer server) {
        int removed = 0;
        for (ActiveRandomEvent event : activeEvents(server)) {
            if (RandomEventManager.definition(event.id()) == null
                    && end(server, event.id(), RandomEventRegistry.EndReason.DEFINITION_REMOVED)) {
                removed++;
            }
        }
        return removed;
    }

    // Add or replace one persistent string value owned by the active event's gameplay handler.
    public static void putMetadata(MinecraftServer server, Identifier eventId, String key, String value) {
        validateMetadata(key, value);
        RandomEventData data = RandomEventData.get(server);
        RandomEventData.StoredActiveEvent active = requireActive(data, eventId);
        data.putActive(eventId.toString(), active.withMetadata(key, value));
    }

    // Remove one persistent metadata value and leave the event active.
    public static void removeMetadata(MinecraftServer server, Identifier eventId, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Event metadata key must not be blank");
        RandomEventData data = RandomEventData.get(server);
        RandomEventData.StoredActiveEvent active = requireActive(data, eventId);
        data.putActive(eventId.toString(), active.withoutMetadata(key));
    }

    // Report whether the current wall-clock instant lies inside any registered global blackout window.
    public static boolean isBlackoutActive() {
        return isBlackoutActive(Instant.now());
    }

    // Expire finished instances and evaluate due random rolls once per scheduler second.
    private static void tick(MinecraftServer server) {
        Instant now = Instant.now();
        long gameTime = server.overworld().getGameTime();
        expireEvents(server, now.toEpochMilli(), gameTime);
        RandomEventData data = RandomEventData.get(server);
        for (RandomEventDefinition definition : RandomEventManager.definitions()) {
            if (definition.trigger() instanceof RandomEventDefinition.InGamePhaseTrigger trigger) {
                evaluateInGameTrigger(server, data, definition, trigger, now);
            } else if (definition.trigger() instanceof RandomEventDefinition.RealTimeTrigger trigger) {
                evaluateRealTimeTrigger(server, data, definition, trigger, now);
            }
        }
    }

    // Roll at most once for each newly reached Minecraft day or night half-day slot.
    private static void evaluateInGameTrigger(MinecraftServer server, RandomEventData data,
                                              RandomEventDefinition definition,
                                              RandomEventDefinition.InGamePhaseTrigger trigger, Instant now) {
        long slot = Math.floorDiv(server.overworld().getOverworldClockTime(), MINECRAFT_PHASE_TICKS);
        Long previous = data.lastGameSlot(definition.id().toString());
        if (previous != null && slot <= previous) return;
        data.setLastGameSlot(definition.id().toString(), slot);
        RandomEventDefinition.InGamePhase currentPhase = Math.floorMod(slot, 2L) == 0L
                ? RandomEventDefinition.InGamePhase.DAY : RandomEventDefinition.InGamePhase.NIGHT;
        if (currentPhase == trigger.phase()) attemptRandomStart(server, definition, trigger.chance(), now);
    }

    // Initialize or advance one persisted wall-clock deadline and perform at most one due roll.
    private static void evaluateRealTimeTrigger(MinecraftServer server, RandomEventData data,
                                                RandomEventDefinition definition,
                                                RandomEventDefinition.RealTimeTrigger trigger, Instant now) {
        String eventId = definition.id().toString();
        long nowMillis = now.toEpochMilli();
        Long nextRoll = data.nextRealRoll(eventId);
        long intervalMillis = saturatedMultiply(trigger.intervalSeconds(), 1_000L);
        if (nextRoll == null) {
            data.setNextRealRoll(eventId, saturatedAdd(nowMillis, intervalMillis));
            return;
        }
        if (nowMillis < nextRoll) return;
        data.setNextRealRoll(eventId, saturatedAdd(nowMillis, intervalMillis));
        attemptRandomStart(server, definition, trigger.chance(), now);
    }

    // Apply shared player, blackout, availability, and probability gates before a random start.
    private static void attemptRandomStart(MinecraftServer server, RandomEventDefinition definition,
                                           double chance, Instant now) {
        if (RandomEventData.get(server).active(definition.id().toString()) != null
                || server.getPlayerList().getPlayerCount() == 0 || isBlackoutActive(now)
                || !definition.availability().allows(now)) return;
        if (server.overworld().getRandom().nextDouble() < chance) start(server, definition);
    }

    // Persist one active instance with end values calculated from the definition's selected clock.
    private static boolean start(MinecraftServer server, RandomEventDefinition definition) {
        RandomEventData data = RandomEventData.get(server);
        String eventId = definition.id().toString();
        if (data.active(eventId) != null) return false;
        long nowMillis = Instant.now().toEpochMilli();
        long gameTime = server.overworld().getGameTime();
        boolean realTime = definition.duration().clock() == REAL_TIME;
        long endsEpochMillis = realTime
                ? saturatedAdd(nowMillis, saturatedMultiply(definition.duration().amount(), 1_000L)) : -1L;
        long endsGameTime = realTime ? -1L : saturatedAdd(gameTime, definition.duration().amount());
        RandomEventData.StoredActiveEvent stored = new RandomEventData.StoredActiveEvent(
                data.allocateSequence(), nowMillis, endsEpochMillis, gameTime, endsGameTime, Map.of());
        data.putActive(eventId, stored);
        ActiveRandomEvent event = snapshot(definition.id(), stored);
        CrazyWorldProgression.LOGGER.info("Started random event {} (instance {})", event.id(), event.sequence());
        invokeStarted(server, event);
        return true;
    }

    // End every real-time or game-time instance whose persisted deadline has been reached.
    private static void expireEvents(MinecraftServer server, long nowMillis, long gameTime) {
        for (Map.Entry<String, RandomEventData.StoredActiveEvent> entry
                : RandomEventData.get(server).activeEvents().entrySet()) {
            RandomEventData.StoredActiveEvent event = entry.getValue();
            boolean expired = event.endsEpochMillis() >= 0L ? nowMillis >= event.endsEpochMillis()
                    : event.endsGameTime() >= 0L && gameTime >= event.endsGameTime();
            if (expired) end(server, parseStoredIdentifier(entry.getKey()), RandomEventRegistry.EndReason.DURATION_EXPIRED);
        }
    }

    // Restore retained instances, expire overdue ones, and remove state whose definition no longer exists.
    private static void restoreActiveEvents(MinecraftServer server) {
        RandomEventData data = RandomEventData.get(server);
        long nowMillis = Instant.now().toEpochMilli();
        long gameTime = server.overworld().getGameTime();
        for (Map.Entry<String, RandomEventData.StoredActiveEvent> entry : data.activeEvents().entrySet()) {
            Identifier eventId = parseStoredIdentifier(entry.getKey());
            if (RandomEventManager.definition(eventId) == null) {
                end(server, eventId, RandomEventRegistry.EndReason.DEFINITION_REMOVED);
                continue;
            }
            RandomEventData.StoredActiveEvent stored = entry.getValue();
            boolean expired = stored.endsEpochMillis() >= 0L ? nowMillis >= stored.endsEpochMillis()
                    : stored.endsGameTime() >= 0L && gameTime >= stored.endsGameTime();
            if (expired) {
                end(server, eventId, RandomEventRegistry.EndReason.DURATION_EXPIRED);
            } else {
                invokeRestored(server, snapshot(eventId, stored));
            }
        }
    }

    // Remove one active record before invoking cleanup so callbacks observe the final inactive state.
    private static boolean end(MinecraftServer server, Identifier eventId, RandomEventRegistry.EndReason reason) {
        RandomEventData.StoredActiveEvent stored = RandomEventData.get(server).removeActive(eventId.toString());
        if (stored == null) return false;
        ActiveRandomEvent event = snapshot(eventId, stored);
        CrazyWorldProgression.LOGGER.info("Ended random event {} (instance {}, reason {})",
                event.id(), event.sequence(), reason);
        invokeEnded(server, event, reason);
        return true;
    }

    // Safely call a content handler without allowing one broken event to stop the scheduler.
    private static void invokeStarted(MinecraftServer server, ActiveRandomEvent event) {
        RandomEventRegistry.RandomEventHandler handler = RandomEventRegistry.handler(event.id());
        if (handler == null) return;
        try {
            handler.onStarted(server, event);
        } catch (RuntimeException exception) {
            CrazyWorldProgression.LOGGER.error("Random event {} failed during onStarted", event.id(), exception);
        }
    }

    // Safely ask content code to reconstruct behavior for a persisted active instance.
    private static void invokeRestored(MinecraftServer server, ActiveRandomEvent event) {
        RandomEventRegistry.RandomEventHandler handler = RandomEventRegistry.handler(event.id());
        if (handler == null) return;
        try {
            handler.onRestored(server, event);
        } catch (RuntimeException exception) {
            CrazyWorldProgression.LOGGER.error("Random event {} failed during onRestored", event.id(), exception);
        }
    }

    // Safely notify content code that its active gameplay should be cleaned up.
    private static void invokeEnded(MinecraftServer server, ActiveRandomEvent event,
                                    RandomEventRegistry.EndReason reason) {
        RandomEventRegistry.RandomEventHandler handler = RandomEventRegistry.handler(event.id());
        if (handler == null) return;
        try {
            handler.onEnded(server, event, reason);
        } catch (RuntimeException exception) {
            CrazyWorldProgression.LOGGER.error("Random event {} failed during onEnded", event.id(), exception);
        }
    }

    // Test all global blackout windows against one shared scheduler instant.
    private static boolean isBlackoutActive(Instant now) {
        return RandomEventManager.blackoutWindows().stream().anyMatch(window -> window.allows(now));
    }

    // Resolve and validate one active record before mutating its metadata.
    private static RandomEventData.StoredActiveEvent requireActive(RandomEventData data, Identifier eventId) {
        RandomEventData.StoredActiveEvent active = data.active(eventId.toString());
        if (active == null) throw new IllegalStateException("Random event '" + eventId + "' is not active");
        return active;
    }

    // Reject metadata values that cannot be represented safely and clearly in SavedData.
    private static void validateMetadata(String key, String value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Event metadata key must not be blank");
        if (value == null) throw new IllegalArgumentException("Event metadata value must not be null");
    }

    // Convert a stored record into an immutable public API snapshot.
    private static ActiveRandomEvent snapshot(Identifier eventId, RandomEventData.StoredActiveEvent stored) {
        return new ActiveRandomEvent(eventId, stored.sequence(), stored.startedEpochMillis(), stored.endsEpochMillis(),
                stored.startedGameTime(), stored.endsGameTime(), stored.metadata());
    }

    // Reconstruct an identifier previously written by this framework's validated configuration loader.
    private static Identifier parseStoredIdentifier(String value) {
        int separator = value.indexOf(':');
        if (separator < 1 || separator == value.length() - 1) {
            throw new IllegalStateException("Invalid stored random event id '" + value + "'");
        }
        return Identifier.fromNamespaceAndPath(value.substring(0, separator), value.substring(separator + 1));
    }

    // Add two positive scheduling values while saturating instead of wrapping into the past.
    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    // Multiply two positive scheduling values while saturating overflow.
    private static long saturatedMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /** Immutable public view of one active event and its content-owned persistent metadata. */
    public record ActiveRandomEvent(Identifier id, long sequence, long startedEpochMillis, long endsEpochMillis,
                                    long startedGameTime, long endsGameTime, Map<String, String> metadata) {
        // Freeze metadata so integrations cannot bypass the service's persistence methods.
        public ActiveRandomEvent {
            metadata = Map.copyOf(new LinkedHashMap<>(metadata));
        }
    }
}
