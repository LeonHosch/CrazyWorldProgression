package ekuzo.crazyworldprogression.portals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidDownsTest {
    @Test void dyingAgainSoonAfterAReviveMovesTheRevivePointToTheCheckpoint() {
        assertFalse(RaidDowns.useCheckpoint(-1L, true), "never revived");
        assertTrue(RaidDowns.useCheckpoint(100L, false), "any death within 5 seconds");
        assertFalse(RaidDowns.useCheckpoint(101L, false), "ordinary death after 5 seconds");
        assertTrue(RaidDowns.useCheckpoint(400L, true), "void or drowning within 20 seconds");
        assertFalse(RaidDowns.useCheckpoint(401L, true), "void or drowning after 20 seconds");
    }

    @Test void selfReviveNeedsFiveSecondsTouchingThePointAndNoEncounter() {
        assertTrue(RaidDowns.canSelfRevive(false, 100L, 0.5));
        assertFalse(RaidDowns.canSelfRevive(false, 99L, 0.5), "too early");
        assertFalse(RaidDowns.canSelfRevive(false, 200L, 1.5), "not at the point");
        assertFalse(RaidDowns.canSelfRevive(true, 200L, 0.0), "never during an encounter");
    }

    @Test void spectatorsAreHeldInsideTheLeashSphere() {
        Vec3 point = new Vec3(0, 64, 0);
        Vec3 inside = new Vec3(3, 66, 4);
        assertSame(inside, RaidDowns.leash(point, inside, 10.0));
        Vec3 clamped = RaidDowns.leash(point, new Vec3(0, 64, 30), 10.0);
        assertEquals(10.0, clamped.distanceTo(point), 1e-9);
        assertEquals(new Vec3(0, 64, 10), clamped);
    }

    @Test void onlyRaidsAndDungeonsWithoutKeepInventoryHaveStrictEncounters() {
        assertTrue(RaidService.strictEncounters(true, true), "raid");
        assertTrue(RaidService.strictEncounters(true, false), "raid without keep-inventory");
        assertTrue(RaidService.strictEncounters(false, false), "dungeon without keep-inventory");
        assertFalse(RaidService.strictEncounters(false, true), "dungeon with keep-inventory: unlimited revives");
    }
}
