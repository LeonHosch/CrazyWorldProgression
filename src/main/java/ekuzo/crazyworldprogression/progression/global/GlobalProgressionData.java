package ekuzo.crazyworldprogression.progression.global;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Dynamic global wallets and global skill unlocks. The name retains save compatibility. */
public final class GlobalProgressionData extends SavedData {
    private static final String LEGACY_KP = "echelon-core:kingdom_points";
    private final Map<String, Long> balances = new HashMap<>();
    private final Set<String> unlockedSkills = new HashSet<>();
    private UUID legacyElectedKing;

    public static final Codec<GlobalProgressionData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("balances", Map.of())
                    .forGetter(data -> data.balances),
            Codec.STRING.listOf().optionalFieldOf("unlocked_technologies", List.of())
                    .forGetter(data -> data.unlockedSkills.stream().sorted().toList()),
            Codec.LONG.optionalFieldOf("kingdom_points", 0L).forGetter(data -> 0L),
            Codec.STRING.xmap(UUID::fromString, UUID::toString).optionalFieldOf("elected_king")
                    .forGetter(data -> Optional.empty())
    ).apply(instance, GlobalProgressionData::new));

    public static final SavedDataType<GlobalProgressionData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("kingdom_progression"), GlobalProgressionData::new, CODEC, null);

    public GlobalProgressionData() {
    }

    private GlobalProgressionData(Map<String, Long> balances, List<String> unlockedSkills,
                                   long legacyKingdomPoints, Optional<UUID> legacyElectedKing) {
        balances.forEach((id, amount) -> { if (amount > 0L) this.balances.put(id, amount); });
        if (legacyKingdomPoints > 0L) this.balances.putIfAbsent(LEGACY_KP, legacyKingdomPoints);
        this.unlockedSkills.addAll(unlockedSkills);
        this.legacyElectedKing = legacyElectedKing.orElse(null);
    }

    public static GlobalProgressionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public long balance(Identifier currencyId) {
        return balances.getOrDefault(currencyId.toString(), 0L);
    }

    public void setBalance(Identifier currencyId, long amount) {
        if (amount == 0L) balances.remove(currencyId.toString()); else balances.put(currencyId.toString(), amount);
        setDirty();
    }

    public boolean isSkillUnlocked(String skillId) {
        return unlockedSkills.contains(skillId);
    }

    public void unlockSkill(String skillId) {
        unlockedSkills.add(skillId);
        setDirty();
    }

    public Set<String> unlockedSkills() {
        return Set.copyOf(unlockedSkills);
    }

    /** Lets the owning application migrate the pre-framework king exactly once. */
    public Optional<UUID> takeLegacyElectedKing() {
        Optional<UUID> result = Optional.ofNullable(legacyElectedKing);
        if (legacyElectedKing != null) {
            legacyElectedKing = null;
            setDirty();
        }
        return result;
    }
}

