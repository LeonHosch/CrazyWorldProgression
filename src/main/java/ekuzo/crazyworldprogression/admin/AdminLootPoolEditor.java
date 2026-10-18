package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.loot.LootPoolData;
import ekuzo.crazyworldprogression.loot.LootPoolService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.GOOD;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.NORMAL;

/**
 * Loot pool editor following the Pockets/Portals pattern: a card list, a separate create page, then an editor.
 * Top-level pools roll every entry on its own chance; nested pools pick exactly one entry by share.
 */
final class AdminLootPoolEditor {
    static final String ROOT = "loot_pools";
    private static final String NEW = ROOT + "/new";
    private static final String EDIT = ROOT + "/edit/";
    private static final String ENTRY = ROOT + "/entry/";

    private AdminLootPoolEditor() { }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId,
                                              List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        if (pageId.equals(NEW)) return create(tabs);
        if (pageId.startsWith(EDIT)) {
            Identifier id = Identifier.tryParse(pageId.substring(EDIT.length()));
            if (id != null && LootPoolService.get(server, id) != null) return edit(server, id, tabs);
        }
        if (pageId.startsWith(ENTRY)) {
            String suffix = pageId.substring(ENTRY.length());
            int separator = suffix.lastIndexOf('/');
            Identifier id = separator < 0 ? null : Identifier.tryParse(suffix.substring(0, separator));
            LootPoolData.Pool pool = id == null ? null : LootPoolService.get(server, id);
            Integer index = separator < 0 ? null : parseIndex(suffix.substring(separator + 1));
            if (pool != null && index != null && index < pool.entries().size()) return entry(server, pool, index, tabs);
            if (pool != null) return edit(server, id, tabs);
        }
        return list(server, tabs);
    }

    private static AdminPanelRegistry.AdminPage list(MinecraftServer server, List<AdminPanelRegistry.AdminTab> tabs) {
        Map<String, LootPoolData.Pool> pools = LootPoolService.all(server);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(CwpAdminModule.in("Create", new AdminPanelRegistry.AdminEntry("Create loot pool",
                "Create a pool first, then add items and nested pools in its editor.", GOOD, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Create", NEW)), null)));
        List<LootPoolData.Pool> topLevel = pools.values().stream()
                .filter(pool -> !LootPoolService.nested(pools, pool.id()))
                .sorted(Comparator.comparing(pool -> pool.id().toString())).toList();
        Map<String, AdminPanelRegistry.AdminEntry> all = new java.util.LinkedHashMap<>();
        for (LootPoolData.Pool pool : topLevel) {
            var firstItem = pool.entries().stream().filter(entry -> entry.kind().equals("item")).findFirst();
            all.put(pool.id().toString(), (new AdminPanelRegistry.AdminEntry(pool.name(),
                    pool.id() + " · " + countLabel(pool.entries().size()), GOOD, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", EDIT + pool.id()),
                            new AdminPanelRegistry.AdminAction("loot.delete|" + pool.id(), "Delete", true)),
                    new AdminPanelRegistry.EntryPreview("item",
                            firstItem.map(entry -> entry.target().toString()).orElse("minecraft:chest"), 0))));
        }
        rows.addAll(AdminCategories.sectioned(server, "loot_pool", "All loot pools", all, ROOT));
        return new AdminPanelRegistry.AdminPage(ROOT, "Loot pools",
                topLevel.size() + " loot pool(s) · use loot(lootpool_id=\"…\") in a pocket script", tabs, rows,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage create(List<AdminPanelRegistry.AdminTab> tabs) {
        return new AdminPanelRegistry.AdminPage(NEW, "Create loot pool",
                "Choose a permanent ID. You can add items and nested pools after creating it.", tabs,
                List.of(AdminPanelRegistry.AdminEntry.editor("Loot pool", "Identity",
                        "The ID is what scripts use; the name is only shown here.",
                        List.of(field("id", "Pool ID", "cwp:raid_rewards", "", List.of()),
                                field("name", "Display name", "Raid rewards", "", List.of())),
                        List.of(new AdminPanelRegistry.AdminAction("loot.create", "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", ROOT)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage edit(MinecraftServer server, Identifier id,
                                                      List<AdminPanelRegistry.AdminTab> tabs) {
        Map<String, LootPoolData.Pool> pools = LootPoolService.all(server);
        LootPoolData.Pool pool = pools.get(id.toString());
        boolean pickOne = pool.mode().equals("one");
        int total = pool.entries().stream().mapToInt(LootPoolData.Entry::chance).sum();
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(AdminPanelRegistry.AdminEntry.editor("Pool", "Name", pool.id().toString(),
                List.of(field("name", "Display name", "", pool.name(), List.of())),
                List.of(new AdminPanelRegistry.AdminAction("loot.save|" + id, "Save", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", backPage(pools, id)))));
        if (pickOne) rows.add(AdminPanelRegistry.AdminEntry.editor("How it rolls", "Picks exactly one entry",
                "Each chance is that entry's share of the pick. Total: " + percent(total)
                        + (pool.entries().isEmpty() || total == 10000 ? "" : " (odds are scaled to 100%)"),
                List.of(), List.of()));
        else rows.add(AdminPanelRegistry.AdminEntry.editor("How it rolls", "Every entry rolls on its own",
                "A 25% entry drops in about 1 of 4 rolls. A nested pool rolls its chance, then picks one of its entries.",
                List.of(), List.of()));
        String entriesSection = "Entries (" + pool.entries().size() + ")";
        if (pool.entries().isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor(entriesSection, "No entries yet",
                "Add an item or a nested pool below.", List.of(), List.of()));
        for (int index = 0; index < pool.entries().size(); index++)
            rows.add(entryRow(pools, pool, index, total, entriesSection));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Add", "Item", "Start typing to search items",
                List.of(field("add_item", "Item (type to search)", "diamond_sword", "", itemIds()),
                        field("add_chance", pickOne ? "Share %" : "Chance %", "100", "", List.of()),
                        field("add_amount", "Amount", "1 or 1-3", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("loot.add_item|" + id, "Add item", false))));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Add", "Nested pool",
                "Rolls its chance, then picks one of its own entries",
                List.of(field("nested_chance", pickOne ? "Nested pool share %" : "Nested pool chance %", "25", "",
                        List.of())),
                List.of(new AdminPanelRegistry.AdminAction("loot.add_nested|" + id, "Add nested pool", false))));
        List<String> currencies = playerCurrencies();
        if (!currencies.isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor("Add", "Currency",
                "Credits a player currency",
                List.of(field("add_currency", "Currency", currencies.getFirst(), "", currencies),
                        field("currency_chance", pickOne ? "Share %" : "Chance %", "100", "", List.of()),
                        field("currency_amount", "Amount", "1 or 5-10", "", List.of())),
                List.of(new AdminPanelRegistry.AdminAction("loot.add_currency|" + id, "Add currency", false))));
        String subtitle = pool.id() + " · " + (pickOne ? "nested pool, picks one entry" : "every entry rolls on its own");
        return new AdminPanelRegistry.AdminPage(EDIT + id, pool.name(), subtitle, tabs, rows,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminEntry entryRow(Map<String, LootPoolData.Pool> pools, LootPoolData.Pool pool,
                                                          int index, int total, String section) {
        LootPoolData.Entry entry = pool.entries().get(index);
        boolean pickOne = pool.mode().equals("one");
        String odds = pickOne ? percent(entry.chance()) + " share" + (total == 10000 || total == 0 ? ""
                : " (" + percent(scaledShare(entry.chance(), total)) + " real)") : percent(entry.chance()) + " chance";
        String title;
        String detail;
        List<AdminPanelRegistry.AdminAction> actions = new ArrayList<>();
        String base = pool.id() + "|" + index;
        switch (entry.kind()) {
            case "pool" -> {
                LootPoolData.Pool nested = pools.get(entry.target().toString());
                title = "Nested: " + (nested == null ? entry.target().toString() : nested.name());
                detail = odds + " · picks 1 of " + (nested == null ? 0 : nested.entries().size());
                actions.add(AdminPanelRegistry.AdminAction.navigate("Open", EDIT + entry.target()));
            }
            case "currency" -> {
                title = "Currency " + entry.target();
                detail = odds + " · " + amountLabel(entry);
            }
            default -> {
                title = entry.target().toString();
                detail = odds + " · " + amountLabel(entry);
            }
        }
        if (entry.kind().equals("item"))
            actions.add(new AdminPanelRegistry.AdminAction("loot.chat|" + base,
                    entry.showInChat() ? "Chat: on" : "Chat: off", false));
        actions.add(AdminPanelRegistry.AdminAction.navigate("Edit", ENTRY + pool.id() + "/" + index));
        actions.add(new AdminPanelRegistry.AdminAction("loot.up|" + base, "↑", false));
        actions.add(new AdminPanelRegistry.AdminAction("loot.down|" + base, "↓", false));
        actions.add(new AdminPanelRegistry.AdminAction("loot.remove|" + base, "Remove", true));
        return new AdminPanelRegistry.AdminEntry(title, detail, NORMAL, List.of(), actions, section,
                entry.kind().equals("item") ? new AdminPanelRegistry.EntryPreview("item", entry.target().toString(), 0)
                        : entry.kind().equals("pool") ? new AdminPanelRegistry.EntryPreview("item", "minecraft:chest", 0)
                        : null);
    }

    private static AdminPanelRegistry.AdminPage entry(MinecraftServer server, LootPoolData.Pool pool, int index,
                                                       List<AdminPanelRegistry.AdminTab> tabs) {
        LootPoolData.Entry reward = pool.entries().get(index);
        boolean pickOne = pool.mode().equals("one");
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        if (reward.kind().equals("item")) rows.add(new AdminPanelRegistry.AdminEntry("Item preview",
                reward.target().toString(), NORMAL, List.of(), List.of(), "Entry",
                new AdminPanelRegistry.EntryPreview("item", reward.target().toString(), 0)));
        if (reward.kind().equals("item")) rows.add(AdminPanelRegistry.AdminEntry.editor("Entry", "Item",
                "Type to search", List.of(field("target", "Item (type to search)", "minecraft:diamond",
                        reward.target().toString(), itemIds())), List.of()));
        if (reward.kind().equals("currency")) rows.add(AdminPanelRegistry.AdminEntry.editor("Entry", "Currency",
                "Player currency", List.of(field("target", "Currency", "namespace:id",
                        reward.target().toString(), playerCurrencies())), List.of()));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Roll", pickOne ? "Share" : "Chance",
                pickOne ? "This entry's share when the nested pool picks one entry."
                        : "100% always drops. Each entry rolls on its own.",
                List.of(field("chance", pickOne ? "Share %" : "Chance %", "25", percentValue(reward.chance()),
                        List.of())), List.of()));
        if (!reward.kind().equals("pool")) rows.add(AdminPanelRegistry.AdminEntry.editor("Amount", "Amount",
                "A whole number between Min and Max, both included.",
                List.of(field("min", "Min", "1", Integer.toString(reward.min()), List.of()),
                        field("max", "Max", "1", Integer.toString(reward.max()), List.of())), List.of()));
        if (reward.kind().equals("item")) rows.add(AdminPanelRegistry.AdminEntry.editor("Announcement",
                "Show in chat", "Off by default. When on, chat shows \"<player> just got <item>\" to everyone.",
                List.of(AdminPanelRegistry.AdminField.select("show_in_chat", "Show in chat",
                        reward.showInChat() ? "Yes" : "No", List.of("No", "Yes"))), List.of()));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Actions", "Save entry", "Changes are saved to this world.",
                List.of(), List.of(new AdminPanelRegistry.AdminAction("loot.entry.save|" + pool.id() + "|" + index,
                                "Save", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", EDIT + pool.id()))));
        String name = reward.kind().equals("pool") ? "nested pool" : reward.kind();
        return new AdminPanelRegistry.AdminPage(ENTRY + pool.id() + "/" + index, "Edit " + name,
                pool.name() + " · entry " + (index + 1), tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("loot.create")) {
            Identifier id = parse(values.get("id"));
            if (LootPoolService.get(server, id) != null) throw new IllegalArgumentException("Loot pool already exists: " + id);
            String name = values.getOrDefault("name", "").trim();
            LootPoolService.save(server, new LootPoolData.Pool(id, name.isEmpty() ? id.getPath() : name, "all", List.of()));
            return AdminPanelRegistry.ActionResult.success("Created loot pool " + id, EDIT + id);
        }
        String[] parts = actionId.split("\\|");
        if (parts.length < 2) throw new IllegalArgumentException("Unknown loot action");
        Identifier id = parse(parts[1]);
        LootPoolData.Pool pool = require(server, id);
        List<LootPoolData.Entry> entries = new ArrayList<>(pool.entries());
        switch (parts[0]) {
            case "loot.delete" -> {
                LootPoolService.delete(server, id);
                return AdminPanelRegistry.ActionResult.success("Deleted loot pool " + id, ROOT);
            }
            case "loot.save" -> {
                String name = values.getOrDefault("name", pool.name()).trim();
                LootPoolService.save(server, new LootPoolData.Pool(id, name, pool.mode(), pool.entries()));
                return AdminPanelRegistry.ActionResult.success("Saved " + name, EDIT + id);
            }
            case "loot.add_item" -> {
                Identifier item = itemId(values.get("add_item"));
                int[] amount = amount(values.get("add_amount"));
                entries.add(new LootPoolData.Entry("item", item, chance(values.get("add_chance")), 1,
                        amount[0], amount[1]));
                saveEntries(server, pool, entries);
                return AdminPanelRegistry.ActionResult.success("Added " + item, EDIT + id);
            }
            case "loot.add_currency" -> {
                Identifier currency = parse(values.get("add_currency"));
                int[] amount = amount(values.get("currency_amount"));
                entries.add(new LootPoolData.Entry("currency", currency, chance(values.get("currency_chance")), 1,
                        amount[0], amount[1]));
                saveEntries(server, pool, entries);
                return AdminPanelRegistry.ActionResult.success("Added currency " + currency, EDIT + id);
            }
            case "loot.add_nested" -> {
                requireRoom(entries);
                Map<String, LootPoolData.Pool> pools = LootPoolService.all(server);
                if (depth(pools, id) >= LootPoolService.MAX_DEPTH)
                    throw new IllegalArgumentException("Pools can nest at most " + LootPoolService.MAX_DEPTH + " levels");
                Identifier nestedId = LootPoolService.nestedId(pools, id);
                int chance = chance(values.get("nested_chance"));
                LootPoolService.save(server, new LootPoolData.Pool(nestedId,
                        "Nested pool " + nestedId.getPath().substring(nestedId.getPath().lastIndexOf('_') + 1), "one",
                        List.of()));
                entries.add(new LootPoolData.Entry("pool", nestedId, chance, 1, 1, 1));
                try {
                    saveEntries(server, pool, entries);
                } catch (IllegalArgumentException exception) {
                    LootPoolService.deleteIfUnused(server, nestedId);
                    throw exception;
                }
                return AdminPanelRegistry.ActionResult.success("Added a nested pool. Add the entries it picks from.",
                        EDIT + nestedId);
            }
            default -> { }
        }
        if (parts.length != 3) throw new IllegalArgumentException("Invalid entry action");
        Integer parsedIndex = parseIndex(parts[2]);
        if (parsedIndex == null || parsedIndex >= entries.size()) throw new IllegalArgumentException("Entry no longer exists");
        int index = parsedIndex;
        LootPoolData.Entry old = entries.get(index);
        switch (parts[0]) {
            case "loot.remove" -> {
                entries.remove(index);
                saveEntries(server, pool, entries);
                if (old.kind().equals("pool")) LootPoolService.deleteIfUnused(server, old.target());
                return AdminPanelRegistry.ActionResult.success("Removed entry", EDIT + id);
            }
            case "loot.chat" -> {
                if (!old.kind().equals("item")) throw new IllegalArgumentException("Only items can be announced in chat");
                entries.set(index, new LootPoolData.Entry(old.kind(), old.target(), old.chance(), old.weight(),
                        old.min(), old.max(), !old.showInChat()));
                saveEntries(server, pool, entries);
                return AdminPanelRegistry.ActionResult.success(old.showInChat()
                        ? "Chat announcement off for " + old.target() : "Chat announcement on for " + old.target(), EDIT + id);
            }
            case "loot.up" -> { if (index > 0) Collections.swap(entries, index, index - 1); }
            case "loot.down" -> { if (index < entries.size() - 1) Collections.swap(entries, index, index + 1); }
            case "loot.entry.save" -> {
                Identifier target = switch (old.kind()) {
                    case "item" -> itemId(values.getOrDefault("target", old.target().toString()));
                    case "currency" -> parse(values.getOrDefault("target", old.target().toString()));
                    default -> old.target();
                };
                int chance = values.containsKey("chance") ? chance(values.get("chance")) : old.chance();
                int min = values.containsKey("min") ? number(values.get("min"), "Min") : old.min();
                int max = values.containsKey("max") ? number(values.get("max"), "Max") : old.max();
                boolean showInChat = values.containsKey("show_in_chat")
                        ? "Yes".equals(values.get("show_in_chat")) : old.showInChat();
                entries.set(index, new LootPoolData.Entry(old.kind(), target, chance, old.weight(), min, max,
                        showInChat));
                saveEntries(server, pool, entries);
                return AdminPanelRegistry.ActionResult.success("Saved entry", EDIT + id);
            }
            default -> throw new IllegalArgumentException("Unknown loot action");
        }
        saveEntries(server, pool, entries);
        return AdminPanelRegistry.ActionResult.success("Moved entry", EDIT + id);
    }

    private static void saveEntries(MinecraftServer server, LootPoolData.Pool pool, List<LootPoolData.Entry> entries) {
        LootPoolService.save(server, new LootPoolData.Pool(pool.id(), pool.name(), pool.mode(), entries));
    }

    private static void requireRoom(List<LootPoolData.Entry> entries) {
        if (entries.size() >= 128) throw new IllegalArgumentException("A pool can hold at most 128 entries");
    }

    private static String backPage(Map<String, LootPoolData.Pool> pools, Identifier id) {
        return LootPoolService.parents(pools, id).stream().findFirst()
                .map(parent -> EDIT + parent.id()).orElse(ROOT);
    }

    private static int depth(Map<String, LootPoolData.Pool> pools, Identifier id) {
        int depth = 0;
        Identifier current = id;
        while (depth <= LootPoolService.MAX_DEPTH) {
            var parent = LootPoolService.parents(pools, current).stream().findFirst();
            if (parent.isEmpty()) break;
            current = parent.get().id();
            depth++;
        }
        return depth;
    }

    private static LootPoolData.Pool require(MinecraftServer server, Identifier id) {
        LootPoolData.Pool pool = LootPoolService.get(server, id);
        if (pool == null) throw new IllegalArgumentException("Unknown loot pool " + id);
        return pool;
    }

    private static List<String> itemIds() {
        return BuiltInRegistries.ITEM.keySet().stream().map(Object::toString)
                .filter(value -> !value.equals("minecraft:air")).sorted().toList();
    }

    private static List<String> playerCurrencies() {
        return CurrencyRegistry.values().stream()
                .filter(value -> value.scope() == CurrencyDefinition.CurrencyScope.PLAYER)
                .map(value -> value.id().toString()).sorted().toList();
    }

    /** Accepts "diamond" as well as "minecraft:diamond". */
    private static Identifier itemId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Choose an item first");
        Identifier id = Identifier.tryParse(value.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty() || id.toString().equals("minecraft:air"))
            throw new IllegalArgumentException("Unknown item: " + value.trim());
        return id;
    }

    private static Identifier parse(String value) {
        if (value == null || !value.contains(":")) throw new IllegalArgumentException("Use a namespaced ID such as cwp:raid_rewards");
        Identifier id = Identifier.tryParse(value.trim());
        if (id == null) throw new IllegalArgumentException("Invalid ID: " + value);
        return id;
    }

    private static Integer parseIndex(String value) {
        try {
            int index = Integer.parseInt(value);
            return index < 0 ? null : index;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Parses "3" or "1-3" (also with an en dash); blank means 1. */
    static int[] amount(String value) {
        if (value == null || value.isBlank()) return new int[]{1, 1};
        String[] range = value.trim().split("\\s*[-–]\\s*");
        try {
            int min = Integer.parseInt(range[0]);
            int max = range.length > 1 ? Integer.parseInt(range[1]) : min;
            if (range.length > 2 || min < 1 || max < min) throw new NumberFormatException();
            return new int[]{min, max};
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Amount must be a number like 2 or a range like 1-3");
        }
    }

    private static int number(String value, String label) {
        try { return Integer.parseInt(value.trim()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException(label + " must be a whole number"); }
    }

    /** Blank means 100%. Accepts up to two decimals and an optional % sign. */
    static int chance(String value) {
        if (value == null || value.isBlank()) return 10000;
        try {
            int chance = new BigDecimal(value.trim().replace("%", "").replace(',', '.').trim())
                    .movePointRight(2).intValueExact();
            if (chance < 0 || chance > 10000) throw new ArithmeticException();
            return chance;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Chance must be a percentage from 0 to 100, like 25 or 2.5");
        }
    }

    private static int scaledShare(int chance, int total) {
        return (int) Math.round(chance * 10000.0 / total);
    }

    private static String percentValue(int chance) {
        return BigDecimal.valueOf(chance, 2).stripTrailingZeros().toPlainString();
    }

    private static String percent(int chance) {
        return BigDecimal.valueOf(chance, 2).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private static String amountLabel(LootPoolData.Entry entry) {
        return "×" + entry.min() + (entry.max() == entry.min() ? "" : "–" + entry.max());
    }

    private static String countLabel(int count) {
        return count == 1 ? "1 entry" : count + " entries";
    }

    private static AdminPanelRegistry.AdminField field(String id, String label, String placeholder,
                                                       String initial, List<String> suggestions) {
        return new AdminPanelRegistry.AdminField(id, label, placeholder, initial, 128, suggestions);
    }
}
