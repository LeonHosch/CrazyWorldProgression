package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.fletching.FletchingMenu;
import ekuzo.crazyworldprogression.fletching.FletchingNetworking;
import ekuzo.crazyworldprogression.skills.Proficiency;
import ekuzo.crazyworldprogression.smithing.SmithingBranding;
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

/**
 * The fletching window opened by a fletching table: a page of recipes (result, rank, costs and a Craft button each)
 * above the player's inventory. Recipes above the player's Craftsmanship rank show what they need instead. Looks like
 * the repair window (ReforgeScreenStyle.smithing()).
 */
public final class FletchingScreen extends AbstractContainerScreen<FletchingMenu> {
    private static final int WIDTH = 300, HEIGHT = 222;
    private static final int LIST_TOP = 18, ROW = 22, ROWS = 5, COSTS_X = 118, BUTTON_X = 254;
    private String parsedState;
    private int rank;
    private int level;
    private int extra;
    private String message = "";
    private boolean error;
    private int page;
    private final List<RecipeView> recipes = new ArrayList<>();
    private JsonObject balances = new JsonObject();
    private final List<Button> craftButtons = new ArrayList<>();
    private final List<HoverArea> hover = new ArrayList<>();

    private record HoverArea(int x, int y, int width, int height, List<Component> lines) { }

    private record RecipeView(String output, String name, int count, int rank, List<CostCells.Cost> costs) { }

    public FletchingScreen(FletchingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = FletchingMenu.INVENTORY_X;
        inventoryLabelY = FletchingMenu.INVENTORY_Y - 10;
    }

    static void initialize() {
        MenuScreens.register(FletchingMenu.TYPE, FletchingScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(FletchingNetworking.StatePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof FletchingScreen screen) screen.menu.setState(payload.json());
        });
        // What each fletched arrow does, under its name.
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (stack.getItem() instanceof ekuzo.crazyworldprogression.fletching.FletchedArrowItem && !lines.isEmpty())
                lines.add(1, Component.translatable(stack.getItem().getDescriptionId() + ".desc")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
        });
    }

    @Override
    protected void init() {
        super.init();
        parseState();
        craftButtons.clear();
        for (int row = 0; row < ROWS; row++) {
            int slot = row;
            craftButtons.add(addRenderableWidget(Button.builder(Component.literal("Craft"), ignored -> {
                        int index = page * ROWS + slot;
                        if (minecraft.gameMode != null && index < recipes.size())
                            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, index);
                    }).bounds(leftPos + BUTTON_X, topPos + LIST_TOP + row * ROW + 1, 38, 18).build()));
        }
        addRenderableWidget(Button.builder(Component.literal("<"), ignored -> page = Math.max(0, page - 1))
                .bounds(leftPos + WIDTH - 64, topPos + 4, 14, 12).build());
        addRenderableWidget(Button.builder(Component.literal(">"), ignored -> page = Math.min(pages() - 1, page + 1))
                .bounds(leftPos + WIDTH - 22, topPos + 4, 14, 12).build());
        updateButtons();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        parseState();
        updateButtons();
    }

    private int pages() { return Math.max(1, (recipes.size() + ROWS - 1) / ROWS); }

    private void parseState() {
        String state = menu.state();
        if (state == null || state.isBlank() || state.equals(parsedState)) return;
        parsedState = state;
        JsonObject root = JsonParser.parseString(state).getAsJsonObject();
        rank = root.get("rank").getAsInt();
        level = root.get("level").getAsInt();
        extra = root.get("extra").getAsInt();
        message = root.get("message").getAsString();
        error = root.get("error").getAsBoolean();
        balances = root.getAsJsonObject("balances");
        recipes.clear();
        for (JsonElement element : root.getAsJsonArray("recipes")) {
            JsonObject recipe = element.getAsJsonObject();
            List<CostCells.Cost> costs = new ArrayList<>();
            for (JsonElement cost : recipe.getAsJsonArray("costs")) {
                JsonObject value = cost.getAsJsonObject();
                costs.add(new CostCells.Cost(value.get("kind").getAsString(), value.get("target").getAsString(),
                        value.get("amount").getAsInt(), value.get("label").getAsString()));
            }
            recipes.add(new RecipeView(recipe.get("output").getAsString(), recipe.get("name").getAsString(),
                    recipe.get("count").getAsInt(), recipe.get("rank").getAsInt(), costs));
        }
        page = Math.min(page, pages() - 1);
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

    private void updateButtons() {
        for (int row = 0; row < craftButtons.size(); row++) {
            int index = page * ROWS + row;
            Button button = craftButtons.get(row);
            button.visible = index < recipes.size();
            if (!button.visible) continue;
            RecipeView recipe = recipes.get(index);
            button.active = rank >= recipe.rank() && recipe.costs().stream().allMatch(cost -> have(cost) >= cost.amount());
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = ReforgeScreenStyle.smithing();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, WIDTH, HEIGHT);
        for (int row = 0; row < ROWS; row++)
            if (page * ROWS + row < recipes.size())
                style.panel(graphics, x + 6, y + LIST_TOP + row * ROW, WIDTH - 12, ROW - 2);
        for (Slot slot : menu.slots) style.slot(graphics, x + slot.x, y + slot.y);
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
        ReforgeScreenStyle style = ReforgeScreenStyle.smithing();
        String name = SmithingBranding.get().name();
        graphics.text(font, title, 8, 6, style.titleColor(), false);
        String status = name + " " + Proficiency.name(rank) + " · Lv " + level;
        int statusX = WIDTH - 70 - font.width(status);
        graphics.text(font, status, statusX, 6, style.accentColor(), false);
        hover.add(new HoverArea(leftPos + statusX, topPos + 5, font.width(status), 10, List.of(
                Component.literal(name + " proficiency: " + Proficiency.name(rank) + " (unlocks recipes)"),
                Component.literal(name + " level " + level + ": " + extra + "% chance of a double batch"))));
        String pageText = (page + 1) + "/" + pages();
        graphics.centeredText(font, pageText, WIDTH - 36, 6, style.mutedColor());
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
        if (recipes.isEmpty()) graphics.centeredText(font, "No fletching recipes yet", WIDTH / 2, LIST_TOP + 30, style.mutedColor());
        for (int row = 0; row < ROWS; row++) {
            int index = page * ROWS + row;
            if (index >= recipes.size()) break;
            RecipeView recipe = recipes.get(index);
            int y = LIST_TOP + row * ROW + 2;
            var item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(recipe.output()));
            if (item.isPresent()) {
                ItemStack stack = new ItemStack(item.get(), recipe.count());
                graphics.item(stack, 10, y);
                graphics.itemDecorations(font, stack, 10, y);
                hover.add(new HoverArea(leftPos + 10, topPos + y, 16, 16,
                        List.of(stack.getHoverName(), Component.literal("Makes " + recipe.count() + " per craft"))));
            }
            graphics.text(font, trim(recipe.name(), COSTS_X - 34), 30, y, style.textColor(), false);
            boolean unlocked = rank >= recipe.rank();
            graphics.text(font, Proficiency.name(recipe.rank()), 30, y + 9,
                    unlocked ? style.mutedColor() : style.badColor(), false);
            if (!unlocked) {
                hover.add(new HoverArea(leftPos + 30, topPos + y + 8, font.width(Proficiency.name(recipe.rank())), 10,
                        List.of(Component.literal("Needs " + Proficiency.name(recipe.rank()) + " " + name))));
            }
            if (recipe.costs().isEmpty()) graphics.text(font, "Free", COSTS_X, y + 5, style.mutedColor(), false);
            else CostCells.row(graphics, font, style, recipe.costs(), COSTS_X, y, BUTTON_X - 2, leftPos, topPos, this::have,
                    (x, hy, width, height, lines) -> hover.add(new HoverArea(x, hy, width, height, lines)));
        }
        if (!message.isBlank())
            graphics.text(font, trim(message, WIDTH - 16), 8, LIST_TOP + ROWS * ROW + 1, error ? style.badColor() : style.goodColor(), false);
    }

    private String trim(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }
}
