package ekuzo.crazyworldprogression.progression;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.progression.global.GlobalProgressionData;
import ekuzo.crazyworldprogression.progression.player.PlayerProgressionData;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Generic persistence and purchase operations shared by all skill trees. */
public final class ProgressionService {
    // Prevent instantiation of the generic skill-unlock facade.
    private ProgressionService() {
    }

    // Read unlocks for the requested scope and expose legacy path-only keys under their new namespaced form.
    public static Set<String> getUnlocks(MinecraftServer server, UUID playerUuid,
                                         SkillTreeDefinition.SkillTreeType type) {
        Set<String> stored = type == SkillTreeDefinition.SkillTreeType.GLOBAL
                ? GlobalProgressionData.get(server).unlockedSkills()
                : PlayerProgressionData.get(server).unlockedSkills(playerUuid);
        Set<String> compatible = new HashSet<>(stored);
        for (SkillTreeDefinition tree : SkillTreeManager.getSkillTrees()) {
            if (tree.type() != type) continue;
            for (SkillTreeDefinition.SkillNode skill : tree.skills()) {
                if (stored.contains(tree.id().getPath() + "/" + skill.id())) {
                    compatible.add(tree.persistedKey(skill));
                }
            }
        }
        return Set.copyOf(compatible);
    }

    // Deduct all costs and persist the resulting unlock after the caller has checked tree prerequisites and policy.
    public static boolean purchase(MinecraftServer server, UUID playerUuid, SkillTreeDefinition tree,
                                   SkillTreeDefinition.SkillNode skill) {
        String key = tree.persistedKey(skill);
        if (isUnlocked(server, playerUuid, tree.type(), key) || !canAfford(server, playerUuid, skill.costs())) {
            return false;
        }
        for (Map.Entry<Identifier, Long> cost : skill.costs().entrySet()) {
            CurrencyService.debit(server, playerUuid, cost.getKey(), cost.getValue());
        }
        if (tree.type() == SkillTreeDefinition.SkillTreeType.GLOBAL) {
            GlobalProgressionData.get(server).unlockSkill(key);
        } else {
            PlayerProgressionData.get(server).unlockSkill(playerUuid, key);
        }
        return true;
    }

    // Check every dynamic cost against the correct global or player-owned wallet without changing state.
    public static boolean canAfford(MinecraftServer server, UUID playerUuid, Map<Identifier, Long> costs) {
        for (Map.Entry<Identifier, Long> cost : costs.entrySet()) {
            CurrencyDefinition definition = CurrencyRegistry.require(cost.getKey());
            UUID walletOwner = definition.scope() == CurrencyDefinition.CurrencyScope.GLOBAL ? null : playerUuid;
            if (CurrencyService.getBalance(server, walletOwner, cost.getKey()) < cost.getValue()) return false;
        }
        return true;
    }

    // Query the storage object selected by the tree scope for one canonical persisted skill key.
    private static boolean isUnlocked(MinecraftServer server, UUID playerUuid,
                                      SkillTreeDefinition.SkillTreeType type, String key) {
        return type == SkillTreeDefinition.SkillTreeType.GLOBAL
                ? GlobalProgressionData.get(server).isSkillUnlocked(key)
                : PlayerProgressionData.get(server).isSkillUnlocked(playerUuid, key);
    }
}

