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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

/** Responsive generic administration screen whose content and controls are supplied by server modules. */
public final class AdminPanelScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 900;
    private static final int PANEL_MARGIN = 18;
    private static final int HEADER_HEIGHT = 58;
    private static final int FOOTER_HEIGHT = 34;
    private static final int ROW_HEIGHT = 62;
    private static final int PORTAL_ROW_HEIGHT = 76;
    private static final int EDITOR_FIELD_ROW_HEIGHT = 35;
    private static final int EDITOR_ACTION_ROW_HEIGHT = 26;
    private static final int EDITOR_SECTION_HEIGHT = 16;
    private static final int ROW_GAP = 5;
    private final Screen parent;
    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private final Map<String, String> formValues = new HashMap<>();
    private final Map<String, String> pendingAutoValues = new HashMap<>();
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
    private long autoSaveAtMillis;

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
        boolean successfulAction = snapshot.notice() != null && !snapshot.notice().isBlank() && !snapshot.error();
        if (changedPage || successfulAction) {
            scroll = 0;
            formValues.clear();
            pendingAutoValues.clear();
            autoSaveAtMillis = 0L;
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
        addRenderableWidget(Button.builder(Component.literal("Refresh"), ignored -> {
                    flushAutoSave();
                    request(snapshot.selectedModule(), pageId(snapshot));
                })
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
        addRenderableWidget(Button.builder(Component.literal("‹ Mods"), ignored -> {
                    flushAutoSave();
                    request("", "");
                })
                .bounds(left + 10, top + 31, 64, 20).build());
        int tabX = left + 80;
        int tabY = top + 31;
        for (AdminPanelRegistry.AdminTab tab : page.tabs()) {
            int tabWidth = Math.max(52, font.width(tab.label()) + 14);
            if (tabX + tabWidth > left + panelWidth - 10) {
                tabX = left + 10;
                tabY += 24;
            }
            Button button = Button.builder(Component.literal(tab.label()), ignored -> {
                        flushAutoSave();
                        request(snapshot.selectedModule(), tab.id());
                    })
                    .bounds(tabX, tabY, tabWidth, 20).build();
            button.active = !isSelectedTab(page.id(), tab.id());
            addRenderableWidget(button);
            tabX += tabWidth + 4;
        }
        List<AdminPanelRegistry.AdminEntry> entries = page.entries();
        if (page.layout() == AdminPanelRegistry.PageLayout.EDITOR) {
            buildEditorWidgets();
            addSelectOptions();
            return;
        }
        if (page.layout() == AdminPanelRegistry.PageLayout.PREVIEW_CARDS) {
            for (PreviewPlacement placement : previewPlacements()) {
                addActionButtons(placement.entry(), placement.y(),
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
            addFields(entry, y, left + 24, panelWidth - 48, false);
            addActionButtons(entry, y, left + panelWidth - 18, false);
        }
        addSelectOptions();
    }

    // Lay out a compact continuous editor using section dividers instead of oversized status cards.
    private void buildEditorWidgets() {
        List<EditorPlacement> placements = editorPlacements();
        for (EditorPlacement placement : placements) {
            AdminPanelRegistry.AdminEntry entry = placement.entry();
            addFields(entry, placement.y(), left + 24, panelWidth - 48, true);
            addActionButtons(entry, placement.y(), left + panelWidth - 24, true);
        }
    }

    // Add generic text inputs and retain their values while the current page rebuilds its widgets.
    private void addFields(AdminPanelRegistry.AdminEntry entry, int y, int areaX, int areaWidth, boolean compact) {
        if (entry.fields().isEmpty()) return;
        int actionWidth = actionWidth(entry);
        int available = Math.max(100, areaWidth - actionWidth);
        int dividedWidth = (available - (entry.fields().size() - 1) * 6) / entry.fields().size();
        int fieldWidth = Math.max(72, compact ? dividedWidth : Math.min(150, dividedWidth));
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

    // Place an entry's compact controls from right to left while preserving room for its text.
    private void addActionButtons(AdminPanelRegistry.AdminEntry entry, int y, int right, boolean compact) {
        if (compact && entry.actions().size() == 1 && entry.actions().getFirst().wide()) {
            AdminPanelRegistry.AdminAction action = entry.actions().getFirst();
            String label = action.dangerous() && action.id().equals(pendingDangerousAction)
                    ? "Confirm" : action.label();
            addRenderableWidget(Button.builder(Component.literal(label), ignored -> activate(action))
                    .bounds(left + 24, y + 3, right - left - 24, 20).build());
            return;
        }
        int x = right;
        List<AdminPanelRegistry.AdminAction> actions = entry.actions();
        for (int index = actions.size() - 1; index >= 0; index--) {
            AdminPanelRegistry.AdminAction action = actions.get(index);
            boolean confirming = action.dangerous() && action.id().equals(pendingDangerousAction);
            String label = confirming ? "Confirm" : action.label();
            int buttonWidth = Math.max(38, Math.min(70, font.width(label) + 12));
            x -= buttonWidth;
            int buttonY = compact ? y + (entry.fields().isEmpty() ? 3 : 13)
                    : y + (entry.fields().isEmpty() ? (rowHeight() - 20) / 2 : 35);
            addRenderableWidget(Button.builder(Component.literal(label), ignored -> activate(action))
                    .bounds(x, buttonY, buttonWidth, 20).build());
            x -= 4;
        }
    }

    // Return the total horizontal space reserved by an entry's action buttons.
    private int actionWidth(AdminPanelRegistry.AdminEntry entry) {
        return entry.actions().stream().mapToInt(action ->
                Math.max(38, Math.min(70, font.width(action.label()) + 12)) + 4).sum();
    }

    // Require a deliberate second click for destructive controls before sending them to the server.
    private void activate(AdminPanelRegistry.AdminAction action) {
        if (action.targetPage() != null && !action.targetPage().isBlank()) {
            flushAutoSave();
            request(snapshot.selectedModule(), action.targetPage());
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
            graphics.fill(left + 14, y, left + panelWidth - 14, y + rowHeight(), 0xFF1D222B);
            graphics.fill(left + 14, y, left + 17, y + rowHeight(), toneColor(entry.tone()));
            int actionWidth = entry.actions().stream().mapToInt(action ->
                    Math.max(38, Math.min(70, font.width(action.label()) + 12)) + 4).sum();
            int previewWidth = entry.preview() == null ? 0 : 66;
            int textWidth = Math.max(80, panelWidth - 48 - actionWidth - previewWidth);
            int textX = left + 24 + previewWidth;
            if (entry.preview() != null) renderPreview(graphics, entry.preview(), left + 23, y + 9, 56, 56);
            graphics.text(font, Component.literal(trim(entry.title(), textWidth)),
                    textX, y + 10, 0xFFF0F1F3, false);
            graphics.text(font, Component.literal(trim(entry.detail(), textWidth)),
                    textX, y + 24, 0xFFA7AFBA, false);
        }
        if (entries.size() > visible) {
            String position = (scroll + 1) + "–" + Math.min(entries.size(), scroll + visible)
                    + " / " + entries.size();
            graphics.text(font, Component.literal(position), left + panelWidth - font.width(position) - 16,
                    top + 10, 0xFF9CA3AF, false);
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
            graphics.fill(x, y, x + 3, y + PORTAL_ROW_HEIGHT, toneColor(entry.tone()));
            int previewWidth = entry.preview() == null ? 0 : 58;
            if (entry.preview() != null) renderPreview(graphics, entry.preview(), x + 9, y + 10, 50, 56);
            int actions = actionWidth(entry);
            int textX = x + 10 + previewWidth;
            int textWidth = Math.max(45, cardWidth - 20 - previewWidth - actions);
            graphics.text(font, Component.literal(trim(entry.title(), textWidth)), textX, y + 17,
                    0xFFF0F1F3, false);
            graphics.text(font, Component.literal(trim(entry.detail(), textWidth)), textX, y + 32,
                    0xFFA7AFBA, false);
        }
        int columns = previewColumns();
        int totalRows = (snapshot.page().entries().size() + columns - 1) / columns;
        if (totalRows > visibleRows()) {
            String position = (scroll + 1) + "–" + Math.min(totalRows, scroll + visibleRows())
                    + " / " + totalRows + " rows";
            graphics.text(font, Component.literal(position), left + panelWidth - font.width(position) - 16,
                    top + 10, 0xFF9CA3AF, false);
        }
    }

    // Calculate responsive preview-card positions using two columns whenever the screen has enough width.
    private List<PreviewPlacement> previewPlacements() {
        List<PreviewPlacement> placements = new ArrayList<>();
        List<AdminPanelRegistry.AdminEntry> entries = snapshot.page().entries();
        int columns = previewColumns();
        int rows = visibleRows();
        int totalRows = (entries.size() + columns - 1) / columns;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));
        int gap = 7;
        int available = panelWidth - 28;
        int cardWidth = (available - gap * (columns - 1)) / columns;
        int first = scroll * columns;
        int capacity = rows * columns;
        for (int slot = 0; slot < capacity && first + slot < entries.size(); slot++) {
            int column = slot % columns;
            int row = slot / columns;
            int x = left + 14 + column * (cardWidth + gap);
            int y = contentTop() + row * (PORTAL_ROW_HEIGHT + ROW_GAP);
            placements.add(new PreviewPlacement(first + slot, entries.get(first + slot), x, y, cardWidth));
        }
        return placements;
    }

    // Return the responsive preview-grid column count for the current scaled window width.
    private int previewColumns() {
        return panelWidth >= 700 ? 2 : 1;
    }

    // Render a dense form with shared section headings, aligned labels, and subtle row backgrounds.
    private void renderEditor(GuiGraphicsExtractor graphics) {
        List<EditorPlacement> placements = editorPlacements();
        for (EditorPlacement placement : placements) {
            AdminPanelRegistry.AdminEntry entry = placement.entry();
            if (placement.sectionY() >= 0) {
                String section = placement.section();
                graphics.text(font, Component.literal(section), left + 22, placement.sectionY() + 3,
                        0xFFE1D8FF, false);
                int lineX = left + 30 + font.width(section);
                graphics.fill(lineX, placement.sectionY() + 7, left + panelWidth - 22,
                        placement.sectionY() + 8, 0xFF4A405F);
            }
            graphics.fill(left + 18, placement.y(), left + panelWidth - 18,
                    placement.y() + placement.height(), 0x99191E26);
            if (entry.fields().isEmpty()) {
                graphics.text(font, Component.literal(editorRowName(entry.title())), left + 25,
                        placement.y() + 8, 0xFFF0F1F3, false);
                continue;
            }
            int actionWidth = actionWidth(entry);
            int available = Math.max(100, panelWidth - 48 - actionWidth);
            int fieldWidth = Math.max(72,
                    (available - (entry.fields().size() - 1) * 6) / entry.fields().size());
            int x = left + 24;
            for (AdminPanelRegistry.AdminField field : entry.fields()) {
                graphics.text(font, Component.literal(trim(field.label(), fieldWidth)), x, placement.y() + 2,
                        0xFFAEB6C2, false);
                x += fieldWidth + 6;
            }
        }
        if (!placements.isEmpty()) {
            int last = placements.getLast().index() + 1;
            if (scroll > 0 || last < snapshot.page().entries().size()) {
                String position = (scroll + 1) + "–" + last + " / " + snapshot.page().entries().size();
                graphics.text(font, Component.literal(position), left + panelWidth - font.width(position) - 16,
                        top + 10, 0xFF9CA3AF, false);
            }
        }
    }

    // Calculate visible compact-editor rows and insert space only when a logical section changes.
    private List<EditorPlacement> editorPlacements() {
        List<EditorPlacement> placements = new ArrayList<>();
        List<AdminPanelRegistry.AdminEntry> entries = snapshot.page().entries();
        int y = contentTop() + 1;
        String previousSection = "";
        for (int index = scroll; index < entries.size(); index++) {
            AdminPanelRegistry.AdminEntry entry = entries.get(index);
            String section = editorSection(entry);
            boolean changedSection = placements.isEmpty() || !section.equals(previousSection);
            int sectionY = -1;
            if (changedSection) {
                sectionY = y;
                y += EDITOR_SECTION_HEIGHT;
            }
            int height = entry.fields().isEmpty() ? EDITOR_ACTION_ROW_HEIGHT : EDITOR_FIELD_ROW_HEIGHT;
            if (y + height > top + panelHeight - FOOTER_HEIGHT - 2) break;
            placements.add(new EditorPlacement(index, entry, y, height, section, sectionY));
            y += height + 3;
            previousSection = section;
        }
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
        if (!isDashboard() && snapshot.page().layout() == AdminPanelRegistry.PageLayout.EDITOR) {
            List<EditorPlacement> placements = editorPlacements();
            int previous = scroll;
            if (verticalAmount < 0 && !placements.isEmpty()
                    && placements.getLast().index() < snapshot.page().entries().size() - 1) scroll++;
            else if (verticalAmount > 0) scroll = Math.max(0, scroll - 1);
            if (scroll != previous) rebuildWidgets();
            return true;
        }
        if (!isDashboard() && snapshot.page().layout() == AdminPanelRegistry.PageLayout.PREVIEW_CARDS) {
            int columns = previewColumns();
            int totalRows = (snapshot.page().entries().size() + columns - 1) / columns;
            int previous = scroll;
            scroll = Math.max(0, Math.min(Math.max(0, totalRows - visibleRows()),
                    scroll + (verticalAmount < 0 ? 1 : -1)));
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
        flushAutoSave();
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
        if (isDashboard()) return HEADER_HEIGHT;
        int x = 80;
        int rows = 1;
        for (AdminPanelRegistry.AdminTab tab : snapshot.page().tabs()) {
            int tabWidth = Math.max(52, font.width(tab.label()) + 14);
            if (x + tabWidth > panelWidth - 10) {
                rows++;
                x = 10;
            }
            x += tabWidth + 4;
        }
        return HEADER_HEIGHT + (rows - 1) * 24;
    }

    // Calculate how many complete cards fit between navigation and footer areas.
    private int visibleRows() {
        return Math.max(1, (panelHeight - moduleHeaderHeight() - FOOTER_HEIGHT - 6) / (rowHeight() + ROW_GAP));
    }

    // Choose the card height requested by the current server-provided page layout.
    private int rowHeight() {
        return snapshot.page() != null && snapshot.page().layout() == AdminPanelRegistry.PageLayout.PREVIEW_CARDS
                ? PORTAL_ROW_HEIGHT : ROW_HEIGHT;
    }

    // Keep a parent tab selected while one of its nested editor pages is open.
    private static boolean isSelectedTab(String pageId, String tabId) {
        return pageId.equals(tabId) || pageId.startsWith(tabId + "/");
    }

    // Draw one compact colored shape preview from server-neutral portal metadata.
    private static void renderPreview(GuiGraphicsExtractor graphics, AdminPanelRegistry.EntryPreview preview,
                                      int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF0D1015);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xFF2A303A);
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

    /** Calculated screen-space row used identically by compact editor widgets and rendering. */
    private record EditorPlacement(int index, AdminPanelRegistry.AdminEntry entry, int y, int height,
                                   String section, int sectionY) {
    }

    /** Screen-space card placement shared by preview-grid controls and rendering. */
    private record PreviewPlacement(int index, AdminPanelRegistry.AdminEntry entry, int x, int y, int width) {
    }
}
