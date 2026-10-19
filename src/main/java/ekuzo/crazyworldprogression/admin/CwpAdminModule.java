package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.events.RandomEventDefinition;
import ekuzo.crazyworldprogression.events.RandomEventManager;
import ekuzo.crazyworldprogression.events.RandomEventRegistry;
import ekuzo.crazyworldprogression.events.RandomEventService;
import ekuzo.crazyworldprogression.portals.PortalDefinition;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.PortalTemplateService;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import ekuzo.crazyworldprogression.progression.skilltrees.BaselineStatRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeManager;
import ekuzo.crazyworldprogression.selection.AreaSelectionService;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgePurge;
import ekuzo.crazyworldprogression.mobforge.networking.MobForgeNetworking;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import ekuzo.crazyworldprogression.api.mobforge.EntitySpawnService;
import ekuzo.crazyworldprogression.api.mobforge.SpawnContext;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.GOOD;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.NORMAL;
import static ekuzo.crazyworldprogression.admin.AdminPanelRegistry.Tone.WARNING;

/** Native CWP administration module implemented entirely through the public generic panel contract. */
public final class CwpAdminModule {
    private static final String OVERVIEW = "overview";
    private static final String POCKETS = "pockets";
    private static final String POCKET_NEW = POCKETS + "/new";
    private static final String POCKET_EDIT = POCKETS + "/edit/";
    private static final String POCKET_INSTANCES = POCKETS + "/instances";
    private static final String SCRIPT_SEGMENT = "/scripts/";
    private static final String PORTALS = "portals";
    private static final String PORTAL_NEW = PORTALS + "/new";
    private static final String PORTAL_EDIT = PORTALS + "/edit/";
    private static final String BUILDINGS = "buildings";
    private static final String BUILDING_NEW = BUILDINGS + "/new";
    private static final String CURRENCIES = "currencies";
    private static final String CURRENCY_EDIT = CURRENCIES + "/edit/";
    private static final String SKILL_TREES = "skill_trees";
    private static final String EVENTS = "events";
    private static final String MOBFORGE = "mobforge";
    private static final String MOB_MOBS = MOBFORGE + "/mobs";
    private static final String MOB_SCRIPTS = MOBFORGE + "/script-list";
    private static final String MOB_API = MOBFORGE + "/registered";
    private static final String MOB_EDIT = MOB_MOBS + "/edit/";
    private static final String MOB_SCRIPT_EDIT = MOBFORGE + SCRIPT_SEGMENT;
    private static final List<String> SHAPES = List.of("Rectangle", "Ellipse", "Diamond", "Triangle");
    private static final List<String> PLANES = List.of(
            "Vertical north/south", "Vertical east/west", "Horizontal floor/ceiling");
    private static final List<String> PORTAL_COLORS = Arrays.stream(PortalDefinition.PortalColor.values())
            .map(color -> PortalDefinition.PortalColor.displayName(color.rgb())).toList();
    private static final List<String> ENTRY_POLICIES = List.of("Repeatable", "Once per instance", "Once per attempt", "Once ever");
    private static final List<String> ACCESS_MODES = List.of("Public", "Dungeon");
    private static final List<String> DAYTIMES = List.of("Dawn", "Morning", "Noon", "Dusk", "Night", "Midnight");
    private static final List<String> WEATHER_STATES = List.of("Clear", "Rain", "Thunder");
    private static final List<String> DIFFICULTIES = List.of("1 skull", "2 skulls", "3 skulls", "4 skulls", "5 skulls");
    private static final String PLAYERS = "players";
    private static final String LOGS = "logs";
    private static final List<AdminPanelRegistry.AdminTab> TABS = List.of(
            new AdminPanelRegistry.AdminTab(PLAYERS, "Players", AdminPanelRegistry.MODERATE),
            new AdminPanelRegistry.AdminTab(POCKET_INSTANCES, "Instances", AdminPanelRegistry.MODERATE),
            new AdminPanelRegistry.AdminTab(EVENTS, "Events", AdminPanelRegistry.MODERATE),
            new AdminPanelRegistry.AdminTab(CURRENCIES, "Currencies", AdminPanelRegistry.MODERATE),
            new AdminPanelRegistry.AdminTab(POCKETS, "Pockets", AdminPanelRegistry.BUILD),
            new AdminPanelRegistry.AdminTab(PORTALS, "Portals", AdminPanelRegistry.BUILD),
            new AdminPanelRegistry.AdminTab(BUILDINGS, "Buildings", AdminPanelRegistry.BUILD),
            new AdminPanelRegistry.AdminTab(MOB_MOBS, "Mobs", AdminPanelRegistry.BUILD),
            new AdminPanelRegistry.AdminTab(MOB_SCRIPTS, "Scripts", AdminPanelRegistry.BUILD),
            new AdminPanelRegistry.AdminTab(AdminLootPoolEditor.ROOT, "Loot pools", AdminPanelRegistry.BUILD),
            new AdminPanelRegistry.AdminTab(AdminPerformance.PAGE, "Performance", AdminPanelRegistry.LOGS),
            new AdminPanelRegistry.AdminTab(LOGS, "Server log", AdminPanelRegistry.LOGS),
            new AdminPanelRegistry.AdminTab(SKILL_TREES, "Skill trees", AdminPanelRegistry.LOGS),
            new AdminPanelRegistry.AdminTab(OVERVIEW, "CWP status", AdminPanelRegistry.LOGS));
    /** Per admin: whether the server log shows only warnings and errors. */
    private static final Map<java.util.UUID, Boolean> LOG_PROBLEMS_ONLY = new java.util.HashMap<>();

    // Prevent construction of the native module registrar.
    private CwpAdminModule() {
    }

    // Publish CWP as the first top-level module in its own extensible dashboard.
    public static void register() {
        AdminPanelRegistry.register(new AdminPanelRegistry.AdminModule(
                CrazyWorldProgression.id("admin"), "Crazy World Progression",
                "Framework health, pockets, MobForge, currencies, skill trees and random events.",
                "minecraft:nether_star", OVERVIEW, CwpAdminModule::page, CwpAdminModule::action, TABS));
        AdminLog.install();
    }

    /** Opens the mob editor in the admin panel (used by /mobforge editor). */
    public static boolean openMobEditor(ServerPlayer player, String templateId) {
        return AdminPanelNetworking.openPage(player, CrazyWorldProgression.id("admin"),
                MOB_EDIT + (templateId == null ? "@new" : templateId));
    }

    public static boolean openScriptEditor(ServerPlayer player, String scriptId) {
        return AdminPanelNetworking.openPage(player, CrazyWorldProgression.id("admin"),
                MOB_SCRIPT_EDIT + (scriptId == null ? "@new" : scriptId));
    }

    // Route a requested tab to a fresh server-authoritative page.
    private static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId) {
        if (AdminCategories.owns(pageId)) return AdminCategories.page(player, pageId, TABS);
        if (AdminLootPoolEditor.owns(pageId)) return AdminLootPoolEditor.page(player, pageId, TABS);
        ScriptRoute scriptRoute = scriptRoute(pageId);
        if (scriptRoute != null) return AdminScriptEditor.page(player, pageId, scriptRoute.backPage(),
                scriptRoute.pocketId(), scriptRoute.scriptId(), TABS);
        if (pageId.startsWith(MOB_EDIT)) return AdminMobEditor.page(player, pageId,
                pageId.substring(MOB_EDIT.length()), TABS, MOB_MOBS);
        if (pageId.equals(POCKET_NEW)) return newPocketPage();
        if (pageId.equals(POCKET_INSTANCES)) return activeInstancesPage(player);
        if (pageId.startsWith(POCKET_EDIT)) {
            Identifier id = Identifier.tryParse(pageId.substring(POCKET_EDIT.length()));
            return pocketEditorPage(player, id);
        }
        if (pageId.equals(PORTAL_NEW)) return portalEditorPage(player, null);
        if (pageId.equals(BUILDING_NEW)) return newBuildingPage(player);
        if (pageId.startsWith(BUILDINGS + "/edit/")) {
            Identifier id = Identifier.tryParse(pageId.substring((BUILDINGS + "/edit/").length()));
            if (id != null) return buildingEditorPage(player, id);
        }
        if (pageId.startsWith(PORTAL_EDIT)) {
            Identifier id = Identifier.tryParse(pageId.substring(PORTAL_EDIT.length()));
            return portalEditorPage(player, id);
        }
        if (pageId.startsWith(CURRENCY_EDIT)) {
            Identifier id = Identifier.tryParse(pageId.substring(CURRENCY_EDIT.length()));
            return currencyEditorPage(player, id);
        }
        return switch (pageId) {
            case PLAYERS -> playersPage(player);
            case LOGS -> logsPage(player);
            case AdminPerformance.PAGE -> AdminPerformance.page(player, TABS);
            case POCKETS -> pocketsPage(player);
            case PORTALS -> portalsPage(player);
            case BUILDINGS -> buildingsPage(player);
            case CURRENCIES -> currenciesPage(player);
            case SKILL_TREES -> skillTreesPage(player);
            case MOBFORGE -> mobMobsPage(player.level().getServer());
            case MOB_MOBS -> mobMobsPage(player.level().getServer());
            case MOB_SCRIPTS -> mobScriptsPage(player.level().getServer());
            case MOB_API -> mobApiPage();
            case EVENTS -> eventsPage(player);
            default -> overviewPage(player);
        };
    }

    /** The same entry filed under a section heading (Create, Active, All…). */
    static AdminPanelRegistry.AdminEntry in(String section, AdminPanelRegistry.AdminEntry entry) {
        return new AdminPanelRegistry.AdminEntry(entry.title(), entry.detail(), entry.tone(), entry.fields(),
                entry.actions(), section, entry.preview());
    }

    /** Moderation: every online player with where they are, and quick actions. */
    private static AdminPanelRegistry.AdminPage playersPage(ServerPlayer viewer) {
        MinecraftServer server = viewer.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        entries.add(AdminPanelRegistry.AdminEntry.editor("Find", "Teleport to a player", "Type a name, then Teleport.",
                List.of(new AdminPanelRegistry.AdminField("player_name", "Player (type to search)", "name", "", 32,
                        players.stream().map(p -> p.getGameProfile().name()).sorted(String.CASE_INSENSITIVE_ORDER).toList())),
                List.of(new AdminPanelRegistry.AdminAction("player.tp_name", "Teleport", false))));
        players.sort(java.util.Comparator.comparing(p -> p.getGameProfile().name().toLowerCase(java.util.Locale.ROOT)));
        for (ServerPlayer target : players) {
            Identifier dimension = target.level().dimension().identifier();
            boolean instance = PortalService.inInstance(target);
            boolean workshop = PocketEditorService.isEditorDimension(dimension);
            String where = instance ? "in instance " + dimension.getPath() : workshop ? "in workshop " + dimension.getPath()
                    : dimension.getPath() + " " + target.blockPosition().toShortString();
            String detail = where + " · " + target.gameMode().getName() + " · "
                    + Math.round(target.getHealth()) + "/" + Math.round(target.getMaxHealth()) + " hp";
            List<AdminPanelRegistry.AdminAction> actions = new ArrayList<>();
            if (!target.getUUID().equals(viewer.getUUID()))
                actions.add(new AdminPanelRegistry.AdminAction("player.tp|" + target.getUUID(), "Teleport to", false));
            if (instance || workshop)
                actions.add(new AdminPanelRegistry.AdminAction("player.out|" + target.getUUID(), "Send out", true));
            entries.add(in("Online", new AdminPanelRegistry.AdminEntry(target.getGameProfile().name()
                    + (target.getUUID().equals(viewer.getUUID()) ? " (you)" : ""), detail, GOOD, List.of(),
                    actions, new AdminPanelRegistry.EntryPreview("item", "minecraft:player_head", 0))));
        }
        return new AdminPanelRegistry.AdminPage(PLAYERS, "Players", players.size() + " online", TABS, entries,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    /** Recent server log lines: warnings and errors from everything, normal messages from CWP and Echelon. */
    private static AdminPanelRegistry.AdminPage logsPage(ServerPlayer player) {
        boolean problemsOnly = LOG_PROBLEMS_ONLY.getOrDefault(player.getUUID(), false);
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(new AdminPanelRegistry.AdminEntry(problemsOnly ? "Showing warnings and errors" : "Showing everything",
                "Newest first · kept in memory until the server restarts", NORMAL, List.of(),
                List.of(new AdminPanelRegistry.AdminAction("logs.toggle", problemsOnly ? "Show all" : "Only problems", false),
                        new AdminPanelRegistry.AdminAction("logs.clear", "Clear", true)), null));
        java.time.format.DateTimeFormatter time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault());
        for (AdminLog.Line line : AdminLog.lines(problemsOnly, 200)) {
            AdminPanelRegistry.Tone tone = line.level().equals("ERROR") || line.level().equals("FATAL")
                    ? AdminPanelRegistry.Tone.DANGER
                    : line.level().equals("WARN") ? AdminPanelRegistry.Tone.WARNING : NORMAL;
            entries.add(new AdminPanelRegistry.AdminEntry(time.format(line.time()) + "  " + line.message(),
                    line.level() + " · " + line.source(), tone, List.of(), List.of(), null));
        }
        if (entries.size() == 1) entries.add(AdminPanelRegistry.AdminEntry.info("Nothing logged yet", ""));
        return new AdminPanelRegistry.AdminPage(LOGS, "Server log", (entries.size() - 1) + " line(s)", TABS, entries,
                AdminPanelRegistry.PageLayout.STANDARD);
    }

    // Assemble the framework health overview and its safe refresh control.
    private static AdminPanelRegistry.AdminPage overviewPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        int activeInstances = PortalService.activeInstances(server).size();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(AdminPanelRegistry.AdminEntry.info("Registered currencies",
                CurrencyRegistry.values().size() + " reusable currency definition(s)"));
        entries.add(AdminPanelRegistry.AdminEntry.info("Skill trees",
                SkillTreeManager.getSkillTrees().size() + " tree(s), "
                        + SkillStatRegistry.registeredFunctions().size() + " stat function(s)"));
        entries.add(new AdminPanelRegistry.AdminEntry("Pocket dimensions",
                activeInstances + " of " + PortalRegistry.instanceSlots().size() + " slots active",
                activeInstances == PortalRegistry.instanceSlots().size() ? WARNING : GOOD, List.of()));
        entries.add(new AdminPanelRegistry.AdminEntry("Random events",
                RandomEventManager.definitions().size() + " loaded, "
                        + RandomEventService.activeEvents(server).size() + " active",
                RandomEventService.isBlackoutActive() ? WARNING : NORMAL, List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.info("MobForge",
                MobForge.TEMPLATES.all().size() + " YAML templates · " + MobForge.SCRIPTS.all().size()
                        + " scripts · " + MobForge.mobs().all().size() + " API mobs"));
        entries.add(new AdminPanelRegistry.AdminEntry("Reload framework data",
                "Reload baseline stats, skill-tree YAML and random-event YAML, then refresh online players.",
                NORMAL, List.of(new AdminPanelRegistry.AdminAction("reload", "Reload", false))));
        return page(OVERVIEW, "Framework overview", "Live state from the running server", entries);
    }

    // List saved pockets separately from running portal instances.
    private static AdminPanelRegistry.AdminPage pocketsPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(in("Create", new AdminPanelRegistry.AdminEntry("Create pocket", "Create a dedicated build dimension first.",
                GOOD, List.of(AdminPanelRegistry.AdminAction.navigate("Create", POCKET_NEW)))));
        Map<Identifier, Long> running = PortalService.activeInstances(server).stream().collect(Collectors.groupingBy(
                PortalService.ActiveInstance::templateId, Collectors.counting()));
        List<AdminPanelRegistry.AdminEntry> active = new ArrayList<>();
        Map<String, AdminPanelRegistry.AdminEntry> all = new java.util.LinkedHashMap<>();
        for (Identifier id : PocketEditorService.pockets(server)) {
            PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, id);
            long instances = running.getOrDefault(id, 0L);
            AdminPanelRegistry.AdminEntry entry = new AdminPanelRegistry.AdminEntry(id.toString(),
                    (template == null ? "Build space ready · no build saved yet"
                            : template.sizeX() + "×" + template.sizeY() + "×" + template.sizeZ())
                            + (instances > 0 ? " · " + instances + " running" : ""), instances > 0 ? GOOD : NORMAL, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", POCKET_EDIT + id),
                            new AdminPanelRegistry.AdminAction("pocket.delete|" + id, "Delete", true)),
                    null);
            if (instances > 0) active.add(in("Active · instances running", entry));
            all.put(id.toString(), entry);
        }
        entries.addAll(active);
        entries.addAll(AdminCategories.sectioned(server, "pocket", "All pockets", all, POCKETS));
        return new AdminPanelRegistry.AdminPage(POCKETS, "Pocket dimensions",
                PocketEditorService.pockets(server).size() + " saved pockets", TABS, entries,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage activeInstancesPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        for (PortalService.ActiveInstance instance : PortalService.activeInstances(server)) {
            String remaining = instance.expires().toEpochMilli() == Long.MAX_VALUE ? "no expiry"
                    : Math.max(0L, Duration.between(Instant.now(), instance.expires()).toSeconds())
                    + "s remaining";
            entries.add(in("Active · running now", new AdminPanelRegistry.AdminEntry(instance.portalId().toString(),
                    instance.templateId() + " in " + instance.slotDimension() + " · " + remaining,
                    GOOD, List.of(new AdminPanelRegistry.AdminAction(
                    "instance.expire|" + instance.slotDimension(), "Expire", true)))));
        }
        return new AdminPanelRegistry.AdminPage(POCKET_INSTANCES, "Active instances",
                PortalService.activeInstances(server).size() + "/" + PortalRegistry.instanceSlots().size()
                        + " reusable slots active", TABS, entries,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage newPocketPage() {
        return new AdminPanelRegistry.AdminPage(POCKET_NEW, "Create pocket",
                "Choose a permanent pocket ID, then enter its private build dimension.", TABS,
                List.of(AdminPanelRegistry.AdminEntry.editor("Pocket", "Identity", "One build dimension per pocket",
                        List.of(textField("template_id", "Pocket ID", "", "cwp:my_pocket", 128, List.of())),
                        List.of(AdminPanelRegistry.AdminAction.wide("pocket.create", "Create pocket", false),
                                AdminPanelRegistry.AdminAction.navigate("Back to pockets", POCKETS)))),
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    // Edit only the private build dimension and structure owned by the selected pocket.
    private static AdminPanelRegistry.AdminPage pocketEditorPage(ServerPlayer player, Identifier requestedId) {
        MinecraftServer server = player.level().getServer();
        if (requestedId == null || !PocketEditorService.exists(server, requestedId)) return pocketsPage(player);
        AreaSelectionService.clear(player);
        PortalTemplateService.TemplateInfo existing = PortalTemplateService.template(server, requestedId);
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(AdminPanelRegistry.AdminEntry.editor("Pocket", "Identity",
                (existing == null ? "No build saved yet. " : "")
                        + PocketEditorService.autoSaveStatus(server, requestedId),
                List.of(AdminPanelRegistry.AdminField.readOnly("template_id", "Pocket ID", requestedId.toString())),
                List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Pocket", "Build dimension",
                "Enter this pocket to build and edit its blocks.", List.of(),
                List.of(new AdminPanelRegistry.AdminAction("pocket.enter", "Enter", false),
                        new AdminPanelRegistry.AdminAction("pocket.leave", "Leave", false))));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Pocket", "Build capture",
                "Build and spawn changes save automatically. Entry uses a safe floor near these coordinates.",
                List.of(textField("pocket_spawn_x", "Spawn X", Integer.toString(PocketEditorService.entry(server, requestedId).getX()), "0", 12, List.of()),
                        textField("pocket_spawn_y", "Spawn Y", Integer.toString(PocketEditorService.entry(server, requestedId).getY()), "64", 12, List.of()),
                        textField("pocket_spawn_z", "Spawn Z", Integer.toString(PocketEditorService.entry(server, requestedId).getZ()), "0", 12, List.of())),
                List.of(new AdminPanelRegistry.AdminAction("pocket.spawn.current", "Use my position", false))));
        Identifier editorDimension = PocketEditorService.dimension(server, requestedId);
        boolean insideEditor = editorDimension != null
                && editorDimension.equals(player.level().dimension().identifier());
        entries.add(AdminPanelRegistry.AdminEntry.editor("Exit portals", "Create portal",
                insideEditor ? "Place a portal here. New portals in this editor return players by default."
                        : "Enter this pocket's build dimension before placing a portal.", List.of(),
                insideEditor ? List.of(AdminPanelRegistry.AdminAction.navigate("New exit portal", PORTAL_NEW))
                        : List.of()));
        if (editorDimension != null) for (PortalDefinition inside : PortalService.portals(server)) {
            if (PortalService.locations(server, inside.id()).values().stream()
                    .noneMatch(location -> location.dimension().equals(editorDimension))) continue;
            entries.add(AdminPanelRegistry.AdminEntry.editor("Exit portals", inside.effectiveDisplayName(),
                    inside.isExit() ? "Returns to the main world" : inside.isCoordinates()
                            ? "Leads to coordinates in this world" : "Leads to " + inside.templateId(),
                    List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Edit", PORTAL_EDIT + inside.id()))));
        }
        List<String> availableScripts = MobForge.SCRIPTS.all().stream().map(MobScript::id).toList();
        entries.add(AdminPanelRegistry.AdminEntry.editor("Mob scripts", "Create script",
                "Build a timeline in this panel and attach it to this pocket when saved.", List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("New script",
                        POCKET_EDIT + requestedId + SCRIPT_SEGMENT + "@new"))));
        if (!availableScripts.isEmpty()) {
            entries.add(AdminPanelRegistry.AdminEntry.editor("Mob scripts", "Attach existing script",
                    "Attached scripts start when a portal instance is created.",
                    List.of(AdminPanelRegistry.AdminField.select("pocket_script", "Script",
                            availableScripts.getFirst(), availableScripts)),
                    List.of(new AdminPanelRegistry.AdminAction("pocket.script.attach", "Attach", false))));
        }
        for (String scriptId : PocketEditorService.scripts(server, requestedId)) {
            MobScript script = MobForge.SCRIPTS.get(scriptId).orElse(null);
            entries.add(AdminPanelRegistry.AdminEntry.editor("Mob scripts", scriptId,
                    script == null ? "Script file missing" : script.steps().size() + " steps",
                    List.of(), script == null
                            ? List.of(new AdminPanelRegistry.AdminAction("pocket.script.remove|" + scriptId,
                                    "Remove", true))
                            : List.of(AdminPanelRegistry.AdminAction.navigate("Edit",
                                    POCKET_EDIT + requestedId + SCRIPT_SEGMENT + scriptId),
                                    new AdminPanelRegistry.AdminAction("pocket.script.remove|" + scriptId,
                                            "Remove", true))));
        }
        entries.add(AdminPanelRegistry.AdminEntry.editor("Actions", "Save",
                "Saves the settings on this page. Blocks you build in the workshop are saved when you leave it.",
                List.of(), List.of(AdminPanelRegistry.AdminAction.wide("pocket.save|" + requestedId, "Save", false),
                        AdminPanelRegistry.AdminAction.navigate("Back to pockets", POCKETS))));
        return new AdminPanelRegistry.AdminPage(POCKET_EDIT + requestedId,
                "Edit pocket", "Private build dimension",
                TABS, entries, AdminPanelRegistry.PageLayout.EDITOR);
    }

    // List portal definitions independently of their placements.
    private static AdminPanelRegistry.AdminPage portalsPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(in("Create", new AdminPanelRegistry.AdminEntry("Create portal", "Open an empty, organized portal editor.",
                GOOD, List.of(AdminPanelRegistry.AdminAction.navigate("Create", PORTAL_NEW)))));
        List<AdminPanelRegistry.AdminEntry> active = new ArrayList<>();
        Map<String, AdminPanelRegistry.AdminEntry> all = new java.util.LinkedHashMap<>();
        for (PortalDefinition portal : PortalService.portals(server)) {
            String portalId = portal.id().toString();
            int locationCount = PortalService.locations(server, portal.id()).size();
            AdminPanelRegistry.AdminEntry entry = new AdminPanelRegistry.AdminEntry(portal.effectiveDisplayName(),
                    "Linked to: " + (portal.isExit() ? "Exit"
                            : portal.isCoordinates() ? "Coordinates" : portal.templateId())
                            + " · " + locationCount + " location(s)",
                    PortalService.isOpen(portal) ? GOOD : NORMAL, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", PORTAL_EDIT + portalId),
                            new AdminPanelRegistry.AdminAction("portal.delete|" + portalId, "Delete", true)),
                    new AdminPanelRegistry.EntryPreview("portal", portal.shape().serializedName(),
                            portal.colorRgb()));
            if (locationCount > 0 && PortalService.isOpen(portal)) active.add(in("Active · placed and open", entry));
            all.put(portalId, entry);
        }
        entries.addAll(active);
        entries.addAll(AdminCategories.sectioned(server, "portal", "All portals", all, PORTALS));
        if (all.isEmpty()) entries.add(in("All portals", AdminPanelRegistry.AdminEntry.info("No portals defined",
                "Create a portal, then add as many locations as needed.")));
        return new AdminPanelRegistry.AdminPage(PORTALS, "Portals",
                PortalService.portals(server).size() + " portal definition(s)", TABS, entries,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage buildingsPage(ServerPlayer player) {
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(in("Create", new AdminPanelRegistry.AdminEntry("Create building",
                "Select a built block range and save an independent copy.", GOOD, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Create", BUILDING_NEW)), null)));
        Map<String, AdminPanelRegistry.AdminEntry> all = new java.util.LinkedHashMap<>();
        for (BuildingService.BuildingInfo building : BuildingService.buildings(player.level().getServer())) {
            all.put(building.id().toString(), (new AdminPanelRegistry.AdminEntry(building.id().toString(),
                    building.sizeX() + " × " + building.sizeY() + " × " + building.sizeZ()
                            + " blocks · by " + building.author(), NORMAL, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", BUILDINGS + "/edit/" + building.id()),
                            new AdminPanelRegistry.AdminAction("building.delete|" + building.id(),
                            "Delete", true)), null)));
        }
        entries.addAll(AdminCategories.sectioned(player.level().getServer(), "building", "All buildings", all, BUILDINGS));
        return new AdminPanelRegistry.AdminPage(BUILDINGS, "Buildings",
                "Saved building copies for scripts", TABS, entries,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    /** A saved building: its details, and replacing its blocks with a new selection. */
    private static AdminPanelRegistry.AdminPage buildingEditorPage(ServerPlayer player, Identifier id) {
        BuildingService.BuildingInfo building = BuildingService.buildings(player.level().getServer()).stream()
                .filter(info -> info.id().equals(id)).findFirst().orElse(null);
        if (building == null) return buildingsPage(player);
        var selection = AreaSelectionService.selection(player).filter(value ->
                BuildingService.SELECTION_OWNER.equals(value.owner())).orElse(null);
        String status = selection == null ? "Select corners to choose the new blocks"
                : selection.complete() ? selection.minimum().toShortString() + " to "
                        + selection.maximum().toShortString() + " · " + selection.volume() + " blocks"
                        : "Select both corners in the world";
        List<AdminPanelRegistry.AdminEntry> entries = List.of(
                AdminPanelRegistry.AdminEntry.editor("Building", "Identity",
                        building.sizeX() + " × " + building.sizeY() + " × " + building.sizeZ() + " blocks · by "
                                + building.author(),
                        List.of(AdminPanelRegistry.AdminField.readOnly("build_id", "Building ID", id.toString())),
                        List.of()),
                AdminPanelRegistry.AdminEntry.editor("Replace blocks", "New block range", status,
                        List.of(), List.of(new AdminPanelRegistry.AdminAction("building.select", "Select corners", false))),
                AdminPanelRegistry.AdminEntry.editor("Replace blocks", "Save over this building",
                        "Scripts using this building place the new blocks from now on.", List.of(),
                        List.of(new AdminPanelRegistry.AdminAction("building.recapture|" + id, "Save new blocks", true))),
                AdminPanelRegistry.AdminEntry.editor("Actions", "Delete building",
                        "Only possible while no script uses it.", List.of(),
                        List.of(new AdminPanelRegistry.AdminAction("building.delete|" + id, "Delete", true),
                                AdminPanelRegistry.AdminAction.navigate("Back to buildings", BUILDINGS))));
        return new AdminPanelRegistry.AdminPage(BUILDINGS + "/edit/" + id, "Edit building", id.toString(), TABS,
                entries, AdminPanelRegistry.PageLayout.EDITOR);
    }

    private static AdminPanelRegistry.AdminPage newBuildingPage(ServerPlayer player) {
        var selection = AreaSelectionService.selection(player).filter(value ->
                BuildingService.SELECTION_OWNER.equals(value.owner())).orElse(null);
        String status = selection == null ? "No active selection"
                : selection.complete() ? selection.minimum().toShortString() + " to "
                        + selection.maximum().toShortString() + " · " + selection.volume() + " blocks"
                        : "Select both corners in the world";
        List<AdminPanelRegistry.AdminEntry> entries = List.of(
                AdminPanelRegistry.AdminEntry.editor("Building", "Identity",
                        "A permanent namespaced ID for this saved copy.",
                        List.of(textField("build_id", "Building ID", "", "cwp:arena_wall", 128, List.of())),
                        List.of()),
                AdminPanelRegistry.AdminEntry.editor("Building", "Block range", status,
                        List.of(), List.of(new AdminPanelRegistry.AdminAction("building.select", "Select corners", false))),
                AdminPanelRegistry.AdminEntry.editor("Actions", "Save copy",
                        "The saved building remains available after the original blocks are removed.",
                        List.of(), List.of(new AdminPanelRegistry.AdminAction("building.capture", "Save building", false),
                                AdminPanelRegistry.AdminAction.navigate("Cancel", BUILDINGS))));
        return new AdminPanelRegistry.AdminPage(BUILDING_NEW, "Create building",
                "Capture blocks from the current world", TABS, entries,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    // Build a dedicated, sectioned editor prefilled from one portal or sensible creation defaults.
    private static AdminPanelRegistry.AdminPage portalEditorPage(ServerPlayer player, Identifier requestedId) {
        MinecraftServer server = player.level().getServer();
        PortalDefinition portal = requestedId == null ? null : PortalService.portal(server, requestedId);
        if (requestedId != null && portal == null) return portalsPage(player);
        boolean creating = portal == null;
        String id = creating ? "" : portal.id().toString();
        List<String> templates = templateSuggestions(server);
        String template = creating ? templates.stream().filter(value -> !value.equals("Exit") && !value.equals("Coordinates"))
                .findFirst().orElse("Exit") : portal.isExit() ? "Exit" : portal.templateId().toString();
        if (!creating && portal.isCoordinates()) template = "Coordinates";
        Identifier currentPocket = PocketEditorService.pocketForDimension(server, player.level().dimension().identifier());
        if (creating && currentPocket != null) template = "Exit";
        PortalDefinition.Schedule schedule = creating ? PortalDefinition.Schedule.always() : portal.schedule();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Portal ID",
                creating ? "A permanent namespaced identifier, for example cwp:forgotten_vault_gate."
                        : "Portal IDs remain fixed after creation.",
                List.of(creating
                        ? textField("portal_id", "Portal ID", id, "cwp:forgotten_vault_gate", 128, List.of())
                        : AdminPanelRegistry.AdminField.readOnly("portal_id", "Portal ID", id)),
                List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Identity",
                "Choose a pocket, Exit to return to the main world, or Coordinates for direct travel in this world.",
                List.of(textField("portal_name", "Name", creating ? "" : portal.effectiveDisplayName(),
                                "Forgotten Vault", 96, List.of()),
                        templateField(template, templates)), List.of()));
        PortalDefinition.CoordinateTarget target = creating
                ? defaultCoordinateTarget(player.getX(), player.getY(), player.getZ(), PortalDefinition.Plane.XY)
                : portal.coordinateTarget().orElseGet(() -> defaultCoordinateTarget(
                        portal.centerX(), portal.centerY(), portal.centerZ(), portal.plane()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Destination coordinates",
                "Direct travel in the portal's world. Pocket editor coordinates move with the active instance.",
                List.of(textField("portal_target_x", "Destination X", decimal(target.x()), "0", 24, List.of()),
                        textField("portal_target_y", "Destination Y", decimal(target.y()), "64", 24, List.of()),
                        textField("portal_target_z", "Destination Z", decimal(target.z()), "0", 24, List.of())),
                List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Description",
                "Shown in the Minecraft-style travel confirmation window.",
                List.of(textField("portal_description", "Description", creating ? "" : portal.description(),
                        "An unstable passage into a forgotten place.", 512, List.of())), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Availability",
                "Controls whether the portal's saved locations are active.",
                List.of(AdminPanelRegistry.AdminField.select("portal_base_active", "Saved locations active",
                        creating || portal.baseActive() ? "Yes" : "No", List.of("Yes", "No"))), List.of()));
        if (!creating) {
            entries.add(AdminPanelRegistry.AdminEntry.editor("Locations", "Add location",
                    "Place another copy of this portal at your current position.",
                    List.of(textField("portal_location_name", "Location ID", "", "main_gate", 48, List.of())),
                    List.of(new AdminPanelRegistry.AdminAction("portal.location.add|" + id,
                            "Add at my position", false))));
            PortalService.locations(server, portal.id()).forEach((name, location) ->
                    entries.add(AdminPanelRegistry.AdminEntry.editor("Locations", name,
                            location.dimension() + " · " + decimal(location.x()) + ", "
                                    + decimal(location.y()) + ", " + decimal(location.z()), List.of(),
                            List.of(new AdminPanelRegistry.AdminAction("portal.location.teleport|" + id + "|" + name,
                                            "Teleport", false),
                                    new AdminPanelRegistry.AdminAction("portal.location.remove|" + id + "|" + name,
                                            "Remove", true)))));
        }
        entries.add(AdminPanelRegistry.AdminEntry.editor("Style", "Shape and color",
                "The preview and in-world surface use these settings.",
                List.of(AdminPanelRegistry.AdminField.select("portal_shape", "Shape",
                                creating ? "Rectangle" : title(portal.shape().serializedName()), SHAPES),
                        AdminPanelRegistry.AdminField.select("portal_color", "Color",
                                creating ? "Purple" : PortalDefinition.PortalColor.displayName(portal.colorRgb()),
                                PORTAL_COLORS)), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Style", "Size",
                "Width and height are measured in blocks.",
                List.of(textField("portal_width", "Width", creating ? "4" : decimal(portal.width()), "4", 12,
                                List.of()),
                        textField("portal_height", "Height", creating ? "6" : decimal(portal.height()), "6", 12,
                                List.of())), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Style", "Orientation",
                "Choose which world plane contains the two-dimensional portal.",
                List.of(AdminPanelRegistry.AdminField.select("portal_plane", "Orientation",
                        creating ? PLANES.getFirst() : planeLabel(portal.plane()), PLANES)), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Difficulty and instance",
                "Lifetime is in minutes. Zero keeps a public instance until an admin expires it.",
                List.of(AdminPanelRegistry.AdminField.select("portal_difficulty", "Difficulty",
                                (creating ? 1 : portal.difficulty()) + (creating || portal.difficulty() == 1
                                        ? " skull" : " skulls"), DIFFICULTIES),
                        textField("portal_lifetime", "Lifetime minutes (0 = permanent public)",
                                Long.toString((creating ? 5_400L : portal.lifetimeSeconds()) / 60L), "90", 10,
                                List.of())), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Entry policy",
                "Choose whether a player may revisit this portal.",
                List.of(AdminPanelRegistry.AdminField.select("portal_entry_policy", "Entry policy",
                        creating ? "Repeatable" : entryPolicyLabel(portal.entryPolicy()), ENTRY_POLICIES)), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Instance access",
                "Public uses one shared instance for everyone. A Dungeon gives each party its own instance; the party limit applies only there.",
                creating || portal.admission().mode() == PortalDefinition.Mode.PRIVATE
                        ? List.of(AdminPanelRegistry.AdminField.select("portal_access_mode", "Access",
                                        creating ? "Public" : "Dungeon", ACCESS_MODES),
                                textField("portal_max_players", "Dungeon party limit (ignored for public)",
                                        Integer.toString(creating ? 4 : portal.admission().maxPlayers()), "4", 3,
                                        List.of()))
                        : List.of(AdminPanelRegistry.AdminField.select("portal_access_mode", "Access",
                                "Public", ACCESS_MODES)), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Raid attempt",
                "Raids are dungeons with strict encounters: one revive each, and a wipe resets the encounter for another try.",
                List.of(AdminPanelRegistry.AdminField.select("portal_raid_mode", "Raid mode",
                        !creating && portal.raidMode() ? "Yes" : "No", List.of("Yes", "No"))), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Building",
                "Allow players to place and break blocks in instances opened by this portal.",
                List.of(AdminPanelRegistry.AdminField.select("portal_allow_building", "Build in instance",
                        creating || portal.allowBuilding() ? "Yes" : "No", List.of("Yes", "No"))), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Keep inventory",
                "If disabled, players drop their inventory on death in this portal's instance. Entry requires two confirmations.",
                List.of(AdminPanelRegistry.AdminField.select("portal_keep_inventory", "Keep inventory on death",
                        creating || portal.keepInventory() ? "Yes" : "No", List.of("Yes", "No"))), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Other players in public instances",
                "When hidden, each player can still choose to show others in their own ECHELON settings.",
                List.of(AdminPanelRegistry.AdminField.select("portal_show_players", "Show other players",
                        creating || portal.showOtherPlayers() ? "Yes" : "No", List.of("Yes", "No"))), List.of()));
        PortalDefinition.Environment environment = creating
                ? PortalDefinition.Environment.defaults() : portal.environment();
        entries.add(AdminPanelRegistry.AdminEntry.editor("Environment", "Day and night",
                "Each instance has its own clock. Daytime sets the starting time; disabling the cycle freezes it.",
                List.of(AdminPanelRegistry.AdminField.select("portal_daytime", "Daytime",
                                title(environment.daytime().serializedName()), DAYTIMES),
                        AdminPanelRegistry.AdminField.select("portal_day_night_cycle", "Day/night cycle",
                                environment.dayNightCycle() ? "Yes" : "No", List.of("Yes", "No"))), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Environment", "Weather",
                "Weather begins at the selected state. Disabling the cycle keeps that state.",
                List.of(AdminPanelRegistry.AdminField.select("portal_weather", "Weather",
                                title(environment.weather().serializedName()), WEATHER_STATES),
                        AdminPanelRegistry.AdminField.select("portal_weather_cycle", "Weather cycle",
                                environment.weatherCycle() ? "Yes" : "No", List.of("Yes", "No"))), List.of()));
        if (!creating) addScheduleEditor(entries, schedule);
        String saveAction = creating ? "portal.create" : "";
        entries.add(AdminPanelRegistry.AdminEntry.editor("Actions",
                creating ? "Create portal" : "Save",
                creating ? "Creates the definition. Add locations from its editor afterward."
                        : "Saves every change on this page.",
                List.of(),
                creating ? List.of(new AdminPanelRegistry.AdminAction(saveAction, "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Cancel", PORTALS))
                        : List.of(new AdminPanelRegistry.AdminAction("portal.apply|" + id, "Save", false),
                                AdminPanelRegistry.AdminAction.navigate("Back to portals", PORTALS))));
        return new AdminPanelRegistry.AdminPage(creating ? PORTAL_NEW : PORTAL_EDIT + id,
                creating ? "Create portal" : "Edit · " + portal.effectiveDisplayName(),
                creating ? "General, style, gameplay, then optional time gates"
                        : portal.id() + " · linked to "
                        + (portal.isExit() ? "Exit" : portal.isCoordinates() ? "Coordinates" : portal.templateId()),
                TABS, entries,
                AdminPanelRegistry.PageLayout.EDITOR);
    }

    // Append periodic and calendar scheduling controls to an existing portal editor.
    private static void addScheduleEditor(List<AdminPanelRegistry.AdminEntry> entries,
                                          PortalDefinition.Schedule schedule) {
        if (schedule.periodSeconds() == 0L && !schedule.calendarEnabled()) {
            entries.add(AdminPanelRegistry.AdminEntry.info("No time settings",
                    "This portal is available at any time."));
        }
        entries.add(AdminPanelRegistry.AdminEntry.editor("Time settings", "Repeating window",
                schedule.periodSeconds() == 0L ? "Not added. Add a 30-minute opening every 240 minutes."
                        : "Opens for the configured minutes in each cycle.",
                schedule.periodSeconds() == 0L ? List.of()
                        : List.of(textField("period_minutes", "Every minutes",
                                        Long.toString(schedule.periodSeconds() / 60L), "240", 10, List.of()),
                                textField("open_minutes", "Open minutes",
                                        Long.toString(schedule.openSeconds() / 60L), "30", 10, List.of())),
                List.of(new AdminPanelRegistry.AdminAction(schedule.periodSeconds() == 0L
                        ? "portal.periodic.add" : "portal.periodic.clear",
                        schedule.periodSeconds() == 0L ? "Add" : "Remove", false))));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Time settings", "Calendar window",
                schedule.calendarEnabled() ? "Entry follows these local weekdays and times."
                        : "Not added. Add a daily 09:00-17:00 UTC window, then edit it.",
                !schedule.calendarEnabled() ? List.of()
                        : List.of(textField("calendar_zone", "Timezone", schedule.timezone().getId(),
                                        "Europe/Berlin", 64, List.of("Europe/Berlin", "UTC")),
                                textField("calendar_days", "Weekdays", schedule.weekdays().stream().sorted()
                                        .map(DayOfWeek::name).collect(Collectors.joining(",")),
                                        "FRIDAY", 96, List.of()),
                                textField("calendar_start", "Start", schedule.start().toString(), "09:00", 8,
                                        List.of()),
                                textField("calendar_end", "End", schedule.end().toString(), "17:00", 8,
                                        List.of())),
                List.of(new AdminPanelRegistry.AdminAction(schedule.calendarEnabled()
                        ? "portal.calendar.clear" : "portal.calendar.add",
                        schedule.calendarEnabled() ? "Remove" : "Add", false))));
    }

    // Keep registered currencies in a compact list; open each wallet's controls individually.
    private static AdminPanelRegistry.AdminPage currenciesPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        Map<String, AdminPanelRegistry.AdminEntry> all = new java.util.LinkedHashMap<>();
        for (CurrencyDefinition currency : CurrencyRegistry.values()) {
            long balance = CurrencyService.getBalance(server, player.getUUID(), currency.id());
            String detail = currency.id() + " · " + currency.scope().name().toLowerCase()
                    + " · " + (currency.scope() == CurrencyDefinition.CurrencyScope.GLOBAL ? "shared" : "your")
                    + " balance " + balance + " " + currency.abbreviation();
            all.put(currency.id().toString(), (new AdminPanelRegistry.AdminEntry(currency.displayName(), detail, NORMAL,
                    List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Edit", CURRENCY_EDIT + currency.id())),
                    null)));
        }
        entries.addAll(AdminCategories.sectioned(server, "currency", "All currencies", all, CURRENCIES));
        if (entries.isEmpty()) entries.add(AdminPanelRegistry.AdminEntry.info("No currencies",
                "Dependent mods have not registered any currency definitions."));
        return new AdminPanelRegistry.AdminPage(CURRENCIES, "Currencies", "Registered wallets", TABS, entries,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage currencyEditorPage(ServerPlayer player, Identifier id) {
        CurrencyDefinition currency = CurrencyRegistry.require(id);
        MinecraftServer server = player.level().getServer();
        List<String> players = server.getPlayerList().getPlayers().stream()
                .map(target -> target.getName().getString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(AdminPanelRegistry.AdminEntry.editor("Currency", "Definition",
                "Currency definitions come from content mods; this editor manages their balances.",
                List.of(AdminPanelRegistry.AdminField.readOnly("currency_id", "ID", id.toString()),
                        AdminPanelRegistry.AdminField.readOnly("currency_scope", "Scope", currency.scope().name()),
                        AdminPanelRegistry.AdminField.readOnly("currency_abbreviation", "Symbol", currency.abbreviation())),
                List.of()));
        if (currency.scope() == CurrencyDefinition.CurrencyScope.PLAYER) {
            entries.add(AdminPanelRegistry.AdminEntry.editor("Wallet", "Player", "Select an online player.",
                    List.of(new AdminPanelRegistry.AdminField("currency_player", "Player", "",
                            player.getName().getString(), 64, players)), List.of()));
        }
        entries.add(AdminPanelRegistry.AdminEntry.editor("Wallet", "Amount",
                "Current " + (currency.scope() == CurrencyDefinition.CurrencyScope.GLOBAL ? "shared" : "your")
                        + " balance: " + CurrencyService.getBalance(server, player.getUUID(), id),
                List.of(new AdminPanelRegistry.AdminField("currency_amount", "Amount", "", "1", 20, List.of())),
                List.of(new AdminPanelRegistry.AdminAction("currency.credit|" + id, "Give", false),
                        new AdminPanelRegistry.AdminAction("currency.debit|" + id, "Take", false),
                        new AdminPanelRegistry.AdminAction("currency.set|" + id, "Set", true))));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Navigation", "Currencies", "Return to the currency list.",
                List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Back", CURRENCIES))));
        return new AdminPanelRegistry.AdminPage(CURRENCY_EDIT + id, currency.displayName(), id.toString(), TABS,
                entries, AdminPanelRegistry.PageLayout.EDITOR);
    }

    // Show loaded trees and offer reload and live attribute refresh operations.
    private static AdminPanelRegistry.AdminPage skillTreesPage(ServerPlayer player) {
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(new AdminPanelRegistry.AdminEntry("Skill-tree configuration",
                "Reload YAML definitions or recalculate attributes for every online player.", NORMAL,
                List.of(new AdminPanelRegistry.AdminAction("skills.reload", "Reload YAML", false),
                        new AdminPanelRegistry.AdminAction("skills.refresh", "Refresh stats", false))));
        for (SkillTreeDefinition tree : SkillTreeManager.getSkillTrees()) {
            entries.add(AdminPanelRegistry.AdminEntry.info(tree.name(), tree.id() + " · "
                    + tree.type().name().toLowerCase() + " · priority " + tree.priority() + " · "
                    + tree.skills().size() + " nodes"));
        }
        return page(SKILL_TREES, "Skill trees", SkillTreeManager.getSkillTrees().size() + " loaded tree(s)", entries);
    }

    // List configured events and expose force-start/stop buttons with active-state feedback.
    private static AdminPanelRegistry.AdminPage mobForgePage(ServerPlayer player) {
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(new AdminPanelRegistry.AdminEntry("Mobs", MobForge.TEMPLATES.all().size()
                + " editable YAML mobs", GOOD, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Manage mobs", MOB_MOBS)), null));
        entries.add(new AdminPanelRegistry.AdminEntry("Scripts", MobForge.SCRIPTS.all().size()
                + " encounter scripts", GOOD, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("Manage scripts", MOB_SCRIPTS)), null));
        entries.add(new AdminPanelRegistry.AdminEntry("Registered API mobs", MobForge.mobs().all().size()
                + " content mod mobs", NORMAL, List.of(),
                List.of(AdminPanelRegistry.AdminAction.navigate("View", MOB_API)), null));
        entries.add(new AdminPanelRegistry.AdminEntry("Reload MobForge YAML",
                "Reload templates and scripts from config/mobforge.", NORMAL,
                List.of(new AdminPanelRegistry.AdminAction("mob.reload", "Reload", false))));
        return new AdminPanelRegistry.AdminPage(MOBFORGE, "MobForge", "Mobs and encounter scripts", TABS,
                entries, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage mobMobsPage(MinecraftServer server) {
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(in("Create", new AdminPanelRegistry.AdminEntry("Create mob", "Add a MobForge mob.", GOOD,
                List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Create", MOB_EDIT + "@new")), null)));
        entries.add(in("Create", new AdminPanelRegistry.AdminEntry("Reload from files",
                "Reload mobs and scripts from config/mobforge.", NORMAL, List.of(),
                List.of(new AdminPanelRegistry.AdminAction("mob.reload", "Reload", false)), null)));
        Map<String, AdminPanelRegistry.AdminEntry> all = new java.util.LinkedHashMap<>();
        for (MobTemplate template : MobForge.TEMPLATES.all()) {
            all.put(template.id(), (new AdminPanelRegistry.AdminEntry(template.id(),
                    template.entity() + " · " + template.ai().mode() + " AI",
                    NORMAL, List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Edit", MOB_EDIT + template.id()),
                            new AdminPanelRegistry.AdminAction("mob.template.delete|" + template.id(),
                                    "Delete", true)), null)));
        }
        entries.addAll(AdminCategories.sectioned(server, "mob", "All mobs", all, MOB_MOBS));
        if (!MobForge.mobs().all().isEmpty()) entries.add(in("Registered by mods", new AdminPanelRegistry.AdminEntry(
                "Content mod mobs", MobForge.mobs().all().size() + " mob(s) registered in code, read only", NORMAL,
                List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("View", MOB_API)), null)));
        return new AdminPanelRegistry.AdminPage(MOB_MOBS, "Mobs", MobForge.TEMPLATES.all().size() + " mob(s)", TABS,
                entries, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage mobScriptsPage(MinecraftServer server) {
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(in("Create", new AdminPanelRegistry.AdminEntry("Create script", "Add an encounter timeline.", GOOD,
                List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Create", MOB_SCRIPT_EDIT + "@new")), null)));
        List<AdminPanelRegistry.AdminEntry> active = new ArrayList<>();
        Map<String, AdminPanelRegistry.AdminEntry> all = new java.util.LinkedHashMap<>();
        for (MobScript script : MobForge.SCRIPTS.all()) {
            int runs = MobForge.SCRIPT_EXECUTOR.activeRuns(script.id());
            AdminPanelRegistry.AdminEntry entry = new AdminPanelRegistry.AdminEntry(script.id(),
                    script.steps().size() + " steps · " + runs + " running", runs > 0 ? GOOD : NORMAL, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", MOB_SCRIPT_EDIT + script.id()),
                            new AdminPanelRegistry.AdminAction("mob.script.delete|" + script.id(), "Delete", true)), null);
            if (runs > 0) active.add(in("Active · running now", entry));
            all.put(script.id(), entry);
        }
        entries.addAll(active);
        entries.addAll(AdminCategories.sectioned(server, "script", "All scripts", all, MOB_SCRIPTS));
        return new AdminPanelRegistry.AdminPage(MOB_SCRIPTS, "Scripts", "Encounter scripts", TABS,
                entries, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage mobApiPage() {
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        for (var definition : MobForge.mobs().all().values().stream()
                .sorted(java.util.Comparator.comparing(mob -> mob.id().toString())).toList()) {
            entries.add(new AdminPanelRegistry.AdminEntry("API mob: " + definition.id(),
                    "Content mod registration · " + definition.abilities().size() + " abilities · "
                            + definition.behaviors().size() + " behaviors · tags " + definition.tags(),
                    NORMAL, List.of(new AdminPanelRegistry.AdminAction("mob.api.spawn|" + definition.id(),
                            "Spawn here", false))));
        }
        entries.add(AdminPanelRegistry.AdminEntry.info("Registered components",
                MobForge.abilities().all().size() + " abilities · "
                        + MobForge.behaviors().all().size() + " behaviors. Content mods register these during startup."));
        if (!MobForge.abilities().all().isEmpty()) {
            entries.add(AdminPanelRegistry.AdminEntry.info("Ability IDs",
                    MobForge.abilities().all().keySet().stream().map(Object::toString).sorted()
                            .collect(Collectors.joining(", "))));
        }
        if (!MobForge.behaviors().all().isEmpty()) {
            entries.add(AdminPanelRegistry.AdminEntry.info("Behavior IDs",
                    MobForge.behaviors().all().keySet().stream().map(Object::toString).sorted()
                            .collect(Collectors.joining(", "))));
        }
        entries.add(new AdminPanelRegistry.AdminEntry("Navigation", "Back to mobs", NORMAL,
                List.of(), List.of(AdminPanelRegistry.AdminAction.navigate("Back", MOB_MOBS)), null));
        return new AdminPanelRegistry.AdminPage(MOB_API, "MobForge API", "Content mod registrations are read only",
                TABS, entries, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    private static AdminPanelRegistry.AdminPage eventsPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        List<AdminPanelRegistry.AdminEntry> running = new ArrayList<>();
        for (RandomEventDefinition event : RandomEventManager.definitions()) {
            boolean active = RandomEventService.isActive(server, event.id());
            AdminPanelRegistry.AdminEntry entry = new AdminPanelRegistry.AdminEntry(event.id().toString(),
                    event.trigger().getClass().getSimpleName().replace("Trigger", "") + " · "
                    + (RandomEventRegistry.hasHandler(event.id()) ? "handler ready" : "missing handler") + " · "
                    + (active ? "ACTIVE" : "inactive"), active ? GOOD : NORMAL,
                    List.of(new AdminPanelRegistry.AdminAction(
                            (active ? "event.stop|" : "event.start|") + event.id(),
                            active ? "Stop" : "Start", active)));
            if (active) running.add(in("Active · running now", entry));
            entries.add(in("All events", entry));
        }
        entries.addAll(0, running);
        if (entries.isEmpty()) entries.add(AdminPanelRegistry.AdminEntry.info("No events loaded",
                "Register a random-event YAML source from a content mod."));
        return new AdminPanelRegistry.AdminPage(EVENTS, "Random events",
                RandomEventService.isBlackoutActive() ? "A blackout window is active" : "Random starts are allowed",
                TABS, entries, AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    // Execute one native action after the networking layer has authenticated the administrator.
    private static AdminPanelRegistry.ActionResult action(ServerPlayer player, String pageId, String actionId,
                                                          Map<String, String> values) {
        if (AdminLootPoolEditor.owns(pageId) && actionId.startsWith("loot."))
            return AdminLootPoolEditor.action(player, actionId, values);
        MinecraftServer server = player.level().getServer();
        if (AdminCategories.owns(pageId)) return AdminCategories.action(player, pageId, actionId, values);
        if (actionId.startsWith("mob.template.delete|")) {
            String id = actionId.substring(actionId.indexOf('|') + 1);
            try {
                return MobForge.TEMPLATES.delete(id) ? AdminPanelRegistry.ActionResult.success("Deleted mob " + id)
                        : AdminPanelRegistry.ActionResult.failure("Unknown mob " + id);
            } catch (IOException exception) {
                return AdminPanelRegistry.ActionResult.failure("Could not delete mob: " + exception.getMessage());
            }
        }
        if (actionId.startsWith("mob.script.delete|")) {
            String id = actionId.substring(actionId.indexOf('|') + 1);
            try {
                if (!MobForge.SCRIPTS.delete(id)) return AdminPanelRegistry.ActionResult.failure("Unknown script " + id);
            } catch (IOException exception) {
                return AdminPanelRegistry.ActionResult.failure("Could not delete script: " + exception.getMessage());
            }
            PocketEditorService.detachScriptEverywhere(server, id);
            return AdminPanelRegistry.ActionResult.success("Deleted script " + id);
        }
        if (actionId.equals("logs.toggle")) {
            LOG_PROBLEMS_ONLY.merge(player.getUUID(), true, (old, ignored) -> !old);
            return AdminPanelRegistry.ActionResult.success("");
        }
        if (actionId.equals("logs.clear")) {
            AdminLog.clear();
            return AdminPanelRegistry.ActionResult.success("Cleared the log view");
        }
        if (actionId.equals("player.tp_name")) {
            String name = values.getOrDefault("player_name", "").trim();
            ServerPlayer target = server.getPlayerList().getPlayerByName(name);
            if (target == null) return AdminPanelRegistry.ActionResult.failure("No online player named " + name);
            player.teleportTo(target.level(), target.getX(), target.getY(), target.getZ(), java.util.Set.of(),
                    player.getYRot(), player.getXRot(), false);
            return AdminPanelRegistry.ActionResult.success("Teleported to " + target.getGameProfile().name());
        }
        if (actionId.startsWith("player.tp|") || actionId.startsWith("player.out|")) {
            ServerPlayer target = server.getPlayerList().getPlayer(
                    java.util.UUID.fromString(actionId.substring(actionId.indexOf('|') + 1)));
            if (target == null) return AdminPanelRegistry.ActionResult.failure("That player is offline");
            String name = target.getGameProfile().name();
            if (actionId.startsWith("player.tp|")) {
                player.teleportTo(target.level(), target.getX(), target.getY(), target.getZ(), java.util.Set.of(),
                        player.getYRot(), player.getXRot(), false);
                return AdminPanelRegistry.ActionResult.success("Teleported to " + name);
            }
            return PortalService.sendOut(target)
                    ? AdminPanelRegistry.ActionResult.success("Sent " + name + " out")
                    : AdminPanelRegistry.ActionResult.failure(name + " is not in an instance or workshop");
        }
        ScriptRoute scriptRoute = scriptRoute(pageId);
        if (scriptRoute != null) return AdminScriptEditor.action(player, pageId, actionId, values,
                scriptRoute.backPage(), scriptRoute.pocketId(), scriptRoute.scriptId());
        if (pageId.startsWith(MOB_EDIT)) return AdminMobEditor.action(player, pageId,
                pageId.substring(MOB_EDIT.length()), actionId, values, MOB_MOBS);
        if (actionId.equals("reload")) return reload(server);
        if (actionId.equals("mob.reload")) {
            var templates = MobForge.TEMPLATES.reload();
            var scripts = MobForge.SCRIPTS.reload();
            return AdminPanelRegistry.ActionResult.success("Reloaded " + templates.loaded() + " mobs and "
                    + scripts.loaded() + " scripts; " + (templates.failed() + scripts.failed()) + " failed");
        }
        if (actionId.equals("mob.template.new")) return AdminPanelRegistry.ActionResult.success("", MOB_EDIT + "@new");
        if (actionId.equals("mob.script.new")) {
            return AdminPanelRegistry.ActionResult.success("", MOB_SCRIPT_EDIT + "@new");
        }
        if (actionId.equals("pocket.create")) {
            if (!pageId.equals(POCKET_NEW)) throw new IllegalArgumentException("Open Create pocket first");
            Identifier id = formId(values, "template_id");
            PocketEditorService.create(server, id);
            return AdminPanelRegistry.ActionResult.success("Created pocket " + id, POCKET_EDIT + id);
        }
        if (actionId.equals("building.select")) {
            AreaSelectionService.begin(player, BuildingService.SELECTION_OWNER);
            return AdminPanelRegistry.ActionResult.success("Select the first and second corners in the world");
        }
        if (actionId.startsWith("building.recapture|")) {
            Identifier id = Identifier.parse(actionId.substring(actionId.indexOf('|') + 1));
            BuildingService.capture(player, id, true);
            return AdminPanelRegistry.ActionResult.success("Saved new blocks for " + id, BUILDINGS + "/edit/" + id);
        }
        if (actionId.equals("building.capture")) {
            Identifier id = formId(values, "build_id");
            BuildingService.capture(player, id);
            return AdminPanelRegistry.ActionResult.success("Saved building " + id, BUILDINGS);
        }
        if (actionId.equals("pocket.enter")) {
            Identifier id = pocketPageId(pageId);
            AreaSelectionService.clear(player);
            PortalService.enterPocketEditor(player, id);
            return AdminPanelRegistry.ActionResult.success("Entered pocket " + id);
        }
        if (actionId.equals("pocket.leave")) {
            pocketPageId(pageId);
            return PortalService.leavePocketEditor(player)
                    ? AdminPanelRegistry.ActionResult.success("Left pocket editor")
                    : AdminPanelRegistry.ActionResult.failure("You are not in a pocket editor");
        }
        if (actionId.equals("pocket.spawn.current")) {
            Identifier id = pocketPageId(pageId);
            requirePocketEditor(player, id);
            PocketEditorService.setEntry(server, id, player.blockPosition());
            return AdminPanelRegistry.ActionResult.success("Pocket spawn set to your position");
        }
        if (actionId.equals("pocket.script.attach")) {
            Identifier id = pocketPageId(pageId);
            String script = required(values, "pocket_script");
            return PocketEditorService.attachScript(server, id, script)
                    ? AdminPanelRegistry.ActionResult.success("Attached " + script + " to " + id)
                    : AdminPanelRegistry.ActionResult.success(script + " is already attached");
        }
        if (actionId.equals("draft.save")) return saveDraft(player, pocketPageId(pageId), values);
        if (actionId.equals("portal.create")) return createPortal(player, values);
        if (actionId.equals("portal.position.current")) {
            return AdminPanelRegistry.ActionResult.success("Coordinates set to your current position");
        }
        if (actionId.equals("portal.update")) return updatePortal(player, formId(values, "portal_id"), values);
        if (actionId.equals("portal.move")) {
            Identifier id = formId(values, "portal_id");
            PortalDefinition portal = PortalService.portal(server, id);
            if (portal == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
            PortalService.setLocation(server, id, "original", player.level().dimension().identifier(),
                    player.position().add(0, 0.9, 0));
            return AdminPanelRegistry.ActionResult.success("Set original location for " + id);
        }
        if (actionId.equals("portal.remove")) {
            Identifier id = formId(values, "portal_id");
            return PortalService.remove(server, id)
                    ? AdminPanelRegistry.ActionResult.success("Removed portal " + id)
                    : AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        }
        if (actionId.startsWith("portal.periodic") || actionId.startsWith("portal.calendar")) {
            return updatePortalSchedule(server, values, actionId);
        }
        if (actionId.equals("skills.reload")) return reloadSkills(server);
        if (actionId.equals("skills.refresh")) {
            SkillStatRegistry.refreshAll(server.getPlayerList().getPlayers());
            return AdminPanelRegistry.ActionResult.success("Refreshed stats for all online players");
        }
        String[] parts = actionId.split("\\|", -1);
        if (parts.length == 2 && parts[0].equals("building.delete")) {
            Identifier id = Identifier.parse(parts[1]);
            try {
                return BuildingService.delete(server, id)
                        ? AdminPanelRegistry.ActionResult.success("Deleted building " + id, BUILDINGS)
                        : AdminPanelRegistry.ActionResult.failure("Unknown building " + id);
            } catch (IOException exception) {
                return AdminPanelRegistry.ActionResult.failure("Could not delete building: " + exception.getMessage());
            }
        }
        if (parts.length == 3 && parts[0].equals("portal.location.remove")) {
            Identifier id = Identifier.parse(parts[1]);
            return PortalService.removeLocation(server, id, parts[2])
                    ? AdminPanelRegistry.ActionResult.success("Removed location " + parts[2], PORTAL_EDIT + id)
                    : AdminPanelRegistry.ActionResult.failure("Unknown location " + parts[2]);
        }
        if (parts.length == 3 && parts[0].equals("portal.location.teleport")) {
            return teleportToPortal(player, Identifier.parse(parts[1]), parts[2]);
        }
        if (parts.length == 2 && parts[0].equals("portal.location.add")) {
            Identifier id = Identifier.parse(parts[1]);
            String name = required(values, "portal_location_name").trim();
            PortalService.addLocation(server, id, name, player.level().dimension().identifier(),
                    player.position().add(0, 0.9, 0));
            return AdminPanelRegistry.ActionResult.success("Added location " + name, PORTAL_EDIT + id);
        }
        if (parts.length == 2 && parts[0].equals("pocket.script.remove")) {
            Identifier id = pocketPageId(pageId);
            return PocketEditorService.removeScript(server, id, parts[1])
                    ? AdminPanelRegistry.ActionResult.success("Removed " + parts[1] + " from " + id)
                    : AdminPanelRegistry.ActionResult.failure("Script is not attached to this pocket");
        }
        if (parts.length == 2 && parts[0].equals("pocket.delete")) {
            Identifier id = Identifier.parse(parts[1]);
            try {
                return PocketEditorService.delete(server, id)
                        ? AdminPanelRegistry.ActionResult.success("Deleted pocket " + id)
                        : AdminPanelRegistry.ActionResult.failure("Unknown pocket " + id);
            } catch (IOException exception) {
                return AdminPanelRegistry.ActionResult.failure("Could not delete pocket: " + exception.getMessage());
            }
        }
        if (parts.length == 2 && parts[0].equals("mob.template.edit")) {
            if (MobForge.TEMPLATES.get(parts[1]).isEmpty())
                return AdminPanelRegistry.ActionResult.failure("Unknown mob template " + parts[1]);
            return AdminPanelRegistry.ActionResult.success("", MOB_EDIT + parts[1]);
        }
        if (parts.length == 2 && parts[0].equals("mob.script.edit")) {
            MobScript script = MobForge.SCRIPTS.get(parts[1]).orElse(null);
            if (script == null) return AdminPanelRegistry.ActionResult.failure("Unknown MobForge script " + parts[1]);
            return AdminPanelRegistry.ActionResult.success("", MOB_SCRIPT_EDIT + script.id());
        }
        if (parts.length == 2 && parts[0].equals("mob.script.run")) {
            MobScript script = MobForge.SCRIPTS.get(parts[1]).orElse(null);
            if (script == null) return AdminPanelRegistry.ActionResult.failure("Unknown MobForge script " + parts[1]);
            UUID runId = MobForge.SCRIPT_EXECUTOR.run(script, (ServerLevel) player.level(),
                    Map.of("origin", player.position()), null, null);
            return AdminPanelRegistry.ActionResult.success("Started " + script.id() + " · run " + runId);
        }
        if (parts.length == 2 && (parts[0].equals("mob.template.spawn") || parts[0].equals("mob.api.spawn"))) {
            Identifier id;
            if (parts[0].equals("mob.template.spawn")) {
                if (MobForge.TEMPLATES.get(parts[1]).isEmpty()) {
                    return AdminPanelRegistry.ActionResult.failure("Unknown mob template " + parts[1]);
                }
                id = CrazyWorldProgression.id(parts[1]);
            } else {
                id = Identifier.parse(parts[1]);
                if (MobForge.mobs().get(id).isEmpty()) {
                    return AdminPanelRegistry.ActionResult.failure("Unknown API mob " + id);
                }
            }
            EntitySpawnService.spawn(id, SpawnContext.at((ServerLevel) player.level(), player.position()));
            return AdminPanelRegistry.ActionResult.success("Spawned " + id + " in "
                    + player.level().dimension().identifier());
        }
        if (parts.length == 2 && (parts[0].equals("portal.autosave") || parts[0].equals("portal.apply"))) {
            return patchPortal(player, Identifier.parse(parts[1]), values);
        }
        if (parts.length == 2 && (parts[0].equals("pocket.autosave") || parts[0].equals("pocket.save"))) {
            Identifier id = pocketPageId(pageId);
            if (!id.equals(Identifier.parse(parts[1]))) {
                return AdminPanelRegistry.ActionResult.failure("Pocket editor changed");
            }
            BlockPos current = PocketEditorService.entry(server, id);
            int x = values.containsKey("pocket_spawn_x")
                    ? Integer.parseInt(required(values, "pocket_spawn_x")) : current.getX();
            int y = values.containsKey("pocket_spawn_y")
                    ? Integer.parseInt(required(values, "pocket_spawn_y")) : current.getY();
            int z = values.containsKey("pocket_spawn_z")
                    ? Integer.parseInt(required(values, "pocket_spawn_z")) : current.getZ();
            PocketEditorService.setEntry(server, id, new BlockPos(x, y, z));
            return AdminPanelRegistry.ActionResult.success("Saved pocket " + id);
        }
        if (parts.length == 2 && parts[0].equals("portal.save")) {
            return updatePortal(player, Identifier.parse(parts[1]), values);
        }
        if (parts.length == 2 && parts[0].equals("portal.position")) {
            Identifier id = Identifier.parse(parts[1]);
            PortalDefinition portal = PortalService.portal(server, id);
            if (portal == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
            PortalService.setLocation(server, id, "original", player.level().dimension().identifier(),
                    player.position().add(0, 0.9, 0));
            return AdminPanelRegistry.ActionResult.success("Set original location for " + id);
        }
        if (parts.length == 2 && parts[0].equals("portal.teleport")) {
            return teleportToPortal(player, Identifier.parse(parts[1]));
        }
        if (parts.length == 2 && parts[0].equals("portal.delete")) {
            Identifier id = Identifier.parse(parts[1]);
            return PortalService.remove(server, id)
                    ? AdminPanelRegistry.ActionResult.success("Deleted portal " + id, PORTALS)
                    : AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        }
        if (parts.length == 2 && parts[0].equals("instance.expire")) {
            Identifier id = Identifier.parse(parts[1]);
            return PortalService.expireInstance(server, id)
                    ? AdminPanelRegistry.ActionResult.success("Expiring " + id)
                    : AdminPanelRegistry.ActionResult.failure("No active instance for " + id);
        }
        if (parts.length == 2 && (parts[0].equals("event.start") || parts[0].equals("event.stop"))) {
            Identifier id = Identifier.parse(parts[1]);
            boolean changed = parts[0].equals("event.start")
                    ? RandomEventService.startNow(server, id) : RandomEventService.stop(server, id);
            return changed ? AdminPanelRegistry.ActionResult.success(
                    (parts[0].equals("event.start") ? "Started " : "Stopped ") + id)
                    : AdminPanelRegistry.ActionResult.failure("Event state did not change for " + id);
        }
        if (parts.length == 2 && (parts[0].equals("currency.credit")
                || parts[0].equals("currency.debit") || parts[0].equals("currency.set"))) {
            Identifier currencyId = Identifier.parse(parts[1]);
            CurrencyDefinition currency = CurrencyRegistry.require(currencyId);
            long amount = nonNegativeLong(values, "currency_amount");
            UUID owner = currency.scope() == CurrencyDefinition.CurrencyScope.GLOBAL
                    ? player.getUUID() : onlinePlayer(server, required(values, "currency_player")).getUUID();
            if (parts[0].equals("currency.credit")) CurrencyService.credit(server, owner, currencyId, amount);
            else if (parts[0].equals("currency.debit")) CurrencyService.debit(server, owner, currencyId, amount);
            else CurrencyService.setBalance(server, owner, currencyId, amount);
            return AdminPanelRegistry.ActionResult.success(parts[0].substring("currency.".length())
                    + " applied to " + currency.displayName());
        }
        return AdminPanelRegistry.ActionResult.failure("Unknown CWP admin action: " + actionId);
    }

    // Reload every configuration-backed CWP subsystem in dependency order.
    private static AdminPanelRegistry.ActionResult reload(MinecraftServer server) {
        BaselineStatRegistry.reload();
        AdminPanelRegistry.ActionResult skills = reloadSkills(server);
        if (skills.error()) return skills;
        RandomEventManager.reload();
        int removed = RandomEventService.reconcileDefinitions(server);
        return AdminPanelRegistry.ActionResult.success("Reloaded CWP data" + (removed == 0 ? "" : " · ended " + removed
                + " removed event(s)"));
    }

    // Reload tree YAML and recalculate all affected online attributes.
    private static AdminPanelRegistry.ActionResult reloadSkills(MinecraftServer server) {
        try {
            SkillTreeManager.reload();
            SkillStatRegistry.refreshAll(server.getPlayerList().getPlayers());
            return AdminPanelRegistry.ActionResult.success("Reloaded skill trees and refreshed online stats");
        } catch (IOException exception) {
            return AdminPanelRegistry.ActionResult.failure("Skill-tree reload failed: " + exception.getMessage());
        }
    }

    // Capture all built chunks with an automatically chosen supported entry point.
    private static AdminPanelRegistry.ActionResult saveDraft(ServerPlayer player, Identifier id,
                                                             Map<String, String> values) {
        requirePocketEditor(player, id);
        BlockPos preferred = values.containsKey("pocket_spawn_x") && values.containsKey("pocket_spawn_y")
                && values.containsKey("pocket_spawn_z")
                ? new BlockPos(Integer.parseInt(required(values, "pocket_spawn_x")),
                        Integer.parseInt(required(values, "pocket_spawn_y")),
                        Integer.parseInt(required(values, "pocket_spawn_z")))
                : PocketEditorService.entry(player.level().getServer(), id);
        // Saving runs over several ticks; the result arrives as a chat message.
        ekuzo.crazyworldprogression.portals.PocketCaptures.start(player.level().getServer(),
                (net.minecraft.server.level.ServerLevel) player.level(), id, preferred, player.getName().getString(),
                template -> player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Saved pocket " + id + " · " + template.volume() + " blocks")),
                exception -> player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Saving pocket " + id + " failed: " + exception.getMessage())));
        AreaSelectionService.clear(player);
        return AdminPanelRegistry.ActionResult.success("Saving pocket " + id + "…", POCKETS);
    }

    // Create a reusable portal definition without a placement.
    private static AdminPanelRegistry.ActionResult createPortal(ServerPlayer player, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        Identifier id = formId(values, "portal_id");
        Identifier template = portalLink(values);
        PortalDefinition.Mode access = PortalDefinition.Mode.parse(required(values, "portal_access_mode"));
        PortalDefinition portal = new PortalDefinition(id, player.level().dimension().identifier(),
                PortalDefinition.Shape.parse(required(values, "portal_shape")),
                parsePortalPlane(required(values, "portal_plane")),
                0.0, 64.0, 0.0,
                positiveDouble(values, "portal_width"),
                positiveDouble(values, "portal_height"), template,
                Math.multiplyExact(nonNegativeLong(values, "portal_lifetime"), 60L),
                PortalDefinition.PortalColor.parse(required(values, "portal_color")).rgb(),
                required(values, "portal_name"), values.getOrDefault("portal_description", "").trim(),
                parseDifficulty(required(values, "portal_difficulty")),
                PortalDefinition.EntryPolicy.parse(required(values, "portal_entry_policy")),
                PortalDefinition.Schedule.always());
        portal = portal.withAdmission(new PortalDefinition.Admission(
                access, access == PortalDefinition.Mode.PRIVATE
                        ? boundedGroupSize(values, "portal_max_players") : 4,
                parseYesNo(required(values, "portal_allow_building")), PortalDefinition.Destination.POCKET));
        portal = portal.withEnvironment(portalEnvironment(values));
        portal = portal.withBaseActive(parseYesNo(required(values, "portal_base_active")));
        portal = portal.withRaidMode(parseYesNo(required(values, "portal_raid_mode")));
        portal = portal.withKeepInventory(portal.isExit() || portal.isCoordinates()
                || parseYesNo(required(values, "portal_keep_inventory")));
        portal = portal.withShowOtherPlayers(parseYesNo(values.getOrDefault("portal_show_players", "Yes")));
        if (portal.isCoordinates()) portal = portal.withCoordinateTarget(coordinateTarget(values));
        PortalService.createUnplaced(server, portal);
        return AdminPanelRegistry.ActionResult.success("Created portal " + id, PORTALS);
    }

    // Apply all portal editor fields to one fixed portal identifier while preserving its schedule and dimension.
    private static AdminPanelRegistry.ActionResult updatePortal(ServerPlayer player, Identifier id,
                                                                 Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        PortalDefinition current = PortalService.portal(server, id);
        if (current == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        PortalDefinition.Mode access = PortalDefinition.Mode.parse(required(values, "portal_access_mode"));
        PortalDefinition updated = new PortalDefinition(id, current.dimension(),
                PortalDefinition.Shape.parse(required(values, "portal_shape")),
                parsePortalPlane(required(values, "portal_plane")),
                finiteDouble(values, "portal_x"), finiteDouble(values, "portal_y"),
                finiteDouble(values, "portal_z"), positiveDouble(values, "portal_width"),
                positiveDouble(values, "portal_height"), portalLink(values),
                Math.multiplyExact(nonNegativeLong(values, "portal_lifetime"), 60L),
                PortalDefinition.PortalColor.parse(required(values, "portal_color")).rgb(),
                required(values, "portal_name"), values.getOrDefault("portal_description", "").trim(),
                parseDifficulty(required(values, "portal_difficulty")),
                PortalDefinition.EntryPolicy.parse(required(values, "portal_entry_policy")), current.schedule());
        updated = updated.withAdmission(new PortalDefinition.Admission(
                access, access == PortalDefinition.Mode.PRIVATE
                        ? boundedGroupSize(values, "portal_max_players") : current.admission().maxPlayers(),
                parseYesNo(required(values, "portal_allow_building")), PortalDefinition.Destination.POCKET));
        updated = updated.withEnvironment(portalEnvironment(values));
        updated = updated.withBaseActive(parseYesNo(required(values, "portal_base_active")));
        updated = updated.withRaidMode(parseYesNo(required(values, "portal_raid_mode")));
        updated = updated.withKeepInventory(updated.isExit() || updated.isCoordinates()
                || parseYesNo(required(values, "portal_keep_inventory")));
        updated = updated.withShowOtherPlayers(values.containsKey("portal_show_players")
                ? parseYesNo(required(values, "portal_show_players")) : current.showOtherPlayers());
        if (updated.isCoordinates()) updated = updated.withCoordinateTarget(coordinateTarget(values));
        PortalService.update(server, updated);
        return AdminPanelRegistry.ActionResult.success("Saved portal edits for " + id);
    }

    // Apply only fields changed by the compact editor so off-screen values are never required or overwritten.
    private static AdminPanelRegistry.ActionResult patchPortal(ServerPlayer player, Identifier id,
                                                                Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        PortalDefinition current = PortalService.portal(server, id);
        if (current == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        PortalDefinition.Schedule schedule = patchSchedule(current.schedule(), values);
        PortalDefinition.Mode access = values.containsKey("portal_access_mode")
                ? PortalDefinition.Mode.parse(required(values, "portal_access_mode"))
                : current.admission().mode();
        PortalDefinition updated = new PortalDefinition(id, current.dimension(),
                values.containsKey("portal_shape")
                        ? PortalDefinition.Shape.parse(required(values, "portal_shape")) : current.shape(),
                values.containsKey("portal_plane")
                        ? parsePortalPlane(required(values, "portal_plane")) : current.plane(),
                values.containsKey("portal_x") ? finiteDouble(values, "portal_x") : current.centerX(),
                values.containsKey("portal_y") ? finiteDouble(values, "portal_y") : current.centerY(),
                values.containsKey("portal_z") ? finiteDouble(values, "portal_z") : current.centerZ(),
                values.containsKey("portal_width") ? positiveDouble(values, "portal_width") : current.width(),
                values.containsKey("portal_height") ? positiveDouble(values, "portal_height") : current.height(),
                values.containsKey("portal_template") ? portalLink(values) : current.templateId(),
                values.containsKey("portal_lifetime")
                        ? Math.multiplyExact(nonNegativeLong(values, "portal_lifetime"), 60L)
                        : current.lifetimeSeconds(),
                values.containsKey("portal_color")
                        ? PortalDefinition.PortalColor.parse(required(values, "portal_color")).rgb()
                        : current.colorRgb(),
                values.containsKey("portal_name") ? required(values, "portal_name") : current.displayName(),
                values.containsKey("portal_description")
                        ? values.getOrDefault("portal_description", "").trim() : current.description(),
                values.containsKey("portal_difficulty")
                        ? parseDifficulty(required(values, "portal_difficulty")) : current.difficulty(),
                values.containsKey("portal_entry_policy")
                        ? PortalDefinition.EntryPolicy.parse(required(values, "portal_entry_policy"))
                        : current.entryPolicy(), schedule);
        updated = updated.withAdmission(new PortalDefinition.Admission(
                access, access == PortalDefinition.Mode.PRIVATE && values.containsKey("portal_max_players")
                        ? boundedGroupSize(values, "portal_max_players") : current.admission().maxPlayers(),
                values.containsKey("portal_allow_building")
                        ? parseYesNo(required(values, "portal_allow_building")) : current.allowBuilding(),
                PortalDefinition.Destination.POCKET, current.environment(), current.coordinateTarget(),
                values.containsKey("portal_base_active")
                        ? parseYesNo(required(values, "portal_base_active")) : current.baseActive(),
                values.containsKey("portal_raid_mode")
                        ? parseYesNo(required(values, "portal_raid_mode")) : current.raidMode()));
        updated = updated.withKeepInventory(updated.isExit() || updated.isCoordinates()
                || (values.containsKey("portal_keep_inventory")
                ? parseYesNo(required(values, "portal_keep_inventory")) : current.keepInventory()));
        updated = updated.withShowOtherPlayers(values.containsKey("portal_show_players")
                ? parseYesNo(required(values, "portal_show_players")) : current.showOtherPlayers());
        PortalDefinition.Environment environment = current.environment();
        updated = updated.withEnvironment(new PortalDefinition.Environment(
                values.containsKey("portal_daytime")
                        ? PortalDefinition.Daytime.parse(required(values, "portal_daytime")) : environment.daytime(),
                values.containsKey("portal_day_night_cycle")
                        ? parseYesNo(required(values, "portal_day_night_cycle")) : environment.dayNightCycle(),
                values.containsKey("portal_weather")
                        ? PortalDefinition.Weather.parse(required(values, "portal_weather")) : environment.weather(),
                values.containsKey("portal_weather_cycle")
                        ? parseYesNo(required(values, "portal_weather_cycle")) : environment.weatherCycle()));
        if (updated.isCoordinates() || values.containsKey("portal_target_x")
                || values.containsKey("portal_target_y") || values.containsKey("portal_target_z")) {
            PortalDefinition.CoordinateTarget base = current.coordinateTarget().orElseGet(() ->
                    defaultCoordinateTarget(current.centerX(), current.centerY(), current.centerZ(), current.plane()));
            updated = updated.withCoordinateTarget(new PortalDefinition.CoordinateTarget(
                    values.containsKey("portal_target_x") ? finiteDouble(values, "portal_target_x") : base.x(),
                    values.containsKey("portal_target_y") ? finiteDouble(values, "portal_target_y") : base.y(),
                    values.containsKey("portal_target_z") ? finiteDouble(values, "portal_target_z") : base.z()));
        }
        PortalService.update(server, updated);
        return AdminPanelRegistry.ActionResult.silentSuccess();
    }

    private static PortalDefinition.Environment portalEnvironment(Map<String, String> values) {
        return new PortalDefinition.Environment(
                PortalDefinition.Daytime.parse(required(values, "portal_daytime")),
                parseYesNo(required(values, "portal_day_night_cycle")),
                PortalDefinition.Weather.parse(required(values, "portal_weather")),
                parseYesNo(required(values, "portal_weather_cycle")));
    }

    private static PortalDefinition.CoordinateTarget coordinateTarget(Map<String, String> values) {
        return new PortalDefinition.CoordinateTarget(finiteDouble(values, "portal_target_x"),
                finiteDouble(values, "portal_target_y"), finiteDouble(values, "portal_target_z"));
    }

    private static PortalDefinition.CoordinateTarget defaultCoordinateTarget(double x, double y, double z,
                                                                            PortalDefinition.Plane plane) {
        return switch (plane) {
            case XY -> new PortalDefinition.CoordinateTarget(x, y, z + 3.0);
            case XZ -> new PortalDefinition.CoordinateTarget(x, y + 3.0, z);
            case YZ -> new PortalDefinition.CoordinateTarget(x + 3.0, y, z);
        };
    }

    private static int boundedGroupSize(Map<String, String> values, String key) {
        int value = Integer.parseInt(required(values, key));
        if (value < 1 || value > 100) throw new IllegalArgumentException("Group size must be 1 to 100");
        return value;
    }

    // Merge independently edited scheduling fields with the portal's currently persisted schedule.
    private static PortalDefinition.Schedule patchSchedule(PortalDefinition.Schedule current,
                                                            Map<String, String> values) {
        PortalDefinition.Schedule result = current;
        if (current.periodSeconds() > 0L &&
                (values.containsKey("period_minutes") || values.containsKey("open_minutes"))) {
            long period = values.containsKey("period_minutes")
                    ? Math.multiplyExact(positiveLong(values, "period_minutes"), 60L)
                    : current.periodSeconds() > 0L ? current.periodSeconds() : 14_400L;
            long open = values.containsKey("open_minutes")
                    ? Math.multiplyExact(positiveLong(values, "open_minutes"), 60L)
                    : current.openSeconds() > 0L ? current.openSeconds() : Math.min(1_800L, period);
            result = result.withPeriodicValues(period, open);
        }
        if (current.calendarEnabled() && (values.containsKey("calendar_zone") || values.containsKey("calendar_days")
                || values.containsKey("calendar_start") || values.containsKey("calendar_end"))) {
            Set<DayOfWeek> days = values.containsKey("calendar_days")
                    ? parseDays(required(values, "calendar_days")) : current.weekdays();
            LocalTime start = values.containsKey("calendar_start")
                    ? LocalTime.parse(required(values, "calendar_start")) : current.start();
            LocalTime end = values.containsKey("calendar_end")
                    ? LocalTime.parse(required(values, "calendar_end")) : current.end();
            ZoneId zone = values.containsKey("calendar_zone")
                    ? ZoneId.of(required(values, "calendar_zone")) : current.timezone();
            result = result.withCalendar(days, start, end, zone);
        }
        return result;
    }

    // Apply or clear one calendar/timer gate while retaining all unrelated portal schedule settings.
    private static AdminPanelRegistry.ActionResult updatePortalSchedule(MinecraftServer server,
                                                                         Map<String, String> values,
                                                                         String actionId) {
        Identifier id = formId(values, "portal_id");
        PortalDefinition portal = PortalService.portal(server, id);
        if (portal == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        PortalDefinition.Schedule schedule = portal.schedule();
        if (actionId.equals("portal.periodic.add")) {
            schedule = schedule.withPeriodic(14_400L, 1_800L, Instant.now());
        } else if (actionId.equals("portal.periodic")) {
            schedule = schedule.withPeriodic(Math.multiplyExact(positiveLong(values, "period_minutes"), 60L),
                    Math.multiplyExact(positiveLong(values, "open_minutes"), 60L), Instant.now());
        } else if (actionId.equals("portal.periodic.clear")) {
            schedule = schedule.withoutPeriodic();
        } else if (actionId.equals("portal.calendar.add")) {
            schedule = schedule.withCalendar(Set.of(DayOfWeek.values()), LocalTime.of(9, 0),
                    LocalTime.of(17, 0), ZoneId.of("UTC"));
        } else if (actionId.equals("portal.calendar")) {
            schedule = schedule.withCalendar(parseDays(required(values, "calendar_days")),
                    LocalTime.parse(required(values, "calendar_start")),
                    LocalTime.parse(required(values, "calendar_end")),
                    ZoneId.of(required(values, "calendar_zone")));
        } else if (actionId.equals("portal.calendar.clear")) {
            schedule = schedule.withoutCalendar();
        } else {
            return AdminPanelRegistry.ActionResult.failure("Unknown schedule action");
        }
        PortalService.update(server, portal.withSchedule(schedule));
        return AdminPanelRegistry.ActionResult.success("Updated schedule for " + id);
    }

    // Move an administrator beside one saved portal location.
    private static AdminPanelRegistry.ActionResult teleportToPortal(ServerPlayer player, Identifier id) {
        String name = PortalService.locations(player.level().getServer(), id).keySet().stream()
                .sorted().findFirst().orElse(null);
        if (name == null) return AdminPanelRegistry.ActionResult.failure("Portal has no locations: " + id);
        return teleportToPortal(player, id, name);
    }

    private static AdminPanelRegistry.ActionResult teleportToPortal(ServerPlayer player, Identifier id, String name) {
        MinecraftServer server = player.level().getServer();
        PortalDefinition portal = PortalService.portal(server, id);
        if (portal == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        PortalService.PortalPlacement location = PortalService.locations(server, id).get(name);
        if (location == null) return AdminPanelRegistry.ActionResult.failure("Unknown location " + name);
        ServerLevel destination = server.getLevel(ResourceKey.create(Registries.DIMENSION, location.dimension()));
        if (destination == null) {
            return AdminPanelRegistry.ActionResult.failure("Portal dimension is unavailable: " + location.dimension());
        }
        double x = location.x();
        double y = location.y();
        double z = location.z();
        switch (portal.plane()) {
            case XY -> z += 2.0;
            case YZ -> x += 2.0;
            case XZ -> y += 2.0;
        }
        boolean moved = player.teleportTo(destination, x, y, z, Set.of(), player.getYRot(), player.getXRot(), false);
        return moved ? AdminPanelRegistry.ActionResult.success("Teleported to " + portal.effectiveDisplayName())
                : AdminPanelRegistry.ActionResult.failure("Could not teleport to " + id);
    }

    // Create a prefilled text field while keeping the public admin-page declarations concise.
    private static AdminPanelRegistry.AdminField textField(String id, String label, String value,
                                                            String placeholder, int maxLength,
                                                            List<String> suggestions) {
        return new AdminPanelRegistry.AdminField(id, label, placeholder, value, maxLength, suggestions);
    }

    // Prefer a constrained template dropdown, falling back to text when no template exists yet.
    private static AdminPanelRegistry.AdminField templateField(String value, List<String> templates) {
        return templates.isEmpty()
                ? textField("portal_template", "Links to", value, "cwp:template_id", 128, templates)
                : AdminPanelRegistry.AdminField.select("portal_template", "Links to", value, templates);
    }

    private static Identifier portalLink(Map<String, String> values) {
        String value = required(values, "portal_template");
        if (value.equalsIgnoreCase("Exit")) return PortalDefinition.EXIT_TEMPLATE;
        if (value.equalsIgnoreCase("Coordinates")) return PortalDefinition.COORDINATES_TEMPLATE;
        return Identifier.parse(value);
    }

    // Convert a persisted portal orientation into the editor's readable dropdown label.
    private static String planeLabel(PortalDefinition.Plane plane) {
        return switch (plane) {
            case XY -> "Vertical north/south";
            case YZ -> "Vertical east/west";
            case XZ -> "Horizontal floor/ceiling";
        };
    }

    // Convert a persisted entry policy into the editor's readable dropdown label.
    private static String entryPolicyLabel(PortalDefinition.EntryPolicy policy) {
        return switch (policy) {
            case REPEATABLE -> "Repeatable";
            case ONCE_PER_INSTANCE -> "Once per instance";
            case ONCE_PER_ATTEMPT -> "Once per attempt";
            case ONCE_EVER -> "Once ever";
        };
    }

    // Capitalize a stable lowercase enum label for a matching dropdown option.
    private static String title(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    // Render compact decimal defaults without losing non-integral portal coordinates or dimensions.
    private static String decimal(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static Identifier pocketPageId(String pageId) {
        if (!pageId.startsWith(POCKET_EDIT)) throw new IllegalArgumentException("Open a pocket editor first");
        Identifier id = Identifier.tryParse(pageId.substring(POCKET_EDIT.length()));
        if (id == null) throw new IllegalArgumentException("Invalid pocket ID");
        return id;
    }

    private static ScriptRoute scriptRoute(String pageId) {
        if (pageId.startsWith(MOB_SCRIPT_EDIT)) {
            String scriptId = pageId.substring(MOB_SCRIPT_EDIT.length());
            return scriptId.isBlank() || scriptId.contains("/") ? null
                    : new ScriptRoute(null, scriptId, MOB_SCRIPTS);
        }
        if (!pageId.startsWith(POCKET_EDIT)) return null;
        int segment = pageId.indexOf(SCRIPT_SEGMENT, POCKET_EDIT.length());
        if (segment < 0) return null;
        Identifier pocketId = Identifier.tryParse(pageId.substring(POCKET_EDIT.length(), segment));
        String scriptId = pageId.substring(segment + SCRIPT_SEGMENT.length());
        return pocketId == null || scriptId.isBlank() || scriptId.contains("/") ? null
                : new ScriptRoute(pocketId, scriptId, POCKET_EDIT + pocketId);
    }

    private record ScriptRoute(Identifier pocketId, String scriptId, String backPage) { }

    // Bind edits to the selected pocket's dedicated build dimension.
    private static void requirePocketEditor(ServerPlayer player, Identifier id) {
        Identifier dimension = PocketEditorService.dimension(player.level().getServer(), id);
        if (dimension == null || !player.level().dimension().identifier().equals(dimension)) {
            throw new IllegalStateException("Enter this pocket's build dimension first");
        }
    }

    // Parse a form ID and apply CWP's namespace to convenient bare names.
    private static Identifier formId(Map<String, String> values, String key) {
        String value = required(values, key);
        return value.contains(":") ? Identifier.parse(value) : CrazyWorldProgression.id(value);
    }

    // Require a nonblank form value before a mutation begins.
    private static String required(Map<String, String> values, String key) {
        String value = values.getOrDefault(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Fill in " + key.replace('_', ' '));
        return value;
    }

    // Parse the leading one-to-five value from the human-readable difficulty dropdown label.
    private static int parseDifficulty(String value) {
        int difficulty = Integer.parseInt(value.split("\\s+", 2)[0]);
        if (difficulty < 1 || difficulty > 5) throw new IllegalArgumentException("Difficulty must be 1 to 5");
        return difficulty;
    }

    private static boolean parseYesNo(String value) {
        if (value.equalsIgnoreCase("Yes")) return true;
        if (value.equalsIgnoreCase("No")) return false;
        throw new IllegalArgumentException("Choose Yes or No");
    }

    // Parse a strictly positive decimal form value.
    private static double positiveDouble(Map<String, String> values, String key) {
        double value = Double.parseDouble(required(values, key));
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    // Parse any finite coordinate without imposing the positive-only size restriction.
    private static double finiteDouble(Map<String, String> values, String key) {
        double value = Double.parseDouble(required(values, key));
        if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite");
        return value;
    }

    // Parse a strictly positive whole-number form value.
    private static long positiveLong(Map<String, String> values, String key) {
        long value = Long.parseLong(required(values, key));
        if (value < 1L) throw new IllegalArgumentException(key + " must be at least 1");
        return value;
    }

    // Parse a non-negative whole-number form value.
    private static long nonNegativeLong(Map<String, String> values, String key) {
        long value = Long.parseLong(required(values, key));
        if (value < 0L) throw new IllegalArgumentException(key + " must not be negative");
        return value;
    }

    // Resolve a currently online player by case-insensitive name for wallet operations.
    private static ServerPlayer onlinePlayer(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player.getName().getString().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Player is not online: " + name));
    }

    // Return stable portal ID completions for the editor field.
    private static List<String> portalSuggestions(MinecraftServer server) {
        return PortalService.portals(server).stream().map(portal -> portal.id().toString()).toList();
    }

    // Return stable template ID completions for draft and portal-link fields.
    private static List<String> templateSuggestions(MinecraftServer server) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("Exit", "Coordinates"),
                PortalTemplateService.templates(server).stream().map(template -> template.id().toString())).toList();
    }

    // Parse comma-separated full or abbreviated weekday names from the calendar editor.
    private static Set<DayOfWeek> parseDays(String value) {
        return Arrays.stream(value.split(",")).map(day -> {
            String normalized = day.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(DayOfWeek.values()).filter(candidate ->
                    candidate.name().equals(normalized) || candidate.name().startsWith(normalized))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown weekday " + day));
        }).collect(Collectors.toUnmodifiableSet());
    }

    // Convert the portal editor's readable orientation choices into stable persisted plane values.
    private static PortalDefinition.Plane parsePortalPlane(String value) {
        return switch (value) {
            case "Vertical north/south" -> PortalDefinition.Plane.XY;
            case "Vertical east/west" -> PortalDefinition.Plane.YZ;
            case "Horizontal floor/ceiling" -> PortalDefinition.Plane.XZ;
            default -> PortalDefinition.Plane.parse(value);
        };
    }

    // Construct a consistently tabbed page for the native CWP module.
    private static AdminPanelRegistry.AdminPage page(String id, String title, String subtitle,
                                                     List<AdminPanelRegistry.AdminEntry> entries) {
        return new AdminPanelRegistry.AdminPage(id, title, subtitle, TABS, entries);
    }
}
