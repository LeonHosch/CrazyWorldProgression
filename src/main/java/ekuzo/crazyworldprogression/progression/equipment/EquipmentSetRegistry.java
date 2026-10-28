package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Equipment sets: wearing enough pieces of a set (armor and accessories) adds its skill stats (the same functions
 * skill trees use, such as addMovementSpeed(20)) to the player's totals. A set can have several bonuses that switch
 * on at different piece counts; they add up. Content mods register a resource directory of YAML files:
 *
 * <pre>
 * name: Wanderer's Set
 * pieces: [mymod:wanderer_helmet, mymod:wanderer_ring, ...]
 * bonuses:
 *   - pieces: 2
 *     stats: [addMovementSpeed(20)]
 *   - pieces: 4
 *     stats: [addMiningSpeed(500)]
 * </pre>
 *
 * The older single-bonus form ({@code required: 4} plus {@code stats:}) still works.
 *
 * The file name is the set ID inside the registering namespace.
 */
public final class EquipmentSetRegistry {
    private static final List<Source> SOURCES = new ArrayList<>();
    private static volatile List<EquipmentSet> sets;

    private EquipmentSetRegistry() { }

    /** Register a mod's resource directory of equipment-set YAML files. */
    public static synchronized void registerSource(String namespace, String modId, String resourceDirectory) {
        Source source = new Source(namespace, modId, resourceDirectory);
        if (SOURCES.contains(source)) throw new IllegalArgumentException("Duplicate equipment-set source: " + source);
        SOURCES.add(source);
        sets = null;
    }

    public static synchronized List<Source> sources() { return List.copyOf(SOURCES); }

    /** Every loaded set; loads the registered sources the first time (the client reads them from the mod jars). */
    public static List<EquipmentSet> sets() {
        List<EquipmentSet> current = sets;
        if (current == null) {
            synchronized (EquipmentSetRegistry.class) {
                if (sets == null) reload();
                current = sets;
            }
        }
        return current;
    }

    /** Parse every registered source again; a broken file is logged and skipped, the others still load. */
    public static synchronized void reload() {
        List<EquipmentSet> loaded = new ArrayList<>();
        Set<Identifier> ids = new HashSet<>();
        for (Source source : SOURCES) {
            Path directory = FabricLoader.getInstance().getModContainer(source.modId())
                    .flatMap(container -> container.findPath(source.resourceDirectory())).orElse(null);
            if (directory == null) {
                CrazyWorldProgression.LOGGER.warn("Missing equipment-set directory '{}' in mod {}",
                        source.resourceDirectory(), source.modId());
                continue;
            }
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(EquipmentSetRegistry::isYaml).sorted().toList()) {
                    try {
                        EquipmentSet set = read(file, source.namespace());
                        if (!ids.add(set.id())) throw new IllegalArgumentException("duplicate set id " + set.id());
                        loaded.add(set);
                    } catch (IOException | RuntimeException exception) {
                        CrazyWorldProgression.LOGGER.error("Skipped equipment set {}: {}", file.getFileName(),
                                exception.getMessage());
                    }
                }
            } catch (IOException exception) {
                CrazyWorldProgression.LOGGER.error("Could not list equipment sets of {}", source.modId(), exception);
            }
        }
        sets = List.copyOf(loaded);
        CrazyWorldProgression.LOGGER.info("Loaded {} equipment set(s)", loaded.size());
    }

    private static EquipmentSet read(Path file, String namespace) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded;
        try (InputStream input = Files.newInputStream(file)) {
            loaded = new Yaml(new SafeConstructor(options)).load(input);
        }
        if (!(loaded instanceof Map<?, ?> root)) throw new IllegalArgumentException("the root must be a mapping");
        String fileName = file.getFileName().toString();
        String path = fileName.substring(0, fileName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
        String name = root.get("name") instanceof String value ? value : path;
        if (!(root.get("pieces") instanceof List<?> rawPieces) || rawPieces.isEmpty())
            throw new IllegalArgumentException("pieces must list the set's item IDs");
        List<Identifier> pieces = new ArrayList<>();
        for (Object piece : rawPieces) {
            Identifier item = Identifier.tryParse(String.valueOf(piece));
            if (item == null) throw new IllegalArgumentException("invalid item ID " + piece);
            pieces.add(item);
        }
        List<Bonus> bonuses = new ArrayList<>();
        if (root.get("bonuses") instanceof List<?> rawBonuses) {
            for (Object raw : rawBonuses) {
                if (!(raw instanceof Map<?, ?> bonus)) throw new IllegalArgumentException("each bonus needs pieces and stats");
                int count = bonus.get("pieces") instanceof Number number ? number.intValue() : -1;
                bonuses.add(new Bonus(count, stats(bonus.get("stats"), pieces.size(), count)));
            }
        } else {
            int required = root.get("required") instanceof Number number ? number.intValue() : pieces.size();
            bonuses.add(new Bonus(required, stats(root.get("stats"), pieces.size(), required)));
        }
        if (bonuses.isEmpty()) throw new IllegalArgumentException("bonuses must list at least one bonus");
        bonuses.sort(java.util.Comparator.comparingInt(Bonus::pieces));
        return new EquipmentSet(id, name, List.copyOf(pieces), List.copyOf(bonuses));
    }

    private static List<String> stats(Object raw, int pieceCount, int required) {
        if (required < 1 || required > pieceCount)
            throw new IllegalArgumentException("a bonus needs pieces from 1 to " + pieceCount);
        if (!(raw instanceof List<?> rawStats) || rawStats.isEmpty())
            throw new IllegalArgumentException("stats must list at least one stat, e.g. addMovementSpeed(20)");
        List<String> stats = new ArrayList<>();
        for (Object stat : rawStats) {
            String expression = String.valueOf(stat);
            SkillStatRegistry.parse(expression);
            stats.add(expression);
        }
        return List.copyOf(stats);
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    public record Source(String namespace, String modId, String resourceDirectory) { }

    /** pieces: item IDs that count towards the set; each bonus applies with at least its piece count worn. */
    public record EquipmentSet(Identifier id, String name, List<Identifier> pieces, List<Bonus> bonuses) { }

    /** Stats that switch on once {@code pieces} pieces of the set are worn. */
    public record Bonus(int pieces, List<String> stats) { }
}
