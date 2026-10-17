package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player raid checkpoints: how many encounters of a raid portal were completed. A checkpoint lasts
 * 24 hours from the last completed encounter and decides where a new instance of that raid starts.
 */
public final class RaidCheckpoints extends SavedData {
    public static final long LIFETIME_MILLIS = 24L * 60L * 60L * 1000L;
    static final Codec<Checkpoint> CHECKPOINT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("encounters").forGetter(Checkpoint::encounters),
            Codec.LONG.fieldOf("expires").forGetter(Checkpoint::expiresEpochMillis)
    ).apply(instance, Checkpoint::new));
    static final Codec<RaidCheckpoints> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, CHECKPOINT_CODEC))
                    .optionalFieldOf("players", Map.of()).forGetter(data -> data.players)
    ).apply(instance, RaidCheckpoints::new));
    private static final SavedDataType<RaidCheckpoints> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("raid_checkpoints"), RaidCheckpoints::new, CODEC, null);
    private final Map<String, Map<String, Checkpoint>> players = new HashMap<>();

    public record Checkpoint(int encounters, long expiresEpochMillis) {
        public boolean valid(long now) { return encounters > 0 && expiresEpochMillis > now; }
    }

    RaidCheckpoints() { }

    private RaidCheckpoints(Map<String, Map<String, Checkpoint>> players) {
        players.forEach((player, raids) -> this.players.put(player, new HashMap<>(raids)));
    }

    static RaidCheckpoints get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The valid checkpoint for this player and raid portal, or null. */
    public static Checkpoint checkpoint(MinecraftServer server, UUID playerId, Identifier portalId) {
        return get(server).current(playerId.toString(), portalId.toString(), Instant.now().toEpochMilli());
    }

    Checkpoint current(String playerId, String portalId, long now) {
        Checkpoint checkpoint = players.getOrDefault(playerId, Map.of()).get(portalId);
        return checkpoint != null && checkpoint.valid(now) ? checkpoint : null;
    }

    /** Raises a checkpoint to the given encounter count and restarts its 24-hour lifetime. */
    void record(String playerId, String portalId, int encounters, long now) {
        Checkpoint previous = current(playerId, portalId, now);
        int best = Math.max(encounters, previous == null ? 0 : previous.encounters());
        players.computeIfAbsent(playerId, ignored -> new HashMap<>())
                .put(portalId, new Checkpoint(best, now + LIFETIME_MILLIS));
        setDirty();
    }

    boolean reset(String playerId, String portalId) {
        Map<String, Checkpoint> raids = players.get(playerId);
        if (raids == null || raids.remove(portalId) == null) return false;
        if (raids.isEmpty()) players.remove(playerId);
        setDirty();
        return true;
    }

    public static boolean reset(MinecraftServer server, UUID playerId, Identifier portalId) {
        return get(server).reset(playerId.toString(), portalId.toString());
    }

    /** A new or reset instance of a raid starts at its party leader's checkpoint (the first admitted player). */
    public static int resumeFor(MinecraftServer server, PortalData.StoredInstance instance) {
        PortalDefinition portal = PortalService.portal(server, instance.portalId());
        if (portal == null || !portal.raidMode() || instance.admittedPlayers().isEmpty()) return 0;
        Checkpoint checkpoint = get(server).current(instance.admittedPlayers().getFirst(),
                instance.portalId().toString(), Instant.now().toEpochMilli());
        return checkpoint == null ? 0 : checkpoint.encounters();
    }

    /** Called by the script when an encounter ends: everyone taking part gets the checkpoint. */
    public static void encounterCompleted(ServerLevel level, int encounters) {
        MinecraftServer server = level.getServer();
        PortalData.StoredInstance instance = PortalData.get(server).instances()
                .get(level.dimension().identifier().toString());
        if (instance == null || encounters < 1 || !RaidService.isRaid(server, instance.slotDimension())) return;
        Set<String> participants = new LinkedHashSet<>();
        for (ServerPlayer player : level.players()) {
            String id = player.getUUID().toString();
            if (instance.admittedPlayers().contains(id)) participants.add(id);
        }
        RaidCheckpoints data = get(server);
        long now = Instant.now().toEpochMilli();
        for (String id : participants) data.record(id, instance.portalId().toString(), encounters, now);
    }

}
