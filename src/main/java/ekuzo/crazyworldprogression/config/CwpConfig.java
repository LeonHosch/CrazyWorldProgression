package ekuzo.crazyworldprogression.config;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * CWP's one config file, config/crazy-world-progression.yml: which parts of the framework are on, how many instance
 * and workshop worlds exist, and the tick time budget for block work. Read once at startup (changes need a restart)
 * and written back every time, so new settings show up in the file with their explanation.
 * <p>
 * Another mod can keep parts on that it needs, in its fabric.mod.json:
 * {@code "custom": {"crazy-world-progression": {"requires_modules": ["skill_trees", "portals"]}}}.
 * <p>
 * Plain Java and Fabric Loader only: the mixin plugin reads this before Minecraft is loaded.
 */
public final class CwpConfig {
    public static final String FILE = "crazy-world-progression.yml";
    private static final String LEGACY_PERFORMANCE_FILE = "crazy-world-progression-performance.properties";
    private static final Logger LOGGER = LoggerFactory.getLogger("crazy-world-progression");
    public static final int MAX_WORLDS = 256;
    private static CwpConfig instance;

    private final Map<CwpModule, Boolean> modules = new EnumMap<>(CwpModule.class);
    /** Parts other mods need, with those mods' ids; they stay on whatever the file says. */
    private final Map<CwpModule, TreeSet<String>> requiredBy = new EnumMap<>(CwpModule.class);
    private int instances = 8;
    private int workshops = 32;
    private double tickBufferMs = 10.0, minBudgetMs = 2.0, maxBudgetMs = 30.0, scriptMaxMs = 30.0, cleanupFloorMs = 1.0;

    private CwpConfig() {
        for (CwpModule module : CwpModule.values()) modules.put(module, true);
    }

    public static synchronized CwpConfig get() {
        if (instance == null) {
            instance = new CwpConfig();
            Path configDir;
            try {
                configDir = FabricLoader.getInstance().getConfigDir();
            } catch (RuntimeException outsideTheGame) {
                // Unit tests run without Fabric Loader: everything stays at its default.
                return instance;
            }
            instance.load(configDir);
        }
        return instance;
    }

    /** True when the part is switched on in the file, or another mod needs it. */
    public boolean enabled(CwpModule module) {
        return modules.get(module) || requiredBy.containsKey(module);
    }

    /** Instance worlds portals copy pockets into (0 while portals are off). */
    public int instances() { return enabled(CwpModule.PORTALS) ? instances : 0; }

    /** Workshop worlds pockets are built in (0 while portals are off). */
    public int workshops() { return enabled(CwpModule.PORTALS) ? workshops : 0; }

    public double tickBufferMs() { return tickBufferMs; }

    public double minBudgetMs() { return minBudgetMs; }

    public double maxBudgetMs() { return maxBudgetMs; }

    public double scriptMaxMs() { return scriptMaxMs; }

    public double cleanupFloorMs() { return cleanupFloorMs; }

    private void load(Path configDir) {
        Path file = configDir.resolve(FILE);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Object root = new Yaml().load(reader);
                if (root instanceof Map<?, ?> map) read(map);
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Could not read {}; using the defaults for anything it could not read", file, exception);
            }
        } else {
            importLegacyPerformance(configDir.resolve(LEGACY_PERFORMANCE_FILE));
        }
        collectRequirements();
        try {
            Files.createDirectories(configDir);
            Files.writeString(file, render(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("Could not write {}", file, exception);
        }
        StringBuilder off = new StringBuilder();
        for (CwpModule module : CwpModule.values()) if (!enabled(module)) off.append(' ').append(module.key());
        if (!off.isEmpty()) LOGGER.info("CWP parts switched off:{}", off);
    }

    private void read(Map<?, ?> root) {
        if (root.get("modules") instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                CwpModule module = CwpModule.byKey(String.valueOf(entry.getKey()));
                if (module == null) LOGGER.warn("Unknown CWP part in {}: {}", FILE, entry.getKey());
                else if (entry.getValue() instanceof Boolean on) modules.put(module, on);
                else LOGGER.warn("CWP part {} in {} must be true or false, not {}", module.key(), FILE, entry.getValue());
            }
        }
        if (root.get("worlds") instanceof Map<?, ?> worlds) {
            instances = count(worlds.get("instances"), instances, "worlds.instances");
            workshops = count(worlds.get("workshops"), workshops, "worlds.workshops");
        }
        if (root.get("performance") instanceof Map<?, ?> performance) {
            tickBufferMs = millis(performance.get("tick_buffer_ms"), tickBufferMs);
            minBudgetMs = millis(performance.get("min_budget_ms"), minBudgetMs);
            maxBudgetMs = millis(performance.get("max_budget_ms"), maxBudgetMs);
            scriptMaxMs = millis(performance.get("script_max_ms"), scriptMaxMs);
            cleanupFloorMs = millis(performance.get("cleanup_floor_ms"), cleanupFloorMs);
        }
    }

    private static int count(Object value, int fallback, String name) {
        if (!(value instanceof Number number)) return fallback;
        int wanted = number.intValue();
        int clamped = Math.max(0, Math.min(MAX_WORLDS, wanted));
        if (clamped != wanted) LOGGER.warn("{} in {} must be between 0 and {}; using {}", name, FILE, MAX_WORLDS, clamped);
        return clamped;
    }

    private static double millis(Object value, double fallback) {
        return value instanceof Number number ? Math.max(0.0, number.doubleValue()) : fallback;
    }

    /** Takes the tick budget settings from the old performance file the first time this file is made. */
    private void importLegacyPerformance(Path file) {
        if (!Files.isRegularFile(file)) return;
        Properties values = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
            values.load(stream);
            tickBufferMs = legacy(values, "tick_buffer_ms", tickBufferMs);
            minBudgetMs = legacy(values, "min_budget_ms", minBudgetMs);
            maxBudgetMs = legacy(values, "max_budget_ms", maxBudgetMs);
            scriptMaxMs = legacy(values, "script_max_ms", scriptMaxMs);
            cleanupFloorMs = legacy(values, "cleanup_floor_ms", cleanupFloorMs);
            LOGGER.info("Moved the settings of {} into {}; the old file is no longer read", LEGACY_PERFORMANCE_FILE, FILE);
        } catch (IOException | NumberFormatException exception) {
            LOGGER.warn("Could not read {}", file, exception);
        }
    }

    private static double legacy(Properties values, String key, double fallback) {
        String value = values.getProperty(key);
        return value == null ? fallback : Math.max(0.0, Double.parseDouble(value.trim()));
    }

    private void collectRequirements() {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            CustomValue value = mod.getMetadata().getCustomValue("crazy-world-progression");
            if (value == null || value.getType() != CustomValue.CvType.OBJECT) continue;
            CustomValue list = value.getAsObject().get("requires_modules");
            if (list == null || list.getType() != CustomValue.CvType.ARRAY) continue;
            for (CustomValue entry : list.getAsArray()) {
                if (entry.getType() != CustomValue.CvType.STRING) continue;
                CwpModule module = CwpModule.byKey(entry.getAsString().toLowerCase(Locale.ROOT));
                if (module == null) {
                    LOGGER.warn("{} needs an unknown CWP part: {}", mod.getMetadata().getId(), entry.getAsString());
                    continue;
                }
                requiredBy.computeIfAbsent(module, ignored -> new TreeSet<>()).add(mod.getMetadata().getId());
                if (!modules.get(module))
                    LOGGER.warn("CWP part {} is switched off in {}, but {} needs it, so it stays on",
                            module.key(), FILE, mod.getMetadata().getId());
            }
        }
    }

    private String render() {
        StringBuilder out = new StringBuilder();
        out.append("""
                # Crazy World Progression configuration.
                # Changes take effect after a restart. On a server, players' games use their own copy of this file for
                # their screens, so ship the same file with your modpack.

                # Every part of the framework, on (true) or off (false). A part that is off does nothing: no events,
                # commands, screens, admin pages or game changes. Its items and saved data stay known to the game, so a
                # world that used it still loads, and switching it back on brings everything back.
                modules:
                """);
        for (CwpModule module : CwpModule.values()) {
            out.append("  # ").append(module.description()).append('\n');
            TreeSet<String> mods = requiredBy.get(module);
            if (mods != null) out.append("  # Needed by ").append(String.join(", ", mods)).append(", so it stays on.\n");
            out.append("  ").append(module.key()).append(": ").append(modules.get(module)).append('\n');
        }
        out.append(String.format(Locale.ROOT, """

                # How many worlds of each kind are made at startup (0 to %d). Each is a loaded world, so only make as
                # many as you need. Without the portals part there are none.
                worlds:
                  # Instance worlds: each running instance (a dungeon run, a raid) uses one, so this is how many can run at once.
                  instances: %d
                  # Workshop worlds: each pocket being built has its own, so this is how many pockets can exist.
                  # Lowering it below the pockets you have hides the pockets in the higher workshops until you raise it again.
                  workshops: %d

                # Time budget per server tick for block work (building and clearing instances, scripts), in milliseconds.
                # Budget = tick length - the server's own load - tick_buffer_ms, kept between min_budget_ms and max_budget_ms.
                performance:
                  # Kept free for the rest of the server each tick.
                  tick_buffer_ms: %s
                  # Building new instances always gets at least this much.
                  min_budget_ms: %s
                  # Never more than this for building and clearing.
                  max_budget_ms: %s
                  # Scripts may always use up to this, however busy the server is, so raid and dungeon mechanics never stall.
                  script_max_ms: %s
                  # Clearing finished instances always gets at least this much.
                  cleanup_floor_ms: %s
                """, MAX_WORLDS, instances, workshops, tickBufferMs, minBudgetMs, maxBudgetMs, scriptMaxMs, cleanupFloorMs));
        return out.toString();
    }

    /** For the admin panel: every part with whether it is on and which mods need it. */
    public Map<CwpModule, String> describe() {
        Map<CwpModule, String> result = new LinkedHashMap<>();
        for (CwpModule module : CwpModule.values()) {
            TreeSet<String> mods = requiredBy.get(module);
            result.put(module, (enabled(module) ? "on" : "off") + (mods == null ? "" : " (needed by " + String.join(", ", mods) + ")"));
        }
        return result;
    }
}
