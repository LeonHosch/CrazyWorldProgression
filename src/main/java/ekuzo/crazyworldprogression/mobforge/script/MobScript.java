package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.resources.Identifier;

import java.util.List;

public record MobScript(String id, List<Step> steps) {
	public MobScript { steps = steps == null ? List.of() : List.copyOf(steps); }

	public sealed interface Step permits SpawnStep, SpawnMobsStep, SleepStep, SpawnEntityStep, WaitUntilStep, MobCastStep, SetPhaseStep, TriggerStep, RewardCurrencyStep, RewardItemStep, RewardLootStep, LootPoolStep, AchievementStep, VariableStep, EventStep, EventBoxStep, RedstoneStep, IfStep, ElseStep, EndIfStep, LoopStep, EndLoopStep, PortalLocationStep, BuildingStep, ParticleStep, ExplosionStep, SoundStep, EffectStep, EncounterStartStep, EncounterEndStep, RaidCompleteStep { }
	public record Position(double x, double y, double z) { }
	public record Offset(double x, double y, double z) {
		public static final Offset NONE = new Offset(0, 0, 0);
	}
	public record SpawnStep(String template, Position location, Offset offset, int count, MobTemplate.Ai aiOverride) implements Step {
		public SpawnStep { offset = offset == null ? Offset.NONE : offset; }
	}
	/** One namespaced mob ID, usable for both MobForge templates and registered API mobs. */
	public record SpawnMobsStep(Identifier mobId, Position location, Offset offset, int count) implements Step {
		public SpawnMobsStep { offset = offset == null ? Offset.NONE : offset; }
	}
	public record SleepStep(long ticks) implements Step { }
	public record SpawnEntityStep(Identifier id, String at, String saveAs) implements Step { }
	public record WaitUntilStep(String condition, long timeoutTicks) implements Step { }
	public record MobCastStep(String mob, Identifier ability) implements Step { }
	public record SetPhaseStep(String mob, String phase) implements Step { }
	public record TriggerStep(Identifier event) implements Step { }
	public record RewardCurrencyStep(Identifier currency, long amount) implements Step { }
	public record RewardItemStep(Identifier item, int count) implements Step { }
	public record RewardLootStep(Identifier lootTable, int rolls) implements Step { }
	public record LootPoolStep(Identifier lootPoolId, String playerId) implements Step { }
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
	public record RaidCompleteStep() implements Step { }
	public record EncounterStartStep() implements Step { }
	public record EncounterEndStep() implements Step { }
	/** Script step "building": action "add" pastes the saved blocks, "delete" clears them (formerly spawn/despawn). */
	public record BuildingStep(Identifier buildId, String action, Position location) implements Step {
		public BuildingStep {
			if ("spawn".equals(action)) action = "add";
			else if ("despawn".equals(action)) action = "delete";
		}
	}
	public record ParticleStep(Identifier particleId, Position location, Offset offset, int count, double speed) implements Step { }
	public record ExplosionStep(Position location, float power) implements Step { }
	public record SoundStep(Identifier soundId, Position location) implements Step { }
	/** A blank playerId targets every player in this run's world or pocket instance. */
	public record EffectStep(Identifier effectId, String playerId, String action, int amplifier) implements Step { }
}
