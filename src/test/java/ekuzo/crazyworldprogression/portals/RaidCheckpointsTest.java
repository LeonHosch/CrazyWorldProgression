package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidCheckpointsTest {
    private static final String PLAYER = "00000000-0000-0000-0000-000000000001";
    private static final String RAID = "cwp:raid";

    @Test void checkpointsLastTwentyFourHoursFromTheLastCompletedEncounter() {
        RaidCheckpoints data = new RaidCheckpoints();
        long start = 1_000_000L;
        data.record(PLAYER, RAID, 1, start);
        assertEquals(1, data.current(PLAYER, RAID, start + 1).encounters());
        assertNull(data.current(PLAYER, RAID, start + RaidCheckpoints.LIFETIME_MILLIS));
        data.record(PLAYER, RAID, 2, start + 1_000L);
        assertEquals(start + 1_000L + RaidCheckpoints.LIFETIME_MILLIS, data.current(PLAYER, RAID, start + 2_000L).expiresEpochMillis());
    }

    @Test void checkpointsNeverGoBackwardsUntilReset() {
        RaidCheckpoints data = new RaidCheckpoints();
        data.record(PLAYER, RAID, 2, 0L);
        data.record(PLAYER, RAID, 1, 10L);
        assertEquals(2, data.current(PLAYER, RAID, 20L).encounters());
        assertTrue(data.reset(PLAYER, RAID));
        assertNull(data.current(PLAYER, RAID, 20L));
        assertFalse(data.reset(PLAYER, RAID));
    }

    @Test void checkpointsSurviveSaving() {
        RaidCheckpoints data = new RaidCheckpoints();
        data.record(PLAYER, RAID, 3, 0L);
        var encoded = RaidCheckpoints.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        RaidCheckpoints restored = RaidCheckpoints.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(data.current(PLAYER, RAID, 1L), restored.current(PLAYER, RAID, 1L));
    }

    @Test void finishingClearsEveryParticipantOnlyForThisRaidPortal() {
        RaidCheckpoints data = new RaidCheckpoints();
        String second = "00000000-0000-0000-0000-000000000002";
        data.record(PLAYER, RAID, 2, 0L);
        data.record(second, RAID, 1, 0L);
        data.record(PLAYER, "cwp:other", 1, 0L);
        data.clearForParticipants(RAID, List.of(PLAYER, second));
        assertNull(data.current(PLAYER, RAID, 1L));
        assertNull(data.current(second, RAID, 1L));
        assertEquals(1, data.current(PLAYER, "cwp:other", 1L).encounters());
    }

    @Test void inviteNeedsAFreeSlotUnlessThePlayerAlreadyBelongs() {
        assertTrue(PortalService.hasRoom(List.of("a"), "b", 2));
        assertFalse(PortalService.hasRoom(List.of("a", "b"), "c", 2));
        assertTrue(PortalService.hasRoom(List.of("a", "b"), "b", 2));
    }

    @Test void emptyPrivateInstancesWaitFiveMinutesForTheirParty() {
        assertFalse(PortalService.graceOver(100L, 100L + PortalService.REJOIN_GRACE_TICKS - 1));
        assertTrue(PortalService.graceOver(100L, 100L + PortalService.REJOIN_GRACE_TICKS));
    }

    @Test void leavingIsOnlyBlockedDuringAnEncounterOfARaidWithoutKeepInventory() {
        assertTrue(PortalService.leaveBlocked(true, true, false));
        assertFalse(PortalService.leaveBlocked(true, true, true));
        assertFalse(PortalService.leaveBlocked(true, false, false));
        assertFalse(PortalService.leaveBlocked(false, true, false));
    }

    @Test void finishedInstancesCloseAsSoonAsEveryoneLeaves() {
        assertTrue(PortalService.releaseEmpty(true, false, 100L, 100L));
        assertFalse(PortalService.releaseEmpty(false, false, 100L, 100L));
        assertTrue(PortalService.releaseEmpty(false, false, 100L, 100L + PortalService.REJOIN_GRACE_TICKS));
    }

    @Test void finishedLeaseSurvivesSavingAndPlayerUpdates() {
        PortalData.StoredInstance lease = new PortalData.StoredInstance(Identifier.parse(RAID),
                Identifier.parse("cwp:pocket"), Identifier.parse("cwp:instance_1"),
                1L, 1000L, 0, 64, 0, 16, 16, 16, List.of(PLAYER)).withFinished();
        lease = lease.withVisitor(java.util.UUID.fromString(PLAYER));
        var encoded = PortalData.StoredInstance.CODEC.encodeStart(JsonOps.INSTANCE, lease).getOrThrow();
        assertTrue(PortalData.StoredInstance.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().finished());
        encoded.getAsJsonObject().remove("finished");
        assertFalse(PortalData.StoredInstance.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().finished());
    }
}
