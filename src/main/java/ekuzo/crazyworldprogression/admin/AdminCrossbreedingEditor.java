package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.farming.CrossbreedingData;
import ekuzo.crazyworldprogression.farming.CrossbreedingService;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.GOOD;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.NORMAL;

/**
 * Crossbreeding recipes in the Moderate workspace: a list of seed pairs, a create page, and one page per pair with
 * its result, amount, farming level, failure chance for undiscovered results, and costs.
 */
final class AdminCrossbreedingEditor {
    static final String ROOT = "crossbreeding";
    private static final String NEW = ROOT + "/new";
    private static final String RECIPE = ROOT + "/recipe/";

    private AdminCrossbreedingEditor() { }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        if (pageId.equals(NEW)) return create(tabs);
        if (pageId.startsWith(RECIPE)) {
            Identifier id = Identifier.tryParse(pageId.substring(RECIPE.length()));
            CrossbreedingData.Recipe recipe = id == null ? null : CrossbreedingService.recipe(server, id);
            if (recipe != null) return recipe(recipe, tabs);
        }
        return list(server, tabs);
    }

    private static AdminPanelRegistry.AdminPage list(MinecraftServer server, List<AdminPanelRegistry.AdminTab> tabs) {
        List<CrossbreedingData.Recipe> recipes = CrossbreedingService.recipes(server);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(CwpAdminModule.in("Create", new AdminPanelRegistry.AdminEntry("Create crossbreed",
                "Two seeds in, one result out. Players crossbreed at a composter (right-click with an empty hand).",
                GOOD, List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Create", NEW)), null)));
        for (CrossbreedingData.Recipe recipe : recipes) {
            rows.add(CwpAdminModule.in("Crossbreeds (" + recipes.size() + ")", new AdminPanelRegistry.AdminEntry(
                    itemName(recipe.first()) + " + " + itemName(recipe.second()) + " → " + itemName(recipe.output()),
                    "Farming " + ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()) + " · " + percent(recipe.failureChance()) + " failure while unknown · "
                            + CrossbreedingService.describeCosts(recipe.costs()), GOOD, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", RECIPE + recipe.id()),
                            new AdminPanelRegistry.AdminAction("crossbreeding.delete|" + recipe.id(), "Delete", true)),
                    new AdminPanelRegistry.EntryPreview("item", recipe.output().toString(), 0))));
        }
        return new AdminPanelRegistry.AdminPage(ROOT, "Crossbreeding",
                recipes.size() + " crossbreed(s) · players combine seeds at a composter", tabs, rows,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage create(List<AdminPanelRegistry.AdminTab> tabs) {
        List<String> items = itemIds();
        return new AdminPanelRegistry.AdminPage(NEW, "Create crossbreed",
                "Pick the two seeds and what they make. Set costs, level and failure chance in its editor.", tabs,
                List.of(AdminPanelRegistry.AdminEntry.editor("Crossbreed", "Seeds and result",
                        "Either seed can go in either slot.",
                        List.of(field("first", "First seed (type to search)", "wheat_seeds", "", items),
                                field("second", "Second seed (type to search)", "beetroot_seeds", "", items),
                                field("output", "Result (type to search)", "melon_seeds", "", items)),
                        List.of(new AdminPanelRegistry.AdminAction("crossbreeding.create", "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", ROOT)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage recipe(CrossbreedingData.Recipe recipe, List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        String base = recipe.id().toString();
        rows.add(new AdminPanelRegistry.AdminEntry(itemName(recipe.first()) + " + " + itemName(recipe.second()),
                "→ " + recipe.count() + "× " + itemName(recipe.output()), NORMAL, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Back", ROOT)), "Crossbreed",
                new AdminPanelRegistry.EntryPreview("item", recipe.output().toString(), 0)));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Settings", "Result and rules",
                "Players below the farming level cannot crossbreed, plant or harvest the result; their harvest is "
                        + "destroyed without drops. The failure chance only applies until a player has bred it once.",
                List.of(field("output", "Result (type to search)", "", recipe.output().toString(), itemIds()),
                        field("count", "Result amount", "1", Integer.toString(recipe.count()), List.of()),
                        AdminPanelRegistry.AdminField.select("level", "Farming proficiency", ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()), ekuzo.crazyworldprogression.skills.Proficiency.names()),
                        field("failure", "Failure chance % while unknown", "25", percentValue(recipe.failureChance()), List.of())),
                List.of(new AdminPanelRegistry.AdminAction("crossbreeding.save|" + base, "Save", false))));
        String section = "Costs per crossbreed (" + recipe.costs().size() + ")";
        if (recipe.costs().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Free",
                "Add items or currencies one crossbreed uses up, on top of the two seeds.", List.of(), List.of()));
        for (int index = 0; index < recipe.costs().size(); index++) {
            ReforgeData.Cost cost = recipe.costs().get(index);
            rows.add(new AdminPanelRegistry.AdminEntry(cost.amount() + "× " + ReforgeService.label(cost),
                    cost.kind().equals("currency") ? "Currency " + cost.target() : cost.target().toString(), NORMAL,
                    List.of(), List.of(new AdminPanelRegistry.AdminAction("crossbreeding.cost_remove|" + base + "|" + index,
                            "Remove", true)), section,
                    cost.kind().equals("item") ? new AdminPanelRegistry.EntryPreview("item", cost.target().toString(), 0) : null));
        }
        rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Add item cost", "Start typing to search items",
                List.of(field("cost_item", "Item (type to search)", "bone_meal", "", itemIds()),
                        field("cost_item_amount", "Amount", "4", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("crossbreeding.cost_item|" + base, "Add item", false))));
        List<String> currencies = playerCurrencies();
        if (!currencies.isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Add currency cost",
                "Taken from the player's balance",
                List.of(field("cost_currency", "Currency", currencies.getFirst(), "", currencies),
                        field("cost_currency_amount", "Amount", "1", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("crossbreeding.cost_currency|" + base, "Add currency", false))));
        return new AdminPanelRegistry.AdminPage(RECIPE + base, itemName(recipe.output()),
                recipe.id() + " · farming " + ekuzo.crazyworldprogression.skills.Proficiency.name(recipe.level()), tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("crossbreeding.create")) {
            Identifier first = itemId(values.get("first")), second = itemId(values.get("second"));
            Identifier output = itemId(values.get("output"));
            Identifier id = AdminIds.createAvailable(output.getPath() + "_from_" + first.getPath() + "_" + second.getPath(),
                    CrossbreedingService.recipes(server).stream().map(CrossbreedingData.Recipe::id).toList());
            CrossbreedingService.create(server, id, first, second, output);
            return AdminPanelRegistry.ActionResult.success("Created crossbreed. Now set its costs.", RECIPE + id);
        }
        String[] parts = actionId.split("\\|");
        if (parts.length < 2) throw new IllegalArgumentException("Unknown crossbreeding action");
        Identifier id = AdminIds.create(parts[1]);
        CrossbreedingData.Recipe recipe = CrossbreedingService.recipe(server, id);
        if (recipe == null) throw new IllegalArgumentException("Unknown crossbreed " + id);
        String back = RECIPE + id;
        List<ReforgeData.Cost> costs = new ArrayList<>(recipe.costs());
        switch (parts[0]) {
            case "crossbreeding.delete" -> {
                CrossbreedingService.delete(server, id);
                return AdminPanelRegistry.ActionResult.success("Deleted crossbreed " + id, ROOT);
            }
            case "crossbreeding.save" -> {
                CrossbreedingService.save(server, new CrossbreedingData.Recipe(id, recipe.first(), recipe.second(),
                        itemId(values.getOrDefault("output", recipe.output().toString())),
                        number(values.getOrDefault("count", Integer.toString(recipe.count())), "Result amount"),
                        chance(values.getOrDefault("failure", percentValue(recipe.failureChance()))),
                        values.containsKey("level") ? ekuzo.crazyworldprogression.skills.Proficiency.parse(values.get("level")) : recipe.level(), costs));
                return AdminPanelRegistry.ActionResult.success("Saved crossbreed", back);
            }
            case "crossbreeding.cost_item" -> costs.add(new ReforgeData.Cost("item", itemId(values.get("cost_item")),
                    number(values.getOrDefault("cost_item_amount", "1"), "Amount")));
            case "crossbreeding.cost_currency" -> costs.add(new ReforgeData.Cost("currency",
                    AdminIds.reference(values.get("cost_currency"), playerCurrencyIds()),
                    number(values.getOrDefault("cost_currency_amount", "1"), "Amount")));
            case "crossbreeding.cost_remove" -> {
                int index = parts.length > 2 ? parseIndex(parts[2]) : -1;
                if (index < 0 || index >= costs.size()) throw new IllegalArgumentException("Cost no longer exists");
                costs.remove(index);
            }
            default -> throw new IllegalArgumentException("Unknown crossbreeding action");
        }
        CrossbreedingService.save(server, new CrossbreedingData.Recipe(id, recipe.first(), recipe.second(), recipe.output(),
                recipe.count(), recipe.failureChance(), recipe.level(), costs));
        return AdminPanelRegistry.ActionResult.success("Saved costs", back);
    }

    private static String itemName(Identifier id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName().getString())
                .orElse(id.toString());
    }

    private static List<String> itemIds() {
        return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
                .filter(value -> !value.equals("minecraft:air")).sorted().toList();
    }

    private static List<String> playerCurrencies() {
        return playerCurrencyIds().stream().map(AdminIds::display).sorted().toList();
    }

    private static List<Identifier> playerCurrencyIds() {
        return CurrencyRegistry.values().stream().filter(value -> value.scope() == CurrencyDefinition.CurrencyScope.PLAYER)
                .map(CurrencyDefinition::id).toList();
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

    /** "10" or "2.5" percent into hundredths of a percent. */
    private static int chance(String value) {
        try {
            int chance = new BigDecimal(value.trim().replace("%", "").replace(',', '.').trim()).movePointRight(2).intValueExact();
            if (chance < 0 || chance > 10_000) throw new ArithmeticException();
            return chance;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Failure chance must be a percentage from 0 to 100, like 10 or 2.5");
        }
    }

    private static String percentValue(int hundredths) {
        return BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString();
    }

    private static String percent(int hundredths) { return percentValue(hundredths) + "%"; }

    private static int parseIndex(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException exception) { return -1; }
    }

    private static AdminPanelRegistry.AdminField field(String id, String label, String placeholder, String initial,
                                                       List<String> suggestions) {
        return new AdminPanelRegistry.AdminField(id, label, placeholder, initial, 128, suggestions);
    }
}
