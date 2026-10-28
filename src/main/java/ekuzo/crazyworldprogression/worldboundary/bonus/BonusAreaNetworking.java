package ekuzo.crazyworldprogression.worldboundary.bonus;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tells clients where bonus areas are (for maps such as the optional JourneyMap integration). Sent once when a
 * player joins and again only when an area changes or the level moves the spawn area's radius.
 */
public final class BonusAreaNetworking {
    private static final Gson GSON = new Gson();
    private static final Map<MinecraftServer, Double> LAST_SPAWN_RADIUS = new WeakHashMap<>();

    /** One bonus area as the client sees it; the spawn area has an empty {@code id}. */
    public record Zone(String id, String name, String dimension, double x, double z, double radius) { }

    public record Payload(String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("bonus_area_zones"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), Payload::json, Payload::new);
        @Override public Type<Payload> type() { return TYPE; }
    }

    private BonusAreaNetworking() { }

    static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.getPlayer()));
    }

    /** Every 5 seconds: resend when the bonus area level changed the spawn area's radius. */
    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 0) return;
        double radius = BonusAreas.spawnRadius(server);
        Double before = LAST_SPAWN_RADIUS.put(server, radius);
        if (before != null && before != radius) broadcast(server);
    }

    public static void broadcast(MinecraftServer server) {
        String json = GSON.toJson(zones(server));
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (ServerPlayNetworking.canSend(player, Payload.TYPE)) ServerPlayNetworking.send(player, new Payload(json));
    }

    private static void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, Payload.TYPE))
            ServerPlayNetworking.send(player, new Payload(GSON.toJson(zones(player.level().getServer()))));
    }

    static List<Zone> zones(MinecraftServer server) {
        List<Zone> zones = new ArrayList<>();
        double radius = BonusAreas.spawnRadius(server);
        if (radius > 0) {
            var spawn = server.getRespawnData();
            zones.add(new Zone("", BonusAreas.displayName(), spawn.dimension().identifier().toString(),
                    spawn.pos().getX() + 0.5, spawn.pos().getZ() + 0.5, radius));
        }
        BonusAreas.spots(server).forEach((id, spot) -> zones.add(new Zone(id, title(id), spot.dimension(), spot.x(), spot.z(),
                spot.radius())));
        return zones;
    }

    /** "north_gate" becomes "North Gate". */
    private static String title(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.split("[_-]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public static List<Zone> decode(String json) {
        List<Zone> zones = GSON.fromJson(json, new TypeToken<List<Zone>>() { }.getType());
        return zones == null ? List.of() : List.copyOf(zones);
    }
}
