package ekuzo.crazyworldprogression.mobforge.ai;

import ekuzo.crazyworldprogression.mobforge.mixin.MobGoalSelectorsAccessor;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Exclusively suspends both vanilla goal selectors while a MobForge controller owns the mob. */
final class VanillaGoalStateManager {
	private static final Map<Mob, GoalState> SUSPENDED = new WeakHashMap<>();
	private VanillaGoalStateManager() { }

	static void applyMode(Mob mob, String mode) {
		if ("vanilla".equals(mode)) restore(mob);
		else suspend(mob);
	}

	private static void suspend(Mob mob) {
		if (SUSPENDED.containsKey(mob)) return;
		GoalSelector goals = mob.getGoalSelector();
		GoalSelector targets = ((MobGoalSelectorsAccessor) mob).mobforge$getTargetSelector();
		SUSPENDED.put(mob, new GoalState(capture(goals), capture(targets)));
		goals.removeAllGoals(goal -> true);
		targets.removeAllGoals(goal -> true);
		mob.getNavigation().stop();
		mob.setTarget(null);
	}

	private static void restore(Mob mob) {
		GoalState state = SUSPENDED.remove(mob);
		if (state == null) return;
		mob.getNavigation().stop();
		mob.setTarget(null);
		restore(mob.getGoalSelector(), state.goals());
		restore(((MobGoalSelectorsAccessor) mob).mobforge$getTargetSelector(), state.targets());
	}

	private static List<GoalEntry> capture(GoalSelector selector) {
		List<GoalEntry> entries = new ArrayList<>();
		for (WrappedGoal wrapped : selector.getAvailableGoals()) entries.add(new GoalEntry(wrapped.getPriority(), wrapped.getGoal()));
		return List.copyOf(entries);
	}

	private static void restore(GoalSelector selector, List<GoalEntry> entries) {
		selector.removeAllGoals(goal -> true);
		for (GoalEntry entry : entries) selector.addGoal(entry.priority(), entry.goal());
	}

	private record GoalState(List<GoalEntry> goals, List<GoalEntry> targets) { }
	private record GoalEntry(int priority, Goal goal) { }
}
