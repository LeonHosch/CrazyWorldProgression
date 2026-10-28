package ekuzo.crazyworldprogression.mobforge.ai;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/**
 * A MobForge mob's AI settings, parsed once from its entity tags instead of on every tick. Kept in a field on the
 * mob itself (see MobMixin), so it costs one small object per MobForge mob and disappears with the mob.
 * MobForgeEntityData drops it whenever it rewrites the AI or definition tags.
 */
public final class MobAiState {
	/** Paths are recalculated at most this often (ticks) close to the target, and less often further away. */
	static final int NEAR_REPATH_TICKS = 20, MID_REPATH_TICKS = 40, FAR_REPATH_TICKS = 60;
	/** A path is kept while the target stays within this many blocks of where it was heading. */
	static final double PATH_REUSE_DISTANCE = 2.0;

	final String mode;
	final boolean combat;
	final double range, arrival;
	final MobTemplate.Position target;
	final String player;
	final Identifier definition;
	final boolean burnsInDaylight;
	/** Game tick of the mob's next path update; starts spread out by entity ID so mobs do not all repath together. */
	int nextRepath;

	private MobAiState(Mob mob) {
		mode = MobForgeEntityData.aiMode(mob).orElse(null);
		combat = MobForgeEntityData.combat(mob);
		range = MobForgeEntityData.range(mob);
		arrival = MobForgeEntityData.arrival(mob);
		target = MobForgeEntityData.target(mob).orElse(null);
		player = MobForgeEntityData.player(mob).orElse(null);
		definition = MobForgeEntityData.definition(mob).orElse(null);
		burnsInDaylight = MobForgeEntityData.burnsInDaylight(mob).orElse(true);
		nextRepath = mob.tickCount + Math.floorMod(mob.getId(), NEAR_REPATH_TICKS);
	}

	public boolean burnsInDaylight() { return burnsInDaylight; }

	/** The cached state, parsed now if the mob has none yet. */
	public static MobAiState of(Mob mob) {
		Holder holder = (Holder) mob;
		MobAiState state = holder.mobforge$aiState();
		if (state == null) holder.mobforge$setAiState(state = new MobAiState(mob));
		return state;
	}

	/** Forget the cached state after the mob's tags changed; the next tick parses them again. */
	public static void invalidate(Object entity) {
		if (entity instanceof Holder holder) holder.mobforge$setAiState(null);
	}

	/** Whether the next path update is due, and if so when the one after it is, from the distance to the goal. */
	boolean repathDue(Mob mob, double distanceSqr) {
		if (mob.tickCount < nextRepath) return false;
		nextRepath = mob.tickCount + (distanceSqr <= 16 * 16 ? NEAR_REPATH_TICKS
				: distanceSqr <= 40 * 40 ? MID_REPATH_TICKS : FAR_REPATH_TICKS);
		return true;
	}

	/** Implemented on every Mob by MobMixin. */
	public interface Holder {
		MobAiState mobforge$aiState();
		void mobforge$setAiState(MobAiState state);
	}
}
