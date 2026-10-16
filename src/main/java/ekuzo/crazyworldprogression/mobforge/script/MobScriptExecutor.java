package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.api.mobforge.EntitySpawnService;
import ekuzo.crazyworldprogression.api.mobforge.EntityHandle;
import ekuzo.crazyworldprogression.api.mobforge.MobForgeHandle;
import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;
import ekuzo.crazyworldprogression.api.mobforge.SpawnContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.Identifier;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.ThreadLocalRandom;

public final class MobScriptExecutor {
	private final Map<UUID, ActiveRun> active = new LinkedHashMap<>();
	private final Map<UUID, ActiveRun> pending = new LinkedHashMap<>();
	private final Set<UUID> canceled = new HashSet<>();
	private boolean ticking;
	private long clock;

	public UUID run(MobScript script, ServerLevel level) {
		return run(script, level, Map.of(), null, null);
	}

	/** Anchors and ownership come from the caller's world or pocket instance. */
	public UUID run(MobScript script, ServerLevel level, Map<String, Vec3> anchors, UUID encounterId, UUID pocketInstanceId) {
		java.util.Objects.requireNonNull(script, "script");
		java.util.Objects.requireNonNull(level, "script world");
		java.util.Objects.requireNonNull(anchors, "script anchors");
		UUID id = UUID.randomUUID();
		(ticking ? pending : active).put(id, new ActiveRun(id, script, level, Map.copyOf(anchors), encounterId, pocketInstanceId));
		MobForge.LOGGER.info("Started MobForge script {} (run {}).", script.id(), id);
		return id;
	}

	public int cancel(String scriptId) {
		int count = 0;
		for (ActiveRun run : active.values()) if (run.script.id().equals(scriptId)) { canceled.add(run.id); count++; }
		count += (int) pending.values().stream().filter(run -> run.script.id().equals(scriptId)).count();
		pending.values().removeIf(run -> run.script.id().equals(scriptId));
		if (!ticking) { active.keySet().removeAll(canceled); canceled.clear(); }
		return count;
	}
	public int cancelPocket(UUID pocketInstanceId) {
		int count = 0;
		for (ActiveRun run : active.values()) if (pocketInstanceId.equals(run.pocketInstanceId)) { canceled.add(run.id); count++; }
		count += (int) pending.values().stream().filter(run -> pocketInstanceId.equals(run.pocketInstanceId)).count();
		pending.values().removeIf(run -> pocketInstanceId.equals(run.pocketInstanceId));
		if (!ticking) { active.keySet().removeAll(canceled); canceled.clear(); }
		return count;
	}
	public void clear() { active.clear(); pending.clear(); canceled.clear(); ticking = false; clock = 0; }
	public void entityDied(ServerLevel level, UUID entityId) {
		for (ActiveRun run : active.values()) if (run.level == level)
			for (ScriptEntityHandle handle : run.handles.values())
				if (handle.uuid().equals(entityId) && handle instanceof EntityHandle entityHandle) entityHandle.markDead();
	}

	public int activeRuns(String scriptId) {
		return (int) active.values().stream().filter(run -> run.script.id().equals(scriptId)).count();
	}

	public void tick(MinecraftServer server) {
		clock++;
		ticking = true;
		Iterator<ActiveRun> iterator = active.values().iterator();
		while (iterator.hasNext()) {
			ActiveRun run = iterator.next();
			if (canceled.contains(run.id)) { iterator.remove(); continue; }
			try {
				if (advance(run)) {
					iterator.remove();
					MobForge.LOGGER.info("{} MobForge script {} (run {}).", canceled.contains(run.id) ? "Canceled" : "Completed", run.script.id(), run.id);
				}
			} catch (RuntimeException exception) {
				iterator.remove();
				MobForge.LOGGER.warn("Canceled MobForge script {} (run {}) in {} at step {}: {}", run.script.id(), run.id, run.level.dimension().identifier(), run.step, exception.getMessage(), exception);
				ScriptSignals.failed(run.id, run.script.id(), run.level, exception.getMessage());
			}
		}
		ticking = false;
		canceled.clear();
		active.putAll(pending);
		pending.clear();
	}

	private boolean advance(ActiveRun run) {
		if (clock < run.wakeTick) return false;
		while (run.step < run.script.steps().size()) {
			if (canceled.contains(run.id)) return true;
			MobScript.Step step = run.script.steps().get(run.step);
			if (step instanceof MobScript.SleepStep sleep) {
				run.step++;
				run.wakeTick = clock + sleep.ticks();
				if (sleep.ticks() > 0) return false;
			} else if (step instanceof MobScript.SpawnStep spawn) {
				run.step++;
				spawn(run, spawn);
			} else if (step instanceof MobScript.SpawnEntityStep spawn) {
				run.step++;
				Vec3 position = run.anchors.get(spawn.at());
				if (position == null) throw new IllegalArgumentException("Unknown anchor " + spawn.at() + " in script " + run.script.id());
				SpawnContext context = new SpawnContext(run.level, position, 0, 0, run.encounterId, run.pocketInstanceId, run.id, Map.of());
				run.handles.put(spawn.saveAs(), EntitySpawnService.spawn(spawn.id(), context));
			} else if (step instanceof MobScript.WaitUntilStep wait) {
				if (run.waitStartedAt < 0) run.waitStartedAt = clock;
				if (!ScriptCondition.evaluate(wait.condition(), run.handles)) {
					if (clock - run.waitStartedAt >= wait.timeoutTicks()) throw new IllegalStateException("wait_until timed out: " + wait.condition());
					return false;
				}
				run.waitStartedAt = -1;
				run.step++;
			} else if (step instanceof MobScript.MobCastStep cast) {
				run.step++;
				if (!mob(run, cast.mob()).castAbility(cast.ability()))
					throw new IllegalStateException("Ability " + cast.ability() + " was on cooldown or rejected by canCast");
			} else if (step instanceof MobScript.SetPhaseStep phase) {
				run.step++;
				mob(run, phase.mob()).setPhase(phase.phase());
			} else if (step instanceof MobScript.TriggerStep trigger) {
				run.step++;
				ScriptSignals.trigger(run.id, trigger.event(), run.level, run.handles);
			}
		}
		return true;
	}
	private MobForgeHandle mob(ActiveRun run, String expression) {
		ScriptEntityHandle handle = run.handles.get(ScriptCondition.reference(expression));
		if (!(handle instanceof MobForgeHandle mob)) throw new IllegalArgumentException("Script handle " + expression + " is not a registered MobForge mob");
		return mob;
	}

	private void spawn(ActiveRun run, MobScript.SpawnStep step) {
		if (MobForge.TEMPLATES.get(step.template()).isEmpty()) throw new IllegalStateException("template disappeared after reload: " + step.template());
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < step.count(); i++) {
			double x = step.location().x() + spread(random, step.offset().x());
			double y = step.location().y() + spread(random, step.offset().y());
			double z = step.location().z() + spread(random, step.offset().z());
			ScriptEntityHandle handle = EntitySpawnService.spawn(Identifier.fromNamespaceAndPath(CrazyWorldProgression.MOD_ID, step.template()),
					new SpawnContext(run.level, new Vec3(x, y, z), 0, 0, run.encounterId, run.pocketInstanceId, run.id, Map.of()));
			Entity entity = run.level.getEntity(handle.uuid());
			MobForgeEntityData.setScript(entity, run.script.id(), run.id.toString());
			if (step.aiOverride() != null) MobForgeEntityData.setAi(entity, step.aiOverride());
		}
	}

	private static double spread(ThreadLocalRandom random, double offset) { return offset == 0 ? 0 : random.nextDouble(-offset, offset); }

	private static final class ActiveRun {
		private final UUID id;
		private final MobScript script;
		private final ServerLevel level;
		private final Map<String, Vec3> anchors;
		private final UUID encounterId;
		private final UUID pocketInstanceId;
		private final Map<String, ScriptEntityHandle> handles = new HashMap<>();
		private int step;
		private long wakeTick;
		private long waitStartedAt = -1;
		private ActiveRun(UUID id, MobScript script, ServerLevel level, Map<String, Vec3> anchors, UUID encounterId, UUID pocketInstanceId) {
			this.id = id; this.script = script; this.level = level; this.anchors = anchors; this.encounterId = encounterId; this.pocketInstanceId = pocketInstanceId;
		}
	}
}
