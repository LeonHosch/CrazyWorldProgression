package ekuzo.crazyworldprogression.mobforge.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

public final class TargetPlayerAiController implements MobAiController {
	@Override public String id() { return "target_player"; }
	@Override public void tick(ServerLevel level, Mob mob) {
		MobAiState state = MobAiState.of(mob);
		if (!state.combat) mob.setTarget(null);
		if (mob.tickCount < state.nextRepath || state.player == null) return;
		ServerPlayer player = find(level, state.player);
		if (player == null) { state.repathDue(mob, 0); return; }
		if (!player.isAlive() || player.level() != level) { state.repathDue(mob, 0); mob.getNavigation().stop(); return; }
		if (!state.repathDue(mob, mob.distanceToSqr(player))) return;
		if (state.combat && mob.canAttack(player)) mob.setTarget(player);
		PathReuse.follow(mob, player);
	}
	private static ServerPlayer find(ServerLevel level, String value) {
		try { return level.getServer().getPlayerList().getPlayer(UUID.fromString(value)); }
		catch (IllegalArgumentException ignored) { return level.getServer().getPlayerList().getPlayerByName(value); }
	}
}
