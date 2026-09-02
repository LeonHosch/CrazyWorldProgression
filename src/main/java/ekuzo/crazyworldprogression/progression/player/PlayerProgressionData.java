package ekuzo.crazyworldprogression.progression.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
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

/** Dynamic player wallets, personal unlocks, and reusable one-time claim ledgers. */
public final class PlayerProgressionData extends SavedData {
    private static final String LEGACY_EP = "echelon-core:echelon_points";
    private static final String LEGACY_FC = "echelon-core:fakhrul_currency";
    private static final String LEGACY_PS = "echelon-core:powerful_souls";
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    private static final Codec<Map<UUID, Long>> UUID_BALANCES_CODEC = Codec.unboundedMap(UUID_CODEC, Codec.LONG);
    private static final Codec<Map<String, Map<UUID, Long>>> BALANCES_CODEC = Codec.unboundedMap(Codec.STRING, UUID_BALANCES_CODEC);
    private static final Codec<Map<UUID, Set<String>>> UUID_SETS_CODEC = Codec.unboundedMap(UUID_CODEC, Codec.STRING.listOf())
            .xmap(PlayerProgressionData::toSets, PlayerProgressionData::toLists);
    private static final Codec<Map<String, Map<UUID, Set<String>>>> CLAIMS_CODEC =
            Codec.unboundedMap(Codec.STRING, UUID_SETS_CODEC);

    private final Map<String, Map<UUID, Long>> balances = new HashMap<>();
    private final Map<String, Map<UUID, Set<String>>> claims = new HashMap<>();
    private final Map<UUID, Set<String>> unlockedSkills = new HashMap<>();

    public static final Codec<PlayerProgressionData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BALANCES_CODEC.optionalFieldOf("balances", Collections.emptyMap()).forGetter(data -> data.balances),
            CLAIMS_CODEC.optionalFieldOf("claims", Collections.emptyMap()).forGetter(data -> data.claims),
            UUID_SETS_CODEC.optionalFieldOf("unlocked_skills", Collections.emptyMap()).forGetter(data -> data.unlockedSkills),
            UUID_BALANCES_CODEC.optionalFieldOf("points", Collections.emptyMap()).forGetter(data -> Collections.emptyMap()),
            UUID_BALANCES_CODEC.optionalFieldOf("fakhrul_currency", Collections.emptyMap()).forGetter(data -> Collections.emptyMap()),
            UUID_BALANCES_CODEC.optionalFieldOf("powerful_souls", Collections.emptyMap()).forGetter(data -> Collections.emptyMap()),
            UUID_SETS_CODEC.optionalFieldOf("powerful_soul_claims", Collections.emptyMap()).forGetter(data -> Collections.emptyMap())
    ).apply(instance, PlayerProgressionData::new));

    public static final SavedDataType<PlayerProgressionData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("echelon_points"), PlayerProgressionData::new, CODEC, null);

    // Create empty per-player progression state for a new world.
    public PlayerProgressionData() {
    }

    // Decode generic state and merge fixed-format balances and claims written by pre-framework releases.
    private PlayerProgressionData(
            Map<String, Map<UUID, Long>> balances,
            Map<String, Map<UUID, Set<String>>> claims,
            Map<UUID, Set<String>> unlockedSkills,
            Map<UUID, Long> legacyEchelonPoints,
            Map<UUID, Long> legacyFakhrulCurrency,
            Map<UUID, Long> legacyPowerfulSouls,
            Map<UUID, Set<String>> legacyPowerfulSoulClaims
    ) {
        balances.forEach((id, values) -> copyBalances(values, this.balances.computeIfAbsent(id, ignored -> new HashMap<>())));
        claims.forEach((id, values) -> this.claims.put(id, copySets(values)));
        this.unlockedSkills.putAll(copySets(unlockedSkills));
        mergeLegacyBalance(LEGACY_EP, legacyEchelonPoints);
        mergeLegacyBalance(LEGACY_FC, legacyFakhrulCurrency);
        mergeLegacyBalance(LEGACY_PS, legacyPowerfulSouls);
        if (!legacyPowerfulSoulClaims.isEmpty()) {
            this.claims.computeIfAbsent(LEGACY_PS, ignored -> new HashMap<>()).putAll(copySets(legacyPowerfulSoulClaims));
        }
    }

    // Load the one per-player progression record stored in the overworld's SavedData storage.
    public static PlayerProgressionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Read one player's balance for a registered player-owned currency.
    public long balance(UUID playerUuid, Identifier currencyId) {
        return balances.getOrDefault(currencyId.toString(), Map.of()).getOrDefault(playerUuid, 0L);
    }

    // Store an exact player balance and remove empty currency maps from serialized state.
    public void setBalance(UUID playerUuid, Identifier currencyId, long amount) {
        Map<UUID, Long> values = balances.computeIfAbsent(currencyId.toString(), ignored -> new HashMap<>());
        putOrRemoveZero(values, playerUuid, amount);
        if (values.isEmpty()) balances.remove(currencyId.toString());
        setDirty();
    }

    // Check whether one player has already purchased a personal tree node.
    public boolean isSkillUnlocked(UUID playerUuid, String skillId) {
        return unlockedSkills.getOrDefault(playerUuid, Set.of()).contains(skillId);
    }

    // Persist one personal unlock after its complete purchase has succeeded.
    public void unlockSkill(UUID playerUuid, String skillId) {
        unlockedSkills.computeIfAbsent(playerUuid, ignored -> new HashSet<>()).add(skillId);
        setDirty();
    }

    // Return an immutable snapshot of one player's personal unlock keys.
    public Set<String> unlockedSkills(UUID playerUuid) {
        return Set.copyOf(unlockedSkills.getOrDefault(playerUuid, Set.of()));
    }

    // Test a namespaced claim ledger for a previously recorded unique reward identifier.
    public boolean hasClaim(UUID playerUuid, Identifier ledgerId, String claimId) {
        return claims.getOrDefault(ledgerId.toString(), Map.of()).getOrDefault(playerUuid, Set.of()).contains(claimId);
    }

    // Count lifetime claims in one ledger without being affected by later currency spending.
    public int claimCount(UUID playerUuid, Identifier ledgerId) {
        return claims.getOrDefault(ledgerId.toString(), Map.of()).getOrDefault(playerUuid, Set.of()).size();
    }

    // Record a unique claim in its ledger and mark the player progression save dirty.
    public void addClaim(UUID playerUuid, Identifier ledgerId, String claimId) {
        claims.computeIfAbsent(ledgerId.toString(), ignored -> new HashMap<>())
                .computeIfAbsent(playerUuid, ignored -> new HashSet<>()).add(claimId);
        setDirty();
    }

    // Merge a decoded fixed-format balance only when generic state does not already contain that player.
    private void mergeLegacyBalance(String currencyId, Map<UUID, Long> values) {
        if (!values.isEmpty()) copyBalances(values, balances.computeIfAbsent(currencyId, ignored -> new HashMap<>()));
    }

    // Copy positive balances while preserving values already decoded from the new generic format.
    private static void copyBalances(Map<UUID, Long> source, Map<UUID, Long> target) {
        source.forEach((uuid, amount) -> { if (amount > 0L) target.putIfAbsent(uuid, amount); });
    }

    // Deep-copy UUID-to-set mappings so decoded collections remain mutable and independently owned.
    private static Map<UUID, Set<String>> copySets(Map<UUID, Set<String>> source) {
        Map<UUID, Set<String>> copy = new HashMap<>();
        source.forEach((uuid, entries) -> copy.put(uuid, new HashSet<>(entries)));
        return copy;
    }

    // Convert serialized lists to sets to eliminate duplicate identifiers at runtime.
    private static Map<UUID, Set<String>> toSets(Map<UUID, List<String>> storedValues) {
        Map<UUID, Set<String>> values = new HashMap<>();
        storedValues.forEach((uuid, entries) -> values.put(uuid, new HashSet<>(entries)));
        return values;
    }

    // Convert identifier sets to sorted lists for deterministic SavedData output.
    private static Map<UUID, List<String>> toLists(Map<UUID, Set<String>> values) {
        Map<UUID, List<String>> stored = new HashMap<>();
        values.forEach((uuid, entries) -> stored.put(uuid, entries.stream().sorted().toList()));
        return stored;
    }

    // Store positive balances and remove zero balances rather than serializing redundant entries.
    private static void putOrRemoveZero(Map<UUID, Long> values, UUID playerUuid, long amount) {
        if (amount == 0L) values.remove(playerUuid); else values.put(playerUuid, amount);
    }
}
