package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.loot.LootPoolData;
import ekuzo.crazyworldprogression.loot.LootPoolService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.GOOD;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.NORMAL;

/** Compact server-backed loot pool editor with real item icons. */
final class AdminLootPoolEditor {
    static final String ROOT = "loot_pools";
    private static final String EDIT = ROOT + "/edit/";
    private static final String ENTRY = ROOT + "/entry/";

    private AdminLootPoolEditor() { }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId,
                                              List<AdminPanelRegistry.AdminTab> tabs) {
        if (pageId.equals(ROOT)) return list(player, tabs);
        if (pageId.startsWith(EDIT)) {
            Identifier id = Identifier.tryParse(pageId.substring(EDIT.length()));
            if (id != null && LootPoolService.get(player.level().getServer(), id) != null) return edit(player, id, tabs);
        }
        if (pageId.startsWith(ENTRY)) {
            String suffix = pageId.substring(ENTRY.length());
            int separator = suffix.lastIndexOf('/');
            Identifier id = separator < 0 ? null : Identifier.tryParse(suffix.substring(0, separator));
            if (id != null && LootPoolService.get(player.level().getServer(), id) != null)
                return entry(player, id, Integer.parseInt(suffix.substring(separator + 1)), tabs);
        }
        return list(player, tabs);
    }

    private static AdminPanelRegistry.AdminPage list(ServerPlayer player, List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(AdminPanelRegistry.AdminEntry.editor("Create", "New loot pool",
                "Use a namespaced ID. Create a parent pool and any nested pools separately.",
                List.of(field("id", "Pool ID", "cwp:raid_rewards", "", List.of()),
                        field("name", "Display name", "Raid rewards", "", List.of()),
                        AdminPanelRegistry.AdminField.select("mode", "Roll mode", "Independent", List.of("Independent", "Pick one"))),
                List.of(AdminPanelRegistry.AdminAction.wide("loot.create", "Create pool", false))));
        LootPoolService.all(player.level().getServer()).values().stream()
                .sorted(Comparator.comparing(pool -> pool.id().toString())).forEach(pool -> {
                    var firstItem = pool.entries().stream().filter(e -> e.kind().equals("item")).findFirst();
                    rows.add(new AdminPanelRegistry.AdminEntry(pool.name(),
                            pool.id() + " · " + modeLabel(pool.mode()) + " · " + pool.entries().size() + " rewards",
                            GOOD, List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Edit", EDIT + pool.id()),
                            new AdminPanelRegistry.AdminAction("loot.delete|" + pool.id(), "Delete", true)),
                            firstItem.map(value -> new AdminPanelRegistry.EntryPreview("item", value.target().toString(), 0))
                                    .orElse(null)));
                });
        return new AdminPanelRegistry.AdminPage(ROOT, "Loot pools",
                "Saved per world. Independent rewards each roll separately; a Pick one pool chooses one weighted entry.",
                tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage edit(ServerPlayer player, Identifier id,
                                                      List<AdminPanelRegistry.AdminTab> tabs) {
        LootPoolData.Pool pool = require(player, id);
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(AdminPanelRegistry.AdminEntry.editor("Pool", pool.id().toString(),
                "Independent rolls every reward's chance. Pick one chooses exactly one reward using weights.",
                List.of(field("name", "Display name", "", pool.name(), List.of()),
                        AdminPanelRegistry.AdminField.select("mode", "Roll mode", modeLabel(pool.mode()),
                                List.of("Independent", "Pick one"))),
                List.of(new AdminPanelRegistry.AdminAction("loot.save|" + id, "Save pool", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", ROOT))));
        for (int i = 0; i < pool.entries().size(); i++) {
            LootPoolData.Entry reward = pool.entries().get(i);
            String details = reward.kind().equals("pool") ? "Nested pool " + reward.target()
                    : reward.target() + " × " + reward.min() + (reward.max() == reward.min() ? "" : "–" + reward.max());
            details += pool.mode().equals("all") ? " · " + chanceLabel(reward.chance()) + " chance"
                    : " · weight " + reward.weight();
            if (reward.kind().equals("item") && reward.showInChat()) details += " · chat announcement";
            rows.add(new AdminPanelRegistry.AdminEntry((i + 1) + ". " + reward.kind(), details, NORMAL,
                    List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Edit", ENTRY + id + "/" + i),
                    new AdminPanelRegistry.AdminAction("loot.up|" + id + "|" + i, "Up", false),
                    new AdminPanelRegistry.AdminAction("loot.down|" + id + "|" + i, "Down", false),
                    new AdminPanelRegistry.AdminAction("loot.remove|" + id + "|" + i, "Remove", true)),
                    reward.kind().equals("item")
                            ? new AdminPanelRegistry.EntryPreview("item", reward.target().toString(), 0) : null));
        }
        rows.add(AdminPanelRegistry.AdminEntry.editor("Rewards", "Add a reward",
                "Items include all registered modded items. Nested pools may contain more nested pools.", List.of(),
                List.of(new AdminPanelRegistry.AdminAction("loot.add|" + id + "|item", "Item", false),
                        new AdminPanelRegistry.AdminAction("loot.add|" + id + "|currency", "Currency", false),
                        new AdminPanelRegistry.AdminAction("loot.add|" + id + "|pool", "Nested pool", false))));
        return new AdminPanelRegistry.AdminPage(EDIT + id, pool.name(),
                pool.id() + " · " + pool.entries().size() + " rewards", tabs, rows,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage entry(ServerPlayer player, Identifier id, int index,
                                                       List<AdminPanelRegistry.AdminTab> tabs) {
        LootPoolData.Pool pool = require(player, id);
        LootPoolData.Entry reward = pool.entries().get(index);
        List<String> suggestions = switch (reward.kind()) {
            case "item" -> BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).sorted().toList();
            case "currency" -> CurrencyRegistry.values().stream()
                    .filter(value -> value.scope() == CurrencyDefinition.CurrencyScope.PLAYER)
                    .map(value -> value.id().toString()).sorted().toList();
            default -> LootPoolService.all(player.level().getServer()).keySet().stream()
                    .filter(value -> !value.equals(id.toString())).sorted().toList();
        };
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        if (reward.kind().equals("item")) rows.add(new AdminPanelRegistry.AdminEntry("Item preview",
                reward.target().toString(), NORMAL, List.of(), List.of(), "Reward",
                new AdminPanelRegistry.EntryPreview("item", reward.target().toString(), 0)));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Reward", "Reward " + (index + 1), reward.kind(),
                List.of(field("target", reward.kind().equals("item") ? "Item ID"
                                : reward.kind().equals("currency") ? "Currency ID" : "Nested pool ID",
                        "namespace:id", reward.target().toString(), suggestions)), List.of()));
        if (pool.mode().equals("all")) rows.add(AdminPanelRegistry.AdminEntry.editor("Roll", "Chance",
                "Each reward in this pool rolls independently. 100% always gives this reward.",
                List.of(field("chance", "Chance %", "25", chanceLabel(reward.chance()), List.of())), List.of()));
        else rows.add(AdminPanelRegistry.AdminEntry.editor("Roll", "Weight",
                "One reward is chosen. Equal weights mean equal odds.",
                List.of(field("weight", "Weight", "1", Integer.toString(reward.weight()), List.of())), List.of()));
        if (!reward.kind().equals("pool")) rows.add(AdminPanelRegistry.AdminEntry.editor("Quantity", "Amount",
                "Roll a whole number between Min and Max, including both ends.",
                List.of(field("min", "Min", "1", Integer.toString(reward.min()), List.of()),
                        field("max", "Max", "1", Integer.toString(reward.max()), List.of())), List.of()));
        if (reward.kind().equals("item")) rows.add(AdminPanelRegistry.AdminEntry.editor("Announcement",
                "Show in chat", "Announce this item to everyone when a player rolls it.",
                List.of(AdminPanelRegistry.AdminField.select("show_in_chat", "Show in chat",
                        reward.showInChat() ? "Yes" : "No", List.of("No", "Yes"))), List.of()));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Actions", "Save reward", "Changes are saved to this world.",
                List.of(), List.of(AdminPanelRegistry.AdminAction.wide("loot.entry.save|" + id + "|" + index,
                                "Save reward", false),
                        AdminPanelRegistry.AdminAction.navigate("Back", EDIT + id))));
        return new AdminPanelRegistry.AdminPage(ENTRY + id + "/" + index, "Edit " + reward.kind(),
                pool.name() + " · reward " + (index + 1), tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        var server = player.level().getServer();
        if (actionId.equals("loot.create")) {
            Identifier id = parse(values.get("id"));
            if (LootPoolService.get(server, id) != null) throw new IllegalArgumentException("Loot pool already exists: " + id);
            LootPoolService.save(server, new LootPoolData.Pool(id, values.getOrDefault("name", "").trim(),
                    mode(values.get("mode")), List.of()));
            return AdminPanelRegistry.ActionResult.success("Created loot pool " + id, EDIT + id);
        }
        String[] parts = actionId.split("\\|");
        if (parts.length < 2) throw new IllegalArgumentException("Unknown loot action");
        Identifier id = parse(parts[1]);
        LootPoolData.Pool pool = require(player, id);
        if (parts[0].equals("loot.delete")) {
            LootPoolService.delete(server, id);
            return AdminPanelRegistry.ActionResult.success("Deleted loot pool " + id, ROOT);
        }
        if (parts[0].equals("loot.save")) {
            LootPoolService.save(server, new LootPoolData.Pool(id, values.getOrDefault("name", pool.name()).trim(),
                    mode(values.getOrDefault("mode", modeLabel(pool.mode()))), pool.entries()));
            return AdminPanelRegistry.ActionResult.success("Saved loot pool " + id, EDIT + id);
        }
        if (parts.length != 3) throw new IllegalArgumentException("Invalid reward action");
        List<LootPoolData.Entry> entries = new ArrayList<>(pool.entries());
        if (parts[0].equals("loot.add")) {
            if (entries.size() >= 128) throw new IllegalArgumentException("Pool has 128 rewards already");
            Identifier target = switch (parts[2]) {
                case "item" -> Identifier.parse("minecraft:stone");
                case "currency" -> CurrencyRegistry.values().stream()
                        .filter(value -> value.scope() == CurrencyDefinition.CurrencyScope.PLAYER)
                        .map(CurrencyDefinition::id).findFirst().orElseThrow(() -> new IllegalArgumentException("Create a player currency first"));
                case "pool" -> LootPoolService.all(server).values().stream()
                        .map(LootPoolData.Pool::id).filter(value -> !value.equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Create a second loot pool first"));
                default -> throw new IllegalArgumentException("Unknown reward type");
            };
            entries.add(new LootPoolData.Entry(parts[2], target, 10000, 1, 1, 1));
            LootPoolService.save(server, new LootPoolData.Pool(id, pool.name(), pool.mode(), entries));
            return AdminPanelRegistry.ActionResult.success("Added reward", ENTRY + id + "/" + (entries.size() - 1));
        }
        int index = Integer.parseInt(parts[2]);
        LootPoolData.Entry old = entries.get(index);
        switch (parts[0]) {
            case "loot.remove" -> entries.remove(index);
            case "loot.up" -> { if (index > 0) java.util.Collections.swap(entries, index, index - 1); }
            case "loot.down" -> { if (index < entries.size() - 1) java.util.Collections.swap(entries, index, index + 1); }
            case "loot.entry.save" -> {
                Identifier target = parse(values.getOrDefault("target", old.target().toString()));
                int chance = values.containsKey("chance") ? chance(values.get("chance")) : old.chance();
                int weight = values.containsKey("weight") ? Integer.parseInt(values.get("weight")) : old.weight();
                int min = values.containsKey("min") ? Integer.parseInt(values.get("min")) : old.min();
                int max = values.containsKey("max") ? Integer.parseInt(values.get("max")) : old.max();
                boolean showInChat = values.containsKey("show_in_chat")
                        ? switch (values.get("show_in_chat")) {
                            case "Yes" -> true;
                            case "No" -> false;
                            default -> throw new IllegalArgumentException("Choose Yes or No for the chat announcement");
                        } : old.showInChat();
                entries.set(index, new LootPoolData.Entry(old.kind(), target, chance, weight, min, max,
                        showInChat));
            }
            default -> throw new IllegalArgumentException("Unknown loot action");
        }
        LootPoolService.save(server, new LootPoolData.Pool(id, pool.name(), pool.mode(), entries));
        return AdminPanelRegistry.ActionResult.success("Saved loot rewards", EDIT + id);
    }

    private static LootPoolData.Pool require(ServerPlayer player, Identifier id) {
        LootPoolData.Pool pool = LootPoolService.get(player.level().getServer(), id);
        if (pool == null) throw new IllegalArgumentException("Unknown loot pool " + id);
        return pool;
    }
    private static Identifier parse(String value) {
        if (value == null || !value.contains(":")) throw new IllegalArgumentException("Use a namespaced ID such as cwp:raid_rewards");
        Identifier id = Identifier.tryParse(value.trim());
        if (id == null) throw new IllegalArgumentException("Invalid ID: " + value);
        return id;
    }
    private static String mode(String label) {
        if ("Pick one".equals(label)) return "one";
        if ("Independent".equals(label)) return "all";
        throw new IllegalArgumentException("Choose Independent or Pick one");
    }
    private static String modeLabel(String mode) { return mode.equals("one") ? "Pick one" : "Independent"; }
    private static String chanceLabel(int chance) {
        return java.math.BigDecimal.valueOf(chance, 2).stripTrailingZeros().toPlainString();
    }
    private static int chance(String value) {
        try { return new java.math.BigDecimal(value.trim()).movePointRight(2).intValueExact(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Chance must be a percentage from 0 to 100"); }
    }
    private static AdminPanelRegistry.AdminField field(String id, String label, String placeholder,
                                                       String initial, List<String> suggestions) {
        return new AdminPanelRegistry.AdminField(id, label, placeholder, initial, 128, suggestions);
    }
}
