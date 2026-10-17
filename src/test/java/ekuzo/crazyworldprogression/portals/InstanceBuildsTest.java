package ekuzo.crazyworldprogression.portals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceBuildsTest {
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
