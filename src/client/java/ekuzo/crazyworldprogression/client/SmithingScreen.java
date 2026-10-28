package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.smithing.SmithingBranding;
import ekuzo.crazyworldprogression.smithing.SmithingMenu;
import ekuzo.crazyworldprogression.smithing.SmithingNetworking;
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
 * The resmith window opened by an anvil, laid out like the reforge window: the item socket with the action button
 * below it, and a panel with the player's smithing tier, the success rate and the item's costs. The tier is not
 * chosen; the server works it out from the player's Smithing Proficiency. Names come from SmithingBranding, the
 * look from ReforgeScreenStyle.smithing().
 */
public final class SmithingScreen extends AbstractContainerScreen<SmithingMenu> {
    private static final int WIDTH = 248, HEIGHT = 206;
    private static final int PANEL_X = 108, PANEL_RIGHT = WIDTH - 8, PANEL_TOP = 16, PANEL_BOTTOM = 92;
    private String parsedState;
    private int proficiency;
    private String tierName;
    private int failure;
    private int nextRequired = -1;
    private String problem = "";
    private String fromTier;
    private boolean resolved;
    private final List<CostCells.Cost> costs = new ArrayList<>();
    private String message = "";
    private boolean error;
    private Button action;
    private final List<HoverArea> hover = new ArrayList<>();

    private record HoverArea(int x, int y, int width, int height, List<Component> lines) { }

    public SmithingScreen(SmithingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = SmithingMenu.INVENTORY_X;
        inventoryLabelY = SmithingMenu.INVENTORY_Y - 10;
    }

    static void initialize() {
        MenuScreens.register(SmithingMenu.TYPE, SmithingScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(SmithingNetworking.StatePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof SmithingScreen screen) screen.menu.setState(payload.json());
        });
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (ekuzo.crazyworldprogression.smithing.ItemDurability.isBrokenDown(stack))
                lines.add(1, Component.literal("Broken: " + SmithingBranding.get().action().toLowerCase(Locale.ROOT)
                        + " it at an anvil").withStyle(net.minecraft.ChatFormatting.RED));
        });
    }

    @Override
    protected void init() {
        super.init();
        parseState();
        action = addRenderableWidget(Button.builder(Component.literal(SmithingBranding.get().action()), ignored -> {
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

    /** One tier as sent on opening: required level, failure chance and costs per item ID. */
    private record TierData(String name, int required, int failure, java.util.Map<String, List<CostCells.Cost>> items) { }

    private final List<TierData> tierData = new ArrayList<>();
    /** Chest upgrades to Normal..Perfect: rank and costs (ChestQuality.STEPS). */
    private record ChestStep(int rank, List<CostCells.Cost> costs) { }
    private final List<ChestStep> chestSteps = new ArrayList<>();
    /** The quality the chest in the slot would be upgraded to, or -1 when it is not a chest. */
    private int chestTarget = -1;
    private JsonObject balanceData = new JsonObject();

    private void parseState() {
        String state = menu.state();
        if (state != null && !state.isBlank() && !state.equals(parsedState)) {
            parsedState = state;
            JsonObject root = JsonParser.parseString(state).getAsJsonObject();
            proficiency = root.get("proficiency").getAsInt();
            message = root.get("message").getAsString();
            error = root.get("error").getAsBoolean();
            balanceData = root.getAsJsonObject("balances");
            tierData.clear();
            for (JsonElement element : root.getAsJsonArray("tiers")) {
                JsonObject tier = element.getAsJsonObject();
                java.util.Map<String, List<CostCells.Cost>> items = new java.util.HashMap<>();
                for (var item : tier.getAsJsonObject("items").entrySet()) {
                    List<CostCells.Cost> list = new ArrayList<>();
                    for (JsonElement cost : item.getValue().getAsJsonArray()) {
                        JsonObject value = cost.getAsJsonObject();
                        list.add(new CostCells.Cost(value.get("kind").getAsString(), value.get("target").getAsString(),
                                value.get("amount").getAsInt(), value.get("label").getAsString()));
                    }
                    items.put(item.getKey(), list);
                }
                tierData.add(new TierData(tier.get("name").getAsString(), tier.get("required").getAsInt(),
                        tier.get("failure").getAsInt(), items));
            }
            chestSteps.clear();
            if (root.has("chests")) for (JsonElement element : root.getAsJsonArray("chests")) {
                JsonObject step = element.getAsJsonObject();
                List<CostCells.Cost> list = new ArrayList<>();
                for (JsonElement cost : step.getAsJsonArray("costs")) {
                    JsonObject value = cost.getAsJsonObject();
                    list.add(new CostCells.Cost(value.get("kind").getAsString(), value.get("target").getAsString(),
                            value.get("amount").getAsInt(), value.get("label").getAsString()));
                }
                chestSteps.add(new ChestStep(step.get("rank").getAsInt(), list));
            }
        }
        evaluate();
    }

    /**
     * Works out, here in the window, what the server will check on Repair: the highest tier the proficiency reaches,
     * the item's costs from that tier or the nearest lower one listing it, and whether the item can be repaired.
     */
    private void evaluate() {
        TierData current = tierData.stream().filter(tier -> tier.required() <= proficiency)
                .max(java.util.Comparator.comparingInt(TierData::required)).orElse(null);
        tierName = current == null ? null : current.name();
        failure = current == null ? 0 : current.failure();
        nextRequired = tierData.stream().mapToInt(TierData::required).filter(required -> required > proficiency).min().orElse(-1);
        costs.clear();
        fromTier = null;
        resolved = false;
        chestTarget = -1;
        ItemStack stack = menu.item();
        String action = SmithingBranding.get().action().toLowerCase(Locale.ROOT);
        if (stack.isEmpty()) { problem = "Place an item on the anvil"; return; }
        if (ekuzo.crazyworldprogression.smithing.ChestQuality.isChest(stack)) {
            int quality = ekuzo.crazyworldprogression.smithing.ChestQuality.of(stack);
            if (quality >= chestSteps.size()) { problem = "This chest is already Perfect"; return; }
            ChestStep step = chestSteps.get(quality);
            chestTarget = quality + 1;
            resolved = true;
            costs.addAll(step.costs());
            failure = 0;
            problem = proficiency < step.rank() ? "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(step.rank())
                    + " " + SmithingBranding.get().name() : "";
            return;
        }
        if (current == null) { problem = "You need " + SmithingBranding.get().name() + " Proficiency to " + action; return; }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        TierData source = tierData.stream().filter(tier -> tier.required() <= proficiency && tier.items().containsKey(id))
                .max(java.util.Comparator.comparingInt(TierData::required)).orElse(null);
        if (!stack.isDamageableItem()) problem = "This item has no durability";
        else if (source == null) problem = "Your " + SmithingBranding.get().name() + " tier cannot " + action + " this item";
        else if (stack.getDamageValue() == 0 && !ekuzo.crazyworldprogression.smithing.ItemDurability.isBrokenDown(stack)
                && ekuzo.crazyworldprogression.smithing.ItemDurability.quality(stack)
                == ekuzo.crazyworldprogression.farming.ProduceQuality.Quality.PERFECT)
            problem = "This item is already Perfect";
        else problem = "";
        if (source != null) {
            resolved = true;
            costs.addAll(source.items().get(id));
            if (source != current) fromTier = source.name();
        }
    }

    private long have(CostCells.Cost cost) {
        if (cost.kind().equals("currency"))
            return balanceData.has(cost.target()) ? balanceData.get(cost.target()).getAsLong() : 0L;
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
        action.setMessage(Component.literal(chestTarget >= 0 ? "Upgrade" : SmithingBranding.get().action()));
        action.active = !menu.item().isEmpty() && problem.isEmpty() && resolved
                && costs.stream().allMatch(cost -> have(cost) >= cost.amount());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = ReforgeScreenStyle.smithing();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, WIDTH, HEIGHT);
        style.panel(graphics, x + PANEL_X, y + PANEL_TOP, PANEL_RIGHT - PANEL_X, PANEL_BOTTOM - PANEL_TOP);
        for (Slot slot : menu.slots) if (slot.index != 0) style.slot(graphics, x + slot.x, y + slot.y);
        style.itemSocket(graphics, x + SmithingMenu.ITEM_X + 8, y + SmithingMenu.ITEM_Y + 8,
                net.minecraft.util.Util.getMillis());
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
        String level = name + " " + ekuzo.crazyworldprogression.skills.Proficiency.name(proficiency);
        graphics.text(font, level, WIDTH - 8 - font.width(level), 6, style.accentColor(), false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
        if (!message.isBlank())
            graphics.text(font, trim(message, WIDTH - 16), 8, 100, error ? style.badColor() : style.goodColor(), false);
        int centre = (PANEL_X + PANEL_RIGHT) / 2;
        if (chestTarget >= 0) {
            var quality = ekuzo.crazyworldprogression.farming.ProduceQuality.Quality.of(chestTarget);
            graphics.centeredText(font, "Upgrade to " + quality.label(), centre, PANEL_TOP + 5, style.textColor());
            graphics.centeredText(font, ekuzo.crazyworldprogression.smithing.ChestQuality.slotsFor(chestTarget) + " slots",
                    centre, PANEL_TOP + 19, style.goodColor());
        } else if (tierName == null) {
            graphics.centeredText(font, "No " + name + " tier yet", centre, PANEL_TOP + 5, style.badColor());
            if (nextRequired > 0)
                graphics.centeredText(font, "Reach " + ekuzo.crazyworldprogression.skills.Proficiency.name(nextRequired) + " " + name, centre, PANEL_TOP + 20, style.mutedColor());
            return;
        }
        if (chestTarget < 0) {
            graphics.centeredText(font, trim(tierName, PANEL_RIGHT - PANEL_X - 8), centre, PANEL_TOP + 5, style.textColor());
            String success = "Success " + percent(10_000 - failure);
            int successColor = failure <= 1000 ? style.goodColor() : failure <= 4000 ? 0xFFFFD866 : style.badColor();
            graphics.centeredText(font, success, centre, PANEL_TOP + 19, successColor);
            hover.add(new HoverArea(leftPos + centre - font.width(success) / 2, topPos + PANEL_TOP + 18, font.width(success), 10,
                    List.of(Component.literal("Chance that the " + SmithingBranding.get().action().toLowerCase(Locale.ROOT)
                            + " works"), Component.literal("A failure uses up the materials and leaves the item as it is"))));
        }
        if (menu.item().isEmpty()) {
            graphics.centeredText(font, "Place an item", centre, PANEL_TOP + 44, style.mutedColor());
            return;
        }
        if (!problem.isEmpty() && !resolved) {
            for (var part : font.split(Component.literal(problem), PANEL_RIGHT - PANEL_X - 10)) {
                graphics.centeredText(font, part, centre, PANEL_TOP + 38, style.badColor());
                break;
            }
            return;
        }
        List<CostCells.Cost> items = costs.stream().filter(cost -> !cost.kind().equals("currency")).toList();
        List<CostCells.Cost> currencies = costs.stream().filter(cost -> cost.kind().equals("currency")).toList();
        int rowY = PANEL_TOP + 32;
        if (items.isEmpty() && currencies.isEmpty())
            graphics.centeredText(font, "Free", centre, rowY + 6, style.mutedColor());
        if (!items.isEmpty()) {
            CostCells.row(graphics, font, style, items, PANEL_X + 5, rowY, PANEL_RIGHT, leftPos, topPos, this::have,
                    (x, y, width, height, lines) -> hover.add(new HoverArea(x, y, width, height, lines)));
            rowY += 19;
        }
        if (!currencies.isEmpty())
            CostCells.row(graphics, font, style, currencies, PANEL_X + 5, rowY, PANEL_RIGHT, leftPos, topPos, this::have,
                    (x, y, width, height, lines) -> hover.add(new HoverArea(x, y, width, height, lines)));
        if (!problem.isEmpty()) graphics.text(font, trim(problem, PANEL_RIGHT - PANEL_X - 8), PANEL_X + 4, PANEL_BOTTOM - 10,
                style.mutedColor(), false);
        else if (fromTier != null) graphics.text(font, trim("Prices from " + fromTier, PANEL_RIGHT - PANEL_X - 8),
                PANEL_X + 4, PANEL_BOTTOM - 10, style.mutedColor(), false);
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
