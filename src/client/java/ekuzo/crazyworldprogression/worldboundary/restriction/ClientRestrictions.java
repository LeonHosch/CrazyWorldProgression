package ekuzo.crazyworldprogression.worldboundary.restriction;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static ekuzo.crazyworldprogression.worldboundary.restriction.Restriction.Collision.SOLID;
import static ekuzo.crazyworldprogression.worldboundary.restriction.Restriction.BoundaryMode.KEEP_OUT;
import static ekuzo.crazyworldprogression.worldboundary.restriction.Restriction.Visibility.HIDDEN;

/** Client snapshot cache split into visible geometry and block-protection prediction indexes. */
public final class ClientRestrictions {
    private static final double SOLID_BLACKOUT_SAFETY_MARGIN = 0.75;
    private static volatile int boundaryLevel;
    private static volatile List<VisibleRestriction> visible = List.of();
    private static volatile RestrictionSpatialIndex interactionIndex = new RestrictionSpatialIndex();

    // Prevent instantiation of the client restriction cache.
    private ClientRestrictions() {
    }

    // Register authoritative snapshot reception and clear world-specific data on disconnect.
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(RestrictionNetworking.RestrictionSnapshotPayload.TYPE,
                (payload, context) -> apply(RestrictionNetworking.decode(payload.json())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                player instanceof LocalPlayer localPlayer && protectsInteraction(localPlayer, pos)
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(player instanceof LocalPlayer localPlayer)) return InteractionResult.PASS;
            BlockPos hit = hitResult.getBlockPos();
            return protectsInteraction(localPlayer, hit)
                    || protectsInteraction(localPlayer, hit.relative(hitResult.getDirection()))
                    ? InteractionResult.FAIL : InteractionResult.PASS;
        });
    }

    // Return resolved visible restrictions without exposing mutable client collections.
    public static List<VisibleRestriction> visibleRestrictions() {
        return visible;
    }

    // Predict server-side block protection so mining cracks and ghost placements are suppressed immediately.
    public static boolean protectsInteraction(LocalPlayer player, BlockPos position) {
        return RestrictionCollision.protectsBlock(player.level().dimension().identifier(), position,
                interactionIndex);
    }

    // Resolve the strongest camera effect, blacking out slightly before the view plane crosses a solid surface.
    public static RestrictionVision visionAt(Identifier dimension, Vec3 point) {
        RestrictionVision result = RestrictionVision.NONE;
        for (VisibleRestriction visibleRestriction : visible) {
            Restriction restriction = visibleRestriction.restriction();
            if (!restriction.dimension().equals(dimension)) continue;
            if (visibleRestriction.collision() == SOLID && restriction.boundaryMode() == KEEP_OUT
                    && restriction.contains(point, SOLID_BLACKOUT_SAFETY_MARGIN, SOLID_BLACKOUT_SAFETY_MARGIN)) {
                return RestrictionVision.BLACKOUT;
            }
            if (visibleRestriction.collision() != SOLID && visibleRestriction.visibility() == Restriction.Visibility.OPAQUE
                    && restriction.contains(point, 0.0, 0.0)) result = RestrictionVision.VEIL;
        }
        return result;
    }

    // Resolve all states once per snapshot and independently index explicit block-protection states.
    private static void apply(RestrictionNetworking.Snapshot snapshot) {
        RestrictionSpatialIndex nextInteraction = new RestrictionSpatialIndex();
        List<VisibleRestriction> nextVisible = new ArrayList<>();
        for (Restriction restriction : snapshot.restrictions()) {
            Restriction.RestrictionState state = restriction.stateAt(snapshot.boundaryLevel());
            if (state.protectsBlocks()) nextInteraction.add(restriction);
            if (state.visibility() != HIDDEN || state.collision() == SOLID) {
                nextVisible.add(new VisibleRestriction(restriction, state.collision(), state.visibility()));
            }
        }
        boundaryLevel = snapshot.boundaryLevel();
        interactionIndex = nextInteraction;
        visible = List.copyOf(nextVisible);
    }

    // Drop stale server geometry when leaving a world.
    private static void clear() {
        boundaryLevel = 0;
        visible = List.of();
        interactionIndex = new RestrictionSpatialIndex();
    }

    public record VisibleRestriction(Restriction restriction, Restriction.Collision collision,
                                 Restriction.Visibility visibility) {
    }

    public enum RestrictionVision {
        NONE,
        VEIL,
        BLACKOUT
    }
}
