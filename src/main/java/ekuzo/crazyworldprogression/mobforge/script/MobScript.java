package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.resources.Identifier;

import java.util.List;

public record MobScript(String id, List<Step> steps) {
	public MobScript { steps = steps == null ? List.of() : List.copyOf(steps); }

	public sealed interface Step permits SpawnStep, SpawnMobsStep, WaitDurationStep, SpawnEntityStep, WaitUntilStep, MobCastStep, SetPhaseStep, TriggerStep, RewardCurrencyStep, RewardItemStep, RewardLootStep, LootPoolStep, SkillXpStep, AchievementStep, VariableStep, EventStep, EventBoxStep, RedstoneStep, IfStep, ElseStep, EndIfStep, LoopStep, EndLoopStep, PortalLocationStep, BuildingStep, ParticleStep, ExplosionStep, SoundStep, StopSoundStep, DistancesStep, EffectStep, EncounterStartStep, EncounterEndStep, FinishInstanceStep, WipeStep { }
	public record Position(double x, double y, double z) { }
	public record Offset(double x, double y, double z) {
		public static final Offset NONE = new Offset(0, 0, 0);
	}
	/** mob_spawn(template=...): MobForge template mobs. A non-empty tag is added to every spawned mob. */
	public record SpawnStep(String template, Position location, Offset offset, int count, MobTemplate.Ai aiOverride,
	                        String tag) implements Step {
		public SpawnStep { offset = offset == null ? Offset.NONE : offset; tag = tag == null ? "" : tag; }
		public SpawnStep(String template, Position location, Offset offset, int count, MobTemplate.Ai aiOverride) {
			this(template, location, offset, count, aiOverride, "");
		}
	}
	/** One namespaced mob ID, usable for both MobForge templates and registered API mobs. */
	/** mob_spawn(mob_id=...): mobs by entity type or MobForge ID. A non-empty tag is added to every spawned mob. */
	public record SpawnMobsStep(Identifier mobId, Position location, Offset offset, int count, String tag) implements Step {
		public SpawnMobsStep { offset = offset == null ? Offset.NONE : offset; tag = tag == null ? "" : tag; }
		public SpawnMobsStep(Identifier mobId, Position location, Offset offset, int count) {
			this(mobId, location, offset, count, "");
		}
	}
	public record WaitDurationStep(long ticks) implements Step { }
	public record SpawnEntityStep(Identifier id, String at, String saveAs) implements Step { }
	public record WaitUntilStep(String condition, long timeoutTicks) implements Step { }
	public record MobCastStep(String mob, Identifier ability) implements Step { }
	public record SetPhaseStep(String mob, String phase) implements Step { }
	public record TriggerStep(Identifier event) implements Step { }
	public record RewardCurrencyStep(Identifier currency, long amount) implements Step { }
	public record RewardItemStep(Identifier item, int count) implements Step { }
	public record RewardLootStep(Identifier lootTable, int rolls) implements Step { }
	public record LootPoolStep(Identifier lootPoolId, String playerId) implements Step { }
	/** Gives skill XP (dungeoning unless another skill is named) to the players in a list variable, or everyone inside. */
	public record SkillXpStep(String skill, int amount, String playerId) implements Step { }
	/** Award all remaining criteria of a real advancement to the selected players. */
	public record AchievementStep(Identifier achievementId, String playerId) implements Step { }
	/** All state belongs to one run, so parallel pocket instances never share variables. */
	public record VariableStep(String name, String operation, String value) implements Step { }
	/** A watched condition fires on a false-to-true transition after this step registers it. */
	public record EventStep(String condition, String variable, String operation, String value) implements Step { }
	public record EventBoxStep(String name, Position min, Position max, String mode, String mobId,
	                           String playerVariable, String countVariable) implements Step {
		public EventBoxStep(String name, Position min, Position max) {
			this(name, min, max, "players", "", "", "");
		}
		public EventBoxStep(String name, Position min, Position max, String mode, String mobId) {
			this(name, min, max, mode, mobId, "", "");
		}
		public EventBoxStep(String name, Position min, Position max, String mode, String mobId,
		                    String playerVariable) {
			this(name, min, max, mode, mobId, playerVariable, "");
		}
	}
	public record RedstoneStep(String name, Position position) implements Step { }
	public record IfStep(String condition) implements Step { }
	public record ElseStep() implements Step { }
	public record EndIfStep() implements Step { }
	/** Rechecks condition at the end, with a hard iteration cap. */
	public record LoopStep(String condition, int maxIterations) implements Step { }
	public record EndLoopStep() implements Step { }
	/**
	 * Add or delete a named location of an existing portal preset in this instance (script step "portal").
	 * Action is "add" or "delete"; deleting with an empty location deletes every location of the preset.
	 * Older scripts used "remove" (one location) and "clear" (all); both are mapped to "delete".
	 */
	public record PortalLocationStep(Identifier portalId, String action, String locationId, Position position,
	                                 Position target) implements Step {
		public PortalLocationStep {
			if ("clear".equals(action)) { action = "delete"; locationId = ""; }
			else if ("remove".equals(action)) action = "delete";
			if (locationId == null) locationId = "";
		}
		public PortalLocationStep(Identifier portalId, String action, String locationId, Position position) {
			this(portalId, action, locationId, position, null);
		}
		/** True when this deletes every location of the preset, including its original placement. */
		public boolean deletesAll() { return action.equals("delete") && locationId.isEmpty(); }
	}
	/** Marks the instance finished; it closes as soon as all players have left. */
	public record FinishInstanceStep(Position spawn) implements Step {
		public FinishInstanceStep() { this(null); }
	}
	public record EncounterStartStep() implements Step { }
	/** Fail the encounter: everyone is downed, and after 5 seconds the encounter resets unless it ends first. */
	public record WipeStep() implements Step { }
	/**
	 * Ends a raid encounter. The optional spawn becomes the instance's rally, respawn and rejoin point, and is
	 * where a run resumed from this checkpoint starts.
	 */
	public record EncounterEndStep(Position spawn) implements Step {
		public EncounterEndStep() { this(null); }
	}
	/** Script step "building": action "add" pastes the saved blocks, "delete" clears them (formerly spawn/despawn). */
	public record BuildingStep(Identifier buildId, String action, Position location) implements Step {
		public BuildingStep {
			if ("spawn".equals(action)) action = "add";
			else if ("despawn".equals(action)) action = "delete";
		}
	}
	public record ParticleStep(Identifier particleId, Position location, Offset offset, int count, double speed) implements Step { }
	public record ExplosionStep(Position location, float power) implements Step { }
	/** View and simulation distance of the instance in chunks: -1 leaves it, 0 returns to the portal's setting. */
	public record DistancesStep(int view, int simulation) implements Step { }
	/** {@code location} null plays the sound to every player in the world, attached to them, like music. */
	public record SoundStep(Identifier soundId, Position location) implements Step { }
	/** Stops a sound the scripts played in this world; {@code soundId} null stops all of them. */
	public record StopSoundStep(Identifier soundId) implements Step { }
	/** A blank playerId targets every player in this run's world or pocket instance. */
	public record EffectStep(Identifier effectId, String playerId, String action, int amplifier) implements Step { }
}
