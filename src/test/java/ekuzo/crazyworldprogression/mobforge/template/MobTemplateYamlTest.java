package ekuzo.crazyworldprogression.mobforge.template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobTemplateYamlTest {
	private final MobTemplateYaml yaml = new MobTemplateYaml();

	@Test void cleanYamlRoundTripsThroughCanonicalModel() {
		MobTemplate input = new MobTemplate("raid_zombie", "minecraft:zombie", "Raid Zombie",
				Map.of("max_health", 40.0, "movement_speed", 0.3),
				Map.of("main_hand", new MobTemplate.EquipmentEntry("minecraft:diamond_sword", 1, Map.of())),
				new MobTemplate.Properties(true, true, false, false, true, false, false, "vanilla"),
				new MobTemplate.Loot(false, 0), List.of("raid", "undead"),
				new MobTemplate.Ai("target_position", true, new MobTemplate.Position(0, 80, 0), 3, null, 64, Map.of()));
		MobTemplate output = yaml.decode(yaml.encode(input));
		assertEquals(input, output);
		assertFalse(yaml.encode(input).contains("!!"), "generated YAML must not expose Java type metadata");
	}

	@Test void malformedYamlFailsWithoutAffectingOtherFiles() {
		assertThrows(RuntimeException.class, () -> yaml.decode("id: [unterminated"));
	}

	@Test void missingTargetIsRejected() {
		// An invalid identifier avoids requiring Minecraft's runtime registry bootstrap in this pure unit test.
		MobTemplate template = new MobTemplate("bad", "not an id", null, Map.of(), Map.of(), null, null, List.of(),
				new MobTemplate.Ai("target_position", true, null, 3, null, 64, Map.of()));
		TemplateValidationException error = assertThrows(TemplateValidationException.class, () -> MobTemplateValidator.validate(template));
		assertTrue(error.getMessage().contains("ai.target is required"));
	}

	@Test void unstableMovementSpeedIsRejected() {
		MobTemplate template = new MobTemplate("too_fast", "not an id", null, Map.of("movement_speed", 1.0), Map.of(), null, null,
				List.of(), new MobTemplate.Ai("vanilla", true, null, 2, null, 64, Map.of()));
		TemplateValidationException error = assertThrows(TemplateValidationException.class, () -> MobTemplateValidator.validate(template));
		assertTrue(error.getMessage().contains("movement_speed cannot exceed 0.7"));
	}
}
