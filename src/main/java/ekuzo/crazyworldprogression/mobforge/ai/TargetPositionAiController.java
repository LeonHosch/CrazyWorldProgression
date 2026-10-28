package ekuzo.crazyworldprogression.mobforge.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class TargetPositionAiController implements MobAiController {
	@Override public String id() { return "target_position"; }
	@Override public void tick(ServerLevel level, Mob mob) {
		MobAiState state = MobAiState.of(mob);
		if (!state.combat) mob.setTarget(null);
		if (mob.tickCount < state.nextRepath || state.target == null) return;
		var target = state.target;
		double distanceSqr = mob.distanceToSqr(target.x(), target.y(), target.z());
		state.repathDue(mob, distanceSqr);
		if (state.combat && mob.getTarget() != null && mob.getTarget().isAlive()) return;
		if (distanceSqr <= state.arrival * state.arrival) { if (mob.getNavigation().isInProgress()) mob.getNavigation().stop(); }
		else if (!PathReuse.heading(mob, target.x(), target.y(), target.z()))
			mob.getNavigation().moveTo(target.x(), target.y(), target.z(), 1.0);
	}
}
