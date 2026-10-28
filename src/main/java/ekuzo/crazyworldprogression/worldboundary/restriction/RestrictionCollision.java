package ekuzo.crazyworldprogression.worldboundary.restriction;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared geometry checks used for matching client and server block-interaction protection. */
public final class RestrictionCollision {
    private static final double INTERACTION_GUARD_DISTANCE = 1.0;

    // Prevent instantiation of the geometry collision utility.
    private RestrictionCollision() {
    }

    // Test the targeted block and a one-block build buffer against currently protected restriction shapes.
    static boolean protectsBlock(Identifier dimension, BlockPos position,
                                 RestrictionSpatialIndex interactionIndex) {
        AABB guardedBlock = new AABB(position).inflate(INTERACTION_GUARD_DISTANCE);
        Vec3 blockCenter = Vec3.atCenterOf(position);
        for (Restriction restriction : interactionIndex.query(dimension, guardedBlock)) {
            if (restriction.contains(blockCenter, 1.5, 1.5)) return true;
        }
        return false;
    }
}
