package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillCosts;
import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillNode;
import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillTreeType;

public final class SkillTreeManager {
    private static final String RESOURCE_DIRECTORY = "default-skilltrees";
    private static volatile List<SkillTreeDefinition> skillTrees = List.of();

    // Prevent this static configuration manager from being instantiated.
    private SkillTreeManager() {
    }

    // Load every tracked or packaged YAML resource as a separate skill-tree tab.
    public static void initialize() {
        try {
            reload();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize skill trees", exception);
        }
    }

    // Reload all resource tabs while keeping the previous valid set if parsing fails.
    public static void reload() throws IOException {
        Path skillTreeDirectory = locateSkillTreeDirectory();
        List<SkillTreeDefinition> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(skillTreeDirectory)) {
            for (Path file : files.filter(SkillTreeManager::isYamlFile).sorted().toList()) {
                loaded.add(readTree(file));
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalArgumentException("At least one .yml skill tree is required");
        }

        Set<String> treeIds = new HashSet<>();
        for (SkillTreeDefinition tree : loaded) {
            if (!treeIds.add(tree.id())) {
                throw new IllegalArgumentException("Duplicate skill-tree id: " + tree.id());
            }
        }
        skillTrees = List.copyOf(loaded);
        CrazyWorldProgression.LOGGER.info("Loaded {} skill-tree tab(s) from {}", loaded.size(), skillTreeDirectory);
    }

    // Return the immutable collection of currently loaded tabs.
    public static List<SkillTreeDefinition> getSkillTrees() {
        return skillTrees;
    }

    // Find one loaded tab by its filename-derived id.
    public static SkillTreeDefinition findTree(String treeId) {
        return skillTrees.stream().filter(tree -> tree.id().equals(treeId)).findFirst().orElse(null);
    }

    // Prefer source resources in Loom development runs and packaged resources in released JARs.
    private static Path locateSkillTreeDirectory() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment()) {
            Path gameDirectory = loader.getGameDir().toAbsolutePath().normalize();
            Path projectDirectory = gameDirectory.getParent();
            if (projectDirectory != null) {
                Path sourceDirectory = projectDirectory
                        .resolve("src")
                        .resolve("main")
                        .resolve("resources")
                        .resolve(RESOURCE_DIRECTORY);
                if (Files.isDirectory(sourceDirectory)) {
                    return sourceDirectory;
                }
            }
        }

        return loader.getModContainer(CrazyWorldProgression.MOD_ID)
                .flatMap(container -> container.findPath(RESOURCE_DIRECTORY))
                .orElseThrow(() -> new IllegalStateException(
                        "Missing packaged skill-tree resource directory '" + RESOURCE_DIRECTORY + "'"
                ));
    }

    // Identify supported YAML files while ignoring editor backups and unrelated files.
    private static boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    // Parse and validate a complete skill-tree tab from one YAML file.
    private static SkillTreeDefinition readTree(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded;
        try (InputStream input = Files.newInputStream(file)) {
            loaded = yaml.load(input);
        }
        if (!(loaded instanceof Map<?, ?> rawRoot)) {
            
            throw invalid(file, "the root must be a YAML mapping");
        }

        Map<String, Object> root = stringMap(file, rawRoot, "root");
        String treeId = normalizeId(stripExtension(file.getFileName().toString()));
        String treeName = optionalString(root, "name", humanize(treeId));
        String treeGermanName = optionalString(root, "gname", treeName);
        SkillTreeType type = parseType(file, requiredString(file, root, "skilltree"));
        String treeIcon = optionalString(root, "icon", defaultTreeIcon(type));
        List<Object> rawSkills = requiredList(file, root, "skills");
        List<SkillNode> skills = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();

        for (int index = 0; index < rawSkills.size(); index++) {
            if (!(rawSkills.get(index) instanceof Map<?, ?> rawSkill)) {
                throw invalid(file, "skills[" + index + "] must be a mapping");
            }
            Map<String, Object> skill = stringMap(file, rawSkill, "skills[" + index + "]");
            String name = requiredString(file, skill, "name");
            String description = optionalString(skill, "description", "");
            String id = normalizeId(optionalString(skill, "id", name));
            if (!ids.add(id)) {
                throw invalid(file, "duplicate skill id '" + id + "'");
            }
            if (!names.add(name)) {
                throw invalid(file, "duplicate skill name '" + name + "'");
            }
            skills.add(new SkillNode(
                    id,
                    name,
                    optionalString(skill, "gname", name),
                    description,
                    optionalString(skill, "beschreibung", description),
                    optionalString(skill, "icon", "minecraft:book"),
                    stringList(file, skill.get("previous"), "previous"),
                    nonNegativeInt(file, skill, "following"),
                    stringList(file, skill.get("stats"), "stats"),
                    readCosts(file, skill.get("costs"))
            ));
        }

        validateTree(file, type, skills);
        return new SkillTreeDefinition(treeId, treeName, treeGermanName, treeIcon, type, List.copyOf(skills));
    }

    // Choose a recognizable vanilla tab icon when the YAML does not configure one.
    private static String defaultTreeIcon(SkillTreeType type) {
        return type == SkillTreeType.GLOBAL ? "minecraft:golden_helmet" : "minecraft:player_head";
    }

    // Validate dependencies, costs, stat expressions, and dependency cycles before gameplay starts.
    private static void validateTree(Path file, SkillTreeType type, List<SkillNode> skills) {
        Map<String, SkillNode> byName = new LinkedHashMap<>();
        skills.forEach(skill -> byName.put(skill.name(), skill));
        for (SkillNode skill : skills) {
            for (String previous : skill.previous()) {
                if (!byName.containsKey(previous)) {
                    throw invalid(file, "skill '" + skill.name() + "' references unknown previous skill '" + previous + "'");
                }
                if (previous.equals(skill.name())) {
                    throw invalid(file, "skill '" + skill.name() + "' cannot depend on itself");
                }
            }
            for (String stat : skill.stats()) {
                try {
                    SkillStatRegistry.parse(stat);
                } catch (IllegalArgumentException exception) {
                    throw invalid(file, "skill '" + skill.name() + "': " + exception.getMessage());
                }
            }
            validateAllowedCosts(file, type, skill);
        }

        Map<String, VisitState> states = new HashMap<>();
        for (SkillNode skill : skills) {
            visit(file, skill, byName, states);
        }
    }

    // Reject currencies that are not permitted for a tab's global or personal scope.
    private static void validateAllowedCosts(Path file, SkillTreeType type, SkillNode skill) {
        SkillCosts costs = skill.costs();
        if (type == SkillTreeType.GLOBAL && (costs.echelonPoints() > 0L || costs.powerfulSouls() > 0L)) {
            throw invalid(file, "global skill '" + skill.name() + "' may only cost Kingdom Points and Fakhrul Currency");
        }
        if (type == SkillTreeType.PERSONAL && costs.kingdomPoints() > 0L) {
            throw invalid(file, "personal skill '" + skill.name() + "' may not cost Kingdom Points");
        }
    }

    // Traverse dependencies to detect cycles that would make skills permanently unreachable.
    private static void visit(
            Path file,
            SkillNode skill,
            Map<String, SkillNode> byName,
            Map<String, VisitState> states
    ) {
        VisitState state = states.get(skill.name());
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            throw invalid(file, "dependency cycle detected at skill '" + skill.name() + "'");
        }
        states.put(skill.name(), VisitState.VISITING);
        for (String previous : skill.previous()) {
            visit(file, byName.get(previous), byName, states);
        }
        states.put(skill.name(), VisitState.VISITED);
    }

    // Parse the four optional currency fields from a node's costs mapping.
    private static SkillCosts readCosts(Path file, Object rawCosts) {
        if (rawCosts == null) {
            return SkillCosts.FREE;
        }
        if (!(rawCosts instanceof Map<?, ?> rawMap)) {
            throw invalid(file, "costs must be a mapping");
        }
        Map<String, Object> costs = stringMap(file, rawMap, "costs");
        return new SkillCosts(
                nonNegativeLong(file, costs, "kingdomPoints"),
                nonNegativeLong(file, costs, "echelonPoints"),
                nonNegativeLong(file, costs, "fakhrulCurrency"),
                nonNegativeLong(file, costs, "powerfulSouls")
        );
    }

    // Read one optional non-negative whole-number cost.
    private static long nonNegativeLong(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return 0L;
        }
        if (!(value instanceof Number number) || number.longValue() < 0L || number.doubleValue() != number.longValue()) {
            throw invalid(file, key + " must be a non-negative whole number");
        }
        return number.longValue();
    }

    // Read one optional non-negative integer and use zero for an unrestricted branch count.
    private static int nonNegativeInt(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return 0;
        }
        if (!(value instanceof Number number)
                || number.longValue() < 0L
                || number.longValue() > Integer.MAX_VALUE
                || number.doubleValue() != number.longValue()) {
            throw invalid(file, key + " must be a non-negative integer");
        }
        return number.intValue();
    }

    // Convert a YAML scalar or list into a trimmed immutable string list.
    private static List<String> stringList(Path file, Object value, String key) {
        if (value == null || "none".equalsIgnoreCase(String.valueOf(value).trim())) {
            return List.of();
        }
        if (value instanceof String text) {
            return List.of(text.trim());
        }
        if (!(value instanceof List<?> values)) {
            throw invalid(file, key + " must be 'none', a string, or a YAML list");
        }
        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw invalid(file, key + " entries must be non-empty strings");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    // Convert an untyped YAML mapping into string-keyed values with helpful errors.
    private static Map<String, Object> stringMap(Path file, Map<?, ?> raw, String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid(file, location + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    // Read a required non-empty string from a YAML mapping.
    private static String requiredString(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(file, key + " must be a non-empty string");
        }
        return text.trim();
    }

    // Read a required YAML list from a mapping.
    private static List<Object> requiredList(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) {
            throw invalid(file, key + " must be a YAML list");
        }
        return new ArrayList<>(list);
    }

    // Read an optional string and use a default when it is absent or blank.
    private static String optionalString(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    // Translate the YAML scope keyword into the internal tab type.
    private static SkillTreeType parseType(Path file, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "global" -> SkillTreeType.GLOBAL;
            case "personal" -> SkillTreeType.PERSONAL;
            default -> throw invalid(file, "skilltree must be either 'global' or 'personal'");
        };
    }

    // Turn a filename or configured id into a safe stable save-data identifier.
    private static String normalizeId(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Skill-tree and skill ids must contain a letter or number");
        }
        return normalized;
    }

    // Remove the final extension from a configuration filename.
    private static String stripExtension(String fileName) {
        int extension = fileName.lastIndexOf('.');
        return extension < 0 ? fileName : fileName.substring(0, extension);
    }

    // Produce a readable fallback tab title from a normalized filename.
    private static String humanize(String id) {
        return Stream.of(id.split("[_-]+"))
                .filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .reduce((left, right) -> left + " " + right)
                .orElse(id);
    }

    // Attach the source filename to a configuration validation error.
    private static IllegalArgumentException invalid(Path file, String message) {
        return new IllegalArgumentException(file.getFileName() + ": " + message);
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
