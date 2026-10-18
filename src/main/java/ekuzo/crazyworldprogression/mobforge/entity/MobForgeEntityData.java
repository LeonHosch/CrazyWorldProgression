package ekuzo.crazyworldprogression.mobforge.entity;

import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/** Persistent metadata is encoded as vanilla scoreboard tags and therefore survives saves and needs no custom entity type. */
public final class MobForgeEntityData {
	public static final String MARKER = "mobforge.managed";
	private static final String TEMPLATE = "mobforge.template.";
	private static final String AI = "mobforge.ai.";
	private static final String TARGET = "mobforge.target.";
	private static final String PLAYER = "mobforge.player.";
	private static final String RANGE = "mobforge.range.";
	private static final String ARRIVAL = "mobforge.arrival.";
	private static final String COMBAT = "mobforge.combat.";
	private static final String NO_LOOT = "mobforge.no_vanilla_loot";
	private static final String XP = "mobforge.xp.";
	private static final String LOOT_POOL = "mobforge.loot_pool.";
	private static final String BURNS_IN_DAYLIGHT = "mobforge.burns_in_daylight.";
	private static final String SCRIPT = "mobforge.script.";
	private static final String SCRIPT_RUN = "mobforge.script_run.";
	private static final String DEFINITION = "mobforge.definition.";
	private static final String PHASE = "mobforge.phase.";
	private static final String ENCOUNTER = "mobforge.encounter.";
	private static final String POCKET = "mobforge.pocket.";
	private static final String EXECUTION = "mobforge.execution.";
	private static final String COOLDOWN = "mobforge.cooldown.";

	private MobForgeEntityData() {}

	public static void apply(Entity entity, MobTemplate template) {
		entity.addTag(MARKER); replace(entity, TEMPLATE, template.id());
		for (String tag : template.tags()) entity.addTag(tag);
		setAi(entity, template.ai());
		if (!template.loot().dropVanillaLoot()) entity.addTag(NO_LOOT);
		if (template.loot().xp() >= 0) entity.addTag(XP + template.loot().xp());
		if (template.loot().lootPool() != null) entity.addTag(LOOT_POOL + template.loot().lootPool());
		entity.addTag(BURNS_IN_DAYLIGHT + template.properties().burnsInDaylight());
	}

	public static void setAi(Entity entity, MobTemplate.Ai ai) {
		removePrefix(entity, AI); removePrefix(entity, TARGET); removePrefix(entity, PLAYER); removePrefix(entity, RANGE);
		removePrefix(entity, ARRIVAL); removePrefix(entity, COMBAT);
		entity.addTag(AI + ai.mode()); entity.addTag(COMBAT + ai.combat());
		if (ai.target() != null) entity.addTag(TARGET + ai.target().x() + "," + ai.target().y() + "," + ai.target().z());
		if (ai.player() != null) entity.addTag(PLAYER + ai.player());
		entity.addTag(RANGE + ai.range()); entity.addTag(ARRIVAL + ai.arrivalRadius());
	}

	public static Optional<String> value(Entity entity, String prefix) {
		return entity.entityTags().stream().filter(tag -> tag.startsWith(prefix)).map(tag -> tag.substring(prefix.length())).findFirst();
	}
	public static Optional<String> template(Entity entity) { return value(entity, TEMPLATE); }
	/** The loot pool rolled for the player who kills this mob, set from its template when spawned. */
	public static Optional<String> lootPool(Entity entity) { return value(entity, LOOT_POOL); }
	public static Optional<String> aiMode(Entity entity) { return value(entity, AI); }
	public static Optional<String> player(Entity entity) { return value(entity, PLAYER); }
	public static double range(Entity entity) { return value(entity, RANGE).map(MobForgeEntityData::safeDouble).orElse(64.0); }
	public static double arrival(Entity entity) { return value(entity, ARRIVAL).map(MobForgeEntityData::safeDouble).orElse(2.0); }
	public static boolean combat(Entity entity) { return value(entity, COMBAT).map(Boolean::parseBoolean).orElse(true); }
	public static Optional<MobTemplate.Position> target(Entity entity) {
		return value(entity, TARGET).flatMap(value -> {
			String[] bits = value.split(",", -1); if (bits.length != 3) return Optional.empty();
			try { return Optional.of(new MobTemplate.Position(Double.parseDouble(bits[0]), Double.parseDouble(bits[1]), Double.parseDouble(bits[2]))); }
			catch (NumberFormatException ignored) { return Optional.empty(); }
		});
	}
	public static boolean suppressVanillaLoot(Entity entity) { return entity.entityTags().contains(NO_LOOT); }
	public static Optional<Integer> xp(Entity entity) { return value(entity, XP).flatMap(value -> { try { return Optional.of(Integer.parseInt(value)); } catch (NumberFormatException ignored) { return Optional.empty(); } }); }
	public static Optional<Boolean> burnsInDaylight(Entity entity) { return value(entity, BURNS_IN_DAYLIGHT).map(Boolean::parseBoolean); }
	public static void setScript(Entity entity, String scriptId, String runId) {
		replace(entity, SCRIPT, scriptId);
		replace(entity, SCRIPT_RUN, runId);
	}
	public static Optional<String> script(Entity entity) { return value(entity, SCRIPT); }
	public static Optional<String> scriptRun(Entity entity) { return value(entity, SCRIPT_RUN); }
	public static void setDefinition(Entity entity, Identifier id) { entity.addTag(MARKER); replace(entity, DEFINITION, id.toString()); }
	public static Optional<Identifier> definition(Entity entity) { return value(entity, DEFINITION).map(Identifier::tryParse); }
	public static Optional<String> phase(Entity entity) { return value(entity, PHASE); }
	public static void setPhase(Entity entity, String phase) {
		if (phase == null || !phase.matches("[a-z0-9_./-]{1,64}")) throw new IllegalArgumentException("Invalid MobForge phase: " + phase);
		replace(entity, PHASE, phase);
	}
	public static void setEncounter(Entity entity, java.util.UUID id) { if (id != null) replace(entity, ENCOUNTER, id.toString()); }
	public static void setPocket(Entity entity, java.util.UUID id) { if (id != null) replace(entity, POCKET, id.toString()); }
	public static void setExecution(Entity entity, java.util.UUID id) { if (id != null) replace(entity, EXECUTION, id.toString()); }
	public static Optional<java.util.UUID> encounter(Entity entity) { return uuid(entity, ENCOUNTER); }
	public static Optional<java.util.UUID> pocket(Entity entity) { return uuid(entity, POCKET); }
	public static Optional<java.util.UUID> execution(Entity entity) { return uuid(entity, EXECUTION); }
	private static Optional<java.util.UUID> uuid(Entity entity, String prefix) {
		try { return value(entity, prefix).map(java.util.UUID::fromString); }
		catch (IllegalArgumentException exception) { return Optional.empty(); }
	}
	public static long cooldown(Entity entity, Identifier ability) {
		return value(entity, COOLDOWN + ability + ".").map(value -> {
			try { return Long.parseLong(value); } catch (NumberFormatException exception) { return 0L; }
		}).orElse(0L);
	}
	public static void setCooldown(Entity entity, Identifier ability, long expiresAtTick) {
		replace(entity, COOLDOWN + ability + ".", Long.toString(expiresAtTick));
	}
	private static void replace(Entity entity, String prefix, String value) { removePrefix(entity, prefix); entity.addTag(prefix + value); }
	private static void removePrefix(Entity entity, String prefix) { entity.entityTags().stream().filter(tag -> tag.startsWith(prefix)).toList().forEach(entity::removeTag); }
	private static double safeDouble(String value) { try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return 0; } }
}
