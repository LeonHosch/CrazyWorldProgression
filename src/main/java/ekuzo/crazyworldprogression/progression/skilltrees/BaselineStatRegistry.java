package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads always-on stat expressions and applies them separately from unlocked progression. */
public final class BaselineStatRegistry {
    private static final List<BaselineSource> SOURCES = new ArrayList<>();
    private static volatile Map<String, List<BaselineModifier>> loadedModifiers = Map.of();

    // Prevent instantiation of the process-wide baseline registry.
    private BaselineStatRegistry() {
    }

    // Register a dependent mod's bundled YAML profile for loading when the server starts.
    public static synchronized void registerSource(Identifier profileId, String modId, String resourcePath) {
        BaselineSource source = new BaselineSource(profileId, modId, resourcePath);
        if (SOURCES.stream().anyMatch(existing -> existing.profileId().equals(profileId))) {
            throw new IllegalArgumentException("Duplicate baseline profile id: " + profileId);
        }
        SOURCES.add(source);
    }

    // Load every registered profile and publish the complete immutable modifier set atomically.
    public static void reload() {
        Map<String, List<BaselineModifier>> loaded = new LinkedHashMap<>();
        for (BaselineSource source : sources()) {
            Path file = locateSource(source);
            for (BaselineModifier modifier : readProfile(file, source.profileId())) {
                loaded.computeIfAbsent(modifier.statKey(), ignored -> new ArrayList<>()).add(modifier);
            }
        }
        Map<String, List<BaselineModifier>> immutable = new LinkedHashMap<>();
        loaded.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        loadedModifiers = Map.copyOf(immutable);
        CrazyWorldProgression.LOGGER.info("Loaded {} baseline stat expression(s) from {} registered profile(s)",
                immutable.values().stream().mapToInt(List::size).sum(), sources().size());
    }

    // Apply all declared attribute baselines and remove obsolete entries from registered profiles.
    public static void apply(ServerPlayer player) {
        for (SkillStatRegistry.StatDefinition definition : SkillStatRegistry.definitions()) {
            if (definition.attribute() == null) continue;
            AttributeInstance instance = player.getAttribute(definition.attribute());
            if (instance == null) continue;
            for (BaselineSource source : sources()) {
                instance.removeModifier(modifierId(source.profileId(), definition.key()));
            }
            for (BaselineModifier modifier : modifiers(definition.key())) {
                instance.addTransientModifier(new AttributeModifier(
                        modifier.modifierId(), modifier.value(), modifier.operation()));
            }
        }
    }

    // Return the loaded baseline modifiers for one normalized skill-stat function.
    static List<BaselineModifier> modifiers(String functionName) {
        return loadedModifiers.getOrDefault(normalizeKey(functionName), List.of());
    }

    // Reproduce vanilla attribute operation ordering for baselines and one optional progression bonus.
    static double calculate(double base, List<BaselineModifier> baseline,
                            AttributeModifier.Operation syntheticOperation, double syntheticAmount) {
        double value = base;
        for (BaselineModifier modifier : baseline) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) value += modifier.value();
        }
        if (syntheticOperation == AttributeModifier.Operation.ADD_VALUE) value += syntheticAmount;
        for (BaselineModifier modifier : baseline) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                value += base * modifier.value();
            }
        }
        if (syntheticOperation == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) value += base * syntheticAmount;
        for (BaselineModifier modifier : baseline) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                value *= 1.0 + modifier.value();
            }
        }
        if (syntheticOperation == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) value *= 1.0 + syntheticAmount;
        return value;
    }

    // Return a stable source snapshot after dependent mod initializers register their profiles.
    public static synchronized List<BaselineSource> sources() {
        return List.copyOf(SOURCES);
    }

    // Count loaded expressions across every stat key for framework diagnostics.
    public static int loadedModifierCount() {
        return loadedModifiers.values().stream().mapToInt(List::size).sum();
    }

    // Return the normalized stat keys currently affected by at least one baseline profile.
    public static Set<String> loadedStatKeys() {
        return Set.copyOf(loadedModifiers.keySet());
    }

    // Ask Fabric Loader for one profile resource in development and packaged environments.
    private static Path locateSource(BaselineSource source) {
        return FabricLoader.getInstance().getModContainer(source.modId())
                .flatMap(container -> container.findPath(source.resourcePath()))
                .orElseThrow(() -> new IllegalStateException("Missing baseline profile '"
                        + source.resourcePath() + "' in mod " + source.modId()));
    }

    // Parse baseline entries through the same expression registry used by skill-tree nodes.
    private static List<BaselineModifier> readProfile(Path file, Identifier profileId) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        Object loaded;
        try (InputStream input = Files.newInputStream(file)) {
            loaded = new Yaml(new SafeConstructor(options)).load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read baseline profile " + file, exception);
        }
        if (!(loaded instanceof Map<?, ?> rawRoot)) throw invalid(file, "the root must be a YAML mapping");
        Map<String, Object> root = stringMap(file, rawRoot, "root");
        Object rawStats = root.get("baseline_stats");
        if (!(rawStats instanceof List<?> stats)) throw invalid(file, "baseline_stats must be a YAML list");
        List<BaselineModifier> result = new ArrayList<>();
        Set<String> functions = new HashSet<>();
        for (int index = 0; index < stats.size(); index++) {
            Object entry = stats.get(index);
            if (!(entry instanceof String expression) || expression.isBlank()) {
                throw invalid(file, "baseline_stats[" + index + "] must be a non-empty stat expression");
            }
            SkillStatRegistry.ParsedStat parsed;
            try {
                parsed = SkillStatRegistry.parse(expression);
            } catch (IllegalArgumentException exception) {
                throw invalid(file, "baseline_stats[" + index + "]: " + exception.getMessage());
            }
            SkillStatRegistry.StatDefinition definition = SkillStatRegistry.definition(parsed.functionName());
            if (definition == null) {
                throw invalid(file, "stat function '" + parsed.functionName()
                        + "' has a custom handler but no reusable baseline definition");
            }
            if (!functions.add(definition.key())) {
                throw invalid(file, "duplicate baseline stat function '" + definition.functionName() + "'");
            }
            double scaledValue = parsed.value() * definition.scale();
            if ((definition.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                    || definition.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                    && scaledValue < -1.0) {
                throw invalid(file, definition.functionName() + " must not reduce the starting percentage below 0%");
            }
            result.add(new BaselineModifier(definition.key(), definition.operation(), scaledValue,
                    modifierId(profileId, definition.key())));
        }
        return List.copyOf(result);
    }

    // Convert an untyped YAML mapping into a string-keyed insertion-ordered map.
    private static Map<String, Object> stringMap(Path file, Map<?, ?> raw, String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(file, location + " contains a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    // Normalize stat functions for case-insensitive lookup and stable modifier paths.
    private static String normalizeKey(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("Baseline stat function must not be blank");
        }
        return functionName.trim().toLowerCase(Locale.ROOT);
    }

    // Create a stable modifier ID from the contributing profile and shared stat function.
    private static Identifier modifierId(Identifier profileId, String functionName) {
        return Identifier.fromNamespaceAndPath(profileId.getNamespace(),
                "baseline/" + profileId.getPath() + "/" + normalizeKey(functionName));
    }

    // Prefix profile validation failures with their resource filename.
    private static IllegalArgumentException invalid(Path file, String message) {
        return new IllegalArgumentException(file.getFileName() + ": " + message);
    }

    public record BaselineSource(Identifier profileId, String modId, String resourcePath) {
        // Reject incomplete source metadata at registration instead of during server startup.
        public BaselineSource {
            if (profileId == null || modId == null || modId.isBlank()
                    || resourcePath == null || resourcePath.isBlank()) {
                throw new IllegalArgumentException("Baseline source values must not be blank");
            }
        }
    }

    record BaselineModifier(String statKey, AttributeModifier.Operation operation,
                            double value, Identifier modifierId) {
    }
}
