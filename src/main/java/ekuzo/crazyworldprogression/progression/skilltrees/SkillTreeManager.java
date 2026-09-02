package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillNode;
import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillTreeType;

public final class SkillTreeManager {
    private static volatile List<SkillTreeDefinition> skillTrees = List.of();

    // Prevent instantiation of the process-wide authoritative definition manager.
    private SkillTreeManager() {
    }

    // Load registered sources during startup and convert checked I/O failures into fatal initialization errors.
    public static void initialize() {
        try {
            reload();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize skill trees", exception);
        }
    }

    // Parse every registered YAML source and replace the active immutable tree set only after validation succeeds.
    public static void reload() throws IOException {
        List<SkillTreeDefinition> loaded = new ArrayList<>();
        for (SkillTreeRegistry.TreeSource source : SkillTreeRegistry.sources()) {
            Path directory = locateSource(source);
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(SkillTreeManager::isYamlFile).sorted().toList()) {
                    loaded.add(readTree(file, source.namespace()));
                }
            }
        }
        Set<Identifier> treeIds = new HashSet<>();
        for (SkillTreeDefinition tree : loaded) {
            if (!treeIds.add(tree.id())) throw new IllegalArgumentException("Duplicate skill-tree id: " + tree.id());
        }
        loaded.sort(Comparator
                .comparing(SkillTreeDefinition::type)
                .thenComparingInt(SkillTreeDefinition::priority)
                .thenComparing(tree -> tree.id().getPath(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(tree -> tree.id().toString()));
        skillTrees = List.copyOf(loaded);
        SkillStatRegistry.clearCachedTotals();
        CrazyWorldProgression.LOGGER.info("Loaded {} skill-tree tab(s) from {} registered source(s)",
                loaded.size(), SkillTreeRegistry.sources().size());
    }

    // Return the immutable definitions currently used for snapshots, purchases, and stat refreshes.
    public static List<SkillTreeDefinition> getSkillTrees() {
        return skillTrees;
    }

    // Find a tree by the complete namespaced ID received from the client.
    public static SkillTreeDefinition findTree(String treeId) {
        return skillTrees.stream().filter(tree -> tree.id().toString().equals(treeId)).findFirst().orElse(null);
    }

    // Ask Fabric Loader for the registered mod's resource directory in development or packaged environments.
    private static Path locateSource(SkillTreeRegistry.TreeSource source) {
        return FabricLoader.getInstance().getModContainer(source.modId())
                .flatMap(container -> container.findPath(source.resourceDirectory()))
                .orElseThrow(() -> new IllegalStateException("Missing skill-tree resource directory '"
                        + source.resourceDirectory() + "' in mod " + source.modId()));
    }

    // Accept regular .yml and .yaml files while ignoring directories and unrelated resources.
    private static boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    // Parse one YAML document, apply defaults, normalize IDs, and validate its complete dependency graph.
    private static SkillTreeDefinition readTree(Path file, String namespace) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        Object loaded;
        try (InputStream input = Files.newInputStream(file)) {
            loaded = new Yaml(new SafeConstructor(options)).load(input);
        }
        if (!(loaded instanceof Map<?, ?> rawRoot)) throw invalid(file, "the root must be a YAML mapping");
        Map<String, Object> root = stringMap(file, rawRoot, "root");
        Identifier treeId = Identifier.fromNamespaceAndPath(namespace, normalizeId(stripExtension(file.getFileName().toString())));
        String treeName = optionalString(root, "name", humanize(treeId.getPath()));
        String treeGermanName = optionalString(root, "gname", treeName);
        SkillTreeType type = parseType(file, requiredString(file, root, "skilltree"));
        int priority = integer(file, root, "priority", 0);
        String treeIcon = optionalString(root, "icon", type == SkillTreeType.GLOBAL
                ? "minecraft:golden_helmet" : "minecraft:player_head");
        List<Object> rawSkills = requiredList(file, root, "skills");
        List<SkillNode> skills = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < rawSkills.size(); index++) {
            if (!(rawSkills.get(index) instanceof Map<?, ?> rawSkill)) {
                throw invalid(file, "skills[" + index + "] must be a mapping");
            }
            Map<String, Object> skill = stringMap(file, rawSkill, "skills[" + index + "]");
            String id = normalizeId(requiredString(file, skill, "id"));
            if (!ids.add(id)) throw invalid(file, "duplicate skill id '" + id + "'");
            String name = requiredString(file, skill, "name");
            String description = optionalString(skill, "description", "");
            skills.add(new SkillNode(id, name, optionalString(skill, "gname", name), description,
                    optionalString(skill, "beschreibung", description), optionalString(skill, "icon", "minecraft:book"),
                    stringList(file, skill.get("previous"), "previous"), nonNegativeInt(file, skill, "following"),
                    stringList(file, skill.get("stats"), "stats"), readCosts(file, namespace, skill.get("costs"))));
        }
        validateTree(file, skills);
        return new SkillTreeDefinition(treeId, treeName, treeGermanName, treeIcon, type, priority, List.copyOf(skills));
    }

    // Validate predecessor references, stat expressions, and cycles after every node has been parsed.
    private static void validateTree(Path file, List<SkillNode> skills) {
        Map<String, SkillNode> byId = new LinkedHashMap<>();
        skills.forEach(skill -> byId.put(skill.id(), skill));
        for (SkillNode skill : skills) {
            for (String previous : skill.previous()) {
                if (!byId.containsKey(previous)) throw invalid(file, "skill id '" + skill.id()
                        + "' references unknown previous skill id '" + previous + "'");
                if (previous.equals(skill.id())) throw invalid(file, "skill id '" + skill.id() + "' cannot depend on itself");
            }
            for (String stat : skill.stats()) {
                try { SkillStatRegistry.parse(stat); }
                catch (IllegalArgumentException exception) { throw invalid(file, "skill id '" + skill.id() + "': " + exception.getMessage()); }
            }
        }
        Map<String, VisitState> states = new HashMap<>();
        for (SkillNode skill : skills) visit(file, skill, byId, states);
    }

    // Depth-first traversal that detects a dependency cycle through visiting and visited markers.
    private static void visit(Path file, SkillNode skill, Map<String, SkillNode> byId, Map<String, VisitState> states) {
        VisitState state = states.get(skill.id());
        if (state == VisitState.VISITED) return;
        if (state == VisitState.VISITING) throw invalid(file, "dependency cycle detected at skill id '" + skill.id() + "'");
        states.put(skill.id(), VisitState.VISITING);
        for (String previous : skill.previous()) visit(file, byId.get(previous), byId, states);
        states.put(skill.id(), VisitState.VISITED);
    }

    // Convert an open YAML costs mapping into registered namespaced currency IDs and non-negative amounts.
    private static Map<Identifier, Long> readCosts(Path file, String namespace, Object rawCosts) {
        if (rawCosts == null) return Map.of();
        if (!(rawCosts instanceof Map<?, ?> rawMap)) throw invalid(file, "costs must be a mapping");
        Map<String, Object> values = stringMap(file, rawMap, "costs");
        Map<Identifier, Long> costs = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            Identifier id = parseIdentifier(file, namespace, key);
            if (CurrencyRegistry.get(id) == null) throw invalid(file, "unknown currency '" + id + "'");
            costs.put(id, nonNegativeLong(file, key, value));
        });
        return Map.copyOf(costs);
    }

    // Resolve local cost keys against the tree namespace while retaining explicit cross-mod identifiers.
    private static Identifier parseIdentifier(Path file, String namespace, String value) {
        try {
            int separator = value.indexOf(':');
            return separator < 0 ? Identifier.fromNamespaceAndPath(namespace, value)
                    : Identifier.fromNamespaceAndPath(value.substring(0, separator), value.substring(separator + 1));
        } catch (RuntimeException exception) {
            throw invalid(file, "invalid identifier '" + value + "'");
        }
    }

    // Parse one required whole-number cost and reject negative or fractional YAML numbers.
    private static long nonNegativeLong(Path file, String key, Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0L || number.doubleValue() != number.longValue()) {
            throw invalid(file, key + " must be a non-negative whole number");
        }
        return number.longValue();
    }

    // Parse an optional branch limit, using zero when the YAML omits an unrestricted following value.
    private static int nonNegativeInt(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return 0;
        if (!(value instanceof Number number) || number.longValue() < 0L || number.longValue() > Integer.MAX_VALUE
                || number.doubleValue() != number.longValue()) throw invalid(file, key + " must be a non-negative integer");
        return number.intValue();
    }

    // Parse an optional signed whole-number field such as tree ordering priority.
    private static int integer(Path file, Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() < Integer.MIN_VALUE
                || number.longValue() > Integer.MAX_VALUE || number.doubleValue() != number.longValue()) {
            throw invalid(file, key + " must be an integer");
        }
        return number.intValue();
    }

    // Normalize an optional scalar, list, or "none" marker into an immutable list of trimmed strings.
    private static List<String> stringList(Path file, Object value, String key) {
        if (value == null || "none".equalsIgnoreCase(String.valueOf(value).trim())) return List.of();
        if (value instanceof String text) return List.of(text.trim());
        if (!(value instanceof List<?> values)) throw invalid(file, key + " must be 'none', a string, or a YAML list");
        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (!(entry instanceof String text) || text.isBlank()) throw invalid(file, key + " entries must be non-empty strings");
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    // Convert SnakeYAML's untyped map into a string-keyed insertion-ordered map with contextual errors.
    private static Map<String, Object> stringMap(Path file, Map<?, ?> raw, String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(file, location + " contains a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    // Read and trim a mandatory non-empty string property.
    private static String requiredString(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw invalid(file, key + " must be a non-empty string");
        return text.trim();
    }

    // Read a mandatory YAML sequence into a mutable list used during parsing.
    private static List<Object> requiredList(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) throw invalid(file, key + " must be a YAML list");
        return new ArrayList<>(list);
    }

    // Read an optional non-blank string or return the caller-provided default.
    private static String optionalString(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    // Translate the YAML scope keyword into the framework's persistence and purchase scope.
    private static SkillTreeType parseType(Path file, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "global" -> SkillTreeType.GLOBAL;
            case "personal" -> SkillTreeType.PERSONAL;
            default -> throw invalid(file, "skilltree must be either 'global' or 'personal'");
        };
    }

    // Turn a configured name into a stable lowercase path suitable for IDs and save keys.
    private static String normalizeId(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Skill-tree and skill ids must contain a letter or number");
        return normalized;
    }

    // Remove only the final filename extension before deriving the tree path.
    private static String stripExtension(String fileName) {
        int extension = fileName.lastIndexOf('.');
        return extension < 0 ? fileName : fileName.substring(0, extension);
    }

    // Produce a readable title fallback from a normalized underscore-or-hyphen-separated ID.
    private static String humanize(String id) {
        return Stream.of(id.split("[_-]+")).filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .reduce((left, right) -> left + " " + right).orElse(id);
    }

    // Prefix parser and validation failures with their source filename for actionable startup logs.
    private static IllegalArgumentException invalid(Path file, String message) {
        return new IllegalArgumentException(file.getFileName() + ": " + message);
    }

    private enum VisitState { VISITING, VISITED }
}
