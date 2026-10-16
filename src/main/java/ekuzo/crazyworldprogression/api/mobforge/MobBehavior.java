package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** Reusable decision logic invoked for a registered mob without replacing vanilla goals. */
@FunctionalInterface
public interface MobBehavior {
    void tick(ServerLevel world, MobForgeHandle handle, Mob mob);
}
