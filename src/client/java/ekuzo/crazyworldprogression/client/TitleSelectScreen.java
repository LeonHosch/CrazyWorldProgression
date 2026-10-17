package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.titles.TitleNetworking;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Lets the player pick one of their unlocked achievement titles; the server validates every choice. */
public final class TitleSelectScreen extends Screen {
    private static final String KEY = "screen.crazy-world-progression.titles.";
    private static final int ROW_WIDTH = 310;
    private static final int ROW_HEIGHT = 34;
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private TitleList list;

    public TitleSelectScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        list = layout.addToContents(new TitleList(minecraft, width, layout));
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        TitleClient.onOptionsChanged(this::refresh);
        refresh();
        TitleClient.requestOptions();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) list.updateSize(width, layout);
    }

    private void refresh() {
        if (list != null) list.rebuild(TitleClient.options());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        String message = statusMessage();
        if (message != null)
            graphics.centeredText(font, Component.translatable(KEY + message), width / 2,
                    layout.getHeaderHeight() + 24, 0xFFA0A0A0);
    }

    private String statusMessage() {
        if (!TitleClient.available()) return "unavailable";
        TitleNetworking.Options options = TitleClient.options();
        if (options == null) return "loading";
        return options.titles().isEmpty() ? "empty" : null;
    }

    @Override
    public void removed() {
        TitleClient.onOptionsChanged(null);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private final class TitleList extends ContainerObjectSelectionList<TitleEntry> {
        TitleList(Minecraft minecraft, int width, HeaderAndFooterLayout layout) {
            super(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight(), ROW_HEIGHT);
            this.centerListVertically = false;
        }

        void rebuild(TitleNetworking.Options options) {
            clearEntries();
            if (options == null || options.titles().isEmpty()) return;
            addEntry(new TitleEntry(null, options.equipped().isEmpty()));
            for (TitleNetworking.Option option : options.titles())
                addEntry(new TitleEntry(option, option.id().equals(options.equipped())));
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }
    }

    private final class TitleEntry extends ContainerObjectSelectionList.Entry<TitleEntry> {
        private final TitleNetworking.Option option;
        private final boolean equipped;
        private final Component name;
        private final Component detail;
        private final Button button;

        TitleEntry(TitleNetworking.Option option, boolean equipped) {
            this.option = option;
            this.equipped = equipped;
            if (option == null) {
                name = Component.translatable(KEY + "none");
                detail = Component.translatable(KEY + "none.description");
            } else {
                name = Component.literal(option.name()).withColor(TitleClient.color(option.color()));
                Component achievement = achievementName(option.achievementId());
                detail = achievement == null ? Component.literal(option.id())
                        : Component.translatable(KEY + "unlocked_by", achievement);
            }
            button = Button.builder(Component.translatable(KEY + (equipped ? "equipped" : "equip")), ignored -> {
                if (option == null) TitleClient.clear();
                else TitleClient.equip(option.id());
            }).width(80).build();
            button.active = !equipped;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int left = getContentX();
            int top = getContentY();
            int right = getContentRight();
            int bottom = top + ROW_HEIGHT - 4;
            int accent = option == null ? 0xFF808080 : 0xFF000000 | TitleClient.color(option.color());
            graphics.fill(left, top, right, bottom, equipped ? 0x50FFFFFF : hovered ? 0x30FFFFFF : 0x20000000);
            graphics.fill(left, top, left + 3, bottom, accent);
            if (equipped) graphics.outline(left, top, right - left, bottom - top, 0x80FFFFFF);
            graphics.text(font, name, left + 10, top + 5, 0xFFFFFFFF, true);
            graphics.text(font, detail, left + 10, top + 17, 0xFFA0A0A0, false);
            button.setPosition(right - button.getWidth() - 5, top + (bottom - top - 20) / 2);
            button.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }

    private Component achievementName(String achievementId) {
        if (minecraft.player == null) return null;
        try {
            AdvancementHolder holder = minecraft.player.connection.getAdvancements().get(Identifier.parse(achievementId));
            return holder == null ? null : holder.value().display().map(display -> display.getTitle()).orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
