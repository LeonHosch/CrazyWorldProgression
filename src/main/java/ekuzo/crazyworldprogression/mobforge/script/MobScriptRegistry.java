package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.api.mobforge.EntitySpawnService;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateValidator;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MobScriptRegistry {
	private final Path directory = FabricLoader.getInstance().getConfigDir().resolve("mobforge/scripts");
	private final MobScriptYaml yaml = new MobScriptYaml();
	private final MobScriptCode code = new MobScriptCode();
	private volatile Map<String, MobScript> scripts = Map.of();

	public synchronized ReloadResult reload() {
		Map<String, MobScript> loaded = new LinkedHashMap<>(); int failures = 0;
		try {
			Files.createDirectories(directory);
			try (var files = Files.list(directory)) {
				for (Path file : files.filter(Files::isRegularFile).filter(MobScriptRegistry::isScript)
						.sorted(Comparator.comparingInt((Path path) -> isCode(path) ? 0 : 1)
								.thenComparing(path -> path.getFileName().toString())).toList()) {
					try {
						String source = Files.readString(file, StandardCharsets.UTF_8);
						MobScript script = isCode(file) ? code.decode(source) : yaml.decode(source);
						if (isCode(file) && !file.getFileName().toString().equals(script.id() + ".mfs"))
							throw new IllegalArgumentException("script ID must match its .mfs filename");
						validate(script);
						if (loaded.putIfAbsent(script.id(), script) != null && isCode(file))
							throw new IllegalArgumentException("duplicate script ID: " + script.id());
					} catch (Exception exception) {
						failures++; MobForge.LOGGER.warn("Failed to load script {}: {}", file.getFileName(), cleanMessage(exception));
					}
				}
			}
		} catch (IOException exception) { failures++; MobForge.LOGGER.error("Could not read script directory: {}", exception.getMessage()); }
		scripts = Map.copyOf(loaded);
		MobForge.LOGGER.info("Loaded {} MobForge scripts ({} failed).", loaded.size(), failures);
		return new ReloadResult(loaded.size(), failures);
	}

	private static void validate(MobScript script) {
		if (script.id() == null || !MobTemplateValidator.SAFE_ID.matcher(script.id()).matches()) throw new IllegalArgumentException("id must match [a-z0-9_-]+");
		if (script.steps().isEmpty()) throw new IllegalArgumentException("steps cannot be empty");
		java.util.Set<String> handles = new java.util.HashSet<>();
		for (int i = 0; i < script.steps().size(); i++) {
			MobScript.Step step = script.steps().get(i);
			if (step instanceof MobScript.SpawnStep spawn && !spawn.tag().isEmpty()) handles.add(spawn.tag());
			if (step instanceof MobScript.SpawnMobsStep spawn && !spawn.tag().isEmpty()) handles.add(spawn.tag());
			if (step instanceof MobScript.SpawnEntityStep spawn) {
				EntitySpawnService.resolve(spawn.id());
				if (!spawn.at().matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("steps[" + i + "].at must be an anchor name");
				if (!spawn.saveAs().matches("[a-z][a-z0-9_]{0,63}"))
					throw new IllegalArgumentException("steps[" + i + "].save_as must be a tag name");
				handles.add(spawn.saveAs());
			} else if (step instanceof MobScript.WaitUntilStep wait) {
				ScriptCondition.validate(wait.condition());
				if (wait.timeoutTicks() < 1 || wait.timeoutTicks() > 1_728_000)
					throw new IllegalArgumentException("steps[" + i + "].timeout must be between 1 tick and 24 hours");
				String handle = ScriptCondition.referencedHandle(wait.condition());
				if (handle != null && !handles.contains(handle)) throw new IllegalArgumentException("steps[" + i + "] references a handle before spawn: " + wait.condition());
			} else if (step instanceof MobScript.MobCastStep cast) {
				if (!handles.contains(ScriptCondition.reference(cast.mob()))) throw new IllegalArgumentException("steps[" + i + "] uses mob tag " + cast.mob() + " before any mob_spawn gives it");
				if (MobForge.abilities().get(cast.ability()).isEmpty()) throw new IllegalArgumentException("steps[" + i + "] references unknown ability " + cast.ability());
			} else if (step instanceof MobScript.SetPhaseStep phase) {
				if (!handles.contains(ScriptCondition.reference(phase.mob()))) throw new IllegalArgumentException("steps[" + i + "] uses mob tag " + phase.mob() + " before any mob_spawn gives it");
				if (!phase.phase().matches("[a-z0-9_./-]{1,64}")) throw new IllegalArgumentException("steps[" + i + "] has invalid phase " + phase.phase());
			} else if (step instanceof MobScript.RewardCurrencyStep reward) {
				if (reward.amount() < 1) throw new IllegalArgumentException("steps[" + i + "].amount must be positive");
				if (CurrencyRegistry.require(reward.currency()).scope() != CurrencyDefinition.CurrencyScope.PLAYER)
					throw new IllegalArgumentException("steps[" + i + "] needs a player-scoped currency");
			} else if (step instanceof MobScript.RewardItemStep reward) {
				if (reward.count() < 1 || reward.count() > 2304)
					throw new IllegalArgumentException("steps[" + i + "].count must be from 1 to 2304");
				var rewardItem = BuiltInRegistries.ITEM.getValue(reward.item());
				if (rewardItem == null || rewardItem == Items.AIR)
					throw new IllegalArgumentException("steps[" + i + "] references unknown item " + reward.item());
			} else if (step instanceof MobScript.RewardLootStep reward) {
				if (reward.rolls() < 1 || reward.rolls() > 32)
					throw new IllegalArgumentException("steps[" + i + "].rolls must be from 1 to 32");
			} else if (step instanceof MobScript.LootPoolStep loot) {
				String player = loot.playerId();
				if (!player.isBlank() && !player.matches("[a-z][a-z0-9_]*")
						&& !player.matches("\\$\\{[a-z][a-z0-9_]*}")
						&& !player.matches("[0-9a-fA-F-]{36}"))
					throw new IllegalArgumentException("steps[" + i + "] player must be a player variable or UUID");
			} else if (step instanceof MobScript.AchievementStep achievement) {
				String player = achievement.playerId();
				if (!player.isBlank() && !player.matches("[a-z][a-z0-9_]*")
						&& !player.matches("\\$\\{[a-z][a-z0-9_]*}")
						&& !player.matches("[0-9a-fA-F-]{36}"))
					throw new IllegalArgumentException("steps[" + i + "] player must be a player variable or UUID");
			} else if (step instanceof MobScript.BuildingStep building) {
				if (!finite(building.location().x(), building.location().y(), building.location().z()))
					throw new IllegalArgumentException("steps[" + i + "].location must be finite");
			} else if (step instanceof MobScript.ParticleStep particles) {
				if (particles.count() < 1 || particles.count() > 1000 || !finite(particles.speed())
						|| particles.speed() < 0 || !finite(particles.location().x(), particles.location().y(), particles.location().z())
						|| !finite(particles.offset().x(), particles.offset().y(), particles.offset().z())
						|| particles.offset().x() < 0 || particles.offset().y() < 0 || particles.offset().z() < 0)
					throw new IllegalArgumentException("steps[" + i + "] has invalid particles");
				if (!(BuiltInRegistries.PARTICLE_TYPE.getValue(particles.particleId())
						instanceof net.minecraft.core.particles.ParticleOptions))
					throw new IllegalArgumentException("steps[" + i + "] particle needs additional data");
			} else if (step instanceof MobScript.ExplosionStep explosion) {
				if (!finite(explosion.location().x(), explosion.location().y(), explosion.location().z())
						|| !Float.isFinite(explosion.power()) || explosion.power() <= 0 || explosion.power() > 8)
					throw new IllegalArgumentException("steps[" + i + "] explosion power must be 0-8");
			} else if (step instanceof MobScript.DistancesStep distances) {
				for (int value : new int[] {distances.view(), distances.simulation()})
					if (value != -1 && value != 0 && (value < 2 || value > 32))
						throw new IllegalArgumentException("steps[" + i + "] distances must be 2-32 chunks, or 0 for the portal's setting");
				if (distances.view() < 0 && distances.simulation() < 0)
					throw new IllegalArgumentException("steps[" + i + "] distances needs view or simulation");
			} else if (step instanceof MobScript.SoundStep sound) {
				if (!finite(sound.location().x(), sound.location().y(), sound.location().z())
						|| BuiltInRegistries.SOUND_EVENT.getValue(sound.soundId()) == null)
					throw new IllegalArgumentException("steps[" + i + "] has unknown sound or invalid location");
			} else if (step instanceof MobScript.EffectStep effect) {
				if (!BuiltInRegistries.MOB_EFFECT.containsKey(effect.effectId())
						|| !java.util.List.of("apply", "remove").contains(effect.action())
						|| effect.amplifier() < 0 || effect.amplifier() > 255)
					throw new IllegalArgumentException("steps[" + i + "] has invalid effect, action, or amplifier");
				String player = effect.playerId();
				if (!player.isBlank() && !player.matches("[a-z][a-z0-9_]*")
						&& !player.matches("\\$\\{[a-z][a-z0-9_]*}")
						&& !player.matches("[0-9a-fA-F-]{36}"))
					throw new IllegalArgumentException("steps[" + i + "] player_id must be a player variable or UUID");
			}
			if (step instanceof MobScript.SpawnStep spawn) {
				if (MobForge.TEMPLATES.get(spawn.template()).isEmpty()) throw new IllegalArgumentException("steps[" + i + "] references unknown template: " + spawn.template());
				if (spawn.count() < 1 || spawn.count() > 1000) throw new IllegalArgumentException("steps[" + i + "].count must be between 1 and 1000");
				if (!finite(spawn.location().x(), spawn.location().y(), spawn.location().z())) throw new IllegalArgumentException("steps[" + i + "].location must be finite");
				if (!finite(spawn.offset().x(), spawn.offset().y(), spawn.offset().z()) || spawn.offset().x() < 0 || spawn.offset().y() < 0 || spawn.offset().z() < 0)
					throw new IllegalArgumentException("steps[" + i + "].offset must be finite and non-negative");
				if (spawn.aiOverride() != null) {
					var ai = spawn.aiOverride();
					if (!MobTemplateValidator.AI_MODES.contains(ai.mode())) throw new IllegalArgumentException("steps[" + i + "].ai has unknown mode: " + ai.mode());
					if ("target_position".equals(ai.mode()) && ai.target() == null) throw new IllegalArgumentException("steps[" + i + "].ai.target is required for target_position");
					if ("target_player".equals(ai.mode()) && (ai.player() == null || ai.player().isBlank())) throw new IllegalArgumentException("steps[" + i + "].ai.player is required for target_player");
					if (ai.arrivalRadius() < 0 || !finite(ai.arrivalRadius())) throw new IllegalArgumentException("steps[" + i + "].ai.arrival_radius must be non-negative");
					if (ai.range() <= 0 || !finite(ai.range())) throw new IllegalArgumentException("steps[" + i + "].ai.range must be greater than zero");
				}
			} else if (step instanceof MobScript.SpawnMobsStep spawn) {
				EntitySpawnService.resolve(spawn.mobId());
				if (spawn.count() < 1 || spawn.count() > 1000)
					throw new IllegalArgumentException("steps[" + i + "].count must be between 1 and 1000");
				if (!finite(spawn.location().x(), spawn.location().y(), spawn.location().z()))
					throw new IllegalArgumentException("steps[" + i + "].location must be finite");
				if (!finite(spawn.offset().x(), spawn.offset().y(), spawn.offset().z())
						|| spawn.offset().x() < 0 || spawn.offset().y() < 0 || spawn.offset().z() < 0)
					throw new IllegalArgumentException("steps[" + i + "].offset must be finite and non-negative");
			} else if (step instanceof MobScript.SleepStep sleep && (sleep.ticks() < 0 || sleep.ticks() > 1_728_000))
				throw new IllegalArgumentException("steps[" + i + "] sleep must be between 0 and 24 hours");
		}
	}

	public Optional<MobScript> get(String id) { return Optional.ofNullable(scripts.get(id)); }
	public Collection<MobScript> all() { return scripts.values().stream().sorted(Comparator.comparing(MobScript::id)).toList(); }
	public synchronized void save(MobScript script) throws IOException {
		validate(script); Files.createDirectories(directory);
		Path destination = directory.resolve(script.id() + ".mfs").normalize();
		if (!destination.getParent().equals(directory.normalize())) throw new IOException("unsafe script path");
		Path temporary = Files.createTempFile(directory, script.id() + "-", ".tmp");
		try {
			Files.writeString(temporary, code.encode(script), StandardCharsets.UTF_8);
			MobScript roundTrip = code.decode(Files.readString(temporary, StandardCharsets.UTF_8)); validate(roundTrip);
			try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
			Files.deleteIfExists(directory.resolve(script.id() + ".yml"));
			Files.deleteIfExists(directory.resolve(script.id() + ".yaml"));
		} finally { Files.deleteIfExists(temporary); }
		reload();
	}
	public synchronized boolean delete(String id) throws IOException {
		if (!MobTemplateValidator.SAFE_ID.matcher(id).matches()) return false;
		boolean deleted = Files.deleteIfExists(directory.resolve(id + ".mfs")); deleted |= Files.deleteIfExists(directory.resolve(id + ".yml")); deleted |= Files.deleteIfExists(directory.resolve(id + ".yaml")); reload(); return deleted;
	}
	private static boolean finite(double... values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
	private static boolean isCode(Path path) { return path.getFileName().toString().toLowerCase().endsWith(".mfs"); }
	private static boolean isScript(Path path) { String name = path.getFileName().toString().toLowerCase(); return name.endsWith(".mfs") || name.endsWith(".yml") || name.endsWith(".yaml"); }
	private static String cleanMessage(Exception exception) { Throwable cause = exception; while (cause.getCause() != null) cause = cause.getCause(); return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(); }
	public record ReloadResult(int loaded, int failed) { }
}
