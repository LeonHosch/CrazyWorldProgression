package ekuzo.crazyworldprogression.admin;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public, data-driven extension point for modules displayed by CWP's admin panel. */
public final class AdminPanelRegistry {
    private static final Map<Identifier, AdminModule> MODULES = new LinkedHashMap<>();

    // Prevent construction of the process-wide module registry.
    private AdminPanelRegistry() {
    }

    // Register one uniquely identified module during common mod initialization.
    public static synchronized AdminModule register(AdminModule module) {
        if (MODULES.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate CWP admin module: " + module.id());
        }
        return module;
    }

    // Return registered modules in deterministic registration order.
    public static synchronized List<AdminModule> modules() {
        return List.copyOf(MODULES.values());
    }

    // Resolve an optional module selected by a client request.
    public static synchronized AdminModule module(Identifier id) {
        return MODULES.get(id);
    }

    /** One top-level mod button and its server-owned page and action providers. */
    public record AdminModule(Identifier id, String displayName, String description, String icon,
                              String defaultPage, PageProvider pages, ActionHandler actions) {
        // Validate extension metadata early so a broken integration cannot break the admin screen later.
        public AdminModule {
            if (id == null || displayName == null || displayName.isBlank() || description == null
                    || icon == null || icon.isBlank() || defaultPage == null || defaultPage.isBlank()
                    || pages == null || actions == null) {
                throw new IllegalArgumentException("CWP admin module fields must be complete");
            }
        }
    }

    /** Complete generic page rendered by CWP, including navigation, information, and controls. */
    public record AdminPage(String id, String title, String subtitle, List<AdminTab> tabs,
                            List<AdminEntry> entries) {
        // Freeze extension-owned collections before they cross the network boundary.
        public AdminPage {
            tabs = List.copyOf(tabs);
            entries = List.copyOf(entries);
        }
    }

    /** Compact page selector shown consistently across all registered modules. */
    public record AdminTab(String id, String label) {
    }

    /** One status card with optional server-authoritative action buttons. */
    public record AdminEntry(String title, String detail, Tone tone, List<AdminField> fields,
                             List<AdminAction> actions) {
        // Freeze the action list supplied by an extension.
        public AdminEntry {
            fields = List.copyOf(fields);
            actions = List.copyOf(actions);
        }

        // Preserve the concise constructor used by informational and button-only rows.
        public AdminEntry(String title, String detail, Tone tone, List<AdminAction> actions) {
            this(title, detail, tone, List.of(), actions);
        }

        // Create an informational entry without controls.
        public static AdminEntry info(String title, String detail) {
            return new AdminEntry(title, detail, Tone.NORMAL, List.of(), List.of());
        }
    }

    /** One reusable text field whose optional suggestions are rendered and completed by CWP. */
    public record AdminField(String id, String label, String placeholder, String initialValue,
                             int maxLength, List<String> suggestions, FieldType type) {
        // Validate field identity and freeze its server-provided completion candidates.
        public AdminField {
            if (id == null || id.isBlank() || label == null || placeholder == null || initialValue == null
                    || maxLength < 1 || type == null) throw new IllegalArgumentException("Invalid CWP admin field");
            suggestions = List.copyOf(suggestions);
            if (type == FieldType.SELECT && (suggestions.isEmpty() || !suggestions.contains(initialValue))) {
                throw new IllegalArgumentException("Select fields need options and an initial value from that list");
            }
        }

        // Preserve the original full text-field constructor for existing admin-module integrations.
        public AdminField(String id, String label, String placeholder, String initialValue,
                          int maxLength, List<String> suggestions) {
            this(id, label, placeholder, initialValue, maxLength, suggestions, FieldType.TEXT);
        }

        // Create an empty suggested field whose example is displayed as placeholder text.
        public AdminField(String id, String label, String placeholder, int maxLength, List<String> suggestions) {
            this(id, label, placeholder, "", maxLength, suggestions, FieldType.TEXT);
        }

        // Create an empty ordinary field without completion candidates.
        public static AdminField text(String id, String label, String placeholder, int maxLength) {
            return new AdminField(id, label, placeholder, "", maxLength, List.of(), FieldType.TEXT);
        }

        // Create a constrained dropdown whose submitted value must come from the supplied option list.
        public static AdminField select(String id, String label, String initialValue, List<String> options) {
            int maxLength = options.stream().mapToInt(String::length).max().orElse(1);
            return new AdminField(id, label, "", initialValue, maxLength, options, FieldType.SELECT);
        }
    }

    /** Generic input presentation understood by CWP's shared administration screen. */
    public enum FieldType {
        TEXT,
        SELECT
    }

    /** One action sent back to the owning module; dangerous actions require a second click. */
    public record AdminAction(String id, String label, boolean dangerous) {
    }

    /** Result shown in the panel header after a module action is handled. */
    public record ActionResult(String message, boolean error) {
        // Create a successful action result.
        public static ActionResult success(String message) {
            return new ActionResult(message, false);
        }

        // Create a rejected or failed action result.
        public static ActionResult failure(String message) {
            return new ActionResult(message, true);
        }
    }

    /** Visual severity used by CWP without exposing client-only rendering classes to extensions. */
    public enum Tone {
        NORMAL,
        GOOD,
        WARNING,
        DANGER
    }

    /** Produces a fresh page from authoritative server state for one administrator. */
    @FunctionalInterface
    public interface PageProvider {
        // Build the requested page, falling back to the module default when appropriate.
        AdminPage create(ServerPlayer player, String pageId);
    }

    /** Executes an opaque action owned by the module that published it. */
    @FunctionalInterface
    public interface ActionHandler {
        // Validate and apply one action, then return concise operator feedback.
        ActionResult execute(ServerPlayer player, String pageId, String actionId, Map<String, String> values);
    }
}
