package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.progression.kingdom.KingdomProgressionService;
import ekuzo.crazyworldprogression.progression.player.PersonalCurrency;
import ekuzo.crazyworldprogression.progression.player.PlayerProgressionService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillCosts;
import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillNode;
import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillTreeType;

public final class SkillTreeService {
    // Prevent this static gameplay service from being instantiated.
    private SkillTreeService() {
    }

    // Build the complete server-authoritative screen state visible to one player.
    public static SkillTreeSnapshot createSnapshot(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Balances balances = balances(server, player);
        boolean electedKing = KingdomProgressionService.getElectedKing(server)
                .filter(player.getUUID()::equals)
                .isPresent();
        Set<String> globalUnlocks = KingdomProgressionService.getUnlockedTechnologies(server);
        Set<String> personalUnlocks = PlayerProgressionService.getUnlockedSkills(server, player.getUUID());
        List<TreeSnapshot> trees = new ArrayList<>();

        for (SkillTreeDefinition tree : SkillTreeManager.getSkillTrees()) {
            Set<String> unlocks = tree.type() == SkillTreeType.GLOBAL ? globalUnlocks : personalUnlocks;
            Set<String> excludedSkillIds = excludedSkillIds(tree, unlocks);
            Map<String, String> idsByName = new LinkedHashMap<>();
            tree.skills().forEach(skill -> idsByName.put(skill.name(), skill.id()));
            List<NodeSnapshot> nodes = new ArrayList<>();

            for (SkillNode skill : tree.skills()) {
                String persistedKey = tree.persistedKey(skill);
                boolean unlocked = unlocks.contains(persistedKey);
                boolean previousUnlocked = prerequisitesUnlocked(tree, skill, unlocks);
                boolean affordable = canAfford(tree.type(), skill.costs(), balances);
                boolean excluded = excludedSkillIds.contains(skill.id()) && !unlocked;
                boolean canPurchase = !unlocked
                        && !excluded
                        && previousUnlocked
                        && affordable
                        && (tree.type() != SkillTreeType.GLOBAL || electedKing);
                nodes.add(new NodeSnapshot(
                        skill.id(),
                        skill.name(),
                        skill.germanName(),
                        skill.description(),
                        skill.germanDescription(),
                        skill.icon(),
                        skill.previous().stream().map(idsByName::get).toList(),
                        skill.following(),
                        skill.costs(),
                        unlocked,
                        previousUnlocked,
                        affordable,
                        excluded,
                        canPurchase
                ));
            }
            trees.add(new TreeSnapshot(
                    tree.id(),
                    tree.name(),
                    tree.germanName(),
                    tree.icon(),
                    tree.type(),
                    List.copyOf(nodes)
            ));
        }
        return new SkillTreeSnapshot(List.copyOf(trees), balances, electedKing);
    }

    // Validate and execute one clicked node purchase without trusting client-side state.
    public static void purchase(ServerPlayer player, String treeId, String skillId) {
        SkillTreeDefinition tree = SkillTreeManager.findTree(treeId);
        if (tree == null) {
            return;
        }
        SkillNode skill = tree.findSkill(skillId);
        if (skill == null) {
            return;
        }

        MinecraftServer server = player.level().getServer();
        Set<String> unlocks = tree.type() == SkillTreeType.GLOBAL
                ? KingdomProgressionService.getUnlockedTechnologies(server)
                : PlayerProgressionService.getUnlockedSkills(server, player.getUUID());
        if (unlocks.contains(tree.persistedKey(skill))) {
            return;
        }
        if (excludedSkillIds(tree, unlocks).contains(skill.id())) {
            return;
        }
        if (!prerequisitesUnlocked(tree, skill, unlocks)) {
            return;
        }

        boolean unlocked = tree.type() == SkillTreeType.GLOBAL
                ? purchaseGlobal(player, tree, skill)
                : purchasePersonal(player, tree, skill);
        if (unlocked) {
            if (tree.type() == SkillTreeType.GLOBAL) {
                SkillStatRegistry.refreshAll(server.getPlayerList().getPlayers());
            } else {
                SkillStatRegistry.refresh(player);
            }
        }
    }

    // Purchase one global node with shared KP and the elected king's Fakhrul Currency.
    private static boolean purchaseGlobal(
            ServerPlayer player,
            SkillTreeDefinition tree,
            SkillNode skill
    ) {
        SkillCosts costs = skill.costs();
        KingdomProgressionService.TechnologyUnlockResult result = KingdomProgressionService.unlockTechnology(
                player.level().getServer(),
                player.getUUID(),
                tree.persistedKey(skill),
                costs.kingdomPoints(),
                costs.fakhrulCurrency()
        );
        return result == KingdomProgressionService.TechnologyUnlockResult.UNLOCKED;
    }

    // Purchase one personal node with the acting player's three eligible currencies.
    private static boolean purchasePersonal(
            ServerPlayer player,
            SkillTreeDefinition tree,
            SkillNode skill
    ) {
        SkillCosts costs = skill.costs();
        PlayerProgressionService.SkillUnlockResult result = PlayerProgressionService.unlockSkill(
                player.level().getServer(),
                player.getUUID(),
                tree.persistedKey(skill),
                costs.echelonPoints(),
                costs.fakhrulCurrency(),
                costs.powerfulSouls()
        );
        return result == PlayerProgressionService.SkillUnlockResult.UNLOCKED;
    }

    // Check whether every name-based dependency has a persisted unlock key.
    private static boolean prerequisitesUnlocked(
            SkillTreeDefinition tree,
            SkillNode skill,
            Set<String> unlocks
    ) {
        Map<String, SkillNode> byName = new LinkedHashMap<>();
        tree.skills().forEach(candidate -> byName.put(candidate.name(), candidate));
        return skill.previous().stream()
                .map(byName::get)
                .allMatch(previous -> unlocks.contains(tree.persistedKey(previous)));
    }

    // Derive every permanently excluded branch from the already unlocked direct successors.
    private static Set<String> excludedSkillIds(SkillTreeDefinition tree, Set<String> unlocks) {
        Map<String, List<SkillNode>> childrenByParent = new LinkedHashMap<>();
        tree.skills().forEach(skill -> childrenByParent.put(skill.name(), new ArrayList<>()));
        for (SkillNode child : tree.skills()) {
            child.previous().forEach(parent -> childrenByParent.get(parent).add(child));
        }

        Set<String> excluded = new HashSet<>();
        for (SkillNode parent : tree.skills()) {
            if (parent.following() <= 0) {
                continue;
            }
            List<SkillNode> branches = childrenByParent.get(parent.name());
            long chosenBranches = branches.stream()
                    .filter(branch -> unlocks.contains(tree.persistedKey(branch)))
                    .count();
            if (chosenBranches < parent.following()) {
                continue;
            }
            for (SkillNode branch : branches) {
                if (!unlocks.contains(tree.persistedKey(branch))) {
                    excludeBranch(branch, childrenByParent, excluded);
                }
            }
        }
        return Set.copyOf(excluded);
    }

    // Mark one unchosen branch and all nodes that depend on it as permanently excluded.
    private static void excludeBranch(
            SkillNode skill,
            Map<String, List<SkillNode>> childrenByParent,
            Set<String> excluded
    ) {
        if (!excluded.add(skill.id())) {
            return;
        }
        for (SkillNode child : childrenByParent.get(skill.name())) {
            excludeBranch(child, childrenByParent, excluded);
        }
    }

    // Test the appropriate shared or personal balances without mutating them.
    private static boolean canAfford(SkillTreeType type, SkillCosts costs, Balances balances) {
        if (type == SkillTreeType.GLOBAL) {
            return balances.kingdomPoints() >= costs.kingdomPoints()
                    && balances.kingFakhrulCurrency() >= costs.fakhrulCurrency();
        }
        return balances.echelonPoints() >= costs.echelonPoints()
                && balances.fakhrulCurrency() >= costs.fakhrulCurrency()
                && balances.powerfulSouls() >= costs.powerfulSouls();
    }

    // Read the viewer's balances together with the elected king's Fakhrul Currency.
    private static Balances balances(MinecraftServer server, ServerPlayer player) {
        long kingFakhrulCurrency = KingdomProgressionService.getElectedKing(server)
                .map(kingUuid -> PlayerProgressionService.getBalance(
                        server,
                        kingUuid,
                        PersonalCurrency.FAKHRUL_CURRENCY
                ))
                .orElse(0L);
        return new Balances(
                KingdomProgressionService.getKingdomPoints(server),
                PlayerProgressionService.getBalance(server, player.getUUID(), PersonalCurrency.ECHELON_POINTS),
                PlayerProgressionService.getBalance(server, player.getUUID(), PersonalCurrency.FAKHRUL_CURRENCY),
                PlayerProgressionService.getPowerfulSouls(server, player.getUUID()),
                kingFakhrulCurrency
        );
    }

    public record SkillTreeSnapshot(
            List<TreeSnapshot> trees,
            Balances balances,
            boolean electedKing
    ) {
    }

    public record TreeSnapshot(
            String id,
            String name,
            String germanName,
            String icon,
            SkillTreeType type,
            List<NodeSnapshot> skills
    ) {
    }

    public record NodeSnapshot(
            String id,
            String name,
            String germanName,
            String description,
            String germanDescription,
            String icon,
            List<String> previous,
            int following,
            SkillCosts costs,
            boolean unlocked,
            boolean prerequisitesUnlocked,
            boolean affordable,
            boolean excluded,
            boolean canPurchase
    ) {
    }

    public record Balances(
            long kingdomPoints,
            long echelonPoints,
            long fakhrulCurrency,
            long powerfulSouls,
            long kingFakhrulCurrency
    ) {
    }

}
