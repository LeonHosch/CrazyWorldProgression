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
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
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
    private static final String CURRENCIES = "currencies";
    private static final String SKILL_TREES = "skill_trees";
    private static final String EVENTS = "events";
    private static final List<String> SHAPES = List.of("Rectangle", "Ellipse", "Diamond", "Triangle");
    private static final List<String> PLANES = List.of(
            "Vertical north/south", "Vertical east/west", "Horizontal floor/ceiling");
    private static final List<AdminPanelRegistry.AdminTab> TABS = List.of(
            new AdminPanelRegistry.AdminTab(OVERVIEW, "Overview"),
            new AdminPanelRegistry.AdminTab(POCKETS, "Pockets"),
            new AdminPanelRegistry.AdminTab(PORTALS, "Portals"),
            new AdminPanelRegistry.AdminTab(CURRENCIES, "Currency"),
            new AdminPanelRegistry.AdminTab(SKILL_TREES, "Skill Trees"),
            new AdminPanelRegistry.AdminTab(EVENTS, "Events"));

    // Prevent construction of the native module registrar.
    private CwpAdminModule() {
    }

    // Publish CWP as the first top-level module in its own extensible dashboard.
    public static void register() {
        AdminPanelRegistry.register(new AdminPanelRegistry.AdminModule(
                CrazyWorldProgression.id("admin"), "Crazy World Progression",
                "Framework health, pocket instances, currencies, skill trees and random events.",
                "minecraft:nether_star", OVERVIEW, CwpAdminModule::page, CwpAdminModule::action));
    }

    // Route a requested tab to a fresh server-authoritative page.
    private static AdminPanelRegistry.AdminPage page(ServerPlayer player, String pageId) {
        return switch (pageId) {
            case POCKETS -> pocketsPage(player);
            case PORTALS -> portalsPage(player);
            case CURRENCIES -> currenciesPage(player);
            case SKILL_TREES -> skillTreesPage(player);
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
                    "instance.expire|" + instance.portalId(), "Expire", true))));
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

    // Manage placed portal identity, constrained geometry, schedules, and current status.
    private static AdminPanelRegistry.AdminPage portalsPage(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(new AdminPanelRegistry.AdminEntry("Portal identity",
                "Choose a new ID or an existing portal ID to edit.", NORMAL,
                List.of(new AdminPanelRegistry.AdminField("portal_id", "Portal ID", "vault_gate", 128,
                                portalSuggestions(server)),
                        new AdminPanelRegistry.AdminField("portal_template", "Template", "forgotten_vault", 128,
                                templateSuggestions(server))), List.of()));
        entries.add(new AdminPanelRegistry.AdminEntry("Portal geometry",
                "Choose a supported shape and orientation from the dropdowns.", NORMAL,
                List.of(AdminPanelRegistry.AdminField.select("portal_shape", "Shape", "Rectangle", SHAPES),
                        AdminPanelRegistry.AdminField.select("portal_plane", "Orientation",
                                "Vertical north/south", PLANES),
                        AdminPanelRegistry.AdminField.text("portal_width", "Width", "4", 12),
                        AdminPanelRegistry.AdminField.text("portal_height", "Height", "6", 12)), List.of()));
        entries.add(new AdminPanelRegistry.AdminEntry("Portal placement and editing",
                "Create at your current position or apply non-empty fields to an existing portal.", NORMAL,
                List.of(AdminPanelRegistry.AdminField.text("portal_lifetime", "Lifetime minutes", "90", 10)),
                List.of(new AdminPanelRegistry.AdminAction("portal.create", "Create here", false),
                        new AdminPanelRegistry.AdminAction("portal.update", "Save edits", false),
                        new AdminPanelRegistry.AdminAction("portal.move", "Move here", false),
                        new AdminPanelRegistry.AdminAction("portal.remove", "Remove", true))));
        entries.add(new AdminPanelRegistry.AdminEntry("Repeating entry window",
                "For example: open 30 minutes every 240 minutes.", NORMAL,
                List.of(AdminPanelRegistry.AdminField.text("period_minutes", "Every minutes", "240", 10),
                        AdminPanelRegistry.AdminField.text("open_minutes", "Open minutes", "30", 10)),
                List.of(new AdminPanelRegistry.AdminAction("portal.periodic", "Apply", false),
                        new AdminPanelRegistry.AdminAction("portal.periodic.clear", "Clear", true))));
        entries.add(new AdminPanelRegistry.AdminEntry("Calendar entry window",
                "Weekdays use comma-separated names; overnight ranges are supported.", NORMAL,
                List.of(new AdminPanelRegistry.AdminField("calendar_zone", "Timezone", "Europe/Berlin", 64,
                                List.of("Europe/Berlin", "UTC")),
                        AdminPanelRegistry.AdminField.text("calendar_days", "Weekdays", "FRIDAY", 96),
                        AdminPanelRegistry.AdminField.text("calendar_start", "Start", "19:00", 8),
                        AdminPanelRegistry.AdminField.text("calendar_end", "End", "23:00", 8)),
                List.of(new AdminPanelRegistry.AdminAction("portal.calendar", "Apply", false),
                        new AdminPanelRegistry.AdminAction("portal.calendar.clear", "Clear", true))));
        for (PortalDefinition portal : PortalService.portals(server)) {
            entries.add(new AdminPanelRegistry.AdminEntry("Portal: " + portal.id(),
                    portal.templateId() + " · " + portal.shape().serializedName() + " "
                            + portal.width() + "×" + portal.height() + " · "
                            + (PortalService.isOpen(portal) ? "open" : "closed"),
                    PortalService.isOpen(portal) ? GOOD : NORMAL, List.of()));
        }
        if (entries.size() == 5) entries.add(AdminPanelRegistry.AdminEntry.info("No portals placed",
                "Fill in the editor and use Create here."));
        return page(PORTALS, "Portals", PortalService.portals(server).size() + " placed portal(s)", entries);
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
        if (actionId.equals("portal.update")) return updatePortal(player, values);
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
        if (parts.length == 2 && parts[0].equals("instance.expire")) {
            Identifier id = Identifier.parse(parts[1]);
            return PortalService.expire(server, id)
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
                player.getX(), player.getY() + 0.9, player.getZ(), positiveDouble(values, "portal_width"),
                positiveDouble(values, "portal_height"), template,
                Math.multiplyExact(positiveLong(values, "portal_lifetime"), 60L), PortalDefinition.Schedule.always());
        PortalService.create(server, portal);
        return AdminPanelRegistry.ActionResult.success("Created portal " + id + " at your position");
    }

    // Apply all portal editor fields while preserving its location and existing schedule.
    private static AdminPanelRegistry.ActionResult updatePortal(ServerPlayer player, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        Identifier id = formId(values, "portal_id");
        PortalDefinition current = PortalService.portal(server, id);
        if (current == null) return AdminPanelRegistry.ActionResult.failure("Unknown portal " + id);
        PortalDefinition updated = new PortalDefinition(id, current.dimension(),
                PortalDefinition.Shape.parse(required(values, "portal_shape")),
                parsePortalPlane(required(values, "portal_plane")),
                current.centerX(), current.centerY(), current.centerZ(), positiveDouble(values, "portal_width"),
                positiveDouble(values, "portal_height"), formId(values, "portal_template"),
                Math.multiplyExact(positiveLong(values, "portal_lifetime"), 60L), current.schedule());
        PortalService.update(server, updated);
        return AdminPanelRegistry.ActionResult.success("Saved portal edits for " + id);
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

    // Parse a strictly positive decimal form value.
    private static double positiveDouble(Map<String, String> values, String key) {
        double value = Double.parseDouble(required(values, key));
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(key + " must be positive");
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
