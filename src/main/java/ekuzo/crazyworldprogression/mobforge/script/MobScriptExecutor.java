package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.portals.EncounterBanner;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.RaidCheckpoints;
import ekuzo.crazyworldprogression.portals.RaidService;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import ekuzo.crazyworldprogression.loot.LootPoolService;
import ekuzo.crazyworldprogression.api.mobforge.EntitySpawnService;
import ekuzo.crazyworldprogression.api.mobforge.EntityHandle;
import ekuzo.crazyworldprogression.api.mobforge.MobForgeHandle;
import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;
import ekuzo.crazyworldprogression.api.mobforge.SpawnContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
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
		return run(script, level, anchors, encounterId, pocketInstanceId, false);
	}

	/** Pocket-authored runs can request supported-floor placement for every spawned mob. */
	public UUID run(MobScript script, ServerLevel level, Map<String, Vec3> anchors, UUID encounterId,
	                UUID pocketInstanceId, boolean groundSpawns) {
		return run(script, level, anchors, encounterId, pocketInstanceId, groundSpawns, 0);
	}

	/**
	 * Raid checkpoints: with resumeEncounters > 0 the run starts right after that many encounter_end steps and
	 * players are moved to that encounter_end's spawn. Rewards right after it are not given again.
	 */
	public UUID run(MobScript script, ServerLevel level, Map<String, Vec3> anchors, UUID encounterId,
	                UUID pocketInstanceId, boolean groundSpawns, int resumeEncounters) {
		java.util.Objects.requireNonNull(script, "script");
		java.util.Objects.requireNonNull(level, "script world");
		java.util.Objects.requireNonNull(anchors, "script anchors");
		UUID id = UUID.randomUUID();
		ActiveRun created = new ActiveRun(id, script, level, Map.copyOf(anchors), encounterId, pocketInstanceId,
				groundSpawns);
		int resume = Math.max(0, Math.min(resumeEncounters, encounterCount(script)));
		if (resume > 0) {
			// Jump straight past the checkpoint's encounter_end; its spawn becomes the starting point.
			created.step = resumeStepIndex(script, resume);
			created.encountersCompleted = resume;
			created.suppressRewards = true;
			created.resumeFrom = (MobScript.EncounterEndStep) script.steps().get(created.step - 1);
		}
		(ticking ? pending : active).put(id, created);
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
	/** True while any script of this pocket instance is still running or about to start. */
	public boolean hasPocketRuns(UUID pocketInstanceId) {
		if (pocketInstanceId == null) return false;
		for (ActiveRun run : active.values())
			if (pocketInstanceId.equals(run.pocketInstanceId) && !canceled.contains(run.id)) return true;
		for (ActiveRun run : pending.values()) if (pocketInstanceId.equals(run.pocketInstanceId)) return true;
		return false;
	}
	/** Drop per-run encounter state without interrupting any following script steps. */
	public void clearPocketState(UUID pocketInstanceId) {
		if (pocketInstanceId == null) return;
		for (ActiveRun run : active.values()) if (pocketInstanceId.equals(run.pocketInstanceId)) run.clearState();
		for (ActiveRun run : pending.values()) if (pocketInstanceId.equals(run.pocketInstanceId)) run.clearState();
	}
	public void clear() { active.clear(); pending.clear(); canceled.clear(); ticking = false; clock = 0; }
	public void entityDied(ServerLevel level, Entity entity) {
		for (ActiveRun run : active.values()) if (run.level == level)
		{
			for (ScriptEntityHandle handle : run.handles.values())
				if (handle.uuid().equals(entity.getUUID()) && handle instanceof EntityHandle entityHandle) entityHandle.markDead();
			if (entity instanceof net.minecraft.world.entity.Mob)
			{
				for (String tag : entity.entityTags()) run.kills.merge(tag, 1L, Long::sum);
				run.kills.merge(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), 1L, Long::sum);
				MobForgeEntityData.template(entity).ifPresent(id -> {
					run.kills.merge(id, 1L, Long::sum);
					run.kills.merge(MobForge.id(id).toString(), 1L, Long::sum);
				});
				MobForgeEntityData.definition(entity).ifPresent(id -> run.kills.merge(id.toString(), 1L, Long::sum));
			}
		}
	}
	public void portalUsed(ServerLevel level, Identifier portalId) {
		for (ActiveRun run : active.values()) if (run.level == level)
			run.portalUses.merge(portalId, 1L, Long::sum);
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

	/** How many encounter_end steps a script has; a checkpoint can never skip past the last one. */
	public static int encounterCount(MobScript script) {
		return (int) script.steps().stream().filter(MobScript.EncounterEndStep.class::isInstance).count();
	}

	/** Index of the step right after the given number of encounter_end steps. */
	static int resumeStepIndex(MobScript script, int encounters) {
		int seen = 0;
		for (int index = 0; index < script.steps().size(); index++) {
			if (script.steps().get(index) instanceof MobScript.EncounterEndStep && ++seen == encounters) return index + 1;
		}
		return script.steps().size();
	}

	/**
	 * encounter_start and encounter_end clear what the script manages in its instance: script-spawned mobs,
	 * pasted buildings, script-added portal locations, eventboxes, redstone sensors and their counters.
	 * Variables (including player lists) are kept.
	 */
	private static void clearScriptManaged(ActiveRun run) {
		if (run.pocketInstanceId != null) {
			java.util.List<net.minecraft.world.entity.Entity> owned = new java.util.ArrayList<>();
			for (net.minecraft.world.entity.Entity entity : run.level.getAllEntities())
				if (ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData.pocket(entity)
						.filter(run.pocketInstanceId::equals).isPresent()) owned.add(entity);
			owned.forEach(net.minecraft.world.entity.Entity::discard);
		}
		ekuzo.crazyworldprogression.buildings.BuildingService.clearInstance(run.level);
		PortalService.resetRuntimeLocations(run.level.getServer(), run.level.dimension().identifier());
		run.handles.clear();
		run.boxes.clear(); run.boxPlayers.clear(); run.boxOccupied.clear(); run.boxTriggers.clear();
		run.redstone.clear(); run.kills.clear(); run.portalUses.clear(); run.watches.clear();
	}

	/** Rewards that belong to an already completed encounter and are not repeated after resuming. */
	static boolean rewardStep(MobScript.Step step) {
		return step instanceof MobScript.LootPoolStep || step instanceof MobScript.AchievementStep
				|| step instanceof MobScript.RewardCurrencyStep || step instanceof MobScript.RewardItemStep
				|| step instanceof MobScript.RewardLootStep;
	}

	private boolean advance(ActiveRun run) {
		ScriptCondition.Context context = run.context();
		for (var entry : run.boxes.entrySet()) {
			ScriptCondition.Box box = entry.getValue();
			if (box.mode().equals("players") && !box.playerVariable().isEmpty()) {
				Set<UUID> inside = new HashSet<>();
				for (var player : run.level.players()) {
					if (player.isSpectator() || !box.contains(player.position())) continue;
					inside.add(player.getUUID());
					if (!run.boxPlayers.getOrDefault(entry.getKey(), Set.of()).contains(player.getUUID())) {
						run.variables.remove(box.playerVariable());
						run.playerVariables.computeIfAbsent(box.playerVariable(), ignored -> new LinkedHashSet<>())
							.add(player.getUUID());
					}
				}
				run.boxPlayers.put(entry.getKey(), inside);
			}
			long count = box.count(run.level);
			if (!box.countVariable().isEmpty()) {
				run.playerVariables.remove(box.countVariable());
				run.variables.put(box.countVariable(), count);
			}
			boolean occupied = count > 0;
			if (occupied && !run.boxOccupied.getOrDefault(entry.getKey(), false))
				run.boxTriggers.merge(entry.getKey(), 1L, Long::sum);
			run.boxOccupied.put(entry.getKey(), occupied);
		}
		for (Watch watch : run.watches.values()) {
			Long count = ScriptCondition.eventCounter(watch.step.condition(), context);
			if (count != null) {
				long occurrences = Math.max(0L, count - watch.lastCount);
				watch.lastCount = count;
				if (occurrences > 0) changeVariable(run, watch.step.variable(), watch.step.operation(),
						watch.step.value(), occurrences);
				continue;
			}
			boolean current = ScriptCondition.evaluate(watch.step.condition(), run.handles, context);
			if (current && !watch.previous) changeVariable(run, watch.step.variable(), watch.step.operation(), watch.step.value());
			watch.previous = current;
		}
		if (run.resumeFrom != null) {
			MobScript.EncounterEndStep checkpoint = run.resumeFrom;
			run.resumeFrom = null;
			if (checkpoint.spawn() != null) RaidService.moveSpawn(run.level, checkpoint.spawn().x(),
					checkpoint.spawn().y(), checkpoint.spawn().z(), true);
			EncounterBanner.show(run.level.players(), EncounterBanner.Kind.RESUMED,
					Integer.toString(run.encountersCompleted + 1));
		}
		if (clock < run.wakeTick) return false;
		int operations = 0;
		while (run.step < run.script.steps().size()) {
			if (++operations > 256) return false;
			if (canceled.contains(run.id)) return true;
			MobScript.Step step = run.script.steps().get(run.step);
			if (run.suppressRewards && rewardStep(step)) {
				run.step++;
				continue;
			}
			if (step instanceof MobScript.SleepStep sleep) {
				run.step++;
				run.wakeTick = clock + sleep.ticks();
				if (sleep.ticks() > 0) return false;
			} else if (step instanceof MobScript.SpawnStep spawn) {
				run.step++;
				spawn(run, spawn);
			} else if (step instanceof MobScript.SpawnMobsStep spawn) {
				run.step++;
				spawnMobs(run, spawn);
			} else if (step instanceof MobScript.SpawnEntityStep spawn) {
				run.step++;
				Vec3 position = run.anchors.get(spawn.at());
				if (position == null) throw new IllegalArgumentException("Unknown anchor " + spawn.at() + " in script " + run.script.id());
				if (run.groundSpawns) position = grounded(run, position.x, position.y, position.z);
				SpawnContext spawnContext = new SpawnContext(run.level, position, 0, 0, run.encounterId, run.pocketInstanceId, run.id, Map.of());
				// Deprecated spawn_entity: its save_as name works like a mob_spawn tag.
				tagSpawned(run, EntitySpawnService.spawn(spawn.id(), spawnContext), spawn.saveAs());
			} else if (step instanceof MobScript.WaitUntilStep wait) {
				if (run.waitStartedAt < 0) run.waitStartedAt = clock;
				if (!ScriptCondition.evaluate(wait.condition(), run.handles, context)) {
					if (clock - run.waitStartedAt >= wait.timeoutTicks()) throw new IllegalStateException("wait timed out: " + wait.condition());
					return false;
				}
				run.waitStartedAt = -1;
				run.step++;
			} else if (step instanceof MobScript.MobCastStep cast) {
				run.step++;
				boolean cast_ = false;
				for (MobForgeHandle mob : tagged(run, cast.mob())) cast_ |= mob.castAbility(cast.ability());
				if (!cast_) MobForge.LOGGER.debug("No mob tagged {} could cast {} in script {}", cast.mob(), cast.ability(), run.script.id());
			} else if (step instanceof MobScript.SetPhaseStep phase) {
				run.step++;
				for (MobForgeHandle mob : tagged(run, phase.mob())) mob.setPhase(phase.phase());
			} else if (step instanceof MobScript.TriggerStep trigger) {
				run.step++;
				ScriptSignals.trigger(run.id, trigger.event(), run.level, run.handles);
			} else if (step instanceof MobScript.RewardCurrencyStep reward) {
				run.step++;
				rewardCurrency(run, reward);
			} else if (step instanceof MobScript.RewardItemStep reward) {
				run.step++;
				rewardItem(run, reward);
			} else if (step instanceof MobScript.RewardLootStep reward) {
				run.step++;
				rewardLoot(run, reward);
			} else if (step instanceof MobScript.LootPoolStep loot) {
				run.step++;
				if (run.pocketInstanceId == null) throw new IllegalStateException("Loot rewards require a pocket instance");
				for (UUID playerId : playerTargets(run, loot.playerId())) {
					var player = run.level.getServer().getPlayerList().getPlayer(playerId);
					if (player != null && player.level() == run.level)
						LootPoolService.roll(run.level.getServer(), player, loot.lootPoolId());
				}
			} else if (step instanceof MobScript.AchievementStep achievement) {
				run.step++;
				var holder = run.level.getServer().getAdvancements().get(achievement.achievementId());
				if (holder == null) throw new IllegalArgumentException("Unknown achievement " + achievement.achievementId());
				for (UUID playerId : playerTargets(run, achievement.playerId())) {
					var player = run.level.getServer().getPlayerList().getPlayer(playerId);
					if (player == null || player.level() != run.level) continue;
					for (String criterion : player.getAdvancements().getOrStartProgress(holder).getRemainingCriteria())
						player.getAdvancements().award(holder, criterion);
				}
			} else if (step instanceof MobScript.VariableStep variable) {
				run.step++;
				changeVariable(run, variable.name(), variable.operation(), variable.value());
			} else if (step instanceof MobScript.EventStep event) {
				int eventIndex = run.step;
				run.step++;
				run.watches.putIfAbsent(eventIndex, new Watch(event,
						ScriptCondition.eventCounter(event.condition(), context)));
			} else if (step instanceof MobScript.EventBoxStep box) {
				run.step++;
				ScriptCondition.Box area = new ScriptCondition.Box(box.min(), box.max(), box.mode(),
						box.mobId(), box.playerVariable(), box.countVariable());
				run.boxes.put(box.name(), area);
				if (!box.countVariable().isEmpty()) run.variables.put(box.countVariable(), area.count(run.level));
			} else if (step instanceof MobScript.RedstoneStep sensor) {
				run.step++;
				run.redstone.put(sensor.name(), BlockPos.containing(sensor.position().x(), sensor.position().y(), sensor.position().z()));
			} else if (step instanceof MobScript.IfStep test) {
				run.step = ScriptCondition.evaluate(test.condition(), run.handles, context)
						? run.step + 1 : run.flow.falseJump.get(run.step);
			} else if (step instanceof MobScript.ElseStep) {
				run.step = run.flow.endJump.get(run.step);
			} else if (step instanceof MobScript.EndIfStep) {
				run.step++;
			} else if (step instanceof MobScript.LoopStep loop) {
				if (!ScriptCondition.evaluate(loop.condition(), run.handles, context)) {
					run.loopCounts.remove(run.step);
					run.step = run.flow.falseJump.get(run.step);
				} else {
					int count = run.loopCounts.merge(run.step, 1, Integer::sum);
					if (count > loop.maxIterations()) throw new IllegalStateException("Loop exceeded max iterations at step " + (run.step + 1));
					run.step++;
				}
			} else if (step instanceof MobScript.EndLoopStep) {
				run.step = run.flow.loopBack.get(run.step);
				run.wakeTick = clock + 1;
				return false;
			} else if (step instanceof MobScript.PortalLocationStep portal) {
				run.step++;
				PortalService.changeLocation(run.level.getServer(), run.level.dimension().identifier(),
						portal.portalId(), portal.action().equals("add") ? "add" : portal.deletesAll() ? "clear" : "remove",
						portal.locationId(),
						new Vec3(portal.position().x(), portal.position().y(), portal.position().z()),
						portal.target() == null ? null : new Vec3(portal.target().x(), portal.target().y(), portal.target().z()));
			} else if (step instanceof MobScript.BuildingStep building) {
				BlockPos origin = BlockPos.containing(building.location().x(), building.location().y(),
						building.location().z());
				if (building.action().equals("add")) {
					BuildingService.spawn(run.level, building.buildId(), origin);
					run.step++;
				} else {
					long next = BuildingService.clearBatch(run.level, building.buildId(), origin,
							run.buildingClearIndex, 8_192);
					if (next >= 0) {
						run.buildingClearIndex = next;
						run.wakeTick = clock + 1;
						return false;
					}
					run.buildingClearIndex = 0;
					run.step++;
				}
			} else if (step instanceof MobScript.ParticleStep particles) {
				run.step++;
				var particle = BuiltInRegistries.PARTICLE_TYPE.getValue(particles.particleId());
				if (!(particle instanceof ParticleOptions options))
					throw new IllegalArgumentException("Particle requires additional data: " + particles.particleId());
				run.level.sendParticles(options, particles.location().x(), particles.location().y(),
						particles.location().z(), particles.count(), particles.offset().x(),
						particles.offset().y(), particles.offset().z(), particles.speed());
			} else if (step instanceof MobScript.ExplosionStep explosion) {
				run.step++;
				run.level.explode(null, explosion.location().x(), explosion.location().y(),
						explosion.location().z(), explosion.power(), Level.ExplosionInteraction.NONE);
			} else if (step instanceof MobScript.SoundStep sound) {
				run.step++;
				var event = BuiltInRegistries.SOUND_EVENT.getValue(sound.soundId());
				if (event == null) throw new IllegalArgumentException("Unknown sound " + sound.soundId());
				run.level.playSound(null, sound.location().x(), sound.location().y(),
						sound.location().z(), event, SoundSource.MASTER, 1.0F, 1.0F);
			} else if (step instanceof MobScript.EffectStep effect) {
				run.step++;
				var holder = BuiltInRegistries.MOB_EFFECT.get(ResourceKey.create(Registries.MOB_EFFECT,
						effect.effectId())).orElseThrow(() -> new IllegalArgumentException("Unknown effect " + effect.effectId()));
				for (UUID playerId : playerTargets(run, effect.playerId())) {
					var player = run.level.getServer().getPlayerList().getPlayer(playerId);
					if (player == null || player.level() != run.level) continue;
					if (effect.action().equals("remove")) player.removeEffect(holder);
					else player.addEffect(new MobEffectInstance(holder, -1, effect.amplifier()));
				}
			} else if (step instanceof MobScript.RaidCompleteStep) {
				run.step++;
				RaidService.complete(run.level);
			} else if (step instanceof MobScript.EncounterStartStep) {
				run.step++;
				run.suppressRewards = false;
				clearScriptManaged(run);
				RaidService.start(run.level);
			} else if (step instanceof MobScript.EncounterEndStep end) {
				run.step++;
				run.encountersCompleted++;
				clearScriptManaged(run);
				// Store the new spawn first so a clutch clear (wipe pending) respawns everyone there.
				if (end.spawn() != null) RaidService.moveSpawn(run.level, end.spawn().x(), end.spawn().y(),
						end.spawn().z(), false);
				RaidService.complete(run.level);
				RaidCheckpoints.encounterCompleted(run.level, run.encountersCompleted);
			} else if (step instanceof MobScript.WipeStep) {
				run.step++;
				RaidService.forceWipe(run.level);
			}
		}
		return run.watches.isEmpty();
	}
	private static Set<UUID> playerTargets(ActiveRun run, String playerId) {
		Set<UUID> targets = new LinkedHashSet<>();
		if (playerId.isBlank()) {
			for (var player : run.level.players()) targets.add(player.getUUID());
			return targets;
		}
		String reference = playerId;
		if (reference.startsWith("${") && reference.endsWith("}"))
			reference = reference.substring(2, reference.length() - 1);
		Set<UUID> saved = run.playerVariables.get(reference);
		if (saved != null) return saved;
		try { targets.add(UUID.fromString(reference)); }
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Unknown player variable or UUID " + playerId);
		}
		return targets;
	}
	private static void changeVariable(ActiveRun run, String name, String operation, String value) {
		changeVariable(run, name, operation, value, 1L);
	}
	private static void changeVariable(ActiveRun run, String name, String operation, String value, long occurrences) {
		long number = value.equals("true") ? 1L : value.equals("false") ? 0L : Long.parseLong(value);
		run.playerVariables.remove(name);
		if (operation.equals("set")) run.variables.put(name, number);
		else run.variables.put(name, Math.addExact(run.variables.getOrDefault(name, 0L),
				Math.multiplyExact(number, occurrences)));
	}
	private static final class Watch {
		private final MobScript.EventStep step;
		private boolean previous;
		private long lastCount;
		private Watch(MobScript.EventStep step, Long count) { this.step = step; this.lastCount = count == null ? 0L : count; }
	}

	private static void rewardCurrency(ActiveRun run, MobScript.RewardCurrencyStep reward) {
		if (run.pocketInstanceId == null) throw new IllegalStateException("Player rewards require a pocket instance");
		CurrencyDefinition definition = CurrencyRegistry.require(reward.currency());
		if (definition.scope() != CurrencyDefinition.CurrencyScope.PLAYER) {
			throw new IllegalArgumentException("Reward currency must be player scoped: " + reward.currency());
		}
		var players = java.util.List.copyOf(run.level.players());
		for (var player : players) {
			Math.addExact(CurrencyService.getBalance(run.level.getServer(), player.getUUID(), reward.currency()),
					reward.amount());
		}
		for (var player : players) {
			CurrencyService.credit(run.level.getServer(), player.getUUID(), reward.currency(), reward.amount());
		}
	}

	private static void rewardItem(ActiveRun run, MobScript.RewardItemStep reward) {
		if (run.pocketInstanceId == null) throw new IllegalStateException("Player rewards require a pocket instance");
		Item item = BuiltInRegistries.ITEM.getValue(reward.item());
		if (item == null || item == Items.AIR) throw new IllegalArgumentException("Unknown reward item: " + reward.item());
		for (var player : run.level.players()) {
			int remaining = reward.count();
			while (remaining > 0) {
				int count = Math.min(remaining, item.getDefaultMaxStackSize());
				give(player, new ItemStack(item, count));
				remaining -= count;
			}
		}
	}

	private static void rewardLoot(ActiveRun run, MobScript.RewardLootStep reward) {
		if (run.pocketInstanceId == null) throw new IllegalStateException("Player rewards require a pocket instance");
		ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, reward.lootTable());
		LootTable table = run.level.getServer().reloadableRegistries().getLootTable(key);
		if (table == LootTable.EMPTY) throw new IllegalArgumentException("Unknown or empty loot table: " + reward.lootTable());
		for (var player : run.level.players()) {
			LootParams params = new LootParams.Builder(run.level)
					.withParameter(LootContextParams.ORIGIN, player.position())
					.withLuck(player.getLuck()).create(LootContextParamSets.CHEST);
			for (int roll = 0; roll < reward.rolls(); roll++) {
				for (ItemStack stack : table.getRandomItems(params)) give(player, stack);
			}
		}
	}

	private static void give(net.minecraft.server.level.ServerPlayer player, ItemStack stack) {
		if (!player.addItem(stack) && !stack.isEmpty()) player.drop(stack, false);
	}
	/** Living MobForge mobs with this tag (mob_cast and mob_phase act on all of them). */
	private static List<MobForgeHandle> tagged(ActiveRun run, String expression) {
		ScriptEntityHandle handle = run.handles.get(ScriptCondition.reference(expression));
		if (handle instanceof TagGroup group) return group.livingMobForge();
		if (handle instanceof MobForgeHandle mob && mob.isAlive()) return List.of(mob);
		return List.of();
	}

	/** Adds the script tag to a spawned mob (used by kills()) and to the tag's group (dead, health_percent, phase). */
	private static void tagSpawned(ActiveRun run, ScriptEntityHandle handle, String tag) {
		if (handle == null || tag == null || tag.isEmpty()) return;
		Entity entity = run.level.getEntity(handle.uuid());
		if (entity != null) entity.addTag(tag);
		ScriptEntityHandle existing = run.handles.get(tag);
		TagGroup group = existing instanceof TagGroup current ? current : new TagGroup();
		group.add(handle);
		run.handles.put(tag, group);
	}

	private void spawn(ActiveRun run, MobScript.SpawnStep step) {
		if (MobForge.TEMPLATES.get(step.template()).isEmpty()) throw new IllegalStateException("template disappeared after reload: " + step.template());
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < step.count(); i++) {
			double x = step.location().x() + spread(random, step.offset().x());
			double y = step.location().y() + spread(random, step.offset().y());
			double z = step.location().z() + spread(random, step.offset().z());
			if (run.groundSpawns) {
				Vec3 grounded = grounded(run, x, y, z);
				x = grounded.x; y = grounded.y; z = grounded.z;
			}
			ScriptEntityHandle handle = EntitySpawnService.spawn(Identifier.fromNamespaceAndPath(CrazyWorldProgression.MOD_ID, step.template()),
					new SpawnContext(run.level, new Vec3(x, y, z), 0, 0, run.encounterId, run.pocketInstanceId, run.id, Map.of()));
			Entity entity = run.level.getEntity(handle.uuid());
			MobForgeEntityData.setScript(entity, run.script.id(), run.id.toString());
			if (step.aiOverride() != null) MobForgeEntityData.setAi(entity, step.aiOverride());
			tagSpawned(run, handle, step.tag());
		}
	}

	private void spawnMobs(ActiveRun run, MobScript.SpawnMobsStep step) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < step.count(); i++) {
			Vec3 position = new Vec3(step.location().x() + spread(random, step.offset().x()),
					step.location().y() + spread(random, step.offset().y()),
					step.location().z() + spread(random, step.offset().z()));
			if (run.groundSpawns) position = grounded(run, position.x, position.y, position.z);
			ScriptEntityHandle handle = EntitySpawnService.spawn(step.mobId(), new SpawnContext(run.level,
					position, 0, 0, run.encounterId, run.pocketInstanceId, run.id, Map.of()));
			Entity entity = run.level.getEntity(handle.uuid());
			if (entity != null) MobForgeEntityData.setScript(entity, run.script.id(), run.id.toString());
			tagSpawned(run, handle, step.tag());
		}
	}

	private static double spread(ThreadLocalRandom random, double offset) { return offset == 0 ? 0 : random.nextDouble(-offset, offset); }

	private static Vec3 grounded(ActiveRun run, double x, double y, double z) {
		Vec3 nearby = findGround(run.level, x, y, z);
		if (nearby != null) return nearby;
		Vec3 fallback = run.anchors.get("pocket_spawn");
		if (fallback != null) {
			Vec3 safe = findGround(run.level, fallback.x, fallback.y, fallback.z);
			if (safe != null) return safe;
		}
		throw new IllegalStateException("No safe floor near pocket mob spawn " + x + ", " + y + ", " + z);
	}

	private static Vec3 findGround(ServerLevel level, double x, double y, double z) {
		int minY = level.getMinY() + 1;
		int maxY = level.getMinY() + level.getLogicalHeight() - 2;
		int wantedY = Math.max(minY, Math.min(maxY, (int) Math.floor(y)));
		int blockX = (int) Math.floor(x), blockZ = (int) Math.floor(z);
		Vec3 nearest = null;
		long bestDistance = Long.MAX_VALUE;
		for (int radius = 0; radius <= 2; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				for (int feetY = minY; feetY <= Math.min(maxY, wantedY + 24); feetY++) {
					BlockPos feet = new BlockPos(blockX + dx, feetY, blockZ + dz);
					if (!supported(level, feet)) continue;
					long vertical = (long) feetY - wantedY;
					long distance = 4 * vertical * vertical + dx * dx + dz * dz;
					if (distance < bestDistance) {
						bestDistance = distance;
						nearest = new Vec3(feet.getX() + 0.5, feetY, feet.getZ() + 0.5);
					}
				}
			}
		}
		return nearest;
	}

	private static boolean supported(ServerLevel level, BlockPos feet) {
		return level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
				&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
	}

	private static final class ActiveRun {
		private final UUID id;
		private final MobScript script;
		private final ServerLevel level;
		private final Map<String, Vec3> anchors;
		private final UUID encounterId;
		private final UUID pocketInstanceId;
		private final boolean groundSpawns;
		private final Map<String, ScriptEntityHandle> handles = new HashMap<>();
		private final MobScriptFlow flow;
		private final Map<String, Long> variables = new HashMap<>();
		private final Map<String, Set<UUID>> playerVariables = new HashMap<>();
		private final Map<String, ScriptCondition.Box> boxes = new HashMap<>();
		private final Map<String, Set<UUID>> boxPlayers = new HashMap<>();
		private final Map<String, Boolean> boxOccupied = new HashMap<>();
		private final Map<String, Long> boxTriggers = new HashMap<>();
		private final Map<String, BlockPos> redstone = new HashMap<>();
		private final Map<String, Long> kills = new HashMap<>();
		private final Map<Identifier, Long> portalUses = new HashMap<>();
		private final Map<Integer, Integer> loopCounts = new HashMap<>();
		private final Map<Integer, Watch> watches = new HashMap<>();
		private int step;
		private MobScript.EncounterEndStep resumeFrom;
		private int encountersCompleted;
		private boolean suppressRewards;
		private long wakeTick;
		private long waitStartedAt = -1;
		private long buildingClearIndex;
		private ActiveRun(UUID id, MobScript script, ServerLevel level, Map<String, Vec3> anchors,
		                  UUID encounterId, UUID pocketInstanceId, boolean groundSpawns) {
			this.id = id; this.script = script; this.level = level; this.anchors = anchors; this.encounterId = encounterId;
			this.pocketInstanceId = pocketInstanceId; this.groundSpawns = groundSpawns;
			this.flow = MobScriptFlow.compile(script.steps());
		}
		private ScriptCondition.Context context() {
			return new ScriptCondition.Context(level, variables, boxes, redstone, kills, portalUses, boxTriggers);
		}
		private void clearState() {
			variables.clear(); playerVariables.clear(); boxes.clear(); boxPlayers.clear();
			boxOccupied.clear(); boxTriggers.clear(); kills.clear(); portalUses.clear(); watches.clear();
		}
	}
}
