package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.admin.AdminPanelNetworking;
import ekuzo.crazyworldprogression.admin.AdminPanelRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

/** Responsive generic administration screen whose content and controls are supplied by server modules. */
public final class AdminPanelScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 680;
    private static final int PANEL_MARGIN = 18;
    private static final int HEADER_HEIGHT = 58;
    private static final int FOOTER_HEIGHT = 34;
    private static final int ROW_HEIGHT = 62;
    private static final int ROW_GAP = 5;
    private final Screen parent;
    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private final Map<String, String> formValues = new HashMap<>();
    private final Map<String, EditBox> editBoxes = new HashMap<>();
    private final Map<String, AdminPanelRegistry.AdminField> fieldDefinitions = new HashMap<>();
    private AdminPanelNetworking.PanelSnapshot snapshot;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int scroll;
    private String pendingDangerousAction;
    private String expandedSelect;
    private SelectAnchor selectAnchor;

    // Create a panel that returns to the screen from which the command opened it.
    public AdminPanelScreen(Screen parent, AdminPanelNetworking.PanelSnapshot snapshot) {
        super(Component.literal("CWP Administration"));
        this.parent = parent;
        this.snapshot = snapshot;
    }

    // Apply authoritative refreshed state after navigation or a completed action.
    public void updateSnapshot(AdminPanelNetworking.PanelSnapshot snapshot) {
        boolean changedPage = this.snapshot == null
                || !this.snapshot.selectedModule().equals(snapshot.selectedModule())
                || !pageId(this.snapshot).equals(pageId(snapshot));
        this.snapshot = snapshot;
        if (changedPage) {
            scroll = 0;
            formValues.clear();
            expandedSelect = null;
        }
        pendingDangerousAction = null;
        rebuildWidgets();
    }

    // Lay out responsive module, navigation, row-action, refresh, and close buttons.
    @Override
    protected void init() {
        editBoxes.clear();
        fieldDefinitions.clear();
        selectAnchor = null;
        panelWidth = Math.min(MAX_PANEL_WIDTH, width - PANEL_MARGIN * 2);
        panelHeight = Math.max(220, height - PANEL_MARGIN * 2);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        if (isDashboard()) buildDashboardButtons();
        else buildModuleButtons();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(left + panelWidth - 88, top + panelHeight - 27, 78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), ignored -> request(
                        snapshot.selectedModule(), pageId(snapshot)))
                .bounds(left + panelWidth - 174, top + panelHeight - 27, 78, 20).build());
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
        addRenderableWidget(Button.builder(Component.literal("‹ Mods"), ignored -> request("", ""))
                .bounds(left + 10, top + 31, 64, 20).build());
        int tabX = left + 80;
        for (AdminPanelRegistry.AdminTab tab : page.tabs()) {
            int tabWidth = Math.max(52, font.width(tab.label()) + 14);
            Button button = Button.builder(Component.literal(tab.label()), ignored -> request(
                            snapshot.selectedModule(), tab.id()))
                    .bounds(tabX, top + 31, tabWidth, 20).build();
            button.active = !tab.id().equals(page.id());
            addRenderableWidget(button);
            tabX += tabWidth + 4;
        }
        List<AdminPanelRegistry.AdminEntry> entries = page.entries();
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - visible)));
        for (int visibleIndex = 0; visibleIndex < visible && scroll + visibleIndex < entries.size(); visibleIndex++) {
            int entryIndex = scroll + visibleIndex;
            AdminPanelRegistry.AdminEntry entry = entries.get(entryIndex);
            int y = contentTop() + visibleIndex * (ROW_HEIGHT + ROW_GAP);
            addFields(entry, y);
            addActionButtons(entry, y);
        }
        addSelectOptions();
    }

    // Add generic text inputs and retain their values while the current page rebuilds its widgets.
    private void addFields(AdminPanelRegistry.AdminEntry entry, int y) {
        if (entry.fields().isEmpty()) return;
        int actionWidth = entry.actions().stream().mapToInt(action ->
                Math.max(38, Math.min(70, font.width(action.label()) + 12)) + 4).sum();
        int available = Math.max(100, panelWidth - 48 - actionWidth);
        int fieldWidth = Math.max(72, Math.min(150,
                (available - (entry.fields().size() - 1) * 5) / entry.fields().size()));
        int x = left + 24;
        for (AdminPanelRegistry.AdminField field : entry.fields()) {
            fieldDefinitions.put(field.id(), field);
            if (field.type() == AdminPanelRegistry.FieldType.SELECT) {
                String value = formValues.computeIfAbsent(field.id(), ignored -> field.initialValue());
                addRenderableWidget(Button.builder(Component.literal(value + " ▾"), ignored -> {
                    expandedSelect = field.id().equals(expandedSelect) ? null : field.id();
                    rebuildWidgets();
                }).bounds(x, y + 35, fieldWidth, 20).build());
                if (field.id().equals(expandedSelect)) selectAnchor = new SelectAnchor(x, y + 35, fieldWidth, field);
                x += fieldWidth + 5;
                continue;
            }
            EditBox box = new EditBox(font, x, y + 35, fieldWidth, 20, Component.literal(field.label()));
            box.setMaxLength(field.maxLength());
            box.setHint(Component.literal(field.placeholder()));
            box.setValue(formValues.getOrDefault(field.id(), field.initialValue()));
            box.setResponder(value -> {
                formValues.put(field.id(), value);
                updateSuggestion(box, field, value);
            });
            updateSuggestion(box, field, box.getValue());
            editBoxes.put(field.id(), box);
            addRenderableWidget(box);
            x += fieldWidth + 5;
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
                expandedSelect = null;
                rebuildWidgets();
            }).bounds(selectAnchor.x(), optionY, selectAnchor.width(), optionHeight).build());
            y += optionHeight;
        }
    }

    // Place an entry's compact controls from right to left while preserving room for its text.
    private void addActionButtons(AdminPanelRegistry.AdminEntry entry, int y) {
        int x = left + panelWidth - 18;
        List<AdminPanelRegistry.AdminAction> actions = entry.actions();
        for (int index = actions.size() - 1; index >= 0; index--) {
            AdminPanelRegistry.AdminAction action = actions.get(index);
            boolean confirming = action.dangerous() && action.id().equals(pendingDangerousAction);
            String label = confirming ? "Confirm" : action.label();
            int buttonWidth = Math.max(38, Math.min(70, font.width(label) + 12));
            x -= buttonWidth;
            addRenderableWidget(Button.builder(Component.literal(label), ignored -> activate(action))
                    .bounds(x, y + (entry.fields().isEmpty() ? 21 : 35), buttonWidth, 20).build());
            x -= 4;
        }
    }

    // Require a deliberate second click for destructive controls before sending them to the server.
    private void activate(AdminPanelRegistry.AdminAction action) {
        if (action.dangerous() && !action.id().equals(pendingDangerousAction)) {
            pendingDangerousAction = action.id();
            rebuildWidgets();
            return;
        }
        ClientPlayNetworking.send(new AdminPanelNetworking.ExecuteActionPayload(
                snapshot.selectedModule(), snapshot.page().id(), action.id(),
                AdminPanelNetworking.encodeValues(formValues)));
    }

    // Render the dark modular shell, current headings, status notice, cards, and scroll indicator.
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xB0000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF012151B);
        graphics.fill(left, top, left + 4, top + panelHeight, 0xFF7C5CDA);
        graphics.text(font, Component.literal(isDashboard() ? "Administration" : snapshot.page().title()),
                left + 14, top + 10, 0xFFF2F2F5, false);
        String subtitle = isDashboard() ? "Choose a mod to manage" : snapshot.page().subtitle();
        graphics.text(font, Component.literal(trim(subtitle, panelWidth - 28)),
                left + 14, top + 21, 0xFF9CA3AF, false);
        if (isDashboard()) renderDashboard(graphics);
        else renderEntries(graphics);
        if (snapshot.notice() != null && !snapshot.notice().isBlank()) {
            graphics.text(font, Component.literal(trim(snapshot.notice(), panelWidth - 190)), left + 14,
                    top + panelHeight - 21, snapshot.error() ? 0xFFFF6B6B : 0xFF72D69C, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (isDashboard()) renderModuleIcons(graphics);
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
        List<AdminPanelRegistry.AdminEntry> entries = snapshot.page().entries();
        int visible = visibleRows();
        for (int index = 0; index < visible && scroll + index < entries.size(); index++) {
            AdminPanelRegistry.AdminEntry entry = entries.get(scroll + index);
            int y = contentTop() + index * (ROW_HEIGHT + ROW_GAP);
            graphics.fill(left + 14, y, left + panelWidth - 14, y + ROW_HEIGHT, 0xFF1D222B);
            graphics.fill(left + 14, y, left + 17, y + ROW_HEIGHT, toneColor(entry.tone()));
            int actionWidth = entry.actions().stream().mapToInt(action ->
                    Math.max(38, Math.min(70, font.width(action.label()) + 12)) + 4).sum();
            int textWidth = Math.max(80, panelWidth - 48 - actionWidth);
            graphics.text(font, Component.literal(trim(entry.title(), textWidth)),
                    left + 24, y + 10, 0xFFF0F1F3, false);
            graphics.text(font, Component.literal(trim(entry.detail(), textWidth)),
                    left + 24, y + 22, 0xFFA7AFBA, false);
        }
        if (entries.size() > visible) {
            String position = (scroll + 1) + "–" + Math.min(entries.size(), scroll + visible)
                    + " / " + entries.size();
            graphics.text(font, Component.literal(position), left + panelWidth - font.width(position) - 16,
                    top + 10, 0xFF9CA3AF, false);
        }
    }

    // Scroll status cards while leaving navigation and footer controls fixed.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
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

    // Return to the opening screen rather than forcing the player back to gameplay.
    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    // Send one navigation/refresh request through CWP's common transport.
    private static void request(String moduleId, String pageId) {
        ClientPlayNetworking.send(new AdminPanelNetworking.RequestPanelPayload(moduleId, pageId));
    }

    // Determine whether the current snapshot represents the top-level mod selector.
    private boolean isDashboard() {
        return snapshot.selectedModule() == null || snapshot.selectedModule().isBlank() || snapshot.page() == null;
    }

    // Calculate the first y-coordinate reserved for status cards.
    private int contentTop() {
        return top + HEADER_HEIGHT + 3;
    }

    // Calculate how many complete cards fit between navigation and footer areas.
    private int visibleRows() {
        return Math.max(1, (panelHeight - HEADER_HEIGHT - FOOTER_HEIGHT - 6) / (ROW_HEIGHT + ROW_GAP));
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
        String match = completion(field, value);
        box.setSuggestion(match == null || match.equals(value) ? null : match.substring(value.length()));
    }

    // Find the first server-provided candidate matching a typed prefix.
    private static String completion(AdminPanelRegistry.AdminField field, String value) {
        if (field == null || value == null) return null;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return field.suggestions().stream().filter(candidate ->
                candidate.toLowerCase(java.util.Locale.ROOT).startsWith(normalized)).findFirst().orElse(null);
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
}
