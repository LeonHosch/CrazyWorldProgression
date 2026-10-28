package ekuzo.crazyworldprogression.worldboundary;

import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;

/** Named areas and spawn-area radii whose configured global bonuses apply to players inside. */
public final class BonusAreaService {
    private BonusAreaService() { }

    public record Area(String name, Identifier dimension, double x, double z, double radius) { }

    public static List<Area> areas(MinecraftServer server) {
        return BonusAreas.bonusAreas(server).stream()
                .map(area -> new Area(area.name(), Identifier.parse(area.dimension()),
                        area.x(), area.z(), area.radius())).toList();
    }

    public static void setArea(MinecraftServer server, String name, Identifier dimension,
                               double x, double z, double radius) {
        BonusAreas.setSpot(server, name, dimension.toString(), x, z, radius);
    }

    public static boolean removeArea(MinecraftServer server, String name) {
        return BonusAreas.removeSpot(server, name);
    }

    public static boolean contains(ServerPlayer player) { return BonusAreas.contains(player); }

    public static int level(MinecraftServer server) { return BonusAreas.level(server); }

    public static double spawnRadius(MinecraftServer server) { return BonusAreas.spawnRadius(server); }

    public static Map<Integer, Double> configuredSpawnRadii(MinecraftServer server) {
        return BonusAreas.levelRadii(server);
    }

    public static void setSpawnRadius(MinecraftServer server, int level, double radius) {
        BonusAreas.setLevelRadius(server, level, radius);
    }

    public static boolean removeSpawnRadius(MinecraftServer server, int level) {
        return BonusAreas.removeLevelRadius(server, level);
    }
}
