package ekuzo.crazyworldprogression.events;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent active instances and roll cursors that prevent rerolls after a restart. */
final class RandomEventData extends SavedData {
    private static final Codec<Map<String, StoredActiveEvent>> ACTIVE_CODEC =
            Codec.unboundedMap(Codec.STRING, StoredActiveEvent.CODEC);
    private static final Codec<Map<String, Long>> LONG_MAP_CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG);

    static final Codec<RandomEventData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ACTIVE_CODEC.optionalFieldOf("active_events", Map.of()).forGetter(data -> data.activeEvents),
            LONG_MAP_CODEC.optionalFieldOf("last_game_slots", Map.of()).forGetter(data -> data.lastGameSlots),
            LONG_MAP_CODEC.optionalFieldOf("next_real_rolls", Map.of()).forGetter(data -> data.nextRealRolls),
            Codec.LONG.optionalFieldOf("next_sequence", 1L).forGetter(data -> data.nextSequence)
    ).apply(instance, RandomEventData::new));

    static final SavedDataType<RandomEventData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("random_events"), RandomEventData::new, CODEC, null);

    private final Map<String, StoredActiveEvent> activeEvents = new LinkedHashMap<>();
    private final Map<String, Long> lastGameSlots = new HashMap<>();
    private final Map<String, Long> nextRealRolls = new HashMap<>();
    private long nextSequence = 1L;

    // Create empty random-event state for a new world.
    RandomEventData() {
    }

    // Copy decoded collections so later scheduler mutations never touch immutable codec defaults.
    private RandomEventData(Map<String, StoredActiveEvent> activeEvents, Map<String, Long> lastGameSlots,
                            Map<String, Long> nextRealRolls, long nextSequence) {
        this.activeEvents.putAll(activeEvents);
        this.lastGameSlots.putAll(lastGameSlots);
        this.nextRealRolls.putAll(nextRealRolls);
        this.nextSequence = Math.max(1L, nextSequence);
    }

    // Load the server-wide event state from overworld SavedData storage.
    static RandomEventData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Return one stored active instance or null when the event is inactive.
    StoredActiveEvent active(String eventId) {
        return activeEvents.get(eventId);
    }

    // Return an immutable active-instance snapshot for safe iteration during callbacks.
    Map<String, StoredActiveEvent> activeEvents() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(activeEvents));
    }

    // Insert or replace one active instance and schedule it for persistence.
    void putActive(String eventId, StoredActiveEvent event) {
        activeEvents.put(eventId, event);
        setDirty();
    }

    // Remove and return one active instance while marking changed state dirty.
    StoredActiveEvent removeActive(String eventId) {
        StoredActiveEvent removed = activeEvents.remove(eventId);
        if (removed != null) setDirty();
        return removed;
    }

    // Read the latest observed half-day slot, returning null for a never-evaluated event.
    Long lastGameSlot(String eventId) {
        return lastGameSlots.get(eventId);
    }

    // Advance one in-game event's persisted roll cursor.
    void setLastGameSlot(String eventId, long slot) {
        lastGameSlots.put(eventId, slot);
        setDirty();
    }

    // Read the next due wall-clock roll, returning null before its first initialization.
    Long nextRealRoll(String eventId) {
        return nextRealRolls.get(eventId);
    }

    // Persist the next wall-clock instant at which one event may roll.
    void setNextRealRoll(String eventId, long epochMillis) {
        nextRealRolls.put(eventId, epochMillis);
        setDirty();
    }

    // Allocate a persistent monotonically increasing number for distinguishing repeated instances.
    long allocateSequence() {
        long allocated = nextSequence;
        if (nextSequence < Long.MAX_VALUE) nextSequence++;
        setDirty();
        return allocated;
    }

    /** Serializable representation of one active event and its content-owned string metadata. */
    record StoredActiveEvent(long sequence, long startedEpochMillis, long endsEpochMillis,
                             long startedGameTime, long endsGameTime, Map<String, String> metadata) {
        static final Codec<StoredActiveEvent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("sequence").forGetter(StoredActiveEvent::sequence),
                Codec.LONG.fieldOf("started_epoch_millis").forGetter(StoredActiveEvent::startedEpochMillis),
                Codec.LONG.optionalFieldOf("ends_epoch_millis", -1L).forGetter(StoredActiveEvent::endsEpochMillis),
                Codec.LONG.fieldOf("started_game_time").forGetter(StoredActiveEvent::startedGameTime),
                Codec.LONG.optionalFieldOf("ends_game_time", -1L).forGetter(StoredActiveEvent::endsGameTime),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("metadata", Map.of())
                        .forGetter(StoredActiveEvent::metadata)
        ).apply(instance, StoredActiveEvent::new));

        // Freeze decoded or handler-produced metadata before exposing the stored record.
        StoredActiveEvent {
            metadata = Map.copyOf(metadata);
        }

        // Return a replacement record containing one added or updated metadata value.
        StoredActiveEvent withMetadata(String key, String value) {
            Map<String, String> updated = new LinkedHashMap<>(metadata);
            updated.put(key, value);
            return new StoredActiveEvent(sequence, startedEpochMillis, endsEpochMillis,
                    startedGameTime, endsGameTime, updated);
        }

        // Return a replacement record without one metadata key.
        StoredActiveEvent withoutMetadata(String key) {
            if (!metadata.containsKey(key)) return this;
            Map<String, String> updated = new LinkedHashMap<>(metadata);
            updated.remove(key);
            return new StoredActiveEvent(sequence, startedEpochMillis, endsEpochMillis,
                    startedGameTime, endsGameTime, updated);
        }
    }
}
