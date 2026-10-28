package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.loot.FishingLootData;
import ekuzo.crazyworldprogression.loot.FishingLootService;
import ekuzo.crazyworldprogression.loot.LootPoolService;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A biome or pocket is created once, then owns any number of fishing rulesets. */
final class AdminFishingEditor {
    static final String ROOT = "fishing_loot";
    private static final String NEW_LOCATION = ROOT + "/new";
    private static final String LOCATION = ROOT + "/location/";
    private static final String NEW_RULE = ROOT + "/new_ruleset/";
    private static final String EDIT_RULE = ROOT + "/edit_ruleset/";
    private static final String LEGACY_ANY = "~";

    private AdminFishingEditor() { }

    static boolean owns(String pageId) { return pageId.equals(ROOT) || pageId.startsWith(ROOT + "/"); }

    static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId,
            List<AdminPanelRegistry.AdminTab> tabs) {
        MinecraftServer server = player.level().getServer();
        if (pageId.equals(NEW_LOCATION)) return newLocation(server, tabs);
        if (pageId.startsWith(NEW_RULE)) {
            String location = decodeLocation(pageId.substring(NEW_RULE.length()));
            if (!location.isEmpty() && FishingLootService.locations(server).contains(location))
                return ruleForm(server, null, location, tabs);
        }
        if (pageId.startsWith(EDIT_RULE)) {
            Identifier id = Identifier.tryParse(pageId.substring(EDIT_RULE.length()));
            FishingLootData.Rule rule = id == null ? null : FishingLootService.get(server, id);
            if (rule != null) return ruleForm(server, rule, rule.biome(), tabs);
        }
        if (pageId.startsWith(LOCATION)) {
            String location = decodeLocation(pageId.substring(LOCATION.length()));
            if (FishingLootService.locations(server).contains(location)) return location(server, location, tabs);
        }
        return root(server, tabs);
    }

    private static AdminPanelRegistry.AdminPage root(MinecraftServer server,
            List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        rows.add(AdminPanelRegistry.AdminEntry.editor("Create", "Biome or pocket",
                "Add a biome or pocket once, then manage its rulesets inside it.", List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Add biome or pocket", NEW_LOCATION),
                        AdminPanelRegistry.AdminAction.navigate("Edit loot pools", AdminLootPoolEditor.ROOT))));
        FishingLootService.locations(server).stream().sorted().forEach(location -> {
            int count = FishingLootService.rulesAt(server, location).size();
            rows.add(new AdminPanelRegistry.AdminEntry(locationLabel(location),
                    count + (count == 1 ? " ruleset" : " rulesets"), AdminPanelRegistry.Tone.GOOD,
                    List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Open", LOCATION + route(location)),
                            new AdminPanelRegistry.AdminAction("fishing.location.delete|" + route(location),
                                    "Delete", true))));
        });
        return new AdminPanelRegistry.AdminPage(ROOT, "Fishing loot",
                "Each biome or pocket has its own rulesets. One matching ruleset is selected by weight per catch.",
                tabs, rows, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage newLocation(MinecraftServer server,
            List<AdminPanelRegistry.AdminTab> tabs) {
        List<Identifier> known = locationIds(server);
        List<Identifier> available = known.stream()
                .filter(id -> !FishingLootService.locations(server).contains(id.toString())).toList();
        List<String> suggestions = available.stream()
                .map(id -> known.stream().filter(other -> displaySelector(other).equals(displaySelector(id)))
                        .count() > 1 ? id.toString() : displaySelector(id))
                .distinct().sorted().toList();
        return new AdminPanelRegistry.AdminPage(NEW_LOCATION, "Add biome or pocket",
                "Each biome or pocket can be added once.", tabs,
                List.of(AdminPanelRegistry.AdminEntry.editor("Location", "Biome or pocket",
                        "Choose a registered biome or pocket.",
                        List.of(new AdminPanelRegistry.AdminField("biome", "Biome or pocket",
                                "dark_forest or my_pocket", "", 128, suggestions)),
                        List.of(new AdminPanelRegistry.AdminAction("fishing.location.create", "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", ROOT)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage location(MinecraftServer server, String location,
            List<AdminPanelRegistry.AdminTab> tabs) {
        List<AdminPanelRegistry.AdminEntry> rows = new ArrayList<>();
        List<FishingLootData.Rule> rules = FishingLootService.rulesAt(server, location);
        List<AdminPanelRegistry.AdminAction> actions = new ArrayList<>();
        if (!location.isEmpty() && !location.startsWith("#"))
            actions.add(AdminPanelRegistry.AdminAction.navigate("Add ruleset",
                NEW_RULE + route(location)));
        actions.add(AdminPanelRegistry.AdminAction.navigate("Back", ROOT));
        rows.add(AdminPanelRegistry.AdminEntry.editor("Rulesets", locationLabel(location),
                "Add rulesets with different liquids, weather, time, pools, and weights.", List.of(), actions));
        if (rules.isEmpty()) rows.add(AdminPanelRegistry.AdminEntry.editor("Rulesets", "No rulesets yet",
                "Add one to replace the normal fishing catch here.", List.of(), List.of()));
        rules.stream().sorted(Comparator.comparing(rule -> rule.id().toString())).forEach(rule ->
                rows.add(new AdminPanelRegistry.AdminEntry("Ruleset · " + AdminIds.display(rule.pool()),
                        "Weight " + rule.weight() + " · liquid " + any(displayLiquid(rule.liquid()))
                                + " · " + rule.weather() + " · " + rule.time(),
                        AdminPanelRegistry.Tone.GOOD, List.of(),
                        List.of(AdminPanelRegistry.AdminAction.navigate("Edit", EDIT_RULE + rule.id()),
                                new AdminPanelRegistry.AdminAction("fishing.rule.delete|" + rule.id(),
                                        "Delete", true)))));
        return new AdminPanelRegistry.AdminPage(LOCATION + route(location), locationLabel(location),
                "Matching rulesets compete by weight; the selected pool keeps its independent entry rolls.",
                tabs, rows, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage ruleForm(MinecraftServer server, FishingLootData.Rule rule,
            String location, List<AdminPanelRegistry.AdminTab> tabs) {
        boolean creating = rule == null;
        List<String> pools = LootPoolService.all(server).values().stream()
                .map(pool -> AdminIds.display(pool.id())).distinct().sorted().toList();
        List<String> liquids = BuiltInRegistries.FLUID.keySet().stream()
                .map(AdminFishingEditor::displaySelector).distinct().sorted().toList();
        List<AdminPanelRegistry.AdminEntry> rows = List.of(
                AdminPanelRegistry.AdminEntry.editor("Location", locationLabel(location),
                        "This ruleset belongs to this biome or pocket.", List.of(), List.of()),
                AdminPanelRegistry.AdminEntry.editor("Loot", "Loot pool", "Choose an existing pool.", List.of(
                        new AdminPanelRegistry.AdminField("pool", "Loot pool", "river_fish",
                                creating ? "" : AdminIds.display(rule.pool()), 128, pools)), List.of()),
                AdminPanelRegistry.AdminEntry.editor("Conditions", "Liquid", "Blank matches any liquid.", List.of(
                        new AdminPanelRegistry.AdminField("liquid", "Liquid", "water or lava",
                                creating ? "" : displayLiquid(rule.liquid()), 128, liquids)), List.of()),
                AdminPanelRegistry.AdminEntry.editor("Conditions", "Weather and time", "Both must match.", List.of(
                        AdminPanelRegistry.AdminField.select("weather", "Weather",
                                creating ? "any" : rule.weather(), List.of("any", "clear", "rain", "thunder")),
                        AdminPanelRegistry.AdminField.select("time", "Time",
                                creating ? "any" : rule.time(), List.of("any", "day", "night"))), List.of()),
                AdminPanelRegistry.AdminEntry.editor("Selection", "Ruleset weight",
                        "Relative to other matching rulesets. Weights 90 and 10 give a 90% / 10% split.", List.of(
                        new AdminPanelRegistry.AdminField("weight", "Weight", "100",
                                creating ? "100" : Integer.toString(rule.weight()), 8, List.of())), List.of()),
                AdminPanelRegistry.AdminEntry.editor("Actions", "Save ruleset",
                        "The selected pool replaces vanilla fishing loot.", List.of(),
                        List.of(new AdminPanelRegistry.AdminAction(creating
                                        ? "fishing.rule.create|" + route(location)
                                        : "fishing.rule.save|" + rule.id(),
                                        creating ? "Create" : "Save", false),
                                AdminPanelRegistry.AdminAction.navigate("Back", LOCATION + route(location)))));
        return new AdminPanelRegistry.AdminPage(creating ? NEW_RULE + route(location) : EDIT_RULE + rule.id(),
                creating ? "Add ruleset" : "Edit ruleset", "Night is world ticks 13000–22999.",
                tabs, rows, AdminPanelRegistry.PageLayout.EDITOR);
    }

    static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("fishing.location.create")) {
            Identifier id = AdminIds.reference(values.get("biome"), locationIds(server));
            FishingLootService.createLocation(server, id);
            return AdminPanelRegistry.ActionResult.success("Added " + locationLabel(id.toString()),
                    LOCATION + id);
        }
        if (actionId.startsWith("fishing.location.delete|")) {
            String location = decodeLocation(actionId.substring("fishing.location.delete|".length()));
            FishingLootService.deleteLocation(server, location);
            return AdminPanelRegistry.ActionResult.success("Deleted " + locationLabel(location)
                    + " and its rulesets", ROOT);
        }
        if (actionId.startsWith("fishing.rule.delete|")) {
            Identifier id = parse(actionId.substring("fishing.rule.delete|".length()));
            FishingLootData.Rule rule = FishingLootService.get(server, id);
            if (rule == null) throw new IllegalArgumentException("Unknown fishing ruleset " + id);
            FishingLootService.delete(server, id);
            return AdminPanelRegistry.ActionResult.success("Deleted ruleset", LOCATION + route(rule.biome()));
        }
        boolean creating = actionId.startsWith("fishing.rule.create|");
        String location;
        Identifier id;
        if (creating) {
            location = decodeLocation(actionId.substring("fishing.rule.create|".length()));
            if (location.isEmpty() || location.startsWith("#")
                    || !FishingLootService.locations(server).contains(location))
                throw new IllegalArgumentException("Choose an existing biome or pocket");
            id = nextId(server);
        } else if (actionId.startsWith("fishing.rule.save|")) {
            id = parse(actionId.substring("fishing.rule.save|".length()));
            FishingLootData.Rule old = FishingLootService.get(server, id);
            if (old == null) throw new IllegalArgumentException("Unknown fishing ruleset " + id);
            location = old.biome();
        } else throw new IllegalArgumentException("Unknown fishing action");
        String liquid = values.getOrDefault("liquid", "").trim();
        if (liquid.startsWith("#")) throw new IllegalArgumentException("Enter a liquid name without #");
        int weight;
        try { weight = Integer.parseInt(values.getOrDefault("weight", "100").trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Weight must be a whole number"); }
        FishingLootData.Rule rule = new FishingLootData.Rule(id,
                AdminIds.reference(values.get("pool"), LootPoolService.all(server).values().stream()
                        .map(pool -> pool.id()).toList()), location,
                liquid.isEmpty() ? "" : AdminIds.reference(liquid, BuiltInRegistries.FLUID.keySet()).toString(),
                values.getOrDefault("weather", "any"), values.getOrDefault("time", "any"), weight);
        FishingLootService.save(server, rule);
        return AdminPanelRegistry.ActionResult.success("Saved fishing ruleset", EDIT_RULE + id);
    }

    private static List<Identifier> locationIds(MinecraftServer server) {
        return Stream.concat(server.registryAccess().lookupOrThrow(Registries.BIOME)
                        .listElementIds().map(key -> key.identifier()), PocketEditorService.pockets(server).stream())
                .toList();
    }

    private static Identifier nextId(MinecraftServer server) {
        for (int index = 1; index <= 256; index++) {
            Identifier candidate = CrazyWorldProgression.id("fishing_rule_" + index);
            if (FishingLootService.get(server, candidate) == null) return candidate;
        }
        throw new IllegalArgumentException("At most 256 fishing rulesets are allowed");
    }

    private static String route(String location) { return location.isEmpty() ? LEGACY_ANY : location; }
    private static String decodeLocation(String route) { return route.equals(LEGACY_ANY) ? "" : route; }
    private static String any(String value) { return value.isEmpty() ? "any" : value; }
    private static String locationLabel(String value) {
        if (value.isEmpty()) return "Any biome (legacy)";
        if (value.startsWith("#")) return "Biome tag " + value + " (legacy)";
        Identifier id = Identifier.tryParse(value);
        return id == null ? value : displaySelector(id);
    }
    private static String displaySelector(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : AdminIds.display(id);
    }
    private static String displayLiquid(String value) {
        return value.isEmpty() || value.startsWith("#") ? value : displaySelector(Identifier.parse(value));
    }
    private static Identifier parse(String raw) { return AdminIds.create(raw); }
}
