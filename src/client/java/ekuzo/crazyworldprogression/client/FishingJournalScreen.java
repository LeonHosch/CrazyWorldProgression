package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.loot.FishingJournal;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The loot journal: a two-page book. The left page first lists the categories in two sections: Loot (fishing, mobs,
 * dungeons, raids, events) and Collectibles (mounts, pets), each with its symbol. Picking one lists its entries like a
 * legend, with a back arrow to the categories. For loot, the right page shows the selected entry's loot in groups:
 * items the player has got show as themselves, others as the category's grey symbol, and fish the player's fishing
 * level is too low for as a black one. For collectibles it shows the mount or pet (turning with the mouse), its rarity
 * and stats, and a button to select it. Everything shown comes from the server, which never names an item, entry,
 * mount or pet the player has not got.
 */
final class FishingJournalScreen extends Screen {
    private static final String KEY = "screen.crazy-world-progression.fishing_journal.";
    private static final int WIDTH = 300, HEIGHT = 196, PAGE = 142, ROW = 13, CATEGORY_ROW = 18, SECTION_ROW = 14,
            SLOT = 22, COLUMNS = 6;
    private static final int PAPER = 0xFFEFE4C8, PAPER_SHADE = 0xFFE2D4B0, INK = 0xFF3B2A1A, INK_SOFT = 0xFF7A6448;
    private static final int COVER = 0xFF1F5C63, COVER_DARK = 0xFF123A3F, ACCENT = 0xFF2C7F89;
    private static final int HEADER = 11;
    private static final List<String> COLLECTIBLES = List.of("mounts", "pets");
    private final int level;
    private final List<Category> categories;
    /** The open category, or -1 while the categories are listed. */
    private int category = -1;
    private int selected;
    private int listScroll;
    private int left, top;
    /** Scroll offset of the loot page, in pixels. */
    private int lootScroll;

    /** The last journal the server sent; an empty message means "open this again". Cleared on disconnect. */
    private static int cachedLevel;
    private static List<Category> cachedCategories;
    /** Entities shown on the collectible page, by entity type ID. */
    private static final Map<String, LivingEntity> PREVIEWS = new HashMap<>();

    private record Category(String id, Component name, Identifier unknown, Identifier locked, List<Place> places) {
        boolean collectibles() { return COLLECTIBLES.contains(id); }
        int found() { return places.stream().mapToInt(Place::found).sum(); }
        int total() { return places.stream().mapToInt(Place::total).sum(); }
    }
    /** An entry: loot (items in groups), or a mount or pet ({@code collectible} set). */
    private record Place(Component name, List<Fish> items, List<Group> groups, Collectible collectible) {
        int found() {
            if (collectible != null) return collectible.unlocked() ? 1 : 0;
            return (int) items.stream().filter(fish -> fish.state().equals("caught")).count();
        }
        int total() { return collectible != null ? 1 : items.size(); }
    }
    private record Collectible(boolean unlocked, String id, String rarity, int color, String entity, boolean selected,
                               List<String> stats) { }
    private record Fish(String state, ItemStack item, int level, List<String> when) { }
    /** Items under one heading (for fish: those that bite under the same conditions). */
    private record Group(Component label, int restrictions, List<Fish> fish) { }

    private FishingJournalScreen(int level, List<Category> categories) {
        super(Component.translatable(KEY + "title"));
        this.level = level;
        this.categories = categories;
    }

    static void initialize() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            cachedCategories = null;
            PREVIEWS.clear();
        });
        ClientPlayNetworking.registerGlobalReceiver(FishingJournal.Payload.TYPE, (payload, context) -> {
            if (payload.json().isEmpty()) {
                if (cachedCategories != null) show(new FishingJournalScreen(cachedLevel, cachedCategories));
                return;
            }
            JsonObject root = JsonParser.parseString(payload.json()).getAsJsonObject();
            List<Category> categories = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("categories")) {
                JsonObject category = element.getAsJsonObject();
                String id = category.get("id").getAsString();
                List<Place> places = new ArrayList<>();
                for (JsonElement entry : category.getAsJsonArray("entries")) {
                    JsonObject object = entry.getAsJsonObject();
                    places.add(id.equals("fishing") ? fishingPlace(object)
                            : COLLECTIBLES.contains(id) ? collectible(object) : place(object));
                }
                // Known entries by name, then the ones still "???".
                places.sort(java.util.Comparator.comparing((Place place) -> place.found() == 0)
                        .thenComparing(place -> place.name().getString(), String.CASE_INSENSITIVE_ORDER));
                String icon = switch (id) {
                    case "fishing" -> "fish";
                    case "mobs" -> "mob";
                    case "dungeons" -> "dungeon";
                    case "raids" -> "raid";
                    case "mounts" -> "mount";
                    case "pets" -> "pet";
                    default -> "event";
                };
                categories.add(new Category(id, Component.translatable(KEY + "category." + id),
                        CrazyWorldProgression.id("textures/gui/" + icon + "_unknown.png"),
                        CrazyWorldProgression.id("textures/gui/" + icon + "_locked.png"), List.copyOf(places)));
            }
            // Loot first, then collectibles.
            categories.sort(java.util.Comparator.comparing(Category::collectibles));
            cachedLevel = root.get("level").getAsInt();
            cachedCategories = List.copyOf(categories);
            show(new FishingJournalScreen(cachedLevel, cachedCategories));
        });
    }

    /** Opens the journal; when it is already open (a refresh after selecting), it stays on the same page. */
    private static void show(FishingJournalScreen screen) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof FishingJournalScreen open && open.category >= 0
                && open.category < screen.categories.size()
                && screen.categories.get(open.category).id().equals(open.categories.get(open.category).id())) {
            screen.category = open.category;
            Place was = open.places().isEmpty() ? null : open.places().get(Math.min(open.selected, open.places().size() - 1));
            List<Place> places = screen.places();
            screen.selected = 0;
            for (int index = 0; index < places.size() && was != null; index++)
                if (places.get(index).name().getString().equals(was.name().getString())) screen.selected = index;
            screen.listScroll = Math.min(open.listScroll, Math.max(0, places.size() - 1));
        }
        client.gui.setScreen(screen);
    }

    private static Fish fish(JsonObject entry) {
        String state = entry.get("state").getAsString();
        ItemStack item = ItemStack.EMPTY;
        if (entry.has("item")) {
            Identifier itemId = Identifier.tryParse(entry.get("item").getAsString());
            if (itemId != null) item = new ItemStack(BuiltInRegistries.ITEM.getValue(itemId));
        }
        List<String> when = new ArrayList<>();
        if (entry.has("when")) for (JsonElement condition : entry.getAsJsonArray("when")) when.add(condition.getAsString());
        when.sort(null);
        return new Fish(state, item, entry.has("level") ? entry.get("level").getAsInt() : 0, when);
    }

    private static Place fishingPlace(JsonObject place) {
        List<Fish> fish = new ArrayList<>();
        for (JsonElement slot : place.getAsJsonArray("fish")) fish.add(fish(slot.getAsJsonObject()));
        Component name = place.has("hidden") && place.get("hidden").getAsBoolean() ? Component.literal("???")
                : placeName(place.get("id").getAsString(), place.get("biome").getAsBoolean());
        return new Place(name, fish, groups(fish), null);
    }

    /** Mobs, pockets and events: the server already grouped the loot. */
    private static Place place(JsonObject place) {
        List<Fish> all = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        for (JsonElement element : place.getAsJsonArray("groups")) {
            JsonObject group = element.getAsJsonObject();
            List<Fish> items = new ArrayList<>();
            for (JsonElement slot : group.getAsJsonArray("items")) items.add(fish(slot.getAsJsonObject()));
            all.addAll(items);
            String label = group.get("label").getAsString();
            groups.add(new Group(label.isEmpty() ? Component.translatable(KEY + "drops")
                    : label.equals("Rewards") ? Component.translatable(KEY + "rewards") : Component.literal(label), 0, items));
        }
        Component name = place.has("key") ? Component.translatableWithFallback(place.get("key").getAsString(),
                place.get("name").getAsString()) : Component.literal(place.get("name").getAsString());
        return new Place(name, all, groups, null);
    }

    /** A mount or pet. */
    private static Place collectible(JsonObject entry) {
        boolean unlocked = entry.get("unlocked").getAsBoolean();
        if (!unlocked) return new Place(Component.literal("???"), List.of(), List.of(),
                new Collectible(false, "", "", 0, "", false, List.of()));
        List<String> stats = new ArrayList<>();
        for (JsonElement stat : entry.getAsJsonArray("stats")) stats.add(stat.getAsString());
        int color = entry.get("color").getAsInt();
        return new Place(Component.literal(entry.get("name").getAsString()).withColor(color), List.of(), List.of(),
                new Collectible(true, entry.get("id").getAsString(), entry.get("rarity").getAsString(), color,
                        entry.get("entity").getAsString(), entry.get("selected").getAsBoolean(), stats));
    }

    /** Groups fish by their exact set of conditions; no conditions first, then fewer restrictions first. */
    private static List<Group> groups(List<Fish> fish) {
        Map<String, List<Fish>> byKey = new java.util.LinkedHashMap<>();
        for (Fish entry : fish) byKey.computeIfAbsent(String.join(";", entry.when()), ignored -> new ArrayList<>()).add(entry);
        List<Group> groups = new ArrayList<>();
        byKey.forEach((key, members) -> {
            List<Component> parts = new ArrayList<>();
            int restrictions = Integer.MAX_VALUE;
            for (String condition : key.isEmpty() ? List.of("any|any|") : List.of(key.split(";"))) {
                String[] split = (condition + "||").split("\\|", -1);
                List<String> words = new ArrayList<>();
                if (!split[1].equals("any") && !split[1].isEmpty()) words.add(split[1]);
                if (!split[0].equals("any") && !split[0].isEmpty()) words.add(split[0]);
                restrictions = Math.min(restrictions, words.size() + (split[2].isEmpty() ? 0 : 1));
                Component part = null;
                for (String word : words) {
                    Component piece = Component.translatable(KEY + "when." + word);
                    part = part == null ? piece : Component.empty().append(part).append(" · ").append(piece);
                }
                if (!split[2].isEmpty()) {
                    Identifier liquid = Identifier.tryParse(split[2]);
                    Component piece = Component.translatable(KEY + "when.in",
                            Component.literal(liquid == null ? split[2] : pretty(liquid.getPath())));
                    part = part == null ? piece : Component.empty().append(part).append(" · ").append(piece);
                }
                parts.add(part == null ? Component.translatable(KEY + "when.always") : part);
            }
            Component label = parts.getFirst();
            for (int index = 1; index < parts.size(); index++)
                label = Component.translatable(KEY + "when.or", label, parts.get(index));
            groups.add(new Group(label, restrictions, members));
        });
        groups.sort(java.util.Comparator.comparingInt(Group::restrictions));
        return groups;
    }

    /** Biomes use their game name; pockets get a readable version of their ID. */
    private static Component placeName(String id, boolean biome) {
        Identifier location = Identifier.tryParse(id);
        String readable = location == null ? id : pretty(location.getPath());
        if (location != null && biome)
            return Component.translatableWithFallback("biome." + location.getNamespace() + "." + location.getPath(), readable);
        return Component.literal(readable);
    }

    private static String pretty(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.replace('/', ' ').replace('_', ' ').split(" ")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.toString();
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int visibleRows() { return (HEIGHT - 44) / ROW; }

    private List<Place> places() { return category < 0 ? List.of() : categories.get(category).places(); }

    /** The category list's rows: a section heading ("loot"/"collectibles") or a category index, with its y offset. */
    private List<int[]> categoryRows() {
        List<int[]> rows = new ArrayList<>();
        int y = 0;
        Boolean section = null;
        for (int index = 0; index < categories.size(); index++) {
            boolean collectibles = categories.get(index).collectibles();
            if (section == null || section != collectibles) {
                rows.add(new int[] {collectibles ? -2 : -1, y});
                y += SECTION_ROW;
                section = collectibles;
            }
            rows.add(new int[] {index, y});
            y += CATEGORY_ROW;
        }
        return rows;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        // Cover, then two pages with a spine in the middle.
        graphics.fill(left - 6, top - 6, left + WIDTH + 6, top + HEIGHT + 6, COVER_DARK);
        graphics.fill(left - 4, top - 4, left + WIDTH + 4, top + HEIGHT + 4, COVER);
        int spine = left + WIDTH / 2;
        graphics.fill(left, top, spine - 2, top + HEIGHT, PAPER);
        graphics.fill(spine + 2, top, left + WIDTH, top + HEIGHT, PAPER);
        graphics.fill(spine - 2, top, spine + 2, top + HEIGHT, PAPER_SHADE);
        graphics.fill(spine - 1, top, spine, top + HEIGHT, 0x40000000);

        int pageX = left + 8, rightX = spine + 10;
        if (category < 0) {
            drawCategories(graphics, pageX, mouseX, mouseY);
            graphics.text(font, Component.translatable(KEY + "choose_category"), rightX, top + 8, INK_SOFT, false);
            return;
        }
        Category open = categories.get(category);
        boolean backHovered = mouseX >= pageX - 2 && mouseX < pageX + PAGE - 14 && mouseY >= top + 6 && mouseY < top + 18;
        graphics.text(font, Component.literal("< ").append(open.name()), pageX, top + 8, backHovered ? ACCENT : INK, false);
        graphics.fill(pageX, top + 19, pageX + PAGE - 16, top + 20, INK_SOFT);
        List<Place> places = open.places();
        int rows = visibleRows();
        for (int row = 0; row < rows && listScroll + row < places.size(); row++) {
            int index = listScroll + row;
            Place place = places.get(index);
            int y = top + 26 + row * ROW;
            boolean hovered = mouseX >= pageX - 2 && mouseX < pageX + PAGE - 14 && mouseY >= y - 2 && mouseY < y + ROW - 2;
            if (index == selected) graphics.fill(pageX - 2, y - 2, pageX + PAGE - 14, y + ROW - 2, 0x402C7F89);
            else if (hovered) graphics.fill(pageX - 2, y - 2, pageX + PAGE - 14, y + ROW - 2, 0x20000000);
            String count = place.collectible() != null ? place.collectible().selected() ? "✔" : ""
                    : place.found() + "/" + place.items().size();
            int countWidth = font.width(count);
            String name = font.plainSubstrByWidth(place.name().getString(), PAGE - 26 - countWidth);
            int color = place.collectible() != null && place.collectible().unlocked() ? 0xFF000000 | darker(place.collectible().color())
                    : index == selected ? ACCENT : INK;
            graphics.text(font, Component.literal(name), pageX, y, color, false);
            graphics.text(font, Component.literal(count), pageX + PAGE - 18 - countWidth, y, INK_SOFT, false);
        }
        if (places.size() > rows) {
            int trackTop = top + 24, trackHeight = rows * ROW;
            int thumb = Math.max(10, trackHeight * rows / places.size());
            int thumbY = trackTop + (trackHeight - thumb) * listScroll / Math.max(1, places.size() - rows);
            graphics.fill(spine - 8, trackTop, spine - 6, trackTop + trackHeight, 0x30000000);
            graphics.fill(spine - 8, thumbY, spine - 6, thumbY + thumb, INK_SOFT);
        }

        if (open.id().equals("fishing"))
            graphics.text(font, Component.translatable(KEY + "level", level), rightX, top + HEIGHT - 14, INK_SOFT, false);
        if (places.isEmpty()) return;
        Place place = places.get(selected);
        if (place.collectible() != null) {
            drawCollectible(graphics, open, place, rightX, mouseX, mouseY);
            return;
        }
        graphics.text(font, Component.literal(font.plainSubstrByWidth(place.name().getString(), PAGE - 20)), rightX,
                top + 8, INK, false);
        graphics.fill(rightX, top + 19, rightX + PAGE - 20, top + 20, INK_SOFT);
        Fish hovered = null;
        int areaTop = top + 24, areaBottom = top + HEIGHT - 18;
        int y = areaTop - lootScroll;
        for (Group group : place.groups()) {
            if (y >= areaTop && y + HEADER <= areaBottom)
                graphics.text(font, Component.literal(font.plainSubstrByWidth(group.label().getString(), PAGE - 20)),
                        rightX, y + 1, ACCENT, false);
            y += HEADER;
            for (int index = 0; index < group.fish().size(); index++) {
                int x = rightX + (index % COLUMNS) * SLOT, slotY = y + (index / COLUMNS) * SLOT;
                if (slotY < areaTop || slotY + SLOT - 2 > areaBottom) continue;
                Fish fish = group.fish().get(index);
                graphics.fill(x, slotY, x + SLOT - 2, slotY + SLOT - 2, PAPER_SHADE);
                graphics.outline(x, slotY, SLOT - 2, SLOT - 2, 0x60000000);
                if (fish.state().equals("caught") && !fish.item().isEmpty()) graphics.item(fish.item(), x + 2, slotY + 2);
                else graphics.blit(RenderPipelines.GUI_TEXTURED, fish.state().equals("locked") ? open.locked() : open.unknown(),
                        x + 2, slotY + 2, 0, 0, 16, 16, 16, 16, 16, 16);
                if (mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= slotY && mouseY < slotY + SLOT - 2) hovered = fish;
            }
            y += ((group.fish().size() + COLUMNS - 1) / COLUMNS) * SLOT + 2;
        }
        if (lootHeight(place) > areaBottom - areaTop) {
            int trackHeight = areaBottom - areaTop, content = lootHeight(place);
            int thumb = Math.max(10, trackHeight * trackHeight / content);
            int thumbY = areaTop + (trackHeight - thumb) * lootScroll / Math.max(1, content - trackHeight);
            graphics.fill(left + WIDTH - 6, areaTop, left + WIDTH - 4, areaBottom, 0x30000000);
            graphics.fill(left + WIDTH - 6, thumbY, left + WIDTH - 4, thumbY + thumb, INK_SOFT);
        }
        if (hovered != null) graphics.setComponentTooltipForNextFrame(font, tooltip(hovered, open), mouseX, mouseY);
    }

    /** Rarity colours are made for dark backgrounds; on paper they are drawn a little darker. */
    private static int darker(int rgb) {
        int r = (rgb >> 16 & 0xFF) * 7 / 10, g = (rgb >> 8 & 0xFF) * 7 / 10, b = (rgb & 0xFF) * 7 / 10;
        return r << 16 | g << 8 | b;
    }

    /** The button on a collectible page: x, y, width and height. */
    private int[] selectButton() { return new int[] {left + WIDTH / 2 + 10, top + HEIGHT - 26, PAGE - 20, 16}; }

    private void drawCollectible(GuiGraphicsExtractor graphics, Category open, Place place, int rightX, int mouseX, int mouseY) {
        Collectible collectible = place.collectible();
        boolean mount = open.id().equals("mounts");
        if (!collectible.unlocked()) {
            graphics.text(font, Component.literal("???"), rightX, top + 8, INK, false);
            graphics.fill(rightX, top + 19, rightX + PAGE - 20, top + 20, INK_SOFT);
            graphics.pose().pushMatrix();
            graphics.pose().translate(rightX + (PAGE - 20) / 2.0F - 24, top + 50);
            graphics.pose().scale(3.0F, 3.0F);
            graphics.blit(RenderPipelines.GUI_TEXTURED, open.unknown(), 0, 0, 0, 0, 16, 16, 16, 16, 16, 16);
            graphics.pose().popMatrix();
            graphics.centeredText(font, Component.translatable(KEY + "not_unlocked"), rightX + (PAGE - 20) / 2, top + 110, INK_SOFT);
            return;
        }
        graphics.text(font, Component.literal(font.plainSubstrByWidth(place.name().getString(), PAGE - 20))
                .withColor(darker(collectible.color())), rightX, top + 8, INK, false);
        graphics.fill(rightX, top + 19, rightX + PAGE - 20, top + 20, INK_SOFT);
        graphics.text(font, Component.literal(collectible.rarity()).withColor(darker(collectible.color())), rightX, top + 23, INK, false);
        // The mount or pet itself, turning towards the mouse; a little lower, as models such as antlers reach above the hitbox.
        int boxTop = top + 34, boxBottom = top + 34 + 70;
        graphics.fill(rightX, boxTop, rightX + PAGE - 20, boxBottom, PAPER_SHADE);
        LivingEntity preview = preview(collectible.entity());
        if (preview != null) {
            float size = Math.max(preview.getBbHeight(), preview.getBbWidth());
            int scale = (int) Math.max(8, Math.min(60, 40 / Math.max(0.4F, size)));
            InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, rightX, boxTop, rightX + PAGE - 20, boxBottom,
                    scale, 0.15F * preview.getBbHeight(), mouseX, mouseY, preview);
        }
        int y = boxBottom + 5;
        for (String stat : collectible.stats()) {
            String[] parts = stat.split("\\|", 2);
            graphics.text(font, Component.literal(parts[0]), rightX, y, INK_SOFT, false);
            String value = parts.length > 1 ? parts[1] : "";
            graphics.text(font, Component.literal(value), rightX + PAGE - 20 - font.width(value), y, INK, false);
            y += 10;
        }
        int[] button = selectButton();
        boolean hovered = mouseX >= button[0] && mouseX < button[0] + button[2] && mouseY >= button[1] && mouseY < button[1] + button[3];
        boolean active = !(mount && collectible.selected());
        graphics.fill(button[0], button[1], button[0] + button[2], button[1] + button[3],
                !active ? 0xFF9AA8A0 : hovered ? 0xFF3A97A2 : ACCENT);
        graphics.outline(button[0], button[1], button[2], button[3], COVER_DARK);
        String label = collectible.selected() ? mount ? "selected" : "put_away" : "select";
        graphics.centeredText(font, Component.translatable(KEY + label), button[0] + button[2] / 2, button[1] + 4, 0xFFFFFFFF);
        if (mount && collectible.selected())
            graphics.text(font, Component.translatable(KEY + "ride_hint", CollectiblesClient.keyName()), rightX,
                    button[1] - 11, INK_SOFT, false);
    }

    private static LivingEntity preview(String entity) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return null;
        return PREVIEWS.computeIfAbsent(entity, id -> {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.tryParse(id)).orElse(null);
            if (type == null || !(type.create(client.level, EntitySpawnReason.LOAD) instanceof LivingEntity living)) return null;
            // Rendering needs an entity ID; a negative one never clashes with real entities.
            living.setId(-2000 - PREVIEWS.size());
            return living;
        });
    }

    /** The category list: section headings, then each category's symbol, name and how much of it the player has. */
    private void drawCategories(GuiGraphicsExtractor graphics, int pageX, int mouseX, int mouseY) {
        graphics.text(font, Component.translatable(KEY + "categories"), pageX, top + 8, INK, false);
        graphics.fill(pageX, top + 19, pageX + PAGE - 16, top + 20, INK_SOFT);
        if (categories.isEmpty()) graphics.text(font, Component.translatable(KEY + "empty"), pageX, top + 26, INK_SOFT, false);
        for (int[] row : categoryRows()) {
            int y = top + 24 + row[1];
            if (row[0] < 0) {
                graphics.text(font, Component.translatable(KEY + (row[0] == -1 ? "section.loot" : "section.collectibles")),
                        pageX, y + 3, ACCENT, false);
                continue;
            }
            Category entry = categories.get(row[0]);
            if (mouseX >= pageX - 2 && mouseX < pageX + PAGE - 14 && mouseY >= y && mouseY < y + CATEGORY_ROW)
                graphics.fill(pageX - 2, y, pageX + PAGE - 14, y + CATEGORY_ROW, 0x20000000);
            graphics.blit(RenderPipelines.GUI_TEXTURED, entry.unknown(), pageX + 4, y + 1, 0, 0, 16, 16, 16, 16, 16, 16);
            graphics.text(font, entry.name(), pageX + 24, y + 5, INK, false);
            String count = entry.found() + "/" + entry.total();
            graphics.text(font, Component.literal(count), pageX + PAGE - 18 - font.width(count), y + 5, INK_SOFT, false);
        }
    }

    private static int lootHeight(Place place) {
        int height = 0;
        for (Group group : place.groups()) height += HEADER + ((group.fish().size() + COLUMNS - 1) / COLUMNS) * SLOT + 2;
        return height;
    }

    private List<Component> tooltip(Fish fish, Category open) {
        return switch (fish.state()) {
            case "caught" -> List.of(fish.item().getHoverName());
            case "locked" -> List.of(Component.literal("???"),
                    Component.translatable(KEY + "locked", fish.level()).withColor(0xFFB04040));
            default -> List.of(Component.literal("???"), Component.translatable(open.id().equals("fishing")
                    ? KEY + "unknown" : KEY + "not_found").withColor(0xFF909090));
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int pageX = left + 8;
            boolean onList = event.x() >= pageX - 2 && event.x() < pageX + PAGE - 14;
            if (category < 0) {
                for (int[] row : categoryRows()) {
                    int y = top + 24 + row[1];
                    if (row[0] >= 0 && onList && event.y() >= y && event.y() < y + CATEGORY_ROW) {
                        category = row[0];
                        selected = 0;
                        listScroll = 0;
                        lootScroll = 0;
                        return true;
                    }
                }
            } else {
                if (onList && event.y() >= top + 6 && event.y() < top + 18) {
                    category = -1;
                    return true;
                }
                int rows = visibleRows();
                List<Place> places = places();
                for (int row = 0; row < rows && listScroll + row < places.size() && onList; row++) {
                    int y = top + 26 + row * ROW;
                    if (event.y() >= y - 2 && event.y() < y + ROW - 2) {
                        selected = listScroll + row;
                        lootScroll = 0;
                        return true;
                    }
                }
                if (!places.isEmpty() && places.get(selected).collectible() != null && places.get(selected).collectible().unlocked()) {
                    Collectible collectible = places.get(selected).collectible();
                    boolean mount = categories.get(category).id().equals("mounts");
                    int[] button = selectButton();
                    if (event.x() >= button[0] && event.x() < button[0] + button[2] && event.y() >= button[1]
                            && event.y() < button[1] + button[3] && !(mount && collectible.selected())) {
                        CollectiblesClient.send(mount ? "select_mount" : "select_pet", collectible.selected() ? "" : collectible.id());
                        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                                .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<Place> places = places();
        if (mouseX < left + WIDTH / 2.0) {
            listScroll = Math.max(0, Math.min(places.size() - visibleRows(), listScroll - (int) Math.signum(verticalAmount)));
            return true;
        }
        if (!places.isEmpty()) {
            int visible = HEIGHT - 42;
            int max = Math.max(0, lootHeight(places.get(selected)) - visible);
            lootScroll = Math.max(0, Math.min(max, lootScroll - (int) Math.signum(verticalAmount) * SLOT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
