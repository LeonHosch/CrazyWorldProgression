package ekuzo.crazyworldprogression.mobforge.ai;

import ekuzo.crazyworldprogression.api.mobforge.RegisteredMobHandle;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MobAiRegistry {
	private static final Map<String, MobAiController> CONTROLLERS = new LinkedHashMap<>();
	private MobAiRegistry() {}
	public static void initialize() {
		register(new VanillaAiController()); register(new TargetPositionAiController());
		register(new TargetPlayerAiController()); register(new NearestPlayerAiController());
	}
	public static void register(MobAiController controller) { CONTROLLERS.put(controller.id(), controller); }
	public static void tick(Mob mob) {
		if (!(mob.level() instanceof ServerLevel level) || !mob.entityTags().contains(MobForgeEntityData.MARKER)) return;
		MobAiState state = MobAiState.of(mob);
		if (state.mode != null) {
			VanillaGoalStateManager.applyMode(mob, state.mode);
			MobAiController controller = CONTROLLERS.get(state.mode);
			if (controller != null) controller.tick(level, mob);
		}
		if (state.definition == null || mob.tickCount % 5 != Math.floorMod(mob.getId(), 5)) return;
		MobForge.mobs().get(state.definition).ifPresent(definition -> {
			RegisteredMobHandle handle = new RegisteredMobHandle(mob);
			for (var id : definition.behaviors()) {
				String failureTag = "mobforge.behavior_failed." + id;
				if (mob.entityTags().contains(failureTag)) continue;
				try { MobForge.behaviors().get(id).orElseThrow().tick(level, handle, mob); }
				catch (RuntimeException exception) {
					mob.addTag(failureTag);
					MobForge.LOGGER.error("MobForge behavior {} disabled for entity {} ({})", id, mob.getUUID(), definition.id(), exception);
				}
			}
		});
	}
}
