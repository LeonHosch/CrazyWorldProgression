package ekuzo.crazyworldprogression.titles;

import com.google.gson.Gson;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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

public final class TitleNetworking {
    private static final Gson GSON = new Gson();
    private TitleNetworking() { }
    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> broadcast(server));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
            if (success) server.execute(() -> broadcast(server));
        });
    }
    public static void broadcast(MinecraftServer server) {
        List<Display> titles = new ArrayList<>();
        var definitions = TitleService.definitions(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            TitleService.Definition definition = TitleService.equipped(player, definitions);
            if (definition != null) titles.add(new Display(player.getUUID().toString(), definition.name(),
                    definition.color()));
        }
        Payload payload = new Payload(GSON.toJson(titles));
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (ServerPlayNetworking.canSend(player, Payload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
    public record Display(String playerId, String title, String color) { }
    public record Payload(String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("title_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(65_536), Payload::json, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
