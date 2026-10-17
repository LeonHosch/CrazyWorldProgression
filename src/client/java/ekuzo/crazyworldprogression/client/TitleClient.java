package ekuzo.crazyworldprogression.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.titles.TitleNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client-only snapshot used by name-tag and tab renderers. */
public final class TitleClient {
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<TitleNetworking.Display>>() { }.getType();
    private static volatile Map<UUID, TitleNetworking.Display> displays = Map.of();
    private TitleClient() { }
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(TitleNetworking.Payload.TYPE, (payload, context) -> {
            List<TitleNetworking.Display> entries = GSON.fromJson(payload.json(), LIST_TYPE);
            Map<UUID, TitleNetworking.Display> updated = new HashMap<>();
            if (entries != null) for (TitleNetworking.Display entry : entries) {
                try { updated.put(UUID.fromString(entry.playerId()), entry); }
                catch (IllegalArgumentException ignored) { }
            }
            displays = Map.copyOf(updated);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> displays = Map.of());
    }
    public static TitleNetworking.Display get(UUID playerId) { return displays.get(playerId); }
    public static int color(TitleNetworking.Display display) {
        try { return Integer.parseInt(display.color().substring(1), 16); }
        catch (RuntimeException exception) { return 0xFFFFFF; }
    }
}
