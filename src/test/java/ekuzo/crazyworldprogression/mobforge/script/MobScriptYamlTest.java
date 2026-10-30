package ekuzo.crazyworldprogression.mobforge.script;

import org.junit.jupiter.api.Test;
import net.minecraft.resources.Identifier;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MobScriptYamlTest {
	private final MobScriptYaml yaml = new MobScriptYaml();

	@Test void finishInstanceRoundTripsWithOptionalSpawn() {
		MobScript script = yaml.decode("id: raid\nsteps:\n  - finish_instance: {spawn: {x: 10, y: 64, z: 5}}\n");
		assertEquals(new MobScript.Position(10, 64, 5),
				((MobScript.FinishInstanceStep) script.steps().get(0)).spawn());
		assertEquals(script, yaml.decode(yaml.encode(script)));
		assertEquals(new MobScript.FinishInstanceStep(),
				yaml.decode("id: raid\nsteps:\n  - finish_instance: true\n").steps().get(0));
	}

	@Test void buildingModeRoundTripsAndStaysOutOfOldSteps() {
		MobScript script = yaml.decode("id: raid\nsteps:\n"
				+ "  - building: {build_id: cwp:wall, action: add, location: {x: 1, y: 64, z: 2}}\n"
				+ "  - building: {build_id: cwp:wall, action: delete, location: {x: 1, y: 64, z: 2}, mode: replace}\n");
		assertEquals("", ((MobScript.BuildingStep) script.steps().get(0)).mode());
		assertEquals("replace", ((MobScript.BuildingStep) script.steps().get(1)).mode());
		assertEquals(script, yaml.decode(yaml.encode(script)));
		assertEquals(1, yaml.encode(script).split("mode", -1).length - 1);
		assertThrows(IllegalArgumentException.class, () -> yaml.decode("id: raid\nsteps:\n"
				+ "  - building: {build_id: cwp:wall, action: add, location: {x: 1, y: 64, z: 2}, mode: merge}\n"));
	}

	@Test void parsesOrderedSpawnAndWaitBlocks() {
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
				  - wait: {duration: 5s}
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
		assertEquals(100, assertInstanceOf(MobScript.WaitDurationStep.class, script.steps().get(1)).ticks());
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
		assertThrows(IllegalArgumentException.class, () -> yaml.decode("id: old\nsteps:\n  - sleep: 5s\n"));
		assertThrows(IllegalArgumentException.class, () -> yaml.decode("id: old\nsteps:\n  - type: sleep\n    duration: 5s\n"));
		assertThrows(IllegalArgumentException.class, () -> yaml.decode("id: mixed\nsteps:\n  - wait: {duration: 5s, condition: 'true'}\n"));
	}

	@Test void typedWaitDurationParses() {
		MobScript script = yaml.decode("id: typed\nsteps:\n  - type: wait\n    duration: 5s\n");
		assertEquals(new MobScript.WaitDurationStep(100), script.steps().getFirst());
	}

	@Test void rewardStepsRoundTripAndRejectInvalidAmounts() {
		MobScript script = yaml.decode("""
				id: victory
				steps:
				  - reward_currency: {currency: echeloncore:echelon_points, amount: 12}
				  - reward_item: {item: minecraft:diamond, count: 3}
				  - reward_loot: {loot_table: minecraft:chests/simple_dungeon, rolls: 2}
				""");
		assertEquals(new MobScript.RewardCurrencyStep(
				net.minecraft.resources.Identifier.parse("echeloncore:echelon_points"), 12), script.steps().getFirst());
		assertEquals(script, yaml.decode(yaml.encode(script)));
		assertThrows(IllegalArgumentException.class, () -> yaml.decode("""
				id: bad
				steps:
				  - reward_currency: {currency: echeloncore:echelon_points, amount: 0}
				"""));
	}

	@Test void raidControlFlowAndSensorsRoundTrip() {
		MobScript script = yaml.decode("""
				id: raid
				steps:
				  - event_box: {name: plate, min: {x: 1, y: 64, z: 1}, max: {x: 2, y: 66, z: 2}}
				  - redstone_sensor: {name: switch, position: {x: 4, y: 64, z: 4}}
				  - variable: {name: charge, operation: set, value: 0}
				  - event: {condition: '${box.plate >= 2}', variable: charge, operation: increment, value: 1}
				  - event: {condition: '${kills.raid_add}', variable: charge, operation: increment, value: 1}
				  - loop: {condition: '${var.charge < 3}', max_iterations: 1000}
				  - wait: {duration: 1s}
				  - end_loop: true
				  - if: '${redstone.switch == true}'
				  - portal_location: {portal: cwp:raid_gate, action: add, location: arena, position: {x: 5, y: 65, z: 5}}
				  - else: true
				  - portal_location: {portal: cwp:raid_gate, action: clear, location: arena}
				  - end_if: true
				""");
		assertEquals(script, yaml.decode(yaml.encode(script)));
		assertEquals(13, script.steps().size());
		MobScriptFlow flow = MobScriptFlow.compile(script.steps());
		assertEquals(8, flow.falseJump.get(5));
		assertEquals(11, flow.falseJump.get(8));
		assertEquals(13, flow.endJump.get(10));
		assertEquals(5, flow.loopBack.get(7));
		ScriptCondition.validate("${portal.cwp:raid_gate >= 1}");
		ScriptCondition.Context counters = new ScriptCondition.Context(null, Map.of(), Map.of(), Map.of(),
				Map.of("raid_add", 2L), Map.of(Identifier.parse("cwp:raid_gate"), 3L));
		assertEquals(2L, ScriptCondition.eventCounter("${kills.raid_add}", counters));
		assertEquals(3L, ScriptCondition.eventCounter("${portal.cwp:raid_gate}", counters));
		assertThrows(IllegalArgumentException.class, () -> yaml.decode("""
				id: broken
				steps:
				  - if: '${var.charge}'
				  - wait: {duration: 1s}
				"""));
	}
}
