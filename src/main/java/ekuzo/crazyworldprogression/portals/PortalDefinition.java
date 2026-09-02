package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Persistent generic portal geometry, template link, availability schedule, and instance lifetime. */
public record PortalDefinition(Identifier id, Identifier dimension, Shape shape, Plane plane,
                               double centerX, double centerY, double centerZ, double width, double height,
                               Identifier templateId, long lifetimeSeconds, Schedule schedule) {
    private static final Codec<Shape> SHAPE_CODEC = Codec.STRING.xmap(Shape::parse, Shape::serializedName);
    private static final Codec<Plane> PLANE_CODEC = Codec.STRING.xmap(Plane::parse, Plane::serializedName);

    public static final Codec<PortalDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("id").forGetter(PortalDefinition::id),
            Identifier.CODEC.fieldOf("dimension").forGetter(PortalDefinition::dimension),
            SHAPE_CODEC.fieldOf("shape").forGetter(PortalDefinition::shape),
            PLANE_CODEC.fieldOf("plane").forGetter(PortalDefinition::plane),
            Codec.DOUBLE.fieldOf("center_x").forGetter(PortalDefinition::centerX),
            Codec.DOUBLE.fieldOf("center_y").forGetter(PortalDefinition::centerY),
            Codec.DOUBLE.fieldOf("center_z").forGetter(PortalDefinition::centerZ),
            Codec.DOUBLE.fieldOf("width").forGetter(PortalDefinition::width),
            Codec.DOUBLE.fieldOf("height").forGetter(PortalDefinition::height),
            Identifier.CODEC.fieldOf("template").forGetter(PortalDefinition::templateId),
            Codec.LONG.fieldOf("lifetime_seconds").forGetter(PortalDefinition::lifetimeSeconds),
            Schedule.CODEC.optionalFieldOf("schedule", Schedule.always()).forGetter(PortalDefinition::schedule)
    ).apply(instance, PortalDefinition::new));

    // Validate a complete definition before it can enter persistent or runtime state.
    public PortalDefinition {
        if (id == null || dimension == null || shape == null || plane == null || templateId == null
                || schedule == null) throw new IllegalArgumentException("Portal definition values must not be null");
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0.0 || height <= 0.0) {
            throw new IllegalArgumentException("Portal position and size must be positive and finite");
        }
        if (lifetimeSeconds < 1L) throw new IllegalArgumentException("Portal instance lifetime must be positive");
    }

    // Test a point against the finite two-dimensional surface with a small perpendicular trigger thickness.
    public boolean contains(Vec3 point, double thickness) {
        Coordinates coordinates = coordinates(point);
        if (Math.abs(coordinates.perpendicular()) > thickness) return false;
        double halfWidth = width / 2.0;
        double halfHeight = height / 2.0;
        double u = coordinates.u();
        double v = coordinates.v();
        return switch (shape) {
            case RECTANGLE -> Math.abs(u) <= halfWidth && Math.abs(v) <= halfHeight;
            case ELLIPSE -> square(u / halfWidth) + square(v / halfHeight) <= 1.0;
            case DIAMOND -> Math.abs(u) / halfWidth + Math.abs(v) / halfHeight <= 1.0;
            case TRIANGLE -> v >= -halfHeight && v <= halfHeight
                    && Math.abs(u) <= halfWidth * (1.0 - (v + halfHeight) / height);
        };
    }

    // Return a tight broad-phase box around the portal trigger surface.
    public AABB bounds(double thickness) {
        return switch (plane) {
            case XY -> new AABB(centerX - width / 2.0, centerY - height / 2.0, centerZ - thickness,
                    centerX + width / 2.0, centerY + height / 2.0, centerZ + thickness);
            case XZ -> new AABB(centerX - width / 2.0, centerY - thickness, centerZ - height / 2.0,
                    centerX + width / 2.0, centerY + thickness, centerZ + height / 2.0);
            case YZ -> new AABB(centerX - thickness, centerY - height / 2.0, centerZ - width / 2.0,
                    centerX + thickness, centerY + height / 2.0, centerZ + width / 2.0);
        };
    }

    // Convert local portal coordinates back into one world-space point for particles and exit placement.
    public Vec3 point(double u, double v, double perpendicular) {
        return switch (plane) {
            case XY -> new Vec3(centerX + u, centerY + v, centerZ + perpendicular);
            case XZ -> new Vec3(centerX + u, centerY + perpendicular, centerZ + v);
            case YZ -> new Vec3(centerX + perpendicular, centerY + v, centerZ + u);
        };
    }

    // Move a source-side feet position two blocks away from the plane so returning cannot retrigger the portal.
    public Vec3 safeReturnPosition(Vec3 feet, Vec3 bodyCenter, Vec3 movement) {
        double perpendicular = switch (plane) {
            case XY -> bodyCenter.z - centerZ;
            case XZ -> bodyCenter.y - centerY;
            case YZ -> bodyCenter.x - centerX;
        };
        double velocity = switch (plane) {
            case XY -> movement.z;
            case XZ -> movement.y;
            case YZ -> movement.x;
        };
        double sign = Math.abs(perpendicular) > 1.0E-4 ? Math.signum(perpendicular)
                : Math.abs(velocity) > 1.0E-4 ? -Math.signum(velocity) : 1.0;
        return switch (plane) {
            case XY -> new Vec3(feet.x, feet.y, centerZ + sign * 2.0);
            case XZ -> new Vec3(feet.x, centerY + sign * 2.0, feet.z);
            case YZ -> new Vec3(centerX + sign * 2.0, feet.y, feet.z);
        };
    }

    // Return a copy moved to a new dimension and center.
    public PortalDefinition movedTo(Identifier newDimension, Vec3 center) {
        return new PortalDefinition(id, newDimension, shape, plane, center.x, center.y, center.z, width, height,
                templateId, lifetimeSeconds, schedule);
    }

    // Return a copy linked directly to a different captured template.
    public PortalDefinition withTemplate(Identifier replacement) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                replacement, lifetimeSeconds, schedule);
    }

    // Return a copy with a changed maximum instance lifetime.
    public PortalDefinition withLifetime(long seconds) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, seconds, schedule);
    }

    // Return a copy with a changed recurring availability schedule.
    public PortalDefinition withSchedule(Schedule replacement) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, lifetimeSeconds, replacement);
    }

    // Project one world point into the selected portal plane.
    private Coordinates coordinates(Vec3 point) {
        return switch (plane) {
            case XY -> new Coordinates(point.x - centerX, point.y - centerY, point.z - centerZ);
            case XZ -> new Coordinates(point.x - centerX, point.z - centerZ, point.y - centerY);
            case YZ -> new Coordinates(point.z - centerZ, point.y - centerY, point.x - centerX);
        };
    }

    // Square a normalized shape coordinate.
    private static double square(double value) {
        return value * value;
    }

    public enum Shape {
        RECTANGLE, ELLIPSE, DIAMOND, TRIANGLE;

        // Parse a command or persisted portal shape.
        public static Shape parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase shape name.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Plane {
        XY, XZ, YZ;

        // Parse a command or persisted portal plane.
        public static Plane parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase plane name.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Calendar and repeating-window gates applied together to every attempted entry. */
    public record Schedule(Set<DayOfWeek> weekdays, LocalTime start, LocalTime end, ZoneId timezone,
                           long periodSeconds, long openSeconds, long anchorEpochMillis) {
        private static final Codec<DayOfWeek> DAY_CODEC = Codec.STRING.xmap(
                value -> DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT)),
                value -> value.name().toLowerCase(Locale.ROOT));
        private static final Codec<LocalTime> TIME_CODEC = Codec.STRING.xmap(LocalTime::parse, LocalTime::toString);
        private static final Codec<ZoneId> ZONE_CODEC = Codec.STRING.xmap(ZoneId::of, ZoneId::getId);
        public static final Codec<Schedule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                DAY_CODEC.listOf().fieldOf("weekdays").xmap(Set::copyOf, List::copyOf)
                        .forGetter(Schedule::weekdays),
                TIME_CODEC.fieldOf("start").forGetter(Schedule::start),
                TIME_CODEC.fieldOf("end").forGetter(Schedule::end),
                ZONE_CODEC.fieldOf("timezone").forGetter(Schedule::timezone),
                Codec.LONG.optionalFieldOf("period_seconds", 0L).forGetter(Schedule::periodSeconds),
                Codec.LONG.optionalFieldOf("open_seconds", 0L).forGetter(Schedule::openSeconds),
                Codec.LONG.optionalFieldOf("anchor_epoch_millis", 0L).forGetter(Schedule::anchorEpochMillis)
        ).apply(instance, Schedule::new));

        // Copy mutable inputs and reject periodic windows longer than their cycle.
        public Schedule {
            weekdays = Set.copyOf(weekdays);
            if (weekdays.isEmpty() || start == null || end == null || timezone == null) {
                throw new IllegalArgumentException("Portal schedule values must not be empty");
            }
            if (periodSeconds < 0L || openSeconds < 0L || periodSeconds == 0L != (openSeconds == 0L)
                    || periodSeconds > 0L && (openSeconds < 1L || openSeconds > periodSeconds)) {
                throw new IllegalArgumentException("Periodic portal window must be positive and no longer than its cycle");
            }
        }

        // Create an unrestricted schedule with no timer cycle.
        public static Schedule always() {
            return new Schedule(Set.of(DayOfWeek.values()), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT,
                    ZoneId.of("UTC"), 0L, 0L, 0L);
        }

        // Test both the calendar gate and repeating timer window at one instant.
        public boolean allows(Instant instant) {
            ZonedDateTime local = instant.atZone(timezone);
            LocalTime time = local.toLocalTime();
            boolean calendar;
            if (start.equals(end)) {
                calendar = weekdays.contains(local.getDayOfWeek());
            } else if (start.isBefore(end)) {
                calendar = weekdays.contains(local.getDayOfWeek()) && !time.isBefore(start) && time.isBefore(end);
            } else {
                calendar = weekdays.contains(local.getDayOfWeek()) && !time.isBefore(start)
                        || weekdays.contains(local.minusDays(1L).getDayOfWeek()) && time.isBefore(end);
            }
            if (!calendar || periodSeconds == 0L) return calendar;
            long elapsed = Math.max(0L, instant.toEpochMilli() - anchorEpochMillis);
            return elapsed / 1_000L % periodSeconds < openSeconds;
        }

        // Replace only the calendar gate while retaining the timer cycle.
        public Schedule withCalendar(Set<DayOfWeek> days, LocalTime newStart, LocalTime newEnd, ZoneId zone) {
            return new Schedule(days, newStart, newEnd, zone, periodSeconds, openSeconds, anchorEpochMillis);
        }

        // Replace the timer cycle and anchor its first open window at the supplied instant.
        public Schedule withPeriodic(long period, long open, Instant anchor) {
            return new Schedule(weekdays, start, end, timezone, period, open, anchor.toEpochMilli());
        }

        // Disable the repeating timer while retaining the calendar gate.
        public Schedule withoutPeriodic() {
            return new Schedule(weekdays, start, end, timezone, 0L, 0L, 0L);
        }

        // Disable the calendar restriction while retaining any repeating timer.
        public Schedule withoutCalendar() {
            return new Schedule(Set.of(DayOfWeek.values()), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT,
                    ZoneId.of("UTC"), periodSeconds, openSeconds, anchorEpochMillis);
        }
    }

    private record Coordinates(double u, double v, double perpendicular) {
    }
}
