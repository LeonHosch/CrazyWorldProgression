package ekuzo.crazyworldprogression.mobforge.ai;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

public final class TargetPlayerAiController implements MobAiController {
	@Override public String id() { return "target_player"; }
	@Override public void tick(ServerLevel level, Mob mob) {
		if (!MobForgeEntityData.combat(mob)) mob.setTarget(null);
		if (mob.tickCount % 20 != Math.floorMod(mob.getId(), 20)) return;
		MobForgeEntityData.player(mob).map(value -> find(level, value)).ifPresent(player -> {
			if (!player.isAlive() || player.level() != level) { mob.getNavigation().stop(); return; }
			if (MobForgeEntityData.combat(mob) && mob.canAttack(player)) mob.setTarget(player);
			mob.getNavigation().moveTo(player, 1.0);
		});
	}
	private static ServerPlayer find(ServerLevel level, String value) {
		try { return level.getServer().getPlayerList().getPlayer(UUID.fromString(value)); }
		catch (IllegalArgumentException ignored) { return level.getServer().getPlayerList().getPlayerByName(value); }
	}
}
