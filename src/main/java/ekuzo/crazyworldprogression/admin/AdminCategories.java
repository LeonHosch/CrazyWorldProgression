package ekuzo.crazyworldprogression.admin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Your own categories for everything editable in the admin panel (pockets, portals, mobs, loot pools…). Lists
 * group their objects under these categories; each card has a Category button that opens a small page to set
 * it. Saved with the world. Any module can use it for its own lists.
 */
public final class AdminCategories {
    /** Prefix of the category page; the full page ID is category|kind|object id|page to return to. */
    public static final String PAGE = "category|";
    private static final String SAVE = "category.save";
    private static final String CLEAR = "category.clear";

    private AdminCategories() { }

    private static final class Data extends SavedData {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("categories", Map.of())
                        .forGetter(data -> data.categories)
        ).apply(instance, Data::new));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(CrazyWorldProgression.id("admin_categories"),
                Data::new, CODEC, null);
        /** "kind|id" -> category. */
        final Map<String, String> categories = new LinkedHashMap<>();
        Data() { }
        Data(Map<String, String> categories) { this.categories.putAll(categories); }
    }

    private static Data data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Data.TYPE);
    }

    public static String get(MinecraftServer server, String kind, String id) {
        return data(server).categories.getOrDefault(kind + "|" + id, "");
    }

    public static void set(MinecraftServer server, String kind, String id, String category) {
        Data data = data(server);
        String clean = category == null ? "" : category.trim();
        if (clean.isEmpty()) data.categories.remove(kind + "|" + id);
        else data.categories.put(kind + "|" + id, clean);
        data.setDirty();
    }

    /** Categories in use for this kind, sorted. */
    public static List<String> categories(MinecraftServer server, String kind) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        data(server).categories.forEach((key, value) -> { if (key.startsWith(kind + "|")) names.add(value); });
        return List.copyOf(names);
    }

    /**
     * Files the given entries (object ID -> card) under their categories, alphabetically, with uncategorized
     * ones last, and adds a Category button to each card. Without any categories they all go under allLabel.
     */
    public static List<AdminPanelRegistry.AdminEntry> sectioned(MinecraftServer server, String kind, String allLabel,
                                                                Map<String, AdminPanelRegistry.AdminEntry> entries,
                                                                String backPage) {
        Map<String, List<AdminPanelRegistry.AdminEntry>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<AdminPanelRegistry.AdminEntry> uncategorized = new ArrayList<>();
        entries.forEach((id, entry) -> {
            List<AdminPanelRegistry.AdminAction> actions = new ArrayList<>(entry.actions());
            actions.addFirst(AdminPanelRegistry.AdminAction.navigate("Category", PAGE + kind + "|" + id + "|" + backPage));
            String category = get(server, kind, id);
            AdminPanelRegistry.AdminEntry withButton = new AdminPanelRegistry.AdminEntry(entry.title(), entry.detail(),
                    entry.tone(), entry.fields(), actions, category.isEmpty() ? "" : "📁 " + category, entry.preview());
            if (category.isEmpty()) uncategorized.add(withButton);
            else groups.computeIfAbsent(category, ignored -> new ArrayList<>()).add(withButton);
        });
        List<AdminPanelRegistry.AdminEntry> result = new ArrayList<>();
        groups.values().forEach(result::addAll);
        String rest = groups.isEmpty() ? allLabel : "Uncategorized";
        for (AdminPanelRegistry.AdminEntry entry : uncategorized)
            result.add(new AdminPanelRegistry.AdminEntry(entry.title(), entry.detail(), entry.tone(), entry.fields(),
                    entry.actions(), rest, entry.preview()));
        return result;
    }

    /** True for page IDs this class serves. */
    public static boolean owns(String pageId) { return pageId.startsWith(PAGE); }

    public static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId,
                                                    List<AdminPanelRegistry.AdminTab> tabs) {
        String[] parts = parts(pageId);
        MinecraftServer server = player.level().getServer();
        String current = get(server, parts[0], parts[1]);
        List<AdminPanelRegistry.AdminEntry> rows = List.of(
                AdminPanelRegistry.AdminEntry.editor("Category", parts[1],
                        "Type a new category or pick an existing one. Lists group their entries by category.",
                        List.of(new AdminPanelRegistry.AdminField("category", "Category (type to search)", "no category",
                                current, 64, categories(server, parts[0]))),
                        List.of(new AdminPanelRegistry.AdminAction(SAVE, "Save", false),
                                new AdminPanelRegistry.AdminAction(CLEAR, "Remove category", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", parts[2]))));
        return new AdminPanelRegistry.AdminPage(pageId, "Category", parts[1], tabs, rows,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    public static AdminPanelRegistry.ActionResult action(ServerPlayer player, String pageId, String actionId,
                                                         Map<String, String> values) {
        String[] parts = parts(pageId);
        MinecraftServer server = player.level().getServer();
        if (actionId.equals(CLEAR)) {
            set(server, parts[0], parts[1], "");
            return AdminPanelRegistry.ActionResult.success("Removed the category of " + parts[1], parts[2]);
        }
        if (actionId.equals(SAVE)) {
            String category = values.getOrDefault("category", "").trim();
            if (category.length() > 64) throw new IllegalArgumentException("Use at most 64 characters");
            set(server, parts[0], parts[1], category);
            return AdminPanelRegistry.ActionResult.success(category.isEmpty() ? "Removed the category of " + parts[1]
                    : parts[1] + " is now in " + category, parts[2]);
        }
        throw new IllegalArgumentException("Unknown category action");
    }

    /** kind, object id, page to return to. */
    private static String[] parts(String pageId) {
        String[] parts = pageId.substring(PAGE.length()).split("\\|", 3);
        if (parts.length < 3) throw new IllegalArgumentException("Invalid category page");
        return parts;
    }
}
