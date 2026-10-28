package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import ekuzo.crazyworldprogression.smithing.SmithingBranding;
import ekuzo.crazyworldprogression.smithing.SmithingData;
import ekuzo.crazyworldprogression.smithing.SmithingService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.GOOD;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.NORMAL;

/**
 * Resmith tiers in the Moderate workspace, laid out like the reforge editor: a tier list, a create page, a tier
 * page with its settings and items, and an item page with what resmithing that item costs.
 */
final class AdminSmithingEditor {
    static final String ROOT = "smithing";
    private static final String NEW = ROOT + "/new";
    private static final String TIER = ROOT + "/tier/";
    private static final String ITEM = ROOT + "/item/";

    private AdminSmithingEditor() { }

    private static String systemName() { return SmithingBranding.get().name(); }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        if (pageId.equals(NEW)) return create(tabs);
        if (pageId.startsWith(TIER)) {
            Identifier id = Identifier.tryParse(pageId.substring(TIER.length()));
            SmithingData.Tier tier = id == null ? null : SmithingService.tier(server, id);
            if (tier != null) return tier(tier, tabs);
        }
        if (pageId.startsWith(ITEM)) {
            // smithing/item/<tier id>|<item id>
            String[] parts = pageId.substring(ITEM.length()).split("\\|");
            Identifier tierId = parts.length == 2 ? Identifier.tryParse(parts[0]) : null;
            Identifier itemId = parts.length == 2 ? Identifier.tryParse(parts[1]) : null;
            SmithingData.Tier tier = tierId == null ? null : SmithingService.tier(server, tierId);
            if (tier != null && itemId != null && tier.item(itemId) != null) return item(tier, tier.item(itemId), tabs);
            if (tier != null) return tier(tier, tabs);
        }
        return list(server, tabs);
    }

    private static AdminPanelRegistry.AdminPage list(MinecraftServer server, List<AdminPanelRegistry.AdminTab> tabs) {
        List<SmithingData.Tier> tiers = SmithingService.tiers(server);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(CwpAdminModule.in("Create", new AdminPanelRegistry.AdminEntry("Create " + systemName() + " tier",
                "Players use the highest tier their " + systemName() + " Proficiency reaches. Each tier lists what "
                        + "repairing each item costs.", GOOD, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Create", NEW)), null)));
        for (int index = 0; index < tiers.size(); index++) {
            SmithingData.Tier tier = tiers.get(index);
            String icon = tier.items().isEmpty() ? "minecraft:anvil" : tier.items().getFirst().item().toString();
            rows.add(CwpAdminModule.in("Tiers (" + tiers.size() + ")", new AdminPanelRegistry.AdminEntry(
                    (index + 1) + ". " + tier.name(),
                    "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(tier.requiredLevel()) + " " + systemName() + " · " + percent(10_000 - tier.failureChance())
                            + " success · " + tier.items().size() + " item(s)", GOOD, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", TIER + tier.id()),
                            new AdminPanelRegistry.AdminAction("smithing.tier_up|" + tier.id(), "↑", false),
                            new AdminPanelRegistry.AdminAction("smithing.tier_down|" + tier.id(), "↓", false),
                            new AdminPanelRegistry.AdminAction("smithing.tier_delete|" + tier.id(), "Delete", true)),
                    new AdminPanelRegistry.EntryPreview("item", icon, 0))));
        }
        return new AdminPanelRegistry.AdminPage(ROOT, systemName() + " tiers",
                tiers.size() + " tier(s) · players repair damaged gear at an anvil", tabs, rows,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage create(List<AdminPanelRegistry.AdminTab> tabs) {
        return new AdminPanelRegistry.AdminPage(NEW, "Create " + systemName() + " tier",
                "Choose a permanent name. Add items and their costs in its editor.", tabs,
                List.of(AdminPanelRegistry.AdminEntry.editor("Tier", "Identity",
                        "The name is what players see at the anvil.",
                        List.of(field("id", "Tier name", "smith_tier_1", "", List.of()),
                                field("name", "Display name", "Apprentice", "", List.of())),
                        List.of(new AdminPanelRegistry.AdminAction("smithing.create", "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", ROOT)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage tier(SmithingData.Tier tier, List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(AdminPanelRegistry.AdminEntry.editor("Tier", "Settings", AdminIds.display(tier.id()),
                List.of(field("name", "Display name", "", tier.name(), List.of()),
                        AdminPanelRegistry.AdminField.select("required", "Needs " + systemName(),
                                ekuzo.crazyworldprogression.skills.Proficiency.name(tier.requiredLevel()), ekuzo.crazyworldprogression.skills.Proficiency.names()),
                        field("failure", "Failure chance %", "10", percentValue(tier.failureChance()), List.of())),
                List.of(new AdminPanelRegistry.AdminAction("smithing.tier_save|" + tier.id(), "Save", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", ROOT))));
        rows.add(AdminPanelRegistry.AdminEntry.editor("How it works", "Highest reached tier, with fallback",
                "Players at this tier use its failure chance. Items not listed here use the price of the nearest lower "
                        + "tier that lists them. A failure uses up the materials and leaves the item as it is.",
                List.of(), List.of()));
        String section = "Items (" + tier.items().size() + ")";
        if (tier.items().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "No items yet",
                "Add the items players can repair at this tier below.", List.of(), List.of()));
        for (SmithingData.ItemCost entry : tier.items()) {
            String costs = entry.costs().isEmpty() ? "free" : entry.costs().stream()
                    .map(cost -> cost.amount() + "× " + ReforgeService.label(cost)).reduce((a, b) -> a + ", " + b).orElse("");
            rows.add(new AdminPanelRegistry.AdminEntry(itemName(entry.item()), costs, NORMAL, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Costs", ITEM + tier.id() + "|" + entry.item()),
                            new AdminPanelRegistry.AdminAction("smithing.item_remove|" + tier.id() + "|" + entry.item(),
                                    "Remove", true)),
                    section, new AdminPanelRegistry.EntryPreview("item", entry.item().toString(), 0)));
        }
        rows.add(AdminPanelRegistry.AdminEntry.editor("Add", "Item", "Start typing to search items; then set its costs",
                List.of(field("add_item", "Item (type to search)", "iron_pickaxe", "", damageableItemIds())),
                List.of(new AdminPanelRegistry.AdminAction("smithing.item_add|" + tier.id(), "Add item", false))));
        return new AdminPanelRegistry.AdminPage(TIER + tier.id(), tier.name(),
                tier.id() + " · needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(tier.requiredLevel()) + " " + systemName() + " · "
                        + percent(10_000 - tier.failureChance()) + " success", tabs, rows,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage item(SmithingData.Tier tier, SmithingData.ItemCost entry,
                                                      List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        String base = tier.id() + "|" + entry.item();
        rows.add(new AdminPanelRegistry.AdminEntry("Item preview", entry.item().toString(), NORMAL, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Back", TIER + tier.id())), "Item",
                new AdminPanelRegistry.EntryPreview("item", entry.item().toString(), 0)));
        String section = "Costs per " + SmithingBranding.get().action().toLowerCase(java.util.Locale.ROOT)
                + " (" + entry.costs().size() + ")";
        if (entry.costs().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Free",
                "Add items or currencies one repair uses up.", List.of(), List.of()));
        for (int index = 0; index < entry.costs().size(); index++) {
            ReforgeData.Cost cost = entry.costs().get(index);
            rows.add(new AdminPanelRegistry.AdminEntry(cost.amount() + "× " + ReforgeService.label(cost),
                    cost.kind().equals("currency") ? "Currency " + cost.target() : cost.target().toString(), NORMAL,
                    List.of(), List.of(new AdminPanelRegistry.AdminAction("smithing.cost_remove|" + base + "|" + index,
                            "Remove", true)), section,
                    cost.kind().equals("item") ? new AdminPanelRegistry.EntryPreview("item", cost.target().toString(), 0) : null));
        }
        rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Add item cost", "Start typing to search items",
                List.of(field("cost_item", "Item (type to search)", "iron_ingot", "", itemIds()),
                        field("cost_item_amount", "Amount", "3", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("smithing.cost_item|" + base, "Add item", false))));
        List<String> currencies = playerCurrencies();
        if (!currencies.isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "Add currency cost",
                "Taken from the player's balance",
                List.of(field("cost_currency", "Currency", currencies.getFirst(), "", currencies),
                        field("cost_currency_amount", "Amount", "1", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("smithing.cost_currency|" + base, "Add currency", false))));
        return new AdminPanelRegistry.AdminPage(ITEM + base, itemName(entry.item()), tier.name() + " · " + entry.item(),
                tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("smithing.create")) {
            Identifier id = AdminIds.createAvailable(values.get("id"),
                    SmithingService.tiers(server).stream().map(SmithingData.Tier::id).toList());
            String name = values.getOrDefault("name", "").trim();
            SmithingService.createTier(server, id, name.isEmpty() ? id.getPath() : name);
            return AdminPanelRegistry.ActionResult.success("Created " + systemName() + " tier " + id, TIER + id);
        }
        String[] parts = actionId.split("\\|");
        if (parts.length < 2) throw new IllegalArgumentException("Unknown smithing action");
        Identifier id = parse(parts[1]);
        SmithingData.Tier tier = SmithingService.tier(server, id);
        if (tier == null) throw new IllegalArgumentException("Unknown " + systemName() + " tier " + id);
        switch (parts[0]) {
            case "smithing.tier_delete" -> {
                SmithingService.deleteTier(server, id);
                return AdminPanelRegistry.ActionResult.success("Deleted " + systemName() + " tier " + id, ROOT);
            }
            case "smithing.tier_up", "smithing.tier_down" -> {
                SmithingService.moveTier(server, id, parts[0].endsWith("up") ? -1 : 1);
                return AdminPanelRegistry.ActionResult.success("Moved tier", ROOT);
            }
            case "smithing.tier_save" -> {
                SmithingService.saveTier(server, new SmithingData.Tier(id, values.getOrDefault("name", tier.name()).trim(),
                        values.containsKey("required") ? ekuzo.crazyworldprogression.skills.Proficiency.parse(values.get("required")) : tier.requiredLevel(),
                        chance(values.getOrDefault("failure", percentValue(tier.failureChance()))), tier.items()));
                return AdminPanelRegistry.ActionResult.success("Saved " + tier.name(), TIER + id);
            }
            case "smithing.item_add" -> {
                Identifier item = itemId(values.get("add_item"));
                if (tier.item(item) != null) return AdminPanelRegistry.ActionResult.success("Already listed", ITEM + id + "|" + item);
                List<SmithingData.ItemCost> items = new ArrayList<>(tier.items());
                items.add(new SmithingData.ItemCost(item, List.of()));
                save(server, tier, items);
                return AdminPanelRegistry.ActionResult.success("Added " + item + ". Now set what it costs.", ITEM + id + "|" + item);
            }
            default -> { }
        }
        if (parts.length < 3) throw new IllegalArgumentException("Unknown smithing action");
        Identifier itemId = parse(parts[2]);
        SmithingData.ItemCost entry = tier.item(itemId);
        if (entry == null) throw new IllegalArgumentException(itemId + " is not listed in " + tier.name());
        String back = ITEM + id + "|" + itemId;
        List<ReforgeData.Cost> costs = new ArrayList<>(entry.costs());
        switch (parts[0]) {
            case "smithing.item_remove" -> {
                List<SmithingData.ItemCost> items = new ArrayList<>(tier.items());
                items.removeIf(value -> value.item().equals(itemId));
                save(server, tier, items);
                return AdminPanelRegistry.ActionResult.success("Removed " + itemId, TIER + id);
            }
            case "smithing.cost_item" -> costs.add(new ReforgeData.Cost("item", itemId(values.get("cost_item")),
                    number(values.getOrDefault("cost_item_amount", "1"), "Amount")));
            case "smithing.cost_currency" -> costs.add(new ReforgeData.Cost("currency",
                    AdminIds.reference(values.get("cost_currency"), playerCurrencyIds()),
                    number(values.getOrDefault("cost_currency_amount", "1"), "Amount")));
            case "smithing.cost_remove" -> {
                Integer index = parts.length > 3 ? parseIndex(parts[3]) : null;
                if (index == null || index >= costs.size()) throw new IllegalArgumentException("Cost no longer exists");
                costs.remove((int) index);
            }
            default -> throw new IllegalArgumentException("Unknown smithing action");
        }
        List<SmithingData.ItemCost> items = new ArrayList<>(tier.items());
        items.replaceAll(value -> value.item().equals(itemId) ? new SmithingData.ItemCost(itemId, costs) : value);
        save(server, tier, items);
        return AdminPanelRegistry.ActionResult.success("Saved costs", back);
    }

    private static void save(MinecraftServer server, SmithingData.Tier tier, List<SmithingData.ItemCost> items) {
        SmithingService.saveTier(server, new SmithingData.Tier(tier.id(), tier.name(), tier.requiredLevel(),
                tier.failureChance(), items));
    }

    private static String itemName(Identifier id) {
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> new ItemStack(item).getHoverName().getString())
                .orElse(id.toString());
    }

    private static List<String> itemIds() {
        return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
                .filter(value -> !value.equals("minecraft:air")).sorted().toList();
    }

    /** Items that take damage, which are the only ones resmithing can restore. */
    private static List<String> damageableItemIds() {
        return BuiltInRegistries.ITEM.stream().filter(item -> new ItemStack(item).isDamageableItem())
                .map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).sorted().toList();
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
        Identifier id = Identifier.tryParse(value.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty() || id.toString().equals("minecraft:air"))
            throw new IllegalArgumentException("Unknown item: " + value.trim());
        return id;
    }

    private static Identifier parse(String value) {
        return AdminIds.create(value);
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

    private static String percent(int hundredths) {
        return percentValue(hundredths) + "%";
    }

    private static Integer parseIndex(String value) {
        try {
            int index = Integer.parseInt(value);
            return index < 0 ? null : index;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static AdminPanelRegistry.AdminField field(String id, String label, String placeholder, String initial,
                                                       List<String> suggestions) {
        return new AdminPanelRegistry.AdminField(id, label, placeholder, initial, 128, suggestions);
    }
}
