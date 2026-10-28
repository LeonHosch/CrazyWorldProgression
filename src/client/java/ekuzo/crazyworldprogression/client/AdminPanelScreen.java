package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.admin.AdminPanelNetworking;
import ekuzo.crazyworldprogression.admin.AdminPanelRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import ekuzo.crazyworldprogression.mixin.client.MultiLineEditBoxAccessor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

/** Responsive generic administration screen whose content and controls are supplied by server modules. */
public final class AdminPanelScreen extends Screen {
    /** The panel draws at GUI scale 2 at most, so more fits on screen than at the game's usual scale. */
    private static final int PANEL_GUI_SCALE = 2;
    private static String lastModule = "";
    private static String lastPage = "";
    private static int lastScroll;
    private static final int MAX_PANEL_WIDTH = 1320;
    private static final int PANEL_MARGIN = 12;
    private static final int SIDEBAR_WIDTH = 118;
    private static final int HEADER_HEIGHT = 46;
    private static final int FOOTER_HEIGHT = 18;
    private static final int SECTION_HEADING_HEIGHT = 14;
    private static final int ROW_HEIGHT = 30;
    private static final int FIELD_ROW_HEIGHT = 62;
    private static final int PORTAL_ROW_HEIGHT = 40;
    private static final List<String> WORKSPACES = List.of("moderate", "build", "logs");
    private static final int EDITOR_FIELD_ROW_HEIGHT = 35;
    private static final int EDITOR_ACTION_ROW_HEIGHT = 26;
    private static final int EDITOR_SECTION_HEIGHT = 16;
    private static final int ROW_GAP = 5;
    private final Screen parent;
    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private final Map<String, String> formValues = new HashMap<>();
    private final Map<String, String> pendingAutoValues = new HashMap<>();
    private final Map<String, EditBox> editBoxes = new HashMap<>();
    private MultiLineEditBox codeBox;
    /** Inputs panel right of the source editor; zero width when the panel is too narrow for it. */
    private int codeHintX, codeHintY, codeHintWidth, codeHintHeight;
    /** Inner padding of MultiLineEditBox (AbstractTextAreaWidget.INNER_PADDING). */
    private static final int CODE_BOX_PADDING = 4;
    /** Space between the text cursor and the faded completion preview. */
    private static final int GHOST_GAP = 3;
    /** Values the server sent for script inputs (templates, loot pools, ...), by list name. */
    private final Map<String, List<String>> codeValues = new HashMap<>();
    private static final Map<String, List<String>> CLIENT_VALUE_CACHE = new HashMap<>();
    private List<String> codeMatches = List.of();
    private int codeMatchIndex;
    private String codeMatchKey = "";
    private String codeDismissedKey;
    private static final int CODE_MATCH_ROWS = 8;
    private final Map<String, AdminPanelRegistry.AdminField> fieldDefinitions = new HashMap<>();
    private AdminPanelNetworking.PanelSnapshot snapshot;
    /** Content area right of the sidebar; the whole panel is shellLeft..shellLeft + shellWidth. */
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int shellLeft;
    private int shellWidth;
    private int scroll;
    private String pendingDangerousAction;
    private String expandedSelect;
    private SelectAnchor selectAnchor;
    private long autoSaveAtMillis;
    private float panelRenderScale = 1.0F;
    private final Map<String, String> itemNameCache = new HashMap<>();
    private String dropdownField;
    private List<String> dropdownMatches = List.of();
    private int dropdownIndex;
    private static final int DROPDOWN_ROWS = 8;
    private static final int DROPDOWN_ROW_HEIGHT = 18;

    // Create a panel that returns to the screen from which the command opened it.
    public AdminPanelScreen(Screen parent, AdminPanelNetworking.PanelSnapshot snapshot) {
        super(Component.literal("CWP Administration"));
        this.parent = parent;
        this.snapshot = withoutBackActions(snapshot);
        if (snapshot.selectedModule().equals(lastModule) && pageId(snapshot).equals(lastPage)) {
            scroll = lastScroll;
        }
        seedFormValues();
    }

    // Apply authoritative refreshed state after navigation or a completed action.
    public void updateSnapshot(AdminPanelNetworking.PanelSnapshot snapshot) {
        boolean changedPage = this.snapshot == null
                || !this.snapshot.selectedModule().equals(snapshot.selectedModule())
                || !pageId(this.snapshot).equals(pageId(snapshot));
        snapshot = withoutBackActions(snapshot);
        this.snapshot = snapshot;
        Runnable pendingLeave = afterSave;
        afterSave = null;
        if (!changedPage && snapshot.error() && snapshot.page() != null
                && snapshot.page().layout() == AdminPanelRegistry.PageLayout.CODE) return;
        // "Save and leave": only leave when the save worked and the save itself did not already move on.
        if (pendingLeave != null && !snapshot.error() && !changedPage) {
            formValues.clear();
            seedFormValues();
            pendingLeave.run();
            return;
        }
        boolean successfulAction = snapshot.notice() != null && !snapshot.notice().isBlank() && !snapshot.error();
        if (changedPage) {
            scroll = 0;
            formValues.clear();
            pendingAutoValues.clear();
            autoSaveAtMillis = 0L;
            expandedSelect = null;
        }
        if (changedPage) seedFormValues();
        pendingDangerousAction = null;
        if (!changedPage && snapshot.page() != null
                && snapshot.page().layout() == AdminPanelRegistry.PageLayout.EDITOR
                && !snapshot.page().autoAction().isBlank()) return;
        if (successfulAction) {
            formValues.clear();
            seedFormValues();
        }
        rebuildWidgets();
    }

    // Lay out responsive module, navigation, row-action, refresh, and close buttons.
    @Override
    protected void init() {
        updatePanelScale();
        closeDropdown();
        editBoxes.clear();
        codeBox = null;
        fieldDefinitions.clear();
        selectAnchor = null;
        shellWidth = Math.min(MAX_PANEL_WIDTH, width - PANEL_MARGIN * 2);
        panelHeight = Math.max(200, height - PANEL_MARGIN * 2);
        shellLeft = (width - shellWidth) / 2;
        top = (height - panelHeight) / 2;
        left = shellLeft + SIDEBAR_WIDTH;
        panelWidth = shellWidth - SIDEBAR_WIDTH;
        buildSidebar();
        if (isDashboard()) buildDashboardButtons();
        else buildModuleButtons();
        int right = left + panelWidth - 6;
        Button close = Button.builder(Component.literal("✕"), ignored -> onClose())
                .bounds(right - 18, top + 5, 18, 18).build();
        close.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Close")));
        addRenderableWidget(close);
        Button refresh = Button.builder(Component.literal("⟳"), ignored -> {
                    flushAutoSave();
                    request(snapshot.selectedModule(), pageId(snapshot));
                })
                .bounds(right - 40, top + 5, 18, 18).build();
        refresh.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Refresh")));
        addRenderableWidget(refresh);
        if (backTarget != null) {
            String target = backTarget;
            addRenderableWidget(Button.builder(Component.literal("‹ Back"), ignored -> leave(() -> {
                        flushAutoSave();
                        request(snapshot.selectedModule(), target);
                    }))
                    .bounds(right - 52, top + 26, 52, 16).build());
        }
    }

    /** Runs after the next successful snapshot once "Save and leave" was chosen. */
    private Runnable afterSave;

    /**
     * True when a field differs from what the page was opened with. Pages whose autoAction persists every
     * edit right away (named …autosave) never count as unsaved.
     */
    private boolean hasUnsavedChanges() {
        if (isDashboard() || snapshot.page().autoAction().contains("autosave")) return false;
        for (AdminPanelRegistry.AdminEntry entry : snapshot.page().entries())
            for (AdminPanelRegistry.AdminField field : entry.fields()) {
                if (field.type() == AdminPanelRegistry.FieldType.READ_ONLY) continue;
                String value = formValues.get(field.id());
                if (value != null && !value.equals(field.initialValue())) return true;
            }
        return false;
    }

    /** The page's own Save button (first action labelled Save…), used for "Save and leave". */
    private AdminPanelRegistry.AdminAction saveAction() {
        for (AdminPanelRegistry.AdminEntry entry : snapshot.page().entries())
            for (AdminPanelRegistry.AdminAction action : entry.actions())
                if (action.label().startsWith("Save") && !action.dangerous()
                        && (action.targetPage() == null || action.targetPage().isBlank())) return action;
        return null;
    }

    /** Leaving the page: asks first when there are unsaved changes. */
    private void leave(Runnable go) {
        if (!hasUnsavedChanges()) {
            go.run();
            return;
        }
        AdminPanelRegistry.AdminAction save = saveAction();
        minecraft.gui.setScreen(new UnsavedChangesScreen(this, save == null ? null : () -> {
            afterSave = go;
            activate(save);
        }, go));
    }

    /** Where the page's Back button leads, shown in the header instead of in the page's rows. */
    private String backTarget;

    /**
     * Takes every "Back" navigation button out of the page's rows; the first one becomes the header's Back
     * button. Rows that only held a Back button disappear.
     */
    private AdminPanelNetworking.PanelSnapshot withoutBackActions(AdminPanelNetworking.PanelSnapshot value) {
        backTarget = null;
        if (value == null || value.page() == null) return value;
        AdminPanelRegistry.AdminPage page = value.page();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        for (AdminPanelRegistry.AdminEntry entry : page.entries()) {
            List<AdminPanelRegistry.AdminAction> actions = new ArrayList<>();
            boolean hadBack = false;
            for (AdminPanelRegistry.AdminAction action : entry.actions()) {
                String label = action.label();
                boolean back = (label.equals("Back") || label.startsWith("Back to ") || label.equals("Cancel"))
                        && action.targetPage() != null && !action.targetPage().isBlank();
                if (!back) { actions.add(action); continue; }
                hadBack = true;
                if (backTarget == null) backTarget = action.targetPage();
            }
            if (!hadBack) { entries.add(entry); continue; }
            if (actions.isEmpty() && entry.fields().isEmpty()) continue;
            entries.add(new AdminPanelRegistry.AdminEntry(entry.title(), entry.detail(), entry.tone(), entry.fields(),
                    actions, entry.section(), entry.preview()));
        }
        return new AdminPanelNetworking.PanelSnapshot(value.modules(), value.selectedModule(),
                new AdminPanelRegistry.AdminPage(page.id(), page.title(), page.subtitle(), page.tabs(), entries,
                        page.layout(), page.autoAction()), value.notice(), value.error(), value.navigation());
    }

    /** Moderate / Build / Logs switch and the pages of the current workspace, from every module. */
    private void buildSidebar() {
        List<AdminPanelNetworking.NavTab> navigation = snapshot.navigation();
        if (navigation == null || navigation.isEmpty()) return;
        String workspace = currentWorkspace();
        int x = shellLeft + 8;
        int buttonWidth = SIDEBAR_WIDTH - 16;
        int y = top + 10;
        for (String option : WORKSPACES) {
            AdminPanelNetworking.NavTab first = navigation.stream().filter(tab -> tab.workspace().equals(option))
                    .findFirst().orElse(null);
            if (first == null) continue;
            Button button = Button.builder(Component.literal(workspaceName(option)), ignored -> leave(() -> {
                flushAutoSave();
                request(first.moduleId(), first.pageId());
            })).bounds(x, y, buttonWidth, 18).build();
            button.active = !option.equals(workspace);
            addRenderableWidget(button);
            y += 20;
        }
        y += 12;
        AdminPanelNetworking.NavTab selected = selectedTab();
        for (AdminPanelNetworking.NavTab tab : navigation) {
            if (!tab.workspace().equals(workspace)) continue;
            if (y + 16 > top + panelHeight - 6) break;
            Button button = Button.builder(Component.literal(trim(tab.label(), buttonWidth - 8)), ignored -> leave(() -> {
                flushAutoSave();
                request(tab.moduleId(), tab.pageId());
            })).bounds(x, y, buttonWidth, 16).build();
            button.active = tab != selected;
            addRenderableWidget(button);
            y += 18;
        }
    }

    private static String workspaceName(String workspace) {
        return switch (workspace) {
            case "moderate" -> "Moderate";
            case "logs" -> "Logs";
            default -> "Build";
        };
    }

    /** The sidebar entry of the open page: same module, and the longest page ID the open page starts with. */
    private AdminPanelNetworking.NavTab selectedTab() {
        if (snapshot.navigation() == null || isDashboard()) return null;
        String page = snapshot.page().id();
        AdminPanelNetworking.NavTab best = null;
        for (AdminPanelNetworking.NavTab tab : snapshot.navigation()) {
            if (!tab.moduleId().equals(snapshot.selectedModule()) || !isSelectedTab(page, tab.pageId())) continue;
            if (best == null || tab.pageId().length() > best.pageId().length()) best = tab;
        }
        return best;
    }

    private static String lastWorkspace = "moderate";

    private String currentWorkspace() {
        AdminPanelNetworking.NavTab selected = selectedTab();
        if (selected != null) lastWorkspace = selected.workspace();
        return lastWorkspace;
    }

    // Add one full-width, readable button for every registered mod module.
    private void buildDashboardButtons() {
        int y = top + HEADER_HEIGHT;
        for (AdminPanelNetworking.ModuleSnapshot module : snapshot.modules()) {
            if (y + 30 > top + panelHeight - FOOTER_HEIGHT) break;
            addRenderableWidget(Button.builder(Component.literal(module.displayName()),
                            ignored -> request(module.id(), ""))
                    .bounds(left + 14, y, panelWidth - 28, 26).build());
            y += 48;
        }
    }

    // Add Back, tab, and visible row action buttons for the selected module page.
    private void buildModuleButtons() {
        AdminPanelRegistry.AdminPage page = snapshot.page();
        List<AdminPanelRegistry.AdminEntry> entries = page.entries();
        if (page.layout() == AdminPanelRegistry.PageLayout.EDITOR) {
            buildEditorWidgets();
            addSelectOptions();
            return;
        }
        if (page.layout() == AdminPanelRegistry.PageLayout.CODE) {
            buildCodeWidgets();
            return;
        }
        if (page.layout() == AdminPanelRegistry.PageLayout.PREVIEW_CARDS) {
            for (PreviewPlacement placement : previewPlacements()) {
                addActionButtons(placement.entry(), placement.y(), placement.x() + 8,
                        placement.x() + placement.width() - 8, false);
            }
            return;
        }
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - visible)));
        for (int visibleIndex = 0; visibleIndex < visible && scroll + visibleIndex < entries.size(); visibleIndex++) {
            int entryIndex = scroll + visibleIndex;
            AdminPanelRegistry.AdminEntry entry = entries.get(entryIndex);
            int y = contentTop() + visibleIndex * (rowHeight() + ROW_GAP);
            addFields(entry, y, left + 18, panelWidth - 36, false);
            addActionButtons(entry, y, left + 18, left + panelWidth - 14, false);
        }
        addSelectOptions();
    }

    // Editor pages: every section is a box, boxes fill the columns like a dashboard.
    private void buildEditorWidgets() {
        for (EditorPlacement placement : editorPlacements()) {
            if (!placement.visible()) continue;
            AdminPanelRegistry.AdminEntry entry = placement.entry();
            addFields(entry, placement.y(), placement.x() + 6, placement.width() - 12, true);
            addActionButtons(entry, placement.y(), placement.x() + 6, placement.x() + placement.width() - 6, true,
                    placement.height());
        }
    }

    private void buildCodeWidgets() {
        AdminPanelRegistry.AdminField source = snapshot.page().entries().stream()
                .flatMap(entry -> entry.fields().stream())
                .filter(field -> field.id().equals("script_source"))
                .findFirst().orElseThrow();
        int x = left + 14;
        int y = contentTop() + 18;
        int areaWidth = panelWidth - 28;
        codeHintWidth = areaWidth >= 480 ? Math.max(160, Math.min(230, areaWidth / 3)) : 0;
        int editorWidth = areaWidth - (codeHintWidth > 0 ? codeHintWidth + 8 : 0);
        int editorHeight = Math.max(80, top + panelHeight - FOOTER_HEIGHT - 42 - y);
        codeHintX = x + editorWidth + 8;
        codeHintY = y;
        codeHintHeight = editorHeight;
        codeBox = MultiLineEditBox.builder().setX(x).setY(y)
                .setPlaceholder(Component.literal("script <id>"))
                .build(font, editorWidth, editorHeight, Component.literal("MobForge script source"));
        codeBox.setCharacterLimit(source.maxLength());
        codeBox.setValue(formValues.getOrDefault(source.id(), source.initialValue()));
        codeBox.setValueListener(value -> formValues.put(source.id(), value));
        codeValues.clear();
        for (String entry : source.suggestions()) {
            int tab = entry.indexOf('\t');
            if (tab > 0) codeValues.computeIfAbsent(entry.substring(0, tab), ignored -> new ArrayList<>())
                    .add(entry.substring(tab + 1));
        }
        addRenderableWidget(codeBox);
        int buttonY = y + editorHeight + 7;
        for (AdminPanelRegistry.AdminEntry entry : snapshot.page().entries()) {
            for (AdminPanelRegistry.AdminAction action : entry.actions()) {
                int buttonWidth = 112;
                int buttonX = action.wide()
                        ? x + areaWidth - buttonWidth : x;
                String label = action.dangerous() && action.id().equals(pendingDangerousAction)
                        ? "Confirm" : action.label();
                addRenderableWidget(Button.builder(Component.literal(label), ignored -> activate(action))
                        .bounds(buttonX, buttonY, buttonWidth, 20).build());
            }
        }
    }

    // Add generic text inputs and retain their values while the current page rebuilds its widgets.
    private void addFields(AdminPanelRegistry.AdminEntry entry, int y, int areaX, int areaWidth, boolean compact) {
        if (entry.fields().isEmpty()) return;
        int actionWidth = actionWidth(entry) + (compact ? previewWidth(entry) : 0);
        int available = Math.max(100, areaWidth - actionWidth);
        int dividedWidth = (available - (entry.fields().size() - 1) * 6) / entry.fields().size();
        int fieldWidth = compact ? Math.max(40, dividedWidth) : Math.max(72, Math.min(150, dividedWidth));
        int x = areaX;
        int fieldY = y + (compact ? 13 : 35);
        for (AdminPanelRegistry.AdminField field : entry.fields()) {
            fieldDefinitions.put(field.id(), field);
            if (field.type() == AdminPanelRegistry.FieldType.READ_ONLY) {
                formValues.put(field.id(), field.initialValue());
                Button display = Button.builder(Component.literal(field.initialValue()), ignored -> { })
                        .bounds(x, fieldY, fieldWidth, 20).build();
                display.active = false;
                addRenderableWidget(display);
                x += fieldWidth + 6;
                continue;
            }
            if (field.type() == AdminPanelRegistry.FieldType.SELECT) {
                String value = formValues.computeIfAbsent(field.id(), ignored -> field.initialValue());
                addRenderableWidget(Button.builder(Component.literal(value + " ▾"), ignored -> {
                    expandedSelect = field.id().equals(expandedSelect) ? null : field.id();
                    rebuildWidgets();
                }).bounds(x, fieldY, fieldWidth, 20).build());
                if (field.id().equals(expandedSelect)) selectAnchor = new SelectAnchor(x, fieldY, fieldWidth, field);
                x += fieldWidth + 6;
                continue;
            }
            EditBox box = new EditBox(font, x, fieldY, fieldWidth, 20, Component.literal(field.label()));
            box.setMaxLength(field.maxLength());
            box.setHint(Component.literal(field.placeholder()));
            box.setValue(formValues.getOrDefault(field.id(), field.initialValue()));
            box.setResponder(value -> {
                formValues.put(field.id(), value);
                updateSuggestion(box, field, value);
                updateDropdown(field, value);
                queueAutoSave(field.id(), value, false);
            });
            updateSuggestion(box, field, box.getValue());
            editBoxes.put(field.id(), box);
            addRenderableWidget(box);
            x += fieldWidth + 6;
        }
    }

    // Render every option for the currently expanded dropdown above other row controls.
    private void addSelectOptions() {
        if (selectAnchor == null) return;
        int optionHeight = 20;
        int totalHeight = selectAnchor.field().suggestions().size() * optionHeight;
        int y = selectAnchor.y() + 20;
        if (y + totalHeight > top + panelHeight - FOOTER_HEIGHT) y = selectAnchor.y() - totalHeight;
        for (String option : selectAnchor.field().suggestions()) {
            int optionY = y;
            addRenderableWidget(Button.builder(Component.literal(option), ignored -> {
                formValues.put(selectAnchor.field().id(), option);
                queueAutoSave(selectAnchor.field().id(), option, true);
                expandedSelect = null;
                rebuildWidgets();
            }).bounds(selectAnchor.x(), optionY, selectAnchor.width(), optionHeight).build());
            y += optionHeight;
        }
    }

    // Initialize fields on the entire page, including editor rows below the visible scroll area.
    private void seedFormValues() {
        if (snapshot == null || snapshot.page() == null) return;
        for (AdminPanelRegistry.AdminEntry entry : snapshot.page().entries()) {
            for (AdminPanelRegistry.AdminField field : entry.fields()) {
                formValues.putIfAbsent(field.id(), field.initialValue());
            }
        }
    }

    // Dropdown options take priority over inputs in rows beneath the opened menu.
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        event = panelMouseEvent(event);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int codeRow = codeMatchRowAt(event.x(), event.y());
            if (codeRow >= 0) {
                acceptCodeMatch(codeRow);
                return true;
            }
            int row = dropdownRowAt(event.x(), event.y());
            if (row >= 0) {
                acceptDropdown(row);
                return true;
            }
        }
        if (selectAnchor != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int count = selectAnchor.field().suggestions().size();
            int totalHeight = count * 20;
            int y = selectAnchor.y() + 20;
            if (y + totalHeight > top + panelHeight - FOOTER_HEIGHT) y = selectAnchor.y() - totalHeight;
            if (event.x() >= selectAnchor.x() && event.x() < selectAnchor.x() + selectAnchor.width()
                    && event.y() >= y && event.y() < y + totalHeight) {
                int index = ((int) event.y() - y) / 20;
                String option = selectAnchor.field().suggestions().get(index);
                formValues.put(selectAnchor.field().id(), option);
                queueAutoSave(selectAnchor.field().id(), option, true);
                expandedSelect = null;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(panelMouseEvent(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(panelMouseEvent(event), dragX / panelRenderScale, dragY / panelRenderScale);
    }

    private MouseButtonEvent panelMouseEvent(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() / panelRenderScale, event.y() / panelRenderScale,
                event.buttonInfo());
    }

    private void updatePanelScale() {
        var window = minecraft.getWindow();
        int panelScale = window.calculateScale(PANEL_GUI_SCALE, false);
        panelRenderScale = (float) panelScale / window.getGuiScale();
        width = (int) Math.ceil(window.getWidth() / (double) panelScale);
        height = (int) Math.ceil(window.getHeight() / (double) panelScale);
    }

    @Override
    public void resize(int width, int height) {
        updatePanelScale();
        super.resize(this.width, this.height);
    }

    // Place an entry's compact controls from right to left while preserving room for its text.
    private void addActionButtons(AdminPanelRegistry.AdminEntry entry, int y, int areaX, int right, boolean compact) {
        addActionButtons(entry, y, areaX, right, compact, compact ? EDITOR_ACTION_ROW_HEIGHT : rowHeight());
    }

    private void addActionButtons(AdminPanelRegistry.AdminEntry entry, int y, int areaX, int right, boolean compact,
                                  int height) {
        if (compact && entry.fields().isEmpty() && entry.actions().size() == 1 && entry.actions().getFirst().wide()) {
            AdminPanelRegistry.AdminAction action = entry.actions().getFirst();
            String label = action.dangerous() && action.id().equals(pendingDangerousAction)
                    ? "Confirm" : action.label();
            addRenderableWidget(Button.builder(Component.literal(label), ignored -> activate(action))
                    .bounds(areaX, y + (height - 20) / 2, right - areaX, 20).build());
            return;
        }
        int x = right;
        List<AdminPanelRegistry.AdminAction> actions = entry.actions();
        for (int index = actions.size() - 1; index >= 0; index--) {
            AdminPanelRegistry.AdminAction action = actions.get(index);
            boolean confirming = action.dangerous() && action.id().equals(pendingDangerousAction);
            String label = confirming ? "Confirm" : action.label();
            int buttonWidth = buttonWidth(label);
            x -= buttonWidth;
            int buttonY = compact ? y + (entry.fields().isEmpty() ? (height - 20) / 2 : 13)
                    : y + (entry.fields().isEmpty() ? (rowHeight() - 20) / 2 : 35);
            addRenderableWidget(Button.builder(Component.literal(label), ignored -> activate(action))
                    .bounds(x, buttonY, buttonWidth, 20).build());
            x -= 4;
        }
    }

    // Return the total horizontal space reserved by an entry's action buttons.
    private int actionWidth(AdminPanelRegistry.AdminEntry entry) {
        return entry.actions().stream().mapToInt(action -> buttonWidth(action.label()) + 4).sum();
    }

    private int buttonWidth(String label) {
        return Math.max(38, Math.min(96, font.width(label) + 12));
    }

    // Require a deliberate second click for destructive controls before sending them to the server.
    private void activate(AdminPanelRegistry.AdminAction action) {
        if (action.targetPage() != null && !action.targetPage().isBlank()) {
            leave(() -> {
                flushAutoSave();
                request(snapshot.selectedModule(), action.targetPage());
            });
            return;
        }
        if (action.dangerous() && !action.id().equals(pendingDangerousAction)) {
            pendingDangerousAction = action.id();
            rebuildWidgets();
            return;
        }
        ClientPlayNetworking.send(new AdminPanelNetworking.ExecuteActionPayload(
                snapshot.selectedModule(), snapshot.page().id(), action.id(),
                AdminPanelNetworking.encodeValues(formValues)));
    }

    // Debounce text edits while applying discrete dropdown choices immediately.
    private void queueAutoSave(String fieldId, String value, boolean immediate) {
        if (snapshot.page().autoAction() == null || snapshot.page().autoAction().isBlank()) return;
        pendingAutoValues.put(fieldId, value);
        autoSaveAtMillis = System.currentTimeMillis() + (immediate ? 0L : 650L);
        if (immediate) flushAutoSave();
    }

    // Send the accumulated changed fields as one small server-authoritative patch.
    private void flushAutoSave() {
        if (pendingAutoValues.isEmpty() || snapshot.page() == null
                || snapshot.page().autoAction() == null || snapshot.page().autoAction().isBlank()) return;
        Map<String, String> values = Map.copyOf(pendingAutoValues);
        pendingAutoValues.clear();
        autoSaveAtMillis = 0L;
        ClientPlayNetworking.send(new AdminPanelNetworking.ExecuteActionPayload(
                snapshot.selectedModule(), snapshot.page().id(), snapshot.page().autoAction(),
                AdminPanelNetworking.encodeValues(values)));
    }

    // Apply a pending automatic edit once the player pauses typing briefly.
    @Override
    public void tick() {
        super.tick();
        if (autoSaveAtMillis > 0L && System.currentTimeMillis() >= autoSaveAtMillis) flushAutoSave();
    }

    // Render the dark modular shell, current headings, status notice, cards, and scroll indicator.
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.pose().pushMatrix();
        graphics.pose().scale(panelRenderScale, panelRenderScale);
        graphics.fill(0, 0, width, height, 0xB0000000);
        graphics.fill(shellLeft, top, shellLeft + shellWidth, top + panelHeight, 0xF012151B);
        graphics.fill(shellLeft, top, left, top + panelHeight, 0xF00C0E13);
        graphics.fill(left, top, left + 1, top + panelHeight, 0xFF2A2F3A);
        graphics.fill(shellLeft, top, shellLeft + 2, top + panelHeight, 0xFF7C5CDA);
        graphics.text(font, Component.literal(trim(isDashboard() ? "Administration" : snapshot.page().title(),
                panelWidth / 2)), left + 12, top + 8, 0xFFF2F2F5, false);
        String subtitle = isDashboard() ? "" : snapshot.page().subtitle();
        graphics.text(font, Component.literal(trim(subtitle, panelWidth - 80)),
                left + 12, top + 20, 0xFF9CA3AF, false);
        if (isDashboard()) renderDashboard(graphics);
        else renderEntries(graphics);
        if (snapshot.notice() != null && !snapshot.notice().isBlank()) {
            graphics.text(font, Component.literal(trim(snapshot.notice(), panelWidth - 160)), left + 12,
                    top + panelHeight - 13, snapshot.error() ? 0xFFFF6B6B : 0xFF72D69C, false);
        }
        super.extractRenderState(graphics, (int) (mouseX / panelRenderScale),
                (int) (mouseY / panelRenderScale), delta);
        if (isDashboard()) renderModuleIcons(graphics);
        renderCodeGhost(graphics);
        renderCodeMatches(graphics, (int) (mouseX / panelRenderScale), (int) (mouseY / panelRenderScale));
        renderDropdown(graphics, (int) (mouseX / panelRenderScale), (int) (mouseY / panelRenderScale));
        graphics.pose().popMatrix();
    }

    // Render descriptions beneath dashboard module buttons.
    private void renderDashboard(GuiGraphicsExtractor graphics) {
        int y = top + HEADER_HEIGHT;
        for (AdminPanelNetworking.ModuleSnapshot module : snapshot.modules()) {
            if (y + 30 > top + panelHeight - FOOTER_HEIGHT) break;
            graphics.text(font, Component.literal(trim(module.description(), panelWidth - 48)), left + 24, y + 30,
                    0xFF8E96A3, false);
            y += 48;
        }
    }

    // Draw extension-provided item icons above their module buttons after widgets have been extracted.
    private void renderModuleIcons(GuiGraphicsExtractor graphics) {
        int y = top + HEADER_HEIGHT;
        for (AdminPanelNetworking.ModuleSnapshot module : snapshot.modules()) {
            if (y + 30 > top + panelHeight - FOOTER_HEIGHT) break;
            graphics.item(icon(module.icon()), left + 20, y + 5);
            y += 48;
        }
    }

    // Render visible status entries with severity accents and concise secondary text.
    private void renderEntries(GuiGraphicsExtractor graphics) {
        if (snapshot.page().layout() == AdminPanelRegistry.PageLayout.CODE) {
            graphics.text(font, Component.literal(trim("Tab: complete a command or input, jump to the next input, or indent"
                            + " · Save validates on the server", panelWidth - 32)),
                    left + 16, contentTop() + 4, 0xFFA7AFBA, false);
            renderCodeHint(graphics);
            return;
        }
        if (snapshot.page().layout() == AdminPanelRegistry.PageLayout.EDITOR) {
            renderEditor(graphics);
            return;
        }
        if (snapshot.page().layout() == AdminPanelRegistry.PageLayout.PREVIEW_CARDS) {
            renderPreviewCards(graphics);
            return;
        }
        List<AdminPanelRegistry.AdminEntry> entries = snapshot.page().entries();
        int visible = visibleRows();
        for (int index = 0; index < visible && scroll + index < entries.size(); index++) {
            AdminPanelRegistry.AdminEntry entry = entries.get(scroll + index);
            int y = contentTop() + index * (rowHeight() + ROW_GAP);
            graphics.fill(left + 10, y, left + panelWidth - 10, y + rowHeight(), 0xFF1D222B);
            graphics.fill(left + 10, y, left + 12, y + rowHeight(), toneColor(entry.tone()));
            int actionWidth = actionWidth(entry);
            int previewWidth = entry.preview() == null ? 0 : 28;
            int textWidth = Math.max(80, panelWidth - 40 - actionWidth - previewWidth);
            int textX = left + 18 + previewWidth;
            if (entry.preview() != null) renderPreview(graphics, entry.preview(), left + 16, y + 3, 24, 24);
            graphics.text(font, Component.literal(trim(entry.title(), textWidth)),
                    textX, y + 5, 0xFFF0F1F3, false);
            graphics.text(font, Component.literal(trim(entry.detail(), textWidth)),
                    textX, y + 17, 0xFFA7AFBA, false);
        }
        if (entries.size() > visible) {
            String position = (scroll + 1) + "–" + Math.min(entries.size(), scroll + visible)
                    + " / " + entries.size();
            graphics.text(font, Component.literal(position), left + panelWidth - font.width(position) - 62,
                    top + 30, 0xFF9CA3AF, false);
        }
    }

    // Render preview entries in a responsive two-column grid with their key identity kept readable.
    private void renderPreviewCards(GuiGraphicsExtractor graphics) {
        List<PreviewPlacement> placements = previewPlacements();
        for (PreviewPlacement placement : placements) {
            AdminPanelRegistry.AdminEntry entry = placement.entry();
            int x = placement.x();
            int y = placement.y();
            int cardWidth = placement.width();
            graphics.fill(x, y, x + cardWidth, y + PORTAL_ROW_HEIGHT, 0xFF1D222B);
            graphics.fill(x, y, x + 2, y + PORTAL_ROW_HEIGHT, toneColor(entry.tone()));
            int previewWidth = entry.preview() == null ? 0 : 36;
            if (entry.preview() != null) renderPreview(graphics, entry.preview(), x + 6, y + 4, 32, 32);
            int actions = actionWidth(entry);
            int textX = x + 8 + previewWidth;
            int textWidth = Math.max(45, cardWidth - 16 - previewWidth - actions);
            graphics.text(font, Component.literal(trim(entry.title(), textWidth)), textX, y + 9,
                    0xFFF0F1F3, false);
            graphics.text(font, Component.literal(trim(entry.detail(), textWidth)), textX, y + 22,
                    0xFFA7AFBA, false);
        }
        for (PreviewHeading heading : previewHeadings) {
            graphics.text(font, Component.literal(heading.title()), left + 12, heading.y() + 3, 0xFFE1D8FF, false);
            int lineX = left + 20 + font.width(heading.title());
            graphics.fill(lineX, heading.y() + 7, left + panelWidth - 10, heading.y() + 8, 0xFF4A405F);
        }
        if (scroll > 0 || previewHasMore) {
            String position = previewHasMore ? "scroll for more ↓" : "↑ scroll up";
            graphics.text(font, Component.literal(position), left + panelWidth - font.width(position) - 62,
                    top + 30, 0xFF9CA3AF, false);
        }
    }

    /** Section headings of the cards currently on screen. */
    private final List<PreviewHeading> previewHeadings = new ArrayList<>();
    private boolean previewHasMore;

    /** Card rows and section headings in order: a heading whenever the section changes, then rows of cards. */
    private List<List<Integer>> previewLines() {
        List<List<Integer>> lines = new ArrayList<>();
        List<AdminPanelRegistry.AdminEntry> entries = snapshot.page().entries();
        int columns = previewColumns();
        String section = null;
        List<Integer> row = null;
        for (int index = 0; index < entries.size(); index++) {
            String entrySection = entries.get(index).section();
            if (!entrySection.equals(section)) {
                section = entrySection;
                if (!section.isBlank()) lines.add(List.of(-1 - index)); // heading, carries the entry index
                row = null;
            }
            if (row == null || row.size() >= columns) {
                row = new ArrayList<>();
                lines.add(row);
            }
            row.add(index);
        }
        return lines;
    }

    // Calculate card positions line by line, with section headings between groups.
    private List<PreviewPlacement> previewPlacements() {
        List<PreviewPlacement> placements = new ArrayList<>();
        previewHeadings.clear();
        List<AdminPanelRegistry.AdminEntry> entries = snapshot.page().entries();
        List<List<Integer>> lines = previewLines();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - 1)));
        int columns = previewColumns();
        int gap = 5;
        int available = panelWidth - 20;
        int cardWidth = (available - gap * (columns - 1)) / columns;
        int y = contentTop();
        int bottom = top + panelHeight - FOOTER_HEIGHT;
        int line = scroll;
        for (; line < lines.size(); line++) {
            List<Integer> indexes = lines.get(line);
            if (indexes.size() == 1 && indexes.getFirst() < 0) {
                if (y + SECTION_HEADING_HEIGHT + PORTAL_ROW_HEIGHT > bottom) break;
                previewHeadings.add(new PreviewHeading(entries.get(-1 - indexes.getFirst()).section(), y));
                y += SECTION_HEADING_HEIGHT;
                continue;
            }
            if (y + PORTAL_ROW_HEIGHT > bottom) break;
            for (int column = 0; column < indexes.size(); column++) {
                int index = indexes.get(column);
                placements.add(new PreviewPlacement(index, entries.get(index), left + 10 + column * (cardWidth + gap), y,
                        cardWidth));
            }
            y += PORTAL_ROW_HEIGHT + ROW_GAP;
        }
        previewHasMore = line < lines.size();
        return placements;
    }

    // Return the responsive preview-grid column count for the current scaled window width.
    private int previewColumns() {
        return panelWidth >= 1000 ? 3 : panelWidth >= 620 ? 2 : 1;
    }

    // Editor pages as dashboard boxes: a rounded-looking tile per section, rows inside, cut at the viewport.
    private void renderEditor(GuiGraphicsExtractor graphics) {
        List<EditorPlacement> placements = editorPlacements();
        int viewTop = contentTop();
        int viewBottom = top + panelHeight - FOOTER_HEIGHT;
        for (EditorBox box : editorBoxes) {
            int y0 = Math.max(viewTop, box.y()), y1 = Math.min(viewBottom, box.y() + box.height());
            if (y1 <= y0) continue;
            graphics.fill(box.x(), y0, box.x() + box.width(), y1, 0xFF1A1E26);
            graphics.fill(box.x(), y0, box.x() + box.width(), y0 + 1, 0xFF2C3240);
            if (box.y() >= viewTop && box.y() + 14 <= viewBottom)
                graphics.text(font, Component.literal(trim(box.title(), box.width() - 12)), box.x() + 7, box.y() + 5,
                        0xFFE1D8FF, false);
        }
        for (EditorPlacement placement : placements) {
            if (!placement.visible()) continue;
            AdminPanelRegistry.AdminEntry entry = placement.entry();
            int rowLeft = placement.x() + 6, rowRight = placement.x() + placement.width() - 6;
            graphics.fill(rowLeft - 2, placement.y(), rowRight + 2, placement.y() + placement.height(), 0x66232934);
            if (entry.fields().isEmpty()) {
                // A single wide button fills the row, so its text would only peek out around the button.
                if (entry.actions().size() == 1 && entry.actions().getFirst().wide()) continue;
                int textX = rowLeft + 2;
                if (entry.preview() != null && entry.preview().kind().equals("item")) {
                    graphics.item(icon(entry.title().equals("Item preview")
                                    ? formValues.getOrDefault("target", entry.preview().shape())
                                    : entry.preview().shape()), rowLeft, placement.y() + (placement.height() - 16) / 2);
                    textX = rowLeft + 20;
                }
                int textRight = rowRight - actionWidth(entry) - 4;
                String name = trim(editorRowName(entry.title()), Math.max(20, textRight - textX));
                boolean twoLines = !entry.detail().isBlank();
                graphics.text(font, Component.literal(name), textX, placement.y() + (twoLines ? 4 : 8), 0xFFF0F1F3, false);
                if (twoLines) graphics.text(font, Component.literal(trim(entry.detail(), Math.max(20, textRight - textX))),
                        textX, placement.y() + 16, 0xFF9CA3AF, false);
                continue;
            }
            int actionWidth = actionWidth(entry) + previewWidth(entry);
            if (previewWidth(entry) > 0) {
                int iconX = rowRight - actionWidth + 2;
                String target = previewTarget(entry);
                if (entry.preview().kind().equals("entity"))
                    renderEntityPreview(graphics, target, iconX, placement.y() + 1, previewWidth(entry) - 4,
                            placement.height() - 2);
                else if (!target.isBlank()) graphics.item(icon(target), iconX, placement.y() + 15);
            }
            int available = Math.max(60, rowRight - rowLeft - actionWidth);
            int fieldWidth = Math.max(40, (available - (entry.fields().size() - 1) * 6) / entry.fields().size());
            int x = rowLeft;
            for (AdminPanelRegistry.AdminField field : entry.fields()) {
                graphics.text(font, Component.literal(trim(field.label(), fieldWidth)), x, placement.y() + 2,
                        0xFFAEB6C2, false);
                x += fieldWidth + 6;
            }
        }
        if (scroll > 0 || editorHasMore) {
            String position = editorHasMore ? "scroll for more ↓" : "↑ scroll up";
            graphics.text(font, Component.literal(position), left + panelWidth - font.width(position) - 62,
                    top + 30, 0xFF9CA3AF, false);
        }
    }

    private final List<EditorBox> editorBoxes = new ArrayList<>();
    private boolean editorHasMore;
    private static final int EDITOR_SCROLL_STEP = 24;
    private static final int EDITOR_BOX_HEADER = 18;

    /** Columns of section boxes; each box goes to the currently shortest column, keeping page order. */
    private List<EditorPlacement> editorPlacements() {
        List<EditorPlacement> placements = new ArrayList<>();
        editorBoxes.clear();
        List<AdminPanelRegistry.AdminEntry> entries = snapshot.page().entries();
        // Group consecutive rows of the same section into one box.
        List<String> titles = new ArrayList<>();
        List<List<Integer>> groups = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            AdminPanelRegistry.AdminEntry entry = entries.get(index);
            if (entry.fields().stream().anyMatch(field -> field.id().equals("portal_target_x"))
                    && !"Coordinates".equalsIgnoreCase(formValues.getOrDefault("portal_template", ""))) continue;
            if (entry.fields().stream().anyMatch(field -> field.id().equals("portal_keep_inventory"))
                    && ("Coordinates".equalsIgnoreCase(formValues.getOrDefault("portal_template", ""))
                    || "Exit".equalsIgnoreCase(formValues.getOrDefault("portal_template", "")))) continue;
            String section = editorSection(entry);
            if (titles.isEmpty() || !titles.getLast().equals(section)) {
                titles.add(section);
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(index);
        }
        int columns = panelWidth >= 1150 ? 3 : panelWidth >= 640 ? 2 : 1;
        int gap = 6;
        int columnWidth = (panelWidth - 20 - gap * (columns - 1)) / columns;
        int[] heights = new int[columns];
        int scrollPixels = scroll * EDITOR_SCROLL_STEP;
        int viewTop = contentTop();
        int viewBottom = top + panelHeight - FOOTER_HEIGHT;
        for (int group = 0; group < groups.size(); group++) {
            int column = 0;
            for (int candidate = 1; candidate < columns; candidate++) if (heights[candidate] < heights[column]) column = candidate;
            int x = left + 10 + column * (columnWidth + gap);
            int boxTop = viewTop + heights[column] - scrollPixels;
            int y = boxTop + EDITOR_BOX_HEADER;
            for (int index : groups.get(group)) {
                AdminPanelRegistry.AdminEntry entry = entries.get(index);
                int height = entry.fields().isEmpty() ? (entry.detail().isBlank() ? 24 : 30) : EDITOR_FIELD_ROW_HEIGHT;
                boolean visible = y >= viewTop && y + height <= viewBottom;
                placements.add(new EditorPlacement(index, entry, x, y, columnWidth, height, visible));
                y += height + 3;
            }
            int boxHeight = y + 3 - boxTop;
            editorBoxes.add(new EditorBox(titles.get(group), x, boxTop, columnWidth, boxHeight));
            heights[column] += boxHeight + gap;
        }
        int tallest = 0;
        for (int height : heights) tallest = Math.max(tallest, height);
        editorHasMore = tallest - scrollPixels > viewBottom - viewTop;
        return placements;
    }

    // Resolve an explicit framework section while retaining compatibility with older compound titles.
    private static String editorSection(AdminPanelRegistry.AdminEntry entry) {
        if (!entry.section().isBlank()) return entry.section();
        String title = entry.title();
        int delimiter = title.indexOf(" · ");
        return delimiter < 0 ? "Actions" : title.substring(0, delimiter);
    }

    // Extract the concise row name displayed when an editor entry contains only actions.
    private static String editorRowName(String title) {
        int delimiter = title.indexOf(" · ");
        return delimiter < 0 ? title : title.substring(delimiter + 3);
    }

    // Scroll status cards while leaving navigation and footer controls fixed.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        mouseX /= panelRenderScale;
        mouseY /= panelRenderScale;
        if (!isDashboard() && snapshot.page().layout() == AdminPanelRegistry.PageLayout.EDITOR) {
            editorPlacements();
            int previous = scroll;
            if (verticalAmount < 0 && editorHasMore) scroll++;
            else if (verticalAmount > 0) scroll = Math.max(0, scroll - 1);
            if (scroll != previous) rebuildWidgets();
            return true;
        }
        if (!isDashboard() && snapshot.page().layout() == AdminPanelRegistry.PageLayout.PREVIEW_CARDS) {
            previewPlacements();
            int previous = scroll;
            if (verticalAmount < 0 && previewHasMore) scroll++;
            else if (verticalAmount > 0) scroll = Math.max(0, scroll - 1);
            if (scroll != previous) rebuildWidgets();
            return true;
        }
        if (!isDashboard() && snapshot.page().entries().size() > visibleRows()) {
            int previous = scroll;
            scroll = Math.max(0, Math.min(snapshot.page().entries().size() - visibleRows(),
                    scroll + (verticalAmount < 0 ? 1 : -1)));
            if (scroll != previous) rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // Accept the visible autocomplete candidate with Tab while a suggested field is focused.
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (codeBox != null && codeBox.isFocused() && !updateCodeMatches().isEmpty()) {
            switch (event.key()) {
                case GLFW.GLFW_KEY_DOWN -> {
                    codeMatchIndex = (codeMatchIndex + 1) % codeMatches.size();
                    return true;
                }
                case GLFW.GLFW_KEY_UP -> {
                    codeMatchIndex = (codeMatchIndex + codeMatches.size() - 1) % codeMatches.size();
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> {
                    acceptCodeMatch(codeMatchIndex);
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    codeDismissedKey = codeMatchKey;
                    codeMatches = List.of();
                    return true;
                }
                default -> { }
            }
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && codeBox != null && codeBox.isFocused()) {
            completeCode();
            return true;
        }
        if (dropdownVisible()) {
            switch (event.key()) {
                case GLFW.GLFW_KEY_DOWN -> {
                    dropdownIndex = (dropdownIndex + 1) % dropdownMatches.size();
                    return true;
                }
                case GLFW.GLFW_KEY_UP -> {
                    dropdownIndex = (dropdownIndex + dropdownMatches.size() - 1) % dropdownMatches.size();
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> {
                    acceptDropdown(dropdownIndex);
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    closeDropdown();
                    return true;
                }
                default -> { }
            }
        }
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            for (Map.Entry<String, EditBox> entry : editBoxes.entrySet()) {
                EditBox box = entry.getValue();
                if (!box.isFocused()) continue;
                String match = completion(fieldDefinitions.get(entry.getKey()), box.getValue());
                if (match != null) {
                    box.setValue(match);
                    box.moveCursorToEnd(false);
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    /**
     * Tab in the source editor: completes a command or condition together with its required inputs, completes an
     * input name inside a call, adds a missing required input, or jumps to the next input. Otherwise indents.
     */
    private void completeCode() {
        MultilineTextField field = ((MultiLineEditBoxAccessor) codeBox).cwp$textField();
        String source = field.value();
        int cursor = field.cursor();
        CodeInsertion insertion = pendingInsertion(source, cursor);
        if (insertion != null) {
            field.insertText(insertion.text());
            if (insertion.valueOffset() >= 0)
                selectValue(field, cursor + insertion.valueOffset(),
                        cursor + insertion.valueOffset() + insertion.template().length(), insertion.template());
            else if (insertion.cursorInParens()) field.seekCursor(Whence.RELATIVE, -1);
            return;
        }
        ScriptSignatures.Context context = ScriptSignatures.at(source, cursor);
        if (context.callOpen() >= 0 && nextInput(field, source, cursor, context.signature().condition())) return;
        field.insertText("    ");
    }

    /**
     * Text Tab would insert at the cursor: a command or condition with its required inputs, or an input name with
     * its value. {@code valueOffset} is where the value to select starts in {@code text}, or -1.
     */
    private record CodeInsertion(String text, int valueOffset, String template, boolean cursorInParens) { }

    /** What Tab would insert here, or null when Tab jumps to the next input or indents instead. */
    private static CodeInsertion pendingInsertion(String source, int cursor) {
        ScriptSignatures.Context context = ScriptSignatures.at(source, cursor);
        if (context.callOpen() >= 0) return context.prefix() == null ? null : inputInsertion(context);
        int start = cursor;
        while (start > 0 && (Character.isLowerCase(source.charAt(start - 1))
                || source.charAt(start - 1) == '_')) start--;
        if (start == cursor) return null;
        String prefix = source.substring(start, cursor);
        int lineStart = source.lastIndexOf('\n', start - 1) + 1;
        String before = source.substring(lineStart, start).trim();
        boolean conditions = before.equals("if") || before.equals("while")
                || before.startsWith("if ") || before.startsWith("while ")
                || before.endsWith(" and") || before.endsWith(" or") || before.endsWith("not");
        // Only the first word of a line can be a command; later words (script IDs, values) are not completed.
        if (!conditions && !before.isEmpty()) return null;
        Map<String, ScriptSignatures.Signature> table = conditions ? ScriptSignatures.CONDITIONS : ScriptSignatures.COMMANDS;
        ScriptSignatures.Signature match = table.containsKey(prefix) ? table.get(prefix) : table.keySet().stream()
                .sorted().filter(value -> value.startsWith(prefix)).findFirst().map(table::get).orElse(null);
        if (match == null) return null;
        String rest = match.name().substring(prefix.length());
        if (cursor < source.length() && source.charAt(cursor) == '(')
            return rest.isEmpty() ? null : new CodeInsertion(rest, -1, null, false);
        if (match.block()) return new CodeInsertion(rest + " ", -1, null, false);
        StringBuilder call = new StringBuilder(rest).append('(');
        int firstValue = -1;
        for (ScriptSignatures.Arg arg : match.inserted()) {
            if (call.charAt(call.length() - 1) != '(') call.append(", ");
            if (!match.condition()) call.append(arg.name()).append('=');
            if (firstValue < 0) firstValue = call.length();
            call.append(arg.template());
        }
        call.append(')');
        return new CodeInsertion(call.toString(), firstValue,
                firstValue < 0 ? null : match.inserted().getFirst().template(),
                firstValue < 0 && !match.optional().isEmpty());
    }

    /** The input name typed inside a call, completed; on an empty one, the first missing required input. */
    private static CodeInsertion inputInsertion(ScriptSignatures.Context context) {
        String prefix = context.prefix();
        ScriptSignatures.Signature signature = context.signature();
        List<ScriptSignatures.Arg> candidates = new ArrayList<>(signature.required());
        if (!prefix.isEmpty()) candidates.addAll(signature.optional());
        for (ScriptSignatures.Arg arg : candidates) {
            if (context.usedArgs().contains(arg.name()) || !arg.name().startsWith(prefix)) continue;
            // An alternative of a "one of" input that is already written would be invalid.
            if (arg.oneOf() != null && signature.satisfied(arg, context.usedArgs())) continue;
            String text = arg.name().substring(prefix.length()) + "=" + arg.template();
            return new CodeInsertion(text, text.length() - arg.template().length(), arg.template(), false);
        }
        return null;
    }

    /**
     * Values matching what is typed inside the quotes of an input that has a value list, such as sound IDs.
     * Recomputed only when the typed text or the input changes.
     */
    private List<String> updateCodeMatches() {
        if (codeBox == null || !codeBox.isFocused()) return codeMatches = List.of();
        MultilineTextField field = ((MultiLineEditBoxAccessor) codeBox).cwp$textField();
        String source = field.value();
        int cursor = field.cursor();
        ScriptSignatures.Context context = ScriptSignatures.at(source, cursor);
        ScriptSignatures.Arg arg = context.currentArg();
        if (context.stringStart() < 0 || arg == null || arg.values() == null || field.hasSelection()) {
            codeMatchKey = "";
            return codeMatches = List.of();
        }
        String typed = source.substring(context.stringStart(), cursor);
        String key = arg.values() + "\u0000" + context.stringStart() + "\u0000" + typed;
        if (key.equals(codeMatchKey)) return codeMatches;
        codeMatchKey = key;
        codeMatchIndex = 0;
        if (key.equals(codeDismissedKey)) return codeMatches = List.of();
        codeDismissedKey = null;
        String whole = source.substring(context.stringStart(), ScriptSignatures.stringEnd(source, context.stringStart()));
        List<String> values = codeValues(arg.values(), source);
        codeMatches = typed.isBlank() || values.contains(whole) && whole.equals(typed) ? List.of()
                : ScriptSignatures.rank(values, typed, CODE_MATCH_ROWS);
        return codeMatches;
    }

    /** IDs for one value list: from the client's registries, the script itself, or what the server sent. */
    private List<String> codeValues(String list, String source) {
        if (ScriptSignatures.SCRIPT_VALUES.contains(list)) return ScriptSignatures.scriptValues(list, source);
        if (!ScriptSignatures.CLIENT_VALUES.contains(list)) return codeValues.getOrDefault(list, List.of());
        return CLIENT_VALUE_CACHE.computeIfAbsent(list, ignored -> {
            var registry = switch (list) {
                case "sound" -> BuiltInRegistries.SOUND_EVENT.keySet();
                case "effect" -> BuiltInRegistries.MOB_EFFECT.keySet();
                case "particle" -> BuiltInRegistries.PARTICLE_TYPE.keySet();
                case "item" -> BuiltInRegistries.ITEM.keySet();
                default -> BuiltInRegistries.ENTITY_TYPE.keySet();
            };
            return registry.stream().map(Identifier::toString).sorted().toList();
        });
    }

    /** Replaces the whole quoted value with the chosen ID and puts the cursor after the closing quote. */
    private void acceptCodeMatch(int row) {
        if (row < 0 || row >= codeMatches.size()) return;
        String value = codeMatches.get(row);
        MultilineTextField field = ((MultiLineEditBoxAccessor) codeBox).cwp$textField();
        String source = field.value();
        ScriptSignatures.Context context = ScriptSignatures.at(source, field.cursor());
        if (context.stringStart() < 0) return;
        int end = ScriptSignatures.stringEnd(source, context.stringStart());
        field.setSelecting(false);
        field.seekCursor(Whence.ABSOLUTE, context.stringStart());
        field.setSelecting(true);
        field.seekCursor(Whence.ABSOLUTE, end);
        field.setSelecting(false);
        field.insertText(value);
        String updated = field.value();
        int cursor = field.cursor();
        if (cursor < updated.length() && (updated.charAt(cursor) == '"' || updated.charAt(cursor) == '\''))
            field.seekCursor(Whence.RELATIVE, 1);
        codeMatches = List.of();
        codeDismissedKey = null;
    }

    /** Screen position of the text cursor's line, or null when it is scrolled out of view. */
    private int[] codeCursorPosition() {
        MultilineTextField field = ((MultiLineEditBoxAccessor) codeBox).cwp$textField();
        int lineIndex = field.getLineAtCursor();
        int lineBegin = CodeLines.begin(field, lineIndex);
        int y = codeBox.getY() + CODE_BOX_PADDING + lineIndex * font.lineHeight - (int) codeBox.scrollAmount();
        if (y < codeBox.getY() + 2 || y + font.lineHeight > codeBox.getY() + codeBox.getHeight() - 2) return null;
        int x = codeBox.getX() + CODE_BOX_PADDING + font.width(field.value().substring(lineBegin, field.cursor()));
        return new int[] {x, y};
    }

    private int codeMatchWidth() { return 230; }

    /** Top-left of the value list: below the cursor's line, or above it near the bottom of the editor. */
    private int[] codeMatchOrigin() {
        int[] cursor = codeCursorPosition();
        if (cursor == null) return null;
        int height = codeMatches.size() * DROPDOWN_ROW_HEIGHT;
        int x = Math.min(cursor[0], codeBox.getX() + codeBox.getWidth() - codeMatchWidth());
        int y = cursor[1] + font.lineHeight + 2;
        if (y + height > top + panelHeight - 4) y = cursor[1] - height - 2;
        return new int[] {Math.max(codeBox.getX(), x), y};
    }

    private int codeMatchRowAt(double mouseX, double mouseY) {
        if (codeBox == null || updateCodeMatches().isEmpty()) return -1;
        int[] origin = codeMatchOrigin();
        if (origin == null || mouseX < origin[0] || mouseX >= origin[0] + codeMatchWidth() || mouseY < origin[1]) return -1;
        int row = (int) (mouseY - origin[1]) / DROPDOWN_ROW_HEIGHT;
        return row < codeMatches.size() ? row : -1;
    }

    private void renderCodeMatches(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (codeBox == null || updateCodeMatches().isEmpty()) return;
        int[] origin = codeMatchOrigin();
        if (origin == null) return;
        int x = origin[0], y = origin[1], width = codeMatchWidth();
        int height = codeMatches.size() * DROPDOWN_ROW_HEIGHT;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF7C5CDA);
        graphics.fill(x, y, x + width, y + height, 0xF0101318);
        int hovered = codeMatchRowAt(mouseX, mouseY);
        for (int row = 0; row < codeMatches.size(); row++) {
            int rowY = y + row * DROPDOWN_ROW_HEIGHT;
            if (row == codeMatchIndex || row == hovered)
                graphics.fill(x, rowY, x + width, rowY + DROPDOWN_ROW_HEIGHT,
                        row == codeMatchIndex ? 0xFF33294D : 0xFF242A35);
            graphics.text(font, Component.literal(trim(codeMatches.get(row), width - 8)), x + 4, rowY + 5,
                    0xFFF0F1F3, false);
        }
    }

    /**
     * Shows what Tab would insert as faded text at the cursor. Text after the cursor on that line moves right
     * behind it, the way it will after Tab.
     */
    private void renderCodeGhost(GuiGraphicsExtractor graphics) {
        if (codeBox == null || !codeBox.isFocused()) return;
        MultilineTextField field = ((MultiLineEditBoxAccessor) codeBox).cwp$textField();
        if (field.hasSelection()) return;
        String source = field.value();
        int cursor = field.cursor();
        CodeInsertion insertion = pendingInsertion(source, cursor);
        if (insertion == null || insertion.text().isEmpty()) return;
        int lineIndex = field.getLineAtCursor();
        int lineBegin = CodeLines.begin(field, lineIndex), lineEnd = CodeLines.end(field, lineIndex);
        int innerLeft = codeBox.getX() + CODE_BOX_PADDING;
        int innerRight = codeBox.getX() + codeBox.getWidth() - CODE_BOX_PADDING;
        int y = codeBox.getY() + CODE_BOX_PADDING + lineIndex * font.lineHeight - (int) codeBox.scrollAmount();
        if (y < codeBox.getY() + 2 || y + font.lineHeight > codeBox.getY() + codeBox.getHeight() - 2) return;
        int x = innerLeft + font.width(source.substring(lineBegin, cursor));
        String after = source.substring(cursor, Math.max(cursor, lineEnd));
        String ghost = font.plainSubstrByWidth(insertion.text(), Math.max(0, innerRight - x));
        if (ghost.isEmpty()) return;
        // Cover the text after the cursor, then draw the suggestion and that text again behind it.
        graphics.fill(x + 1, y - 1, innerRight, y + font.lineHeight, 0xFF000000);
        graphics.text(font, Component.literal(ghost), x + GHOST_GAP, y, 0x88B8B8C8, false);
        int afterX = x + GHOST_GAP + font.width(ghost);
        if (!after.isEmpty() && afterX < innerRight)
            graphics.text(font, Component.literal(font.plainSubstrByWidth(after, innerRight - afterX)), afterX, y,
                    0xFFE0E0E0, false);
    }

    /**
     * Moves to the next input of the current call (or the next coordinate inside a position) and selects its value,
     * or past the call's closing parenthesis when the cursor is on its last input.
     */
    private static boolean nextInput(MultilineTextField field, String source, int cursor, boolean positional) {
        int lineStart = source.lastIndexOf('\n', cursor - 1) + 1;
        int lineEnd = source.indexOf('\n', cursor);
        if (lineEnd < 0) lineEnd = source.length();
        char quote = 0;
        boolean escaped = false;
        java.util.ArrayDeque<Character> open = new java.util.ArrayDeque<>();
        for (int index = lineStart; index < cursor; index++) {
            char c = source.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) { if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '{' || c == '[') open.push(c);
            else if ((c == ')' || c == '}' || c == ']') && !open.isEmpty()) open.pop();
        }
        int depth = 0;
        for (char c : open) { if (c == '(') break; depth++; }
        int braces = depth;
        for (int index = cursor; index < lineEnd; index++) {
            char c = source.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) { if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ')') {
                if (depth == 0) {
                    field.seekCursor(Whence.ABSOLUTE, index + 1);
                    return true;
                }
                depth--;
            } else if (c == ',' && (depth == 0 || depth == braces && braces > 0)) {
                int mark = depth == 0 && positional ? index : source.indexOf(depth == 0 ? '=' : ':', index);
                if (mark < 0 || mark >= lineEnd) return false;
                int valueStart = mark + 1;
                while (valueStart < lineEnd && source.charAt(valueStart) == ' ') valueStart++;
                int valueEnd = valueEnd(source, valueStart, lineEnd);
                selectValue(field, valueStart, valueEnd, source.substring(valueStart, valueEnd));
                return true;
            }
        }
        return false;
    }

    /** End of the value starting at {@code start}: the next comma or closing bracket outside quotes and nesting. */
    private static int valueEnd(String source, int start, int lineEnd) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < lineEnd; index++) {
            char c = source.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) { if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') { if (depth == 0) return trimEnd(source, start, index); depth--; }
            else if (c == ',' && depth == 0) return trimEnd(source, start, index);
        }
        return trimEnd(source, start, lineEnd);
    }

    private static int trimEnd(String source, int start, int end) {
        while (end > start && source.charAt(end - 1) == ' ') end--;
        return end;
    }

    /**
     * Puts the cursor into a value so typing replaces it: between empty quotes, over a quoted word, over the first
     * coordinate of a position, or over the whole value.
     */
    private static void selectValue(MultilineTextField field, int start, int end, String value) {
        int from = start, to = end;
        if (value.startsWith("\"") && value.length() >= 2 && value.endsWith("\"")) { from = start + 1; to = end - 1; }
        else if (value.startsWith("{")) {
            int colon = value.indexOf(':');
            if (colon >= 0) {
                from = start + colon + 1;
                to = valueEnd(field.value(), from, end);
            }
        }
        field.setSelecting(false);
        field.seekCursor(Whence.ABSOLUTE, from);
        if (to > from) {
            field.setSelecting(true);
            field.seekCursor(Whence.ABSOLUTE, to);
            field.setSelecting(false);
        }
    }

    /** Inputs and usage of the command or condition the cursor is in, right of the source editor. */
    private void renderCodeHint(GuiGraphicsExtractor graphics) {
        if (codeBox == null || codeHintWidth <= 0) return;
        int x = codeHintX, y = codeHintY, right = codeHintX + codeHintWidth, bottom = codeHintY + codeHintHeight;
        graphics.fill(x, y, right, bottom, 0xFF1A1E26);
        graphics.fill(x, y, right, y + 1, 0xFF2C3240);
        MultilineTextField field = ((MultiLineEditBoxAccessor) codeBox).cwp$textField();
        ScriptSignatures.Context context = ScriptSignatures.at(field.value(), field.cursor());
        HintWriter out = new HintWriter(graphics, x + 7, y + 6, codeHintWidth - 14, bottom - 4);
        ScriptSignatures.Signature signature = context.signature();
        if (signature == null) {
            out.line("Script reference", 0xFFE1D8FF);
            out.gap();
            out.wrap("Type a command and press Tab to insert it with its required inputs.", 0xFFA7AFBA);
            out.wrap("In a call, Tab selects the next input. Type the start of an input name and press Tab to add it.",
                    0xFFA7AFBA);
            out.gap();
            out.line("Commands", 0xFFAEB6C2);
            out.wrap(String.join(", ", ScriptSignatures.COMMANDS.keySet()), 0xFF8E96A3);
            out.gap();
            out.line("Conditions", 0xFFAEB6C2);
            out.wrap(String.join(", ", ScriptSignatures.CONDITIONS.keySet()), 0xFF8E96A3);
            return;
        }
        out.line(signature.block() ? signature.name() : signature.name() + "(…)", 0xFFE1D8FF);
        out.wrap(signature.about(), 0xFFA7AFBA);
        String current = signature.condition()
                ? (context.argIndex() >= 0 && context.argIndex() < signature.required().size()
                        ? signature.required().get(context.argIndex()).name() : null)
                : context.argument();
        boolean inCall = context.callOpen() >= 0;
        if (!signature.required().isEmpty()) {
            out.gap();
            out.line(signature.condition() ? "Inputs, in order" : "Required", 0xFFAEB6C2);
            List<ScriptSignatures.Arg> required = signature.required();
            for (int index = 0; index < required.size(); index++) {
                ScriptSignatures.Arg arg = required.get(index);
                boolean missing = inCall && !signature.condition() && !signature.satisfied(arg, context.usedArgs());
                if (arg.oneOf() == null) {
                    out.arg(arg, arg.name().equals(current), missing, 0);
                    continue;
                }
                boolean first = index == 0 || !arg.oneOf().equals(required.get(index - 1).oneOf());
                boolean last = index + 1 == required.size() || !arg.oneOf().equals(required.get(index + 1).oneOf());
                if (first) out.note("One of these:", missing);
                out.arg(arg, arg.name().equals(current), false, 8);
                if (!last) out.note("    or", false);
            }
        }
        if (!signature.optional().isEmpty()) {
            out.gap();
            out.line("Optional", 0xFFAEB6C2);
            for (ScriptSignatures.Arg arg : signature.optional()) out.arg(arg, arg.name().equals(current), false, 0);
        }
        out.gap();
        out.line("Example", 0xFFAEB6C2);
        out.wrap(signature.example(), 0xFF8E96A3);
    }

    /** Reads wrapped-line bounds; MultilineTextField.StringView is only accessible from a subclass. */
    private static final class CodeLines extends MultilineTextField {
        private CodeLines() { super(null, 0); }

        static int begin(MultilineTextField field, int line) { return field.getLineView(line).beginIndex(); }

        static int end(MultilineTextField field, int line) { return field.getLineView(line).endIndex(); }
    }

    /** Writes the hint panel top to bottom and stops at its bottom edge. */
    private final class HintWriter {
        private final GuiGraphicsExtractor graphics;
        private final int x, width, bottom;
        private int y;

        private HintWriter(GuiGraphicsExtractor graphics, int x, int y, int width, int bottom) {
            this.graphics = graphics;
            this.x = x;
            this.y = y;
            this.width = width;
            this.bottom = bottom;
        }

        private void line(String text, int color) {
            if (y + 9 > bottom) return;
            graphics.text(font, Component.literal(trim(text, width)), x, y, color, false);
            y += 11;
        }

        private void wrap(String text, int color) { wrap(text, color, 0); }

        private void wrap(String text, int color, int indent) {
            for (net.minecraft.util.FormattedCharSequence part : font.split(Component.literal(text), width - indent)) {
                if (y + 9 > bottom) return;
                graphics.text(font, part, x + indent, y, color, false);
                y += 10;
            }
        }

        private void gap() { y += 5; }

        /** A small grey line, such as the "one of these" label of alternative inputs. */
        private void note(String text, boolean missing) {
            if (y + 9 > bottom) return;
            graphics.text(font, Component.literal(text), x, y, 0xFF9CA3AF, false);
            if (missing) graphics.text(font, Component.literal("missing"), x + font.width(text) + 6, y, 0xFFFF8A8A, false);
            y += 10;
        }

        /** One input: the current one is highlighted, a missing required one is marked. */
        private void arg(ScriptSignatures.Arg arg, boolean current, boolean missing, int indent) {
            if (y + 9 > bottom) return;
            if (current) graphics.fill(x - 3, y - 2, x + width + 3, y + 9, 0xFF3A3352);
            graphics.text(font, Component.literal(trim(arg.name(), width - indent)), x + indent, y,
                    current ? 0xFFFFD866 : 0xFFF0F1F3, false);
            if (missing) graphics.text(font, Component.literal("missing"), x + indent + font.width(arg.name()) + 6, y,
                    0xFFFF8A8A, false);
            y += 10;
            wrap(arg.about(), 0xFF8E96A3, indent + 6);
            y += 2;
        }
    }

    // Return to the opening screen rather than forcing the player back to gameplay.
    @Override
    public void onClose() {
        leave(this::closePanel);
    }

    private void closePanel() {
        if (!pendingAutoValues.isEmpty()) AdminPanelClient.ignoreNextClosedPanelSnapshot();
        flushAutoSave();
        lastModule = snapshot.selectedModule();
        lastPage = pageId(snapshot);
        lastScroll = scroll;
        minecraft.gui.setScreen(parent);
    }

    // Send one navigation/refresh request through CWP's common transport.
    private static void request(String moduleId, String pageId) {
        ClientPlayNetworking.send(new AdminPanelNetworking.RequestPanelPayload(moduleId, pageId));
    }

    /** Refresh the page after a nested editor saves or deletes MobForge content. */
    public void refreshFromServer() {
        request(snapshot.selectedModule(), snapshot.page() == null ? "" : snapshot.page().id());
    }

    // Determine whether the current snapshot represents the top-level mod selector.
    private boolean isDashboard() {
        return snapshot.selectedModule() == null || snapshot.selectedModule().isBlank() || snapshot.page() == null;
    }

    // Calculate the first y-coordinate reserved for status cards.
    private int contentTop() {
        return top + moduleHeaderHeight() + 3;
    }

    private int moduleHeaderHeight() {
        return HEADER_HEIGHT;
    }

    // Calculate how many complete cards fit between navigation and footer areas.
    private int visibleRows() {
        return Math.max(1, (panelHeight - moduleHeaderHeight() - FOOTER_HEIGHT - 6) / (rowHeight() + ROW_GAP));
    }

    // Choose the card height requested by the current server-provided page layout.
    private int rowHeight() {
        if (snapshot.page() == null) return ROW_HEIGHT;
        if (snapshot.page().layout() == AdminPanelRegistry.PageLayout.PREVIEW_CARDS) return PORTAL_ROW_HEIGHT;
        // Plain pages with input fields keep room for a label line above the fields.
        return snapshot.page().entries().stream().anyMatch(entry -> !entry.fields().isEmpty()) ? FIELD_ROW_HEIGHT : ROW_HEIGHT;
    }

    // Keep a parent tab selected while one of its nested editor pages is open.
    private static boolean isSelectedTab(String pageId, String tabId) {
        return pageId.equals(tabId) || pageId.startsWith(tabId + "/");
    }

    // Draw one compact colored shape preview from server-neutral portal metadata.
    private void renderPreview(GuiGraphicsExtractor graphics, AdminPanelRegistry.EntryPreview preview,
                                      int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF0D1015);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xFF2A303A);
        if (preview.kind().equals("item")) {
            graphics.item(icon(preview.shape()), x + (width - 16) / 2, y + (height - 16) / 2);
            return;
        }
        int color = 0xFF000000 | preview.colorRgb() & 0x00FFFFFF;
        int centerX = x + width / 2;
        int top = y + 8;
        int bottom = y + height - 8;
        int halfWidth = width / 2 - 8;
        switch (preview.shape().toLowerCase(java.util.Locale.ROOT)) {
            case "ellipse" -> {
                int radiusY = Math.max(1, (bottom - top) / 2);
                for (int row = -radiusY; row <= radiusY; row++) {
                    double normalized = (double) row / radiusY;
                    int span = (int) Math.round(halfWidth * Math.sqrt(Math.max(0.0, 1.0 - normalized * normalized)));
                    graphics.fill(centerX - span, y + height / 2 + row, centerX + span + 1,
                            y + height / 2 + row + 1, color);
                }
            }
            case "diamond" -> {
                int radiusY = Math.max(1, (bottom - top) / 2);
                for (int row = -radiusY; row <= radiusY; row++) {
                    int span = halfWidth * (radiusY - Math.abs(row)) / radiusY;
                    graphics.fill(centerX - span, y + height / 2 + row, centerX + span + 1,
                            y + height / 2 + row + 1, color);
                }
            }
            case "triangle" -> {
                int rows = Math.max(1, bottom - top);
                for (int row = 0; row <= rows; row++) {
                    int span = halfWidth * row / rows;
                    graphics.fill(centerX - span, top + row, centerX + span + 1, top + row + 1, color);
                }
            }
            default -> graphics.fill(centerX - halfWidth, top, centerX + halfWidth, bottom, color);
        }
    }

    // Extract a nullable page ID without making update comparisons fragile.
    private static String pageId(AdminPanelNetworking.PanelSnapshot value) {
        return value.page() == null ? "" : value.page().id();
    }

    // Trim one line to its available pixel width with an ellipsis.
    private String trim(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
    }

    // Resolve and cache a module's item icon while gracefully handling invalid extension metadata.
    private ItemStack icon(String itemId) {
        return iconCache.computeIfAbsent(itemId, key -> {
            Identifier identifier = Identifier.tryParse(key);
            return new ItemStack(identifier == null ? Items.BOOK
                    : BuiltInRegistries.ITEM.getOptional(identifier).orElse(Items.BOOK));
        });
    }

    // Show the unmatched suffix of the first case-insensitive completion candidate as ghost text.
    private static void updateSuggestion(EditBox box, AdminPanelRegistry.AdminField field, String value) {
        // Nothing typed yet: the placeholder is shown instead, so the field never shows two words at once.
        String match = value == null || value.isEmpty() ? null : completion(field, value);
        box.setSuggestion(match == null || match.equals(value) ? null : match.substring(value.length()));
    }

    // Find the first server-provided candidate matching a typed prefix.
    private static String completion(AdminPanelRegistry.AdminField field, String value) {
        if (field == null || value == null) return null;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return field.suggestions().stream().filter(candidate ->
                candidate.toLowerCase(java.util.Locale.ROOT).startsWith(normalized)).findFirst().orElse(null);
    }

    /** Room an editor row keeps free for its picture: an item icon or a small entity model. */
    private static int previewWidth(AdminPanelRegistry.AdminEntry entry) {
        if (entry.preview() == null) return 0;
        return switch (entry.preview().kind()) {
            case "item" -> 22;
            case "entity" -> 40;
            default -> 0;
        };
    }

    /** A preview shape "field:<id>" follows what is typed in that field right now. */
    private String previewTarget(AdminPanelRegistry.AdminEntry entry) {
        String shape = entry.preview().shape();
        return shape.startsWith("field:") ? formValues.getOrDefault(shape.substring(6), "").trim() : shape;
    }

    /** Draws the entity type as a small model; entities that cannot be shown that way get their spawn egg. */
    private void renderEntityPreview(GuiGraphicsExtractor graphics, String entityId, int x, int y, int width, int height) {
        net.minecraft.world.entity.LivingEntity entity = previewEntity(entityId);
        if (entity == null) {
            String egg = spawnEgg(entityId);
            if (egg != null) graphics.item(icon(egg), x + (width - 16) / 2, y + (height - 16) / 2);
            return;
        }
        float size = Math.max(1f, Math.min(height / Math.max(0.3f, entity.getBbHeight()),
                width / Math.max(0.3f, entity.getBbWidth())) * 0.8f);
        // The model is drawn into its own picture box in real screen coordinates, which ignores the panel's own
        // scaling; so the box is converted to unscaled coordinates here, and the panel scale is undone meanwhile.
        float scale = panelRenderScale;
        int x0 = Math.round(x * scale), y0 = Math.round(y * scale);
        int x1 = Math.round((x + width) * scale), y1 = Math.round((y + height) * scale);
        graphics.pose().pushMatrix();
        graphics.pose().identity();
        try {
            net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(graphics,
                    x0, y0, x1, y1, Math.max(1, Math.round(size * scale)), 0.0625f, (x0 + x1) / 2f, (y0 + y1) / 2f, entity);
        } catch (RuntimeException exception) {
            // Some entities cannot be drawn outside the world; show their spawn egg from now on.
            entityCache.put(entityId, java.util.Optional.empty());
        }
        graphics.pose().popMatrix();
    }

    private final Map<String, java.util.Optional<net.minecraft.world.entity.LivingEntity>> entityCache = new HashMap<>();

    private net.minecraft.world.entity.LivingEntity previewEntity(String entityId) {
        if (entityId == null || entityId.isBlank() || minecraft.level == null) return null;
        return entityCache.computeIfAbsent(entityId, key -> {
            Identifier id = Identifier.tryParse(key);
            var type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (type == null) return java.util.Optional.empty();
            try {
                net.minecraft.world.entity.Entity created = type.create(minecraft.level,
                        net.minecraft.world.entity.EntitySpawnReason.LOAD);
                if (!(created instanceof net.minecraft.world.entity.LivingEntity living)) return java.util.Optional.empty();
                // Renderers read the entity ID; a negative one cannot clash with a real entity.
                living.setId(-1_000_000 - entityCache.size());
                return java.util.Optional.of(living);
            } catch (RuntimeException exception) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    /** The spawn egg item of an entity type, if it has one. */
    private static String spawnEgg(String entityId) {
        Identifier id = Identifier.tryParse(entityId);
        if (id == null) return null;
        Identifier egg = Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_spawn_egg");
        return BuiltInRegistries.ITEM.containsKey(egg) ? egg.toString() : null;
    }

    // Search fields with many candidates (such as every item) get a clickable dropdown of matches.
    private void updateDropdown(AdminPanelRegistry.AdminField field, String value) {
        if (field.type() != AdminPanelRegistry.FieldType.TEXT || field.suggestions().size() < 2
                || value == null || value.isBlank() || field.suggestions().contains(value)) {
            if (field.id().equals(dropdownField)) closeDropdown();
            return;
        }
        String query = value.trim().toLowerCase(java.util.Locale.ROOT);
        record Match(String candidate, int score) { }
        List<Match> matches = new ArrayList<>();
        for (String candidate : field.suggestions()) {
            String id = candidate.toLowerCase(java.util.Locale.ROOT);
            String path = id.substring(id.indexOf(':') + 1);
            String name = displayName(candidate).toLowerCase(java.util.Locale.ROOT);
            int score = path.startsWith(query) || name.startsWith(query) ? 0
                    : id.startsWith(query) ? 1
                    : path.contains(query) || name.contains(query) ? 2 : -1;
            if (score >= 0) matches.add(new Match(candidate, score));
        }
        matches.sort(java.util.Comparator.comparingInt(Match::score)
                .thenComparingInt(match -> match.candidate().length())
                .thenComparing(Match::candidate));
        dropdownMatches = matches.stream().limit(DROPDOWN_ROWS).map(Match::candidate).toList();
        dropdownField = dropdownMatches.isEmpty() ? null : field.id();
        dropdownIndex = 0;
    }

    // Item IDs show their in-game name; other candidates show the ID itself.
    private String displayName(String candidate) {
        return itemNameCache.computeIfAbsent(candidate, key -> {
            Identifier id = Identifier.tryParse(key);
            if (id != null && !BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ENTITY_TYPE.containsKey(id))
                return BuiltInRegistries.ENTITY_TYPE.getValue(id).getDescription().getString();
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return key;
            return new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getHoverName().getString();
        });
    }

    private boolean dropdownVisible() {
        if (dropdownField == null || dropdownMatches.isEmpty()) return false;
        EditBox box = editBoxes.get(dropdownField);
        return box != null && box.isFocused();
    }

    private void closeDropdown() {
        dropdownField = null;
        dropdownMatches = List.of();
        dropdownIndex = 0;
    }

    private int dropdownWidth(EditBox box) {
        return Math.max(box.getWidth(), 200);
    }

    // Open below the field, or above it when the footer is too close.
    private int dropdownTop(EditBox box) {
        int height = dropdownMatches.size() * DROPDOWN_ROW_HEIGHT;
        int below = box.getY() + box.getHeight() + 1;
        return below + height > top + panelHeight - 4 ? box.getY() - height - 1 : below;
    }

    private int dropdownRowAt(double x, double y) {
        if (!dropdownVisible()) return -1;
        EditBox box = editBoxes.get(dropdownField);
        int dropdownTop = dropdownTop(box);
        if (x < box.getX() || x >= box.getX() + dropdownWidth(box) || y < dropdownTop) return -1;
        int row = (int) (y - dropdownTop) / DROPDOWN_ROW_HEIGHT;
        return row < dropdownMatches.size() ? row : -1;
    }

    private void acceptDropdown(int row) {
        EditBox box = editBoxes.get(dropdownField);
        String value = dropdownMatches.get(row);
        closeDropdown();
        if (box == null) return;
        box.setValue(value);
        box.moveCursorToEnd(false);
        closeDropdown();
    }

    private void renderDropdown(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!dropdownVisible()) return;
        EditBox box = editBoxes.get(dropdownField);
        int x = box.getX();
        int y = dropdownTop(box);
        int width = dropdownWidth(box);
        int height = dropdownMatches.size() * DROPDOWN_ROW_HEIGHT;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF7C5CDA);
        graphics.fill(x, y, x + width, y + height, 0xF0101318);
        int hovered = dropdownRowAt(mouseX, mouseY);
        for (int row = 0; row < dropdownMatches.size(); row++) {
            String candidate = dropdownMatches.get(row);
            int rowY = y + row * DROPDOWN_ROW_HEIGHT;
            if (row == dropdownIndex || row == hovered)
                graphics.fill(x, rowY, x + width, rowY + DROPDOWN_ROW_HEIGHT, row == dropdownIndex ? 0xFF33294D : 0xFF242A35);
            Identifier id = Identifier.tryParse(candidate);
            boolean item = id != null && BuiltInRegistries.ITEM.containsKey(id);
            String egg = item || id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id) ? null : spawnEgg(candidate);
            int textX = x + 4;
            if (item || egg != null) {
                graphics.item(icon(item ? candidate : egg), x + 2, rowY + 1);
                textX = x + 22;
            }
            String name = displayName(candidate);
            String label = trim(name, width - (textX - x) - 4);
            graphics.text(font, Component.literal(label), textX, rowY + 5, 0xFFF0F1F3, false);
            if (!name.equals(candidate)) {
                int idX = textX + font.width(label) + 6;
                int room = x + width - 4 - idX;
                if (room > 30) graphics.text(font, Component.literal(trim(candidate, room)), idX, rowY + 5,
                        0xFF7D8590, false);
            }
        }
    }

    // Convert server-neutral severity into the module card's accent color.
    private static int toneColor(AdminPanelRegistry.Tone tone) {
        return switch (tone) {
            case GOOD -> 0xFF43B581;
            case WARNING -> 0xFFE0A84B;
            case DANGER -> 0xFFE05A68;
            default -> 0xFF6E7B8D;
        };
    }

    /** Screen-space location and definition for one expanded generic select field. */
    private record SelectAnchor(int x, int y, int width, AdminPanelRegistry.AdminField field) {
    }

    /** Calculated screen-space row used identically by compact editor widgets and rendering. */
    private record EditorPlacement(int index, AdminPanelRegistry.AdminEntry entry, int x, int y, int width, int height,
                                   boolean visible) {
    }

    /** One section box of an editor page. */
    private record EditorBox(String title, int x, int y, int width, int height) {
    }

    /** Screen-space card placement shared by preview-grid controls and rendering. */
    private record PreviewHeading(String title, int y) { }

    private record PreviewPlacement(int index, AdminPanelRegistry.AdminEntry entry, int x, int y, int width) {
    }
}
