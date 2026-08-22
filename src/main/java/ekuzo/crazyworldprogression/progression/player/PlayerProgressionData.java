package ekuzo.crazyworldprogression.progression.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerProgressionData extends SavedData {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    private static final Codec<Map<UUID, Long>> BALANCES_CODEC = Codec.unboundedMap(UUID_CODEC, Codec.LONG);
    private static final Codec<Map<UUID, Set<String>>> ID_SETS_CODEC =
            Codec.unboundedMap(UUID_CODEC, Codec.STRING.listOf())
                    .xmap(PlayerProgressionData::toSets, PlayerProgressionData::toLists);

    // Balances are stored by UUID so they remain available while a player is offline.
    private final Map<UUID, Long> echelonPoints = new HashMap<>();
    private final Map<UUID, Long> fakhrulCurrency = new HashMap<>();
    private final Map<UUID, Long> powerfulSouls = new HashMap<>();
    private final Map<UUID, Set<String>> powerfulSoulClaims = new HashMap<>();
    private final Map<UUID, Set<String>> unlockedSkills = new HashMap<>();

    public static final Codec<PlayerProgressionData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BALANCES_CODEC.optionalFieldOf("points", Collections.emptyMap())
                            .forGetter(data -> data.echelonPoints),
                    BALANCES_CODEC.optionalFieldOf("fakhrul_currency", Collections.emptyMap())
                            .forGetter(data -> data.fakhrulCurrency),
                    BALANCES_CODEC.optionalFieldOf("powerful_souls", Collections.emptyMap())
                            .forGetter(data -> data.powerfulSouls),
                    ID_SETS_CODEC.optionalFieldOf("powerful_soul_claims", Collections.emptyMap())
                            .forGetter(data -> data.powerfulSoulClaims),
                    ID_SETS_CODEC.optionalFieldOf("unlocked_skills", Collections.emptyMap())
                            .forGetter(data -> data.unlockedSkills)
            ).apply(instance, PlayerProgressionData::new)
    );

    public static final SavedDataType<PlayerProgressionData> TYPE = new SavedDataType<>(
            // Keep the original SavedData id and "points" field so existing EP balances migrate in place.
            CrazyWorldProgression.id("echelon_points"),
            PlayerProgressionData::new,
            CODEC,
            null
    );

    // Create an empty progression save for a new world.
    public PlayerProgressionData() {
    }

    // Rebuild mutable progression state from values decoded from disk.
    private PlayerProgressionData(
            Map<UUID, Long> echelonPoints,
            Map<UUID, Long> fakhrulCurrency,
            Map<UUID, Long> powerfulSouls,
            Map<UUID, Set<String>> powerfulSoulClaims,
            Map<UUID, Set<String>> unlockedSkills
    ) {
        copyNonNegative(echelonPoints, this.echelonPoints);
        copyNonNegative(fakhrulCurrency, this.fakhrulCurrency);
        copyNonNegative(powerfulSouls, this.powerfulSouls);
        powerfulSoulClaims.forEach((uuid, claims) -> this.powerfulSoulClaims.put(uuid, new HashSet<>(claims)));
        unlockedSkills.forEach((uuid, skills) -> this.unlockedSkills.put(uuid, new HashSet<>(skills)));
    }

    // Load the single player progression save shared across every dimension.
    public static PlayerProgressionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Return the requested regular currency balance for one player.
    long balance(UUID playerUuid, PersonalCurrency currency) {
        return balances(currency).getOrDefault(playerUuid, 0L);
    }

    // Persist an exact regular currency balance for one player.
    void setBalance(UUID playerUuid, PersonalCurrency currency, long amount) {
        putOrRemoveZero(balances(currency), playerUuid, amount);
        setDirty();
    }

    // Return one player's spendable Powerful Souls balance.
    long powerfulSouls(UUID playerUuid) {
        return powerfulSouls.getOrDefault(playerUuid, 0L);
    }

    // Persist an exact Powerful Souls balance without changing claim history.
    void setPowerfulSouls(UUID playerUuid, long amount) {
        putOrRemoveZero(powerfulSouls, playerUuid, amount);
        setDirty();
    }

    // Count how many unique Powerful Soul rewards a player has claimed.
    int powerfulSoulClaimCount(UUID playerUuid) {
        return powerfulSoulClaims.getOrDefault(playerUuid, Set.of()).size();
    }

    // Check whether a player has already used a specific Powerful Soul claim.
    boolean hasPowerfulSoulClaim(UUID playerUuid, String claimId) {
        return powerfulSoulClaims.getOrDefault(playerUuid, Set.of()).contains(claimId);
    }

    // Record a new claim and update its resulting spendable Soul balance together.
    void claimPowerfulSoul(UUID playerUuid, String claimId, long updatedBalance) {
        // Record the one-time claim and its reward as one logical state change.
        powerfulSoulClaims.computeIfAbsent(playerUuid, ignored -> new HashSet<>()).add(claimId);
        putOrRemoveZero(powerfulSouls, playerUuid, updatedBalance);
        setDirty();
    }

    // Check whether a player has already unlocked a skill node.
    boolean isSkillUnlocked(UUID playerUuid, String skillId) {
        return unlockedSkills.getOrDefault(playerUuid, Set.of()).contains(skillId);
    }

    // Deduct a skill's costs and persist its unlock as one state change.
    void unlockSkill(UUID playerUuid, String skillId, long updatedEchelonPoints, long updatedPowerfulSouls) {
        // Costs and the resulting unlock are persisted together to prevent partially applied purchases.
        putOrRemoveZero(echelonPoints, playerUuid, updatedEchelonPoints);
        putOrRemoveZero(powerfulSouls, playerUuid, updatedPowerfulSouls);
        unlockedSkills.computeIfAbsent(playerUuid, ignored -> new HashSet<>()).add(skillId);
        setDirty();
    }

    // Select the backing balance map for a regular personal currency.
    private Map<UUID, Long> balances(PersonalCurrency currency) {
        return switch (currency) {
            case ECHELON_POINTS -> echelonPoints;
            case FAKHRUL_CURRENCY -> fakhrulCurrency;
        };
    }

    // Copy valid positive balances from decoded storage into mutable runtime state.
    private static void copyNonNegative(Map<UUID, Long> source, Map<UUID, Long> target) {
        source.forEach((uuid, amount) -> {
            if (amount > 0L) {
                target.put(uuid, amount);
            }
        });
    }

    // Convert serialized lists into sets so duplicate identifiers cannot exist at runtime.
    private static Map<UUID, Set<String>> toSets(Map<UUID, List<String>> storedValues) {
        Map<UUID, Set<String>> values = new HashMap<>();
        storedValues.forEach((uuid, entries) -> values.put(uuid, new HashSet<>(entries)));
        return values;
    }

    // Convert identifier sets into sorted lists for stable serialization.
    private static Map<UUID, List<String>> toLists(Map<UUID, Set<String>> values) {
        Map<UUID, List<String>> storedValues = new HashMap<>();
        values.forEach((uuid, entries) -> storedValues.put(uuid, entries.stream().sorted().toList()));
        return storedValues;
    }

    // Store positive balances and remove zero-value entries to keep save data compact.
    private static void putOrRemoveZero(Map<UUID, Long> balances, UUID playerUuid, long amount) {
        if (amount == 0L) {
            balances.remove(playerUuid);
        } else {
            balances.put(playerUuid, amount);
        }
    }
}
