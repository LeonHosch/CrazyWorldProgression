package ekuzo.crazyworldprogression.mobforge.ai;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class NearestPlayerAiController implements MobAiController {
	@Override public String id() { return "nearest_player"; }
	@Override public void tick(ServerLevel level, Mob mob) {
		if (!MobForgeEntityData.combat(mob)) mob.setTarget(null);
		if (mob.tickCount % 20 != Math.floorMod(mob.getId(), 20)) return;
		var player = level.getNearestPlayer(mob, MobForgeEntityData.range(mob));
		if (player == null || !player.isAlive()) { mob.getNavigation().stop(); return; }
		if (MobForgeEntityData.combat(mob) && mob.canAttack(player)) mob.setTarget(player);
		mob.getNavigation().moveTo(player, 1.0);
	}
}
