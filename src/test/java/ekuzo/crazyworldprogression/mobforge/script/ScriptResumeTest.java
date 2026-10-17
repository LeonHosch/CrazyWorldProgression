package ekuzo.crazyworldprogression.mobforge.script;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptResumeTest {
    private final MobScriptCode code = new MobScriptCode();

    @Test void resumingReplaysWorldStateButNotFightsOrRewards() {
        MobScript.Position at = new MobScript.Position(0, 64, 0);
        assertTrue(MobScriptExecutor.replayedOnResume(new MobScript.BuildingStep(Identifier.parse("cwp:bridge"), "add", at)));
        assertTrue(MobScriptExecutor.replayedOnResume(new MobScript.PortalLocationStep(Identifier.parse("cwp:gate"), "add", "main", at)));
        assertTrue(MobScriptExecutor.replayedOnResume(new MobScript.VariableStep("charge", "set", "1")));
        assertFalse(MobScriptExecutor.replayedOnResume(new MobScript.EncounterStartStep()));
        assertFalse(MobScriptExecutor.replayedOnResume(new MobScript.SleepStep(20)));
        assertTrue(MobScriptExecutor.rewardStep(new MobScript.LootPoolStep(Identifier.parse("cwp:testpool"), "")));
        assertTrue(MobScriptExecutor.rewardStep(new MobScript.AchievementStep(Identifier.parse("echelon-core:mysteries/raidmaster"), "")));
        assertFalse(MobScriptExecutor.rewardStep(new MobScript.EncounterEndStep()));
    }

    @Test void encountersAreCountedByEncounterEndSteps() {
        MobScript script = code.decode("""
                script two_bosses
                encounter_start()
                encounter_end()
                encounter_start()
                encounter_end()
                """);
        assertEquals(2, MobScriptExecutor.encounterCount(script));
    }
}
