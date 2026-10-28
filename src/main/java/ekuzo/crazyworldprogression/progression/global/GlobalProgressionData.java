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
import java.util.Set;

/** Dynamic global wallets and global skill unlocks. */
public final class GlobalProgressionData extends SavedData {
    private final Map<String, Long> balances = new HashMap<>();
    private final Set<String> unlockedSkills = new HashSet<>();

    public static final Codec<GlobalProgressionData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("balances", Map.of())
                    .forGetter(data -> data.balances),
            Codec.STRING.listOf().optionalFieldOf("unlocked_skills", List.of())
                    .forGetter(data -> data.unlockedSkills.stream().sorted().toList())
    ).apply(instance, GlobalProgressionData::new));

    public static final SavedDataType<GlobalProgressionData> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("global_progression"), GlobalProgressionData::new, CODEC, null);

    // Create empty global progression state for a new world.
    public GlobalProgressionData() {
    }

    // Rebuild mutable state from disk.
    private GlobalProgressionData(Map<String, Long> balances, List<String> unlockedSkills) {
        balances.forEach((id, amount) -> { if (amount > 0L) this.balances.put(id, amount); });
        this.unlockedSkills.addAll(unlockedSkills);
    }

    // Load the one global progression record stored in the overworld's SavedData storage.
    public static GlobalProgressionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // Read one registered global currency by its stable namespaced identifier.
    public long balance(Identifier currencyId) {
        return balances.getOrDefault(currencyId.toString(), 0L);
    }

    // Store an exact balance and omit zero entries to keep the save compact.
    public void setBalance(Identifier currencyId, long amount) {
        if (balance(currencyId) == amount) return;
        if (amount == 0L) balances.remove(currencyId.toString()); else balances.put(currencyId.toString(), amount);
        setDirty();
    }

    // Check whether a global tree node has already been purchased.
    public boolean isSkillUnlocked(String skillId) {
        return unlockedSkills.contains(skillId);
    }

    // Persist one global unlock after its complete purchase has succeeded.
    public void unlockSkill(String skillId) {
        if (unlockedSkills.add(skillId)) setDirty();
    }

    // Return an immutable snapshot so callers cannot mutate SavedData without marking it dirty.
    public Set<String> unlockedSkills() {
        return Set.copyOf(unlockedSkills);
    }

}

