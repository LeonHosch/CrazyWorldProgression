package ekuzo.crazyworldprogression.mobforge.script;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptResumeTest {
    private final MobScriptCode code = new MobScriptCode();

    private MobScript twoBosses() {
        return code.decode("""
                script two_bosses
                encounter_start()
                encounter_end(spawn={"x":10,"y":64,"z":5})
                loot(lootpool_id="cwp:first")
                encounter_start()
                encounter_end()
                """);
    }

    @Test void encounterEndCarriesItsSpawnAndRoundTrips() {
        MobScript script = twoBosses();
        assertEquals(new MobScript.Position(10, 64, 5), ((MobScript.EncounterEndStep) script.steps().get(1)).spawn());
        assertNull(((MobScript.EncounterEndStep) script.steps().get(4)).spawn());
        String written = code.encode(script);
        assertTrue(written.contains("encounter_end(spawn="), written);
        assertTrue(written.contains("encounter_end()"), written);
        assertEquals(script, code.decode(written));
    }

    @Test void aCheckpointJumpsRightPastItsEncounterEnd() {
        MobScript script = twoBosses();
        assertEquals(2, MobScriptExecutor.encounterCount(script));
        assertEquals(2, MobScriptExecutor.resumeStepIndex(script, 1));
        assertInstanceOf(MobScript.LootPoolStep.class, script.steps().get(MobScriptExecutor.resumeStepIndex(script, 1)));
        assertEquals(script.steps().size(), MobScriptExecutor.resumeStepIndex(script, 2));
    }

    @Test void rewardsRightAfterTheCheckpointAreNotRepeated() {
        assertTrue(MobScriptExecutor.rewardStep(new MobScript.LootPoolStep(Identifier.parse("cwp:testpool"), "")));
        assertTrue(MobScriptExecutor.rewardStep(new MobScript.AchievementStep(Identifier.parse("echelon-core:mysteries/raidmaster"), "")));
        assertFalse(MobScriptExecutor.rewardStep(new MobScript.EncounterEndStep()));
    }
}
