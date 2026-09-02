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

    // Prevent instantiation of the process-wide skill-tree extension registry.
    private SkillTreeRegistry() {
    }

    // Register a dependent mod's resource directory and namespace for discovery during server startup.
    public static synchronized void registerSource(String namespace, String modId, String resourceDirectory) {
        TreeSource source = new TreeSource(namespace, modId, resourceDirectory);
        if (SOURCES.contains(source)) throw new IllegalArgumentException("Duplicate skill-tree source: " + source);
        SOURCES.add(source);
    }

    // Attach application-specific authorization to one exact namespaced tree identifier.
    public static synchronized void registerPurchasePolicy(Identifier treeId, PurchasePolicy policy) {
        if (POLICIES.putIfAbsent(treeId, policy) != null) {
            throw new IllegalArgumentException("Duplicate purchase policy for " + treeId);
        }
    }

    // Return an immutable source snapshot for the loader after all mod initializers have registered.
    public static synchronized List<TreeSource> sources() {
        return List.copyOf(SOURCES);
    }

    // Report whether a dependent mod attached special purchase authorization to one tree.
    public static synchronized boolean hasPurchasePolicy(Identifier treeId) {
        return POLICIES.containsKey(treeId);
    }

    // Return the number of application-specific purchase policies currently registered.
    public static synchronized int purchasePolicyCount() {
        return POLICIES.size();
    }

    // Evaluate a registered policy or allow the purchase when a tree has no application restriction.
    public static PurchaseDecision canPurchase(ServerPlayer player, SkillTreeDefinition tree) {
        PurchasePolicy policy;
        synchronized (SkillTreeRegistry.class) {
            policy = POLICIES.get(tree.id());
        }
        return policy == null ? PurchaseDecision.ALLOWED : policy.canPurchase(player, tree);
    }

    public record TreeSource(String namespace, String modId, String resourceDirectory) {
        // Reject incomplete source metadata before server startup attempts resource discovery.
        public TreeSource {
            if (namespace.isBlank() || modId.isBlank() || resourceDirectory.isBlank()) {
                throw new IllegalArgumentException("Skill-tree source values must not be blank");
            }
        }
    }

    public record PurchaseDecision(boolean allowed, String reason) {
        public static final PurchaseDecision ALLOWED = new PurchaseDecision(true, "");

        // Normalize absent denial text so snapshots never need null checks.
        public PurchaseDecision {
            reason = reason == null ? "" : reason;
        }

        // Create a denied decision with the player-facing explanation shown by the generic GUI.
        public static PurchaseDecision denied(String reason) {
            return new PurchaseDecision(false, reason);
        }
    }

    @FunctionalInterface
    public interface PurchasePolicy {
        // Decide whether this player may purchase from the tree without replacing normal cost validation.
        PurchaseDecision canPurchase(ServerPlayer player, SkillTreeDefinition tree);
    }
}
