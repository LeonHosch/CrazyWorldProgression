package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Server-thread context for an ability; the target may be absent. */
public record MobAbilityContext(MobForgeHandle caster, Entity target, ServerLevel world, Vec3 position) { }
