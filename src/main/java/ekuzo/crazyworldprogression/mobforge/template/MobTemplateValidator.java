package ekuzo.crazyworldprogression.mobforge.template;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class MobTemplateValidator {
	/** Values above this move far enough per tick to make vanilla node navigation overshoot and visibly rubber-band. */
	public static final double MAX_STABLE_MOVEMENT_SPEED = 0.7;
	public static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]+");
	public static final Set<String> AI_MODES = Set.of("vanilla", "target_position", "target_player", "nearest_player");
	public static final Set<String> ATTRIBUTES = Set.of("max_health", "movement_speed", "attack_damage", "attack_speed", "armor",
			"armor_toughness", "knockback_resistance", "attack_knockback", "follow_range", "scale");
	public static final Set<String> EQUIPMENT_SLOTS = Set.of("main_hand", "off_hand", "head", "chest", "legs", "feet");
	private MobTemplateValidator() {}

	public static void validate(MobTemplate template) throws TemplateValidationException {
		List<String> errors = new ArrayList<>();
		if (template.id() == null || !SAFE_ID.matcher(template.id()).matches()) errors.add("id must match [a-z0-9_-]+");
		Identifier entityId = parseId(template.entity(), "entity", errors);
		if (entityId != null && !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) errors.add("unknown entity ID: " + entityId);
		else if (entityId != null && "minecraft:player".equals(entityId.toString())) errors.add("player is not spawnable");
		template.attributes().forEach((key, value) -> {
			if (!ATTRIBUTES.contains(key)) errors.add("unknown attribute: " + key);
			if (value == null || !Double.isFinite(value)) errors.add("attribute " + key + " must be finite");
			else if (value < 0) errors.add("attribute " + key + " cannot be negative");
		});
		Double health = template.attributes().get("max_health");
		if (health != null && health <= 0) errors.add("max_health must be greater than zero");
		Double movementSpeed = template.attributes().get("movement_speed");
		if (movementSpeed != null && movementSpeed > MAX_STABLE_MOVEMENT_SPEED)
			errors.add("movement_speed cannot exceed " + MAX_STABLE_MOVEMENT_SPEED
					+ "; vanilla mobs normally use 0.20-0.35 and higher values cause unstable pathfinding");
		template.equipment().forEach((slot, item) -> {
			if (!EQUIPMENT_SLOTS.contains(slot)) errors.add("unknown equipment slot: " + slot);
			Identifier itemId = parseId(item.id(), "equipment." + slot + ".id", errors);
			if (itemId != null && !BuiltInRegistries.ITEM.containsKey(itemId)) errors.add("unknown item ID: " + itemId);
			if (item.count() < 1 || item.count() > 99) errors.add("equipment." + slot + ".count must be between 1 and 99");
		});
		MobTemplate.Ai ai = template.ai();
		if (!AI_MODES.contains(ai.mode())) errors.add("unknown AI mode: " + ai.mode());
		if ("target_position".equals(ai.mode()) && ai.target() == null) errors.add("ai.target is required for AI mode target_position");
		if ("target_player".equals(ai.mode()) && (ai.player() == null || ai.player().isBlank())) errors.add("ai.player is required for AI mode target_player");
		if (ai.arrivalRadius() < 0 || !Double.isFinite(ai.arrivalRadius())) errors.add("ai.arrival_radius must be finite and non-negative");
		if (ai.range() <= 0 || !Double.isFinite(ai.range())) errors.add("ai.range must be finite and greater than zero");
		if (template.loot().xp() < -1) errors.add("loot.xp cannot be less than -1");
		if (!Set.of("vanilla", "never").contains(template.properties().despawnBehavior())) errors.add("properties.despawn_behavior must be vanilla or never");
		if (template.tags().size() > 64) errors.add("tags cannot contain more than 64 entries");
		for (String tag : template.tags()) {
			if (tag == null || tag.isBlank() || tag.length() > 128) errors.add("tags must be non-blank and at most 128 characters");
			else if (tag.startsWith("mobforge.")) errors.add("custom tags cannot use the reserved mobforge. prefix");
		}
		if (!errors.isEmpty()) throw new TemplateValidationException(errors);
	}

	private static Identifier parseId(String value, String field, List<String> errors) {
		if (value == null || value.isBlank()) { errors.add(field + " is required"); return null; }
		try { return Identifier.parse(value); }
		catch (RuntimeException exception) { errors.add("invalid " + field + ": " + value); return null; }
	}
}
