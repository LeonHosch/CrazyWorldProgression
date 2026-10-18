package ekuzo.crazyworldprogression.mobforge.template;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit schema mapping keeps generated YAML readable and free of Java type metadata. */
public final class MobTemplateYaml {
	private final Load loader = new Load(LoadSettings.builder().setLabel("MobForge template").setCodePointLimit(1_000_000).build());
	private final Dump dumper = new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).setIndent(2).build());

	public MobTemplate decode(String yaml) {
		Object root = loader.loadFromString(yaml);
		Map<String, Object> map = map(root, "root");
		MobTemplate.Builder out = MobTemplate.builder();
		out.id = string(map.get("id"));
		out.entity = string(map.get("entity"));
		out.name = nullableString(map.get("name"));

		map(map.get("attributes"), "attributes", true).forEach((key, value) -> out.attributes.put(key, number(value, "attributes." + key).doubleValue()));
		map(map.get("multipliers"), "multipliers", true).forEach((key, value) -> out.multipliers.put(key, number(value, "multipliers." + key).doubleValue()));
		map(map.get("equipment"), "equipment", true).forEach((slot, value) -> {
			Map<String, Object> item = map(value, "equipment." + slot);
			out.equipment.put(slot, new MobTemplate.EquipmentEntry(string(item.get("id")), integer(item.getOrDefault("count", 1), "count"),
					map(item.get("components"), "components", true)));
		});

		Map<String, Object> properties = map(map.get("properties"), "properties", true);
		out.properties = new MobTemplate.Properties(bool(properties, "persistent", false), bool(properties, "burns_in_daylight", true),
				bool(properties, "can_pick_up_loot", false), bool(properties, "silent", false), bool(properties, "glowing", false),
				bool(properties, "invulnerable", false), bool(properties, "no_gravity", false), string(properties.getOrDefault("despawn_behavior", "vanilla")));

		Map<String, Object> loot = map(map.get("loot"), "loot", true);
		out.loot = new MobTemplate.Loot(bool(loot, "drop_vanilla_loot", true), integer(loot.getOrDefault("xp", -1), "loot.xp"),
				nullableString(loot.get("loot_pool")));
		Object rawTags = map.get("tags");
		if (rawTags instanceof List<?> list) out.tags = list.stream().map(MobTemplateYaml::string).toList();
		else if (rawTags != null) throw new IllegalArgumentException("tags must be a list");

		Map<String, Object> ai = map(map.get("ai"), "ai", true);
		String mode = string(ai.getOrDefault("mode", "vanilla"));
		MobTemplate.Position target = null;
		if (ai.get("target") != null) {
			Map<String, Object> pos = map(ai.get("target"), "ai.target");
			target = new MobTemplate.Position(number(pos.get("x"), "ai.target.x").doubleValue(), number(pos.get("y"), "ai.target.y").doubleValue(), number(pos.get("z"), "ai.target.z").doubleValue());
		}
		Map<String, Object> extra = new LinkedHashMap<>(ai);
		List.of("mode", "combat", "target", "arrival_radius", "player", "range").forEach(extra::remove);
		out.ai = new MobTemplate.Ai(mode, bool(ai, "combat", true), target, decimal(ai.getOrDefault("arrival_radius", 2.0), "ai.arrival_radius"),
				nullableString(ai.get("player")), decimal(ai.getOrDefault("range", 64.0), "ai.range"), extra);
		return out.build();
	}

	public String encode(MobTemplate template) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("id", template.id());
		root.put("entity", template.entity());
		if (template.name() != null && !template.name().isBlank()) root.put("name", template.name());
		if (!template.attributes().isEmpty()) root.put("attributes", new LinkedHashMap<>(template.attributes()));
		if (!template.multipliers().isEmpty()) root.put("multipliers", new LinkedHashMap<>(template.multipliers()));
		if (!template.equipment().isEmpty()) {
			Map<String, Object> equipment = new LinkedHashMap<>();
			template.equipment().forEach((slot, entry) -> {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", entry.id()); item.put("count", entry.count());
				if (!entry.components().isEmpty()) item.put("components", entry.components());
				equipment.put(slot, item);
			});
			root.put("equipment", equipment);
		}
		MobTemplate.Properties p = template.properties();
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("persistent", p.persistent()); properties.put("burns_in_daylight", p.burnsInDaylight());
		properties.put("can_pick_up_loot", p.canPickUpLoot()); properties.put("silent", p.silent()); properties.put("glowing", p.glowing());
		properties.put("invulnerable", p.invulnerable()); properties.put("no_gravity", p.noGravity()); properties.put("despawn_behavior", p.despawnBehavior());
		root.put("properties", properties);
		Map<String, Object> loot = new LinkedHashMap<>();
		loot.put("drop_vanilla_loot", template.loot().dropVanillaLoot()); loot.put("xp", template.loot().xp());
		if (template.loot().lootPool() != null) loot.put("loot_pool", template.loot().lootPool());
		root.put("loot", loot);
		if (!template.tags().isEmpty()) root.put("tags", new ArrayList<>(template.tags()));
		MobTemplate.Ai ai = template.ai();
		Map<String, Object> aiMap = new LinkedHashMap<>();
		aiMap.put("mode", ai.mode()); aiMap.put("combat", ai.combat());
		if (ai.target() != null) aiMap.put("target", Map.of("x", ai.target().x(), "y", ai.target().y(), "z", ai.target().z()));
		if ("target_position".equals(ai.mode())) aiMap.put("arrival_radius", ai.arrivalRadius());
		if (ai.player() != null) aiMap.put("player", ai.player());
		if ("nearest_player".equals(ai.mode())) aiMap.put("range", ai.range());
		aiMap.putAll(ai.settings()); root.put("ai", aiMap);
		return dumper.dumpToString(root);
	}

	@SuppressWarnings("unchecked") private static Map<String, Object> map(Object value, String field) {
		if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException(field + " must be a map");
		Map<String, Object> result = new LinkedHashMap<>(); raw.forEach((k, v) -> result.put(String.valueOf(k), v)); return result;
	}
	private static Map<String, Object> map(Object value, String field, boolean optional) { return value == null && optional ? Map.of() : map(value, field); }
	private static String string(Object value) { if (value == null) throw new IllegalArgumentException("required value is missing"); return String.valueOf(value); }
	private static String nullableString(Object value) { return value == null ? null : String.valueOf(value); }
	private static Number number(Object value, String field) { if (value instanceof Number n) return n; try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { throw new IllegalArgumentException(field + " must be a number"); } }
	private static double decimal(Object value, String field) { return number(value, field).doubleValue(); }
	private static int integer(Object value, String field) { double number = decimal(value, field); if (number != Math.rint(number)) throw new IllegalArgumentException(field + " must be an integer"); return (int) number; }
	private static boolean bool(Map<String, Object> map, String key, boolean fallback) { Object value = map.get(key); if (value == null) return fallback; if (value instanceof Boolean b) return b; if ("true".equalsIgnoreCase(String.valueOf(value))) return true; if ("false".equalsIgnoreCase(String.valueOf(value))) return false; throw new IllegalArgumentException(key + " must be true or false"); }
}
