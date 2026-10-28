package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.fletching.ArrowType;
import ekuzo.crazyworldprogression.fletching.FletchedArrowItem;
import ekuzo.crazyworldprogression.fletching.FletchingData;
import ekuzo.crazyworldprogression.fletching.FletchingService;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import ekuzo.crazyworldprogression.skills.Proficiency;
import ekuzo.crazyworldprogression.smithing.SmithingBranding;
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
 * Fletching recipes in the Moderate workspace: recipes grouped by the Craftsmanship rank they need, a create page,
 * and one page per recipe with its result, amount, rank and costs. The window lists recipes in this order.
 */
final class AdminFletchingEditor {
    static final String ROOT = "fletching";
    private static final String NEW = ROOT + "/new";
    private static final String RECIPE = ROOT + "/recipe/";

    private AdminFletchingEditor() { }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        if (pageId.equals(NEW)) return create(tabs);
        if (pageId.startsWith(RECIPE)) {
            Identifier id = Identifier.tryParse(pageId.substring(RECIPE.length()));
            FletchingData.Recipe recipe = id == null ? null : FletchingService.recipe(server, id);
            if (recipe != null) return recipe(recipe, tabs);
        }
        return list(server, tabs);
    }

    private static AdminPanelRegistry.AdminPage list(MinecraftServer server, List<AdminPanelRegistry.AdminTab> tabs) {
        List<FletchingData.Recipe> recipes = FletchingService.recipes(server);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(CwpAdminModule.in("Create", new AdminPanelRegistry.AdminEntry("Create fletching recipe",
                "Players craft at a fletching table. A recipe needs a " + SmithingBranding.get().name()
                        + " rank; each " + SmithingBranding.get().name() + " level adds 10% chance of a double batch.",
                GOOD, List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Create", NEW)), null)));
        for (int rank = Proficiency.NOVICE; rank <= Proficiency.MAX; rank++) {
            int current = rank;
            List<FletchingData.Recipe> atRank = recipes.stream().filter(recipe -> recipe.rank() == current).toList();
            for (FletchingData.Recipe recipe : atRank)
                rows.add(CwpAdminModule.in(Proficiency.name(rank) + " (" + atRank.size() + ")", new AdminPanelRegistry.AdminEntry(
                        recipe.count() + "× " + itemName(recipe.output()), costLine(recipe), GOOD, List.of(),
                        List.of(AdminPanelRegistry.AdminAction.navigate("Edit", RECIPE + recipe.id()),
                                new AdminPanelRegistry.AdminAction("fletching.up|" + recipe.id(), "↑", false),
                                new AdminPanelRegistry.AdminAction("fletching.down|" + recipe.id(), "↓", false),
                                new AdminPanelRegistry.AdminAction("fletching.delete|" + recipe.id(), "Delete", true)),
                        new AdminPanelRegistry.EntryPreview("item", recipe.output().toString(), 0))));
        }
        for (ArrowType type : ArrowType.values())
            if (recipes.stream().noneMatch(recipe -> isArrow(recipe.output(), type)))
                rows.add(CwpAdminModule.in("Missing arrows", new AdminPanelRegistry.AdminEntry(type.label(),
                        "Its recipe was deleted. Restore the default recipe?", NORMAL, List.of(),
                        List.of(new AdminPanelRegistry.AdminAction("fletching.restore|" + type.name(), "Restore", false)),
                        new AdminPanelRegistry.EntryPreview("item", "crazy-world-progression:" + type.path(), 0))));
        return new AdminPanelRegistry.AdminPage(ROOT, "Fletching", recipes.size() + " recipe(s) · fletching table",
                tabs, rows, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage create(List<AdminPanelRegistry.AdminTab> tabs) {
        return new AdminPanelRegistry.AdminPage(NEW, "Create fletching recipe",
                "Pick what it makes. Add the costs and rank in its editor.", tabs,
                List.of(AdminPanelRegistry.AdminEntry.editor("Recipe", "Result", "Any item, usually one of the arrows",
                        List.of(field("output", "Result (type to search)", "crazy-world-progression:broadhead_arrow", "", itemIds())),
                        List.of(new AdminPanelRegistry.AdminAction("fletching.create", "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", ROOT)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage recipe(FletchingData.Recipe recipe, List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        String base = recipe.id().toString();
        List<AdminPanelRegistry.AdminAction> top = new ArrayList<>();
        top.add(AdminPanelRegistry.AdminAction.navigate("Back", ROOT));
        ArrowType type = BuiltInRegistries.ITEM.getOptional(recipe.output())
                .map(item -> item instanceof FletchedArrowItem arrow ? arrow.type() : null).orElse(null);
        if (type != null) top.add(new AdminPanelRegistry.AdminAction("fletching.default|" + base, "Reset to default", true));
        rows.add(new AdminPanelRegistry.AdminEntry(recipe.count() + "× " + itemName(recipe.output()),
                "Needs " + Proficiency.name(recipe.rank()) + " " + SmithingBranding.get().name(), NORMAL, List.of(), top, "Recipe",
                new AdminPanelRegistry.EntryPreview("item", recipe.output().toString(), 0)));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Settings", "Result and rank",
                "Players below the rank see the recipe but cannot craft it.",
                List.of(field("output", "Result (type to search)", "", recipe.output().toString(), itemIds()),
                        field("count", "Amount per craft", "4", Integer.toString(recipe.count()), List.of()),
                        AdminPanelRegistry.AdminField.select("rank", SmithingBranding.get().name() + " rank",
                                Proficiency.name(recipe.rank()), Proficiency.names())),
                List.of(new AdminPanelRegistry.AdminAction("fletching.save|" + base, "Save", false))));
        String section = "Costs (" + recipe.costs().size() + "/6)";
        if (recipe.costs().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Free",
                "Add items or currencies that one craft uses up.", List.of(), List.of()));
        for (int index = 0; index < recipe.costs().size(); index++) {
            ReforgeData.Cost cost = recipe.costs().get(index);
            rows.add(new AdminPanelRegistry.AdminEntry(ReforgeService.label(cost), cost.kind() + " " + cost.target(), NORMAL,
                    List.of(), List.of(new AdminPanelRegistry.AdminAction("fletching.cost_remove|" + base + "|" + index,
                    "Remove", true)), section, cost.kind().equals("item")
                    ? new AdminPanelRegistry.EntryPreview("item", cost.target().toString(), 0) : null));
        }
        if (recipe.costs().size() < 6) {
            rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Add item cost", "Start typing to search items",
                    List.of(field("cost_item", "Item (type to search)", "minecraft:arrow", "", itemIds()),
                            field("cost_amount", "Amount", "1", "", List.of())),
                    List.of(new AdminPanelRegistry.AdminAction("fletching.cost_item|" + base, "Add item", false))));
            List<String> currencies = currencyIds();
            if (!currencies.isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Add currency cost",
                    "A player currency",
                    List.of(field("cost_currency", "Currency", currencies.getFirst(), "", currencies),
                            field("currency_amount", "Amount", "1", "", List.of())),
                    List.of(new AdminPanelRegistry.AdminAction("fletching.cost_currency|" + base, "Add currency", false))));
        }
        return new AdminPanelRegistry.AdminPage(RECIPE + base, itemName(recipe.output()),
                recipe.id() + " · " + Proficiency.name(recipe.rank()), tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("fletching.create")) {
            Identifier output = itemId(values.get("output"));
            Identifier id = AdminIds.createAvailable(output.getPath(),
                    FletchingService.recipes(server).stream().map(FletchingData.Recipe::id).toList());
            FletchingService.save(server, new FletchingData.Recipe(id, output, 1, Proficiency.NOVICE, List.of()));
            return AdminPanelRegistry.ActionResult.success("Created recipe. Now add its costs.", RECIPE + id);
        }
        if (actionId.startsWith("fletching.restore|")) {
            ArrowType type = ArrowType.valueOf(actionId.substring("fletching.restore|".length()));
            FletchingService.reset(server, type);
            return AdminPanelRegistry.ActionResult.success("Restored " + type.label(), ROOT);
        }
        String[] parts = actionId.split("\\|");
        if (parts.length < 2) throw new IllegalArgumentException("Unknown fletching action");
        Identifier id = AdminIds.create(parts[1]);
        FletchingData.Recipe recipe = FletchingService.recipe(server, id);
        if (recipe == null) throw new IllegalArgumentException("Unknown recipe " + id);
        String back = RECIPE + id;
        List<ReforgeData.Cost> costs = new ArrayList<>(recipe.costs());
        Identifier output = recipe.output();
        int count = recipe.count(), rank = recipe.rank();
        switch (parts[0]) {
            case "fletching.delete" -> {
                FletchingService.delete(server, id);
                return AdminPanelRegistry.ActionResult.success("Deleted recipe " + id, ROOT);
            }
            case "fletching.up", "fletching.down" -> {
                FletchingService.move(server, id, parts[0].endsWith("up") ? -1 : 1);
                return AdminPanelRegistry.ActionResult.success("Moved recipe", ROOT);
            }
            case "fletching.default" -> {
                if (!(BuiltInRegistries.ITEM.getValue(output) instanceof FletchedArrowItem arrow))
                    throw new IllegalArgumentException("Only arrow recipes have a default");
                FletchingService.delete(server, id);
                FletchingService.reset(server, arrow.type());
                return AdminPanelRegistry.ActionResult.success("Reset to the default recipe", ROOT);
            }
            case "fletching.save" -> {
                output = itemId(values.getOrDefault("output", output.toString()));
                count = number(values.getOrDefault("count", Integer.toString(count)), "Amount");
                if (values.containsKey("rank")) rank = Proficiency.parse(values.get("rank"));
            }
            case "fletching.cost_item" -> costs.add(new ReforgeData.Cost("item", itemId(values.get("cost_item")),
                    amount(values.get("cost_amount"))));
            case "fletching.cost_currency" -> costs.add(new ReforgeData.Cost("currency",
                    AdminIds.reference(values.get("cost_currency"), playerCurrencies()), amount(values.get("currency_amount"))));
            case "fletching.cost_remove" -> {
                int index = parts.length > 2 ? parseIndex(parts[2]) : -1;
                if (index < 0 || index >= costs.size()) throw new IllegalArgumentException("Cost no longer exists");
                costs.remove(index);
            }
            default -> throw new IllegalArgumentException("Unknown fletching action");
        }
        FletchingService.save(server, new FletchingData.Recipe(id, output, count, rank, costs));
        return AdminPanelRegistry.ActionResult.success("Saved recipe", back);
    }

    private static boolean isArrow(Identifier output, ArrowType type) {
        return BuiltInRegistries.ITEM.getValue(output) instanceof FletchedArrowItem arrow && arrow.type() == type;
    }

    private static String costLine(FletchingData.Recipe recipe) {
        if (recipe.costs().isEmpty()) return "free";
        return String.join(" + ", recipe.costs().stream().map(ReforgeService::label).toList());
    }

    private static String itemName(Identifier id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName().getString())
                .orElse(id.toString());
    }

    private static List<String> itemIds() {
        return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
                .filter(value -> !value.equals("minecraft:air")).sorted().toList();
    }

    private static List<Identifier> playerCurrencies() {
        return CurrencyRegistry.values().stream().filter(value -> value.scope() == CurrencyDefinition.CurrencyScope.PLAYER)
                .map(CurrencyDefinition::id).toList();
    }

    private static List<String> currencyIds() { return playerCurrencies().stream().map(AdminIds::display).sorted().toList(); }

    private static Identifier itemId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose an item first");
        Identifier id = Identifier.tryParse(value.trim().toLowerCase(Locale.ROOT));
        if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty() || id.toString().equals("minecraft:air"))
            throw new IllegalArgumentException("Unknown item: " + value.trim());
        return id;
    }

    private static int amount(String value) {
        int amount = value == null || value.isBlank() ? 1 : number(value, "Amount");
        if (amount < 1) throw new IllegalArgumentException("Amount must be at least 1");
        return amount;
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
