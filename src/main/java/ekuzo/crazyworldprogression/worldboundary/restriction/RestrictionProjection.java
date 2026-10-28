package ekuzo.crazyworldprogression.worldboundary.restriction;

import ekuzo.crazyworldprogression.portals.PortalService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Translates a saved pocket-editor restriction into one leased runtime dimension. */
final class RestrictionProjection {
    private RestrictionProjection() {
    }

    static Restriction intoInstance(Restriction source, PortalService.ActiveInstance instance,
                                    BlockPos captureOrigin) {
        BlockPos delta = instance.origin().subtract(captureOrigin);
        UUID projectionId = UUID.nameUUIDFromBytes((source.id() + "@" + instance.instanceId())
                .getBytes(StandardCharsets.UTF_8));
        Identifier id = Identifier.fromNamespaceAndPath("crazy-world-progression", "projected_restriction/" + projectionId);
        return new Restriction(id, instance.slotDimension(), source.shape(),
                source.centerX() + delta.getX(), source.centerY() + delta.getY(),
                source.centerZ() + delta.getZ(), source.sizeX(), source.sizeY(), source.sizeZ(),
                source.boundaryMode(), source.states());
    }
}
