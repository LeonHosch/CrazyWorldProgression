package ekuzo.crazyworldprogression.events;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ekuzo.crazyworldprogression.events.RandomEventDefinition.DurationClock.GAME_TIME;
import static ekuzo.crazyworldprogression.events.RandomEventDefinition.DurationClock.REAL_TIME;

/** Loads random-event definitions and global blackout windows from registered YAML resources. */
public final class RandomEventManager {
    private static volatile Map<Identifier, RandomEventDefinition> definitions = Map.of();
    private static volatile List<RandomEventDefinition.TimeRestriction> blackoutWindows = List.of();

    // Prevent instantiation of the process-wide definition manager.
    private RandomEventManager() {
    }

    // Parse every registered source and publish the complete configuration only after all validation succeeds.
    public static void reload() {
        Map<Identifier, RandomEventDefinition> loadedDefinitions = new LinkedHashMap<>();
        List<RandomEventDefinition.TimeRestriction> loadedBlackouts = new ArrayList<>();
        for (RandomEventRegistry.EventSource source : RandomEventRegistry.sources()) {
            Path file = locateSource(source);
            SourceConfiguration loaded = readSource(file, source.namespace());
            for (RandomEventDefinition definition : loaded.definitions()) {
                if (loadedDefinitions.putIfAbsent(definition.id(), definition) != null) {
                    throw invalid(file, "duplicate random event id '" + definition.id() + "'");
                }
            }
            loadedBlackouts.addAll(loaded.blackoutWindows());
        }
        definitions = Collections.unmodifiableMap(new LinkedHashMap<>(loadedDefinitions));
        blackoutWindows = List.copyOf(loadedBlackouts);
        CrazyWorldProgression.LOGGER.info("Loaded {} random event definition(s) and {} blackout window(s) from {} source(s)",
                definitions.size(), blackoutWindows.size(), RandomEventRegistry.sources().size());
    }

    // Return one loaded event definition or null when the identifier is not configured.
    public static RandomEventDefinition definition(Identifier eventId) {
        return definitions.get(eventId);
    }

    // Return definitions in stable source and YAML order for deterministic scheduling.
    public static List<RandomEventDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    // Return every recurring window during which no random start may occur.
    public static List<RandomEventDefinition.TimeRestriction> blackoutWindows() {
        return blackoutWindows;
    }

    // Locate a dependent mod's bundled configuration in development and packaged environments.
    private static Path locateSource(RandomEventRegistry.EventSource source) {
        return FabricLoader.getInstance().getModContainer(source.modId())
                .flatMap(container -> container.findPath(source.resourcePath()))
                .orElseThrow(() -> new IllegalStateException("Missing random-event configuration '"
                        + source.resourcePath() + "' in mod " + source.modId()));
    }

    // Decode one source document into event definitions and blackout windows using its default time zone.
    private static SourceConfiguration readSource(Path file, String namespace) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        Object loaded;
        try (InputStream input = Files.newInputStream(file)) {
            loaded = new Yaml(new SafeConstructor(options)).load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read random-event configuration " + file, exception);
        }
        if (!(loaded instanceof Map<?, ?> rawRoot)) throw invalid(file, "the root must be a YAML mapping");
        Map<String, Object> root = stringMap(file, rawRoot, "root");
        ZoneId timezone = parseTimezone(file, optionalString(root, "timezone", "UTC"), "timezone");

        List<RandomEventDefinition.TimeRestriction> blackouts = new ArrayList<>();
        for (IndexedMap entry : mapList(file, root.get("blackout_windows"), "blackout_windows")) {
            blackouts.add(parseRestriction(file, entry.values(), timezone, entry.location()));
        }

        List<RandomEventDefinition> events = new ArrayList<>();
        for (IndexedMap entry : mapList(file, root.get("events"), "events")) {
            Map<String, Object> event = entry.values();
            Identifier id = parseIdentifier(file, namespace, requiredString(file, event, "id", entry.location()));
            Map<String, Object> trigger = requiredMap(file, event, "trigger", entry.location());
            Map<String, Object> duration = requiredMap(file, event, "duration", entry.location());
            RandomEventDefinition.TimeRestriction availability = event.get("availability") == null
                    ? RandomEventDefinition.TimeRestriction.unrestricted(timezone)
                    : parseRestriction(file, requiredMap(file, event, "availability", entry.location()),
                    timezone, entry.location() + ".availability");
            events.add(new RandomEventDefinition(id, parseTrigger(file, trigger, entry.location() + ".trigger"),
                    parseDuration(file, duration, entry.location() + ".duration"), availability));
        }
        return new SourceConfiguration(List.copyOf(events), List.copyOf(blackouts));
    }

    // Parse either an in-game phase roll or an interval-based real-time roll.
    private static RandomEventDefinition.Trigger parseTrigger(Path file, Map<String, Object> values, String location) {
        String type = requiredString(file, values, "type", location).toLowerCase(Locale.ROOT);
        double chance = probability(file, values.get("chance"), location + ".chance");
        return switch (type) {
            case "in_game_phase" -> new RandomEventDefinition.InGamePhaseTrigger(
                    parsePhase(file, requiredString(file, values, "phase", location), location + ".phase"), chance);
            case "real_time" -> new RandomEventDefinition.RealTimeTrigger(
                    positiveLong(file, values.get("interval_seconds"), location + ".interval_seconds"), chance);
            default -> throw invalid(file, location + ".type must be 'in_game_phase' or 'real_time'");
        };
    }

    // Parse an event duration expressed either in wall-clock seconds or monotonic game ticks.
    private static RandomEventDefinition.EventDuration parseDuration(
            Path file, Map<String, Object> values, String location) {
        String clock = requiredString(file, values, "clock", location).toLowerCase(Locale.ROOT);
        return switch (clock) {
            case "real_time" -> new RandomEventDefinition.EventDuration(
                    REAL_TIME, positiveLong(file, values.get("seconds"), location + ".seconds"));
            case "game_time" -> new RandomEventDefinition.EventDuration(
                    GAME_TIME, positiveLong(file, values.get("ticks"), location + ".ticks"));
            default -> throw invalid(file, location + ".clock must be 'real_time' or 'game_time'");
        };
    }

    // Convert a YAML phase name into the corresponding half of a Minecraft day.
    private static RandomEventDefinition.InGamePhase parsePhase(Path file, String value, String location) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "day" -> RandomEventDefinition.InGamePhase.DAY;
            case "night" -> RandomEventDefinition.InGamePhase.NIGHT;
            default -> throw invalid(file, location + " must be 'day' or 'night'");
        };
    }

    // Parse weekdays, local start/end times, and an optional per-window time-zone override.
    private static RandomEventDefinition.TimeRestriction parseRestriction(
            Path file, Map<String, Object> values, ZoneId defaultTimezone, String location) {
        ZoneId timezone = parseTimezone(file, optionalString(values, "timezone", defaultTimezone.getId()),
                location + ".timezone");
        Set<DayOfWeek> weekdays = parseWeekdays(file, values.get("weekdays"), location + ".weekdays");
        Object rawStart = values.get("start");
        Object rawEnd = values.get("end");
        if ((rawStart == null) != (rawEnd == null)) {
            throw invalid(file, location + " must provide both start and end, or neither for whole days");
        }
        LocalTime start = rawStart == null ? LocalTime.MIDNIGHT : parseTime(file, rawStart, location + ".start");
        LocalTime end = rawEnd == null ? LocalTime.MIDNIGHT : parseTime(file, rawEnd, location + ".end");
        return new RandomEventDefinition.TimeRestriction(weekdays, start, end, timezone);
    }

    // Accept omitted weekdays as every day or parse an explicit YAML list of English weekday names.
    private static Set<DayOfWeek> parseWeekdays(Path file, Object raw, String location) {
        if (raw == null) return Set.of(DayOfWeek.values());
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw invalid(file, location + " must be a non-empty YAML list");
        }
        Set<DayOfWeek> result = new LinkedHashSet<>();
        for (Object value : list) {
            if (!(value instanceof String text)) throw invalid(file, location + " entries must be weekday names");
            try {
                result.add(DayOfWeek.valueOf(text.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw invalid(file, location + " contains unknown weekday '" + text + "'");
            }
        }
        return Set.copyOf(result);
    }

    // Parse a quoted ISO local time such as 14:00 or 21:30:00.
    private static LocalTime parseTime(Path file, Object raw, String location) {
        if (!(raw instanceof String text)) throw invalid(file, location + " must be a quoted time such as '19:00'");
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException exception) {
            throw invalid(file, location + " must be an ISO local time such as '19:00'");
        }
    }

    // Parse an IANA zone ID such as UTC or Europe/Berlin with source context.
    private static ZoneId parseTimezone(Path file, String value, String location) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            throw invalid(file, location + " contains unknown time zone '" + value + "'");
        }
    }

    // Resolve an unqualified event ID against its source namespace while allowing explicit cross-mod IDs.
    private static Identifier parseIdentifier(Path file, String namespace, String value) {
        try {
            int separator = value.indexOf(':');
            return separator < 0 ? Identifier.fromNamespaceAndPath(namespace, value)
                    : Identifier.fromNamespaceAndPath(value.substring(0, separator), value.substring(separator + 1));
        } catch (RuntimeException exception) {
            throw invalid(file, "invalid event identifier '" + value + "'");
        }
    }

    // Parse a finite YAML probability in the inclusive range from zero through one.
    private static double probability(Path file, Object raw, String location) {
        if (!(raw instanceof Number number)) throw invalid(file, location + " must be a number between 0.0 and 1.0");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw invalid(file, location + " must be between 0.0 and 1.0");
        }
        return value;
    }

    // Parse one strictly positive whole-number interval or duration.
    private static long positiveLong(Path file, Object raw, String location) {
        if (!(raw instanceof Number number) || number.longValue() < 1L || number.doubleValue() != number.longValue()) {
            throw invalid(file, location + " must be a positive whole number");
        }
        return number.longValue();
    }

    // Read an optional list of mappings and include an index in every later validation error.
    private static List<IndexedMap> mapList(Path file, Object raw, String key) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw invalid(file, key + " must be a YAML list");
        List<IndexedMap> result = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof Map<?, ?> map)) throw invalid(file, key + "[" + index + "] must be a mapping");
            String location = key + "[" + index + "]";
            result.add(new IndexedMap(stringMap(file, map, location), location));
        }
        return List.copyOf(result);
    }

    // Read a required nested mapping from a parent YAML object.
    private static Map<String, Object> requiredMap(
            Path file, Map<String, Object> values, String key, String location) {
        Object raw = values.get(key);
        if (!(raw instanceof Map<?, ?> map)) throw invalid(file, location + "." + key + " must be a mapping");
        return stringMap(file, map, location + "." + key);
    }

    // Convert SnakeYAML's untyped mapping into a deterministic string-keyed map.
    private static Map<String, Object> stringMap(Path file, Map<?, ?> raw, String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(file, location + " contains a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    // Read and trim one mandatory non-empty YAML string.
    private static String requiredString(Path file, Map<String, Object> values, String key, String location) {
        Object raw = values.get(key);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw invalid(file, location + "." + key + " must be a non-empty string");
        }
        return text.trim();
    }

    // Return a trimmed optional string or the supplied fallback.
    private static String optionalString(Map<String, Object> values, String key, String fallback) {
        Object raw = values.get(key);
        return raw instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    // Prefix every configuration error with the owning resource filename.
    private static IllegalArgumentException invalid(Path file, String message) {
        return new IllegalArgumentException(file.getFileName() + ": " + message);
    }

    /** Complete validated contents of one registered configuration resource. */
    private record SourceConfiguration(List<RandomEventDefinition> definitions,
                                       List<RandomEventDefinition.TimeRestriction> blackoutWindows) {
    }

    /** Mapping paired with its YAML path for precise validation errors. */
    private record IndexedMap(Map<String, Object> values, String location) {
    }
}
