package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.reforge.ReforgeBranding;
import ekuzo.crazyworldprogression.reforge.ReforgeMenu;
import ekuzo.crazyworldprogression.reforge.ReforgeNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The reforge window opened by the enchanting table: put an item in, pick a tier, see what it costs and which
 * enchantments the item can get from that tier (worked out here from the tier's pool tree; nested pools are the
 * categories), then press the action button. Names come from ReforgeBranding, the look from ReforgeScreenStyle.
 */
public final class ReforgeScreen extends AbstractContainerScreen<ReforgeMenu> {
    private static final int MAIN_WIDTH = 248, HINT_X = 254, HINT_WIDTH = 150, WIDTH = HINT_X + HINT_WIDTH;
    /** The research panel sits left of the main window (negative x), RESEARCH_WIDTH wide with a gap. */
    private static final int RESEARCH_WIDTH = 132, RESEARCH_X = -RESEARCH_WIDTH - 6, RESEARCH_TOP = 20;
    private static final int HEIGHT = 206, LINE = 11, HINT_TOP = 20;
    /** The tier panel: tier choice, unlock state and costs. The item slot and the action button sit left of it. */
    private static final int PANEL_X = 108, PANEL_RIGHT = MAIN_WIDTH - 8, PANEL_TOP = 16, PANEL_BOTTOM = 92;
    private static final int CURRENCY_ICON_SOURCE_SIZE = 32;
    /** Width of one cost: a 16-pixel icon box plus room for "×999". */
    private static final int COST_CELL = 42;
    private static int lastTier;
    private String parsedState;
    private final List<TierView> tiers = new ArrayList<>();
    private int proficiency;
    /**
     * Researched enchantments in the server's order (sorted by ID). The focus is picked here in the window only and
     * sent with the reforge button; it clears itself when the slot empties or the new item cannot roll it.
     */
    private final List<ResearchView> research = new ArrayList<>();
    private String focus;
    private int focusMultiplier = 1;
    private int researchScroll;
    private JsonObject balances = new JsonObject();
    private String message = "";
    private boolean error;
    private int tier;
    private Button reforge;
    /** Possible enchantments for the item in the slot at the selected tier, rebuilt when either changes. */
    private List<HintLine> hints = List.of();
    private String hintKey = "";
    private int hintScroll;
    /** Hover areas of this frame's cost icons and hint lines, in screen coordinates. */
    private final List<HoverArea> hover = new ArrayList<>();

    private record ResearchView(String id, int points, int proficiency, int next, int multiplier) { }

    private record CostView(String kind, String target, int amount, String label) { }

    private record TierView(String name, int required, List<CostView> costs, JsonObject pool) { }

    private record HintLine(Component text, int indent, boolean category, double chance) { }

    private record HoverArea(int x, int y, int width, int height, List<Component> lines) { }

    public ReforgeScreen(ReforgeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelX = ReforgeMenu.INVENTORY_X;
        inventoryLabelY = ReforgeMenu.INVENTORY_Y - 10;
    }

    static void initialize() {
        MenuScreens.register(ReforgeMenu.TYPE, ReforgeScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(ReforgeNetworking.StatePayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof ReforgeScreen screen) screen.menu.setState(payload.json());
        });
    }

    @Override
    protected void init() {
        super.init();
        leftPos += (-RESEARCH_X) / 2;
        parseState();
        addRenderableWidget(Button.builder(Component.literal("◀"), ignored -> cycle(-1))
                .bounds(leftPos + PANEL_X + 2, topPos + PANEL_TOP + 2, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), ignored -> cycle(1))
                .bounds(leftPos + PANEL_RIGHT - 16, topPos + PANEL_TOP + 2, 14, 14).build());
        reforge = addRenderableWidget(Button.builder(Component.literal(ReforgeBranding.get().action()), ignored -> {
                    if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                            tier * ReforgeMenu.FOCUS_SCALE + focusIndex() + 1);
                }).bounds(leftPos + 8, topPos + 74, 92, 18).build());
        updateButton();
    }

    private void cycle(int direction) {
        if (tiers.isEmpty()) return;
        tier = Math.floorMod(tier + direction, tiers.size());
        lastTier = tier;
        message = "";
        updateButton();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        parseState();
        if (focus != null && (menu.item().isEmpty() || !available().contains(focus))) setFocus(null);
        updateButton();
    }

    private void setFocus(String id) {
        focus = id;
        focusMultiplier = 1;
        for (ResearchView entry : research) if (entry.id().equals(id)) focusMultiplier = entry.multiplier();
        hintKey = "";
    }

    /** Position of the focus in the research list (the server's order), or -1 without focus. */
    private int focusIndex() {
        for (int index = 0; index < research.size(); index++) if (research.get(index).id().equals(focus)) return index;
        return -1;
    }

    /**
     * Researched enchantments this tier's pool can give: those that fit the item in the slot, or with an empty slot
     * every enchantment in the pool.
     */
    private java.util.Set<String> available() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (tiers.isEmpty()) return ids;
        collect(tiers.get(tier).pool(), menu.item(), 0, ids);
        return ids;
    }

    private void collect(JsonObject pool, ItemStack stack, int depth, java.util.Set<String> ids) {
        if (depth > 16 || !pool.has("entries")) return;
        for (JsonElement element : pool.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("kind").getAsString().equals("pool")) collect(entry.getAsJsonObject("pool"), stack, depth + 1, ids);
            else if (stack.isEmpty() || suits(entry, stack, depth)) ids.add(entry.get("id").getAsString());
        }
    }

    /** Research entries the current tier and item can use, in list order. */
    private List<ResearchView> visibleResearch() {
        if (menu.item().isEmpty()) return List.of();
        java.util.Set<String> ids = available();
        return research.stream().filter(entry -> ids.contains(entry.id())).toList();
    }

    private void parseState() {
        String state = menu.state();
        if (state == null || state.equals(parsedState) || state.isBlank()) return;
        parsedState = state;
        JsonObject root = JsonParser.parseString(state).getAsJsonObject();
        proficiency = root.get("proficiency").getAsInt();
        message = root.get("message").getAsString();
        error = root.get("error").getAsBoolean();
        balances = root.getAsJsonObject("balances");
        research.clear();
        if (root.has("research")) for (JsonElement element : root.getAsJsonArray("research")) {
            JsonObject value = element.getAsJsonObject();
            research.add(new ResearchView(value.get("id").getAsString(), value.get("points").getAsInt(),
                    value.get("proficiency").getAsInt(), value.get("next").getAsInt(), value.get("multiplier").getAsInt()));
        }
        tiers.clear();
        for (JsonElement element : root.getAsJsonArray("tiers")) {
            JsonObject value = element.getAsJsonObject();
            List<CostView> costs = new ArrayList<>();
            for (JsonElement cost : value.getAsJsonArray("costs")) {
                JsonObject entry = cost.getAsJsonObject();
                costs.add(new CostView(entry.get("kind").getAsString(), entry.get("target").getAsString(),
                        entry.get("amount").getAsInt(), entry.get("label").getAsString()));
            }
            tiers.add(new TierView(value.get("name").getAsString(), value.get("required").getAsInt(), costs,
                    value.has("pool") ? value.getAsJsonObject("pool") : new JsonObject()));
        }
        tier = tiers.isEmpty() ? 0 : Math.min(Math.max(0, lastTier), tiers.size() - 1);
        hintKey = "";
    }

    private long have(CostView cost) {
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
        if (reforge == null) return;
        TierView selected = tiers.isEmpty() ? null : tiers.get(tier);
        reforge.active = selected != null && proficiency >= selected.required() && !menu.item().isEmpty()
                && !hints().isEmpty() && selected.costs().stream().allMatch(cost -> have(cost) >= cost.amount());
    }

    // ---------------------------------------------------------------- possible enchantments

    /** The hint lines for the current item and tier; recomputed only when one of them changes. */
    private List<HintLine> hints() {
        ItemStack stack = menu.item();
        String key = tier + "|" + (stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|"
                + stack.getComponentsPatch().hashCode()) + "|" + parsedState + "|" + focus;
        if (key.equals(hintKey)) return hints;
        hintKey = key;
        hintScroll = 0;
        List<HintLine> lines = new ArrayList<>();
        if (!stack.isEmpty() && !tiers.isEmpty()) addPool(tiers.get(tier).pool(), stack, 1.0, 0, lines);
        hints = List.copyOf(lines);
        return hints;
    }

    /** An entry's weight; the focused enchantment's entries count its research multiplier, as on the server. */
    private int weight(JsonObject entry) {
        int weight = entry.get("weight").getAsInt();
        return focus != null && !entry.get("kind").getAsString().equals("pool") && focus.equals(entry.get("id").getAsString())
                ? weight * focusMultiplier : weight;
    }

    /** Lists a pool's suitable enchantments, then each suitable nested pool as a category with its own lines. */
    private void addPool(JsonObject pool, ItemStack stack, double chance, int depth, List<HintLine> out) {
        if (depth > 16 || !pool.has("entries")) return;
        List<JsonObject> suitable = new ArrayList<>();
        int total = 0;
        for (JsonElement element : pool.getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            if (!suits(entry, stack, depth)) continue;
            suitable.add(entry);
            total += weight(entry);
        }
        if (total <= 0) return;
        boolean categories = suitable.stream().anyMatch(entry -> entry.get("kind").getAsString().equals("pool"));
        boolean direct = suitable.stream().anyMatch(entry -> !entry.get("kind").getAsString().equals("pool"));
        int indent = depth;
        if (depth == 0 && categories && direct) {
            double directChance = suitable.stream().filter(entry -> !entry.get("kind").getAsString().equals("pool"))
                    .mapToInt(this::weight).sum() * chance / total;
            out.add(new HintLine(Component.literal("General"), 0, true, directChance));
            indent = 1;
        }
        for (JsonObject entry : suitable) {
            if (entry.get("kind").getAsString().equals("pool")) continue;
            double share = chance * weight(entry) / total;
            Identifier id = Identifier.tryParse(entry.get("id").getAsString());
            var holder = enchantment(id);
            int level = entry.get("level").getAsInt();
            Component name = holder == null ? Component.literal(String.valueOf(id))
                    : Enchantment.getFullname(holder, level);
            out.add(new HintLine(name, indent, false, share));
        }
        for (JsonObject entry : suitable) {
            if (!entry.get("kind").getAsString().equals("pool")) continue;
            double share = chance * weight(entry) / total;
            JsonObject nested = entry.getAsJsonObject("pool");
            out.add(new HintLine(Component.literal(nested.get("name").getAsString()), depth, true, share));
            addPool(nested, stack, share, depth + 1, out);
        }
    }

    private boolean suits(JsonObject entry, ItemStack stack, int depth) {
        if (entry.get("weight").getAsInt() <= 0) return false;
        if (entry.get("kind").getAsString().equals("pool")) {
            if (depth > 16) return false;
            JsonObject nested = entry.getAsJsonObject("pool");
            for (JsonElement inner : nested.getAsJsonArray("entries"))
                if (suits(inner.getAsJsonObject(), stack, depth + 1)) return true;
            return false;
        }
        var holder = enchantment(Identifier.tryParse(entry.get("id").getAsString()));
        return holder != null && holder.value().canEnchant(stack);
    }

    private net.minecraft.core.Holder<Enchantment> enchantment(Identifier id) {
        if (id == null || minecraft.level == null) return null;
        return minecraft.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT, id)).orElse(null);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = ReforgeScreenStyle.get();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, MAIN_WIDTH, HEIGHT);
        style.panel(graphics, x + PANEL_X, y + PANEL_TOP, PANEL_RIGHT - PANEL_X, PANEL_BOTTOM - PANEL_TOP);
        style.window(graphics, x + HINT_X, y, HINT_WIDTH, HEIGHT);
        style.window(graphics, x + RESEARCH_X, y, RESEARCH_WIDTH, HEIGHT);
        for (Slot slot : menu.slots) if (slot.index != 0) style.slot(graphics, x + slot.x, y + slot.y);
        style.itemSocket(graphics, x + ReforgeMenu.ITEM_X + 8, y + ReforgeMenu.ITEM_Y + 8,
                net.minecraft.util.Util.getMillis());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        hover.clear();
        researchClicks.clear();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        for (HoverArea area : hover)
            if (mouseX >= area.x() && mouseX < area.x() + area.width() && mouseY >= area.y() && mouseY < area.y() + area.height()) {
                graphics.setComponentTooltipForNextFrame(font, area.lines(), mouseX, mouseY);
                break;
            }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ReforgeScreenStyle style = ReforgeScreenStyle.get();
        String name = ReforgeBranding.get().name();
        graphics.text(font, title, 8, 6, style.titleColor(), false);
        String level = ReforgeBranding.get().skill() + " " + ekuzo.crazyworldprogression.skills.Proficiency.name(proficiency);
        graphics.text(font, level, MAIN_WIDTH - 8 - font.width(level), 6, style.accentColor(), false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
        if (!message.isBlank())
            graphics.text(font, trim(message, MAIN_WIDTH - 16), 8, 100, error ? style.badColor() : style.goodColor(), false);
        extractHints(graphics, style);
        extractResearch(graphics, style);
        int centre = (PANEL_X + PANEL_RIGHT) / 2;
        if (tiers.isEmpty()) {
            graphics.centeredText(font, "No " + name + " tiers yet", centre, PANEL_TOP + 22, style.mutedColor());
            return;
        }
        TierView selected = tiers.get(tier);
        graphics.centeredText(font, trim(selected.name(), PANEL_RIGHT - PANEL_X - 56) + " (" + (tier + 1) + "/"
                + tiers.size() + ")", centre, PANEL_TOP + 5, style.textColor());
        boolean unlocked = proficiency >= selected.required();
        graphics.centeredText(font, unlocked ? "Unlocked" : "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(selected.required()) + " " + ReforgeBranding.get().skill(), centre,
                PANEL_TOP + 20, unlocked ? style.goodColor() : style.badColor());
        extractCosts(graphics, style, selected, centre);
    }

    /**
     * Materials in one row, currencies in the row below. Every cost is a fixed-size cell (a 16-pixel icon box,
     * then the amount), so spacing stays the same for any icon size and any number of digits.
     */
    private void extractCosts(GuiGraphicsExtractor graphics, ReforgeScreenStyle style, TierView selected, int centre) {
        List<CostView> items = selected.costs().stream().filter(cost -> !cost.kind().equals("currency")).toList();
        List<CostView> currencies = selected.costs().stream().filter(cost -> cost.kind().equals("currency")).toList();
        if (items.isEmpty() && currencies.isEmpty()) {
            graphics.centeredText(font, "Free", centre, PANEL_TOP + 44, style.mutedColor());
            return;
        }
        int rowY = PANEL_TOP + 33;
        if (!items.isEmpty()) {
            costRow(graphics, style, items, rowY);
            rowY += 20;
        }
        if (!currencies.isEmpty()) costRow(graphics, style, currencies, rowY);
    }

    private void costRow(GuiGraphicsExtractor graphics, ReforgeScreenStyle style, List<CostView> costs, int rowY) {
        int x = PANEL_X + 5;
        for (CostView cost : costs) {
            if (x + COST_CELL > PANEL_RIGHT) break;
            Identifier id = Identifier.tryParse(cost.target());
            if (cost.kind().equals("currency")) {
                CurrencyDefinition currency = id == null ? null : CurrencyRegistry.get(id);
                if (currency != null) {
                    // Fit the icon into the 16×16 box keeping its proportions, centred.
                    float scale = Math.min(16f / currency.iconWidth(), 16f / currency.iconHeight());
                    int width = Math.max(1, Math.round(currency.iconWidth() * scale));
                    int height = Math.max(1, Math.round(currency.iconHeight() * scale));
                    graphics.blit(RenderPipelines.GUI_TEXTURED, currency.icon(), x + (16 - width) / 2,
                            rowY + (16 - height) / 2, currency.iconU(), currency.iconV(), width, height,
                            currency.iconWidth(), currency.iconHeight(), CURRENCY_ICON_SOURCE_SIZE, CURRENCY_ICON_SOURCE_SIZE);
                }
            } else if (id != null) {
                var item = BuiltInRegistries.ITEM.getOptional(id);
                if (item.isPresent()) graphics.item(new ItemStack(item.get()), x, rowY);
            }
            long have = have(cost);
            graphics.text(font, "×" + cost.amount(), x + 18, rowY + 5,
                    have >= cost.amount() ? style.textColor() : style.badColor(), false);
            hover.add(new HoverArea(leftPos + x, topPos + rowY, COST_CELL - 2, 16, List.of(
                    Component.literal(cost.label()), Component.literal("Needs " + cost.amount() + ", you have " + have))));
            x += COST_CELL;
        }
    }

    /** The possible-enchantments panel: categories (nested pools) and enchantments, chances on hover. */
    private void extractHints(GuiGraphicsExtractor graphics, ReforgeScreenStyle style) {
        int x = HINT_X + 7, width = HINT_WIDTH - 14;
        graphics.text(font, "Possible enchantments", x, 6, style.accentColor(), false);
        List<HintLine> lines = hints();
        if (menu.item().isEmpty()) {
            wrapped(graphics, "Place an item in the slot to see what it can get.", x, width, style.mutedColor());
            return;
        }
        if (lines.isEmpty()) {
            wrapped(graphics, tiers.isEmpty() ? "No " + ReforgeBranding.get().name() + " tiers yet."
                    : "Nothing in this tier fits this item.", x, width, style.badColor());
            return;
        }
        int visible = (HEIGHT - HINT_TOP - 6) / LINE;
        hintScroll = Math.max(0, Math.min(hintScroll, Math.max(0, lines.size() - visible)));
        int y = HINT_TOP;
        for (int index = hintScroll; index < lines.size() && index < hintScroll + visible; index++) {
            HintLine line = lines.get(index);
            int lineX = x + line.indent() * 8;
            String chance = percent(line.chance());
            Component text = line.category() ? Component.literal(style.categoryMarker()).append(line.text()) : line.text();
            graphics.text(font, trim(text.getString(), width - (lineX - x)), lineX, y,
                    line.category() ? style.categoryColor() : style.textColor(), false);
            hover.add(new HoverArea(leftPos + lineX, topPos + y - 1, width - (lineX - x), LINE, List.of(line.text(),
                    Component.literal((line.category() ? "Category: " : "") + chance + " chance"))));
            y += LINE;
        }
        if (lines.size() > visible) {
            String more = (hintScroll + visible < lines.size() ? "↓ " : "") + (hintScroll > 0 ? "↑" : "");
            graphics.text(font, more, HINT_X + HINT_WIDTH - 7 - font.width(more), 6, style.mutedColor(), false);
        }
    }

    /** Text broken into lines that fit the panel, one below the other. */
    private void wrapped(GuiGraphicsExtractor graphics, String text, int x, int width, int color) {
        int y = HINT_TOP;
        for (var part : font.split(Component.literal(text), width)) {
            graphics.text(font, part, x, y, color, false);
            y += LINE - 1;
        }
    }

    /** The research list: icon, name and level per studied enchantment; click one to focus it. */
    private void extractResearch(GuiGraphicsExtractor graphics, ReforgeScreenStyle style) {
        int x = RESEARCH_X + 7, width = RESEARCH_WIDTH - 14;
        graphics.text(font, "Research focus", x, 6, style.accentColor(), false);
        List<ResearchView> shown = visibleResearch();
        if (shown.isEmpty()) {
            int y = RESEARCH_TOP;
            for (var part : font.split(Component.literal(research.isEmpty()
                    ? "Study enchanted books at a lectern to research their enchantments."
                    : menu.item().isEmpty() ? "Place an item to see which of your research you can focus."
                    : "None of your research can roll from this tier on this item."), width)) {
                graphics.text(font, part, x, y, style.mutedColor(), false);
                y += LINE - 1;
            }
            return;
        }
        int visible = (HEIGHT - RESEARCH_TOP - 4) / 18;
        researchScroll = Math.max(0, Math.min(researchScroll, Math.max(0, shown.size() - visible)));
        int y = RESEARCH_TOP;
        for (int index = researchScroll; index < shown.size() && index < researchScroll + visible; index++) {
            ResearchView entry = shown.get(index);
            boolean focused = entry.id().equals(focus);
            if (focused) graphics.fill(x - 3, y - 1, x + width + 3, y + 17, 0x55FFFFFF & style.accentColor());
            EnchantIcons.draw(graphics, entry.id(), x, y);
            String name = EnchantIcons.name(entry.id());
            String level = roman(entry.proficiency());
            graphics.text(font, trim(name, width - 22 - font.width(level) - 4), x + 20, y + 4,
                    focused ? style.accentColor() : style.textColor(), false);
            graphics.text(font, level, x + width - font.width(level), y + 4, style.categoryColor(), false);
            hover.add(new HoverArea(leftPos + x - 3, topPos + y - 1, width + 6, 18, List.of(Component.literal(name + " " + level),
                    Component.literal(focused ? "Focused: Roll weight multiplied by ×" + entry.multiplier() : "Click to focus"))));
            researchClicks.add(new int[] {leftPos + x - 3, topPos + y - 1, width + 6, 18, research.indexOf(entry)});
            y += 18;
        }
    }

    /** Click areas of this frame's research lines: x, y, width, height, index. */
    private final List<int[]> researchClicks = new ArrayList<>();

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        for (int[] area : researchClicks)
            if (event.x() >= area[0] && event.x() < area[0] + area[2] && event.y() >= area[1] && event.y() < area[1] + area[3]) {
                String id = research.get(area[4]).id();
                if (!menu.item().isEmpty()) setFocus(id.equals(focus) ? null : id);
                return true;
            }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        boolean inResearch = mouseX >= left + RESEARCH_X && mouseX < left && mouseY >= top && mouseY < top + HEIGHT;
        return !inResearch && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    private static String roman(int level) {
        return switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; default -> Integer.toString(level); };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= leftPos + RESEARCH_X && mouseX < leftPos && mouseY >= topPos && mouseY < topPos + HEIGHT) {
            researchScroll = Math.max(0, researchScroll + (vertical < 0 ? 1 : -1));
            return true;
        }
        if (mouseX >= leftPos + HINT_X && mouseX < leftPos + WIDTH && mouseY >= topPos && mouseY < topPos + HEIGHT) {
            hintScroll = Math.max(0, hintScroll + (vertical < 0 ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private static String percent(double chance) {
        double value = chance * 100;
        return (value >= 10 || value == Math.rint(value) ? String.format(Locale.ROOT, "%.1f", value)
                : String.format(Locale.ROOT, "%.2f", value)).replaceAll("\\.0+$", "") + "%";
    }

    private String trim(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }
}
