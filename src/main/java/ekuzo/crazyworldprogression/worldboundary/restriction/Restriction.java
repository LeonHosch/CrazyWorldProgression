package ekuzo.crazyworldprogression.worldboundary.restriction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Persistent geometric definition and Boundary-level state sequence for one hand-placed restriction. */
public record Restriction(Identifier id, Identifier dimension, Shape shape, double centerX, double centerY,
                          double centerZ, double sizeX, double sizeY, double sizeZ, BoundaryMode boundaryMode,
                          List<RestrictionState> states) {
    private static final Codec<Shape> SHAPE_CODEC = Codec.STRING.xmap(Shape::parse, Shape::serializedName);
    private static final Codec<BoundaryMode> BOUNDARY_MODE_CODEC = Codec.STRING.xmap(
            BoundaryMode::parse, BoundaryMode::serializedName);
    private static final Codec<Collision> COLLISION_CODEC = Codec.STRING.xmap(Collision::parse, Collision::serializedName);
    private static final Codec<Visibility> VISIBILITY_CODEC = Codec.STRING.xmap(Visibility::parse, Visibility::serializedName);
    private static final Codec<BlockInteraction> BLOCK_INTERACTION_CODEC = Codec.STRING.xmap(
            BlockInteraction::parse, BlockInteraction::serializedName);

    public static final Codec<RestrictionState> STATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("minimum_boundary_level").forGetter(RestrictionState::minimumBoundaryLevel),
            COLLISION_CODEC.fieldOf("collision").forGetter(RestrictionState::collision),
            VISIBILITY_CODEC.fieldOf("visibility").forGetter(RestrictionState::visibility),
            BLOCK_INTERACTION_CODEC.optionalFieldOf("block_interaction", BlockInteraction.AUTOMATIC)
                    .forGetter(RestrictionState::blockInteraction)
    ).apply(instance, RestrictionState::new));

    public static final Codec<Restriction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("id").forGetter(Restriction::id),
            Identifier.CODEC.fieldOf("dimension").forGetter(Restriction::dimension),
            SHAPE_CODEC.fieldOf("shape").forGetter(Restriction::shape),
            Codec.DOUBLE.fieldOf("center_x").forGetter(Restriction::centerX),
            Codec.DOUBLE.fieldOf("center_y").forGetter(Restriction::centerY),
            Codec.DOUBLE.fieldOf("center_z").forGetter(Restriction::centerZ),
            Codec.DOUBLE.fieldOf("size_x").forGetter(Restriction::sizeX),
            Codec.DOUBLE.fieldOf("size_y").forGetter(Restriction::sizeY),
            Codec.DOUBLE.fieldOf("size_z").forGetter(Restriction::sizeZ),
            BOUNDARY_MODE_CODEC.optionalFieldOf("boundary_mode", BoundaryMode.KEEP_OUT)
                    .forGetter(Restriction::boundaryMode),
            STATE_CODEC.listOf().fieldOf("states").forGetter(Restriction::states)
    ).apply(instance, Restriction::new));

    // Validate geometry and publish states in ascending threshold order.
    public Restriction {
        if (id == null || dimension == null || shape == null || boundaryMode == null) {
            throw new IllegalArgumentException("Restriction identity and boundary mode must not be null");
        }
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)) {
            throw new IllegalArgumentException("Restriction center must be finite");
        }
        if (!Double.isFinite(sizeX) || !Double.isFinite(sizeY) || !Double.isFinite(sizeZ)
                || sizeX <= 0.0 || sizeY <= 0.0 || sizeZ <= 0.0) {
            throw new IllegalArgumentException("Restriction dimensions must be positive and finite");
        }
        states = states.stream().sorted(Comparator.comparingInt(RestrictionState::minimumBoundaryLevel)).toList();
        if (states.isEmpty() || states.getFirst().minimumBoundaryLevel() != 0) {
            throw new IllegalArgumentException("Restriction states must begin at Advanced World Boundary level 0");
        }
        for (int index = 1; index < states.size(); index++) {
            if (states.get(index - 1).minimumBoundaryLevel() == states.get(index).minimumBoundaryLevel()) {
                throw new IllegalArgumentException("Duplicate restriction state Advanced World Boundary level "
                        + states.get(index).minimumBoundaryLevel());
            }
        }
    }

    // Resolve the highest state threshold reached by the current server Advanced World Boundary level.
    public RestrictionState stateAt(int boundaryLevel) {
        RestrictionState result = states.getFirst();
        for (RestrictionState state : states) {
            if (state.minimumBoundaryLevel() > boundaryLevel) break;
            result = state;
        }
        return result;
    }

    // Return a copy with one threshold inserted or replaced.
    public Restriction withState(RestrictionState replacement) {
        List<RestrictionState> updated = states.stream()
                .filter(state -> state.minimumBoundaryLevel() != replacement.minimumBoundaryLevel()).collect(java.util.stream.Collectors.toList());
        updated.add(replacement);
        return new Restriction(id, dimension, shape, centerX, centerY, centerZ, sizeX, sizeY, sizeZ,
                boundaryMode, updated);
    }

    // Return a copy without one nonzero transition threshold while preserving the required level-zero state.
    public Restriction withoutState(int minimumBoundaryLevel) {
        if (minimumBoundaryLevel == 0) throw new IllegalArgumentException("The Advanced World Boundary level 0 state cannot be removed");
        List<RestrictionState> updated = states.stream()
                .filter(state -> state.minimumBoundaryLevel() != minimumBoundaryLevel).toList();
        if (updated.size() == states.size()) {
            throw new IllegalArgumentException("No restriction state exists at Advanced World Boundary level " + minimumBoundaryLevel);
        }
        return new Restriction(id, dimension, shape, centerX, centerY, centerZ, sizeX, sizeY, sizeZ,
                boundaryMode, updated);
    }

    // Return a copy moved to a new center and optionally a different dimension.
    public Restriction movedTo(Identifier newDimension, Vec3 center) {
        return new Restriction(id, newDimension, shape, center.x, center.y, center.z, sizeX, sizeY, sizeZ,
                boundaryMode, states);
    }

    // Return a copy with the requested one-way boundary direction while preserving geometry and states.
    public Restriction withBoundaryMode(BoundaryMode replacement) {
        return new Restriction(id, dimension, shape, centerX, centerY, centerZ, sizeX, sizeY, sizeZ,
                replacement, states);
    }

    // Compute an axis-aligned broad-phase box enclosing the rendered and protected shape.
    public AABB bounds() {
        double halfX = shape == Shape.SPHERE || shape == Shape.CYLINDER ? sizeX : sizeX / 2.0;
        double halfZ = shape == Shape.SPHERE || shape == Shape.CYLINDER ? sizeX : sizeZ / 2.0;
        double halfY = shape == Shape.SPHERE ? sizeX : sizeY / 2.0;
        return new AABB(centerX - halfX, centerY - halfY, centerZ - halfZ,
                centerX + halfX, centerY + halfY, centerZ + halfZ);
    }

    // Test an entity-center point against the shape expanded by its horizontal and vertical half-size.
    public boolean contains(Vec3 point, double horizontalMargin, double verticalMargin) {
        double x = Math.abs(point.x - centerX);
        double y = Math.abs(point.y - centerY);
        double z = Math.abs(point.z - centerZ);
        return switch (shape) {
            case SPHERE -> {
                double radius = sizeX + Math.max(horizontalMargin, verticalMargin);
                yield x * x + y * y + z * z <= radius * radius;
            }
            case CYLINDER -> x * x + z * z <= square(sizeX + horizontalMargin)
                    && y <= sizeY / 2.0 + verticalMargin;
            case CUBE -> x <= sizeX / 2.0 + horizontalMargin
                    && y <= sizeY / 2.0 + verticalMargin && z <= sizeZ / 2.0 + horizontalMargin;
            case PYRAMID -> {
                double adjustedY = y - verticalMargin;
                if (adjustedY > sizeY / 2.0) yield false;
                double fromBase = Math.max(0.0, point.y - (centerY - sizeY / 2.0));
                double scale = Math.max(0.0, 1.0 - fromBase / sizeY);
                yield x <= sizeX * scale / 2.0 + horizontalMargin
                        && z <= sizeZ * scale / 2.0 + horizontalMargin;
            }
        };
    }

    // Square one shape dimension without repeating multiplication expressions.
    private static double square(double value) {
        return value * value;
    }

    public enum Shape {
        SPHERE, CYLINDER, PYRAMID, CUBE;

        // Parse a command or persisted lowercase shape name.
        public static Shape parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase name written to world data and network snapshots.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Collision {
        SOLID, PASSABLE;

        // Parse a command or persisted lowercase collision mode.
        public static Collision parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase collision mode.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum BoundaryMode {
        KEEP_OUT, KEEP_IN;

        // Parse a command, persisted, or network boundary-direction name.
        public static BoundaryMode parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase name used by commands, world data, and snapshots.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Visibility {
        OPAQUE, TRANSLUCENT, HIDDEN;

        // Parse a command or persisted lowercase visibility mode.
        public static Visibility parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase visibility mode.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum BlockInteraction {
        AUTOMATIC, PROTECTED, ALLOWED;

        // Parse a command, persisted, or network block-interaction rule.
        public static BlockInteraction parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        // Return the stable lowercase state name.
        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record RestrictionState(int minimumBoundaryLevel, Collision collision, Visibility visibility,
                               BlockInteraction blockInteraction) {
        // Reject state thresholds that can never match the non-negative Advanced World Boundary level.
        public RestrictionState {
            if (minimumBoundaryLevel < 0 || collision == null || visibility == null || blockInteraction == null) {
                throw new IllegalArgumentException("Invalid restriction state");
            }
        }

        // Resolve legacy automatic behavior or an explicit protection choice for this Boundary-level state.
        public boolean protectsBlocks() {
            return blockInteraction == BlockInteraction.PROTECTED
                    || blockInteraction == BlockInteraction.AUTOMATIC && collision == Collision.SOLID;
        }
    }
}
