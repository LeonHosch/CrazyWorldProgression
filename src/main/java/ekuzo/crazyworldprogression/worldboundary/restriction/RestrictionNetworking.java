package ekuzo.crazyworldprogression.worldboundary.restriction;

import com.google.gson.Gson;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Clientbound synchronization for authoritative restriction geometry and resolved Advanced World Boundary level state. */
public final class RestrictionNetworking {
    private static final Gson GSON = new Gson();

    // Prevent instantiation of the network bridge.
    private RestrictionNetworking() {
    }

    // Register the restriction snapshot payload during common mod initialization.
    public static void registerPayloadType() {
        PayloadTypeRegistry.clientboundPlay().register(RestrictionSnapshotPayload.TYPE, RestrictionSnapshotPayload.CODEC);
    }

    // Send one complete authoritative snapshot to a connected player.
    static void send(ServerPlayer player, int boundaryLevel, List<Restriction> restrictions) {
        WireSnapshot snapshot = new WireSnapshot(boundaryLevel, restrictions.stream().map(WireRestriction::from).toList());
        ServerPlayNetworking.send(player, new RestrictionSnapshotPayload(GSON.toJson(snapshot)));
    }

    // Decode a received snapshot for client rendering and prediction.
    public static Snapshot decode(String json) {
        WireSnapshot snapshot = GSON.fromJson(json, WireSnapshot.class);
        return new Snapshot(snapshot.boundaryLevel(), snapshot.restrictions().stream().map(WireRestriction::toRestriction).toList());
    }

    public record Snapshot(int boundaryLevel, List<Restriction> restrictions) {
        // Freeze decoded restriction lists before the client indexes them.
        public Snapshot {
            restrictions = List.copyOf(restrictions);
        }
    }

    private record WireSnapshot(int boundaryLevel, List<WireRestriction> restrictions) {
    }

    private record WireRestriction(String id, String dimension, String shape, String boundaryMode,
                               double centerX, double centerY,
                               double centerZ, double sizeX, double sizeY, double sizeZ,
                               List<WireState> states) {
        // Convert validated server geometry into a primitive JSON transport representation.
        private static WireRestriction from(Restriction restriction) {
            return new WireRestriction(restriction.id().toString(), restriction.dimension().toString(),
                    restriction.shape().serializedName(), restriction.boundaryMode().serializedName(),
                    restriction.centerX(), restriction.centerY(), restriction.centerZ(),
                    restriction.sizeX(), restriction.sizeY(), restriction.sizeZ(),
                    restriction.states().stream().map(WireState::from).toList());
        }

        // Reconstruct and validate one common restriction model from clientbound primitive fields.
        private Restriction toRestriction() {
            return new Restriction(net.minecraft.resources.Identifier.parse(id),
                    net.minecraft.resources.Identifier.parse(dimension), Restriction.Shape.parse(shape),
                    centerX, centerY, centerZ, sizeX, sizeY, sizeZ,
                    boundaryMode == null ? Restriction.BoundaryMode.KEEP_OUT
                            : Restriction.BoundaryMode.parse(boundaryMode),
                    states.stream().map(WireState::toState).toList());
        }
    }

    private record WireState(int minimumBoundaryLevel, String collision, String visibility, String blockInteraction) {
        // Convert one resolved-threshold definition into stable lowercase wire names.
        private static WireState from(Restriction.RestrictionState state) {
            return new WireState(state.minimumBoundaryLevel(), state.collision().serializedName(),
                    state.visibility().serializedName(), state.blockInteraction().serializedName());
        }

        // Reconstruct one validated level state from clientbound primitive fields.
        private Restriction.RestrictionState toState() {
            return new Restriction.RestrictionState(minimumBoundaryLevel, Restriction.Collision.parse(collision),
                    Restriction.Visibility.parse(visibility), blockInteraction == null
                    ? Restriction.BlockInteraction.AUTOMATIC
                    : Restriction.BlockInteraction.parse(blockInteraction));
        }
    }

    public record RestrictionSnapshotPayload(String json) implements CustomPacketPayload {
        public static final Type<RestrictionSnapshotPayload> TYPE = new Type<>(CrazyWorldProgression.id("restriction_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RestrictionSnapshotPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1_048_576), RestrictionSnapshotPayload::json, RestrictionSnapshotPayload::new);

        // Return the payload type registered by EchelonCore.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
