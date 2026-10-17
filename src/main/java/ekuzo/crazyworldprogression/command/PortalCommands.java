package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.portals.PortalDefinition;
import ekuzo.crazyworldprogression.portals.PortalGroups;
import ekuzo.crazyworldprogression.portals.PortalNetworking;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.PortalTemplateService;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Player exit command and game-master authoring tools for the CWP portal-instance framework. */
public final class PortalCommands {
    private static final List<String> SHAPES = List.of("rectangle", "ellipse", "diamond", "triangle");
    private static final List<String> PLANES = List.of("xy", "xz", "yz");
    private static final List<String> COLORS = Arrays.stream(PortalDefinition.PortalColor.values())
            .map(PortalDefinition.PortalColor::serializedName).toList();
    private static final List<String> ENTRY_POLICIES = Arrays.stream(PortalDefinition.EntryPolicy.values())
            .map(PortalDefinition.EntryPolicy::serializedName).toList();

    // Prevent instantiation of the command-tree owner.
    private PortalCommands() {
    }

    // Register the player-accessible emergency/intentional exit command.
    public static void registerPlayer(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("portal")
                .then(Commands.literal("leave").executes(PortalCommands::leave))
                .then(Commands.literal("group")
                        .executes(PortalCommands::groupScreen)
                        .then(Commands.literal("invite").then(Commands.argument("player", StringArgumentType.word())
                                .executes(PortalCommands::groupInvite)))
                        .then(Commands.literal("accept").then(Commands.argument("player", StringArgumentType.word())
                                .executes(PortalCommands::groupAccept)))
                        .then(Commands.literal("leave").executes(PortalCommands::groupLeave))
                        .then(Commands.literal("list").executes(PortalCommands::groupList))));
    }

    // Build the game-master-only branch attached beneath CWP's existing /cwp root.
    public static LiteralArgumentBuilder<CommandSourceStack> adminBranch() {
        return Commands.literal("portals")
                .executes(PortalCommands::summary)
                .then(Commands.literal("list").executes(PortalCommands::list))
                .then(Commands.literal("info").then(portalIdArgument().executes(PortalCommands::info)))
                .then(createBranch())
                .then(Commands.literal("remove").then(portalIdArgument().executes(PortalCommands::remove)))
                .then(Commands.literal("move").then(portalIdArgument()
                        .then(Commands.literal("here").executes(PortalCommands::moveHere))))
                .then(Commands.literal("link").then(portalIdArgument().then(linkTargetArgument()
                        .executes(PortalCommands::link))))
                .then(Commands.literal("color").then(portalIdArgument()
                        .then(colorArgument().executes(PortalCommands::color))))
                .then(Commands.literal("presentation").then(portalIdArgument()
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("description", StringArgumentType.greedyString())
                                        .executes(PortalCommands::presentation)))))
                .then(Commands.literal("entry-policy").then(portalIdArgument()
                        .then(Commands.argument("policy", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        ENTRY_POLICIES, builder)).executes(PortalCommands::entryPolicy))))
                .then(Commands.literal("access").then(portalIdArgument()
                        .then(Commands.literal("public").executes(context -> setAccess(context, false)))
                        .then(Commands.literal("dungeon").then(Commands.argument("max_players",
                                IntegerArgumentType.integer(1, 100))
                                .executes(context -> setAccess(context, true))))
                        .then(Commands.literal("private").then(Commands.argument("max_players",
                                IntegerArgumentType.integer(1, 100))
                                .executes(context -> setAccess(context, true))))))
                .then(Commands.literal("building").then(portalIdArgument()
                        .then(Commands.argument("allowed", BoolArgumentType.bool())
                                .executes(PortalCommands::building))))
                .then(Commands.literal("exits").then(Commands.literal("create")
                        .then(Commands.argument("portal_id", StringArgumentType.word())
                                .executes(PortalCommands::createExit))))
                .then(Commands.literal("difficulty").then(portalIdArgument()
                        .then(Commands.argument("skulls", IntegerArgumentType.integer(1, 5))
                                .executes(PortalCommands::difficulty))))
                .then(Commands.literal("lifetime").then(portalIdArgument()
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 43_200))
                                .executes(PortalCommands::lifetime))))
                .then(scheduleBranch())
                .then(pocketBranch())
                .then(templateBranch())
                .then(instanceBranch());
    }

    // Build portal creation syntax centered on the executing source.
    private static LiteralArgumentBuilder<CommandSourceStack> createBranch() {
        var lifetime = Commands.argument("lifetime_minutes", IntegerArgumentType.integer(0, 43_200))
                .executes(PortalCommands::create)
                .then(colorArgument().executes(PortalCommands::createWithColor));
        var height = Commands.argument("height", DoubleArgumentType.doubleArg(0.5, 256.0)).then(lifetime);
        var width = Commands.argument("width", DoubleArgumentType.doubleArg(0.5, 256.0)).then(height);
        var plane = Commands.argument("plane", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(PLANES, builder)).then(width);
        var shape = Commands.argument("shape", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SHAPES, builder)).then(plane);
        return Commands.literal("create").then(Commands.argument("portal_id", StringArgumentType.word())
                .then(templateIdArgument().then(shape)));
    }

    // Build independent calendar and repeating timer-window editing commands.
    private static LiteralArgumentBuilder<CommandSourceStack> scheduleBranch() {
        return Commands.literal("schedule")
                .then(Commands.literal("periodic").then(portalIdArgument()
                        .then(Commands.argument("every_minutes", IntegerArgumentType.integer(1, 525_600))
                                .then(Commands.argument("open_minutes", IntegerArgumentType.integer(1, 525_600))
                                        .executes(PortalCommands::periodic)))))
                .then(Commands.literal("calendar").then(portalIdArgument()
                        .then(Commands.argument("timezone", StringArgumentType.string())
                                .then(Commands.argument("weekdays", StringArgumentType.string())
                                        .then(Commands.argument("start", StringArgumentType.string())
                                                .then(Commands.argument("end", StringArgumentType.string())
                                                        .executes(PortalCommands::calendar)))))))
                .then(Commands.literal("clear-periodic").then(portalIdArgument()
                        .executes(PortalCommands::clearPeriodic)))
                .then(Commands.literal("clear-calendar").then(portalIdArgument()
                        .executes(PortalCommands::clearCalendar)));
    }

    // Build direct in-world template capture and inspection commands.
    private static LiteralArgumentBuilder<CommandSourceStack> templateBranch() {
        return Commands.literal("templates")
                .executes(PortalCommands::templateList)
                .then(Commands.literal("list").executes(PortalCommands::templateList))
                .then(Commands.literal("capture").then(Commands.argument("template_id", StringArgumentType.word())
                        .executes(PortalCommands::capture)));
    }

    // Build per-pocket authoring commands without exposing a shared workshop.
    private static LiteralArgumentBuilder<CommandSourceStack> pocketBranch() {
        return Commands.literal("pockets")
                .then(Commands.literal("create").then(templateIdArgument().executes(PortalCommands::createPocket)))
                .then(Commands.literal("enter").then(templateIdArgument().executes(PortalCommands::enterPocket)))
                .then(Commands.literal("spawn").then(templateIdArgument()
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(PortalCommands::setPocketSpawn))))))
                .then(Commands.literal("leave").executes(PortalCommands::leavePocket))
                .then(Commands.literal("stresstest").then(templateIdArgument()
                        .then(Commands.argument("size_x", IntegerArgumentType.integer(16, 4096))
                                .then(Commands.argument("size_y", IntegerArgumentType.integer(6, 384))
                                        .then(Commands.argument("size_z", IntegerArgumentType.integer(16, 4096))
                                                .executes(PortalCommands::stressPocket))))));
    }

    // Generate and save a large test pocket to try instance building with.
    private static int stressPocket(CommandContext<CommandSourceStack> context) {
        try {
            ekuzo.crazyworldprogression.portals.StressPocket.start(context.getSource().getPlayerOrException(),
                    identifierArgument(context, "template_id"), IntegerArgumentType.getInteger(context, "size_x"),
                    IntegerArgumentType.getInteger(context, "size_y"), IntegerArgumentType.getInteger(context, "size_z"));
            return 1;
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    // Build active slot diagnostics and forced expiry commands.
    private static LiteralArgumentBuilder<CommandSourceStack> instanceBranch() {
        return Commands.literal("instances")
                .executes(PortalCommands::instanceList)
                .then(Commands.literal("list").executes(PortalCommands::instanceList))
                .then(Commands.literal("expire").then(portalIdArgument().executes(PortalCommands::expire)))
                .then(Commands.literal("expire-slot").then(Commands.argument("slot", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                PortalService.activeInstances(context.getSource().getServer()).stream()
                                        .map(instance -> instance.slotDimension().toString()).toList(), builder))
                        .executes(PortalCommands::expireSlot)));
    }

    // Show framework template, portal, slot, and active-instance totals.
    private static int summary(CommandContext<CommandSourceStack> context) {
        int templates = PortalTemplateService.templates(context.getSource().getServer()).size();
        int portals = PortalService.portals(context.getSource().getServer()).size();
        int instances = PortalService.activeInstances(context.getSource().getServer()).size();
        context.getSource().sendSuccess(() -> Component.literal("CWP portals: " + templates + " templates, "
                + portals + " portal definitions, " + instances + "/" + PortalRegistry.instanceSlots().size()
                + " active instance slots").withStyle(ChatFormatting.AQUA), false);
        return portals;
    }

    // List placed portals and their current entry availability.
    private static int list(CommandContext<CommandSourceStack> context) {
        List<PortalDefinition> portals = PortalService.portals(context.getSource().getServer());
        for (PortalDefinition portal : portals) {
            context.getSource().sendSuccess(() -> Component.literal(portal.id() + " -> " + portal.templateId()
                    + " [" + portal.shape().serializedName() + "/" + portal.plane().serializedName() + ", "
                    + (PortalService.isOpen(portal) ? "OPEN" : "closed") + "]").withStyle(ChatFormatting.GRAY), false);
        }
        return portals.size();
    }

    // Display complete geometry, schedule, template, and lifetime details for one portal.
    private static int info(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        PortalDefinition.Schedule schedule = portal.schedule();
        context.getSource().sendSuccess(() -> Component.literal("Portal " + portal.id() + " -> "
                + (portal.isExit() ? "Exit" : portal.templateId()) + " · " + portal.effectiveDisplayName())
                .withStyle(ChatFormatting.GOLD), false);
        context.getSource().sendSuccess(() -> Component.literal("Locations "
                + PortalService.locations(context.getSource().getServer(), portal.id()) + ", "
                + portal.shape().serializedName() + " " + portal.width() + "x" + portal.height() + " on "
                + portal.plane().serializedName() + ", color "
                + PortalDefinition.PortalColor.displayName(portal.colorRgb()) + " (#"
                + String.format("%06X", portal.colorRgb()) + ")").withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("Lifetime "
                + (portal.lifetimeSeconds() == 0L ? "no expiry" : portal.lifetimeSeconds() + "s")
                + "; calendar " + (schedule.calendarEnabled() ? schedule.weekdays() + " " + schedule.start()
                + "-" + schedule.end() + " " + schedule.timezone() : "off")
                + "; periodic " + (schedule.periodSeconds() == 0L ? "off"
                : schedule.openSeconds() + "s every " + schedule.periodSeconds() + "s"))
                .withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("Description: "
                + (portal.description().isBlank() ? "(none)" : portal.description()))
                .withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("Player entry policy: "
                + portal.entryPolicy().serializedName()).withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("Access: "
                + portal.admission().mode().serializedName() + (portal.admission().mode() == PortalDefinition.Mode.PRIVATE
                ? ", max group " + portal.admission().maxPlayers() : ""))
                .withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("Difficulty: " + portal.difficulty()
                + "/5 skulls").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // Create a directly linked portal at the command source position.
    private static int create(CommandContext<CommandSourceStack> context) {
        return create(context, PortalDefinition.DEFAULT_COLOR_RGB);
    }

    // Create a portal using the explicitly selected named color.
    private static int createWithColor(CommandContext<CommandSourceStack> context) {
        return create(context, PortalDefinition.PortalColor.parse(
                StringArgumentType.getString(context, "color")).rgb());
    }

    // Create a directly linked portal at the command source position with one RGB color.
    private static int create(CommandContext<CommandSourceStack> context, int colorRgb) {
        try {
            Identifier id = identifierArgument(context, "portal_id");
            Identifier template = identifierArgument(context, "template_id");
            PortalDefinition.Shape shape = PortalDefinition.Shape.parse(StringArgumentType.getString(context, "shape"));
            PortalDefinition.Plane plane = PortalDefinition.Plane.parse(StringArgumentType.getString(context, "plane"));
            var position = context.getSource().getPosition();
            PortalDefinition portal = new PortalDefinition(id,
                    context.getSource().getLevel().dimension().identifier(), shape, plane,
                    position.x, position.y, position.z, DoubleArgumentType.getDouble(context, "width"),
                    DoubleArgumentType.getDouble(context, "height"), template,
                    IntegerArgumentType.getInteger(context, "lifetime_minutes") * 60L,
                    colorRgb, PortalDefinition.Schedule.always());
            PortalService.create(context.getSource().getServer(), portal);
            return success(context, "Created portal " + id + " linked to " + template);
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    // Change one existing portal's flat render color without altering its geometry or schedule.
    private static int color(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        PortalDefinition.PortalColor color = PortalDefinition.PortalColor.parse(
                StringArgumentType.getString(context, "color"));
        PortalService.update(context.getSource().getServer(), portal.withColor(color.rgb()));
        return success(context, "Set portal " + portal.id() + " color to " + color.serializedName());
    }

    // Change the name and description displayed by one portal's travel confirmation screen.
    private static int presentation(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        String name = StringArgumentType.getString(context, "name").trim();
        String description = StringArgumentType.getString(context, "description").trim();
        if (name.isEmpty()) return failure(context, new IllegalArgumentException("Portal name must not be empty"));
        PortalService.update(context.getSource().getServer(), portal.withPresentation(name, description));
        return success(context, "Updated presentation text for portal " + portal.id());
    }

    // Change whether player entry is repeatable, permanent-once, or reset with each instance.
    private static int entryPolicy(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        PortalDefinition.EntryPolicy policy = PortalDefinition.EntryPolicy.parse(
                StringArgumentType.getString(context, "policy"));
        PortalService.update(context.getSource().getServer(), portal.withEntryPolicy(policy));
        return success(context, "Set portal " + portal.id() + " entry policy to " + policy.serializedName());
    }

    private static int setAccess(CommandContext<CommandSourceStack> context, boolean privateMode) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        int max = privateMode ? IntegerArgumentType.getInteger(context, "max_players")
                : portal.admission().maxPlayers();
        try {
            PortalService.update(context.getSource().getServer(), portal.withAdmission(portal.admission().withAccess(
                    privateMode ? PortalDefinition.Mode.PRIVATE : PortalDefinition.Mode.PUBLIC, max)));
            return success(context, "Set " + portal.id() + " to " + (privateMode ? "dungeon, max party " + max : "public"));
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    private static int building(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        boolean allowed = BoolArgumentType.getBool(context, "allowed");
        PortalService.update(context.getSource().getServer(), portal.withAllowBuilding(allowed));
        return success(context, "Building in " + portal.id() + " instances: " + allowed);
    }

    private static int createExit(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        Identifier pocket = PocketEditorService.pocketForDimension(context.getSource().getServer(),
                player.level().dimension().identifier());
        if (pocket == null) return failure(context, new IllegalArgumentException("Enter a pocket editor first"));
        Identifier id = Identifier.parse(StringArgumentType.getString(context, "portal_id"));
        PortalDefinition exit = new PortalDefinition(id, player.level().dimension().identifier(),
                PortalDefinition.Shape.RECTANGLE, PortalDefinition.Plane.XY,
                player.getX(), player.getY() + 0.9, player.getZ(), 4, 6,
                PortalDefinition.EXIT_TEMPLATE, 5_400L, PortalDefinition.DEFAULT_COLOR_RGB,
                "Exit", "Return to the main world", 1, PortalDefinition.EntryPolicy.REPEATABLE,
                PortalDefinition.Schedule.always());
        PortalService.create(context.getSource().getServer(), exit);
        return success(context, "Created exit portal " + id + " in " + pocket);
    }

    private static int setPocketSpawn(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        Identifier id = identifierArgument(context, "template_id");
        if (!player.level().dimension().identifier().equals(PocketEditorService.dimension(
                context.getSource().getServer(), id))) {
            return failure(context, new IllegalArgumentException("Enter that pocket editor first"));
        }
        BlockPos position = new BlockPos(IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
        PocketEditorService.setEntry(context.getSource().getServer(), id, position);
        return success(context, "Preferred pocket spawn set to " + position.toShortString());
    }

    private static int groupScreen(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        PortalNetworking.openGroupScreen(player);
        return 1;
    }

    private static ServerPlayer groupTarget(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException("Player is not online: " + name);
        return target;
    }

    private static int groupInvite(CommandContext<CommandSourceStack> context) {
        try {
            PortalGroups.invite(context.getSource().getPlayerOrException(), groupTarget(context));
            return 1;
        } catch (Exception exception) { return failure(context, new IllegalArgumentException(exception.getMessage())); }
    }

    private static int groupAccept(CommandContext<CommandSourceStack> context) {
        try {
            PortalGroups.accept(context.getSource().getPlayerOrException(), groupTarget(context));
            return 1;
        } catch (Exception exception) { return failure(context, new IllegalArgumentException(exception.getMessage())); }
    }

    private static int groupLeave(CommandContext<CommandSourceStack> context) {
        PortalGroups.leave(context.getSource().getPlayer());
        return success(context, "Left portal group");
    }

    private static int groupList(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        return success(context, "Portal group: " + String.join(", ",
                PortalGroups.names(context.getSource().getServer(), player.getUUID())));
    }

    // Change the one-to-five-skull difficulty displayed before portal travel.
    private static int difficulty(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        int skulls = IntegerArgumentType.getInteger(context, "skulls");
        PortalService.update(context.getSource().getServer(), portal.withDifficulty(skulls));
        return success(context, "Set portal " + portal.id() + " difficulty to " + skulls + " skulls");
    }

    // Remove a portal and expire its active instance first.
    private static int remove(CommandContext<CommandSourceStack> context) {
        Identifier id = portalArgument(context);
        if (!PortalService.remove(context.getSource().getServer(), id)) {
            context.getSource().sendFailure(Component.literal("Unknown portal: " + id));
            return 0;
        }
        return success(context, "Removed portal " + id);
    }

    // Move one portal to the source position and dimension.
    private static int moveHere(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        try {
            PortalService.setLocation(context.getSource().getServer(), portal.id(), "original",
                    context.getSource().getLevel().dimension().identifier(), context.getSource().getPosition());
            return success(context, "Set original location for " + portal.id());
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    // Link an existing portal directly to a different CWP template.
    private static int link(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        try {
            Identifier template = linkArgument(context);
            PortalService.update(context.getSource().getServer(), portal.withTemplate(template));
            return success(context, "Linked portal " + portal.id() + " to " + template);
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    // Change the real-time lifetime applied to the next instance created by one portal.
    private static int lifetime(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        long seconds = IntegerArgumentType.getInteger(context, "minutes") * 60L;
        try {
            PortalService.update(context.getSource().getServer(), portal.withLifetime(seconds));
            return success(context, "Set portal " + portal.id() + " lifetime to " + seconds + " seconds");
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    // Set a repeating entry window anchored at command execution time.
    private static int periodic(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        long period = IntegerArgumentType.getInteger(context, "every_minutes") * 60L;
        long open = IntegerArgumentType.getInteger(context, "open_minutes") * 60L;
        try {
            PortalService.update(context.getSource().getServer(), portal.withSchedule(
                    portal.schedule().withPeriodic(period, open, Instant.now())));
            return success(context, "Set portal " + portal.id() + " to open " + open + "s every " + period + "s");
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    // Set a recurring weekday and local-time entry gate while retaining the periodic timer.
    private static int calendar(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        try {
            ZoneId zone = ZoneId.of(StringArgumentType.getString(context, "timezone"));
            Set<DayOfWeek> days = parseDays(StringArgumentType.getString(context, "weekdays"));
            LocalTime start = LocalTime.parse(StringArgumentType.getString(context, "start"));
            LocalTime end = LocalTime.parse(StringArgumentType.getString(context, "end"));
            PortalService.update(context.getSource().getServer(), portal.withSchedule(
                    portal.schedule().withCalendar(days, start, end, zone)));
            return success(context, "Set calendar gate for portal " + portal.id());
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    // Disable only one portal's repeating timer gate.
    private static int clearPeriodic(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        PortalService.update(context.getSource().getServer(), portal.withSchedule(portal.schedule().withoutPeriodic()));
        return success(context, "Cleared periodic gate for portal " + portal.id());
    }

    // Disable only one portal's calendar gate.
    private static int clearCalendar(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        PortalService.update(context.getSource().getServer(), portal.withSchedule(portal.schedule().withoutCalendar()));
        return success(context, "Cleared calendar gate for portal " + portal.id());
    }

    // Capture every built block in the player's current pocket editor dimension.
    private static int capture(CommandContext<CommandSourceStack> context) {
        try {
            Identifier id = identifierArgument(context, "template_id");
            var player = context.getSource().getPlayerOrException();
            PortalTemplateService.TemplateInfo template = PortalTemplateService.captureAll(
                    context.getSource().getServer(), context.getSource().getLevel(), id, player.blockPosition(),
                    context.getSource().getTextName());
            return success(context, "Captured template " + id + " (" + template.sizeX() + "x"
                    + template.sizeY() + "x" + template.sizeZ() + ", " + template.volume() + " blocks)");
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    private static int createPocket(CommandContext<CommandSourceStack> context) {
        try {
            var id = identifierArgument(context, "template_id");
            ekuzo.crazyworldprogression.portals.PocketEditorService.create(context.getSource().getServer(), id);
            context.getSource().sendSuccess(() -> Component.literal("Created pocket " + id), true);
            return 1;
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    private static int enterPocket(CommandContext<CommandSourceStack> context) {
        try {
            PortalService.enterPocketEditor(context.getSource().getPlayerOrException(),
                    identifierArgument(context, "template_id"));
            return 1;
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    private static int leavePocket(CommandContext<CommandSourceStack> context) {
        try {
            if (!PortalService.leavePocketEditor(context.getSource().getPlayerOrException())) {
                context.getSource().sendFailure(Component.literal("You are not inside a pocket editor"));
                return 0;
            }
            return 1;
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    // List CWP-hosted template metadata.
    private static int templateList(CommandContext<CommandSourceStack> context) {
        List<PortalTemplateService.TemplateInfo> templates =
                PortalTemplateService.templates(context.getSource().getServer());
        templates.forEach(template -> context.getSource().sendSuccess(() -> Component.literal(template.id()
                + " - " + template.sizeX() + "x" + template.sizeY() + "x" + template.sizeZ()
                + ", spawn " + template.spawnX() + " " + template.spawnY() + " " + template.spawnZ())
                .withStyle(ChatFormatting.GRAY), false));
        return templates.size();
    }

    // List active reconstructed instances and their real-time expiry.
    private static int instanceList(CommandContext<CommandSourceStack> context) {
        List<PortalService.ActiveInstance> instances = PortalService.activeInstances(context.getSource().getServer());
        instances.forEach(instance -> context.getSource().sendSuccess(() -> Component.literal(instance.portalId()
                + " -> " + instance.templateId() + " in " + instance.slotDimension() + ", "
                + (instance.expires().toEpochMilli() == Long.MAX_VALUE ? "no automatic expiry"
                : "expires " + instance.expires())).withStyle(ChatFormatting.GRAY), false));
        return instances.size();
    }

    // Force-expire every active instance owned by one portal.
    private static int expire(CommandContext<CommandSourceStack> context) {
        Identifier portal = portalArgument(context);
        if (!PortalService.expire(context.getSource().getServer(), portal)) {
            context.getSource().sendFailure(Component.literal("Portal has no active instance: " + portal));
            return 0;
        }
        return success(context, "Expiring all instances for portal " + portal);
    }

    private static int expireSlot(CommandContext<CommandSourceStack> context) {
        Identifier slot = Identifier.parse(StringArgumentType.getString(context, "slot"));
        if (!PortalService.expireInstance(context.getSource().getServer(), slot)) {
            context.getSource().sendFailure(Component.literal("No active instance in slot " + slot));
            return 0;
        }
        return success(context, "Expiring instance in " + slot);
    }

    // Return a player from a leased dimension slot to their remembered source position.
    private static int leave(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!PortalService.leave(player)) {
                context.getSource().sendFailure(Component.literal("You are not inside a CWP portal instance"));
                return 0;
            }
            return 1;
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    // Create a portal-ID argument with live persistent suggestions.
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> portalIdArgument() {
        return Commands.argument("portal_id", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        PortalService.portals(context.getSource().getServer()).stream()
                                .map(portal -> portal.id().toString()).toList(), builder));
    }

    // Create a template-ID argument with live CWP-hosted suggestions.
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> templateIdArgument() {
        return Commands.argument("template_id", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        PortalTemplateService.templates(context.getSource().getServer()).stream()
                                .map(template -> template.id().toString()).toList(), builder));
    }

    private static Identifier linkArgument(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "template_id");
        return value.equalsIgnoreCase("Exit") ? PortalDefinition.EXIT_TEMPLATE : Identifier.parse(value);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> linkTargetArgument() {
        return Commands.argument("template_id", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        java.util.stream.Stream.concat(java.util.stream.Stream.of("Exit"),
                                PortalTemplateService.templates(context.getSource().getServer()).stream()
                                        .map(template -> template.id().toString())).toList(), builder));
    }

    // Create a named portal-color argument with all built-in presets as suggestions.
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> colorArgument() {
        return Commands.argument("color", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(COLORS, builder));
    }

    // Resolve one portal argument or send a consistent unknown-ID error.
    private static PortalDefinition requirePortal(CommandContext<CommandSourceStack> context) {
        Identifier id = portalArgument(context);
        PortalDefinition portal = PortalService.portal(context.getSource().getServer(), id);
        if (portal == null) context.getSource().sendFailure(Component.literal("Unknown portal: " + id));
        return portal;
    }

    // Parse the shared portal argument.
    private static Identifier portalArgument(CommandContext<CommandSourceStack> context) {
        return identifierArgument(context, "portal_id");
    }

    // Parse a synchronized string argument as an ID, defaulting convenient bare names to CWP's namespace.
    private static Identifier identifierArgument(CommandContext<CommandSourceStack> context, String name) {
        String value = StringArgumentType.getString(context, name);
        return value.contains(":") ? Identifier.parse(value) : CrazyWorldProgression.id(value);
    }

    // Parse comma-separated ISO weekday names and convenient three-letter abbreviations.
    private static Set<DayOfWeek> parseDays(String value) {
        return Arrays.stream(value.split(",")).map(day -> {
            String normalized = day.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(DayOfWeek.values())
                    .filter(candidate -> candidate.name().equals(normalized)
                            || candidate.name().startsWith(normalized))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown weekday " + day));
        }).collect(Collectors.toUnmodifiableSet());
    }

    // Send one broadcast operator success message.
    private static int success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // Convert command exceptions into concise feedback without leaking a Brigadier stack trace.
    private static int failure(CommandContext<CommandSourceStack> context, Exception exception) {
        context.getSource().sendFailure(Component.literal(exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage()));
        return 0;
    }

}
