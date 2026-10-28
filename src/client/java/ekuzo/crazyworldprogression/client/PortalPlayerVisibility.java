package ekuzo.crazyworldprogression.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.portals.PortalNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

/** Local rendering preference for public instances configured to hide other players. */
public final class PortalPlayerVisibility {
    private static final Gson GSON = new Gson();
    private static final Type DIMENSIONS_TYPE = new TypeToken<List<String>>() { }.getType();
    private static volatile Set<String> hiddenDimensions = Set.of();

    private PortalPlayerVisibility() { }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(PortalNetworking.PortalVisibilityPayload.TYPE,
                (payload, context) -> {
                    List<String> dimensions = GSON.fromJson(payload.json(), DIMENSIONS_TYPE);
                    hiddenDimensions = dimensions == null ? Set.of() : Set.copyOf(dimensions);
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> hiddenDimensions = Set.of());
    }

    public static boolean hide(Player other) {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && other != client.player
                && !CwpClientSettings.showPlayersInSemiPrivateInstances
                && hiddenDimensions.contains(client.player.level().dimension().identifier().toString());
    }
}
