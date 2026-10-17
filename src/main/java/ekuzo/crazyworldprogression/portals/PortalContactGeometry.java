package ekuzo.crazyworldprogression.portals;

import net.minecraft.world.phys.Vec3;

/** Shared finite-surface contact math for client prediction and server validation. */
public final class PortalContactGeometry {
    private PortalContactGeometry() { }

    public static boolean contains(PortalDefinition.Shape shape, PortalDefinition.Plane plane,
                                   double centerX, double centerY, double centerZ,
                                   double width, double height, Vec3 point, double thickness) {
        double u, v, perpendicular;
        switch (plane) {
            case XY -> { u = point.x - centerX; v = point.y - centerY; perpendicular = point.z - centerZ; }
            case XZ -> { u = point.x - centerX; v = point.z - centerZ; perpendicular = point.y - centerY; }
            case YZ -> { u = point.z - centerZ; v = point.y - centerY; perpendicular = point.x - centerX; }
            default -> { return false; }
        }
        if (Math.abs(perpendicular) > thickness) return false;
        double halfWidth = width / 2.0;
        double halfHeight = height / 2.0;
        return switch (shape) {
            case RECTANGLE -> Math.abs(u) <= halfWidth && Math.abs(v) <= halfHeight;
            case ELLIPSE -> square(u / halfWidth) + square(v / halfHeight) <= 1.0;
            case DIAMOND -> Math.abs(u) / halfWidth + Math.abs(v) / halfHeight <= 1.0;
            case TRIANGLE -> v >= -halfHeight && v <= halfHeight
                    && Math.abs(u) <= halfWidth * (1.0 - (v + halfHeight) / height);
        };
    }

    private static double square(double value) { return value * value; }
}
