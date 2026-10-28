package ekuzo.crazyworldprogression.worldboundary;

import com.google.gson.Gson;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Synchronizes only the configured radii for the current global Advanced World Boundary level. */
public final class BoundaryRadiusNetworking {
    private static final Gson GSON = new Gson();

    private BoundaryRadiusNetworking() { }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(RadiusSnapshotPayload.TYPE, RadiusSnapshotPayload.CODEC);
    }

    public static void send(ServerPlayer player) {
        ServerPlayNetworking.send(player, new RadiusSnapshotPayload(GSON.toJson(
                AdvancedWorldBoundary.currentRadii(player.level().getServer()))));
    }

    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }

    public static Map<String, AdvancedWorldBoundary.Radii> decode(String json) {
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, AdvancedWorldBoundary.Radii>>() { }.getType();
        Map<String, AdvancedWorldBoundary.Radii> values = GSON.fromJson(json, type);
        return values == null ? Map.of() : Map.copyOf(values);
    }

    public record RadiusSnapshotPayload(String json) implements CustomPacketPayload {
        public static final Type<RadiusSnapshotPayload> TYPE = new Type<>(CrazyWorldProgression.id("advanced_world_boundary_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RadiusSnapshotPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), RadiusSnapshotPayload::json, RadiusSnapshotPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
