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

    private SkillTreeManager() {
    }

    public static void initialize() {
        try {
            reload();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize skill trees", exception);
        }
    }

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
        skillTrees = List.copyOf(loaded);
        CrazyWorldProgression.LOGGER.info("Loaded {} skill-tree tab(s) from {} registered source(s)",
                loaded.size(), SkillTreeRegistry.sources().size());
    }

    public static List<SkillTreeDefinition> getSkillTrees() {
        return skillTrees;
    }

    public static SkillTreeDefinition findTree(String treeId) {
        return skillTrees.stream().filter(tree -> tree.id().toString().equals(treeId)).findFirst().orElse(null);
    }

    private static Path locateSource(SkillTreeRegistry.TreeSource source) {
        return FabricLoader.getInstance().getModContainer(source.modId())
                .flatMap(container -> container.findPath(source.resourceDirectory()))
                .orElseThrow(() -> new IllegalStateException("Missing skill-tree resource directory '"
                        + source.resourceDirectory() + "' in mod " + source.modId()));
    }

    private static boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

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
        String treeIcon = optionalString(root, "icon", type == SkillTreeType.GLOBAL
                ? "minecraft:golden_helmet" : "minecraft:player_head");
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
            if (!ids.add(id)) throw invalid(file, "duplicate skill id '" + id + "'");
            if (!names.add(name)) throw invalid(file, "duplicate skill name '" + name + "'");
            skills.add(new SkillNode(id, name, optionalString(skill, "gname", name), description,
                    optionalString(skill, "beschreibung", description), optionalString(skill, "icon", "minecraft:book"),
                    stringList(file, skill.get("previous"), "previous"), nonNegativeInt(file, skill, "following"),
                    stringList(file, skill.get("stats"), "stats"), readCosts(file, namespace, skill.get("costs"))));
        }
        validateTree(file, skills);
        return new SkillTreeDefinition(treeId, treeName, treeGermanName, treeIcon, type, List.copyOf(skills));
    }

    private static void validateTree(Path file, List<SkillNode> skills) {
        Map<String, SkillNode> byName = new LinkedHashMap<>();
        skills.forEach(skill -> byName.put(skill.name(), skill));
        for (SkillNode skill : skills) {
            for (String previous : skill.previous()) {
                if (!byName.containsKey(previous)) throw invalid(file, "skill '" + skill.name()
                        + "' references unknown previous skill '" + previous + "'");
                if (previous.equals(skill.name())) throw invalid(file, "skill '" + skill.name() + "' cannot depend on itself");
            }
            for (String stat : skill.stats()) {
                try { SkillStatRegistry.parse(stat); }
                catch (IllegalArgumentException exception) { throw invalid(file, "skill '" + skill.name() + "': " + exception.getMessage()); }
            }
        }
        Map<String, VisitState> states = new HashMap<>();
        for (SkillNode skill : skills) visit(file, skill, byName, states);
    }

    private static void visit(Path file, SkillNode skill, Map<String, SkillNode> byName, Map<String, VisitState> states) {
        VisitState state = states.get(skill.name());
        if (state == VisitState.VISITED) return;
        if (state == VisitState.VISITING) throw invalid(file, "dependency cycle detected at skill '" + skill.name() + "'");
        states.put(skill.name(), VisitState.VISITING);
        for (String previous : skill.previous()) visit(file, byName.get(previous), byName, states);
        states.put(skill.name(), VisitState.VISITED);
    }

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

    private static Identifier parseIdentifier(Path file, String namespace, String value) {
        try {
            int separator = value.indexOf(':');
            return separator < 0 ? Identifier.fromNamespaceAndPath(namespace, value)
                    : Identifier.fromNamespaceAndPath(value.substring(0, separator), value.substring(separator + 1));
        } catch (RuntimeException exception) {
            throw invalid(file, "invalid identifier '" + value + "'");
        }
    }

    private static long nonNegativeLong(Path file, String key, Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0L || number.doubleValue() != number.longValue()) {
            throw invalid(file, key + " must be a non-negative whole number");
        }
        return number.longValue();
    }

    private static int nonNegativeInt(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return 0;
        if (!(value instanceof Number number) || number.longValue() < 0L || number.longValue() > Integer.MAX_VALUE
                || number.doubleValue() != number.longValue()) throw invalid(file, key + " must be a non-negative integer");
        return number.intValue();
    }

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

    private static Map<String, Object> stringMap(Path file, Map<?, ?> raw, String location) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(file, location + " contains a non-string key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String requiredString(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw invalid(file, key + " must be a non-empty string");
        return text.trim();
    }

    private static List<Object> requiredList(Path file, Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) throw invalid(file, key + " must be a YAML list");
        return new ArrayList<>(list);
    }

    private static String optionalString(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    private static SkillTreeType parseType(Path file, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "global" -> SkillTreeType.GLOBAL;
            case "personal" -> SkillTreeType.PERSONAL;
            default -> throw invalid(file, "skilltree must be either 'global' or 'personal'");
        };
    }

    private static String normalizeId(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) throw new IllegalArgumentException("Skill-tree and skill ids must contain a letter or number");
        return normalized;
    }

    private static String stripExtension(String fileName) {
        int extension = fileName.lastIndexOf('.');
        return extension < 0 ? fileName : fileName.substring(0, extension);
    }

    private static String humanize(String id) {
        return Stream.of(id.split("[_-]+")).filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .reduce((left, right) -> left + " " + right).orElse(id);
    }

    private static IllegalArgumentException invalid(Path file, String message) {
        return new IllegalArgumentException(file.getFileName() + ": " + message);
    }

    private enum VisitState { VISITING, VISITED }
}
