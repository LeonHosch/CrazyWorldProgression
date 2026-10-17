package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaidEncounterStateTest {
    @Test void encounterActiveStatePersistsAndOldAttemptsStayActive() {
        RaidData.Attempt outside = RaidData.Attempt.first().withActive(false);
        var encoded = RaidData.Attempt.CODEC.encodeStart(JsonOps.INSTANCE, outside).getOrThrow();
        assertFalse(RaidData.Attempt.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().encounterActive());
        encoded.getAsJsonObject().remove("encounter_active");
        assertTrue(RaidData.Attempt.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().encounterActive());
    }
}
