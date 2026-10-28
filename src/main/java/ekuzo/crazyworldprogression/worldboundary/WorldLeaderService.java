package ekuzo.crazyworldprogression.worldboundary;

import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/** Content-neutral leadership state for a world's progression policy. */
public final class WorldLeaderService {
    private WorldLeaderService() { }

    public static Optional<UUID> getLeader(MinecraftServer server) {
        return WorldLeaderData.get(server).leader();
    }

    public static boolean isLeader(MinecraftServer server, UUID player) {
        return getLeader(server).filter(player::equals).isPresent();
    }

    public static void setLeader(MinecraftServer server, UUID player) {
        WorldLeaderData.get(server).setLeader(player);
    }

    public static void clearLeader(MinecraftServer server) {
        WorldLeaderData.get(server).clearLeader();
    }
}
