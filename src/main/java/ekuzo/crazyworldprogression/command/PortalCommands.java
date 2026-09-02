package ekuzo.crazyworldprogression.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.portals.PortalDefinition;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.PortalTemplateService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
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

    // Prevent instantiation of the command-tree owner.
    private PortalCommands() {
    }

    // Register the player-accessible emergency/intentional exit command.
    public static void registerPlayer(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("portal")
                .then(Commands.literal("leave").executes(PortalCommands::leave)));
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
                .then(Commands.literal("link").then(portalIdArgument().then(templateIdArgument()
                        .executes(PortalCommands::link))))
                .then(Commands.literal("lifetime").then(portalIdArgument()
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 43_200))
                                .executes(PortalCommands::lifetime))))
                .then(scheduleBranch())
                .then(workshopBranch())
                .then(templateBranch())
                .then(instanceBranch());
    }

    // Build portal creation syntax centered on the executing source.
    private static LiteralArgumentBuilder<CommandSourceStack> createBranch() {
        return Commands.literal("create").then(Commands.argument("portal_id", StringArgumentType.word())
                .then(templateIdArgument()
                        .then(Commands.argument("shape", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(SHAPES, builder))
                                .then(Commands.argument("plane", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(PLANES, builder))
                                        .then(Commands.argument("width", DoubleArgumentType.doubleArg(0.5, 256.0))
                                                .then(Commands.argument("height", DoubleArgumentType.doubleArg(0.5, 256.0))
                                                        .then(Commands.argument("lifetime_minutes",
                                                                        IntegerArgumentType.integer(1, 43_200))
                                                                .executes(PortalCommands::create))))))));
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
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("spawn", BlockPosArgument.blockPos())
                                                .executes(PortalCommands::capture))))));
    }

    // Build entry and return commands for CWP's persistent template-authoring void dimension.
    private static LiteralArgumentBuilder<CommandSourceStack> workshopBranch() {
        return Commands.literal("workshop")
                .then(Commands.literal("enter").executes(PortalCommands::enterWorkshop))
                .then(Commands.literal("leave").executes(PortalCommands::leaveWorkshop));
    }

    // Build active slot diagnostics and forced expiry commands.
    private static LiteralArgumentBuilder<CommandSourceStack> instanceBranch() {
        return Commands.literal("instances")
                .executes(PortalCommands::instanceList)
                .then(Commands.literal("list").executes(PortalCommands::instanceList))
                .then(Commands.literal("expire").then(portalIdArgument().executes(PortalCommands::expire)));
    }

    // Show framework template, portal, slot, and active-instance totals.
    private static int summary(CommandContext<CommandSourceStack> context) {
        int templates = PortalTemplateService.templates(context.getSource().getServer()).size();
        int portals = PortalService.portals(context.getSource().getServer()).size();
        int instances = PortalService.activeInstances(context.getSource().getServer()).size();
        context.getSource().sendSuccess(() -> Component.literal("CWP portals: " + templates + " templates, "
                + portals + " placed portals, " + instances + "/" + PortalRegistry.instanceSlots().size()
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
                + portal.templateId()).withStyle(ChatFormatting.GOLD), false);
        context.getSource().sendSuccess(() -> Component.literal("Dimension " + portal.dimension() + ", center "
                + portal.centerX() + " " + portal.centerY() + " " + portal.centerZ() + ", "
                + portal.shape().serializedName() + " " + portal.width() + "x" + portal.height() + " on "
                + portal.plane().serializedName()).withStyle(ChatFormatting.GRAY), false);
        context.getSource().sendSuccess(() -> Component.literal("Lifetime " + portal.lifetimeSeconds()
                + "s; calendar " + schedule.weekdays() + " " + schedule.start() + "-" + schedule.end() + " "
                + schedule.timezone() + "; periodic " + (schedule.periodSeconds() == 0L ? "off"
                : schedule.openSeconds() + "s every " + schedule.periodSeconds() + "s"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // Create a directly linked portal at the command source position.
    private static int create(CommandContext<CommandSourceStack> context) {
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
                    PortalDefinition.Schedule.always());
            PortalService.create(context.getSource().getServer(), portal);
            return success(context, "Created portal " + id + " linked to " + template);
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
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
            PortalService.update(context.getSource().getServer(), portal.movedTo(
                    context.getSource().getLevel().dimension().identifier(), context.getSource().getPosition()));
            return success(context, "Moved portal " + portal.id());
        } catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    // Link an existing portal directly to a different CWP template.
    private static int link(CommandContext<CommandSourceStack> context) {
        PortalDefinition portal = requirePortal(context);
        if (portal == null) return 0;
        try {
            Identifier template = identifierArgument(context, "template_id");
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
        PortalService.update(context.getSource().getServer(), portal.withLifetime(seconds));
        return success(context, "Set portal " + portal.id() + " lifetime to " + seconds + " seconds");
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

    // Capture a loaded cuboid and its relative entry point into CWP world template storage.
    private static int capture(CommandContext<CommandSourceStack> context) {
        try {
            Identifier id = identifierArgument(context, "template_id");
            BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
            BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");
            BlockPos spawn = BlockPosArgument.getLoadedBlockPos(context, "spawn");
            PortalTemplateService.TemplateInfo template = PortalTemplateService.capture(
                    context.getSource().getServer(), context.getSource().getLevel(), id, from, to, spawn,
                    context.getSource().getTextName());
            return success(context, "Captured template " + id + " (" + template.sizeX() + "x"
                    + template.sizeY() + "x" + template.sizeZ() + ", " + template.volume() + " blocks)");
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    // Teleport one game master into the dedicated persistent template-authoring dimension.
    private static int enterWorkshop(CommandContext<CommandSourceStack> context) {
        try {
            PortalService.enterWorkshop(context.getSource().getPlayerOrException());
            return 1;
        } catch (Exception exception) {
            return failure(context, exception);
        }
    }

    // Return one game master from the template workshop.
    private static int leaveWorkshop(CommandContext<CommandSourceStack> context) {
        try {
            if (!PortalService.leaveWorkshop(context.getSource().getPlayerOrException())) {
                context.getSource().sendFailure(Component.literal("You are not inside the CWP portal workshop"));
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
                + " -> " + instance.templateId() + " in " + instance.slotDimension() + ", expires "
                + instance.expires()).withStyle(ChatFormatting.GRAY), false));
        return instances.size();
    }

    // Force-expire one portal's active instance.
    private static int expire(CommandContext<CommandSourceStack> context) {
        Identifier portal = portalArgument(context);
        if (!PortalService.expire(context.getSource().getServer(), portal)) {
            context.getSource().sendFailure(Component.literal("Portal has no active instance: " + portal));
            return 0;
        }
        return success(context, "Expired instance for portal " + portal);
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
