package ekuzo.crazyworldprogression.mobforge.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one canonical representation used by YAML, commands, spawning, and the editor. */
public record MobTemplate(String id, String entity, String name, Map<String, Double> attributes,
		Map<String, EquipmentEntry> equipment, Properties properties, Loot loot, List<String> tags, Ai ai,
		Map<String, Double> multipliers) {
	/** Attributes that are set as a multiple of the entity type's own default value (1 = unchanged). */
	public static final List<String> MULTIPLIED = List.of("scale", "movement_speed", "follow_range", "max_health",
			"attack_damage", "attack_speed");

	public MobTemplate {
		attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
		multipliers = multipliers == null ? Map.of() : Map.copyOf(multipliers);
		equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
		properties = properties == null ? Properties.DEFAULT : properties;
		loot = loot == null ? Loot.DEFAULT : loot;
		tags = tags == null ? List.of() : List.copyOf(tags);
		ai = ai == null ? Ai.VANILLA : ai;
	}

	/** Without multipliers, for code that only sets absolute attribute values. */
	public MobTemplate(String id, String entity, String name, Map<String, Double> attributes,
			Map<String, EquipmentEntry> equipment, Properties properties, Loot loot, List<String> tags, Ai ai) {
		this(id, entity, name, attributes, equipment, properties, loot, tags, ai, Map.of());
	}

	public record EquipmentEntry(String id, int count, Map<String, Object> components) {
		public EquipmentEntry {
			count = count == 0 ? 1 : count;
			components = components == null ? Map.of() : Map.copyOf(components);
		}
	}

	public record Properties(boolean persistent, boolean burnsInDaylight, boolean canPickUpLoot,
			boolean silent, boolean glowing, boolean invulnerable, boolean noGravity, String despawnBehavior) {
		public static final Properties DEFAULT = new Properties(false, true, false, false, false, false, false, "vanilla");
	}

	/** lootPool: a CWP loot pool rolled for the player who lands the killing blow, or null. */
	public record Loot(boolean dropVanillaLoot, int xp, String lootPool) {
		public static final Loot DEFAULT = new Loot(true, -1, null);
		public Loot {
			lootPool = lootPool == null || lootPool.isBlank() ? null : lootPool.trim();
		}
		public Loot(boolean dropVanillaLoot, int xp) { this(dropVanillaLoot, xp, null); }
	}

	public record Position(double x, double y, double z) {}

	public record Ai(String mode, boolean combat, Position target, double arrivalRadius,
			String player, double range, Map<String, Object> settings) {
		public static final Ai VANILLA = new Ai("vanilla", true, null, 2.0, null, 64.0, Map.of());
		public Ai {
			mode = mode == null || mode.isBlank() ? "vanilla" : mode;
			arrivalRadius = arrivalRadius == 0 ? 2.0 : arrivalRadius;
			range = range == 0 ? 64.0 : range;
			settings = settings == null ? Map.of() : Map.copyOf(settings);
		}
	}

	public static Builder builder() { return new Builder(); }
	public static final class Builder {
		public String id;
		public String entity = "minecraft:zombie";
		public String name;
		public final Map<String, Double> attributes = new LinkedHashMap<>();
		public final Map<String, Double> multipliers = new LinkedHashMap<>();
		public final Map<String, EquipmentEntry> equipment = new LinkedHashMap<>();
		public Properties properties = Properties.DEFAULT;
		public Loot loot = Loot.DEFAULT;
		public List<String> tags = List.of();
		public Ai ai = Ai.VANILLA;
		public MobTemplate build() { return new MobTemplate(id, entity, name, attributes, equipment, properties, loot, tags, ai, multipliers); }
	}
}
