package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.divine.DivineProtection;
import ekuzo.crazyworldprogression.divine.DivineProtectionMenu;
import ekuzo.crazyworldprogression.divine.DivineProtectionNetworking;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
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
 * The Divine Protection window, laid out like the resmith window: the item socket with the charge button below it,
 * and a panel with the item's charges and what the next one needs and costs. The look comes from
 * ReforgeScreenStyle.divine().
 */
public final class DivineProtectionScreen extends AbstractContainerScreen<DivineProtectionMenu> {
    private static final int WIDTH = 248, HEIGHT = 206;
    private static final int PANEL_X = 108, PANEL_RIGHT = WIDTH - 8, PANEL_TOP = 16, PANEL_BOTTOM = 92;
    private String parsedState;
    private String skill = "Enchanting";
    private int level, skillLevel, xp, next;
    private String message = "";
    private boolean error;
    private final List<ChargeData> charges = new ArrayList<>();
    private JsonObject balances = new JsonObject();
    private Button action;
    private final List<HoverArea> hover = new ArrayList<>();

    private record HoverArea(int x, int y, int width, int height, List<Component> lines) { }

    private record ChargeData(int required, int failure, List<CostCells.Cost> costs) {
        int failureAt(int level, int drop) { return Math.max(0, failure - drop * Math.max(0, level - required)); }
    }

    private int failureDrop;

    public DivineProtectionScreen(DivineProtectionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = DivineProtectionMenu.INVENTORY_X;
        inventoryLabelY = DivineProtectionMenu.INVENTORY_Y - 10;
    }

    static void initialize() {
        MenuScreens.register(DivineProtectionMenu.TYPE, DivineProtectionScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(DivineProtectionNetworking.StatePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof DivineProtectionScreen screen) screen.menu.setState(payload.json());
        });
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            int amount = DivineProtection.charges(stack);
            if (amount <= 0 || lines.isEmpty()) return;
            lines.add(1, Component.literal("Divine Protection " + "✦".repeat(amount)).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("✧".repeat(DivineProtection.MAX_CHARGES - amount)).withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(" " + amount + "/" + DivineProtection.MAX_CHARGES).withStyle(ChatFormatting.GRAY)));
        });
    }

    @Override
    protected void init() {
        super.init();
        parseState();
        action = addRenderableWidget(Button.builder(Component.literal("Bless"), ignored -> {
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
        if (state == null || state.isBlank() || state.equals(parsedState)) return;
        parsedState = state;
        JsonObject root = JsonParser.parseString(state).getAsJsonObject();
        skill = root.get("skill").getAsString();
        level = root.get("level").getAsInt();
        skillLevel = root.has("skillLevel") ? root.get("skillLevel").getAsInt() : 0;
        xp = root.get("xp").getAsInt();
        next = root.get("next").getAsInt();
        message = root.get("message").getAsString();
        error = root.get("error").getAsBoolean();
        balances = root.getAsJsonObject("balances");
        failureDrop = root.has("failureDrop") ? root.get("failureDrop").getAsInt() : 0;
        charges.clear();
        for (JsonElement element : root.getAsJsonArray("charges")) {
            JsonObject charge = element.getAsJsonObject();
            List<CostCells.Cost> costs = new ArrayList<>();
            for (JsonElement cost : charge.getAsJsonArray("costs")) {
                JsonObject value = cost.getAsJsonObject();
                costs.add(new CostCells.Cost(value.get("kind").getAsString(), value.get("target").getAsString(),
                        value.get("amount").getAsInt(), value.get("label").getAsString()));
            }
            charges.add(new ChargeData(charge.get("required").getAsInt(),
                    charge.has("failure") ? charge.get("failure").getAsInt() : 0, costs));
        }
    }

    /** The next charge for the item in the slot, or null (no item, or all charges). */
    private ChargeData nextCharge() {
        ItemStack stack = menu.item();
        int current = DivineProtection.charges(stack);
        return stack.isEmpty() || current >= charges.size() ? null : charges.get(current);
    }

    private long have(CostCells.Cost cost) {
        if (cost.kind().equals("currency")) return balances.has(cost.target()) ? balances.get(cost.target()).getAsLong() : 0L;
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
        ChargeData charge = nextCharge();
        action.active = charge != null && level >= charge.required()
                && charge.costs().stream().allMatch(cost -> have(cost) >= cost.amount());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = ReforgeScreenStyle.divine();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, WIDTH, HEIGHT);
        style.panel(graphics, x + PANEL_X, y + PANEL_TOP, PANEL_RIGHT - PANEL_X, PANEL_BOTTOM - PANEL_TOP);
        for (Slot slot : menu.slots) if (slot.index != 0) style.slot(graphics, x + slot.x, y + slot.y);
        style.itemSocket(graphics, x + DivineProtectionMenu.ITEM_X + 8, y + DivineProtectionMenu.ITEM_Y + 8,
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
        ReforgeScreenStyle style = ReforgeScreenStyle.divine();
        graphics.text(font, title, 8, 6, style.titleColor(), false);
        String levelText = skill + " " + ekuzo.crazyworldprogression.skills.Proficiency.name(level);
        int levelX = WIDTH - 8 - font.width(levelText);
        graphics.text(font, levelText, levelX, 6, style.accentColor(), false);
        hover.add(new HoverArea(leftPos + levelX, topPos + 5, font.width(levelText), 10, List.of(
                Component.literal(skill + " proficiency: " + ekuzo.crazyworldprogression.skills.Proficiency.name(level) + " (" + level + "/" + ekuzo.crazyworldprogression.skills.Proficiency.MAX + ", from skill trees)"),
                Component.literal(skill + " level " + skillLevel + (next < 0 ? " (max)" : ": " + xp + "/" + next + " XP to the next level")),
                Component.literal("The level grows by " + skill.toLowerCase(java.util.Locale.ROOT) + " and blessing items,"),
                Component.literal("and unlocks skill tree nodes that give proficiency"))));
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
        if (!message.isBlank())
            graphics.text(font, trim(message, WIDTH - 16), 8, 100, error ? style.badColor() : style.goodColor(), false);
        int centre = (PANEL_X + PANEL_RIGHT) / 2;
        ItemStack stack = menu.item();
        if (stack.isEmpty()) {
            graphics.centeredText(font, "Place an item", centre, PANEL_TOP + 12, style.mutedColor());
            int lineY = PANEL_TOP + 30;
            for (var part : font.split(Component.literal("Each charge keeps it in your inventory through one death"),
                    PANEL_RIGHT - PANEL_X - 10)) {
                graphics.centeredText(font, part, centre, lineY, style.mutedColor());
                lineY += 10;
            }
            return;
        }
        int current = DivineProtection.charges(stack);
        StringBuilder pips = new StringBuilder();
        for (int index = 0; index < DivineProtection.MAX_CHARGES; index++) pips.append(index < current ? "✦" : "✧");
        graphics.centeredText(font, pips.toString(), centre, PANEL_TOP + 5, style.accentColor());
        hover.add(new HoverArea(leftPos + centre - font.width(pips.toString()) / 2, topPos + PANEL_TOP + 4,
                font.width(pips.toString()), 10, List.of(Component.literal(current + "/" + DivineProtection.MAX_CHARGES + " charges"),
                Component.literal("A death without keep-inventory uses one charge"), Component.literal("and the item stays with you"))));
        ChargeData charge = nextCharge();
        if (charge == null) {
            graphics.centeredText(font, "Fully blessed", centre, PANEL_TOP + 30, style.goodColor());
            return;
        }
        boolean unlocked = level >= charge.required();
        int failure = charge.failureAt(level, failureDrop);
        String requirement = unlocked ? "Success " + percent(10_000 - failure) : "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(charge.required()) + " " + skill;
        int color = !unlocked ? style.badColor() : failure <= 1000 ? style.goodColor() : failure <= 3000 ? 0xFFFFD866 : style.badColor();
        graphics.centeredText(font, trim(requirement, PANEL_RIGHT - PANEL_X - 6), centre, PANEL_TOP + 18, color);
        if (unlocked) hover.add(new HoverArea(leftPos + centre - font.width(requirement) / 2, topPos + PANEL_TOP + 17,
                font.width(requirement), 10, List.of(Component.literal("Chance that the blessing takes"),
                Component.literal("A failure uses up the offering and adds no charge"),
                Component.literal("Higher " + skill + " proficiency and level make it more likely"))));
        List<CostCells.Cost> items = charge.costs().stream().filter(cost -> !cost.kind().equals("currency")).toList();
        List<CostCells.Cost> currencies = charge.costs().stream().filter(cost -> cost.kind().equals("currency")).toList();
        int rowY = PANEL_TOP + 32;
        if (items.isEmpty() && currencies.isEmpty()) graphics.centeredText(font, "Free", centre, rowY + 6, style.mutedColor());
        if (!items.isEmpty()) {
            CostCells.row(graphics, font, style, items, PANEL_X + 5, rowY, PANEL_RIGHT, leftPos, topPos, this::have,
                    (x, y, width, height, lines) -> hover.add(new HoverArea(x, y, width, height, lines)));
            rowY += 19;
        }
        if (!currencies.isEmpty())
            CostCells.row(graphics, font, style, currencies, PANEL_X + 5, rowY, PANEL_RIGHT, leftPos, topPos, this::have,
                    (x, y, width, height, lines) -> hover.add(new HoverArea(x, y, width, height, lines)));
    }

    private static String percent(int hundredths) {
        double value = hundredths / 100.0;
        return (value == Math.rint(value) ? Long.toString((long) value) : String.format(java.util.Locale.ROOT, "%.2f", value)
                .replaceAll("0+$", "").replaceAll("\\.$", "")) + "%";
    }

    private String trim(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }
}
