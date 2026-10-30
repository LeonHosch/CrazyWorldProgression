package ekuzo.crazyworldprogression;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** CWP's player ranks. Change who is a builder here (or with permissions.builder in the CWP config). */
public final class CwpPermissions {
    private CwpPermissions() { }

    /**
     * Builders may be in creative or spectator mode, see and move world and workshop helpers, and design workshops:
     * operator level 2 or higher by default, level 1 or higher with {@code permissions.builder: op1}.
     */
    public static boolean isBuilder(ServerPlayer player) {
        return Commands.hasPermission(ekuzo.crazyworldprogression.config.CwpConfig.get().builderLevel() <= 1
                ? Commands.LEVEL_MODERATORS : Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
    }
}
