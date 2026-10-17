package ekuzo.crazyworldprogression.portals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalContactGeometryTest {
    @Test void planeAxesAndThicknessMatchPortalSurface() {
        for (PortalDefinition.Plane plane : PortalDefinition.Plane.values()) {
            Vec3 onSurface = switch (plane) {
                case XY -> new Vec3(11, 65, 30);
                case XZ -> new Vec3(11, 64, 31);
                case YZ -> new Vec3(10, 65, 31);
            };
            Vec3 outsideThickness = switch (plane) {
                case XY -> onSurface.add(0, 0, 0.86);
                case XZ -> onSurface.add(0, 0.86, 0);
                case YZ -> onSurface.add(0.86, 0, 0);
            };
            assertTrue(PortalContactGeometry.contains(PortalDefinition.Shape.RECTANGLE, plane,
                    10, 64, 30, 4, 6, onSurface, 0.85));
            assertFalse(PortalContactGeometry.contains(PortalDefinition.Shape.RECTANGLE, plane,
                    10, 64, 30, 4, 6, outsideThickness, 0.85));
        }
    }

    @Test void triangleRejectsTheCornerOutsideItsSlopedEdge() {
        assertTrue(PortalContactGeometry.contains(PortalDefinition.Shape.TRIANGLE,
                PortalDefinition.Plane.XY, 0, 64, 0, 4, 6, new Vec3(0, 66, 0), 0.85));
        assertFalse(PortalContactGeometry.contains(PortalDefinition.Shape.TRIANGLE,
                PortalDefinition.Plane.XY, 0, 64, 0, 4, 6, new Vec3(1, 66, 0), 0.85));
    }
}
