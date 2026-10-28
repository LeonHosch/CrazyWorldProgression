package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Local preferences only; the server still validates every portal journey. Shown as a settings screen from the pause
 * menu, under a name a content mod can choose ({@link #setMenuTitle}).
 */
public final class CwpClientSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("crazy-world-progression-client.properties");
    /** Where these settings were kept before 0.5.0. */
    private static final Path OLD_FILE = FabricLoader.getInstance().getConfigDir().resolve("echelon-client.properties");
    private static Component menuTitle = Component.translatable("menu.crazy-world-progression.client_settings");
    public static boolean protectHotbar = true;
    public static boolean coordinatePortalMenu = false;
    public static boolean exitPortalMenu = true;
    public static boolean portalParticles = true;
    public static boolean editorPlacementPreviews = true;
    public static boolean showTitles = true;
    public static boolean showPlayerNames = true;
    public static boolean showPlayersInSemiPrivateInstances = false;

    private CwpClientSettings() { }

    /**
     * The name of the settings button in the pause menu and of the settings screen ("CWP Settings" unless set). A
     * content mod calls this in its client initializer to show its own name, for example "Echelon Settings".
     */
    public static void setMenuTitle(Component title) { menuTitle = title; }

    public static Component menuTitle() { return menuTitle; }

    /** The pause menu buttons are added by PauseScreenSettingsMixin. */
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
        Path file = Files.isRegularFile(FILE) ? FILE : OLD_FILE;
        if (!Files.isRegularFile(file)) return;
        Properties values = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
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
                values.store(stream, "Crazy World Progression client settings");
            }
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not save client settings", exception);
        }
    }
}
