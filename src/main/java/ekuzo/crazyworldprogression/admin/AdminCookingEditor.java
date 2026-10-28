package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.cooking.CookingData;
import ekuzo.crazyworldprogression.cooking.CookingService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.GOOD;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.NORMAL;

/**
 * Cooking recipes in the Moderate workspace: the recipes of both stations, a create page, and one page per recipe
 * with its ingredients (up to three), dish, amount and cooking level.
 */
final class AdminCookingEditor {
    static final String ROOT = "cooking";
    private static final String NEW = ROOT + "/new";
    private static final String RECIPE = ROOT + "/recipe/";
    private static final List<String> STATIONS = List.of("stew", "smoker");

    private AdminCookingEditor() { }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        if (pageId.equals(NEW)) return create(tabs);
        if (pageId.startsWith(RECIPE)) {
            Identifier id = Identifier.tryParse(pageId.substring(RECIPE.length()));
            CookingData.Recipe recipe = id == null ? null : CookingService.recipe(server, id);
            if (recipe != null) return recipe(recipe, tabs);
        }
        return list(server, tabs);
    }

    private static AdminPanelRegistry.AdminPage list(MinecraftServer server, List<AdminPanelRegistry.AdminTab> tabs) {
        List<CookingData.Recipe> recipes = CookingService.recipes(server);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(CwpAdminModule.in("Create", new AdminPanelRegistry.AdminEntry("Create cooking recipe",
                "Stews cook in a full water cauldron over a lit campfire and use a bowl; smoker dishes use coal. "
                        + "Players open both by right-clicking the block with an empty hand.", GOOD, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Create", NEW)), null)));
        for (CookingData.Station station : CookingData.Station.values()) {
            List<CookingData.Recipe> atStation = recipes.stream().filter(recipe -> recipe.station() == station).toList();
            for (CookingData.Recipe recipe : atStation) {
                String ingredients = recipe.ingredients().isEmpty() ? "no ingredients yet"
                        : String.join(" + ", recipe.ingredients().stream().map(AdminCookingEditor::itemName).toList());
                rows.add(CwpAdminModule.in(station.label() + " (" + atStation.size() + ")", new AdminPanelRegistry.AdminEntry(
                        recipe.count() + "× " + itemName(recipe.output()),
                        ingredients + " · cooking " + ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()), GOOD, List.of(),
                        List.of(AdminPanelRegistry.AdminAction.navigate("Edit", RECIPE + recipe.id()),
                                new AdminPanelRegistry.AdminAction("cooking.delete|" + recipe.id(), "Delete", true)),
                        new AdminPanelRegistry.EntryPreview("item", recipe.output().toString(), 0))));
            }
        }
        return new AdminPanelRegistry.AdminPage(ROOT, "Cooking", recipes.size() + " recipe(s) · cauldron stews and smoker dishes",
                tabs, rows, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage create(List<AdminPanelRegistry.AdminTab> tabs) {
        return new AdminPanelRegistry.AdminPage(NEW, "Create cooking recipe",
                "Pick the station and the dish. Add ingredients and the level in its editor.", tabs,
                List.of(AdminPanelRegistry.AdminEntry.editor("Recipe", "Station and dish",
                        "stew: cauldron with water over a lit campfire, uses a bowl · smoker: uses coal",
                        List.of(field("station", "Station", "stew", "stew", STATIONS),
                                field("output", "Dish (type to search)", "mushroom_stew", "", itemIds())),
                        List.of(new AdminPanelRegistry.AdminAction("cooking.create", "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", ROOT)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage recipe(CookingData.Recipe recipe, List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        String base = recipe.id().toString();
        rows.add(new AdminPanelRegistry.AdminEntry(recipe.count() + "× " + itemName(recipe.output()),
                recipe.station().label() + (recipe.station() == CookingData.Station.STEW ? " · uses a bowl" : " · uses coal"),
                NORMAL, List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Back", ROOT)), "Recipe",
                new AdminPanelRegistry.EntryPreview("item", recipe.output().toString(), 0)));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Settings", "Dish and level",
                "Players below the cooking level cannot cook this.",
                List.of(field("station", "Station", "stew", recipe.station().key(), STATIONS),
                        field("output", "Dish (type to search)", "", recipe.output().toString(), itemIds()),
                        field("count", "Amount", "1", Integer.toString(recipe.count()), List.of()),
                        AdminPanelRegistry.AdminField.select("level", "Cooking proficiency", ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()), ekuzo.crazyworldprogression.skills.Proficiency.names())),
                List.of(new AdminPanelRegistry.AdminAction("cooking.save|" + base, "Save", false))));
        String section = "Ingredients (" + recipe.ingredients().size() + "/3)";
        if (recipe.ingredients().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "No ingredients yet",
                "Add one to three. Players can put them in any order; each slot uses one.", List.of(), List.of()));
        for (int index = 0; index < recipe.ingredients().size(); index++) {
            Identifier item = recipe.ingredients().get(index);
            rows.add(new AdminPanelRegistry.AdminEntry(itemName(item), item.toString(), NORMAL, List.of(),
                    List.of(new AdminPanelRegistry.AdminAction("cooking.ingredient_remove|" + base + "|" + index, "Remove", true)),
                    section, new AdminPanelRegistry.EntryPreview("item", item.toString(), 0)));
        }
        if (recipe.ingredients().size() < 3) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Add ingredient",
                "Start typing to search items. Add the same item twice to need two of it.",
                List.of(field("ingredient", "Item (type to search)", "red_mushroom", "", itemIds())),
                List.of(new AdminPanelRegistry.AdminAction("cooking.ingredient_add|" + base, "Add", false))));
        return new AdminPanelRegistry.AdminPage(RECIPE + base, itemName(recipe.output()),
                recipe.id() + " · " + recipe.station().label() + " · cooking " + ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()), tabs, rows,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("cooking.create")) {
            CookingData.Station station = CookingData.Station.parse(values.getOrDefault("station", "stew"));
            Identifier output = itemId(values.get("output"));
            Identifier id = AdminIds.createAvailable(station.key() + "_" + output.getPath(),
                    CookingService.recipes(server).stream().map(CookingData.Recipe::id).toList());
            CookingService.create(server, id, station, output);
            return AdminPanelRegistry.ActionResult.success("Created recipe. Now add its ingredients.", RECIPE + id);
        }
        String[] parts = actionId.split("\\|");
        if (parts.length < 2) throw new IllegalArgumentException("Unknown cooking action");
        Identifier id = AdminIds.create(parts[1]);
        CookingData.Recipe recipe = CookingService.recipe(server, id);
        if (recipe == null) throw new IllegalArgumentException("Unknown recipe " + id);
        String back = RECIPE + id;
        List<Identifier> ingredients = new ArrayList<>(recipe.ingredients());
        CookingData.Station station = recipe.station();
        Identifier output = recipe.output();
        int count = recipe.count(), level = recipe.level();
        switch (parts[0]) {
            case "cooking.delete" -> {
                CookingService.delete(server, id);
                return AdminPanelRegistry.ActionResult.success("Deleted recipe " + id, ROOT);
            }
            case "cooking.save" -> {
                station = CookingData.Station.parse(values.getOrDefault("station", station.key()));
                output = itemId(values.getOrDefault("output", output.toString()));
                count = number(values.getOrDefault("count", Integer.toString(count)), "Amount");
                if (values.containsKey("level")) level = ekuzo.crazyworldprogression.skills.Proficiency.parse(values.get("level"));
            }
            case "cooking.ingredient_add" -> {
                if (ingredients.size() >= 3) throw new IllegalArgumentException("A recipe has at most 3 ingredients");
                ingredients.add(itemId(values.get("ingredient")));
            }
            case "cooking.ingredient_remove" -> {
                int index = parts.length > 2 ? parseIndex(parts[2]) : -1;
                if (index < 0 || index >= ingredients.size()) throw new IllegalArgumentException("Ingredient no longer exists");
                ingredients.remove(index);
            }
            default -> throw new IllegalArgumentException("Unknown cooking action");
        }
        CookingService.save(server, new CookingData.Recipe(id, station, ingredients, output, count, level));
        return AdminPanelRegistry.ActionResult.success("Saved recipe", back);
    }

    private static String itemName(Identifier id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName().getString())
                .orElse(id.toString());
    }

    private static List<String> itemIds() {
        return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
                .filter(value -> !value.equals("minecraft:air")).sorted().toList();
    }

    private static Identifier itemId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose an item first");
        Identifier id = Identifier.tryParse(value.trim().toLowerCase(Locale.ROOT));
        if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty() || id.toString().equals("minecraft:air"))
            throw new IllegalArgumentException("Unknown item: " + value.trim());
        return id;
    }

    private static int number(String value, String label) {
        try {
            int number = Integer.parseInt(value.trim());
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(label + " must be a whole number of 0 or more");
        }
    }

    private static int parseIndex(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException exception) { return -1; }
    }

    private static AdminPanelRegistry.AdminField field(String id, String label, String placeholder, String initial,
                                                       List<String> suggestions) {
        return new AdminPanelRegistry.AdminField(id, label, placeholder, initial, 128, suggestions);
    }
}
