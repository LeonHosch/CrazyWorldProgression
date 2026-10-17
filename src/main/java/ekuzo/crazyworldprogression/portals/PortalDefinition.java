package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
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
import java.util.Optional;
import java.util.Set;

/** Persistent generic portal geometry, template link, availability schedule, and instance lifetime. */
public record PortalDefinition(Identifier id, Identifier dimension, Shape shape, Plane plane,
                               double centerX, double centerY, double centerZ, double width, double height,
                               Identifier templateId, long lifetimeSeconds, int colorRgb,
                               String displayName, String description, int difficulty,
                               EntryPolicy entryPolicy, Schedule schedule, Admission admission) {
    public static final int DEFAULT_COLOR_RGB = 0x9B59FF;
    public static final Identifier EXIT_TEMPLATE = Identifier.parse("crazy-world-progression:__exit__");
    public static final Identifier COORDINATES_TEMPLATE = Identifier.parse("crazy-world-progression:__coordinates__");
    private static final Codec<Shape> SHAPE_CODEC = Codec.STRING.xmap(Shape::parse, Shape::serializedName);
    private static final Codec<Plane> PLANE_CODEC = Codec.STRING.xmap(Plane::parse, Plane::serializedName);
    private static final Codec<EntryPolicy> ENTRY_POLICY_CODEC = Codec.STRING.xmap(
            EntryPolicy::parse, EntryPolicy::serializedName);

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
            Codec.INT.optionalFieldOf("color_rgb", DEFAULT_COLOR_RGB).forGetter(PortalDefinition::colorRgb),
            Presentation.CODEC.forGetter(PortalDefinition::presentation),
            ENTRY_POLICY_CODEC.optionalFieldOf("entry_policy", EntryPolicy.REPEATABLE)
                    .forGetter(PortalDefinition::entryPolicy),
            Schedule.CODEC.optionalFieldOf("schedule", Schedule.always()).forGetter(PortalDefinition::schedule),
            Admission.CODEC.forGetter(PortalDefinition::admission)
    ).apply(instance, (id, dimension, shape, plane, centerX, centerY, centerZ, width, height, templateId,
                       lifetimeSeconds, colorRgb, presentation, entryPolicy, schedule, admission) ->
            new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                    templateId, lifetimeSeconds, colorRgb, presentation.displayName(), presentation.description(),
                    presentation.difficulty(), entryPolicy, schedule, admission)));

    // Validate a complete definition before it can enter persistent or runtime state.
    public PortalDefinition {
        if (id == null || dimension == null || shape == null || plane == null || templateId == null
                || displayName == null || description == null || entryPolicy == null || schedule == null || admission == null) {
            throw new IllegalArgumentException("Portal definition values must not be null");
        }
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0.0 || height <= 0.0) {
            throw new IllegalArgumentException("Portal position and size must be positive and finite");
        }
        if (admission.destination() == Destination.RETURN) {
            templateId = EXIT_TEMPLATE;
            admission = admission.withDestination(Destination.POCKET);
        }
        if (lifetimeSeconds < 0L || lifetimeSeconds == 0L && admission.mode() == Mode.PRIVATE) {
            throw new IllegalArgumentException("Zero lifetime is only available to public portals");
        }
        if (colorRgb < 0 || colorRgb > 0xFFFFFF) {
            throw new IllegalArgumentException("Portal color must be a 24-bit RGB value");
        }
        if (difficulty < 1 || difficulty > 5) {
            throw new IllegalArgumentException("Portal difficulty must be between 1 and 5 skulls");
        }
    }

    public PortalDefinition(Identifier id, Identifier dimension, Shape shape, Plane plane,
                            double centerX, double centerY, double centerZ, double width, double height,
                            Identifier templateId, long lifetimeSeconds, int colorRgb, String displayName,
                            String description, int difficulty, EntryPolicy entryPolicy, Schedule schedule) {
        this(id, dimension, shape, plane, centerX, centerY, centerZ, width, height, templateId,
                lifetimeSeconds, colorRgb, displayName, description, difficulty, entryPolicy, schedule,
                Admission.publicPortal());
    }

    // Preserve source compatibility for integrations that create portals without selecting a color.
    public PortalDefinition(Identifier id, Identifier dimension, Shape shape, Plane plane,
                            double centerX, double centerY, double centerZ, double width, double height,
                            Identifier templateId, long lifetimeSeconds, Schedule schedule) {
        this(id, dimension, shape, plane, centerX, centerY, centerZ, width, height, templateId,
                lifetimeSeconds, DEFAULT_COLOR_RGB, "", "", 1, EntryPolicy.REPEATABLE, schedule);
    }

    // Preserve the color-aware constructor introduced before portal presentation text was added.
    public PortalDefinition(Identifier id, Identifier dimension, Shape shape, Plane plane,
                            double centerX, double centerY, double centerZ, double width, double height,
                            Identifier templateId, long lifetimeSeconds, int colorRgb, Schedule schedule) {
        this(id, dimension, shape, plane, centerX, centerY, centerZ, width, height, templateId,
                lifetimeSeconds, colorRgb, "", "", 1, EntryPolicy.REPEATABLE, schedule);
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

    /** Check whether a player is within the given distance of the portal's finite surface bounds. */
    public boolean isNear(Vec3 point, double distance) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException("Portal proximity distance must be finite and nonnegative");
        }
        Coordinates local = coordinates(point);
        double du = Math.max(0.0, Math.abs(local.u()) - width / 2.0);
        double dv = Math.max(0.0, Math.abs(local.v()) - height / 2.0);
        return square(du) + square(dv) + square(local.perpendicular()) <= square(distance);
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
                templateId, lifetimeSeconds, colorRgb, displayName, description, difficulty, entryPolicy, schedule, admission);
    }

    // Return a copy linked directly to a different captured template.
    public PortalDefinition withTemplate(Identifier replacement) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                replacement, lifetimeSeconds, colorRgb, displayName, description, difficulty, entryPolicy, schedule, admission);
    }

    // Return a copy with a changed maximum instance lifetime.
    public PortalDefinition withLifetime(long seconds) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, seconds, colorRgb, displayName, description, difficulty, entryPolicy, schedule, admission);
    }

    // Return a copy with a changed recurring availability schedule.
    public PortalDefinition withSchedule(Schedule replacement) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, lifetimeSeconds, colorRgb, displayName, description, difficulty, entryPolicy, replacement, admission);
    }

    // Return a copy with a different flat client-rendered RGB color.
    public PortalDefinition withColor(int replacementRgb) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, lifetimeSeconds, replacementRgb, displayName, description, difficulty,
                entryPolicy, schedule, admission);
    }

    // Return a copy with administrator-facing confirmation-window presentation text.
    public PortalDefinition withPresentation(String replacementName, String replacementDescription) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, lifetimeSeconds, colorRgb, replacementName, replacementDescription, difficulty,
                entryPolicy, schedule, admission);
    }

    // Return a copy with a different per-player entry limitation policy.
    public PortalDefinition withEntryPolicy(EntryPolicy replacement) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, lifetimeSeconds, colorRgb, displayName, description, difficulty, replacement, schedule, admission);
    }

    // Return a copy with a different one-to-five-skull destination difficulty.
    public PortalDefinition withDifficulty(int replacement) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, lifetimeSeconds, colorRgb, displayName, description, replacement, entryPolicy, schedule, admission);
    }

    public PortalDefinition withAdmission(Admission replacement) {
        return new PortalDefinition(id, dimension, shape, plane, centerX, centerY, centerZ, width, height,
                templateId, lifetimeSeconds, colorRgb, displayName, description, difficulty, entryPolicy,
                schedule, replacement);
    }

    public Environment environment() { return admission.environment(); }

    public PortalDefinition withEnvironment(Environment replacement) {
        return withAdmission(admission.withEnvironment(replacement));
    }

    public boolean allowBuilding() { return admission.allowBuilding(); }

    public boolean keepInventory() { return admission.keepInventory(); }

    public boolean showOtherPlayers() { return admission.showOtherPlayers(); }

    public PortalDefinition withKeepInventory(boolean enabled) { return withAdmission(admission.withKeepInventory(enabled)); }

    public PortalDefinition withShowOtherPlayers(boolean enabled) { return withAdmission(admission.withShowOtherPlayers(enabled)); }

    public boolean baseActive() { return admission.baseActive(); }

    public boolean raidMode() { return admission.raidMode(); }

    public PortalDefinition withRaidMode(boolean enabled) { return withAdmission(admission.withRaidMode(enabled)); }

    public PortalDefinition withBaseActive(boolean active) { return withAdmission(admission.withBaseActive(active)); }

    public PortalDefinition withAllowBuilding(boolean allowed) {
        return withAdmission(admission.withAllowBuilding(allowed));
    }

    public boolean isExit() { return templateId.equals(EXIT_TEMPLATE); }

    public boolean isCoordinates() { return templateId.equals(COORDINATES_TEMPLATE); }

    public Optional<CoordinateTarget> coordinateTarget() { return admission.coordinateTarget(); }

    public PortalDefinition withCoordinateTarget(CoordinateTarget target) {
        return withAdmission(admission.withCoordinateTarget(Optional.of(target)));
    }

    public record CoordinateTarget(double x, double y, double z) {
        private static final Codec<CoordinateTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("x").forGetter(CoordinateTarget::x),
                Codec.DOUBLE.fieldOf("y").forGetter(CoordinateTarget::y),
                Codec.DOUBLE.fieldOf("z").forGetter(CoordinateTarget::z)
        ).apply(instance, CoordinateTarget::new));

        public CoordinateTarget {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Portal destination coordinates must be finite");
            }
        }
    }

    public record Environment(Daytime daytime, boolean dayNightCycle, Weather weather, boolean weatherCycle) {
        private static final Codec<Environment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("daytime", Daytime.NOON.serializedName())
                        .xmap(Daytime::parse, Daytime::serializedName).forGetter(Environment::daytime),
                Codec.BOOL.optionalFieldOf("day_night_cycle", true).forGetter(Environment::dayNightCycle),
                Codec.STRING.optionalFieldOf("weather", Weather.CLEAR.serializedName())
                        .xmap(Weather::parse, Weather::serializedName).forGetter(Environment::weather),
                Codec.BOOL.optionalFieldOf("weather_cycle", true).forGetter(Environment::weatherCycle)
        ).apply(instance, Environment::new));

        public Environment {
            if (daytime == null || weather == null) throw new IllegalArgumentException("Portal environment is incomplete");
        }

        public static Environment defaults() { return new Environment(Daytime.NOON, true, Weather.CLEAR, true); }
    }

    public enum Daytime {
        DAWN(0), MORNING(1000), NOON(6000), DUSK(12000), NIGHT(13000), MIDNIGHT(18000);
        private final int ticks;
        Daytime(int ticks) { this.ticks = ticks; }
        public int ticks() { return ticks; }
        public static Daytime parse(String value) { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        public String serializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Weather {
        CLEAR, RAIN, THUNDER;
        public static Weather parse(String value) { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        public String serializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record Admission(Mode mode, int maxPlayers, boolean allowBuilding, Destination destination,
                            Environment environment, Optional<CoordinateTarget> coordinateTarget, boolean baseActive,
                            boolean raidMode, boolean keepInventory, boolean showOtherPlayers) {
        private static final Codec<Mode> MODE_CODEC = Codec.STRING.xmap(Mode::parse, Mode::serializedName);
        private static final Codec<Destination> DESTINATION_CODEC = Codec.STRING.xmap(
                Destination::parse, Destination::serializedName);
        private static final MapCodec<Admission> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                MODE_CODEC.optionalFieldOf("access_mode", Mode.PUBLIC).forGetter(Admission::mode),
                Codec.INT.optionalFieldOf("max_group_size", 4).forGetter(Admission::maxPlayers),
                Codec.BOOL.optionalFieldOf("allow_building", true).forGetter(Admission::allowBuilding),
                DESTINATION_CODEC.optionalFieldOf("destination_mode", Destination.POCKET)
                        .forGetter(Admission::destination),
                Environment.CODEC.optionalFieldOf("instance_environment", Environment.defaults())
                        .forGetter(Admission::environment),
                CoordinateTarget.CODEC.optionalFieldOf("coordinate_target")
                        .forGetter(Admission::coordinateTarget),
                Codec.BOOL.optionalFieldOf("base_active", true).forGetter(Admission::baseActive),
                Codec.BOOL.optionalFieldOf("raid_mode", false).forGetter(Admission::raidMode),
                Codec.BOOL.optionalFieldOf("keep_inventory", true).forGetter(Admission::keepInventory),
                Codec.BOOL.optionalFieldOf("show_other_players", true).forGetter(Admission::showOtherPlayers)
        ).apply(instance, Admission::new));

        public Admission {
            if (mode == null || destination == null || environment == null || coordinateTarget == null
                    || maxPlayers < 1 || maxPlayers > 100) {
                throw new IllegalArgumentException("Portal group size must be between 1 and 100");
            }
            if (raidMode && mode != Mode.PRIVATE) throw new IllegalArgumentException("Raid mode requires private group access");
        }

        public Admission(Mode mode, int maxPlayers, boolean allowBuilding, Destination destination,
                         Environment environment, Optional<CoordinateTarget> coordinateTarget, boolean baseActive,
                         boolean raidMode, boolean keepInventory) {
            this(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget,
                    baseActive, raidMode, keepInventory, true);
        }

        public Admission(Mode mode, int maxPlayers, boolean allowBuilding, Destination destination,
                         Environment environment) {
            this(mode, maxPlayers, allowBuilding, destination, environment, Optional.empty(), true, false, true);
        }

        public Admission(Mode mode, int maxPlayers, boolean allowBuilding, Destination destination,
                         Environment environment, Optional<CoordinateTarget> coordinateTarget) {
            this(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget, true, false, true);
        }

        public Admission(Mode mode, int maxPlayers, boolean allowBuilding, Destination destination,
                         Environment environment, Optional<CoordinateTarget> coordinateTarget, boolean baseActive) {
            this(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget, baseActive, false, true);
        }

        public Admission(Mode mode, int maxPlayers, boolean allowBuilding, Destination destination,
                         Environment environment, Optional<CoordinateTarget> coordinateTarget,
                         boolean baseActive, boolean raidMode) {
            this(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget,
                    baseActive, raidMode, true);
        }

        public Admission(Mode mode, int maxPlayers, boolean allowBuilding, Destination destination) {
            this(mode, maxPlayers, allowBuilding, destination, Environment.defaults());
        }

        public Admission(Mode mode, int maxPlayers) { this(mode, maxPlayers, true, Destination.POCKET); }

        public Admission withAccess(Mode mode, int maxPlayers) {
            return new Admission(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget, baseActive, raidMode, keepInventory, showOtherPlayers);
        }

        public Admission withAllowBuilding(boolean value) {
            return new Admission(mode, maxPlayers, value, destination, environment, coordinateTarget, baseActive, raidMode, keepInventory, showOtherPlayers);
        }

        public Admission withDestination(Destination value) {
            return new Admission(mode, maxPlayers, allowBuilding, value, environment, coordinateTarget, baseActive, raidMode, keepInventory, showOtherPlayers);
        }

        public Admission withEnvironment(Environment value) {
            return new Admission(mode, maxPlayers, allowBuilding, destination, value, coordinateTarget, baseActive, raidMode, keepInventory, showOtherPlayers);
        }

        public Admission withCoordinateTarget(Optional<CoordinateTarget> value) {
            return new Admission(mode, maxPlayers, allowBuilding, destination, environment, value, baseActive, raidMode, keepInventory, showOtherPlayers);
        }

        public Admission withBaseActive(boolean value) {
            return new Admission(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget, value, raidMode, keepInventory, showOtherPlayers);
        }

        public Admission withRaidMode(boolean value) {
            return new Admission(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget, baseActive, value, keepInventory, showOtherPlayers);
        }

        public Admission withKeepInventory(boolean value) {
            return new Admission(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget,
                    baseActive, raidMode, value, showOtherPlayers);
        }

        public Admission withShowOtherPlayers(boolean value) {
            return new Admission(mode, maxPlayers, allowBuilding, destination, environment, coordinateTarget,
                    baseActive, raidMode, keepInventory, value);
        }

        public static Admission publicPortal() { return new Admission(Mode.PUBLIC, 4); }
    }

    public enum Destination {
        POCKET, RETURN;
        public static Destination parse(String value) { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        public String serializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Mode {
        PUBLIC, PRIVATE;
        public static Mode parse(String value) { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        public String serializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    // Return the configured portal name or a readable fallback derived from its identifier path.
    public String effectiveDisplayName() {
        if (!displayName.isBlank()) return displayName;
        String[] words = id.getPath().replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? id.toString() : result.toString();
    }

    // Combine presentation fields into one codec member so the complete record stays codec-builder compatible.
    private Presentation presentation() {
        return new Presentation(displayName, description, difficulty);
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

    /** Flat persisted presentation fields grouped as one parent-codec member. */
    private record Presentation(String displayName, String description, int difficulty) {
        private static final MapCodec<Presentation> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("display_name", "").forGetter(Presentation::displayName),
                Codec.STRING.optionalFieldOf("description", "").forGetter(Presentation::description),
                Codec.INT.optionalFieldOf("difficulty", 1).forGetter(Presentation::difficulty)
        ).apply(instance, Presentation::new));
    }

    /** Named portal colors exposed by commands and the generic administration panel. */
    public enum PortalColor {
        PURPLE(0x9B59FF), PINK(0xFF5CAA), RED(0xF04444), ORANGE(0xFF8A32), YELLOW(0xFFD84A),
        GREEN(0x4DDB74), CYAN(0x43DDE6), BLUE(0x4F7DFF), WHITE(0xF4F4F4), BLACK(0x101010);

        private final int rgb;

        // Store the exact 24-bit color emitted by this named preset.
        PortalColor(int rgb) {
            this.rgb = rgb;
        }

        // Parse a case-insensitive command or panel color name.
        public static PortalColor parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the 24-bit RGB value persisted on a portal.
        public int rgb() {
            return rgb;
        }

        // Return the stable lowercase command representation.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        // Find the display name of a known preset or fall back to its hexadecimal value.
        public static String displayName(int rgb) {
            for (PortalColor color : values()) {
                if (color.rgb == rgb) return color.name().charAt(0) + color.name().substring(1).toLowerCase(Locale.ROOT);
            }
            return String.format("#%06X", rgb);
        }
    }

    /** Determines whether a player may reuse a portal and when that record is cleared. */
    public enum EntryPolicy {
        REPEATABLE, ONCE_PER_INSTANCE, ONCE_PER_ATTEMPT, ONCE_EVER;

        // Parse a case-insensitive persisted, command, or panel policy name.
        public static EntryPolicy parse(String value) {
            return valueOf(value.trim().replace(' ', '_').toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase persistence and command representation.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Calendar and repeating-window gates applied together to every attempted entry. */
    public record Schedule(Set<DayOfWeek> weekdays, LocalTime start, LocalTime end, ZoneId timezone,
                           long periodSeconds, long openSeconds, long anchorEpochMillis,
                           boolean calendarEnabled) {
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
                Codec.LONG.optionalFieldOf("anchor_epoch_millis", 0L).forGetter(Schedule::anchorEpochMillis),
                Codec.BOOL.optionalFieldOf("calendar_enabled").forGetter(schedule ->
                        java.util.Optional.of(schedule.calendarEnabled()))
        ).apply(instance, (days, start, end, zone, period, open, anchor, enabled) ->
                new Schedule(days, start, end, zone, period, open, anchor,
                        enabled.orElse(!unrestrictedCalendar(days, start, end)))));

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

        public Schedule(Set<DayOfWeek> weekdays, LocalTime start, LocalTime end, ZoneId timezone,
                        long periodSeconds, long openSeconds, long anchorEpochMillis) {
            this(weekdays, start, end, timezone, periodSeconds, openSeconds, anchorEpochMillis,
                    !unrestrictedCalendar(weekdays, start, end));
        }

        private static boolean unrestrictedCalendar(Set<DayOfWeek> days, LocalTime start, LocalTime end) {
            return days.containsAll(Set.of(DayOfWeek.values())) && start.equals(LocalTime.MIDNIGHT)
                    && end.equals(LocalTime.MIDNIGHT);
        }

        // Create an unrestricted schedule with no timer cycle.
        public static Schedule always() {
            return new Schedule(Set.of(DayOfWeek.values()), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT,
                    ZoneId.of("UTC"), 0L, 0L, 0L, false);
        }

        // Test both the calendar gate and repeating timer window at one instant.
        public boolean allows(Instant instant) {
            if (!calendarEnabled && periodSeconds == 0L) return true;
            ZonedDateTime local = instant.atZone(timezone);
            LocalTime time = local.toLocalTime();
            boolean calendar = !calendarEnabled;
            if (calendarEnabled && start.equals(end)) {
                calendar = weekdays.contains(local.getDayOfWeek());
            } else if (calendarEnabled && start.isBefore(end)) {
                calendar = weekdays.contains(local.getDayOfWeek()) && !time.isBefore(start) && time.isBefore(end);
            } else if (calendarEnabled) {
                calendar = weekdays.contains(local.getDayOfWeek()) && !time.isBefore(start)
                        || weekdays.contains(local.minusDays(1L).getDayOfWeek()) && time.isBefore(end);
            }
            if (!calendar || periodSeconds == 0L) return calendar;
            long elapsed = Math.max(0L, instant.toEpochMilli() - anchorEpochMillis);
            return elapsed / 1_000L % periodSeconds < openSeconds;
        }

        // Replace only the calendar gate while retaining the timer cycle.
        public Schedule withCalendar(Set<DayOfWeek> days, LocalTime newStart, LocalTime newEnd, ZoneId zone) {
            return new Schedule(days, newStart, newEnd, zone, periodSeconds, openSeconds, anchorEpochMillis, true);
        }

        // Replace the timer cycle and anchor its first open window at the supplied instant.
        public Schedule withPeriodic(long period, long open, Instant anchor) {
            return new Schedule(weekdays, start, end, timezone, period, open, anchor.toEpochMilli(), calendarEnabled);
        }

        public Schedule withPeriodicValues(long period, long open) {
            return new Schedule(weekdays, start, end, timezone, period, open, anchorEpochMillis, calendarEnabled);
        }

        // Disable the repeating timer while retaining the calendar gate.
        public Schedule withoutPeriodic() {
            return new Schedule(weekdays, start, end, timezone, 0L, 0L, 0L, calendarEnabled);
        }

        // Disable the calendar restriction while retaining any repeating timer.
        public Schedule withoutCalendar() {
            return new Schedule(Set.of(DayOfWeek.values()), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT,
                    ZoneId.of("UTC"), periodSeconds, openSeconds, anchorEpochMillis, false);
        }
    }

    private record Coordinates(double u, double v, double perpendicular) {
    }
}
