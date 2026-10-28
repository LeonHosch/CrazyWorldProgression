package ekuzo.crazyworldprogression.worldboundary.bonus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Bonus areas: safe havens where no monsters spawn naturally, creepers break no blocks, and players get the
 * buffs of registered global bonus area stats.
 *
 * <ul>
 *   <li><b>Bonus area level</b> ({@value #ADD_AREA_LEVEL}): a global level raised by global skill tree nodes, one at a time.</li>
 *   <li><b>Spawn field</b>: a circle around the world spawn. Its radius is set per Bonus area level; the radius of the
 *       highest configured level at or below the current one applies ({@code /bonusarea radius <level> <radius>}).</li>
 *   <li><b>Bonus areas</b>: extra fields with their own center and radius, for outposts
 *       ({@code /bonusarea spot add <name> <radius>}).</li>
 *   <li><b>Field buffs</b>: global stats such as {@code addBonusAreaMovementSpeed(10)} in a global skill tree. While a
 *       player stands in any bonus area they count towards the matching player stat (here addMovementSpeed);
 *       outside they do nothing. A content mod can add more pairs with {@link #registerFieldBuff}.</li>
 * </ul>
 */
public final class BonusAreas {
    public static final String ADD_AREA_LEVEL = "addBonusAreaLevel";
    /** Health healed every 5 seconds inside a field. */
    public static final String ADD_AREA_REGENERATION = "addBonusAreaRegeneration";
    private static final Set<String> LEVEL_STATS = new java.util.LinkedHashSet<>();
    private static final Set<String> REGENERATION_STATS = new java.util.LinkedHashSet<>();
    private static final Map<String, String> BUFFS = new LinkedHashMap<>();
    private static final Map<MinecraftServer, Set<UUID>> INSIDE = new WeakHashMap<>();
    private static String displayName = "Bonus area";

    private BonusAreas() { }

    /** Allows a content mod to brand player-facing area messages. Call during initialization. */
    public static void setDisplayName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("The display name is required");
        displayName = name.trim();
    }

    public static String displayName() { return displayName; }

    public static void initialize() {
        registerLevelStat(ADD_AREA_LEVEL, "stat.crazy-world-progression.bonus_area_level");
        registerRegenerationStat(ADD_AREA_REGENERATION, "stat.crazy-world-progression.bonus_area_regeneration");
        registerFieldBuff("addBonusAreaMovementSpeed", "stat.crazy-world-progression.bonus_area_movement_speed", "addMovementSpeed");
        registerFieldBuff("addBonusAreaJumpStrength", "stat.crazy-world-progression.bonus_area_jump_strength", "addJumpStrength");
        registerFieldBuff("addBonusAreaMiningSpeed", "stat.crazy-world-progression.bonus_area_mining_speed", "addMiningSpeed");
        registerFieldBuff("addBonusAreaAttackSpeed", "stat.crazy-world-progression.bonus_area_attack_speed", "addAttackSpeed");
        registerFieldBuff("addBonusAreaHealth", "stat.crazy-world-progression.bonus_area_health", "addHealth");
        registerFieldBuff("addBonusAreaBlockRange", "stat.crazy-world-progression.bonus_area_block_range", "addBlockInteractionRange");
        registerFieldBuff("addBonusAreaDamageTaken", "stat.crazy-world-progression.bonus_area_damage_taken", SkillStatRegistry.DAMAGE_TAKEN);
        // Legacy expressions remain readable in existing world skill-tree files.
        registerLevelStat("addDivineLevel", "stat.crazy-world-progression.bonus_area_level");
        registerRegenerationStat("addDivineRegeneration", "stat.crazy-world-progression.bonus_area_regeneration");
        registerFieldBuff("addDivineMovementSpeed", "stat.crazy-world-progression.bonus_area_movement_speed", "addMovementSpeed");
        registerFieldBuff("addDivineJumpStrength", "stat.crazy-world-progression.bonus_area_jump_strength", "addJumpStrength");
        registerFieldBuff("addDivineMiningSpeed", "stat.crazy-world-progression.bonus_area_mining_speed", "addMiningSpeed");
        registerFieldBuff("addDivineAttackSpeed", "stat.crazy-world-progression.bonus_area_attack_speed", "addAttackSpeed");
        registerFieldBuff("addDivineHealth", "stat.crazy-world-progression.bonus_area_health", "addHealth");
        registerFieldBuff("addDivineBlockRange", "stat.crazy-world-progression.bonus_area_block_range", "addBlockInteractionRange");
        registerFieldBuff("addDivineDamageTaken", "stat.crazy-world-progression.bonus_area_damage_taken", SkillStatRegistry.DAMAGE_TAKEN);
        ServerTickEvents.END_SERVER_TICK.register(BonusAreas::tick);
        BonusAreaNetworking.initialize();
        ServerTickEvents.END_SERVER_TICK.register(BonusAreaNetworking::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher, "bonusarea");
            registerCommands(dispatcher, "divine");
        });
    }

    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, String root) {
        dispatcher.register(
                Commands.literal(root).executes(BonusAreas::status)
                        .then(Commands.literal("radius").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("level", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1)).executes(BonusAreas::setRadius))
                                        .then(Commands.literal("remove").executes(BonusAreas::removeRadius))))
                        .then(Commands.literal("spot").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("list").executes(BonusAreas::status))
                                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1)).executes(context -> addSpot(context, null)))
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1))
                                                                .executes(context -> addSpot(context, new Vec3(DoubleArgumentType.getDouble(context, "x"), 0,
                                                                        DoubleArgumentType.getDouble(context, "z")))))))))
                                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                                        .executes(BonusAreas::removeSpot)))));
    }

    public static void registerLevelStat(String name, String translationKey) {
        if (!LEVEL_STATS.add(name)) throw new IllegalArgumentException("Duplicate bonus area level stat " + name);
        SkillStatRegistry.registerNumberStat(name, translationKey, 0.0, true,
                value -> {
                    if (value != 1.0) throw new IllegalArgumentException(name + " only accepts 1");
                }, (player, value) -> { });
    }

    public static void registerRegenerationStat(String name, String translationKey) {
        if (!REGENERATION_STATS.add(name)) throw new IllegalArgumentException("Duplicate bonus area regeneration stat " + name);
        SkillStatRegistry.registerNumberStat(name, translationKey, 0.0, true, value -> { }, (player, value) -> { });
    }

    /**
     * Adds a field buff: the global stat {@code bonusStat} counts towards the player stat {@code playerStat} while
     * the player is inside a bonus area. Call from a mod initializer, after CWP has initialized.
     */
    public static void registerFieldBuff(String bonusStat, String translationKey, String playerStat) {
        boolean signed = playerStat.equals(SkillStatRegistry.DAMAGE_TAKEN);
        SkillStatRegistry.registerNumberStat(bonusStat, translationKey, 0.0, !signed, value -> { }, (player, value) -> { });
        BUFFS.put(bonusStat, playerStat);
        SkillStatRegistry.registerDynamicBonus(playerStat, player -> inside(player)
                ? SkillStatRegistry.getGlobalTotal(player.level().getServer(), bonusStat) : 0.0);
    }

    // ---------------------------------------------------------------- fields

    /** Field buff stats: global stat to the player stat it adds to inside an area. */
    public static Map<String, String> fieldBuffs() { return Map.copyOf(BUFFS); }

    /** Configured spawn field radius per Bonus area level, lowest level first. */
    public static Map<Integer, Double> levelRadii(MinecraftServer server) {
        return new java.util.TreeMap<>(BonusAreaData.get(server).levelRadii);
    }

    public static void setLevelRadius(MinecraftServer server, int level, double radius) {
        if (level < 0) throw new IllegalArgumentException("The Bonus area level must be 0 or more");
        if (!(radius >= 1) || !Double.isFinite(radius)) throw new IllegalArgumentException("The radius must be at least 1");
        BonusAreaData data = BonusAreaData.get(server);
        data.levelRadii.put(level, radius);
        data.changed();
        BonusAreaNetworking.broadcast(server);
    }

    public static boolean removeLevelRadius(MinecraftServer server, int level) {
        BonusAreaData data = BonusAreaData.get(server);
        boolean removed = data.levelRadii.remove(level) != null;
        if (removed) {
            data.changed();
            BonusAreaNetworking.broadcast(server);
        }
        return removed;
    }

    /** The bonus areas by name. */
    public static Map<String, BonusAreaData.Spot> spots(MinecraftServer server) {
        return new LinkedHashMap<>(BonusAreaData.get(server).spots);
    }

    /** Content-neutral view of the named areas that grant configured field bonuses. */
    public record BonusArea(String name, String dimension, double x, double z, double radius) { }

    public static java.util.List<BonusArea> bonusAreas(MinecraftServer server) {
        return spots(server).entrySet().stream()
                .map(entry -> new BonusArea(entry.getKey(), entry.getValue().dimension(),
                        entry.getValue().x(), entry.getValue().z(), entry.getValue().radius()))
                .toList();
    }

    public static void setSpot(MinecraftServer server, String name, String dimension, double x, double z, double radius) {
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!key.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Use letters, digits, - and _ for the spot name");
        if (!(radius >= 1) || !Double.isFinite(radius)) throw new IllegalArgumentException("The radius must be at least 1");
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Give a valid center");
        BonusAreaData data = BonusAreaData.get(server);
        data.spots.put(key, new BonusAreaData.Spot(dimension, x, z, radius));
        data.changed();
        BonusAreaNetworking.broadcast(server);
    }

    public static boolean removeSpot(MinecraftServer server, String name) {
        BonusAreaData data = BonusAreaData.get(server);
        boolean removed = data.spots.remove(name.trim().toLowerCase(Locale.ROOT).replace(' ', '_')) != null;
        if (removed) {
            data.changed();
            BonusAreaNetworking.broadcast(server);
        }
        return removed;
    }

    public static int level(MinecraftServer server) {
        return (int) Math.round(LEVEL_STATS.stream().mapToDouble(stat -> SkillStatRegistry.getGlobalTotal(server, stat)).sum());
    }

    public static double regeneration(MinecraftServer server) {
        return REGENERATION_STATS.stream().mapToDouble(stat -> SkillStatRegistry.getGlobalTotal(server, stat)).sum();
    }

    /** The spawn field's radius now: that of the highest configured level at or below the Bonus area level (0 if none). */
    public static double spawnRadius(MinecraftServer server) {
        var entry = BonusAreaData.get(server).levelRadii.floorEntry(level(server));
        return entry == null ? 0.0 : entry.getValue();
    }

    /** True when (x, z) in {@code level} lies in the spawn field or a bonus area. */
    public static boolean contains(ServerLevel level, double x, double z) {
        MinecraftServer server = level.getServer();
        var spawn = server.getRespawnData();
        double radius = spawnRadius(server);
        if (radius > 0 && level.dimension() == spawn.dimension()) {
            double dx = x - (spawn.pos().getX() + 0.5), dz = z - (spawn.pos().getZ() + 0.5);
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        String dimension = level.dimension().identifier().toString();
        for (BonusAreaData.Spot spot : BonusAreaData.get(server).spots.values()) {
            if (!spot.dimension().equals(dimension)) continue;
            double dx = x - spot.x(), dz = z - spot.z();
            if (dx * dx + dz * dz <= spot.radius() * spot.radius()) return true;
        }
        return false;
    }

    public static boolean contains(ServerPlayer player) {
        return contains(player.level(), player.getX(), player.getZ());
    }

    /** Whether {@code player} was inside a field at the last check (what the buffs use). */
    public static boolean inside(ServerPlayer player) {
        Set<UUID> inside = INSIDE.get(player.level().getServer());
        return inside != null && inside.contains(player.getUUID());
    }

    /** Twice a second: who entered or left a field (their stats are refreshed); every 5 seconds the regeneration. */
    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        Set<UUID> inside = INSIDE.computeIfAbsent(server, ignored -> new HashSet<>());
        double regeneration = server.getTickCount() % 100 == 0 ? regeneration(server) : 0;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            boolean now = contains(player);
            boolean before = inside.contains(player.getUUID());
            if (now != before) {
                if (now) inside.add(player.getUUID());
                else inside.remove(player.getUUID());
                SkillStatRegistry.refresh(player);
                player.sendOverlayMessage(Component.literal(now ? "You enter a " + displayName.toLowerCase(Locale.ROOT)
                                : "You leave the " + displayName.toLowerCase(Locale.ROOT))
                        .withStyle(now ? ChatFormatting.GOLD : ChatFormatting.GRAY));
            }
            if (now && regeneration > 0 && player.isAlive() && player.getHealth() < player.getMaxHealth()) player.heal((float) regeneration);
        }
        inside.retainAll(online);
    }

    // ---------------------------------------------------------------- commands

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        BonusAreaData data = BonusAreaData.get(server);
        double radius = spawnRadius(server);
        source.sendSuccess(() -> Component.literal(displayName + " level " + level(server) + " · spawn field radius "
                + (radius > 0 ? fmt(radius) : "none")).withStyle(ChatFormatting.GOLD), false);
        if (!data.levelRadii.isEmpty()) {
            StringBuilder radii = new StringBuilder("Radius per level:");
            data.levelRadii.forEach((level, value) -> radii.append(" ").append(level).append("=").append(fmt(value)));
            source.sendSuccess(() -> Component.literal(radii.toString()).withStyle(ChatFormatting.GRAY), false);
        }
        data.spots.forEach((name, spot) -> source.sendSuccess(() -> Component.literal(displayName + " " + name + ": " + spot.dimension()
                + " x " + fmt(spot.x()) + " z " + fmt(spot.z()) + " radius " + fmt(spot.radius())).withStyle(ChatFormatting.GRAY), false));
        if (source.getEntity() instanceof ServerPlayer player)
            source.sendSuccess(() -> Component.literal(contains(player) ? "You are inside a " + displayName.toLowerCase(Locale.ROOT) + "." : "You are outside the " + displayName.toLowerCase(Locale.ROOT) + "s."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setRadius(CommandContext<CommandSourceStack> context) {
        int level = IntegerArgumentType.getInteger(context, "level");
        double radius = DoubleArgumentType.getDouble(context, "radius");
        setLevelRadius(context.getSource().getServer(), level, radius);
        context.getSource().sendSuccess(() -> Component.literal(displayName + " level " + level + ": spawn field radius " + fmt(radius)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int removeRadius(CommandContext<CommandSourceStack> context) {
        int level = IntegerArgumentType.getInteger(context, "level");
        if (!removeLevelRadius(context.getSource().getServer(), level)) {
            context.getSource().sendFailure(Component.literal("No radius set for " + displayName.toLowerCase(Locale.ROOT) + " level " + level));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Removed the radius of " + displayName.toLowerCase(Locale.ROOT) + " level " + level), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int addSpot(CommandContext<CommandSourceStack> context, Vec3 center) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        double radius = DoubleArgumentType.getDouble(context, "radius");
        Vec3 at = center != null ? center : source.getPosition();
        try {
            setSpot(source.getServer(), name, source.getLevel().dimension().identifier().toString(), at.x, at.z, radius);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(displayName + " " + name + " at x " + fmt(at.x) + " z " + fmt(at.z) + ", radius " + fmt(radius)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int removeSpot(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        if (!removeSpot(context.getSource().getServer(), name)) {
            context.getSource().sendFailure(Component.literal("No " + displayName.toLowerCase(Locale.ROOT) + " named " + name));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Removed " + displayName.toLowerCase(Locale.ROOT) + " " + name), true);
        return Command.SINGLE_SUCCESS;
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
