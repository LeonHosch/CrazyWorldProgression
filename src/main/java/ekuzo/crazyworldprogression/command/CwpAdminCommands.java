package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import ekuzo.crazyworldprogression.admin.AdminPanelNetworking;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.events.RandomEventDefinition;
import ekuzo.crazyworldprogression.events.RandomEventManager;
import ekuzo.crazyworldprogression.events.RandomEventRegistry;
import ekuzo.crazyworldprogression.events.RandomEventService;
import ekuzo.crazyworldprogression.progression.skilltrees.BaselineStatRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeManager;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeRegistry;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.PortalTemplateService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillTreeType.GLOBAL;
import static ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillTreeType.PERSONAL;

/** Operator diagnostics and controlled debugging actions for every CWP framework subsystem. */
public final class CwpAdminCommands {
    private static final int PAGE_SIZE = 8;

    // Prevent instantiation of the command-tree owner.
    private CwpAdminCommands() {
    }

    // Register the complete game-master-only /cwp command hierarchy.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cwp")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CwpAdminCommands::status)
                .then(Commands.literal("admin").executes(CwpAdminCommands::openAdminPanel))
                .then(Commands.literal("status").executes(CwpAdminCommands::status))
                .then(Commands.literal("help").executes(CwpAdminCommands::help))
                .then(eventsBranch())
                .then(currenciesBranch())
                .then(skillTreesBranch())
                .then(statsBranch())
                .then(PortalCommands.adminBranch())
                .then(Commands.literal("sources").executes(CwpAdminCommands::sources))
                .then(Commands.literal("reload").executes(CwpAdminCommands::reload)));
    }

    // Open CWP's modular administration dashboard for the executing game master.
    private static int openAdminPanel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return AdminPanelNetworking.open(context.getSource().getPlayerOrException()) ? 1 : 0;
    }

    // Build event inspection, force-start, and force-stop debugging branches.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> eventsBranch() {
        return Commands.literal("events")
                .executes(CwpAdminCommands::eventSummary)
                .then(pagedLiteral("list", CwpAdminCommands::eventList))
                .then(pagedLiteral("active", CwpAdminCommands::activeEventList))
                .then(pagedLiteral("blackouts", CwpAdminCommands::blackoutList))
                .then(Commands.literal("info").then(Commands.argument("event", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(eventIds(), builder))
                        .executes(CwpAdminCommands::eventInfo)))
                .then(Commands.literal("start").then(Commands.argument("event", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(eventIds(), builder))
                        .executes(CwpAdminCommands::eventStart)))
                .then(Commands.literal("stop").then(Commands.argument("event", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(activeEventIds(context), builder))
                        .executes(CwpAdminCommands::eventStop)));
    }

    // Build currency registry list and detailed-definition inspection branches.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> currenciesBranch() {
        return Commands.literal("currencies")
                .executes(context -> currencyList(context, 1))
                .then(pagedLiteral("list", CwpAdminCommands::currencyList))
                .then(Commands.literal("info").then(Commands.argument("currency", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(currencyIds(), builder))
                        .executes(CwpAdminCommands::currencyInfo)));
    }

    // Build skill-tree list and definition inspection branches.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> skillTreesBranch() {
        return Commands.literal("skilltrees")
                .executes(context -> skillTreeList(context, 1))
                .then(pagedLiteral("list", CwpAdminCommands::skillTreeList))
                .then(Commands.literal("info").then(Commands.argument("tree", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(skillTreeIds(), builder))
                        .executes(CwpAdminCommands::skillTreeInfo)));
    }

    // Build registered-stat diagnostics and a safe online-player refresh action.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> statsBranch() {
        return Commands.literal("stats")
                .executes(CwpAdminCommands::stats)
                .then(Commands.literal("refresh").executes(CwpAdminCommands::refreshStats));
    }

    // Build a literal whose optional positive page argument delegates to one common handler shape.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> pagedLiteral(
            String name, PagedCommand command) {
        return Commands.literal(name)
                .executes(context -> command.run(context, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> command.run(context, IntegerArgumentType.getInteger(context, "page"))));
    }

    // Show a compact framework health overview when an operator runs /cwp or /cwp status.
    private static int status(CommandContext<CommandSourceStack> context) {
        List<SkillTreeDefinition> trees = SkillTreeManager.getSkillTrees();
        long globalTrees = trees.stream().filter(tree -> tree.type() == GLOBAL).count();
        long personalTrees = trees.stream().filter(tree -> tree.type() == PERSONAL).count();
        int nodes = trees.stream().mapToInt(tree -> tree.skills().size()).sum();
        int activeEvents = RandomEventService.activeEvents(context.getSource().getServer()).size();
        header(context.getSource(), "CWP framework status");
        line(context.getSource(), "Currencies: " + CurrencyRegistry.values().size(), ChatFormatting.AQUA);
        line(context.getSource(), "Skill trees: " + trees.size() + " (" + globalTrees + " global, "
                + personalTrees + " personal, " + nodes + " nodes)", ChatFormatting.AQUA);
        line(context.getSource(), "Stat functions: " + SkillStatRegistry.registeredFunctions().size()
                + ", baseline modifiers: " + BaselineStatRegistry.loadedModifierCount(), ChatFormatting.AQUA);
        line(context.getSource(), "Random events: " + RandomEventManager.definitions().size() + " loaded, "
                + activeEvents + " active, " + RandomEventRegistry.handlerCount() + " handlers", ChatFormatting.AQUA);
        line(context.getSource(), "Blackout windows: " + RandomEventManager.blackoutWindows().size()
                + " (currently " + (RandomEventService.isBlackoutActive() ? "ACTIVE" : "clear") + ")",
                RandomEventService.isBlackoutActive() ? ChatFormatting.RED : ChatFormatting.GREEN);
        line(context.getSource(), "Portals: " + PortalService.portals(context.getSource().getServer()).size()
                + " placed, " + PortalTemplateService.templates(context.getSource().getServer()).size()
                + " templates, " + PortalService.activeInstances(context.getSource().getServer()).size() + "/"
                + PortalRegistry.instanceSlots().size() + " slots active", ChatFormatting.AQUA);
        return 1;
    }

    // Print the discoverable command hierarchy without relying on external documentation.
    private static int help(CommandContext<CommandSourceStack> context) {
        header(context.getSource(), "CWP admin commands");
        line(context.getSource(), "/cwp admin - open the modular administration panel", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp status - framework health overview", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp events list|active|blackouts|info|start|stop", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp currencies list|info", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp skilltrees list|info", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp stats [refresh]", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp portals - templates, placed portals, schedules, and instances", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp sources - registered integration sources", ChatFormatting.GRAY);
        line(context.getSource(), "/cwp reload - reload YAML and refresh online stats", ChatFormatting.GRAY);
        return 1;
    }

    // Summarize scheduler configuration, handler coverage, active state, and blackout state.
    private static int eventSummary(CommandContext<CommandSourceStack> context) {
        header(context.getSource(), "Random events");
        line(context.getSource(), "Loaded definitions: " + RandomEventManager.definitions().size(), ChatFormatting.AQUA);
        line(context.getSource(), "Registered sources: " + RandomEventRegistry.sources().size(), ChatFormatting.AQUA);
        line(context.getSource(), "Registered handlers: " + RandomEventRegistry.handlerCount(), ChatFormatting.AQUA);
        line(context.getSource(), "Active instances: "
                + RandomEventService.activeEvents(context.getSource().getServer()).size(), ChatFormatting.AQUA);
        line(context.getSource(), "Blackout windows: " + RandomEventManager.blackoutWindows().size()
                + ", currently " + (RandomEventService.isBlackoutActive() ? "ACTIVE" : "clear"),
                RandomEventService.isBlackoutActive() ? ChatFormatting.RED : ChatFormatting.GREEN);
        line(context.getSource(), "Use /cwp events list to see names and schedules.", ChatFormatting.GRAY);
        return 1;
    }

    // List loaded event names with trigger odds, handler coverage, and active markers.
    private static int eventList(CommandContext<CommandSourceStack> context, int page) {
        List<RandomEventDefinition> events = RandomEventManager.definitions();
        PageWindow window = pageWindow(context.getSource(), events.size(), page, "events");
        if (window == null) return 0;
        header(context.getSource(), "Random events " + window.label());
        for (RandomEventDefinition event : events.subList(window.start(), window.end())) {
            boolean active = RandomEventService.isActive(context.getSource().getServer(), event.id());
            String flags = (active ? "ACTIVE, " : "")
                    + (RandomEventRegistry.hasHandler(event.id()) ? "handler" : "NO HANDLER");
            line(context.getSource(), event.id() + " [" + flags + "] - " + triggerSummary(event.trigger()),
                    active ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        }
        return events.size();
    }

    // List persisted active event instances with their remaining duration.
    private static int activeEventList(CommandContext<CommandSourceStack> context, int page) {
        List<RandomEventService.ActiveRandomEvent> events = RandomEventService.activeEvents(context.getSource().getServer());
        PageWindow window = pageWindow(context.getSource(), events.size(), page, "active events");
        if (window == null) return 0;
        header(context.getSource(), "Active random events " + window.label());
        for (RandomEventService.ActiveRandomEvent event : events.subList(window.start(), window.end())) {
            line(context.getSource(), event.id() + " - " + remainingSummary(context, event)
                    + ", metadata keys: " + event.metadata().size(), ChatFormatting.GREEN);
        }
        return events.size();
    }

    // List configured global blackout windows and mark those blocking random starts right now.
    private static int blackoutList(CommandContext<CommandSourceStack> context, int page) {
        List<RandomEventDefinition.TimeRestriction> windows = RandomEventManager.blackoutWindows();
        PageWindow window = pageWindow(context.getSource(), windows.size(), page, "blackout windows");
        if (window == null) return 0;
        header(context.getSource(), "Random-event blackouts " + window.label());
        Instant now = Instant.now();
        for (int index = window.start(); index < window.end(); index++) {
            RandomEventDefinition.TimeRestriction blackout = windows.get(index);
            boolean active = blackout.allows(now);
            line(context.getSource(), "#" + (index + 1) + " [" + (active ? "ACTIVE" : "inactive") + "] - "
                    + restrictionSummary(blackout), active ? ChatFormatting.RED : ChatFormatting.GRAY);
        }
        return windows.size();
    }

    // Display every scheduling and runtime field relevant to one event definition.
    private static int eventInfo(CommandContext<CommandSourceStack> context) {
        Identifier id = argumentId(context, "event");
        if (id == null) return 0;
        RandomEventDefinition event = RandomEventManager.definition(id);
        if (event == null) return unknown(context.getSource(), "random event", id);
        header(context.getSource(), "Random event: " + id);
        line(context.getSource(), "Trigger: " + triggerSummary(event.trigger()), ChatFormatting.AQUA);
        line(context.getSource(), "Duration: " + durationSummary(event.duration()), ChatFormatting.AQUA);
        line(context.getSource(), "Availability: " + restrictionSummary(event.availability()), ChatFormatting.AQUA);
        line(context.getSource(), "Handler: " + (RandomEventRegistry.hasHandler(id) ? "registered" : "MISSING"),
                RandomEventRegistry.hasHandler(id) ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        var active = RandomEventService.activeEvent(context.getSource().getServer(), id);
        line(context.getSource(), "State: " + (active.isPresent() ? "ACTIVE" : "inactive"),
                active.isPresent() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
        active.ifPresent(instance -> {
            line(context.getSource(), "Instance: #" + instance.sequence() + ", started "
                    + Instant.ofEpochMilli(instance.startedEpochMillis()) + ", "
                    + remainingSummary(context, instance), ChatFormatting.GRAY);
            line(context.getSource(), "Metadata: " + (instance.metadata().isEmpty() ? "none" : instance.metadata()),
                    ChatFormatting.GRAY);
        });
        return 1;
    }

    // Force-start one configured event while preserving normal duplicate-active protection.
    private static int eventStart(CommandContext<CommandSourceStack> context) {
        Identifier id = argumentId(context, "event");
        if (id == null) return 0;
        try {
            if (!RandomEventService.startNow(context.getSource().getServer(), id)) {
                context.getSource().sendFailure(Component.literal("Event is already active: " + id));
                return 0;
            }
            success(context.getSource(), "Started random event " + id, true);
            return 1;
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    // Manually stop one active event and execute its registered cleanup handler.
    private static int eventStop(CommandContext<CommandSourceStack> context) {
        Identifier id = argumentId(context, "event");
        if (id == null) return 0;
        if (!RandomEventService.stop(context.getSource().getServer(), id)) {
            context.getSource().sendFailure(Component.literal("Event is not active: " + id));
            return 0;
        }
        success(context.getSource(), "Stopped random event " + id, true);
        return 1;
    }

    // List registered currencies in deterministic registration order.
    private static int currencyList(CommandContext<CommandSourceStack> context, int page) {
        List<CurrencyDefinition> currencies = CurrencyRegistry.values();
        PageWindow window = pageWindow(context.getSource(), currencies.size(), page, "currencies");
        if (window == null) return 0;
        header(context.getSource(), "Currencies " + window.label());
        for (CurrencyDefinition currency : currencies.subList(window.start(), window.end())) {
            line(context.getSource(), currency.id() + " - " + currency.displayName() + " ("
                    + currency.abbreviation() + ", " + currency.scope().name().toLowerCase(Locale.ROOT) + ")",
                    currency.color());
        }
        return currencies.size();
    }

    // Display one currency's complete framework registration metadata.
    private static int currencyInfo(CommandContext<CommandSourceStack> context) {
        Identifier id = argumentId(context, "currency");
        if (id == null) return 0;
        CurrencyDefinition currency = CurrencyRegistry.get(id);
        if (currency == null) return unknown(context.getSource(), "currency", id);
        header(context.getSource(), "Currency: " + id);
        line(context.getSource(), "Name: " + currency.displayName() + " (" + currency.abbreviation() + ")", currency.color());
        line(context.getSource(), "Scope: " + currency.scope().name().toLowerCase(Locale.ROOT), ChatFormatting.AQUA);
        line(context.getSource(), "Commands: " + String.join(", ", currency.commands()), ChatFormatting.AQUA);
        line(context.getSource(), "Icon: " + currency.icon() + " [" + currency.iconU() + "," + currency.iconV()
                + " " + currency.iconWidth() + "x" + currency.iconHeight() + "]", ChatFormatting.GRAY);
        return 1;
    }

    // List loaded trees with their type, priority, node count, and policy marker.
    private static int skillTreeList(CommandContext<CommandSourceStack> context, int page) {
        List<SkillTreeDefinition> trees = SkillTreeManager.getSkillTrees();
        PageWindow window = pageWindow(context.getSource(), trees.size(), page, "skill trees");
        if (window == null) return 0;
        header(context.getSource(), "Skill trees " + window.label());
        for (SkillTreeDefinition tree : trees.subList(window.start(), window.end())) {
            line(context.getSource(), tree.id() + " - " + tree.name() + " ["
                    + tree.type().name().toLowerCase(Locale.ROOT) + ", priority " + tree.priority() + ", "
                    + tree.skills().size() + " nodes" + (SkillTreeRegistry.hasPurchasePolicy(tree.id()) ? ", policy" : "")
                    + "]", ChatFormatting.AQUA);
        }
        return trees.size();
    }

    // Display structural totals and dependencies for one loaded skill tree.
    private static int skillTreeInfo(CommandContext<CommandSourceStack> context) {
        Identifier id = argumentId(context, "tree");
        if (id == null) return 0;
        SkillTreeDefinition tree = SkillTreeManager.findTree(id.toString());
        if (tree == null) return unknown(context.getSource(), "skill tree", id);
        int expressions = tree.skills().stream().mapToInt(node -> node.stats().size()).sum();
        Set<Identifier> currencies = tree.skills().stream().flatMap(node -> node.costs().keySet().stream())
                .collect(Collectors.toSet());
        header(context.getSource(), "Skill tree: " + id);
        line(context.getSource(), "Name: " + tree.name() + " / " + tree.germanName(), ChatFormatting.AQUA);
        line(context.getSource(), "Type: " + tree.type().name().toLowerCase(Locale.ROOT)
                + ", priority: " + tree.priority(), ChatFormatting.AQUA);
        line(context.getSource(), "Nodes: " + tree.skills().size() + ", stat expressions: " + expressions,
                ChatFormatting.AQUA);
        line(context.getSource(), "Cost currencies: " + (currencies.isEmpty() ? "none" : currencies), ChatFormatting.GRAY);
        line(context.getSource(), "Purchase policy: "
                + (SkillTreeRegistry.hasPurchasePolicy(id) ? "registered" : "default allow"), ChatFormatting.GRAY);
        line(context.getSource(), "Icon: " + tree.icon(), ChatFormatting.GRAY);
        return 1;
    }

    // Show registered stat functions plus loaded baseline profile coverage.
    private static int stats(CommandContext<CommandSourceStack> context) {
        header(context.getSource(), "Stats and baselines");
        line(context.getSource(), "Registered functions (" + SkillStatRegistry.registeredFunctions().size() + "): "
                + String.join(", ", SkillStatRegistry.registeredFunctions()), ChatFormatting.AQUA);
        line(context.getSource(), "Baseline profiles: " + BaselineStatRegistry.sources().size(), ChatFormatting.AQUA);
        line(context.getSource(), "Loaded baseline modifiers: " + BaselineStatRegistry.loadedModifierCount(), ChatFormatting.AQUA);
        line(context.getSource(), "Affected baseline stats: "
                + (BaselineStatRegistry.loadedStatKeys().isEmpty() ? "none" : BaselineStatRegistry.loadedStatKeys()),
                ChatFormatting.GRAY);
        return 1;
    }

    // Reapply current baseline and progression modifiers to every connected player.
    private static int refreshStats(CommandContext<CommandSourceStack> context) {
        var players = context.getSource().getServer().getPlayerList().getPlayers();
        SkillStatRegistry.refreshAll(players);
        success(context.getSource(), "Refreshed CWP stats for " + players.size() + " online player(s)", true);
        return players.size();
    }

    // List every dependent-mod resource registered with the framework loaders.
    private static int sources(CommandContext<CommandSourceStack> context) {
        header(context.getSource(), "Registered CWP sources");
        line(context.getSource(), "Event sources (" + RandomEventRegistry.sources().size() + "):", ChatFormatting.AQUA);
        RandomEventRegistry.sources().forEach(source -> line(context.getSource(), "  " + source.namespace() + " <- "
                + source.modId() + ":" + source.resourcePath(), ChatFormatting.GRAY));
        line(context.getSource(), "Skill-tree sources (" + SkillTreeRegistry.sources().size() + "):", ChatFormatting.AQUA);
        SkillTreeRegistry.sources().forEach(source -> line(context.getSource(), "  " + source.namespace() + " <- "
                + source.modId() + ":" + source.resourceDirectory(), ChatFormatting.GRAY));
        line(context.getSource(), "Baseline profiles (" + BaselineStatRegistry.sources().size() + "):", ChatFormatting.AQUA);
        BaselineStatRegistry.sources().forEach(source -> line(context.getSource(), "  " + source.profileId() + " <- "
                + source.modId() + ":" + source.resourcePath(), ChatFormatting.GRAY));
        return 1;
    }

    // Reload every YAML-backed subsystem, reconcile removed events, and refresh connected players.
    private static int reload(CommandContext<CommandSourceStack> context) {
        try {
            BaselineStatRegistry.reload();
            SkillTreeManager.reload();
            RandomEventManager.reload();
            int removedEvents = RandomEventService.reconcileDefinitions(context.getSource().getServer());
            SkillStatRegistry.refreshAll(context.getSource().getServer().getPlayerList().getPlayers());
            success(context.getSource(), "Reloaded CWP: " + SkillTreeManager.getSkillTrees().size() + " trees, "
                    + RandomEventManager.definitions().size() + " events, "
                    + BaselineStatRegistry.loadedModifierCount() + " baseline modifiers; ended "
                    + removedEvents + " removed active event(s)", true);
            return 1;
        } catch (IOException | RuntimeException exception) {
            context.getSource().sendFailure(Component.literal("CWP reload failed: " + exception.getMessage()));
            return 0;
        }
    }

    // Convert one loaded event trigger into concise operator-facing scheduling text.
    private static String triggerSummary(RandomEventDefinition.Trigger trigger) {
        if (trigger instanceof RandomEventDefinition.InGamePhaseTrigger game) {
            return "in-game " + game.phase().name().toLowerCase(Locale.ROOT) + " roll at "
                    + percent(game.chance());
        }
        RandomEventDefinition.RealTimeTrigger real = (RandomEventDefinition.RealTimeTrigger) trigger;
        return "real-time roll every " + formatSeconds(real.intervalSeconds()) + " at " + percent(real.chance());
    }

    // Format real-time seconds or game-time ticks without hiding which clock controls expiry.
    private static String durationSummary(RandomEventDefinition.EventDuration duration) {
        if (duration.clock() == RandomEventDefinition.DurationClock.REAL_TIME) {
            return formatSeconds(duration.amount()) + " real time";
        }
        return duration.amount() + " game ticks (" + formatSeconds(duration.amount() / 20L) + ")";
    }

    // Format a recurring weekday/time restriction with its explicit timezone.
    private static String restrictionSummary(RandomEventDefinition.TimeRestriction restriction) {
        String days = restriction.weekdays().stream().sorted(Comparator.comparingInt(DayOfWeek::getValue))
                .map(day -> day.name().substring(0, 3)).collect(Collectors.joining(","));
        String time = restriction.start().equals(restriction.end()) ? "all day"
                : restriction.start() + "-" + restriction.end();
        return days + " " + time + " " + restriction.timezone();
    }

    // Calculate remaining expiry time using the clock selected by the stored event definition.
    private static String remainingSummary(CommandContext<CommandSourceStack> context,
                                           RandomEventService.ActiveRandomEvent event) {
        if (event.endsEpochMillis() >= 0L) {
            long millis = Math.max(0L, event.endsEpochMillis() - Instant.now().toEpochMilli());
            return formatSeconds((millis + 999L) / 1_000L) + " remaining";
        }
        long ticks = Math.max(0L, event.endsGameTime() - context.getSource().getServer().overworld().getGameTime());
        return ticks + " game ticks (" + formatSeconds((ticks + 19L) / 20L) + ") remaining";
    }

    // Format a probability as a percentage with useful precision for low-chance events.
    private static String percent(double chance) {
        return String.format(Locale.ROOT, "%.3f%%", chance * 100.0).replaceAll("0+%$", "%").replace(".%", "%");
    }

    // Render a duration using compact day, hour, minute, and second units.
    private static String formatSeconds(long seconds) {
        long days = seconds / 86_400L;
        long hours = seconds % 86_400L / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        long remainder = seconds % 60L;
        List<String> parts = new ArrayList<>();
        if (days > 0L) parts.add(days + "d");
        if (hours > 0L) parts.add(hours + "h");
        if (minutes > 0L) parts.add(minutes + "m");
        if (remainder > 0L || parts.isEmpty()) parts.add(remainder + "s");
        return String.join(" ", parts);
    }

    // Parse a namespaced command argument and report malformed identifiers without throwing into Brigadier.
    private static Identifier argumentId(CommandContext<CommandSourceStack> context, String argument) {
        String value = StringArgumentType.getString(context, argument);
        Identifier id = Identifier.tryParse(value);
        if (id == null || !value.contains(":")) {
            context.getSource().sendFailure(Component.literal("Expected a complete namespaced ID, for example mod:event"));
            return null;
        }
        return id;
    }

    // Return loaded event identifiers for Brigadier autocomplete.
    private static List<String> eventIds() {
        return RandomEventManager.definitions().stream().map(event -> event.id().toString()).toList();
    }

    // Return active event identifiers for the stop-command autocomplete provider.
    private static List<String> activeEventIds(CommandContext<CommandSourceStack> context) {
        return RandomEventService.activeEvents(context.getSource().getServer()).stream()
                .map(event -> event.id().toString()).toList();
    }

    // Return registered currency identifiers for Brigadier autocomplete.
    private static List<String> currencyIds() {
        return CurrencyRegistry.values().stream().map(currency -> currency.id().toString()).toList();
    }

    // Return loaded skill-tree identifiers for Brigadier autocomplete.
    private static List<String> skillTreeIds() {
        return SkillTreeManager.getSkillTrees().stream().map(tree -> tree.id().toString()).toList();
    }

    // Validate a requested page and calculate its bounded list slice.
    private static PageWindow pageWindow(CommandSourceStack source, int size, int page, String noun) {
        int pages = Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > pages) {
            source.sendFailure(Component.literal("Page " + page + " does not exist; " + noun + " have " + pages + " page(s)."));
            return null;
        }
        int start = Math.min(size, (page - 1) * PAGE_SIZE);
        return new PageWindow(start, Math.min(size, start + PAGE_SIZE), page, pages, size);
    }

    // Send one consistent gold section title.
    private static void header(CommandSourceStack source, String text) {
        line(source, "--- " + text + " ---", ChatFormatting.GOLD);
    }

    // Send one non-broadcast diagnostic line with the requested formatting.
    private static void line(CommandSourceStack source, String text, ChatFormatting formatting) {
        source.sendSuccess(() -> Component.literal(text).withStyle(formatting), false);
    }

    // Send a successful operator action and optionally mirror it to other administrators.
    private static void success(CommandSourceStack source, String text, boolean broadcast) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), broadcast);
    }

    // Report a valid but unknown framework identifier.
    private static int unknown(CommandSourceStack source, String type, Identifier id) {
        source.sendFailure(Component.literal("Unknown " + type + ": " + id));
        return 0;
    }

    @FunctionalInterface
    private interface PagedCommand {
        // Execute one list command for its validated positive page number.
        int run(CommandContext<CommandSourceStack> context, int page);
    }

    private record PageWindow(int start, int end, int page, int pages, int total) {
        // Format a stable page/count label shared by every paginated listing.
        private String label() {
            return "(page " + page + "/" + pages + ", " + total + " total)";
        }
    }
}
