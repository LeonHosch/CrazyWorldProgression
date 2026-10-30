package ekuzo.crazyworldprogression.mobforge.script;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MobScriptCodeTest {
    private final MobScriptCode code = new MobScriptCode();

    @Test void replacingStepsKeepsEveryOtherLineAndComments() {
        String source = """
                script moved
                # arena entrance
                eventbox(name="gate", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2})  # trigger
                if kills("adds") > 0:
                    building(build_id="cwp:wall", action="add", location={"x":5,"y":64,"z":5})
                """;
        MobScript script = code.decode(source);
        String replaced = code.replaceSteps(source, java.util.Map.of(
                0, new MobScript.EventBoxStep("gate", new MobScript.Position(1, 64, 0), new MobScript.Position(3, 66, 2)),
                2, new MobScript.BuildingStep(net.minecraft.resources.Identifier.parse("cwp:wall"), "add",
                        new MobScript.Position(5, 65, 5), "replace")));
        String[] lines = replaced.split("\n", -1);
        assertEquals("# arena entrance", lines[1]);
        assertTrue(lines[2].startsWith("eventbox(") && lines[2].endsWith("# trigger"));
        assertTrue(lines[4].startsWith("    building("));
        assertEquals(source.split("\n", -1)[3], lines[3]);
        MobScript moved = code.decode(replaced);
        assertEquals(new MobScript.Position(1, 64, 0), ((MobScript.EventBoxStep) moved.steps().get(0)).min());
        assertEquals("replace", ((MobScript.BuildingStep) moved.steps().get(2)).mode());
        assertEquals(script.steps().size(), moved.steps().size());
        assertThrows(IllegalArgumentException.class, () -> code.replaceSteps(source,
                java.util.Map.of(3, new MobScript.ElseStep())));
    }

    @Test void achievementUnlockSupportsPlayerListsAndRoundTrips() {
        MobScript script = code.decode("""
                script achievement_example
                achievement(achievement_id="echelon-core:mysteries/root", player="entered")
                achievement(achievement_id="minecraft:story/root")
                """);
        assertEquals(new MobScript.AchievementStep(net.minecraft.resources.Identifier.parse("echelon-core:mysteries/root"),
                "entered"), script.steps().getFirst());
        assertEquals(script, code.decode(code.encode(script)));
    }

    @Test void soundLocationIsOptional() {
        MobScript script = code.decode("""
                script music_example
                sound(sound_id="minecraft:music_disc.pigstep")
                sound(sound_id="minecraft:block.note_block.pling", location={"x":1,"y":64,"z":2})
                """);
        assertEquals(new MobScript.SoundStep(net.minecraft.resources.Identifier.parse("minecraft:music_disc.pigstep"),
                null), script.steps().getFirst());
        assertEquals(new MobScript.Position(1, 64, 2), ((MobScript.SoundStep) script.steps().get(1)).location());
        assertEquals(script, code.decode(code.encode(script)));
    }

    @Test void soundStopStopsOneOrEverySound() {
        MobScript script = code.decode("""
                script stop_example
                sound_stop(sound_id="minecraft:music_disc.pigstep")
                sound_stop()
                """);
        assertEquals(java.util.List.of(
                new MobScript.StopSoundStep(net.minecraft.resources.Identifier.parse("minecraft:music_disc.pigstep")),
                new MobScript.StopSoundStep(null)), script.steps());
        assertEquals(script, code.decode(code.encode(script)));
    }

    @Test void savedLootPoolSupportsPlayerListVariables() {
        MobScript script = code.decode("""
                script loot_example

                loot(lootpool_id="cwp:raid_rewards", player="entrants")
                loot(lootpool_id="cwp:raid_rewards")
                """);
        assertEquals(new MobScript.LootPoolStep(net.minecraft.resources.Identifier.parse("cwp:raid_rewards"),
                "entrants"), script.steps().getFirst());
        assertEquals(script, code.decode(code.encode(script)));
    }

    @Test void developmentRaidScriptParsesAndKeepsItsSequence() throws Exception {
        Path source = Path.of("../EchelonCore/run/config/mobforge/scripts/raidscript.mfs");
        if (!Files.exists(source)) return;
        MobScript script = code.decode(Files.readString(source));
        assertEquals("raidscript", script.id());
        // Check the order of the key steps rather than exact positions, so editing the dev script stays easy.
        var steps = script.steps();
        assertInstanceOf(MobScript.EventBoxStep.class, steps.getFirst());
        int start = indexOf(steps, MobScript.EncounterStartStep.class);
        int end = indexOf(steps, MobScript.EncounterEndStep.class);
        assertTrue(start >= 0 && end > start, "encounter_start must come before encounter_end");
        assertEquals(new MobScript.LootPoolStep(net.minecraft.resources.Identifier.parse("cwp:testpool"), ""),
                steps.get(end + 1));
        assertEquals(new MobScript.AchievementStep(
                net.minecraft.resources.Identifier.parse("echelon-core:mysteries/raidmaster"), ""), steps.get(end + 2));
        assertTrue(indexOf(steps, MobScript.BuildingStep.class) > end);
        assertInstanceOf(MobScript.PortalLocationStep.class, steps.getLast());
        assertEquals(script, code.decode(code.encode(script)));
    }

    @Test void nestedChecksAndCommandsRoundTrip() {
        String source = """
                script raid_example

                event_box(name="plate", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2})
                event(condition="portal_used(\\\"cwp:entrance\\\")", variable="entries", operation="increment", value="1")
                while kills("raid_add") < 6, max_iterations=1000:
                    if players_in("plate") >= 2 and powered(10, 64, 5):
                        variable(name="charge", operation="increment", value="1")
                    else:
                        wait(duration="1s")
                portal_location(portal="cwp:boss_gate", action="add", location="entrance", position={"x":3,"y":64,"z":4})
                finish_instance()
                """;
        MobScript parsed = code.decode(source);
        assertEquals("raid_example", parsed.id());
        assertEquals(11, parsed.steps().size());
        assertEquals(parsed, code.decode(code.encode(parsed)));
        assertInstanceOf(MobScript.LoopStep.class, parsed.steps().get(2));
        assertInstanceOf(MobScript.FinishInstanceStep.class, parsed.steps().getLast());
    }

    @Test void rejectsMalformedBlocks() {
		assertThrows(IllegalArgumentException.class, () -> code.decode("script old\nraid_complete()\n"));
		assertThrows(IllegalArgumentException.class, () -> code.decode("script old\nsleep(duration=\"1s\")\n"));
		assertThrows(IllegalArgumentException.class, () -> code.decode("script mixed\nwait(duration=\"1s\", condition=\"true\")\n"));
        assertThrows(IllegalArgumentException.class, () -> code.decode("script test\nif kills(\"add\") > 1:\n"));
        assertThrows(IllegalArgumentException.class, () -> code.decode("script test\nelse:\n    wait(duration=\"1s\")\n"));
        assertThrows(IllegalArgumentException.class, () -> code.decode("script test\nwhile var(\"x\") < 1:\n  wait(duration=\"1s\")\n"));
    }

    @Test void outerElseClosesNestedBlocksAndConditionsValidate() {
        MobScript script = code.decode("""
                script nested
                if (players_in("plate") >= 2 and not powered("switch")) or kills("adds") >= 3:
                    if var("ready") == true:
                        wait(duration="1t")
                else:
                    wait(duration="2t")
                """);
        assertEquals(script, code.decode(code.encode(script)));
        assertThrows(IllegalArgumentException.class, () -> ScriptCondition.validate("unknown('x')"));
    }

    @Test void legacyYamlConvertsWithoutLosingStepFields() {
        MobScriptYaml yaml = new MobScriptYaml();
        MobScript original = yaml.decode("""
                id: legacy
                steps:
                  - spawn:
                      template: raid_zombie
                      location: {x: 1, y: 65, z: 2}
                      offset: {x: 2, y: 0, z: 2}
                      count: 3
                      ai: {mode: target_player, combat: true, player: Alice, range: 32, aggressive: true}
                  - wait_until: {condition: '${kills.raid_add >= 6}', timeout: 5m}
                """);
        assertEquals(original, code.decode(code.encode(original)));
    }

    @Test void everySupportedStepSurvivesTextRoundTrip() {
        MobScriptYaml yaml = new MobScriptYaml();
        MobScript all = yaml.decode("""
                id: all_steps
                steps:
                  - spawn: {template: raid_zombie, location: {x: 1, y: 64, z: 2}, offset: {x: 1, y: 0, z: 1}, count: 2}
                  - wait: {duration: 2t}
                  - spawn_entity: {id: 'minecraft:zombie', at: origin, save_as: boss}
                  - wait_until: {condition: '${boss.health_percent <= 0.5}', timeout: 5m}
                  - mob_cast: {mob: '${boss}', ability: 'cwp:slam'}
                  - set_phase: {mob: '${boss}', phase: enraged}
                  - trigger: {event: 'cwp:start'}
                  - reward_currency: {currency: 'cwp:coins', amount: 5}
                  - reward_item: {item: 'minecraft:diamond', count: 2}
                  - reward_loot: {loot_table: 'minecraft:chests/simple_dungeon', rolls: 1}
                  - variable: {name: charge, operation: set, value: 'true'}
                  - event: {condition: '${kills.raid_add}', variable: charge, operation: increment, value: '1'}
                  - event_box: {name: plate, min: {x: 0, y: 64, z: 0}, max: {x: 2, y: 66, z: 2}}
                  - redstone_sensor: {name: switch, position: {x: 3, y: 64, z: 3}}
                  - if: '${box.plate >= 2}'
                  - wait: {duration: 1t}
                  - else: true
                  - wait: {duration: 2t}
                  - end_if: true
                  - loop: {condition: '${kills.raid_add < 6}', max_iterations: 100}
                  - wait: {duration: 1t}
                  - end_loop: true
                  - portal_location: {portal: 'cwp:gate', action: add, location: main, position: {x: 3, y: 64, z: 4}}
                  - finish_instance: true
                """);
        assertEquals(all, code.decode(code.encode(all)));
    }

    @Test void encounterBuildingMobAndEffectStepsRoundTrip() {
        MobScript script = code.decode("""
                script new_encounter
                encounter_start()
                spawn_mobs(mob_id="minecraft:zombie", location={"x":1,"y":65,"z":2}, offset={"x":2,"y":0,"z":2}, count=3)
                event_box(name="arena", mode="mobs", mob_id="minecraft:zombie", min={"x":0,"y":64,"z":0}, max={"x":5,"y":70,"z":5})
                event_box(name="players", mode="players", player_variable="entered", count_variable="inside", min={"x":0,"y":64,"z":0}, max={"x":5,"y":70,"z":5})
                event(condition="box_triggers('arena')", variable="activations", operation="increment", value="1")
                wait_until(condition="powered(3, 64, 4)", timeout="5m")
                building(build_id="cwp:wall", action="spawn", location={"x":3,"y":64,"z":4})
                particles(particle_id="minecraft:flame", location={"x":3,"y":64,"z":4}, offset={"x":1,"y":1,"z":1}, count=20, speed=0.1)
                explosion(location={"x":3,"y":64,"z":4}, power=2)
                sound(sound_id="minecraft:block.note_block.pling", location={"x":3,"y":64,"z":4})
                effect(effect_id="minecraft:speed", player_id="entered", action="apply", amplifier=1)
                effect(effect_id="minecraft:speed", player_id="entered", action="remove", amplifier=0)
                building(build_id="cwp:wall", action="despawn", location={"x":3,"y":64,"z":4})
                encounter_end()
                """);
        assertEquals(script, code.decode(code.encode(script)));
        assertInstanceOf(MobScript.EventBoxStep.class, script.steps().get(2));
        assertEquals("mobs", ((MobScript.EventBoxStep) script.steps().get(2)).mode());
        assertEquals("entered", ((MobScript.EventBoxStep) script.steps().get(3)).playerVariable());
        assertEquals("inside", ((MobScript.EventBoxStep) script.steps().get(3)).countVariable());
    }

    @Test void renamedStepsParseAndOldNamesStillLoad() {
        MobScript current = code.decode("""
                script renamed
                eventbox(name="start", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2})
                wait(condition="players_in('start') >= 1", timeout="5m")
                portal(portal="cwp:gate", action="add", location="arena", position={"x":1,"y":64,"z":1})
                portal(portal="cwp:gate", action="delete", location="arena")
                portal(portal="cwp:gate", action="delete")
                building(build_id="cwp:bridge", action="add", location={"x":5,"y":64,"z":5})
                building(build_id="cwp:bridge", action="delete", location={"x":5,"y":64,"z":5})
                """);
        MobScript legacy = code.decode("""
                script renamed
                event_box(name="start", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2})
                wait_until(condition="players_in('start') >= 1", timeout="5m")
                portal_location(portal="cwp:gate", action="add", location="arena", position={"x":1,"y":64,"z":1})
                portal_location(portal="cwp:gate", action="remove", location="arena")
                portal_location(portal="cwp:gate", action="clear")
                building(build_id="cwp:bridge", action="spawn", location={"x":5,"y":64,"z":5})
                building(build_id="cwp:bridge", action="despawn", location={"x":5,"y":64,"z":5})
                """);
        assertEquals(current, legacy);
        var deleteAll = (MobScript.PortalLocationStep) current.steps().get(4);
        assertTrue(deleteAll.deletesAll());
        assertFalse(((MobScript.PortalLocationStep) current.steps().get(3)).deletesAll());
        assertEquals("add", ((MobScript.BuildingStep) current.steps().get(5)).action());
        String written = code.encode(legacy);
        assertTrue(written.contains("eventbox(") && written.contains("wait(") && written.contains("portal("), written);
        assertFalse(written.contains("event_box") || written.contains("wait_until") || written.contains("portal_location")
                || written.contains("spawn") || written.contains("remove") || written.contains("clear"), written);
        assertEquals(current, code.decode(written));
    }

    private static int indexOf(java.util.List<MobScript.Step> steps, Class<?> type) {
        for (int index = 0; index < steps.size(); index++) if (type.isInstance(steps.get(index))) return index;
        return -1;
    }
}
