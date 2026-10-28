package ekuzo.crazyworldprogression.mobforge.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class NearestPlayerAiController implements MobAiController {
	@Override public String id() { return "nearest_player"; }
	@Override public void tick(ServerLevel level, Mob mob) {
		MobAiState state = MobAiState.of(mob);
		if (!state.combat) mob.setTarget(null);
		if (mob.tickCount < state.nextRepath) return;
		var player = level.getNearestPlayer(mob, state.range);
		if (player == null || !player.isAlive()) { state.repathDue(mob, 0); mob.getNavigation().stop(); return; }
		if (!state.repathDue(mob, mob.distanceToSqr(player))) return;
		if (state.combat && mob.canAttack(player)) mob.setTarget(player);
		PathReuse.follow(mob, player);
	}
}
