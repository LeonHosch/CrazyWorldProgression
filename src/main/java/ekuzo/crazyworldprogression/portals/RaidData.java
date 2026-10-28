package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-slot raid attempts survive world saves without mixing groups. */
final class RaidData extends SavedData {
    private static final Codec<RaidData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Attempt.CODEC).optionalFieldOf("attempts", Map.of())
                    .forGetter(data -> data.attempts),
            Codec.unboundedMap(Codec.STRING, Spot.CODEC).optionalFieldOf("spawns", Map.of())
                    .forGetter(data -> data.spawns),
            Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).optionalFieldOf("encounter_players", Map.of())
                    .forGetter(data -> data.encounterPlayers)
    ).apply(instance, RaidData::new));
    private static final SavedDataType<RaidData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("raid_attempts"), RaidData::new, CODEC, null);
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();
    /** Instance spawn set by encounter_end(spawn=...) or finish_instance(spawn=...). */
    private final Map<String, Spot> spawns = new LinkedHashMap<>();

    private RaidData() { }
    /** Players inside the instance when its current encounter started; they all share a wipe. */
    private final Map<String, List<String>> encounterPlayers = new LinkedHashMap<>();

    private RaidData(Map<String, Attempt> attempts, Map<String, Spot> spawns, Map<String, List<String>> encounterPlayers) {
        this.attempts.putAll(attempts);
        this.spawns.putAll(spawns);
        this.encounterPlayers.putAll(encounterPlayers);
    }
    List<String> encounterPlayers(String slot) { return encounterPlayers.getOrDefault(slot, List.of()); }
    void encounterPlayers(String slot, List<String> players) {
        if (players == null || players.isEmpty()) encounterPlayers.remove(slot);
        else encounterPlayers.put(slot, List.copyOf(players));
        setDirty();
    }
    Spot spawn(String slot) { return spawns.get(slot); }
    void spawn(String slot, Spot spot) {
        if (spot == null) spawns.remove(slot); else spawns.put(slot, spot);
        setDirty();
    }
    static RaidData get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
    Attempt get(String slot) { return attempts.get(slot); }
    void put(String slot, Attempt attempt) { attempts.put(slot, attempt); setDirty(); }
    void remove(String slot) {
        if (attempts.remove(slot) != null | spawns.remove(slot) != null | encounterPlayers.remove(slot) != null) setDirty();
    }

    record Spot(double x, double y, double z) {
        static final Codec<Spot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("x").forGetter(Spot::x),
                Codec.DOUBLE.fieldOf("y").forGetter(Spot::y),
                Codec.DOUBLE.fieldOf("z").forGetter(Spot::z)
        ).apply(instance, Spot::new));
    }

    record Attempt(int number, Map<String, Spot> deaths, List<String> spentRevives,
                   Map<String, List<String>> portalEntries, List<String> pendingRespawn,
                   Map<String, String> originalModes, boolean encounterActive) {
        static final Codec<Attempt> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("number", 1).forGetter(Attempt::number),
                Codec.unboundedMap(Codec.STRING, Spot.CODEC).optionalFieldOf("deaths", Map.of())
                        .forGetter(Attempt::deaths),
                Codec.STRING.listOf().optionalFieldOf("spent_revives", List.of()).forGetter(Attempt::spentRevives),
                Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).optionalFieldOf("portal_entries", Map.of())
                        .forGetter(Attempt::portalEntries),
                Codec.STRING.listOf().optionalFieldOf("pending_respawn", List.of()).forGetter(Attempt::pendingRespawn),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("original_modes", Map.of())
                        .forGetter(Attempt::originalModes),
                Codec.BOOL.optionalFieldOf("encounter_active", true).forGetter(Attempt::encounterActive)
        ).apply(instance, Attempt::new));

        Attempt(int number, Map<String, Spot> deaths, List<String> spentRevives,
                Map<String, List<String>> portalEntries, List<String> pendingRespawn,
                Map<String, String> originalModes) {
            this(number, deaths, spentRevives, portalEntries, pendingRespawn, originalModes, true);
        }

        Attempt {
            if (number < 1) throw new IllegalArgumentException("Raid attempt must be positive");
            deaths = Map.copyOf(deaths);
            spentRevives = List.copyOf(spentRevives);
            portalEntries = Map.copyOf(portalEntries);
            pendingRespawn = List.copyOf(pendingRespawn);
            originalModes = Map.copyOf(originalModes);
        }
        static Attempt first() { return new Attempt(1, Map.of(), List.of(), Map.of(), List.of(), Map.of()); }
        Attempt withActive(boolean active) {
            return new Attempt(number, deaths, spentRevives, portalEntries, pendingRespawn, originalModes, active);
        }
    }
}
