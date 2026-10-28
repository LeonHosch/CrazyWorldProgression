package ekuzo.crazyworldprogression.worldboundary;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

import java.util.Map;

/** Client cache of server-authoritative radius settings for the current level. */
public final class ClientBoundaryRadii {
    private static volatile Map<String, AdvancedWorldBoundary.Radii> current = Map.of();

    private ClientBoundaryRadii() { }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(BoundaryRadiusNetworking.RadiusSnapshotPayload.TYPE,
                (payload, context) -> current = BoundaryRadiusNetworking.decode(payload.json()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> current = Map.of());
    }

    public static AdvancedWorldBoundary.Radii get(Identifier dimension) {
        return current.get(dimension.toString());
    }
}
