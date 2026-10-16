package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
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
    private static final String PORTALS = "portals";
    private static final String PORTAL_NEW = PORTALS + "/new";
    private static final String PORTAL_EDIT = PORTALS + "/edit/";
    private static final String CURRENCIES = "currencies";
    private static final String SKILL_TREES = "skill_trees";
    private static final String EVENTS = "events";
    private static final String MOBFORGE = "mobforge";
    private static final List<String> SHAPES = List.of("Rectangle", "Ellipse", "Diamond", "Triangle");
    private static final List<String> PLANES = List.of(
            "Vertical north/south", "Vertical east/west", "Horizontal floor/ceiling");
    private static final List<String> PORTAL_COLORS = Arrays.stream(PortalDefinition.PortalColor.values())
            .map(color -> PortalDefinition.PortalColor.displayName(color.rgb())).toList();
    private static final List<String> ENTRY_POLICIES = List.of("Repeatable", "Once per instance", "Once ever");
    private static final List<String> ACCESS_MODES = List.of("Public", "Private");
    private static final List<String> DIFFICULTIES = List.of("1 skull", "2 skulls", "3 skulls", "4 skulls", "5 skulls");
    private static final List<AdminPanelRegistry.AdminTab> TABS = List.of(
            new AdminPanelRegistry.AdminTab(OVERVIEW, "Overview"),
            new AdminPanelRegistry.AdminTab(POCKETS, "Pockets"),
            new AdminPanelRegistry.AdminTab(PORTALS, "Portals"),
            new AdminPanelRegistry.AdminTab(CURRENCIES, "Currency"),
            new AdminPanelRegistry.AdminTab(SKILL_TREES, "Skill Trees"),
            new AdminPanelRegistry.AdminTab(MOBFORGE, "MobForge"),
            new AdminPanelRegistry.AdminTab(EVENTS, "Events"));

    // Prevent construction of the native module registrar.
    private CwpAdminModule() {
    }

    // Publish CWP as the first top-level module in its own extensible dashboard.
    public static void register() {
        AdminPanelRegistry.register(new AdminPanelRegistry.AdminModule(
                CrazyWorldProgression.id("admin"), "Crazy World Progression",
                "Framework health, pockets, MobForge, currencies, skill trees and random events.",
                "minecraft:nether_star", OVERVIEW, CwpAdminModule::page, CwpAdminModule::action));
    }

    // Route a requested tab to a fresh server-authoritative page.
    private static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId) {
        if (pageId.equals(PORTAL_NEW)) return portalEditorPage(player, null);
        if (pageId.startsWith(PORTAL_EDIT)) {
            Identifier id = Identifier.tryParse(pageId.substring(PORTAL_EDIT.length()));
            return portalEditorPage(player, id);
        }
        return switch (pageId) {
            case POCKETS -> pocketsPage(player);
            case PORTALS -> portalsPage(player);
            case CURRENCIES -> currenciesPage(player);
            case SKILL_TREES -> skillTreesPage(player);
            case MOBFORGE -> mobForgePage(player);
            case EVENTS -> eventsPage(player);
            default -> overviewPage(player);
        };
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

    // Manage the template workshop, selected draft, spawn anchor, templates, and live instances.
    private static AdminPanelRegistry.AdminPage pocketsPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        AreaSelectionService.Selection selection = AreaSelectionService.selection(player).orElse(null);
        String selectionStatus = selection == null ? "No active selection"
                : selection.complete() ? selection.minimum().toShortString() + " → "
                + selection.maximum().toShortString() + " · " + selection.volume() + " blocks"
                : "Selection active · choose both corners";
        String spawnStatus = selection == null || selection.anchor() == null
                ? "No spawn selected · stand at the intended arrival position"
                : "Spawn at " + selection.anchor().toShortString();
        entries.add(new AdminPanelRegistry.AdminEntry("Template workshop",
                "Enter or leave the persistent authoring world.", NORMAL,
                List.of(new AdminPanelRegistry.AdminAction("workshop.enter", "Enter", false),
                        new AdminPanelRegistry.AdminAction("workshop.leave", "Leave", false))));
        entries.add(new AdminPanelRegistry.AdminEntry("Current draft selection", selectionStatus, NORMAL,
                List.of(new AdminPanelRegistry.AdminField("template_id", "Template ID",
                                "forgotten_vault", 128, templateSuggestions(server))),
                List.of(new AdminPanelRegistry.AdminAction("selection.begin", "Select", false),
                        new AdminPanelRegistry.AdminAction("selection.cancel", "Cancel", false),
                        new AdminPanelRegistry.AdminAction("draft.save", "Save draft", false),
                        new AdminPanelRegistry.AdminAction("draft.clear", "Clear area", true))));
        entries.add(new AdminPanelRegistry.AdminEntry("Pocket spawn", spawnStatus, NORMAL,
                List.of(new AdminPanelRegistry.AdminAction("draft.spawn", "Set spawn", false))));
        for (PortalService.ActiveInstance instance : PortalService.activeInstances(server)) {
            long seconds = Math.max(0L, Duration.between(Instant.now(), instance.expires()).toSeconds());
            entries.add(new AdminPanelRegistry.AdminEntry("Active: " + instance.portalId(),
                    instance.templateId() + " in " + instance.slotDimension() + " · " + seconds + "s remaining",
                    GOOD, List.of(new AdminPanelRegistry.AdminAction(
                    "instance.expire|" + instance.slotDimension(), "Expire", true))));
        }
        for (PortalTemplateService.TemplateInfo template : PortalTemplateService.templates(server)) {
            entries.add(AdminPanelRegistry.AdminEntry.info("Template: " + template.id(),
                    template.sizeX() + "×" + template.sizeY() + "×" + template.sizeZ()
                            + " · spawn " + template.spawnX() + ", " + template.spawnY() + ", "
                            + template.spawnZ()));
        }
        if (entries.size() == 3) entries.add(AdminPanelRegistry.AdminEntry.info("No pocket content",
                "Select a workshop build and its spawn, then save the draft."));
        return page(POCKETS, "Pocket dimensions",
                PortalService.activeInstances(server).size() + "/" + PortalRegistry.instanceSlots().size()
                        + " reusable slots active", entries);
    }

    // List placed portals as visual cards with direct edit, teleport, and delete controls.
    private static AdminPanelRegistry.AdminPage portalsPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(new AdminPanelRegistry.AdminEntry("Create portal", "Open an empty, organized portal editor.",
                GOOD, List.of(AdminPanelRegistry.AdminAction.navigate("Create", PORTAL_NEW))));
        for (PortalDefinition portal : PortalService.portals(server)) {
            String portalId = portal.id().toString();
            entries.add(new AdminPanelRegistry.AdminEntry(portal.effectiveDisplayName(),
                    "Linked to: " + portal.templateId(), PortalService.isOpen(portal) ? GOOD : NORMAL, List.of(),
                    List.of(AdminPanelRegistry.AdminAction.navigate("Edit", PORTAL_EDIT + portalId),
                            new AdminPanelRegistry.AdminAction("portal.teleport|" + portalId, "Teleport", false),
                            new AdminPanelRegistry.AdminAction("portal.delete|" + portalId, "Delete", true)),
                    new AdminPanelRegistry.EntryPreview("portal", portal.shape().serializedName(),
                            portal.colorRgb())));
        }
        if (entries.size() == 1) entries.add(AdminPanelRegistry.AdminEntry.info("No portals placed",
                "Create one to link a location directly to a pocket template."));
        return new AdminPanelRegistry.AdminPage(PORTALS, "Portals",
                PortalService.portals(server).size() + " placed portal(s)", TABS, entries,
                AdminPanelRegistry.PageLayout.PREVIEW_CARDS);
    }

    // Build a dedicated, sectioned editor prefilled from one portal or sensible creation defaults.
    private static AdminPanelRegistry.AdminPage portalEditorPage(ServerPlayer player, Identifier requestedId) {
        MinecraftServer server = player.level().getServer();
        PortalDefinition portal = requestedId == null ? null : PortalService.portal(server, requestedId);
        if (requestedId != null && portal == null) return portalsPage(player);
        boolean creating = portal == null;
        String id = creating ? "" : portal.id().toString();
        List<String> templates = templateSuggestions(server);
        String template = creating ? (templates.isEmpty() ? "" : templates.getFirst()) : portal.templateId().toString();
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
                "Player-facing name and the pocket template opened by this portal.",
                List.of(textField("portal_name", "Name", creating ? "" : portal.effectiveDisplayName(),
                                "Forgotten Vault", 96, List.of()),
                        templateField(template, templates)), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Description",
                "Shown in the Minecraft-style travel confirmation window.",
                List.of(textField("portal_description", "Description", creating ? "" : portal.description(),
                        "An unstable passage into a forgotten place.", 512, List.of())), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Location",
                "Coordinates in " + (creating ? player.level().dimension().identifier() : portal.dimension()),
                List.of(textField("portal_x", "X", decimal(creating ? player.getX() : portal.centerX()), "0", 24,
                                List.of()),
                        textField("portal_y", "Y", decimal(creating ? player.getY() + 0.9 : portal.centerY()), "64",
                                24, List.of()),
                        textField("portal_z", "Z", decimal(creating ? player.getZ() : portal.centerZ()), "0", 24,
                                List.of())), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("General", "Current position",
                "Replace all coordinates with your current location.", List.of(),
                List.of(AdminPanelRegistry.AdminAction.wide(
                        creating ? "portal.position.current" : "portal.position|" + id,
                        "Set to current position", false))));
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
                "Difficulty controls presentation; lifetime controls pocket cleanup.",
                List.of(AdminPanelRegistry.AdminField.select("portal_difficulty", "Difficulty",
                                (creating ? 1 : portal.difficulty()) + (creating || portal.difficulty() == 1
                                        ? " skull" : " skulls"), DIFFICULTIES),
                        textField("portal_lifetime", "Instance lifetime (minutes)",
                                Long.toString((creating ? 5_400L : portal.lifetimeSeconds()) / 60L), "90", 10,
                                List.of())), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Entry policy",
                "Choose whether a player may revisit this portal.",
                List.of(AdminPanelRegistry.AdminField.select("portal_entry_policy", "Entry policy",
                        creating ? "Repeatable" : entryPolicyLabel(portal.entryPolicy()), ENTRY_POLICIES)), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Gameplay", "Instance access",
                "Public shares one instance. Private starts one sealed instance per group.",
                List.of(AdminPanelRegistry.AdminField.select("portal_access_mode", "Access",
                                creating ? "Public" : title(portal.admission().mode().serializedName()), ACCESS_MODES),
                        textField("portal_max_players", "Private group limit",
                                Integer.toString(creating ? 4 : portal.admission().maxPlayers()), "4", 3,
                                List.of())), List.of()));
        if (!creating) addScheduleEditor(entries, schedule);
        String saveAction = creating ? "portal.create" : "";
        entries.add(AdminPanelRegistry.AdminEntry.editor("Actions",
                creating ? "Create portal" : "Changes apply automatically",
                creating ? "Creates the portal in your current dimension at the coordinates above."
                        : "Text applies after a short pause; dropdowns apply immediately.",
                List.of(),
                creating ? List.of(new AdminPanelRegistry.AdminAction(saveAction, "Create", false),
                                AdminPanelRegistry.AdminAction.navigate("Cancel", PORTALS))
                        : List.of(AdminPanelRegistry.AdminAction.navigate("Back to portals", PORTALS))));
        return new AdminPanelRegistry.AdminPage(creating ? PORTAL_NEW : PORTAL_EDIT + id,
                creating ? "Create portal" : "Edit · " + portal.effectiveDisplayName(),
                creating ? "General, style, gameplay, then optional time gates"
                        : portal.id() + " · changes save automatically · linked to " + portal.templateId(),
                TABS, entries,
                AdminPanelRegistry.PageLayout.EDITOR, creating ? "" : "portal.autosave|" + id);
    }

    // Append periodic and calendar scheduling controls to an existing portal editor.
    private static void addScheduleEditor(List<AdminPanelRegistry.AdminEntry> entries,
                                          PortalDefinition.Schedule schedule) {
        entries.add(AdminPanelRegistry.AdminEntry.editor("Time settings", "Repeating window",
                schedule.periodSeconds() == 0L ? "Disabled · optionally open for Y minutes every X minutes."
                        : "Currently active from its saved real-time anchor.",
                List.of(textField("period_minutes", "Every minutes",
                                schedule.periodSeconds() == 0L ? "240" : Long.toString(schedule.periodSeconds() / 60L),
                                "240", 10, List.of()),
                        textField("open_minutes", "Open minutes",
                                schedule.openSeconds() == 0L ? "30" : Long.toString(schedule.openSeconds() / 60L),
                                "30", 10, List.of())),
                List.of(new AdminPanelRegistry.AdminAction("portal.periodic.clear", "Disable", false))));
        entries.add(AdminPanelRegistry.AdminEntry.editor("Time settings", "Calendar window",
                "Restrict entry by local weekday and clock time; overnight windows are supported.",
                List.of(textField("calendar_zone", "Timezone", schedule.timezone().getId(), "Europe/Berlin", 64,
                                List.of("Europe/Berlin", "UTC")),
                        textField("calendar_days", "Weekdays", schedule.weekdays().stream().sorted()
                                .map(DayOfWeek::name).collect(Collectors.joining(",")), "FRIDAY", 96, List.of()),
                        textField("calendar_start", "Start", schedule.start().toString(), "19:00", 8, List.of()),
                        textField("calendar_end", "End", schedule.end().toString(), "23:00", 8, List.of())),
                List.of(new AdminPanelRegistry.AdminAction("portal.calendar.clear", "Disable", false))));
    }

    // Show global and online-player wallets with deliberate small adjustment controls.
    private static AdminPanelRegistry.AdminPage currenciesPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        List<String> players = server.getPlayerList().getPlayers().stream()
                .map(target -> target.getName().getString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        entries.add(new AdminPanelRegistry.AdminEntry("Wallet operation",
                "Type an online player and amount. Press Tab to accept the shown player completion.", NORMAL,
                List.of(new AdminPanelRegistry.AdminField("currency_player", "Player", "Player name", 64, players),
                        AdminPanelRegistry.AdminField.text("currency_amount", "Amount", "0", 20)), List.of()));
        for (CurrencyDefinition currency : CurrencyRegistry.values()) {
            long balance = currency.scope() == CurrencyDefinition.CurrencyScope.GLOBAL
                    ? CurrencyService.getBalance(server, player.getUUID(), currency.id()) : 0L;
            String detail = currency.id() + " · " + currency.scope().name().toLowerCase()
                    + (currency.scope() == CurrencyDefinition.CurrencyScope.GLOBAL
                    ? " · current " + balance + " " + currency.abbreviation() : "");
            entries.add(new AdminPanelRegistry.AdminEntry(currency.displayName(), detail, NORMAL,
                    List.of(new AdminPanelRegistry.AdminAction("currency.credit|" + currency.id(), "Give", false),
                            new AdminPanelRegistry.AdminAction("currency.debit|" + currency.id(), "Take", false),
                            new AdminPanelRegistry.AdminAction("currency.set|" + currency.id(), "Set", true))));
        }
        if (entries.size() == 1) entries.add(AdminPanelRegistry.AdminEntry.info("No currencies",
                "Dependent mods have not registered any currency definitions."));
        return page(CURRENCIES, "Currency control", "One compact operation form for every registered currency", entries);
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
        entries.add(new AdminPanelRegistry.AdminEntry("Create content",
                "Templates configure YAML mobs; scripts sequence spawns, abilities, phases, waits and events.",
                NORMAL, List.of(new AdminPanelRegistry.AdminAction("mob.template.new", "New mob", false),
                        new AdminPanelRegistry.AdminAction("mob.script.new", "New script", false))));
        entries.add(new AdminPanelRegistry.AdminEntry("Reload MobForge YAML",
                "Reload templates and scripts from config/mobforge.", NORMAL,
                List.of(new AdminPanelRegistry.AdminAction("mob.reload", "Reload", false))));
        for (MobTemplate template : MobForge.TEMPLATES.all()) {
            entries.add(new AdminPanelRegistry.AdminEntry("Mob: " + template.id(),
                    template.entity() + " · " + template.ai().mode() + " AI",
                    GOOD, List.of(new AdminPanelRegistry.AdminAction("mob.template.edit|" + template.id(),
                            "Edit", false), new AdminPanelRegistry.AdminAction("mob.template.spawn|" + template.id(),
                            "Spawn here", false))));
        }
        for (MobScript script : MobForge.SCRIPTS.all()) {
            entries.add(new AdminPanelRegistry.AdminEntry("Script: " + script.id(),
                    script.steps().size() + " steps · " + MobForge.SCRIPT_EXECUTOR.activeRuns(script.id())
                            + " active runs", NORMAL,
                    List.of(new AdminPanelRegistry.AdminAction("mob.script.edit|" + script.id(), "Edit", false),
                            new AdminPanelRegistry.AdminAction("mob.script.run|" + script.id(), "Run here", false))));
        }
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
        return page(MOBFORGE, "MobForge", "CWP mobs, YAML content and encounter scripts", entries);
    }

    private static AdminPanelRegistry.AdminPage eventsPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        for (RandomEventDefinition event : RandomEventManager.definitions()) {
            boolean active = RandomEventService.isActive(server, event.id());
            entries.add(new AdminPanelRegistry.AdminEntry(event.id().toString(),
                    event.trigger().getClass().getSimpleName().replace("Trigger", "") + " · "
                    + (RandomEventRegistry.hasHandler(event.id()) ? "handler ready" : "missing handler") + " · "
                    + (active ? "ACTIVE" : "inactive"), active ? GOOD : NORMAL,
                    List.of(new AdminPanelRegistry.AdminAction(
                            (active ? "event.stop|" : "event.start|") + event.id(),
                            active ? "Stop" : "Start", active))));
        }
        if (entries.isEmpty()) entries.add(AdminPanelRegistry.AdminEntry.info("No events loaded",
                "Register a random-event YAML source from a content mod."));
        return page(EVENTS, "Random events",
                RandomEventService.isBlackoutActive() ? "A blackout window is active" : "Random starts are allowed",
                entries);
    }

    // Execute one native action after the networking layer has authenticated the administrator.
    private static AdminPanelRegistry.ActionResult action(ServerPlayer player, String pageId, String actionId,
                                                          Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        if (actionId.equals("reload")) return reload(server);
        if (actionId.equals("mob.reload")) {
            var templates = MobForge.TEMPLATES.reload();
            var scripts = MobForge.SCRIPTS.reload();
            return AdminPanelRegistry.ActionResult.success("Reloaded " + templates.loaded() + " mobs and "
                    + scripts.loaded() + " scripts; " + (templates.failed() + scripts.failed()) + " failed");
        }
        if (actionId.equals("mob.template.new")) {
            MobTemplate template = new MobTemplate("new_template", "minecraft:zombie", null,
                    Map.of(), Map.of(), null, null, List.of(), null);
            return MobForgeNetworking.open(player, template, false) ? AdminPanelRegistry.ActionResult.silentSuccess()
                    : AdminPanelRegistry.ActionResult.failure("MobForge editor is unavailable on this client");
        }
        if (actionId.equals("mob.script.new")) {
            return MobForgeNetworking.openScript(player, new MobScript("new_script", List.of()), false)
                    ? AdminPanelRegistry.ActionResult.silentSuccess()
                    : AdminPanelRegistry.ActionResult.failure("MobForge script editor is unavailable on this client");
        }
        if (actionId.equals("workshop.enter")) {
            PortalService.enterWorkshop(player);
            return AdminPanelRegistry.ActionResult.success("Entered the template workshop");
        }
        if (actionId.equals("workshop.leave")) {
            return PortalService.leaveWorkshop(player)
                    ? AdminPanelRegistry.ActionResult.success("Left the template workshop")
                    : AdminPanelRegistry.ActionResult.failure("You are not in the template workshop");
        }
        if (actionId.equals("selection.begin")) {
            AreaSelectionService.begin(player, CrazyWorldProgression.id("portal_template_draft"));
            return AdminPanelRegistry.ActionResult.success(
                    "Selection started · close the panel, then left/right-click the corners");
        }
        if (actionId.equals("selection.cancel")) {
            AreaSelectionService.clear(player);
            return AdminPanelRegistry.ActionResult.success("Area selection cancelled");
        }
        if (actionId.equals("draft.spawn")) {
            requireWorkshop(player);
            AreaSelectionService.Selection selection = AreaSelectionService.setAnchor(player, player.blockPosition());
            return AdminPanelRegistry.ActionResult.success("Pocket spawn set to "
                    + selection.anchor().toShortString());
        }
        if (actionId.equals("draft.save")) return saveDraft(player, values);
        if (actionId.equals("draft.clear")) {
            requireWorkshop(player);
            long blocks = AreaSelectionService.clearSelectedArea(player);
            return AdminPanelRegistry.ActionResult.success("Clearing " + blocks + " selected workshop blocks");
        }
        if (actionId.equals("portal.create")) return createPortal(player, values);
        if (actionId.equals("portal.position.current")) {
            return AdminPanelRegistry.ActionResult.success("Coordinates set to your current position");
        }
        if (actionId.equals("portal.update")) return updatePortal(player, formId(values, "portal_id"), values);
        if (actionId.equals("portal.move")) {
            Identifier id = formId(values, "portal_id");
            PortalDefinition portal = PortalService.portal(server, id);
            if (portal == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
            PortalService.update(server, portal.movedTo(player.level().dimension().identifier(), player.position()));
            return AdminPanelRegistry.ActionResult.success("Moved portal " + id + " to your position");
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
        if (parts.length == 2 && parts[0].equals("mob.template.edit")) {
            MobTemplate template = MobForge.TEMPLATES.get(parts[1]).orElse(null);
            if (template == null) return AdminPanelRegistry.ActionResult.failure("Unknown mob template " + parts[1]);
            return MobForgeNetworking.open(player, template) ? AdminPanelRegistry.ActionResult.silentSuccess()
                    : AdminPanelRegistry.ActionResult.failure("MobForge editor is unavailable on this client");
        }
        if (parts.length == 2 && parts[0].equals("mob.script.edit")) {
            MobScript script = MobForge.SCRIPTS.get(parts[1]).orElse(null);
            if (script == null) return AdminPanelRegistry.ActionResult.failure("Unknown MobForge script " + parts[1]);
            return MobForgeNetworking.openScript(player, script) ? AdminPanelRegistry.ActionResult.silentSuccess()
                    : AdminPanelRegistry.ActionResult.failure("MobForge script editor is unavailable on this client");
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
        if (parts.length == 2 && parts[0].equals("portal.autosave")) {
            return patchPortal(player, Identifier.parse(parts[1]), values);
        }
        if (parts.length == 2 && parts[0].equals("portal.save")) {
            return updatePortal(player, Identifier.parse(parts[1]), values);
        }
        if (parts.length == 2 && parts[0].equals("portal.position")) {
            Identifier id = Identifier.parse(parts[1]);
            PortalDefinition portal = PortalService.portal(server, id);
            if (portal == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
            PortalService.update(server, portal.movedTo(player.level().dimension().identifier(), player.position()));
            return AdminPanelRegistry.ActionResult.success("Set " + id + " to your current position");
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

    // Capture the selected workshop draft with its explicitly selected in-bounds spawn anchor.
    private static AdminPanelRegistry.ActionResult saveDraft(ServerPlayer player, Map<String, String> values) {
        requireWorkshop(player);
        AreaSelectionService.Selection selection = AreaSelectionService.requireComplete(player);
        if (selection.anchor() == null) throw new IllegalStateException("Set the pocket spawn before saving");
        Identifier id = formId(values, "template_id");
        PortalTemplateService.TemplateInfo template = PortalTemplateService.capture(player.level().getServer(),
                (net.minecraft.server.level.ServerLevel) player.level(), id, selection.first(), selection.second(),
                selection.anchor(), player.getName().getString());
        AreaSelectionService.clear(player);
        return AdminPanelRegistry.ActionResult.success("Saved draft " + id + " · " + template.volume() + " blocks");
    }

    // Create a directly linked portal at the administrator's exact current position from panel fields.
    private static AdminPanelRegistry.ActionResult createPortal(ServerPlayer player, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        Identifier id = formId(values, "portal_id");
        Identifier template = formId(values, "portal_template");
        PortalDefinition portal = new PortalDefinition(id, player.level().dimension().identifier(),
                PortalDefinition.Shape.parse(required(values, "portal_shape")),
                parsePortalPlane(required(values, "portal_plane")),
                finiteDouble(values, "portal_x"), finiteDouble(values, "portal_y"), finiteDouble(values, "portal_z"),
                positiveDouble(values, "portal_width"),
                positiveDouble(values, "portal_height"), template,
                Math.multiplyExact(positiveLong(values, "portal_lifetime"), 60L),
                PortalDefinition.PortalColor.parse(required(values, "portal_color")).rgb(),
                required(values, "portal_name"), values.getOrDefault("portal_description", "").trim(),
                parseDifficulty(required(values, "portal_difficulty")),
                PortalDefinition.EntryPolicy.parse(required(values, "portal_entry_policy")),
                PortalDefinition.Schedule.always()).withAdmission(new PortalDefinition.Admission(
                PortalDefinition.Mode.parse(required(values, "portal_access_mode")),
                boundedGroupSize(values, "portal_max_players")));
        PortalService.create(server, portal);
        return AdminPanelRegistry.ActionResult.success("Created portal " + id, PORTALS);
    }

    // Apply all portal editor fields to one fixed portal identifier while preserving its schedule and dimension.
    private static AdminPanelRegistry.ActionResult updatePortal(ServerPlayer player, Identifier id,
                                                                 Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        PortalDefinition current = PortalService.portal(server, id);
        if (current == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        PortalDefinition updated = new PortalDefinition(id, current.dimension(),
                PortalDefinition.Shape.parse(required(values, "portal_shape")),
                parsePortalPlane(required(values, "portal_plane")),
                finiteDouble(values, "portal_x"), finiteDouble(values, "portal_y"),
                finiteDouble(values, "portal_z"), positiveDouble(values, "portal_width"),
                positiveDouble(values, "portal_height"), formId(values, "portal_template"),
                Math.multiplyExact(positiveLong(values, "portal_lifetime"), 60L),
                PortalDefinition.PortalColor.parse(required(values, "portal_color")).rgb(),
                required(values, "portal_name"), values.getOrDefault("portal_description", "").trim(),
                parseDifficulty(required(values, "portal_difficulty")),
                PortalDefinition.EntryPolicy.parse(required(values, "portal_entry_policy")), current.schedule())
                .withAdmission(new PortalDefinition.Admission(
                        PortalDefinition.Mode.parse(required(values, "portal_access_mode")),
                        boundedGroupSize(values, "portal_max_players")));
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
                values.containsKey("portal_template") ? formId(values, "portal_template") : current.templateId(),
                values.containsKey("portal_lifetime")
                        ? Math.multiplyExact(positiveLong(values, "portal_lifetime"), 60L)
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
                        : current.entryPolicy(), schedule).withAdmission(new PortalDefinition.Admission(
                        values.containsKey("portal_access_mode")
                                ? PortalDefinition.Mode.parse(required(values, "portal_access_mode"))
                                : current.admission().mode(),
                        values.containsKey("portal_max_players")
                                ? boundedGroupSize(values, "portal_max_players")
                                : current.admission().maxPlayers()));
        PortalService.update(server, updated);
        return AdminPanelRegistry.ActionResult.silentSuccess();
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
        if (values.containsKey("period_minutes") || values.containsKey("open_minutes")) {
            long period = values.containsKey("period_minutes")
                    ? Math.multiplyExact(positiveLong(values, "period_minutes"), 60L)
                    : current.periodSeconds() > 0L ? current.periodSeconds() : 14_400L;
            long open = values.containsKey("open_minutes")
                    ? Math.multiplyExact(positiveLong(values, "open_minutes"), 60L)
                    : current.openSeconds() > 0L ? current.openSeconds() : Math.min(1_800L, period);
            result = result.withPeriodic(period, open, Instant.now());
        }
        if (values.containsKey("calendar_zone") || values.containsKey("calendar_days")
                || values.containsKey("calendar_start") || values.containsKey("calendar_end")) {
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
        if (actionId.equals("portal.periodic")) {
            schedule = schedule.withPeriodic(Math.multiplyExact(positiveLong(values, "period_minutes"), 60L),
                    Math.multiplyExact(positiveLong(values, "open_minutes"), 60L), Instant.now());
        } else if (actionId.equals("portal.periodic.clear")) {
            schedule = schedule.withoutPeriodic();
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

    // Move an administrator beside a portal's source surface without activating its travel prompt.
    private static AdminPanelRegistry.ActionResult teleportToPortal(ServerPlayer player, Identifier id) {
        MinecraftServer server = player.level().getServer();
        PortalDefinition portal = PortalService.portal(server, id);
        if (portal == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        ServerLevel destination = server.getLevel(ResourceKey.create(Registries.DIMENSION, portal.dimension()));
        if (destination == null) {
            return AdminPanelRegistry.ActionResult.failure("Portal dimension is unavailable: " + portal.dimension());
        }
        double x = portal.centerX();
        double y = portal.centerY();
        double z = portal.centerZ();
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

    // Require draft operations to run inside CWP's isolated workshop dimension.
    private static void requireWorkshop(ServerPlayer player) {
        if (!player.level().dimension().identifier().equals(PortalService.WORKSHOP_DIMENSION)) {
            throw new IllegalStateException("Enter the template workshop first");
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
        return PortalTemplateService.templates(server).stream().map(template -> template.id().toString()).toList();
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
