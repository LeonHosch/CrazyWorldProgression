package ekuzo.crazyworldprogression.portals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatePortalVehicleTest {
    @Test void ridersTravelWithTheirVehicleOnlyWhenEveryRiderMayUseThePortal() {
        assertTrue(PortalService.takesVehicle(true, true));
        assertFalse(PortalService.takesVehicle(true, false));
        assertFalse(PortalService.takesVehicle(false, true));
    }
}
