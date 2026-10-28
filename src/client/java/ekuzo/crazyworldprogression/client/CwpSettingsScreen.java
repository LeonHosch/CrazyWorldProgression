package ekuzo.crazyworldprogression.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Immediate, locally saved client options, grouped by category with a description per option. */
public final class CwpSettingsScreen extends Screen {
    private static final String KEY = "screen.crazy-world-progression.client_settings.";
    private static final int ROW_WIDTH = 310;
    private static final int TOGGLE_WIDTH = 64;
    private static final int TEXT_WIDTH = ROW_WIDTH - TOGGLE_WIDTH - 22;
    private static final int ACCENT = 0xFFE0B040;
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private SettingsList list;

    public CwpSettingsScreen(Screen parent) {
        super(CwpClientSettings.menuTitle());
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        list = layout.addToContents(new SettingsList(minecraft, width, layout));
        populate();
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.translatable(KEY + "reset"), ignored -> {
            CwpClientSettings.resetToDefaults();
            CwpClientSettings.save();
            rebuildWidgets();
        }).width(150).tooltip(Tooltip.create(Component.translatable(KEY + "reset.description"))).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose()).width(150).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private void populate() {
        list.category("portals");
        list.toggle("coordinate_portal_menu", () -> CwpClientSettings.coordinatePortalMenu,
                value -> CwpClientSettings.coordinatePortalMenu = value);
        list.toggle("exit_portal_menu", () -> CwpClientSettings.exitPortalMenu,
                value -> CwpClientSettings.exitPortalMenu = value);
        list.toggle("portal_particles", () -> CwpClientSettings.portalParticles,
                value -> CwpClientSettings.portalParticles = value);
        list.toggle("semi_private_players", () -> CwpClientSettings.showPlayersInSemiPrivateInstances,
                value -> CwpClientSettings.showPlayersInSemiPrivateInstances = value);
        list.category("inventory");
        list.toggle("protect_hotbar", () -> CwpClientSettings.protectHotbar,
                value -> CwpClientSettings.protectHotbar = value);
        list.category("names");
        list.toggle("show_titles", () -> CwpClientSettings.showTitles,
                value -> CwpClientSettings.showTitles = value);
        list.toggle("show_player_names", () -> CwpClientSettings.showPlayerNames,
                value -> CwpClientSettings.showPlayerNames = value);
        list.category("building");
        list.toggle("placement_previews", () -> CwpClientSettings.editorPlacementPreviews,
                value -> CwpClientSettings.editorPlacementPreviews = value);
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) list.updateSize(width, layout);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private final class SettingsList extends ContainerObjectSelectionList<Row> {
        SettingsList(Minecraft minecraft, int width, HeaderAndFooterLayout layout) {
            super(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), 24);
            this.centerListVertically = false;
        }

        void category(String id) {
            int paddingTop = children().isEmpty() ? 2 : 12;
            addEntry(new CategoryRow(Component.translatable(KEY + "category." + id), paddingTop), paddingTop + 16);
        }

        void toggle(String id, BooleanSupplier getter, Consumer<Boolean> setter) {
            OptionRow row = new OptionRow(id, getter, setter);
            addEntry(row, row.height());
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }
    }

    private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> { }

    private final class CategoryRow extends Row {
        private final Component label;
        private final int paddingTop;

        CategoryRow(Component label, int paddingTop) {
            this.label = label;
            this.paddingTop = paddingTop;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int left = getContentX();
            int top = getContentY() + paddingTop;
            graphics.text(font, label, left, top, ACCENT, true);
            int lineLeft = left + font.width(label) + 6;
            graphics.fill(lineLeft, top + 4, getContentRight(), top + 5, 0x60E0B040);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private final class OptionRow extends Row {
        private final Component label;
        private final List<FormattedCharSequence> description;
        private final CycleButton<Boolean> toggle;

        OptionRow(String id, BooleanSupplier getter, Consumer<Boolean> setter) {
            label = Component.translatable(KEY + "option." + id);
            Component descriptionText = Component.translatable(KEY + "option." + id + ".description");
            description = font.split(descriptionText, TEXT_WIDTH);
            toggle = CycleButton.onOffBuilder(getter.getAsBoolean())
                    .displayOnlyValue()
                    .withTooltip(value -> Tooltip.create(descriptionText))
                    .create(label, (button, value) -> {
                        setter.accept(value);
                        CwpClientSettings.save();
                    });
            toggle.setWidth(TOGGLE_WIDTH);
        }

        int height() {
            return Math.max(26, 8 + 11 + description.size() * 10);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int left = getContentX();
            int top = getContentY();
            int right = getContentRight();
            int bottom = top + height() - 4;
            graphics.fill(left, top, right, bottom, hovered ? 0x38FFFFFF : 0x24000000);
            graphics.text(font, label, left + 6, top + 4, 0xFFFFFFFF, true);
            int y = top + 15;
            for (FormattedCharSequence line : description) {
                graphics.text(font, line, left + 6, y, 0xFFA8A8A8, false);
                y += 10;
            }
            toggle.setPosition(right - TOGGLE_WIDTH - 5, top + (bottom - top - 20) / 2);
            toggle.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(toggle);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(toggle);
        }
    }
}
