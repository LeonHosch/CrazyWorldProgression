package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.script.MobScriptCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidWipeWindowTest {
    @Test void aWipeResetsTheEncounterFiveSecondsLater() {
        long wipedAt = 1_000L;
        long reset = RaidService.wipeResetTick(wipedAt);
        assertEquals(wipedAt + 100L, reset);
        assertEquals(RaidService.WipeState.WAITING, RaidService.wipeState(reset, wipedAt));
        assertEquals(RaidService.WipeState.WAITING, RaidService.wipeState(reset, reset - 1));
        assertEquals(RaidService.WipeState.RESET, RaidService.wipeState(reset, reset));
        // An encounter_end inside the window removes the pending wipe, which is the clutch clear.
        assertEquals(RaidService.WipeState.NONE, RaidService.wipeState(null, reset));
    }

    @Test void wipeIsAScriptStep() {
        MobScriptCode code = new MobScriptCode();
        MobScript script = code.decode("""
                script enrage
                encounter_start()
                wait(duration="5m")
                wipe()
                """);
        assertInstanceOf(MobScript.WipeStep.class, script.steps().get(2));
        String written = code.encode(script);
        assertTrue(written.contains("wipe()"), written);
        assertEquals(script, code.decode(written));
    }
}
