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
                            List<AdminEntry> entries, PageLayout layout, String autoAction) {
        // Freeze extension-owned collections before they cross the network boundary.
        public AdminPage {
            tabs = List.copyOf(tabs);
            entries = List.copyOf(entries);
            if (layout == null) layout = PageLayout.STANDARD;
            if (autoAction == null) autoAction = "";
        }

        // Preserve the original page constructor for existing admin-module integrations.
        public AdminPage(String id, String title, String subtitle, List<AdminTab> tabs, List<AdminEntry> entries) {
            this(id, title, subtitle, tabs, entries, PageLayout.STANDARD, "");
        }

        // Preserve layout-aware page construction for integrations that do not use automatic field actions.
        public AdminPage(String id, String title, String subtitle, List<AdminTab> tabs, List<AdminEntry> entries,
                         PageLayout layout) {
            this(id, title, subtitle, tabs, entries, layout, "");
        }
    }

    /** Optional layout hints interpreted by CWP without coupling extensions to client classes. */
    public enum PageLayout {
        STANDARD,
        PREVIEW_CARDS,
        EDITOR
    }

    /** Compact page selector shown consistently across all registered modules. */
    public record AdminTab(String id, String label) {
    }

    /** One status card with optional server-authoritative action buttons. */
    public record AdminEntry(String title, String detail, Tone tone, List<AdminField> fields,
                             List<AdminAction> actions, String section, EntryPreview preview) {
        // Freeze the action list supplied by an extension.
        public AdminEntry {
            fields = List.copyOf(fields);
            actions = List.copyOf(actions);
            if (section == null) section = "";
        }

        // Preserve the concise constructor used by informational and button-only rows.
        public AdminEntry(String title, String detail, Tone tone, List<AdminAction> actions) {
            this(title, detail, tone, List.of(), actions, "", null);
        }

        // Preserve the original field-bearing constructor for existing admin-module integrations.
        public AdminEntry(String title, String detail, Tone tone, List<AdminField> fields,
                          List<AdminAction> actions) {
            this(title, detail, tone, fields, actions, "", null);
        }

        // Preserve preview-card construction while allowing editors to use explicit sections.
        public AdminEntry(String title, String detail, Tone tone, List<AdminField> fields,
                          List<AdminAction> actions, EntryPreview preview) {
            this(title, detail, tone, fields, actions, "", preview);
        }

        // Create an informational entry without controls.
        public static AdminEntry info(String title, String detail) {
            return new AdminEntry(title, detail, Tone.NORMAL, List.of(), List.of(), "", null);
        }

        // Create one compact form row under an explicit reusable editor section.
        public static AdminEntry editor(String section, String title, String detail, List<AdminField> fields,
                                        List<AdminAction> actions) {
            return new AdminEntry(title, detail, Tone.NORMAL, fields, actions, section, null);
        }
    }

    /** Small server-described visual shown beside an entry, such as a portal shape and color. */
    public record EntryPreview(String kind, String shape, int colorRgb) {
        // Reject incomplete preview metadata before it reaches the client renderer.
        public EntryPreview {
            if (kind == null || kind.isBlank() || shape == null || shape.isBlank()) {
                throw new IllegalArgumentException("Invalid CWP admin entry preview");
            }
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

        // Create an immutable value display for identifiers and other non-editable form context.
        public static AdminField readOnly(String id, String label, String value) {
            return new AdminField(id, label, "", value, Math.max(1, value.length()), List.of(), FieldType.READ_ONLY);
        }
    }

    /** Generic input presentation understood by CWP's shared administration screen. */
    public enum FieldType {
        TEXT,
        SELECT,
        READ_ONLY
    }

    /** One action sent back to the owning module; dangerous actions require a second click. */
    public record AdminAction(String id, String label, boolean dangerous, String targetPage, boolean wide) {
        // Preserve the original server-action constructor used by existing integrations.
        public AdminAction(String id, String label, boolean dangerous) {
            this(id, label, dangerous, "", false);
        }

        // Preserve direct navigation-action construction for source-compatible integrations.
        public AdminAction(String id, String label, boolean dangerous, String targetPage) {
            this(id, label, dangerous, targetPage, false);
        }

        // Create a client navigation control that opens another server-provided page.
        public static AdminAction navigate(String label, String targetPage) {
            return new AdminAction("", label, false, targetPage, false);
        }

        // Create a full-row editor action suited to position pickers and similar primary controls.
        public static AdminAction wide(String id, String label, boolean dangerous) {
            return new AdminAction(id, label, dangerous, "", true);
        }
    }

    /** Result shown in the panel header after a module action is handled. */
    public record ActionResult(String message, boolean error, String targetPage, boolean refresh) {
        // Preserve the original result constructor for existing module integrations.
        public ActionResult(String message, boolean error) {
            this(message, error, "", true);
        }

        // Preserve redirecting result construction while defaulting to a refreshed page response.
        public ActionResult(String message, boolean error, String targetPage) {
            this(message, error, targetPage, true);
        }

        // Create a successful action result.
        public static ActionResult success(String message) {
            return new ActionResult(message, false, "", true);
        }

        // Create a successful result that continues on another server-provided page.
        public static ActionResult success(String message, String targetPage) {
            return new ActionResult(message, false, targetPage, true);
        }

        // Acknowledge a high-frequency automatic field patch without rebuilding the client's open form.
        public static ActionResult silentSuccess() {
            return new ActionResult("", false, "", false);
        }

        // Create a rejected or failed action result.
        public static ActionResult failure(String message) {
            return new ActionResult(message, true, "", true);
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
