package ekuzo.crazyworldprogression.config;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The instance and workshop worlds, as many as config/crazy-world-progression.yml asks for. They are written as a data
 * pack into .crazy-world-progression/worlds-pack at startup (from the templates in cwp-worlds/ in the jar) and that
 * pack is always on. Each instance world needs its own dimension type, clock and timelines; a workshop only a
 * dimension. Without the portals part there are none, not even the shared portal workshop.
 */
public final class GeneratedWorlds {
    public static final String PACK_ID = "crazy-world-progression/worlds";
    private static final String INSTANCE_TOKEN = "portal_instance_1";
    private static final String WORKSHOP_TOKEN = "pocket_editor_1";
    private static final List<String> INSTANCE_FILES = List.of(
            "dimension/portal_instance_1.json", "dimension_type/portal_instance_1.json",
            "world_clock/portal_instance_1.json", "timeline/portal_instance_1_day.json",
            "timeline/portal_instance_1_early_game.json", "timeline/portal_instance_1_moon.json",
            "tags/timeline/in_portal_instance_1.json");
    private static final List<String> WORKSHOP_FILES = List.of("dimension/pocket_editor_1.json");
    private static Path directory;

    private GeneratedWorlds() { }

    /** The RepositorySource added to data pack lists (PackRepositoryWorldsMixin). */
    public static void addPack(Consumer<Pack> output) {
        // A known pack, like the mods' own data: worlds from unknown packs count as "experimental" and singleplayer
        // asks for a backup before every load. The counts are in its version, so a player whose config makes
        // different worlds gets the server's copy instead of using their own.
        CwpConfig config = CwpConfig.get();
        String version = FabricLoader.getInstance().getModContainer(CrazyWorldProgression.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev")
                + "+instances" + config.instances() + ".workshops" + config.workshops();
        Pack pack = Pack.readMetaAndCreate(
                new PackLocationInfo(PACK_ID, Component.literal("CWP instance and workshop worlds"), PackSource.BUILT_IN,
                        Optional.of(new KnownPack(CrazyWorldProgression.MOD_ID, "worlds", version))),
                new PathPackResources.PathResourcesSupplier(directory()), PackType.SERVER_DATA,
                new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) output.accept(pack);
    }

    private static synchronized Path directory() {
        if (directory == null) {
            Path target = FabricLoader.getInstance().getGameDir().resolve(".crazy-world-progression").resolve("worlds-pack");
            try {
                write(target);
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not write the CWP worlds pack to " + target, exception);
            }
            directory = target;
        }
        return directory;
    }

    private static void write(Path target) throws IOException {
        if (Files.exists(target)) {
            try (Stream<Path> old = Files.walk(target)) {
                for (Path path : old.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Path data = target.resolve("data").resolve(CrazyWorldProgression.MOD_ID);
        Files.createDirectories(data);
        PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
        Files.writeString(target.resolve("pack.mcmeta"), """
                {"pack": {"description": "CWP instance and workshop worlds (made from config/crazy-world-progression.yml)",
                  "min_format": [%d, %d], "max_format": %d}}
                """.formatted(format.major(), format.minor(), format.major()), StandardCharsets.UTF_8);
        CwpConfig config = CwpConfig.get();
        copies(data, "instance", INSTANCE_FILES, INSTANCE_TOKEN, "portal_instance_", config.instances());
        copies(data, "workshop", WORKSHOP_FILES, WORKSHOP_TOKEN, "pocket_editor_", config.workshops());
        // The shared workshop world, only while portals are on.
        if (config.enabled(CwpModule.PORTALS)) copies(data, "portals", List.of("dimension/portal_workshop.json"), "", "", 1);
        CrazyWorldProgression.LOGGER.info("CWP worlds: {} instance(s), {} workshop(s)", config.instances(), config.workshops());
    }

    /** One copy of every template per world, with the template's number replaced by the world's. */
    private static void copies(Path data, String kind, List<String> files, String token, String prefix, int count) throws IOException {
        for (String file : files) {
            String template = template(kind + "/" + file);
            for (int index = 1; index <= count; index++) {
                String name = token.isEmpty() ? file : file.replace(token, prefix + index);
                Path path = data.resolve(name);
                Files.createDirectories(path.getParent());
                Files.writeString(path, token.isEmpty() ? template : template.replace(token, prefix + index), StandardCharsets.UTF_8);
            }
        }
    }

    private static String template(String path) throws IOException {
        try (InputStream stream = GeneratedWorlds.class.getResourceAsStream("/cwp-worlds/" + path)) {
            if (stream == null) throw new IOException("Missing template cwp-worlds/" + path + " in the CWP jar");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
