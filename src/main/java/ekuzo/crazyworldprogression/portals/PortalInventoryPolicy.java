package ekuzo.crazyworldprogression.portals;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player death rule for portal instances; the vanilla gamerule remains untouched elsewhere. */
public final class PortalInventoryPolicy {
    private static final Map<UUID, Boolean> DEATH_RULES = new ConcurrentHashMap<>();

    private PortalInventoryPolicy() { }

    public static boolean onDrop(Player player, boolean vanillaRule) {
        if (!(player instanceof ServerPlayer serverPlayer)) return vanillaRule;
        Boolean override = PortalService.keepInventoryOverride(serverPlayer);
        if (override == null) {
            DEATH_RULES.remove(serverPlayer.getUUID());
            return vanillaRule;
        }
        DEATH_RULES.put(serverPlayer.getUUID(), override);
        return override;
    }

    public static boolean onRestore(ServerPlayer previous, boolean alive, boolean vanillaRule) {
        if (alive) {
            DEATH_RULES.remove(previous.getUUID());
            return vanillaRule;
        }
        Boolean override = DEATH_RULES.remove(previous.getUUID());
        if (override == null) override = PortalService.keepInventoryOverride(previous);
        return override == null ? vanillaRule : override;
    }
}
