package ekuzo.crazyworldprogression.mobforge.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/** Keeps a mob's current path while its goal has barely moved, instead of pathfinding again. */
final class PathReuse {
	private PathReuse() { }

	/** True while the mob is still walking a path that ends within PATH_REUSE_DISTANCE of the point. */
	static boolean heading(Mob mob, double x, double y, double z) {
		var navigation = mob.getNavigation();
		if (!navigation.isInProgress() || navigation.getTargetPos() == null) return false;
		double limit = MobAiState.PATH_REUSE_DISTANCE;
		return navigation.getTargetPos().distToCenterSqr(x, y, z) <= limit * limit;
	}

	static void follow(Mob mob, Entity target) {
		if (!heading(mob, target.getX(), target.getY(), target.getZ())) mob.getNavigation().moveTo(target, 1.0);
	}
}
