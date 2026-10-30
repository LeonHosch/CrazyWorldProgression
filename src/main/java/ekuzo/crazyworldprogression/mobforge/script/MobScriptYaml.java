package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.resources.Identifier;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MobScriptYaml {
	private static final Pattern DURATION = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(ms|t|ticks?|s|sec(?:onds?)?|m|min(?:utes?)?)?", Pattern.CASE_INSENSITIVE);
	private final Load loader = new Load(LoadSettings.builder().setLabel("MobForge script").setCodePointLimit(1_000_000).build());
	private final Dump dumper = new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).build());

	public MobScript decode(String yaml) {
		Map<String, Object> root = map(loader.loadFromString(yaml), "root");
		String id = string(root.get("id"), "id");
		Object rawSteps = root.get("steps");
		if (!(rawSteps instanceof List<?> steps)) throw new IllegalArgumentException("steps must be a list");
		List<MobScript.Step> decoded = new ArrayList<>();
		for (int i = 0; i < steps.size(); i++) decoded.add(step(steps.get(i), "steps[" + i + "]"));
		MobScriptFlow.compile(decoded);
		return new MobScript(id, decoded);
	}

	private MobScript.Step step(Object value, String field) {
		Map<String, Object> step = map(value, field);
		if (step.containsKey("mob_spawn")) return mobSpawn(map(step.get("mob_spawn"), field + ".mob_spawn"), field + ".mob_spawn");
		if (step.containsKey("spawn")) return spawn(map(step.get("spawn"), field + ".spawn"), field);
		if (step.containsKey("spawn_mobs")) return spawnMobs(map(step.get("spawn_mobs"), field + ".spawn_mobs"), field);
		if (step.containsKey("spawn_entity")) return spawnEntity(map(step.get("spawn_entity"), field + ".spawn_entity"), field);
		if (step.containsKey("wait")) return waitUntil(map(step.get("wait"), field + ".wait"), field);
		if (step.containsKey("wait_until")) return waitUntil(map(step.get("wait_until"), field + ".wait_until"), field);
		if (step.containsKey("mob_cast")) return cast(map(step.get("mob_cast"), field + ".mob_cast"), field);
		if (step.containsKey("mob_phase")) return setPhase(map(step.get("mob_phase"), field + ".mob_phase"), field);
		if (step.containsKey("set_phase")) return setPhase(map(step.get("set_phase"), field + ".set_phase"), field);
		if (step.containsKey("trigger")) return trigger(map(step.get("trigger"), field + ".trigger"), field);
		if (step.containsKey("reward_currency")) return rewardCurrency(map(step.get("reward_currency"), field + ".reward_currency"), field);
		if (step.containsKey("reward_item")) return rewardItem(map(step.get("reward_item"), field + ".reward_item"), field);
		if (step.containsKey("reward_loot")) return rewardLoot(map(step.get("reward_loot"), field + ".reward_loot"), field);
		if (step.containsKey("loot")) return loot(map(step.get("loot"), field + ".loot"), field);
		if (step.containsKey("skill_xp")) return skillXp(map(step.get("skill_xp"), field + ".skill_xp"), field);
		if (step.containsKey("achievement")) return achievement(map(step.get("achievement"), field + ".achievement"), field);
		if (step.containsKey("variable")) return variable(map(step.get("variable"), field + ".variable"), field);
		if (step.containsKey("event")) return event(map(step.get("event"), field + ".event"), field);
		if (step.containsKey("eventbox")) return eventBox(map(step.get("eventbox"), field + ".eventbox"), field);
		if (step.containsKey("event_box")) return eventBox(map(step.get("event_box"), field + ".event_box"), field);
		if (step.containsKey("redstone_sensor")) return redstone(map(step.get("redstone_sensor"), field + ".redstone_sensor"), field);
		if (step.containsKey("if")) return new MobScript.IfStep(condition(step.get("if"), field + ".if"));
		if (step.containsKey("else")) return new MobScript.ElseStep();
		if (step.containsKey("end_if")) return new MobScript.EndIfStep();
		if (step.containsKey("loop")) return loop(map(step.get("loop"), field + ".loop"), field);
		if (step.containsKey("end_loop")) return new MobScript.EndLoopStep();
		if (step.containsKey("portal")) return portalLocation(map(step.get("portal"), field + ".portal"), field);
		if (step.containsKey("portal_location")) return portalLocation(map(step.get("portal_location"), field + ".portal_location"), field);
		if (step.containsKey("building")) return building(map(step.get("building"), field + ".building"), field);
		if (step.containsKey("particles")) return particles(map(step.get("particles"), field + ".particles"), field);
		if (step.containsKey("explosion")) return explosion(map(step.get("explosion"), field + ".explosion"), field);
		if (step.containsKey("distances")) return distances(map(step.get("distances"), field + ".distances"), field);
		if (step.containsKey("sound")) return sound(map(step.get("sound"), field + ".sound"), field);
		if (step.containsKey("sound_stop")) return soundStop(step.get("sound_stop"), field + ".sound_stop");
		if (step.containsKey("effect")) return effect(map(step.get("effect"), field + ".effect"), field);
		if (step.containsKey("finish_instance")) return finishInstance(step.get("finish_instance"), field + ".finish_instance");
		if (step.containsKey("encounter_start")) return new MobScript.EncounterStartStep();
		if (step.containsKey("wipe")) return new MobScript.WipeStep();
		if (step.containsKey("encounter_end")) return encounterEnd(step.get("encounter_end"), field + ".encounter_end");
		// "area" and "message" are also argument names, so the short form only counts when it is the step's only key.
		if (step.size() == 1 && step.containsKey("area")) return area(map(step.get("area"), field + ".area"), field);
		if (step.size() == 1 && step.containsKey("replace_blocks")) return replaceBlocks(map(step.get("replace_blocks"), field + ".replace_blocks"), field);
		if (step.size() == 1 && step.containsKey("aura")) return aura(map(step.get("aura"), field + ".aura"), field);
		if (step.size() == 1 && step.containsKey("message")) return message(map(step.get("message"), field + ".message"), field);
		if (step.containsKey("mob_despawn")) return new MobScript.DespawnStep(name(map(step.get("mob_despawn"), field + ".mob_despawn").get("mob"), field + ".mob_despawn.mob"));
		String type = string(step.get("type"), field + ".type");
		return switch (type) {
			case "mob_spawn" -> mobSpawn(step, field);
			case "spawn" -> spawn(step, field);
			case "spawn_mobs" -> spawnMobs(step, field);
			case "spawn_entity" -> spawnEntity(step, field);
			case "wait", "wait_until" -> waitUntil(step, field);
			case "mob_cast" -> cast(step, field);
			case "mob_phase", "set_phase" -> setPhase(step, field);
			case "trigger" -> trigger(step, field);
			case "reward_currency" -> rewardCurrency(step, field);
			case "reward_item" -> rewardItem(step, field);
			case "reward_loot" -> rewardLoot(step, field);
			case "loot" -> loot(step, field);
			case "skill_xp" -> skillXp(step, field);
			case "achievement" -> achievement(step, field);
			case "variable" -> variable(step, field);
			case "event" -> event(step, field);
			case "eventbox", "event_box" -> eventBox(step, field);
			case "redstone_sensor" -> redstone(step, field);
			case "if" -> new MobScript.IfStep(condition(step.get("condition"), field + ".condition"));
			case "else" -> new MobScript.ElseStep();
			case "end_if" -> new MobScript.EndIfStep();
			case "loop" -> loop(step, field);
			case "end_loop" -> new MobScript.EndLoopStep();
			case "portal", "portal_location" -> portalLocation(step, field);
			case "building" -> building(step, field);
			case "particles" -> particles(step, field);
			case "explosion" -> explosion(step, field);
			case "distances" -> distances(step, field);
			case "sound" -> sound(step, field);
			case "sound_stop" -> soundStop(step, field);
			case "effect" -> effect(step, field);
			case "finish_instance" -> finishInstance(step, field);
			case "encounter_start" -> new MobScript.EncounterStartStep();
			case "wipe" -> new MobScript.WipeStep();
			case "encounter_end" -> encounterEnd(step, field);
			case "area" -> area(step, field);
			case "replace_blocks" -> replaceBlocks(step, field);
			case "aura" -> aura(step, field);
			case "message" -> message(step, field);
			case "mob_despawn" -> new MobScript.DespawnStep(name(step.get("mob"), field + ".mob"));
			default -> throw new IllegalArgumentException(field + ".type is unknown: " + type);
		};
	}
	private static String condition(Object value, String field) {
		String expression = string(value, field);
		ScriptCondition.validate(expression);
		return expression;
	}
	private MobScript.Step variable(Map<String, Object> value, String field) {
		String name = string(value.get("name"), field + ".name");
		String operation = string(value.getOrDefault("operation", "set"), field + ".operation");
		String amount = string(value.getOrDefault("value", "0"), field + ".value");
		validateVariable(name, operation, amount);
		return new MobScript.VariableStep(name, operation, amount);
	}
	private MobScript.Step event(Map<String, Object> value, String field) {
		String expression = condition(value.get("condition"), field + ".condition");
		String variable = string(value.get("variable"), field + ".variable");
		String operation = string(value.getOrDefault("operation", "increment"), field + ".operation");
		String amount = string(value.getOrDefault("value", "1"), field + ".value");
		validateVariable(variable, operation, amount);
		return new MobScript.EventStep(expression, variable, operation, amount);
	}
	private static void validateVariable(String name, String operation, String value) {
		if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Variable name must use lowercase letters, digits, or _");
		if (!operation.equals("set") && !operation.equals("increment")) throw new IllegalArgumentException("Variable operation must be set or increment");
		if (operation.equals("increment") && (value.equals("true") || value.equals("false")))
			throw new IllegalArgumentException("Increment needs a whole number");
		if (!value.equals("true") && !value.equals("false")) Long.parseLong(value);
	}
	private MobScript.Step eventBox(Map<String, Object> value, String field) {
		String name = string(value.get("name"), field + ".name");
		if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid event box name");
		MobScript.Position min = position(map(value.get("min"), field + ".min"), field + ".min");
		MobScript.Position max = position(map(value.get("max"), field + ".max"), field + ".max");
		new ScriptCondition.Box(min, max);
		String mode = string(value.getOrDefault("mode", "players"), field + ".mode");
		if (!List.of("players", "mobs").contains(mode)) throw new IllegalArgumentException("Event box mode must be players or mobs");
		String mobId = mode.equals("mobs") ? string(value.get("mob_id"), field + ".mob_id") : "";
		String playerVariable = String.valueOf(value.getOrDefault("player_variable", ""));
		String countVariable = String.valueOf(value.getOrDefault("count_variable", ""));
		if (!playerVariable.isEmpty() && !playerVariable.matches("[a-z][a-z0-9_]*"))
			throw new IllegalArgumentException("Invalid event box player variable");
		if (!countVariable.isEmpty() && !countVariable.matches("[a-z][a-z0-9_]*"))
			throw new IllegalArgumentException("Invalid event box count variable");
		if (!playerVariable.isEmpty() && playerVariable.equals(countVariable))
			throw new IllegalArgumentException("Player list and count need different variable names");
		if (!playerVariable.isEmpty() && !mode.equals("players"))
			throw new IllegalArgumentException("Only player event boxes can store a player");
		return new MobScript.EventBoxStep(name, min, max, mode, mobId, playerVariable, countVariable);
	}
	private MobScript.Step redstone(Map<String, Object> value, String field) {
		String name = string(value.get("name"), field + ".name");
		if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid redstone sensor name");
		return new MobScript.RedstoneStep(name, position(map(value.get("position"), field + ".position"), field + ".position"));
	}
	private MobScript.Step loop(Map<String, Object> value, String field) {
		int limit = integer(value.getOrDefault("max_iterations", 1000), field + ".max_iterations");
		return new MobScript.LoopStep(condition(value.get("condition"), field + ".condition"), limit);
	}
	private MobScript.Step portalLocation(Map<String, Object> value, String field) {
		String action = string(value.get("action"), field + ".action");
		if (!List.of("add", "delete", "remove", "clear").contains(action))
			throw new IllegalArgumentException("Portal action must be add or delete");
		// delete without a location deletes every location; the deprecated remove kept the "main" default.
		String location = action.equals("clear") || action.equals("delete") && !value.containsKey("location") ? ""
				: string(value.getOrDefault("location", "main"), field + ".location");
		if (!location.matches("[a-z][a-z0-9_]*") && !(location.isEmpty() && !action.equals("add")))
			throw new IllegalArgumentException("Invalid portal location name");
		MobScript.Position position = action.equals("add")
				? position(map(value.get("position"), field + ".position"), field + ".position") : new MobScript.Position(0, 0, 0);
		MobScript.Position target = value.containsKey("target")
				? position(map(value.get("target"), field + ".target"), field + ".target") : null;
		if (target != null && !action.equals("add")) throw new IllegalArgumentException("Portal target is only valid with add");
		return new MobScript.PortalLocationStep(namespaced(value.get("portal"), field + ".portal"), action, location, position, target);
	}
	private MobScript.Step encounterEnd(Object value, String field) {
		if (!(value instanceof Map<?, ?> body) || !body.containsKey("spawn")) return new MobScript.EncounterEndStep();
		Map<String, Object> fields = map(value, field);
		return new MobScript.EncounterEndStep(position(map(fields.get("spawn"), field + ".spawn"), field + ".spawn"));
	}
	private MobScript.Step finishInstance(Object value, String field) {
		if (!(value instanceof Map<?, ?> body) || !body.containsKey("spawn")) return new MobScript.FinishInstanceStep();
		Map<String, Object> fields = map(value, field);
		return new MobScript.FinishInstanceStep(position(map(fields.get("spawn"), field + ".spawn"), field + ".spawn"));
	}
	private MobScript.Step building(Map<String, Object> value, String field) {
		String action = string(value.get("action"), field + ".action");
		if (!List.of("add", "delete", "spawn", "despawn").contains(action))
			throw new IllegalArgumentException("Building action must be add or delete");
		String mode = value.get("mode") == null ? "" : string(value.get("mode"), field + ".mode");
		if (!List.of("", MobScript.BuildingStep.SKIP_AIR, MobScript.BuildingStep.REPLACE).contains(mode))
			throw new IllegalArgumentException("Building mode must be skip_air or replace");
		return new MobScript.BuildingStep(namespaced(value.get("build_id"), field + ".build_id"), action,
				position(map(value.get("location"), field + ".location"), field + ".location"), mode);
	}
	private MobScript.Step particles(Map<String, Object> value, String field) {
		return new MobScript.ParticleStep(namespaced(value.get("particle_id"), field + ".particle_id"),
				position(map(value.get("location"), field + ".location"), field + ".location"),
				offset(value.get("offset"), field + ".offset"),
				integer(value.getOrDefault("count", 1), field + ".count"),
				number(value.getOrDefault("speed", 0), field + ".speed"));
	}
	private MobScript.Step explosion(Map<String, Object> value, String field) {
		return new MobScript.ExplosionStep(
				position(map(value.get("location"), field + ".location"), field + ".location"),
				(float) number(value.getOrDefault("power", 2), field + ".power"));
	}
	private MobScript.Step distances(Map<String, Object> value, String field) {
		return new MobScript.DistancesStep(integer(value.getOrDefault("view", -1), field + ".view"),
				integer(value.getOrDefault("simulation", -1), field + ".simulation"));
	}
	private MobScript.Step sound(Map<String, Object> value, String field) {
		return new MobScript.SoundStep(namespaced(value.get("sound_id"), field + ".sound_id"),
				value.containsKey("location") ? position(map(value.get("location"), field + ".location"), field + ".location") : null);
	}
	private MobScript.Step soundStop(Object value, String field) {
		if (!(value instanceof Map<?, ?> body) || !body.containsKey("sound_id")) return new MobScript.StopSoundStep(null);
		return new MobScript.StopSoundStep(namespaced(map(value, field).get("sound_id"), field + ".sound_id"));
	}
	private MobScript.Step effect(Map<String, Object> value, String field) {
		return new MobScript.EffectStep(namespaced(value.get("effect_id"), field + ".effect_id"),
				String.valueOf(value.getOrDefault("player_id", "")),
				String.valueOf(value.getOrDefault("action", "apply")),
				integer(value.getOrDefault("amplifier", 0), field + ".amplifier"));
	}
	private static MobScript.Position position(Map<String, Object> value, String field) {
		return new MobScript.Position(number(value.get("x"), field + ".x"),
				number(value.get("y"), field + ".y"), number(value.get("z"), field + ".z"));
	}
	private MobScript.Step spawnEntity(Map<String, Object> value, String field) {
		return new MobScript.SpawnEntityStep(namespaced(value.get("id"), field + ".id"),
				string(value.get("at"), field + ".at"), string(value.get("save_as"), field + ".save_as"));
	}
	private MobScript.Step waitUntil(Map<String, Object> value, String field) {
		if (value.containsKey("duration")) {
			if (value.size() != 1 && !(value.size() == 2 && value.containsKey("type")))
				throw new IllegalArgumentException(field + " wait duration cannot be combined with condition or timeout");
			return new MobScript.WaitDurationStep(duration(value.get("duration"), field + ".duration"));
		}
		String condition = string(value.get("condition"), field + ".condition");
		ScriptCondition.validate(condition);
		return new MobScript.WaitUntilStep(condition, value.containsKey("timeout")
				? duration(value.get("timeout"), field + ".timeout") : 1_728_000L);
	}
	private MobScript.Step cast(Map<String, Object> value, String field) {
		return new MobScript.MobCastStep(string(value.get("mob"), field + ".mob"), namespaced(value.get("ability"), field + ".ability"));
	}
	private MobScript.Step setPhase(Map<String, Object> value, String field) {
		return new MobScript.SetPhaseStep(string(value.get("mob"), field + ".mob"), string(value.get("phase"), field + ".phase"));
	}
	private MobScript.Step trigger(Map<String, Object> value, String field) {
		return new MobScript.TriggerStep(namespaced(value.get("event"), field + ".event"));
	}
	private MobScript.Step rewardCurrency(Map<String, Object> value, String field) {
		return new MobScript.RewardCurrencyStep(namespaced(value.get("currency"), field + ".currency"),
				positiveLong(value.get("amount"), field + ".amount"));
	}
	private MobScript.Step rewardItem(Map<String, Object> value, String field) {
		int count = integer(value.get("count"), field + ".count");
		if (count < 1 || count > 2304) throw new IllegalArgumentException(field + ".count must be from 1 to 2304");
		return new MobScript.RewardItemStep(namespaced(value.get("item"), field + ".item"), count);
	}
	private MobScript.Step rewardLoot(Map<String, Object> value, String field) {
		int rolls = integer(value.getOrDefault("rolls", 1), field + ".rolls");
		if (rolls < 1 || rolls > 32) throw new IllegalArgumentException(field + ".rolls must be from 1 to 32");
		return new MobScript.RewardLootStep(namespaced(value.get("loot_table"), field + ".loot_table"), rolls);
	}
	private MobScript.Step loot(Map<String, Object> value, String field) {
		return new MobScript.LootPoolStep(namespaced(value.get("lootpool_id"), field + ".lootpool_id"),
				String.valueOf(value.getOrDefault("player", "")));
	}
	private MobScript.Step skillXp(Map<String, Object> value, String field) {
		return new MobScript.SkillXpStep(String.valueOf(value.getOrDefault("skill", "dungeoning")),
				integer(value.get("amount"), field + ".amount"), String.valueOf(value.getOrDefault("player", "")));
	}
	private MobScript.Step achievement(Map<String, Object> value, String field) {
		return new MobScript.AchievementStep(namespaced(value.get("achievement_id"), field + ".achievement_id"),
				String.valueOf(value.getOrDefault("player", "")));
	}
	private static long positiveLong(Object value, String field) {
		try {
			long amount = Long.parseLong(String.valueOf(value));
			if (amount > 0) return amount;
		} catch (NumberFormatException ignored) { }
		throw new IllegalArgumentException(field + " must be a positive whole number");
	}
	private static Identifier namespaced(Object value, String field) {
		String text = string(value, field);
		if (!text.contains(":")) throw new IllegalArgumentException(field + " must be a namespaced ID: " + text);
		try { return Identifier.parse(text); }
		catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid " + field + ": " + text); }
	}

	/** mob_spawn takes exactly one of template= (MobForge template) or mob_id= (entity type or MobForge ID). */
	private MobScript.Step mobSpawn(Map<String, Object> value, String field) {
		boolean template = value.containsKey("template"), mob = value.containsKey("mob_id");
		if (template == mob) throw new IllegalArgumentException(field + " needs exactly one of template or mob_id");
		if (mob && value.containsKey("ai")) throw new IllegalArgumentException(field + ".ai only works with template");
		return template ? spawn(value, field) : spawnMobs(value, field);
	}
	private static String tag(Map<String, Object> value, String field) {
		String tag = String.valueOf(value.getOrDefault("tag", ""));
		if (!tag.isEmpty() && !tag.matches("[a-z][a-z0-9_]{0,63}"))
			throw new IllegalArgumentException(field + ".tag must use lowercase letters, digits or _");
		return tag;
	}
	private MobScript.SpawnStep spawn(Map<String, Object> value, String field) {
		String template = string(value.get("template"), field + ".template");
		Map<String, Object> location = map(value.get("location"), field + ".location");
		MobScript.Position position = new MobScript.Position(number(location.get("x"), field + ".location.x"),
				number(location.get("y"), field + ".location.y"), number(location.get("z"), field + ".location.z"));
		MobScript.Offset offset = offset(value.get("offset"), field + ".offset");
		int count = integer(value.getOrDefault("count", 1), field + ".count");
		return new MobScript.SpawnStep(template, position, offset, count, ai(value.get("ai"), field + ".ai"), tag(value, field));
	}

	private MobScript.SpawnMobsStep spawnMobs(Map<String, Object> value, String field) {
		String area = String.valueOf(value.getOrDefault("area", ""));
		if (!area.isEmpty() && !area.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException(field + ".area must be an area name");
		int perPlayer = integer(value.getOrDefault("per_player", 0), field + ".per_player");
		int maxCount = integer(value.getOrDefault("max", 0), field + ".max");
		double health = number(value.getOrDefault("health", 1), field + ".health");
		double damage = number(value.getOrDefault("damage", 1), field + ".damage");
		if (perPlayer < 0 || maxCount < 0 || health <= 0 || damage <= 0 || health > 100 || damage > 100)
			throw new IllegalArgumentException(field + ": per_player and max must be 0 or more, health and damage above 0");
		Object fireproof = value.getOrDefault("fireproof", false);
		int cap = integer(value.getOrDefault("cap", 0), field + ".cap");
		int capPerPlayer = integer(value.getOrDefault("cap_per_player", 0), field + ".cap_per_player");
		int capAfter = integer(value.getOrDefault("cap_after", 1), field + ".cap_after");
		int capMax = integer(value.getOrDefault("cap_max", 0), field + ".cap_max");
		if (cap < 0 || capPerPlayer < 0 || capAfter < 0 || capMax < 0)
			throw new IllegalArgumentException(field + ": cap values must be 0 or more");
		if (cap > 0 && tag(value, field).isEmpty()) throw new IllegalArgumentException(field + ".cap needs a tag to count");
		return new MobScript.SpawnMobsStep(namespaced(value.get("mob_id"), field + ".mob_id"),
				position(map(value.get("location"), field + ".location"), field + ".location"),
				offset(value.get("offset"), field + ".offset"),
				integer(value.getOrDefault("count", 1), field + ".count"), tag(value, field), area, perPlayer, maxCount,
				health, damage, Boolean.TRUE.equals(fireproof) || "true".equals(String.valueOf(fireproof)),
				cap, capPerPlayer, capAfter, capMax);
	}

	/** A text colour by its Minecraft name (red, gold, dark_purple, ...), or null; the first 16 formats are the colours. */
	public static net.minecraft.ChatFormatting color(String name) {
		for (net.minecraft.ChatFormatting format : net.minecraft.ChatFormatting.values())
			if (format.ordinal() < 16 && format.name().toLowerCase(java.util.Locale.ROOT).equals(name)) return format;
		return null;
	}

	private static String name(Object value, String field) {
		String name = string(value, field);
		if (!name.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException(field + " must use lowercase letters, digits or _");
		return name;
	}

	private static String optionalName(Object value, String field) {
		return value == null || String.valueOf(value).isEmpty() ? "" : name(value, field);
	}

	private MobScript.Step area(Map<String, Object> value, String field) {
		double radius = number(value.get("radius"), field + ".radius");
		double height = number(value.getOrDefault("height", 32), field + ".height");
		if (radius < 1 || radius > 512 || height < 1 || height > 512)
			throw new IllegalArgumentException(field + ": radius and height must be from 1 to 512");
		String players = optionalName(value.get("player_variable"), field + ".player_variable");
		String count = optionalName(value.get("count_variable"), field + ".count_variable");
		if (!players.isEmpty() && players.equals(count))
			throw new IllegalArgumentException("Player list and count need different variable names");
		double defend = number(value.getOrDefault("defend_radius", 0), field + ".defend_radius");
		if (defend < 0 || defend > radius) throw new IllegalArgumentException(field + ".defend_radius must be from 0 to the radius");
		return new MobScript.AreaStep(name(value.get("name"), field + ".name"),
				position(map(value.get("center"), field + ".center"), field + ".center"), radius, height,
				value.containsKey("grace") ? duration(value.get("grace"), field + ".grace") : 0, players, count, defend);
	}

	private MobScript.Step replaceBlocks(Map<String, Object> value, String field) {
		boolean named = value.containsKey("area");
		if (named == (value.containsKey("min") || value.containsKey("max")))
			throw new IllegalArgumentException(field + " needs either area= or min= and max=");
		MobScript.Position min = named ? null : position(map(value.get("min"), field + ".min"), field + ".min");
		MobScript.Position max = named ? null : position(map(value.get("max"), field + ".max"), field + ".max");
		if (!named) new ScriptCondition.Box(min, max);
		return new MobScript.ReplaceBlocksStep(namespaced(value.get("from"), field + ".from"),
				namespaced(value.get("to"), field + ".to"), named ? name(value.get("area"), field + ".area") : "", min, max);
	}

	private MobScript.Step aura(Map<String, Object> value, String field) {
		String action = String.valueOf(value.getOrDefault("action", "add"));
		if (!List.of("add", "remove").contains(action)) throw new IllegalArgumentException(field + ".action must be add or remove");
		String name = name(value.get("name"), field + ".name");
		if (action.equals("remove")) return new MobScript.AuraStep(name, null, null, 0, 0, 0, 0, action);
		double radius = number(value.get("radius"), field + ".radius");
		int stacks = integer(value.getOrDefault("max_stacks", 1), field + ".max_stacks");
		if (radius <= 0 || radius > 256 || stacks < 1 || stacks > 255)
			throw new IllegalArgumentException(field + ": radius from 0 to 256, max_stacks from 1 to 255");
		return new MobScript.AuraStep(name, namespaced(value.get("effect_id"), field + ".effect_id"),
				position(map(value.get("center"), field + ".center"), field + ".center"), radius,
				duration(value.getOrDefault("interval", "2s"), field + ".interval"), stacks,
				duration(value.getOrDefault("duration", "3s"), field + ".duration"), action);
	}

	private MobScript.Step message(Map<String, Object> value, String field) {
		String style = String.valueOf(value.getOrDefault("style", "chat"));
		if (!List.of("chat", "actionbar", "title").contains(style))
			throw new IllegalArgumentException(field + ".style must be chat, actionbar or title");
		String color = String.valueOf(value.getOrDefault("color", "white"));
		if (color(color) == null)
			throw new IllegalArgumentException(field + ".color must be a Minecraft color name such as red or gold");
		return new MobScript.MessageStep(string(value.get("text"), field + ".text"),
				String.valueOf(value.getOrDefault("player", "")), style, color);
	}

	private MobTemplate.Ai ai(Object value, String field) {
		if (value == null) return null;
		Map<String, Object> ai = map(value, field);
		String mode = string(ai.get("mode"), field + ".mode");
		MobTemplate.Position target = null;
		if (ai.get("target") != null) {
			Map<String, Object> position = map(ai.get("target"), field + ".target");
			target = new MobTemplate.Position(number(position.get("x"), field + ".target.x"), number(position.get("y"), field + ".target.y"), number(position.get("z"), field + ".target.z"));
		}
		Map<String, Object> settings = new LinkedHashMap<>(ai);
		List.of("mode", "combat", "target", "arrival_radius", "player", "range").forEach(settings::remove);
		return new MobTemplate.Ai(mode, bool(ai.get("combat"), true, field + ".combat"), target,
				number(ai.getOrDefault("arrival_radius", 2), field + ".arrival_radius"), ai.get("player") == null ? null : String.valueOf(ai.get("player")),
				number(ai.getOrDefault("range", 64), field + ".range"), settings);
	}

	public String encode(MobScript script) {
		Map<String, Object> root = new LinkedHashMap<>(); root.put("id", script.id());
		List<Object> steps = new ArrayList<>();
		for (MobScript.Step step : script.steps()) {
			if (step instanceof MobScript.WaitDurationStep wait) steps.add(Map.of("wait", Map.of("duration", formatDuration(wait.ticks()))));
			else if (step instanceof MobScript.SpawnStep spawn) {
				Map<String, Object> body = new LinkedHashMap<>(); body.put("template", spawn.template());
				body.put("location", position(spawn.location().x(), spawn.location().y(), spawn.location().z()));
				if (!spawn.offset().equals(MobScript.Offset.NONE)) body.put("offset", position(spawn.offset().x(), spawn.offset().y(), spawn.offset().z()));
				body.put("count", spawn.count());
				if (spawn.aiOverride() != null) body.put("ai", ai(spawn.aiOverride()));
				if (!spawn.tag().isEmpty()) body.put("tag", spawn.tag());
				steps.add(Map.of("mob_spawn", body));
			}
			else if (step instanceof MobScript.SpawnMobsStep spawn) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("mob_id", spawn.mobId().toString());
				body.put("location", position(spawn.location().x(), spawn.location().y(), spawn.location().z()));
				body.put("offset", position(spawn.offset().x(), spawn.offset().y(), spawn.offset().z()));
				body.put("count", spawn.count());
				if (!spawn.tag().isEmpty()) body.put("tag", spawn.tag());
				if (!spawn.area().isEmpty()) body.put("area", spawn.area());
				if (spawn.perPlayer() != 0) body.put("per_player", spawn.perPlayer());
				if (spawn.maxCount() != 0) body.put("max", spawn.maxCount());
				if (spawn.health() != 1) body.put("health", spawn.health());
				if (spawn.damage() != 1) body.put("damage", spawn.damage());
				if (spawn.fireproof()) body.put("fireproof", true);
				if (spawn.cap() > 0) {
					body.put("cap", spawn.cap());
					if (spawn.capPerPlayer() > 0) body.put("cap_per_player", spawn.capPerPlayer());
					if (spawn.capAfter() != 1) body.put("cap_after", spawn.capAfter());
					if (spawn.capMax() > 0) body.put("cap_max", spawn.capMax());
				}
				steps.add(Map.of("mob_spawn", body));
			}
			else if (step instanceof MobScript.AreaStep area) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("name", area.name());
				body.put("center", position(area.center().x(), area.center().y(), area.center().z()));
				body.put("radius", area.radius());
				body.put("height", area.height());
				if (area.graceTicks() > 0) body.put("grace", formatDuration(area.graceTicks()));
				if (!area.playerVariable().isEmpty()) body.put("player_variable", area.playerVariable());
				if (!area.countVariable().isEmpty()) body.put("count_variable", area.countVariable());
				if (area.defendRadius() > 0) body.put("defend_radius", area.defendRadius());
				steps.add(Map.of("area", body));
			}
			else if (step instanceof MobScript.ReplaceBlocksStep replace) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("from", replace.from().toString());
				body.put("to", replace.to().toString());
				if (!replace.area().isEmpty()) body.put("area", replace.area());
				else {
					body.put("min", position(replace.min().x(), replace.min().y(), replace.min().z()));
					body.put("max", position(replace.max().x(), replace.max().y(), replace.max().z()));
				}
				steps.add(Map.of("replace_blocks", body));
			}
			else if (step instanceof MobScript.AuraStep aura) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("name", aura.name());
				if (aura.action().equals("remove")) body.put("action", "remove");
				else {
					body.put("effect_id", aura.effectId().toString());
					body.put("center", position(aura.center().x(), aura.center().y(), aura.center().z()));
					body.put("radius", aura.radius());
					body.put("interval", formatDuration(aura.intervalTicks()));
					body.put("max_stacks", aura.maxStacks());
					body.put("duration", formatDuration(aura.durationTicks()));
				}
				steps.add(Map.of("aura", body));
			}
			else if (step instanceof MobScript.DespawnStep despawn) steps.add(Map.of("mob_despawn", Map.of("mob", despawn.tag())));
			else if (step instanceof MobScript.MessageStep message) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("text", message.text());
				if (!message.playerId().isEmpty()) body.put("player", message.playerId());
				body.put("style", message.style());
				if (!message.color().equals("white")) body.put("color", message.color());
				steps.add(Map.of("message", body));
			}
			else if (step instanceof MobScript.SpawnEntityStep spawn) steps.add(Map.of("spawn_entity", Map.of("id", spawn.id().toString(), "at", spawn.at(), "save_as", spawn.saveAs())));
			else if (step instanceof MobScript.WaitUntilStep wait) steps.add(Map.of("wait", Map.of("condition", wait.condition(), "timeout", formatDuration(wait.timeoutTicks()))));
			else if (step instanceof MobScript.MobCastStep cast) steps.add(Map.of("mob_cast", Map.of("mob", cast.mob(), "ability", cast.ability().toString())));
			else if (step instanceof MobScript.SetPhaseStep phase) steps.add(Map.of("mob_phase", Map.of("mob", phase.mob(), "phase", phase.phase())));
			else if (step instanceof MobScript.TriggerStep trigger) steps.add(Map.of("trigger", Map.of("event", trigger.event().toString())));
			else if (step instanceof MobScript.RewardCurrencyStep reward) steps.add(Map.of("reward_currency", Map.of("currency", reward.currency().toString(), "amount", reward.amount())));
			else if (step instanceof MobScript.RewardItemStep reward) steps.add(Map.of("reward_item", Map.of("item", reward.item().toString(), "count", reward.count())));
			else if (step instanceof MobScript.RewardLootStep reward) steps.add(Map.of("reward_loot", Map.of("loot_table", reward.lootTable().toString(), "rolls", reward.rolls())));
			else if (step instanceof MobScript.LootPoolStep loot) steps.add(Map.of("loot", Map.of("lootpool_id", loot.lootPoolId().toString(), "player", loot.playerId())));
			else if (step instanceof MobScript.SkillXpStep xp) steps.add(Map.of("skill_xp", Map.of("skill", xp.skill(), "amount", xp.amount(), "player", xp.playerId())));
			else if (step instanceof MobScript.AchievementStep achievement) steps.add(Map.of("achievement", Map.of("achievement_id", achievement.achievementId().toString(), "player", achievement.playerId())));
			else if (step instanceof MobScript.VariableStep variable) steps.add(Map.of("variable", Map.of("name", variable.name(), "operation", variable.operation(), "value", variable.value())));
			else if (step instanceof MobScript.EventStep event) steps.add(Map.of("event", Map.of("condition", event.condition(), "variable", event.variable(), "operation", event.operation(), "value", event.value())));
			else if (step instanceof MobScript.EventBoxStep box) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("name", box.name()); body.put("min", position(box.min().x(), box.min().y(), box.min().z()));
				body.put("max", position(box.max().x(), box.max().y(), box.max().z()));
				body.put("mode", box.mode());
				if (box.mode().equals("mobs")) body.put("mob_id", box.mobId());
				if (!box.playerVariable().isEmpty()) body.put("player_variable", box.playerVariable());
				if (!box.countVariable().isEmpty()) body.put("count_variable", box.countVariable());
				steps.add(Map.of("eventbox", body));
			}
			else if (step instanceof MobScript.RedstoneStep sensor) steps.add(Map.of("redstone_sensor", Map.of("name", sensor.name(), "position", position(sensor.position().x(), sensor.position().y(), sensor.position().z()))));
			else if (step instanceof MobScript.IfStep test) steps.add(Map.of("if", test.condition()));
			else if (step instanceof MobScript.ElseStep) steps.add(Map.of("else", true));
			else if (step instanceof MobScript.EndIfStep) steps.add(Map.of("end_if", true));
			else if (step instanceof MobScript.LoopStep loop) steps.add(Map.of("loop", Map.of("condition", loop.condition(), "max_iterations", loop.maxIterations())));
			else if (step instanceof MobScript.EndLoopStep) steps.add(Map.of("end_loop", true));
			else if (step instanceof MobScript.PortalLocationStep portal) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("portal", portal.portalId().toString()); body.put("action", portal.action());
				if (!portal.deletesAll()) body.put("location", portal.locationId());
				if (portal.action().equals("add")) body.put("position", position(portal.position().x(), portal.position().y(), portal.position().z()));
				if (portal.target() != null) body.put("target", position(portal.target().x(), portal.target().y(), portal.target().z()));
				steps.add(Map.of("portal", body));
			}
			else if (step instanceof MobScript.BuildingStep building) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("build_id", building.buildId().toString());
				body.put("action", building.action());
				body.put("location", position(building.location().x(), building.location().y(), building.location().z()));
				if (!building.mode().isEmpty()) body.put("mode", building.mode());
				steps.add(Map.of("building", body));
			}
			else if (step instanceof MobScript.ParticleStep particles) steps.add(Map.of("particles",
					Map.of("particle_id", particles.particleId().toString(),
							"location", position(particles.location().x(), particles.location().y(), particles.location().z()),
							"offset", position(particles.offset().x(), particles.offset().y(), particles.offset().z()),
							"count", particles.count(), "speed", particles.speed())));
			else if (step instanceof MobScript.ExplosionStep explosion) steps.add(Map.of("explosion",
					Map.of("location", position(explosion.location().x(), explosion.location().y(), explosion.location().z()),
							"power", explosion.power())));
			else if (step instanceof MobScript.DistancesStep distances) {
				Map<String, Object> values = new LinkedHashMap<>();
				if (distances.view() >= 0) values.put("view", distances.view());
				if (distances.simulation() >= 0) values.put("simulation", distances.simulation());
				steps.add(Map.of("distances", values));
			}
			else if (step instanceof MobScript.SoundStep sound) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("sound_id", sound.soundId().toString());
				if (sound.location() != null)
					body.put("location", position(sound.location().x(), sound.location().y(), sound.location().z()));
				steps.add(Map.of("sound", body));
			}
			else if (step instanceof MobScript.StopSoundStep stop) steps.add(Map.of("sound_stop", stop.soundId() == null
					? Map.of() : Map.of("sound_id", stop.soundId().toString())));
			else if (step instanceof MobScript.EffectStep effect) steps.add(Map.of("effect",
					Map.of("effect_id", effect.effectId().toString(), "player_id", effect.playerId(),
							"action", effect.action(), "amplifier", effect.amplifier())));
			else if (step instanceof MobScript.FinishInstanceStep finish) steps.add(Map.of("finish_instance",
					finish.spawn() == null ? true : Map.of("spawn", position(finish.spawn().x(), finish.spawn().y(), finish.spawn().z()))));
			else if (step instanceof MobScript.EncounterStartStep) steps.add(Map.of("encounter_start", true));
			else if (step instanceof MobScript.WipeStep) steps.add(Map.of("wipe", true));
			else if (step instanceof MobScript.EncounterEndStep end) steps.add(Map.of("encounter_end", end.spawn() == null ? true
					: Map.of("spawn", position(end.spawn().x(), end.spawn().y(), end.spawn().z()))));
		}
		root.put("steps", steps); return dumper.dumpToString(root);
	}

	private static Map<String, Object> position(double x, double y, double z) { Map<String, Object> map = new LinkedHashMap<>(); map.put("x", x); map.put("y", y); map.put("z", z); return map; }
	private static Map<String, Object> ai(MobTemplate.Ai ai) {
		Map<String, Object> map = new LinkedHashMap<>(); map.put("mode", ai.mode()); map.put("combat", ai.combat());
		if (ai.target() != null) map.put("target", position(ai.target().x(), ai.target().y(), ai.target().z()));
		map.put("arrival_radius", ai.arrivalRadius());
		if (ai.player() != null) map.put("player", ai.player());
		map.put("range", ai.range());
		map.putAll(ai.settings()); return map;
	}

	private MobScript.Offset offset(Object value, String field) {
		if (value == null) return MobScript.Offset.NONE;
		if (value instanceof Number || value instanceof String) {
			double radius = number(value, field); return new MobScript.Offset(radius, 0, radius);
		}
		Map<String, Object> map = map(value, field);
		return new MobScript.Offset(number(map.getOrDefault("x", 0), field + ".x"), number(map.getOrDefault("y", 0), field + ".y"), number(map.getOrDefault("z", 0), field + ".z"));
	}

	public static long parseDuration(Object value) { return duration(value, "duration"); }
	public static String formatDuration(long ticks) { return ticks % 20 == 0 ? (ticks / 20) + "s" : ticks + "t"; }
	private static long duration(Object value, String field) {
		if (value == null) throw new IllegalArgumentException(field + " is required");
		if (value instanceof Number number) return Math.max(0, Math.round(number.doubleValue() * 20.0));
		Matcher matcher = DURATION.matcher(String.valueOf(value).trim());
		if (!matcher.matches()) throw new IllegalArgumentException(field + " must look like 40t, 5s, 500ms, or 2m");
		double amount = Double.parseDouble(matcher.group(1)); String unit = matcher.group(2);
		if (unit == null || unit.equalsIgnoreCase("s") || unit.toLowerCase().startsWith("sec")) return Math.round(amount * 20.0);
		if (unit.equalsIgnoreCase("ms")) return Math.round(amount / 50.0);
		if (unit.toLowerCase().startsWith("m")) return Math.round(amount * 1200.0);
		return Math.round(amount);
	}

	private static Map<String, Object> map(Object value, String field) {
		if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException(field + " must be a map");
		Map<String, Object> result = new LinkedHashMap<>(); raw.forEach((key, item) -> result.put(String.valueOf(key), item)); return result;
	}
	private static String string(Object value, String field) { if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(field + " is required"); return String.valueOf(value); }
	private static double number(Object value, String field) { if (value instanceof Number number) return number.doubleValue(); try { return Double.parseDouble(String.valueOf(value)); } catch (Exception exception) { throw new IllegalArgumentException(field + " must be a number"); } }
	private static int integer(Object value, String field) { double number = number(value, field); if (number != Math.rint(number)) throw new IllegalArgumentException(field + " must be an integer"); return (int) number; }
	private static boolean bool(Object value, boolean fallback, String field) { if (value == null) return fallback; if (value instanceof Boolean bool) return bool; if ("true".equalsIgnoreCase(String.valueOf(value))) return true; if ("false".equalsIgnoreCase(String.valueOf(value))) return false; throw new IllegalArgumentException(field + " must be true or false"); }
}
