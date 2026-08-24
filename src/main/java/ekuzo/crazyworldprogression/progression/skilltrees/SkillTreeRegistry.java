package ekuzo.crazyworldprogression.progression.skilltrees;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extension API for tree resources and application-specific purchase authorization. */
public final class SkillTreeRegistry {
    private static final List<TreeSource> SOURCES = new ArrayList<>();
    private static final Map<Identifier, PurchasePolicy> POLICIES = new LinkedHashMap<>();

    private SkillTreeRegistry() {
    }

    public static synchronized void registerSource(String namespace, String modId, String resourceDirectory) {
        TreeSource source = new TreeSource(namespace, modId, resourceDirectory);
        if (SOURCES.contains(source)) throw new IllegalArgumentException("Duplicate skill-tree source: " + source);
        SOURCES.add(source);
    }

    public static synchronized void registerPurchasePolicy(Identifier treeId, PurchasePolicy policy) {
        if (POLICIES.putIfAbsent(treeId, policy) != null) {
            throw new IllegalArgumentException("Duplicate purchase policy for " + treeId);
        }
    }

    static synchronized List<TreeSource> sources() {
        return List.copyOf(SOURCES);
    }

    public static PurchaseDecision canPurchase(ServerPlayer player, SkillTreeDefinition tree) {
        PurchasePolicy policy;
        synchronized (SkillTreeRegistry.class) {
            policy = POLICIES.get(tree.id());
        }
        return policy == null ? PurchaseDecision.ALLOWED : policy.canPurchase(player, tree);
    }

    public record TreeSource(String namespace, String modId, String resourceDirectory) {
        public TreeSource {
            if (namespace.isBlank() || modId.isBlank() || resourceDirectory.isBlank()) {
                throw new IllegalArgumentException("Skill-tree source values must not be blank");
            }
        }
    }

    public record PurchaseDecision(boolean allowed, String reason) {
        public static final PurchaseDecision ALLOWED = new PurchaseDecision(true, "");

        public PurchaseDecision {
            reason = reason == null ? "" : reason;
        }

        public static PurchaseDecision denied(String reason) {
            return new PurchaseDecision(false, reason);
        }
    }

    @FunctionalInterface
    public interface PurchasePolicy {
        PurchaseDecision canPurchase(ServerPlayer player, SkillTreeDefinition tree);
    }
}
