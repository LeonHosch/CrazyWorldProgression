package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Narrow script capability for any Minecraft entity. */
public interface ScriptEntityHandle {
    UUID uuid();
    Identifier entityId();
    boolean isAlive();
    double health();
    double maxHealth();
    ServerLevel world();
    Vec3 position();
    void teleport(Vec3 position);
    void kill();
}
