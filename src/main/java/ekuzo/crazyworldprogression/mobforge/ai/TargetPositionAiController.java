package ekuzo.crazyworldprogression.mobforge.ai;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class TargetPositionAiController implements MobAiController {
	@Override public String id() { return "target_position"; }
	@Override public void tick(ServerLevel level, Mob mob) {
		if (!MobForgeEntityData.combat(mob)) mob.setTarget(null);
		if (mob.tickCount % 20 != Math.floorMod(mob.getId(), 20)) return;
		if (MobForgeEntityData.combat(mob) && mob.getTarget() != null && mob.getTarget().isAlive()) return;
		MobForgeEntityData.target(mob).ifPresent(target -> {
			double radius = MobForgeEntityData.arrival(mob);
			if (mob.distanceToSqr(target.x(), target.y(), target.z()) <= radius * radius) mob.getNavigation().stop();
			else mob.getNavigation().moveTo(target.x(), target.y(), target.z(), 1.0);
		});
	}
}
