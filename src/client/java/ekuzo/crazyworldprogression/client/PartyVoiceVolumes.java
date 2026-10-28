package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.portals.PartyVoiceVolumeBridge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-speaker gain belongs to this client, keyed by player UUID across parties and sessions. */
public final class PartyVoiceVolumes {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("crazy-world-progression-party-volumes.properties");
    private static final Map<UUID, Integer> PERCENT = new ConcurrentHashMap<>();

    private PartyVoiceVolumes() { }

    public static void initialize() {
        load();
        PartyVoiceVolumeBridge.install(PartyVoiceVolumes::adjust);
    }

    public static int percent(UUID player) { return PERCENT.getOrDefault(player, 100); }

    public static void setPercent(UUID player, int percent) {
        int safe = Math.clamp(percent, 0, 200);
        if (percent(player) == safe) return;
        if (safe == 100) PERCENT.remove(player);
        else PERCENT.put(player, safe);
        save();
    }

    private static short[] adjust(UUID sender, short[] audio) {
        if (sender == null) return audio;
        int gain = percent(sender);
        if (gain == 100 || audio.length == 0) return audio;
        short[] adjusted = new short[audio.length];
        for (int i = 0; i < audio.length; i++)
            adjusted[i] = (short) Math.clamp(Math.round(audio[i] * gain / 100.0f), Short.MIN_VALUE, Short.MAX_VALUE);
        return adjusted;
    }

    private static void load() {
        if (!Files.isRegularFile(FILE)) return;
        Properties values = new Properties();
        try (InputStream stream = Files.newInputStream(FILE)) {
            values.load(stream);
            for (String key : values.stringPropertyNames()) {
                try {
                    int percent = Integer.parseInt(values.getProperty(key));
                    if (percent >= 0 && percent <= 200 && percent != 100) PERCENT.put(UUID.fromString(key), percent);
                } catch (IllegalArgumentException ignored) {
                    // Ignore damaged or hand-edited entries without losing the other speakers.
                }
            }
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not read party voice volumes", exception);
        }
    }

    private static void save() {
        Properties values = new Properties();
        PERCENT.forEach((player, gain) -> values.setProperty(player.toString(), Integer.toString(gain)));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream stream = Files.newOutputStream(FILE)) {
                values.store(stream, "CWP party voice volume percentages (0-200)");
            }
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not save party voice volumes", exception);
        }
    }
}
