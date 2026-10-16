package ekuzo.crazyworldprogression.mobforge.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one canonical representation used by YAML, commands, spawning, and the editor. */
public record MobTemplate(String id, String entity, String name, Map<String, Double> attributes,
		Map<String, EquipmentEntry> equipment, Properties properties, Loot loot, List<String> tags, Ai ai) {
	public MobTemplate {
		attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
		equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
		properties = properties == null ? Properties.DEFAULT : properties;
		loot = loot == null ? Loot.DEFAULT : loot;
		tags = tags == null ? List.of() : List.copyOf(tags);
		ai = ai == null ? Ai.VANILLA : ai;
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

	public record Loot(boolean dropVanillaLoot, int xp) {
		public static final Loot DEFAULT = new Loot(true, -1);
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
		public final Map<String, EquipmentEntry> equipment = new LinkedHashMap<>();
		public Properties properties = Properties.DEFAULT;
		public Loot loot = Loot.DEFAULT;
		public List<String> tags = List.of();
		public Ai ai = Ai.VANILLA;
		public MobTemplate build() { return new MobTemplate(id, entity, name, attributes, equipment, properties, loot, tags, ai); }
	}
}
