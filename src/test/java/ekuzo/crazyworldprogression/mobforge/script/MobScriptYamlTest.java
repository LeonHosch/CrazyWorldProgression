package ekuzo.crazyworldprogression.mobforge.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MobScriptYamlTest {
	private final MobScriptYaml yaml = new MobScriptYaml();

	@Test void parsesOrderedSpawnAndSleepBlocks() {
		MobScript script = yaml.decode("""
				id: invasion
				steps:
				  - spawn:
				      template: raid_zombie
				      location: {x: 100, y: 70, z: 200}
				      offset: {x: 8, y: 0, z: 8}
				      count: 30
				      ai:
				        mode: target_position
				        combat: true
				        target: {x: 0, y: 80, z: 0}
				        arrival_radius: 4
				  - sleep: 5s
				  - type: spawn
				    template: raid_zombie
				    location: {x: 0, y: 80, z: 0}
				""");

		assertEquals("invasion", script.id());
		assertEquals(3, script.steps().size());
		MobScript.SpawnStep first = assertInstanceOf(MobScript.SpawnStep.class, script.steps().get(0));
		assertEquals(30, first.count());
		assertEquals(8, first.offset().x());
		assertEquals("target_position", first.aiOverride().mode());
		assertEquals(100, assertInstanceOf(MobScript.SleepStep.class, script.steps().get(1)).ticks());
		assertEquals(script, yaml.decode(yaml.encode(script)));
	}

	@Test void scalarOffsetSpreadsHorizontally() {
		MobScript script = yaml.decode("""
				id: compact
				steps:
				  - spawn:
				      template: zombie
				      location: {x: 0, y: 64, z: 0}
				      offset: 4
				""");
		MobScript.SpawnStep spawn = assertInstanceOf(MobScript.SpawnStep.class, script.steps().getFirst());
		assertEquals(new MobScript.Offset(4, 0, 4), spawn.offset());
	}

	@Test void rejectsUnknownStepTypes() {
		assertThrows(IllegalArgumentException.class, () -> yaml.decode("id: bad\nsteps:\n  - type: explode\n"));
	}
}
