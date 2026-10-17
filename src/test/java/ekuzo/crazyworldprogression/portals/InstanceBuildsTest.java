package ekuzo.crazyworldprogression.portals;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceBuildsTest {
    @Test void oneBuildGetsTheWholeBudget() {
        assertArrayEquals(new int[]{100_000}, InstanceBuilds.split(100_000, new int[]{5_000_000}));
        assertArrayEquals(new int[]{1_234}, InstanceBuilds.split(100_000, new int[]{1_234}), "never more than it needs");
    }

    @Test void concurrentBuildsShareOneBudget() {
        int[] shares = InstanceBuilds.split(100_000, new int[]{1_000_000, 1_000_000});
        assertArrayEquals(new int[]{50_000, 50_000}, shares);
        // A small build finishing early hands the rest of its share to the others.
        int[] uneven = InstanceBuilds.split(100_000, new int[]{10_000, 1_000_000, 1_000_000});
        assertEquals(10_000, uneven[0]);
        assertEquals(100_000, Arrays.stream(uneven).sum());
        assertTrue(Arrays.stream(InstanceBuilds.split(100_000, new int[]{70_000, 70_000, 70_000})).sum() <= 100_000);
    }

    @Test void finishedBuildsGetNothing() {
        assertArrayEquals(new int[]{0, 100_000}, InstanceBuilds.split(100_000, new int[]{0, 300_000}));
    }

    @Test void progressCountsUpButShows100OnlyWhenDone() {
        assertEquals(0, InstanceBuilds.percent(0, 1_000));
        assertEquals(50, InstanceBuilds.percent(500, 1_000));
        assertEquals(99, InstanceBuilds.percent(1_000, 1_000));
        assertEquals(100, InstanceBuilds.percent(0, 0));
    }

    @Test void onlyPlayersStillAtThePortalArePulledIn() {
        assertTrue(PortalService.stillAtPortal(true, true));
        assertFalse(PortalService.stillAtPortal(true, false));
        assertFalse(PortalService.stillAtPortal(false, true));
    }
}
