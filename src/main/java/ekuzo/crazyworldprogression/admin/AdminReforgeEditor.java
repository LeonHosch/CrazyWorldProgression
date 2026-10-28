package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.GOOD;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.NORMAL;

/**
 * Reforge tiers (called Enchanting unless a mod renames the system) in the Moderate workspace, laid out like the loot pool editor: a tier list, a create page,
 * and a tier editor with its costs at the top and its weighted enchantment pool below. Nested pools open their
 * own page. Pools are shared by all items; at the table, entries that do not fit the item are skipped.
 */
final class AdminReforgeEditor {
    static final String ROOT = "reforge";
    private static final String NEW = ROOT + "/new";
    private static final String TIER = ROOT + "/tier/";
    private static final String POOL = ROOT + "/pool/";
    private static final String ENTRY = ROOT + "/entry/";

    private AdminReforgeEditor() { }

    /** The system's current name (Enchanting in CWP; a content mod can rename it). */
    private static String systemName() { return ekuzo.crazyworldprogression.reforge.ReforgeBranding.get().name(); }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId, List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        if (pageId.equals(NEW)) return create(tabs);
        if (pageId.startsWith(TIER)) {
            Identifier id = Identifier.tryParse(pageId.substring(TIER.length()));
            ReforgeData.Tier tier = id == null ? null : ReforgeService.tier(server, id);
            if (tier != null) return tier(server, tier, tabs);
        }
        if (pageId.startsWith(POOL)) {
            Identifier id = Identifier.tryParse(pageId.substring(POOL.length()));
            ReforgeData.Pool pool = id == null ? null : ReforgeService.pool(server, id);
            if (pool != null) return pool(server, pool, tabs);
        }
        if (pageId.startsWith(ENTRY)) {
            String suffix = pageId.substring(ENTRY.length());
            int separator = suffix.lastIndexOf('/');
            Identifier id = separator < 0 ? null : Identifier.tryParse(suffix.substring(0, separator));
            ReforgeData.Pool pool = id == null ? null : ReforgeService.pool(server, id);
            Integer index = separator < 0 ? null : parseIndex(suffix.substring(separator + 1));
            if (pool != null && index != null && index < pool.entries().size()) return entry(pool, index, tabs);
            if (pool != null) return pool(server, pool, tabs);
        }
        return list(server, tabs);
    }

    private static AdminPanelRegistry.AdminPage list(MinecraftServer server, List<AdminPanelRegistry.AdminTab> tabs) {
        List<ReforgeData.Tier> tiers = ReforgeService.tiers(server);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(CwpAdminModule.in("Create", new AdminPanelRegistry.AdminEntry("Create " + systemName() + " tier",
                "Each tier is one choice at the enchanting table, with its own costs and enchantment pool.", GOOD,
                List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Create", NEW)), null)));
        for (int index = 0; index < tiers.size(); index++) {
            ReforgeData.Tier tier = tiers.get(index);
            ReforgeData.Pool pool = ReforgeService.pool(server, tier.pool());
            String costs = tier.costs().isEmpty() ? "free" : tier.costs().stream()
                    .map(cost -> cost.amount() + "× " + ReforgeService.label(cost)).reduce((a, b) -> a + ", " + b).orElse("");
            String icon = tier.costs().stream().filter(cost -> cost.kind().equals("item")).findFirst()
                    .map(cost -> cost.target().toString()).orElse("minecraft:enchanting_table");
            rows.add(CwpAdminModule.in("Tiers (" + tiers.size() + ")", new AdminPanelRegistry.AdminEntry(
                    (index + 1) + ". " + tier.name(),
                    "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(tier.requiredLevel()) + " " + systemName() + " · " + (pool == null ? 0 : pool.entries().size())
                            + " entries · " + costs, GOOD, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", TIER + tier.id()),
                            new AdminPanelRegistry.AdminAction("reforge.tier_up|" + tier.id(), "↑", false),
                            new AdminPanelRegistry.AdminAction("reforge.tier_down|" + tier.id(), "↓", false),
                            new AdminPanelRegistry.AdminAction("reforge.tier_delete|" + tier.id(), "Delete", true)),
                    new AdminPanelRegistry.EntryPreview("item", icon, 0))));
        }
        return new AdminPanelRegistry.AdminPage(ROOT, systemName() + " tiers",
                tiers.size() + " tier(s) · players use them at the enchanting table with " + systemName() + " Proficiency", tabs,
                rows, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage create(List<AdminPanelRegistry.AdminTab> tabs) {
        return new AdminPanelRegistry.AdminPage(NEW, "Create " + systemName() + " tier",
                "Choose a permanent name. Add costs and enchantments in its editor.", tabs,
                List.of(AdminPanelRegistry.AdminEntry.editor("Tier", "Identity",
                        "The name is what players see at the enchanting table.",
                        List.of(field("id", "Tier name", "tier_1", "", List.of()),
                                field("name", "Display name", "Apprentice", "", List.of())),
                        List.of(new AdminPanelRegistry.AdminAction("reforge.create", "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", ROOT)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage tier(MinecraftServer server, ReforgeData.Tier tier,
                                                      List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(AdminPanelRegistry.AdminEntry.editor("Tier", "Settings", AdminIds.display(tier.id()),
                List.of(field("name", "Display name", "", tier.name(), List.of()),
                        AdminPanelRegistry.AdminField.select("required", "Needs " + systemName(),
                                ekuzo.crazyworldprogression.skills.Proficiency.name(tier.requiredLevel()), ekuzo.crazyworldprogression.skills.Proficiency.names())),
                List.of(new AdminPanelRegistry.AdminAction("reforge.tier_save|" + tier.id(), "Save", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", ROOT))));
        String costSection = "Costs per reforge (" + tier.costs().size() + ")";
        if (tier.costs().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(costSection, "Free",
                "Add items or currencies a reforge at this tier uses up.", List.of(), List.of()));
        for (int index = 0; index < tier.costs().size(); index++) {
            ReforgeData.Cost cost = tier.costs().get(index);
            rows.add(new AdminPanelRegistry.AdminEntry(cost.amount() + "× " + ReforgeService.label(cost),
                    cost.kind().equals("currency") ? "Currency " + cost.target() : cost.target().toString(), NORMAL,
                    List.of(), List.of(new AdminPanelRegistry.AdminAction("reforge.cost_remove|" + tier.id() + "|" + index,
                            "Remove", true)), costSection,
                    cost.kind().equals("item") ? new AdminPanelRegistry.EntryPreview("item", cost.target().toString(), 0) : null));
        }
        rows.add(AdminPanelRegistry.AdminEntry.editor(costSection, "Add item cost", "Start typing to search items",
                List.of(field("cost_item", "Item (type to search)", "lapis_lazuli", "", itemIds()),
                        field("cost_item_amount", "Amount", "10", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("reforge.cost_item|" + tier.id(), "Add item", false))));
        List<String> currencies = playerCurrencies();
        if (!currencies.isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(costSection, "Add currency cost",
                "Taken from the player's balance",
                List.of(field("cost_currency", "Currency", currencies.getFirst(), "", currencies),
                        field("cost_currency_amount", "Amount", "1", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("reforge.cost_currency|" + tier.id(), "Add currency", false))));
        ReforgeData.Pool pool = ReforgeService.pool(server, tier.pool());
        if (pool != null) poolRows(server, pool, rows);
        return new AdminPanelRegistry.AdminPage(TIER + tier.id(), tier.name(),
                AdminIds.display(tier.id()) + " · needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(tier.requiredLevel()) + " " + systemName(),
                tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage pool(MinecraftServer server, ReforgeData.Pool pool,
                                                      List<AdminPanelRegistry.AdminTab> tabs) {
        ReforgeData.Tier tier = ReforgeService.owningTier(server, pool.id());
        if (tier != null && tier.pool().equals(pool.id())) return tier(server, tier, tabs);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(AdminPanelRegistry.AdminEntry.editor("Pool", "Name", pool.id().toString(),
                List.of(field("name", "Display name", "", pool.name(), List.of())),
                List.of(new AdminPanelRegistry.AdminAction("reforge.pool_save|" + pool.id(), "Save", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", backPage(server, pool.id())))));
        poolRows(server, pool, rows);
        return new AdminPanelRegistry.AdminPage(POOL + pool.id(), pool.name(),
                (tier == null ? "" : tier.name() + " · ") + "nested pool, picks one entry by weight", tabs, rows,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    /** The pool's entries and add rows, shared by the tier page (root pool) and nested pool pages. */
    private static void poolRows(MinecraftServer server, ReforgeData.Pool pool, List<AdminPanelRegistry.AdminEntry> rows) {
        int total = pool.entries().stream().mapToInt(ReforgeData.Entry::weight).sum();
        rows.add(AdminPanelRegistry.AdminEntry.editor("How it rolls", "Picks exactly one entry by weight",
                "Only entries that fit the item take part, so Sharpness never lands on a chestplate. A nested pool "
                        + "picks one of its own entries. Shares below are before that filtering.", List.of(), List.of()));
        String section = "Enchantments (" + pool.entries().size() + ")";
        if (pool.entries().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(section, "No entries yet",
                "Add an enchantment or a nested pool below.", List.of(), List.of()));
        Map<String, ReforgeData.Pool> pools = ReforgeService.pools(server);
        for (int index = 0; index < pool.entries().size(); index++) {
            ReforgeData.Entry entry = pool.entries().get(index);
            String share = "weight " + entry.weight() + (total <= 0 ? "" : " · " + Math.round(entry.weight() * 1000.0 / total) / 10.0 + "%");
            String base = pool.id() + "|" + index;
            List<AdminPanelRegistry.AdminAction> actions = new ArrayList<>();
            String title;
            if (entry.kind().equals("pool")) {
                ReforgeData.Pool nested = pools.get(entry.target().toString());
                title = "Nested: " + (nested == null ? entry.target().toString() : nested.name());
                share += " · picks 1 of " + (nested == null ? 0 : nested.entries().size());
                actions.add(AdminPanelRegistry.AdminAction.navigate("Open", POOL + entry.target()));
            } else title = enchantmentName(server, entry.target()) + " " + roman(entry.level());
            actions.add(AdminPanelRegistry.AdminAction.navigate("Edit", ENTRY + pool.id() + "/" + index));
            actions.add(new AdminPanelRegistry.AdminAction("reforge.up|" + base, "↑", false));
            actions.add(new AdminPanelRegistry.AdminAction("reforge.down|" + base, "↓", false));
            actions.add(new AdminPanelRegistry.AdminAction("reforge.remove|" + base, "Remove", true));
            rows.add(new AdminPanelRegistry.AdminEntry(title, share, NORMAL, List.of(), actions, section,
                    new AdminPanelRegistry.EntryPreview("item", entry.kind().equals("pool") ? "minecraft:chest"
                            : "minecraft:enchanted_book", 0)));
        }
        rows.add(AdminPanelRegistry.AdminEntry.editor("Add", "Enchantment", "Start typing to search enchantments",
                List.of(field("add_enchantment", "Enchantment (type to search)", "sharpness", "", enchantmentIds(server)),
                        field("add_level", "Level", "1", "", List.of()),
                        field("add_weight", "Weight", "10", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("reforge.add_enchantment|" + pool.id(), "Add enchantment", false))));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Add", "Nested pool", "Picks one of its own entries by weight",
                List.of(field("nested_weight", "Nested pool weight", "10", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("reforge.add_nested|" + pool.id(), "Add nested pool", false))));
    }

    private static AdminPanelRegistry.AdminPage entry(ReforgeData.Pool pool, int index, List<AdminPanelRegistry.AdminTab> tabs) {
        ReforgeData.Entry entry = pool.entries().get(index);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        if (entry.kind().equals("enchantment")) rows.add(AdminPanelRegistry.AdminEntry.editor("Entry", "Enchantment",
                "Type to search", List.of(field("target", "Enchantment", "minecraft:sharpness", entry.target().toString(),
                        List.of()), field("level", "Level", "1", Integer.toString(entry.level()), List.of())), List.of()));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Roll", "Weight", "This entry's share when the pool picks one entry.",
                List.of(field("weight", "Weight", "10", Integer.toString(entry.weight()), List.of())), List.of()));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Actions", "Save entry", "Changes are saved to this world.",
                List.of(), List.of(new AdminPanelRegistry.AdminAction("reforge.entry_save|" + pool.id() + "|" + index,
                                "Save", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", POOL + pool.id()))));
        return new AdminPanelRegistry.AdminPage(ENTRY + pool.id() + "/" + index,
                "Edit " + (entry.kind().equals("pool") ? "nested pool" : "enchantment"),
                pool.name() + " · entry " + (index + 1), tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("reforge.create")) {
            Identifier id = AdminIds.createAvailable(values.get("id"),
                    ReforgeService.tiers(server).stream().map(ReforgeData.Tier::id).toList());
            String name = values.getOrDefault("name", "").trim();
            ReforgeService.createTier(server, id, name.isEmpty() ? id.getPath() : name);
            return AdminPanelRegistry.ActionResult.success("Created " + systemName() + " tier " + id, TIER + id);
        }
        String[] parts = actionId.split("\\|");
        if (parts.length < 2) throw new IllegalArgumentException("Unknown reforge action");
        Identifier id = parse(parts[1]);
        switch (parts[0]) {
            case "reforge.tier_delete" -> {
                ReforgeService.deleteTier(server, id);
                return AdminPanelRegistry.ActionResult.success("Deleted " + systemName() + " tier " + id, ROOT);
            }
            case "reforge.tier_up", "reforge.tier_down" -> {
                ReforgeService.moveTier(server, id, parts[0].endsWith("up") ? -1 : 1);
                return AdminPanelRegistry.ActionResult.success("Moved tier", ROOT);
            }
            case "reforge.tier_save" -> {
                ReforgeData.Tier tier = requireTier(server, id);
                ReforgeService.saveTier(server, new ReforgeData.Tier(id, values.getOrDefault("name", tier.name()).trim(),
                        values.containsKey("required") ? ekuzo.crazyworldprogression.skills.Proficiency.parse(values.get("required")) : tier.requiredLevel(),
                        tier.costs(), tier.pool()));
                return AdminPanelRegistry.ActionResult.success("Saved " + tier.name(), TIER + id);
            }
            case "reforge.cost_item", "reforge.cost_currency" -> {
                ReforgeData.Tier tier = requireTier(server, id);
                boolean item = parts[0].equals("reforge.cost_item");
                Identifier target = item ? itemId(values.get("cost_item"))
                        : AdminIds.reference(values.get("cost_currency"), playerCurrencyIds());
                int amount = number(values.getOrDefault(item ? "cost_item_amount" : "cost_currency_amount", "1"), "Amount");
                List<ReforgeData.Cost> costs = new ArrayList<>(tier.costs());
                costs.add(new ReforgeData.Cost(item ? "item" : "currency", target, amount));
                ReforgeService.saveTier(server, new ReforgeData.Tier(id, tier.name(), tier.requiredLevel(), costs, tier.pool()));
                return AdminPanelRegistry.ActionResult.success("Added cost", TIER + id);
            }
            case "reforge.cost_remove" -> {
                ReforgeData.Tier tier = requireTier(server, id);
                List<ReforgeData.Cost> costs = new ArrayList<>(tier.costs());
                Integer index = parts.length > 2 ? parseIndex(parts[2]) : null;
                if (index == null || index >= costs.size()) throw new IllegalArgumentException("Cost no longer exists");
                costs.remove((int) index);
                ReforgeService.saveTier(server, new ReforgeData.Tier(id, tier.name(), tier.requiredLevel(), costs, tier.pool()));
                return AdminPanelRegistry.ActionResult.success("Removed cost", TIER + id);
            }
            default -> { }
        }
        ReforgeData.Pool pool = ReforgeService.pool(server, id);
        if (pool == null) throw new IllegalArgumentException("Unknown " + systemName() + " pool " + id);
        List<ReforgeData.Entry> entries = new ArrayList<>(pool.entries());
        String back = POOL + id;
        switch (parts[0]) {
            case "reforge.pool_save" -> {
                String name = values.getOrDefault("name", pool.name()).trim();
                ReforgeService.savePool(server, new ReforgeData.Pool(id, name, pool.entries()));
                return AdminPanelRegistry.ActionResult.success("Saved " + name, back);
            }
            case "reforge.add_enchantment" -> {
                Identifier enchantment = enchantmentId(server, values.get("add_enchantment"));
                entries.add(new ReforgeData.Entry("enchantment", enchantment,
                        number(blankAs(values.get("add_level"), "1"), "Level"),
                        number(blankAs(values.get("add_weight"), "10"), "Weight")));
                ReforgeService.savePool(server, new ReforgeData.Pool(id, pool.name(), entries));
                return AdminPanelRegistry.ActionResult.success("Added " + enchantment, back);
            }
            case "reforge.add_nested" -> {
                Identifier nested = ReforgeService.createNested(server, id);
                entries.add(new ReforgeData.Entry("pool", nested, 1, number(blankAs(values.get("nested_weight"), "10"), "Weight")));
                ReforgeService.savePool(server, new ReforgeData.Pool(id, pool.name(), entries));
                return AdminPanelRegistry.ActionResult.success("Added a nested pool. Add the enchantments it picks from.",
                        POOL + nested);
            }
            default -> { }
        }
        Integer parsed = parts.length > 2 ? parseIndex(parts[2]) : null;
        if (parsed == null || parsed >= entries.size()) throw new IllegalArgumentException("Entry no longer exists");
        int index = parsed;
        ReforgeData.Entry old = entries.get(index);
        switch (parts[0]) {
            case "reforge.remove" -> {
                entries.remove(index);
                ReforgeService.savePool(server, new ReforgeData.Pool(id, pool.name(), entries));
                if (old.kind().equals("pool") && ReforgeService.parents(server, old.target()).isEmpty())
                    ReforgeService.deletePoolTree(server, old.target(), 0);
                return AdminPanelRegistry.ActionResult.success("Removed entry", back);
            }
            case "reforge.up" -> { if (index > 0) Collections.swap(entries, index, index - 1); }
            case "reforge.down" -> { if (index < entries.size() - 1) Collections.swap(entries, index, index + 1); }
            case "reforge.entry_save" -> {
                Identifier target = old.kind().equals("enchantment")
                        ? enchantmentId(server, values.getOrDefault("target", old.target().toString())) : old.target();
                int level = values.containsKey("level") ? number(values.get("level"), "Level") : old.level();
                int weight = values.containsKey("weight") ? number(values.get("weight"), "Weight") : old.weight();
                entries.set(index, new ReforgeData.Entry(old.kind(), target, level, weight));
                ReforgeService.savePool(server, new ReforgeData.Pool(id, pool.name(), entries));
                return AdminPanelRegistry.ActionResult.success("Saved entry", back);
            }
            default -> throw new IllegalArgumentException("Unknown reforge action");
        }
        ReforgeService.savePool(server, new ReforgeData.Pool(id, pool.name(), entries));
        return AdminPanelRegistry.ActionResult.success("Moved entry", back);
    }

    private static String backPage(MinecraftServer server, Identifier poolId) {
        return ReforgeService.parents(server, poolId).stream().findFirst().map(parent -> POOL + parent.id()).orElse(ROOT);
    }

    private static ReforgeData.Tier requireTier(MinecraftServer server, Identifier id) {
        ReforgeData.Tier tier = ReforgeService.tier(server, id);
        if (tier == null) throw new IllegalArgumentException("Unknown " + systemName() + " tier " + id);
        return tier;
    }

    private static List<String> enchantmentIds(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElementIds()
                .map(key -> key.identifier().toString()).sorted().toList();
    }

    private static String enchantmentName(MinecraftServer server, Identifier id) {
        return server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT, id))
                .map(holder -> holder.value().description().getString()).orElse(id.toString());
    }

    /** Accepts "sharpness" as well as "minecraft:sharpness". */
    private static Identifier enchantmentId(MinecraftServer server, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose an enchantment first");
        Identifier id = Identifier.tryParse(value.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null || server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT, id)).isEmpty())
            throw new IllegalArgumentException("Unknown enchantment: " + value.trim());
        return id;
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; case 10 -> "X";
            default -> Integer.toString(level);
        };
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
        Identifier id = Identifier.tryParse(value.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty() || id.toString().equals("minecraft:air"))
            throw new IllegalArgumentException("Unknown item: " + value.trim());
        return id;
    }

    private static Identifier parse(String value) {
        return AdminIds.create(value);
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
