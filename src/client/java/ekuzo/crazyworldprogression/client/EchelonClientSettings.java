package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Local preferences only; the server still validates every portal journey. */
public final class EchelonClientSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("echelon-client.properties");
    public static boolean protectHotbar = true;
    public static boolean coordinatePortalMenu = false;
    public static boolean exitPortalMenu = true;
    public static boolean portalParticles = true;
    public static boolean editorPlacementPreviews = true;
    public static boolean showTitles = true;
    public static boolean showPlayerNames = true;
    public static boolean showPlayersInSemiPrivateInstances = false;

    private EchelonClientSettings() { }

    /** The pause menu buttons are added by PauseScreenEchelonMixin. */
    public static void initialize() {
        load();
    }

    public static void resetToDefaults() {
        protectHotbar = true;
        coordinatePortalMenu = false;
        exitPortalMenu = true;
        portalParticles = true;
        editorPlacementPreviews = true;
        showTitles = true;
        showPlayerNames = true;
        showPlayersInSemiPrivateInstances = false;
    }

    private static void load() {
        if (!Files.isRegularFile(FILE)) return;
        Properties values = new Properties();
        try (InputStream stream = Files.newInputStream(FILE)) {
            values.load(stream);
            protectHotbar = Boolean.parseBoolean(values.getProperty("protectHotbar", "true"));
            coordinatePortalMenu = Boolean.parseBoolean(values.getProperty("coordinatePortalMenu", "false"));
            exitPortalMenu = Boolean.parseBoolean(values.getProperty("exitPortalMenu", "true"));
            portalParticles = Boolean.parseBoolean(values.getProperty("portalParticles", "true"));
            editorPlacementPreviews = Boolean.parseBoolean(values.getProperty("editorPlacementPreviews", "true"));
            showTitles = Boolean.parseBoolean(values.getProperty("showTitles", "true"));
            showPlayerNames = Boolean.parseBoolean(values.getProperty("showPlayerNames", "true"));
            showPlayersInSemiPrivateInstances = Boolean.parseBoolean(
                    values.getProperty("showPlayersInSemiPrivateInstances", "false"));
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not read client settings", exception);
        }
    }

    public static void save() {
        Properties values = new Properties();
        values.setProperty("protectHotbar", Boolean.toString(protectHotbar));
        values.setProperty("coordinatePortalMenu", Boolean.toString(coordinatePortalMenu));
        values.setProperty("exitPortalMenu", Boolean.toString(exitPortalMenu));
        values.setProperty("portalParticles", Boolean.toString(portalParticles));
        values.setProperty("editorPlacementPreviews", Boolean.toString(editorPlacementPreviews));
        values.setProperty("showTitles", Boolean.toString(showTitles));
        values.setProperty("showPlayerNames", Boolean.toString(showPlayerNames));
        values.setProperty("showPlayersInSemiPrivateInstances", Boolean.toString(showPlayersInSemiPrivateInstances));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream stream = Files.newOutputStream(FILE)) {
                values.store(stream, "Echelon client settings");
            }
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not save client settings", exception);
        }
    }
}
