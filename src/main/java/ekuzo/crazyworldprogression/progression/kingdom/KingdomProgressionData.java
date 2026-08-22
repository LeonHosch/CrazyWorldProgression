package ekuzo.crazyworldprogression.progression.kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class KingdomProgressionData extends SavedData {
    // This state belongs to the kingdom as a whole rather than to an individual player.
    private long kingdomPoints;
    private UUID electedKing;
    private UUID activeVoteCandidate;
    private final Set<String> unlockedTechnologies = new HashSet<>();

    public static final Codec<KingdomProgressionData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.LONG.optionalFieldOf("kingdom_points", 0L)
                            .forGetter(data -> data.kingdomPoints),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).optionalFieldOf("elected_king")
                            .forGetter(data -> Optional.ofNullable(data.electedKing)),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).optionalFieldOf("active_vote_candidate")
                            .forGetter(data -> Optional.ofNullable(data.activeVoteCandidate)),
                    Codec.STRING.listOf().optionalFieldOf("unlocked_technologies", List.of())
                            .forGetter(data -> List.copyOf(data.unlockedTechnologies))
            ).apply(instance, KingdomProgressionData::new)
    );

    public static final SavedDataType<KingdomProgressionData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("kingdom_progression"),
            KingdomProgressionData::new,
            CODEC,
            null
    );

    // Create an empty global progression save for a new world.
    public KingdomProgressionData() {
    }

    // Rebuild mutable global progression state from values decoded from disk.
    private KingdomProgressionData(
            long kingdomPoints,
            Optional<UUID> electedKing,
            Optional<UUID> activeVoteCandidate,
            List<String> unlockedTechnologies
    ) {
        this.kingdomPoints = Math.max(0L, kingdomPoints);
        this.electedKing = electedKing.orElse(null);
        this.activeVoteCandidate = activeVoteCandidate.orElse(null);
        this.unlockedTechnologies.addAll(unlockedTechnologies);
    }

    // Load the single kingdom progression save shared across every dimension.
    public static KingdomProgressionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Return the server-wide Kingdom Points balance.
    long kingdomPoints() {
        return kingdomPoints;
    }

    // Persist an exact server-wide Kingdom Points balance.
    void setKingdomPoints(long kingdomPoints) {
        this.kingdomPoints = kingdomPoints;
        setDirty();
    }

    // Return the UUID of the elected king when one exists.
    Optional<UUID> electedKing() {
        return Optional.ofNullable(electedKing);
    }

    // Appoint a king and close the election that selected them.
    void electKing(UUID electedKing) {
        this.electedKing = electedKing;
        activeVoteCandidate = null;
        setDirty();
    }

    // Return the active election candidate when a vote is running.
    Optional<UUID> activeVoteCandidate() {
        return Optional.ofNullable(activeVoteCandidate);
    }

    // Store the candidate for a newly started king vote.
    void startKingVote(UUID candidateUuid) {
        activeVoteCandidate = candidateUuid;
        setDirty();
    }

    // Clear all election leadership state for administration or recovery.
    void clearKingAndVote() {
        electedKing = null;
        activeVoteCandidate = null;
        setDirty();
    }

    // Check whether a global technology node has already been unlocked.
    boolean isTechnologyUnlocked(String technologyId) {
        return unlockedTechnologies.contains(technologyId);
    }

    // Deduct the technology cost and record its global unlock together.
    void unlockTechnology(String technologyId, long updatedKingdomPoints) {
        // The global cost and unlock are one purchase and must be persisted together.
        kingdomPoints = updatedKingdomPoints;
        unlockedTechnologies.add(technologyId);
        setDirty();
    }
}
