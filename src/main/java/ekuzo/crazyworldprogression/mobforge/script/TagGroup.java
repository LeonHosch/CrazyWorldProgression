package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.api.mobforge.MobForgeHandle;
import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every mob a script spawned with one tag. Conditions treat the group as one mob: dead once none are alive,
 * health is the combined health of the living members, phase is the first living MobForge member's phase.
 */
final class TagGroup implements ScriptEntityHandle {
    private final List<ScriptEntityHandle> members = new ArrayList<>();

    void add(ScriptEntityHandle member) { members.add(member); }

    List<ScriptEntityHandle> living() {
        return members.stream().filter(ScriptEntityHandle::isAlive).toList();
    }

    /** Living tagged MobForge mobs, which can cast abilities and change phase. */
    List<MobForgeHandle> livingMobForge() {
        return living().stream().filter(MobForgeHandle.class::isInstance).map(MobForgeHandle.class::cast).toList();
    }

    String phase() {
        return livingMobForge().stream().findFirst().flatMap(MobForgeHandle::phase).orElse("");
    }

    private ScriptEntityHandle first() {
        List<ScriptEntityHandle> living = living();
        return living.isEmpty() ? members.getFirst() : living.getFirst();
    }

    @Override public UUID uuid() { return first().uuid(); }
    @Override public Identifier entityId() { return first().entityId(); }
    @Override public boolean isAlive() { return !living().isEmpty(); }
    @Override public double health() { return living().stream().mapToDouble(ScriptEntityHandle::health).sum(); }
    @Override public double maxHealth() { return living().stream().mapToDouble(ScriptEntityHandle::maxHealth).sum(); }
    @Override public ServerLevel world() { return first().world(); }
    @Override public Vec3 position() { return first().position(); }
    @Override public void teleport(Vec3 position) { living().forEach(member -> member.teleport(position)); }
    @Override public void kill() { living().forEach(ScriptEntityHandle::kill); }
}
