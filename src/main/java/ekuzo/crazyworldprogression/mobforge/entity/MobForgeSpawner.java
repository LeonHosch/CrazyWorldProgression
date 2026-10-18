package ekuzo.crazyworldprogression.mobforge.entity;

import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

public final class MobForgeSpawner {
	private static final Map<String, Holder<Attribute>> ATTRIBUTES = Map.ofEntries(
			Map.entry("max_health", Attributes.MAX_HEALTH), Map.entry("movement_speed", Attributes.MOVEMENT_SPEED),
			Map.entry("attack_damage", Attributes.ATTACK_DAMAGE), Map.entry("attack_speed", Attributes.ATTACK_SPEED),
			Map.entry("armor", Attributes.ARMOR), Map.entry("armor_toughness", Attributes.ARMOR_TOUGHNESS),
			Map.entry("knockback_resistance", Attributes.KNOCKBACK_RESISTANCE), Map.entry("attack_knockback", Attributes.ATTACK_KNOCKBACK),
			Map.entry("follow_range", Attributes.FOLLOW_RANGE), Map.entry("scale", Attributes.SCALE));
	private static final Map<String, EquipmentSlot> SLOTS = Map.of("main_hand", EquipmentSlot.MAINHAND, "off_hand", EquipmentSlot.OFFHAND,
			"head", EquipmentSlot.HEAD, "chest", EquipmentSlot.CHEST, "legs", EquipmentSlot.LEGS, "feet", EquipmentSlot.FEET);

	private MobForgeSpawner() {}

	public static Entity spawn(ServerLevel level, MobTemplate template, double x, double y, double z) {
		var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(template.entity()));
		Entity entity = type.create(level, EntitySpawnReason.COMMAND);
		if (entity == null) throw new IllegalArgumentException("entity type cannot be created: " + template.entity());
		entity.setPos(x, y, z);
		if (template.name() != null && !template.name().isBlank()) entity.setCustomName(Component.literal(template.name()));
		var p = template.properties();
		entity.setSilent(p.silent()); entity.setGlowingTag(p.glowing()); entity.setInvulnerable(p.invulnerable()); entity.setNoGravity(p.noGravity());
		if (entity instanceof Mob mob) { if (p.persistent() || "never".equals(p.despawnBehavior())) mob.setPersistenceRequired(); mob.setCanPickUpLoot(p.canPickUpLoot()); }
		if (entity instanceof LivingEntity living) {
			template.attributes().forEach((name, value) -> {
				Holder<Attribute> attribute = ATTRIBUTES.get(name); AttributeInstance instance = attribute == null ? null : living.getAttribute(attribute);
				if (instance != null) instance.setBaseValue(value);
			});
			// Multipliers scale the entity type's own default, after absolute values.
			template.multipliers().forEach((name, factor) -> {
				Holder<Attribute> attribute = ATTRIBUTES.get(name); AttributeInstance instance = attribute == null ? null : living.getAttribute(attribute);
				if (instance != null) instance.setBaseValue(instance.getBaseValue() * factor);
			});
			if (template.attributes().containsKey("max_health") || template.multipliers().containsKey("max_health"))
				living.setHealth(living.getMaxHealth());
			template.equipment().forEach((slot, entry) -> {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.id()));
				living.setItemSlot(SLOTS.get(slot), new ItemStack(item, entry.count()));
			});
			if (template.loot().xp() == 0) living.skipDropExperience();
		}
		MobForgeEntityData.apply(entity, template);
		if (!level.addFreshEntity(entity)) throw new IllegalStateException("server rejected spawned entity");
		return entity;
	}

	public static Holder<Attribute> attribute(String name) { return ATTRIBUTES.get(name); }

	public static boolean setAttribute(Entity entity, String name, double value) {
		if (!(entity instanceof LivingEntity living)) return false;
		Holder<Attribute> attribute = ATTRIBUTES.get(name); if (attribute == null) return false;
		AttributeInstance instance = living.getAttribute(attribute); if (instance == null) return false;
		instance.setBaseValue(value);
		if ("max_health".equals(name) && living.getHealth() > value) living.setHealth((float) value);
		return true;
	}
}
