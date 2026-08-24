package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.progression.ProgressionService;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillNode;
import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillTreeType;

public final class SkillTreeService {
    private SkillTreeService() {
    }

    public static SkillTreeSnapshot createSnapshot(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<TreeSnapshot> trees = new ArrayList<>();
        for (SkillTreeDefinition tree : SkillTreeManager.getSkillTrees()) {
            Set<String> unlocks = ProgressionService.getUnlocks(server, player.getUUID(), tree.type());
            Set<String> excludedSkillIds = excludedSkillIds(tree, unlocks);
            Map<String, String> idsByName = new LinkedHashMap<>();
            tree.skills().forEach(skill -> idsByName.put(skill.name(), skill.id()));
            SkillTreeRegistry.PurchaseDecision decision = SkillTreeRegistry.canPurchase(player, tree);
            List<NodeSnapshot> nodes = new ArrayList<>();
            for (SkillNode skill : tree.skills()) {
                String persistedKey = tree.persistedKey(skill);
                boolean unlocked = unlocks.contains(persistedKey);
                boolean previousUnlocked = prerequisitesUnlocked(tree, skill, unlocks);
                List<CostSnapshot> costs = costSnapshots(server, player, skill.costs());
                boolean affordable = costs.stream().allMatch(CostSnapshot::affordable);
                boolean excluded = excludedSkillIds.contains(skill.id()) && !unlocked;
                nodes.add(new NodeSnapshot(skill.id(), skill.name(), skill.germanName(), skill.description(),
                        skill.germanDescription(), skill.icon(), skill.previous().stream().map(idsByName::get).toList(),
                        skill.following(), costs, unlocked, previousUnlocked, affordable, excluded,
                        !unlocked && !excluded && previousUnlocked && affordable && decision.allowed()));
            }
            trees.add(new TreeSnapshot(tree.id().toString(), tree.name(), tree.germanName(), tree.icon(), tree.type(),
                    List.copyOf(nodes), treeBalances(server, player, tree), decision.allowed() ? "" : decision.reason()));
        }
        return new SkillTreeSnapshot(List.copyOf(trees));
    }

    public static void purchase(ServerPlayer player, String treeId, String skillId) {
        SkillTreeDefinition tree = SkillTreeManager.findTree(treeId);
        if (tree == null || !SkillTreeRegistry.canPurchase(player, tree).allowed()) return;
        SkillNode skill = tree.findSkill(skillId);
        if (skill == null) return;
        MinecraftServer server = player.level().getServer();
        Set<String> unlocks = ProgressionService.getUnlocks(server, player.getUUID(), tree.type());
        if (unlocks.contains(tree.persistedKey(skill)) || excludedSkillIds(tree, unlocks).contains(skill.id())
                || !prerequisitesUnlocked(tree, skill, unlocks)) return;
        if (ProgressionService.purchase(server, player.getUUID(), tree, skill)) {
            if (tree.type() == SkillTreeType.GLOBAL) SkillStatRegistry.refreshAll(server.getPlayerList().getPlayers());
            else SkillStatRegistry.refresh(player);
        }
    }

    private static List<CostSnapshot> costSnapshots(MinecraftServer server, ServerPlayer player,
                                                     Map<Identifier, Long> costs) {
        List<CostSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<Identifier, Long> cost : costs.entrySet()) {
            CurrencySnapshot currency = currencySnapshot(server, player, CurrencyRegistry.require(cost.getKey()));
            snapshots.add(new CostSnapshot(currency, cost.getValue(), currency.balance() >= cost.getValue()));
        }
        return List.copyOf(snapshots);
    }

    private static List<CurrencySnapshot> treeBalances(MinecraftServer server, ServerPlayer player,
                                                        SkillTreeDefinition tree) {
        Set<Identifier> used = new LinkedHashSet<>();
        tree.skills().forEach(skill -> used.addAll(skill.costs().keySet()));
        return CurrencyRegistry.values().stream().filter(currency -> used.contains(currency.id()))
                .map(currency -> currencySnapshot(server, player, currency)).toList();
    }

    private static CurrencySnapshot currencySnapshot(MinecraftServer server, ServerPlayer player,
                                                       CurrencyDefinition definition) {
        long balance = CurrencyService.getBalance(server, player.getUUID(), definition.id());
        return new CurrencySnapshot(definition.id().toString(), definition.displayName(), definition.abbreviation(),
                definition.icon().toString(), definition.iconU(), definition.iconV(), definition.iconWidth(),
                definition.iconHeight(), definition.color().name().toLowerCase(java.util.Locale.ROOT), balance);
    }

    private static boolean prerequisitesUnlocked(SkillTreeDefinition tree, SkillNode skill, Set<String> unlocks) {
        Map<String, SkillNode> byName = new LinkedHashMap<>();
        tree.skills().forEach(candidate -> byName.put(candidate.name(), candidate));
        return skill.previous().stream().map(byName::get).allMatch(previous -> unlocks.contains(tree.persistedKey(previous)));
    }

    private static Set<String> excludedSkillIds(SkillTreeDefinition tree, Set<String> unlocks) {
        Map<String, List<SkillNode>> childrenByParent = new LinkedHashMap<>();
        tree.skills().forEach(skill -> childrenByParent.put(skill.name(), new ArrayList<>()));
        for (SkillNode child : tree.skills()) child.previous().forEach(parent -> childrenByParent.get(parent).add(child));
        Set<String> excluded = new HashSet<>();
        for (SkillNode parent : tree.skills()) {
            if (parent.following() <= 0) continue;
            List<SkillNode> branches = childrenByParent.get(parent.name());
            long chosen = branches.stream().filter(branch -> unlocks.contains(tree.persistedKey(branch))).count();
            if (chosen < parent.following()) continue;
            for (SkillNode branch : branches) if (!unlocks.contains(tree.persistedKey(branch))) {
                excludeBranch(branch, childrenByParent, excluded);
            }
        }
        return Set.copyOf(excluded);
    }

    private static void excludeBranch(SkillNode skill, Map<String, List<SkillNode>> childrenByParent, Set<String> excluded) {
        if (!excluded.add(skill.id())) return;
        for (SkillNode child : childrenByParent.get(skill.name())) excludeBranch(child, childrenByParent, excluded);
    }

    public record SkillTreeSnapshot(List<TreeSnapshot> trees) { }

    public record TreeSnapshot(String id, String name, String germanName, String icon, SkillTreeType type,
                               List<NodeSnapshot> skills, List<CurrencySnapshot> balances,
                               String purchaseDeniedReason) { }

    public record NodeSnapshot(String id, String name, String germanName, String description,
                               String germanDescription, String icon, List<String> previous, int following,
                               List<CostSnapshot> costs, boolean unlocked, boolean prerequisitesUnlocked,
                               boolean affordable, boolean excluded, boolean canPurchase) { }

    public record CurrencySnapshot(String id, String displayName, String abbreviation, String icon,
                                   int iconU, int iconV, int iconWidth, int iconHeight,
                                   String color, long balance) { }

    public record CostSnapshot(CurrencySnapshot currency, long amount, boolean affordable) { }
}
