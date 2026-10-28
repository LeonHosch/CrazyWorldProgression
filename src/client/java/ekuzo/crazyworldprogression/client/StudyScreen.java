package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.reforge.ResearchService;
import ekuzo.crazyworldprogression.reforge.StudyMenu;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * The study table opened by a lectern, in its own scholar's-desk look (ReforgeScreenStyle.study()): the book slot with the Study button, a panel
 * with what this book adds, and on the right every studied enchantment with its level and the points still
 * needed for the next one (none at the top level).
 */
public final class StudyScreen extends AbstractContainerScreen<StudyMenu> {
    private static final int MAIN_WIDTH = 248, LIST_X = 254, LIST_WIDTH = 150, WIDTH = LIST_X + LIST_WIDTH, HEIGHT = 206;
    private static final int PANEL_X = 108, PANEL_RIGHT = MAIN_WIDTH - 8, PANEL_TOP = 16, PANEL_BOTTOM = 92;
    private static final int ROW = 20, LIST_TOP = 20;
    private String parsedState;
    private String message = "";
    private boolean error;
    private String problem = "";
    private final List<Research> research = new ArrayList<>();
    private final List<Gain> gains = new ArrayList<>();
    private int scroll;
    private Button study;

    private record Research(String id, int level, int points, int next) { }

    private record Gain(String id, int points, boolean maxed) { }

    public StudyScreen(StudyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = StudyMenu.INVENTORY_X;
        inventoryLabelY = StudyMenu.INVENTORY_Y - 10;
    }

    static void initialize() {
        MenuScreens.register(StudyMenu.TYPE, StudyScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(ResearchService.StatePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof StudyScreen screen) screen.menu.setState(payload.json());
        });
    }

    @Override
    protected void init() {
        super.init();
        parseState();
        study = addRenderableWidget(Button.builder(Component.literal("Study"), ignored -> {
                    if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
                }).bounds(leftPos + 8, topPos + 74, 92, 18).build());
        updateButton();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        parseState();
        updateButton();
    }

    private void parseState() {
        String state = menu.state();
        if (state != null && !state.isBlank() && !state.equals(parsedState)) {
            parsedState = state;
            JsonObject root = JsonParser.parseString(state).getAsJsonObject();
            message = root.get("message").getAsString();
            error = root.get("error").getAsBoolean();
            research.clear();
            for (JsonElement element : root.getAsJsonArray("research")) {
                JsonObject value = element.getAsJsonObject();
                research.add(new Research(value.get("id").getAsString(), value.get("level").getAsInt(),
                        value.has("points") ? value.get("points").getAsInt() : -1,
                        value.has("next") ? value.get("next").getAsInt() : -1));
            }
        }
        evaluateBook();
    }

    /**
     * What the book in the slot would add, worked out here in the window with the same rules the server checks on
     * Study: each enchantment gives its level's points; already fully studied ones show "max".
     */
    private void evaluateBook() {
        gains.clear();
        var book = menu.book();
        var stored = book.isEmpty() ? null : book.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
        if (book.isEmpty()) { problem = "Place an enchanted book"; return; }
        if (stored == null || stored.isEmpty()) { problem = "This book holds no enchantments"; return; }
        boolean anyOpen = false;
        for (var entry : stored.entrySet()) {
            String id = entry.getKey().unwrapKey().map(key -> key.identifier().toString()).orElse(null);
            if (id == null) continue;
            int level = research.stream().filter(value -> value.id().equals(id)).mapToInt(Research::level).findFirst().orElse(0);
            boolean maxed = level >= ResearchService.MAX_PROFICIENCY;
            anyOpen |= !maxed;
            gains.add(new Gain(id, ResearchService.pointsFor(entry.getIntValue()), maxed));
        }
        problem = anyOpen ? "" : "Everything in this book is already fully studied";
    }

    private void updateButton() {
        if (study != null) study.active = !menu.book().isEmpty() && problem.isEmpty();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = ReforgeScreenStyle.study();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, MAIN_WIDTH, HEIGHT);
        style.panel(graphics, x + PANEL_X, y + PANEL_TOP, PANEL_RIGHT - PANEL_X, PANEL_BOTTOM - PANEL_TOP);
        style.window(graphics, x + LIST_X, y, LIST_WIDTH, HEIGHT);
        for (Slot slot : menu.slots) if (slot.index != 0) style.slot(graphics, x + slot.x, y + slot.y);
        style.itemSocket(graphics, x + StudyMenu.ITEM_X + 8, y + StudyMenu.ITEM_Y + 8, net.minecraft.util.Util.getMillis());
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ReforgeScreenStyle style = ReforgeScreenStyle.study();
        graphics.text(font, title, 8, 6, style.titleColor(), false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
        if (!message.isBlank())
            graphics.text(font, trim(message, MAIN_WIDTH - 16), 8, 100, error ? style.badColor() : style.goodColor(), false);
        extractBook(graphics, style);
        extractResearch(graphics, style);
    }

    /** What the book in the slot adds: each enchantment with its points, or "max" when already fully studied. */
    private void extractBook(GuiGraphicsExtractor graphics, ReforgeScreenStyle style) {
        int centre = (PANEL_X + PANEL_RIGHT) / 2;
        if (menu.book().isEmpty()) {
            graphics.centeredText(font, "Place an enchanted book", centre, PANEL_TOP + 32, style.mutedColor());
            return;
        }
        int y = PANEL_TOP + 4;
        for (Gain gain : gains) {
            if (y + 16 > PANEL_BOTTOM) break;
            EnchantIcons.draw(graphics, gain.id(), PANEL_X + 4, y);
            String amount = gain.maxed() ? "max" : "+" + gain.points();
            int amountX = PANEL_RIGHT - 5 - font.width(amount);
            graphics.text(font, trim(EnchantIcons.name(gain.id()), amountX - PANEL_X - 26), PANEL_X + 22, y + 4,
                    gain.maxed() ? style.mutedColor() : style.textColor(), false);
            graphics.text(font, amount, amountX, y + 4, gain.maxed() ? style.mutedColor() : style.goodColor(), false);
            y += 18;
        }
        if (!problem.isEmpty() && y + 9 <= PANEL_BOTTOM)
            graphics.text(font, trim(problem, PANEL_RIGHT - PANEL_X - 8), PANEL_X + 4, PANEL_BOTTOM - 11, style.badColor(), false);
    }

    /** Every studied enchantment: icon, name, level, and the points still needed below the top level. */
    private void extractResearch(GuiGraphicsExtractor graphics, ReforgeScreenStyle style) {
        int x = LIST_X + 7, width = LIST_WIDTH - 14;
        graphics.text(font, "Studied enchantments", x, 6, style.accentColor(), false);
        if (research.isEmpty()) {
            int y = LIST_TOP;
            for (var part : font.split(Component.literal("Nothing studied yet. Place an enchanted book and press Study."), width)) {
                graphics.text(font, part, x, y, style.mutedColor(), false);
                y += 10;
            }
            return;
        }
        int visible = (HEIGHT - LIST_TOP - 4) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, research.size() - visible)));
        int y = LIST_TOP;
        for (int index = scroll; index < research.size() && index < scroll + visible; index++) {
            Research entry = research.get(index);
            EnchantIcons.draw(graphics, entry.id(), x, y);
            String level = EnchantIcons.roman(entry.level());
            graphics.text(font, trim(EnchantIcons.name(entry.id()), width - 22 - font.width(level) - 4), x + 20, y,
                    style.textColor(), false);
            graphics.text(font, level, x + width - font.width(level), y, style.categoryColor(), false);
            String progress = entry.next() < 0 ? "Fully studied"
                    : (entry.next() - entry.points()) + " more to " + EnchantIcons.roman(entry.level() + 1);
            graphics.text(font, trim(progress, width - 20), x + 20, y + 9, style.mutedColor(), false);
            y += ROW;
        }
        if (research.size() > visible) {
            String more = (scroll + visible < research.size() ? "↓ " : "") + (scroll > 0 ? "↑" : "");
            graphics.text(font, more, LIST_X + LIST_WIDTH - 7 - font.width(more), 6, style.mutedColor(), false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= leftPos + LIST_X && mouseX < leftPos + WIDTH && mouseY >= topPos && mouseY < topPos + HEIGHT) {
            scroll = Math.max(0, scroll + (vertical < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private String trim(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }
}
