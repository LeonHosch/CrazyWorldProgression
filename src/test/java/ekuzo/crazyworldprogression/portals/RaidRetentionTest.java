package ekuzo.crazyworldprogression.portals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidRetentionTest {
    @Test void aDeadSoloPlayerKeepsTheRaidEvenWhenTheEncounterIsNotActive() {
        // After a solo wipe the encounter is inactive and the body has left the world; the player is still dead.
        assertTrue(RaidService.keepWhileRespawning(Set.of("a"), Set.of("a"), Set.of(), List.of()));
        // Waiting to respawn at the rally point after the wipe.
        assertTrue(RaidService.keepWhileRespawning(Set.of("a"), Set.of(), Set.of(), List.of("a")));
        // Down during an encounter.
        assertTrue(RaidService.keepWhileRespawning(Set.of("a"), Set.of(), Set.of("a"), List.of()));
    }

    @Test void anEmptyRaidWithNobodyRespawningIsReleased() {
        assertFalse(RaidService.keepWhileRespawning(Set.of("a"), Set.of(), Set.of(), List.of()));
        // Offline players do not hold the instance open.
        assertFalse(RaidService.keepWhileRespawning(Set.of(), Set.of(), Set.of("a"), List.of("a")));
    }
}
