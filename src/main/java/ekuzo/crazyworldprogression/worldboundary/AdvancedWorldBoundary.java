package ekuzo.crazyworldprogression.worldboundary;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import ekuzo.crazyworldprogression.worldboundary.restriction.RestrictionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class AdvancedWorldBoundary {
    public static final String ADD_BOUNDARY_LEVEL = "addAdvancedWorldBoundaryLevel";
    private static final java.util.Set<String> LEVEL_STATS = new java.util.LinkedHashSet<>();
    private static final Map<MinecraftServer, Integer> LAST_LEVELS = new WeakHashMap<>();

    // Prevent instantiation of the server-side boundary event handler.
    private AdvancedWorldBoundary() { }

    // Check player distances once per second and apply zone-specific periodic damage.
    public static void initialize() {
        registerLevelStat(ADD_BOUNDARY_LEVEL, "stat.crazy-world-progression.world_boundary_level");
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                BoundaryRadiusNetworking.send(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 0) {
                int level = getLevel(server);
                Integer previous = LAST_LEVELS.put(server, level);
                if (previous != null && previous != level) BoundaryRadiusNetworking.syncAll(server);
                applyBoundaryDamage(server);
            }
        });
    }

    /** A content mod can add a themed progression expression for the same generic boundary level. */
    public static void registerLevelStat(String functionName, String translationKey) {
        if (!LEVEL_STATS.add(functionName)) throw new IllegalArgumentException("Duplicate boundary level stat " + functionName);
        SkillStatRegistry.registerNumberStat(functionName, translationKey, 0.0, true,
                AdvancedWorldBoundary::validateLevelIncrement,
                (player, total) -> {
                    RestrictionService.refreshLevel(player.level().getServer());
                    BoundaryRadiusNetworking.syncAll(player.level().getServer());
                });
    }

    // Damage players only in dimensions configured at the current exact Advanced World Boundary level.
    private static void applyBoundaryDamage(MinecraftServer server) {
        int level = getLevel(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Radii radii = getRadii(server, player.level().dimension().identifier(), level);
            if (radii == null) continue;
            double distance = distanceFromSpawn(server, player);
            if (distance > radii.outer()) {
                player.hurtServer(player.level(), player.damageSources().magic(), 5.0F);
            } else if (distance > radii.inner() && server.getTickCount() % 100 == 0) {
                player.hurtServer(player.level(), player.damageSources().magic(), 2.0F);
            }
        }
    }

    // Return the current global boundary level represented by unlocked progression expressions.
    public static int getLevel(MinecraftServer server) {
        return levelFromTotal(LEVEL_STATS.stream().mapToDouble(stat -> SkillStatRegistry.getGlobalTotal(server, stat)).sum());
    }

    // Use overworld spawn only in the overworld; other dimensions center their own radius at origin.
    public static double distanceFromSpawn(MinecraftServer server, ServerPlayer player) {
        BlockPos center = player.level() == server.overworld() ? server.getRespawnData().pos() : BlockPos.ZERO;
        double x = player.getX() - center.getX();
        double z = player.getZ() - center.getZ();
        return Math.sqrt(x * x + z * z);
    }

    private static String key(Identifier dimension, int level) {
        return dimension + "|" + level;
    }

    public static Radii getRadii(MinecraftServer server, Identifier dimension, int level) {
        return BoundaryRadiusData.get(server).values().get(key(dimension, level));
    }

    public static Map<String, Radii> configuredRadii(MinecraftServer server) {
        return BoundaryRadiusData.get(server).values();
    }

    public static Map<String, Radii> currentRadii(MinecraftServer server) {
        int level = getLevel(server);
        Map<String, Radii> result = new LinkedHashMap<>();
        for (Map.Entry<String, Radii> entry : configuredRadii(server).entrySet()) {
            if (entry.getKey().endsWith("|" + level)) {
                result.put(entry.getKey().substring(0, entry.getKey().lastIndexOf('|')), entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    /** Named bonus areas share the world-boundary management surface and use CWP's field-bonus rules. */
    public static java.util.List<BonusAreaService.Area> bonusAreas(MinecraftServer server) {
        return BonusAreaService.areas(server);
    }

    public static void setBonusArea(MinecraftServer server, String name, Identifier dimension,
                                    double x, double z, double radius) {
        BonusAreaService.setArea(server, name, dimension, x, z, radius);
    }

    public static boolean removeBonusArea(MinecraftServer server, String name) {
        return BonusAreaService.removeArea(server, name);
    }

    public static void setRadii(MinecraftServer server, Identifier dimension, int level,
                                double inner, double outer) {
        if (server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dimension)) == null) {
            throw new IllegalArgumentException("Unknown dimension " + dimension);
        }
        if (level < 0) throw new IllegalArgumentException("Advanced World Boundary level must be non-negative");
        BoundaryRadiusData.get(server).put(key(dimension, level), new Radii(inner, outer));
        BoundaryRadiusNetworking.syncAll(server);
    }

    public static boolean removeRadii(MinecraftServer server, Identifier dimension, int level) {
        boolean removed = BoundaryRadiusData.get(server).remove(key(dimension, level));
        if (removed) BoundaryRadiusNetworking.syncAll(server);
        return removed;
    }

    public record Radii(double inner, double outer) {
        public Radii {
            if (!Double.isFinite(inner) || !Double.isFinite(outer) || inner <= 0 || outer <= inner) {
                throw new IllegalArgumentException("Radii must be finite and 0 < inner < outer");
            }
        }
    }

    // Restrict every boundary-level skill expression to the single permitted +1 increment.
    private static void validateLevelIncrement(double value) {
        if (value != 1.0) {
            throw new IllegalArgumentException("Boundary level expressions only accept 1");
        }
    }

    // Convert an accumulated stat total into its exact bounded integer representation.
    private static int levelFromTotal(double total) {
        if (total < 0.0 || total > Integer.MAX_VALUE || total != Math.rint(total)) {
            throw new IllegalStateException("Invalid cumulative Advanced World Boundary level: " + total);
        }
        return (int) total;
    }
}
