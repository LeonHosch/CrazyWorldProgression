package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.cooking.CookingData;
import ekuzo.crazyworldprogression.cooking.CookingMenu;
import ekuzo.crazyworldprogression.cooking.CookingNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The cooking window for cauldron stews and the smoker: three ingredient slots, the bowl or coal slot, the dish slot
 * over the pot or grate, and a panel with the dish, the cooking level and what is missing. Looks come from
 * ReforgeScreenStyle.stewPot() and smoker().
 */
public final class CookingScreen extends AbstractContainerScreen<CookingMenu> {
    private static final int WIDTH = 248, HEIGHT = 206;
    private static final int PANEL_X = 108, PANEL_RIGHT = WIDTH - 8, PANEL_TOP = 16, PANEL_BOTTOM = 92;
    private String parsedState;
    private int level;
    private String message = "";
    private boolean error;
    private String problem = "";
    private String stationProblem = "";
    private ItemStack dish = ItemStack.EMPTY;
    private int required;
    private Button action;

    public CookingScreen(CookingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = CookingMenu.INVENTORY_X;
        inventoryLabelY = CookingMenu.INVENTORY_Y - 10;
    }

    static void initialize() {
        MenuScreens.register(CookingMenu.TYPE, CookingScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(CookingNetworking.StatePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof CookingScreen screen) screen.menu.setState(payload.json());
        });
    }

    private boolean stew() { return menu.station() == CookingData.Station.STEW; }

    private ReforgeScreenStyle style() { return stew() ? ReforgeScreenStyle.stewPot() : ReforgeScreenStyle.smoker(); }

    @Override
    protected void init() {
        super.init();
        parseState();
        action = addRenderableWidget(Button.builder(Component.literal("Cook"), ignored -> {
                    if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
                }).bounds(leftPos + 8, topPos + 78, 92, 18).build());
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
        if (state == null || state.isBlank() || state.equals(parsedState)) return;
        parsedState = state;
        JsonObject root = JsonParser.parseString(state).getAsJsonObject();
        level = root.get("level").getAsInt();
        message = root.get("message").getAsString();
        error = root.get("error").getAsBoolean();
        problem = root.has("problem") ? root.get("problem").getAsString() : "";
        stationProblem = root.has("station_problem") ? root.get("station_problem").getAsString() : "";
        required = root.has("required") ? root.get("required").getAsInt() : 0;
        dish = ItemStack.EMPTY;
        if (root.has("output")) {
            Identifier id = Identifier.tryParse(root.get("output").getAsString());
            if (id != null) dish = new ItemStack(BuiltInRegistries.ITEM.getValue(id), root.get("count").getAsInt());
        }
    }

    private void updateButton() {
        if (action != null) action.active = !dish.isEmpty() && problem.isEmpty();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = style();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, WIDTH, HEIGHT);
        style.panel(graphics, x + PANEL_X, y + PANEL_TOP, PANEL_RIGHT - PANEL_X, PANEL_BOTTOM - PANEL_TOP);
        for (Slot slot : menu.slots) if (slot.index != 4) style.slot(graphics, x + slot.x, y + slot.y);
        style.itemSocket(graphics, x + CookingMenu.OUTPUT_X + 8, y + CookingMenu.OUTPUT_Y + 8, net.minecraft.util.Util.getMillis());
        // A faint bowl or coal in the empty tool slot shows what goes there.
        if (menu.slots.get(3).getItem().isEmpty()) {
            graphics.item(new ItemStack(stew() ? Items.BOWL : Items.COAL), x + CookingMenu.TOOL_X, y + CookingMenu.TOOL_Y);
            graphics.fill(x + CookingMenu.TOOL_X, y + CookingMenu.TOOL_Y, x + CookingMenu.TOOL_X + 16, y + CookingMenu.TOOL_Y + 16, 0xA03A322A);
        }
        // Arrow from the ingredients to the dish.
        int ax = x + CookingMenu.INGREDIENT_X + 3 * 18 + 2, ay = y + CookingMenu.OUTPUT_Y + 7;
        graphics.fill(ax, ay, ax + 8, ay + 2, style.accentColor());
        graphics.fill(ax + 6, ay - 2, ax + 8, ay + 4, style.accentColor());
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ReforgeScreenStyle style = style();
        graphics.text(font, title, 8, 6, style.titleColor(), false);
        String levelText = "Cooking " + level;
        graphics.text(font, levelText, WIDTH - 8 - font.width(levelText), 6, style.accentColor(), false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
        if (!message.isBlank())
            graphics.text(font, trim(message, WIDTH - 16), 8, 100, error ? style.badColor() : style.goodColor(), false);
        int centre = (PANEL_X + PANEL_RIGHT) / 2;
        int textWidth = PANEL_RIGHT - PANEL_X - 10;
        if (!dish.isEmpty()) {
            graphics.item(dish, PANEL_X + 6, PANEL_TOP + 4);
            graphics.itemDecorations(font, dish, PANEL_X + 6, PANEL_TOP + 4);
            graphics.text(font, trim(dish.getHoverName().getString(), PANEL_RIGHT - PANEL_X - 30), PANEL_X + 26,
                    PANEL_TOP + 5, style.textColor(), false);
            graphics.text(font, required > ekuzo.crazyworldprogression.skills.Proficiency.NOVICE ? "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(required) + " cooking" : "No proficiency needed", PANEL_X + 26,
                    PANEL_TOP + 15, required > level ? style.badColor() : style.mutedColor(), false);
        } else graphics.centeredText(font, "Add ingredients", centre, PANEL_TOP + 10, style.mutedColor());
        String shown = !problem.isEmpty() && !problem.equals("Add ingredients") ? problem : stationProblem;
        int lineY = PANEL_TOP + 34;
        if (!shown.isEmpty()) for (var part : font.split(Component.literal(shown), textWidth)) {
            graphics.centeredText(font, part, centre, lineY, style.badColor());
            lineY += 10;
        }
        String uses = stew() ? "Uses 1 bowl per dish" : "Uses 1 coal per dish";
        graphics.text(font, uses, PANEL_X + 4, PANEL_BOTTOM - 10, style.mutedColor(), false);
    }

    private String trim(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }
}
