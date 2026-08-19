package ekuzo.crazyworldprogression.veil;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;

public class VeilManager {

    private static final double WORLD_RADIUS_ONE = 1000.0;
    private static final double WORLD_RADIUS_TWO = 2000.0;

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            server.overworld().getLevelData();

            // Check player locations every second
            if (server.getTickCount() % 20 != 0) {
                return;
            }

            applyVeilDamage(server);
        });
    }

    private static void applyVeilDamage(MinecraftServer server) {
        BlockPos spawn = server.getRespawnData().pos();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Get player location
            double playerx = player.getX() - spawn.getX();
            double playerz = player.getZ() - spawn.getZ();
            // Calculate distance from world spawn
            double distance = Math.sqrt(playerx * playerx + playerz * playerz);

            // If player is in the veil deal damage
            if (distance > WORLD_RADIUS_TWO) {
                player.hurtServer(
                    player.level(),
                    player.damageSources().magic(),
                    5.0F
                );
            }
            // If player is between WORLD_RADIUS_ONE and TWO, deal damage every 5 seconds
            else if (distance > WORLD_RADIUS_ONE & server.getTickCount() % 100 == 0) {
                player.hurtServer(
                    player.level(),
                    player.damageSources().magic(),
                    2.0F
                );
            }
        }
    }
}
