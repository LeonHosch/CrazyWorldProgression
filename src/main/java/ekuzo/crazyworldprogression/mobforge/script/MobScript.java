package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.resources.Identifier;

import java.util.List;

public record MobScript(String id, List<Step> steps) {
	public MobScript { steps = steps == null ? List.of() : List.copyOf(steps); }

	public sealed interface Step permits SpawnStep, SpawnMobsStep, WaitDurationStep, SpawnEntityStep, WaitUntilStep, MobCastStep, SetPhaseStep, TriggerStep, RewardCurrencyStep, RewardItemStep, RewardLootStep, LootPoolStep, SkillXpStep, AchievementStep, VariableStep, EventStep, EventBoxStep, RedstoneStep, IfStep, ElseStep, EndIfStep, LoopStep, EndLoopStep, PortalLocationStep, BuildingStep, ParticleStep, ExplosionStep, SoundStep, StopSoundStep, DistancesStep, EffectStep, EncounterStartStep, EncounterEndStep, FinishInstanceStep, WipeStep, AreaStep, ReplaceBlocksStep, AuraStep, MessageStep, DespawnStep, MobHideStep { }
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
	/**
	 * mob_spawn(mob_id=...): mobs by entity type or MobForge ID. A non-empty tag is added to every spawned mob.
	 * With {@code area} the mobs stay inside that event area, drop targets outside it and cannot be hurt from
	 * outside it; {@code perPlayer} more of them spawn for every player in the area beyond the first, up to
	 * {@code maxCount} (0: no limit). {@code health} and {@code damage} multiply the mob's values (1 keeps them), and
	 * {@code fireproof} keeps undead from burning in daylight. With {@code cap} above 0, no more mobs spawn than keep
	 * the number of living mobs with this tag at {@code cap}, plus {@code capPerPlayer} for every player in the area
	 * beyond {@code capAfter}, at most {@code capMax} (0: no limit).
	 */
	public record SpawnMobsStep(Identifier mobId, Position location, Offset offset, int count, String tag, String area,
	                            int perPlayer, int maxCount, double health, double damage, boolean fireproof,
	                            int cap, int capPerPlayer, int capAfter, int capMax) implements Step {
		public SpawnMobsStep {
			offset = offset == null ? Offset.NONE : offset;
			tag = tag == null ? "" : tag;
			area = area == null ? "" : area;
		}
		public SpawnMobsStep(Identifier mobId, Position location, Offset offset, int count, String tag) {
			this(mobId, location, offset, count, tag, "", 0, 0, 1, 1, false, 0, 0, 0, 0);
		}
		public SpawnMobsStep(Identifier mobId, Position location, Offset offset, int count) {
			this(mobId, location, offset, count, "");
		}
		public SpawnMobsStep at(Position location) {
			return new SpawnMobsStep(mobId, location, offset, count, tag, area, perPlayer, maxCount, health, damage, fireproof,
					cap, capPerPlayer, capAfter, capMax);
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
	/**
	 * An event box. With {@code charging} it is a charging platform, with {@code delivery} a delivery box (both
	 * player boxes only; null when not set).
	 */
	public record EventBoxStep(String name, Position min, Position max, String mode, String mobId,
	                           String playerVariable, String countVariable, Charging charging,
	                           Delivery delivery) implements Step {
		public EventBoxStep(String name, Position min, Position max, String mode, String mobId,
		                    String playerVariable, String countVariable) {
			this(name, min, max, mode, mobId, playerVariable, countVariable, null, null);
		}
		public EventBoxStep(String name, Position min, Position max) {
			this(name, min, max, "players", "", "", "");
		}
		/** The same box with other corners (moving, pocket translation). */
		public EventBoxStep at(Position newMin, Position newMax) {
			return new EventBoxStep(name, newMin, newMax, mode, mobId, playerVariable, countVariable, charging, delivery);
		}
		public EventBoxStep(String name, Position min, Position max, String mode, String mobId) {
			this(name, min, max, mode, mobId, "", "");
		}
		public EventBoxStep(String name, Position min, Position max, String mode, String mobId,
		                    String playerVariable) {
			this(name, min, max, mode, mobId, playerVariable, "");
		}
	}
	/**
	 * A charging platform. Every {@code intervalTicks} each player inside gains one stack of the per-player counter
	 * {@code stackId} (shared by every box with the same ID). At {@code maxStacks} the stacks are used up: while the
	 * box has {@code charges} left the player gets effect {@code onMax} for {@code onMaxTicks} (0: until removed),
	 * otherwise the player dies. With {@code onExpire} "explode", someone whose effect runs out explodes. With
	 * {@code abandonTicks} above 0, once the box has been entered, every tick nobody is inside adds one danger tick and
	 * every tick someone is inside takes {@code abandonRecover} away; at {@code abandonTicks} the box explodes and the
	 * encounter wipes.
	 */
	public record Charging(String stackId, long intervalTicks, int maxStacks, int charges, Identifier onMax,
	                       long onMaxTicks, String onExpire, long abandonTicks, double abandonRecover,
	                       Identifier stackEffect, long decayTicks) {
		public Charging(String stackId, long intervalTicks, int maxStacks, int charges, Identifier onMax,
		                long onMaxTicks, String onExpire, long abandonTicks, double abandonRecover) {
			this(stackId, intervalTicks, maxStacks, charges, onMax, onMaxTicks, onExpire, abandonTicks, abandonRecover,
					null, 0);
		}
	}
	/** A delivery box: takes effect {@code removeEffect} off players inside and adds 1 to {@code variable} each time. */
	public record Delivery(Identifier removeEffect, String variable) { }
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
	/**
	 * Script step "building": action "add" pastes the saved blocks, "delete" clears them (formerly spawn/despawn).
	 * Mode "skip_air" places only the building's own blocks and removes only those; "replace" also writes its air,
	 * clearing whatever stood in its box, and removal clears the whole box. Blank uses the config default.
	 */
	public record BuildingStep(Identifier buildId, String action, Position location, String mode) implements Step {
		public static final String SKIP_AIR = "skip_air", REPLACE = "replace";
		public BuildingStep {
			if ("spawn".equals(action)) action = "add";
			else if ("despawn".equals(action)) action = "delete";
			if (mode == null) mode = "";
		}
		public BuildingStep(Identifier buildId, String action, Position location) {
			this(buildId, action, location, "");
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
	/**
	 * A round event area: {@code radius} blocks around {@code center}, {@code height} blocks up and down. Players
	 * inside are listed in {@code playerVariable}; someone who leaves is warned and counted down for
	 * {@code graceTicks}, then taken off the list (back inside in time, they stay on it). {@code countVariable} holds
	 * how many are on the list. With {@code defendRadius} above 0, mobs kept in the area are likely to go after players
	 * within that distance of the center (guards defending what lies there).
	 */
	public record AreaStep(String name, Position center, double radius, double height, long graceTicks,
	                       String playerVariable, String countVariable, double defendRadius) implements Step {
		public AreaStep(String name, Position center, double radius, double height, long graceTicks,
		                String playerVariable, String countVariable) {
			this(name, center, radius, height, graceTicks, playerVariable, countVariable, 0);
		}
		public boolean contains(net.minecraft.world.phys.Vec3 point) {
			double dx = point.x - center.x(), dz = point.z - center.z();
			return dx * dx + dz * dz <= radius * radius && Math.abs(point.y - center.y()) <= height;
		}
	}
	/** Replaces every block {@code from} with {@code to} in a named area or event box, or between min and max. */
	public record ReplaceBlocksStep(Identifier from, Identifier to, String area, Position min, Position max) implements Step { }
	/**
	 * A stacking effect around {@code center}: every {@code intervalTicks}, players within {@code radius} get one
	 * level more of the effect (up to {@code maxStacks}), lasting {@code durationTicks}. Action "remove" ends it.
	 */
	public record AuraStep(String name, Identifier effectId, Position center, double radius, long intervalTicks,
	                       int maxStacks, long durationTicks, String action) implements Step { }
	/** Shows text to players: style "chat", "actionbar" or "title"; a blank playerId targets the whole world. */
	public record MessageStep(String text, String playerId, String style, String color) implements Step { }
	/** Removes every living mob with this tag (with a puff of smoke), for example when an event ends. */
	public record DespawnStep(String tag) implements Step { }
	/**
	 * Hides the living mobs with this tag (invisible, invulnerable, frozen, silent; their health stays) or shows them
	 * again, moving them to {@code location} when it is set. A boss can leave between damage phases and come back
	 * with the health it had.
	 */
	public record MobHideStep(String tag, boolean hidden, Position location) implements Step { }
}
