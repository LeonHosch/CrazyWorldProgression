package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkshopHelpersTest {
    @Test void movingShiftsTheHelperButNotItsTarget() {
        var portal = (MobScript.PortalLocationStep) WorkshopHelpers.shifted(new MobScript.PortalLocationStep(
                Identifier.parse("cwp:exit"), "add", "a", new MobScript.Position(1, 2, 3),
                new MobScript.Position(10, 20, 30)), 1, -1, 5);
        assertEquals(new MobScript.Position(2, 1, 8), portal.position());
        assertEquals(new MobScript.Position(10, 20, 30), portal.target());
        var box = (MobScript.EventBoxStep) WorkshopHelpers.shifted(new MobScript.EventBoxStep("gate",
                new MobScript.Position(0, 0, 0), new MobScript.Position(2, 2, 2)), 0, 0, -1);
        assertEquals(new MobScript.Position(0, 0, -1), box.min());
        assertEquals(new MobScript.Position(2, 2, 1), box.max());
        var building = (MobScript.BuildingStep) WorkshopHelpers.shifted(new MobScript.BuildingStep(
                Identifier.parse("cwp:wall"), "add", new MobScript.Position(0, 0, 0), "replace"), 0, 5, 0);
        assertEquals("replace", building.mode());
        assertNull(WorkshopHelpers.shifted(new MobScript.WaitDurationStep(20), 1, 0, 0));
    }
}
