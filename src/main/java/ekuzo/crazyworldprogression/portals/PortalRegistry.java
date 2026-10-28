package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/** Extension registry for reusable dimension slots and temporary-instance lifecycle listeners. */
public final class PortalRegistry {
    private static final List<Identifier> INSTANCE_SLOTS = new ArrayList<>();
    private static final List<InstanceListener> LISTENERS = new ArrayList<>();

    static {
        // As many as config/crazy-world-progression.yml asks for (GeneratedWorlds makes the worlds).
        for (int index = 1; index <= ekuzo.crazyworldprogression.config.CwpConfig.get().instances(); index++) {
            INSTANCE_SLOTS.add(CrazyWorldProgression.id("portal_instance_" + index));
        }
    }

    // Prevent instantiation of the registry owner.
    private PortalRegistry() {
    }

    // Add a pre-registered empty dimension that CWP may lease for reconstructed templates.
    public static synchronized void registerInstanceSlot(Identifier dimension) {
        if (dimension == null) throw new IllegalArgumentException("Portal instance dimension must not be null");
        if (INSTANCE_SLOTS.contains(dimension)) throw new IllegalArgumentException("Duplicate portal slot " + dimension);
        INSTANCE_SLOTS.add(dimension);
    }

    // Register an integration listener for instance creation and expiry.
    public static synchronized void registerListener(InstanceListener listener) {
        if (listener == null) throw new IllegalArgumentException("Portal listener must not be null");
        LISTENERS.add(listener);
    }

    // Return the deterministic registered slot sequence.
    public static synchronized List<Identifier> instanceSlots() {
        return List.copyOf(INSTANCE_SLOTS);
    }

    // Notify integrations after a template was successfully reconstructed.
    static synchronized void created(MinecraftServer server, PortalService.ActiveInstance instance) {
        for (InstanceListener listener : LISTENERS) {
            try {
                listener.onCreated(server, instance);
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.error("Portal instance listener failed during creation of {}",
                        instance.portalId(), exception);
            }
        }
    }

    // Notify integrations immediately before an expired instance is cleared.
    static synchronized void expiring(MinecraftServer server, PortalService.ActiveInstance instance) {
        for (InstanceListener listener : LISTENERS) {
            try {
                listener.onExpiring(server, instance);
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.error("Portal instance listener failed during expiry of {}",
                        instance.portalId(), exception);
            }
        }
    }

    /** Optional gameplay hooks implemented by content mods without moving their logic into CWP. */
    public interface InstanceListener {
        default void onCreated(MinecraftServer server, PortalService.ActiveInstance instance) { onCreated(instance); }
        default void onExpiring(MinecraftServer server, PortalService.ActiveInstance instance) { onExpiring(instance); }
        // React after the destination template exists and can safely receive gameplay state.
        default void onCreated(PortalService.ActiveInstance instance) {
        }

        // Remove integration state before CWP clears blocks and releases the slot.
        default void onExpiring(PortalService.ActiveInstance instance) {
        }
    }
}
