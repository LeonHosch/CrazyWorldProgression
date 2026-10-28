package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.farming.CrossbreedingMenu;
import ekuzo.crazyworldprogression.farming.CrossbreedingNetworking;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The crossbreeding window opened by a composter: two seed pots, the result under a glass dome, and a panel with what
 * the pair makes (or "Unknown result" with its success chance until discovered), the farming level and the costs.
 * The server decides what may be shown and sends it whenever the seeds change; the look is
 * ReforgeScreenStyle.crossbreeding().
 */
public final class CrossbreedingScreen extends AbstractContainerScreen<CrossbreedingMenu> {
    private static final int WIDTH = 248, HEIGHT = 206;
    private static final int PANEL_X = 108, PANEL_RIGHT = WIDTH - 8, PANEL_TOP = 16, PANEL_BOTTOM = 92;
    private String parsedState;
    private int level;
    private String message = "";
    private boolean error;
    private String problem = "";
    private boolean hasRecipe;
    private boolean known;
    private ItemStack output = ItemStack.EMPTY;
    private int failure;
    private int required;
    private final List<CostCells.Cost> costs = new ArrayList<>();
    private JsonObject balances = new JsonObject();
    private Button action;
    private final List<HoverArea> hover = new ArrayList<>();

    private record HoverArea(int x, int y, int width, int height, List<Component> lines) { }

    public CrossbreedingScreen(CrossbreedingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = CrossbreedingMenu.INVENTORY_X;
        inventoryLabelY = CrossbreedingMenu.INVENTORY_Y - 10;
    }

    static void initialize() {
        MenuScreens.register(CrossbreedingMenu.TYPE, CrossbreedingScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(CrossbreedingNetworking.StatePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof CrossbreedingScreen screen) screen.menu.setState(payload.json());
        });
    }

    @Override
    protected void init() {
        super.init();
        parseState();
        action = addRenderableWidget(Button.builder(Component.literal("Crossbreed"), ignored -> {
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
        hasRecipe = root.has("known");
        known = hasRecipe && root.get("known").getAsBoolean();
        required = root.has("required") ? root.get("required").getAsInt() : 0;
        failure = root.has("failure") ? root.get("failure").getAsInt() : 0;
        output = ItemStack.EMPTY;
        if (root.has("output")) {
            Identifier id = Identifier.tryParse(root.get("output").getAsString());
            if (id != null) output = new ItemStack(BuiltInRegistries.ITEM.getValue(id), root.get("count").getAsInt());
        }
        costs.clear();
        if (root.has("costs")) for (JsonElement element : root.getAsJsonArray("costs")) {
            JsonObject cost = element.getAsJsonObject();
            costs.add(new CostCells.Cost(cost.get("kind").getAsString(), cost.get("target").getAsString(),
                    cost.get("amount").getAsInt(), cost.get("label").getAsString()));
        }
        balances = root.has("balances") ? root.getAsJsonObject("balances") : new JsonObject();
    }

    private long have(CostCells.Cost cost) {
        if (cost.kind().equals("currency"))
            return balances.has(cost.target()) ? balances.get(cost.target()).getAsLong() : 0L;
        Identifier id = Identifier.tryParse(cost.target());
        long count = 0;
        Inventory inventory = minecraft.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) count += stack.getCount();
        }
        return count;
    }

    private void updateButton() {
        if (action == null) return;
        action.active = hasRecipe && problem.isEmpty() && costs.stream().allMatch(cost -> have(cost) >= cost.amount());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = ReforgeScreenStyle.crossbreeding();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, WIDTH, HEIGHT);
        style.panel(graphics, x + PANEL_X, y + PANEL_TOP, PANEL_RIGHT - PANEL_X, PANEL_BOTTOM - PANEL_TOP);
        for (Slot slot : menu.slots) if (slot.index != 2) style.slot(graphics, x + slot.x, y + slot.y);
        style.itemSocket(graphics, x + CrossbreedingMenu.OUTPUT_X + 8, y + CrossbreedingMenu.OUTPUT_Y + 8,
                net.minecraft.util.Util.getMillis());
        // "+" between the seeds and a small arrow down to the result.
        int plusX = x + (CrossbreedingMenu.FIRST_X + CrossbreedingMenu.SECOND_X) / 2 + 8, plusY = y + CrossbreedingMenu.SEED_Y + 8;
        graphics.fill(plusX - 4, plusY - 1, plusX + 4, plusY + 1, style.accentColor());
        graphics.fill(plusX - 1, plusY - 4, plusX + 1, plusY + 4, style.accentColor());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        hover.clear();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        for (HoverArea area : hover)
            if (mouseX >= area.x() && mouseX < area.x() + area.width() && mouseY >= area.y() && mouseY < area.y() + area.height()) {
                graphics.setComponentTooltipForNextFrame(font, area.lines(), mouseX, mouseY);
                break;
            }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ReforgeScreenStyle style = ReforgeScreenStyle.crossbreeding();
        graphics.text(font, title, 8, 6, style.titleColor(), false);
        String levelText = "Farming " + level;
        graphics.text(font, levelText, WIDTH - 8 - font.width(levelText), 6, style.accentColor(), false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
        if (!message.isBlank())
            graphics.text(font, trim(message, WIDTH - 16), 8, 100, error ? style.badColor() : style.goodColor(), false);
        int centre = (PANEL_X + PANEL_RIGHT) / 2;
        if (!hasRecipe) {
            String text = problem.isEmpty() ? "Place two seeds" : problem;
            int lineY = PANEL_TOP + 30;
            for (var part : font.split(Component.literal(text), PANEL_RIGHT - PANEL_X - 10)) {
                graphics.centeredText(font, part, centre, lineY, problem.equals("Place two seeds") ? style.mutedColor() : style.badColor());
                lineY += 10;
            }
            return;
        }
        if (known) {
            graphics.item(output, PANEL_X + 6, PANEL_TOP + 4);
            graphics.text(font, trim(output.getHoverName().getString(), PANEL_RIGHT - PANEL_X - 30), PANEL_X + 26,
                    PANEL_TOP + 5, style.textColor(), false);
            graphics.text(font, "Known · always works", PANEL_X + 26, PANEL_TOP + 15, style.goodColor(), false);
        } else {
            // A mystery seed: a "?" in a soil box, never the real item.
            graphics.fill(PANEL_X + 6, PANEL_TOP + 4, PANEL_X + 22, PANEL_TOP + 20, 0xFF2A1C10);
            graphics.centeredText(font, "?", PANEL_X + 14, PANEL_TOP + 8, style.accentColor());
            graphics.text(font, "Unknown result", PANEL_X + 26, PANEL_TOP + 5, style.textColor(), false);
            String success = "Success " + percent(10_000 - failure);
            int color = failure <= 1000 ? style.goodColor() : failure <= 4000 ? 0xFFFFD866 : style.badColor();
            graphics.text(font, success, PANEL_X + 26, PANEL_TOP + 15, color, false);
            hover.add(new HoverArea(leftPos + PANEL_X + 26, topPos + PANEL_TOP + 14, font.width(success), 10,
                    List.of(Component.literal("Chance this new crossbreed works"),
                            Component.literal("A failure uses up the seeds and materials"),
                            Component.literal("Once discovered it always works"))));
        }
        int rowY = PANEL_TOP + 30;
        List<CostCells.Cost> items = costs.stream().filter(cost -> !cost.kind().equals("currency")).toList();
        List<CostCells.Cost> currencies = costs.stream().filter(cost -> cost.kind().equals("currency")).toList();
        if (items.isEmpty() && currencies.isEmpty()) graphics.centeredText(font, "No extra cost", centre, rowY + 4, style.mutedColor());
        if (!items.isEmpty()) {
            CostCells.row(graphics, font, style, items, PANEL_X + 5, rowY, PANEL_RIGHT, leftPos, topPos, this::have,
                    (x, y, width, height, lines) -> hover.add(new HoverArea(x, y, width, height, lines)));
            rowY += 19;
        }
        if (!currencies.isEmpty())
            CostCells.row(graphics, font, style, currencies, PANEL_X + 5, rowY, PANEL_RIGHT, leftPos, topPos, this::have,
                    (x, y, width, height, lines) -> hover.add(new HoverArea(x, y, width, height, lines)));
        String footer = !problem.isEmpty() ? problem : required > ekuzo.crazyworldprogression.skills.Proficiency.NOVICE ? "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(required) + " farming" : "";
        if (!footer.isEmpty()) graphics.text(font, trim(footer, PANEL_RIGHT - PANEL_X - 8), PANEL_X + 4, PANEL_BOTTOM - 10,
                problem.isEmpty() ? style.mutedColor() : style.badColor(), false);
    }

    private static String percent(int hundredths) {
        double value = hundredths / 100.0;
        return (value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value)
                .replaceAll("0+$", "").replaceAll("\\.$", "")) + "%";
    }

    private String trim(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }
}
